package com.chengyinhub.business.service.offline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * C12 isolation: another operation/request cannot rewrite the original pack or
 * side-quest hold; in-process recover keeps the original record; missing
 * requestId stays Java {@code null}, never {@code ""}.
 */
class C05AccountSwitchRestartIsolationTest {

    private static final Instant NOW = OfflinePackFixtures.NOW;

    private static OfflinePackOperation.Manifest manifest(String operationId) {
        return OfflinePackFixtures.manifest(operationId);
    }

    @Test
    void otherOperationOrRequestDoesNotRewriteOriginal() {
        OfflinePackOperation pack = new OfflinePackOperation();
        pack.startDownload(manifest("op-1"), NOW);
        pack.pause();
        assertEquals(OfflinePackOperation.Failure.OPERATION_MISMATCH,
                assertThrows(OfflinePackOperation.OfflinePackException.class, () -> pack.resume("op-other")).code);
        assertEquals("op-1", pack.operationId());
        pack.resume("op-1");
        pack.finishDownload(OfflinePackFixtures.PAYLOAD);
        assertEquals("op-1", pack.operationId());
        assertEquals(OfflinePackOperation.Phase.READY, pack.phase());

        SideQuestAcceptHold quest = new SideQuestAcceptHold(new MemoryPlaceCapacity());
        quest.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, false));
        quest.acceptHold("hold-1", "sq-req-1", 1);
        quest.submitProof(SideQuestAcceptHold.Proof.create(SideQuestAcceptHold.ProofKind.QR, true, false, null));
        quest.complete();
        SideQuestAcceptHold.OriginalRecord original = quest.recoverOriginal("sq-req-1");
        assertEquals("sq-req-1", original.originalRequestId);
        assertEquals(SideQuestAcceptHold.Failure.STALE_HOLD,
                assertThrows(SideQuestAcceptHold.SideQuestException.class, () -> quest.recoverOriginal("sq-req-other"))
                        .code);
        assertEquals("sq-req-1", quest.original().originalRequestId);
        assertNotEquals("sq-req-other", quest.original().originalRequestId);
        assertEquals(SideQuestAcceptHold.Failure.ALREADY_TERMINAL,
                assertThrows(SideQuestAcceptHold.SideQuestException.class,
                        () -> quest.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, false))).code);
        assertEquals("sq-req-1", quest.original().originalRequestId);
    }

    @Test
    void inProcessRecoverKeepsOriginalPackAndQuest() {
        OfflinePackOperation pack = new OfflinePackOperation();
        pack.startDownload(manifest("op-1"), NOW);
        pack.pause();
        pack.resume("op-1");
        pack.finishDownload(OfflinePackFixtures.PAYLOAD);
        assertEquals("op-1", pack.operationId());
        assertEquals("v1", pack.manifest().version);

        SideQuestAcceptHold quest = new SideQuestAcceptHold(new MemoryPlaceCapacity());
        quest.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, false));
        quest.acceptHold("hold-1", "sq-req-1", 1);
        quest.submitProof(SideQuestAcceptHold.Proof.create(SideQuestAcceptHold.ProofKind.QR, true, false, null));
        quest.complete();
        SideQuestAcceptHold.OriginalRecord recovered = quest.recoverOriginal("sq-req-1");
        assertEquals("sq-req-1", recovered.originalRequestId);
        assertEquals(SideQuestAcceptHold.Terminal.COMPLETED, recovered.terminal);
        assertEquals(SideQuestAcceptHold.Phase.RECOVERED, quest.phase());
        assertEquals("sq-req-1", quest.recoverOriginal("sq-req-1").originalRequestId);
    }

    @Test
    void missingRequestIdStaysJavaNullNeverEmptyString() {
        OfflinePackOperation pack = new OfflinePackOperation();
        pack.startDownload(manifest("op-1"), NOW);
        pack.pause();
        assertEquals(OfflinePackOperation.Failure.OPERATION_MISMATCH,
                assertThrows(OfflinePackOperation.OfflinePackException.class, () -> pack.resume(null)).code);
        assertEquals(OfflinePackOperation.Failure.OPERATION_MISMATCH,
                assertThrows(OfflinePackOperation.OfflinePackException.class, () -> pack.resume("")).code);
        assertEquals("op-1", pack.operationId());
        assertNotEquals("", pack.operationId());

        SideQuestAcceptHold quest = new SideQuestAcceptHold(new MemoryPlaceCapacity());
        quest.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, false));
        assertEquals(SideQuestAcceptHold.Failure.CAPACITY_UNAVAILABLE,
                assertThrows(SideQuestAcceptHold.SideQuestException.class,
                        () -> quest.acceptHold("hold-1", null, 1)).code);
        assertEquals(SideQuestAcceptHold.Failure.CAPACITY_UNAVAILABLE,
                assertThrows(SideQuestAcceptHold.SideQuestException.class,
                        () -> quest.acceptHold("hold-1", "  ", 1)).code);
        assertNull(quest.original());
        assertNull(quest.hold());
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyLedgerThenUniqueHoldApplies() {
        SideQuestAcceptHold quest = new SideQuestAcceptHold(new MemoryPlaceCapacity());
        assertEquals(SideQuestAcceptHold.Failure.ORIGINAL_RECORD_UNKNOWN,
                assertThrows(SideQuestAcceptHold.SideQuestException.class, () -> quest.recoverOriginal("sq-orig-unk"))
                        .code);
        assertNull(quest.original());
        assertNull(quest.hold());
        assertEquals(SideQuestAcceptHold.Phase.IDLE, quest.phase());

        quest.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, false));
        assertEquals(SideQuestAcceptHold.Failure.ORIGINAL_RECORD_UNKNOWN,
                assertThrows(SideQuestAcceptHold.SideQuestException.class, () -> quest.recoverOriginal("sq-orig-unk"))
                        .code);
        assertNull(quest.original());
        assertNull(quest.hold());
        assertEquals(SideQuestAcceptHold.Phase.DISCOVERED, quest.phase());

        quest.acceptHold("hold-unk", "sq-orig-unk", 1);
        assertEquals("sq-orig-unk", quest.hold().originalRequestId);
        quest.submitProof(SideQuestAcceptHold.Proof.create(SideQuestAcceptHold.ProofKind.QR, true, false, null));
        quest.complete();
        SideQuestAcceptHold.OriginalRecord recovered = quest.recoverOriginal("sq-orig-unk");
        assertEquals("sq-orig-unk", recovered.originalRequestId);
        assertEquals(SideQuestAcceptHold.Terminal.COMPLETED, recovered.terminal);
        assertEquals(SideQuestAcceptHold.Phase.RECOVERED, quest.phase());
        assertEquals(SideQuestAcceptHold.Failure.ALREADY_TERMINAL,
                assertThrows(SideQuestAcceptHold.SideQuestException.class,
                        () -> quest.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, false))).code);
        assertEquals("sq-orig-unk", quest.original().originalRequestId);
    }
}
