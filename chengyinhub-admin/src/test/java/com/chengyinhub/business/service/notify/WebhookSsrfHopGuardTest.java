package com.chengyinhub.business.service.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

class WebhookSsrfHopGuardTest {

    private final WebhookSsrfHopGuard guard = new WebhookSsrfHopGuard();

    @Test
    void publicHttpsHopIsAllowedWithoutDns() {
        WebhookSsrfHopGuard.HopVerdict verdict = guard.inspect(InMemoryNotifyFixtures.publicHop());
        assertTrue(verdict.allowed());
        assertEquals(WebhookSsrfHopGuard.Decision.ALLOW, verdict.decision);
    }

    @Test
    void httpSchemeAndUserinfoAndNon443AreRejected() {
        assertEquals(WebhookSsrfHopGuard.Decision.REJECT_SCHEME,
                guard.inspect(WebhookSsrfHopGuard.Hop.of("http://hooks.example.test/c09")).decision);
        assertEquals(WebhookSsrfHopGuard.Decision.REJECT_USERINFO,
                guard.inspect(WebhookSsrfHopGuard.Hop.of("https://user:pass@hooks.example.test/c09",
                        InMemoryNotifyFixtures.publicTestNet())).decision);
        assertEquals(WebhookSsrfHopGuard.Decision.REJECT_PORT,
                guard.inspect(WebhookSsrfHopGuard.Hop.of("https://hooks.example.test:8443/c09",
                        InMemoryNotifyFixtures.publicTestNet())).decision);
    }

    @Test
    void hostnameWithoutFixtureResolutionFailsClosed() {
        assertEquals(WebhookSsrfHopGuard.Decision.REJECT_UNRESOLVED,
                guard.inspect(WebhookSsrfHopGuard.Hop.of("https://hooks.example.test/c09")).decision);
    }

    @Test
    void literalLoopbackAndPrivateAndMetadataAreRejected() {
        assertEquals(WebhookSsrfHopGuard.Decision.REJECT_PRIVATE,
                guard.inspect(WebhookSsrfHopGuard.Hop.of("https://127.0.0.1/hook")).decision);
        assertEquals(WebhookSsrfHopGuard.Decision.REJECT_PRIVATE,
                guard.inspect(WebhookSsrfHopGuard.Hop.of("https://hooks.example.test/c09",
                        InMemoryNotifyFixtures.rfc1918())).decision);
        assertEquals(WebhookSsrfHopGuard.Decision.REJECT_PRIVATE,
                guard.inspect(WebhookSsrfHopGuard.Hop.of("https://hooks.example.test/c09",
                        InMemoryNotifyFixtures.metadata())).decision);
    }

    @Test
    void eachRedirectHopIsInspectedIndependently() {
        WebhookSsrfHopGuard.ChainVerdict allowed = guard.inspectChain(Collections.singletonList(
                InMemoryNotifyFixtures.publicHop()));
        assertTrue(allowed.allowed());
        WebhookSsrfHopGuard.ChainVerdict poisoned = guard.inspectChain(Arrays.asList(
                InMemoryNotifyFixtures.publicHop(),
                WebhookSsrfHopGuard.Hop.of("https://127.0.0.1/steal")));
        assertFalse(poisoned.allowed());
        assertEquals(WebhookSsrfHopGuard.Decision.REJECT_PRIVATE, poisoned.decision);
        assertThrows(SecurityException.class, () -> guard.assertSafeChain(poisoned.hops.isEmpty()
                ? Arrays.asList(InMemoryNotifyFixtures.publicHop(),
                WebhookSsrfHopGuard.Hop.of("https://10.0.0.1/x"))
                : Arrays.asList(InMemoryNotifyFixtures.publicHop(),
                WebhookSsrfHopGuard.Hop.of("https://10.0.0.1/x"))));
        assertEquals(WebhookSsrfHopGuard.Decision.REJECT_HOP_LIMIT, guard.inspectChain(Arrays.asList(
                InMemoryNotifyFixtures.publicHop(),
                InMemoryNotifyFixtures.publicHop(),
                InMemoryNotifyFixtures.publicHop(),
                InMemoryNotifyFixtures.publicHop())).decision);
    }

    @Test
    void recoverOriginalParsesRequestIdAndDoesNotOccupyThenPublicHopAllows() {
        WebhookSsrfHopGuard.ChainVerdict original = guard.recoverOriginal(
                "evt-E", Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertTrue(original.allowed());
        assertEquals(WebhookSsrfHopGuard.Decision.ALLOW, original.decision);
        WebhookSsrfHopGuard.ChainVerdict blank = guard.recoverOriginal(
                null, Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertTrue(blank.allowed());
        assertEquals(WebhookSsrfHopGuard.Decision.ALLOW, blank.decision);
        WebhookSsrfHopGuard.ChainVerdict empty = guard.recoverOriginal(
                "  ", Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertTrue(empty.allowed());
        assertEquals(WebhookSsrfHopGuard.Decision.ALLOW, empty.decision);
    }
}
