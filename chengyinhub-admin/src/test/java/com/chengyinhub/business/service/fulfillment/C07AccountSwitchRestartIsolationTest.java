package com.chengyinhub.business.service.fulfillment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.fulfillment.EntitlementHoldProjection.HoldFact;
import com.chengyinhub.business.service.fulfillment.EntitlementHoldProjection.HoldView;
import com.chengyinhub.business.service.fulfillment.EntitlementHoldProjection.Outcome;
import com.chengyinhub.business.service.fulfillment.FourLedgerBillReread.BillFact;
import com.chengyinhub.business.service.fulfillment.FourLedgerBillReread.Dimension;
import com.chengyinhub.business.service.fulfillment.FourLedgerBillReread.View;
import com.chengyinhub.business.service.fulfillment.InvoiceHistoryUnknown.ApplicationFact;
import com.chengyinhub.business.service.fulfillment.MerchantSupplyDryRun.Decision;
import com.chengyinhub.business.service.fulfillment.MerchantSupplyDryRun.SkuSupplyFact;
import com.chengyinhub.business.service.fulfillment.TransferSoldPolicyGate.TransferFact;

/**
 * C12 isolation: merchant/account switch must not rewrite the original
 * requestId; in-process restart requery keeps UNKNOWN; missing requestId stays
 * Java {@code null}, never {@code ""}.
 */
class C07AccountSwitchRestartIsolationTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final Instant END = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void otherMerchantHoldDoesNotRewriteOriginalRequest() {
        EntitlementHoldProjection projection = new EntitlementHoldProjection();
        HoldFact original = new HoldFact("c07-hold-acct-1", 78L, 124L, 3L,
                EntitlementHoldProjection.STATUS_HELD, END, "");
        HoldFact otherMerchant = new HoldFact("c07-hold-acct-2", 79L, 125L, 9L,
                EntitlementHoldProjection.STATUS_HELD, END, "");
        HoldView first = projection.project(original, NOW);
        HoldView switched = projection.project(otherMerchant, NOW);
        assertEquals("c07-hold-acct-1", first.originalRequestId());
        assertEquals(Long.valueOf(3L), original.merchantId());
        assertEquals("c07-hold-acct-2", switched.originalRequestId());
        assertNotEquals(first.originalRequestId(), switched.originalRequestId());
        assertEquals(Outcome.HELD, projection.requery(original, NOW).outcome());
        assertEquals("c07-hold-acct-1", projection.requery(original, NOW).originalRequestId());
    }

    @Test
    void restartRequeryKeepsOriginalUnknownWechatBill() {
        BillFact missingBill = new BillFact("c07-bill-restart", Dimension.WECHAT_TRADE,
                new BigDecimal("69.00"), BigDecimal.ZERO, false, "");
        FourLedgerBillReread first = new FourLedgerBillReread();
        View original = first.reread("c07-bill-restart", List.of(missingBill));
        assertEquals(FourLedgerBillReread.Outcome.UNKNOWN, original.outcome(Dimension.WECHAT_TRADE));
        FourLedgerBillReread restarted = new FourLedgerBillReread();
        View recovered = restarted.requery("c07-bill-restart", List.of(missingBill));
        assertEquals(original.outcome(Dimension.WECHAT_TRADE), recovered.outcome(Dimension.WECHAT_TRADE));
        assertEquals("c07-bill-restart", recovered.originalRequestId());
        assertTrue(restarted.originalUnknownNotReplacedByProjectionSuccess(recovered, true));
        assertFalse(recovered.startedNewReconcile());
    }

    @Test
    void missingRequestIdStaysJavaNullNeverEmptyString() {
        Decision supply = new MerchantSupplyDryRun().evaluate(
                new SkuSupplyFact(8L, 3L, 10L, false, 60, 0, null, null,
                        START, END, null, null, null),
                NOW, 1);
        assertEquals(MerchantSupplyDryRun.Outcome.UNKNOWN, supply.outcome());
        assertNull(supply.originalRequestId());
        assertNotEquals("", supply.originalRequestId());

        HoldView hold = new EntitlementHoldProjection().project(
                new HoldFact(null, 78L, 124L, 3L, EntitlementHoldProjection.STATUS_HELD, END, ""),
                NOW);
        assertEquals(Outcome.UNKNOWN, hold.outcome());
        assertNull(hold.originalRequestId());
        assertFalse(hold.occupiesCapacity());

        InvoiceHistoryUnknown.View invoice = new InvoiceHistoryUnknown().reread(
                new ApplicationFact(null, "SUBMITTED", "", "", ""));
        assertEquals(InvoiceHistoryUnknown.FileStatus.UNKNOWN, invoice.fileStatus());
        assertNull(invoice.originalRequestId());
        assertFalse(invoice.startedNewApply());

        TransferSoldPolicyGate.Decision transfer = new TransferSoldPolicyGate().evaluate(
                new TransferFact(null, "", 88L, true, true));
        assertEquals(TransferSoldPolicyGate.Outcome.WAITING_SOURCE, transfer.outcome());
        assertNull(transfer.originalRequestId());
        assertFalse(transfer.grantsTransfer());
        assertFalse(transfer.usedSmsIdentityAsPolicy());

        assertThrows(IllegalArgumentException.class,
                () -> new FourLedgerBillReread().reread(null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new FourLedgerBillReread().reread("  ", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new FulfillmentEvidence().recordJourney(null, new FulfillmentEvidence.JourneyFacts(
                        false, false, null, null, null)));
    }
}
