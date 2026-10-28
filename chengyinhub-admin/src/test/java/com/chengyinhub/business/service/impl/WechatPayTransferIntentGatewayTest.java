package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.auth.Credential;
import com.wechat.pay.java.core.auth.Validator;
import com.wechat.pay.java.core.cipher.*;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.service.transferbatch.TransferBatchService;
import com.wechat.pay.java.service.transferbatch.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Constructor;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Gateway boundaries only. A owns real DTO canonical-hash tests; no DTO implementation or hash clone here. */
class WechatPayTransferIntentGatewayTest {
    private final WechatPaymentSdkGateway gateway = new WechatPaymentSdkGateway();
    private final TransferBatchService sdk = mock(TransferBatchService.class);

    @Test
    void sdkInitializationBindsTheActualCredentialMerchantRatherThanMutableProperties() {
        WxPayConfig properties = mock(WxPayConfig.class);
        Config config = mock(Config.class);
        Credential credential = mock(Credential.class);
        when(credential.getMerchantId()).thenReturn("credential-original");
        when(config.createCredential()).thenReturn(credential);
        when(config.createValidator()).thenReturn(mock(Validator.class));
        when(config.createEncryptor()).thenReturn(mock(PrivacyEncryptor.class));
        when(config.createDecryptor()).thenReturn(mock(PrivacyDecryptor.class));
        when(config.createSigner()).thenReturn(mock(Signer.class));
        NotificationConfig notification = mock(NotificationConfig.class);
        when(notification.getSignType()).thenReturn("WECHATPAY2-SHA256-RSA2048");
        when(notification.getCipherType()).thenReturn("AEAD_AES_256_GCM");
        when(notification.createVerifier()).thenReturn(mock(Verifier.class));
        when(notification.createAeadCipher()).thenReturn(mock(AeadCipher.class));
        when(properties.isWechatPayEnabled()).thenReturn(true);
        when(properties.getConfig()).thenReturn(config);
        when(properties.getNotificationConfig()).thenReturn(notification);
        when(properties.getMerchantId()).thenReturn("mutable-property-other");
        ReflectionTestUtils.setField(gateway, "wxPayConfig", properties);
        gateway.init(); // SDK construction only; no signing/HTTP/provider calls.
        Object bound = ReflectionTestUtils.getField(gateway, "transferClientBinding");
        assertNotNull(bound);
        assertEquals("credential-original", ReflectionTestUtils.getField(bound, "mchid"));
        assertSame(ReflectionTestUtils.getField(gateway, "transferBatchService"), ReflectionTestUtils.getField(bound, "client"));
        Object actualHttpClient = ReflectionTestUtils.getField(ReflectionTestUtils.getField(bound, "client"), "httpClient");
        assertSame(credential, ReflectionTestUtils.getField(actualHttpClient, "credential"));
        verify(credential, never()).getAuthorization(any(java.net.URI.class), anyString(), anyString());
    }

    @Test
    void aMissingRealClientBindingCannotFallBackToTheLegacySignature() {
        ReflectionTestUtils.setField(gateway, "transferBatchService", sdk);
        CashWithdrawTransferIntent original = intent();
        assertOriginalNotSent(gateway.transferToBalance(original), original);
        assertEquals("UNKNOWN", gateway.queryTransferResult(original).get("result"));
        verifyNoInteractions(sdk);
    }

    @Test
    void dtoHashValidationMustPrecedeBothSdkPaths() throws Exception {
        bind("merchant");
        CashWithdrawTransferIntent intent = intent();
        doThrow(new IllegalArgumentException("original hash conflict")).when(intent).requireValid();
        assertSubmissionUnknown(gateway.transferToBalance(intent));
        assertEquals("UNKNOWN", gateway.queryTransferResult(intent).get("result"));
        verify(intent, times(2)).requireValid();
        verifyNoInteractions(sdk);
    }

    @Test
    void canonicalHashFromTheDtoMustStillMatchTheSealedRequest() throws Exception {
        bind("merchant");
        CashWithdrawTransferIntent intent = intent();
        when(intent.canonicalRequestHash()).thenReturn("changed-request-hash");
        assertSubmissionUnknown(gateway.transferToBalance(intent));
        assertEquals("UNKNOWN", gateway.queryTransferResult(intent).get("result"));
        verify(intent, times(2)).requireValid();
        verifyNoInteractions(sdk);
    }

    @Test
    void wrongOriginalMerchantNeverUsesTheCurrentPropertyOrSendsAnything() throws Exception {
        bind("merchant");
        CashWithdrawTransferIntent intent = intent();
        when(intent.mchid()).thenReturn("other");
        WxPayConfig properties = mock(WxPayConfig.class);
        when(properties.getMerchantId()).thenReturn("other");
        ReflectionTestUtils.setField(gateway, "wxPayConfig", properties);
        assertOriginalNotSent(gateway.transferToBalance(intent), intent);
        assertEquals("UNKNOWN", gateway.queryTransferResult(intent).get("result"));
        verifyNoInteractions(sdk);
    }

    @Test
    void uncertainSubmissionRestoresOnlyTheExactSealedParametersAndBoundClient() throws Exception {
        bind("merchant");
        TransferBatchService unrelatedLegacyClient = mock(TransferBatchService.class);
        ReflectionTestUtils.setField(gateway, "transferBatchService", unrelatedLegacyClient);
        CashWithdrawTransferIntent intent = intent();
        doThrow(new RuntimeException("timeout")).when(sdk).initiateBatchTransfer(any());
        assertSubmissionUnknown(gateway.transferToBalance(intent));
        InitiateBatchTransferResponse response = new InitiateBatchTransferResponse();
        response.setOutBatchNo("CW500"); response.setBatchId("provider-batch"); response.setBatchStatus("ACCEPTED");
        doReturn(response).when(sdk).initiateBatchTransfer(any());
        assertEquals("true", gateway.transferToBalance(intent).get("ok"));
        var requests = org.mockito.ArgumentCaptor.forClass(InitiateBatchTransferRequest.class);
        var ordered = inOrder(intent, sdk);
        ordered.verify(intent).requireValid(); ordered.verify(sdk).initiateBatchTransfer(any());
        ordered.verify(intent).requireValid(); ordered.verify(sdk).initiateBatchTransfer(any());
        verify(sdk, times(2)).initiateBatchTransfer(requests.capture());
        for (InitiateBatchTransferRequest request : requests.getAllValues()) {
            assertEquals("CW500", request.getOutBatchNo()); assertEquals("wx-original", request.getAppid());
            assertEquals(3000L, request.getTotalAmount()); assertEquals(1, request.getTotalNum());
            assertEquals("城瘾分润提现", request.getBatchName()); assertEquals("提现", request.getBatchRemark());
            TransferDetailInput detail = request.getTransferDetailList().get(0);
            assertEquals("CD500", detail.getOutDetailNo()); assertEquals(3000L, detail.getTransferAmount());
            assertEquals("openid-original", detail.getOpenid()); assertEquals("提现", detail.getTransferRemark());
        }
        verifyNoInteractions(unrelatedLegacyClient);
    }

    @Test
    void effectiveParametersCannotBeRenormalizedAfterTheirHashWasSealed() throws Exception {
        bind("merchant");
        for (String remark : new String[]{null, "", "x".repeat(33)}) {
            CashWithdrawTransferIntent intent = intent(); when(intent.effectiveRemark()).thenReturn(remark);
            assertSubmissionUnknown(gateway.transferToBalance(intent));
        }
        CashWithdrawTransferIntent tooLarge = intent(); when(tooLarge.amountFen()).thenReturn(2147483648L);
        assertSubmissionUnknown(gateway.transferToBalance(tooLarge));
        verifyNoInteractions(sdk);
    }

    @Test
    void onlyMatchingOriginalTerminalEvidenceCanSettleIncludingProviderOpenid() throws Exception {
        bind("merchant"); serve("FINISHED", "SUCCESS");
        for (String state : new String[]{"SUCCESS", "FAIL"}) {
            TransferDetailEntity detail = detail(state); doReturn(detail).when(sdk).getTransferDetailByOutNo(any());
            Map<String, String> result = gateway.queryTransferResult(intent());
            assertEquals(state, result.get("result")); assertEquals("FINISHED", result.get("batchStatus"));
            assertEquals(state, result.get("detailStatus")); assertEquals("merchant", result.get("mchid"));
            assertEquals("wx-original", result.get("appid")); assertEquals("CW500", result.get("outBatchNo"));
            assertEquals("CD500", result.get("outDetailNo")); assertEquals("3000", result.get("transferAmount"));
            assertEquals("openid-original", result.get("openid"));
            assertFalse(result.containsKey("requestHash")); // Not fabricated from the caller as provider evidence.
        }
    }

    @Test
    void providerSelfConsistencyDoesNotReplaceMatchingTheOriginalIntent() throws Exception {
        bind("merchant");
        for (boolean wrongApp : new boolean[]{true, false}) {
            TransferBatchGet batch = batch("FINISHED"); TransferDetailEntity detail = detail("FAIL");
            if (wrongApp) { batch.setAppid("other-app"); detail.setAppid("other-app"); }
            else { batch.setTotalAmount(2999L); detail.setTransferAmount(2999L); }
            TransferBatchEntity entity = new TransferBatchEntity(); entity.setTransferBatch(batch);
            doReturn(entity).when(sdk).getTransferBatchByOutNo(any()); doReturn(detail).when(sdk).getTransferDetailByOutNo(any());
            assertEquals("UNKNOWN", gateway.queryTransferResult(intent()).get("result"));
        }
    }

    @Test
    void missingOrWrongProviderReceiverRemainsUnknownAndIsNeverFilledFromIntent() throws Exception {
        bind("merchant"); serve("FINISHED", "FAIL");
        for (String receiver : new String[]{null, "", "other-openid"}) {
            TransferDetailEntity detail = detail("FAIL"); detail.setOpenid(receiver);
            doReturn(detail).when(sdk).getTransferDetailByOutNo(any());
            Map<String, String> result = gateway.queryTransferResult(intent());
            assertEquals("UNKNOWN", result.get("result")); assertEquals(receiver, result.get("openid"));
        }
    }

    @Test
    void closedOrPendingBatchNeverProvesAnOriginalDetailFailed() throws Exception {
        bind("merchant");
        for (String status : new String[]{"CLOSED", "PROCESSING", "ACCEPTED", "WAIT_PAY"}) {
            serve(status, "FAIL"); assertEquals("UNKNOWN", gateway.queryTransferResult(intent()).get("result"));
        }
        verify(sdk, never()).getTransferDetailByOutNo(any());
    }

    @Test
    void notFoundAfterOriginalTimeoutKeepsTheSameIdentityAndDoesNotReleaseAnything() throws Exception {
        bind("merchant"); CashWithdrawTransferIntent intent = intent();
        doThrow(new RuntimeException("timeout")).when(sdk).initiateBatchTransfer(any());
        assertSubmissionUnknown(gateway.transferToBalance(intent));
        var missing = mock(com.wechat.pay.java.core.exception.ServiceException.class);
        when(missing.getHttpStatusCode()).thenReturn(404); when(missing.getErrorCode()).thenReturn("NOT_FOUND");
        doThrow(missing).when(sdk).getTransferBatchByOutNo(any());
        assertEquals("UNKNOWN", gateway.queryTransferResult(intent).get("result"));
        verify(sdk).getTransferBatchByOutNo(argThat(q -> "CW500".equals(q.getOutBatchNo())));
        verify(sdk, never()).getTransferDetailByOutNo(any());
    }

    private CashWithdrawTransferIntent intent() {
        CashWithdrawTransferIntent intent = mock(CashWithdrawTransferIntent.class);
        when(intent.canonicalRequestHash()).thenReturn("original-hash-from-dto");
        when(intent.requestHash()).thenReturn("original-hash-from-dto");
        when(intent.mchid()).thenReturn("merchant"); when(intent.appid()).thenReturn("wx-original");
        when(intent.outBatchNo()).thenReturn("CW500"); when(intent.outDetailNo()).thenReturn("CD500");
        when(intent.amountFen()).thenReturn(3000L); when(intent.openid()).thenReturn("openid-original");
        when(intent.effectiveRemark()).thenReturn("提现");
        // Canonical hashing itself is not replaced/implemented by this gateway test; A owns that suite.
        return intent;
    }

    private void bind(String merchant) throws Exception {
        Class<?> binding = Class.forName(WechatPaymentSdkGateway.class.getName() + "$TransferClientBinding");
        Constructor<?> constructor = binding.getDeclaredConstructor(TransferBatchService.class, String.class, String.class, String.class);
        constructor.setAccessible(true);
        ReflectionTestUtils.setField(gateway, "transferClientBinding", constructor.newInstance(sdk, merchant, "wx-original", null));
    }

    private TransferBatchGet batch(String status) {
        TransferBatchGet batch = new TransferBatchGet(); batch.setMchid("merchant"); batch.setAppid("wx-original");
        batch.setOutBatchNo("CW500"); batch.setBatchId("provider-batch"); batch.setBatchStatus(status);
        batch.setTotalAmount(3000L); batch.setTotalNum(1); return batch;
    }
    private TransferDetailEntity detail(String state) {
        TransferDetailEntity detail = new TransferDetailEntity(); detail.setMchid("merchant"); detail.setAppid("wx-original");
        detail.setOutBatchNo("CW500"); detail.setOutDetailNo("CD500"); detail.setBatchId("provider-batch");
        detail.setDetailId("provider-detail"); detail.setDetailStatus(state); detail.setTransferAmount(3000L);
        detail.setOpenid("openid-original"); return detail;
    }
    private void serve(String batchStatus, String detailStatus) {
        TransferBatchEntity entity = new TransferBatchEntity(); entity.setTransferBatch(batch(batchStatus));
        doReturn(entity).when(sdk).getTransferBatchByOutNo(any()); doReturn(detail(detailStatus)).when(sdk).getTransferDetailByOutNo(any());
    }
    private void assertOriginalNotSent(Map<String, String> result, CashWithdrawTransferIntent original) {
        assertEquals("false", result.get("ok")); assertEquals("false", result.get("ambiguous"));
        assertEquals("true", result.get("notSent")); assertEquals(original.requestHash(), result.get("requestHash"));
    }
    private void assertSubmissionUnknown(Map<String, String> result) {
        assertEquals("false", result.get("ok")); assertEquals("true", result.get("ambiguous"));
        assertNotEquals("true", result.get("notSent"), "未验证原 DTO 或 SDK 后异常不得获得未发送凭证");
    }
}
