package com.chengyinhub.business.task;

import com.chengyinhub.common.config.RestoreDispatchGuard;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.WechatOrderNotExistException;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.common.constant.EntitlementInvalidReason;
import com.chengyinhub.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Date;
import java.util.Map;

/**
 * 过期未支付订单自动关单定时任务(P0-10 弃单释放库存)。
 *
 * 在 RuoYi「定时任务管理」(sys_job)新增一条:invokeTarget = orderCloseTask.closeExpired(),
 * cron 建议每 5 分钟。逐单经 Service 代理处理(per-单独立事务,单笔失败不影响其它);
 * 关单为条件 UPDATE(仍待支付才关),与支付回调竞态时 0 行跳过,不会关掉刚支付成功的单。
 */
@Component("orderCloseTask")
public class OrderCloseTask {

    private static final Logger log = LoggerFactory.getLogger(OrderCloseTask.class);

    /** 关单回补写进 update_by / points_returned 的来源标识。 */
    private static final String RECONCILE_UPDATE_BY = "payment-reconcile-task";

    /**
     * 单轮对账最多处理多少单(清单 6)。每单要向微信查一次单,积压 N 单就是 N 次外部调用;
     * 无上限时一轮可能拖过 cron 周期,concurrent=1 只会让下一轮顺延排队、越积越长。
     */
    private static final int RECONCILE_SCAN_LIMIT = 500;

    /**
     * 上一轮扫到哪个 id。触到上限就停在这儿,下一轮从这儿接着扫,不会每轮都从头啃同一批。
     * 扫到尾(翻到空页)归零,下一轮重新从头扫,新过期的小 id 单才不会被永远跳过。
     *
     * <p>ponytail:只活在进程内(Quartz 与本 bean 同 JVM 单例)。重启归零 = 从头扫一遍,
     * 与今天的行为相同、不丢单;要跨重启接着扫再落库(sys_config 或专表)。
     */
    private volatile long reconcileCursor = 0L;
    private long unpaidCursor = 0L;

    @Autowired
    private ICmsRegistrationService cmsRegistrationService;

    @Autowired
    private IWechatPayService wechatPayService;

    @Autowired
    private AlarmService alarmService;

    /** 扫描 pay_expire_time 已过仍待支付的报名单:关单 + 回补库存 + 退还预扣积分 */
    public synchronized void closeExpired() {
        Date now = new Date();
        Long maxId = cmsRegistrationService.getExpiredUnpaidScanUpperBound(now);
        if (maxId == null) { unpaidCursor = 0L; return; }
        int scanned = 0;
        int closed = 0;
        boolean reachedEnd = false;
        while (scanned < RECONCILE_SCAN_LIMIT) {
            List<Long> ids = cmsRegistrationService.listExpiredUnpaidIdsAfter(
                    now, unpaidCursor, maxId, Math.min(200, RECONCILE_SCAN_LIMIT - scanned));
            if (ids.isEmpty()) { reachedEnd = true; break; }
            for (Long id : ids) {
                scanned++;
                try {
                    if (cmsRegistrationService.closeExpiredOne(id)) closed++;
                } catch (RestoreDispatchGuard.Blocked paused) {
                    log.info("过期关单暂停 reason=RECOVERY_DISPATCH_BLOCKED");
                } catch (Exception e) {
                    log.error("过期关单失败 registrationId={}", id, e);
                } finally {
                    unpaidCursor = id;
                }
            }
            if (unpaidCursor >= maxId) { reachedEnd = true; break; }
        }
        if (reachedEnd) unpaidCursor = 0L;
        log.info("过期关单完成:扫描 {} 单,关闭 {} 单,下轮起点 id={}", scanned, closed, unpaidCursor);
    }

    /**
     * §5-3 PAYING 超时对账:预下单已发起(payment_status=1)又过了支付窗口的单,既不能被用户再支付
     * (/pay 拒过期),也不会被 {@link #closeExpired} 扫到(只扫 payment_status=0)——
     * 只查本地状态永远不知道钱到底付没付,必须逐个问微信支付真实态(复用 queryOrderStatus):
     *
     * <ul>
     *   <li><b>SUCCESS</b> → 按支付回调同一条链路补记(handleWechatPayCallback),绝不关单回补;</li>
     *   <li><b>NOTPAY / USERPAYING / PAYERROR</b> → 先关闭微信订单把状态钉成终态(否则查单后用户仍可能支付成功),
     *       微信确认关单后再本地关单 + 回补库存/积分/权益;</li>
     *   <li><b>CLOSED（原尝试仍是当前尝试）</b> → 微信已是终态,直接本地关单 + 回补;</li>
     *   <li><b>ORDER_NOT_EXIST</b> → 保留原号继续查证，不凭时间回补;</li>
     *   <li><b>查单失败 / 单号对不上 / 未知态</b> → 保持不动并告警,不猜结果,下轮再查。</li>
     * </ul>
     *
     * <p>单轮最多处理 {@value #RECONCILE_SCAN_LIMIT} 单,停在哪儿下一轮从哪儿接着扫(见 {@link #reconcileCursor})。
     *
     * <p>在 RuoYi「定时任务管理」(sys_job)注册:invokeTarget = orderCloseTask.reconcileExpiredPaying(),
     * cron 建议每 10 分钟(见 migration_registration_paying_reconcile_job_20260915.sql)。
     */
    public void reconcileExpiredPaying() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("支付对账必须在原业务事务外执行");
        }
        if (wechatPayService == null || !wechatPayService.isReady()) {
            log.warn("微信支付能力未就绪,跳过支付中过期订单对账");
            return;
        }
        int scanned = 0;
        int applied = 0;
        int closed = 0;
        int uncertain = 0;
        String firstFailure = null;
        long afterId = reconcileCursor;
        boolean reachedEnd = false;
        // 游标翻页:卡单(查单一直失败)留在原地,但不再占住固定的前 200 条把后面的单饿死;
        // 单轮扫到上限就停,游标留给下一轮接着扫。
        while (scanned < RECONCILE_SCAN_LIMIT) {
            List<Long> ids = cmsRegistrationService.listExpiredPayingIds(afterId);
            if (ids.isEmpty()) {
                reachedEnd = true;
                break;
            }
            for (Long id : ids) {
                if (scanned >= RECONCILE_SCAN_LIMIT) {
                    break;
                }
                afterId = id;
                scanned++;
                try {
                    CmsRegistration registration = cmsRegistrationService.selectCmsRegistrationById(id);
                    if (registration == null
                            || !Integer.valueOf(1).equals(registration.getPaymentStatus())
                            || !Integer.valueOf(1).equals(registration.getRegistrationStatus())) {
                        continue; // 扫描后已被回调/取消处理,无需对账
                    }
                    if (StringUtils.isBlank(registration.getRegistrationNo())) {
                        uncertain++;
                        if (firstFailure == null) {
                            firstFailure = "registrationId=" + id + " 业务单号缺失";
                        }
                        continue;
                    }
                    Map<String, String> wxOrder;
                    try {
                        wxOrder = wechatPayService.queryOrderStatus(registration.getRegistrationNo());
                    } catch (WechatOrderNotExistException notExist) {
                        // NOT_FOUND and elapsed time do not prove an earlier submission was never accepted.
                        uncertain++;
                        if (firstFailure == null) firstFailure = "registrationId=" + id + " 原支付号待查证";
                        continue;
                    } catch (Exception queryFailure) {
                        uncertain++;
                        if (firstFailure == null) {
                            firstFailure = "registrationId=" + id + " 查单失败:" + queryFailure.getMessage();
                        }
                        log.error("支付中过期订单查单失败 registrationId={}", id, queryFailure);
                        continue;
                    }
                    String tradeState = wxOrder == null ? null : wxOrder.get("trade_state");
                    if (StringUtils.isBlank(tradeState)
                            || !registration.getRegistrationNo().equals(wxOrder.get("out_trade_no"))) {
                        uncertain++;
                        if (firstFailure == null) {
                            firstFailure = "registrationId=" + id + " 查单结果不可确认";
                        }
                        log.error("支付中过期订单查单结果不可确认 registrationId={} tradeState={}", id, tradeState);
                        continue;
                    }
                    if ("SUCCESS".equalsIgnoreCase(tradeState)) {
                        // 钱真的到了:走支付回调同一条链路补记,不能关单回补。
                        String result = cmsRegistrationService.handleWechatPayCallback(wxOrder);
                        // The original verified callback/inbox is retained above. A paused business
                        // consumer is not a provider failure, and must not be counted as applied.
                        if (RestoreDispatchGuard.isRestoreMode()) continue;
                        if ("SUCCESS".equalsIgnoreCase(result)) {
                            applied++;
                        } else {
                            uncertain++;
                            if (firstFailure == null) {
                                firstFailure = "registrationId=" + id + " 已支付但补记失败:" + result;
                            }
                            log.error("支付中过期订单已支付但补记失败 registrationId={} result={}", id, result);
                        }
                        continue;
                    }
                    // Keep querying original attempts, but neither close externally nor release
                    // points/inventory locally from a restored database.
                    if (RestoreDispatchGuard.isRestoreMode()) continue;
                    if ("NOTPAY".equalsIgnoreCase(tradeState) || "USERPAYING".equalsIgnoreCase(tradeState)
                            || "PAYERROR".equalsIgnoreCase(tradeState)) {
                        // 未支付不是终态(PAYERROR 能否再付未核实,按裁决保守同处理):先把微信订单关掉,关单被微信接受才可能安全本地关单。
                        boolean closeAccepted;
                        try {
                            closeAccepted = wechatPayService.closePaymentAttempt(registration.getRegistrationNo(), wxOrder.get("wx_out_trade_no"));
                        } catch (RestoreDispatchGuard.Blocked paused) { throw paused; }
                        catch (Exception closeFailure) {
                            closeAccepted = false;
                            log.error("支付中过期订单关微信单失败 registrationId={}", id, closeFailure);
                        }
                        if (!closeAccepted) {
                            uncertain++;
                            if (firstFailure == null) {
                                firstFailure = "registrationId=" + id + " 微信关单未确认(" + tradeState + ")";
                            }
                            continue;
                        }
                    } else if (!"CLOSED".equalsIgnoreCase(tradeState)) {
                        uncertain++;
                        if (firstFailure == null) {
                            firstFailure = "registrationId=" + id + " 未知支付状态:" + tradeState;
                        }
                        log.error("支付中过期订单支付状态无法确认 registrationId={} tradeState={}", id, tradeState);
                        continue;
                    }
                    if (closeObservedRegistration(registration, wxOrder.get("wx_out_trade_no"))) {
                        closed++;
                    }
                } catch (RestoreDispatchGuard.Blocked paused) {
                    log.info("支付派发与本地关单暂停 reason=RECOVERY_DISPATCH_BLOCKED");
                } catch (Exception e) {
                    uncertain++;
                    if (firstFailure == null) {
                        firstFailure = "registrationId=" + id + " " + e.getMessage();
                    }
                    log.error("支付中过期订单对账失败 registrationId={}", id, e);
                } finally {
                    // 含 continue 分支的已尝试行都保留进度，后页查询异常不重扫整个前缀。
                    reconcileCursor = afterId;
                }
            }
        }
        // 扫到尾就归零(下一轮重新从头,接住新过期的小 id 单);触上限就停在这儿,下一轮接着扫。
        reconcileCursor = reachedEnd ? 0L : afterId;
        if (scanned == 0) {
            return;
        }
        log.info("支付中过期订单对账完成:扫描 {} 单,已支付补记 {} 单,确认未支付关单 {} 单,未确认 {} 单,下轮起点 id={}",
                scanned, applied, closed, uncertain, reconcileCursor);
        if (uncertain > 0) {
            try {
                alarmService.alarm("支付中过期订单对账失败",
                        "共 " + uncertain + " 单未确认支付状态(保持不动,未关单);示例:" + firstFailure
                                + ";请人工核对微信商户平台后再处理");
            } catch (Exception alarmFailure) {
                log.error("支付中过期订单对账告警发送失败", alarmFailure);
            }
        }
    }
    private boolean closeObservedRegistration(CmsRegistration registration, String observedWxNo) {
        return cmsRegistrationService.settleClosedPayingOne(registration, observedWxNo,
                EntitlementInvalidReason.ORGANIZER_CANCEL, RECONCILE_UPDATE_BY, 4);
    }

}
