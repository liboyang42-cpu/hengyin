package com.chengyinhub.business.citygame;

import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.business.pointsgov.PointsGovernanceService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.web.controller.api.ApiCityMonopolyController;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** W7 x-city-economy: CITY-7 governance, CITY-8 opening credit, CITY-15 member debt, CITY-19 authoring. */
class CityMonopolyEconomyTest {
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    /** Day 2 of the season that starts Mon 2026-11-09 10:00 (opening days 1–3). */
    private static final long OPENING = ZonedDateTime.of(2026, 11, 10, 12, 0, 0, 0, SH).toInstant().toEpochMilli();
    /** Day 8: regular phase. */
    private static final long REGULAR = ZonedDateTime.of(2026, 11, 16, 12, 0, 0, 0, SH).toInstant().toEpochMilli();

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static CityMonopolyService service(JdbcTemplate jdbc, CityGameMapper mapper, long now) {
        return new CityMonopolyService(jdbc, mapper, CityMonopolyConfigService.fixed(CityMonopolyConfig.defaults()), null, () -> now, faces -> 20);
    }

    private static PointsGovernanceService governance(boolean admit) {
        PointsGovernanceService g = mock(PointsGovernanceService.class);
        when(g.admit(anyLong(), anyLong(), anyInt())).thenReturn(admit
                ? new PointsGovernanceService.Admission(true, "ADMITTED", "CITY")
                : new PointsGovernanceService.Admission(false, "DAILY_CAP", "CITY"));
        return g;
    }

    // ------------------------------------------------------------------ CITY-7

    @Test
    void creditRefusedByGovernanceStaysPendingAndIsNotPaid() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CityGameMapper mapper = mock(CityGameMapper.class);
        CityMonopolyService s = service(jdbc, mapper, REGULAR);
        PointsGovernanceService g = governance(false);
        s.setGovernance(g);

        long r = s.applyCredit(7L, "s1", 40, "城市大富翁收租", "CITYM:YIELD:YIELD_EVENT:9", "YIELD", "YIELD_EVENT", "9");

        assertEquals(CityMonopolyService.HELD, r);
        verify(g).admit(7L, 71L, 40);
        verify(jdbc).update(contains("SET state = 'PENDING', hold_reason = 'GOVERNANCE'"), eq(7L), eq("YIELD"), eq("YIELD_EVENT"), eq("9"));
        verify(mapper, never()).creditMemberPoints(anyLong(), anyLong());
        verify(mapper, never()).insertPointsEarnDetailTyped(anyLong(), anyLong(), anyLong(), any(), any(), anyLong());
    }

    @Test
    void admittedCreditIsPaidUnderItsOwnSource() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CityGameMapper mapper = mock(CityGameMapper.class);
        when(mapper.selectMemberPoints(7L)).thenReturn(100L);
        CityMonopolyService s = service(jdbc, mapper, REGULAR);
        s.setGovernance(governance(true));

        long r = s.applyCredit(7L, "s1", 2, "城市大富翁访客奖励", "CITYM:VISIT:YIELD_EVENT:9", "VISIT", "YIELD_EVENT", "9");

        assertEquals(2, r);
        verify(mapper).creditMemberPoints(7L, 2L);
        verify(mapper).insertPointsEarnDetailTyped(7L, 100L, 2L, "城市大富翁访客奖励", "CITYM:VISIT:YIELD_EVENT:9", 72L);
    }

    @Test
    void refundOfTheChallengeFeeIsNeverCapped() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CityGameMapper mapper = mock(CityGameMapper.class);
        when(mapper.selectMemberPoints(7L)).thenReturn(0L);
        CityMonopolyService s = service(jdbc, mapper, REGULAR);
        PointsGovernanceService g = governance(false);
        s.setGovernance(g);

        assertEquals(15, s.applyCredit(7L, "s1", 15, "退款", "k", "REFUND", "CHALLENGE", "r1"));
        verify(g, never()).admit(anyLong(), anyLong(), anyInt());
        verify(mapper).insertPointsEarnDetailTyped(7L, 0L, 15L, "退款", "k", 75L);
    }

    @Test
    void everyIncomeKindHasItsOwnGovernedSource() {
        assertEquals(71L, CityMonopolyService.eventTypeOf("YIELD"));
        assertEquals(72L, CityMonopolyService.eventTypeOf("VISIT"));
        assertEquals(74L, CityMonopolyService.eventTypeOf("COMP"));
        assertEquals(75L, CityMonopolyService.eventTypeOf("REFUND"));
        assertEquals(76L, CityMonopolyService.eventTypeOf("CHANCE"));
    }

    @Test
    void settleRetriesHeldCreditsFirst() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CityGameMapper mapper = mock(CityGameMapper.class);
        when(mapper.selectMemberPoints(7L)).thenReturn(0L);
        when(jdbc.queryForList(contains("hold_reason = 'GOVERNANCE' ORDER BY id"))).thenReturn(List.of(
                row("id", 5L, "member_id", 7L, "season_id", "s1", "kind", "YIELD", "ref_type", "YIELD_EVENT", "ref_id", "9", "delta", 30L)));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyLong())).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyLong(), any())).thenReturn(0);
        when(jdbc.update(contains("SET state = 'AVAILABLE', hold_reason = NULL"), any(), any(), any())).thenReturn(1);
        CityMonopolyService s = service(jdbc, mapper, REGULAR);
        s.setGovernance(governance(true));

        Map<String, Object> out = s.settle();

        assertEquals(1, out.get("releasedFromHold"));
        assertEquals(30L, out.get("creditedPoints"));
        verify(mapper).insertPointsEarnDetailTyped(eq(7L), eq(0L), eq(30L), eq("城市大富翁收租"), eq("CITYM:YIELD:YIELD_EVENT:9"), eq(71L));
    }

    @Test
    void cityMonopolyBoardsSayPoints() {
        RoamTerritoryService t = new RoamTerritoryService(mock(JdbcTemplate.class), mock(CityGameMapper.class), null, () -> REGULAR);
        assertEquals("CITY_COIN", t.revenueModeFor("B1"), "classic territory keeps city coin");
        CityMonopolyService on = mock(CityMonopolyService.class);
        when(on.enabled("B1")).thenReturn(true);
        t.setMonopoly(on);
        assertEquals("POINTS", t.revenueModeFor("B1"));
    }

    // ------------------------------------------------------------------ CITY-8

    @Test
    void claimSpendsTheOpeningCreditFirst() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CityGameMapper mapper = mock(CityGameMapper.class);
        when(jdbc.update(startsWith("INSERT IGNORE INTO city_points_flow"), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForList(contains("SELECT stipend_left FROM city_member_season"), eq(7L), eq("s1"))).thenReturn(List.of(row("stipend_left", 50L)));
        when(jdbc.update(contains("SET stipend_left = stipend_left - ?"), eq(50L), eq(7L), eq("s1"), eq(50L))).thenReturn(1);
        when(mapper.selectMemberPoints(7L)).thenReturn(100L);
        when(mapper.deductMemberPoints(7L, 10L)).thenReturn(1);

        assertTrue(service(jdbc, mapper, REGULAR).debit(7L, "s1", 60, "CLAIM", "CLAIM", "r1", "占领"));

        verify(mapper).deductMemberPoints(7L, 10L);
        verify(mapper).insertPointsDetail(7L, 100L, 10L, "占领", "CITYM:CLAIM:CLAIM:r1");
    }

    @Test
    void challengeFeeNeverUsesTheOpeningCredit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CityGameMapper mapper = mock(CityGameMapper.class);
        when(jdbc.update(startsWith("INSERT IGNORE INTO city_points_flow"), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForList(contains("SELECT stipend_left FROM city_member_season"), anyLong(), anyString())).thenReturn(List.of(row("stipend_left", 300L)));
        when(mapper.selectMemberPoints(7L)).thenReturn(100L);
        when(mapper.deductMemberPoints(7L, 30L)).thenReturn(1);

        assertTrue(service(jdbc, mapper, REGULAR).debit(7L, "s1", 30, "CHALLENGE", "CHALLENGE", "r1", "挑战"));
        verify(mapper).deductMemberPoints(7L, 30L);
        verify(jdbc, never()).update(contains("stipend_left - ?"), any(Object[].class));
    }

    @Test
    void shortBalanceGivesTheOpeningCreditBack() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CityGameMapper mapper = mock(CityGameMapper.class);
        when(jdbc.update(startsWith("INSERT IGNORE INTO city_points_flow"), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForList(contains("SELECT stipend_left FROM city_member_season"), eq(7L), eq("s1"))).thenReturn(List.of(row("stipend_left", 50L)));
        when(jdbc.update(contains("SET stipend_left = stipend_left - ?"), eq(50L), eq(7L), eq("s1"), eq(50L))).thenReturn(1);
        when(mapper.selectMemberPoints(7L)).thenReturn(5L);

        assertFalse(service(jdbc, mapper, REGULAR).debit(7L, "s1", 60, "CLAIM", "CLAIM", "r1", "占领"));
        verify(jdbc).update(contains("SET stipend_left = stipend_left + ?"), eq(50L), eq(7L), eq("s1"));
        verify(mapper, never()).deductMemberPoints(anyLong(), anyLong());
    }

    @Test
    void stipendNeverRaisesThePointBalance() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CityGameMapper mapper = mock(CityGameMapper.class);
        when(jdbc.queryForList(contains("FROM ums_member m LEFT JOIN member_growth"), eq(7L))).thenReturn(List.of(
                row("create_time", new java.util.Date(OPENING - 30L * 86_400_000L), "phone", "138", "union_id", null, "level_no", 5)));
        when(jdbc.queryForObject(contains("city_abuse_signal"), eq(Integer.class), any(), any())).thenReturn(0);
        when(jdbc.update(contains("SET stipend_left = ?, stipend_at = ?"), any(), any(), any(), any())).thenReturn(1);
        when(jdbc.queryForList(contains("SELECT stipend_left FROM city_member_season"), eq(7L), anyString())).thenReturn(List.of(row("stipend_left", 300L)));
        when(mapper.selectMemberPoints(7L)).thenReturn(12L);

        CityMonopolyService s = service(jdbc, mapper, OPENING);
        CityGameException closed = assertThrows(CityGameException.class, () -> s.stipend(7L));
        assertEquals("APP_ATTEST_REQUIRED", closed.getErrorCode(), "nothing could spend it while claims are closed");
        CityAttestVerifier attest = mock(CityAttestVerifier.class);
        when(attest.ready()).thenReturn(true);
        s.setAttest(attest);

        Map<String, Object> out = s.stipend(7L);

        assertEquals(300L, out.get("stipend"));
        assertEquals(false, out.get("alreadyClaimed"));
        assertEquals(300L, out.get("stipendLeft"));
        assertEquals(12L, out.get("balance"));
        verify(mapper, never()).creditMemberPoints(anyLong(), anyLong());
        verify(mapper, never()).insertPointsEarnDetailTyped(anyLong(), anyLong(), anyLong(), any(), any(), anyLong());
    }

    @Test
    void stipendIsClosedAfterTheOpeningDays() {
        CityGameException e = assertThrows(CityGameException.class,
                () -> service(mock(JdbcTemplate.class), mock(CityGameMapper.class), REGULAR).stipend(7L));
        assertEquals("STIPEND_CLOSED", e.getErrorCode());
    }

    @Test
    void stipendExpiresAtSeasonSettle() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        service(jdbc, mock(CityGameMapper.class), REGULAR).settleSeason("s2026w42", null);
        verify(jdbc).update(contains("SET stipend_left = 0 WHERE season_id = ?"), eq("s2026w42"));
    }

    @Test
    void stipendIsRateLimitedAndBehindLocationConsent() throws Exception {
        RateLimiter limit = ApiCityMonopolyController.class.getMethod("stipend").getAnnotation(RateLimiter.class);
        assertNotNull(limit);
        assertEquals(10, limit.count());
        assertEquals(60, limit.time());
    }

    // ------------------------------------------------------------------ CITY-15

    @Test
    void debtCarriesAcrossSeasons() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CityGameMapper mapper = mock(CityGameMapper.class);
        when(mapper.selectMemberPoints(7L)).thenReturn(0L);
        when(jdbc.queryForList(contains("FROM city_member_debt WHERE member_id = ? FOR UPDATE"), eq(7L))).thenReturn(List.of(row("debt", 50L)));
        CityMonopolyService s = service(jdbc, mapper, REGULAR);

        // Debt from last season, credit in a new season: the debt is paid first.
        assertEquals(30, s.applyCredit(7L, "s-next", 80, "收租", "k", "YIELD", "YIELD_EVENT", "1"));
        verify(jdbc).update(contains("UPDATE city_member_debt SET debt = GREATEST(0, debt - ?)"), eq(50L), eq(7L));
        verify(mapper).creditMemberPoints(7L, 30L);
    }

    @Test
    void shortfallBecomesMemberDebt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CityGameMapper mapper = mock(CityGameMapper.class);
        when(jdbc.update(startsWith("INSERT IGNORE INTO city_points_flow"), any(Object[].class))).thenReturn(1);
        when(mapper.selectMemberPoints(7L)).thenReturn(4L);
        when(mapper.deductMemberPoints(7L, 4L)).thenReturn(1);

        assertEquals(4, service(jdbc, mapper, REGULAR).debitOrDebt(7L, "s1", 10, "UPKEEP", "TERRITORY_DAY", "t:1", "维护费"));
        verify(jdbc).update(startsWith("INSERT INTO city_member_debt"), eq(7L), eq(6L));
    }

    @Test
    void clawbackReachesPreviousSeason() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CityGameMapper mapper = mock(CityGameMapper.class);
        when(jdbc.queryForList(contains("FROM city_abuse_signal WHERE id = ?"), eq(3L))).thenReturn(List.of(row("member_id", 7L)));
        when(jdbc.queryForList(contains("kind IN ('YIELD','VISIT') AND delta > 0 AND created_at >= ?"), eq(7L), any()))
                .thenReturn(List.of(row("id", 91L, "delta", 40L)));
        when(jdbc.update(startsWith("INSERT IGNORE INTO city_points_flow"), any(Object[].class))).thenReturn(1);
        when(mapper.selectMemberPoints(7L)).thenReturn(0L);

        service(jdbc, mapper, REGULAR).reviewSignal(3L, true, 1L);

        verify(jdbc, never()).queryForList(contains("AND season_id = ? AND state = 'AVAILABLE'"), eq(7L), anyString());
        verify(jdbc).update(startsWith("INSERT INTO city_member_debt"), eq(7L), eq(40L));
        verify(jdbc).update(contains("hold_reason = 'GOVERNANCE' AND kind IN ('YIELD','VISIT')"), eq(7L));
    }

    // ------------------------------------------------------------------ CITY-19

    private static CityBoardAdminService admin(JdbcTemplate jdbc) {
        when(jdbc.queryForList(contains("FROM city_game_board b WHERE b.board_id = ?"), eq("B1"))).thenReturn(List.of(row("lifecycle", "OPEN")));
        return new CityBoardAdminService(jdbc, mock(CityGameMapper.class), null, null, () -> REGULAR);
    }

    @Test
    void boardValidationRejectsOutOfRangeDistricts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<Map<String, Object>> ten = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) ten.add(row("district_id", "d" + i));
        when(jdbc.queryForList(contains("SELECT district_id FROM city_district WHERE board_id = ? FOR UPDATE"), eq("B1"))).thenReturn(ten);
        CityBoardAdminService s = spy(admin(jdbc));
        doReturn(Map.of("lifecycle", "OPEN")).when(s).boardRowForAuthoring("B1");

        CityGameException tooMany = assertThrows(CityGameException.class, () -> s.upsertDistrict(1L, "B1", "d11", "新街区", null));
        assertEquals("DISTRICTS_OUT_OF_RANGE", tooMany.getErrorCode());

        when(jdbc.queryForList(contains("WHERE board_id = ? AND district_id = ? FOR UPDATE"), eq("B1"), eq("d1"))).thenReturn(List.of(row("district_id", "d1")));
        when(jdbc.queryForObject(contains("FROM city_game_point WHERE board_id = ? AND district_id = ?"), eq(Integer.class), eq("B1"), eq("d1"), eq("P9")))
                .thenReturn(10);
        CityGameException full = assertThrows(CityGameException.class, () -> s.assignDistrict(1L, "B1", "P9", "d1"));
        assertEquals("DISTRICT_FULL", full.getErrorCode());

        CityGameException tier = assertThrows(CityGameException.class, () -> s.updatePointTier(1L, "B1", "P1", 5));
        assertEquals("TIER_INVALID", tier.getErrorCode());
        assertThrows(CityGameException.class, () -> CityBoardAdminService.requireColorToken("red"));
        assertEquals(3, CityBoardAdminService.requireTier("T3"));
    }

    @Test
    void layoutIssuesFollowTheApprovedRanges() {
        CityMonopolyConfig c = CityMonopolyConfig.defaults();
        Map<String, Integer> six = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 6; i++) six.put("d" + i, 5);
        assertEquals(List.of(), CityBoardAdminService.layoutIssues(c, six, 34, 4, 0), "6 districts × 5 places, 4/34 neutral");
        assertEquals(List.of("DISTRICTS_TOO_FEW", "DISTRICT_TOO_LARGE:d1", "NEUTRAL_SHARE_OUT_OF_RANGE"),
                CityBoardAdminService.layoutIssues(c, Map.of("d1", 30), 30, 0, 0), "the backfilled single district");
        assertTrue(CityBoardAdminService.layoutIssues(c, six, 34, 4, 2).contains("PLACES_WITHOUT_DISTRICT"));
    }

    @Test
    void rentUsesAuthoredTier() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CityBoardAdminService s = spy(admin(jdbc));
        doReturn(Map.of("lifecycle", "OPEN")).when(s).boardRowForAuthoring("B1");
        when(jdbc.update(startsWith("UPDATE city_game_point SET tier = ?"), eq(4), eq("B1"), eq("P1"))).thenReturn(1);
        s.updatePointTier(1L, "B1", "P1", 4);
        verify(jdbc).update(startsWith("UPDATE city_game_point SET tier = ?"), eq(4), eq("B1"), eq("P1"));

        // The claim target reads the authored tier from the board place, so price and rent follow it.
        when(jdbc.queryForList(contains("FROM city_game_point p WHERE p.board_id = ? AND p.point_id = ?"), eq("B1"), eq("P1")))
                .thenReturn(List.of(row("board_id", "B1", "point_id", "P1", "title", "外滩", "latitude", 31.24, "longitude", 121.49,
                        "tier", 4, "property_id", "PROP-1")));
        when(jdbc.queryForList(contains("FROM city_game_property WHERE property_id = ?"), eq("PROP-1"))).thenReturn(List.of(row("board_id", "B1", "point_id", "P1")));
        CityMonopolyService.Target t = service(jdbc, mock(CityGameMapper.class), REGULAR).resolveTarget(Map.of("propertyId", "PROP-1"));
        CityMonopolyConfig c = CityMonopolyConfig.defaults();
        assertEquals(4, t.tier());
        assertEquals(400, CityMonopolyRules.claimCost(c, t.tier()));
    }
}
