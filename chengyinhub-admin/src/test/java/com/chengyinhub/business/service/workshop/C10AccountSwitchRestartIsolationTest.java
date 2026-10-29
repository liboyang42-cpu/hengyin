package com.chengyinhub.business.service.workshop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * C10 isolation: another account / install identity cannot rewrite the original
 * import {@code originalRequestId}; in-process restart recover keeps original
 * {@code WAITING_SOURCE}/{@code UNKNOWN}; omitted requestId stays Java
 * {@code null} (never {@code ""}); JS self-report is still rejected.
 */
class C10AccountSwitchRestartIsolationTest {

    private static final String OWNER_IMPORT_ID = "c10-acct-owner-import";
    private static final String OTHER_ACCOUNT_ID = "c10-acct-other-import";
    private static final String OTHER_INSTALL_ID = "c10-install-other";
    private static final String UNKNOWN_RECEIPT_ID = "c10-acct-unknown";
    private static final String JS_REPORT_ID = "c10-acct-js";
    private static final String CONTENT = "a".repeat(64);

    @Test
    void anotherAccountOrInstallIdentityCannotRewriteOriginalImportRequestId() {
        assertTrue(WorkshopModuleImportGate.QMOD_ORIGINAL_PRESENT == false);
        assertTrue(WorkshopModuleImportGate.H5_SDK_ORIGINAL_PRESENT == false);

        WorkshopOriginalRequestBinder ledger = new WorkshopOriginalRequestBinder();
        WorkshopModuleImportGate ownerImport = new WorkshopModuleImportGate(ledger);
        WorkshopModuleImportGate.Decision owner = ownerImport.importPreview(qmod(OWNER_IMPORT_ID, "mod-owner"));
        assertEquals(WorkshopModuleImportGate.Outcome.WAITING_SOURCE, owner.outcome());
        assertEquals(OWNER_IMPORT_ID, owner.originalRequestId());
        assertEquals(WorkshopOriginalRequestBinder.WAITING_SOURCE, ledger.existing(OWNER_IMPORT_ID).status());
        Map<String, Object> original = Map.copyOf(ledger.existing(OWNER_IMPORT_ID).result());

        WorkshopModuleImportGate otherAccount = new WorkshopModuleImportGate(ledger);
        WorkshopModuleImportGate.Decision steal = otherAccount.importPreview(qmod(OWNER_IMPORT_ID, "mod-stolen"));
        assertEquals(OWNER_IMPORT_ID, steal.originalRequestId());
        assertEquals(WorkshopModuleImportGate.Outcome.WAITING_SOURCE, steal.outcome());
        assertFalse(Boolean.TRUE.equals(ledger.existing(OWNER_IMPORT_ID).result().get("secondEffect")));
        assertEquals(original.get("status"), ledger.existing(OWNER_IMPORT_ID).result().get("status"));
        assertEquals(original.get("reason"), ledger.existing(OWNER_IMPORT_ID).result().get("reason"));
        assertEquals(original.get("outcome"), ledger.existing(OWNER_IMPORT_ID).result().get("outcome"));
        assertEquals(OWNER_IMPORT_ID, ledger.existing(OWNER_IMPORT_ID).requestId());

        WorkshopModuleImportGate.Decision independent = otherAccount.importPreview(qmod(OTHER_ACCOUNT_ID, "mod-other"));
        assertEquals(OTHER_ACCOUNT_ID, independent.originalRequestId());
        assertEquals(WorkshopModuleImportGate.Outcome.WAITING_SOURCE, independent.outcome());
        assertEquals(OWNER_IMPORT_ID, ownerImport.recover(OWNER_IMPORT_ID).originalRequestId());
        assertEquals(WorkshopModuleImportGate.Outcome.WAITING_SOURCE, ownerImport.recover(OWNER_IMPORT_ID).outcome());
        assertEquals(OWNER_IMPORT_ID, ledger.existing(OWNER_IMPORT_ID).requestId());
        assertEquals(OTHER_ACCOUNT_ID, ledger.existing(OTHER_ACCOUNT_ID).requestId());

        WorkshopInstallUpgradeRollback otherInstall = new WorkshopInstallUpgradeRollback(ledger);
        WorkshopInstallUpgradeRollback.Snapshot collide = otherInstall.install(OWNER_IMPORT_ID, "9.9.9");
        assertEquals(OWNER_IMPORT_ID, collide.originalRequestId());
        assertEquals(WorkshopInstallUpgradeRollback.Phase.WAITING_SOURCE, collide.phase());
        assertEquals(OWNER_IMPORT_ID, ledger.existing(OWNER_IMPORT_ID).requestId());
        assertEquals(WorkshopOriginalRequestBinder.WAITING_SOURCE, ledger.existing(OWNER_IMPORT_ID).status());

        WorkshopInstallUpgradeRollback.Snapshot otherHold = otherInstall.install(OTHER_INSTALL_ID, "9.9.9");
        assertEquals(OTHER_INSTALL_ID, otherHold.originalRequestId());
        assertEquals(WorkshopInstallUpgradeRollback.Phase.WAITING_SOURCE, otherHold.phase());
        assertEquals(OWNER_IMPORT_ID, ownerImport.recover(OWNER_IMPORT_ID).originalRequestId());
        assertFalse(Boolean.TRUE.equals(ledger.recover(OWNER_IMPORT_ID).get("clearedOriginalIntent")));
        assertFalse(Boolean.TRUE.equals(ledger.recover(OWNER_IMPORT_ID).get("mintedNewRequestId")));
    }

    @Test
    void inProcessRestartRecoversOriginalWaitingSourceAndUnknown() {
        WorkshopOriginalRequestBinder ledger = new WorkshopOriginalRequestBinder();
        WorkshopModuleImportGate importer = new WorkshopModuleImportGate(ledger);
        WorkshopRunServerVerification verify = new WorkshopRunServerVerification(ledger);

        WorkshopModuleImportGate.Decision waiting = importer.importPreview(qmod(OWNER_IMPORT_ID, "mod-owner"));
        assertEquals(WorkshopModuleImportGate.Outcome.WAITING_SOURCE, waiting.outcome());
        assertEquals(OWNER_IMPORT_ID, waiting.originalRequestId());

        WorkshopRunServerVerification.Decision unknown = verify.verifyServer(
                new WorkshopRunServerVerification.ServerReceipt(UNKNOWN_RECEIPT_ID, null, null));
        assertEquals(WorkshopRunServerVerification.Outcome.UNKNOWN, unknown.outcome());
        assertEquals(UNKNOWN_RECEIPT_ID, unknown.originalRequestId());

        WorkshopModuleImportGate restartedImport = new WorkshopModuleImportGate(ledger);
        WorkshopRunServerVerification restartedVerify = new WorkshopRunServerVerification(ledger);
        assertNotSame(importer, restartedImport);
        assertNotSame(verify, restartedVerify);

        WorkshopModuleImportGate.Decision recoveredWaiting = restartedImport.recover(OWNER_IMPORT_ID);
        assertEquals(OWNER_IMPORT_ID, recoveredWaiting.originalRequestId());
        assertEquals(WorkshopModuleImportGate.Outcome.WAITING_SOURCE, recoveredWaiting.outcome());
        assertEquals(WorkshopOriginalRequestBinder.WAITING_SOURCE, ledger.existing(OWNER_IMPORT_ID).status());
        Map<String, Object> waitingRecover = ledger.recover(OWNER_IMPORT_ID);
        assertEquals(OWNER_IMPORT_ID, waitingRecover.get("originalRequestId"));
        assertEquals(Boolean.FALSE, waitingRecover.get("mintedNewRequestId"));
        assertEquals(Boolean.FALSE, waitingRecover.get("clearedOriginalIntent"));

        WorkshopRunServerVerification.Decision recoveredUnknown = restartedVerify.recover(UNKNOWN_RECEIPT_ID);
        assertEquals(UNKNOWN_RECEIPT_ID, recoveredUnknown.originalRequestId());
        assertEquals(WorkshopRunServerVerification.Outcome.UNKNOWN, recoveredUnknown.outcome());
        assertFalse(recoveredUnknown.mintedNewRequestId());
        assertFalse(recoveredUnknown.projectionTakenAsSuccess());
        Map<String, Object> unknownRecover = ledger.recover(UNKNOWN_RECEIPT_ID);
        assertEquals(UNKNOWN_RECEIPT_ID, unknownRecover.get("originalRequestId"));
        assertEquals(WorkshopOriginalRequestBinder.UNKNOWN, unknownRecover.get("status"));
        assertEquals(Boolean.FALSE, unknownRecover.get("mintedNewRequestId"));
        assertEquals(Boolean.FALSE, unknownRecover.get("projectionTakenAsSuccess"));
    }

    @Test
    void missingRequestIdStaysJavaNullAndJsSelfReportRejected() {
        WorkshopModuleImportGate.Decision omitted = new WorkshopModuleImportGate().importPreview(
                qmod(null, "mod-omitted"));
        assertEquals(WorkshopModuleImportGate.Outcome.UNKNOWN, omitted.outcome());
        assertNull(omitted.originalRequestId());

        WorkshopModuleImportGate.Decision blank = new WorkshopModuleImportGate().importPreview(
                qmod("", "mod-blank"));
        assertEquals(WorkshopModuleImportGate.Outcome.UNKNOWN, blank.outcome());
        assertNull(blank.originalRequestId());

        WorkshopModuleImportGate.Decision recoveredMissing = new WorkshopModuleImportGate().recover(null);
        assertNull(recoveredMissing.originalRequestId());

        WorkshopOriginalRequestBinder ledger = new WorkshopOriginalRequestBinder();
        Map<String, Object> neverSeen = ledger.recover("c10-never-seen");
        assertEquals("c10-never-seen", neverSeen.get("originalRequestId"));
        assertEquals(WorkshopOriginalRequestBinder.UNKNOWN, neverSeen.get("status"));
        assertFalse(Boolean.TRUE.equals(neverSeen.get("mintedNewRequestId")));
        assertNull(ledger.existing("c10-never-seen"));
        WorkshopModuleImportGate later = new WorkshopModuleImportGate(ledger);
        WorkshopModuleImportGate.Decision firstWrite = later.importPreview(qmod("c10-never-seen", "mod-later"));
        assertEquals(WorkshopModuleImportGate.Outcome.WAITING_SOURCE, firstWrite.outcome());
        assertEquals("c10-never-seen", firstWrite.originalRequestId());
        assertEquals(WorkshopOriginalRequestBinder.WAITING_SOURCE, ledger.existing("c10-never-seen").status());
        assertNull(ledger.recover("").get("originalRequestId"));
        assertNull(ledger.existing(""));

        WorkshopRunServerVerification.Decision js = new WorkshopRunServerVerification()
                .rejectJsSelfReport(JS_REPORT_ID, true);
        assertEquals(WorkshopRunServerVerification.Outcome.REJECTED, js.outcome());
        assertEquals(JS_REPORT_ID, js.originalRequestId());
        assertFalse(js.acceptedJsSelfReport());
        assertFalse(js.mintedNewRequestId());
        assertFalse(js.projectionTakenAsSuccess());
    }

    @Test
    void markUnknownOfNeverSeenIdDoesNotOccupyLedgerThenImportApplies() {
        WorkshopOriginalRequestBinder ledger = new WorkshopOriginalRequestBinder();
        Map<String, Object> marked = ledger.markUnknown("c10-mark-never-seen", "server receipt UNKNOWN");
        assertEquals("c10-mark-never-seen", marked.get("originalRequestId"));
        assertEquals(WorkshopOriginalRequestBinder.UNKNOWN, marked.get("status"));
        assertFalse(Boolean.TRUE.equals(marked.get("mintedNewRequestId")));
        assertNull(ledger.existing("c10-mark-never-seen"));

        WorkshopRunServerVerification verify = new WorkshopRunServerVerification(ledger);
        WorkshopRunServerVerification.Decision recovered = verify.recoverUnknown("c10-mark-never-seen");
        assertEquals("c10-mark-never-seen", recovered.originalRequestId());
        assertEquals(WorkshopRunServerVerification.Outcome.UNKNOWN, recovered.outcome());
        assertFalse(recovered.mintedNewRequestId());
        assertNull(ledger.existing("c10-mark-never-seen"));

        WorkshopModuleImportGate later = new WorkshopModuleImportGate(ledger);
        WorkshopModuleImportGate.Decision firstWrite = later.importPreview(qmod("c10-mark-never-seen", "mod-later"));
        assertEquals(WorkshopModuleImportGate.Outcome.WAITING_SOURCE, firstWrite.outcome());
        assertEquals("c10-mark-never-seen", firstWrite.originalRequestId());
        assertEquals(WorkshopOriginalRequestBinder.WAITING_SOURCE, ledger.existing("c10-mark-never-seen").status());
        assertFalse(Boolean.TRUE.equals(ledger.existing("c10-mark-never-seen").result().get("replay")));
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyLedgerThenImportApplies() {
        WorkshopOriginalRequestBinder ledger = new WorkshopOriginalRequestBinder();
        WorkshopRunServerVerification verify = new WorkshopRunServerVerification(ledger);
        WorkshopRunServerVerification.Decision blank = verify.recoverOriginal(null);
        assertNull(blank.originalRequestId());
        assertEquals(WorkshopRunServerVerification.Outcome.UNKNOWN, blank.outcome());
        assertFalse(blank.mintedNewRequestId());
        assertNull(ledger.existing(null));

        WorkshopRunServerVerification.Decision never = verify.recoverOriginal("c10-recover-never-seen");
        assertEquals("c10-recover-never-seen", never.originalRequestId());
        assertEquals(WorkshopRunServerVerification.Outcome.UNKNOWN, never.outcome());
        assertFalse(never.mintedNewRequestId());
        assertNull(ledger.existing("c10-recover-never-seen"));

        WorkshopModuleImportGate later = new WorkshopModuleImportGate(ledger);
        WorkshopModuleImportGate.Decision firstWrite = later.importPreview(qmod("c10-recover-never-seen", "mod-later"));
        assertEquals(WorkshopModuleImportGate.Outcome.WAITING_SOURCE, firstWrite.outcome());
        assertEquals("c10-recover-never-seen", firstWrite.originalRequestId());
        assertEquals(WorkshopOriginalRequestBinder.WAITING_SOURCE, ledger.existing("c10-recover-never-seen").status());
        assertFalse(Boolean.TRUE.equals(ledger.existing("c10-recover-never-seen").result().get("replay")));
    }

    private static WorkshopModuleImportGate.Manifest qmod(String originalRequestId, String moduleId) {
        return new WorkshopModuleImportGate.Manifest(
                originalRequestId, moduleId, "1.0.0", CONTENT, 1, "qmod",
                Map.of("title", moduleId));
    }
}
