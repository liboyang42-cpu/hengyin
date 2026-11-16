package com.chengyinhub.business.citygame;

import com.chengyinhub.business.locationtrust.ClaimEvidenceRow;
import com.chengyinhub.business.locationtrust.ClaimEvidenceService;
import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.business.service.NativeAppAttestService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** W7 x-city-monopoly: CITY-1, 2, 3, 4, 6, 9, 10 service behaviour without a database. */
class CityMonopolyEndToEndFixTest {
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final long NOW = ZonedDateTime.of(2026, 11, 16, 12, 0, 0, 0, SH).toInstant().toEpochMilli();

    private CityMonopolyService service(JdbcTemplate jdbc, CityMonopolyConfig c) {
        return new CityMonopolyService(jdbc, mock(CityGameMapper.class), CityMonopolyConfigService.fixed(c), null, () -> NOW, faces -> 20);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static void stubPoint(JdbcTemplate jdbc) {
        when(jdbc.queryForList(contains("FROM city_game_point p WHERE p.board_id = ? AND p.point_id = ?"), eq("B1"), eq("PT1")))
                .thenReturn(List.of(row("board_id", "B1", "point_id", "PT1", "title", "老街", "latitude", 31.2304, "longitude", 121.4737,
                        "tier", 2, "property_id", "PROP-1")));
    }

    // ------------------------------------------------------------------ CITY-1 targets

    @Test
    void resolveTargetAcceptsBoardPropertyId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM city_game_property WHERE property_id = ?"), eq("PROP-1")))
                .thenReturn(List.of(row("board_id", "B1", "point_id", "PT1")));
        stubPoint(jdbc);
        CityMonopolyService.Target t = service(jdbc, CityMonopolyConfig.defaults()).resolveTarget(Map.of("propertyId", "PROP-1"));
        assertEquals("board:B1:PT1", t.key());
        assertEquals(2, t.tier());
    }

    @Test
    void resolveTargetAcceptsTerritoryId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM roam_territory WHERE territory_id = ?"), eq("T-1")))
                .thenReturn(List.of(row("board_id", "B1", "point_id", "PT1", "poi_id", null)));
        stubPoint(jdbc);
        CityMonopolyService.Target t = service(jdbc, CityMonopolyConfig.defaults()).resolveTarget(Map.of("territoryId", "T-1"));
        assertEquals("board:B1:PT1", t.key());
    }

    @Test
    void unknownAliasStillAsksForATarget() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CityGameException e = assertThrows(CityGameException.class,
                () -> service(jdbc, CityMonopolyConfig.defaults()).resolveTarget(Map.of("propertyId", "nope")));
        assertEquals("TARGET_REQUIRED", e.getErrorCode());
    }

    // ------------------------------------------------------------------ CITY-1 server photo evidence

    private static ClaimEvidenceRow evidence(String verdict, String placeRef) {
        ClaimEvidenceRow r = new ClaimEvidenceRow();
        r.setId(77L);
        r.setMemberId(7L);
        r.setPlaceRef(placeRef);
        r.setPlaceLat(new BigDecimal("31.230400"));
        r.setPlaceLng(new BigDecimal("121.473700"));
        r.setCaptureSource("CAMERA");
        r.setPhotoPhash(0x0f0f0f0f0f0f0f0fL);
        r.setPhotoTakenAt(new Date(NOW - 20_000L));
        r.setPhotoLat(new BigDecimal("31.230410"));
        r.setPhotoLng(new BigDecimal("121.473710"));
        r.setVerdict(verdict);
        return r;
    }

    private static CityMonopolyService.Target target() {
        return new CityMonopolyService.Target("board:B1:PT1", null, "B1", "PT1", "PROP-1", "老街", 31.2304, 121.4737, 1, null, null, null);
    }

    private static CityMonopolyRules.Evidence clientEvidence() {
        return new CityMonopolyRules.Evidence(31.2304, 121.4737, 8.0, List.of(), false, false, false,
                true, NOW, 31.2304, 121.4737, 123L, 0.0);
    }

    @Test
    void claimWithAcceptedEvidenceUsesServerPhotoFacts() {
        ClaimEvidenceService ces = mock(ClaimEvidenceService.class);
        when(ces.readRow(7L, 77L)).thenReturn(evidence("ACCEPT", "board:B1:PT1"));
        CityMonopolyService s = service(mock(JdbcTemplate.class), CityMonopolyConfig.defaults());
        s.setClaimEvidence(ces);
        CityMonopolyService.ServerPhoto p = s.serverPhoto(7L, Map.of("evidenceId", "77"), target(), clientEvidence(), CityMonopolyConfig.defaults());
        assertEquals("ACCEPT", p.verdict());
        assertTrue(p.evidence().inAppCamera());
        assertEquals(0x0f0f0f0f0f0f0f0fL, p.evidence().phash(), "phash from the server, not the client's 123");
        assertEquals(NOW - 20_000L, p.evidence().capturedAtMillis());
        assertEquals(31.23041, p.evidence().captureLat(), 1e-9);
    }

    @Test
    void claimWithReviewEvidenceKeepsTheReviewVerdict() {
        ClaimEvidenceService ces = mock(ClaimEvidenceService.class);
        when(ces.readRow(7L, 77L)).thenReturn(evidence("REVIEW", null));
        CityMonopolyService s = service(mock(JdbcTemplate.class), CityMonopolyConfig.defaults());
        s.setClaimEvidence(ces);
        CityMonopolyService.ServerPhoto p = s.serverPhoto(7L, Map.of("evidenceId", 77), target(), clientEvidence(), CityMonopolyConfig.defaults());
        assertEquals("REVIEW", p.verdict(), "no placeRef, but the evidence place is the target's coordinates");
    }

    @Test
    void clientPhotoFieldsIgnoredWhenServerEvidenceIsMissingOrElsewhere() {
        ClaimEvidenceService ces = mock(ClaimEvidenceService.class);
        ClaimEvidenceRow far = evidence("ACCEPT", "poi:9");
        far.setPlaceLat(new BigDecimal("31.300000"));
        when(ces.readRow(7L, 78L)).thenReturn(far);
        CityMonopolyService s = service(mock(JdbcTemplate.class), CityMonopolyConfig.defaults());
        s.setClaimEvidence(ces);
        CityMonopolyService.ServerPhoto missing = s.serverPhoto(7L, Map.of(), target(), clientEvidence(), CityMonopolyConfig.defaults());
        assertEquals("MISSING", missing.verdict());
        assertFalse(missing.evidence().inAppCamera(), "client inAppCamera ignored");
        assertNull(missing.evidence().phash(), "client phash ignored");
        CityMonopolyService.ServerPhoto other = s.serverPhoto(7L, Map.of("evidenceId", 78L), target(), clientEvidence(), CityMonopolyConfig.defaults());
        assertEquals("OTHER_PLACE", other.verdict());
    }

    @Test
    void parseEvidenceKeepsTheEvidenceIdForTheServerLookup() {
        Map<String, Object> ev = new HashMap<>();
        ev.put("lat", 31.2304);
        ev.put("lng", 121.4737);
        ev.put("evidenceId", "77");
        assertEquals(77L, CityMonopolyService.num(ev, "evidenceId"));
        assertNotNull(CityMonopolyService.parseEvidence(Map.of("evidence", ev)));
    }

    // ------------------------------------------------------------------ CITY-2 attest

    @Test
    void capabilitiesSayClaimsAreClosedWithoutAVerifier() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("city_abuse_signal"), eq(Integer.class), any(), any())).thenReturn(0);
        CityMonopolyService s = service(jdbc, CityMonopolyConfig.defaults());
        s.setClaimEvidence(mock(ClaimEvidenceService.class));
        Map<String, Object> cap = s.capabilities(7L);
        assertEquals(false, cap.get("claimOpen"));
        assertEquals(false, cap.get("challengeOpen"));
        assertEquals(false, cap.get("chanceOpen"));
        @SuppressWarnings("unchecked") List<String> reasons = (List<String>) cap.get("reasons");
        assertTrue(reasons.contains("ATTEST_UNAVAILABLE"));
        assertTrue(reasons.contains("TASK_VERIFIER_UNAVAILABLE"));
    }

    @Test
    void capabilitiesOpenWithAReadyVerifierOrSignalMode() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("city_abuse_signal"), eq(Integer.class), any(), any())).thenReturn(0);
        CityMonopolyService s = service(jdbc, CityMonopolyConfig.defaults());
        s.setClaimEvidence(mock(ClaimEvidenceService.class));
        s.setAttest((m, k, p, a) -> true);
        assertEquals(true, s.capabilities(7L).get("claimOpen"));

        CityMonopolyService signal = service(jdbc, CityMonopolyConfig.withOverrides(Map.of("city.monopoly.claim.attest_mode", "SIGNAL")));
        signal.setClaimEvidence(mock(ClaimEvidenceService.class));
        assertEquals(true, signal.capabilities(7L).get("claimOpen"), "SIGNAL lets the device through (claims go to review)");
        assertEquals("REQUIRED", CityMonopolyConfig.withOverrides(Map.of("city.monopoly.claim.attest_mode", "OFF")).str("city.monopoly.claim.attest_mode"),
                "an unknown mode fails closed to REQUIRED");
    }

    @Test
    void appAttestCityVerifierRefusesWhileAppAttestIsNotConfigured() {
        NativeAppAttestService native_ = mock(NativeAppAttestService.class);
        when(native_.isReady()).thenReturn(false);
        AppAttestCityVerifier v = new AppAttestCityVerifier(native_);
        assertFalse(v.ready());
        assertFalse(v.verify(7L, "k".repeat(44), CityAttestVerifier.canonicalPayload("CLAIM", 7, "R", "Q", "k".repeat(44)), "AAAA"));
        verify(native_, never()).verifyCityAssertion(anyLong(), any(), any(), any());
    }

    @Test
    void appAttestCityVerifierDelegatesTheExactPayload() {
        NativeAppAttestService native_ = mock(NativeAppAttestService.class);
        when(native_.isReady()).thenReturn(true);
        String key = "k".repeat(44);
        String payload = CityAttestVerifier.canonicalPayload("CLAIM", 7, "R", "Q", key);
        when(native_.verifyCityAssertion(7L, key, payload, "AAAA")).thenReturn(true);
        assertTrue(new AppAttestCityVerifier(native_).verify(7L, key, payload, "AAAA"));
    }

    // ------------------------------------------------------------------ CITY-3 / CITY-6 reads

    @Test
    void nextSettleIsTheComingOvernightCheck() {
        long at = CityMonopolyService.nextSettleAt(CityMonopolyConfig.defaults(), NOW);
        ZonedDateTime z = Instant.ofEpochMilli(at).atZone(SH);
        assertEquals(17, z.getDayOfMonth());
        assertEquals(3, z.getHour());
        long early = ZonedDateTime.of(2026, 11, 16, 1, 0, 0, 0, SH).toInstant().toEpochMilli();
        assertEquals(16, Instant.ofEpochMilli(CityMonopolyService.nextSettleAt(CityMonopolyConfig.defaults(), early)).atZone(SH).getDayOfMonth());
    }

    @Test
    void territoryIdOfFallsBackFromBoardPropertyToTheActiveTerritory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("WHERE territory_id = ? LIMIT 1"), eq(String.class), eq("PROP-1"))).thenReturn(List.of());
        when(jdbc.queryForList(contains("WHERE property_id = ? AND status = 'ACTIVE'"), eq(String.class), eq("PROP-1"))).thenReturn(List.of("T-9"));
        CityMonopolyService s = service(jdbc, CityMonopolyConfig.defaults());
        assertEquals("T-9", s.territoryIdOf(Map.of("propertyId", "PROP-1")));
        assertEquals("T-1", s.territoryIdOf(Map.of("territoryId", "T-1", "propertyId", "PROP-1")), "territoryId wins");
    }

    // ------------------------------------------------------------------ CITY-9 legacy

    @Test
    void legacyRowPaysNoRent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> terr = row("territory_id", "T-1", "owner_member_id", 42L, "board_id", null, "rules_kind", "LEGACY");
        Map<String, Object> out = service(jdbc, CityMonopolyConfig.defaults()).onVisit(8L, terr, 31.0, 121.0, 5.0, "ref", null);
        assertEquals("LEGACY_TERRITORY", out.get("ineligibleReason"));
        assertEquals(0L, out.get("yield"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void legacyTerritoryIsConvertedToAChallengeableProperty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("SELECT tier, district_id FROM city_game_point"), eq("B1"), eq("PT1")))
                .thenReturn(List.of(row("tier", 3, "district_id", "D1")));
        when(jdbc.update(contains("SET rules_kind = 'MONOPOLY'"), any(Object[].class))).thenReturn(1);
        CityMonopolyService s = service(jdbc, CityMonopolyConfig.defaults());
        Map<String, Object> terr = row("territory_id", "T-1", "owner_member_id", 42L, "board_id", "B1", "point_id", "PT1",
                "rules_kind", "LEGACY", "status", "ACTIVE");
        assertTrue(s.adoptLegacy(terr, NOW));
        assertEquals("MONOPOLY", terr.get("rules_kind"));
        long claimT3 = CityMonopolyRules.claimCost(CityMonopolyConfig.defaults(), 3);
        verify(jdbc).update(contains("WHERE territory_id = ? AND status = 'ACTIVE' AND rules_kind = 'LEGACY'"),
                eq(3), eq("D1"), eq(claimT3), any(), eq("s2026w46"), any(), any(), eq("T-1"));
    }

    @Test
    void legacyConversionHonoursItsSwitchAndLeavesMonopolyRowsAlone() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CityMonopolyService off = service(jdbc, CityMonopolyConfig.withOverrides(Map.of("city.monopoly.legacy.convert", "false")));
        assertFalse(off.adoptLegacy(row("territory_id", "T-1", "board_id", "B1", "rules_kind", "LEGACY", "status", "ACTIVE"), NOW));
        CityMonopolyService on = service(jdbc, CityMonopolyConfig.defaults());
        assertFalse(on.adoptLegacy(row("territory_id", "T-2", "board_id", "B1", "rules_kind", "MONOPOLY", "status", "ACTIVE"), NOW));
        assertFalse(on.adoptLegacy(row("territory_id", "T-3", "rules_kind", "LEGACY", "status", "ACTIVE"), NOW), "no place: nothing to convert");
        verifyNoInteractions(jdbc);
    }

    // ------------------------------------------------------------------ CITY-4 check-in receipt

    @Test
    void checkinReplayAnswersInTheFirstAnswersShape() {
        Map<String, Object> stored = row("checkin_id", "C-1", "territory_id", "T-1", "owner_member_id", 42L, "points_spent", 0L,
                "owner_revenue_points", 3L, "revenue_mode", "POINTS", "plausibility_status", "PASS", "distance_m", 12.34);
        Map<String, Object> out = RoamTerritoryService.checkinReplay(stored, 8L);
        assertEquals(true, out.get("idempotentReplay"));
        assertEquals(false, out.get("cooldownActive"));
        assertEquals(false, out.get("selfVisit"));
        assertEquals("C-1", out.get("checkinId"));
        assertEquals("PENDING", out.get("rentState"));
        assertEquals(12.3, out.get("distanceM"));
        assertFalse(out.keySet().stream().anyMatch(k -> k.contains("_")), "no snake_case keys");
    }

    // ------------------------------------------------------------------ CITY-10 one holder

    @Test
    void soloClaimDoesNotOverwriteTeamHolding() {
        CityGameMapper mapper = mock(CityGameMapper.class);
        when(mapper.selectBoardForUpdate("B1")).thenReturn(row("lifecycle", "OPEN"));
        when(mapper.selectPropertyForUpdate("B1", "PROP-1")).thenReturn(row("holderTeamId", "TEAM-A", "stateVersion", 3L));
        when(mapper.selectParticipation("B1", 7L)).thenReturn(null);
        RoamTerritoryService t = new RoamTerritoryService(mock(JdbcTemplate.class), mapper, null, () -> NOW);
        t.syncBoardPropertyOnClaim("B1", "PROP-1", "PT1", 7L, "T-1", "p", new java.sql.Timestamp(NOW + 3_600_000L));
        verify(mapper, never()).updateProperty(any(), any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void takeoverUpdatesBoardProjection() {
        CityGameMapper mapper = mock(CityGameMapper.class);
        when(mapper.selectBoardForUpdate("B1")).thenReturn(row("lifecycle", "OPEN"));
        when(mapper.selectPropertyForUpdate("B1", "PROP-1")).thenReturn(row("holderTeamId", "TEAM-A", "stateVersion", 3L));
        when(mapper.selectParticipation("B1", 7L)).thenReturn(row("teamId", "TEAM-B"));
        when(mapper.selectParticipation("B1", 42L)).thenReturn(row("teamId", "TEAM-A"));
        when(mapper.selectBoard("B1")).thenReturn(row("eventSequence", 5L, "boardVersion", 9L));
        RoamTerritoryService t = new RoamTerritoryService(mock(JdbcTemplate.class), mapper, null, () -> NOW);
        t.projectBoardHolder("B1", "PROP-1", "PT1", 7L, "T-2", "p", new java.sql.Timestamp(NOW + 3_600_000L), 42L);
        verify(mapper).updateProperty(eq("B1"), eq("PROP-1"), eq(3L), eq("PROTECTED"), eq("TEAM-B"), any(), any(), any());
    }

    @Test
    void liveBoardRefusesTeamAcquire() {
        CityGameMapper mapper = mock(CityGameMapper.class);
        when(mapper.selectBoard("B1")).thenReturn(row("lifecycle", "OPEN"));
        CityGameActionService actions = new CityGameActionService(mapper, n -> 0, () -> NOW);
        actions.setMonopoly(service(mock(JdbcTemplate.class), CityMonopolyConfig.defaults()));
        CityGameException e = assertThrows(CityGameException.class, () -> actions.quote(7L,
                Map.of("action", "ACQUIRE_PROPERTY", "boardId", "B1", "targetId", "PROP1")));
        assertEquals("PROPERTY_IS_CITY_MONOPOLY", e.getErrorCode());

        CityGameActionService teamMode = new CityGameActionService(mapper, n -> 0, () -> NOW);
        teamMode.setMonopoly(service(mock(JdbcTemplate.class), CityMonopolyConfig.withOverrides(Map.of("city.monopoly.enabled", "false"))));
        CityGameException other = assertThrows(CityGameException.class, () -> teamMode.quote(7L,
                Map.of("action", "ACQUIRE_PROPERTY", "boardId", "B1", "targetId", "PROP1")));
        assertNotEquals("PROPERTY_IS_CITY_MONOPOLY", other.getErrorCode(), "team game unchanged when City Monopoly is off");
    }
}
