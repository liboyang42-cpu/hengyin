package com.chengyinhub.business.service.workshop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorkshopInstallUpgradeRollbackTest {

    @Test
    void installWithoutQmodOriginalIsWaitingSourceNotSuccess() {
        WorkshopInstallUpgradeRollback machine = new WorkshopInstallUpgradeRollback();
        WorkshopInstallUpgradeRollback.Snapshot snap = machine.install("c10-ins-1", "1.0.0");
        assertEquals(WorkshopInstallUpgradeRollback.Phase.WAITING_SOURCE, snap.phase());
        assertEquals("c10-ins-1", snap.originalRequestId());
        assertFalse(snap.secondEffect());
        WorkshopInstallUpgradeRollback.Snapshot replay = machine.install("c10-ins-1", "1.0.0");
        assertEquals("c10-ins-1", replay.originalRequestId());
        assertEquals(WorkshopInstallUpgradeRollback.Phase.WAITING_SOURCE, replay.phase());
        assertTrue(replay.secondEffect()
                || "WAITING_SOURCE:qmod.original".equals(replay.reason()));
        assertEquals("c10-ins-1", machine.recover("c10-ins-1").originalRequestId());
    }

    @Test
    void rollbackWithoutRetainedPriorFailsInsteadOfInventing() {
        WorkshopInstallUpgradeRollback machine = new WorkshopInstallUpgradeRollback();
        WorkshopInstallUpgradeRollback.Snapshot snap = machine.rollback("c10-rb-1");
        assertEquals(WorkshopInstallUpgradeRollback.Phase.UNKNOWN, snap.phase());
        assertEquals("rollback without retained prior", snap.reason());
        assertNull(snap.retainedPriorVersion());
        assertEquals("c10-rb-1", snap.originalRequestId());
    }

    @Test
    void missingRequestIdStaysNull() {
        WorkshopInstallUpgradeRollback.Snapshot snap = new WorkshopInstallUpgradeRollback().install(null, "1.0.0");
        assertEquals(WorkshopInstallUpgradeRollback.Phase.UNKNOWN, snap.phase());
        assertNull(snap.originalRequestId());
    }

    @Test
    void recoverOfNeverSeenIdDoesNotOccupyThenUniqueInstallApplies() {
        WorkshopOriginalRequestBinder binder = new WorkshopOriginalRequestBinder();
        WorkshopInstallUpgradeRollback machine = new WorkshopInstallUpgradeRollback(binder);
        WorkshopInstallUpgradeRollback.Snapshot blank = machine.recover("  ");
        assertNull(blank.originalRequestId());
        assertEquals(WorkshopInstallUpgradeRollback.Phase.UNKNOWN, blank.phase());
        assertNull(binder.existing("  "));
        WorkshopInstallUpgradeRollback.Snapshot recovered = machine.recover("c10-ins-never-c10");
        assertEquals("c10-ins-never-c10", recovered.originalRequestId());
        assertEquals(WorkshopInstallUpgradeRollback.Phase.UNKNOWN, recovered.phase());
        assertNull(binder.existing("c10-ins-never-c10"));
        WorkshopInstallUpgradeRollback.Snapshot first = machine.install("c10-ins-never-c10", "1.0.0");
        assertEquals("c10-ins-never-c10", first.originalRequestId());
        assertEquals(WorkshopInstallUpgradeRollback.Phase.WAITING_SOURCE, first.phase());
        assertFalse(first.secondEffect());
        assertEquals(WorkshopOriginalRequestBinder.WAITING_SOURCE, binder.existing("c10-ins-never-c10").status());
        WorkshopInstallUpgradeRollback.Snapshot replay = machine.install("c10-ins-never-c10", "1.0.0");
        assertEquals("c10-ins-never-c10", replay.originalRequestId());
        assertEquals(WorkshopInstallUpgradeRollback.Phase.WAITING_SOURCE, replay.phase());
        assertEquals(WorkshopOriginalRequestBinder.WAITING_SOURCE, binder.existing("c10-ins-never-c10").status());
    }
}
