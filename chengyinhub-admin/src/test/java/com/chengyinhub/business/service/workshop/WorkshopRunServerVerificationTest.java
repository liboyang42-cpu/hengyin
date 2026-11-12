package com.chengyinhub.business.service.workshop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class WorkshopRunServerVerificationTest {

    @Test
    void jsSelfReportIsRejected() {
        WorkshopRunServerVerification verify = new WorkshopRunServerVerification();
        WorkshopRunServerVerification.Decision decision = verify.rejectJsSelfReport("c10-run-js", true);
        assertEquals(WorkshopRunServerVerification.Outcome.REJECTED, decision.outcome());
        assertEquals("c10-run-js", decision.originalRequestId());
        assertFalse(decision.acceptedJsSelfReport());
        assertFalse(decision.mintedNewRequestId());
        assertFalse(decision.projectionTakenAsSuccess());
    }

    @Test
    void unknownReceiptKeepsOriginalIntentOnRecover() {
        WorkshopRunServerVerification verify = new WorkshopRunServerVerification();
        WorkshopRunServerVerification.Decision unknown = verify.verifyServer(
                new WorkshopRunServerVerification.ServerReceipt("c10-run-unk", null, null));
        assertEquals(WorkshopRunServerVerification.Outcome.UNKNOWN, unknown.outcome());
        assertEquals("c10-run-unk", unknown.originalRequestId());
        WorkshopRunServerVerification.Decision recovered = verify.recover("c10-run-unk");
        assertEquals("c10-run-unk", recovered.originalRequestId());
        assertEquals(WorkshopRunServerVerification.Outcome.UNKNOWN, recovered.outcome());
        assertFalse(recovered.mintedNewRequestId());
        assertFalse(recovered.projectionTakenAsSuccess());
    }

    @Test
    void missingRequestIdStaysNull() {
        WorkshopRunServerVerification.Decision decision = new WorkshopRunServerVerification()
                .verifyServer(new WorkshopRunServerVerification.ServerReceipt(null, true, "deadbeef"));
        assertEquals(WorkshopRunServerVerification.Outcome.UNKNOWN, decision.outcome());
        assertNull(decision.originalRequestId());
    }

    @Test
    void recoverUnknownDoesNotMintNewId() {
        WorkshopRunServerVerification verify = new WorkshopRunServerVerification();
        verify.verifyServer(new WorkshopRunServerVerification.ServerReceipt("c10-run-keep", null, ""));
        WorkshopRunServerVerification.Decision marked = verify.recoverUnknown("c10-run-keep");
        assertEquals("c10-run-keep", marked.originalRequestId());
        assertEquals(WorkshopRunServerVerification.Outcome.UNKNOWN, marked.outcome());
        assertFalse(marked.mintedNewRequestId());
        assertFalse(marked.projectionTakenAsSuccess());
    }

    @Test
    void recoverOfNeverSeenIdDoesNotOccupyThenUniqueVerifyApplies() {
        WorkshopOriginalRequestBinder binder = new WorkshopOriginalRequestBinder();
        WorkshopRunServerVerification verify = new WorkshopRunServerVerification(binder);
        WorkshopRunServerVerification.Decision blank = verify.recover("  ");
        assertNull(blank.originalRequestId());
        assertEquals(WorkshopRunServerVerification.Outcome.UNKNOWN, blank.outcome());
        assertNull(binder.existing("  "));
        WorkshopRunServerVerification.Decision recovered = verify.recover("c10-run-never-c10");
        assertEquals("c10-run-never-c10", recovered.originalRequestId());
        assertEquals(WorkshopRunServerVerification.Outcome.UNKNOWN, recovered.outcome());
        assertFalse(recovered.mintedNewRequestId());
        assertNull(binder.existing("c10-run-never-c10"));
        WorkshopRunServerVerification.Decision first = verify.verifyServer(
                new WorkshopRunServerVerification.ServerReceipt(
                        "c10-run-never-c10", Boolean.TRUE, "deadbeefdeadbeefdeadbeefdeadbeef"));
        assertEquals(WorkshopRunServerVerification.Outcome.WAITING_SOURCE, first.outcome());
        assertEquals("c10-run-never-c10", first.originalRequestId());
        assertEquals(WorkshopOriginalRequestBinder.WAITING_SOURCE, binder.existing("c10-run-never-c10").status());
        WorkshopRunServerVerification.Decision replay = verify.verifyServer(
                new WorkshopRunServerVerification.ServerReceipt(
                        "c10-run-never-c10", Boolean.TRUE, "deadbeefdeadbeefdeadbeefdeadbeef"));
        assertEquals(WorkshopRunServerVerification.Outcome.WAITING_SOURCE, replay.outcome());
        assertEquals("c10-run-never-c10", replay.originalRequestId());
        assertEquals(WorkshopOriginalRequestBinder.WAITING_SOURCE, binder.existing("c10-run-never-c10").status());
    }
}
