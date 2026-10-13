package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.PaymentPreorderException;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 原生 App 合作保证金支付的公开 Controller 合同。 */
class ApiCoopDepositAppPaymentTest {

    private static final Long MEMBER_ID = 42L;
    private static final Long INVITE_ID = 19L;

    private ApiCoopController controller;
    private ICoopInviteService coopInviteService;
    private IWechatPayService wechatPayService;

    @BeforeEach
    void setUp() {
        controller = new ApiCoopController() {
            @Override
            public Long getAppUserId() {
                return MEMBER_ID;
            }
        };
        coopInviteService = mock(ICoopInviteService.class);
        wechatPayService = mock(IWechatPayService.class);
        when(wechatPayService.isAppReady()).thenReturn(true);
        ReflectionTestUtils.setField(controller, "coopInviteService", coopInviteService);
        ReflectionTestUtils.setField(controller, "wechatPayService", wechatPayService);
    }

    @Test
    void currentOwedPartyReceivesNativeAppPaymentContract() {
        Map<String, Object> prepared = new LinkedHashMap<>();
        prepared.put("outTradeNo", "DEP19T1000");
        prepared.put("amount", new BigDecimal("50.00"));
        prepared.put("payerMemberId", MEMBER_ID);
        when(coopInviteService.prepareDeposit(INVITE_ID, MEMBER_ID)).thenReturn(prepared);

        Map<String, String> appPayment = new LinkedHashMap<>();
        appPayment.put("appId", "wx_app");
        appPayment.put("partnerId", "1900000109");
        appPayment.put("prepayId", "wx201410272009395522657a690389285100");
        appPayment.put("packageValue", "Sign=WXPay");
        appPayment.put("nonceStr", "5K8264ILTKCH16CQ2502SI8ZNMTM67VS");
        appPayment.put("timeStamp", "1412000000");
        appPayment.put("sign", "signed");
        when(wechatPayService.createGenericAppOrder(
                "DEP19T1000", new BigDecimal("50.00"), "城市定向合作保证金"))
                .thenReturn(appPayment);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inviteId", INVITE_ID);
        AjaxResult result = controller.depositCreateApp(body);

        assertEquals(200, result.get("code"));
        assertSame(appPayment, result.get("data"));
        verify(coopInviteService).prepareDeposit(INVITE_ID, MEMBER_ID);
        verify(wechatPayService).createGenericAppOrder(
                "DEP19T1000", new BigDecimal("50.00"), "城市定向合作保证金");
    }

    @Test
    void endpointIsPublishedAtTheAppDepositRoute() throws Exception {
        org.springframework.web.bind.annotation.PostMapping mapping = ApiCoopController.class
                .getDeclaredMethod("depositCreateApp", Map.class)
                .getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);

        assertArrayEquals(new String[] {"/deposit/create/app"}, mapping.value());
    }

    @Test
    void missingLoginIdentityFailsBeforePreparingDeposit() {
        ApiCoopController anonymous = new ApiCoopController() {
            @Override
            public Long getAppUserId() {
                return null;
            }
        };
        ReflectionTestUtils.setField(anonymous, "coopInviteService", coopInviteService);
        ReflectionTestUtils.setField(anonymous, "wechatPayService", wechatPayService);

        AjaxResult result = anonymous.depositCreateApp(bodyWithInvite());

        assertEquals(500, result.get("code"));
        assertEquals("请先登录", result.get("msg"));
        verify(coopInviteService, never()).prepareDeposit(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(wechatPayService, never()).createGenericAppOrder(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonOwedPartyIsRejectedBySharedDepositRules() {
        when(coopInviteService.prepareDeposit(INVITE_ID, MEMBER_ID))
                .thenThrow(new IllegalStateException("仅应缴方可缴纳保证金"));

        AjaxResult result = controller.depositCreateApp(bodyWithInvite());

        assertEquals(500, result.get("code"));
        assertEquals("仅应缴方可缴纳保证金", result.get("msg"));
        verify(wechatPayService, never()).createGenericAppOrder(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingInviteFailsBeforeDepositPreparation() {
        AjaxResult result = controller.depositCreateApp(new LinkedHashMap<>());

        assertEquals(500, result.get("code"));
        assertEquals("缺少邀约", result.get("msg"));
        verify(coopInviteService, never()).prepareDeposit(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingAppPaymentConfigurationFailsBeforeDepositPreparation() {
        when(wechatPayService.isAppReady()).thenReturn(false);

        AjaxResult result = controller.depositCreateApp(bodyWithInvite());

        assertEquals(503, result.get("code"));
        assertEquals("App 支付服务暂不可用，请稍后再试", result.get("msg"));
        verify(coopInviteService, never()).prepareDeposit(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(wechatPayService, never()).createGenericAppOrder(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void technicalPaymentFailureIsMasked() {
        Map<String, Object> prepared = new LinkedHashMap<>();
        prepared.put("outTradeNo", "DEP19T1000");
        prepared.put("amount", new BigDecimal("50.00"));
        when(coopInviteService.prepareDeposit(INVITE_ID, MEMBER_ID)).thenReturn(prepared);
        when(wechatPayService.createGenericAppOrder(
                "DEP19T1000", new BigDecimal("50.00"), "城市定向合作保证金"))
                .thenThrow(PaymentPreorderException.resultUncertain(
                        "创建 App 支付订单结果未知：java.net.SocketTimeoutException", null));

        AjaxResult result = controller.depositCreateApp(bodyWithInvite());

        assertEquals(500, result.get("code"));
        assertEquals("支付结果确认中，请稍后查看，勿重复支付", result.get("msg"));
    }

    private Map<String, Object> bodyWithInvite() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inviteId", INVITE_ID);
        return body;
    }
}
