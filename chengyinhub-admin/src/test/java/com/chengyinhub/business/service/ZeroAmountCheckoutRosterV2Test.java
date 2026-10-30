package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.RegistrationOrderResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.service.impl.RegistrationCheckoutServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CL-16(Java 侧):免费(0 元)报名在下单编排里必须走 completeZeroAmountPayment,把
 * payment_status 落成 2 —— 主理人名册 selectPaidRoster 只收 payment_status=2 且
 * registration_status=2 的行,不走这一步玩家看得到订单、名册上却没有人。
 */
class ZeroAmountCheckoutRosterV2Test {

    private static final Long MEMBER = 9901L;

    private ICmsRegistrationService registrationService;
    private IRegistrationOrderService orderService;
    private IWechatPayService wechatPayService;
    private RegistrationCheckoutServiceImpl checkout;

    @BeforeEach
    void setUp() {
        registrationService = mock(ICmsRegistrationService.class);
        orderService = mock(IRegistrationOrderService.class);
        wechatPayService = mock(IWechatPayService.class);
        checkout = new RegistrationCheckoutServiceImpl();
        ReflectionTestUtils.setField(checkout, "registrationService", registrationService);
        ReflectionTestUtils.setField(checkout, "registrationOrderService", orderService);
        ReflectionTestUtils.setField(checkout, "wechatPayService", wechatPayService);
    }

    private RegistrationRequest givenOrder(BigDecimal payable, Integer paymentStatus) {
        CmsRegistration r = new CmsRegistration();
        r.setId(77L);
        r.setRegistrationNo("REG-FREE-77");
        r.setOwnerType(2L);
        r.setPayableAmount(payable);
        r.setPaymentStatus(paymentStatus);
        RegistrationRequest request = new RegistrationRequest();
        when(orderService.createOrReplay(MEMBER, request))
                .thenReturn(new RegistrationOrderResult(Collections.singletonList(r), false));
        return request;
    }

    @Test
    void freeOrderIsCompletedAsPaidSoItReachesTheRoster() {
        RegistrationRequest request = givenOrder(BigDecimal.ZERO, 1);
        when(registrationService.completeZeroAmountPayment("REG-FREE-77")).thenReturn("SUCCESS");

        RegistrationCheckoutResult result = checkout.createOrReplay(MEMBER, request);

        assertEquals("报名创建成功", result.getMessage());
        verify(registrationService).completeZeroAmountPayment("REG-FREE-77");
        verify(wechatPayService, never()).createOrder(any());
        verify(registrationService, never()).beginPaymentAttempt(anyLong(), anyLong());
    }

    /** 零元确认失败不能对玩家报成功(否则玩家侧显示报名成功、名册上没有人)。 */
    @Test
    void zeroAmountConfirmationFailureIsNotReportedAsSuccess() {
        RegistrationRequest request = givenOrder(BigDecimal.ZERO, 1);
        when(registrationService.completeZeroAmountPayment("REG-FREE-77")).thenReturn("FAIL");

        assertThrows(RegistrationCheckoutException.class, () -> checkout.createOrReplay(MEMBER, request));
    }

    /** 负控:付费单不走零元确认。 */
    @Test
    void paidOrderDoesNotUseZeroAmountCompletion() {
        RegistrationRequest request = givenOrder(new BigDecimal("19.90"), 1);
        when(wechatPayService.isReady()).thenReturn(true);
        when(registrationService.beginPaymentAttempt(77L, MEMBER)).thenReturn(new CmsRegistration());

        checkout.createOrReplay(MEMBER, request);

        verify(registrationService, never()).completeZeroAmountPayment(anyString());
    }

    @Test
    void bothRetryChannelsUseOnlyActorBoundOriginalZeroOrderCompletion() {
        CmsRegistration original = new CmsRegistration();
        original.setId(77L); original.setRegistrationNo("REG-FREE-77");
        original.setMemberId(MEMBER); original.setPayableAmount(BigDecimal.ZERO);
        original.setWechatPaymentAmount(BigDecimal.ZERO); original.setPaymentStatus(2);
        original.setRegistrationStatus(2);
        when(registrationService.completeZeroAmountPaymentForRetry(77L, MEMBER))
                .thenReturn(Collections.singletonList(original));
        for (boolean app : new boolean[]{false, true}) {
            RegistrationCheckoutResult result = checkout.retryPayment(MEMBER, 77L, app);
            assertEquals(77L, result.getData().get("registrationId"));
            assertEquals(Collections.singletonList(77L), result.getData().get("registrationIds"));
            assertEquals(2, result.getData().get("paymentStatus"));
            assertEquals(2, result.getData().get("registrationStatus"));
            assertEquals(false, result.getData().containsKey("payParams"));
        }
        org.mockito.Mockito.verifyNoInteractions(wechatPayService, orderService);
        verify(registrationService, never()).beginPaymentAttempt(anyLong(), anyLong());
        verify(registrationService, never()).completeZeroAmountPayment(anyString());
    }
}
