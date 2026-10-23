package com.chengyinhub.business.service.workshop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

class H5AnswerProtectionTest {

    @Test
    void answersInClientPackAreRejected() {
        H5AnswerProtection protection = new H5AnswerProtection();
        H5AnswerProtection.Decision decision = protection.evaluate(new H5AnswerProtection.Attempt(
                "c10-ans-pack", Map.of("answer", "42"), "42", "42", "v1", "v1", false, false));
        assertEquals(H5AnswerProtection.Outcome.REJECTED, decision.outcome());
        assertEquals("c10-ans-pack", decision.originalRequestId());
        assertFalse(decision.acceptedJsReport());
        assertFalse(decision.acceptedClientClock());
    }

    @Test
    void jsReportAndClientClockCannotSucceed() {
        H5AnswerProtection protection = new H5AnswerProtection();
        H5AnswerProtection.Decision js = protection.evaluate(new H5AnswerProtection.Attempt(
                "c10-ans-js", Map.of("title", "demo"), "ok", "ok", "v1", "v1", true, false));
        assertEquals(H5AnswerProtection.Outcome.REJECTED, js.outcome());
        assertFalse(js.acceptedJsReport());
        H5AnswerProtection clockGate = new H5AnswerProtection();
        H5AnswerProtection.Decision clock = clockGate.evaluate(new H5AnswerProtection.Attempt(
                "c10-ans-clock", Map.of("title", "demo"), "ok", "ok", "v1", "v1", false, true));
        assertEquals(H5AnswerProtection.Outcome.REJECTED, clock.outcome());
        assertFalse(clock.acceptedClientClock());
    }

    @Test
    void frozenAuthorVersionMatchDoesNotUseClientClock() {
        H5AnswerProtection protection = new H5AnswerProtection();
        H5AnswerProtection.Decision decision = protection.evaluate(new H5AnswerProtection.Attempt(
                "c10-ans-ok", Map.of("title", "demo"), "north-gate", "north-gate", "v1", "v1", false, false));
        assertEquals(H5AnswerProtection.Outcome.SERVER_MATCH, decision.outcome());
        assertEquals("c10-ans-ok", decision.originalRequestId());
        assertFalse(decision.acceptedJsReport());
        assertFalse(decision.acceptedClientClock());
    }

    @Test
    void missingRequestIdStaysNull() {
        H5AnswerProtection.Decision decision = new H5AnswerProtection().evaluate(new H5AnswerProtection.Attempt(
                null, Map.of(), "x", "x", "v1", "v1", false, false));
        assertEquals(H5AnswerProtection.Outcome.UNKNOWN, decision.outcome());
        assertNull(decision.originalRequestId());
    }
}
