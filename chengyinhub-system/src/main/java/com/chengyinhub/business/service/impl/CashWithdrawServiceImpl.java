package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.config.RestoreDispatchGuard;

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
import org.springframework.transaction.annotation.Propagation;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.CashWithdrawFundFact;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
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
        RestoreDispatchGuard.requireDispatchAllowed();
        requireOutsideTransaction();
        if (memberId == null) throw new IllegalStateException("请先登录");
        if (amountYuan == null) throw new IllegalStateException("请输入提现金额");
        BigDecimal amount = amountYuan.setScale(2, RoundingMode.DOWN);
        if (amount.compareTo(MIN_AMOUNT) < 0) throw new IllegalStateException("最低提现 ¥" + MIN_AMOUNT);
        if (amount.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE, 2)) > 0)
            throw new IllegalStateException("提现金额超过渠道单次上限");

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
        final String transferMchid = wxPayConfig.getMerchantId();


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

        if (transferMchid == null || transferMchid.trim().isEmpty() || transferMchid.length() > 64
                || transferAppid == null || transferAppid.trim().isEmpty() || transferAppid.length() > 64
                || openid.trim().isEmpty() || openid.length() > 128)
            throw new IllegalStateException("提现渠道原始身份未配置完整");

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
            if (mapper.insertMemberCashWithdraw(w) != 1 || w.getId() == null)
                throw new IllegalStateException("提现原单写入失败");
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
            throw new IllegalStateException("提现原业务号写入失败");
        w.setOutBatchNo(outBatchNo); w.setOutDetailNo(outDetailNo);
        CashWithdrawTransferIntent request = CashWithdrawTransferIntent.fromRequest(id, transferMchid,
                transferAppid, outBatchNo, outDetailNo, amount, openid, "城瘾分润提现");

        // 3. 原子扣余额,并给冻结赏金留底(护栏与扣款同一条 UPDATE ⇒ 并发也提不走冻结额)。
        boolean deducted;
        boolean currentRequestEligible;
        try {
            com.chengyinhub.business.service.support.WithdrawalBalanceAdmissionService.Admission admission =
                    balanceAdmissionService.debitCash(id, memberId, amount, transferMchid, transferAppid, "城瘾分润提现");
            deducted = admission.deducted;
            currentRequestEligible = admission.currentRequestEligible;
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
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
        CashWithdrawTransferIntent submission = recordSubmissionIntent(id, request);
        if (submission == null)
            throw new IllegalStateException("提现单状态已更新，请刷新");
        Map<String, String> res;
        try {
            res = wechatPayService.transferToBalance(submission);
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (Exception uncertain) {
            // Intent is already committed. Exception type never proves the channel did not act.
            res = Map.of("ok", "false", "ambiguous", "true", "errorCode", "SUBMIT_EXCEPTION");
            logger.warn("提现提交结果未知 id={} type={}", id, uncertain.getClass().getSimpleName());
        }
        boolean accepted = res != null && "true".equals(res.get("ok")) && emptyToNull(res.get("batchId")) != null;
        if (accepted) {
            recordChannelFact(id, CashWithdrawFundFact.ACCEPTED, res, submission.submissionIntentId());
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
        // Positive evidence is required: an omitted ambiguous flag is not a rejection certificate.
        boolean ambiguous = res == null || !"false".equals(res.get("ok"))
                || !"false".equals(res.get("ambiguous")) || !"true".equals(res.get("notSent"))
                || !submission.requestHash().equals(res.get("requestHash"));
        if (ambiguous) {
            recordChannelFact(id, CashWithdrawFundFact.UNKNOWN, res, submission.submissionIntentId());
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
        String err = res.get("error");
        Date failedAt = DateUtils.getNowDate();
        if (!recordChannelFact(id, CashWithdrawFundFact.NOT_ACCEPTED, res, submission.submissionIntentId()))
            throw new IllegalStateException("提现状态已变化，待核查");
        if (!claimFailedRefund(id, failedAt, err, null, null)) {
            throw new IllegalStateException("提现失败:" + (err == null ? "请稍后重试" : err));
        }
        try {
            refundFailedClaim(w, failedAt);
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int syncPendingWithdrawals(int limit) {
        List<MemberCashWithdraw> pending = mapper.selectPendingForQuery(limit);
        int handled = 0;
        for (MemberCashWithdraw w : pending) {
            try {
                syncOne(w);
            } catch (RestoreDispatchGuard.Blocked paused) {
                throw paused;
            } catch (Exception e) {
                logger.error("提现状态同步异常 id={}: {}", w.getId(), e.getMessage(), e);
            }
            handled++;
        }
        return handled;
    }

    private void syncOne(MemberCashWithdraw w) {
        // Serialize recovery with recordSubmissionIntent before doing any channel I/O.
        ChannelQuery query = prepareChannelQuery(w.getId());
        if (query == null) return;
        w = query.withdrawal();
        Long id = w.getId();
        Date now = DateUtils.getNowDate();
        Map<String, String> q;
        try { q = wechatPayService.queryTransferResult(query.intent()); }
        catch (RestoreDispatchGuard.Blocked paused) { throw paused; }
        catch (Exception uncertain) {
            q = Map.of("result", "UNKNOWN", "errorCode", "QUERY_EXCEPTION");
            logger.warn("提现查单结果未知 id={} intentId={} type={}", id,
                    query.intent().submissionIntentId(), uncertain.getClass().getSimpleName());
        }
        if (q != null && ("SUCCESS".equals(q.get("result")) || "FAIL".equals(q.get("result")))
                && (!queryMatchesIntent(query.intent(), q)
                    || (query.batchId() != null && !query.batchId().equals(q.get("batchId"))))) {
            logger.warn("提现查单事实与原意图不符 id={} intentId={}", id, query.intent().submissionIntentId());
            q = Map.of("result", "UNKNOWN", "errorCode", "ORIGINAL_INTENT_MISMATCH");
        }
        if (query.intent().hasDebitSnapshot()) {
            settleSnapshotObservation(query, q, now);
            return;
        }
        String result = q == null ? "UNKNOWN" : q.get("result");

        if ("SUCCESS".equals(result)) {
            recordChannelFact(id, CashWithdrawFundFact.SUCCESS, q, query.intent().submissionIntentId());
            return;
        }

        // CLOSED alone has not been certified as proof that this detail can never succeed.
        if ("FAIL".equals(result) && "FAIL".equals(q.get("detailStatus"))) {
            if (!recordChannelFact(id, CashWithdrawFundFact.FINAL_FAIL, q, query.intent().submissionIntentId())) return;
            // Keep the original channel fact, but recovery admission is not permission to credit money.
            if (RestoreDispatchGuard.isRestoreMode()) return;
            // ★先 CAS 抢占 status 0->3(暂置待人工),赢家独占退款;输者直接返回,绝不重复退款
            if (!claimFailedRefund(id, now, "微信转账失败",
                    emptyToNull(q.get("failReasonCode")), emptyToNull(q.get("detailId")))) {
                return; // 已被其它线程/节点处理,不重复退款
            }
            // 赢家:退款恰一次
            try {
                if (refundFailedClaim(w, now)) {
                    logger.warn("提现失败已退款 id={} member={} amount={}", id, w.getMemberId(), w.getAmount());
                }
            } catch (RestoreDispatchGuard.Blocked paused) {
                throw paused;
            } catch (Exception e) {
                // 退款失败 → 保持待人工 + 告警;备注只写到仍为 3 的行,不误导已回补(2)的单
                noteManualIfStillPending(id, "渠道失败且自动退款失败,待人工:" + safe(e.getMessage()));
                logger.error("提现失败退款异常 id={} member={} amount={}", id, w.getMemberId(), w.getAmount(), e);
                alarmService.alarm("提现待人工", "渠道失败且退款失败 id=" + id + " member=" + w.getMemberId() + " amount=" + w.getAmount());
            }
            return;
        }

        // A query that cannot establish the final detail result never authorizes a later local rejection refund.
        if (!recordChannelFact(id, CashWithdrawFundFact.UNKNOWN, q, query.intent().submissionIntentId())) return;
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

    /**
     * A committed DEBIT without any channel history is a local crash window, not UNKNOWN.
     * The original row lock chooses recovery or the first submission intent, never both.
     * Recovery keeps the original identity and uses the existing two-person manual flow.
     */
    private record ChannelQuery(MemberCashWithdraw withdrawal, CashWithdrawTransferIntent intent, String batchId) { }

    private ChannelQuery prepareChannelQuery(Long id) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> {
                    MemberCashWithdraw original = mapper.selectByIdForUpdate(id);
                    if (original == null || Integer.valueOf(1).equals(original.getFrozen())) return null;
                    if (!Integer.valueOf(MemberCashWithdraw.STATUS_PROCESSING).equals(original.getStatus())
                            && !Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_MANUAL).equals(original.getStatus())
                            && !Integer.valueOf(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW).equals(original.getStatus())
                            && !Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_REFUNDED).equals(original.getStatus())) return null;
                    List<CashWithdrawFundFact> facts = checkedFacts(original);
                    CashWithdrawTransferIntent snapshot = snapshotIntent(original);
                    if (facts.isEmpty() && snapshot != null) {
                        requireSnapshotOnly(original, snapshot);
                        if (!snapshot.submitted() || snapshot.locallyRejected()) {
                            if (Integer.valueOf(MemberCashWithdraw.STATUS_PROCESSING).equals(original.getStatus()))
                                claimFailedRefund(id, DateUtils.getNowDate(), "原扣款快照证明未提交，保留原号待双人复核", null, null);
                            return null;
                        }
                        return new ChannelQuery(original, snapshot, emptyToNull(original.getTransferBatchId()));
                    }
                    // Do not reinterpret a missing intent alongside old UNKNOWN or any other channel fact.
                    // REFUND is local too: repeated recovery after this manual refund needs no channel query.
                    boolean onlyLocalFacts = !facts.isEmpty() && facts.stream().allMatch(f ->
                            CashWithdrawFundFact.DEBIT.equals(f.getKind()) || CashWithdrawFundFact.REFUND.equals(f.getKind()));
                    if (!onlyLocalFacts) {
                        try {
                            requireDebitEvidence(original, facts);
                            CashWithdrawTransferIntent intent = requireOriginalIntent(original, facts);
                            if (originalAttemptNotSent(facts, intent)) {
                                requireRefundEvidence(original);
                                if (Integer.valueOf(MemberCashWithdraw.STATUS_PROCESSING).equals(original.getStatus()))
                                    claimFailedRefund(id, DateUtils.getNowDate(), "原未发送证明完整，保留原号待双人复核", null, null);
                                return null;
                            }
                            return new ChannelQuery(original, intent, originalBatchId(original, facts));
                        } catch (IllegalStateException unprovable) {
                            MemberCashWithdraw investigation = new MemberCashWithdraw();
                            investigation.setId(id); investigation.setStatus(MemberCashWithdraw.STATUS_FAILED_MANUAL);
                            investigation.setManualNote("缺少完整原提交身份/扣款证据，保留原号人工调查；不补造、不换号");
                            mapper.updateStatusIfProcessing(investigation);
                            return null;
                        }
                    }
                    requireDebitEvidence(original, facts);
                    if (Integer.valueOf(MemberCashWithdraw.STATUS_PROCESSING).equals(original.getStatus())) {
                        requireRefundEvidence(original);
                        MemberCashWithdraw manual = new MemberCashWithdraw();
                        manual.setId(id);
                        manual.setStatus(MemberCashWithdraw.STATUS_FAILED_MANUAL);
                        manual.setFailReason("已扣款但未提交渠道，待双人复核回补");
                        manual.setManualNote("原扣款证据完整且无提交意图，保留原CW/CD；不重发转账");
                        if (mapper.updateStatusIfProcessing(manual) != 1)
                            throw new IllegalStateException("提现未提交恢复状态写入失败");
                    }
                    return null;
                });
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

    /** 状态领取与余额回补同事务，失败回滚后才可转人工。 */
    private boolean refundFailedClaim(MemberCashWithdraw withdrawal, Date now) {
        RestoreDispatchGuard.requireDispatchAllowed();
        return Boolean.TRUE.equals(new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> {
                    MemberCashWithdraw original = mapper.selectByIdForUpdate(withdrawal.getId());
                    if (original == null || !Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_MANUAL).equals(original.getStatus()))
                        return false;
                    requireRefundEvidence(original);
                    CashWithdrawFundFact fact = appendFact(original, CashWithdrawFundFact.REFUND);
                    MemberCashWithdraw refunded = new MemberCashWithdraw();
                    refunded.setId(original.getId());
                    refunded.setStatus(MemberCashWithdraw.STATUS_FAILED_REFUNDED);
                    refunded.setBalanceRefundId(fact.getId());
                    refunded.setFinishTime(now);
                    if (mapper.updateStatusIfManual(refunded) != 1)
                        throw new IllegalStateException("提现回补状态写入失败");
                    if (!umsMemberService.addBalance(original.getMemberId(), original.getAmount()))
                        throw new IllegalStateException("提现余额回补失败");
                    return true;
                }));
    }

    private CashWithdrawFundFact appendFact(MemberCashWithdraw w, String kind) {
        return appendFact(w, kind, null, null);
    }

    private CashWithdrawFundFact appendFact(MemberCashWithdraw w, String kind, Map<String, String> observation,
                                           CashWithdrawTransferIntent intent) {
        CashWithdrawFundFact fact = CashWithdrawFundFact.of(w, kind);
        if (intent != null) {
            fact.bindTransferIntent(intent);
            fact.setSubmissionIntentId(intent.submissionIntentId());
        }
        if (observation != null) {
            fact.setChannelBatchId(emptyToNull(observation.get("batchId")));
            fact.setChannelBatchStatus(emptyToNull(observation.get("batchStatus")));
            fact.setChannelDetailStatus(emptyToNull(observation.get("detailStatus")));
            fact.setChannelDetailId(emptyToNull(observation.get("detailId")));
            fact.setChannelErrorCode(emptyToNull(observation.get("failReasonCode")));
            if (fact.getChannelErrorCode() == null)
                fact.setChannelErrorCode(emptyToNull(observation.get("errorCode")));
        }
        if (mapper.insertFundFact(fact) != 1 || fact.getId() == null)
            throw new IllegalStateException("提现资金事实写入失败");
        return fact;
    }

    private List<CashWithdrawFundFact> checkedFacts(MemberCashWithdraw w) {
        if (w.getId() == null || !Integer.valueOf(0).equals(w.getDelFlag())
                || w.getAmount() == null || w.getAmount().signum() <= 0
                || !("CW" + w.getId()).equals(w.getOutBatchNo()) || !("CD" + w.getId()).equals(w.getOutDetailNo()))
            throw new IllegalStateException("提现原身份缺失或不一致，保留调查");
        List<CashWithdrawFundFact> facts = mapper.selectFundFacts(w.getId());
        if (facts == null || facts.stream().anyMatch(f -> !f.matches(w)))
            throw new IllegalStateException("提现资金证据不一致，保留调查");
        return facts;
    }

    private boolean hasFact(List<CashWithdrawFundFact> facts, String kind) {
        return facts.stream().anyMatch(f -> kind.equals(f.getKind()));
    }

    private void requireDebitEvidence(MemberCashWithdraw w, List<CashWithdrawFundFact> facts) {
        boolean debited = facts.stream().anyMatch(f -> CashWithdrawFundFact.DEBIT.equals(f.getKind())
                && f.getId() != null && f.getId().equals(w.getBalanceDeductId())
                && f.getBalanceBefore() != null && f.getBalanceAfter() != null
                && f.getBalanceBefore().subtract(f.getBalanceAfter()).compareTo(w.getAmount()) == 0);
        if (!debited) throw new IllegalStateException("缺少原扣款持久证据，保留调查");
        requireCompatibleSnapshot(w, facts);
    }

    private void requireRefundEvidence(MemberCashWithdraw w) {
        if (Integer.valueOf(1).equals(w.getFrozen()))
            throw new IllegalStateException("该提现单已被冻结，请先解冻");
        List<CashWithdrawFundFact> facts = checkedFacts(w);
        requireDebitEvidence(w, facts);
        if (w.getBalanceRefundId() != null || hasFact(facts, CashWithdrawFundFact.REFUND)
                || hasFact(facts, CashWithdrawFundFact.SUCCESS))
            throw new IllegalStateException("该提现单已回补或渠道已成功，禁止回补");
        CashWithdrawFundFact submission = facts.stream().filter(f -> CashWithdrawFundFact.SUBMIT_INTENT.equals(f.getKind()))
                .findFirst().orElse(null);
        boolean neverSubmitted = submission == null && facts.size() == 1;
        CashWithdrawTransferIntent intent = submission == null ? null : requireOriginalIntent(w, facts);
        boolean originalAttemptNotSent = originalAttemptNotSent(facts, intent);
        boolean finalDetailFailure = intent != null && facts.stream().anyMatch(f -> CashWithdrawFundFact.FINAL_FAIL.equals(f.getKind())
                && "FAIL".equals(f.getChannelDetailStatus()) && "FINISHED".equals(f.getChannelBatchStatus())
                && emptyToNull(f.getChannelBatchId()) != null && emptyToNull(f.getChannelDetailId()) != null
                && boundToIntent(f, intent));
        if (!neverSubmitted && !originalAttemptNotSent && !finalDetailFailure)
            throw new IllegalStateException("渠道结果未确定，禁止回补，保留原号调查");
    }

    private boolean originalAttemptNotSent(List<CashWithdrawFundFact> facts, CashWithdrawTransferIntent intent) {
        return intent != null && !hasFact(facts, CashWithdrawFundFact.UNKNOWN)
                && !hasFact(facts, CashWithdrawFundFact.ACCEPTED)
                && facts.stream().anyMatch(f -> CashWithdrawFundFact.NOT_ACCEPTED.equals(f.getKind())
                    && boundToIntent(f, intent));
    }

    private CashWithdrawTransferIntent recordSubmissionIntent(Long id, CashWithdrawTransferIntent request) {
        RestoreDispatchGuard.requireDispatchAllowed();
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> {
                    MemberCashWithdraw w = mapper.selectByIdForUpdate(id);
                    if (w == null || !Integer.valueOf(MemberCashWithdraw.STATUS_PROCESSING).equals(w.getStatus())
                            || Integer.valueOf(1).equals(w.getFrozen())) return null;
                    List<CashWithdrawFundFact> facts = checkedFacts(w);
                    requireDebitEvidence(w, facts);
                    if (facts.size() != 1 || w.getBalanceRefundId() != null) return null;
                    requireIntentMatchesOriginal(w, request);
                    CashWithdrawTransferIntent snapshot = snapshotIntent(w);
                    if (snapshot == null || !sameWire(request, snapshot))
                        throw new IllegalStateException("新提现缺少同事务原扣款快照，未提交渠道");
                    if (transferIntents.markSubmitted(id, snapshot.requestHash()) != 1) return null;
                    return appendFact(w, CashWithdrawFundFact.SUBMIT_INTENT, null, request).transferIntent();
                });
    }

    /** Channel call already finished; revalidate the captured original intent under the same financial row lock. */
    private boolean recordChannelFact(Long id, String kind, Map<String, String> observation) {
        return recordChannelFact(id, kind, observation, null);
    }

    private boolean recordChannelFact(Long id, String kind, Map<String, String> observation, Long expectedIntentId) {
        return Boolean.TRUE.equals(new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> {
                    MemberCashWithdraw w = mapper.selectByIdForUpdate(id);
                    if (w == null || Integer.valueOf(1).equals(w.getFrozen())) return false;
                    List<CashWithdrawFundFact> facts = checkedFacts(w);
                    CashWithdrawTransferIntent intent = requireOriginalIntent(w, facts);
                    if (expectedIntentId != null && !expectedIntentId.equals(intent.submissionIntentId())) return false;
                    // An in-flight query may finish after the original no-send proof committed.
                    // Its UNKNOWN observation cannot invalidate that proof; contradictory terminals retain the existing investigation path.
                    if (CashWithdrawFundFact.UNKNOWN.equals(kind) && originalAttemptNotSent(facts, intent)) return false;
                    boolean terminal = CashWithdrawFundFact.SUCCESS.equals(kind) || CashWithdrawFundFact.FINAL_FAIL.equals(kind);
                    if (terminal && (expectedIntentId == null || !queryMatchesIntent(intent, observation)
                            || (CashWithdrawFundFact.SUCCESS.equals(kind) && !"SUCCESS".equals(observation.get("result")))
                            || (CashWithdrawFundFact.FINAL_FAIL.equals(kind) && !"FAIL".equals(observation.get("result")))
                            || (originalBatchId(w, facts) != null && !originalBatchId(w, facts).equals(observation.get("batchId"))))) {
                        logger.warn("提现渠道终态未通过原意图锁内核验 id={} intentId={}", id, intent.submissionIntentId());
                        if (!hasFact(facts, CashWithdrawFundFact.UNKNOWN))
                            appendFact(w, CashWithdrawFundFact.UNKNOWN,
                                    Map.of("errorCode", "ORIGINAL_INTENT_MISMATCH"), intent);
                        return false;
                    }
                    if (CashWithdrawFundFact.SUCCESS.equals(kind)
                            && Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_REFUNDED).equals(w.getStatus())) {
                        if (!hasFact(facts, kind)) appendFact(w, kind, observation, intent);
                        logger.error("已回补提现观察到渠道成功，保留冲突证据待调查 id={}", id);
                        return false;
                    }
                    if (!Integer.valueOf(MemberCashWithdraw.STATUS_PROCESSING).equals(w.getStatus())
                            && !Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_MANUAL).equals(w.getStatus())
                            && !Integer.valueOf(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW).equals(w.getStatus())) return false;
                    if (w.getBalanceRefundId() != null || hasFact(facts, CashWithdrawFundFact.REFUND)) return false;
                    if (!CashWithdrawFundFact.SUCCESS.equals(kind) && hasFact(facts, CashWithdrawFundFact.SUCCESS)) return false;
                    if (CashWithdrawFundFact.NOT_ACCEPTED.equals(kind)
                            && (expectedIntentId == null || facts.size() != 2
                                || hasFact(facts, CashWithdrawFundFact.UNKNOWN) || hasFact(facts, CashWithdrawFundFact.ACCEPTED))) return false;
                    CashWithdrawTransferIntent snapshot = snapshotIntent(w);
                    if (snapshot != null && snapshot.locallyRejected() && terminal) {
                        if (!hasFact(facts, CashWithdrawFundFact.UNKNOWN)) {
                            Map<String, String> conflict = new java.util.HashMap<>(observation);
                            conflict.put("errorCode", "SNAPSHOT_CHANNEL_CONFLICT");
                            appendFact(w, CashWithdrawFundFact.UNKNOWN, conflict, intent);
                        }
                        return false;
                    }
                    if (CashWithdrawFundFact.NOT_ACCEPTED.equals(kind) && snapshot != null
                            && transferIntents.markLocallyRejected(id, snapshot.requestHash()) != 1)
                        throw new IllegalStateException("原提现未提交证据写入失败");
                    if (!hasFact(facts, kind)) appendFact(w, kind, observation, intent);
                    if (CashWithdrawFundFact.SUCCESS.equals(kind)) {
                        MemberCashWithdraw success = new MemberCashWithdraw();
                        success.setId(id); success.setStatus(MemberCashWithdraw.STATUS_SUCCESS);
                        success.setWechatBatchStatus(observation.get("batchStatus"));
                        success.setWechatDetailId(emptyToNull(observation.get("detailId")));
                        success.setFinishTime(DateUtils.getNowDate()); success.setLastQueryTime(DateUtils.getNowDate());
                        if (mapper.confirmChannelSuccess(success) != 1)
                            throw new IllegalStateException("提现成功事实与终态写入失败");
                    }
                    return true;
                }));
    }

    private CashWithdrawTransferIntent requireOriginalIntent(MemberCashWithdraw w, List<CashWithdrawFundFact> facts) {
        requireDebitEvidence(w, facts);
        CashWithdrawFundFact stored = facts.stream().filter(f -> CashWithdrawFundFact.SUBMIT_INTENT.equals(f.getKind()))
                .findFirst().orElseThrow(() -> new IllegalStateException("缺少原提交意图，保留调查"));
        try {
            CashWithdrawTransferIntent intent = stored.transferIntent();
            if (intent.submissionIntentId() == null) throw new IllegalArgumentException("缺少原意图关联");
            requireIntentMatchesOriginal(w, intent);
            return intent;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("原提交参数证据缺失或不一致，保留调查");
        }
    }

    private void requireIntentMatchesOriginal(MemberCashWithdraw w, CashWithdrawTransferIntent intent) {
        if (!intent.canonicalRequestHash().equals(intent.requestHash()))
            throw new IllegalStateException("原提交参数指纹不一致");
        if (!w.getId().equals(intent.withdrawalId()) || !w.getOutBatchNo().equals(intent.outBatchNo())
                || !w.getOutDetailNo().equals(intent.outDetailNo()) || w.getOpenid() == null
                || !w.getOpenid().equals(intent.openid()) || w.getAmount().compareTo(intent.amountYuan()) != 0)
            throw new IllegalStateException("原提现与提交参数不一致，保留调查");
    }

    private boolean boundToIntent(CashWithdrawFundFact fact, CashWithdrawTransferIntent intent) {
        try {
            CashWithdrawTransferIntent recorded = fact.transferIntent();
            return intent.submissionIntentId().equals(recorded.submissionIntentId())
                    && intent.withdrawalId().equals(recorded.withdrawalId())
                    && intent.requestHash().equals(recorded.requestHash());
        } catch (IllegalArgumentException incomplete) { return false; }
    }

    private String originalBatchId(MemberCashWithdraw w, List<CashWithdrawFundFact> facts) {
        var batches = facts.stream().filter(f -> CashWithdrawFundFact.ACCEPTED.equals(f.getKind())
                        || CashWithdrawFundFact.SUCCESS.equals(f.getKind()) || CashWithdrawFundFact.FINAL_FAIL.equals(f.getKind()))
                .map(CashWithdrawFundFact::getChannelBatchId).filter(value -> emptyToNull(value) != null).distinct().toList();
        if (batches.size() > 1) throw new IllegalStateException("原渠道批次事实冲突，保留人工调查");
        String known = batches.isEmpty() ? null : batches.get(0);
        String receipt = emptyToNull(w.getTransferBatchId());
        if (known != null && receipt != null && !known.equals(receipt))
            throw new IllegalStateException("原受理批次证据冲突，保留调查");
        return known == null ? receipt : known;
    }

    private boolean queryMatchesIntent(CashWithdrawTransferIntent intent, Map<String, String> observation) {
        if (observation == null || !"FINISHED".equals(observation.get("batchStatus"))
                || emptyToNull(observation.get("batchId")) == null || emptyToNull(observation.get("detailId")) == null
                || !intent.mchid().equals(observation.get("mchid")) || !intent.appid().equals(observation.get("appid"))
                || !intent.outBatchNo().equals(observation.get("outBatchNo"))
                || !intent.outDetailNo().equals(observation.get("outDetailNo"))
                || !intent.openid().equals(observation.get("openid"))) return false;
        String outcome = observation.get("result");
        if (!("SUCCESS".equals(outcome) || "FAIL".equals(outcome)) || !outcome.equals(observation.get("detailStatus"))) return false;
        try { return intent.amountFen() == Long.parseLong(observation.get("transferAmount")); }
        catch (RuntimeException invalidAmount) { return false; }
    }

    private static void requireOutsideTransaction() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("提现渠道操作必须在原资金事务提交后执行");
    }

    private CashWithdrawTransferIntent snapshotIntent(MemberCashWithdraw original) {
        if (transferIntents == null) throw new IllegalStateException("原提现证据读取器未装配");
        CashWithdrawTransferIntent snapshot = transferIntents.selectByWithdrawId(original.getId());
        if (snapshot != null) {
            if (!snapshot.hasDebitSnapshot()) throw new IllegalStateException("原扣款快照来源不符");
            snapshot.requireMatches(original);
        }
        return snapshot;
    }

    private boolean sameWire(CashWithdrawTransferIntent left, CashWithdrawTransferIntent right) {
        return left.withdrawalId().equals(right.withdrawalId()) && left.mchid().equals(right.mchid())
                && left.appid().equals(right.appid()) && left.outBatchNo().equals(right.outBatchNo())
                && left.outDetailNo().equals(right.outDetailNo()) && left.amountFen() == right.amountFen()
                && left.openid().equals(right.openid()) && left.effectiveRemark().equals(right.effectiveRemark())
                && left.requestVersion() == right.requestVersion();
    }

    private void requireCompatibleSnapshot(MemberCashWithdraw original, List<CashWithdrawFundFact> facts) {
        CashWithdrawTransferIntent snapshot = snapshotIntent(original);
        if (snapshot == null) return; // Existing ff2b-only ledger retains its original proof contract.
        CashWithdrawFundFact debit = facts.stream().filter(f -> CashWithdrawFundFact.DEBIT.equals(f.getKind()))
                .findFirst().orElseThrow(() -> new IllegalStateException("两侧原扣款证据缺失"));
        if (snapshot.balanceBefore().compareTo(debit.getBalanceBefore()) != 0
                || snapshot.balanceAfter().compareTo(debit.getBalanceAfter()) != 0)
            throw new IllegalStateException("两侧原扣款快照冲突，保留人工调查");
        CashWithdrawFundFact submission = facts.stream().filter(f -> CashWithdrawFundFact.SUBMIT_INTENT.equals(f.getKind()))
                .findFirst().orElse(null);
        if (submission != null) {
            try {
                if (!snapshot.submitted() || !sameWire(submission.transferIntent(), snapshot))
                    throw new IllegalStateException("两侧原提交证据冲突，保留原号调查");
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException("原提交证据不完整，保留原号调查", invalid);
            }
        }
        if (submission == null && snapshot.submitted())
            throw new IllegalStateException("两侧原提交身份缺失，禁止补造关联");
        if (hasFact(facts, CashWithdrawFundFact.NOT_ACCEPTED) != snapshot.locallyRejected())
            throw new IllegalStateException("两侧原未提交证明冲突，保留人工调查");
        if (snapshot.locallyRejected() && (hasFact(facts, CashWithdrawFundFact.UNKNOWN)
                || hasFact(facts, CashWithdrawFundFact.ACCEPTED) || hasFact(facts, CashWithdrawFundFact.SUCCESS)
                || hasFact(facts, CashWithdrawFundFact.FINAL_FAIL)))
            throw new IllegalStateException("原未提交与渠道事实冲突，保留人工调查");
    }

    private void requireSnapshotOnly(MemberCashWithdraw original, CashWithdrawTransferIntent snapshot) {
        snapshot.requireMatches(original);
        if (original.getBalanceDeductId() != null || original.getBalanceRefundId() != null
                || !checkedFacts(original).isEmpty())
            throw new IllegalStateException("原证据来源冲突，禁止重建资金账本");
        if ((!snapshot.submitted() || snapshot.locallyRejected())
                && (emptyToNull(original.getTransferBatchId()) != null
                    || emptyToNull(original.getWechatDetailId()) != null
                    || emptyToNull(original.getWechatBatchStatus()) != null))
            throw new IllegalStateException("原未提交记录与既有渠道回执冲突，保留原号调查");
    }

    private void requireSameSnapshot(MemberCashWithdraw original, CashWithdrawTransferIntent captured) {
        CashWithdrawTransferIntent current = snapshotIntent(original);
        if (captured == null || current == null || !captured.equals(current))
            throw new IllegalStateException("原扣款快照或提交状态已变化，保留原号调查");
        requireSnapshotOnly(original, current);
    }

    private void requireSnapshotRefund(MemberCashWithdraw original, CashWithdrawTransferIntent intent,
            Map<String, String> facts) {
        requireSnapshotOnly(original, intent);
        if (!intent.submitted() || intent.locallyRejected()) return;
        if (facts == null || !"FAIL".equals(facts.get("result")) || !queryMatchesIntent(intent, facts)
                || (emptyToNull(original.getTransferBatchId()) != null
                    && !original.getTransferBatchId().equals(facts.get("batchId")))
                || (emptyToNull(original.getWechatDetailId()) != null
                    && !original.getWechatDetailId().equals(facts.get("detailId"))))
            throw new IllegalStateException("原渠道尚未确认失败，不能回补");
    }

    /** Original master rows recover under their original snapshot/CAS; no ledger facts are fabricated. */
    private void settleSnapshotObservation(ChannelQuery captured, Map<String, String> facts, Date now) {
        boolean refund = Boolean.TRUE.equals(new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
            MemberCashWithdraw original = mapper.selectByIdForUpdate(captured.withdrawal().getId());
            if (original == null || Integer.valueOf(1).equals(original.getFrozen())) return null;
            requireSameSnapshot(original, captured.intent());
            CashWithdrawTransferIntent intent = captured.intent();
            String result = facts == null ? "UNKNOWN" : facts.get("result");
            boolean terminal = "SUCCESS".equals(result) || "FAIL".equals(result);
            if (terminal && (!queryMatchesIntent(intent, facts)
                    || (captured.batchId() != null && !captured.batchId().equals(facts.get("batchId")))
                    || (emptyToNull(original.getWechatDetailId()) != null
                        && !original.getWechatDetailId().equals(facts.get("detailId"))))) {
                result = "UNKNOWN";
            }
            if ("SUCCESS".equals(result)) {
                if (Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_REFUNDED).equals(original.getStatus())) {
                    Map<String, String> conflict = new java.util.HashMap<>(facts);
                    conflict.put("errorCode", "REFUNDED_SUCCESS_CONFLICT");
                    appendFact(original, CashWithdrawFundFact.UNKNOWN, conflict, intent);
                    logger.error("已回补原提现观察到成功，保留原快照与终态待人工核查 id={}", original.getId());
                    return null;
                }
                MemberCashWithdraw paid = new MemberCashWithdraw();
                paid.setId(original.getId()); paid.setStatus(MemberCashWithdraw.STATUS_SUCCESS);
                paid.setTransferBatchId(facts.get("batchId")); paid.setWechatBatchStatus(facts.get("batchStatus"));
                paid.setWechatDetailId(facts.get("detailId")); paid.setFinishTime(now); paid.setLastQueryTime(now);
                mapper.updateSuccessIfPending(paid);
                return null;
            }
            if ("FAIL".equals(result)) {
                requireSnapshotRefund(original, intent, facts);
                if (RestoreDispatchGuard.isRestoreMode()) {
                    // Retain the original provider observation without inventing ledger DEBIT facts
                    // for this master-only V1 source or claiming a balance refund.
                    MemberCashWithdraw observation = new MemberCashWithdraw();
                    observation.setId(original.getId()); observation.setLastQueryTime(now);
                    observation.setWechatBatchStatus(facts.get("batchStatus"));
                    observation.setWechatDetailId(facts.get("detailId"));
                    mapper.updateReceiptIfProcessing(observation);
                    return null;
                }
                return claimFailedRefund(original.getId(), now, "微信转账失败", facts.get("failReasonCode"), facts.get("detailId"));
            }
            MemberCashWithdraw observation = new MemberCashWithdraw();
            observation.setId(original.getId()); observation.setLastQueryTime(now);
            if (!terminal && facts != null) observation.setWechatBatchStatus(emptyToNull(facts.get("batchStatus")));
            if (mapper.updateReceiptIfProcessing(observation) != 1) return null;
            if (isStuck(original, now)) claimFailedRefund(original.getId(), now,
                    "原提现查询未知，保留原号人工核查", null, null);
            return null;
        }));
        if (!refund) return;
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                MemberCashWithdraw original = mapper.selectByIdForUpdate(captured.withdrawal().getId());
                if (original == null || Integer.valueOf(1).equals(original.getFrozen())
                        || !Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_MANUAL).equals(original.getStatus())) return null;
                requireSameSnapshot(original, captured.intent());
                requireSnapshotRefund(original, captured.intent(), facts);
                MemberCashWithdraw refunded = new MemberCashWithdraw();
                refunded.setId(original.getId()); refunded.setStatus(MemberCashWithdraw.STATUS_FAILED_REFUNDED);
                refunded.setFinishTime(now);
                if (mapper.updateStatusIfManual(refunded) != 1) return null;
                if (!umsMemberService.addBalance(original.getMemberId(), captured.intent().amountYuan()))
                    throw new IllegalStateException("提现余额回补失败");
                return null;
            });
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (RuntimeException failure) {
            noteManualIfStillPending(captured.withdrawal().getId(), "原渠道失败且自动回补未完成，保留人工核查");
            alarmService.alarm("提现待人工", "原快照自动回补失败 id=" + captured.withdrawal().getId());
        }
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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
                || Integer.valueOf(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW).equals(w.getStatus())
                || Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_REFUNDED).equals(w.getStatus()))) {
            syncOne(w);
        }
        return mapper.selectByIdForUpdate(id);
    }

    @Override
    public boolean manualRefund(Long id, String operator, String note) {
        return manualRefund(id, operator, note, null);
    }

    @Override
    public boolean manualRefund(Long id, String operator, String note, Integer expectedStatus) {
        RestoreDispatchGuard.requireDispatchAllowed();
        requireOutsideTransaction();
        MakerCheckerGuard.requireActor(operator, "operator");
        // This unlocked read only chooses the path. The mutation rechecks its source under the original lock.
        MemberCashWithdraw hint = mapper.selectById(id);
        List<CashWithdrawFundFact> hintedFacts = mapper.selectFundFacts(id);
        if (hintedFacts == null) throw new IllegalStateException("提现原资金事实读取未知");
        if (!hintedFacts.isEmpty()) {
            ChannelQuery ledger = new ChannelQuery(hint, null, null);
            return Boolean.TRUE.equals(new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .execute(status -> manualRefundLocked(id, operator, note, expectedStatus, ledger, null)));
        }
        ChannelQuery prepared = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> {
                    MemberCashWithdraw original = mapper.selectByIdForUpdate(id);
                    if (original == null) throw new IllegalStateException("提现单不存在");
                    if (Integer.valueOf(1).equals(original.getFrozen()))
                        throw new IllegalStateException("该提现单已被冻结，请先解冻");
                    if (Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_NO_DEBIT).equals(original.getStatus()))
                        throw new IllegalStateException("该提现单未实际扣款,无需人工回补");
                    if (expectedStatus != null && !expectedStatus.equals(original.getStatus())) return null;
                    if (!Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_MANUAL).equals(original.getStatus())
                            && !Integer.valueOf(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW).equals(original.getStatus()))
                        throw new IllegalStateException("仅『失败待人工/待复核』的提现单可人工补单");
                    if (Integer.valueOf(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW).equals(original.getStatus()))
                        MakerCheckerGuard.requireDistinct(original.getManualOperator(), operator);
                    List<CashWithdrawFundFact> facts = checkedFacts(original);
                    CashWithdrawTransferIntent snapshot = snapshotIntent(original);
                    if (facts.isEmpty() && snapshot != null) {
                        requireSnapshotOnly(original, snapshot);
                        return new ChannelQuery(original, snapshot, emptyToNull(original.getTransferBatchId()));
                    }
                    return new ChannelQuery(original, null, null);
                });
        if (prepared == null) return false;
        final Map<String, String> observed;
        if (prepared.intent() != null && prepared.intent().submitted() && !prepared.intent().locallyRejected()) {
            Map<String, String> facts;
            try { facts = wechatPayService.queryTransferResult(prepared.intent()); }
            catch (RestoreDispatchGuard.Blocked paused) { throw paused; }
            catch (RuntimeException unknown) { facts = Map.of("result", "UNKNOWN"); }
            observed = facts;
            if (observed != null && "SUCCESS".equals(observed.get("result"))) {
                settleSnapshotObservation(prepared, observed, DateUtils.getNowDate());
                return false;
            }
        } else observed = null;
        return Boolean.TRUE.equals(new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> manualRefundLocked(id, operator, note, expectedStatus, prepared, observed)));
    }

    private boolean manualRefundLocked(Long id, String operator, String note, Integer expectedStatus,
            ChannelQuery prepared, Map<String, String> observed) {
        RestoreDispatchGuard.requireDispatchAllowed();
        MakerCheckerGuard.requireActor(operator, "operator");
        MemberCashWithdraw w = mapper.selectByIdForUpdate(id);
        if (w == null) {
            throw new IllegalStateException("提现单不存在");
        }
        // P块冻结:冻结中的单暂停一切处理(含人工),直到运营解冻,避免"冻结了但人工通道还能动钱"。
        if (Integer.valueOf(1).equals(w.getFrozen())) {
            throw new IllegalStateException("该提现单已被冻结,请先解冻:" + safe(w.getFrozenReason()));
        }
        // 未实际扣款(终态 5):原子 UPDATE 影响 0 行,余额从未离开用户账户,无需也不应回补。
        if (Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_NO_DEBIT).equals(w.getStatus())) {
            throw new IllegalStateException("该提现单未实际扣款,无需人工回补");
        }
        if (expectedStatus != null && !expectedStatus.equals(w.getStatus())) {
            return false;
        }
        List<CashWithdrawFundFact> history = checkedFacts(w);
        CashWithdrawTransferIntent snapshot = snapshotIntent(w);
        boolean snapshotOnly = history.isEmpty() && snapshot != null;
        if (snapshotOnly) {
            requireSameSnapshot(w, prepared.intent());
            requireSnapshotRefund(w, snapshot, observed);
        } else {
            if (prepared.intent() != null && prepared.intent().hasDebitSnapshot())
                throw new IllegalStateException("提现证据来源已变化，保留人工调查");
            requireRefundEvidence(w);
        }
        if (Integer.valueOf(MemberCashWithdraw.STATUS_FAILED_MANUAL).equals(w.getStatus())) {
            // 第一人只登记并送复核，绝不移动余额。
            MemberCashWithdraw prepare = new MemberCashWithdraw();
            prepare.setId(id);
            prepare.setStatus(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW);
            prepare.setManualOperator(operator);
            prepare.setManualNote(note == null ? "人工回补待复核" : note);
            return mapper.updateStatusIfManual(prepare) > 0;
        }
        if (!Integer.valueOf(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW).equals(w.getStatus())) {
            throw new IllegalStateException("仅『失败待人工/待复核』的提现单可人工补单");
        }
        MakerCheckerGuard.requireDistinct(w.getManualOperator(), operator);
        CashWithdrawFundFact refundFact = snapshotOnly ? null : appendFact(w, CashWithdrawFundFact.REFUND);
        // 第二人 CAS 抢占 4->2，赢家独占退款，避免并发/重复点击重复回补。
        MemberCashWithdraw claim = new MemberCashWithdraw();
        claim.setId(id);
        claim.setStatus(MemberCashWithdraw.STATUS_FAILED_REFUNDED);
        if (refundFact != null) claim.setBalanceRefundId(refundFact.getId());
        claim.setManualVerifier(operator);
        claim.setManualNote(note == null ? "人工补单双人复核通过" : note);
        claim.setFinishTime(DateUtils.getNowDate());
        claim.setAuditBy(0L);      // 系统自动操作(manualRefund 由后台触发)
        claim.setAuditTime(DateUtils.getNowDate());
        int n = mapper.updateStatusIfRefundReview(claim);
        if (n == 0) {
            throw new IllegalStateException("提现回补状态已变化");
        }
        // Let any failure escape the transactional boundary: receipt, fact and balance roll back together.
        if (!umsMemberService.addBalance(w.getMemberId(), w.getAmount()))
            throw new IllegalStateException("提现余额回补失败");
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
