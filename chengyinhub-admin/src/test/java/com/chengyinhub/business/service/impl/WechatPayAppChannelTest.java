package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.PaymentPreorderException;
import com.chengyinhub.common.config.WxPayConfig;
import org.junit.jupiter.api.Test;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * App 支付(trade_type=APP)下单通道的守卫测试。
 *
 * 这条链路最危险的失败方式不是「报错」,而是「用错 appid 悄悄下单」——
 * 微信侧签名校验会失败,但错误信息指向签名而非配置,排查成本极高。
 * 故核心断言是:**移动应用 appid 未配置时必须明确失败,且绝不读小程序 appid**。
 */
class WechatPayAppChannelTest {

    @Test
    void genericAppOrderReturnsNativeContractFromAppChannel() {
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.isWechatPayEnabled()).thenReturn(true);
        when(config.getAppAppid()).thenReturn("wx_native_app");
        when(config.getMerchantId()).thenReturn("1900000109");
        when(config.getNotifyUrl()).thenReturn("https://example.test/pay/notify");
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
        markServiceReady(service);

        com.wechat.pay.java.service.payments.app.AppServiceExtension appExtension =
                mock(com.wechat.pay.java.service.payments.app.AppServiceExtension.class);
        WechatVerifiedFactTestSupport.setField(service, "appServiceExtension", appExtension);
        assertTrue(service.isAppReady());
        com.wechat.pay.java.service.payments.app.model.PrepayWithRequestPaymentResponse response =
                new com.wechat.pay.java.service.payments.app.model.PrepayWithRequestPaymentResponse();
        response.setAppid("wx_native_app");
        response.setPartnerId("1900000109");
        response.setPrepayId("wx_prepay_1");
        response.setPackageVal("Sign=WXPay");
        response.setNonceStr("nonce-1");
        response.setTimestamp("1412000000");
        response.setSign("signed");
        when(appExtension.prepayWithRequestPayment(org.mockito.ArgumentMatchers.any()))
                .thenReturn(response);

        java.util.Map<String, String> result = service.createGenericAppOrder(
                "DEP19T1000", new BigDecimal("50.00"), "城市定向合作保证金");

        assertEquals("wx_native_app", result.get("appId"));
        assertEquals("1900000109", result.get("partnerId"));
        assertEquals("wx_prepay_1", result.get("prepayId"));
        assertEquals("Sign=WXPay", result.get("packageValue"));
        assertEquals("nonce-1", result.get("nonceStr"));
        assertEquals("1412000000", result.get("timeStamp"));
        assertEquals("signed", result.get("sign"));

        org.mockito.ArgumentCaptor<com.wechat.pay.java.service.payments.app.model.PrepayRequest> request =
                org.mockito.ArgumentCaptor.forClass(
                        com.wechat.pay.java.service.payments.app.model.PrepayRequest.class);
        verify(appExtension).prepayWithRequestPayment(request.capture());
        assertEquals("DEP19T1000", request.getValue().getOutTradeNo());
        assertEquals(5000, request.getValue().getAmount().getTotal());
        assertEquals("城市定向合作保证金", request.getValue().getDescription());
        assertEquals("wx_native_app", request.getValue().getAppid());
        assertEquals("1900000109", request.getValue().getMchid());
        assertEquals("https://example.test/pay/notify", request.getValue().getNotifyUrl());
    }

    @Test
    void genericAppOrderFailsClosedWhenWechatReturnsIncompleteContract() {
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.isWechatPayEnabled()).thenReturn(true);
        when(config.getAppAppid()).thenReturn("wx_native_app");
        when(config.getMerchantId()).thenReturn("1900000109");
        when(config.getNotifyUrl()).thenReturn("https://example.test/pay/notify");
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
        markServiceReady(service);

        com.wechat.pay.java.service.payments.app.AppServiceExtension appExtension =
                mock(com.wechat.pay.java.service.payments.app.AppServiceExtension.class);
        WechatVerifiedFactTestSupport.setField(service, "appServiceExtension", appExtension);
        com.wechat.pay.java.service.payments.app.model.PrepayWithRequestPaymentResponse incomplete =
                new com.wechat.pay.java.service.payments.app.model.PrepayWithRequestPaymentResponse();
        incomplete.setAppid("wx_native_app");
        when(appExtension.prepayWithRequestPayment(org.mockito.ArgumentMatchers.any()))
                .thenReturn(incomplete);

        PaymentPreorderException error = assertThrows(PaymentPreorderException.class,
                () -> service.createGenericAppOrder(
                        "DEP19T1000", new BigDecimal("50.00"), "城市定向合作保证金"));

        assertTrue(error.isResultUncertain(),
                "微信已可能受理下单，返回字段不完整时不能谎报明确失败");
    }

    @Test
    void genericAppOrderRejectsMissingMobileAppidWithoutMiniProgramFallback() {
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.isWechatPayEnabled()).thenReturn(true);
        when(config.getAppAppid()).thenReturn("   ");
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
        markServiceReady(service);

        PaymentPreorderException error = assertThrows(PaymentPreorderException.class,
                () -> service.createGenericAppOrder(
                        "DEP19T1000", new BigDecimal("50.00"), "城市定向合作保证金"));

        assertFalse(error.isResultUncertain(), "配置缺失必须是明确失败");
        assertTrue(error.getMessage().contains("app-appid"));
        verify(config, never()).getAppid();
    }

    @Test
    void genericAppOrderRejectsMissingBusinessParametersBeforeWechatCall() {
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.isWechatPayEnabled()).thenReturn(true);
        when(config.getAppAppid()).thenReturn("wx_native_app");
        when(config.getNotifyUrl()).thenReturn("https://example.test/pay/notify");
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
        markServiceReady(service);
        com.wechat.pay.java.service.payments.app.AppServiceExtension appExtension =
                mock(com.wechat.pay.java.service.payments.app.AppServiceExtension.class);
        WechatVerifiedFactTestSupport.setField(service, "appServiceExtension", appExtension);

        PaymentPreorderException missingOrderNo = assertThrows(PaymentPreorderException.class,
                () -> service.createGenericAppOrder(" ", new BigDecimal("50.00"), "保证金"));
        PaymentPreorderException missingAmount = assertThrows(PaymentPreorderException.class,
                () -> service.createGenericAppOrder("DEP19T1000", null, "保证金"));
        PaymentPreorderException missingDescription = assertThrows(PaymentPreorderException.class,
                () -> service.createGenericAppOrder("DEP19T1000", new BigDecimal("50.00"), " "));

        assertFalse(missingOrderNo.isResultUncertain());
        assertFalse(missingAmount.isResultUncertain());
        assertFalse(missingDescription.isResultUncertain());
        verify(appExtension, never()).prepayWithRequestPayment(org.mockito.ArgumentMatchers.any());
    }

    private CmsRegistration registration() {
        CmsRegistration reg = new CmsRegistration();
        reg.setId(1L);
        reg.setMemberId(2L);
        reg.setRegistrationNo("R-APP-0001");
        reg.setWechatPaymentAmount(new BigDecimal("69.00"));
        reg.setActivityTitle("静安探店日");
        reg.setTicketName("首期通票");
        return reg;
    }

    /** 支付功能整体关闭时,App 通道同样不可用,且是「明确失败」而不是「结果未知」。 */
    @Test
    void disabledPaymentRejectsAppOrderAsDefiniteFailure() {
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.isWechatPayEnabled()).thenReturn(false);
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);

        assertFalse(service.isReady());
        PaymentPreorderException ex = assertThrows(PaymentPreorderException.class,
                () -> service.createAppOrder(registration()));
        // 本次未发请求可明确拒绝；领域仍须保留原号历史，不能据此释放之前的 UNKNOWN。
        assertFalse(ex.isResultUncertain(),
                "支付未就绪属于明确失败，不能当作结果未知挂起订单");
    }

    /**
     * ★ 本文件最重要的一条:移动应用 appid 未配置时必须明确报错,
     * 且**一次都不能去读小程序 appid**(getAppid)——
     * 回落小程序 appid 会让微信侧签名校验失败,错误指向签名而非配置。
     */
    @Test
    void missingAppAppidFailsFastAndNeverFallsBackToMiniProgramAppid() {
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.isWechatPayEnabled()).thenReturn(true);
        when(config.getAppAppid()).thenReturn("");   // 未配置
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
        // 绕过 init:把 isReady() 需要的五个字段全部填上,只考察 appid 这道闸。
        markServiceReady(service);
        assertFalse(service.isAppReady());

        PaymentPreorderException ex = assertThrows(PaymentPreorderException.class,
                () -> service.createAppOrder(registration()));

        assertFalse(ex.isResultUncertain(), "配置缺失是明确失败");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("app-appid"),
                "错误文案必须指明是哪一项配置缺失，否则等于没有定位价值：" + ex.getMessage());
        // 关键:绝不回落小程序 appid。
        verify(config, never()).getAppid();
    }

    /** appid 为 null(而非空串)时同样拦住,不能因为判空写法漏掉。 */
    @Test
    void nullAppAppidAlsoBlocked() {
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.isWechatPayEnabled()).thenReturn(true);
        when(config.getAppAppid()).thenReturn(null);
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
        markServiceReady(service);

        assertThrows(PaymentPreorderException.class,
                () -> service.createAppOrder(registration()));
        verify(config, never()).getAppid();
    }

    /**
     * 把 isReady() 依赖的五个字段一次填齐(enabled 由各用例自己 mock)。
     * ⚠️ isReady() **不检查** appServiceExtension —— 这正是 createAppOrder 里
     * 单独判它非空的原因:只看 isReady() 会让 App 通道在未初始化时空指针。
     */
    private void markServiceReady(WechatPayServiceImpl service) {
        WechatPaymentIntentTestSupport.install(service, java.util.Map.of(
                "DEP19T1000", new BigDecimal("50.00"), "R-APP-0001", new BigDecimal("69.00")));
        WechatVerifiedFactTestSupport.setField(service, "jsapiService",
                WechatPaymentIntentTestSupport.absentOrderSdk());
        WechatVerifiedFactTestSupport.setField(service, "jsapiServiceExtension",
                mock(com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension.class));
        WechatVerifiedFactTestSupport.setField(service, "refundService",
                mock(com.wechat.pay.java.service.refund.RefundService.class));
        WechatVerifiedFactTestSupport.setField(service, "notificationParser",
                mock(com.wechat.pay.java.core.notification.NotificationParser.class));
        WechatVerifiedFactTestSupport.setField(service, "appServiceExtension",
                mock(com.wechat.pay.java.service.payments.app.AppServiceExtension.class));
    }

    /**
     * R2 守卫:转账 appid 必须由调用方显式给出,为空时**明确拒绝**,
     * 不回落到某个"默认"appid。
     *
     * 微信按 (appid, openid) 认人。回落会让它判「openid 与 appid 不匹配」而拒付,
     * 错误信息指向 openid,排查时容易误以为是用户身份问题,而真因是配置。
     */
    @Test
    void transferRejectsBlankAppidInsteadOfFallingBack() {
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.isWechatPayEnabled()).thenReturn(true);
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
        WechatVerifiedFactTestSupport.setField(service, "transferBatchService",
                mock(com.wechat.pay.java.service.transferbatch.TransferBatchService.class));

        for (String blank : new String[] {null, "", "   "}) {
            java.util.Map<String, String> r = service.transferToBalance(
                    "B1", "D1", new BigDecimal("1.00"), "o_ABC", "提现", blank);
            assertEquals("false", r.get("ok"), "appid 为空必须拒绝转账");
            assertTrue(r.get("error") != null && r.get("error").contains("appid"),
                    "错误文案要指明是 appid 问题：" + r.get("error"));
        }
        // 绝不去读小程序 appid 做回落。
        verify(config, never()).getAppid();
    }
}
