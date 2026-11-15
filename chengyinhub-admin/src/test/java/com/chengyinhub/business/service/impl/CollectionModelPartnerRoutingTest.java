package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.collection.CollectionDecision;
import com.chengyinhub.business.collection.CollectionModelService;
import com.chengyinhub.business.collection.CollectionPolicy;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomProfitShareLine;
import com.chengyinhub.business.domain.EcomProfitShareOrder;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.EcommerceSellerResolver;
import com.chengyinhub.business.payout.FakeEcomPayoutMapper;
import com.chengyinhub.business.payout.FakeEcommerceGateway;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.payout.SellerOfRecordPolicy.SellerType;
import com.chengyinhub.business.service.RefundRequestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S2 collection model through the existing 收付通 adapter (service-provider JSAPI order with profit_sharing=true), with a
 * stub gateway: split activity and topic tickets go to the seller's sub-merchant, refused ones never reach any account,
 * the platform's take is the only profit-sharing receiver, and a refund returns that take before refunding.
 */
class CollectionModelPartnerRoutingTest {
    static final String SP = "1900000001";
    static final String SUB = "1900000999";
    static final String PLATFORM = "1900000109";
    static final PayoutSwitches ON = new PayoutSwitches(true, true, true, SP, "wx_mini", "", 30, false, "none");

    FakeEcomPayoutMapper mapper;
    FakeEcommerceGateway gateway;
    EcommerceSellerResolver resolver;
    EcommerceProfitShareService profitSharing;
    WechatPaymentIntentStore intents;
    CollectionModelService model;
    EcommercePartnerPaymentAdapter adapter;

    @BeforeEach
    void setUp() {
        mapper = new FakeEcomPayoutMapper() {
            @Override public Long selectSellerMemberId(String t, Long id) { return "CLUB".equals(t) && id == 3L ? 500L : null; }
        };
        gateway = new FakeEcommerceGateway();
        resolver = mock(EcommerceSellerResolver.class);
        profitSharing = new EcommerceProfitShareService(mapper, gateway, ON, TransactionOperations.withoutTransaction());
        intents = mock(WechatPaymentIntentStore.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        UmsMember member = new UmsMember();
        member.setId(2L);
        member.setWxOpenId("o_member_2");
        when(members.selectUmsMemberById(2L)).thenReturn(member);
        when(intents.freezePaymentIntent(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), anyString(), any())).thenAnswer(call -> call.getArgument(0));
        model = mock(CollectionModelService.class);
        when(model.active()).thenReturn(true);
        adapter = new EcommercePartnerPaymentAdapter(ON, mapper, gateway, resolver, profitSharing, intents,
                mock(WechatVerifiedPaymentFactService.class), members);
        adapter.setCollectionModel(model);
    }

    static CmsRegistration registration(long ownerType) {
        CmsRegistration reg = new CmsRegistration();
        reg.setId(1L);
        reg.setMemberId(2L);
        reg.setOwnerType(ownerType);
        reg.setOwnerId(9L);
        reg.setRegistrationNo("R-SPLIT-1");
        reg.setWechatPaymentAmount(new BigDecimal("69.00"));
        reg.setFeePlatformRate(new BigDecimal("0.03"));
        reg.setActivityTitle("俱乐部夜跑");
        reg.setTicketName("单人票");
        return reg;
    }

    static CollectionDecision split() {
        return new CollectionDecision(CollectionPolicy.SPLIT, SellerType.CLUB, 3L, SUB, CollectionDecision.SUB_MERCHANT);
    }

    @Test
    void splitActivityTicketIsPlacedOnTheSellerSubMerchantAndRecorded() {
        CmsRegistration reg = registration(2L);
        when(model.decide(reg)).thenReturn(split());
        Map<String, String> params = adapter.createOrder(reg, PLATFORM);
        assertNotNull(params);
        assertEquals(List.of("prepay"), gateway.calls, "one service-provider JSAPI order, nothing on the platform account");
        EcomOrderSeller row = mapper.selectOrderSeller("R-SPLIT-1");
        assertEquals(SUB, row.getSubMchid());
        assertEquals("CLUB", row.getSellerType());
        verify(model).recordSplitOrder(eq(reg), any(), eq(6900L));
        verifyNoInteractions(resolver);
    }

    @Test
    void splitTopicTicketToo() {
        CmsRegistration reg = registration(1L);
        when(model.decide(reg)).thenReturn(split());
        assertNotNull(adapter.createOrder(reg, PLATFORM));
        assertEquals(SUB, mapper.selectOrderSeller("R-SPLIT-1").getSubMchid());
    }

    @Test
    void refusedOrPlatformDecisionNeverPlacesAPartnerOrder() {
        CmsRegistration reg = registration(2L);
        when(model.decide(reg)).thenReturn(new CollectionDecision(CollectionPolicy.REFUSE, SellerType.CLUB, 3L, null,
                CollectionDecision.SELLER_NOT_BOUND));
        assertNull(adapter.createOrder(reg, PLATFORM), "returns to the platform path, which refuses it (requirePlatformMayCollect)");
        when(model.decide(reg)).thenReturn(new CollectionDecision(CollectionPolicy.PLATFORM, SellerType.PLATFORM, null, null,
                CollectionDecision.OFFICIAL));
        assertNull(adapter.createOrder(reg, PLATFORM));
        assertTrue(gateway.calls.isEmpty());
        assertNull(mapper.selectOrderSeller("R-SPLIT-1"));
        verify(model, never()).recordSplitOrder(any(), any(), anyLong());
    }

    @Test
    void platformTakeIsTheOnlyReceiverAndARefundReturnsItFirst() {
        EcomOrderSeller trade = new EcomOrderSeller();
        trade.setOutTradeNo("R-SPLIT-1");
        trade.setSubMchid(SUB);
        trade.setSellerType("CLUB");
        trade.setSellerId(3L);
        trade.setTotalFen(6900L);
        trade.setTransactionId("TX9");
        mapper.trades.add(trade);
        Long id = profitSharing.planSingleSeller(-9L, trade, 207L, null);
        assertNotNull(id);
        EcomProfitShareOrder order = mapper.selectShareOrdersByTrade("R-SPLIT-1").get(0);
        assertEquals("PLANNED", order.getState(), "manual reason: " + order.getManualReason());
        List<EcomProfitShareLine> lines = mapper.selectShareLines(id);
        long toPlatform = lines.stream().filter(l -> SP.equals(l.getReceiverMchid())).mapToLong(EcomProfitShareLine::getAmountFen).sum();
        assertEquals(207L, toPlatform);
        assertEquals(207L, lines.stream().filter(l -> !"RETAINED".equals(l.getReceiverKind()))
                .mapToLong(EcomProfitShareLine::getAmountFen).sum(), "the platform take is the only receiver");
        assertEquals(6693L, lines.stream().filter(l -> "RETAINED".equals(l.getReceiverKind()) && SUB.equals(l.getReceiverMchid()))
                .mapToLong(EcomProfitShareLine::getAmountFen).sum(), "the seller's part stays on its own sub-merchant");
        assertEquals(id, profitSharing.planSingleSeller(-9L, trade, 207L, null), "idempotent");

        order.setState("SUCCESS");
        assertEquals(RefundRequestResult.ACCEPTED, adapter.submitRefund(
                new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact(
                        com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT, SUB, "wx_mini", "R-SPLIT-1",
                        "R-SPLIT-1", "R-SPLIT-1", "TX9", "TX9", "JSAPI", "CNY", 6900, 6900,
                        java.time.Instant.parse("2026-11-10T02:00:00Z"), "b".repeat(64), "SUCCESS"), trade, "RF1", 6900, 6900, "退票"));
        assertEquals(List.of("return:" + SP + ":207", "refund:RF1:6900/6900"), gateway.calls, "return the take, then refund");
    }

    @Test
    void manualReasonPlanHasNoReceiver() {
        EcomOrderSeller trade = new EcomOrderSeller();
        trade.setOutTradeNo("R-SPLIT-2");
        trade.setSubMchid(SUB);
        trade.setSellerType("CLUB");
        trade.setSellerId(3L);
        trade.setTotalFen(6900L);
        trade.setTransactionId("TX10");
        Long id = profitSharing.planSingleSeller(-9L, trade, 0L, "TAKE_RATE_MISSING");
        EcomProfitShareOrder order = mapper.selectShareOrdersByTrade("R-SPLIT-2").get(0);
        assertEquals("MANUAL", order.getState());
        assertEquals(EcommerceProfitShareService.SPLIT_PREFIX + "TAKE_RATE_MISSING", order.getManualReason());
        assertTrue(mapper.selectShareLines(id).isEmpty());
    }
}
