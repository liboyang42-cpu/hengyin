package com.chengyinhub.business.service.workshop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * C10 first runnable journey (isolation): import catalog facts → version freeze
 * → authorized hold is not IAP → install/run against missing qmod originals
 * stay WAITING_SOURCE → JS self-report rejected → UNKNOWN recover keeps the
 * original requestId and does not mint a retry.
 */
class C10FirstJourneyIsolationTest {

    private static final String REQUEST_ID = "c10-journey-req";
    private static final String CONTENT = "e".repeat(64);
    private static final String TERMS = "f".repeat(64);

    @Test
    void importPublishInstallRunRecoverOriginal() {
        WorkshopOriginalRequestBinder shared = new WorkshopOriginalRequestBinder();
        WorkshopModuleImportGate importer = new WorkshopModuleImportGate(shared);
        WorkshopPackageVersionPublish publish = new WorkshopPackageVersionPublish(new WorkshopOriginalRequestBinder());
        WorkshopInstallUpgradeRollback install = new WorkshopInstallUpgradeRollback(new WorkshopOriginalRequestBinder());
        WorkshopRunServerVerification verify = new WorkshopRunServerVerification(new WorkshopOriginalRequestBinder());
        H5SessionCapabilityGate h5 = new H5SessionCapabilityGate(new WorkshopOriginalRequestBinder());
        H5AnswerProtection answers = new H5AnswerProtection(new WorkshopOriginalRequestBinder());

        WorkshopModuleImportGate.Decision preview = importer.importPreview(new WorkshopModuleImportGate.Manifest(
                REQUEST_ID, "mod-city-walk", "1.0.0", CONTENT, 1, "qmod",
                Map.of("title", "city-walk")));
        assertEquals(WorkshopModuleImportGate.Outcome.WAITING_SOURCE, preview.outcome());
        assertEquals(REQUEST_ID, preview.originalRequestId());
        assertFalse(preview.executedJs());
        assertFalse(preview.wroteRuntime());
        assertFalse(preview.grantsImport());

        WorkshopModuleImportGate.Decision replay = importer.importPreview(new WorkshopModuleImportGate.Manifest(
                REQUEST_ID, "mod-city-walk", "1.0.0", CONTENT, 1, "qmod",
                Map.of("title", "city-walk")));
        assertEquals(preview.outcome(), replay.outcome());
        assertEquals(REQUEST_ID, replay.originalRequestId());
        assertEquals(WorkshopOriginalRequestBinder.WAITING_SOURCE, shared.existing(REQUEST_ID).status());
        Map<String, Object> stored = shared.existing(REQUEST_ID).result();
        assertEquals(Boolean.FALSE, stored.get("secondEffect"));
        assertEquals(Boolean.FALSE, stored.get("clearedOriginalIntent"));
        Map<String, Object> recovered = shared.recover(REQUEST_ID);
        assertEquals(Boolean.FALSE, recovered.get("mintedNewRequestId"));
        assertEquals(REQUEST_ID, recovered.get("originalRequestId"));

        WorkshopPackageVersionPublish.Decision frozen = publish.freeze(new WorkshopPackageVersionPublish.FreezeFact(
                REQUEST_ID, "mod-city-walk", "1.0.0", CONTENT, TERMS));
        assertEquals(WorkshopPackageVersionPublish.Outcome.FROZEN, frozen.outcome());
        assertEquals(CONTENT, frozen.frozenContentHash());
        assertFalse(frozen.wrotePublicRegistry());

        WorkshopInstallUpgradeRollback.Snapshot held = install.install(REQUEST_ID, "1.0.0");
        assertEquals(WorkshopInstallUpgradeRollback.Phase.WAITING_SOURCE, held.phase());
        assertEquals(REQUEST_ID, held.originalRequestId());
        assertFalse(held.secondEffect());

        WorkshopRunServerVerification.Decision js = verify.rejectJsSelfReport(REQUEST_ID, true);
        assertEquals(WorkshopRunServerVerification.Outcome.REJECTED, js.outcome());
        assertFalse(js.acceptedJsSelfReport());

        WorkshopRunServerVerification recoverer = new WorkshopRunServerVerification();
        WorkshopRunServerVerification.Decision unknown = recoverer.verifyServer(
                new WorkshopRunServerVerification.ServerReceipt(REQUEST_ID, null, null));
        assertEquals(WorkshopRunServerVerification.Outcome.UNKNOWN, unknown.outcome());
        WorkshopRunServerVerification.Decision keep = recoverer.recover(REQUEST_ID);
        assertEquals(REQUEST_ID, keep.originalRequestId());
        assertEquals(WorkshopRunServerVerification.Outcome.UNKNOWN, keep.outcome());
        assertFalse(keep.mintedNewRequestId());
        assertFalse(keep.projectionTakenAsSuccess());

        H5SessionCapabilityGate.Decision session = h5.evaluate(new H5SessionCapabilityGate.SessionFact(
                REQUEST_ID, "https://allowed.example/mod", "1", "1",
                List.of("https://allowed.example/"), Set.of("preview"), Set.of("preview")));
        assertEquals(H5SessionCapabilityGate.Outcome.WAITING_SOURCE, session.outcome());
        assertFalse(session.openedArbitraryH5());

        H5AnswerProtection.Decision pack = answers.evaluate(new H5AnswerProtection.Attempt(
                REQUEST_ID + "-ans", Map.of("answer", "north"), "north", "north", "v1", "v1",
                false, false));
        assertEquals(H5AnswerProtection.Outcome.REJECTED, pack.outcome());

        WorkshopModuleImportGate.Decision omitted = new WorkshopModuleImportGate().importPreview(
                new WorkshopModuleImportGate.Manifest(null, "mod-city-walk", "1.0.0", CONTENT, 1,
                        "catalog", Map.of("title", "demo")));
        assertEquals(WorkshopModuleImportGate.Outcome.UNKNOWN, omitted.outcome());
        assertNull(omitted.originalRequestId());
        assertTrue(WorkshopModuleImportGate.QMOD_ORIGINAL_PRESENT == false);
    }
}
