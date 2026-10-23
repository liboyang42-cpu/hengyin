package com.chengyinhub.business.service.workshop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class H5SessionCapabilityGateTest {

    @Test
    void missingSdkOriginalIsWaitingSourceNotArbitraryOpen() {
        assertFalse(H5SessionCapabilityGate.H5_SDK_ORIGINAL_PRESENT);
        H5SessionCapabilityGate gate = new H5SessionCapabilityGate();
        H5SessionCapabilityGate.Decision decision = gate.evaluate(new H5SessionCapabilityGate.SessionFact(
                "c10-h5-1",
                "https://evil.example/page",
                "1",
                "1",
                List.of("https://allowed.example/"),
                Set.of("preview"),
                Set.of("preview")));
        assertEquals(H5SessionCapabilityGate.Outcome.WAITING_SOURCE, decision.outcome());
        assertEquals("c10-h5-1", decision.originalRequestId());
        assertFalse(decision.openedArbitraryH5());
        assertFalse(decision.inventedSdkMethod());
        assertFalse(decision.grantsSession());
    }

    @Test
    void missingRequestIdStaysNull() {
        H5SessionCapabilityGate.Decision decision = new H5SessionCapabilityGate().evaluate(
                new H5SessionCapabilityGate.SessionFact(null, "https://x.example/", "1", "1",
                        List.of(), Set.of(), Set.of()));
        assertEquals(H5SessionCapabilityGate.Outcome.UNKNOWN, decision.outcome());
        assertNull(decision.originalRequestId());
    }

    @Test
    void httpsAllowlistHelperRejectsNonHttpsAndUnknownHost() {
        assertFalse(H5SessionCapabilityGate.urlOnAllowlist("http://allowed.example/", List.of("https://allowed.example/")));
        assertFalse(H5SessionCapabilityGate.urlOnAllowlist("https://evil.example/", List.of("https://allowed.example/")));
        assertFalse(H5SessionCapabilityGate.urlOnAllowlist("https://allowed.example/", List.of()));
    }
}
