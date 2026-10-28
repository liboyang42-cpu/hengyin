package com.chengyinhub.business.it;

import com.chengyinhub.business.service.ai.AiProviderNotSentException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

/** Protocol/adapter controls only. Controlled replies here are not SQL/business acceptance evidence. */
@ResourceLock("t19-process-global-http-ai-map")
class CombinedWorkloadContractTest {
    @org.junit.jupiter.api.io.TempDir Path owner;

    @Test
    void sharedSeedRejectsForeignOrExistingIdentityPathsBeforeAnySql() throws Exception {
        assertThrows(IllegalStateException.class, () -> GameplayProcessRecoveryChild.seedCombination(null, null, owner.resolve("foreign.json")));
        Files.writeString(owner.resolve("identities.json.new"), "owned pending sentinel");
        assertThrows(IllegalStateException.class, () -> GameplayProcessRecoveryChild.seedCombination(null, null, owner.resolve("identities.json")));
        assertEquals("owned pending sentinel", Files.readString(owner.resolve("identities.json.new")));
        Files.delete(owner.resolve("identities.json.new"));
        Files.createSymbolicLink(owner.resolve("alias"), owner);
        assertThrows(IllegalStateException.class, () -> GameplayProcessRecoveryChild.seedCombination(null, null, owner.resolve("alias/identities.json")));
    }
    @Test @Timeout(15)
    void actualClientsParseControlledRecoveryResponses() throws Exception {
        try (var provider = new CombinedControlledProviders()) {
            assertTrue(provider.aiClient().chatVision("s", CrossDomainTransportStage.SMALL_IMAGE, "controlled")
                    .contains("\"pass\":true"));
            assertEquals("杭州市", provider.cities().resolveCity(120D, 30D));
            assertEquals(1, provider.ai.get()); assertEquals(1, provider.map.get());
        }
    }

    @Test @Timeout(20)
    void acceptedDisconnectAndBadJsonNeverBecomeNotSentOrSuccess() throws Exception {
        try (var provider = new CombinedControlledProviders()) {
            for (var mode : new CombinedControlledProviders.Mode[]{CombinedControlledProviders.Mode.DISCONNECT,
                    CombinedControlledProviders.Mode.BAD_RESPONSE}) {
                provider.mode(mode); provider.releaseFailure.countDown(); // this control tests response classification, not overlap
                int before = provider.ai.get();
                var error = assertThrows(RuntimeException.class, () -> provider.aiClient()
                        .chatVision("s", CrossDomainTransportStage.SMALL_IMAGE, "controlled"));
                assertFalse(error instanceof AiProviderNotSentException);
                assertEquals(before + 1, provider.ai.get(), "real POST must not retry");
                assertNull(provider.cities().resolveCity(121D, 31D));
            }
        }
    }

    @Test @Timeout(15)
    void fourHeldMapHttpRequestsDoNotAllowAnExtraPhysicalProvider() throws Exception {
        try (var provider = new CombinedControlledProviders()) {
            provider.mode(CombinedControlledProviders.Mode.HOLD_MAP);
            var clients = provider.cities(); ExecutorService callers = Executors.newFixedThreadPool(4);
            try {
                var calls = new java.util.ArrayList<Future<String>>();
                for (int i = 0; i < 4; i++) { int index = i; calls.add(callers.submit(() -> clients.resolveCity(120 + index / 1000D, 30D))); }
                assertTrue(provider.mapEntered.await(3, TimeUnit.SECONDS));
                assertEquals(4, provider.active.get());
                assertNull(provider.cities().resolveCity(122D, 32D)); assertEquals(4, provider.map.get());
                provider.releaseMap.countDown();
                for (var call : calls) assertEquals("杭州市", call.get(5, TimeUnit.SECONDS));
            } finally {
                provider.releaseMap.countDown(); callers.shutdownNow(); assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test @Timeout(10)
    void bodyCanaryAndGenericFiveHundredCannotBecomeVerifiedForeignRefusal() throws Exception {
        String canary = "CONTROLLED_RESPONSE_SECRET_CANARY";
        HttpServer source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1);
        ExecutorService worker = Executors.newSingleThreadExecutor(); source.setExecutor(worker);
        source.createContext(CombinedCoreHttpWorkload.COMMAND, exchange -> {
            try {
                exchange.getRequestBody().readNBytes(4096);
                byte[] body = ("{\"code\":500,\"msg\":\"" + canary + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        }); source.start();
        var identities = new ObjectMapper().readTree("{\"a\":{\"memberId\":1,\"token\":\"unit-double-a\"},"
                + "\"b\":{\"memberId\":2,\"token\":\"unit-double-b\"},\"CAPACITY_00\":{\"activityId\":3,"
                + "\"commandId\":\"controlled-original\",\"expectedChapterId\":4,\"targetChapterId\":5}}");
        try (var core = new CombinedCoreHttpWorkload(null, identities, source.getAddress().getPort())) {
            var observation = core.observe("CAPACITY_00", "foreign");
            assertEquals("UNKNOWN", observation.get("category"));
            assertEquals(0, core.verifiedResponses.get());
            assertFalse(new ObjectMapper().writeValueAsString(observation).contains(canary));
            assertEquals(java.util.Set.of("kind", "intentSha256", "category", "elapsedSeconds"), observation.keySet());
        } finally { source.stop(0); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
