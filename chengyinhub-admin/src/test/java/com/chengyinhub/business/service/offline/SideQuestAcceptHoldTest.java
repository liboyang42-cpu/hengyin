package com.chengyinhub.business.service.offline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SideQuestAcceptHoldTest {

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueHoldApplies() {
        SideQuestAcceptHold quest = new SideQuestAcceptHold();
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
}
