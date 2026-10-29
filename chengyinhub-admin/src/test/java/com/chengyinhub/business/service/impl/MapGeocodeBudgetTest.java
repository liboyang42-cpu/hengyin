package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.TencentMapClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real city cache/work admission, provider doubles only; no network or location permission acceptance. */
class MapGeocodeBudgetTest {
    @Test void distinctCityFloodIsBoundedByBytesAndDerivedItemCountWithLruRecovery() {
        Controlled service = ready(new Controlled());
        int requests = (int) (MapGeocodeServiceImpl.CITY_CACHE_BYTES / 256) + 1;
        for (int i = 0; i < requests; i++) {
            double longitude = 120 + i / 1000D;
            assertEquals(cityName(longitude, 30), service.resolveCity(longitude, 30));
            assertBounded(service);
        }
        assertEquals(requests, service.requests.get());
        double latest = 120 + (requests - 1) / 1000D;
        assertEquals(cityName(latest, 30), service.resolveCity(latest, 30));
        assertEquals(requests, service.requests.get());
        assertEquals(cityName(120, 30), service.resolveCity(120, 30));
        assertEquals(requests + 1, service.requests.get(), "an evicted city can be recomputed without poisoning other entries");
        assertBounded(service);
    }

    @Test void oversizedCityIsUnavailableNotRetainedAndOriginalKeyCanRecover() {
        Controlled service = ready(new Controlled());
        service.reply = url -> city("x".repeat((int) MapGeocodeServiceImpl.CITY_ENTRY_BYTES));
        assertNull(service.resolveCity(120.1, 30.1));
        assertCacheEmpty(service);
        service.reply = url -> city("杭州市");
        assertEquals("杭州市", service.resolveCity(120.1, 30.1));
        assertEquals("杭州市", service.resolveCity(120.1, 30.1));
        assertEquals(2, service.requests.get());
        assertBounded(service);
    }

    @Test void cityTtlUsesOriginalMonotonicWriteTimeAcrossWrapAndHitsDoNotExtendIt() {
        Controlled service = ready(new Controlled());
        service.now.set(Long.MAX_VALUE - 50);
        AtomicInteger cities = new AtomicInteger();
        service.reply = url -> city("city-" + cities.incrementAndGet());
        assertEquals("city-1", service.resolveCity(120.15514, 30.27411));
        service.now.addAndGet(TimeUnit.MINUTES.toNanos(5) - 1);
        assertEquals("city-1", service.resolveCity(120.15518, 30.27418), "preserve the original coordinate grid");
        service.now.incrementAndGet();
        assertEquals("city-2", service.resolveCity(120.15514, 30.27411));
        assertEquals(2, service.requests.get());
        assertBounded(service);
    }

    @Test void reverseAndForwardShareFourKeysAndColdDuplicatesNeverCreateWaiters() throws Exception {
        Controlled service = ready(new Controlled());
        String warm = service.resolveCity(119, 29);
        CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
        service.reply = url -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            return url.contains("address=") ? forward() : city("cached city");
        };
        ExecutorService workers = Executors.newFixedThreadPool(4);
        List<Future<?>> calls = new ArrayList<>();
        try {
            calls.add(workers.submit(() -> service.resolveCity(120.1, 30.1)));
            calls.add(workers.submit(() -> service.resolveCity(120.2, 30.2)));
            calls.add(workers.submit(() -> service.suggestCoords("street-a", "city")));
            calls.add(workers.submit(() -> service.suggestCoords("street-b", "city")));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(4, flights(service).size());
            assertNull(service.resolveCity(120.1, 30.1));
            assertNull(service.suggestCoords("street-a", "city"));
            for (int i = 0; i < 32; i++) {
                assertNull(service.resolveCity(121 + i / 1000D, 31));
                assertNull(service.suggestCoords("other-street-" + i, "city"));
            }
            assertEquals(warm, service.resolveCity(119, 29), "a hit must not wait for provider work");
            assertEquals(5, service.requests.get());
            release.countDown();
            for (Future<?> call : calls) assertNotNull(call.get(5, TimeUnit.SECONDS));
            assertTrue(flights(service).isEmpty());
            assertNotNull(service.suggestCoords("after-release", "city"));
            assertEquals(6, service.requests.get());
            assertBounded(service);
        } finally {
            release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void rejectedUnknownAndFatalResponsesReleaseOriginalWorkKeyWithoutNegativeCaching() {
        for (String rejected : List.of("broken-json", "{\"status\":348}", "{\"status\":0,\"result\":{}}",
                "x".repeat(TencentMapClient.MAX_RESPONSE_BYTES + 1))) {
            Controlled service = ready(new Controlled()); service.reply = url -> rejected;
            assertNull(service.resolveCity(120, 30));
            assertCacheEmpty(service); assertTrue(flights(service).isEmpty());
            service.reply = url -> city("recovered");
            assertEquals("recovered", service.resolveCity(120, 30));
            assertEquals("recovered", service.resolveCity(120, 30));
            assertEquals(2, service.requests.get());
        }
        Controlled service = ready(new Controlled());
        service.reply = url -> { throw new AssertionError("controlled worker exit"); };
        assertThrows(AssertionError.class, () -> service.resolveCity(120, 30));
        assertTrue(flights(service).isEmpty());
        service.reply = url -> city("recovered");
        assertEquals("recovered", service.resolveCity(120, 30));
    }

    @Test void forwardEncodingInputIsBoundedBeforeAllocationWithoutChangingThresholdOrCoordinateOrder() {
        Controlled service = ready(new Controlled()); service.reply = url -> forward();
        assertNull(service.suggestCoords("a".repeat(513), "city"));
        assertNull(service.suggestCoords("street", "r".repeat(129)));
        assertEquals(0, service.requests.get());
        double[] coordinates = service.suggestCoords("街".repeat(512), "市".repeat(128));
        assertArrayEquals(new double[]{120.1, 30.2}, coordinates, 1e-7);
        assertEquals(1, service.requests.get());
        assertTrue(service.lastUrl.contains("address=")); assertTrue(service.lastUrl.contains("region="));
        assertTrue(service.lastUrl.length() < 8192 - 512 - 5);
        assertTrue(flights(service).isEmpty());
    }

    private void assertBounded(Controlled service) {
        Object cache = ReflectionTestUtils.getField(service, "cityCache");
        synchronized (cache) {
            Map<?, ?> entries = (Map<?, ?>) ReflectionTestUtils.getField(cache, "entries");
            long bytes = (Long) ReflectionTestUtils.getField(cache, "retainedBytes"), counted = 0;
            assertTrue(bytes >= 0 && bytes <= MapGeocodeServiceImpl.CITY_CACHE_BYTES);
            assertTrue(entries.size() <= MapGeocodeServiceImpl.CITY_CACHE_BYTES / 256);
            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                String value = (String) ReflectionTestUtils.getField(entry.getValue(), "json");
                long weight = 256L + 2L * (((String) entry.getKey()).length() + value.length());
                assertTrue(weight <= MapGeocodeServiceImpl.CITY_ENTRY_BYTES);
                counted += weight;
            }
            assertEquals(counted, bytes);
        }
    }

    private void assertCacheEmpty(Controlled service) {
        Object cache = ReflectionTestUtils.getField(service, "cityCache");
        synchronized (cache) {
            assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(cache, "entries")).isEmpty());
            assertEquals(0L, ReflectionTestUtils.getField(cache, "retainedBytes"));
        }
    }

    private Set<?> flights(Controlled service) { return (Set<?>) ReflectionTestUtils.getField(service, "inFlight"); }

    private Controlled ready(Controlled service) {
        TencentMapClient client = mock(TencentMapClient.class); when(client.isReady()).thenReturn(true);
        ReflectionTestUtils.setField(service, "tencentMapClient", client); return service;
    }

    private static String city(String name) {
        JSONObject component = new JSONObject(); component.put("city", name);
        JSONObject result = new JSONObject(); result.put("address_component", component);
        JSONObject root = new JSONObject(); root.put("status", 0); root.put("result", result); return root.toJSONString();
    }

    private static String cityName(double lng, double lat) { return "city-" + Math.round(lng * 1000D) + ":" + Math.round(lat * 1000D); }

    private static String forward() { return "{\"status\":0,\"result\":{\"reliability\":7,\"location\":{\"lng\":120.1,\"lat\":30.2}}}"; }

    private static class Controlled extends MapGeocodeServiceImpl {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicLong now = new AtomicLong();
        volatile String lastUrl;
        volatile Function<String, String> reply = url -> {
            String[] coordinates = url.substring(url.indexOf("location=") + 9).split(",");
            return city(cityName(Double.parseDouble(coordinates[1]), Double.parseDouble(coordinates[0])));
        };
        @Override protected String requestGeocode(String url) { lastUrl = url; requests.incrementAndGet(); return reply.apply(url); }
        @Override protected long nanoTime() { return now.get(); }
    }
}
