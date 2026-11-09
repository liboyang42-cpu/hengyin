package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.GiftEntitlement;
import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.MarketingProductOrderLine;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.RefundSettledFact;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.GiftEntitlementMapper;
import com.chengyinhub.business.mapper.MarketingProductOrderMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.IMarketingProductPurchaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketingProductRefundConsumerTest {
    private final MarketingProductOrderMapper orders = mock(MarketingProductOrderMapper.class);
    private final CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
    private final RefundApplicationMapper refunds = mock(RefundApplicationMapper.class);
    private final GiftEntitlementMapper gifts = mock(GiftEntitlementMapper.class);
    private final PlayerNodeProgressMapper progress = mock(PlayerNodeProgressMapper.class);
    private final IMarketingProductPurchaseService purchase = mock(IMarketingProductPurchaseService.class);
    private final MarketingProductRefundConsumer consumer = new MarketingProductRefundConsumer(
            orders, purchase, registrations, refunds, gifts, progress);
    private MarketingProductOrder order;
    private List<CmsRegistration> rows;
    private List<MarketingProductOrderLine> lines;
    private Map<Long, List<RefundApplication>> history;

    @BeforeEach
    void setUp() {
        order = new MarketingProductOrder();
        order.setId(10L);
        order.setOrderNo("bundle-10");
        order.setMemberId(7L);
        order.setPrimaryRegistrationId(101L);
        order.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_SELF);
        order.setStatus(MarketingProductOrder.STATUS_PAID);
        order.setPaymentFactType(MarketingProductOrder.PAYMENT_FACT_WECHAT);
        order.setCurrency("CNY");
        order.setTotalCashAmount(new BigDecimal("100.00"));
        rows = new ArrayList<>(List.of(row(101, 41, "40.00"), row(102, 42, "60.00")));
        lines = new ArrayList<>(List.of(line(41, "40.00"), line(42, "60.00")));
        history = new HashMap<>();
        history.put(101L, new ArrayList<>());
        history.put(102L, new ArrayList<>());
        when(orders.selectByPrimaryRegistrationId(101L)).thenReturn(order);
        when(orders.selectByRegistrationIdInJson(102L)).thenReturn(order);
        when(orders.selectByOrderNoForUpdate("bundle-10")).thenReturn(order);
        when(orders.selectFrozenLines("bundle-10")).thenAnswer(i -> lines);
        when(registrations.selectByParentOrderNoForUpdate("bundle-10", null)).thenAnswer(i -> rows);
        when(refunds.selectRegistrationHistoryForUpdate(anyLong())).thenAnswer(i -> history.get(i.getArgument(0)));
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(List.of());
    }

    @Test
    void firstItemRefundKeepsOtherItemAndTheParentOrder() {
        settle(101, 501, "40.00");
        consumer.onRefundSettled(fact(101, 501, 4000));
        verifyNoInteractions(purchase);
        assertEquals(2, rows.get(1).getPaymentStatus());
        assertEquals(2, rows.get(1).getRegistrationStatus());
        verify(registrations, never()).claimRegistrationRefunded(anyLong(), any(), any());
        verify(refunds, never()).insertRefundApplication(any());
    }

    @Test
    void sourceIdSelectsTheItemEvenWhenTwoItemsHaveTheSameAmount() {
        lines.get(0).setPayableAmount(new BigDecimal("50.00"));
        lines.get(1).setPayableAmount(new BigDecimal("50.00"));
        rows.forEach(r -> r.setPayableAmount(new BigDecimal("50.00")));
        settle(102, 502, "50.00");
        consumer.onRefundSettled(fact(102, 502, 5000));
        verify(progress).selectPlayerNodeProgressList(argThat(p -> p.getTopicId().equals(42L)));
        assertEquals(2, rows.get(0).getPaymentStatus());
        verifyNoInteractions(purchase);
    }

    @Test
    void fullOrderAmountOnOneItemCannotBeUsedToGuessOtherTargets() {
        settle(101, 501, "100.00");
        assertThrows(IllegalStateException.class, () -> consumer.onRefundSettled(fact(101, 501, 10000)));
        verifyNoInteractions(purchase);
    }

    @Test
    void unknownPartialAmountFailsClosed() {
        settle(101, 501, "20.00");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> consumer.onRefundSettled(fact(101, 501, 2000))).getMessage()
                .contains("MARKETING_PARTIAL_REFUND_REQUIRES_LINE_HANDLING"));
        verifyNoInteractions(purchase);
    }

    @Test
    void priorSuccessfulRefundIsCountedOnceAndOnlyTheExactRemainderIsAccepted() {
        settle(101, 500, "10.00");
        settle(101, 501, "30.00");
        consumer.onRefundSettled(fact(101, 501, 3000));
        consumer.onRefundSettled(fact(101, 501, 3000));
        assertEquals(2, history.get(101L).size());
        verifyNoInteractions(purchase);
    }

    @Test
    void approvedButNotSettledRefundDoesNotCompleteTheRemainder() {
        settle(101, 500, "10.00").setPayoutStatus(RefundApplication.PAYOUT_PROCESSING);
        settle(101, 501, "30.00");
        assertThrows(IllegalStateException.class, () -> consumer.onRefundSettled(fact(101, 501, 3000)));
        verifyNoInteractions(purchase);
    }

    @Test
    void onlyAllFrozenItemsWithSettledCashCloseTheParent() {
        settle(101, 501, "40.00");
        settle(102, 502, "60.00");
        consumer.onRefundSettled(fact(102, 502, 6000));
        verify(purchase).revokeAfterRefund("bundle-10", "REFUND:RF502");
    }

    @Test
    void pendingActivationItemKeepsItsPurchaseSource() {
        rows.remove(1);
        settle(101, 501, "40.00");
        consumer.onRefundSettled(fact(101, 501, 4000));
        verifyNoInteractions(purchase);
    }

    @Test
    void claimedGiftReadsTheRealBeneficiaryAndKeepsTheUnrefundedGiftSource() {
        gift();
        settle(101, 501, "40.00");
        consumer.onRefundSettled(fact(101, 501, 4000));
        verify(progress).selectPlayerNodeProgressList(argThat(p -> p.getMemberId().equals(9L)
                && p.getTopicId().equals(41L) && p.getActivityId() == null));
        verifyNoInteractions(purchase);
        verify(gifts, never()).revoke(anyLong(), anyString(), any());
    }

    @Test
    void claimedGiftAlreadyUsedByRecipientFailsClosed() {
        gift();
        settle(101, 501, "40.00");
        when(progress.selectPlayerNodeProgressList(argThat(p -> p != null && p.getMemberId().equals(9L))))
                .thenReturn(List.of(new PlayerNodeProgress()));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> consumer.onRefundSettled(fact(101, 501, 4000))).getMessage()
                .contains("USED_CONTENT_NOT_REVOCABLE"));
        verifyNoInteractions(purchase);
    }

    @Test
    void duplicateDeliveryTopicAndDriftedAllocationFailClosed() {
        settle(101, 501, "40.00");
        rows.add(row(103, 41, "40.00"));
        assertThrows(IllegalStateException.class, () -> consumer.onRefundSettled(fact(101, 501, 4000)));
        rows.remove(2);
        lines.get(0).setPayableAmount(new BigDecimal("41.00"));
        assertThrows(IllegalStateException.class, () -> consumer.onRefundSettled(fact(101, 501, 4000)));
        verifyNoInteractions(purchase);
    }

    @Test
    void missingOriginalRefundOrChangedPayloadCannotRevoke() {
        assertThrows(IllegalStateException.class, () -> consumer.onRefundSettled(fact(101, 501, 4000)));
        settle(101, 501, "40.00");
        assertThrows(IllegalStateException.class, () -> consumer.onRefundSettled(fact(101, 501, 3900)));
        verifyNoInteractions(purchase);
    }

    private GiftEntitlement gift() {
        order.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_GIFT);
        GiftEntitlement gift = new GiftEntitlement();
        gift.setOrderId(10L);
        gift.setBuyerMemberId(7L);
        gift.setSourceRegistrationId(101L);
        gift.setStatus(GiftEntitlement.STATUS_CLAIMED);
        gift.setClaimedMemberId(9L);
        gift.setClaimedRegistrationId(101L);
        when(gifts.selectByOrderId(10L)).thenReturn(gift);
        return gift;
    }

    private RefundApplication settle(long registrationId, long refundId, String amount) {
        CmsRegistration row = rows.stream().filter(r -> r.getId() == registrationId).findFirst().orElseThrow();
        row.setPaymentStatus(4);
        row.setRegistrationStatus(3);
        RefundApplication refund = new RefundApplication();
        refund.setId(refundId);
        refund.setRefundNo("RF" + refundId);
        refund.setSourceType("registration");
        refund.setSourceId(registrationId);
        refund.setMemberId(7L);
        refund.setPaidAmount(row.getPayableAmount());
        refund.setRefundAmount(new BigDecimal(amount));
        refund.setStatus(1);
        refund.setPayoutStatus(RefundApplication.PAYOUT_SUCCESS);
        history.get(registrationId).add(refund);
        return refund;
    }

    private RefundSettledFact fact(long registrationId, long refundId, long fen) {
        return new RefundSettledFact(registrationId, refundId, "RF" + refundId, "provider-" + refundId,
                fen, 10000, "CNY", refundId + 1000, "verified-" + refundId);
    }

    private CmsRegistration row(long id, long topicId, String amount) {
        CmsRegistration row = new CmsRegistration();
        row.setId(id);
        row.setParentOrderNo("bundle-10");
        row.setTopicId(topicId);
        row.setOwnerType(1L);
        row.setOwnerId(topicId);
        row.setMemberId(7L);
        row.setPayableAmount(new BigDecimal(amount));
        row.setPaymentStatus(2);
        row.setRegistrationStatus(2);
        return row;
    }

    private MarketingProductOrderLine line(long topicId, String amount) {
        MarketingProductOrderLine line = new MarketingProductOrderLine();
        line.setOrderId(10L);
        line.setTopicId(topicId);
        line.setPayableAmount(new BigDecimal(amount));
        return line;
    }
}
