package com.chengyinhub.business.citygame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chengyinhub.business.mapper.CityGameMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantgap.MerchantGapH2;
import com.chengyinhub.common.exception.ServiceException;

/** merchant/city-value/{report, result/{id}, events, event/{id}/benefit}. */
class MerchantCityValueReportServiceTest {
    static final long OWNER = 9002L, STORE = 8001L, OTHER = 9005L;

    MerchantGapH2 db;
    MerchantCityValueReportService service;

    @BeforeEach
    void setUp() throws Exception {
        db = MerchantGapH2.create(
                "CREATE TABLE city_game_point (board_id VARCHAR(96), point_id VARCHAR(96), merchant_id BIGINT, del_flag INT)",
                "CREATE TABLE city_value_visit (visit_id CHAR(36) PRIMARY KEY, member_id BIGINT, board_id VARCHAR(96), point_id VARCHAR(96), entered_at DATETIME(3), closed TINYINT, feedback_kind VARCHAR(16), reason_code VARCHAR(32), rating INT)",
                "INSERT INTO city_game_point VALUES ('b1', 'p1', 8001, 0), ('b1', 'p2', 8001, 0), ('b1', 'p9', 8009, 0)");
        visit("v1", "p1", "2026-11-13T02:00:00Z", "SERVICE_RATING", null, 80);
        visit("v2", "p1", "2026-11-13T03:00:00Z", "SKIP", "no_time", null);
        visit("v3", "p2", "2026-11-13T04:00:00Z", null, null, null);
        visit("v4", "p9", "2026-11-13T04:00:00Z", "SERVICE_RATING", null, 10);
        visit("v5", "p1", "2026-11-20T04:00:00Z", "EXIT", "not_interested", null);
        CityGameMapper mapper = mock(CityGameMapper.class);
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("regionId", "r1");
        board.put("boardId", "b1");
        board.put("seasonId", "s1");
        board.put("rulesReleaseId", "rel1");
        board.put("lifecycle", "OPEN");
        when(mapper.selectCurrentBoard("r1")).thenReturn(board);
        CityR491Store store = new CityR491Store(mapper, db.jdbc, db.transactions, () -> 1_763_000_000_000L);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(eq(OWNER), anyString())).thenReturn(new MerchantAccessContext(OWNER, STORE, OWNER, MerchantRoleCode.MERCHANT_OWNER));
        when(access.require(eq(OTHER), anyString())).thenReturn(new MerchantAccessContext(OTHER, 8005L, OTHER, MerchantRoleCode.MERCHANT_OWNER));
        when(access.require(eq(1L), anyString())).thenThrow(new ServiceException("no", 403));
        service = new MerchantCityValueReportService(store, access);
    }

    void visit(String id, String point, String at, String kind, String reason, Integer rating) {
        db.jdbc.update("INSERT INTO city_value_visit VALUES (?,?,?,?,?,?,?,?,?)", id, 9004L, "b1", point,
                Timestamp.from(Instant.parse(at)), kind == null ? 0 : 1, kind, reason, rating);
    }

    static Map<String, String> q(String... extra) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("regionId", "r1");
        q.put("boardId", "b1");
        q.put("seasonId", "s1");
        q.put("rulesReleaseId", "rel1");
        q.put("from", "2026-11-13T00:00:00Z");
        q.put("to", "2026-11-14T00:00:00Z");
        for (int i = 0; i < extra.length; i += 2) q.put(extra[i], extra[i + 1]);
        return q;
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportCountsOnlyThisStoresVisitsInTheWindowAndReplaysByRequestId() {
        Map<String, Object> report = service.report(OWNER, q("requestId", "rq1"));
        assertEquals("REPORTED", report.get("status"));
        assertEquals(STORE, ((Number) report.get("merchantId")).longValue());
        assertEquals(MerchantCityValueReportService.SOURCE, report.get("sourceCollection"));
        Map<String, Map<String, Object>> funnel = (Map<String, Map<String, Object>>) report.get("funnel");
        assertEquals(3, funnel.get("entry").get("visitCount"));
        assertEquals("2/3", funnel.get("meaningfulAction").get("rate"));
        assertEquals("1/3", funnel.get("serviceSuccess").get("rate"));
        assertEquals(Map.of("status", "MISSING_COVERAGE"), funnel.get("publicGoal"));
        assertEquals("80/1", ((Map<String, Object>) report.get("serviceRatings")).get("mean"));
        assertEquals(Map.of("status", "MISSING_DEFINITION"), report.get("byCohort"));
        Map<String, Object> skip = (Map<String, Object>) ((Map<String, Object>) report.get("skipExitReasons")).get("skip");
        assertEquals(List.of(Map.of("reasonCode", "no_time", "visitCount", 1)), skip.get("reasons"));
        Map<String, Object> exit = (Map<String, Object>) ((Map<String, Object>) report.get("skipExitReasons")).get("exit");
        assertEquals(List.of(), exit.get("reasons"));
        assertEquals(Map.of("status", "MISSING_UNIT"), ((Map<String, Object>) report.get("visitCost")).get("netPoints"));

        Map<String, Object> replay = service.report(OWNER, q("requestId", "rq1"));
        assertEquals(((Number) report.get("resultId")).longValue(), ((Number) replay.get("resultId")).longValue());
        MerchantGapException reused = assertThrows(MerchantGapException.class,
                () -> service.report(OWNER, q("requestId", "rq1", "to", "2026-11-15T00:00:00Z")));
        assertEquals("DUPLICATE_REQUEST", reused.getErrorCode());
    }

    @Test
    void resultReadsTheStoredReportOnlyInItsScope() {
        long id = ((Number) service.report(OWNER, q("requestId", "rq2")).get("resultId")).longValue();
        Map<String, Object> read = service.result(OWNER, id, Map.of("regionId", "r1", "rulesReleaseId", "rel1"));
        assertEquals(id, ((Number) read.get("resultId")).longValue());
        assertEquals("SCOPE_MISMATCH", assertThrows(MerchantGapException.class,
                () -> service.result(OWNER, id, Map.of("regionId", "r1", "rulesReleaseId", "rel2"))).getErrorCode());
        assertEquals(404, assertThrows(MerchantGapException.class,
                () -> service.result(OTHER, id, Map.of("regionId", "r1", "rulesReleaseId", "rel1"))).getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventsFreezeASnapshotAndPageWithBoundCursors() {
        Map<String, Object> page = service.events(OWNER, q("cursor", ""));
        assertEquals("PAGE", page.get("status"));
        assertEquals("NEW_SNAPSHOT_REQUIRED", page.get("lateEvents"));
        List<Map<String, Object>> events = (List<Map<String, Object>>) page.get("events");
        assertEquals(List.of("ENTRY", "MEANINGFUL_ACTION", "SERVICE_SUCCESS", "SERVICE_RATING", "ENTRY", "MEANINGFUL_ACTION", "SKIP", "ENTRY"),
                events.stream().map(e -> e.get("kind")).toList());
        String retry = (String) page.get("retryCursor");
        assertTrue(retry.matches("v2\\.[1-9][0-9]*\\.0\\.[0-9a-f]{64}"));
        assertNull(page.get("nextCursor"));
        Map<String, Object> again = service.events(OWNER, q("cursor", retry));
        assertEquals(page.get("snapshotId"), again.get("snapshotId"));
        assertEquals(events, again.get("events"));
        String forged = retry.substring(0, retry.length() - 1) + (retry.endsWith("0") ? "1" : "0");
        assertEquals(400, assertThrows(MerchantGapException.class, () -> service.events(OWNER, q("cursor", forged))).getStatus());
        assertNotEquals(page.get("snapshotId"), service.events(OWNER, q("cursor", "")).get("snapshotId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void benefitOfAServiceSuccessIsSourceUnavailableNeverAGrant() {
        Map<String, Object> page = service.events(OWNER, q("cursor", ""));
        String snapshot = String.valueOf(page.get("snapshotId"));
        List<Map<String, Object>> events = (List<Map<String, Object>>) page.get("events");
        Map<String, Object> success = events.stream().filter(e -> "SERVICE_SUCCESS".equals(e.get("kind"))).findFirst().orElseThrow();
        Map<String, Object> record = service.benefit(OWNER, (Long) success.get("id"), q("snapshotId", snapshot));
        assertEquals("BENEFIT_RECORD", record.get("status"));
        assertEquals("SOURCE_UNAVAILABLE", record.get("evidenceStatus"));
        assertEquals(success.get("visitId"), record.get("visitId"));
        assertNull(record.get("couponId"));
        Map<String, Object> entry = events.get(0);
        assertEquals(409, assertThrows(MerchantGapException.class, () -> service.benefit(OWNER, (Long) entry.get("id"), q("snapshotId", snapshot))).getStatus());
    }

    @Test
    void scopeAndWindowAreChecked() {
        assertEquals("SCOPE_STALE", assertThrows(MerchantGapException.class, () -> service.report(OWNER, q("requestId", "x1", "rulesReleaseId", "rel0"))).getErrorCode());
        assertEquals("INVALID_WINDOW", assertThrows(MerchantGapException.class, () -> service.report(OWNER, q("requestId", "x2", "to", "2026-12-25T00:00:00Z"))).getErrorCode());
        assertEquals("NO_BOARD_RELATION", assertThrows(MerchantGapException.class, () -> service.report(OTHER, q("requestId", "x3"))).getErrorCode());
        assertEquals(403, assertThrows(MerchantGapException.class, () -> service.report(1L, q("requestId", "x4"))).getStatus());
    }
}
