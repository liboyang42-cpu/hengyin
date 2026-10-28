package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.service.WechatPaymentOperations;
import com.chengyinhub.business.service.RefundRequestResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** 保证金原路退款执行：意图先提交，受理只记处理中，渠道终态才记已退。 */
@Service
public class CoopDepositRefundService {
    @Autowired private CoopInviteMapper mapper;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("wechatPaymentSdkGateway")
    private WechatPaymentOperations wechatPayService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AlarmService alarmService;
    @Autowired private WechatVerifiedPaymentFactService verifiedPaymentFacts;

    /** 调用方必须已在业务事务内锁定邀约并完成退款资格校验。 */
    public void requestLocked(CoopInvite invite) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("保证金退款意图必须与业务事务一起提交");
        }
        if (invite == null || invite.getId() == null || invite.getDepositStatus() == null) {
            throw new IllegalStateException("保证金记录无法确认");
        }
        if (Integer.valueOf(4).equals(invite.getDepositStatus())) {
            afterCommit(invite.getId()); // 历史4只查，不会建立可派发意图。
            return;
        }
        if (!Integer.valueOf(2).equals(invite.getDepositStatus())) return;
        if (invite.getDepositRefundState() == null) {
            if (blank(invite.getDepositOutTradeNo()) || !positive(invite.getDepositAmount())) {
                throw new IllegalStateException("保证金原支付事实不完整，请人工核对");
            }
            String refundNo = "R" + invite.getDepositOutTradeNo();
            if (mapper.beginDepositRefund(invite.getId(), invite.getDepositOutTradeNo(),
                    refundNo, invite.getDepositAmount()) != 1) {
                throw new IllegalStateException("保证金退款领取失败，状态或原支付事实已变化");
            }
        }
        afterCommit(invite.getId());
    }

    private void afterCommit(final Long inviteId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) return;
                // Only this post-physical-commit callback may detach the completed transaction's resources.
                // Never use this boundary to suspend an uncommitted business owner/lock.
                TransactionTemplate detached = new TransactionTemplate(transactionManager);
                detached.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
                try { detached.executeWithoutResult(ignored -> refresh(inviteId, true)); }
                catch (Exception e) { alarm(inviteId, "退款派发失败，持久意图待对账恢复"); }
            }
        });
    }

    /** 只在独立短事务领取派发权，微信调用发生在领取提交之后。 */
    public void refresh(Long inviteId, boolean force) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("保证金退款查证不能悬挂未提交的业务事务");
        if (com.chengyinhub.common.config.RestoreDispatchGuard.isRestoreMode()) {
            // No dispatch lease, new refund identity or synthetic failure on a restored row.
            CoopInvite original = newTransaction().execute(status -> mapper.selectDepositFactByIdForUpdate(inviteId));
            if (original != null && linked(original) && !blank(original.getDepositRefundNo())) {
                Map<String, String> observed = wechatPayService.queryRefundFacts(original.getDepositRefundNo());
                if (observed != null) handle(observed);
            }
            return;
        }
        String token = UUID.randomUUID().toString();
        CoopInvite owned = newTransaction().execute(status -> {
            if (mapper.claimDepositRefundDispatch(inviteId, token, force) != 1) return null;
            CoopInvite row = mapper.selectDepositFactByIdForUpdate(inviteId);
            if (row == null || !token.equals(row.getDepositRefundLeaseToken())) {
                throw new IllegalStateException("保证金退款领取后无法确认所有权");
            }
            return row;
        });
        if (owned == null) return;
        boolean legacy = Integer.valueOf(4).equals(owned.getDepositStatus());
        boolean archived = !Integer.valueOf(0).equals(owned.getDelFlag());
        if (!linked(owned)) {
            finish(owned, token, legacy && !archived ? "LEGACY_UNCONFIRMED" : "MANUAL");
            alarm(inviteId, "退款号/支付号/金额缺失或不匹配，请人工核对");
            return;
        }
        Map<String, String> facts = wechatPayService.queryRefundFacts(owned.getDepositRefundNo());
        String state = facts == null ? null : facts.get("status");
        if ("SUCCESS".equals(state) || "PROCESSING".equals(state)
                || "CLOSED".equals(state) || "ABNORMAL".equals(state)) {
            if (!owned.getDepositRefundNo().equals(facts.get("outRefundNo"))
                    || !owned.getDepositOutTradeNo().equals(facts.get("outTradeNo")) || !handle(facts)) {
                finish(owned, token, legacy && !archived ? "LEGACY_UNCONFIRMED" : "MANUAL");
                alarm(inviteId, "退款渠道事实与原单不一致");
            }
            return;
        }
        if (archived) {
            finish(owned, token, "MANUAL");
            alarm(inviteId, "历史归档保证金仅查原退款，结果需人工核验，不重新派发");
            return;
        }
        if (legacy) {
            finish(owned, token, "LEGACY_UNCONFIRMED");
            alarm(inviteId, "历史已退记录没有渠道成功证据，只查不发");
            return;
        }
        if (!"NOT_FOUND".equals(state) || "PROCESSING".equals(owned.getDepositRefundState()) || "MANUAL".equals(owned.getDepositRefundState())) {
            finish(owned, token, "PROCESSING".equals(owned.getDepositRefundState()) ? "PROCESSING"
                    : "MANUAL".equals(owned.getDepositRefundState()) ? "MANUAL" : "UNKNOWN");
            alarm(inviteId, "原路退款结果未知，等待按原退款号对账");
            return;
        }
        // Re-read the original consumption/lease under the same invite lock before freezing SDK inputs.
        // Original paid TX + business number select the immutable fact, never the current alias.
        RefundDispatch dispatch;
        try { dispatch = newTransaction().execute(status -> prepareDispatch(owned, token)); }
        catch (OriginalPaymentUnconfirmed missing) {
            // Missing proof is not a definitive provider failure; retain the same reconcilable intent.
            finish(owned, token, "UNKNOWN");
            alarm(inviteId, "保证金原成功消费证据缺失或冲突，保留原退款号人工核验");
            return;
        }
        if (dispatch == null) return; // Callback/another lease won; no external submission.
        // 只有渠道明确退款单不存在才允许同号恢复；超时/null 不等于不存在。
        RefundRequestResult result;
        try {
            result = wechatPayService.submitRefund(dispatch.originalPayment(), dispatch.outTradeNo(),
                    dispatch.refundNo(), dispatch.refundAmount(), dispatch.totalAmount(), "合作保证金退款");
        } catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (Exception e) { result = RefundRequestResult.UNKNOWN; }
        String next = result == RefundRequestResult.ACCEPTED ? "PROCESSING"
                : result == RefundRequestResult.DEFINITIVE_FAILED ? "MANUAL" : "UNKNOWN";
        finish(owned, token, next);
        if (!"PROCESSING".equals(next)) alarm(inviteId, "原路退款" + next + "，请核对渠道结果");
    }

    private record RefundDispatch(WechatVerifiedPaymentFact.Reference originalPayment,
            String outTradeNo, String refundNo, BigDecimal refundAmount, BigDecimal totalAmount) { }
    private static final class OriginalPaymentUnconfirmed extends IllegalStateException { }

    /** No SDK/current-attempt read. The caller's short transaction owns this exact invite/lease. */
    private RefundDispatch prepareDispatch(CoopInvite observed, String token) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        CoopInvite current = mapper.selectDepositFactByIdForUpdate(observed.getId());
        if (current == null || !token.equals(current.getDepositRefundLeaseToken())
                || !Integer.valueOf(2).equals(current.getDepositStatus())
                || !Integer.valueOf(0).equals(current.getDelFlag())
                || !("DISPATCHING".equals(current.getDepositRefundState())
                    || "UNKNOWN".equals(current.getDepositRefundState()))) return null;
        if (!linked(current) || !java.util.Objects.equals(observed.getDepositOutTradeNo(), current.getDepositOutTradeNo())
                || !java.util.Objects.equals(observed.getDepositRefundNo(), current.getDepositRefundNo())
                || !sameAmount(observed.getDepositRefundAmount(), current.getDepositRefundAmount())
                || !sameAmount(observed.getDepositAmount(), current.getDepositAmount())
                || !java.util.Objects.equals(observed.getDepositPaidTransactionId(), current.getDepositPaidTransactionId())
                || blank(current.getDepositPaidTransactionId())) throw new OriginalPaymentUnconfirmed();
        var matches = mapper.selectDepositPaidFactReferences(current.getDepositOutTradeNo(), current.getDepositPaidTransactionId());
        if (matches == null || matches.size() != 1 || !(matches.get(0).get("id") instanceof Number id)
                || id.longValue() <= 0 || !(matches.get(0).get("fact_hash") instanceof String hash)
                || !hash.matches("[0-9a-f]{64}")) throw new OriginalPaymentUnconfirmed();
        WechatVerifiedPaymentFactService.Stored original;
        try { original = verifiedPaymentFacts.requireVerified(new WechatVerifiedPaymentFact.Reference(id.longValue(), hash)); }
        catch (WechatVerifiedPaymentFactService.ConflictException conflict) { throw new OriginalPaymentUnconfirmed(); }
        var paid = original.facts();
        if (paid.kind() != WechatVerifiedPaymentFact.Kind.PAYMENT || !"CNY".equals(paid.currency())
                || !current.getDepositOutTradeNo().equals(paid.businessOutTradeNo())
                || !current.getDepositPaidTransactionId().equals(paid.transactionId())
                || paid.amountFen() != paid.totalFen()) throw new OriginalPaymentUnconfirmed();
        BigDecimal total = BigDecimal.valueOf(paid.totalFen(), 2);
        if (!sameAmount(current.getDepositAmount(), total) || !positive(current.getDepositRefundAmount())
                || current.getDepositRefundAmount().compareTo(total) > 0) throw new OriginalPaymentUnconfirmed();
        try { current.getDepositRefundAmount().movePointRight(2).longValueExact(); }
        catch (ArithmeticException invalid) { throw new OriginalPaymentUnconfirmed(); }
        if (mapper.renewDepositRefundDispatch(current.getId(), token) != 1) return null;
        return new RefundDispatch(original.reference(), current.getDepositOutTradeNo(), current.getDepositRefundNo(),
                current.getDepositRefundAmount(), total);
    }

    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    /** 验签回调与主动查单共用；不接受只凭前缀或只凭状态的资金终态。 */
    public boolean handle(Map<String, String> facts) {
        if (facts == null || blank(facts.get("outRefundNo"))) return false;
        String refundNo = facts.get("outRefundNo");
        Boolean handled = newTransaction().execute(status -> {
            CoopInvite found = mapper.selectByDepositRefundNo(refundNo);
            if (found == null) return false;
            CoopInvite row = mapper.selectDepositFactByIdForUpdate(found.getId());
            if (!linked(row) || !refundNo.equals(row.getDepositRefundNo())
                    || !row.getDepositOutTradeNo().equals(facts.get("outTradeNo"))
                    || !"CNY".equals(facts.get("currency"))) return false;
            BigDecimal expected = row.getDepositRefundAmount() == null
                    ? row.getDepositAmount() : row.getDepositRefundAmount();
            try {
                long cents = expected.movePointRight(2).longValueExact();
                long originalCents = row.getDepositAmount().movePointRight(2).longValueExact();
                if (cents <= 0 || originalCents <= 0 || cents > originalCents
                        || cents != Long.parseLong(facts.get("refund"))
                        || originalCents != Long.parseLong(facts.get("total"))) return false;
            } catch (RuntimeException e) { return false; }
            if (!Integer.valueOf(2).equals(row.getDepositStatus())
                    && !Integer.valueOf(4).equals(row.getDepositStatus())) return false;
            String channel = facts.get("status");
            String next = "SUCCESS".equals(channel) ? "SUCCESS"
                    : "PROCESSING".equals(channel) ? "PROCESSING"
                    : ("CLOSED".equals(channel) || "ABNORMAL".equals(channel)) ? "MANUAL" : null;
            if (next == null) return false;
            if ("SUCCESS".equals(row.getDepositRefundState())) return true;
            if ("PROCESSING".equals(next) && "MANUAL".equals(row.getDepositRefundState())) return true;
            // The gateway has retained a verified success fact. Pause local economic consumption,
            // leaving the original state/number available for the original recovery flow.
            if (com.chengyinhub.common.config.RestoreDispatchGuard.isRestoreMode()) return false;
            if (mapper.confirmDepositRefundResult(row.getId(), row.getDepositOutTradeNo(), refundNo,
                    expected, next) != 1) throw new IllegalStateException("保证金退款终态写入失败");
            return true;
        });
        if (Boolean.TRUE.equals(handled) && ("CLOSED".equals(facts.get("status"))
                || "ABNORMAL".equals(facts.get("status")))) alarm(null, "退款渠道异常 " + refundNo);
        return Boolean.TRUE.equals(handled);
    }

    public void reconcile() {
        IllegalStateException failure = null;
        for (CoopInvite row : mapper.selectDepositsForRefundReconcile()) {
            try { refresh(row.getId(), false); }
            catch (Exception e) {
                alarm(row.getId(), "退款对账失败，保留原执行状态");
                if (failure == null) failure = new IllegalStateException("保证金退款对账存在技术失败");
                failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    private void finish(CoopInvite row, String token, String state) {
        newTransaction().execute(status -> {
            if (mapper.finishDepositRefundDispatch(row.getId(), token, state) == 1) return true;
            CoopInvite current = mapper.selectDepositFactByIdForUpdate(row.getId());
            if (current == null) throw new IllegalStateException("保证金退款回写后记录缺失");
            // 回调先到或另一个租约接管；迟到结果不能覆盖它们。
            if ("SUCCESS".equals(current.getDepositRefundState())
                    || !token.equals(current.getDepositRefundLeaseToken())) return true;
            throw new IllegalStateException("保证金退款执行状态回写失败");
        });
    }
    private TransactionTemplate newTransaction() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }
    private static boolean linked(CoopInvite row) {
        return row != null && !blank(row.getDepositOutTradeNo()) && !blank(row.getDepositRefundNo())
                && row.getDepositRefundNo().equals("R" + row.getDepositOutTradeNo())
                && positive(row.getDepositRefundAmount() == null ? row.getDepositAmount() : row.getDepositRefundAmount());
    }
    private static boolean positive(BigDecimal n) { return n != null && n.signum() > 0; }
    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private void alarm(Long id, String message) {
        try { alarmService.alarm("合作保证金退款待处理", "inviteId=" + id + " " + message); }
        catch (Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).error("保证金退款告警失败 inviteId={}", id, e); }
    }
}
