package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.PaymentPreorderException;
import com.chengyinhub.business.service.RefundRequestResult;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.transferbatch.TransferBatchService;
import com.chengyinhub.common.config.WxPayConfig;
import org.junit.jupiter.api.Test;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class WechatPayReadinessTest {

    @Test
    void sharedDepositRefreshRejectsAnotherRefundAndPreservesExactMatch() {
        WechatPayServiceImpl service=org.mockito.Mockito.spy(WechatVerifiedFactTestSupport.gateway());
        com.chengyinhub.business.service.ICoopInviteService coop=mock(com.chengyinhub.business.service.ICoopInviteService.class);
        WechatVerifiedFactTestSupport.setField(service,"coopInviteService",coop);
        java.util.Map<String,String> facts=new java.util.HashMap<>();
        facts.put("outRefundNo","RDEP_B");facts.put("outTradeNo","DEP_B");facts.put("status","SUCCESS");
        org.mockito.Mockito.doReturn(facts).when(service).queryRefundFacts("RDEP_A");
        org.junit.jupiter.api.Assertions.assertNull(service.refreshPayoutFromWx("RDEP_A"));
        org.mockito.Mockito.verifyNoInteractions(coop);
        facts.put("outRefundNo","RDEP_A");facts.put("outTradeNo","DEP_A");
        when(coop.handleDepositRefundResult(facts)).thenReturn(true);
        org.junit.jupiter.api.Assertions.assertEquals("SUCCESS",service.refreshPayoutFromWx("RDEP_A"));
        verify(coop).handleDepositRefundResult(facts);
    }

    @Test
    void disabledPaymentCannotCreateGenericJsapiOrder() {
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.isWechatPayEnabled()).thenReturn(false);
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);

        assertFalse(service.isReady());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createGenericOrder("CY-1", new BigDecimal("1.00"), "test", "openid"));
        org.junit.jupiter.api.Assertions.assertEquals("支付服务暂不可用，请稍后再试", error.getMessage());
    }

    @Test
    void disabledRegistrationPreorderIsAClassifiedDefiniteFailure() {
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.isWechatPayEnabled()).thenReturn(false);
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);

        PaymentPreorderException error = assertThrows(PaymentPreorderException.class,
                () -> service.createOrder(new CmsRegistration()));

        assertFalse(error.isResultUncertain());
    }

    @Test
    void refundTransportTimeoutIsUnknownRatherThanDefinitiveFailure() {
        WxPayConfig config = mock(WxPayConfig.class);
        RefundService refundAdapter = mock(RefundService.class);
        when(refundAdapter.create(any())).thenThrow(new RuntimeException("response lost"));
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
        WechatVerifiedFactTestSupport.installRefundOriginal(service,refundAdapter,"REG-1","REG-1","TX1",BigDecimal.ONE);

        RefundRequestResult result = service.submitRefund("REG-1", "RF1",
                new BigDecimal("1.00"), new BigDecimal("1.00"), "测试");

        org.junit.jupiter.api.Assertions.assertEquals(RefundRequestResult.UNKNOWN, result);
        verify(refundAdapter).create(any());
    }

    @Test
    void depositRefundQueryDistinguishesConfirmedMissingFromTransportOrOther404() {
        RefundService adapter = mock(RefundService.class);
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatVerifiedFactTestSupport.bind(service,mock(com.wechat.pay.java.service.payments.jsapi.JsapiService.class),adapter,"merchant");
        com.wechat.pay.java.core.exception.ServiceException missing = mock(com.wechat.pay.java.core.exception.ServiceException.class);
        when(missing.getHttpStatusCode()).thenReturn(404);
        when(missing.getErrorCode()).thenReturn("RESOURCE_NOT_EXISTS");
        when(adapter.queryByOutRefundNo(any())).thenThrow(missing);
        org.junit.jupiter.api.Assertions.assertEquals("NOT_FOUND",service.queryRefundFacts("RDEP1").get("status"));
        when(missing.getErrorCode()).thenReturn("OTHER");
        org.junit.jupiter.api.Assertions.assertNull(service.queryRefundFacts("RDEP1"));
        org.mockito.Mockito.reset(adapter);
        when(adapter.queryByOutRefundNo(any())).thenThrow(new RuntimeException("timeout"));
        org.junit.jupiter.api.Assertions.assertNull(service.queryRefundFacts("RDEP1"));
    }

    @Test
    void refundRejectsFractionalCentAndChannelOverflowBeforeAdapter() {
        RefundService refundAdapter = mock(RefundService.class);
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", mock(WxPayConfig.class));
        WechatVerifiedFactTestSupport.setField(service, "refundService", refundAdapter);

        org.junit.jupiter.api.Assertions.assertEquals(RefundRequestResult.DEFINITIVE_FAILED,
                service.submitRefund("REG-1", "RF1", new BigDecimal("1.999"),
                        new BigDecimal("2.00"), "测试"));
        org.junit.jupiter.api.Assertions.assertEquals(RefundRequestResult.DEFINITIVE_FAILED,
                service.submitRefund("REG-2", "RF2", new BigDecimal("21474836.48"),
                        new BigDecimal("21474836.48"), "测试"));

        verify(refundAdapter, never()).create(any());
    }

    @Test
    void everyWechatYuanConversionUsesExactCentsAndChannelBound() throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName() != null && "chengyinhub-admin".equals(root.getFileName().toString())) {
            root = root.getParent();
        }
        String source = new String(Files.readAllBytes(root.resolve(
                "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/WechatPaymentSdkGateway.java")),
                StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(source.contains("setScale(2, java.math.RoundingMode.UNNECESSARY)"));
        org.junit.jupiter.api.Assertions.assertTrue(source.contains("movePointRight(2).longValueExact()"));
        org.junit.jupiter.api.Assertions.assertFalse(source.contains("multiply(new java.math.BigDecimal(\"100\")).intValue()"));
        org.junit.jupiter.api.Assertions.assertFalse(source.contains("multiply(hundred).longValue()"));
        org.junit.jupiter.api.Assertions.assertFalse(source.contains("multiply(new java.math.BigDecimal(\"100\")).longValueExact()"));
    }

    @Test
    void transferRejectsFractionalCentAndOverflowBeforeAdapter() {
        TransferBatchService transferAdapter = mock(TransferBatchService.class);
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatVerifiedFactTestSupport.setField(service, "transferBatchService", transferAdapter);

        java.util.Map<String, String> fractional = service.transferToBalance(
                "B1", "D1", new BigDecimal("1.999"), "openid", "测试", "wx_test_appid");
        java.util.Map<String, String> overflow = service.transferToBalance(
                "B2", "D2", new BigDecimal("21474836.48"), "openid", "测试", "wx_test_appid");

        org.junit.jupiter.api.Assertions.assertEquals("false", fractional.get("ok"));
        org.junit.jupiter.api.Assertions.assertEquals("false", fractional.get("ambiguous"));
        org.junit.jupiter.api.Assertions.assertEquals("false", overflow.get("ok"));
        org.junit.jupiter.api.Assertions.assertEquals("false", overflow.get("ambiguous"));
        verify(transferAdapter, never()).initiateBatchTransfer(any());
    }
}
