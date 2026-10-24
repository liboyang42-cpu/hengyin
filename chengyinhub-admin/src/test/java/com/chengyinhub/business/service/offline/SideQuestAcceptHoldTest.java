package com.chengyinhub.business.service.offline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SideQuestAcceptHoldTest {

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueHoldApplies() {
        SideQuestAcceptHold quest = new SideQuestAcceptHold(new MemoryPlaceCapacity());
        assertEquals(SideQuestAcceptHold.Failure.ORIGINAL_RECORD_UNKNOWN,
                assertThrows(SideQuestAcceptHold.SideQuestException.class, () -> quest.recoverOriginal("sq-class-unk"))
                        .code);
        assertNull(quest.original());
        assertNull(quest.hold());
        assertEquals(SideQuestAcceptHold.Phase.IDLE, quest.phase());

        quest.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, false));
        assertEquals(SideQuestAcceptHold.Failure.ORIGINAL_RECORD_UNKNOWN,
                assertThrows(SideQuestAcceptHold.SideQuestException.class, () -> quest.recoverOriginal("sq-class-unk"))
                        .code);
        assertNull(quest.original());
        assertNull(quest.hold());
        assertEquals(SideQuestAcceptHold.Phase.DISCOVERED, quest.phase());

        quest.acceptHold("hold-class", "sq-class-unk", 1);
        assertEquals("sq-class-unk", quest.hold().originalRequestId);
        quest.submitProof(SideQuestAcceptHold.Proof.create(SideQuestAcceptHold.ProofKind.QR, true, false, null));
        quest.complete();
        SideQuestAcceptHold.OriginalRecord recovered = quest.recoverOriginal("sq-class-unk");
        assertEquals("sq-class-unk", recovered.originalRequestId);
        assertEquals(SideQuestAcceptHold.Terminal.COMPLETED, recovered.terminal);
        assertEquals(SideQuestAcceptHold.Phase.RECOVERED, quest.phase());
        assertEquals(SideQuestAcceptHold.Failure.ALREADY_TERMINAL,
                assertThrows(SideQuestAcceptHold.SideQuestException.class,
                        () -> quest.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, false))).code);
        assertEquals("sq-class-unk", quest.original().originalRequestId);
        assertEquals(SideQuestAcceptHold.Failure.STALE_HOLD,
                assertThrows(SideQuestAcceptHold.SideQuestException.class, () -> quest.recoverOriginal("sq-class-other"))
                        .code);
        assertEquals("sq-class-unk", quest.original().originalRequestId);
    }

    @Test
    void missingCapacitySourceIsWaitingSourceThenUniqueReserveApplies() {
        SideQuestAcceptHold waiting = new SideQuestAcceptHold();
        waiting.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, false));
        SideQuestAcceptHold.SideQuestException missing = assertThrows(SideQuestAcceptHold.SideQuestException.class,
                () -> waiting.acceptHold("hold-wait", "sq-wait-1", 1));
        assertEquals(SideQuestAcceptHold.Failure.WAITING_SOURCE, missing.code);
        assertEquals(SideQuestAcceptHold.PLACE_CAPACITY_SOURCE, missing.capability);
        assertEquals(SideQuestAcceptHold.Phase.DISCOVERED, waiting.phase(), "缺容量源不得占 hold");
        assertNull(waiting.hold(), "缺容量源不得占 hold");
        SideQuestAcceptHold quest = new SideQuestAcceptHold(new MemoryPlaceCapacity());
        quest.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, false));
        quest.acceptHold("hold-wait", "sq-wait-1", 1);
        assertEquals(SideQuestAcceptHold.Phase.HOLD_ACCEPTED, quest.phase());
        assertEquals("sq-wait-1", quest.hold().originalRequestId);
    }

    @Test
    void unusedAcceptHoldBeforeDiscoverDoesNotOccupyThenUniqueHoldApplies() {
        SideQuestAcceptHold quest = new SideQuestAcceptHold(new MemoryPlaceCapacity());
        assertEquals(SideQuestAcceptHold.Failure.NOT_COMPLIANT,
                assertThrows(SideQuestAcceptHold.SideQuestException.class,
                        () -> quest.acceptHold("hold-idle", "sq-idle-unk", 1)).code);
        assertNull(quest.hold(), "未发现地点的 hold 观察不得占账");
        assertNull(quest.original());
        assertEquals(SideQuestAcceptHold.Phase.IDLE, quest.phase());
        quest.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, false));
        assertEquals(SideQuestAcceptHold.Phase.DISCOVERED, quest.phase());
        assertNull(quest.hold());
        quest.acceptHold("hold-idle", "sq-idle-unk", 1);
        assertEquals("sq-idle-unk", quest.hold().originalRequestId);
        assertEquals(SideQuestAcceptHold.Phase.HOLD_ACCEPTED, quest.phase());
        assertNull(quest.original());
    }

    @Test
    void unusedCompleteBeforeHoldDoesNotOccupyThenUniqueCompleteApplies() {
        SideQuestAcceptHold quest = new SideQuestAcceptHold(new MemoryPlaceCapacity());
        quest.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, false));
        assertEquals(SideQuestAcceptHold.Failure.HOLD_REQUIRED,
                assertThrows(SideQuestAcceptHold.SideQuestException.class, quest::complete).code);
        assertNull(quest.original(), "未预占完赛观察不得占原文");
        assertNull(quest.hold());
        assertEquals(SideQuestAcceptHold.Phase.DISCOVERED, quest.phase());
        quest.acceptHold("hold-comp", "sq-comp-unk", 1);
        quest.submitProof(SideQuestAcceptHold.Proof.create(SideQuestAcceptHold.ProofKind.QR, true, false, null));
        quest.complete();
        assertEquals(SideQuestAcceptHold.Phase.COMPLETED, quest.phase());
        assertEquals("sq-comp-unk", quest.original().originalRequestId);
        assertEquals(SideQuestAcceptHold.Terminal.COMPLETED, quest.original().terminal);
        assertEquals("sq-comp-unk", quest.recoverOriginal("sq-comp-unk").originalRequestId);
    }

    @Test
    void unusedSubmitProofBeforeHoldDoesNotOccupyThenUniqueSubmitApplies() {
        SideQuestAcceptHold quest = new SideQuestAcceptHold(new MemoryPlaceCapacity());
        quest.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, false));
        assertEquals(SideQuestAcceptHold.Failure.HOLD_REQUIRED,
                assertThrows(SideQuestAcceptHold.SideQuestException.class,
                        () -> quest.submitProof(SideQuestAcceptHold.Proof.create(
                                SideQuestAcceptHold.ProofKind.QR, true, false, null))).code);
        assertEquals(SideQuestAcceptHold.Phase.DISCOVERED, quest.phase(), "未见预占 submitProof 观察不得占 proof");
        assertNull(quest.hold());
        assertNull(quest.original());
        quest.acceptHold("hold-proof", "sq-proof-unk", 1);
        assertEquals(SideQuestAcceptHold.Phase.HOLD_ACCEPTED, quest.phase());
        assertNull(quest.original());
        quest.submitProof(SideQuestAcceptHold.Proof.create(SideQuestAcceptHold.ProofKind.QR, true, false, null));
        assertEquals(SideQuestAcceptHold.Phase.PROOF_PENDING, quest.phase());
        assertEquals("sq-proof-unk", quest.hold().originalRequestId);
        assertNull(quest.original());
    }

    @Test
    void unusedExitBeforeHoldDoesNotOccupyThenUniqueExitApplies() {
        SideQuestAcceptHold quest = new SideQuestAcceptHold(new MemoryPlaceCapacity());
        quest.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, false));
        assertEquals(SideQuestAcceptHold.Failure.HOLD_REQUIRED,
                assertThrows(SideQuestAcceptHold.SideQuestException.class, quest::exit).code);
        assertNull(quest.original(), "未见预占 exit 观察不得占原文");
        assertNull(quest.hold());
        assertEquals(SideQuestAcceptHold.Phase.DISCOVERED, quest.phase());
        quest.acceptHold("hold-exit", "sq-exit-unk", 1);
        quest.exit();
        assertEquals(SideQuestAcceptHold.Phase.EXITED, quest.phase());
        assertEquals("sq-exit-unk", quest.original().originalRequestId);
        assertEquals(SideQuestAcceptHold.Terminal.EXITED, quest.original().terminal);
    }
}

final class MemoryPlaceCapacity implements SideQuestAcceptHold.PlaceCapacitySource {
    @Override
    public boolean reserve(String originalRequestId, int placeId, int seats) {
        return originalRequestId != null && placeId > 0 && seats > 0;
    }

    @Override
    public void release(String originalRequestId) {
    }
}
