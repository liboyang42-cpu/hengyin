package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.service.transferbatch.TransferBatchService;
import com.wechat.pay.java.service.transferbatch.model.*;
import org.junit.jupiter.api.Test;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** SDK doubles test gateway classification only; no real transfer or MySQL is exercised. */
class WechatPayTransferClassificationTest {
    private final TransferBatchService sdk = mock(TransferBatchService.class);
    private final WechatPayServiceImpl gateway = WechatVerifiedFactTestSupport.gateway();

    WechatPayTransferClassificationTest() throws ReflectiveOperationException {
        WechatVerifiedFactTestSupport.setField(gateway, "transferBatchService", sdk);
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.getMerchantId()).thenReturn("merchant");
        when(config.getAppid()).thenReturn("wx-origin");
        WechatVerifiedFactTestSupport.setField(gateway, "wxPayConfig", config);
        var binding = Class.forName(WechatPaymentSdkGateway.class.getName() + "$TransferClientBinding")
                .getDeclaredConstructor(TransferBatchService.class, String.class, String.class, String.class);
        binding.setAccessible(true);
        WechatVerifiedFactTestSupport.setField(gateway, "transferClientBinding", binding.newInstance(sdk, "merchant", "wx-origin", null));
    }

    @Test
    void serviceErrorsNeverProveOriginalBatchWasNotAccepted() {
        // INVALID_REQUEST also covers conflicts and expired retry windows; NOT_ENOUGH requires original-no retry.
        Object[][] errors = {{500, "SYSTEM_ERROR"}, {503, "PARAM_ERROR"}, {502, null},
                {429, "FREQUENCY_LIMITED"}, {429, "FREQUENCY_LIMIT_EXCEED"}, {429, "RATELIMIT_EXCEEDED"},
                {400, "INVALID_REQUEST"}, {403, "NOT_ENOUGH"}, {404, "NOT_FOUND"},
                {400, "NEW_UNKNOWN_CODE"}, {400, "PARAM_ERROR"}, {401, "SIGN_ERROR"}};
        for (Object[] error : errors) {
            ServiceException failure = mock(ServiceException.class);
            when(failure.getHttpStatusCode()).thenReturn((Integer) error[0]);
            when(failure.getErrorCode()).thenReturn((String) error[1]);
            doThrow(failure).when(sdk).initiateBatchTransfer(any());
            assertUnknown(submit());
        }
    }

    @Test
    void timeoutAndUnverifiableResponsesRemainUnknown() {
        doThrow(new RuntimeException("timeout")).when(sdk).initiateBatchTransfer(any());
        assertUnknown(submit());
        doReturn(null).when(sdk).initiateBatchTransfer(any());
        assertUnknown(submit());
        InitiateBatchTransferResponse incomplete = new InitiateBatchTransferResponse();
        doReturn(incomplete).when(sdk).initiateBatchTransfer(any());
        assertUnknown(submit());
        incomplete.setBatchId("wx-batch");
        incomplete.setOutBatchNo("OTHER");
        assertUnknown(submit());
    }

    @Test
    void originalIdentityAndParametersSurviveAnUncertainSubmit() {
        doThrow(new RuntimeException("timeout")).when(sdk).initiateBatchTransfer(any());
        assertUnknown(submit());
        InitiateBatchTransferResponse accepted = new InitiateBatchTransferResponse();
        accepted.setOutBatchNo("CW500");
        accepted.setBatchId("wx-batch");
        accepted.setBatchStatus("ACCEPTED");
        doReturn(accepted).when(sdk).initiateBatchTransfer(any());
        Map<String, String> response = submit();
        assertEquals("true", response.get("ok"));
        assertEquals("wx-batch", response.get("batchId"));
        assertNull(response.get("ambiguous"));
        var requests = org.mockito.ArgumentCaptor.forClass(InitiateBatchTransferRequest.class);
        verify(sdk, times(2)).initiateBatchTransfer(requests.capture());
        for (InitiateBatchTransferRequest request : requests.getAllValues()) {
            assertEquals("CW500", request.getOutBatchNo());
            assertEquals("wx-origin", request.getAppid());
            assertEquals(3000L, request.getTotalAmount());
            assertEquals(1, request.getTotalNum());
            TransferDetailInput detail = request.getTransferDetailList().get(0);
            assertEquals("CD500", detail.getOutDetailNo());
            assertEquals("openid-origin", detail.getOpenid());
            assertEquals(3000L, detail.getTransferAmount());
            assertEquals("提现", detail.getTransferRemark());
        }
    }

    @Test
    void onlyPreSubmitValidationFailureIsDefinite() {
        assertDefinite(gateway.transferToBalance("CW500", "CD500", BigDecimal.ZERO, "open", "提现", "wx"));
        assertDefinite(gateway.transferToBalance("CW500", "CD500", new BigDecimal("0.001"), "open", "提现", "wx"));
        assertDefinite(gateway.transferToBalance("CW500", "CD500", BigDecimal.ONE, "open", "提现", " "));
        assertDefinite(gateway.transferToBalance(null, "CD500", BigDecimal.ONE, "open", "提现", "wx"));
        assertDefinite(gateway.transferToBalance("CW500", " ", BigDecimal.ONE, "open", "提现", "wx"));
        verifyNoInteractions(sdk);
        WechatVerifiedFactTestSupport.setField(gateway, "transferBatchService", null);
        assertDefinite(submit());
    }

    @Test
    void notFoundImmediatelyAfterSubmitIsNotEvidenceForRefund() {
        doThrow(new RuntimeException("timeout")).when(sdk).initiateBatchTransfer(any());
        assertUnknown(submit());
        ServiceException missing = mock(ServiceException.class);
        when(missing.getHttpStatusCode()).thenReturn(404);
        when(missing.getErrorCode()).thenReturn("NOT_FOUND");
        when(sdk.getTransferBatchByOutNo(any())).thenThrow(missing);
        assertEquals("UNKNOWN", gateway.queryTransferResult("CW500", "CD500").get("result"));
        verify(sdk, never()).getTransferDetailByOutNo(any());
    }

    @Test
    void closedAloneIsUnknownAndPendingBatchDoesNotQueryDetail() {
        String[][] statuses = {{"CLOSED", "UNKNOWN"}, {"NEW_STATUS", "UNKNOWN"},
                {"WAIT_PAY", "PROCESSING"}, {"ACCEPTED", "PROCESSING"}, {"PROCESSING", "PROCESSING"}};
        for (String[] status : statuses) {
            TransferBatchEntity entity = new TransferBatchEntity();
            entity.setTransferBatch(batch(status[0]));
            doReturn(entity).when(sdk).getTransferBatchByOutNo(any());
            assertEquals(status[1], gateway.queryTransferResult("CW500", "CD500").get("result"));
        }
        verify(sdk, never()).getTransferDetailByOutNo(any());
    }

    @Test
    void onlyVerifiedFinishedDetailProvidesSuccessOrFailureEvidence() {
        TransferBatchEntity entity = new TransferBatchEntity();
        entity.setTransferBatch(batch("FINISHED"));
        doReturn(entity).when(sdk).getTransferBatchByOutNo(any());
        for (String[] status : new String[][]{{"SUCCESS", "SUCCESS"}, {"FAIL", "FAIL"},
                {"PROCESSING", "PROCESSING"}, {"NEW_STATUS", "UNKNOWN"}}) {
            doReturn(detail(status[0])).when(sdk).getTransferDetailByOutNo(any());
            Map<String, String> result = gateway.queryTransferResult("CW500", "CD500");
            assertEquals(status[1], result.get("result"));
            assertEquals(status[0], result.get("detailStatus"));
            assertEquals("CW500", result.get("outBatchNo"));
            assertEquals("CD500", result.get("outDetailNo"));
            assertEquals("3000", result.get("transferAmount"));
            assertEquals("wx-origin", result.get("appid"));
            assertEquals("merchant", result.get("mchid"));
        }
    }

    @Test
    void wrongBatchOrDetailEvidenceCannotReleaseOriginalFunds() {
        var invalidBatch = java.util.List.<java.util.function.Consumer<TransferBatchGet>>of(
                b -> b.setOutBatchNo("OTHER"), b -> b.setMchid("OTHER"), b -> b.setTotalNum(2),
                b -> b.setBatchId(null), b -> b.setTotalAmount(null), b -> b.setAppid(null));
        for (var change : invalidBatch) {
            TransferBatchGet b = batch("FINISHED");
            change.accept(b);
            TransferBatchEntity entity = new TransferBatchEntity();
            entity.setTransferBatch(b);
            doReturn(entity).when(sdk).getTransferBatchByOutNo(any());
            assertEquals("UNKNOWN", gateway.queryTransferResult("CW500", "CD500").get("result"));
        }
        verify(sdk, never()).getTransferDetailByOutNo(any());
        TransferBatchEntity entity = new TransferBatchEntity();
        entity.setTransferBatch(batch("FINISHED"));
        doReturn(entity).when(sdk).getTransferBatchByOutNo(any());
        var invalidDetail = java.util.List.<java.util.function.Consumer<TransferDetailEntity>>of(
                d -> d.setOutBatchNo("OTHER"), d -> d.setOutDetailNo("OTHER"), d -> d.setMchid("OTHER"),
                d -> d.setBatchId("OTHER"), d -> d.setAppid("OTHER"), d -> d.setDetailId(null),
                d -> d.setTransferAmount(1L));
        for (var change : invalidDetail) {
            TransferDetailEntity d = detail("FAIL");
            change.accept(d);
            doReturn(d).when(sdk).getTransferDetailByOutNo(any());
            assertEquals("UNKNOWN", gateway.queryTransferResult("CW500", "CD500").get("result"));
        }
    }

    @Test
    void frozenIntentIsTheSubmittedSdkRequestAndUncertainSubmitNeverProvesNotSent() {
        var intent = intent();
        doThrow(new RuntimeException("timeout")).when(sdk).initiateBatchTransfer(any());
        Map<String, String> result = gateway.transferToBalance(intent);
        assertUnknown(result);
        assertEquals("false", result.get("notSent"));
        assertEquals(intent.requestHash(), result.get("requestHash"));
        var request = org.mockito.ArgumentCaptor.forClass(InitiateBatchTransferRequest.class);
        verify(sdk).initiateBatchTransfer(request.capture());
        assertEquals(intent.appid(), request.getValue().getAppid());
        assertEquals(intent.outBatchNo(), request.getValue().getOutBatchNo());
        assertEquals(intent.amountFen(), request.getValue().getTotalAmount());
        var detail = request.getValue().getTransferDetailList().get(0);
        assertEquals(intent.outDetailNo(), detail.getOutDetailNo());
        assertEquals(intent.openid(), detail.getOpenid());
        assertEquals(intent.remark(), detail.getTransferRemark());
    }

    @Test
    void changedMerchantOrUncommittedSubmitNeverCallsSdkAndCannotInventChannelFailure() {
        var intent = intent();
        WxPayConfig config = (WxPayConfig) WechatVerifiedFactTestSupport.getField(gateway, "wxPayConfig");
        when(config.getMerchantId()).thenReturn("changed-merchant");
        var result = gateway.transferToBalance(intent);
        assertEquals("true", result.get("notSent"));
        assertEquals(intent.requestHash(), result.get("requestHash"));
        assertEquals("UNKNOWN", gateway.queryTransferResult(intent).get("result"));
        when(config.getMerchantId()).thenReturn("merchant");
        var unsubmitted = new com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent(
                intent.withdrawId(), intent.memberId(), intent.mchid(), intent.appid(), intent.outBatchNo(),
                intent.outDetailNo(), intent.amountFen(), intent.openid(), intent.remark(), intent.requestVersion(),
                intent.requestHash(), intent.balanceBefore(), intent.balanceAfter(), false, false);
        assertEquals("true", gateway.transferToBalance(unsubmitted).get("notSent"));
        verifyNoInteractions(sdk);
    }

    @Test
    void internallyConsistentForeignAppAmountOrPayeeCannotSatisfyOriginalIntent() {
        var original = intent();
        for (String mismatch : new String[]{"none", "appid", "amount", "openid", "missing-openid"}) {
            var b = batch("FINISHED");
            var d = detail("FAIL");
            if ("appid".equals(mismatch)) { b.setAppid("other-app"); d.setAppid("other-app"); }
            if ("amount".equals(mismatch)) { b.setTotalAmount(3001L); d.setTransferAmount(3001L); }
            if ("openid".equals(mismatch)) d.setOpenid("another-player");
            if ("missing-openid".equals(mismatch)) d.setOpenid(null);
            var entity = new TransferBatchEntity(); entity.setTransferBatch(b);
            doReturn(entity).when(sdk).getTransferBatchByOutNo(any());
            doReturn(d).when(sdk).getTransferDetailByOutNo(any());
            assertEquals("none".equals(mismatch) ? "FAIL" : "UNKNOWN",
                    gateway.queryTransferResult(original).get("result"), mismatch);
        }
    }

    @Test
    void localRejectCarriesOnlyTheMatchingOriginalHashAndCorruptEvidenceNeverCallsSdk() {
        var original = intent();
        WechatVerifiedFactTestSupport.setField(gateway, "transferBatchService", null);
        WechatVerifiedFactTestSupport.setField(gateway, "transferClientBinding", null);
        var reject = gateway.transferToBalance(original);
        assertEquals("true", reject.get("notSent"));
        assertEquals(original.requestHash(), reject.get("requestHash"));
        var corrupt = new com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent(
                original.withdrawId(), original.memberId(), original.mchid(), original.appid(), original.outBatchNo(),
                original.outDetailNo(), original.amountFen(), original.openid(), original.remark(), original.requestVersion(),
                "forged", original.balanceBefore(), original.balanceAfter(), true, false);
        assertThrows(IllegalStateException.class, () -> gateway.transferToBalance(corrupt));
        verifyNoInteractions(sdk);
    }

    private com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent intent() {
        var w = new com.chengyinhub.business.domain.MemberCashWithdraw();
        w.setId(500L); w.setMemberId(42L); w.setAmount(new BigDecimal("30.00"));
        w.setOpenid("openid-origin"); w.setOutBatchNo("CW500"); w.setOutDetailNo("CD500");
        var i = com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent.create(w, "merchant", "wx-origin",
                "提现", new BigDecimal("100.00"), new BigDecimal("70.00"));
        return new com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent(i.withdrawId(), i.memberId(),
                i.mchid(), i.appid(), i.outBatchNo(), i.outDetailNo(), i.amountFen(), i.openid(), i.remark(),
                i.requestVersion(), i.requestHash(), i.balanceBefore(), i.balanceAfter(), true, false);
    }

    @Test
    void twoPublishedV1ConstructorsRetainTheirOriginalHashAndCannotExchangeSources() {
        var snapshot = intent();
        var ledger = com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent.fromRequest(500L,
                snapshot.mchid(), snapshot.appid(), snapshot.outBatchNo(), snapshot.outDetailNo(),
                snapshot.amountYuan(), snapshot.openid(), snapshot.remark()).withSubmissionIntentId(900L);
        assertDoesNotThrow(snapshot::requireValid); assertDoesNotThrow(ledger::requireValid);
        assertEquals(com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent.OriginalSource.TRANSFER_INTENT,
                snapshot.originalSource());
        assertEquals(com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent.OriginalSource.FUND_FACT,
                ledger.originalSource());
        assertEquals(snapshot.requestHash(), snapshot.canonicalRequestHash());
        assertEquals(ledger.requestHash(), ledger.canonicalRequestHash());
        assertThrows(IllegalArgumentException.class, () -> snapshot.withSubmissionIntentId(900L));
        var wrongLedger = new com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent(500L, 900L,
                snapshot.mchid(), snapshot.appid(), snapshot.outBatchNo(), snapshot.outDetailNo(), snapshot.amountFen(),
                snapshot.openid(), snapshot.remark(), 1, snapshot.requestHash());
        assertThrows(IllegalArgumentException.class, wrongLedger::requireValid);
        var wrongSnapshot = new com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent(snapshot.withdrawId(),
                snapshot.memberId(), snapshot.mchid(), snapshot.appid(), snapshot.outBatchNo(), snapshot.outDetailNo(),
                snapshot.amountFen(), snapshot.openid(), snapshot.remark(), 1, ledger.requestHash(),
                snapshot.balanceBefore(), snapshot.balanceAfter(), true, false);
        assertThrows(IllegalStateException.class, wrongSnapshot::requireValid);
        verifyNoInteractions(sdk);
    }

    private TransferBatchGet batch(String status) {
        TransferBatchGet batch = new TransferBatchGet();
        batch.setMchid("merchant");
        batch.setAppid("wx-origin");
        batch.setOutBatchNo("CW500");
        batch.setBatchId("wx-batch");
        batch.setBatchStatus(status);
        batch.setTotalNum(1);
        batch.setTotalAmount(3000L);
        return batch;
    }

    private TransferDetailEntity detail(String status) {
        TransferDetailEntity detail = new TransferDetailEntity();
        detail.setMchid("merchant");
        detail.setAppid("wx-origin");
        detail.setOutBatchNo("CW500");
        detail.setOutDetailNo("CD500");
        detail.setBatchId("wx-batch");
        detail.setDetailId("wx-detail");
        detail.setDetailStatus(status);
        detail.setTransferAmount(3000L);
        detail.setOpenid("openid-origin");
        return detail;
    }

    private Map<String, String> submit() {
        return gateway.transferToBalance("CW500", "CD500", new BigDecimal("30.00"),
                "openid-origin", "提现", "wx-origin");
    }

    private void assertUnknown(Map<String, String> result) {
        assertEquals("false", result.get("ok"));
        assertEquals("true", result.get("ambiguous"), "must retain original receipt, never refund on unknown");
    }

    private void assertDefinite(Map<String, String> result) {
        assertEquals("false", result.get("ok"));
        assertEquals("false", result.get("ambiguous"));
    }
}
