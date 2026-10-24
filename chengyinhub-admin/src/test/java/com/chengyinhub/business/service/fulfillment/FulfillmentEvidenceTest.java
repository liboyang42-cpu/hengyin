package com.chengyinhub.business.service.fulfillment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.fulfillment.FulfillmentEvidence.AftercareFact;
import com.chengyinhub.business.service.fulfillment.FulfillmentEvidence.BookingFact;
import com.chengyinhub.business.service.fulfillment.FulfillmentEvidence.ChainStep;
import com.chengyinhub.business.service.fulfillment.FulfillmentEvidence.JourneyFacts;
import com.chengyinhub.business.service.fulfillment.FulfillmentEvidence.Outcome;
import com.chengyinhub.business.service.fulfillment.FulfillmentEvidence.RefundEstimateFact;
import com.chengyinhub.business.service.fulfillment.FulfillmentEvidence.Stage;
import com.chengyinhub.common.constant.PurchaseKind;

class FulfillmentEvidenceTest {

    private final FulfillmentEvidence evidence = new FulfillmentEvidence();

    @Test
    void rescheduleKeepsOriginalRequestIdAndRedeemDoesNotCallWriter() {
        Date now = new Date(1_000L);
        Date deadline = new Date(2_000L);
        JourneyFacts facts = new JourneyFacts(true, true,
                new BookingFact("slot-a", "slot-b", true),
                new RefundEstimateFact(1L, 2, 0, 2, null, deadline, null,
                        PurchaseKind.EXPLORE_PASS, deadline, now),
                new AftercareFact(true, false));
        List<ChainStep> steps = evidence.recordJourney("c07-ev-1", facts);
        assertEquals(7, steps.size());
        assertTrue(steps.stream().allMatch(step -> "c07-ev-1".equals(step.originalRequestId())));
        assertTrue(steps.stream().noneMatch(ChainStep::executedWriter));
        ChainStep reschedule = step(steps, Stage.RESCHEDULE);
        assertEquals(Outcome.RECORDED, reschedule.outcome());
        assertTrue(reschedule.detail().contains("slot-b"));
        ChainStep redeem = step(steps, Stage.IN_STORE_REDEEM);
        assertEquals(Outcome.RECORDED, redeem.outcome());
        assertFalse(redeem.executedWriter());
        ChainStep refund = step(steps, Stage.REFUND_ESTIMATE);
        assertEquals(Outcome.RECORDED, refund.outcome());
        assertTrue(refund.detail().contains("可全额退款"));
        assertEquals(step(steps, Stage.HOLD).outcome(),
                step(evidence.requery("c07-ev-1", facts), Stage.HOLD).outcome());
    }

    @Test
    void missingBookingIsUnknownNotANewRequest() {
        JourneyFacts facts = new JourneyFacts(true, true, new BookingFact("", "", false),
                null, new AftercareFact(false, false));
        List<ChainStep> steps = evidence.recordJourney("c07-ev-2", facts);
        assertEquals(Outcome.UNKNOWN, step(steps, Stage.BOOKING).outcome());
        assertEquals("c07-ev-2", step(steps, Stage.BOOKING).originalRequestId());
        assertEquals(Outcome.UNKNOWN, step(steps, Stage.REFUND_ESTIMATE).outcome());
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueRecordedApplies() {
        FulfillmentEvidence ledger = new FulfillmentEvidence();
        assertEquals(Outcome.UNKNOWN, ledger.recoverOriginal("c07-ev-never-seen").get(0).outcome());
        assertEquals(1, ledger.recoverOriginal("c07-ev-never-seen").size());
        List<ChainStep> firstJourney = ledger.recordJourney(
                "c07-ev-never-seen",
                new JourneyFacts(true, true, null, null, null));
        assertEquals(Outcome.RECORDED, firstJourney.get(0).outcome());
        assertEquals(firstJourney.size(), ledger.recoverOriginal("c07-ev-never-seen").size());
        assertEquals("c07-ev-never-seen",
                ledger.recoverOriginal("c07-ev-never-seen").get(0).originalRequestId());
    }

    private static ChainStep step(List<ChainStep> steps, Stage stage) {
        return steps.stream().filter(item -> item.stage() == stage).findFirst().orElseThrow();
    }
}
