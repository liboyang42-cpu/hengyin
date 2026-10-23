package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CashWithdrawFundFact;
import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.mapper.MemberCashWithdrawMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWechatPayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit contract tests only. Actual transaction/lock/uniqueness claims require the MySQL IT. */
class CashWithdrawFundEvidenceTest {
    private CashWithdrawServiceImpl service;
    private MemberCashWithdrawMapper mapper;
    private IUmsMemberService members;
    private IWechatPayService channel;
    private MemberCashWithdraw original;
    private List<CashWithdrawFundFact> facts;

    @BeforeEach void setup() {
        mapper = mock(MemberCashWithdrawMapper.class);
        members = mock(IUmsMemberService.class);
        channel = mock(IWechatPayService.class);
        service = new CashWithdrawServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "umsMemberService", members);
        ReflectionTestUtils.setField(service, "wechatPayService", channel);
        ReflectionTestUtils.setField(service, "alarmService", mock(AlarmService.class));
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        ReflectionTestUtils.setField(service, "transactionManager", tx);
        original = new MemberCashWithdraw();
        original.setId(81L); original.setMemberId(9L); original.setAmount(new BigDecimal("30.00"));
        original.setOutBatchNo("CW81"); original.setOutDetailNo("CD81"); original.setDelFlag(0);
        original.setStatus(MemberCashWithdraw.STATUS_FAILED_MANUAL);
        facts = new ArrayList<>();
        when(mapper.selectById(81L)).thenAnswer(i -> original);
        when(mapper.selectByIdForUpdate(81L)).thenAnswer(i -> original);
        when(mapper.selectFundFacts(81L)).thenAnswer(i -> facts);
        when(mapper.insertFundFact(any())).thenAnswer(i -> {
            CashWithdrawFundFact fact = i.getArgument(0); fact.setId(100L + facts.size()); facts.add(fact); return 1;
        });
        when(mapper.updateStatusIfManual(any())).thenAnswer(i -> apply(i.getArgument(0)));
        when(mapper.updateStatusIfRefundReview(any())).thenAnswer(i -> apply(i.getArgument(0)));
        when(mapper.confirmChannelSuccess(any())).thenAnswer(i -> apply(i.getArgument(0)));
        when(members.addBalance(9L, original.getAmount())).thenReturn(true);
    }

    private int apply(MemberCashWithdraw update) {
        if (update.getStatus() != null) original.setStatus(update.getStatus());
        if (update.getManualOperator() != null) original.setManualOperator(update.getManualOperator());
        if (update.getBalanceRefundId() != null) original.setBalanceRefundId(update.getBalanceRefundId());
        return 1;
    }
    private void fact(String kind) {
        CashWithdrawFundFact f = CashWithdrawFundFact.of(original, kind); f.setId(100L + facts.size());
        if (CashWithdrawFundFact.DEBIT.equals(kind)) {
            f.setBalanceBefore(new BigDecimal("100.00")); f.setBalanceAfter(new BigDecimal("70.00"));
            original.setBalanceDeductId(f.getId());
        }
        if (CashWithdrawFundFact.NOT_ACCEPTED.equals(kind))
            f.setSubmissionIntentId(facts.stream().filter(v -> "SUBMIT_INTENT".equals(v.getKind()))
                    .map(CashWithdrawFundFact::getId).findFirst().orElse(null));
        if (CashWithdrawFundFact.FINAL_FAIL.equals(kind)) f.setChannelDetailStatus("FAIL");
        facts.add(f);
    }
    private void rejected() {
        assertThrows(IllegalStateException.class, () -> service.manualRefund(81L, "maker", "查证", 3));
        verify(mapper, never()).updateStatusIfManual(any());
        verify(mapper, never()).insertFundFact(any());
        verify(members, never()).addBalance(any(), any());
    }

    @Test void workflowStatusWithoutDebitProofCannotRefund() { rejected(); }
    @Test void fabricatedDebitPointerCannotRefund() { original.setBalanceDeductId(123L); rejected(); }
    @Test void processingOrUnknownSubmissionCannotRefund() { fact("DEBIT"); fact("SUBMIT_INTENT"); rejected(); }
    @Test void mismatchedMemberCannotRefund() { fact("DEBIT"); facts.get(0).setMemberId(10L); rejected(); }
    @Test void mismatchedAmountCannotRefund() { fact("DEBIT"); facts.get(0).setAmount(BigDecimal.ONE); rejected(); }
    @Test void mismatchedOriginalNumberCannotRefund() { fact("DEBIT"); original.setOutBatchNo("CW82"); rejected(); }
    @Test void frozenEvidenceCannotRefund() { fact("DEBIT"); original.setFrozen(1); rejected(); }
    @Test void lateSuccessBlocksRefundEvenWithEarlierFailure() {
        fact("DEBIT"); fact("SUBMIT_INTENT"); fact("FINAL_FAIL"); fact("SUCCESS"); rejected();
    }
    @Test void committedRefundBlocksAnotherCredit() {
        fact("DEBIT"); fact("REFUND"); original.setBalanceRefundId(101L); rejected();
    }
    @Test void neverSubmittedDebitWithLostCommitReplyCanBeRefundedAfterTwoPeople() {
        fact("DEBIT");
        assertTrue(service.manualRefund(81L, "maker", "提交回包丢失后已查证", 3));
        verify(members, never()).addBalance(any(), any());
        assertThrows(IllegalStateException.class, () -> service.manualRefund(81L, "maker", "同人", 4));
        assertTrue(service.manualRefund(81L, "checker", "复核", 4));
        assertFalse(service.manualRefund(81L, "other", "重复", 4));
        verify(members, times(1)).addBalance(9L, new BigDecimal("30.00"));
        assertEquals(2, original.getStatus()); assertNotNull(original.getBalanceRefundId());
        assertEquals(1, facts.stream().filter(f -> "REFUND".equals(f.getKind())).count());
    }
    @Test void definiteNonAcceptanceAllowsFirstRefund() {
        fact("DEBIT"); fact("SUBMIT_INTENT"); fact("NOT_ACCEPTED");
        assertTrue(service.manualRefund(81L, "maker", "确定未受理", 3));
        assertTrue(service.manualRefund(81L, "checker", "复核", 4));
        verify(members).addBalance(9L, new BigDecimal("30.00"));
    }
    @Test void acceptedThenFinalFailureAllowsFirstRefund() {
        fact("DEBIT"); fact("SUBMIT_INTENT"); fact("FINAL_FAIL");
        assertTrue(service.manualRefund(81L, "maker", "最终失败", 3));
        assertTrue(service.manualRefund(81L, "checker", "复核", 4));
        verify(members).addBalance(9L, new BigDecimal("30.00"));
    }
    @Test void balanceWriteFailureEscapesTransactionalBoundary() {
        fact("DEBIT"); original.setStatus(4); original.setManualOperator("maker");
        when(members.addBalance(any(), any())).thenThrow(new IllegalStateException("write failed"));
        assertThrows(IllegalStateException.class, () -> service.manualRefund(81L, "checker", "复核", 4));
        verify(mapper, never()).updateMemberCashWithdraw(any());
        // This unit test proves exception propagation only. MySQL IT must prove rollback.
    }
    @Test void oneNotFoundDuringInvestigationDoesNotCreateRefundPermission() {
        fact("DEBIT"); fact("SUBMIT_INTENT");
        when(channel.queryTransferResult("CW81", "CD81")).thenReturn(Map.of("result", "UNKNOWN", "error", "NOT_FOUND"));
        service.requery(81L);
        assertThrows(IllegalStateException.class, () -> service.manualRefund(81L, "maker", "查证", 3));
        verify(members, never()).addBalance(any(), any());
        assertEquals(3, facts.size()); assertEquals("UNKNOWN", facts.get(2).getKind());
    }
    @Test void bareFailureWithoutFinalChannelStateCreatesNoRefundProof() {
        fact("DEBIT"); fact("SUBMIT_INTENT"); original.setStatus(0);
        when(channel.queryTransferResult("CW81", "CD81")).thenReturn(Map.of("result", "FAIL"));
        service.requery(81L);
        assertEquals(3, facts.size()); assertEquals("UNKNOWN", facts.get(2).getKind());
        verify(members, never()).addBalance(any(), any());
    }
    @Test void closedWithoutFinalDetailRemainsUnknown() {
        fact("DEBIT"); fact("SUBMIT_INTENT"); original.setStatus(0);
        when(channel.queryTransferResult("CW81", "CD81")).thenReturn(Map.of("result", "FAIL", "batchStatus", "CLOSED"));
        service.requery(81L);
        assertFalse(facts.stream().anyMatch(f -> "FINAL_FAIL".equals(f.getKind())));
        assertTrue(facts.stream().anyMatch(f -> "UNKNOWN".equals(f.getKind())));
        verify(members, never()).addBalance(any(), any());
    }
    @Test void localRejectionForAnotherIntentCannotAuthorizeRefund() {
        fact("DEBIT"); fact("SUBMIT_INTENT"); fact("NOT_ACCEPTED");
        facts.get(2).setSubmissionIntentId(999L); rejected();
    }
    @Test void laterLocalRejectionCannotEraseEarlierUnknown() {
        fact("DEBIT"); fact("SUBMIT_INTENT"); fact("UNKNOWN");
        Boolean recorded = ReflectionTestUtils.invokeMethod(service, "recordChannelFact", 81L, "NOT_ACCEPTED",
                Map.of("ok", "false", "ambiguous", "false"), 101L);
        assertEquals(Boolean.FALSE, recorded); rejected();
    }
    @Test void existingIntentIsNeverReusedForANewGatewayAttempt() {
        fact("DEBIT"); fact("SUBMIT_INTENT"); original.setStatus(0);
        assertNull(ReflectionTestUtils.invokeMethod(service, "recordSubmissionIntent", 81L));
        assertEquals(2, facts.size()); verifyNoInteractions(channel);
    }
    @Test void firstLocalRejectionBindsTheExactOriginalIntent() {
        fact("DEBIT"); fact("SUBMIT_INTENT");
        Boolean recorded = ReflectionTestUtils.invokeMethod(service, "recordChannelFact", 81L, "NOT_ACCEPTED",
                Map.of("ok", "false", "ambiguous", "false"), 101L);
        assertEquals(Boolean.TRUE, recorded); assertEquals(101L, facts.get(2).getSubmissionIntentId());
        assertTrue(service.manualRefund(81L, "maker", "首次发送前本地拒绝", 3));
        assertTrue(service.manualRefund(81L, "checker", "复核", 4));
        verify(members).addBalance(9L, new BigDecimal("30.00"));
    }
    @Test void lateSuccessDuringReviewResolvesOriginalWithoutCredit() {
        fact("DEBIT"); fact("SUBMIT_INTENT"); original.setStatus(4); original.setManualOperator("maker");
        when(channel.queryTransferResult("CW81", "CD81")).thenReturn(Map.of("result", "SUCCESS", "detailStatus", "SUCCESS"));
        service.requery(81L);
        assertEquals(1, original.getStatus());
        assertFalse(service.manualRefund(81L, "checker", "过期页面", 4));
        verify(members, never()).addBalance(any(), any());
    }
}
