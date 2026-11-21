package com.chengyinhub.business.citygame;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.business.service.INotifyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** CITY-GAPS-R3 items 1–4 against the service layer (SQL answered by {@link SqlRoutingJdbc}). */
class CityGapsR3Test {
    static final long NOW = ZonedDateTime.of(2026, 11, 16, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    static final double LAT = 23.1291, LNG = 113.2644;

    private final CityGameMapper mapper = mock(CityGameMapper.class);
    private final INotifyService notify = mock(INotifyService.class);

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    private CityMonopolyService service(JdbcTemplate jdbc, CityMonopolyConfig c, int die) {
        CityMonopolyService s = new CityMonopolyService(jdbc, mapper, CityMonopolyConfigService.fixed(c), null, () -> NOW, faces -> die);
        s.setNotify(notify);
        return s;
    }

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static Map<String, Object> territory(String id, long owner, Integer deedSerial) {
        return row("territory_id", id, "owner_member_id", owner, "board_id", "B", "point_id", "P1", "poi_id", null, "property_id", null,
                "district_id", "D1", "title", "老街咖啡", "tier", 1, "upgrade_level", 1, "invested", 100L, "latitude", LAT, "longitude", LNG,
                "geohash7", "ws0e9bx", "status", "ACTIVE", "rules_kind", "MONOPOLY", "decay_state", "OK", "deed_serial", deedSerial,
                "claim_photo_url", "/upload/deed.jpg", "city_code", "440100");
    }

    static Map<String, Object> evidence() {
        return Map.of("lat", LAT, "lng", LNG, "hAcc", 5.0, "samples", List.of(
                Map.of("t", NOW - 90_000L, "lat", LAT, "lng", LNG), Map.of("t", NOW - 45_000L, "lat", LAT, "lng", LNG),
                Map.of("t", NOW, "lat", LAT, "lng", LNG)));
    }

    // =================================================================== 1. claim quote for a board point without place_id

    @Test
    void claimQuoteForBoardPointWithoutPlaceIdIsPricedInsteadOfHttp500() {
        SqlRoutingJdbc db = new SqlRoutingJdbc();
        Map<String, Object> point = row("board_id", "B", "point_id", "P9", "title", "无地点的格子", "latitude", LAT, "longitude", LNG,
                "tier", 2, "district_id", "D1", "merchant_id", null, "place_id", null, "property_id", "PR9");
        db.rows("FROM city_game_point p WHERE p.board_id = ? AND p.point_id = ?", List.of(point));
        CityMonopolyService s = service(db.jdbc, CityMonopolyConfig.defaults(), 20);

        CityMonopolyService.Target t = s.resolveTarget(Map.of("boardId", "B", "pointId", "P9"));
        assertNull(t.poiId(), "a board-only space has no roam_poi; it must not be unboxed");
        assertEquals("board:B:P9", t.key());

        Map<String, Object> quote = s.claimQuote(7L, Map.of("boardId", "B", "pointId", "P9"));
        assertEquals("board:B:P9", quote.get("targetKey"));
        assertEquals("P9", quote.get("pointId"));
        assertFalse(quote.containsKey("poiId"));
        assertEquals(CityMonopolyRules.claimCost(CityMonopolyConfig.defaults(), 2), quote.get("cost"));
        assertEquals("POINTS", quote.get("currency"));
        assertNotNull(quote.get("quoteId"));
        assertEquals(1, db.calls("INSERT INTO city_monopoly_quote").size());
    }

    @Test
    void unknownBoardPointIsStillABusinessRefusal() {
        CityMonopolyService s = service(new SqlRoutingJdbc().jdbc, CityMonopolyConfig.defaults(), 20);
        CityGameException e = assertThrows(CityGameException.class, () -> s.claimQuote(7L, Map.of("boardId", "B", "pointId", "nope")));
        assertEquals("SPACE_NOT_FOUND", e.getErrorCode());
    }

    // =================================================================== 2 + 3. district names / colour keys and deed serial in reads

    private SqlRoutingJdbc mapDb() {
        SqlRoutingJdbc db = new SqlRoutingJdbc();
        // be-integ CITY-3: property() first resolves the id (territory or board property id) to a territory id.
        db.on("SELECT territory_id FROM roam_territory", a -> new ArrayList<>(List.of("T1")));
        db.on("FROM roam_fog_tile WHERE member_id = ?", a -> List.of("ws0e9bx"));
        Map<String, Object> lit = territory("T1", 99L, 3);
        Map<String, Object> fogged = territory("T2", 98L, 4);
        fogged.put("geohash7", "ws0e9zz");
        fogged.put("district_id", "D2");
        db.rows("FROM roam_territory WHERE status = 'ACTIVE' AND rules_kind = 'MONOPOLY' AND latitude BETWEEN", List.of(lit, fogged));
        db.rows("FROM roam_territory WHERE territory_id = ?", List.of(lit));
        db.rows("FROM city_district WHERE board_id IN", List.of(
                row("board_id", "B", "district_id", "D1", "name", "北京路", "color_token", "district.c2", "ownable_count", 6, "safety_blocked", 0),
                row("board_id", "B", "district_id", "D2", "name", "上下九", "color_token", "custom-teal", "ownable_count", 5, "safety_blocked", 1)));
        return db;
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapLayerCarriesDistrictNameColourAndDeedSerial() {
        CityMonopolyService s = service(mapDb().jdbc, CityMonopolyConfig.defaults(), 20);
        Map<String, Object> out = s.map(7L, LAT - 0.01, LNG - 0.01, LAT + 0.01, LNG + 0.01);
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        Map<String, Object> visible = items.get(0);
        assertEquals(3, visible.get("deedSerial"));
        assertEquals("北京路", visible.get("districtName"));
        assertEquals("district.c2", visible.get("districtColorToken"));
        assertEquals("老街咖啡", visible.get("title"));
        Map<String, Object> fogged = items.get(1);
        assertEquals("上下九", fogged.get("districtName"), "district names are public board metadata, shown under fog");
        assertFalse(fogged.containsKey("title"));
        assertFalse(fogged.containsKey("deedSerial"), "fog still hides the deed");
        List<Map<String, Object>> districts = (List<Map<String, Object>>) out.get("districts");
        assertEquals(2, districts.size());
        assertEquals(2, districts.get(0).get("colorIndex"));
        assertNull(districts.get(1).get("colorIndex"), "non-slot tokens leave the slot to the client");
        assertEquals(true, districts.get(1).get("safetyBlocked"));
        assertEquals("POINTS", out.get("currency"));
    }

    @Test
    void propertyCardCarriesDistrictAndDeedSerial() {
        CityMonopolyService s = service(mapDb().jdbc, CityMonopolyConfig.defaults(), 20);
        Map<String, Object> card = s.property(7L, "T1");
        assertEquals(3, card.get("deedSerial"));
        assertEquals("北京路", card.get("districtName"));
        assertEquals("district.c2", card.get("districtColorToken"));
        assertEquals("/upload/deed.jpg", card.get("deedPhoto"));
    }

    @Test
    void missingDistrictRowMeansNullNameNotAnError() {
        Map<String, Object> item = new HashMap<>();
        CityMonopolyService.putDistrict(item, Map.of(), "B", "D9");
        assertTrue(item.containsKey("districtName"));
        assertNull(item.get("districtName"));
        assertNull(item.get("districtColorToken"));
        assertEquals(6, CityMonopolyService.colorIndex("district.c6"));
        assertNull(CityMonopolyService.colorIndex(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nearbySpacesCarryDistrictAndDeedSerial() {
        SqlRoutingJdbc db = new SqlRoutingJdbc();
        when(mapper.selectBoard("B")).thenReturn(Map.of("boardId", "B", "regionId", "gz", "seasonId", "S1"));
        db.rows("FROM city_game_point pt", List.of(row("boardId", "B", "pointId", "P1", "pointTitle", "老街咖啡", "latitude", LAT,
                "longitude", LNG, "districtId", "D1", "territoryId", "T1", "deedSerial", 5, "ownerMemberId", 99L)));
        db.rows("FROM city_district WHERE board_id IN", List.of(
                row("board_id", "B", "district_id", "D1", "name", "北京路", "color_token", "district.c1", "ownable_count", 6, "safety_blocked", 0)));
        RoamTerritoryService territories = new RoamTerritoryService(db.jdbc, mapper, null, () -> NOW);
        Map<String, Object> out = territories.nearby(7L, LAT, LNG, 1000, "B", null, null);
        Map<String, Object> item = ((List<Map<String, Object>>) out.get("items")).get(0);
        assertEquals("D1", item.get("districtId"));
        assertEquals("北京路", item.get("districtName"));
        assertEquals("district.c1", item.get("districtColorToken"));
        assertEquals(5, item.get("deedSerial"));
        assertEquals(1, ((List<?>) out.get("districts")).size());
        assertSame(out.get("items"), out.get("territories"), "the legacy alias is kept");
    }

    // =================================================================== 3. serial on first claim, kept on takeover

    @Test
    void deedSerialIsOnlyIssuedInsideADistrict() {
        SqlRoutingJdbc db = new SqlRoutingJdbc();
        db.on("SELECT last_serial FROM city_deed_counter", a -> 7);
        CityMonopolyService s = service(db.jdbc, CityMonopolyConfig.defaults(), 20);
        assertNull(s.issueDeedSerial(null, "D1"));
        assertNull(s.issueDeedSerial("B", null));
        assertNull(s.issueDeedSerial("B", " "));
        assertTrue(db.calls.isEmpty());
        assertEquals(7, s.issueDeedSerial("B", "D1"));
        assertEquals(List.of("ON DUPLICATE KEY UPDATE last_serial = last_serial + 1"),
                db.calls("INSERT INTO city_deed_counter").stream().map(c -> c.sql().substring(c.sql().indexOf("ON DUPLICATE"))).toList());
        assertTrue(db.calls("SELECT last_serial FROM city_deed_counter").get(0).sql().endsWith("FOR UPDATE"));
    }

    private SqlRoutingJdbc challengeDb(CityMonopolyConfig c, Map<String, Object> terr) {
        SqlRoutingJdbc db = new SqlRoutingJdbc();
        // be-integ: monopolyTerritory() first looks for a LEGACY row to adopt; there is none here.
        db.rows("rules_kind = 'LEGACY'", List.of());
        db.rows("FROM city_monopoly_quote WHERE quote_id = ?", List.of(row("quote_id", "Q1", "quote_hash", "QH", "used_at", null,
                "expires_at", new Timestamp(NOW + 60_000L), "cost", 30L, "target_key", "T1", "rules_hash", c.rulesHash(),
                "payload_json", JSON.toJSONString(Map.of("territoryId", "T1", "fee", 30)))));
        db.rows("FROM roam_territory WHERE territory_id = ? AND status = 'ACTIVE' AND rules_kind = 'MONOPOLY'", List.of(terr));
        db.on("SELECT * FROM roam_territory WHERE territory_id = ?", a -> {
            Map<String, Object> fresh = new HashMap<>(terr);
            fresh.put("territory_id", a[0]);
            return fresh;
        });
        when(mapper.selectMemberPoints(anyLong())).thenReturn(1_000L);
        when(mapper.deductMemberPoints(anyLong(), anyLong())).thenReturn(1);
        return db;
    }

    private static Map<String, Object> challengeBody() {
        return Map.of("requestId", "REQ-CH-1", "quoteId", "Q1", "quoteHash", "QH", "evidence", evidence());
    }

    @Test
    void takeoverKeepsTheDeedSerialAndTellsTheDefenderThePlaceIsLost() {
        CityMonopolyConfig c = CityMonopolyConfig.withOverrides(Map.of("city.monopoly.claim.require_app_attest", "false"));
        SqlRoutingJdbc db = challengeDb(c, territory("T1", 99L, 4));
        Map<String, Object> out = service(db.jdbc, c, 20).challengeCommand(7L, challengeBody());

        assertEquals("WIN", out.get("outcome"));
        List<SqlRoutingJdbc.Call> inserts = db.calls("INSERT INTO roam_territory(");
        assertEquals(1, inserts.size());
        assertTrue(inserts.get(0).sql().contains("deed_serial)"));
        Object[] args = inserts.get(0).args();
        assertEquals(4, args[args.length - 1], "the deed changes hands with its serial");
        assertTrue(db.calls("city_deed_counter").isEmpty(), "a takeover never issues a new serial");
        long comp = ((CityMonopolyRules.FeeSplit) out.get("feeSplit")).defenderComp();
        verify(notify).notifyCityTerritory(eq(99L), eq("PLACE_LOST"), eq("T1"), eq("老街咖啡"), contains("被"), eq(comp));
        verify(notify, never()).notifyCityTerritory(eq(7L), any(), any(), any(), any(), anyLong());
    }

    @Test
    void failedChallengeTellsTheDefenderTheyHeld() {
        CityMonopolyConfig c = CityMonopolyConfig.withOverrides(Map.of("city.monopoly.claim.require_app_attest", "false"));
        SqlRoutingJdbc db = challengeDb(c, territory("T1", 99L, 4));
        Map<String, Object> out = service(db.jdbc, c, 1).challengeCommand(7L, challengeBody());
        assertEquals("LOSS", out.get("outcome"));
        assertTrue(db.calls("INSERT INTO roam_territory(").isEmpty());
        verify(notify).notifyCityTerritory(eq(99L), eq("DEFENDED"), eq("T1"), eq("老街咖啡"), startsWith("你守住了"), anyLong());
    }

    @Test
    void rejectedChallengeNotifiesNobody() {
        CityMonopolyConfig c = CityMonopolyConfig.defaults(); // App Attest required and absent → REJECTED
        SqlRoutingJdbc db = challengeDb(c, territory("T1", 99L, 4));
        Map<String, Object> out = service(db.jdbc, c, 20).challengeCommand(7L, challengeBody());
        assertEquals("REJECTED", out.get("outcome"));
        verifyNoInteractions(notify);
    }

    // =================================================================== 4. notifications go out after commit only

    @Test
    void notificationWaitsForCommitAndIsDroppedOnRollback() {
        CityMonopolyService s = service(new SqlRoutingJdbc().jdbc, CityMonopolyConfig.defaults(), 20);
        TransactionSynchronizationManager.initSynchronization();
        s.notifyMember(99L, "DEFENDED", "T1", "老街咖啡", "text", 3);
        verifyNoInteractions(notify);
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(notify);
        syncs.forEach(TransactionSynchronization::afterCommit);
        verify(notify).notifyCityTerritory(99L, "DEFENDED", "T1", "老街咖啡", "text", 3);
    }

    @Test
    void notificationFailureNeverFailsTheGameAction() {
        CityMonopolyService s = service(new SqlRoutingJdbc().jdbc, CityMonopolyConfig.defaults(), 20);
        when(notify.notifyCityTerritory(anyLong(), any(), any(), any(), any(), anyLong())).thenThrow(new IllegalStateException("im down"));
        assertDoesNotThrow(() -> s.notifyMember(99L, "RENT", "T1", "x", "y", 1));
    }

    private SqlRoutingJdbc visitDb(int grantedEventsToday) {
        SqlRoutingJdbc db = new SqlRoutingJdbc();
        db.rows("FROM ums_member m LEFT JOIN member_growth", List.of(row("create_time", new Timestamp(NOW - 200L * 86_400_000L),
                "phone", "13800000000", "union_id", null, "level_no", 20)));
        db.on("AND board_day = ? AND state <> 'VOID' AND granted > 0", a -> grantedEventsToday);
        db.on("SELECT LAST_INSERT_ID()", a -> 55L);
        db.on("FROM member_visited_poi", a -> 5); // a real explorer, not a farm account (visitor-diversity rule)
        db.on("SELECT COALESCE(SUM(raw),0)", a -> 0L);
        return db;
    }

    private static Map<String, Object> visitTerritory() {
        Map<String, Object> terr = territory("T1", 99L, 4);
        terr.put("board_id", null);
        terr.put("district_id", null);
        terr.put("upgrade_level", 0);
        return terr;
    }

    @Test
    void firstEarningVisitOfTheDayTellsTheHolderOnce() {
        SqlRoutingJdbc db = visitDb(1);
        Map<String, Object> out = service(db.jdbc, CityMonopolyConfig.defaults(), 20).onVisit(7L, visitTerritory(), LAT, LNG, 5.0, "ref-1", null);
        assertNull(out.get("ineligibleReason"), String.valueOf(out.get("reasons")));
        long granted = ((Number) out.get("yield")).longValue();
        assertTrue(granted > 0);
        verify(notify).notifyCityTerritory(eq(99L), eq("RENT"), eq("T1"), eq("老街咖啡"), contains("待结算租金 +" + granted), eq(granted));

        SqlRoutingJdbc later = visitDb(2);
        clearInvocations(notify);
        service(later.jdbc, CityMonopolyConfig.defaults(), 20).onVisit(8L, visitTerritory(), LAT, LNG, 5.0, "ref-2", null);
        verifyNoInteractions(notify);
    }

    @Test
    void selfFarmedOrCappedVisitsNeverNotify() {
        CityMonopolyService s = service(visitDb(1).jdbc, CityMonopolyConfig.defaults(), 20);
        assertEquals("SELF_VISIT", s.onVisit(99L, visitTerritory(), LAT, LNG, 5.0, "ref-own", null).get("ineligibleReason"));
        SqlRoutingJdbc pair = visitDb(1);
        pair.on("WHERE visitor_member_id = ? AND territory_id = ? AND board_day = ?", a -> 1);
        Map<String, Object> out = service(pair.jdbc, CityMonopolyConfig.defaults(), 20).onVisit(7L, visitTerritory(), LAT, LNG, 5.0, "ref-3", null);
        assertNotNull(out.get("ineligibleReason"));
        SqlRoutingJdbc far = visitDb(1);
        Map<String, Object> away = service(far.jdbc, CityMonopolyConfig.defaults(), 20).onVisit(7L, visitTerritory(), LAT + 0.01, LNG, 5.0, "ref-4", null);
        assertTrue(((List<?>) away.get("reasons")).contains("OUT_OF_RADIUS"), String.valueOf(away.get("reasons")));
        verifyNoInteractions(notify);
    }

    @Test
    void settleSendsOneRentSummaryPerHolderAndNoneForVoidedRent() {
        SqlRoutingJdbc db = new SqlRoutingJdbc();
        db.rows("FROM city_yield_event WHERE state = 'PENDING' AND board_day < ?", List.of(
                row("id", 1L, "holder_member_id", 99L, "visitor_member_id", 7L, "season_id", "s", "granted", 10L, "visitor_reward", 2L, "risk_flags", null),
                row("id", 2L, "holder_member_id", 99L, "visitor_member_id", 8L, "season_id", "s", "granted", 5L, "visitor_reward", 2L, "risk_flags", null),
                row("id", 3L, "holder_member_id", 98L, "visitor_member_id", 7L, "season_id", "s", "granted", 9L, "visitor_reward", 2L, "risk_flags", "RING")));
        Map<String, Object> out = service(db.jdbc, CityMonopolyConfig.defaults(), 20).settle();
        assertEquals(2, out.get("settled"));
        assertEquals(1, out.get("voided"));
        verify(notify).notifyCityTerritory(eq(99L), eq("RENT_SETTLED"), isNull(), isNull(), contains("15 积分"), eq(15L));
        verify(notify, never()).notifyCityTerritory(eq(98L), any(), any(), any(), any(), anyLong());
        verify(notify, never()).notifyCityTerritory(eq(7L), any(), any(), any(), any(), anyLong());
    }
}
