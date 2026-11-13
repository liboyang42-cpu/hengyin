package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.platformpass.SelfPlayXpayException;
import com.chengyinhub.business.platformpass.SelfPlayXpayService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IRegistrationOrderCreateGate;
import com.chengyinhub.business.service.RegistrationCheckoutException;
import com.chengyinhub.business.service.RegistrationCheckoutResult;
import com.chengyinhub.business.service.WechatPaymentOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 服务端决定自玩票支付渠道：虚拟支付单不发起 JSAPI 尝试、客户端不能强制 JSAPI；开关关闭时与现状一致。 */
class SelfPlayXpayCheckoutRoutingTest {
    private static final long MEMBER = 42L;
    private static final long REG_ID = 900L;

    private RegistrationCheckoutServiceImpl service;
    private ICmsRegistrationService registrations;
    private WechatPaymentOperations wechat;
    private SelfPlayXpayService xpay;
    private CmsRegistration registration;

    @BeforeEach
    void setUp() {
        service = new RegistrationCheckoutServiceImpl();
        registrations = mock(ICmsRegistrationService.class);
        wechat = mock(WechatPaymentOperations.class);
        xpay = mock(SelfPlayXpayService.class);
        ReflectionTestUtils.setField(service, "registrationService", registrations);
        ReflectionTestUtils.setField(service, "wechatPayService", wechat);
        ReflectionTestUtils.setField(service, "registrationOrderCreateGate", mock(IRegistrationOrderCreateGate.class));
        ReflectionTestUtils.setField(service, "selfPlayXpay", xpay);
        registration = new CmsRegistration();
        registration.setId(REG_ID);
        registration.setMemberId(MEMBER);
        registration.setRegistrationNo("T20261113ABCD1234");
        registration.setPaymentStatus(0);
        registration.setRegistrationStatus(1);
        registration.setPayableAmount(new BigDecimal("39.90"));
        when(registrations.selectCmsRegistrationById(REG_ID)).thenReturn(registration);
        when(registrations.completeZeroAmountPaymentForRetry(REG_ID, MEMBER)).thenReturn(null);
        when(registrations.requirePayableForXpay(REG_ID, MEMBER)).thenReturn(registration);
    }

    @Test
    void xpayRouteWithoutJsCodeReturnsIntentAndNeverStartsJsapiAttempt() {
        when(xpay.routeFor(registration)).thenReturn(SelfPlayXpayService.Route.XPAY);
        RegistrationCheckoutResult result = service.retryPayment(MEMBER, REG_ID, false);
        assertEquals("XPAY", result.getData().get("payChannel"));
        assertEquals(Boolean.TRUE, result.getData().get("jsCodeRequired"));
        assertTrue(!result.getData().containsKey("payParams"));
        verify(registrations, never()).beginPaymentAttempt(anyLong(), anyLong());
        verify(wechat, never()).createOrder(any());
        verify(xpay, never()).prepare(anyLong(), any(), any());
    }

    @Test
    void xpayRouteWithJsCodeReturnsVirtualPaymentParams() {
        when(xpay.routeFor(registration)).thenReturn(SelfPlayXpayService.Route.XPAY);
        Map<String, Object> prepared = new HashMap<>();
        prepared.put("payChannel", "XPAY");
        prepared.put("virtualPayment", Map.of("mode", "short_series_goods"));
        when(xpay.prepare(eq(MEMBER), eq(registration), eq("code-1"))).thenReturn(prepared);
        RegistrationCheckoutResult result = service.retryPayment(MEMBER, REG_ID, false, "code-1");
        assertTrue(result.getData().containsKey("virtualPayment"));
        verify(wechat, never()).createOrder(any());
    }

    @Test
    void clientCannotForceJsapiOnAnXpayRegistrationViaAppChannel() {
        when(xpay.routeFor(registration)).thenReturn(SelfPlayXpayService.Route.XPAY);
        RegistrationCheckoutException e = assertThrows(RegistrationCheckoutException.class,
                () -> service.retryPayment(MEMBER, REG_ID, true, "code-1"));
        assertEquals(409, e.getCode());
        verify(wechat, never()).createAppOrder(any());
        verify(registrations, never()).beginPaymentAttempt(anyLong(), anyLong());
    }

    @Test
    void xpayConfigurationFailureSurfacesAsUnavailableNotJsapiFallback() {
        when(xpay.routeFor(registration)).thenReturn(SelfPlayXpayService.Route.XPAY);
        when(xpay.prepare(anyLong(), any(), any())).thenThrow(new SelfPlayXpayException("XPAY_NOT_CONFIGURED", "支付暂不可用"));
        RegistrationCheckoutException e = assertThrows(RegistrationCheckoutException.class,
                () -> service.retryPayment(MEMBER, REG_ID, false, "code-1"));
        assertEquals(503, e.getCode());
        verify(wechat, never()).createOrder(any());
    }

    @Test
    void jsapiRouteKeepsTheOriginalPathUnchanged() {
        when(xpay.routeFor(registration)).thenReturn(SelfPlayXpayService.Route.JSAPI);
        when(wechat.isReady()).thenReturn(true);
        when(registrations.beginPaymentAttempt(REG_ID, MEMBER)).thenReturn(registration);
        Map<String, String> params = new HashMap<>();
        params.put("package", "prepay_id=x");
        when(wechat.createOrder(registration)).thenReturn(params);
        RegistrationCheckoutResult result = service.retryPayment(MEMBER, REG_ID, false);
        assertEquals(params, result.getData().get("payParams"));
        verify(registrations, never()).requirePayableForXpay(anyLong(), anyLong());
    }

    @Test
    void cancelIsFencedWhileAnXpayOrderMayBePaid() {
        when(xpay.readyToClose(REG_ID)).thenReturn(false);
        RegistrationCheckoutException e = assertThrows(RegistrationCheckoutException.class,
                () -> service.cancelUnpaid(MEMBER, REG_ID, "u"));
        assertEquals(409, e.getCode());
        verify(registrations, never()).cancelRegistration(anyLong(), anyLong(), any());
    }
}
