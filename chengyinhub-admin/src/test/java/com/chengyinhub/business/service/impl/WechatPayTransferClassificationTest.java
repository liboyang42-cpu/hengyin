package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.service.transferbatch.TransferBatchService;
import com.wechat.pay.java.service.transferbatch.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** SDK doubles test gateway classification only; no real transfer or MySQL is exercised. */
class WechatPayTransferClassificationTest {
    private final TransferBatchService sdk = mock(TransferBatchService.class);
    private final WechatPayServiceImpl gateway = new WechatPayServiceImpl();

    WechatPayTransferClassificationTest() {
        ReflectionTestUtils.setField(gateway, "transferBatchService", sdk);
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.getMerchantId()).thenReturn("merchant");
        ReflectionTestUtils.setField(gateway, "wxPayConfig", config);
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
        ReflectionTestUtils.setField(gateway, "transferBatchService", null);
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
