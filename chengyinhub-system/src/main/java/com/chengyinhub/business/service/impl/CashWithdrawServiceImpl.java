package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.MemberCashWithdrawMapper;
import com.chengyinhub.business.service.ICashWithdrawService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.support.MakerCheckerGuard;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import com.chengyinhub.common.utils.DateUtils;

/**
 * 微信零钱提现实现(T5 真实出金)。
 *
 * 资金安全(D-02 状态机,经对抗审查加固):
 * - 发起转账只是「受理」,不代表到账。发起成功 → status=0 处理中,由定时任务查询微信最终态后才置成功。
 * - ★余额服务契约:deductBalance/addBalance 失败时**抛异常**(非返回 false),故所有调用都以 try/catch 兜底。
 * - 防重复:软查 + member_cash_withdraw 唯一约束 uk_pending_member(生成列,status in(0,3) 才占键)原子拦截并发重复发起。
 * - 单次退款:失败退款一律「先 CAS 抢占状态守卫(status=0/3)→ 赢家才退款」,杜绝并发/多节点/requery 造成的重复退款,不再用事后 deductBalance 撤销(它会因余额不足再抛异常)。
 * - 崩溃恢复:发起转账前先持久化确定性 out_batch_no=CW+id;syncOne 对空 out_batch_no 按 id 重算,受理后即使写回中断也可从微信侧恢复。
 * - 不静默卡死:处理中超过 STUCK_HOURS 仍未终结 → 升级人工 + 告警,避免永久阻断注销/静默冻结。
 */
@Service
public class CashWithdrawServiceImpl implements ICashWithdrawService {

    private static final Logger logger = LoggerFactory.getLogger(CashWithdrawServiceImpl.class);

    /** 处理中单超过此时长仍未终结 → 升级人工核查 */
    private static final long STUCK_HOURS = 6L;

    @Autowired
    private MemberCashWithdrawMapper mapper;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private IWechatPayService wechatPayService;

    @Autowired
    private com.chengyinhub.common.config.WxPayConfig wxPayConfig;

    @Autowired
    private AlarmService alarmService;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** [赏金冻结] 提现前要刨掉仍在 48h 冻结期内的拓展人赏金,口径与 UmsMemberWithdrawalServiceImpl 一致。 */
    @Autowired
    private WithdrawalFrozenAmountResolver frozenAmountResolver;

    @Autowired
    private com.chengyinhub.business.service.support.WithdrawalBalanceAdmissionService balanceAdmissionService;

    @Autowired
    private com.chengyinhub.business.mapper.CashWithdrawTransferIntentMapper transferIntents;

    @Override
    public boolean hasPendingWithdraw(Long memberId) {
        if (memberId == null) return false;
        return mapper.countPendingByMember(memberId) > 0;
    }

    @Override
    public MemberCashWithdraw withdraw(Long memberId, BigDecimal amountYuan) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("提现须在独立请求中发起，不能在已有资金事务中调用");
        if (memberId == null) throw new IllegalStateException("请先登录");
        if (amountYuan == null) throw new IllegalStateException("请输入提现金额");
        BigDecimal amount = amountYuan.setScale(2, RoundingMode.DOWN);
        if (amount.compareTo(MIN_AMOUNT) < 0) throw new IllegalStateException("最低提现 ¥" + MIN_AMOUNT);

        // 软查在途(快速友好失败);真正的原子防重靠 insert 的唯一键 uk_pending_member
        if (mapper.countPendingByMember(memberId) > 0) {
            throw new IllegalStateException("有提现正在处理中,请等到账后再试");
        }

        UmsMember m = umsMemberService.selectUmsMemberById(memberId);
        if (m == null) throw new IllegalStateException("用户不存在");
        // 选定转账渠道(判据见 resolveTransferChannel 的 javadoc)。
        final String[] channel = resolveTransferChannel(
                m.getWxOpenId(), m.getAppOpenId(),
                wxPayConfig.getAppid(), wxPayConfig.getAppAppid());
        if (channel == null) {
            throw new IllegalStateException("未绑定微信,无法提现到零钱");
        }
        final String openid = channel[0];
        final String transferAppid = channel[1];
        if (transferAppid == null || transferAppid.isBlank()
                || wxPayConfig.getMerchantId() == null || wxPayConfig.getMerchantId().isBlank())
            throw new IllegalStateException("提现渠道未配置，请联系客服核查");

        // [冻结] 可用余额 = 余额 - 总冻结额,口径与 UmsMemberWithdrawalServiceImpl **同一个解析点**。
        // 银行卡提现早已刨掉赏金,微信零钱这条口子原来只走 deductBalance(护栏只认裸余额)⇒ 冻结款可从这里提走。
        // ★所以 PLAYER 投诉补偿冷静额也必须在这条腿生效:同一个 resolver,一次算总额,不许两条腿各算各的。
        //
        // ⚠️ 刻意不 try/catch:任一来源查询挂了就整单失败(fail-closed)。吞异常当 0 = 冻结静默失效、钱当场提走。
        WithdrawalFrozenAmountResolver.Frozen frozenParts = frozenAmountResolver.resolve(memberId);
        BigDecimal frozen = frozenParts.total();
        BigDecimal balance = m.getBalance() == null ? BigDecimal.ZERO : m.getBalance();
        // ★这道预检只给用户一句人话,并保证不足时压根不建单(不留 status=0 僵尸单);
        //   它是 check-then-act,不是防线 —— 真防线是下面 deductWithdrawableBalance 的原子留底扣款。
        //   命中冷静期时同样带 PLAYER_WITHDRAWAL_COOLDOWN 机器码,两条腿的码必须一致。
        if (balance.subtract(frozen).compareTo(amount) < 0) {
            throw new IllegalStateException(
                    frozenParts.isEmpty() ? "可用余额不足" : frozenParts.rejectionMessage());
        }

        Date now = DateUtils.getNowDate();
        String traceId = UUID.randomUUID().toString().replace("-", "");

        // 1. 建提现单(status=0 处理中)。并发重复发起时唯一键冲突 → 原子拦截
        MemberCashWithdraw w = new MemberCashWithdraw();
        w.setMemberId(memberId);
        w.setOpenid(openid);
        w.setAmount(amount);
        w.setStatus(MemberCashWithdraw.STATUS_PROCESSING);
        w.setDelFlag(0);
        w.setCreateTime(now);
        w.setTraceId(traceId);
        w.setAuditBy(memberId);   // 自助提现=本人操作
        w.setAuditTime(now);
        try {
            mapper.insertMemberCashWithdraw(w);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("有提现正在处理中,请等到账后再试");
        }
        Long id = w.getId();
        String outBatchNo = "CW" + id;   // 确定性幂等键
        String outDetailNo = "CD" + id;

        // 2. ★发起转账前先持久化 out_batch_no(崩溃后可按 CW+id 从微信侧恢复,不留无法对账的空批次单)
        MemberCashWithdraw idc = new MemberCashWithdraw();
        idc.setId(id);
        idc.setOutBatchNo(outBatchNo);
        idc.setOutDetailNo(outDetailNo);
        if (mapper.updateMemberCashWithdraw(idc) != 1)
            throw new IllegalStateException("提现原批次号写入失败，未扣款、未转账");
        w.setOutBatchNo(outBatchNo);
        w.setOutDetailNo(outDetailNo);

        // 3. 原子扣余额,并给冻结赏金留底(护栏与扣款同一条 UPDATE ⇒ 并发也提不走冻结额)。
        boolean deducted;
        boolean currentRequestEligible;
        try {
            com.chengyinhub.business.service.support.WithdrawalBalanceAdmissionService.Admission admission =
                    balanceAdmissionService.debitCash(id, memberId, amount, wxPayConfig.getMerchantId(),
                            transferAppid, "城瘾分润提现");
            deducted = admission.deducted;
            currentRequestEligible = admission.currentRequestEligible;
        } catch (Exception e) {
            // DB/mapper 未知异常 → 扣款状态不明(DB 可能已扣但响应丢失),绝不自动退款,置待人工+告警
            claimFailedRefund(id, DateUtils.getNowDate(),
                    "扣款状态不明(可能已扣),保留CW/CD供人工对账:扣款异常:" + safe(e.getMessage()), null, null);
            alarmService.alarm("提现待人工", "原子扣款状态不明 id=" + id + " member=" + memberId
                    + " amount=" + amount + " outBatchNo=" + outBatchNo + " trace=" + traceId);
            throw new IllegalStateException("提现处理异常,请稍后重试");
        }
        if (!currentRequestEligible) {
            // A concurrent terminal/freeze owns this original receipt. Do not debit, submit, refund or overwrite it.
            throw new IllegalStateException("提现单状态已更新，请刷新");
        }
        if (!deducted) {
            // The short transaction persisted the original no-debit terminal while holding its row lock.
            // Do not overwrite a later terminal/freeze after that transaction ended.
            throw new IllegalStateException("可用余额不足");
        }

        // 4. 发起转账(外部调用)——只「受理」,不代表到账
        // appid 与 openid 同源,由上面的渠道判定给出(R1 已加 app_open_id 独立列)。
        var intent = requireTransferIntent(w);
        if (transferIntents.markSubmitted(id, intent.requestHash()) != 1)
            throw new IllegalStateException("提现原提交已被领取或状态已变化，请按原号查询");
        intent = requireTransferIntent(w);
        if (!intent.submitted()) throw new IllegalStateException("提现原提交证据尚未提交，未发起转账");
        Map<String, String> res = wechatPayService.transferToBalance(intent);
        boolean accepted = res != null && "true".equals(res.get("ok"));
        if (accepted) {
            // ★不直接置成功:置「处理中」,记录批次号/受理状态/提交时间,定时任务查询确认到账后才置成功
            MemberCashWithdraw upd = new MemberCashWithdraw();
            upd.setId(id);
            upd.setTransferBatchId(res.get("batchId"));
            upd.setWechatBatchStatus(res.get("batchStatus"));
            upd.setSubmitTime(now);
            int observed = mapper.updateReceiptIfProcessing(upd);
            if (observed > 0) logger.info("提现已受理待确认 id={} member={} amount={} batchId={} trace={}", id, memberId, amount, res.get("batchId"), traceId);
            else logger.info("提现受理观察未改原持久回执 id={} trace={}", id, traceId);
            return mapper.selectByIdForUpdate(id);
        }

        // 5a. ★结果不确定(超时/网络/验签异常,res==null 或 ambiguous):微信可能已受理并已打款。
        //     绝不退款+置终态,否则「已打款 + 余额回补」= 双花丢钱。保持 status=0 处理中(记 submitTime 供超时计时),
        //     由查询任务用 out_batch_no=CW+id 向微信核实最终态后再推进状态机。
        boolean provenNotSent = res != null && "true".equals(res.get("notSent"))
                && intent.requestHash().equals(res.get("requestHash"));
        boolean ambiguous = !provenNotSent;
        if (ambiguous) {
            MemberCashWithdraw upd = new MemberCashWithdraw();
            upd.setId(id);
            upd.setSubmitTime(now);
            upd.setWechatBatchStatus("UNCERTAIN");
            int observed = mapper.updateReceiptIfProcessing(upd);
            if (observed > 0) logger.warn("提现发起结果不确定,保持处理中待查询核实 id={} member={} amount={} err={} trace={}",
                    id, memberId, amount, res == null ? "无响应" : res.get("error"), traceId);
            else logger.info("提现不确定观察未改原持久回执 id={} trace={}", id, traceId);
            return mapper.selectByIdForUpdate(id);
        }

        // 5b. 确定性未受理(微信拒绝/参数非法/服务未就绪),钱未出 → 原路退回余额一次(退款也失败则待人工,不丢钱)
        if (transferIntents.markLocallyRejected(id, intent.requestHash()) != 1)
            throw new IllegalStateException("提现未提交证据写入失败，须按原号核查");
        String err = res.get("error");
        Date failedAt = DateUtils.getNowDate();
        if (!claimFailedRefund(id, failedAt, err, null, null)) {
            throw new IllegalStateException("提现失败:" + (err == null ? "请稍后重试" : err));
        }
        try {
            refundFailedClaim(w, failedAt, null);
        } catch (Exception e) {
            // 回补事务结果可能未知(提交成功但回包丢失):绝不无条件改回 3,否则人工会再补一次。
            // 只在仍为待人工(3)时补备注;已是 2 则保持终态。
            noteManualIfStillPending(id, "转账失败且退款未成功,待人工:" + safe(err));
            logger.error("提现明确失败回补事务异常 id={}", id, e);
            alarmService.alarm("提现待人工", "发起失败且退款异常 id=" + id + " member=" + memberId + " amount=" + amount + " trace=" + traceId);
        }
        throw new IllegalStateException("提现失败:" + (err == null ? "请稍后重试" : err));
    }

    /**
     * 定时查询处理中的提现单,向微信确认最终状态并推进状态机(修复「已受理即成功」)。
     * @param limit 单次处理条数
     * @return 本次处理条数
     */
    @Override
    public int syncPendingWithdrawals(int limit) {
        List<MemberCashWithdraw> pending = mapper.selectPendingForQuery(limit);
        int handled = 0;
        for (MemberCashWithdraw w : pending) {
            try {
                syncOne(w);
            } catch (Exception e) {
                logger.error("提现状态同步异常 id={}: {}", w.getId(), e.getMessage(), e);
            }
            handled++;
        }
        return handled;
    }

    private void syncOne(MemberCashWithdraw w) {
        Long id = w.getId();
        Date now = DateUtils.getNowDate();
        // 崩溃后 out_batch_no 可能为空:按确定性规则 CW+id 重算,保证能查到真实批次
        Map<String, String> q;
        try {
            var original = requireTransferIntent(w);
            q = original.submitted() ? originalTransferFacts(original) : Map.of("result", "UNKNOWN");
        } catch (RuntimeException missingEvidence) {
            q = Map.of("result", "UNKNOWN");
        }
        String result = q == null ? "UNKNOWN" : q.get("result");

        if ("SUCCESS".equals(result)) {
            MemberCashWithdraw upd = new MemberCashWithdraw();
            upd.setId(id);
            upd.setStatus(MemberCashWithdraw.STATUS_SUCCESS);
            upd.setWechatBatchStatus(q.get("batchStatus"));
            upd.setWechatDetailId(emptyToNull(q.get("detailId")));
            upd.setFinishTime(now);
            upd.setLastQueryTime(now);
            int n = mapper.updateSuccessIfPending(upd); // Confirm original success, including escalated manual states.
            if (n > 0) logger.info("提现到账确认 id={} member={} amount={}", id, w.getMemberId(), w.getAmount());
            return;
        }

        if ("FAIL".equals(result)) {
            // ★先 CAS 抢占 status 0->3(暂置待人工),赢家独占退款;输者直接返回,绝不重复退款
            if (!claimFailedRefund(id, now, "微信转账失败",
                    emptyToNull(q.get("failReasonCode")), emptyToNull(q.get("detailId")))) {
                return; // 已被其它线程/节点处理,不重复退款
            }
            // 赢家:退款恰一次
            try {
                if (refundFailedClaim(w, now, q)) {
                    logger.warn("提现失败已退款 id={} member={} amount={}", id, w.getMemberId(), w.getAmount());
                }
            } catch (Exception e) {
                // 退款失败 → 保持待人工 + 告警;备注只写到仍为 3 的行,不误导已回补(2)的单
                noteManualIfStillPending(id, "渠道失败且自动退款失败,待人工:" + safe(e.getMessage()));
                logger.error("提现失败退款异常 id={} member={} amount={}", id, w.getMemberId(), w.getAmount(), e);
                alarmService.alarm("提现待人工", "渠道失败且退款失败 id=" + id + " member=" + w.getMemberId() + " amount=" + w.getAmount());
            }
            return;
        }

        // PROCESSING / UNKNOWN:保持处理中,仅记录查询时间;超时兜底升级人工,避免永久卡死(阻断注销/静默冻结)
        MemberCashWithdraw upd = new MemberCashWithdraw();
        upd.setId(id);
        upd.setLastQueryTime(now);
        String bs = q == null ? null : q.get("batchStatus");
        if (bs != null && !bs.isEmpty()) {
            upd.setWechatBatchStatus(bs);
        }
        upd.setWechatDetailId(emptyToNull(q == null ? null : q.get("detailId")));
        if (mapper.updateReceiptIfProcessing(upd) == 0) return;

        if (isStuck(w, now)) {
            MemberCashWithdraw esc = new MemberCashWithdraw();
            esc.setId(id);
            esc.setStatus(MemberCashWithdraw.STATUS_FAILED_MANUAL);
            esc.setManualNote("超过" + STUCK_HOURS + "h未确认最终状态,转人工核查(result=" + result + ")");
            int n = mapper.updateStatusIfProcessing(esc); // CAS 0->3
            if (n > 0) {
                alarmService.alarm("提现长时间未终结",
                        "id=" + id + " member=" + w.getMemberId() + " amount=" + w.getAmount() + " result=" + result);
            }
        }
    }

    /** status=0 且未冻结才可领取失败回补权；同步发起线和对账线必须共用这一道 CAS。 */
    private boolean claimFailedRefund(Long id, Date now, String reason,
                                      String channelErrorCode, String wechatDetailId) {
        MemberCashWithdraw claim = new MemberCashWithdraw();
        claim.setId(id);
        claim.setStatus(MemberCashWithdraw.STATUS_FAILED_MANUAL);
        claim.setLastQueryTime(now);
        claim.setFailReason(safe(reason));
        claim.setChannelErrorCode(channelErrorCode);
        claim.setWechatDetailId(wechatDetailId);
        return mapper.updateStatusIfProcessing(claim) == 1;
    }

    /** 仅当行仍为失败待人工(3)时写备注;按状态 CAS,不改状态。 */
    private void noteManualIfStillPending(Long id, String note) {
        try {
            MemberCashWithdraw m = new MemberCashWithdraw();
            m.setId(id);
            m.setManualNote(note);
            mapper.updateStatusIfManual(m);
        } catch (Exception noteFailure) {
            logger.error("提现待人工备注写入失败 id={}", id, noteFailure);
        }
    }

    private boolean refundFailedClaim(MemberCashWithdraw withdrawal, Date now, Map<String, String> facts) {
        return Boolean.TRUE.equals(new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> {
                    MemberCashWithdraw original = mapper.selectByIdForUpdate(withdrawal.getId());
                    if (original == null || !Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_MANUAL).equals(original.getStatus())
                            || Integer.valueOf(1).equals(original.getFrozen())) return false;
                    var intent = requireTransferIntent(original);
                    requireRefundEvidence(intent, facts);
                    MemberCashWithdraw refunded = new MemberCashWithdraw();
                    refunded.setId(original.getId());
                    refunded.setStatus(MemberCashWithdraw.STATUS_FAILED_REFUNDED);
                    refunded.setFinishTime(now);
                    if (mapper.updateStatusIfManual(refunded) != 1) return false;
                    umsMemberService.addBalance(original.getMemberId(), intent.amountYuan());
                    return true;
                }));
    }

    private com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent requireTransferIntent(MemberCashWithdraw original) {
        var intent = original == null ? null : transferIntents.selectByWithdrawId(original.getId());
        if (intent == null) throw new IllegalStateException("缺少原提现扣款/提交证据，请按原号核查，不能回补");
        intent.requireMatches(original);
        return intent;
    }

    private Map<String, String> originalTransferFacts(com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent intent) {
        Map<String, String> facts = wechatPayService.queryTransferResult(intent);
        if (facts == null) return Map.of("result", "UNKNOWN");
        if (("SUCCESS".equals(facts.get("result")) || "FAIL".equals(facts.get("result")))
                && !intent.matchesObservation(facts)) return Map.of("result", "UNKNOWN");
        return facts;
    }

    private void requireRefundEvidence(com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent intent,
                                       Map<String, String> facts) {
        intent.requireValid();
        if (!intent.submitted() || intent.locallyRejected()) return;
        if (facts == null || !"FAIL".equals(facts.get("result")) || !intent.matchesObservation(facts))
            throw new IllegalStateException("渠道尚未确认原提现失败，不能回补；请按原号继续核查");
    }

    private boolean isStuck(MemberCashWithdraw w, Date now) {
        Date ref = w.getSubmitTime() != null ? w.getSubmitTime() : w.getCreateTime();
        if (ref == null) return false;
        return (now.getTime() - ref.getTime()) > STUCK_HOURS * 3600_000L;
    }

    private void markFailed(Long id, String outBatchNo, String outDetailNo, String reason, Date time, int status) {
        MemberCashWithdraw upd = new MemberCashWithdraw();
        upd.setId(id);
        upd.setOutBatchNo(outBatchNo);
        upd.setOutDetailNo(outDetailNo);
        upd.setStatus(status);
        upd.setFailReason(safe(reason));
        upd.setFinishTime(time);
        upd.setAuditBy(0L);        // 系统自动操作
        upd.setAuditTime(time);
        mapper.updateMemberCashWithdraw(upd);
    }

    private void markFailed(Long id, String outBatchNo, String outDetailNo, String reason, String manualNote, Date time, int status) {
        MemberCashWithdraw upd = new MemberCashWithdraw();
        upd.setId(id);
        upd.setOutBatchNo(outBatchNo);
        upd.setOutDetailNo(outDetailNo);
        upd.setStatus(status);
        upd.setFailReason(safe(reason));
        upd.setManualNote(manualNote);
        upd.setFinishTime(time);
        upd.setAuditBy(0L);
        upd.setAuditTime(time);
        mapper.updateMemberCashWithdraw(upd);
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.length() > 250 ? s.substring(0, 250) : s;
    }

    /** MyBatis 的 &lt;if test="x != null"&gt; 只挡 null,空串会被当成有效值写入覆盖旧值;查询未拿到明细号/错误码时须传 null 而非空串,避免洗掉已有值。 */
    private String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    @Override
    public List<MemberCashWithdraw> myWithdraws(Long memberId) {
        return mapper.selectByMember(memberId);
    }

    @Override
    public List<MemberCashWithdraw> adminList(MemberCashWithdraw query) {
        return mapper.selectForAdmin(query);
    }

    @Override
    public MemberCashWithdraw getById(Long id) {
        return mapper.selectById(id);
    }

    @Override
    public MemberCashWithdraw requery(Long id) {
        MemberCashWithdraw w = mapper.selectById(id);
        // P块冻结:冻结中的单暂停一切处理(含人工重查),与 manualRefund 同一道闸、同一套话术。
        // ★必须在入口显式拒绝,不能只靠 updateStatusIfProcessing 的 CAS 守卫兜着:
        //   syncOne 的 SUCCESS 分支是 `int n = CAS; if (n > 0) log;` —— CAS 被拦时 n=0 会静默 return,
        //   接口照样回成功。那样操作者看到「重查成功」,而钱可能已经出去、库里仍写着 status=0,
        //   零日志零提示(回执≠观测)。两层并存:入口拦人,CAS 拦并发。
        if (Integer.valueOf(1).equals(w == null ? null : w.getFrozen())) {
            throw new IllegalStateException("该提现单已被冻结,请先解冻:" + safe(w.getFrozenReason()));
        }
        if (w != null && (Integer.valueOf(MemberCashWithdraw.STATUS_PROCESSING).equals(w.getStatus())
                || Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_MANUAL).equals(w.getStatus())
                || Integer.valueOf(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW).equals(w.getStatus()))) {
            syncOne(w);
        }
        return mapper.selectByIdForUpdate(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean manualRefund(Long id, String operator, String note) {
        return manualRefund(id, operator, note, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean manualRefund(Long id, String operator, String note, Integer expectedStatus) {
        MakerCheckerGuard.requireActor(operator, "operator");
        MemberCashWithdraw w = mapper.selectByIdForUpdate(id);
        if (w == null) throw new IllegalStateException("提现单不存在");
        if (Integer.valueOf(1).equals(w.getFrozen()))
            throw new IllegalStateException("该提现单已被冻结,请先解冻:" + safe(w.getFrozenReason()));
        if (Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_NO_DEBIT).equals(w.getStatus()))
            throw new IllegalStateException("该提现单未实际扣款,无需人工回补");
        if (expectedStatus != null && !expectedStatus.equals(w.getStatus())) return false;
        boolean preparing = Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_MANUAL).equals(w.getStatus());
        if (!preparing && !Integer.valueOf(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW).equals(w.getStatus()))
            throw new IllegalStateException("仅『失败待人工/待复核』的提现单可人工补单");
        if (!preparing) MakerCheckerGuard.requireDistinct(w.getManualOperator(), operator);
        var intent = requireTransferIntent(w);
        Map<String, String> facts = intent.submitted() && !intent.locallyRejected()
                ? originalTransferFacts(intent) : null;
        if (facts != null && "SUCCESS".equals(facts.get("result"))) {
            MemberCashWithdraw paid = new MemberCashWithdraw();
            paid.setId(id); paid.setWechatBatchStatus(facts.get("batchStatus"));
            paid.setWechatDetailId(facts.get("detailId"));
            paid.setFinishTime(DateUtils.getNowDate()); paid.setLastQueryTime(DateUtils.getNowDate());
            mapper.updateSuccessIfPending(paid);
            return false;
        }
        requireRefundEvidence(intent, facts);
        if (preparing) {
            MemberCashWithdraw prepare = new MemberCashWithdraw();
            prepare.setId(id); prepare.setStatus(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW);
            prepare.setManualOperator(operator);
            prepare.setManualNote(note == null ? "人工回补待复核" : note);
            return mapper.updateStatusIfManual(prepare) > 0;
        }
        MemberCashWithdraw claim = new MemberCashWithdraw();
        claim.setId(id); claim.setStatus(MemberCashWithdraw.STATUS_FAILED_REFUNDED);
        claim.setManualVerifier(operator);
        claim.setManualNote(note == null ? "人工补单双人复核通过" : note);
        claim.setFinishTime(DateUtils.getNowDate()); claim.setAuditBy(0L);
        claim.setAuditTime(DateUtils.getNowDate());
        if (mapper.updateStatusIfRefundReview(claim) != 1) return false;
        umsMemberService.addBalance(w.getMemberId(), intent.amountYuan());
        return true;
    }

    @Override
    public String reconcile(int stuckHours) {
        Date before = new Date(DateUtils.getNowDate().getTime() - stuckHours * 3600_000L);
        int stuck = mapper.countStuckProcessing(before);
        int manual = mapper.countManualPending();
        int successNoBatch = mapper.countSuccessWithoutBatch();
        if (stuck > 0) {
            String ids = reconciliationIds(mapper.sampleStuckProcessingIds(before));
            logger.warn("Withdrawal monitor anomaly category=stuck count={} withdrawIds={}", stuck, ids);
            alarmService.alarmThrottled("monitor.withdraw.stuck", "提现处理中积压", "超过" + stuckHours + "h仍处理中的提现单=" + stuck
                    + " withdrawIds=" + ids);
        }
        if (manual > 0) {
            String ids = reconciliationIds(mapper.sampleManualPendingIds());
            logger.warn("Withdrawal monitor anomaly category=manual count={} withdrawIds={}", manual, ids);
            alarmService.alarmThrottled("monitor.withdraw.manual", "提现待人工积压", "失败待人工的提现单=" + manual
                    + " withdrawIds=" + ids);
        }
        if (successNoBatch > 0) {
            String ids = reconciliationIds(mapper.sampleSuccessWithoutBatchIds());
            logger.warn("Withdrawal monitor anomaly category=missing_batch count={} withdrawIds={}", successNoBatch, ids);
            alarmService.alarmThrottled("monitor.withdraw.missing_batch", "提现数据异常", "标记成功却无微信批次号的提现单=" + successNoBatch
                    + " withdrawIds=" + ids);
        }
        String summary = "提现对账:处理中积压=" + stuck + " 待人工=" + manual + " 成功无批次号=" + successNoBatch;
        logger.info(summary);
        return summary;
    }

    private String reconciliationIds(java.util.List<Long> ids) {
        return ids == null ? "UNKNOWN" : ids.stream().limit(20)
                .map(id -> id == null ? "UNKNOWN" : id.toString())
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    @Override
    public boolean freezeWithdraw(Long id, String operator, String reason) {
        MakerCheckerGuard.requireActor(operator, "operator");
        if (mapper.selectById(id) == null) {
            throw new IllegalStateException("提现单不存在");
        }
        MemberCashWithdraw w = new MemberCashWithdraw();
        w.setId(id);
        w.setFrozenReason(reason);
        w.setFrozenBy(operator);
        return mapper.freezeWithdraw(w) > 0;
    }

    @Override
    public boolean unfreezeWithdraw(Long id, String operator) {
        MakerCheckerGuard.requireActor(operator, "operator");
        if (mapper.selectById(id) == null) {
            throw new IllegalStateException("提现单不存在");
        }
        return mapper.unfreezeWithdraw(id) > 0;
    }

    /**
     * 选定提现转账的 (openid, appid) 组合 —— 两者**必须同源**,微信按
     * (appid, openid) 认人,配错会被判「不匹配」而拒付。
     *
     * 判据的关键一条:**两列值相同 ⇒ 这是存量 App 用户**。
     *   R1 修复前 App openid 被误写进 wx_open_id;登录迁移只补空的 app_open_id、
     *   **不动 wx_open_id**(那列对真双端用户是真实的小程序 openid,清掉会断小程序侧)。
     *   于是存量 App 用户两列存着同一个 App openid —— 此时若仍「wx 列优先」,
     *   就会拿小程序 appid 配 App openid,继续被拒付。故同值时判为 App 渠道。
     *   真双端用户两列是两个不同应用的不同 id,不会相等,照常走小程序。
     *
     * @return {openid, appid};两列都为空时返回 null(调用方转成业务异常)
     */
    static String[] resolveTransferChannel(String wxOpenid, String appOpenid,
                                           String wxAppid, String appAppid) {
        boolean hasWx = wxOpenid != null && !wxOpenid.isEmpty();
        boolean hasApp = appOpenid != null && !appOpenid.isEmpty();
        boolean sameValue = hasWx && wxOpenid.equals(appOpenid);
        if (hasWx && !sameValue) {
            return new String[] {wxOpenid, wxAppid};
        }
        if (hasApp) {
            // appAppid 可能为空(未配置移动应用),此时 transferToBalance 会明确
            // 拒绝并点名 appid,**不会**回落小程序 appid 去撞「不匹配」。
            return new String[] {appOpenid, appAppid};
        }
        return null;
    }
}
