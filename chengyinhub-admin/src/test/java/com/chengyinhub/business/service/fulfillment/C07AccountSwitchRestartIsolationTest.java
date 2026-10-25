package com.chengyinhub.business.service.fulfillment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        View bills = new FourLedgerBillReread().reread(null, List.of());
        assertNull(bills.originalRequestId());
        assertEquals(FourLedgerBillReread.Outcome.UNKNOWN, bills.outcome(Dimension.WECHAT_TRADE));
        assertFalse(bills.startedNewReconcile());
        View blankBills = new FourLedgerBillReread().reread("  ", List.of());
        assertNull(blankBills.originalRequestId());

        List<FulfillmentEvidence.ChainStep> missing = new FulfillmentEvidence().recordJourney(
                null, new FulfillmentEvidence.JourneyFacts(false, false, null, null, null));
        assertEquals(1, missing.size());
        assertNull(missing.get(0).originalRequestId());
        assertEquals(FulfillmentEvidence.Outcome.UNKNOWN, missing.get(0).outcome());
        assertEquals(FulfillmentEvidence.Stage.ORDER_ISSUE, missing.get(0).stage());

        EntitlementHoldProjection holdLedger = new EntitlementHoldProjection();
        assertNull(holdLedger.recoverOriginal(null).originalRequestId());
        assertEquals(Outcome.UNKNOWN, holdLedger.recoverOriginal(null).outcome());
        assertEquals(0, holdLedger.occupyCount(null));
        assertNull(new FourLedgerBillReread().recoverOriginal(null).originalRequestId());
        assertNull(new InvoiceHistoryUnknown().recoverOriginal("").originalRequestId());
        assertNull(new MerchantSupplyDryRun().recoverOriginal(null).originalRequestId());
        assertNull(new TransferSoldPolicyGate().recoverOriginal(null).originalRequestId());
        assertNull(new FulfillmentEvidence().recoverOriginal(null).get(0).originalRequestId());
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyLedgerThenUniqueWriteApplies() {
        EntitlementHoldProjection holds = new EntitlementHoldProjection();
        HoldView neverHold = holds.recoverOriginal("c07-hold-never-seen");
        assertEquals("c07-hold-never-seen", neverHold.originalRequestId());
        assertEquals(Outcome.UNKNOWN, neverHold.outcome());
        assertEquals(0, holds.occupyCount("c07-hold-never-seen"));
        HoldView unknownHold = holds.project(
                new HoldFact("c07-hold-never-seen", 78L, 124L, 9L, null, END, ""), NOW);
        assertEquals(Outcome.UNKNOWN, unknownHold.outcome());
        assertEquals(0, holds.occupyCount("c07-hold-never-seen"));
        HoldView held = holds.project(
                new HoldFact("c07-hold-never-seen", 78L, 124L, 9L,
                        EntitlementHoldProjection.STATUS_HELD, END, ""), NOW);
        assertEquals(Outcome.HELD, held.outcome());
        assertEquals(1, holds.occupyCount("c07-hold-never-seen"));
        assertEquals(Outcome.HELD, holds.recoverOriginal("c07-hold-never-seen").outcome());
        HoldView steal = holds.project(
                new HoldFact("c07-hold-never-seen", 99L, 1L, 2L,
                        EntitlementHoldProjection.STATUS_REDEEMED, END, ""), NOW);
        assertEquals(Outcome.UNKNOWN, steal.outcome());
        assertEquals(Outcome.HELD, holds.recoverOriginal("c07-hold-never-seen").outcome());
        assertEquals(1, holds.occupyCount("c07-hold-never-seen"));

        FourLedgerBillReread bills = new FourLedgerBillReread();
        View neverBill = bills.recoverOriginal("c07-bill-never-seen");
        assertEquals("c07-bill-never-seen", neverBill.originalRequestId());
        assertEquals(FourLedgerBillReread.Outcome.UNKNOWN, neverBill.outcome(Dimension.WECHAT_TRADE));
        View unknownBill = bills.reread("c07-bill-never-seen", List.of(
                new BillFact("c07-bill-never-seen", Dimension.WECHAT_TRADE,
                        new BigDecimal("69.00"), BigDecimal.ZERO, false, "")));
        assertEquals(FourLedgerBillReread.Outcome.UNKNOWN, unknownBill.outcome(Dimension.WECHAT_TRADE));
        assertEquals(FourLedgerBillReread.Outcome.UNKNOWN,
                bills.recoverOriginal("c07-bill-never-seen").outcome(Dimension.WECHAT_TRADE));
        View matched = bills.reread("c07-bill-never-seen", List.of(
                new BillFact("c07-bill-never-seen", Dimension.WECHAT_TRADE,
                        new BigDecimal("69.00"), new BigDecimal("69.00"), true, "wx-hash")));
        assertEquals(FourLedgerBillReread.Outcome.MATCHED, matched.outcome(Dimension.WECHAT_TRADE));
        assertEquals(FourLedgerBillReread.Outcome.MATCHED,
                bills.recoverOriginal("c07-bill-never-seen").outcome(Dimension.WECHAT_TRADE));

        InvoiceHistoryUnknown invoices = new InvoiceHistoryUnknown();
        assertEquals(InvoiceHistoryUnknown.FileStatus.UNKNOWN,
                invoices.recoverOriginal("c07-inv-never-seen").fileStatus());
        assertEquals("c07-inv-never-seen", invoices.recoverOriginal("c07-inv-never-seen").originalRequestId());
        invoices.reread(new ApplicationFact("c07-inv-never-seen", "SUBMITTED", "", "", "MANUAL"));
        assertEquals(InvoiceHistoryUnknown.FileStatus.UNKNOWN,
                invoices.recoverOriginal("c07-inv-never-seen").fileStatus());
        invoices.reread(new ApplicationFact("c07-inv-never-seen",
                com.chengyinhub.business.domain.UserInvoiceApplication.STATUS_ISSUED,
                "INV-9", "file://x", "OTHER"));
        assertEquals(InvoiceHistoryUnknown.FileStatus.PRESENT,
                invoices.recoverOriginal("c07-inv-never-seen").fileStatus());

        MerchantSupplyDryRun supply = new MerchantSupplyDryRun();
        Decision neverSupply = supply.recoverOriginal("c07-supply-never-seen");
        assertEquals(MerchantSupplyDryRun.Outcome.UNKNOWN, neverSupply.outcome());
        assertEquals("c07-supply-never-seen", neverSupply.originalRequestId());
        assertEquals(0, supply.holdCount("c07-supply-never-seen"));
        supply.evaluate(new SkuSupplyFact(8L, 3L, null, false, 60, 0, null, null,
                START, END, null, null, "c07-supply-never-seen"), NOW, 1);
        assertEquals(0, supply.holdCount("c07-supply-never-seen"));
        Decision accepted = supply.evaluate(new SkuSupplyFact(8L, 3L, 10L, false, 60, 0, null, null,
                START, END, null, null, "c07-supply-never-seen"), NOW, 1);
        assertEquals(MerchantSupplyDryRun.Outcome.ACCEPT, accepted.outcome());
        assertEquals(1, supply.holdCount("c07-supply-never-seen"));
        assertEquals(MerchantSupplyDryRun.Outcome.ACCEPT,
                supply.recoverOriginal("c07-supply-never-seen").outcome());

        FulfillmentEvidence evidence = new FulfillmentEvidence();
        assertEquals(FulfillmentEvidence.Outcome.UNKNOWN,
                evidence.recoverOriginal("c07-ev-never-seen").get(0).outcome());
        assertEquals(1, evidence.recoverOriginal("c07-ev-never-seen").size());
        List<FulfillmentEvidence.ChainStep> firstJourney = evidence.recordJourney(
                "c07-ev-never-seen",
                new FulfillmentEvidence.JourneyFacts(true, true, null, null, null));
        assertEquals(FulfillmentEvidence.Outcome.RECORDED, firstJourney.get(0).outcome());
        assertEquals(firstJourney.size(), evidence.recoverOriginal("c07-ev-never-seen").size());
        assertEquals("c07-ev-never-seen",
                evidence.recoverOriginal("c07-ev-never-seen").get(0).originalRequestId());

        TransferSoldPolicyGate transfer = new TransferSoldPolicyGate();
        TransferSoldPolicyGate.Decision neverTransfer = transfer.recoverOriginal("c07-xfer-never-seen");
        assertEquals(TransferSoldPolicyGate.Outcome.WAITING_SOURCE, neverTransfer.outcome());
        assertEquals("c07-xfer-never-seen", neverTransfer.originalRequestId());
        TransferSoldPolicyGate.Decision firstWrite = transfer.evaluate(
                new TransferFact("c07-xfer-never-seen", "", 88L, true, true));
        assertEquals(TransferSoldPolicyGate.Outcome.WAITING_SOURCE, firstWrite.outcome());
        assertEquals("c07-xfer-never-seen",
                transfer.recoverOriginal("c07-xfer-never-seen").originalRequestId());
        assertEquals(TransferSoldPolicyGate.Outcome.WAITING_SOURCE,
                transfer.recoverOriginal("c07-xfer-never-seen").outcome());
    }
}
