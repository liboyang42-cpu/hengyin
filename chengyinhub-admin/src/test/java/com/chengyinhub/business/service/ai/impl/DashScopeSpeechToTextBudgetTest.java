package com.chengyinhub.business.service.ai.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.common.config.AiModelConfig;
import com.chengyinhub.common.config.AiProviderProperties;
import com.chengyinhub.common.config.AiVoiceConfig;
import com.chengyinhub.common.utils.http.HttpRequestException;
import com.chengyinhub.common.utils.http.HttpUtils;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native STT + actual admitted common HTTP, controlled loopback only; no model, funds, JWT or DB acceptance. */
class DashScopeSpeechToTextBudgetTest {
    private final List<AutoCloseable> fixtures = new ArrayList<>();
    @AfterEach void closeFixtures() throws Exception { for (AutoCloseable fixture : fixtures) fixture.close(); }

    @Test void existingTwoMiBLimitIsEnforcedBeforeEncodingAndExactBoundaryKeepsOriginalWireContract() throws Exception {
        Provider provider = provider(request -> reply("{\"output\":{\"text\":\" 识别结果 \"}}", -1, false));
        DashScopeSpeechToTextService stt = admitted(config(provider.url()), new AiHttpAdmission(List.of(provider.url())));
        assertThrows(IllegalArgumentException.class, () -> stt.transcribe(wav(DashScopeSpeechToTextService.MAX_AUDIO_BYTES + 2), "wav"));
        assertEquals(0, provider.requests.size());
        byte[] original = wav(DashScopeSpeechToTextService.MAX_AUDIO_BYTES);
        assertEquals("识别结果", stt.transcribe(original, " WAV "));
        assertEquals(1, provider.requests.size());
        Request sent = provider.requests.get(0);
        assertEquals("POST", sent.method()); assertEquals("Bearer synthetic-asr", sent.authorization());
        assertEquals("disable", sent.sse()); assertTrue(sent.contentType().startsWith("application/json"));
        assertEquals("asr-fixture", sent.body().getString("model"));
        assertEquals("wav", sent.body().getJSONObject("parameters").getString("format"));
        String data = sent.body().getJSONObject("input").getJSONArray("messages").getJSONObject(0)
                .getJSONArray("content").getJSONObject(0).getJSONObject("input_audio").getString("data");
        assertTrue(data.startsWith("data:audio/wav;base64,"));
        String payload = data.substring(data.indexOf(',') + 1);
        assertEquals(4 * ((original.length + 2) / 3), payload.length());
        assertArrayEquals(original, Base64.getDecoder().decode(payload));
    }

    @Test void absentLengthGzipAndActualReadLimitDoNotTrustOnlyContentLength() throws Exception {
        for (boolean gzip : new boolean[]{false, true}) {
            Provider valid = provider(request -> reply("{\"output\":{\"text\":\"你好\"}}", 0, gzip));
            assertEquals("你好", admitted(config(valid.url()), new AiHttpAdmission(List.of(valid.url()))).transcribe(wav(64), "wav"));
            assertEquals(1, valid.requests.size());
        }
        int limit = 1024 * 1024;
        String prefix = "{\"output\":{\"text\":\"must-not-be-success\"},\"padding\":\"", suffix = "\"}";
        String oversized = prefix + "x".repeat(limit + 1 - prefix.length() - suffix.length()) + suffix;
        for (boolean gzip : new boolean[]{false, true}) {
            Provider invalid = provider(request -> reply(oversized, 0, gzip));
            AiServiceException failure = assertThrows(AiServiceException.class,
                    () -> admitted(config(invalid.url()), new AiHttpAdmission(List.of(invalid.url()))).transcribe(wav(64), "wav"));
            assertMayHaveBeenSent(failure); assertEquals(1, invalid.requests.size());
        }
    }

    @Test void falseLengthsAndPrematureDisconnectAreErrorsWithoutAnotherPaidPost() throws Exception {
        String json = "{\"output\":{\"text\":\"must-not-be-success\"}}";
        for (String raw : List.of(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1048577\r\nConnection: close\r\n\r\n" + json,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n" + json,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 5\r\nConnection: close\r\n\r\n" + json,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{\"output\":")) {
            RawProvider source = new RawProvider(raw); fixtures.add(source);
            AiServiceException failure = assertThrows(AiServiceException.class,
                    () -> admitted(config(source.url()), new AiHttpAdmission(List.of(source.url()))).transcribe(wav(64), "wav"));
            assertMayHaveBeenSent(failure); assertEquals(1, source.calls.get());
        }
    }

    @Test void redirectsAnd503ResponsesCannotReplayPostOrBecomeNoSpeech() throws Exception {
        for (int status : new int[]{302, 503}) {
            Provider source = provider(request -> new Reply(status, "{\"code\":\"controlled\"}".getBytes(StandardCharsets.UTF_8), -1, false, "/must-not-follow"));
            AiServiceException failure = assertThrows(AiServiceException.class,
                    () -> admitted(config(source.url()), new AiHttpAdmission(List.of(source.url()))).transcribe(wav(64), "wav"));
            assertMayHaveBeenSent(failure); assertEquals(1, source.requests.size());
        }
    }

    @Test void saturatedOrMissingAdmissionRejectsBeforeBase64BodyPreparation() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        Provider source = provider(request -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture gate expired"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return reply("{\"output\":{\"text\":\"ok\"}}", -1, false);
        });
        AtomicInteger preparations = new AtomicInteger();
        AiVoiceConfig config = config(source.url());
        AiVoiceConfig observed = new AiVoiceConfig() {
            @Override public boolean isAsrUsable() { return true; }
            @Override public String asrUrl() { return config.asrUrl(); }
            @Override public String getApiKey() { return config.getApiKey(); }
            @Override public int getTimeoutMs() { return config.getTimeoutMs(); }
            @Override public String getAsrModel() { preparations.incrementAndGet(); return config.getAsrModel(); }
        };
        AiHttpAdmission gate = new AiHttpAdmission(List.of(source.url()));
        DashScopeSpeechToTextService stt = admitted(observed, gate);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = callers.submit(() -> stt.transcribe(wav(64), "wav"));
            Future<String> second = callers.submit(() -> stt.transcribe(wav(64), "wav"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            AiServiceException declined = assertThrows(AiServiceException.class, () -> stt.transcribe(wav(64), "wav"));
            assertInstanceOf(AiProviderNotSentException.class, declined.getCause());
            assertEquals(2, preparations.get()); assertEquals(2, source.requests.size());
            release.countDown(); assertEquals("ok", first.get(5, TimeUnit.SECONDS)); assertEquals("ok", second.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); callers.shutdownNow(); assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS)); }
        DashScopeSpeechToTextService unwired = new DashScopeSpeechToTextService(observed);
        assertInstanceOf(AiProviderNotSentException.class,
                assertThrows(AiServiceException.class, () -> unwired.transcribe(wav(64), "wav")).getCause());
        assertEquals(2, preparations.get());
    }

    @Test void newHeaderControlCannotOverrideCredentialsOrFramingAndRejectedLeaseIsReusable() throws Exception {
        Provider source = provider(request -> reply("{}", -1, false));
        AiHttpAdmission gate = new AiHttpAdmission(List.of(source.url()));
        List<Map<String, String>> rejected = new ArrayList<>(List.of(Map.of("Authorization", "poison"), Map.of("Host", "bad"),
                Map.of("Content-Length", "0"), Map.of("X-DashScope-SSE", "enable"),
                Map.of("X-DashScope-SSE", "disable", "Authorization", "poison"), Map.of("X-DashScope-SSE", "disable\r\nHost: poison")));
        rejected.add(null);
        for (Map<String, String> headers : rejected) {
            try (HttpUtils.AttemptLease lease = gate.acquire(source.url())) {
                HttpRequestException failure = assertThrows(HttpRequestException.class, () -> HttpUtils.sendJsonPost(
                        source.url(), "{}", "synthetic", System.nanoTime() + TimeUnit.SECONDS.toNanos(5), lease, headers));
                assertEquals(HttpRequestException.SendDisposition.NOT_SENT, failure.getSendDisposition());
            }
        }
        assertEquals(0, source.requests.size());
        try (HttpUtils.AttemptLease one = gate.acquire(source.url()); HttpUtils.AttemptLease two = gate.acquire(source.url())) { assertNotNull(two); }
    }

    @Test void actualFactoryRegistersIndependentAsrOriginAndInjectsTheSharedAdmission() throws Exception {
        Provider source = provider(request -> reply("{\"output\":{\"text\":\"ok\"}}", -1, false));
        AiModelConfig text = mock(AiModelConfig.class); VisionModelConfig vision = mock(VisionModelConfig.class);
        when(text.getBaseUrl()).thenReturn("https://text.invalid/v1"); when(vision.getBaseUrl()).thenReturn("https://vision.invalid/v1");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("textConfig", text); context.getBeanFactory().registerSingleton("visionConfig", vision);
            context.getBeanFactory().registerSingleton("providerProperties", new AiProviderProperties());
            context.getBeanFactory().registerSingleton("voiceConfig", config(source.url()));
            context.register(LlmClientConfig.class, DashScopeSpeechToTextService.class); context.refresh();
            DashScopeSpeechToTextService stt = context.getBean(DashScopeSpeechToTextService.class);
            assertSame(context.getBean(AiHttpAdmission.class), ReflectionTestUtils.getField(stt, "admission"));
            assertEquals("ok", stt.transcribe(wav(64), "wav"));
            assertEquals("disable", source.requests.get(0).sse());
        }
    }

    private static void assertMayHaveBeenSent(AiServiceException failure) {
        assertEquals(AiServiceException.Reason.UNKNOWN, failure.getReason());
        assertFalse(failure.getCause() instanceof AiProviderNotSentException);
        if (failure.getCause() instanceof HttpRequestException transport)
            assertNotEquals(HttpRequestException.SendDisposition.NOT_SENT, transport.getSendDisposition());
    }
    private static AiVoiceConfig config(String origin) {
        AiVoiceConfig config = new AiVoiceConfig() {
            @Override public String asrUrl() { return origin + "/asr"; }
            @Override public String baseUrl() { return "https://voice.invalid/audio/tts"; }
        };
        config.setAsrEnabled(true); config.setApiKey("synthetic-asr"); config.setWorkspaceId("fixture");
        config.setRegion("cn-beijing"); config.setAsrModel("asr-fixture"); config.setTimeoutMs(10_000); return config;
    }
    private static DashScopeSpeechToTextService admitted(AiVoiceConfig config, AiHttpAdmission gate) {
        DashScopeSpeechToTextService service = new DashScopeSpeechToTextService(config);
        ReflectionTestUtils.setField(service, "admission", gate); return service;
    }
    private static byte[] wav(int size) {
        byte[] bytes = new byte[size]; ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(size - 8).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(size - 44); return bytes;
    }
    private Provider provider(Function<Request, Reply> handler) throws IOException { Provider p = new Provider(handler); fixtures.add(p); return p; }
    private static Reply reply(String text, long length, boolean gzip) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (gzip) {
            try { ByteArrayOutputStream out = new ByteArrayOutputStream(); try (GZIPOutputStream zip = new GZIPOutputStream(out)) { zip.write(body); } body = out.toByteArray(); }
            catch (IOException impossible) { throw new UncheckedIOException(impossible); }
        }
        return new Reply(200, body, length, gzip, null);
    }
    private record Request(String method, String authorization, String contentType, String sse, JSONObject body) { }
    private record Reply(int status, byte[] body, long length, boolean gzip, String location) { }
    private static class Provider implements AutoCloseable {
        final HttpServer server; final ExecutorService workers = Executors.newFixedThreadPool(3);
        final List<Request> requests = new CopyOnWriteArrayList<>();
        Provider(Function<Request, Reply> handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try {
                    Request request = new Request(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("Authorization"),
                            exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestHeaders().getFirst("X-DashScope-SSE"),
                            JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    requests.add(request); Reply reply = handler.apply(request);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    if (reply.gzip()) exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                    if (reply.location() != null) exchange.getResponseHeaders().set("Location", reply.location());
                    exchange.sendResponseHeaders(reply.status(), reply.length() < 0 ? reply.body().length : reply.length());
                    exchange.getResponseBody().write(reply.body());
                } catch (IOException expectedEarlyClose) { /* bounded client can stop reading the controlled oversized body */ }
                finally { exchange.close(); }
            }); server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() throws InterruptedException { server.stop(0); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    private static class RawProvider implements AutoCloseable {
        final ServerSocket server; final ExecutorService worker = Executors.newSingleThreadExecutor();
        final AtomicInteger calls = new AtomicInteger(); final Future<?> done;
        RawProvider(String reply) throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); server.setSoTimeout(10_000);
            done = worker.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10_000); calls.incrementAndGet(); InputStream input = socket.getInputStream();
                    ByteArrayOutputStream head = new ByteArrayOutputStream(); int state = 0;
                    while (state < 4) {
                        int b = input.read(); if (b < 0) throw new EOFException(); head.write(b);
                        if (head.size() > 16384) throw new IOException("fixture request headers too large");
                        state = (state == 0 || state == 2) ? (b == '\r' ? state + 1 : 0) : (b == '\n' ? state + 1 : 0);
                    }
                    int length = Arrays.stream(head.toString(StandardCharsets.US_ASCII).split("\r\n"))
                            .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                            .mapToInt(line -> Integer.parseInt(line.substring(line.indexOf(':') + 1).trim())).findFirst().orElse(0);
                    if (length < 0 || length > 3 * 1024 * 1024) throw new IOException("fixture body outside controlled budget");
                    assertEquals(length, input.readNBytes(length).length);
                    socket.getOutputStream().write(reply.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
        String url() { return "http://127.0.0.1:" + server.getLocalPort(); }
        @Override public void close() throws Exception { server.close(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); done.get(5, TimeUnit.SECONDS); }
    }
}
