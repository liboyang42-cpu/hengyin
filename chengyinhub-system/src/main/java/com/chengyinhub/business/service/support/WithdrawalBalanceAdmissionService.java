package com.chengyinhub.business.service.support;

import com.chengyinhub.common.config.RestoreDispatchGuard;

import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.domain.CashWithdrawFundFact;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

/** Short financial admission only. No channel/provider work while balance or freeze rows are locked. */
@Service
public class WithdrawalBalanceAdmissionService {
    private final UmsMemberMapper members;
    private final com.chengyinhub.business.mapper.MemberCashWithdrawMapper cash;
    private final WithdrawalFrozenAmountResolver frozen;

    private final com.chengyinhub.business.mapper.CashWithdrawTransferIntentMapper transferIntents;

    /** Existing bank-only fixtures need no transfer channel; cash admission requires the full constructor. */
    public WithdrawalBalanceAdmissionService(UmsMemberMapper members, WithdrawalFrozenAmountResolver frozen,
            com.chengyinhub.business.mapper.MemberCashWithdrawMapper cash) {
        this(members, frozen, cash, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WithdrawalBalanceAdmissionService(UmsMemberMapper members, WithdrawalFrozenAmountResolver frozen,
            com.chengyinhub.business.mapper.MemberCashWithdrawMapper cash,
            com.chengyinhub.business.mapper.CashWithdrawTransferIntentMapper transferIntents) {
        this.members = members; this.cash = cash;
        this.frozen = frozen; this.transferIntents = transferIntents;
    }

    /** Bank intent is already claimed by the caller's transaction. */
    @Transactional(rollbackFor = Exception.class)
    public Admission debitBank(Long memberId, BigDecimal amount) { return debit(memberId, amount, false); }

    /** Original cash identity is already committed; finish this transaction before calling the channel. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Admission debitCash(Long id, Long memberId, BigDecimal amount) {
        RestoreDispatchGuard.requireDispatchAllowed();
        return debitCash(id, memberId, amount, null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Admission debitCash(Long id, Long memberId, BigDecimal amount, String mchid, String appid, String remark) {
        RestoreDispatchGuard.requireDispatchAllowed();
        com.chengyinhub.business.domain.MemberCashWithdraw original = cash.selectByIdForUpdate(id);
        if (original == null || memberId == null || !memberId.equals(original.getMemberId())
                || amount == null || original.getAmount() == null || amount.compareTo(original.getAmount()) != 0
                || !Integer.valueOf(0).equals(original.getDelFlag())
                || !Integer.valueOf(com.chengyinhub.business.domain.MemberCashWithdraw.STATUS_PROCESSING).equals(original.getStatus())
                || original.getBalanceRefundId() != null
                || !("CW" + id).equals(original.getOutBatchNo()) || !("CD" + id).equals(original.getOutDetailNo())
                || Integer.valueOf(1).equals(original.getFrozen()))
            return new Admission(null, null, null, false, false);
        var facts = cash.selectFundFacts(id);
        if (facts == null) throw new IllegalStateException("提现原扣款证据读取未知");
        var existing = transferIntents == null ? null : transferIntents.selectByWithdrawId(id);
        if (existing != null) {
            existing.requireMatches(original);
            if (!facts.isEmpty() && facts.stream().noneMatch(f -> CashWithdrawFundFact.DEBIT.equals(f.getKind())
                    && f.getId() != null && f.getId().equals(original.getBalanceDeductId())
                    && f.getBalanceBefore() != null && f.getBalanceAfter() != null
                    && existing.balanceBefore().compareTo(f.getBalanceBefore()) == 0
                    && existing.balanceAfter().compareTo(f.getBalanceAfter()) == 0))
                throw new IllegalStateException("两侧原扣款证据冲突，禁止重复准入");
            // An existing debit is recovery-only. Never admit another debit or dispatch from this request.
            return new Admission(existing.balanceBefore(), existing.balanceAfter(), null, true, false);
        }
        if (original.getBalanceDeductId() != null || !facts.isEmpty())
            return new Admission(null, null, null, false, false);
        if (transferIntents == null || mchid == null || mchid.isBlank() || appid == null || appid.isBlank())
            throw new IllegalStateException("提现原渠道/扣款意图装配缺失，未扣款");
        Admission result = debit(memberId, amount, true);
        if (result.deducted) {
            CashWithdrawFundFact fact = CashWithdrawFundFact.of(original, CashWithdrawFundFact.DEBIT);
            fact.setBalanceBefore(result.before); fact.setBalanceAfter(result.after);
            if (cash.insertFundFact(fact) != 1 || fact.getId() == null)
                throw new IllegalStateException("提现扣款证据写入失败");
            original.setBalanceDeductId(fact.getId());
            if (cash.bindDebitEvidence(original) != 1)
                throw new IllegalStateException("提现扣款证据关联失败");
            var intent = com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent.create(
                    original, mchid, appid, remark, result.before, result.after);
            if (transferIntents.insert(intent) != 1)
                throw new IllegalStateException("提现原始扣款意图写入失败");
        } else {
            com.chengyinhub.business.domain.MemberCashWithdraw failed = new com.chengyinhub.business.domain.MemberCashWithdraw();
            failed.setId(id);
            failed.setStatus(com.chengyinhub.business.domain.MemberCashWithdraw.STATUS_FAILED_NO_DEBIT);
            failed.setFailReason("原子扣款未执行(余额不足或并发)");
            failed.setManualNote("原子 UPDATE 0 行:未扣款,无需回补;outBatchNo=CW" + id + " outDetailNo=CD" + id);
            failed.setFinishTime(new java.util.Date()); failed.setAuditBy(0L); failed.setAuditTime(new java.util.Date());
            if (cash.updateMemberCashWithdraw(failed) != 1)
                throw new IllegalStateException("未扣款终态写入失败");
        }
        return result;
    }

    private Admission debit(Long memberId, BigDecimal amount, boolean cashLeg) {
        RestoreDispatchGuard.requireDispatchAllowed();
        if (memberId == null || amount == null || amount.signum() <= 0)
            throw new IllegalArgumentException("Invalid withdrawal admission");
        BigDecimal before = members.selectBalanceForUpdate(memberId);
        if (before == null) throw new IllegalStateException("用户不存在");
        WithdrawalFrozenAmountResolver.Frozen snapshot = frozen.resolveForWithdrawal(memberId);
        // Preserve the cash leg's existing IUmsMemberService negative-floor rejection.
        if (cashLeg && snapshot.total().signum() < 0)
            throw new IllegalArgumentException("参数错误：冻结额不能为负");
        if (before.subtract(snapshot.total()).compareTo(amount) < 0)
            return new Admission(before, before, snapshot, false);
        boolean deducted = members.deductWithdrawableBalance(memberId, amount, snapshot.total()) > 0;
        return new Admission(before, deducted ? before.subtract(amount) : before, snapshot, deducted);
    }

    public static final class Admission {
        public final BigDecimal before;
        public final BigDecimal after;
        public final WithdrawalFrozenAmountResolver.Frozen frozen;
        public final boolean deducted;
        public final boolean currentRequestEligible;
        public Admission(BigDecimal before, BigDecimal after, WithdrawalFrozenAmountResolver.Frozen frozen, boolean deducted) {
            this(before, after, frozen, deducted, true);
        }
        public Admission(BigDecimal before, BigDecimal after, WithdrawalFrozenAmountResolver.Frozen frozen, boolean deducted, boolean eligible) {
            this.before = before; this.after = after; this.frozen = frozen; this.deducted = deducted;
            this.currentRequestEligible = eligible;
        }
    }
}
