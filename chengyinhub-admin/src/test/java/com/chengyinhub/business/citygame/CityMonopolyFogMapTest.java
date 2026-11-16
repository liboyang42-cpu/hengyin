package com.chengyinhub.business.citygame;

import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.business.service.impl.RoamFogCellService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** W7 x-fog-monopoly: the fog-map layer (states, free places, fog rule), the deed's rent breakdown, masked check-ins. */
class CityMonopolyFogMapTest {
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final long NOW = ZonedDateTime.of(2026, 11, 16, 12, 0, 0, 0, SH).toInstant().toEpochMilli();

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static CityMonopolyService service(JdbcTemplate jdbc, Map<String, String> overrides) {
        CityMonopolyConfig c = overrides.isEmpty() ? CityMonopolyConfig.defaults() : CityMonopolyConfig.withOverrides(overrides);
        return new CityMonopolyService(jdbc, mock(CityGameMapper.class), CityMonopolyConfigService.fixed(c), null, () -> NOW, faces -> 20);
    }

    private static JdbcTemplate layerJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM roam_territory WHERE status = 'ACTIVE' AND rules_kind = 'MONOPOLY' AND latitude"), (Object) any(), any(), any(), any()))
                .thenReturn(List.of(
                        row("territory_id", "T-MINE", "geohash7", "wtw3sj1", "owner_member_id", 7L, "tier", 2, "upgrade_level", 1, "status", "ACTIVE", "title", "我的店"),
                        row("territory_id", "T-HELD", "geohash7", "wtw3sj2", "owner_member_id", 9L, "tier", 1, "upgrade_level", 0, "status", "ACTIVE",
                                "decay_state", "WEATHERED", "title", "老街"),
                        row("territory_id", "T-PROT", "geohash7", "wtw3sj3", "owner_member_id", 9L, "tier", 4, "upgrade_level", 3, "status", "ACTIVE",
                                "protected_until", new Timestamp(NOW + 3_600_000L), "title", "钟楼"),
                        row("territory_id", "T-FOG", "geohash7", "wtw3sj4", "owner_member_id", 9L, "tier", 3, "upgrade_level", 2, "status", "ACTIVE",
                                "district_id", "D1", "title", "藏起来的")));
        when(jdbc.queryForList(contains("FROM city_game_point p"), (Object) any(), any(), any(), any()))
                .thenReturn(List.of(
                        row("board_id", "B1", "point_id", "P-LIT", "title", "公园", "latitude", 31.25, "longitude", 121.45, "tier", 2, "geohash7", "wtw3sj5"),
                        row("board_id", "B1", "point_id", "P-FOG", "title", "看不见", "latitude", 31.26, "longitude", 121.46, "geohash7", "wtw3sj6",
                                "district_id", "D2")));
        when(jdbc.queryForList(startsWith("SELECT cell_key FROM roam_member_fog_cell"), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("wtw3sj2", "wtw3sj3", "wtw3sj5"));
        return jdbc;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> out, String key) {
        return (List<Map<String, Object>>) out.get(key);
    }

    @Test
    void everyRowSaysItsStateAndLitHeldPlacesTheirRent() {
        CityMonopolyService s = service(layerJdbc(), Map.of());
        RoamFogCellService fog = mock(RoamFogCellService.class);
        when(fog.retainEnabled()).thenReturn(true);
        s.setFogCells(fog);

        List<Map<String, Object>> items = list(s.map(7L, 31.2, 121.4, 31.3, 121.5, false), "items");

        assertEquals("MINE", items.get(0).get("state"));
        assertEquals(7L, items.get(0).get("rentPerVisit"), "T2 base 5 × Lv1 1.4 = 7");
        assertEquals("HELD", items.get(1).get("state"));
        assertEquals(2L, items.get(1).get("rentPerVisit"), "T1 base 3, weathered halves it (1.5 rounds to 2)");
        assertEquals("PROTECTED", items.get(2).get("state"));
        assertEquals(29L, items.get(2).get("rentPerVisit"), "T4 base 12 × Lv3 2.4 = 28.8");
        Map<String, Object> fogged = items.get(3);
        assertEquals("FOGGED", fogged.get("state"));
        assertEquals(true, fogged.get("fogged"));
        assertEquals("wtw3sj4", fogged.get("geohash7"));
        assertNull(fogged.get("propertyId"));
        assertNull(fogged.get("holder"), "no holder in the fog");
        assertNull(fogged.get("title"), "no name in the fog");
        assertNull(fogged.get("rentPerVisit"));
    }

    @Test
    void freePlacesOnlyWhenAskedAndUnderTheSameFogRule() {
        JdbcTemplate jdbc = layerJdbc();
        CityMonopolyService s = service(jdbc, Map.of());
        RoamFogCellService fog = mock(RoamFogCellService.class);
        when(fog.retainEnabled()).thenReturn(true);
        s.setFogCells(fog);

        assertNull(s.map(7L, 31.2, 121.4, 31.3, 121.5, false).get("free"));
        verify(jdbc, never()).queryForList(contains("FROM city_game_point p"), (Object) any(), any(), any(), any());

        List<Map<String, Object>> free = list(s.map(7L, 31.2, 121.4, 31.3, 121.5, true), "free");
        assertEquals(2, free.size());
        assertEquals("FREE", free.get(0).get("state"));
        assertEquals("P-LIT", free.get(0).get("pointId"));
        assertEquals("公园", free.get(0).get("title"));
        assertEquals(31.25, free.get(0).get("lat"));
        assertEquals(5L, free.get(0).get("rentPerVisit"), "T2 base rent");
        assertEquals("FOGGED", free.get(1).get("state"));
        assertNull(free.get(1).get("pointId"));
        assertNull(free.get(1).get("title"));
        assertEquals(1, free.get(1).get("tier") == null ? 1 : 0, "fogged free place keeps its (unset) tier only");
        assertEquals("D2", free.get(1).get("districtId"));
    }

    @Test
    void freePlacesOfBoardsNotRunningMonopolyAreLeftOut() {
        CityMonopolyService s = service(layerJdbc(), Map.of("city.monopoly.board.mode", "SHADOW"));
        List<Map<String, Object>> free = list(s.map(7L, 31.2, 121.4, 31.3, 121.5, true), "free");
        assertTrue(free.isEmpty());
    }

    @Test
    void freePlaceWithoutACellGetsOneComputed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM city_game_point p"), (Object) any(), any(), any(), any()))
                .thenReturn(List.of(row("board_id", "B1", "point_id", "P-1", "title", "x", "latitude", 31.2304, "longitude", 121.4737)));
        List<Map<String, Object>> rows = service(jdbc, Map.of()).freeInView(31.2, 121.4, 31.3, 121.5);
        assertEquals(RoamTerritoryService.encodeGeohash7(31.2304, 121.4737), rows.get(0).get("geohash7"));
    }

    @Test
    void deedRentBreakdownInFixedOrderWithoutNeutralFactors() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("charm", 1.004);
        m.put("time", 1.2);
        m.put("set", 1.0);
        m.put("level", 1.4);
        m.put("future", 1.1);
        Map<String, Object> out = new LinkedHashMap<>();
        CityMonopolyService.putMultipliers(out, m);
        assertEquals(List.of("level", "time", "charm", "future"), out.get("multiplierKeys"));
        @SuppressWarnings("unchecked") Map<String, Double> shown = (Map<String, Double>) out.get("multipliers");
        assertFalse(shown.containsKey("set"), "×1.00 explains nothing");
        assertEquals(1.0, shown.get("charm"), 1e-9);
        assertEquals(1.4, shown.get("level"), 1e-9);
        CityMonopolyService.putMultipliers(out, null);
        assertEquals(List.of(), out.get("multiplierKeys"));
    }

    @Test
    void checkinAnswerMasksAnotherHolder() {
        Map<String, Object> other = new LinkedHashMap<>();
        RoamTerritoryService.putHolder(other, 12345L, 8L);
        assertTrue(other.containsKey("ownerMemberId"), "key kept for older builds");
        assertNull(other.get("ownerMemberId"));
        assertEquals(CityMonopolyService.mask(12345L), other.get("holderMasked"));
        assertEquals(false, other.get("isMine"));

        Map<String, Object> mine = new LinkedHashMap<>();
        RoamTerritoryService.putHolder(mine, 8L, 8L);
        assertEquals(8L, mine.get("ownerMemberId"));
        assertEquals(true, mine.get("isMine"));
    }

    @Test
    void checkinReplayMasksTheHolderToo() {
        Map<String, Object> stored = row("checkin_id", "C-1", "territory_id", "T-1", "owner_member_id", 42L, "points_spent", 0L,
                "owner_revenue_points", 3L, "revenue_mode", "POINTS", "plausibility_status", "PASS", "distance_m", 12.34);
        Map<String, Object> visitor = RoamTerritoryService.checkinReplay(stored, 8L);
        assertNull(visitor.get("ownerMemberId"));
        assertEquals(CityMonopolyService.mask(42L), visitor.get("holderMasked"));
        Map<String, Object> holder = RoamTerritoryService.checkinReplay(stored, 42L);
        assertEquals(42L, holder.get("ownerMemberId"));
        assertEquals(true, holder.get("isMine"));
    }

    @Test
    void seasonSaysTheWeeksRentEventClampedLikeRent() {
        assertEquals(1.0, CityMonopolyService.eventMultiplier(CityMonopolyConfig.defaults()), 1e-9);
        assertEquals(1.5, CityMonopolyService.eventMultiplier(CityMonopolyConfig.withOverrides(Map.of("city.monopoly.yield.event_multiplier", "1.5"))), 1e-9);
    }
}
