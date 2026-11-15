package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.collection.ClubDuesSplitService;
import com.chengyinhub.business.collection.CollectionDecision;
import com.chengyinhub.business.collection.CollectionModelService;
import com.chengyinhub.business.collection.CollectionPolicy;
import com.chengyinhub.business.collection.mapper.ClubDuesSplitMapper;
import com.chengyinhub.business.collection.mapper.CollectionModelMapper;
import com.chengyinhub.business.domain.ClubMembershipInvoice;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomProfitShareLine;
import com.chengyinhub.business.domain.EcomProfitShareOrder;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.merchantplans.TakeRateService;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.business.moneyswitch.mapper.MoneySwitchMapper;
import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.EcommerceSellerResolver;
import com.chengyinhub.business.payout.FakeEcomPayoutMapper;
import com.chengyinhub.business.payout.FakeEcommerceGateway;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.payout.SellerOfRecordPolicy.Facts;
import com.chengyinhub.business.payout.SellerOfRecordPolicy.ProductKind;
import com.chengyinhub.business.payout.SellerOfRecordPolicy.SellerType;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.PaymentPreorderException;
import com.chengyinhub.business.service.RefundRequestResult;
import com.chengyinhub.common.exception.BusinessRefusal;
import com.chengyinhub.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * K1 club dues through split settlement: ceiling off changes nothing; a club without a finished sub-merchant is refused
 * before any order row; dues go to the club's own sub-merchant with the take frozen before WeChat, never to a platform-held
 * balance; profit sharing waits for refunds; a refund returns the shared take first.
 */
class ClubDuesSplitTest {
    static final String SP = "1900000001";
    static final String SUB = "1900000999";
    static final String PLATFORM = "1900000109";
    static final String CLM = "CLM0123456789abcdef0123456789abc";
    static final PayoutSwitches ON = new PayoutSwitches(true, true, true, SP, "wx_mini", "", 30, false, "none");
    static final PayoutSwitches RAILS_OFF = new PayoutSwitches(false, false, false, SP, "wx_mini", "", 30, false, "none");

    EcommerceSellerResolver sellers;
    CollectionModelMapper collectionMapper;
    ClubDuesSplitMapper duesMapper;
    FakeEcomPayoutMapper payoutMapper;
    FakeEcommerceGateway gateway;
    EcommerceProfitShareService profitSharing;
    WechatPaymentIntentStore intents;
    TakeRateService takeRates;

    @BeforeEach
    void setUp() {
        sellers = mock(EcommerceSellerResolver.class);
        collectionMapper = mock(CollectionModelMapper.class);
        duesMapper = mock(ClubDuesSplitMapper.class);
        payoutMapper = new FakeEcomPayoutMapper() {
            @Override public Long selectSellerMemberId(String t, Long id) { return "CLUB".equals(t) && id == 3L ? 500L : null; }
        };
        gateway = new FakeEcommerceGateway();
        profitSharing = new EcommerceProfitShareService(payoutMapper, gateway, ON, TransactionOperations.withoutTransaction());
        intents = mock(WechatPaymentIntentStore.class);
        when(intents.freezePaymentIntent(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), anyString(), any())).thenAnswer(call -> call.getArgument(0));
        takeRates = mock(TakeRateService.class);
        when(takeRates.clubDuesRate()).thenReturn(new BigDecimal("0.0300"));
    }

    CollectionModelService model(boolean ceiling, PayoutSwitches switches) {
        return new CollectionModelService(new MoneySwitchService(mock(MoneySwitchMapper.class), ceiling), switches, sellers,
                mock(CmsActivityMapper.class), mock(ISettlementRuleService.class), collectionMapper, "SPLIT", "SPLIT", "SPLIT");
    }

    void club(String state, String sub) {
        when(sellers.clubDuesFacts(3L)).thenReturn(new Facts(ProductKind.CLUB_DUES, false, SellerType.CLUB, 3L, state, sub));
    }

    EcommercePartnerPaymentAdapter adapter(CollectionModelService model) {
        EcommercePartnerPaymentAdapter adapter = new EcommercePartnerPaymentAdapter(ON, payoutMapper, gateway, sellers,
                profitSharing, intents, mock(WechatVerifiedPaymentFactService.class), mock(UmsMemberMapper.class));
        adapter.setCollectionModel(model);
        return adapter;
    }

    ClubDuesSplitService dues(CollectionModelService model, PayoutSwitches switches, EcommercePartnerPaymentAdapter adapter) {
        ClubDuesSplitService service = new ClubDuesSplitService(model, duesMapper, collectionMapper, switches, profitSharing,
                takeRates);
        ReflectionTestUtils.setField(service, "partner", adapter);
        return service;
    }

    static ClubMembershipInvoice invoice() {
        ClubMembershipInvoice invoice = new ClubMembershipInvoice();
        invoice.setId(100L);
        invoice.setClubId(3L);
        invoice.setMemberId(71L);
        invoice.setOutTradeNo(CLM);
        invoice.setAmountFen(9900L);
        invoice.setPlanNameSnapshot("年度会员");
        return invoice;
    }

    static Map<String, Object> dueRow(String takeRate) {
        Map<String, Object> row = new HashMap<>();
        row.put("outTradeNo", CLM);
        row.put("invoiceId", 100L);
        row.put("clubId", 3L);
        row.put("subMchid", SUB);
        row.put("totalFen", 9900L);
        row.put("takeRate", takeRate == null ? null : new BigDecimal(takeRate));
        row.put("transactionId", "TXD1");
        return row;
    }

    // ---- ceiling off: exactly today's behaviour ----

    @Test
    void ceilingOffChangesNothingAndReadsNothing() {
        CollectionModelService model = model(false, ON);
        CollectionDecision d = model.requireClubDuesCollectible(3L);
        assertTrue(d.platform(), "the caller keeps today's platform order");
        assertEquals(CollectionDecision.MODEL_OFF, d.reason());
        assertEquals("AVAILABLE", model.clubDuesStatus(3L).get("state"));
        assertDoesNotThrow(() -> model.requirePlatformMayCollectDues(CLM));
        assertEquals("CLUB_DUES", model.policies().keySet().stream().filter("CLUB_DUES"::equals).findFirst().orElse(null));
        verifyNoInteractions(sellers, collectionMapper);

        ClubDuesSplitService service = dues(model, RAILS_OFF, adapter(model));
        assertEquals(0, service.planDue(50));
        assertFalse(service.isSplitCollected(CLM));
        verifyNoInteractions(duesMapper);
    }

    // ---- refusals ----

    @Test
    void clubWithoutAFinishedSubMerchantIsRefusedWithAPathToBind() {
        club("AUDITING", null);
        CollectionModelService model = model(true, ON);
        BusinessRefusal refusal = assertThrows(BusinessRefusal.class, () -> model.requireClubDuesCollectible(3L));
        assertEquals(ErrorCode.CLUB_DUES_COLLECTION_UNAVAILABLE, refusal.getErrorCode());
        verify(collectionMapper).recordRefusal("DUES", 3L, CollectionDecision.SELLER_NOT_BOUND);
        Map<String, Object> status = model.clubDuesStatus(3L);
        assertEquals("NOT_BOUND", status.get("state"));
        assertEquals("BIND_COLLECTION_ACCOUNT", status.get("nextStep"));
        assertFalse(status.toString().contains("1900"), "no merchant ids leave the server");
    }

    @Test
    void railsOffOrPlatformSubMchidOrRefusePolicyAreRefusedNeverPlatformCollected() {
        club("FINISH", SUB);
        assertEquals(CollectionDecision.SPLIT_RAILS_OFF, model(true, RAILS_OFF).decideClubDues(3L).reason());
        assertEquals("WAIT_FOR_PLATFORM", model(true, RAILS_OFF).clubDuesStatus(3L).get("nextStep"));
        club("FINISH", SP);
        assertEquals(CollectionDecision.SUB_MCHID_IS_PLATFORM, model(true, ON).decideClubDues(3L).reason());
        club("FINISH", SUB);
        CollectionModelService refuse = model(true, ON);
        ReflectionTestUtils.invokeMethod(refuse, "setClubDuesPolicy", "PLATFORM");
        CollectionDecision d = refuse.decideClubDues(3L);
        assertTrue(d.refused(), "a PLATFORM policy for dues is read as REFUSE");
        assertEquals(CollectionDecision.DUES_SPLIT_NOT_BUILT, d.reason());
        when(sellers.clubDuesFacts(4L)).thenReturn(null);
        assertTrue(model(true, ON).decideClubDues(4L).refused());
    }

    @Test
    void thePlatformAccountNeverCollectsDuesWhileTheModelIsOn() {
        CollectionModelService model = model(true, ON);
        assertThrows(IllegalStateException.class, () -> model.requirePlatformMayCollectDues(CLM));
        assertDoesNotThrow(() -> model.requirePlatformMayCollectDues("R-TICKET-1"), "tickets have their own guard");
    }

    // ---- split order ----

    @Test
    void duesArePlacedOnTheClubSubMerchantWithTheTakeFrozenBeforeWechat() {
        club("FINISH", SUB);
        CollectionModelService model = model(true, ON);
        CollectionDecision split = model.requireClubDuesCollectible(3L);
        assertTrue(split.split());
        assertEquals(SUB, split.subMchid());
        ClubDuesSplitService service = dues(model, ON, adapter(model));

        Map<String, String> params = service.createOrder(invoice(), split, "openid-71");

        assertNotNull(params);
        assertEquals(List.of("prepay"), gateway.calls, "one service-provider JSAPI order, nothing on the platform account");
        EcomOrderSeller row = payoutMapper.selectOrderSeller(CLM);
        assertEquals(SUB, row.getSubMchid());
        assertEquals("CLUB", row.getSellerType());
        assertEquals(3L, row.getSellerId());
        assertEquals(9900L, row.getTotalFen());
        InOrder ledgerFirst = inOrder(duesMapper, intents);
        ledgerFirst.verify(duesMapper).insertOrder(CLM, 100L, 3L, SUB, 9900L, new BigDecimal("0.0300"));
        ledgerFirst.verify(intents).freezePaymentIntent(eq(CLM), eq(SUB), anyString(), eq("JSAPI"), eq("CNY"), eq(9900),
                eq("openid-71"), anyString(), anyString(), any());
    }

    @Test
    void anAttemptAlreadyOnThePlatformAccountIsRefusedNotMoved() {
        club("FINISH", SUB);
        CollectionModelService model = model(true, ON);
        CollectionDecision split = model.requireClubDuesCollectible(3L);
        when(intents.hasAnyAttempt(PLATFORM, CLM)).thenReturn(true);
        EcommercePartnerPaymentAdapter adapter = adapter(model);
        assertThrows(PaymentPreorderException.class,
                () -> adapter.createDuesOrder(CLM, 9900L, "会费", "openid-71", split, PLATFORM, seller -> { }));
        assertTrue(gateway.calls.isEmpty());
        assertEquals(null, payoutMapper.selectOrderSeller(CLM));
    }

    @Test
    void aRefusedOrPlatformDecisionNeverReachesWechat() {
        CollectionModelService model = model(true, ON);
        EcommercePartnerPaymentAdapter adapter = adapter(model);
        CollectionDecision refused = new CollectionDecision(CollectionPolicy.REFUSE, SellerType.CLUB, 3L, null,
                CollectionDecision.SELLER_NOT_BOUND);
        assertThrows(PaymentPreorderException.class,
                () -> adapter.createDuesOrder(CLM, 9900L, "会费", "openid-71", refused, PLATFORM, null));
        ClubDuesSplitService service = dues(model, ON, adapter);
        assertThrows(PaymentPreorderException.class, () -> service.createOrder(invoice(), refused, "openid-71"));
        assertTrue(gateway.calls.isEmpty());
        assertEquals(null, payoutMapper.selectOrderSeller(CLM));
        verify(duesMapper, never()).insertOrder(anyString(), anyLong(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void aMissingTakeRateIsFrozenAsNullAndLaterSentToManualReview() {
        club("FINISH", SUB);
        when(takeRates.clubDuesRate()).thenThrow(new IllegalStateException("db down"));
        CollectionModelService model = model(true, ON);
        ClubDuesSplitService service = dues(model, ON, adapter(model));
        service.createOrder(invoice(), model.requireClubDuesCollectible(3L), "openid-71");
        verify(duesMapper).insertOrder(eq(CLM), eq(100L), eq(3L), eq(SUB), eq(9900L), isNull());

        when(duesMapper.selectDueOrders(any(), anyInt())).thenReturn(List.of(dueRow(null)));
        when(duesMapper.updateOrderState(eq(CLM), eq("OPEN"), eq("PLANNED"), any(), isNull())).thenReturn(1);
        payoutMapper.trades.add(ClubDuesSplitServiceTrade.of(CLM, SUB, 9900L, "TXD1"));
        service.planDue(10);
        EcomProfitShareOrder order = payoutMapper.selectShareOrdersByTrade(CLM).get(0);
        assertEquals("MANUAL", order.getState());
        assertEquals(EcommerceProfitShareService.SPLIT_PREFIX + "TAKE_RATE_MISSING", order.getManualReason());
    }

    // ---- settlement: the platform take is the only receiver, never a balance ----

    @Test
    void duesShareOnlyThePlatformTakeAndTheRestStaysOnTheClubSubMerchant() {
        CollectionModelService model = model(true, ON);
        ClubDuesSplitService service = dues(model, ON, adapter(model));
        when(duesMapper.selectDueOrders(any(), anyInt())).thenReturn(List.of(dueRow("0.0300")));
        when(duesMapper.updateOrderState(eq(CLM), eq("OPEN"), eq("PLANNED"), any(), isNull())).thenReturn(1);

        assertEquals(1, service.planDue(10));

        EcomProfitShareOrder order = payoutMapper.selectShareOrdersByTrade(CLM).get(0);
        assertEquals("PLANNED", order.getState(), "manual reason: " + order.getManualReason());
        assertEquals(ClubDuesSplitService.DUES_SOURCE_ID, order.getTopicId());
        List<EcomProfitShareLine> lines = payoutMapper.selectShareLines(order.getId());
        assertEquals(297L, lines.stream().filter(l -> !"RETAINED".equals(l.getReceiverKind()))
                .mapToLong(EcomProfitShareLine::getAmountFen).sum(), "3% of 99.00 to the platform, the only receiver");
        assertTrue(lines.stream().filter(l -> !"RETAINED".equals(l.getReceiverKind()))
                .allMatch(l -> SP.equals(l.getReceiverMchid())));
        assertEquals(9603L, lines.stream().filter(l -> "RETAINED".equals(l.getReceiverKind()) && SUB.equals(l.getReceiverMchid()))
                .mapToLong(EcomProfitShareLine::getAmountFen).sum(), "the club's part stays on its own sub-merchant");
        assertTrue(lines.stream().noneMatch(l -> "MEMBER".equals(l.getReceiverKind())), "no member balance anywhere");
    }

    @Test
    void sharingWaitsForAnOpenRefundAndAFullRefundClosesTheOrder() {
        CollectionModelService model = model(true, ON);
        ClubDuesSplitService service = dues(model, ON, adapter(model));
        when(duesMapper.selectDueOrders(any(), anyInt())).thenReturn(List.of(dueRow("0.0300")));
        when(collectionMapper.countOpenRefunds(CLM)).thenReturn(1);
        assertEquals(0, service.planDue(10));
        assertTrue(payoutMapper.selectShareOrdersByTrade(CLM).isEmpty(), "refunds first: no share while one is open");

        when(collectionMapper.countOpenRefunds(CLM)).thenReturn(0);
        when(collectionMapper.sumSucceededRefundFen(CLM)).thenReturn(9900L);
        assertEquals(0, service.planDue(10));
        verify(duesMapper).updateOrderState(CLM, "OPEN", "REFUNDED", null, "FULLY_REFUNDED");
        assertTrue(payoutMapper.selectShareOrdersByTrade(CLM).isEmpty());
    }

    @Test
    void aDuesRefundReturnsTheSharedTakeFirst() {
        CollectionModelService model = model(true, ON);
        EcommercePartnerPaymentAdapter adapter = adapter(model);
        EcomOrderSeller trade = ClubDuesSplitServiceTrade.of(CLM, SUB, 9900L, "TXD1");
        payoutMapper.trades.add(trade);
        Long id = profitSharing.planSingleSeller(ClubDuesSplitService.DUES_SOURCE_ID, trade, 297L, null);
        payoutMapper.selectShareOrdersByTrade(CLM).get(0).setState("SUCCESS");
        assertNotNull(id);

        RefundRequestResult result = adapter.submitRefund(new WechatVerifiedPaymentFact(
                WechatVerifiedPaymentFact.Kind.PAYMENT, SUB, "wx_mini", CLM, CLM, CLM, "TXD1", "TXD1", "JSAPI", "CNY",
                9900, 9900, java.time.Instant.parse("2026-11-10T02:00:00Z"), "b".repeat(64), "SUCCESS"),
                trade, "CRF1", 9900, 9900, "会费退款");

        assertEquals(RefundRequestResult.ACCEPTED, result);
        assertEquals(List.of("return:" + SP + ":297", "refund:CRF1:9900/9900"), gateway.calls,
                "return the platform's take, then refund from the club sub-merchant");
    }

    /** Builds the ecom_order_seller view of a dues trade. */
    static final class ClubDuesSplitServiceTrade {
        static EcomOrderSeller of(String no, String sub, long total, String tx) {
            EcomOrderSeller trade = new EcomOrderSeller();
            trade.setOutTradeNo(no);
            trade.setSubMchid(sub);
            trade.setSellerType("CLUB");
            trade.setSellerId(3L);
            trade.setTotalFen(total);
            trade.setTransactionId(tx);
            return trade;
        }
    }
}
