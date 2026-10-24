package com.chengyinhub.business.service.workshop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class WorkshopModuleImportGateTest {

    private static final String HASH = "a".repeat(64);

    @Test
    void missingOriginalRequestIdStaysNullUnknown() {
        WorkshopModuleImportGate gate = new WorkshopModuleImportGate();
        WorkshopModuleImportGate.Decision decision = gate.importPreview(new WorkshopModuleImportGate.Manifest(
                null, "mod-1", "1.0.0", HASH, 1, "catalog", Map.of("title", "demo")));
        assertEquals(WorkshopModuleImportGate.Outcome.UNKNOWN, decision.outcome());
        assertNull(decision.originalRequestId());
        assertFalse(decision.executedJs());
        assertFalse(decision.wroteRuntime());
        assertFalse(decision.grantsImport());
    }

    @Test
    void qmodOriginalMissingIsWaitingSourceNotSuccess() {
        assertFalse(WorkshopModuleImportGate.QMOD_ORIGINAL_PRESENT);
        WorkshopModuleImportGate gate = new WorkshopModuleImportGate();
        WorkshopModuleImportGate.Decision decision = gate.importPreview(new WorkshopModuleImportGate.Manifest(
                "c10-import-1", "mod-1", "1.0.0", HASH, 1, "qmod", Map.of("title", "demo")));
        assertEquals(WorkshopModuleImportGate.Outcome.WAITING_SOURCE, decision.outcome());
        assertEquals("c10-import-1", decision.originalRequestId());
        assertFalse(decision.grantsImport());
        assertFalse(decision.executedJs());
    }

    @Test
    void duplicateOriginalRequestIdDoesNotSecondEffect() {
        WorkshopModuleImportGate gate = new WorkshopModuleImportGate();
        WorkshopModuleImportGate.Manifest manifest = new WorkshopModuleImportGate.Manifest(
                "c10-import-dup", "mod-1", "1.0.0", HASH, 1, "qmod", Map.of("title", "demo"));
        WorkshopModuleImportGate.Decision first = gate.importPreview(manifest);
        WorkshopModuleImportGate.Decision second = gate.importPreview(manifest);
        assertEquals(first.outcome(), second.outcome());
        assertEquals("c10-import-dup", second.originalRequestId());
        assertEquals(WorkshopOriginalRequestBinder.WAITING_SOURCE,
                gate.binder().existing("c10-import-dup").status());
        assertFalse(Boolean.TRUE.equals(gate.binder().existing("c10-import-dup").result().get("secondEffect")));
    }

    @Test
    void answersInCatalogAreRejectedBeforeRetain() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkshopModuleImportGate.inspect(Map.of("answer", "42")));
        WorkshopModuleImportGate gate = new WorkshopModuleImportGate();
        WorkshopModuleImportGate.Decision decision = gate.importPreview(new WorkshopModuleImportGate.Manifest(
                "c10-import-answer", "mod-1", "1.0.0", HASH, 1, "catalog",
                Map.of("answer", "hidden")));
        assertEquals(WorkshopModuleImportGate.Outcome.REJECTED, decision.outcome());
        assertEquals("c10-import-answer", decision.originalRequestId());
        assertFalse(decision.wroteRuntime());
    }

    @Test
    void recoverKeepsOriginalIntent() {
        WorkshopModuleImportGate gate = new WorkshopModuleImportGate();
        gate.importPreview(new WorkshopModuleImportGate.Manifest(
                "c10-import-recover", "mod-1", "1.0.0", HASH, 1, "qmod", Map.of("title", "demo")));
        WorkshopModuleImportGate.Decision recovered = gate.recover("c10-import-recover");
        assertEquals("c10-import-recover", recovered.originalRequestId());
        assertEquals(WorkshopModuleImportGate.Outcome.WAITING_SOURCE, recovered.outcome());
        assertTrue(gate.binder().recover("c10-import-recover").get("clearedOriginalIntent") == Boolean.FALSE);
        assertTrue(gate.binder().recover("c10-import-recover").get("mintedNewRequestId") == Boolean.FALSE);
    }

    @Test
    void recoverOfNeverSeenIdDoesNotOccupyThenUniqueImportApplies() {
        WorkshopOriginalRequestBinder binder = new WorkshopOriginalRequestBinder();
        WorkshopModuleImportGate gate = new WorkshopModuleImportGate(binder);
        WorkshopModuleImportGate.Decision blank = gate.recover("  ");
        assertNull(blank.originalRequestId());
        assertEquals(WorkshopModuleImportGate.Outcome.UNKNOWN, blank.outcome());
        assertNull(binder.existing("  "));
        WorkshopModuleImportGate.Decision recovered = gate.recover("c10-import-never-c10");
        assertEquals("c10-import-never-c10", recovered.originalRequestId());
        assertEquals(WorkshopModuleImportGate.Outcome.UNKNOWN, recovered.outcome());
        assertFalse(recovered.grantsImport());
        assertNull(binder.existing("c10-import-never-c10"));
        WorkshopModuleImportGate.Decision first = gate.importPreview(new WorkshopModuleImportGate.Manifest(
                "c10-import-never-c10", "mod-1", "1.0.0", HASH, 1, "qmod", Map.of("title", "demo")));
        assertEquals(WorkshopModuleImportGate.Outcome.WAITING_SOURCE, first.outcome());
        assertEquals("c10-import-never-c10", first.originalRequestId());
        assertEquals(WorkshopOriginalRequestBinder.WAITING_SOURCE, binder.existing("c10-import-never-c10").status());
        WorkshopModuleImportGate.Decision replay = gate.importPreview(new WorkshopModuleImportGate.Manifest(
                "c10-import-never-c10", "mod-1", "1.0.0", HASH, 1, "qmod", Map.of("title", "demo")));
        assertEquals(WorkshopModuleImportGate.Outcome.WAITING_SOURCE, replay.outcome());
        assertEquals(WorkshopOriginalRequestBinder.WAITING_SOURCE, binder.existing("c10-import-never-c10").status());
    }
}
