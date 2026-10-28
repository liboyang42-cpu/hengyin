package com.chengyinhub.business.it;

import com.chengyinhub.business.service.TencentMapClient;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.business.service.impl.CrossDomainSubscriptionProvider;
import com.chengyinhub.business.service.impl.MapGeocodeServiceImpl;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed loopback fault responses through the existing client seams, never external providers. */
final class CombinedControlledProviders implements AutoCloseable {
    enum Mode { DISCONNECT, BAD_RESPONSE, NORMAL, HOLD_MAP }
    private volatile Mode mode = Mode.NORMAL;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), new ThreadPoolExecutor.AbortPolicy());
    private final HttpServer server;
    private final AiHttpAdmission admission;
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    final AtomicInteger ai = new AtomicInteger(), map = new AtomicInteger(), notice = new AtomicInteger();
    final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
    final CountDownLatch mapEntered = new CountDownLatch(4), releaseMap = new CountDownLatch(1);
    volatile CountDownLatch failureEntered = new CountDownLatch(0), releaseFailure = new CountDownLatch(0);
    private Object originalNotifier;
    private CrossDomainStorageFixture store;

    CombinedControlledProviders() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4); server.setExecutor(workers);
        for (String route : List.of("/chat/completions", "/map", "/subscribe")) server.createContext(route, exchange -> {
            Mode observed = mode; int current = active.incrementAndGet(); peak.accumulateAndGet(current, Math::max);
            try {
                String path = exchange.getRequestURI().getPath();
                if (!path.equals("/map")) assertTrue(exchange.getRequestHeaders().getFirst("Content-Type").contains("application/json"));
                if (path.equals("/chat/completions")) {
                    ai.incrementAndGet(); assertEquals("POST", exchange.getRequestMethod());
                    assertEquals("Bearer fixture-token", exchange.getRequestHeaders().getFirst("Authorization"));
                    CrossDomainTransportStage.validateRequest(exchange.getRequestBody());
                } else if (path.equals("/subscribe")) {
                    notice.incrementAndGet(); assertEquals("POST", exchange.getRequestMethod());
                    try (var input = exchange.getRequestBody()) {
                        assertTrue(input.readNBytes(16 * 1024 + 1).length <= 16 * 1024);
                    }
                } else {
                    map.incrementAndGet(); assertEquals("GET", exchange.getRequestMethod());
                    String query = exchange.getRequestURI().getQuery();
                    assertNotNull(query); assertTrue(query.startsWith("location=")); assertTrue(query.endsWith("&key=fixture-map"));
                    if (observed == Mode.HOLD_MAP) { mapEntered.countDown(); assertTrue(releaseMap.await(10, TimeUnit.SECONDS)); }
                }
                if (observed == Mode.DISCONNECT || observed == Mode.BAD_RESPONSE) {
                    failureEntered.countDown(); assertTrue(releaseFailure.await(5, TimeUnit.SECONDS));
                }
                if (observed == Mode.DISCONNECT) return; // Accepted physical request, no response; never NOT_SENT.
                String body = observed == Mode.BAD_RESPONSE ? "{invalid-controlled-json"
                        : path.equals("/map") ? "{\"status\":0,\"result\":{\"address_component\":{\"city\":\"杭州市\"}}}"
                        : path.equals("/subscribe") ? "{\"errcode\":0,\"errmsg\":\"ok\"}"
                        : "{\"choices\":[{\"message\":{\"content\":\"{\\\"pass\\\":true,\\\"confidence\\\":91,\\\"reason\\\":\\\"controlled\\\"}\"}}]}";
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (Throwable failure) { failures.add(failure); }
            finally { exchange.close(); active.decrementAndGet(); }
        });
        server.start(); admission = new AiHttpAdmission(List.of(origin()));
    }
    String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private void awaitIdle() throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (active.get() != 0) { assertTrue(System.nanoTime() < end); Thread.sleep(5); }
    }
    void mode(Mode next) throws InterruptedException {
        awaitIdle();
        if (next == Mode.DISCONNECT || next == Mode.BAD_RESPONSE) {
            failureEntered = new CountDownLatch(3); releaseFailure = new CountDownLatch(1);
        }
        mode = next;
    }
    OpenAiCompatibleLlmClient aiClient() {
        return new OpenAiCompatibleLlmClient("controlled-combination", origin(), "fixture-token", "fixture-model", true, 2000, admission);
    }
    MapGeocodeServiceImpl cities() {
        var mapClient = new TencentMapClient() {
            @Override protected HttpURLConnection openConnection(URL original) throws IOException {
                assertEquals("https", original.getProtocol()); assertEquals("apis.map.qq.com", original.getHost());
                assertEquals("/ws/geocoder/v1/", original.getPath()); assertTrue(original.getQuery().contains("key=fixture-map"));
                return (HttpURLConnection) new URL(origin() + "/map?" + original.getQuery()).openConnection(Proxy.NO_PROXY);
            }
        };
        ReflectionTestUtils.setField(mapClient, "enabled", true); ReflectionTestUtils.setField(mapClient, "mapKey", "fixture-map");
        var cities = new MapGeocodeServiceImpl(); ReflectionTestUtils.setField(cities, "tencentMapClient", mapClient); return cities;
    }
    void attachNotice(CrossDomainStorageFixture storage) {
        assertNull(store); store = storage;
        originalNotifier = ReflectionTestUtils.getField(store.notifications, "wxSubscribeMsgService"); assertNotNull(originalNotifier);
        var provider = new CrossDomainSubscriptionProvider(origin() + "/subscribe", new AtomicInteger());
        for (String field : List.of("umsMemberService", "wxSec", "tplOfficialEvent", "defaultPage"))
            ReflectionTestUtils.setField(provider, field, ReflectionTestUtils.getField(originalNotifier, field));
        ReflectionTestUtils.setField(store.notifications, "wxSubscribeMsgService", provider);
    }
    Map<String,Object> observation() throws InterruptedException {
        awaitIdle();
        return Map.of("aiPosts", ai.get(), "mapGets", map.get(), "noticePosts", notice.get(),
                "activeHandlers", active.get(), "peakHandlers", peak.get(), "workerLimit", 4, "queueLimit", 4);
    }
    @Override public void close() throws Exception {
        releaseMap.countDown();
        releaseFailure.countDown();
        if (store != null) ReflectionTestUtils.setField(store.notifications, "wxSubscribeMsgService", originalNotifier);
        try { awaitIdle(); } catch (Throwable failure) { failures.add(failure); }
        try { server.stop(0); } catch (Throwable failure) { failures.add(failure); }
        workers.shutdownNow();
        try { assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); assertEquals(0, active.get()); }
        catch (Throwable failure) { failures.add(failure); }
        if (!failures.isEmpty()) { Exception error = new Exception("controlled combination provider cleanup failed"); failures.forEach(error::addSuppressed); throw error; }
    }
}
