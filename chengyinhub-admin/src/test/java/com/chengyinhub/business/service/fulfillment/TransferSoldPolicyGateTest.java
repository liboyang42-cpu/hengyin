package com.chengyinhub.business.service.fulfillment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.fulfillment.TransferSoldPolicyGate.Decision;
import com.chengyinhub.business.service.fulfillment.TransferSoldPolicyGate.Outcome;
import com.chengyinhub.business.service.fulfillment.TransferSoldPolicyGate.TransferFact;

class TransferSoldPolicyGateTest {

    private final TransferSoldPolicyGate gate = new TransferSoldPolicyGate();

    @Test
    void missingRemoteOriginalIsWaitingSourceAndKeepsRequestId() {
        TransferFact fact = new TransferFact("c07-xfer-1", "", 88L, true, false);
        Decision decision = gate.evaluate(fact);
        assertEquals(Outcome.WAITING_SOURCE, decision.outcome());
        assertEquals("c07-xfer-1", decision.originalRequestId());
        assertFalse(decision.grantsTransfer());
        assertFalse(TransferSoldPolicyGate.REMOTE_SOLD_POLICY_ORIGINAL_PRESENT);
        assertTrue(decision.reason().contains(TransferSoldPolicyGate.WAITING_SOURCE));
        assertEquals(decision.outcome(), gate.requery(fact).outcome());
    }

    @Test
    void smsIdentityIsNotSoldPolicyEvenWhenVerified() {
        TransferFact fact = new TransferFact("c07-xfer-2", "not-a-remote-hash", 88L, true, true);
        Decision decision = gate.evaluate(fact);
        assertEquals(Outcome.WAITING_SOURCE, decision.outcome());
        assertFalse(decision.usedSmsIdentityAsPolicy());
        assertFalse(decision.grantsTransfer());
        assertTrue(decision.reason().contains("SMS identity is not transfer-sold policy"));
    }

    @Test
    void designatedRecipientWithoutOriginalPolicyStillWaitingSource() {
        Decision decision = gate.evaluate(new TransferFact("c07-xfer-3", "", 99L, true, false));
        assertEquals(Outcome.WAITING_SOURCE, decision.outcome());
        assertEquals("c07-xfer-3", decision.originalRequestId());
        assertFalse(decision.grantsTransfer());
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueWaitingSourceApplies() {
        TransferSoldPolicyGate transfer = new TransferSoldPolicyGate();
        Decision neverTransfer = transfer.recoverOriginal("c07-xfer-never-seen");
        assertEquals(Outcome.WAITING_SOURCE, neverTransfer.outcome());
        assertEquals("c07-xfer-never-seen", neverTransfer.originalRequestId());
        assertTrue(neverTransfer.reason().contains("original intent unknown"));
        Decision firstWrite = transfer.evaluate(
                new TransferFact("c07-xfer-never-seen", "", 88L, true, true));
        assertEquals(Outcome.WAITING_SOURCE, firstWrite.outcome());
        assertEquals("c07-xfer-never-seen", firstWrite.originalRequestId());
        assertTrue(firstWrite.reason().contains("sold-policy")
                || firstWrite.reason().contains(TransferSoldPolicyGate.WAITING_SOURCE));
        assertEquals(firstWrite.reason(), transfer.recoverOriginal("c07-xfer-never-seen").reason());
        assertEquals(Outcome.WAITING_SOURCE, transfer.recoverOriginal("c07-xfer-never-seen").outcome());
    }
}
