package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.MapPublicLayerMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MapPublicLayersTest {
    static Map<String, Object> body(List<String> layers, int limit) {
        Map<String, Object> b = new HashMap<>();
        b.put("datum", "GCJ-02"); b.put("minLat", 31.1); b.put("maxLat", 31.3); b.put("minLng", 121.3); b.put("maxLng", 121.6);
        b.put("limit", limit); b.put("layers", layers);
        return b;
    }

    @SuppressWarnings("unchecked")
    @Test void storesAndActivitiesProjectWithServerStateAndOthersUnavailable() {
        MapPublicLayerMapper m = mock(MapPublicLayerMapper.class);
        when(m.selectStores(any(), any(), any(), any(), eq(0L), eq(2))).thenReturn(List.of(
                new HashMap<>(Map.of("id", 5L, "name", "夜猫书店", "lat", new BigDecimal("31.2000000"), "lng", new BigDecimal("121.5000000"), "businessStatus", 0, "versionTime", LocalDateTime.of(2026, 11, 1, 8, 0)))));
        when(m.selectActivities(any(), any(), any(), any(), eq(0L), eq(2))).thenReturn(List.of(
                new HashMap<>(Map.of("id", 7L, "name", "城市夜跑", "lat", "31.21", "lng", "121.51", "startDate", LocalDateTime.of(2026, 12, 1, 9, 0), "versionTime", LocalDateTime.of(2026, 11, 2, 9, 30)))));
        Map<String, Object> out = new MapPublicLayers(m).read(body(List.of("store", "activity", "theme"), 2), LocalDateTime.of(2026, 11, 13, 12, 0));
        List<Map<String, Object>> pages = (List<Map<String, Object>>) out.get("layers");
        Map<String, Object> store = ((List<Map<String, Object>>) pages.get(0).get("items")).get(0);
        assertEquals("closed", store.get("visualState")); assertEquals("31.2", store.get("latitude")); assertEquals(List.of("navigate"), store.get("actions"));
        assertEquals("2026-11-01T08:00:00", store.get("visibilityVersion"));
        Map<String, Object> activity = ((List<Map<String, Object>>) pages.get(1).get("items")).get(0);
        assertEquals("notStarted", activity.get("visualState")); assertEquals(List.of(), activity.get("actions"));
        assertEquals("UNAVAILABLE", pages.get(2).get("status")); assertEquals(List.of(), pages.get(2).get("items"));
        assertNull(out.get("nextCursor"), "两层都没满页");
    }

    @Test void fullPageYieldsCursorAndExhaustedLayersStayEmpty() {
        MapPublicLayerMapper m = mock(MapPublicLayerMapper.class);
        when(m.selectStores(any(), any(), any(), any(), anyLong(), eq(1))).thenReturn(List.of(
                new HashMap<>(Map.of("id", 9L, "name", "店", "lat", "31.2", "lng", "121.5", "businessStatus", 1, "versionTime", LocalDateTime.of(2026, 11, 1, 8, 0)))));
        when(m.selectActivities(any(), any(), any(), any(), anyLong(), eq(1))).thenReturn(List.of());
        Map<String, Object> first = new MapPublicLayers(m).read(body(List.of("store", "activity"), 1), LocalDateTime.now());
        String cursor = (String) first.get("nextCursor");
        assertNotNull(cursor);
        Map<String, Long> decoded = MapPublicLayers.cursor(cursor);
        assertEquals(9L, decoded.get("store")); assertEquals(MapPublicLayers.EXHAUSTED, decoded.get("activity"));
    }

    @Test void rejectsWrongDatumHugeAreaAndUnknownLayer() {
        MapPublicLayers l = new MapPublicLayers(mock(MapPublicLayerMapper.class));
        Map<String, Object> b = body(List.of("store"), 10); b.put("datum", "WGS-84");
        assertThrows(ServiceException.class, () -> l.read(b, LocalDateTime.now()));
        Map<String, Object> big = body(List.of("store"), 10); big.put("maxLat", 33.0); big.put("maxLng", 123.0);
        assertThrows(ServiceException.class, () -> l.read(big, LocalDateTime.now()));
        assertThrows(ServiceException.class, () -> l.read(body(List.of("parking"), 10), LocalDateTime.now()));
        Map<String, Object> agg = body(List.of("store"), 10); agg.put("aggregatedQueryEnabled", true);
        assertThrows(ServiceException.class, () -> l.read(agg, LocalDateTime.now()));
    }
}
