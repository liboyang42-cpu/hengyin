package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** SOURCE only: actual default-disabled SDK guard, not proof of a global restored-app dispatch fence. */
class RestoreBusinessReconciliationFixtureTest {
    @Test void defaultDisabledProductionGatewayRejectsWriteAndCannotAuthorizeRecoveryRelease() {
        var observed = RestoreBusinessReconciliationFixture.disabledGatewayEvidence();
        assertEquals("SDK_DISABLED_ONLY", observed.get("state"));
        assertEquals(Boolean.TRUE, observed.get("createRejected"));
        assertEquals(Boolean.FALSE, observed.get("allDispatchDomainsFenced"));
        assertEquals("BLOCKED", observed.get("recoveryRelease"));
    }
}
