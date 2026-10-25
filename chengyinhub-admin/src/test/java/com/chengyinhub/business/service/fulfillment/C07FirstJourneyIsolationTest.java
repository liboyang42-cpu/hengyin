package com.chengyinhub.business.service.fulfillment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.UserInvoiceApplication;
import com.chengyinhub.business.service.fulfillment.EntitlementHoldProjection.HoldFact;
import com.chengyinhub.business.service.fulfillment.FulfillmentEvidence.AftercareFact;
import com.chengyinhub.business.service.fulfillment.FulfillmentEvidence.BookingFact;
import com.chengyinhub.business.service.fulfillment.FulfillmentEvidence.ChainStep;
import com.chengyinhub.business.service.fulfillment.FulfillmentEvidence.JourneyFacts;
import com.chengyinhub.business.service.fulfillment.FulfillmentEvidence.RefundEstimateFact;
import com.chengyinhub.business.service.fulfillment.FulfillmentEvidence.Stage;
import com.chengyinhub.business.service.fulfillment.FourLedgerBillReread.BillFact;
import com.chengyinhub.business.service.fulfillment.FourLedgerBillReread.Dimension;
import com.chengyinhub.business.service.fulfillment.InvoiceHistoryUnknown.ApplicationFact;
import com.chengyinhub.business.service.fulfillment.InvoiceHistoryUnknown.FileStatus;
import com.chengyinhub.business.service.fulfillment.MerchantSupplyDryRun.SkuSupplyFact;
import com.chengyinhub.business.service.fulfillment.TransferSoldPolicyGate.TransferFact;
import com.chengyinhub.business.service.invoice.ManualInvoiceIssuer;
import com.chengyinhub.common.constant.PurchaseKind;

/**
 * C07 first runnable journey (isolation): dry-run supply → hold entitlement
 * with the original requestId → reread four-ledger bill for that id; missing
 * invoice file is UNKNOWN, not a new apply. Transfer-sold originals stay
 * WAITING_SOURCE and SMS identity is not the policy.
 */
class C07FirstJourneyIsolationTest {

    private static final String REQUEST_ID = "c07-journey-req";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Instant NOON = LocalDateTime.of(2026, 10, 23, 12, 0)
            .atZone(SHANGHAI).toInstant();
    private static final Instant VALID_START = LocalDateTime.of(2026, 10, 1, 0, 0)
            .atZone(SHANGHAI).toInstant();
    private static final Instant VALID_END = LocalDateTime.of(2026, 11, 1, 0, 0)
            .atZone(SHANGHAI).toInstant();

    @Test
    void dryRunThenHoldThenFourLedgerRereadKeepsOriginalRequestId() {
        MerchantSupplyDryRun.Decision supply = new MerchantSupplyDryRun().evaluate(
                new SkuSupplyFact(8L, 3L, 10L, true, 60, 4, 4, 1,
                        VALID_START, VALID_END, 10 * 60, 21 * 60, REQUEST_ID),
                NOON, 1);
        assertEquals(MerchantSupplyDryRun.Outcome.ACCEPT, supply.outcome());
        assertEquals(REQUEST_ID, supply.originalRequestId());
        assertFalse(supply.writesStock());
        assertFalse(supply.writesReservation());

        HoldFact holdFact = new HoldFact(REQUEST_ID, 78L, 124L, 3L,
                EntitlementHoldProjection.STATUS_HELD, VALID_END, "");
        EntitlementHoldProjection.HoldView hold =
                new EntitlementHoldProjection().project(holdFact, NOON);
        assertEquals(EntitlementHoldProjection.Outcome.HELD, hold.outcome());
        assertTrue(hold.occupiesCapacity());
        assertEquals(REQUEST_ID, hold.originalRequestId());
        assertFalse(hold.executedReserve());
        assertFalse(hold.executedRedeem());
        assertFalse(hold.executedAward());

        FourLedgerBillReread ledger = new FourLedgerBillReread();
        FourLedgerBillReread.View bills = ledger.reread(REQUEST_ID, List.of(
                new BillFact(REQUEST_ID, Dimension.WECHAT_TRADE,
                        new BigDecimal("69.00"), BigDecimal.ZERO, false, ""),
                new BillFact(REQUEST_ID, Dimension.REGISTRATION_ORDER,
                        new BigDecimal("69.00"), new BigDecimal("69.00"), true, "reg-hash"),
                new BillFact(REQUEST_ID, Dimension.MEMBER_BALANCE,
                        BigDecimal.ZERO, BigDecimal.ZERO, true, "bal-hash"),
                new BillFact(REQUEST_ID, Dimension.MERCHANT_PAYABLE,
                        new BigDecimal("12.50"), new BigDecimal("12.50"), true, "pay-hash")));
        assertEquals(REQUEST_ID, bills.originalRequestId());
        assertEquals(FourLedgerBillReread.Outcome.UNKNOWN, bills.outcome(Dimension.WECHAT_TRADE));
        assertEquals(FourLedgerBillReread.Outcome.MATCHED, bills.outcome(Dimension.REGISTRATION_ORDER));
        assertFalse(bills.startedNewReconcile());
        assertTrue(ledger.originalUnknownNotReplacedByProjectionSuccess(bills, true));
        assertEquals(bills.outcome(Dimension.WECHAT_TRADE),
                ledger.requery(REQUEST_ID, List.of(
                        new BillFact(REQUEST_ID, Dimension.WECHAT_TRADE,
                                new BigDecimal("69.00"), BigDecimal.ZERO, false, "")))
                        .outcome(Dimension.WECHAT_TRADE));

        InvoiceHistoryUnknown.View invoice = new InvoiceHistoryUnknown().reread(
                new ApplicationFact(REQUEST_ID, UserInvoiceApplication.STATUS_SUBMITTED,
                        "", "", ManualInvoiceIssuer.NAME));
        assertEquals(FileStatus.UNKNOWN, invoice.fileStatus());
        assertFalse(invoice.startedNewApply());
        assertEquals(REQUEST_ID, invoice.originalRequestId());

        TransferSoldPolicyGate.Decision transfer = new TransferSoldPolicyGate().evaluate(
                new TransferFact(REQUEST_ID, "", 88L, true, true));
        assertEquals(TransferSoldPolicyGate.Outcome.WAITING_SOURCE, transfer.outcome());
        assertEquals(REQUEST_ID, transfer.originalRequestId());
        assertFalse(transfer.grantsTransfer());
        assertFalse(transfer.usedSmsIdentityAsPolicy());

        Date now = Date.from(NOON);
        Date deadline = Date.from(VALID_END);
        List<ChainStep> steps = new FulfillmentEvidence().recordJourney(REQUEST_ID,
                new JourneyFacts(true, hold.occupiesCapacity(),
                        new BookingFact("slot-noon", "slot-dusk", true),
                        new RefundEstimateFact(1L, 2, 0, 2, null, deadline, null,
                                PurchaseKind.EXPLORE_PASS, deadline, now),
                        new AftercareFact(true, false)));
        assertTrue(steps.stream().allMatch(step -> REQUEST_ID.equals(step.originalRequestId())));
        assertTrue(steps.stream().noneMatch(ChainStep::executedWriter));
        assertEquals(FulfillmentEvidence.Outcome.RECORDED,
                steps.stream().filter(step -> step.stage() == Stage.RESCHEDULE).findFirst()
                        .orElseThrow().outcome());
        assertEquals(FulfillmentEvidence.Outcome.RECORDED,
                steps.stream().filter(step -> step.stage() == Stage.REFUND_ESTIMATE).findFirst()
                        .orElseThrow().outcome());
    }
}
