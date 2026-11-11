package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.EcomCombineRefund;
import com.chengyinhub.business.domain.EcomCombineSettlement;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomSupplierPayable;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.payout.CombinedCheckoutPolicy;
import com.chengyinhub.business.payout.EcommerceCombinedSettlementService;
import com.chengyinhub.business.payout.EcommercePayGateway;
import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.EcommerceSellerResolver;
import com.chengyinhub.business.payout.EcommerceSettlementGuard;
import com.chengyinhub.business.payout.FakeEcomCombineMapper;
import com.chengyinhub.business.payout.FakeEcomPayoutMapper;
import com.chengyinhub.business.payout.FakeEcommerceGateway;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.payout.SellerOfRecordPolicy;
import com.chengyinhub.business.service.IPricingService;
import com.chengyinhub.business.service.RefundRequestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** l-payout3：合单下单 / 回调幂等 / 按子单退款 / 退款前回退补差 / 关单，经现有 l-payout2 适配器接点分派。 */
class EcommerceCombinedPaymentTest {
    static final String SP = "1900000001";
    static final String SELLER_SUB = "1900000101";
    static final String PLATFORM_SUB = "1900000888";
    static final String PLATFORM = "1900000109";
    static final String NO = "R-C-1";
    static final String SUCCESS_TIME = "2026-11-11T10:00:00+08:00";
    static final PayoutSwitches ON = new PayoutSwitches(true, true, true, SP, "wx_mini", "", 30, false, "none", true, PLATFORM_SUB);
    static final String SELLER_SUB_NO = CombinedCheckoutPolicy.subOutTradeNo(NO, CombinedCheckoutPolicy.Role.SELLER);
    static final String HELD_SUB_NO = CombinedCheckoutPolicy.subOutTradeNo(NO, CombinedCheckoutPolicy.Role.PLATFORM_HELD);

    FakeEcomPayoutMapper mapper;
    FakeEcomCombineMapper cmapper;
    FakeEcommerceGateway gateway;
    EcommerceSellerResolver resolver;
    WechatPaymentIntentStore intents;
    WechatVerifiedPaymentFactService facts;
    UmsMemberMapper members;
    CoopOrderMapper coopOrders;
    EcommerceCombinedSettlementService settlement;
    EcommercePartnerPaymentAdapter adapter;

    @BeforeEach
    void setUp() {
        mapper = new FakeEcomPayoutMapper();
        cmapper = new FakeEcomCombineMapper();
        gateway = new FakeEcommerceGateway();
        resolver = mock(EcommerceSellerResolver.class);
        when(resolver.decide(any())).thenReturn(new SellerOfRecordPolicy.Decision(true, SELLER_SUB,
                SellerOfRecordPolicy.SellerType.MERCHANT, 5L, "SUB_MERCHANT"));
        intents = mock(WechatPaymentIntentStore.class);
        facts = mock(WechatVerifiedPaymentFactService.class);
        members = mock(UmsMemberMapper.class);
        UmsMember member = new UmsMember();
        member.setId(2L);
        member.setWxOpenId("o_member_2");
        when(members.selectUmsMemberById(2L)).thenReturn(member);
        when(intents.freezePaymentIntent(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), anyString(), any())).thenAnswer(call -> call.getArgument(0));
        when(intents.currentWechatPaymentNo(anyString(), anyString())).thenAnswer(call -> call.getArgument(0));
        when(intents.businessPaymentOrderNo(anyString(), anyString())).thenAnswer(call -> call.getArgument(0));
        when(intents.frozenFacts(eq(SP), anyString())).thenReturn(List.of(Map.of(
                "appid", "wx_mini", "trade_type", "JSAPI", "currency", "CNY", "total_fen", 10000L)));
        when(intents.frozenRequestHash(eq(SP), anyString())).thenReturn("b".repeat(64));
        when(facts.recordVerified(any())).thenReturn(new WechatVerifiedPaymentFact.Reference(41L, "c".repeat(64)));
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        when(topics.selectCmsTopicById(9L)).thenReturn(topic);
        coopOrders = mock(CoopOrderMapper.class);
        CoopOrder lead = new CoopOrder();
        lead.setShareMode(1);
        lead.setShareRate(new BigDecimal("10"));
        when(coopOrders.selectEffectiveOrdersByTopicId(9L)).thenReturn(List.of(lead));
        IPricingService pricing = mock(IPricingService.class);
        when(pricing.platformRateOf(any())).thenReturn(new BigDecimal("0.15"));
        settlement = new EcommerceCombinedSettlementService(cmapper, mapper, gateway, ON, TransactionOperations.withoutTransaction());
        EcommerceCombinedPayment combined = new EcommerceCombinedPayment(ON, cmapper, gateway, resolver, settlement, intents, facts,
                members, topics, coopOrders, pricing);
        EcommerceProfitShareService profitSharing = new EcommerceProfitShareService(mapper, gateway, ON, TransactionOperations.withoutTransaction());
        adapter = new EcommercePartnerPaymentAdapter(ON, mapper, gateway, resolver, profitSharing, intents, facts, members);
        adapter.setCombined(combined);
    }

    static CmsRegistration registration() {
        CmsRegistration reg = new CmsRegistration();
        reg.setId(1L);
        reg.setMemberId(2L);
        reg.setOwnerType(1L);
        reg.setOwnerId(9L);
        reg.setOrderNum(1L);
        reg.setRegistrationNo(NO);
        reg.setWechatPaymentAmount(new BigDecimal("100.00"));
        reg.setActivityTitle("静安探店日");
        reg.setTicketName("协作通票");
        return reg;
    }

    static EcommercePayGateway.CombinedOrder combined(String sellerState, String heldState, long sellerFen) {
        return new EcommercePayGateway.CombinedOrder("wx_mini", SP, NO, List.of(
                new EcommercePayGateway.CombinedSub(SP, SELLER_SUB, SELLER_SUB_NO, "SUCCESS".equals(sellerState) ? "TX-S" : null,
                        sellerState, "SUCCESS".equals(sellerState) ? "JSAPI" : null, sellerFen, "CNY", SUCCESS_TIME),
                new EcommercePayGateway.CombinedSub(SP, PLATFORM_SUB, HELD_SUB_NO, "SUCCESS".equals(heldState) ? "TX-P" : null,
                        heldState, "SUCCESS".equals(heldState) ? "JSAPI" : null, 2500L, "CNY", SUCCESS_TIME)));
    }

    static WechatVerifiedPaymentFact paidFact() {
        return new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT, SP, "wx_mini", NO, NO, NO, "TX-S", "TX-S",
                "JSAPI", "CNY", 10000, 10000, Instant.parse("2026-11-11T02:00:00Z"), "b".repeat(64), "SUCCESS");
    }

    void placeAndPay() {
        adapter.createOrder(registration(), PLATFORM);
        gateway.combinedOrder = combined("SUCCESS", "SUCCESS", 7500L);
        adapter.verifiedCombinedNotificationFacts(NO);
        gateway.calls.clear();
    }

    @Nested
    class PlaceOrder {
        @Test
        void multiPayeeTopicTicketIsPlacedAsCombinedOrderOfSellerAndPlatformHeldSubOrders() {
            Map<String, String> params = adapter.createOrder(registration(), PLATFORM);
            assertNotNull(params);
            assertEquals(List.of("combinePrepay:" + NO), gateway.calls);
            // 佣金 15 元 + 带队费上限 10 元留在平台自营子单，其余 75 元直接进卖家子单。
            assertEquals(List.of(new EcommercePayGateway.CombinedSubRequest(SELLER_SUB, SELLER_SUB_NO, 7500, "静安探店日-协作通票", true),
                    new EcommercePayGateway.CombinedSubRequest(PLATFORM_SUB, HELD_SUB_NO, 2500, "静安探店日-协作通票", false)),
                    gateway.lastCombinedSubs);
            verify(intents).freezePaymentIntent(eq(NO), eq(SP), eq("wx_mini"), eq("JSAPI"), eq("CNY"), eq(10000), eq("o_member_2"),
                    eq("静安探店日-协作通票"), eq("https://api.example.com/api/registration/ecommerce-combine-callback"), any());
            assertTrue(mapper.trades.isEmpty(), "合单不写 l-payout2 卖家单");

            adapter.createOrder(registration(), PLATFORM);
            assertEquals(1, cmapper.calls.stream().filter("insertCombineOrder"::equals).count(), "重复下单不重新拆分");
            assertEquals(2, cmapper.subs.size());
        }

        @Test
        void singlePayeeTopicKeepsTheLPayout2SellerPath() {
            when(coopOrders.selectEffectiveOrdersByTopicId(9L)).thenReturn(List.of());
            adapter.createOrder(registration(), PLATFORM);
            assertEquals(List.of("prepay"), gateway.calls);
            assertEquals(SELLER_SUB, mapper.selectOrderSeller(NO).getSubMchid());
            assertTrue(cmapper.orders.isEmpty());
        }

        @Test
        void startedPlatformPaymentAndActivityTicketStayOnPlatform() {
            when(intents.hasAnyAttempt(PLATFORM, NO)).thenReturn(true);
            assertNull(adapter.createOrder(registration(), PLATFORM));
            CmsRegistration activity = registration();
            activity.setOwnerType(2L);
            assertNull(adapter.createOrder(activity, PLATFORM));
            assertTrue(cmapper.orders.isEmpty());
            assertTrue(gateway.calls.isEmpty());
        }

        @Test
        void unknownContractTermsFallBackToThePlatformMerchant() {
            CoopOrder broken = new CoopOrder();
            when(coopOrders.selectEffectiveOrdersByTopicId(9L)).thenReturn(List.of(broken));
            assertNull(adapter.createOrder(registration(), PLATFORM), "返回 null = 网关走现有平台商户号");
            assertTrue(cmapper.orders.isEmpty());
            assertTrue(mapper.trades.isEmpty());
            assertTrue(gateway.calls.isEmpty());
        }
    }

    @Nested
    class Callback {
        @Test
        void verifiedCombinedNoticeRecordsOneFactAndReplayDoesNotQueryAgain() {
            adapter.createOrder(registration(), PLATFORM);
            gateway.combinedOrder = combined("SUCCESS", "SUCCESS", 7500L);
            Map<String, String> first = adapter.verifiedCombinedNotificationFacts(NO);
            assertEquals("SUCCESS", first.get("trade_state"));
            assertEquals(SP, first.get("mchid"));
            assertEquals("TX-S", first.get("transaction_id"));
            assertEquals("10000", first.get("total"));
            assertEquals("41", first.get("verified_fact_id"));
            assertEquals("TX-S", cmapper.orders.get(NO).getSellerTransactionId());
            assertEquals(List.of("TX-S", "TX-P"), cmapper.selectSubOrders(NO).stream().map(s -> s.getTransactionId()).toList());

            when(facts.findPaymentSuccess(NO)).thenReturn(new WechatVerifiedPaymentFactService.Stored(
                    new WechatVerifiedPaymentFact.Reference(41L, "c".repeat(64)), paidFact()));
            Map<String, String> replay = adapter.verifiedCombinedNotificationFacts(NO);
            assertEquals(first.get("verified_fact_id"), replay.get("verified_fact_id"));
            assertEquals(1, gateway.calls.stream().filter(c -> c.startsWith("combineQuery")).count(), "重放不再查单");
            verify(facts, times(1)).recordVerified(any());
        }

        @Test
        void tamperedOrPartiallyPaidCombinedOrderIsNeverRecorded() {
            adapter.createOrder(registration(), PLATFORM);
            gateway.combinedOrder = combined("SUCCESS", "SUCCESS", 7400L);
            assertThrows(IllegalStateException.class, () -> adapter.verifiedCombinedNotificationFacts(NO));
            gateway.combinedOrder = combined("SUCCESS", "NOTPAY", 7500L);
            assertThrows(IllegalStateException.class, () -> adapter.verifiedCombinedNotificationFacts(NO));
            gateway.combinedOrder = new EcommercePayGateway.CombinedOrder("wx_other", SP, NO, combined("SUCCESS", "SUCCESS", 7500L).subOrders());
            assertThrows(IllegalStateException.class, () -> adapter.verifiedCombinedNotificationFacts(NO));
            assertNull(adapter.verifiedCombinedNotificationFacts("UNKNOWN-NO"));
            verify(facts, never()).recordVerified(any());
        }

        @Test
        void gatewayDispatchPointsSeeTheCombinedOrderUnderTheCombineMerchant() {
            adapter.createOrder(registration(), PLATFORM);
            assertEquals(SP, adapter.partnerMchid(NO), "锁支付尝试用合单商户号");
            EcomOrderSeller marker = adapter.sellerFor(NO);
            assertTrue(marker.isCombined());
            gateway.combinedOrder = combined("NOTPAY", "NOTPAY", 7500L);
            assertEquals("NOTPAY", adapter.queryOrderStatus(NO, marker).get("trade_state"));
            gateway.combinedOrder = combined("SUCCESS", "SUCCESS", 7500L);
            assertEquals("SUCCESS", adapter.queryOrderStatus(NO, marker).get("trade_state"));
            assertTrue(adapter.sellerForPaidFact(paidFact()).isCombined());
        }

        @Test
        void closeClosesAllSubOrdersAndConfirmsByQuery() {
            adapter.createOrder(registration(), PLATFORM);
            when(intents.currentAttemptClosable(NO, NO, SP)).thenReturn(true);
            gateway.combinedOrder = combined("CLOSED", "CLOSED", 7500L);
            assertTrue(adapter.closePaymentAttempt(NO, NO, adapter.sellerFor(NO)));
            assertTrue(gateway.calls.contains("combineClose:" + NO));
            verify(intents).recordConfirmedClosedAttempt(SP, NO);
        }
    }

    String child(String parent, CombinedCheckoutPolicy.Role role) { return CombinedCheckoutPolicy.subOutRefundNo(parent, role); }

    EcommercePayGateway.RefundQuery query(String parent, CombinedCheckoutPolicy.Role role, String status, long fen) {
        boolean seller = role == CombinedCheckoutPolicy.Role.SELLER;
        return new EcommercePayGateway.RefundQuery(child(parent, role), "WX-" + role, seller ? SELLER_SUB_NO : HELD_SUB_NO,
                seller ? "TX-S" : "TX-P", status, fen, "CNY", "SUCCESS".equals(status) ? SUCCESS_TIME : null);
    }

    @Nested
    class Refund {
        @Test
        void refundIsSplitPerSubOrderAndRepeatIsIdempotent() {
            placeAndPay();
            EcomSupplierPayable payable = new EcomSupplierPayable();
            payable.setSource("COMBINED"); payable.setBusinessOutTradeNo(NO); payable.setTopicId(9L); payable.setSettlementId(1L);
            payable.setLineType("LEAD_FEE"); payable.setPayeeMemberId(66L); payable.setAmountFen(1000L);
            cmapper.insertPayable(payable);
            assertEquals(RefundRequestResult.ACCEPTED, adapter.submitRefund(paidFact(), adapter.sellerFor(NO), "RF1", 5000, 10000, "退票"));
            assertEquals(List.of("refund:" + child("RF1", CombinedCheckoutPolicy.Role.SELLER) + ":3750/7500",
                    "refund:" + child("RF1", CombinedCheckoutPolicy.Role.PLATFORM_HELD) + ":1250/2500"), gateway.calls);
            assertEquals("HOLD_REFUND", cmapper.payables.get(0).getState(), "发生退款后待付应付挂起复核");
            assertEquals(RefundRequestResult.ACCEPTED, adapter.submitRefund(paidFact(), adapter.sellerFor(NO), "RF1", 5000, 10000, "退票"));
            assertEquals(2, gateway.calls.size(), "同一退款单号重复提交不再退款");
            assertEquals(RefundRequestResult.DEFINITIVE_FAILED,
                    adapter.submitRefund(paidFact(), adapter.sellerFor(NO), "RF2", 5001, 10000, "超额"));
        }

        @Test
        void allSubRefundsSucceedingRecordOneRefundFactWithTheBusinessAmount() {
            placeAndPay();
            adapter.submitRefund(paidFact(), adapter.sellerFor(NO), "RF1", 10000, 10000, "退票");
            gateway.refundQueries.put(child("RF1", CombinedCheckoutPolicy.Role.SELLER), query("RF1", CombinedCheckoutPolicy.Role.SELLER, "SUCCESS", 7500));
            gateway.refundQueries.put(child("RF1", CombinedCheckoutPolicy.Role.PLATFORM_HELD), query("RF1", CombinedCheckoutPolicy.Role.PLATFORM_HELD, "PROCESSING", 2500));
            Map<String, String> half = adapter.queryRefundFacts(paidFact(), adapter.sellerFor(NO), "RF1");
            assertEquals("PROCESSING", half.get("status"));
            verify(facts, times(1)).recordVerified(any()); // 只有支付事实

            gateway.refundQueries.put(child("RF1", CombinedCheckoutPolicy.Role.PLATFORM_HELD), query("RF1", CombinedCheckoutPolicy.Role.PLATFORM_HELD, "SUCCESS", 2500));
            Map<String, String> done = adapter.queryRefundFacts(paidFact(), adapter.sellerFor(NO), "RF1");
            assertEquals("SUCCESS", done.get("status"));
            assertEquals("10000", done.get("refund"));
            assertEquals("RF1", done.get("outRefundNo"));
            verify(facts).recordVerified(org.mockito.ArgumentMatchers.argThat(f -> f.kind() == WechatVerifiedPaymentFact.Kind.REFUND
                    && f.amountFen() == 10000 && "RF1".equals(f.merchantEventNo()) && SP.equals(f.mchid())));
        }

        @Test
        void partialProviderFailureIsAbnormalForCompensationAndNeverRecordsARefund() {
            placeAndPay();
            adapter.submitRefund(paidFact(), adapter.sellerFor(NO), "RF1", 10000, 10000, "退票");
            gateway.refundQueries.put(child("RF1", CombinedCheckoutPolicy.Role.SELLER), query("RF1", CombinedCheckoutPolicy.Role.SELLER, "SUCCESS", 7500));
            gateway.refundQueries.put(child("RF1", CombinedCheckoutPolicy.Role.PLATFORM_HELD), query("RF1", CombinedCheckoutPolicy.Role.PLATFORM_HELD, "ABNORMAL", 2500));
            assertEquals("ABNORMAL", adapter.queryRefundFacts(paidFact(), adapter.sellerFor(NO), "RF1").get("status"));
            EcomCombineRefund held = cmapper.selectRefund(child("RF1", CombinedCheckoutPolicy.Role.PLATFORM_HELD));
            assertEquals("ABNORMAL", held.getState());
            assertEquals("PROVIDER_ABNORMAL", held.getFailReason());
            verify(facts, times(1)).recordVerified(any());
        }

        void sharedShortfall() {
            EcomCombineSettlement s = new EcomCombineSettlement();
            s.setOutOrderNo("CYCS1"); s.setBusinessOutTradeNo(NO); s.setTopicId(9L); s.setSellerSubMchid(SELLER_SUB);
            s.setSellerTransactionId("TX-S"); s.setSellerFen(7500L); s.setHeldFen(2500L); s.setPlatformFen(1500L);
            s.setShortfallFen(750L); s.setSurplusFen(0L); s.setPlanHash("d".repeat(64)); s.setState("SUCCESS");
            cmapper.insertSettlement(s);
        }

        @Test
        void sellerSubRefundReturnsShortfallShareFirstAndWaitsWhileReturnIsPending() {
            placeAndPay();
            sharedShortfall();
            gateway.returnResult = "PROCESSING";
            assertEquals(RefundRequestResult.UNKNOWN, adapter.submitRefund(paidFact(), adapter.sellerFor(NO), "RF1", 10000, 10000, "退票"));
            assertEquals("return:" + SP + ":750", gateway.calls.get(0));
            assertTrue(gateway.calls.stream().noneMatch(c -> c.startsWith("refund:" + child("RF1", CombinedCheckoutPolicy.Role.SELLER))),
                    "回退未完成绝不退卖家子单");
            assertEquals("RETURNING", cmapper.selectRefund(child("RF1", CombinedCheckoutPolicy.Role.SELLER)).getState());

            gateway.returnResult = "SUCCESS";
            gateway.refundQueries.put(child("RF1", CombinedCheckoutPolicy.Role.SELLER), query("RF1", CombinedCheckoutPolicy.Role.SELLER, "PROCESSING", 7500));
            gateway.refundQueries.put(child("RF1", CombinedCheckoutPolicy.Role.PLATFORM_HELD), query("RF1", CombinedCheckoutPolicy.Role.PLATFORM_HELD, "PROCESSING", 2500));
            assertEquals("PROCESSING", adapter.queryRefundFacts(paidFact(), adapter.sellerFor(NO), "RF1").get("status"));
            assertEquals(1, gateway.calls.stream().filter(c -> c.startsWith("refund:" + child("RF1", CombinedCheckoutPolicy.Role.SELLER))).count());
            assertEquals(1, gateway.calls.stream().filter(c -> c.startsWith("refund:" + child("RF1", CombinedCheckoutPolicy.Role.PLATFORM_HELD))).count());
            assertEquals(1, cmapper.returns.size(), "同一退款单号只建一笔回退（处理中按原回退单号重发，微信按单号幂等）");
            assertTrue(gateway.calls.stream().filter(c -> c.startsWith("return:")).allMatch(("return:" + SP + ":750")::equals));
        }

        @Test
        void subOrderRefundNoticeIsRoutedToTheCombinedOrder() {
            placeAndPay();
            adapter.submitRefund(paidFact(), adapter.sellerFor(NO), "RF1", 10000, 10000, "退票");
            when(facts.findPaymentSuccess(NO)).thenReturn(new WechatVerifiedPaymentFactService.Stored(
                    new WechatVerifiedPaymentFact.Reference(41L, "c".repeat(64)), paidFact()));
            gateway.refundQueries.put(child("RF1", CombinedCheckoutPolicy.Role.SELLER), query("RF1", CombinedCheckoutPolicy.Role.SELLER, "SUCCESS", 7500));
            gateway.refundQueries.put(child("RF1", CombinedCheckoutPolicy.Role.PLATFORM_HELD), query("RF1", CombinedCheckoutPolicy.Role.PLATFORM_HELD, "SUCCESS", 2500));
            Map<String, String> f = adapter.verifiedRefundNoticeFacts(new EcommercePayGateway.RefundNotice(SP, PLATFORM_SUB,
                    child("RF1", CombinedCheckoutPolicy.Role.PLATFORM_HELD)));
            assertEquals("SUCCESS", f.get("status"));
            assertEquals("RF1", f.get("outRefundNo"));
            assertNull(adapter.verifiedRefundNoticeFacts(new EcommercePayGateway.RefundNotice(SP, SELLER_SUB,
                    child("RF1", CombinedCheckoutPolicy.Role.PLATFORM_HELD))), "子单号与二级商户号不符");
        }
    }

    @Nested
    class SwitchOff {
        @Test
        void combinedOffWithEverythingElseOnBehavesExactlyLikeLPayout2AndNeverTouchesCombineTables() {
            PayoutSwitches l2 = new PayoutSwitches(true, true, true, SP, "wx_mini", "", 30, false, "none", false, PLATFORM_SUB);
            EcommerceCombinedPayment off = new EcommerceCombinedPayment(l2, cmapper, gateway, resolver,
                    new EcommerceCombinedSettlementService(cmapper, mapper, gateway, l2, TransactionOperations.withoutTransaction()),
                    intents, facts, members, mock(CmsTopicMapper.class), coopOrders, mock(IPricingService.class));
            EcommercePartnerPaymentAdapter a = new EcommercePartnerPaymentAdapter(l2, mapper, gateway, resolver,
                    new EcommerceProfitShareService(mapper, gateway, l2, TransactionOperations.withoutTransaction()), intents, facts, members);
            a.setCombined(off);
            assertNotNull(a.createOrder(registration(), PLATFORM));
            assertEquals(List.of("prepay"), gateway.calls, "多方收款的单也照 l-payout2 以卖家 sub_mchid 下单");
            assertEquals(SELLER_SUB, mapper.selectOrderSeller(NO).getSubMchid());
            assertNull(a.verifiedCombinedNotificationFacts(NO));
            assertNull(a.sellerFor("R-OTHER"));
            assertTrue(cmapper.calls.isEmpty(), "合单开关关：不读写合单表");
            verify(coopOrders, never()).selectEffectiveOrdersByTopicId(any());
        }

        @Test
        void settlementGuardAddsCombinedPortionOnlyWhenCombinedIsOn() {
            cmapper.combinedPortion = new BigDecimal("25.00");
            cmapper.combinedFacts = 1;
            mapper.partnerPortion = new BigDecimal("3.00");
            EcommerceProfitShareService ps = new EcommerceProfitShareService(mapper, gateway, ON, TransactionOperations.withoutTransaction());
            EcommerceSettlementGuard on = new EcommerceSettlementGuard(ON, mapper, ps);
            on.setCombined(cmapper, settlement);
            assertEquals(new BigDecimal("28.00"), on.partnerPortion(700L));
            assertTrue(on.isPartnerPaymentFact(1L));

            FakeEcomCombineMapper untouched = new FakeEcomCombineMapper();
            PayoutSwitches l2 = new PayoutSwitches(true, true, true, SP, "wx_mini", "", 30, false, "none", false, PLATFORM_SUB);
            EcommerceSettlementGuard off = new EcommerceSettlementGuard(l2, mapper, ps);
            off.setCombined(untouched, settlement);
            assertEquals(new BigDecimal("3.00"), off.partnerPortion(700L));
            off.afterTopicSettled(9L);
            assertTrue(untouched.calls.isEmpty());
            assertFalse(untouched.combinedFacts > 0);
        }
    }
}
