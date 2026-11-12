package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomProfitShareLine;
import com.chengyinhub.business.domain.EcomProfitShareOrder;
import com.chengyinhub.business.domain.EcomRefund;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.payout.EcommercePayGateway;
import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.EcommerceSellerResolver;
import com.chengyinhub.business.payout.FakeEcomPayoutMapper;
import com.chengyinhub.business.payout.FakeEcommerceGateway;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.payout.SellerOfRecordPolicy;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** l-payout2：开关打开时的接线行为（下单路由、回调幂等、退款前回退分账与补偿状态）。 */
class EcommercePartnerPaymentAdapterTest {
    static final String SP = "1900000001";
    static final String SUB = "1900000999";
    static final String PLATFORM = "1900000109";
    static final String SUCCESS_TIME = "2026-11-10T10:00:00+08:00";
    static final PayoutSwitches ON = new PayoutSwitches(true, true, true, SP, "wx_mini", "", 30, false, "none");

    FakeEcomPayoutMapper mapper;
    FakeEcommerceGateway gateway;
    EcommerceSellerResolver resolver;
    EcommerceProfitShareService profitSharing;
    WechatPaymentIntentStore intents;
    WechatVerifiedPaymentFactService facts;
    UmsMemberMapper members;
    EcommercePartnerPaymentAdapter adapter;

    @BeforeEach
    void setUp() {
        mapper = new FakeEcomPayoutMapper();
        gateway = new FakeEcommerceGateway();
        resolver = mock(EcommerceSellerResolver.class);
        profitSharing = new EcommerceProfitShareService(mapper, gateway, ON, TransactionOperations.withoutTransaction());
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
        when(intents.frozenFacts(eq(SUB), anyString())).thenReturn(List.of(Map.of(
                "appid", "wx_mini", "trade_type", "JSAPI", "currency", "CNY", "total_fen", 6900L)));
        when(intents.frozenRequestHash(eq(SUB), anyString())).thenReturn("b".repeat(64));
        when(facts.recordVerified(any())).thenReturn(new WechatVerifiedPaymentFact.Reference(41L, "c".repeat(64)));
        adapter = new EcommercePartnerPaymentAdapter(ON, mapper, gateway, resolver, profitSharing, intents, facts, members);
    }

    static CmsRegistration registration(long ownerType) {
        CmsRegistration reg = new CmsRegistration();
        reg.setId(1L);
        reg.setMemberId(2L);
        reg.setOwnerType(ownerType);
        reg.setOwnerId(9L);
        reg.setRegistrationNo("R-SELLER-1");
        reg.setWechatPaymentAmount(new BigDecimal("69.00"));
        reg.setActivityTitle("静安探店日");
        reg.setTicketName("首期通票");
        return reg;
    }

    EcommercePayGateway.PartnerOrder paidOrder() {
        return new EcommercePayGateway.PartnerOrder(SP, SUB, "wx_mini", "R-SELLER-1", "TX9", "SUCCESS", "JSAPI", 6900L, "CNY", SUCCESS_TIME);
    }

    EcomOrderSeller seller(String transactionId) {
        EcomOrderSeller row = new EcomOrderSeller();
        row.setOutTradeNo("R-SELLER-1");
        row.setSubMchid(SUB);
        row.setSellerType("MERCHANT");
        row.setSellerId(7L);
        row.setTotalFen(6900L);
        row.setTransactionId(transactionId);
        return row;
    }

    static WechatVerifiedPaymentFact paidFact() {
        return new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT, SUB, "wx_mini", "R-SELLER-1", "R-SELLER-1",
                "R-SELLER-1", "TX9", "TX9", "JSAPI", "CNY", 6900, 6900, Instant.parse("2026-11-10T02:00:00Z"), "b".repeat(64), "SUCCESS");
    }

    @Nested
    class PlaceOrder {
        @Test
        void topicTicketOfOnboardedSellerIsPlacedUnderSubMerchant() {
            when(resolver.decide(any())).thenReturn(new SellerOfRecordPolicy.Decision(true, SUB,
                    SellerOfRecordPolicy.SellerType.MERCHANT, 7L, "SUB_MERCHANT"));
            Map<String, String> params = adapter.createOrder(registration(1L), PLATFORM);
            assertNotNull(params);
            assertEquals(List.of("prepay"), gateway.calls);
            EcomOrderSeller row = mapper.selectOrderSeller("R-SELLER-1");
            assertEquals(SUB, row.getSubMchid());
            assertEquals(6900L, row.getTotalFen());
            verify(intents).freezePaymentIntent(eq("R-SELLER-1"), eq(SUB), eq("wx_mini"), eq("JSAPI"), eq("CNY"), eq(6900),
                    eq("o_member_2"), eq("静安探店日-首期通票"), eq("https://api.example.com/api/registration/ecommerce-callback"), any());
        }

        @Test
        void notOnboardedSellerActivityTicketOrStartedPlatformPaymentStayOnPlatform() {
            when(resolver.decide(any())).thenReturn(new SellerOfRecordPolicy.Decision(false, null,
                    SellerOfRecordPolicy.SellerType.PLATFORM, null, "SELLER_NOT_ONBOARDED"));
            assertNull(adapter.createOrder(registration(1L), PLATFORM));
            assertNull(adapter.createOrder(registration(2L), PLATFORM));
            when(intents.hasAnyAttempt(PLATFORM, "R-SELLER-1")).thenReturn(true);
            when(resolver.decide(any())).thenReturn(new SellerOfRecordPolicy.Decision(true, SUB,
                    SellerOfRecordPolicy.SellerType.MERCHANT, 7L, "SUB_MERCHANT"));
            assertNull(adapter.createOrder(registration(1L), PLATFORM), "平台已冻结过意图：不换收款主体");
            assertTrue(mapper.trades.isEmpty());
            assertTrue(gateway.calls.isEmpty());
        }

        @Test
        void routingSwitchOffNeverPlacesNewSellerOrder() {
            PayoutSwitches noRouting = new PayoutSwitches(true, false, true, SP, "wx_mini", "", 30, false, "none");
            EcommercePartnerPaymentAdapter a = new EcommercePartnerPaymentAdapter(noRouting, mapper, gateway, resolver,
                    profitSharing, intents, facts, members);
            assertNull(a.createOrder(registration(1L), PLATFORM));
            verify(resolver, never()).decide(any());
        }
    }

    @Nested
    class Callback {
        @Test
        void verifiedNotificationRecordsFactOnceAndReplayIsIdempotent() {
            mapper.trades.add(seller(null));
            gateway.partnerOrder = paidOrder();
            Map<String, String> first = adapter.verifiedNotificationFacts(paidOrder());
            assertEquals("SUCCESS", first.get("trade_state"));
            assertEquals(SUB, first.get("mchid"));
            assertEquals("R-SELLER-1", first.get("out_trade_no"));
            assertEquals("41", first.get("verified_fact_id"));
            assertEquals("TX9", mapper.selectOrderSeller("R-SELLER-1").getTransactionId());

            when(facts.findPaymentSuccess(SUB, "R-SELLER-1", "TX9"))
                    .thenReturn(new WechatVerifiedPaymentFactService.Stored(new WechatVerifiedPaymentFact.Reference(41L, "c".repeat(64)), paidFact()));
            Map<String, String> replay = adapter.verifiedNotificationFacts(paidOrder());
            assertEquals(first.get("verified_fact_id"), replay.get("verified_fact_id"));
            assertEquals(first.get("verified_fact_hash"), replay.get("verified_fact_hash"));
            assertEquals(first.get("total"), replay.get("total"));
            assertEquals(1, gateway.calls.stream().filter(c -> c.startsWith("partnerQuery")).count(), "重放不再查单");
            verify(facts, times(1)).recordVerified(any());
        }

        @Test
        void foreignPlatformMismatchedAmountOrUnknownSellerIsRejected() {
            mapper.trades.add(seller(null));
            gateway.partnerOrder = paidOrder();
            assertNull(adapter.verifiedNotificationFacts(new EcommercePayGateway.PartnerOrder("1999999999", SUB, "wx_mini",
                    "R-SELLER-1", "TX9", "SUCCESS", "JSAPI", 6900L, "CNY", SUCCESS_TIME)));
            assertNull(adapter.verifiedNotificationFacts(new EcommercePayGateway.PartnerOrder(SP, SUB, "wx_mini",
                    "R-SELLER-1", "TX9", "SUCCESS", "JSAPI", 1L, "CNY", SUCCESS_TIME)));
            assertNull(adapter.verifiedNotificationFacts(new EcommercePayGateway.PartnerOrder(SP, "1900000888", "wx_mini",
                    "R-SELLER-1", "TX9", "SUCCESS", "JSAPI", 6900L, "CNY", SUCCESS_TIME)));
            verify(facts, never()).recordVerified(any());
        }
    }

    @Nested
    class Refund {
        void sharedOrder() {
            mapper.trades.add(seller("TX9"));
            EcomProfitShareOrder order = new EcomProfitShareOrder();
            order.setOutOrderNo("CYPS1");
            order.setOutTradeNo("R-SELLER-1");
            order.setTopicId(9L);
            order.setSubMchid(SUB);
            order.setTransactionId("TX9");
            order.setTotalFen(6900L);
            order.setSharedFen(690L);
            order.setPlanHash("d".repeat(64));
            order.setState("SUCCESS");
            mapper.insertShareOrder(order);
            EcomProfitShareLine line = new EcomProfitShareLine();
            line.setShareOrderId(order.getId());
            line.setSettlementId(700L);
            line.setLineType("PLATFORM");
            line.setReceiverKind("PLATFORM");
            line.setReceiverMchid(SP);
            line.setAmountFen(690L);
            mapper.insertShareLine(line);
        }

        @Test
        void returnsSharedMoneyBeforeRefundAndRepeatIsIdempotent() {
            sharedOrder();
            assertEquals(RefundRequestResult.ACCEPTED, adapter.submitRefund(paidFact(), seller("TX9"), "RF1", 6900, 6900, "退票"));
            assertEquals(List.of("return:" + SP + ":690", "refund:RF1:6900/6900"), gateway.calls, "先回退分账，再退款");
            assertEquals("SUBMITTED", mapper.selectRefund("RF1").getState());

            assertEquals(RefundRequestResult.ACCEPTED, adapter.submitRefund(paidFact(), seller("TX9"), "RF1", 6900, 6900, "退票"));
            assertEquals(2, gateway.calls.size(), "同一退款单号重复提交：不再回退、不再退款");
        }

        @Test
        void pendingReturnKeepsRefundUnknownThenRecoveryQueryFinishesSameNumber() {
            sharedOrder();
            gateway.returnResult = "PROCESSING";
            assertEquals(RefundRequestResult.UNKNOWN, adapter.submitRefund(paidFact(), seller("TX9"), "RF1", 6900, 6900, "退票"));
            assertEquals("RETURNING", mapper.selectRefund("RF1").getState());
            assertTrue(gateway.calls.stream().noneMatch(c -> c.startsWith("refund:")), "回退未完成绝不退款");

            gateway.returnResult = "SUCCESS";
            gateway.refundQuery = new EcommercePayGateway.RefundQuery("RF1", "WXRF1", "R-SELLER-1", "TX9", "PROCESSING", 6900L, "CNY", null);
            Map<String, String> recovered = adapter.queryRefundFacts(paidFact(), seller("TX9"), "RF1");
            assertEquals("PROCESSING", recovered.get("status"));
            assertEquals(1, gateway.calls.stream().filter(c -> c.startsWith("refund:RF1")).count());
            assertEquals("SUBMITTED", mapper.selectRefund("RF1").getState());
        }

        @Test
        void failedReturnBlocksRefundUntilRetriedReturnSucceeds() {
            sharedOrder();
            gateway.returnResult = "FAILED";
            assertEquals(RefundRequestResult.UNKNOWN, adapter.submitRefund(paidFact(), seller("TX9"), "RF1", 6900, 6900, "退票"));
            assertEquals("RETURN_FAILED", mapper.selectRefund("RF1").getState());
            assertNull(adapter.queryRefundFacts(paidFact(), seller("TX9"), "RF1"), "回退失败：恢复查证仍是未知，不退款");
            assertTrue(gateway.calls.stream().noneMatch(c -> c.startsWith("refund:")));

            gateway.returnResult = "SUCCESS";
            long failedId = mapper.selectReturnsByState("FAILED", 10).get(0).getId();
            assertEquals(EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED, profitSharing.retryReturn(failedId));
            gateway.refundQuery = new EcommercePayGateway.RefundQuery("RF1", "WXRF1", "R-SELLER-1", "TX9", "SUCCESS", 6900L, "CNY", SUCCESS_TIME);
            Map<String, String> done = adapter.queryRefundFacts(paidFact(), seller("TX9"), "RF1");
            assertEquals("SUCCESS", done.get("status"));
            assertEquals("41", done.get("verified_fact_id"));
            assertEquals("SUCCESS", mapper.selectRefund("RF1").getState());
        }

        @Test
        void uncertainProviderRefundStaysSubmittingAndRetriesSameNumberWithoutSecondReturn() {
            sharedOrder();
            gateway.refundFailure = new IllegalStateException("timeout");
            assertEquals(RefundRequestResult.UNKNOWN, adapter.submitRefund(paidFact(), seller("TX9"), "RF1", 6900, 6900, "退票"));
            assertEquals("SUBMITTING", mapper.selectRefund("RF1").getState());
            assertEquals(RefundRequestResult.ACCEPTED, adapter.submitRefund(paidFact(), seller("TX9"), "RF1", 6900, 6900, "退票"));
            assertEquals(1, gateway.calls.stream().filter(c -> c.startsWith("return:")).count());
            assertEquals(2, gateway.calls.stream().filter(c -> c.equals("refund:RF1:6900/6900")).count());
        }

        @Test
        void providerClosedRefundIsRecordedForCompensationAndCumulativeOverflowIsRefused() {
            sharedOrder();
            assertEquals(RefundRequestResult.ACCEPTED, adapter.submitRefund(paidFact(), seller("TX9"), "RF1", 4000, 6900, "部分"));
            gateway.refundQuery = new EcommercePayGateway.RefundQuery("RF1", "WXRF1", "R-SELLER-1", "TX9", "ABNORMAL", 4000L, "CNY", null);
            assertEquals("ABNORMAL", adapter.queryRefundFacts(paidFact(), seller("TX9"), "RF1").get("status"));
            EcomRefund closed = mapper.selectRefund("RF1");
            assertEquals("ABNORMAL", closed.getState());
            assertEquals("PROVIDER_ABNORMAL", closed.getFailReason());
            assertEquals(RefundRequestResult.ACCEPTED, adapter.submitRefund(paidFact(), seller("TX9"), "RF2", 6900, 6900, "全退"),
                    "异常退款不计入已退累计");
            assertEquals(RefundRequestResult.DEFINITIVE_FAILED,
                    adapter.submitRefund(paidFact(), seller("TX9"), "RF3", 1, 6900, "超额"));
        }

        @Test
        void refundBeforeAnySplitNeedsNoReturn() {
            mapper.trades.add(seller("TX9"));
            assertEquals(RefundRequestResult.ACCEPTED, adapter.submitRefund(paidFact(), seller("TX9"), "RF1", 6900, 6900, "退票"));
            assertEquals(List.of("refund:RF1:6900/6900"), gateway.calls);
        }
    }

    @Test
    void plannedSplitIsFrozenToManualWhenRefundComesFirst() {
        sharedPlanned();
        assertEquals(RefundRequestResult.ACCEPTED, adapter.submitRefund(paidFact(), seller("TX9"), "RF1", 6900, 6900, "退票"));
        EcomProfitShareOrder order = mapper.selectShareOrder("R-SELLER-1", 9L);
        assertEquals("MANUAL", order.getState());
        assertEquals("REFUNDED_BEFORE_SHARE", order.getManualReason());
        assertEquals(0, profitSharing.submitPlanned(10), "已转人工的计划不会再提交分账");
        assertEquals("NOT_REPLANNABLE", profitSharing.replan(order.getId()));
    }

    void sharedPlanned() {
        mapper.trades.add(seller("TX9"));
        EcomProfitShareOrder order = new EcomProfitShareOrder();
        order.setOutOrderNo("CYPS2");
        order.setOutTradeNo("R-SELLER-1");
        order.setTopicId(9L);
        order.setSubMchid(SUB);
        order.setTransactionId("TX9");
        order.setTotalFen(6900L);
        order.setSharedFen(690L);
        order.setPlanHash("e".repeat(64));
        order.setState("PLANNED");
        mapper.insertShareOrder(order);
    }

    @Test
    void lockAndCloseUseTheSellerMerchantNumber() {
        mapper.trades.add(seller(null));
        assertEquals(SUB, adapter.partnerMchid("R-SELLER-1"));
        assertNull(adapter.partnerMchid("R-OTHER"));
        when(intents.currentAttemptClosable("R-SELLER-1", "R-SELLER-1", SUB)).thenReturn(true);
        gateway.partnerOrder = new EcommercePayGateway.PartnerOrder(SP, SUB, "wx_mini", "R-SELLER-1", null, "CLOSED", null, 6900L, "CNY", null);
        assertTrue(adapter.closePaymentAttempt("R-SELLER-1", "R-SELLER-1", seller(null)));
        verify(intents).recordConfirmedClosedAttempt(SUB, "R-SELLER-1");
        assertTrue(gateway.calls.contains("partnerClose:R-SELLER-1"));
        verify(intents, never()).currentAttemptClosable(anyString(), anyString(), eq(PLATFORM));
        verify(intents, never()).hasAnyAttempt(anyString(), anyString());
    }
}
