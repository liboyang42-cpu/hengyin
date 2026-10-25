package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CashWithdrawFundFact;
import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
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
        // These historical fund-fact scenarios explicitly have no master transfer-intent row.
        // Do not synthesize snapshots, convert hashes or backfill a SUBMIT_INTENT.
        var snapshots = new com.chengyinhub.business.service.CashWithdrawIntentTestSupport();
        ReflectionTestUtils.setField(service, "transferIntents", snapshots.mapper);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "umsMemberService", members);
        ReflectionTestUtils.setField(service, "wechatPayService", channel);
        ReflectionTestUtils.setField(service, "alarmService", mock(AlarmService.class));
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        ReflectionTestUtils.setField(service, "transactionManager", tx);
        original = new MemberCashWithdraw();
        original.setId(81L); original.setMemberId(9L); original.setAmount(new BigDecimal("30.00"));
        original.setOpenid("unit-openid-9");
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
        when(mapper.updateStatusIfProcessing(any())).thenAnswer(i -> apply(i.getArgument(0)));
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
        if (CashWithdrawFundFact.SUBMIT_INTENT.equals(kind)) {
            f.bindTransferIntent(request());
        } else {
            facts.stream().filter(v -> CashWithdrawFundFact.SUBMIT_INTENT.equals(v.getKind()))
                    .findFirst().ifPresent(submission -> {
                        f.setSubmissionIntentId(submission.getId());
                        f.bindTransferIntent(submission.transferIntent());
                    });
        }
        if (CashWithdrawFundFact.FINAL_FAIL.equals(kind)) {
            f.setChannelBatchStatus("FINISHED");
            f.setChannelDetailStatus("FAIL");
            f.setChannelBatchId("provider-batch-81");
            f.setChannelDetailId("provider-detail-81");
        }
        facts.add(f);
    }
    private CashWithdrawTransferIntent request() {
        return CashWithdrawTransferIntent.fromRequest(original.getId(), "unit-merchant", "wx-mp-appid",
                original.getOutBatchNo(), original.getOutDetailNo(), original.getAmount(),
                original.getOpenid(), "城瘾分润提现");
    }
    private CashWithdrawTransferIntent intent() {
        return facts.stream().filter(f -> CashWithdrawFundFact.SUBMIT_INTENT.equals(f.getKind()))
                .findFirst().orElseThrow().transferIntent();
    }
    private Map<String, String> completedQuery(String outcome) {
        CashWithdrawTransferIntent submitted = intent();
        return Map.ofEntries(Map.entry("result", outcome), Map.entry("detailStatus", outcome),
                Map.entry("batchStatus", "FINISHED"), Map.entry("batchId", "provider-batch-81"),
                Map.entry("detailId", "provider-detail-81"), Map.entry("mchid", submitted.mchid()),
                Map.entry("appid", submitted.appid()), Map.entry("outBatchNo", submitted.outBatchNo()),
                Map.entry("outDetailNo", submitted.outDetailNo()), Map.entry("openid", submitted.openid()),
                Map.entry("transferAmount", Long.toString(submitted.amountFen())));
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
    @Test void debitOnlyCrashRecoveryNeverQueriesOrPoisonsTwoPersonRefundEvidence() {
        fact("DEBIT"); original.setStatus(0);
        when(mapper.selectPendingForQuery(10)).thenReturn(List.of(original));
        assertEquals(1, service.syncPendingWithdrawals(10));
        assertEquals(3, original.getStatus()); assertEquals(1, facts.size());
        verifyNoInteractions(channel); verify(members, never()).addBalance(any(), any());
        assertNull(ReflectionTestUtils.invokeMethod(service, "recordSubmissionIntent", 81L, request()));
        assertTrue(service.manualRefund(81L, "maker", "未提交待复核", 3));
        service.requery(81L);
        assertEquals(4, original.getStatus()); assertEquals(1, facts.size());
        assertThrows(IllegalStateException.class, () -> service.manualRefund(81L, "maker", "同人", 4));
        assertTrue(service.manualRefund(81L, "checker", "原扣款复核", 4));
        service.requery(81L);
        assertFalse(service.manualRefund(81L, "other", "重复", 4));
        assertEquals("CW81", original.getOutBatchNo()); assertEquals("CD81", original.getOutDetailNo());
        assertEquals(2, facts.size()); verifyNoInteractions(channel);
        verify(members, times(1)).addBalance(9L, new BigDecimal("30.00"));
    }
    @Test void oldUnknownWithoutIntentIsNotReclassifiedAsNeverSubmitted() {
        fact("DEBIT"); fact("UNKNOWN");
        service.requery(81L);
        assertThrows(IllegalStateException.class, () -> service.manualRefund(81L, "maker", "旧未知", 3));
        assertEquals(2, facts.size());
        verifyNoInteractions(channel);
        verify(members, never()).addBalance(any(), any());
    }
    @Test void frozenDebitOnlySnapshotIsNotRecoveredOrQueried() {
        fact("DEBIT"); original.setStatus(0); original.setFrozen(1);
        when(mapper.selectPendingForQuery(10)).thenReturn(List.of(original));
        assertEquals(1, service.syncPendingWithdrawals(10));
        assertEquals(0, original.getStatus()); assertEquals(1, facts.size());
        verify(mapper, never()).updateStatusIfProcessing(any());
        verifyNoInteractions(channel); verifyNoInteractions(members);
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
        when(channel.queryTransferResult(intent())).thenReturn(Map.of("result", "UNKNOWN", "error", "NOT_FOUND"));
        service.requery(81L);
        assertThrows(IllegalStateException.class, () -> service.manualRefund(81L, "maker", "查证", 3));
        verify(members, never()).addBalance(any(), any());
        assertEquals(3, facts.size()); assertEquals("UNKNOWN", facts.get(2).getKind());
    }
    @Test void bareFailureWithoutFinalChannelStateCreatesNoRefundProof() {
        fact("DEBIT"); fact("SUBMIT_INTENT"); original.setStatus(0);
        when(channel.queryTransferResult(intent())).thenReturn(Map.of("result", "FAIL"));
        service.requery(81L);
        assertEquals(3, facts.size()); assertEquals("UNKNOWN", facts.get(2).getKind());
        verify(members, never()).addBalance(any(), any());
    }
    @Test void closedWithoutFinalDetailRemainsUnknown() {
        fact("DEBIT"); fact("SUBMIT_INTENT"); original.setStatus(0);
        when(channel.queryTransferResult(intent())).thenReturn(Map.of("result", "FAIL", "batchStatus", "CLOSED"));
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
        assertNull(ReflectionTestUtils.invokeMethod(service, "recordSubmissionIntent", 81L, request()));
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
        when(channel.queryTransferResult(intent())).thenReturn(completedQuery("SUCCESS"));
        service.requery(81L);
        assertEquals(1, original.getStatus());
        assertFalse(service.manualRefund(81L, "checker", "过期页面", 4));
        verify(members, never()).addBalance(any(), any());
    }
}
