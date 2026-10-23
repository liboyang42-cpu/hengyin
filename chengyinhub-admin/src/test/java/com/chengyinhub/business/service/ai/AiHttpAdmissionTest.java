package com.chengyinhub.business.service.ai;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.config.AiModelConfig;
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
        when(poison.toJSONString()).thenThrow(new IllegalStateException("synthetic-poison-never-export"));
        AiProviderNotSentException failure = assertThrows(AiProviderNotSentException.class,
                () -> ReflectionTestUtils.invokeMethod(client, "post", poison));
        assertFalse(failure.getMessage().contains("poison"));
        assertNull(failure.getCause());
        assertEquals(0, calls.get());
        verify(poison).toJSONString();
        try (HttpUtils.AttemptLease one = gate.acquire(base); HttpUtils.AttemptLease two = gate.acquire(base))
        {
            assertNotNull(two, "serialization failure must release the unsent lease");
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
        AiHttpAdmission gate = config.aiHttpAdmission(text, vision, props);
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
