package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WechatPaymentCallbackRouterTest {
    private final IClubMembershipService club = mock(IClubMembershipService.class);
    private final IMerchantCommerceService commerce = mock(IMerchantCommerceService.class);
    private final ICoopInviteService deposit = mock(ICoopInviteService.class);
    private final ICmsRegistrationService registration = mock(ICmsRegistrationService.class);
    private final IRefundService refunds = mock(IRefundService.class);
    private final WechatPaymentCallbackRouter router = new WechatPaymentCallbackRouter(
            club, commerce, deposit, registration, refunds);

    @Test
    void allFourOwnersReceiveTheOriginalVerifiedAmountAndTransactionWithoutRewritingFacts() {
        when(club.supportsOutTradeNo("CLM1")).thenReturn(true);
        when(club.handlePaidInvoice("CLM1", true, 123, "TX-original", reference("CLM1"))).thenReturn(true);
        when(commerce.supportsOutTradeNo("TPLTM_1")).thenReturn(true);
        when(commerce.handlePaidOrder("TPLTM_1", true, 123, "TX-original", reference("TPLTM_1"))).thenReturn(true);
        when(deposit.markDepositPaid("DEP1", 123L, "TX-original", reference("DEP1"))).thenReturn(true);
        CmsRegistration order = new CmsRegistration(); order.setRegistrationNo("A20261024ABCDEF01");
        when(registration.getByRegistrationNo(order.getRegistrationNo())).thenReturn(order);
        when(registration.handleWechatPayCallback(anyMap())).thenReturn("SUCCESS");
        String[][] routes = {{"CLM1", "club-membership"}, {"TPLTM_1", "merchant-commerce"},
                {"DEP1", "coop-deposit"}, {order.getRegistrationNo(), "registration"}};
        for (String[] route : routes) {
            Map<String, String> facts = facts(route[0]);
            Map<String, String> original = new HashMap<>(facts);
            assertEquals(new WechatPaymentCallbackRouter.Outcome(route[1], "SUCCESS"), router.payment(facts));
            assertEquals(original, facts);
        }
        verify(club).handlePaidInvoice("CLM1", true, 123, "TX-original", reference("CLM1"));
        verify(commerce).handlePaidOrder("TPLTM_1", true, 123, "TX-original", reference("TPLTM_1"));
        verify(deposit).markDepositPaid("DEP1", 123L, "TX-original", reference("DEP1"));
        verify(registration).handleWechatPayCallback(argThat(f -> f.equals(facts(order.getRegistrationNo()))));
        verifyNoInteractions(refunds);
    }

    @Test
    void unknownNumberHasNoRegistrationFallbackAndDoesNotMutateAnyOwner() {
        var exception = assertThrows(WechatPaymentCallbackRouter.RoutingException.class,
                () -> router.payment(facts("UNKNOWN1")));
        assertEquals(0, exception.matches());
        verify(registration).getByRegistrationNo("UNKNOWN1");
        verifyNoMutations();
    }

    @Test
    void reservedUnknownMembershipCannotFallThroughToAnExistingRegistration() {
        assertEquals(new WechatPaymentCallbackRouter.Outcome("club-membership", "FAIL"),
                router.payment(facts("CLM_UNKNOWN")));
        verify(club).supportsOutTradeNo("CLM_UNKNOWN");
        verify(registration).getByRegistrationNo("CLM_UNKNOWN");
        verifyNoMoreInteractions(registration);
        verifyNoMutations();
    }

    @Test
    void overlappingExistingDomainClaimsRejectBeforeTheFirstBusinessWrite() {
        when(commerce.supportsOutTradeNo("CLM1")).thenReturn(true);
        var exception = assertThrows(WechatPaymentCallbackRouter.RoutingException.class,
                () -> router.payment(facts("CLM1")));
        assertEquals(2, exception.matches());
        verifyNoMutations();
    }

    @Test
    void everyClaimIsResolvedBeforeAnyMutationRegardlessOfOwnerOrder() {
        AtomicInteger writes = new AtomicInteger();
        WechatPaymentCallbackOwner first = owner("one", writes);
        WechatPaymentCallbackOwner second = owner("two", writes);
        for (List<WechatPaymentCallbackOwner> owners : List.of(List.of(first, second), List.of(second, first))) {
            var candidate = new WechatPaymentCallbackRouter(owners, f -> false, f -> false);
            assertEquals(2, assertThrows(WechatPaymentCallbackRouter.RoutingException.class,
                    () -> candidate.payment(facts("ORDER1"))).matches());
            assertEquals(0, writes.get());
        }
    }

    @Test
    void failedClaimResolutionCannotInvokeAnEarlierMatchingOwner() {
        AtomicInteger writes = new AtomicInteger();
        WechatPaymentCallbackOwner broken = mock(WechatPaymentCallbackOwner.class);
        when(broken.claims("ORDER1")).thenThrow(new IllegalStateException("owner lookup unavailable"));
        var candidate = new WechatPaymentCallbackRouter(List.of(owner("one", writes), broken), f -> false, f -> false);
        assertThrows(IllegalStateException.class, () -> candidate.payment(facts("ORDER1")));
        assertEquals(0, writes.get());
        verify(broken, never()).apply(anyMap());
    }

    @Test
    void successfulAckIsReturnedOnlyAfterTheOriginalOwnerReturnsAndLowercaseIsPreserved() {
        CmsRegistration order = new CmsRegistration(); order.setRegistrationNo("REG1");
        when(registration.getByRegistrationNo("REG1")).thenReturn(order);
        when(registration.handleWechatPayCallback(anyMap())).thenReturn("FAIL", "success");
        assertEquals("FAIL", router.payment(facts("REG1")).result());
        assertEquals("success", router.payment(facts("REG1")).result());
        when(registration.handleWechatPayCallback(anyMap())).thenThrow(new IllegalStateException("commit failed"));
        assertThrows(IllegalStateException.class, () -> router.payment(facts("REG1")));
        verify(registration, times(3)).handleWechatPayCallback(anyMap());
    }

    @Test
    void blankOrMissingIdentityAndMutableFactsNeverReachABusinessMutation() {
        assertEquals(0, assertThrows(WechatPaymentCallbackRouter.RoutingException.class,
                () -> router.payment(null)).matches());
        assertThrows(WechatPaymentCallbackRouter.RoutingException.class, () -> router.payment(Map.of()));
        assertThrows(WechatPaymentCallbackRouter.RoutingException.class,
                () -> router.payment(Map.of("out_trade_no", " ")));
        var adapter = new WechatPaymentCallbackOwner() {
            public String route() { return "test"; }
            public boolean claims(String no) { return true; }
            public String apply(Map<String, String> original) {
                assertThrows(UnsupportedOperationException.class, () -> original.put("appid", "different-app"));
                assertEquals("wx-original", original.get("appid"));
                return "FAIL";
            }
        };
        assertEquals("FAIL", new WechatPaymentCallbackRouter(List.of(adapter), f -> false, f -> false)
                .payment(facts("ORDER1")).result());
        verifyNoInteractions(club, commerce, deposit, registration, refunds);
    }

    @Test
    void realFacadeRejectsUnknownAndMultipleOwnersAfterVerificationWithOneSafeDiagnostic() {
        for (boolean multiple : new boolean[]{false, true}) {
            WechatPayServiceImpl facade = new WechatPayServiceImpl();
            WechatPaymentSdkGateway sdk = mock(WechatPaymentSdkGateway.class);
            AlarmService alarm = mock(AlarmService.class);
            var transaction = new com.wechat.pay.java.service.payments.model.Transaction();
            transaction.setOutTradeNo("WX-original");
            when(sdk.parsePaymentNotification("body", Map.of())).thenReturn(transaction);
            when(sdk.verifiedPaymentFacts(transaction)).thenReturn(facts("UNKNOWN1"));
            AtomicInteger writes = new AtomicInteger();
            List<WechatPaymentCallbackOwner> owners = multiple
                    ? List.of(owner("one", writes), owner("two", writes)) : List.of();
            var routes = new WechatPaymentCallbackRouter(owners, f -> false, f -> false);
            org.springframework.test.util.ReflectionTestUtils.setField(facade, "gateway", sdk);
            org.springframework.test.util.ReflectionTestUtils.setField(facade, "callbacks", routes);
            org.springframework.test.util.ReflectionTestUtils.setField(facade, "alarmService", alarm);
            assertTrue(facade.handlePayCallback("body", Map.of()).contains("FAIL"));
            assertEquals(0, writes.get());
            verify(alarm).alarmThrottled("payment.callback.routing", "支付回调业务路由不唯一",
                    "order=WX-original matches=" + (multiple ? 2 : 0) + " result=FAIL");
            verifyNoMoreInteractions(alarm);
            var order = inOrder(sdk);
            order.verify(sdk).parsePaymentNotification("body", Map.of());
            order.verify(sdk).verifiedPaymentFacts(transaction);
        }
    }

    @Test
    void missingDepositReferenceIdCannotReachTheTypedOrLegacyOwner() {
        Map<String,String> unproven = new HashMap<>(facts("DEP1")); unproven.remove("verified_fact_id");
        assertThrows(IllegalArgumentException.class,()->router.payment(unproven));
        verifyNoInteractions(deposit);
    }

    @Test
    void missingDepositReferenceHashIsRejectedBeforeTheTypedOwner() {
        Map<String,String> unproven = new HashMap<>(facts("DEP1"));unproven.remove("verified_fact_hash");
        assertThrows(IllegalArgumentException.class,()->router.payment(unproven));
        verifyNoInteractions(deposit);
    }

    /** Pure router boundary fixture, not a claim that this unit class performs provider verification/storage. */
    private static com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference(String no) {
        var original = new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact(
                com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT,
                "merchant-original","wx-original",no,"WX-original","WX-original","TX-original","TX-original",
                "APP","CNY",123,123,java.time.Instant.ofEpochSecond(1700000000),null,"SUCCESS");
        return new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(7L,original.factHash());
    }

    private void verifyNoMutations() {
        verify(club, never()).handlePaidInvoice(anyString(), anyBoolean(), any(), anyString(), any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class));
        verify(commerce, never()).handlePaidOrder(anyString(), anyBoolean(), any(), anyString(), any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class));
        verify(deposit, never()).markDepositPaid(anyString(), any(), anyString(), any());
        verify(registration, never()).handleWechatPayCallback(anyMap());
        verifyNoInteractions(refunds);
    }
    private static WechatPaymentCallbackOwner owner(String route, AtomicInteger writes) {
        return new WechatPaymentCallbackOwner() {
            public String route() { return route; }
            public boolean claims(String no) { return true; }
            public String apply(Map<String, String> facts) { writes.incrementAndGet(); return "SUCCESS"; }
        };
    }
    private static Map<String, String> facts(String no) {
        Map<String,String> facts = new HashMap<>();
        facts.putAll(Map.of("out_trade_no", no, "wx_out_trade_no", "WX-original", "transaction_id", "TX-original",
                "trade_state", "SUCCESS", "total", "123", "currency", "CNY", "appid", "wx-original",
                "mchid", "merchant-original", "trade_type", "APP"));
        facts.put("verified_fact_id",Long.toString(reference(no).id()));
        facts.put("verified_fact_hash",reference(no).hash());
        return facts;
    }
}
