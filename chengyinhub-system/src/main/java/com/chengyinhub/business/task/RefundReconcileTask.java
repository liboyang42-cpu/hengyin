package com.chengyinhub.business.task;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.IRefundPayoutService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.impl.AlarmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * §6.1 退款对账兜底(P3 回调的纵深防御)。
 *
 * 本地退款提交后先为 DISPATCH_PENDING(payout_status=5),由 afterCommit 触发微信；微信退款
 * 受理后为 PROCESSING(0),最终态靠异步回调回写。afterCommit 与回调都天然可能有损
 * (未配回调地址 / 部署窗口宕机 / 验签解析异常 / 微信重试放弃)。本任务主动拉取微信真实态,
 * ：本任务对卡在 5 的单以确定性退款号重派，对卡在 0 的单拉取微信真实态，收敛到 1(成功)
 * 或 2(失败进人工队列)，消除“提交后未派发”与“受理后异步失败”两类静默漏退。
 *
 * 在 RuoYi「定时任务管理」(sys_job)配一条:invokeTarget = refundReconcileTask.reconcile(),
 * cron 建议每 10~15 分钟(seed_refund_manual_menu_20260714.sql 已附幂等 seed)。
 */
@Component("refundReconcileTask")
public class RefundReconcileTask {

    private static final Logger log = LoggerFactory.getLogger(RefundReconcileTask.class);

    /** 超过此分钟数才重派/拉取,给正常 afterCommit 与异步回调留出窗口。 */
    private static final int STALE_MINUTES = 30;

    @Autowired
    private RefundApplicationMapper refundApplicationMapper;

    @Autowired
    private IWechatPayService wechatPayService;

    @Autowired
    private IRefundPayoutService refundPayoutService;

    @Autowired
    private AlarmService alarmService;

    @Autowired
    private com.chengyinhub.business.service.ICoopInviteService coopInviteService;

    private long scanCursor;

    public synchronized void reconcile() {
        // 保证金与报名退款队列隔离；双方仍复用同一已注册调度入口。
        IllegalStateException depositFailure = null;
        try { coopInviteService.reconcileDepositRefunds(); }
        catch (Exception e) {
            log.error("保证金退款对账失败", e);
            depositFailure = new IllegalStateException("保证金退款对账失败，报名退款已继续处理", e);
        }

        // [H-B] 卡滞计数必须先于重试:下面的补派会经 claim/release/markPayoutResult 把 update_time
        // 刷成 now(),重试后再用同一个 stale 谓词计数必然数到 0 —— 反复失败的单每轮都被"洗新",
        // "退款派发卡滞"告警对注册类退款近似恒哑。先记下扫描时点已卡滞 ≥STALE_MINUTES 的数量,
        // 重试完(无论成败)按这个数告警;本轮真修好的下一轮自然归 0。
        Integer stuckAtScan = null;
        String stuckIdsAtScan = "UNKNOWN";
        try {
            stuckAtScan = refundApplicationMapper.countStuckPayoutDispatch(STALE_MINUTES);
            if (stuckAtScan != null && stuckAtScan > 0) {
                stuckIdsAtScan = sampleIds(() -> refundApplicationMapper.sampleStuckPayoutDispatchIds(STALE_MINUTES));
            }
        } catch (Exception e) {
            log.error("退款派发卡滞计数失败，无法确认卡滞退款数量", e);
            try {
                alarmService.alarmThrottled("monitor.refund.dispatch_count_failed", "退款派发卡滞计数失败",
                        "退款派发卡滞计数异常，请排查数据访问和告警服务");
            } catch (Exception alarmException) {
                log.error("退款派发卡滞计数异常告警发送失败", alarmException);
            }
        }
        Long upperBound = refundApplicationMapper.selectStalePayoutScanUpperBound(STALE_MINUTES);
        long afterId = scanCursor;
        if (upperBound == null || afterId >= upperBound) {
            scanCursor = 0L;
            afterId = 0L;
        }
        int scanned = 0;
        int done = 0;
        while (upperBound != null && afterId < upperBound && scanned < 1000) {
            List<RefundApplication> stale = refundApplicationMapper.selectStalePayoutProcessingAfter(
                    STALE_MINUTES, afterId, upperBound, 200);
            if (stale == null || stale.isEmpty()) { scanCursor = 0L; break; }
            for (RefundApplication r : stale) {
                if (r == null || r.getId() == null || r.getId() <= afterId || r.getId() > upperBound) {
                    throw new IllegalStateException("退款维护游标未前进");
                }
                // 业务失败或无渠道号仍推进游标，不能让永久失败的前缀饿死后页。
                afterId = r.getId();
                scanCursor = afterId;
                scanned++;
                try {
                    if (Integer.valueOf(RefundApplication.PAYOUT_DISPATCH_PENDING).equals(r.getPayoutStatus())
                            || Integer.valueOf(RefundApplication.PAYOUT_DISPATCHING).equals(r.getPayoutStatus())) {
                        // afterCommit 前宕机时这里会补派；微信以 RF+registrationId 幂等，重复调用不会二次退款。
                        refundPayoutService.payoutAfterReview(r.getId());
                    } else {
                        String refundNo = r.getRefundNo();
                        if ((refundNo == null || refundNo.trim().isEmpty())
                                && "registration".equals(r.getSourceType()) && r.getSourceId() != null) {
                            refundNo = "RF" + r.getSourceId();
                            if (refundApplicationMapper.ensureRefundNo(r.getId(), refundNo) != 1) {
                                continue;
                            }
                        }
                        if (refundNo == null || refundNo.trim().isEmpty()) {
                            continue;
                        }
                        // 查微信真实态并经 handleRefundNotify 回写:SUCCESS→1 / ABNORMAL|CLOSED→2 / PROCESSING 保持
                        wechatPayService.refreshPayoutFromWx(refundNo);
                    }
                    done++;
                } catch (Exception e) {
                    log.error("退款对账处理失败 refundId={} refundNo={}", r.getId(), r.getRefundNo(), e);
                }
            }
        }
        if (upperBound != null && afterId >= upperBound) scanCursor = 0L;
        if (scanned > 0) {
            log.info("退款对账:扫描 {} 单,刷新 {} 单", scanned, done);
        }
        // 待人工/待复核积压巡检告警(即使无新失败,也提醒财务清理堆积)
        try {
            int backlog = refundApplicationMapper.countManualPayoutBacklog();
            if (backlog > 0) {
                // 巡检每 10~15 分钟一跑：按固定事件限频，并带前 20 个退款单 id 便于定位。
                String ids = sampleIds(refundApplicationMapper::sampleManualPayoutBacklogIds);
                log.warn("Refund monitor anomaly category=manual count={} refundIds={}", backlog, ids);
                alarmService.alarmThrottled("monitor.refund.manual", "退款待人工积压",
                        "待人工/待复核退款单=" + backlog + " refundIds=" + ids + ",请财务及时处理");
            }
        } catch (Exception e) {
            // 守门告警自身失明是最贵的静默:计数或告警投递挂了必须留痕，否则积压堆到天荒地老也无人知。
            log.error("退款待人工积压巡检失败，积压告警可能未送达", e);
        }
        // 卡在派发态(5/6)的单同样是"钱一分没退",但不落在 (2,3) 的人工队列里 ——
        // 只数人工积压 = 对这一半状态零告警,退款业务号冲突那类反复失败会静默重试到天荒地老。
        // 计数取的是重试前的 stuckAtScan(见方法开头),此处不能重查:重试已刷新 update_time。
        try {
            if (stuckAtScan != null && stuckAtScan > 0) {
                log.warn("Refund monitor anomaly category=dispatch_stuck count={} refundIds={}", stuckAtScan, stuckIdsAtScan);
                alarmService.alarmThrottled("monitor.refund.dispatch_stuck", "退款派发卡滞",
                        "超过 " + STALE_MINUTES + " 分钟仍停在待派发/派发中的退款单=" + stuckAtScan
                                + " refundIds=" + stuckIdsAtScan + ",请排查派发失败原因");
            }
        } catch (Exception e) {
            log.error("退款派发卡滞告警发送失败，卡滞退款单数={}", stuckAtScan, e);
        }
        if (depositFailure != null) throw depositFailure;
    }

    /** 样本只用于定位；取样失败不影响按计数告警。 */
    private String sampleIds(java.util.function.Supplier<List<Long>> sample) {
        try {
            List<Long> ids = sample.get();
            return ids == null ? "UNKNOWN" : ids.stream().limit(20)
                    .map(id -> id == null ? "UNKNOWN" : id.toString())
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        } catch (Exception e) {
            log.error("退款巡检样本读取失败 type={}", e.getClass().getSimpleName());
            return "UNKNOWN";
        }
    }
}
