package com.chengyinhub.business.it;

import com.chengyinhub.business.service.TencentMapClient;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.business.service.impl.MapGeocodeServiceImpl;
import com.chengyinhub.common.utils.http.HttpUtils;
import com.sun.net.httpserver.HttpServer;
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
    static final String LARGE_IMAGE = "data:image/jpeg;base64," + "A".repeat(16 * 1024 * 1024);
    final CountDownLatch aiEntered = new CountDownLatch(4), mapEntered = new CountDownLatch(4);
    final CountDownLatch aiServerExited = new CountDownLatch(4);
    final CountDownLatch releaseAi = new CountDownLatch(1), releaseCleanup = new CountDownLatch(1);
    final CountDownLatch releaseMap = new CountDownLatch(1), mapClosed = new CountDownLatch(4);
    final CountDownLatch releaseDisconnect = new CountDownLatch(1), mapsExited = new CountDownLatch(4);
    final AtomicInteger aiRequests = new AtomicInteger(), mapRequests = new AtomicInteger(), mapActive = new AtomicInteger();
    final AtomicInteger mapPeak = new AtomicInteger(), mapInterrupts = new AtomicInteger();
    final AtomicLong mapClock = new AtomicLong();
    final List<Throwable> providerFailures = new CopyOnWriteArrayList<>();
    final ExecutorService callers = Executors.newFixedThreadPool(8);
    final ExecutorService providers = Executors.newFixedThreadPool(4);
    final List<HttpServer> servers = new ArrayList<>();
    final List<Future<?>> ownedCleanup = new ArrayList<>();
    final List<Future<String>> maps = new ArrayList<>();
    final List<String> origins = new ArrayList<>();
    ThreadPoolExecutor http, cleanup;
    AiHttpAdmission admission;
    boolean fault, cleanupArmed;
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
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(providers); server.createContext("/chat/completions", exchange -> {
                boolean blocked = fault;
                try {
                    assertEquals("POST", exchange.getRequestMethod());
                    assertEquals("Bearer fixture-token", exchange.getRequestHeaders().getFirst("Authorization"));
                    assertTrue(exchange.getRequestHeaders().getFirst("Content-Type").contains("application/json"));
                    aiRequests.incrementAndGet();
                    if (blocked) { aiEntered.countDown(); assertTrue(releaseAi.await(60, TimeUnit.SECONDS)); }
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    var json = com.alibaba.fastjson2.JSONObject.parseObject(new String(body, StandardCharsets.UTF_8));
                    assertEquals("fixture-model", json.getString("model")); assertEquals(2, json.getJSONArray("messages").size());
                    String content = "{\"pass\":true,\"confidence\":91,\"reason\":\"controlled received result\"}";
                    var message = new com.alibaba.fastjson2.JSONObject(); message.put("content", content);
                    var choice = new com.alibaba.fastjson2.JSONObject(); choice.put("message", message);
                    byte[] response = ("{\"choices\":[" + choice.toJSONString() + "]}").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
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
    OpenAiCompatibleLlmClient client(int origin) {
        return new OpenAiCompatibleLlmClient("t19-controlled", origins.get(origin), "fixture-token", "fixture-model", true, 2000, admission);
    }
    String image() { return fault ? LARGE_IMAGE : SMALL_IMAGE; }

    void beginFault() throws Exception {
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
        fault = false;
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
        failures.addAll(providerFailures);
        if (!failures.isEmpty()) { Exception error = new Exception("owned transport cleanup failed"); failures.forEach(error::addSuppressed); throw error; }
    }
}
