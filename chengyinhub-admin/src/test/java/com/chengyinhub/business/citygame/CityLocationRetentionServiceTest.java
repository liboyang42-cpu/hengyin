package com.chengyinhub.business.citygame;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HIGH-S7：领地签到 / 审计 / 大富翁证据的原始 GPS 过期后被粗化或置空，距离与判定保留。 */
class CityLocationRetentionServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CityLocationRetentionService svc = new CityLocationRetentionService(jdbc);
    private final Date cutoff = new Date(1_700_000_000_000L);

    @Test
    void checkinsAreCoarsenedBecauseCoordinatesAreNotNull() {
        when(jdbc.update(anyString(), eq(new Timestamp(cutoff.getTime())), eq(50))).thenReturn(7);
        assertEquals(7, svc.coarsenTerritoryCheckins(cutoff, 50));
        String sql = captureSql();
        assertTrue(sql.startsWith("UPDATE roam_territory_checkin "));
        assertTrue(sql.contains("latitude = ROUND(latitude, 2)"));
        assertTrue(sql.contains("longitude = ROUND(longitude, 2)"));
        assertTrue(sql.contains("accuracy_m = NULL"));
        assertTrue(sql.contains("create_time < ?") && sql.endsWith("LIMIT ?"));
        assertFalse(sql.contains("distance_m"));
        assertFalse(sql.contains("plausibility_status"));
    }

    @Test
    void auditsDropCoordinatesAndPreviousFixInDetailJson() {
        svc.clearTerritoryAudits(cutoff, 50);
        String sql = captureSql();
        assertTrue(sql.startsWith("UPDATE roam_territory_audit "));
        assertTrue(sql.contains("latitude = NULL, longitude = NULL, accuracy_m = NULL"));
        assertTrue(sql.contains("JSON_REMOVE(detail_json, '$.prevLat', '$.prevLng')"));
        assertTrue(sql.contains("JSON_CONTAINS_PATH(detail_json, 'one', '$.prevLat', '$.prevLng')"));
        assertFalse(sql.contains("distance_m"));
        assertFalse(sql.contains("verdict"));
    }

    @Test
    void claimEvidenceDropsCoordinatesButKeepsVerdict() {
        svc.clearClaimEvidence(cutoff, 50);
        String sql = captureSql();
        assertTrue(sql.startsWith("UPDATE city_claim_evidence "));
        assertTrue(sql.contains("lat = NULL, lng = NULL, h_acc = NULL"));
        assertTrue(sql.contains("created_at < ?") && sql.endsWith("LIMIT ?"));
        assertFalse(sql.contains("verdict"));
    }

    private String captureSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(new Timestamp(cutoff.getTime())), eq(50));
        return sql.getValue();
    }
}
