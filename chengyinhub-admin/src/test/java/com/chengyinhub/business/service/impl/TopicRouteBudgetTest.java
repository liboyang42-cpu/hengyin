package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.TencentMapClient;
import com.chengyinhub.business.service.support.TopicSizeLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Route owner budget and result isolation; provider doubles never perform network or authorization. */
class TopicRouteBudgetTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void sameAndDifferentColdKeysCannotQueueOrExceedFourBuilders(boolean cacheEnabled) throws Exception {
        TencentMapClient client = readyClient();
        CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
        when(client.get(anyString())).thenAnswer(call -> {
            String url = call.getArgument(0);
            if (url.contains("from=30.")) {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return response(url);
        });
        TopicRouteServiceImpl service = configure(new TopicRouteServiceImpl(cacheEnabled, 4096, 4096, 300), client);
        List<CmsTopicNode> warm = nodes(31.0, 31.1);
        String warmResult = service.buildGeometryJson(warm);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        List<Future<String>> running = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                final double from = 30.1 + i / 10D;
                running.add(workers.submit(() -> service.buildGeometryJson(nodes(from, from + 0.1))));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(4, flights(service).size());
            assertNull(service.buildGeometryJson(nodes(30.1, 30.2)), "duplicate cold key must not wait or dispatch");
            for (int i = 0; i < 32; i++) assertNull(service.buildGeometryJson(nodes(32 + i / 100D, 33 + i / 100D)));
            if (cacheEnabled) assertEquals(warmResult, service.buildGeometryJson(warm));
            else assertNull(service.buildGeometryJson(warm), "disabled retention must not disable admission");
            verify(client, times(5)).get(anyString());
            release.countDown();
            for (Future<String> result : running) assertEquals(2, JSONArray.parseArray(result.get(5, TimeUnit.SECONDS)).size());
            assertTrue(flights(service).isEmpty());
            assertNotNull(service.buildGeometryJson(nodes(30.1, 30.2)));
            verify(client, times(cacheEnabled ? 5 : 6)).get(anyString());
            Object cache = ReflectionTestUtils.getField(service, "cache");
            synchronized (cache) {
                long retained = (Long) ReflectionTestUtils.getField(cache, "retainedBytes");
                Map<?, ?> entries = (Map<?, ?>) ReflectionTestUtils.getField(cache, "entries");
                assertTrue(retained >= 0 && retained <= 4096);
                assertTrue(entries.size() <= 4096 / 256);
                if (!cacheEnabled) { assertEquals(0L, retained); assertTrue(entries.isEmpty()); }
            }
        } finally {
            release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void builderFailureDoesNotLeaveAKeyOrPermitBehind() {
        TencentMapClient client = readyClient();
        when(client.get(anyString())).thenThrow(new AssertionError("controlled worker exit"))
                .thenAnswer(call -> response(call.getArgument(0)));
        TopicRouteServiceImpl service = configure(new TopicRouteServiceImpl(), client);
        assertThrows(AssertionError.class, () -> service.buildGeometryJson(nodes(30.1, 30.2)));
        assertTrue(flights(service).isEmpty());
        assertEquals(2, JSONArray.parseArray(service.buildGeometryJson(nodes(30.1, 30.2))).size());
        verify(client, times(2)).get(anyString());
    }

    @Test void existingCacheCeilingsCannotBeRaisedOrOverflowedEvenWhenDisabled() {
        for (boolean enabled : List.of(true, false)) {
            assertThrows(IllegalArgumentException.class, () -> new TopicRouteServiceImpl(enabled,
                    TopicRouteServiceImpl.MAX_CACHE_BYTES + 1, 1024, 300));
            assertThrows(IllegalArgumentException.class, () -> new TopicRouteServiceImpl(enabled,
                    1024, TopicRouteServiceImpl.MAX_ENTRY_BYTES + 1, 300));
            assertThrows(IllegalArgumentException.class, () -> new TopicRouteServiceImpl(enabled, 1024, 1024, 301));
            assertThrows(IllegalArgumentException.class, () -> new TopicRouteServiceImpl(enabled, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
        }
        assertThrows(IllegalArgumentException.class, () -> new MapResponseCache(
                Long.MAX_VALUE, 1024, 100, () -> 0));
    }

    @Test void oversizedOrNonFiniteInputAndInvalidModeMakeNoProviderRequest() {
        TencentMapClient client = readyClient();
        TopicRouteServiceImpl service = configure(new TopicRouteServiceImpl(), client);
        List<CmsTopicNode> huge = new ArrayList<>();
        for (int i = 0; i <= TopicSizeLimits.MAX_NODES; i++) huge.add(node(30.1, 120.1));
        assertNull(service.buildGeometryJson(huge));
        for (String invalid : List.of("NaN", "Infinity", "91", "1".repeat(65))) {
            CmsTopicNode n = node(30.1, 120.1); n.setLatitude(invalid);
            assertNull(service.buildGeometryJson(List.of(n, node(30.2, 120.2))));
        }
        ReflectionTestUtils.setField(service, "mode", "walking/" + "x".repeat(9000));
        assertNull(service.buildGeometryJson(nodes(30.1, 30.2)));
        verify(client, never()).get(anyString());
    }

    @Test void exactInputAndDecodedPointLimitsRemainUsable() {
        TencentMapClient client = readyClient();
        when(client.get(anyString())).thenAnswer(call -> response(call.getArgument(0)));
        TopicRouteServiceImpl service = configure(new TopicRouteServiceImpl() {
            @Override protected long nanoTime() { return 0; }
        }, client);
        List<CmsTopicNode> maximumInput = new ArrayList<>();
        for (int i = 0; i < TopicSizeLimits.MAX_NODES; i++) maximumInput.add(node(30 + i / 10000D, 120.1));
        String first = service.buildGeometryJson(maximumInput);
        assertEquals(TopicSizeLimits.MAX_NODES, JSONArray.parseArray(first).size());
        assertEquals(first, service.buildGeometryJson(maximumInput));
        verify(client, times(TopicSizeLimits.MAX_NODES - 1)).get(anyString());

        TencentMapClient dense = readyClient();
        when(dense.get(anyString())).thenReturn("{\"status\":0,\"result\":{\"routes\":[{\"polyline\":"
                + compressed(TopicRouteServiceImpl.MAX_GEOMETRY_POINTS) + "}]}}");
        TopicRouteServiceImpl denseService = configure(new TopicRouteServiceImpl() {
            @Override protected long nanoTime() { return 0; }
        }, dense);
        assertEquals(TopicRouteServiceImpl.MAX_GEOMETRY_POINTS,
                JSONArray.parseArray(denseService.buildGeometryJson(nodes(30.1, 30.2))).size());
        verify(dense, times(1)).get(anyString());
    }

    @Test void rejectedProviderShapesNeverEnterCacheAndRecoveryStillWorks() {
        for (String poly : List.of("[30.1,120.1,1,1,1]", "[30.1,120.1,null,1]", "[91,120,0,0]",
                compressed(TopicRouteServiceImpl.MAX_GEOMETRY_POINTS + 1))) {
            TencentMapClient client = readyClient();
            when(client.get(anyString())).thenReturn("{\"status\":0,\"result\":{\"routes\":[{\"polyline\":" + poly + "}]}}")
                    .thenAnswer(call -> response(call.getArgument(0)));
            TopicRouteServiceImpl service = configure(new TopicRouteServiceImpl(), client);
            assertNull(service.buildGeometryJson(nodes(30.1, 30.2)));
            assertCacheEmpty(service);
            String recovered = service.buildGeometryJson(nodes(30.1, 30.2));
            assertEquals(2, JSONArray.parseArray(recovered).size());
            assertEquals(recovered, service.buildGeometryJson(nodes(30.1, 30.2)));
            verify(client, times(2)).get(anyString());
        }
    }

    @Test void combinedSegmentPointBudgetRejectsWholeResultWithoutCachingPartialGeometry() {
        TencentMapClient client = readyClient();
        String large = "{\"status\":0,\"result\":{\"routes\":[{\"polyline\":" + compressed(3000) + "}]}}";
        when(client.get(anyString())).thenReturn(large, large).thenAnswer(call -> response(call.getArgument(0)));
        TopicRouteServiceImpl service = configure(new TopicRouteServiceImpl(), client);
        List<CmsTopicNode> nodes = List.of(node(30.1, 120.1), node(30.2, 120.2), node(30.3, 120.3));
        assertNull(service.buildGeometryJson(nodes));
        assertCacheEmpty(service);
        String recovered = service.buildGeometryJson(nodes);
        assertEquals(3, JSONArray.parseArray(recovered).size());
        assertEquals(recovered, service.buildGeometryJson(nodes));
        verify(client, times(4)).get(anyString());
    }

    @Test void elapsedBuildBudgetStopsRemainingSegmentsAndDoesNotReleaseCacheIdentityAsSuccess() {
        AtomicLong now = new AtomicLong(); AtomicBoolean first = new AtomicBoolean(true);
        TencentMapClient client = readyClient();
        when(client.get(anyString())).thenAnswer(call -> {
            if (first.getAndSet(false)) now.set(TimeUnit.SECONDS.toNanos(10));
            return response(call.getArgument(0));
        });
        TopicRouteServiceImpl service = configure(new TopicRouteServiceImpl() {
            @Override protected long nanoTime() { return now.get(); }
        }, client);
        List<CmsTopicNode> nodes = List.of(node(30.1, 120.1), node(30.2, 120.2), node(30.3, 120.3));
        assertNull(service.buildGeometryJson(nodes));
        verify(client, times(1)).get(anyString());
        assertCacheEmpty(service); assertTrue(flights(service).isEmpty());
        now.set(0);
        String recovered = service.buildGeometryJson(nodes);
        assertEquals(3, JSONArray.parseArray(recovered).size());
        assertEquals(recovered, service.buildGeometryJson(nodes));
        verify(client, times(3)).get(anyString());
    }

    @Test void orderedCoordinateKeyCannotServeAFullRouteToADifferentVisibleSubset() {
        TencentMapClient client = readyClient();
        when(client.get(anyString())).thenAnswer(call -> response(call.getArgument(0)));
        TopicRouteServiceImpl service = configure(new TopicRouteServiceImpl(), client);
        CmsTopicNode a = node(30.1, 120.1), b = node(30.2, 120.2), c = node(30.3, 120.3);
        a.setId(1L); a.setName("owner-only metadata"); a.setTopicId(99L);
        String full = service.buildGeometryJson(List.of(a, b, c));
        String subset = service.buildGeometryJson(List.of(a, c));
        assertEquals(3, JSONArray.parseArray(full).size());
        JSONArray visible = JSONArray.parseArray(subset);
        assertEquals(2, visible.size());
        assertEquals(30.1, visible.getJSONObject(0).getDoubleValue("latitude"), 1e-7);
        assertEquals(30.3, visible.getJSONObject(1).getDoubleValue("latitude"), 1e-7);
        assertFalse(subset.contains("owner-only metadata"));
        assertEquals(Set.of("latitude", "longitude"), visible.getJSONObject(0).keySet());
        assertEquals(subset, service.buildGeometryJson(List.of(node(30.1, 120.1), node(30.3, 120.3))));
        verify(client, times(3)).get(anyString());
    }

    @Test void invalidIntermediateNodeCannotReuseAValidSubsetCacheOrClaimProviderAvailability() {
        TencentMapClient client = readyClient();
        when(client.get(anyString())).thenAnswer(call -> response(call.getArgument(0)));
        TopicRouteServiceImpl service = configure(new TopicRouteServiceImpl(), client);
        CmsTopicNode a = node(30.1, 120.1), c = node(30.3, 120.3);
        String cached = service.buildGeometryJson(List.of(a, c));
        assertNotNull(cached);
        List<CmsTopicNode> invalids = new ArrayList<>();
        invalids.add(null);
        for (String latitude : List.of("NaN", "Infinity", "91", "-91", "", "1".repeat(65))) {
            CmsTopicNode bad = node(30.2, 120.2); bad.setLatitude(latitude); invalids.add(bad);
        }
        CmsTopicNode missing = node(30.2, 120.2); missing.setLatitude(null); invalids.add(missing);
        CmsTopicNode badLongitude = node(30.2, 120.2); badLongitude.setLongitude("NaN"); invalids.add(badLongitude);
        for (CmsTopicNode bad : invalids) {
            var chapter = new com.chengyinhub.business.domain.vo.TopicChapterVO();
            chapter.setRouteGeometry(service.buildGeometryJson(java.util.Arrays.asList(a, bad, c)));
            assertNull(chapter.getRouteGeometry());
            assertEquals(Boolean.FALSE, chapter.getRouteGeometryAvailable());
            assertEquals("MAP_ROUTE_UNAVAILABLE", chapter.getRouteGeometryErrorCode());
            assertEquals(cached, service.buildGeometryJson(List.of(a, c)), "rejecting a malformed route cannot poison a valid cache entry");
        }
        verify(client, times(1)).get(anyString());
    }

    private void assertCacheEmpty(TopicRouteServiceImpl service) {
        Object cache = ReflectionTestUtils.getField(service, "cache");
        synchronized (cache) {
            assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(cache, "entries")).isEmpty());
            assertEquals(0L, ReflectionTestUtils.getField(cache, "retainedBytes"));
        }
    }

    private Set<?> flights(TopicRouteServiceImpl service) {
        return (Set<?>) ReflectionTestUtils.getField(service, "inFlight");
    }

    private TencentMapClient readyClient() {
        TencentMapClient client = mock(TencentMapClient.class);
        when(client.isReady()).thenReturn(true);
        return client;
    }

    private TopicRouteServiceImpl configure(TopicRouteServiceImpl service, TencentMapClient client) {
        ReflectionTestUtils.setField(service, "tencentMapClient", client);
        ReflectionTestUtils.setField(service, "mode", "walking");
        return service;
    }

    private List<CmsTopicNode> nodes(double from, double to) { return List.of(node(from, 120.1), node(to, 120.2)); }

    private CmsTopicNode node(double lat, double lng) {
        CmsTopicNode node = new CmsTopicNode(); node.setLatitude(Double.toString(lat)); node.setLongitude(Double.toString(lng)); return node;
    }

    private String response(String url) {
        String[] fields = url.substring(url.indexOf('?') + 1).split("&");
        String[] from = fields[0].substring("from=".length()).split(",");
        String[] to = fields[1].substring("to=".length()).split(",");
        JSONArray poly = new JSONArray();
        poly.add(Double.parseDouble(from[0])); poly.add(Double.parseDouble(from[1]));
        poly.add((Double.parseDouble(to[0]) - Double.parseDouble(from[0])) * 1000000D);
        poly.add((Double.parseDouble(to[1]) - Double.parseDouble(from[1])) * 1000000D);
        return "{\"status\":0,\"result\":{\"routes\":[{\"polyline\":" + poly + "}]}}";
    }

    private String compressed(int points) {
        JSONArray poly = new JSONArray(); poly.add(30.1); poly.add(120.1);
        for (int i = 1; i < points; i++) { poly.add(1); poly.add(1); }
        return poly.toJSONString();
    }
}
