package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.seed.DensityQuery;
import com.chengyinhub.business.domain.dto.seed.SeedGeoPoint;
import com.chengyinhub.business.domain.dto.seed.SeedPlace;
import com.chengyinhub.business.mapper.SeedContentMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DistrictDensityServiceTest {
    private SeedContentMapper mapper;
    private DistrictDensityService service;

    @BeforeEach void setUp() {
        mapper = mock(SeedContentMapper.class);
        service = new DistrictDensityService(mapper);
        when(mapper.selectPlacesInBox(any(), any(), any(), any())).thenReturn(List.of(
                poi(1, 1, 1, 1, 10L, null), poi(2, 1, 0, 3, null, null), poi(3, 1, 0, 0, 12L, null),
                poi(4, 2, 1, 1, 14L, 9L), poi(5, 1, 1, 1, null, null),
                at(poi(6, 1, 1, 1, 16L, null), "30.40", "120.40"))); // outside the polygon below
        when(mapper.selectRouteNodesInBox(any(), any(), any(), any())).thenReturn(List.of(
                geo("t1", "30.10", "120.10", 1, 1), geo("t1", "30.11", "120.11", 1, 0), geo("t2", "30.12", "120.12", 0, 1)));
        when(mapper.selectLiveShopsInBox(any(), any(), any(), any())).thenReturn(List.of(
                geo("9", "30.10", "120.10", 1, 0), geo("8", "30.10", "120.10", 0, 0)));
        when(mapper.selectBoardPointsInBox(any(), any(), any(), any())).thenThrow(new RuntimeException("Table 'city_game_point' doesn't exist"));
        when(mapper.countActiveDailyMissions()).thenReturn(3);
        when(mapper.countOpeningEvents("杭州")).thenReturn(0);
    }

    private static SeedPlace poi(long id, int type, int status, int audit, Long template, Long merchant) {
        SeedPlace p = new SeedPlace();
        p.setId(id); p.setType(type); p.setStatus(status); p.setAuditStatus(audit); p.setTemplateId(template); p.setMerchantId(merchant);
        return at(p, "30.10", "120.10");
    }

    private static SeedPlace at(SeedPlace p, String lat, String lng) { p.setLat(new BigDecimal(lat)); p.setLng(new BigDecimal(lng)); return p; }

    private static SeedGeoPoint geo(String key, String lat, String lng, int f1, int f2) {
        SeedGeoPoint g = new SeedGeoPoint();
        g.setKey(key); g.setLat(new BigDecimal(lat)); g.setLng(new BigDecimal(lng)); g.setFlag1(f1); g.setFlag2(f2);
        return g;
    }

    private static List<List<BigDecimal>> triangle() {
        return List.of(List.of(new BigDecimal("30.0"), new BigDecimal("120.0")), List.of(new BigDecimal("30.3"), new BigDecimal("120.0")),
                List.of(new BigDecimal("30.0"), new BigDecimal("120.45")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> byKey(Map<String, Object> report) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) report.get("metrics")) out.put((String) m.get("key"), m);
        return out;
    }

    @Test void countsInsideDrawnPolygonAgainstTargets() {
        DensityQuery q = new DensityQuery();
        q.setPolygon(triangle()); q.setCityKeyword("杭州");
        Map<String, Map<String, Object>> m = byKey(service.report(q));
        assertEquals(2, m.get("places").get("count"), "live official places 1 and 5; 6 is outside, 4 is a shop");
        assertEquals("BELOW", m.get("places").get("state"));
        assertTrue(((String) m.get("places").get("detail")).contains("导入草稿 1、待审核 1"));
        assertEquals(3, m.get("playable").get("count"), "live places with game 1 and 4 + live route node with game");
        assertEquals(1, m.get("routes").get("count"));
        assertEquals(2, m.get("shops").get("count"));
        assertEquals(1, m.get("shopGames").get("count"));
        assertEquals("UNAVAILABLE", m.get("boardPlaces").get("state"));
        assertNull(m.get("boardPlaces").get("count"), "unreadable source is not reported as zero");
        assertEquals("MET", m.get("dailyMissions").get("state"));
        assertEquals("CITY", m.get("dailyMissions").get("scope"));
        assertEquals("BELOW", m.get("openingEvent").get("state"));
    }

    @Test void boxAndPolygonContainment() {
        DistrictDensityService.Area box = DistrictDensityService.Area.box(new BigDecimal("30"), new BigDecimal("120"),
                new BigDecimal("30.2"), new BigDecimal("120.2"), "box");
        assertTrue(box.contains(new BigDecimal("30.1"), new BigDecimal("120.1")));
        assertFalse(box.contains(new BigDecimal("30.3"), new BigDecimal("120.1")));
        assertFalse(box.contains(null, new BigDecimal("120.1")));
        DistrictDensityService.Area tri = DistrictDensityService.Area.polygon(triangle());
        assertTrue(tri.contains(new BigDecimal("30.05"), new BigDecimal("120.05")));
        assertFalse(tri.contains(new BigDecimal("30.25"), new BigDecimal("120.40")), "inside the box, outside the triangle");
    }

    @Test void rejectsBadOrOversizedAreas() {
        DensityQuery q = new DensityQuery();
        assertThrows(ServiceException.class, () -> service.report(q));
        q.setSouth(new BigDecimal("30")); q.setWest(new BigDecimal("120")); q.setNorth(new BigDecimal("31")); q.setEast(new BigDecimal("120.2"));
        assertThrows(ServiceException.class, () -> service.report(q));
        q.setNorth(new BigDecimal("29"));
        assertThrows(ServiceException.class, () -> service.report(q));
        DensityQuery two = new DensityQuery();
        two.setPolygon(triangle().subList(0, 2));
        assertThrows(ServiceException.class, () -> service.report(two));
    }

    @Test void namedBoardAreaUsesItsMapBounds() {
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("boardId", "b1"); board.put("title", "西湖片区");
        board.put("south", new BigDecimal("30.0")); board.put("west", new BigDecimal("120.0"));
        board.put("north", new BigDecimal("30.2")); board.put("east", new BigDecimal("120.2"));
        when(mapper.selectBoardAreas()).thenReturn(List.of(board));
        DensityQuery q = new DensityQuery();
        q.setBoardId("b1");
        Map<String, Object> report = service.report(q);
        @SuppressWarnings("unchecked") Map<String, Object> area = (Map<String, Object>) report.get("area");
        assertEquals("CITY 棋盘：西湖片区", area.get("source"));
        verify(mapper).selectPlacesInBox(new BigDecimal("30.0"), new BigDecimal("30.2"), new BigDecimal("120.0"), new BigDecimal("120.2"));
        q.setBoardId("missing");
        assertThrows(ServiceException.class, () -> service.report(q));
    }

    @Test void boardTargetIsARange() {
        assertEquals("BELOW", DistrictDensityService.metric("b", "b", 19, 20, 30, "AREA", "").get("state"));
        assertEquals("MET", DistrictDensityService.metric("b", "b", 30, 20, 30, "AREA", "").get("state"));
        assertEquals("ABOVE", DistrictDensityService.metric("b", "b", 31, 20, 30, "AREA", "").get("state"));
    }
}
