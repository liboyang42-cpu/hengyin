package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CashWithdrawFundFact;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.mapper.MemberCashWithdrawMapper;
import org.mockito.invocation.InvocationOnMock;

import java.math.BigDecimal;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicit in-memory ledger adapter for legacy cash unit tests. No transaction/SQL claims. */
public final class CashWithdrawEvidenceFixture {
    private final MemberCashWithdrawMapper mapper;
    private final Map<Long, MemberCashWithdraw> rows = new HashMap<>();
    private final Map<Long, List<CashWithdrawFundFact>> facts = new HashMap<>();
    private long nextFact = 10000;

    public CashWithdrawEvidenceFixture(MemberCashWithdrawMapper mapper) {
        this.mapper = mapper;
        doAnswer(i -> row(i.getArgument(0))).when(mapper).selectByIdForUpdate(anyLong());
        doAnswer(i -> facts.computeIfAbsent(i.getArgument(0), k -> new ArrayList<>())).when(mapper).selectFundFacts(anyLong());
        doAnswer(i -> {
            CashWithdrawFundFact f = i.getArgument(0); f.setId(++nextFact);
            facts.computeIfAbsent(f.getWithdrawalId(), k -> new ArrayList<>()).add(f); return 1;
        }).when(mapper).insertFundFact(any());
        doAnswer(i -> {
            MemberCashWithdraw w = i.getArgument(0); row(w.getId()).setBalanceDeductId(w.getBalanceDeductId()); return 1;
        }).when(mapper).bindDebitEvidence(any());
        doAnswer(this::update).when(mapper).updateMemberCashWithdraw(any());
        doAnswer(this::processing).when(mapper).updateStatusIfProcessing(any());
        doAnswer(this::manual).when(mapper).updateStatusIfManual(any());
        doAnswer(this::review).when(mapper).updateStatusIfRefundReview(any());
        doAnswer(this::update).when(mapper).confirmChannelSuccess(any());
    }
    private MemberCashWithdraw row(Long id) {
        return rows.computeIfAbsent(id, mapper::selectById);
    }
    public int update(InvocationOnMock invocation) {
        MemberCashWithdraw change = invocation.getArgument(0);
        if (change == null) return 0;
        MemberCashWithdraw current = row(change.getId());
        if (current == null) return 0;
        if (change.getOutBatchNo() != null) current.setOutBatchNo(change.getOutBatchNo());
        if (change.getOutDetailNo() != null) current.setOutDetailNo(change.getOutDetailNo());
        if (change.getStatus() != null) current.setStatus(change.getStatus());
        if (change.getBalanceRefundId() != null) current.setBalanceRefundId(change.getBalanceRefundId());
        if (change.getManualOperator() != null) current.setManualOperator(change.getManualOperator());
        return 1;
    }
    public int processing(InvocationOnMock i) { return guarded(i, 0); }
    public int manual(InvocationOnMock i) { return guarded(i, 3); }
    public int review(InvocationOnMock i) { return guarded(i, 4); }
    private int guarded(InvocationOnMock i, int expected) {
        MemberCashWithdraw change = i.getArgument(0);
        if (change == null) return 0;
        MemberCashWithdraw current = row(change.getId());
        return current != null && Integer.valueOf(expected).equals(current.getStatus()) ? update(i) : 0;
    }
    /** A test explicitly declares a committed historical debit, using the actual original identity. */
    public void seedDebit(Long id) {
        MemberCashWithdraw w = row(id);
        if (w == null || w.getBalanceDeductId() != null) return;
        CashWithdrawFundFact f = CashWithdrawFundFact.of(w, CashWithdrawFundFact.DEBIT);
        f.setId(++nextFact); f.setBalanceBefore(new BigDecimal("100.00"));
        f.setBalanceAfter(f.getBalanceBefore().subtract(w.getAmount()));
        w.setBalanceDeductId(f.getId()); facts.computeIfAbsent(id, k -> new ArrayList<>()).add(f);
    }
    /** Channel-query scenarios must explicitly include a committed original submission intent. */
    public void seedSubmissionIntent(Long id) {
        seedDebit(id);
        List<CashWithdrawFundFact> history = facts.get(id);
        if (history.stream().anyMatch(f -> CashWithdrawFundFact.SUBMIT_INTENT.equals(f.getKind()))) return;
        CashWithdrawFundFact intent = CashWithdrawFundFact.of(row(id), CashWithdrawFundFact.SUBMIT_INTENT);
        MemberCashWithdraw original = row(id);
        intent.bindTransferIntent(CashWithdrawTransferIntent.fromRequest(id, "unit-merchant", "wx-mp-appid",
                original.getOutBatchNo(), original.getOutDetailNo(), original.getAmount(), original.getOpenid(), "城瘾分润提现"));
        intent.setId(++nextFact); history.add(intent);
    }
}
