package com.chengyinhub.business.service.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.config.AiModelConfig;
import com.chengyinhub.common.config.AiVoiceConfig;
import com.chengyinhub.common.config.AiProviderProperties;
import com.chengyinhub.common.utils.http.HttpUtils;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiHttpAdmissionTest
{
    private final List<HttpServer> servers = new ArrayList<>();
    private final List<ExecutorService> fixtures = new ArrayList<>();

    @AfterEach
    void cleanup()
    {
        servers.forEach(server -> server.stop(0));
        fixtures.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void canonicalOriginsShareTwoSlotsAndDoNotDynamicallyRegister()
    {
        AiHttpAdmission gate = new AiHttpAdmission(List.of("https://PROVIDER.invalid/v1", "https://provider.invalid.:443/v2"));
        try (HttpUtils.AttemptLease first = gate.acquire("https://provider.invalid/model-a");
             HttpUtils.AttemptLease second = gate.acquire("https://PROVIDER.invalid:443/model-b"))
        {
            assertThrows(AiProviderNotSentException.class, () -> gate.acquire("https://provider.invalid/v3"));
            assertThrows(AiProviderNotSentException.class, () -> gate.acquire("https://other.invalid/"));
        }
        try (HttpUtils.AttemptLease recovered = gate.acquire("https://provider.invalid/v1"))
        {
            assertNotNull(recovered);
        }
    }

    @Test
    void processGlobalFourCannotBeBypassedByDifferentAdmissionInstances()
    {
        AiHttpAdmission a = new AiHttpAdmission(List.of("https://one.invalid/"));
        AiHttpAdmission b = new AiHttpAdmission(List.of("https://two.invalid/"));
        AiHttpAdmission c = new AiHttpAdmission(List.of("https://three.invalid/"));
        try (HttpUtils.AttemptLease a1 = a.acquire("https://one.invalid/");
             HttpUtils.AttemptLease a2 = a.acquire("https://one.invalid/");
             HttpUtils.AttemptLease b1 = b.acquire("https://two.invalid/");
             HttpUtils.AttemptLease b2 = b.acquire("https://two.invalid/"))
        {
            assertThrows(AiProviderNotSentException.class, () -> c.acquire("https://three.invalid/"));
        }
        try (HttpUtils.AttemptLease recovered = c.acquire("https://three.invalid/")) { assertNotNull(recovered); }
    }

    @Test
    void invalidOriginsAndInterruptFailBeforeAdmissionWithoutLosingSlots()
    {
        AiHttpAdmission gate = new AiHttpAdmission(List.of("https://ok.invalid/", "https://u:p@bad.invalid/"));
        for (String invalid : List.of("ftp://bad.invalid/", "https://u:p@ok.invalid/", "https://ok.invalid/?secret=x",
                "https://ok.invalid/#fragment", "https://ok.invalid:0/", "bad"))
        {
            assertThrows(AiProviderNotSentException.class, () -> gate.acquire(invalid));
        }
        Thread.currentThread().interrupt();
        try { assertThrows(AiProviderNotSentException.class, () -> gate.acquire("https://ok.invalid/")); }
        finally { assertTrue(Thread.interrupted()); }
        try (HttpUtils.AttemptLease one = gate.acquire("https://ok.invalid/");
             HttpUtils.AttemptLease two = gate.acquire("https://ok.invalid/")) { assertNotNull(two); }
    }

    @Test
    void oldConstructorCannotSendWithoutStartupAdmissionButReadinessRemainsLocal() throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        HttpServer source = server(exchange -> { calls.incrementAndGet(); exchange.close(); });
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                "local", url(source), "synthetic", "model", true, 1000);
        assertDoesNotThrow(client::requireReady);
        AiProviderNotSentException rejected = assertThrows(AiProviderNotSentException.class, () -> client.chat("s", "u"));
        assertNull(rejected.getCause());
        assertEquals(0, rejected.getSuppressed().length);
        assertEquals(0, calls.get());
    }

    @Test
    void configurationAndSerializationFailuresAreTypedUnsentAndReleaseOriginalAdmission() throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        HttpServer source = server(exchange -> { calls.incrementAndGet(); exchange.close(); });
        String base = url(source);
        AiHttpAdmission gate = new AiHttpAdmission(List.of(base));
        for (String key : new String[]{null, "", " ", "fake\r\nAuthorization: poison"})
        {
            OpenAiCompatibleLlmClient bad = new OpenAiCompatibleLlmClient("safe", base, key, "model", true, 1000, gate);
            assertThrows(AiProviderNotSentException.class, () -> bad.chat("s", "u"));
        }
        for (String invalidBase : new String[]{null, "", " ", "http://u:p@127.0.0.1/", base + "?poison=x"})
        {
            OpenAiCompatibleLlmClient bad = new OpenAiCompatibleLlmClient("safe", invalidBase, "synthetic", "model", true, 1000, gate);
            assertThrows(AiProviderNotSentException.class, () -> bad.chat("s", "u"));
        }
        OpenAiCompatibleLlmClient badModel = new OpenAiCompatibleLlmClient("safe", base, "synthetic", " ", true, 1000, gate);
        assertThrows(AiProviderNotSentException.class, badModel::requireReady);
        OpenAiCompatibleLlmClient badTimeout = new OpenAiCompatibleLlmClient("safe", base, "synthetic", "model", true, 0, gate);
        assertThrows(AiProviderNotSentException.class, badTimeout::requireReady);
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient("safe", base, "synthetic", "model", true, 1000, gate);
        JSONObject notToSerialize = mock(JSONObject.class);
        try (HttpUtils.AttemptLease one = gate.acquire(base); HttpUtils.AttemptLease two = gate.acquire(base))
        {
            assertThrows(AiProviderNotSentException.class,
                    () -> ReflectionTestUtils.invokeMethod(client, "post", notToSerialize));
            verifyNoInteractions(notToSerialize);
        }
        JSONObject poison = mock(JSONObject.class);
        // The legacy body fallback now calls the byte codec, not JSONObject.toJSONString().
        try (var codec = mockStatic(JSON.class, CALLS_REAL_METHODS))
        {
            codec.when(() -> JSON.toJSONBytes(poison))
                    .thenThrow(new IllegalStateException("synthetic-poison-never-export"));
            AiProviderNotSentException failure = assertThrows(AiProviderNotSentException.class,
                    () -> ReflectionTestUtils.invokeMethod(client, "post", poison));
            assertFalse(failure.getMessage().contains("poison"));
            assertNull(failure.getCause());
            assertEquals(0, calls.get());
            codec.verify(() -> JSON.toJSONBytes(poison));
        }
        try (HttpUtils.AttemptLease one = gate.acquire(base); HttpUtils.AttemptLease two = gate.acquire(base))
        {
            assertNotNull(two, "serialization failure must release the unsent lease");
        }

        String image = "data:image/jpeg;base64," + "A".repeat(8193);
        JSONObject vision = JSON.parseObject("{\"model\":\"model\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"\"}},{\"type\":\"text\",\"text\":\"u\"}]}]}");
        JSONObject imageField = vision.getJSONArray("messages").getJSONObject(0)
                .getJSONArray("content").getJSONObject(0).getJSONObject("image_url");
        imageField.put("url", image);
        try (var codec = mockStatic(JSON.class, CALLS_REAL_METHODS))
        {
            // Default codec: segmented metadata; non-default features/writer: original byte fallback.
            // Both fail locally before any HttpUtils handoff; the original image must survive finally.
            codec.when(() -> JSON.toJSONBytes(vision))
                    .thenThrow(new IllegalStateException("synthetic-vision-poison-never-export"));
            AiProviderNotSentException failure = assertThrows(AiProviderNotSentException.class,
                    () -> ReflectionTestUtils.invokeMethod(client, "post", vision, image, null, "u"));
            assertFalse(failure.getMessage().contains("poison"));
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
            assertEquals(0, calls.get());
            assertSame(image, imageField.get("url"), "segmented failure must restore the original image");
            codec.verify(() -> JSON.toJSONBytes(vision));
        }
        try (HttpUtils.AttemptLease one = gate.acquire(base); HttpUtils.AttemptLease two = gate.acquire(base))
        {
            assertNotNull(two, "vision serialization failure must release the original unsent admission");
        }
    }

    @Test
    void productionBeansUseOneFixedOriginRegistry()
    {
        AiModelConfig text = mock(AiModelConfig.class);
        VisionModelConfig vision = mock(VisionModelConfig.class);
        AiProviderProperties props = new AiProviderProperties();
        when(text.getBaseUrl()).thenReturn("https://same.invalid/v1");
        when(vision.getBaseUrl()).thenReturn("https://SAME.invalid:443/v2");
        LlmClientConfig config = new LlmClientConfig();
        AiVoiceConfig voice = mock(AiVoiceConfig.class);
        when(voice.baseUrl()).thenReturn("https://same.invalid/voice");
        AiHttpAdmission gate = config.aiHttpAdmission(text, vision, props, voice);
        OpenAiCompatibleLlmClient qwen = (OpenAiCompatibleLlmClient) config.qwenLlmClient(text, gate);
        OpenAiCompatibleLlmClient deepseek = (OpenAiCompatibleLlmClient) config.deepseekLlmClient(text, gate);
        OpenAiCompatibleLlmClient vl = (OpenAiCompatibleLlmClient) config.visionLlmClient(vision, gate);
        assertSame(gate, ReflectionTestUtils.getField(qwen, "admission"));
        assertSame(gate, ReflectionTestUtils.getField(deepseek, "admission"));
        assertSame(gate, ReflectionTestUtils.getField(vl, "admission"));
        try (HttpUtils.AttemptLease one = gate.acquire("https://same.invalid/v1");
             HttpUtils.AttemptLease two = gate.acquire("https://same.invalid/v2"))
        {
            assertThrows(AiProviderNotSentException.class, () -> gate.acquire("https://same.invalid/v3"));
        }
    }

    @Test
    void fourRealAiCallsLeaveOtherCommonHttpUsableAndFifthNeverReachesServer() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<String> endpoints = new ArrayList<>();
        for (int i = 0; i < 2; i++)
        {
            HttpServer server = server(exchange -> {
                calls.incrementAndGet();
                entered.countDown();
                try
                {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            endpoints.add(url(server));
        }
        HttpServer overflow = server(exchange -> { calls.incrementAndGet(); exchange.close(); });
        endpoints.add(url(overflow));
        AiHttpAdmission gate = new AiHttpAdmission(endpoints);
        ExecutorService callers = Executors.newFixedThreadPool(4);
        fixtures.add(callers);
        List<Future<String>> results = new ArrayList<>();
        try
        {
            for (String endpoint : endpoints.subList(0, 2))
            {
                for (String model : List.of("a", "b"))
                {
                    OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient("safe", endpoint, "synthetic", model, true, 5000, gate);
                    results.add(callers.submit(() -> client.chat("s", "u")));
                }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            OpenAiCompatibleLlmClient fifth = new OpenAiCompatibleLlmClient("safe", endpoints.get(2), "synthetic", "c", true, 1000, gate);
            assertThrows(AiProviderNotSentException.class, () -> fifth.chat("s", "u"));
            assertEquals(4, calls.get());
            HttpServer other = server(exchange -> {
                byte[] bytes = "other-channel".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            long otherStarted = System.nanoTime();
            assertEquals("other-channel", HttpUtils.sendGet(url(other)));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - otherStarted) < 2000,
                    "local other-channel witness has a bounded budget, not a production SLO");
        }
        finally
        {
            release.countDown();
            for (Future<String> result : results) assertEquals("ok", result.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void realAdmissionStaysHeldAfterCallerTimeoutAndCleanupRejectionUntilWorkersExit() throws Exception
    {
        java.util.concurrent.ThreadPoolExecutor http = (java.util.concurrent.ThreadPoolExecutor)
                ReflectionTestUtils.getField(HttpUtils.class, "HTTP_EXECUTOR");
        java.util.concurrent.ThreadPoolExecutor cleanup = (java.util.concurrent.ThreadPoolExecutor)
                ReflectionTestUtils.getField(HttpUtils.class, "DISCONNECT_EXECUTOR");
        assertNotNull(http); assertNotNull(cleanup);
        long initialIdle = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (http.getActiveCount() != 0 || !http.getQueue().isEmpty()
                || cleanup.getActiveCount() != 0 || !cleanup.getQueue().isEmpty()) {
            assertTrue(System.nanoTime() - initialIdle < 0, "prior controlled fixtures must finish before measuring slots");
            Thread.sleep(10);
        }
        int originalCleanupCore = cleanup.getCorePoolSize();
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(cleanup.getMaximumPoolSize());
        CountDownLatch releaseBody = new CountDownLatch(1);
        CountDownLatch providerEntered = new CountDownLatch(2);
        List<java.util.concurrent.Future<?>> ownCleanup = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer source = server(exchange -> {
            calls.incrementAndGet(); providerEntered.countDown();
            try {
                if (!releaseBody.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("fixture body gate expired");
                byte[] buffer = new byte[8192];
                while (exchange.getRequestBody().read(buffer) != -1) { /* physical write may finish only now */ }
                exchange.sendResponseHeaders(200, -1);
            } catch (java.io.IOException expectedDisconnect) {
                // Caller cancellation can close the controlled loopback fixture after the assertion.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        String base = url(source);
        AiHttpAdmission gate = new AiHttpAdmission(List.of(base, "https://held-probe.invalid/", "https://overflow-probe.invalid/"));
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                "safe", base, "synthetic", "model", true, 1000, gate);
        ExecutorService callers = Executors.newFixedThreadPool(2); fixtures.add(callers);
        List<java.util.concurrent.Future<RuntimeException>> replies = new ArrayList<>();
        String largePrompt = "x".repeat(16 * 1024 * 1024);
        try {
            Runnable blocker = () -> {
                cleanupEntered.countDown();
                try { releaseCleanup.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            };
            int workers = cleanup.getMaximumPoolSize();
            assertEquals(4, workers);
            try {
                // core=1 otherwise queues three blockers instead of starting four workers.
                cleanup.setCorePoolSize(workers);
                for (int i = 0; i < workers; i++) ownCleanup.add(cleanup.submit(blocker));
                assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            } finally {
                cleanup.setCorePoolSize(originalCleanupCore);
            }
            // All four workers are inside our unreleased blocker before the queue is filled.
            int queued = cleanup.getQueue().remainingCapacity();
            assertEquals(64, queued);
            for (int i = 0; i < queued; i++) ownCleanup.add(cleanup.submit(blocker));
            int capacity = workers + queued;
            assertEquals(originalCleanupCore, cleanup.getCorePoolSize());
            assertEquals(workers, cleanup.getActiveCount());
            assertEquals(queued, cleanup.getQueue().size());
            for (int i = 0; i < 2; i++) replies.add(callers.submit(() ->
                    assertThrows(RuntimeException.class, () -> client.chat("s", largePrompt))));
            assertTrue(providerEntered.await(3, TimeUnit.SECONDS));
            for (java.util.concurrent.Future<RuntimeException> reply : replies) {
                RuntimeException timeout = reply.get(4, TimeUnit.SECONDS);
                assertFalse(timeout instanceof AiProviderNotSentException, "server received this POST; it is UNKNOWN");
                assertNull(timeout.getCause());
                assertEquals(0, timeout.getSuppressed().length);
            }
            assertEquals(capacity, cleanup.getActiveCount() + cleanup.getQueue().size(), "cleanup remains saturated");
            assertThrows(AiProviderNotSentException.class, () -> client.chat("s", "must not dispatch"));
            try (HttpUtils.AttemptLease one = gate.acquire("https://held-probe.invalid/");
                 HttpUtils.AttemptLease two = gate.acquire("https://held-probe.invalid/")) {
                assertThrows(AiProviderNotSentException.class, () -> gate.acquire("https://overflow-probe.invalid/"),
                        "two timed-out physical workers still consume the other two global slots");
            }
            assertEquals(2, calls.get());
        } finally {
            releaseBody.countDown(); releaseCleanup.countDown();
            cleanup.setCorePoolSize(originalCleanupCore);
            List<org.junit.jupiter.api.function.Executable> joins = new ArrayList<>();
            for (java.util.concurrent.Future<?> task : ownCleanup)
                joins.add(() -> task.get(5, TimeUnit.SECONDS));
            for (java.util.concurrent.Future<?> reply : replies)
                joins.add(() -> reply.get(5, TimeUnit.SECONDS));
            try {
                assertAll("all owned fixture futures must finish", joins);
            } finally {
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (http.getActiveCount() != 0 || !http.getQueue().isEmpty()
                        || cleanup.getActiveCount() != 0 || !cleanup.getQueue().isEmpty()) {
                    assertTrue(System.nanoTime() - end < 0, "controlled physical workers must actually exit");
                    Thread.sleep(10);
                }
            }
        }
        try (HttpUtils.AttemptLease one = gate.acquire(base); HttpUtils.AttemptLease two = gate.acquire(base)) {
            assertNotNull(two, "after real worker cleanup the same origin regains both slots");
        }
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        fixtures.add(executor);
        server.setExecutor(executor);
        server.createContext("/", handler);
        server.start();
        servers.add(server);
        return server;
    }

    private static String url(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }
}
