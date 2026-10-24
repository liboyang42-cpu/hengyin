package com.chengyinhub.business.service.fulfillment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.fulfillment.FourLedgerBillReread.BillFact;
import com.chengyinhub.business.service.fulfillment.FourLedgerBillReread.Dimension;
import com.chengyinhub.business.service.fulfillment.FourLedgerBillReread.Outcome;
import com.chengyinhub.business.service.fulfillment.FourLedgerBillReread.View;

class FourLedgerBillRereadTest {

    private final FourLedgerBillReread reread = new FourLedgerBillReread();

    @Test
    void missingWechatBillIsUnknownAndDoesNotStartANewReconcile() {
        BillFact missingBill = new BillFact("c07-bill-1", Dimension.WECHAT_TRADE,
                new BigDecimal("69.00"), BigDecimal.ZERO, false, "");
        View view = reread.reread("c07-bill-1", List.of(missingBill));
        assertEquals(Outcome.UNKNOWN, view.outcome(Dimension.WECHAT_TRADE));
        assertEquals(Outcome.UNKNOWN, view.outcome(Dimension.REGISTRATION_ORDER));
        assertFalse(view.startedNewReconcile());
        assertEquals("c07-bill-1", view.originalRequestId());
        assertTrue(reread.originalUnknownNotReplacedByProjectionSuccess(view, true));
        assertEquals(view.outcome(Dimension.WECHAT_TRADE),
                reread.requery("c07-bill-1", List.of(missingBill)).outcome(Dimension.WECHAT_TRADE));
    }

    @Test
    void factsForAnotherRequestIdAreIgnored() {
        BillFact other = new BillFact("other-id", Dimension.MEMBER_BALANCE,
                BigDecimal.ONE, BigDecimal.ONE, true, "abc");
        View view = reread.reread("c07-bill-2", List.of(other));
        assertEquals(Outcome.UNKNOWN, view.outcome(Dimension.MEMBER_BALANCE));
        assertFalse(view.startedNewReconcile());
    }

    @Test
    void presentBillWithAmountGapIsDivergedNotANewRequest() {
        BillFact gap = new BillFact("c07-bill-3", Dimension.MERCHANT_PAYABLE,
                new BigDecimal("10.00"), new BigDecimal("12.00"), true, "hash-1");
        View view = reread.reread("c07-bill-3", List.of(gap));
        assertEquals(Outcome.DIVERGED, view.outcome(Dimension.MERCHANT_PAYABLE));
        assertFalse(view.startedNewReconcile());
    }

    @Test
    void nullAmountIsUnknownNotZeroCoercion() {
        BillFact fact = new BillFact("c07-bill-4", Dimension.WECHAT_TRADE,
                null, null, true, "hash");
        View view = reread.reread("c07-bill-4", List.of(fact));
        assertEquals(Outcome.UNKNOWN, view.outcome(Dimension.WECHAT_TRADE));
        assertNull(fact.expectedAmount());
        assertNull(fact.actualAmount());
        assertFalse(view.startedNewReconcile());
        assertFalse(view.currentProjectionReplacesOriginal());
    }

    @Test
    void missingRequestIdStaysNullUnknownAndDoesNotStartANewReconcile() {
        View view = reread.reread(null, List.of());
        assertNull(view.originalRequestId());
        assertEquals(Outcome.UNKNOWN, view.outcome(Dimension.WECHAT_TRADE));
        assertFalse(view.startedNewReconcile());
        View blank = reread.reread("  ", List.of());
        assertNull(blank.originalRequestId());
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueMatchedApplies() {
        FourLedgerBillReread bills = new FourLedgerBillReread();
        View neverBill = bills.recoverOriginal("c07-bill-never-seen");
        assertEquals("c07-bill-never-seen", neverBill.originalRequestId());
        assertEquals(Outcome.UNKNOWN, neverBill.outcome(Dimension.WECHAT_TRADE));
        View unknownBill = bills.reread("c07-bill-never-seen", List.of(
                new BillFact("c07-bill-never-seen", Dimension.WECHAT_TRADE,
                        new BigDecimal("69.00"), BigDecimal.ZERO, false, "")));
        assertEquals(Outcome.UNKNOWN, unknownBill.outcome(Dimension.WECHAT_TRADE));
        assertEquals(Outcome.UNKNOWN,
                bills.recoverOriginal("c07-bill-never-seen").outcome(Dimension.WECHAT_TRADE));
        View matched = bills.reread("c07-bill-never-seen", List.of(
                new BillFact("c07-bill-never-seen", Dimension.WECHAT_TRADE,
                        new BigDecimal("69.00"), new BigDecimal("69.00"), true, "wx-hash")));
        assertEquals(Outcome.MATCHED, matched.outcome(Dimension.WECHAT_TRADE));
        assertEquals(Outcome.MATCHED,
                bills.recoverOriginal("c07-bill-never-seen").outcome(Dimension.WECHAT_TRADE));
    }
}
