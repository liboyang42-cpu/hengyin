package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.IClubMembershipService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IMarketingProductPurchaseService;
import com.chengyinhub.business.service.IMerchantCommerceService;
import com.chengyinhub.business.service.IRefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MKO(营销商品现金单)路由契约:号段绝不落到 registration 兜底,承载方仍需真实事实校验。
 *
 * <p>注:只证明路由分派与调用参数,不证明 DB/HTTP;真实支付与 cash-enabled 默认关闭。
 */
class WechatPaymentCallbackMkoRouteTest {

    private IMarketingProductPurchaseService marketing;
    private ICmsRegistrationService registration;
    private WechatPaymentCallbackRouter router;

    @BeforeEach
    void setUp() {
        marketing = mock(IMarketingProductPurchaseService.class);
        registration = mock(ICmsRegistrationService.class);
        router = new WechatPaymentCallbackRouter(
                mock(IClubMembershipService.class),
                mock(IMerchantCommerceService.class),
                mock(ICoopInviteService.class),
                registration,
                mock(IRefundService.class),
                marketing);
    }

    @Test
    void mkoCallbackRoutesToMarketingAndNeverFallsBackToRegistration() {
        when(marketing.handlePaidOrder(eq("MKO1700000000000ABCDEF"), eq(true), eq(1234), eq("TX-1"), any()))
                .thenReturn(true);

        WechatPaymentCallbackRouter.Outcome outcome = router.payment(facts("MKO1700000000000ABCDEF"));

        assertEquals("marketing-product", outcome.route());
        assertEquals("SUCCESS", outcome.result());
        verify(registration, never()).handleWechatPayCallback(any());
    }

    @Test
    void amountMismatchIsNotAcknowledgedAsSuccess() {
        // 承载方判定金额与冻结的现金额不一致 → false → 必须回 FAIL,让微信重投/人工处理。
        when(marketing.handlePaidOrder(anyString(), anyBoolean(), anyInt(), anyString(), any()))
                .thenReturn(false);

        assertEquals("FAIL", router.payment(facts("MKO1")).result());
    }

    @Test
    void marketingOwnerDoesNotClaimNonMkoNumbers() {
        AtomicInteger hits = new AtomicInteger();
        IMarketingProductPurchaseService spy = mock(IMarketingProductPurchaseService.class);
        when(spy.handlePaidOrder(anyString(), anyBoolean(), anyInt(), anyString(), any()))
                .thenAnswer(call -> { hits.incrementAndGet(); return true; });

        WechatPaymentCallbackRouter other = new WechatPaymentCallbackRouter(
                null, null, null, null, null, spy);

        // 非 MKO 号在没有其它 owner 认领时必须是"0 命中"拒绝,不能调用营销承载方。
        assertThrows(WechatPaymentCallbackRouter.RoutingException.class,
                () -> other.payment(facts("A20261108ABCDEF01")));
        assertEquals(0, hits.get());
    }

    @Test
    void multipleOwnersMatchingTheSameNumberIsStillRejected() {
        // 假设某号同时被营销与 registration 认领(malformed 场景):仍是多命中拒绝,不择一执行。
        when(registration.getByRegistrationNo("MKO1")).thenReturn(
                new com.chengyinhub.business.domain.CmsRegistration());

        WechatPaymentCallbackRouter.RoutingException rejected = assertThrows(
                WechatPaymentCallbackRouter.RoutingException.class,
                () -> router.payment(facts("MKO1")));

        assertEquals(2, rejected.matches());
        verify(marketing, never()).handlePaidOrder(anyString(), anyBoolean(), anyInt(), anyString(), any());
        verify(registration, never()).handleWechatPayCallback(any());
    }

    @Test
    void duplicateCallbacksReachTheCarrierWhichOwnsIdempotency() {
        AtomicInteger calls = new AtomicInteger();
        when(marketing.handlePaidOrder(anyString(), anyBoolean(), anyInt(), anyString(), any()))
                .thenAnswer(call -> { calls.incrementAndGet(); return true; });

        assertEquals("SUCCESS", router.payment(facts("MKO2")).result());
        assertEquals("SUCCESS", router.payment(facts("MKO2")).result());

        // 路由层不做去重(去重属 inbox/承载方):两次都交给承载方,由它按支付事实幂等。
        assertEquals(2, calls.get());
    }

    @Test
    void unverifiedReferenceIsRejectedBeforeTheCarrierIsCalled() {
        Map<String, String> facts = facts("MKO3");
        facts.remove("verified_fact_id");

        assertThrows(IllegalArgumentException.class, () -> router.payment(facts));
        verify(marketing, never()).handlePaidOrder(anyString(), anyBoolean(), anyInt(), anyString(), any());
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, String> facts(String outTradeNo) {
        Map<String, String> facts = new HashMap<>();
        facts.put("out_trade_no", outTradeNo);
        facts.put("trade_state", "SUCCESS");
        facts.put("total", "1234");
        facts.put("currency", "CNY");
        facts.put("transaction_id", "TX-1");
        facts.put("verified_fact_id", "77");
        facts.put("verified_fact_hash", "a".repeat(64));
        return facts;
    }
}
