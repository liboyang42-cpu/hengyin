package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WechatOrderCenterGatewayTest {
    private static final String NOTIFY = "https://api.example.com/pay/callback";
    private JsapiServiceExtension jsapi;

    private WechatPayServiceImpl ready(String orderNo) {
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.isWechatPayEnabled()).thenReturn(true);
        when(config.getAppid()).thenReturn("wx_mini");
        when(config.getMerchantId()).thenReturn("1900000109");
        when(config.getNotifyUrl()).thenReturn(NOTIFY);
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatPaymentIntentTestSupport.install(service, Map.of(orderNo, new BigDecimal("9.90")));
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
        jsapi = mock(JsapiServiceExtension.class);
        WechatVerifiedFactTestSupport.setField(service, "jsapiServiceExtension", jsapi);
        WechatVerifiedFactTestSupport.setField(service, "refundService",
                mock(com.wechat.pay.java.service.refund.RefundService.class));
        WechatVerifiedFactTestSupport.setField(service, "notificationParser",
                mock(com.wechat.pay.java.core.notification.NotificationParser.class));
        PrepayWithRequestPaymentResponse response = new PrepayWithRequestPaymentResponse();
        response.setAppId("wx_mini");
        response.setTimeStamp("1412000000");
        response.setNonceStr("nonce");
        response.setPackageVal("prepay_id=unit");
        response.setSignType("RSA");
        response.setPaySign("unit-signature");
        when(jsapi.prepayWithRequestPayment(any())).thenReturn(response);
        return service;
    }

    @Test
    void genericJsapiDescriptionPreservesProductAndFits127WithoutBrokenUnicode() {
        String[][] cases = {
                {"商品".repeat(63) + "票", "商品".repeat(63) + "票"},
                {"城".repeat(128), "城".repeat(124) + "..."},
                {"a".repeat(123) + "\uD83D\uDE80" + "tail", "a".repeat(123) + "..."},
                {"a".repeat(128), "a".repeat(124) + "..."}
        };
        for (int i = 0; i < cases.length; i++) {
            String orderNo = "CUSTOM_DESC" + i;
            WechatPayServiceImpl service = ready(orderNo);
            service.createGenericOrder(orderNo, new BigDecimal("9.90"), cases[i][0], "unit-openid");
            ArgumentCaptor<PrepayRequest> request = ArgumentCaptor.forClass(PrepayRequest.class);
            verify(jsapi).prepayWithRequestPayment(request.capture());
            assertEquals(cases[i][1], request.getValue().getDescription());
            assertTrue(request.getValue().getDescription().length() <= 127);
            assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(request.getValue().getDescription()));
            assertEquals(990, request.getValue().getAmount().getTotal());
            assertEquals("unit-openid", request.getValue().getPayer().getOpenid());
            assertEquals(NOTIFY, request.getValue().getNotifyUrl());
        }
    }

    @Test
    void registrationUsesTheSameDescriptionLimitAndPreservesPaymentFacts() {
        WechatPaymentSdkGateway gateway = new WechatPaymentSdkGateway();
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.getAppid()).thenReturn("wx_mini");
        when(config.getMerchantId()).thenReturn("1900000109");
        when(config.getNotifyUrl()).thenReturn(NOTIFY);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        UmsMember member = new UmsMember();
        member.setWxOpenId("unit-openid");
        when(members.selectUmsMemberById(7L)).thenReturn(member);
        ReflectionTestUtils.setField(gateway, "wxPayConfig", config);
        ReflectionTestUtils.setField(gateway, "paymentMemberMapper", members);
        CmsRegistration registration = new CmsRegistration();
        registration.setMemberId(7L);
        registration.setRegistrationNo("REG-DESC-0001");
        registration.setWechatPaymentAmount(new BigDecimal("9.90"));
        registration.setActivityTitle("城".repeat(126));
        registration.setTicketName("票");
        PrepayRequest request = ReflectionTestUtils.invokeMethod(gateway, "buildPrepayRequest", registration);
        assertNotNull(request);
        assertEquals("城".repeat(124) + "...", request.getDescription());
        assertEquals("REG-DESC-0001", request.getOutTradeNo());
        assertEquals(990, request.getAmount().getTotal());
        assertEquals("unit-openid", request.getPayer().getOpenid());
        assertEquals(NOTIFY, request.getNotifyUrl());
    }

    @Test
    void facadeResolvesExactAttemptAndLegacyNumberWithoutSdkOrPaymentDispatch() {
        WechatPaymentSdkGateway gateway = new WechatPaymentSdkGateway();
        WechatPaymentIntentStore intents = mock(WechatPaymentIntentStore.class);
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.getMerchantId()).thenReturn("1900000109");
        String attempt = "WXP" + "a".repeat(29);
        when(intents.businessPaymentOrderNo(attempt, "1900000109")).thenReturn("REG-ORIGINAL-1");
        when(intents.businessPaymentOrderNo("REG-LEGACY-2", "1900000109")).thenReturn("REG-LEGACY-2");
        ReflectionTestUtils.setField(gateway, "wxPayConfig", config);
        ReflectionTestUtils.setField(gateway, "intents", intents);
        WechatPayServiceImpl facade = new WechatPayServiceImpl();
        ReflectionTestUtils.setField(facade, "gateway", gateway);
        assertEquals("REG-ORIGINAL-1", facade.resolveBusinessOrderNo(attempt));
        assertEquals("REG-LEGACY-2", facade.resolveBusinessOrderNo("REG-LEGACY-2"));
        verify(intents).businessPaymentOrderNo(attempt, "1900000109");
        verify(intents).businessPaymentOrderNo("REG-LEGACY-2", "1900000109");
        verifyNoMoreInteractions(intents);
        verify(config, never()).isWechatPayEnabled();
    }

    @Test
    void missingConfiguredMerchantRefusesBeforeMapping() {
        for (String merchant : new String[]{null, "", " "}) {
            WechatPaymentSdkGateway gateway = new WechatPaymentSdkGateway();
            WxPayConfig config = mock(WxPayConfig.class);
            when(config.getMerchantId()).thenReturn(merchant);
            WechatPaymentIntentStore intents = mock(WechatPaymentIntentStore.class);
            ReflectionTestUtils.setField(gateway, "wxPayConfig", config);
            ReflectionTestUtils.setField(gateway, "intents", intents);
            assertThrows(IllegalStateException.class, () -> gateway.resolveBusinessOrderNo("REG-0001"));
            verifyNoInteractions(intents);
        }
    }
}
