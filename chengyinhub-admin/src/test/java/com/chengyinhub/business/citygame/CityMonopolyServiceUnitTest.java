package com.chengyinhub.business.citygame;

import com.chengyinhub.business.mapper.CityGameMapper;
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

/** Service wiring without a database: legacy claim closed, owner-scoped self visit, evidence parsing, seasons. */
class CityMonopolyServiceUnitTest {
    private static final long NOW = ZonedDateTime.of(2026, 11, 16, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();

    private CityMonopolyService service(JdbcTemplate jdbc, CityMonopolyConfig c) {
        return new CityMonopolyService(jdbc, mock(CityGameMapper.class), CityMonopolyConfigService.fixed(c), null, () -> NOW, faces -> 20);
    }

    @Test
    void legacyClientPricedClaimIsClosedWhenMonopolyIsEnabled() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RoamTerritoryService territories = new RoamTerritoryService(jdbc, mock(CityGameMapper.class), null, () -> NOW);
        territories.setMonopoly(service(jdbc, CityMonopolyConfig.defaults()));
        CityGameException e = assertThrows(CityGameException.class, () -> territories.claim(7L, Map.of(
                "requestId", "REQ-1", "photoUrl", "x", "lat", 31.0, "lng", 121.0, "checkinTollPoints", 500, "ownerRevenuePoints", 500)));
        assertEquals("CLAIM_USE_MONOPOLY", e.getErrorCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void legacyClaimStillWorksWhenMonopolyIsSwitchedOff() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RoamTerritoryService territories = new RoamTerritoryService(jdbc, mock(CityGameMapper.class), null, () -> NOW);
        territories.setMonopoly(service(jdbc, CityMonopolyConfig.withOverrides(Map.of("city.monopoly.enabled", "false"))));
        CityGameException e = assertThrows(CityGameException.class, () -> territories.claim(7L, Map.of("requestId", "bad id!")));
        assertEquals("TERRITORY_INVALID", e.getErrorCode(), "falls through to the legacy validation");
    }

    @Test
    void ownerCheckInOnlyRefreshesTheVisitClockWithOwnerInWhere() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CityMonopolyService s = service(jdbc, CityMonopolyConfig.defaults());
        Map<String, Object> terr = new HashMap<>();
        terr.put("territory_id", "T-1");
        terr.put("owner_member_id", 42L);
        terr.put("board_id", null);
        Map<String, Object> out = s.onVisit(42L, terr, 31.0, 121.0, 5.0, "ref", null);
        assertEquals("SELF_VISIT", out.get("ineligibleReason"));
        assertEquals(0L, out.get("yield"));
        assertEquals("POINTS", out.get("currency"));
        verify(jdbc).update(contains("WHERE territory_id = ? AND owner_member_id = ?"), any(), eq("T-1"), eq(42L));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void evidenceParsingIsStrict() {
        Map<String, Object> ev = new HashMap<>();
        ev.put("lat", 31.2304);
        ev.put("lng", 121.4737);
        ev.put("hAcc", 8.0);
        ev.put("samples", List.of(Map.of("t", NOW - 90_000L, "lat", 31.2304, "lng", 121.4737), Map.of("t", NOW, "lat", 91.0, "lng", 121.0)));
        ev.put("simulated", true);
        ev.put("inAppCamera", true);
        ev.put("capturedAt", NOW - 10_000L);
        ev.put("phash", "00ff00ff00ff00ff");
        ev.put("attestAssertion", "forged-but-unverified");
        CityMonopolyRules.Evidence e = CityMonopolyService.parseEvidence(Map.of("evidence", ev));
        assertEquals(1, e.samples().size(), "invalid samples dropped");
        assertTrue(e.simulated());
        assertFalse(e.attestVerified(), "client cannot assert its own attestation");
        assertEquals(0x00ff00ff00ff00ffL, e.phash());
        assertThrows(CityGameException.class, () -> CityMonopolyService.parseEvidence(Map.of()));
        assertThrows(CityGameException.class, () -> CityMonopolyService.parseEvidence(Map.of("evidence", Map.of("lat", 0.0, "lng", 0.0))));
    }

    @Test
    void seasonComesFromTheConfiguredEpoch() {
        CityMonopolyService s = service(mock(JdbcTemplate.class), CityMonopolyConfig.defaults());
        CityMonopolyRules.Season season = s.season(CityMonopolyConfig.defaults(), NOW);
        assertEquals("s2026w46", season.seasonId());
    }

    @Test
    void maskingHidesMemberIds() {
        assertEquals("玩家1***9", CityMonopolyService.mask(12349));
        assertFalse(CityMonopolyService.mask(12349).contains("234"));
    }

    @Test
    void attestCanonicalPayloadBindsMemberRequestAndQuote() {
        assertEquals("CHENGYIN_CITY_MONOPOLY_V1\nCLAIM\n7\nREQ\nQH\nKEY", CityAttestVerifier.canonicalPayload("CLAIM", 7, "REQ", "QH", "KEY"));
    }
}
