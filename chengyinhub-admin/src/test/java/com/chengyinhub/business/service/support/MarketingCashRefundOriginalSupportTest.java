package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketingCashRefundOriginalSupportTest {
    final MarketingProductOrderMapper orders = mock(MarketingProductOrderMapper.class);
    final CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
    final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    final GiftEntitlementMapper gifts = mock(GiftEntitlementMapper.class);
    final RefundApplicationMapper refunds = mock(RefundApplicationMapper.class);
    final WechatVerifiedPaymentFactService facts = mock(WechatVerifiedPaymentFactService.class);
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final MarketingCashRefundOriginalSupport support = new MarketingCashRefundOriginalSupport(
            orders, registrations, topics, gifts, db, refunds, facts);
    MarketingProductOrder order;
    CmsRegistration first, second;
    WechatVerifiedPaymentFact payment;

    @BeforeEach void seed() {
        order = new MarketingProductOrder();
        order.setId(10L); order.setOrderNo("MKO10"); order.setMemberId(7L); order.setProductId(1L);
        order.setPayMode(1); order.setPaymentFactType(1); order.setPaymentFactId(11L);
        order.setStatus(1); order.setCurrency("CNY"); order.setTotalCashAmount(money(10000));
        order.setPaidAt(new Date()); order.setPrimaryRegistrationId(101L);
        order.setPrimaryRegistrationNo("REG101"); order.setRegistrationIdsJson("[101,102]");
        order.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_SELF);
        first = row(101, 41, 4000); second = row(102, 42, 6000);
        first.setWechatPaymentAmount(money(10000)); first.setTransactionId("TX10");
        second.setWechatPaymentAmount(BigDecimal.ZERO);
        payment = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                "merchant", "appid", "MKO10", "WX10", "WX10", "TX10", "TX10", "JSAPI", "CNY",
                10000, 10000, Instant.parse("2026-11-09T00:00:00Z"), "a".repeat(64), "SUCCESS");
        when(orders.selectByOrderNo("MKO10")).thenReturn(order);
        when(orders.selectByOrderNoForUpdate("MKO10")).thenReturn(order);
        when(orders.selectFrozenLines("MKO10")).thenReturn(List.of(line(41,4000), line(42,6000)));
        when(registrations.selectCmsRegistrationById(101L)).thenReturn(first);
        when(registrations.selectCmsRegistrationById(102L)).thenReturn(second);
        when(registrations.selectByParentOrderNoForUpdate("MKO10", null)).thenReturn(List.of(first, second));
        when(topics.selectCmsTopicByIdForUpdate(anyLong())).thenAnswer(call -> {
            CmsTopic topic = new CmsTopic(); topic.setId(call.getArgument(0)); return topic;
        });
        when(facts.findPaymentSuccess("MKO10")).thenReturn(new WechatVerifiedPaymentFactService.Stored(
                new WechatVerifiedPaymentFact.Reference(11, payment.factHash()), payment));
        when(refunds.selectRegistrationHistoryForUpdate(anyLong())).thenReturn(List.of());
        when(db.queryForList(anyString(), eq(Long.class), anyLong(), anyLong())).thenReturn(List.of());
    }

    @Test void bothLinesResolveSameParentPaymentWithoutCopyingCashOrConsumption() {
        var a = support.find(101L).orElseThrow(); var b = support.find(102L).orElseThrow();
        assertEquals(a.payment(), b.payment()); assertEquals("MKO10", b.businessNo());
        assertEquals(money(10000), b.providerTotal()); assertEquals(6000, b.lineFen());
        assertEquals(BigDecimal.ZERO, second.getWechatPaymentAmount()); assertNull(second.getTransactionId());
        verify(facts, never()).requireConsumablePayment(any());
        var sequence = inOrder(topics, registrations, orders);
        sequence.verify(topics).selectCmsTopicByIdForUpdate(41L);
        sequence.verify(topics).selectCmsTopicByIdForUpdate(42L);
        sequence.verify(registrations).selectByParentOrderNoForUpdate("MKO10", null);
        sequence.verify(orders).selectByOrderNoForUpdate("MKO10");
    }

    @Test void missingParentDoesNotFallBackToOrdinaryPayment() {
        when(orders.selectByOrderNo("MKO10")).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> support.find(101L));
    }

    @Test void wrongConsumedFactAndForgedHashFailClosed() {
        order.setPaymentFactId(12L);
        assertThrows(IllegalStateException.class, () -> support.find(101L));
        order.setPaymentFactId(11L);
        when(facts.findPaymentSuccess("MKO10")).thenReturn(new WechatVerifiedPaymentFactService.Stored(
                new WechatVerifiedPaymentFact.Reference(11, "b".repeat(64)), payment));
        assertThrows(IllegalStateException.class, () -> support.find(101L));
    }

    @Test void wrongMemberOrMissingRealDeliveryIdCannotBorrowAParentPayment() {
        second.setMemberId(8L);
        assertThrows(IllegalStateException.class, () -> support.find(102L));
        second.setMemberId(7L); order.setRegistrationIdsJson("[101]");
        assertThrows(IllegalStateException.class, () -> support.find(102L));
    }

    @Test void equalPriceCannotDisambiguateDuplicateTopicOrDuplicateDeliveryIds() {
        second.setTopicId(41L); second.setOwnerId(41L); second.setPayableAmount(money(4000));
        assertThrows(IllegalStateException.class, () -> support.find(101L));
        second.setTopicId(42L); second.setOwnerId(42L); second.setPayableAmount(money(6000));
        order.setRegistrationIdsJson("[101,101]");
        assertThrows(IllegalStateException.class, () -> support.find(101L));
    }

    @Test void samePriceDifferentTopicStillRequiresTheExactOriginalRegistration() {
        first.setPayableAmount(money(5000)); second.setPayableAmount(money(5000));
        when(orders.selectFrozenLines("MKO10")).thenReturn(List.of(line(41,5000),line(42,5000)));
        var target = support.find(102L).orElseThrow();
        assertEquals(5000,target.lineFen());
        assertThrows(IllegalStateException.class,() -> support.requireRefund(target,refund(first,5000),false));
        support.requireRefund(target,refund(second,5000),false);
    }

    @Test void frozenAllocationDriftOrProviderTotalMismatchIsRejected() {
        second.setPayableAmount(money(5900));
        assertThrows(IllegalStateException.class, () -> support.find(102L));
        second.setPayableAmount(money(6000)); order.setTotalCashAmount(money(9900));
        assertThrows(IllegalStateException.class, () -> support.find(102L));
    }

    @Test void fullBundleCannotBeRefundedThroughPrimaryAndUnknownPartialIsRejected() {
        var original = support.find(101L).orElseThrow();
        assertThrows(IllegalStateException.class, () -> support.requireRefund(original, refund(first,10000), false));
        assertThrows(IllegalStateException.class, () -> support.requireRefund(original, refund(first,2000), false));
        support.requireRefund(original, refund(first,4000), false);
    }

    @Test void approvedUnknownAndPendingIntentKeepTheirLineBudgetReserved() {
        var original = support.find(102L).orElseThrow();
        RefundApplication prior = refund(second,6000); prior.setId(50L); prior.setStatus(1);
        prior.setPayoutStatus(RefundApplication.PAYOUT_DISPATCHING);
        when(refunds.selectRegistrationHistoryForUpdate(102L)).thenReturn(List.of(prior));
        assertThrows(IllegalStateException.class, () -> support.requireRefund(original, refund(second,6000), false));
        prior.setStatus(0);
        assertThrows(IllegalStateException.class, () -> support.requireRefund(original, refund(second,6000), false));
    }

    @Test void cumulativeHistoricalSuccessOnlyAllowsExactRemainingAmount() {
        var original = support.find(102L).orElseThrow();
        RefundApplication prior = refund(second,2000); prior.setId(50L); prior.setStatus(1);
        prior.setPayoutStatus(RefundApplication.PAYOUT_SUCCESS);
        when(refunds.selectRegistrationHistoryForUpdate(102L)).thenReturn(List.of(prior));
        support.requireRefund(original, refund(second,4000), false);
        assertThrows(IllegalStateException.class, () -> support.requireRefund(original, refund(second,6000), false));
    }

    @Test void callbackBeforeApprovalCannotAdvanceAndWrongParentIdentityCannotMatch() {
        var original = support.find(102L).orElseThrow();
        RefundApplication refund = refund(second,6000);
        assertThrows(IllegalStateException.class, () -> support.requireRefund(original, refund, true));
        refund.setRefundNo("RF102");
        assertTrue(support.matches(original, refund, refundFact("MKO10", "TX10", 6000)));
        assertFalse(support.matches(original, refund, refundFact("OTHER", "TX10", 6000)));
        assertFalse(support.matches(original, refund, refundFact("MKO10", "OTHER", 6000)));
        assertFalse(support.matches(original, refund, refundFact("MKO10", "TX10", 4000)));
    }

    @Test void giftUsesActualRecipientIncludingOldRunsAndKeepsBuyerAsRefundOwner() {
        order.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_GIFT);
        GiftEntitlement gift = new GiftEntitlement(); gift.setId(20L); gift.setOrderId(10L); gift.setProductId(1L);
        gift.setBuyerMemberId(7L); gift.setSourceRegistrationId(101L); gift.setStatus(1);
        gift.setClaimedMemberId(9L); gift.setClaimedRegistrationId(101L); gift.setClaimTokenHash("c".repeat(64));
        when(gifts.selectByOrderId(10L)).thenReturn(gift);
        when(gifts.selectByTokenHashForUpdate(gift.getClaimTokenHash())).thenReturn(gift);
        var original = support.find(102L).orElseThrow();
        support.requireRefund(original, refund(second,6000), false);
        verify(db).queryForList(anyString(), eq(Long.class), eq(9L), eq(42L));
        when(db.queryForList(anyString(), eq(Long.class), eq(9L), eq(42L))).thenReturn(List.of(88L));
        assertThrows(IllegalStateException.class, () -> support.requireRefund(original, refund(second,6000), false));
    }

    @Test void missingGiftOrMissingClaimedRegistrationBlocksBeforePayout() {
        order.setFulfillmentKind(MarketingProductOrder.FULFILLMENT_GIFT);
        var original = support.find(102L).orElseThrow();
        assertThrows(IllegalStateException.class, () -> support.requireRefund(original, refund(second,6000), false));
        GiftEntitlement gift = new GiftEntitlement(); gift.setId(20L); gift.setOrderId(10L); gift.setProductId(1L);
        gift.setBuyerMemberId(7L); gift.setSourceRegistrationId(101L); gift.setStatus(1);
        gift.setClaimedMemberId(9L); gift.setClaimTokenHash("c".repeat(64));
        when(gifts.selectByOrderId(10L)).thenReturn(gift);
        when(gifts.selectByTokenHashForUpdate(gift.getClaimTokenHash())).thenReturn(gift);
        assertThrows(IllegalStateException.class, () -> support.requireRefund(original, refund(second,6000), false));
    }

    @Test void ordinaryRegistrationHasNoMarketingAdapterAndRequiresOriginalReceiptElsewhere() {
        first.setParentOrderNo("ORDINARY");
        assertTrue(support.find(101L).isEmpty());
        verifyNoInteractions(orders, facts, topics, refunds, db);
    }

    private CmsRegistration row(long id, long topic, long fen) {
        CmsRegistration row = new CmsRegistration(); row.setId(id); row.setRegistrationNo("REG"+id);
        row.setParentOrderNo("MKO10"); row.setMemberId(7L); row.setOwnerType(1L);
        row.setOwnerId(topic); row.setTopicId(topic); row.setPayableAmount(money(fen));
        row.setPaymentStatus(2); row.setRegistrationStatus(2); return row;
    }
    private MarketingProductOrderLine line(long topic, long amount) {
        MarketingProductOrderLine line = new MarketingProductOrderLine(); line.setOrderId(10L);
        line.setTopicId(topic); line.setPayableAmount(money(amount)); return line;
    }
    private RefundApplication refund(CmsRegistration row, long amount) {
        RefundApplication refund = new RefundApplication(); refund.setSourceType("registration"); refund.setSourceId(row.getId());
        refund.setMemberId(7L); refund.setOwnerType(1L); refund.setOwnerId(row.getTopicId());
        refund.setRefundAmount(money(amount)); refund.setPaidAmount(row.getPayableAmount()); refund.setStatus(0); return refund;
    }
    private WechatVerifiedPaymentFact refundFact(String businessNo, String transaction, long amount) {
        return new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,"merchant","appid",businessNo,"WX10",
                "RF102","WR102",transaction,"JSAPI","CNY",10000,amount,payment.successAt(),payment.intentRequestHash(),"REFUND");
    }
    private static BigDecimal money(long fen) { return BigDecimal.valueOf(fen,2); }
}
