package com.chengyinhub.business.it;

import com.chengyinhub.business.service.TencentMapClient;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.business.service.impl.MapGeocodeServiceImpl;
import com.chengyinhub.common.utils.http.HttpUtils;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed, owned fault gates around real clients; no substitute admission, quota or transport executor. */
final class CrossDomainTransportStage implements AutoCloseable {
    static final String SMALL_IMAGE = "data:image/jpeg;base64,AA==";
    private volatile String faultImage;
    // Keep the original 16 MiB physical-write witness, with bounded metadata headroom.
    static final int REQUEST_LIMIT = 17 * 1024 * 1024;
    private static final JsonFactory REQUEST_JSON = JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(16).maxNameLength(64)
                    .maxStringLength(1024).build()).build();
    final CountDownLatch aiEntered = new CountDownLatch(4), mapEntered = new CountDownLatch(4);
    final CountDownLatch aiServerExited = new CountDownLatch(4);
    final CountDownLatch releaseAi = new CountDownLatch(1), releaseCleanup = new CountDownLatch(1);
    final CountDownLatch releaseMap = new CountDownLatch(1), mapClosed = new CountDownLatch(4);
    final CountDownLatch releaseDisconnect = new CountDownLatch(1), mapsExited = new CountDownLatch(4);
    final AtomicInteger aiRequests = new AtomicInteger(), mapRequests = new AtomicInteger(), mapActive = new AtomicInteger();
    final AtomicInteger mapPeak = new AtomicInteger(), mapInterrupts = new AtomicInteger();
    final AtomicLong mapClock = new AtomicLong();
    final List<Throwable> providerFailures = new CopyOnWriteArrayList<>();
    final ExecutorService callers = boundedExecutor(8, 8);
    final ExecutorService providers = boundedExecutor(4, 4);
    final List<HttpServer> servers = new ArrayList<>();
    final List<Future<?>> ownedCleanup = new ArrayList<>();
    final List<Future<String>> maps = new ArrayList<>();
    final List<String> origins = new ArrayList<>();
    ThreadPoolExecutor http, cleanup;
    AiHttpAdmission admission;
    volatile boolean fault;
    boolean cleanupArmed;
    int originalCore;
    final MapClient map = new MapClient();
    final MapGeocodeServiceImpl cities = new MapGeocodeServiceImpl();
    final MapGeocodeServiceImpl otherCities = new MapGeocodeServiceImpl();

    void open() throws Exception {
        http = (ThreadPoolExecutor)ReflectionTestUtils.getField(HttpUtils.class, "HTTP_EXECUTOR");
        cleanup = (ThreadPoolExecutor)ReflectionTestUtils.getField(HttpUtils.class, "DISCONNECT_EXECUTOR");
        assertNotNull(http); assertNotNull(cleanup);
        assertEquals(0, http.getActiveCount(), "run this process-global witness alone"); assertTrue(http.getQueue().isEmpty());
        assertEquals(0, cleanup.getActiveCount()); assertTrue(cleanup.getQueue().isEmpty());
        for (int i = 0; i < 2; i++) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            server.setExecutor(providers); server.createContext("/chat/completions", exchange -> {
                boolean blocked = fault;
                try {
                    assertEquals("POST", exchange.getRequestMethod());
                    assertEquals("Bearer fixture-token", exchange.getRequestHeaders().getFirst("Authorization"));
                    assertTrue(exchange.getRequestHeaders().getFirst("Content-Type").contains("application/json"));
                    aiRequests.incrementAndGet();
                    if (blocked) { aiEntered.countDown(); assertTrue(releaseAi.await(60, TimeUnit.SECONDS)); }
                    validateRequest(exchange.getRequestBody());
                    String content = "{\"pass\":true,\"confidence\":91,\"reason\":\"controlled received result\"}";
                    var message = new com.alibaba.fastjson2.JSONObject(); message.put("content", content);
                    var choice = new com.alibaba.fastjson2.JSONObject(); choice.put("message", message);
                    byte[] response = ("{\"choices\":[" + choice.toJSONString() + "]}").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
                } catch (RequestProtocolException invalid) {
                    // Malformed/oversized input is never a permitted fault-phase disconnect.
                    providerFailures.add(invalid);
                } catch (IOException disconnected) {
                    // Only fault-phase caller cancellation may disconnect the local post-dispatch fixture.
                    if (!blocked) providerFailures.add(disconnected);
                } catch (Throwable error) { providerFailures.add(error); }
                finally { exchange.close(); if (blocked) aiServerExited.countDown(); }
            });
            server.start(); servers.add(server); origins.add("http://127.0.0.1:" + server.getAddress().getPort());
        }
        admission = new AiHttpAdmission(origins);
        ReflectionTestUtils.setField(map, "enabled", true); ReflectionTestUtils.setField(map, "mapKey", "fixture-map");
        ReflectionTestUtils.setField(cities, "tencentMapClient", map);
        ReflectionTestUtils.setField(otherCities, "tencentMapClient", map);
    }

    private static ExecutorService boundedExecutor(int workers, int queued) {
        return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queued), new ThreadPoolExecutor.AbortPolicy());
    }

    /** Consume the real JSON envelope without retaining the image string or an object tree. */
    static void validateRequest(InputStream input) throws IOException {
        try (JsonParser json = REQUEST_JSON.createParser(new RequestInput(input))) {
            requireProtocol(json.nextToken() == JsonToken.START_OBJECT);
            boolean model = false, messages = false;
            while (json.nextToken() != JsonToken.END_OBJECT) {
                requireProtocol(json.currentToken() == JsonToken.FIELD_NAME);
                String field = json.currentName();
                JsonToken value = json.nextToken();
                if ("model".equals(field)) {
                    requireProtocol(!model && value == JsonToken.VALUE_STRING);
                    requireProtocol("fixture-model".equals(json.getText())); model = true;
                } else if ("messages".equals(field)) {
                    requireProtocol(!messages && value == JsonToken.START_ARRAY);
                    for (int i = 0; i < 2; i++) {
                        requireProtocol(json.nextToken() == JsonToken.START_OBJECT);
                        // nextToken/skipChildren validate syntax/depth without getText on image content.
                        json.skipChildren();
                    }
                    requireProtocol(json.nextToken() == JsonToken.END_ARRAY); messages = true;
                } else {
                    throw new RequestProtocolException();
                }
            }
            requireProtocol(model && messages && json.nextToken() == null);
        } catch (JsonProcessingException malformed) {
            // Parser exceptions may quote request content; retain only a fixed safe failure.
            throw new RequestProtocolException();
        }
    }

    private static void requireProtocol(boolean valid) throws RequestProtocolException {
        if (!valid) throw new RequestProtocolException();
    }

    static final class RequestProtocolException extends IOException {
        RequestProtocolException() { super("CONTROLLED_PROVIDER_REQUEST_INVALID_OR_OVER_BUDGET"); }
    }

    private static final class RequestInput extends FilterInputStream {
        private int remaining = REQUEST_LIMIT;
        RequestInput(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value != -1 && --remaining < 0) throw new RequestProtocolException();
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, Math.min(length, remaining + 1));
            if (count > 0 && (remaining -= count) < 0) throw new RequestProtocolException();
            return count;
        }
        @Override public long skip(long count) throws IOException {
            // Do not allow a parser/stream consumer to bypass the byte budget via skip().
            long skipped = in.skip(Math.min(Math.max(0L, count), (long) remaining + 1));
            remaining -= (int) skipped;
            if (remaining < 0) throw new RequestProtocolException();
            return skipped;
        }
    }
    OpenAiCompatibleLlmClient client(int origin) {
        return new OpenAiCompatibleLlmClient("t19-controlled", origins.get(origin), "fixture-token", "fixture-model", true, 2000, admission);
    }
    private static String newFaultImage() { return "data:image/jpeg;base64," + "A".repeat(16 * 1024 * 1024); }
    String image() {
        if (!fault) return SMALL_IMAGE;
        String source = faultImage;
        // A later explicit busy/admission control still gets exactly the original image value,
        // without repinning it for the remainder of the JVM. No command/provider is dispatched here.
        return source == null ? newFaultImage() : source;
    }

    void retireImageSource(List<? extends Future<PhotoCheckIntentView>> originals) {
        assertEquals(4, originals.size()); assertTrue(originals.stream().allMatch(Future::isDone));
        assertTrue(fault); assertEquals(0L, aiEntered.getCount());
        assertEquals(4L, aiServerExited.getCount()); assertEquals(1L, releaseAi.getCount());
        // The original four transport-owned UTF-8 entities remain held. Only the encoder's
        // now-unused source String loses this fixture reference; no lease/handler/body is released.
        faultImage = null;
    }

    /** Preserve completed caller failures at the original entry barrier; never wait/retry another call. */
    static AssertionError aiEntryFailure(List<? extends Future<PhotoCheckIntentView>> pending,
                                         List<? extends Throwable> providerFailures) throws InterruptedException {
        assertEquals(4, pending.size(), "The original four photo calls must be retained");
        AssertionError missing = new AssertionError("All four original AI provider handlers must enter the held window");
        for (Future<PhotoCheckIntentView> request : pending) {
            if (!request.isDone()) continue;
            try {
                PhotoCheckIntentView result = request.get(); // isDone: no extra wait or timeout budget.
                String state = result == null ? "NULL" : result.state() == null ? "UNAVAILABLE" : result.state().name();
                String provider = result != null && result.providerState() != null
                        && Set.of("NOT_SENT", "UNKNOWN", "RECEIVED").contains(result.providerState())
                        ? result.providerState() : "UNAVAILABLE";
                missing.addSuppressed(new AssertionError("Photo completed before the four-handler barrier: state="
                        + state + ", providerState=" + provider));
            } catch (ExecutionException failed) {
                Throwable original = failed.getCause() == null ? failed : failed.getCause();
                if (missing.getCause() == null) missing.initCause(original);
                else missing.addSuppressed(original);
            } catch (CancellationException cancelled) {
                missing.addSuppressed(cancelled);
            }
        }
        providerFailures.forEach(missing::addSuppressed);
        return missing;
    }

    void beginFault() throws Exception {
        faultImage = newFaultImage();
        fault = true;
        // Reuse AiHttpAdmissionTest's real worker-exit counterexample: saturate only owned cleanup work.
        // Small POSTs often leave their workers immediately on abort; they cannot prove lifetime retention.
        originalCore = cleanup.getCorePoolSize(); int max = cleanup.getMaximumPoolSize(); assertEquals(4, max);
        CountDownLatch entered = new CountDownLatch(max);
        Runnable blocker = () -> { entered.countDown(); awaitUninterruptibly(releaseCleanup, null); };
        cleanupArmed = true;
        try {
            cleanup.setCorePoolSize(max);
            for (int i = 0; i < max; i++) ownedCleanup.add(cleanup.submit(blocker));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        } finally { cleanup.setCorePoolSize(originalCore); }
        int queued = cleanup.getQueue().remainingCapacity(); assertEquals(64, queued);
        for (int i = 0; i < queued; i++) ownedCleanup.add(cleanup.submit(blocker));
        for (int i = 0; i < 4; i++) {
            final int index = i;
            maps.add(callers.submit(() -> {
                try { return cities.resolveCity(120D + index / 1000D, 30D); }
                finally { mapsExited.countDown(); }
            }));
        }
        assertTrue(mapEntered.await(5, TimeUnit.SECONDS)); assertEquals(4, mapActive.get());
    }
    void assertHeldAfterAiCallerTimeouts() {
        assertThrows(AiProviderNotSentException.class, () -> client(0).chat("s", "not dispatched"));
        assertThrows(AiProviderNotSentException.class, () -> client(1).chat("s", "not dispatched"));
        AiHttpAdmission anotherRegistry = new AiHttpAdmission(List.of("https://t19-overflow.invalid/"));
        assertThrows(AiProviderNotSentException.class, () -> anotherRegistry.acquire("https://t19-overflow.invalid/"), "different registry/origin cannot bypass four physical global workers");
        assertEquals(4, mapRequests.get());
        assertNull(map.get("https://apis.map.qq.com/ws/geocoder/v1/?location=31,121"));
        assertNull(cities.resolveCity(120D, 30D), "same cold key has no waiting duplicate");
        assertNull(cities.resolveCity(122D, 32D), "distinct key has no extra provider slot");
        for (int i = 0; i < 16; i++) assertNull(otherCities.resolveCity(123D + i / 1000D, 33D), "a different cache/work owner cannot bypass the physical map budget");
        assertEquals(4, mapRequests.get()); assertEquals(4, mapActive.get());
        assertEquals(4, aiRequests.get() - 1, "one healthy baseline POST and exactly four fault POSTs");
    }
    void finishFault() throws Exception {
        // Complete reading, but keep disconnect blocked: map budget is physical close, not caller timeout.
        mapClock.addAndGet(TimeUnit.SECONDS.toNanos(11)); releaseMap.countDown();
        assertTrue(mapClosed.await(5, TimeUnit.SECONDS));
        assertNull(map.get("https://apis.map.qq.com/ws/geocoder/v1/?location=32,122")); assertEquals(4, mapActive.get());
        releaseDisconnect.countDown(); assertTrue(mapsExited.await(5, TimeUnit.SECONDS));
        for (Future<String> result : maps) assertNull(result.get(5, TimeUnit.SECONDS), "expired body must not be a city success/cache entry");
        assertEquals(0, mapActive.get()); assertEquals(4, mapPeak.get()); assertEquals(4, mapRequests.get());
        releaseAi.countDown(); releaseCleanup.countDown();
        for (Future<?> future : ownedCleanup) future.get(8, TimeUnit.SECONDS);
        awaitIdle();
        assertTrue(aiServerExited.await(8, TimeUnit.SECONDS), "owned local provider handlers must exit too");
        fault = false; faultImage = null;
        assertEquals("杭州市", cities.resolveCity(120D, 30D), "original failed coordinate is retried only by explicit new read after exit");
        assertEquals("杭州市", cities.resolveCity(120D, 30D)); assertEquals(5, mapRequests.get(), "only actual success was cached");
        try (var a = admission.acquire(origins.get(0)); var b = admission.acquire(origins.get(0));
             var c = admission.acquire(origins.get(1)); var d = admission.acquire(origins.get(1))) { assertNotNull(d); }
        assertTrue(providerFailures.isEmpty(), "local provider protocol failures: " + providerFailures.stream().map(e -> e.getClass().getSimpleName()).toList());
    }
    private void awaitIdle() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (http.getActiveCount() != 0 || !http.getQueue().isEmpty() || cleanup.getActiveCount() != 0 || !cleanup.getQueue().isEmpty()) {
            assertTrue(System.nanoTime() - deadline < 0, "physical HTTP/cleanup workers did not exit"); Thread.sleep(10);
        }
    }
    static void awaitUninterruptibly(CountDownLatch latch, AtomicInteger interrupts) {
        boolean interrupted = false; long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            try { assertTrue(latch.await(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS), "owned fault barrier expired"); break; }
            catch (InterruptedException e) { interrupted = true; if (interrupts != null) interrupts.incrementAndGet(); }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    final class MapClient extends TencentMapClient {
        @Override protected long nanoTime() { return mapClock.get(); }
        @Override protected HttpURLConnection openConnection(URL target) {
            assertEquals("apis.map.qq.com", target.getHost()); assertEquals("https", target.getProtocol());
            assertTrue(target.getQuery().contains("key=fixture-map"));
            int request = mapRequests.incrementAndGet(); boolean blocked = fault && request <= 4;
            if (blocked) { int now = mapActive.incrementAndGet(); mapPeak.accumulateAndGet(now, Math::max); }
            return new HttpURLConnection(target) {
                @Override public int getResponseCode() { return 200; }
                @Override public long getContentLengthLong() { return -1; }
                @Override public InputStream getInputStream() {
                    byte[] city = "{\"status\":0,\"result\":{\"address_component\":{\"city\":\"杭州市\"}}}".getBytes(StandardCharsets.UTF_8);
                    return new ByteArrayInputStream(city) {
                        boolean first = true;
                        @Override public synchronized int read(byte[] b, int off, int len) {
                            if (first && blocked) { first = false; mapEntered.countDown(); awaitUninterruptibly(releaseMap, mapInterrupts); }
                            return super.read(b, off, len);
                        }
                    };
                }
                @Override public void disconnect() {
                    if (blocked) { mapClosed.countDown(); awaitUninterruptibly(releaseDisconnect, mapInterrupts); mapActive.decrementAndGet(); }
                }
                @Override public boolean usingProxy() { return false; }
                @Override public void connect() { }
            };
        }
    }
    @Override public void close() throws Exception {
        releaseMap.countDown(); releaseDisconnect.countDown(); releaseAi.countDown(); releaseCleanup.countDown();
        if (cleanupArmed) cleanup.setCorePoolSize(originalCore);
        List<Throwable> failures = new ArrayList<>();
        try { for (Future<?> task : ownedCleanup) task.get(10, TimeUnit.SECONDS); for (Future<?> task : maps) task.get(10, TimeUnit.SECONDS); awaitIdle(); }
        catch (Throwable failure) { failures.add(failure); }
        servers.forEach(server -> server.stop(0)); callers.shutdownNow(); providers.shutdownNow();
        try { assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS)); assertTrue(providers.awaitTermination(10, TimeUnit.SECONDS)); }
        catch (Throwable failure) { failures.add(failure); }
        if (callers.isTerminated() && providers.isTerminated()) faultImage = null;
        failures.addAll(providerFailures);
        if (!failures.isEmpty()) { Exception error = new Exception("owned transport cleanup failed"); failures.forEach(error::addSuppressed); throw error; }
    }
}
