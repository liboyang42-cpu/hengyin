package com.chengyinhub.business.service.offline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class C05FirstJourneyIsolationTest {

    private OfflinePackOperation.Manifest manifest(String version) {
        return OfflinePackFixtures.manifest("op-1", version);
    }

    @Test
    void testFirstJourneyDownloadPauseResumeSameOperationRecoverOriginal() {
        OfflinePackOperation pack = new OfflinePackOperation();
        Instant now = Instant.parse("2026-10-23T20:00:00Z");
        pack.startDownload(manifest("v1"), now);
        assertEquals(OfflinePackOperation.Phase.DOWNLOADING, pack.phase());
        pack.pause();
        pack.resume("op-1");
        OfflinePackOperation.OfflinePackException missingPayload = assertThrows(
                OfflinePackOperation.OfflinePackException.class, pack::finishDownload);
        assertEquals(OfflinePackOperation.Failure.WAITING_SOURCE, missingPayload.code);
        assertEquals(OfflinePackOperation.PACK_PAYLOAD_CONSUMER, missingPayload.capability);
        assertEquals(OfflinePackOperation.Phase.DOWNLOADING, pack.phase());
        pack.finishDownload(OfflinePackFixtures.PAYLOAD);
        assertEquals(OfflinePackOperation.Phase.READY, pack.phase());
        pack.applyUpdate(manifest("v2"), true, now);
        pack.recordCorrupt();
        assertEquals(OfflinePackOperation.Phase.READY, pack.phase());
        assertEquals("v1", pack.manifest().version);

        OfflinePackOperation.OfflinePackException answers = assertThrows(
                OfflinePackOperation.OfflinePackException.class,
                () -> OfflinePackOperation.inspect(Map.of("answer", "42", "packID", "pack-1")));
        assertEquals(OfflinePackOperation.Failure.ANSWERS_OR_HIDDEN_COORDINATES_FORBIDDEN, answers.code);

        OfflinePackOperation.OfflinePackException waiting = assertThrows(
                OfflinePackOperation.OfflinePackException.class, pack::commitOriginalWriter);
        assertEquals(OfflinePackOperation.Failure.WAITING_SOURCE, waiting.code);
        assertEquals(OfflinePackOperation.QMOD_FINAL_OPS, waiting.capability);
        assertFalse(OfflinePackOperation.qmodFinalOpsPresent());
        assertFalse(OfflinePackOperation.t41FullLoopProven());
        assertEquals(OfflinePackOperation.Failure.PAUSE_OPS_RESERVED,
                assertThrows(OfflinePackOperation.OfflinePackException.class, pack::hijackOriginalPauseOps).code);
        assertEquals(OfflinePackOperation.Failure.OPERATION_MISMATCH,
                assertThrows(OfflinePackOperation.OfflinePackException.class, () -> {
                    OfflinePackOperation other = new OfflinePackOperation();
                    other.startDownload(manifest("v1"), now);
                    other.pause();
                    other.resume("other-op");
                }).code);

        NavigationDestinationConsent nav = new NavigationDestinationConsent();
        NavigationDestinationConsent share = new NavigationDestinationConsent();
        nav.grant(NavigationDestinationConsent.Purpose.IN_APP_NAVIGATION_DESTINATION);
        share.grant(NavigationDestinationConsent.Purpose.LOCATION_SHARE);
        NavigationDestinationConsent.split(nav, share);
        nav.startInAppNavigation("shop-door");
        assertEquals(NavigationDestinationConsent.Failure.DESTINATION_IS_NOT_SHARE_PAYLOAD,
                assertThrows(NavigationDestinationConsent.NavigationConsentException.class,
                        () -> nav.sharePayloadFromDestination("shop-door")).code);
        assertEquals(NavigationDestinationConsent.Failure.LOCATION_SHARE_CONSENT_REQUIRED,
                assertThrows(NavigationDestinationConsent.NavigationConsentException.class,
                        () -> nav.publishLocationShare("shop-door")).code);

        PresenceProofDistinctFromFog.FogExplorationMemory fog =
                new PresenceProofDistinctFromFog.FogExplorationMemory(Set.of("tile:1"));
        assertEquals(PresenceProofDistinctFromFog.Failure.FOG_IS_NOT_PRESENCE,
                assertThrows(PresenceProofDistinctFromFog.PresenceProofException.class,
                        () -> PresenceProofDistinctFromFog.certifyPresence(fog, null)).code);
        assertEquals(PresenceProofDistinctFromFog.Failure.FAKE_GPS_REJECTED,
                assertThrows(PresenceProofDistinctFromFog.PresenceProofException.class,
                        PresenceProofDistinctFromFog::rejectSimulatedGps).code);
        assertNull(SideQuestAcceptHold.Proof.create(SideQuestAcceptHold.ProofKind.GPS, true, true, null));

        SideQuestAcceptHold quest = new SideQuestAcceptHold(new MemoryPlaceCapacity());
        quest.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, false));
        quest.acceptHold("hold-1", "sq-req-1", 1);
        quest.submitProof(SideQuestAcceptHold.Proof.create(SideQuestAcceptHold.ProofKind.QR, true, false, null));
        quest.complete();
        SideQuestAcceptHold.OriginalRecord recovered = quest.recoverOriginal("sq-req-1");
        assertEquals("sq-req-1", recovered.originalRequestId);
        assertEquals(SideQuestAcceptHold.Terminal.COMPLETED, recovered.terminal);
        assertEquals(SideQuestAcceptHold.Phase.RECOVERED, quest.phase());
        assertEquals(SideQuestAcceptHold.Failure.STALE_HOLD,
                assertThrows(SideQuestAcceptHold.SideQuestException.class, () -> quest.recoverOriginal("other"))
                        .code);
        assertEquals(SideQuestAcceptHold.Failure.WAITING_SOURCE,
                assertThrows(SideQuestAcceptHold.SideQuestException.class, quest::commitScoringWriter).code);
        assertNotNull(quest.waitingCapability());
    }

    @Test
    void hiddenCoordinateAndNonCompliantPlacesAreRejected() {
        SideQuestAcceptHold quest = new SideQuestAcceptHold();
        assertEquals(SideQuestAcceptHold.Failure.NOT_COMPLIANT,
                assertThrows(SideQuestAcceptHold.SideQuestException.class,
                        () -> quest.discover(new SideQuestAcceptHold.Place(8, 3, false, 4, false))).code);
        assertEquals(SideQuestAcceptHold.Failure.HIDDEN_COORDINATE_SHIPPED,
                assertThrows(SideQuestAcceptHold.SideQuestException.class,
                        () -> quest.discover(new SideQuestAcceptHold.Place(8, 3, true, 4, true))).code);
    }
}
