package com.chengyinhub.business.service.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.config.AiModelConfig;
import com.chengyinhub.common.config.AiProviderProperties;
import com.chengyinhub.common.config.AiVoiceConfig;
import com.chengyinhub.common.utils.http.HttpRequestException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual shared HTTP transport against controlled loopback providers; not a real-model/fee/receipt test. */
class DashScopeVoiceTransportBudgetTest {
    private final List<Provider> providers = new ArrayList<>();

    @AfterEach void cleanup() throws Exception {
        for (Provider provider : providers) provider.close();
    }

    @Test void enrollQueryAndSynthesisKeepOriginalPayloadHeadersAndResponseAliases() throws Exception {
        Provider source = provider(request -> ok(request.path().endsWith("/SpeechSynthesizer")
                ? "{\"output\":{\"audio\":{\"url\":\"https://audio.invalid/original.mp3\"}}}"
                : "query_voice".equals(request.body().getJSONObject("input").getString("action"))
                    ? "{\"output\":{\"status\":\"oK\"}}" : "{\"output\":{\"voice\":\"original-voice\"}}"));
        AiVoiceConfig config = config(source.url());
        DashScopeVoiceClient client = client(config, new AiHttpAdmission(List.of(source.url())));
        assertEquals("original-voice", client.enroll("https://owned.invalid/original.wav"));
        assertTrue(client.isReady("original-voice"));
        assertEquals("https://audio.invalid/original.mp3", client.synthesize("original-voice", "原台词"));
        assertEquals(3, source.requests.size());
        for (Request request : source.requests) {
            assertEquals("POST", request.method()); assertEquals("Bearer synthetic-voice", request.authorization());
            assertTrue(request.contentType().startsWith("application/json"));
        }
        JSONObject enroll = source.requests.get(0).body(), query = source.requests.get(1).body(), speech = source.requests.get(2).body();
        assertEquals("voice-enrollment", enroll.getString("model"));
        assertEquals("create_voice", enroll.getJSONObject("input").getString("action"));
        assertEquals("cosyvoice-fixture", enroll.getJSONObject("input").getString("target_model"));
        assertEquals("city", enroll.getJSONObject("input").getString("prefix"));
        assertEquals("https://owned.invalid/original.wav", enroll.getJSONObject("input").getString("url"));
        assertEquals("query_voice", query.getJSONObject("input").getString("action"));
        assertEquals("original-voice", query.getJSONObject("input").getString("voice_id"));
        assertEquals("cosyvoice-fixture", speech.getString("model"));
        assertEquals("original-voice", speech.getJSONObject("input").getString("voice"));
        assertEquals("原台词", speech.getJSONObject("input").getString("text"));
        assertEquals("mp3", speech.getJSONObject("input").getString("format"));
    }

    @Test void unregisteredOrUninjectedAdmissionCannotDispatchAndKeepsVoiceExceptionContract() throws Exception {
        Provider source = provider(request -> ok("{\"output\":{\"voice_id\":\"must-not-exist\"}}"));
        for (AiHttpAdmission admission : Arrays.asList(null, new AiHttpAdmission(List.of("https://other.invalid/")))) {
            DashScopeVoiceClient voice = client(config(source.url()), admission);
            AiServiceException rejected = assertThrows(AiServiceException.class,
                    () -> voice.enroll("https://owned.invalid/original.wav"));
            assertEquals(AiServiceException.Reason.UNKNOWN, rejected.getReason(), "no new voice business/compensation policy");
            assertInstanceOf(AiProviderNotSentException.class, rejected.getCause(), "only the existing admission proves local non-dispatch");
        }
        assertEquals(0, source.requests.size());
    }

    @Test void redirectsAndProviderFailuresAreOneAttemptAndNeverReportedAsUnsent() throws Exception {
        for (int status : new int[]{302, 503}) {
            Provider source = provider(request -> new Reply(status, "{\"code\":\"controlled\"}".getBytes(StandardCharsets.UTF_8), "/must-not-follow"));
            DashScopeVoiceClient voice = client(config(source.url()), new AiHttpAdmission(List.of(source.url())));
            AiServiceException failure = assertThrows(AiServiceException.class,
                    () -> voice.synthesize("original-voice", "原台词"));
            assertEquals(AiServiceException.Reason.UNKNOWN, failure.getReason());
            HttpRequestException transport = assertInstanceOf(HttpRequestException.class, failure.getCause());
            assertNotEquals(HttpRequestException.SendDisposition.NOT_SENT, transport.getSendDisposition());
            assertEquals(1, source.requests.size(), "no POST retry and no redirect replay");
        }
    }

    @Test void existingOneMiBResponseBoundaryAppliesAndOversizeCannotBecomeSuccessOrAnAutomaticReplay() throws Exception {
        int limit = 1024 * 1024;
        Provider source = provider(request -> {
            if (request.path().endsWith("/customization")) return ok("{\"output\":{\"status\":\"OK\"}}");
            int bytes = "oversize".equals(request.body().getJSONObject("input").getString("text")) ? limit + 1 : limit;
            String prefix = "{\"output\":{\"audio_url\":\"https://audio.invalid/original.mp3\"},\"padding\":\"";
            String suffix = "\"}";
            return ok(prefix + "x".repeat(bytes - prefix.length() - suffix.length()) + suffix);
        });
        AiHttpAdmission gate = new AiHttpAdmission(List.of(source.url()));
        DashScopeVoiceClient voice = client(config(source.url()), gate);
        assertEquals("https://audio.invalid/original.mp3", voice.synthesize("original-voice", "exact"));
        AiServiceException rejected = assertThrows(AiServiceException.class, () -> voice.synthesize("original-voice", "oversize"));
        assertNotEquals(HttpRequestException.SendDisposition.NOT_SENT,
                assertInstanceOf(HttpRequestException.class, rejected.getCause()).getSendDisposition());
        assertEquals(2, source.requests.size());
        assertTrue(voice.isReady("original-voice"), "a later explicit read of the original voice can use released capacity");
        assertEquals(3, source.requests.size());
        assertEquals("query_voice", source.requests.get(2).body().getJSONObject("input").getString("action"));
    }

    @Test void twoVoiceCallsHoldTheSameOriginBudgetAsExistingTextClients() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        Provider source = provider(request -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture gate expired"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return ok("{\"output\":{\"audio_url\":\"https://audio.invalid/original.mp3\"}}");
        });
        AiHttpAdmission gate = new AiHttpAdmission(List.of(source.url()));
        DashScopeVoiceClient voice = client(config(source.url()), gate);
        OpenAiCompatibleLlmClient text = new OpenAiCompatibleLlmClient("safe", source.url(), "synthetic", "model", true, 5000, gate);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<String> one = callers.submit(() -> voice.synthesize("original-voice", "one"));
            Future<String> two = callers.submit(() -> voice.synthesize("original-voice", "two"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertThrows(AiProviderNotSentException.class, () -> text.chat("s", "must not dispatch"));
            assertEquals(2, source.requests.size());
            release.countDown();
            assertEquals("https://audio.invalid/original.mp3", one.get(5, TimeUnit.SECONDS));
            assertEquals("https://audio.invalid/original.mp3", two.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown(); callers.shutdownNow(); assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void realProductionFactoryRegistersDistinctVoiceOriginAndInjectsOneSharedAdmission() throws Exception {
        Provider source = provider(request -> ok("{\"output\":{\"voice_id\":\"original-voice\"}}"));
        AiModelConfig text = mock(AiModelConfig.class); VisionModelConfig vision = mock(VisionModelConfig.class);
        when(text.getBaseUrl()).thenReturn("https://text.invalid/v1"); when(text.getTimeoutMs()).thenReturn(5000);
        when(vision.getBaseUrl()).thenReturn("https://vision.invalid/v1"); when(vision.getTimeoutMs()).thenReturn(5000);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // Completed test dependencies must not have mock @Autowired/@Value fields populated by Spring.
            context.getBeanFactory().registerSingleton("textConfig", text);
            context.getBeanFactory().registerSingleton("visionConfig", vision);
            context.getBeanFactory().registerSingleton("providerProperties", new AiProviderProperties());
            context.getBeanFactory().registerSingleton("voiceConfig", config(source.url()));
            context.register(LlmClientConfig.class, DashScopeVoiceClient.class); context.refresh();
            DashScopeVoiceClient voice = context.getBean(DashScopeVoiceClient.class);
            assertSame(context.getBean(AiHttpAdmission.class), ReflectionTestUtils.getField(voice, "admission"));
            assertEquals("original-voice", voice.enroll("https://owned.invalid/original.wav"));
            assertEquals(1, source.requests.size());
        }
    }

    private static AiVoiceConfig config(String origin) {
        AiVoiceConfig voice = new AiVoiceConfig() { @Override public String baseUrl() { return origin + "/audio"; } };
        voice.setEnabled(true); voice.setApiKey("synthetic-voice"); voice.setWorkspaceId("fixture"); voice.setTimeoutMs(5000);
        voice.setEnrollModel("voice-enrollment"); voice.setTargetModel("cosyvoice-fixture");
        voice.setSynthModel("cosyvoice-fixture"); voice.setPrefix("city"); return voice;
    }

    private static DashScopeVoiceClient client(AiVoiceConfig config, AiHttpAdmission admission) {
        DashScopeVoiceClient voice = new DashScopeVoiceClient(); ReflectionTestUtils.setField(voice, "config", config);
        if (admission != null) ReflectionTestUtils.setField(voice, "admission", admission);
        return voice;
    }

    private Provider provider(Function<Request, Reply> handler) throws IOException {
        Provider provider = new Provider(handler); providers.add(provider); return provider;
    }
    private static Reply ok(String body) { return new Reply(200, body.getBytes(StandardCharsets.UTF_8), null); }
    private record Request(String method, String path, String authorization, String contentType, JSONObject body) { }
    private record Reply(int status, byte[] body, String location) { }

    private static final class Provider implements AutoCloseable {
        final HttpServer server; final ExecutorService workers = Executors.newFixedThreadPool(4);
        final List<Request> requests = new CopyOnWriteArrayList<>();
        Provider(Function<Request, Reply> handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try {
                    Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                            exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("Content-Type"),
                            JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    requests.add(request); Reply reply = handler.apply(request);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    if (reply.location() != null) exchange.getResponseHeaders().set("Location", reply.location());
                    exchange.sendResponseHeaders(reply.status(), reply.body().length);
                    exchange.getResponseBody().write(reply.body());
                } catch (IOException expectedClosedOversizedResponse) {
                    // The controlled client may close once the response limit is exceeded.
                } finally { exchange.close(); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() throws InterruptedException {
            server.stop(0); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
