package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.PaymentPreorderException;
import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR10 复审:支付结果通知地址默认留空。微信下单必须携带 notify_url,留空时四条下单路径
 * (小程序报名 / 小程序通用 / App 报名 / App 通用)都必须在调用微信之前明确失败(「支付回调地址未配置」),
 * 不得带空值或回落到生产回调;配置后原样下发。application.yml 不得把生产回调写成所有环境的默认值。
 */
class WechatPayNotifyUrlTest {

    private static final String URL = "https://api.example.com/prod-api/api/registration/callback";

    private WxPayConfig config;
    private JsapiServiceExtension jsapi;
    private com.wechat.pay.java.service.payments.app.AppServiceExtension app;

    private WechatPayServiceImpl readyService(String notifyUrl) {
        config = mock(WxPayConfig.class);
        when(config.isWechatPayEnabled()).thenReturn(true);
        when(config.getAppid()).thenReturn("wx_mini");
        when(config.getAppAppid()).thenReturn("wx_native_app");
        when(config.getMerchantId()).thenReturn("1900000109");
        when(config.getNotifyUrl()).thenReturn(notifyUrl);
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatPaymentIntentTestSupport.install(service, Map.of("CUSTOM_1", new BigDecimal("9.90"),
                "DEP19T1000", new BigDecimal("50.00"), "R-NOTIFY-0001", new BigDecimal("69.00")));
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
        jsapi = mock(JsapiServiceExtension.class);
        app = mock(com.wechat.pay.java.service.payments.app.AppServiceExtension.class);
        WechatVerifiedFactTestSupport.setField(service, "jsapiService",
                WechatPaymentIntentTestSupport.absentOrderSdk());
        WechatVerifiedFactTestSupport.setField(service, "jsapiServiceExtension", jsapi);
        WechatVerifiedFactTestSupport.setField(service, "refundService",
                mock(com.wechat.pay.java.service.refund.RefundService.class));
        WechatVerifiedFactTestSupport.setField(service, "notificationParser",
                mock(com.wechat.pay.java.core.notification.NotificationParser.class));
        WechatVerifiedFactTestSupport.setField(service, "appServiceExtension", app);
        return service;
    }

    private static CmsRegistration registration() {
        CmsRegistration reg = new CmsRegistration();
        reg.setId(1L);
        reg.setMemberId(2L);
        reg.setRegistrationNo("R-NOTIFY-0001");
        reg.setWechatPaymentAmount(new BigDecimal("69.00"));
        reg.setActivityTitle("静安探店日");
        reg.setTicketName("首期通票");
        return reg;
    }

    @Test
    void blankNotifyUrl_refusesEveryPrepayBeforeAnyWechatCall() {
        for (String blank : new String[] {null, "", "   "}) {
            WechatPayServiceImpl service = readyService(blank);

            PaymentPreorderException mini = assertThrows(PaymentPreorderException.class,
                    () -> service.createOrder(registration()));
            assertFalse(mini.isResultUncertain(), "未调用微信,必须是明确失败,调用方据此释放占用");
            assertEquals("支付回调地址未配置", mini.getMessage());

            PaymentPreorderException appOrder = assertThrows(PaymentPreorderException.class,
                    () -> service.createAppOrder(registration()));
            assertFalse(appOrder.isResultUncertain());
            assertEquals("支付回调地址未配置", appOrder.getMessage());

            PaymentPreorderException appGeneric = assertThrows(PaymentPreorderException.class,
                    () -> service.createGenericAppOrder("DEP19T1000", new BigDecimal("50.00"), "保证金"));
            assertFalse(appGeneric.isResultUncertain());
            assertEquals("支付回调地址未配置", appGeneric.getMessage());

            IllegalStateException generic = assertThrows(IllegalStateException.class,
                    () -> service.createGenericOrder("CUSTOM_1", new BigDecimal("9.90"), "城瘾推广位权益", "o_openid"));
            assertEquals("支付回调地址未配置", generic.getMessage());

            verify(jsapi, never()).prepayWithRequestPayment(any());
            verify(app, never()).prepayWithRequestPayment(any());
        }
    }

    @Test
    void configuredNotifyUrl_isSentOnMiniProgramAndAppPrepay() {
        WechatPayServiceImpl service = readyService(URL);
        PrepayWithRequestPaymentResponse jsapiResponse = new PrepayWithRequestPaymentResponse();
        jsapiResponse.setAppId("wx_mini");
        jsapiResponse.setTimeStamp("1412000000");
        jsapiResponse.setNonceStr("nonce");
        jsapiResponse.setPackageVal("prepay_id=wx1");
        jsapiResponse.setSignType("RSA");
        jsapiResponse.setPaySign("signed");
        when(jsapi.prepayWithRequestPayment(any())).thenReturn(jsapiResponse);

        Map<String, String> params = service.createGenericOrder("CUSTOM_1", new BigDecimal("9.90"), "城瘾推广位权益", "o_openid");
        assertEquals("prepay_id=wx1", params.get("package"));
        ArgumentCaptor<PrepayRequest> request = ArgumentCaptor.forClass(PrepayRequest.class);
        verify(jsapi).prepayWithRequestPayment(request.capture());
        assertEquals(URL, request.getValue().getNotifyUrl());

        com.wechat.pay.java.service.payments.app.model.PrepayWithRequestPaymentResponse appResponse =
                new com.wechat.pay.java.service.payments.app.model.PrepayWithRequestPaymentResponse();
        appResponse.setAppid("wx_native_app");
        appResponse.setPartnerId("1900000109");
        appResponse.setPrepayId("wx_prepay_1");
        appResponse.setPackageVal("Sign=WXPay");
        appResponse.setNonceStr("nonce-1");
        appResponse.setTimestamp("1412000000");
        appResponse.setSign("signed");
        when(app.prepayWithRequestPayment(any())).thenReturn(appResponse);
        service.createGenericAppOrder("DEP19T1000", new BigDecimal("50.00"), "城市定向合作保证金");
        ArgumentCaptor<com.wechat.pay.java.service.payments.app.model.PrepayRequest> appRequest =
                ArgumentCaptor.forClass(com.wechat.pay.java.service.payments.app.model.PrepayRequest.class);
        verify(app).prepayWithRequestPayment(appRequest.capture());
        assertEquals(URL, appRequest.getValue().getNotifyUrl());
    }

    @Test
    void applicationYml_notifyUrlDefaultsToEmpty() throws Exception {
        Path yml = Paths.get("src/main/resources/application.yml");
        if (!Files.exists(yml)) yml = Paths.get("chengyinhub-admin/src/main/resources/application.yml");
        String text = new String(Files.readAllBytes(yml), StandardCharsets.UTF_8);
        assertTrue(text.contains("    notify-url: ${CHENGYIN_WXPAY_NOTIFY_URL:}"),
                "支付通知地址默认必须为空,生产经环境变量显式注入");
        assertFalse(text.matches("(?s).*\\n\\s*notify-url:\\s*https?://.*"), "不得把支付回调写死成所有环境的默认值");
        assertFalse(text.matches("(?s).*CHENGYIN_WXPAY_NOTIFY_URL:https?://.*"), "不得给支付通知地址设生产默认值");
    }
}
