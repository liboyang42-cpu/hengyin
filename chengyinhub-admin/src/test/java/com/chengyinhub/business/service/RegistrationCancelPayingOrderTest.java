package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.impl.RegistrationCheckoutServiceImpl;
import com.chengyinhub.common.constant.EntitlementInvalidReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ROUND10 j6 F2:关掉微信支付面板后的单(payment_status=1)要能取消并放回票,
 * 但真在付款的单(USERPAYING / SUCCESS / 查不清)不能被取消。
 * 旧行为:/api/registration/cancel 直接走 cancelUnpaidIfActive(只收 0/3),一律「该报名状态已变化,无法取消」。
 */
class RegistrationCancelPayingOrderTest {
    private static final Long MEMBER = 11L;
    private static final Long REG = 75L;
    private static final String NO = "REG75";
    private static final String WX = "REG75_1";

    private ICmsRegistrationService registrations;
    private WechatPaymentOperations gateway;
    private CmsRegistrationMapper mapper;
    private RegistrationCheckoutServiceImpl checkout;

    @BeforeEach
    void fixture() {
        registrations = mock(ICmsRegistrationService.class);
        gateway = mock(WechatPaymentOperations.class);
        mapper = mock(CmsRegistrationMapper.class);
        checkout = new RegistrationCheckoutServiceImpl();
        ReflectionTestUtils.setField(checkout, "registrationService", registrations);
        ReflectionTestUtils.setField(checkout, "registrationOrderService", mock(IRegistrationOrderService.class));
        ReflectionTestUtils.setField(checkout, "wechatPayService", gateway);
        ReflectionTestUtils.setField(checkout, "cmsRegistrationMapper", mapper);
        when(gateway.isReady()).thenReturn(true);
    }

    private static CmsRegistration row(Long id, int paymentStatus, String parent) {
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        r.setMemberId(MEMBER);
        r.setRegistrationNo(id.equals(REG) ? NO : "REG" + id);
        r.setPaymentStatus(paymentStatus);
        r.setRegistrationStatus(1);
        r.setParentOrderNo(parent);
        return r;
    }

    private static Map<String, String> facts(String state) {
        Map<String, String> m = new HashMap<>();
        m.put("trade_state", state);
        m.put("out_trade_no", NO);
        m.put("wx_out_trade_no", WX);
        return m;
    }

    private CmsRegistration paying() {
        CmsRegistration r = row(REG, 1, null);
        when(registrations.selectCmsRegistrationById(REG)).thenReturn(r);
        return r;
    }

    @Test
    void unpaidOrderKeepsTheExistingCancelPath() {
        when(registrations.selectCmsRegistrationById(REG)).thenReturn(row(REG, 0, null));
        when(registrations.cancelRegistration(REG, MEMBER, "u")).thenReturn(true);
        assertTrue(checkout.cancelUnpaid(MEMBER, REG, "u"));
        verify(registrations).cancelRegistration(REG, MEMBER, "u");
        verifyNoInteractions(gateway);
    }

    @Test
    void closedPaySheetIsClosedAtWechatFirstThenLocallyWithTicketRelease() {
        CmsRegistration r = paying();
        when(gateway.queryOrderStatus(NO)).thenReturn(facts("NOTPAY"));
        when(gateway.closePaymentAttempt(NO, WX)).thenReturn(true);
        when(registrations.settleClosedPayingOne(r, WX, EntitlementInvalidReason.ORGANIZER_CANCEL, "u", 3))
                .thenReturn(true);

        assertTrue(checkout.cancelUnpaid(MEMBER, REG, "u"));

        InOrder order = inOrder(gateway, registrations);
        order.verify(gateway).queryOrderStatus(NO);
        order.verify(gateway).closePaymentAttempt(NO, WX);
        // registration_status=3「已取消」;settleClosedPayingOne 回补库存/积分/权益并核对当前尝试
        order.verify(registrations).settleClosedPayingOne(r, WX, EntitlementInvalidReason.ORGANIZER_CANCEL, "u", 3);
        verify(registrations, never()).cancelRegistration(anyLong(), anyLong(), anyString());
    }

    @Test
    void alreadyClosedAttemptIsSettledWithoutAnotherClose() {
        CmsRegistration r = paying();
        when(gateway.queryOrderStatus(NO)).thenReturn(facts("CLOSED"));
        when(registrations.settleClosedPayingOne(r, WX, EntitlementInvalidReason.ORGANIZER_CANCEL, "u", 3))
                .thenReturn(true);
        assertTrue(checkout.cancelUnpaid(MEMBER, REG, "u"));
        verify(gateway, never()).closePaymentAttempt(anyString(), anyString());
    }

    @Test
    void userStillPayingCannotBeCancelled() {
        paying();
        when(gateway.queryOrderStatus(NO)).thenReturn(facts("USERPAYING"));
        RegistrationCheckoutException e = assertThrows(RegistrationCheckoutException.class,
                () -> checkout.cancelUnpaid(MEMBER, REG, "u"));
        assertEquals(409, e.getCode());
        verify(gateway, never()).closePaymentAttempt(anyString(), anyString());
        verify(registrations, never()).settleClosedPayingOne(any(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void paidOrderIsRecordedAsPaidAndNotCancelled() {
        paying();
        Map<String, String> success = facts("SUCCESS");
        when(gateway.queryOrderStatus(NO)).thenReturn(success);
        assertThrows(RegistrationCheckoutException.class, () -> checkout.cancelUnpaid(MEMBER, REG, "u"));
        verify(registrations).handleWechatPayCallback(success);
        verify(gateway, never()).closePaymentAttempt(anyString(), anyString());
        verify(registrations, never()).settleClosedPayingOne(any(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void unprovableProviderStateLeavesTheOrderAlone() {
        paying();
        when(gateway.queryOrderStatus(NO)).thenThrow(new WechatOrderNotExistException("missing", null));
        assertThrows(RegistrationCheckoutException.class, () -> checkout.cancelUnpaid(MEMBER, REG, "u"));
        verify(gateway, never()).closePaymentAttempt(anyString(), anyString());
        verify(registrations, never()).settleClosedPayingOne(any(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void unconfirmedProviderCloseLeavesTheOrderAlone() {
        paying();
        when(gateway.queryOrderStatus(NO)).thenReturn(facts("NOTPAY"));
        when(gateway.closePaymentAttempt(NO, WX)).thenReturn(false);
        assertThrows(RegistrationCheckoutException.class, () -> checkout.cancelUnpaid(MEMBER, REG, "u"));
        verify(registrations, never()).settleClosedPayingOne(any(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void newPaymentAttemptStartedMeanwhileWinsOverTheCancel() {
        CmsRegistration r = paying();
        when(gateway.queryOrderStatus(NO)).thenReturn(facts("NOTPAY"));
        when(gateway.closePaymentAttempt(NO, WX)).thenReturn(true);
        // 「继续支付」在关单与本地收口之间发起了新尝试:原尝试不再是当前尝试,收口返回 false
        when(registrations.settleClosedPayingOne(r, WX, EntitlementInvalidReason.ORGANIZER_CANCEL, "u", 3))
                .thenReturn(false);
        RegistrationCheckoutException e = assertThrows(RegistrationCheckoutException.class,
                () -> checkout.cancelUnpaid(MEMBER, REG, "u"));
        assertEquals(409, e.getCode());
    }

    @Test
    void zeroAmountSiblingOfPayingPrimaryIsClosedThroughThePrimary() {
        CmsRegistration sibling = row(76L, 0, "P1");
        CmsRegistration primary = row(REG, 1, "P1");
        when(registrations.selectCmsRegistrationById(76L)).thenReturn(sibling);
        when(mapper.selectByParentOrderNo("P1")).thenReturn(List.of(primary, sibling));
        when(gateway.queryOrderStatus(NO)).thenReturn(facts("NOTPAY"));
        when(gateway.closePaymentAttempt(NO, WX)).thenReturn(true);
        when(registrations.settleClosedPayingOne(primary, WX, EntitlementInvalidReason.ORGANIZER_CANCEL, "u", 3))
                .thenReturn(true);
        assertTrue(checkout.cancelUnpaid(MEMBER, 76L, "u"));
        verify(registrations, never()).cancelRegistration(anyLong(), anyLong(), anyString());
    }

    @Test
    void anotherMembersOrderIsRefusedBeforeAnyProviderCall() {
        CmsRegistration r = row(REG, 1, null);
        r.setMemberId(99L);
        when(registrations.selectCmsRegistrationById(REG)).thenReturn(r);
        RegistrationCheckoutException e = assertThrows(RegistrationCheckoutException.class,
                () -> checkout.cancelUnpaid(MEMBER, REG, "u"));
        assertEquals(403, e.getCode());
        verifyNoInteractions(gateway);
    }
}
