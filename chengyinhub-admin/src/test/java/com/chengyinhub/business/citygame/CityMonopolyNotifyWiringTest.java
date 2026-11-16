package com.chengyinhub.business.citygame;

import com.chengyinhub.business.growthloops.ICityEventNotifyService;
import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.business.service.impl.RoamFogCellService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** W7 x-city-territory: CITY-11 push wiring, CITY-12 masked holders, CITY-16 kept-cell reads. */
class CityMonopolyNotifyWiringTest {
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final long NOW = ZonedDateTime.of(2026, 11, 16, 12, 0, 0, 0, SH).toInstant().toEpochMilli();
    private static final long DAY = 86_400_000L;

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static CityMonopolyService service(JdbcTemplate jdbc) {
        return new CityMonopolyService(jdbc, mock(CityGameMapper.class), CityMonopolyConfigService.fixed(CityMonopolyConfig.defaults()), null, () -> NOW, faces -> 20);
    }

    // ------------------------------------------------------------------ CITY-11

    @Test
    void oneCallPerEventAndNeverToTheActor() {
        ICityEventNotifyService n = mock(ICityEventNotifyService.class);
        CityMonopolyNotices notices = new CityMonopolyNotices();
        notices.setNotify(n);
        notices.lost(4L, 7L, "board:B1:PT1", "T-1", "老街", NOW, 24, 0.5);
        notices.defended(5L, 7L, "board:B1:PT2", "T-2", "鼓楼", NOW, 13, 15);
        notices.defended(7L, 7L, "board:B1:PT3", "T-3", "自己", NOW, 13, 15);
        notices.decaying(6L, "poi:9", "T-4", null, NOW, 5);
        notices.released(8L, "poi:10", "T-5", "码头", NOW);
        notices.seasonResult(9L, "s2026w46", 3, 40, 120);
        verify(n).monopolyLost(4L, "terr:T-1", "老街", 24, 0.5);
        verify(n).defended(5L, "terr:T-2", "鼓楼", 13, 15);
        verify(n, never()).defended(eq(7L), any(), any(), anyInt(), anyInt());
        verify(n).decaying(6L, "terr:T-4", null, 5);
        verify(n).released(8L, "terr:T-5", "码头");
        verify(n).seasonResult(9L, "s2026w46", 3, 40, 120);
    }

    @Test
    void atMostOnePushPerMemberAndPlaceEverySixHours() {
        ICityEventNotifyService n = mock(ICityEventNotifyService.class);
        CityMonopolyNotices notices = new CityMonopolyNotices();
        notices.setNotify(n);
        notices.defended(5L, 7L, "board:B1:PT2", "T-2", "鼓楼", NOW, 13, 15);
        notices.defended(5L, 8L, "board:B1:PT2", "T-2", "鼓楼", NOW + 3_600_000L, 12, 15);
        notices.lost(5L, 8L, "board:B1:PT2", "T-2", "鼓楼", NOW + 5 * 3_600_000L, 24, 0.5);
        notices.defended(5L, 8L, "board:B1:PT9", "T-9", "别处", NOW + 3_600_000L, 12, 15);
        notices.lost(5L, 8L, "board:B1:PT2", "T-2", "鼓楼", NOW + CityMonopolyNotices.THROTTLE_MS, 24, 0.5);
        verify(n, times(1)).defended(eq(5L), eq("terr:T-2"), any(), anyInt(), anyInt());
        verify(n, times(1)).defended(eq(5L), eq("terr:T-9"), any(), anyInt(), anyInt());
        verify(n, times(1)).monopolyLost(eq(5L), eq("terr:T-2"), any(), anyLong(), anyDouble());
    }

    @Test
    void noNotifierAndFailingNotifierNeverBreakTheCaller() {
        assertDoesNotThrow(() -> new CityMonopolyNotices().lost(4L, 7L, "k", "T", "p", NOW, 24, 0.5));
        ICityEventNotifyService n = mock(ICityEventNotifyService.class);
        doThrow(new IllegalStateException()).when(n).released(anyLong(), any(), any());
        CityMonopolyNotices notices = new CityMonopolyNotices();
        notices.setNotify(n);
        assertDoesNotThrow(() -> notices.released(4L, "k", "T", "p", NOW));
    }

    @Test
    void nightlyRemindsDecayingHoldersAndTellsReleasedOnes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("rules_kind = 'MONOPOLY' FOR UPDATE"))).thenReturn(List.of(
                row("territory_id", "T-5", "owner_member_id", 5L, "invested", 0L, "tier", 1, "upgrade_level", 0, "decay_levels_applied", 0,
                        "decay_state", "OK", "title", "老街", "board_id", "B1", "point_id", "PT1", "last_owner_visit_at", new Timestamp(NOW - 5 * DAY)),
                row("territory_id", "T-6", "owner_member_id", 6L, "invested", 0L, "tier", 1, "upgrade_level", 0, "decay_levels_applied", 0,
                        "decay_state", "DECAYING", "title", "码头", "poi_id", 9L, "last_owner_visit_at", new Timestamp(NOW - 22 * DAY))));
        when(jdbc.update(contains("release_reason = 'DECAY'"), any(), eq("T-6"))).thenReturn(1);
        ICityEventNotifyService n = mock(ICityEventNotifyService.class);
        CityMonopolyService s = service(jdbc);
        s.setCityNotify(n);

        s.nightly();

        verify(n).decaying(5L, "terr:T-5", "老街", 5);
        verify(n).released(6L, "terr:T-6", "码头");
        verify(n, never()).decaying(eq(6L), any(), any(), anyLong());
    }

    // ------------------------------------------------------------------ CITY-12

    @Test
    void leaderboardMasksEveryoneButTheViewer() {
        Map<String, Object> other = new HashMap<>(row("ownerMemberId", 12345L, "ownerNickname", "小明"));
        RoamTerritoryService.maskHolder(other, 7L);
        assertNull(other.get("ownerMemberId"));
        assertNull(other.get("ownerNickname"));
        assertEquals("玩家1***5", other.get("holderMasked"));
        assertEquals(false, other.get("isMine"));
        Map<String, Object> mine = new HashMap<>(row("ownerMemberId", 7L, "ownerNickname", "我"));
        RoamTerritoryService.maskHolder(mine, 7L);
        assertEquals(7L, mine.get("ownerMemberId"));
        assertEquals("我", mine.get("ownerNickname"));
        assertEquals(true, mine.get("isMine"));
        Map<String, Object> anonymous = new HashMap<>(row("ownerMemberId", 7L, "ownerNickname", "我"));
        RoamTerritoryService.maskHolder(anonymous, 0L);
        assertNull(anonymous.get("ownerNickname"), "a signed-out read sees nobody");
    }

    @Test
    void nearbyHidesOtherHoldersAndCapsTheRadius() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM roam_poi poi"), any(Object[].class))).thenReturn(List.of(
                row("poiId", 1L, "pointTitle", "A", "latitude", 31.23, "longitude", 121.47, "territoryId", "T-1",
                        "ownerMemberId", 12345L, "ownerNickname", "小明", "claimPhotoUrl", "https://x/p.jpg", "territoryStatus", "ACTIVE"),
                row("poiId", 2L, "pointTitle", "B", "latitude", 31.231, "longitude", 121.471, "territoryId", "T-2",
                        "ownerMemberId", 7L, "ownerNickname", "我", "claimPhotoUrl", "https://x/me.jpg", "territoryStatus", "ACTIVE")));
        RoamTerritoryService t = new RoamTerritoryService(jdbc, mock(CityGameMapper.class), null, () -> NOW);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) t.nearby(7L, 31.23, 121.47, 50_000, null, null, null).get("items");

        Map<String, Object> theirs = items.get(0);
        assertNull(theirs.get("ownerMemberId"));
        assertNull(theirs.get("ownerNickname"));
        assertNull(theirs.get("claimPhotoUrl"));
        assertEquals("玩家1***5", theirs.get("holderMasked"));
        assertEquals(false, theirs.get("isMine"));
        assertTrue(theirs.containsKey("ownerMemberId"), "keys stay for older clients");
        Map<String, Object> mine = items.get(1);
        assertEquals(7L, mine.get("ownerMemberId"));
        assertEquals("https://x/me.jpg", mine.get("claimPhotoUrl"));
        assertEquals(true, mine.get("isMine"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(contains("FROM roam_poi poi"), args.capture());
        double latSpan = (double) args.getValue()[1] - (double) args.getValue()[0];
        assertEquals(2 * RoamTerritoryService.MAX_NEARBY_RADIUS_M / 111320.0, latSpan, 1e-6, "radius capped at 5 km");
    }

    // ------------------------------------------------------------------ CITY-16

    @Test
    void mapUsesKeptCells() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM roam_territory WHERE status = 'ACTIVE' AND rules_kind = 'MONOPOLY' AND latitude"), (Object) any(), any(), any(), any()))
                .thenReturn(List.of(
                        row("territory_id", "T-1", "geohash7", "wtw3sjq", "owner_member_id", 9L, "tier", 1, "upgrade_level", 0, "status", "ACTIVE"),
                        row("territory_id", "T-2", "geohash7", "wtw3sjr", "owner_member_id", 9L, "tier", 1, "upgrade_level", 0, "status", "ACTIVE")));
        when(jdbc.queryForList(startsWith("SELECT cell_key FROM roam_member_fog_cell"), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("wtw3sjq"));
        RoamFogCellService fog = mock(RoamFogCellService.class);
        when(fog.retainEnabled()).thenReturn(true);
        CityMonopolyService s = service(jdbc);
        s.setFogCells(fog);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) s.map(7L, 31.2, 121.4, 31.3, 121.5).get("items");

        assertEquals("T-1", items.get(0).get("propertyId"), "lit long ago, still lit");
        assertNull(items.get(1).get("propertyId"), "unlit place stays fogged");
        verify(jdbc, never()).queryForList(contains("roam_fog_tile"), eq(String.class), any(Object[].class));
        verify(jdbc, never()).queryForList(eq("SELECT geohash7 FROM roam_fog_tile WHERE member_id = ?"), eq(String.class), any());
    }

    @Test
    void mapQueryBoundedByTerritoriesInView() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<Map<String, Object>> many = new java.util.ArrayList<>();
        for (int i = 0; i < 450; i++) {
            many.add(row("territory_id", "T-" + i, "geohash7", String.format("wtw%04d", i), "owner_member_id", 9L, "tier", 1, "upgrade_level", 0));
        }
        when(jdbc.queryForList(contains("AND latitude BETWEEN"), (Object) any(), any(), any(), any())).thenReturn(many);
        CityMonopolyService s = service(jdbc);

        s.map(7L, 31.2, 121.4, 31.3, 121.5);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(3)).queryForList(startsWith("SELECT LOWER(geohash7) FROM roam_fog_tile WHERE member_id = ? AND geohash7 IN ("),
                eq(String.class), args.capture());
        for (Object[] a : args.getAllValues()) assertTrue(a.length <= CityMonopolyService.LIT_CHUNK + 1, "chunks of 200");
    }

    @Test
    void litCellsWithNothingToAskReadsNothing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertEquals(Set.of(), service(jdbc).litCells(7L, List.of()));
        verifyNoInteractions(jdbc);
    }
}
