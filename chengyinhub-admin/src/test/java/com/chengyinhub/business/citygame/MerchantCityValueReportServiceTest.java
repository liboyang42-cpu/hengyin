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
                "CREATE TABLE city_value_visit (visit_id CHAR(36) PRIMARY KEY, member_id BIGINT, board_id VARCHAR(96), point_id VARCHAR(96), poi_id BIGINT, entered_at DATETIME(3), feedback_at DATETIME(3), closed TINYINT, feedback_kind VARCHAR(16), reason_code VARCHAR(32), rating INT)",
                "INSERT INTO city_game_point VALUES ('b1', 'p1', 8001, 0), ('b1', 'p2', 8001, 0), ('b1', 'p9', 8009, 0)",
                "CREATE TABLE city_game_property (board_id VARCHAR(96), property_id VARCHAR(96), point_id VARCHAR(96), role VARCHAR(16))",
                "CREATE TABLE city_game_task_attempt (board_id VARCHAR(96), property_id VARCHAR(96), team_id CHAR(36), member_id BIGINT, request_id VARCHAR(96), create_time DATETIME(3))",
                "CREATE TABLE city_game_participation (board_id VARCHAR(96), team_id CHAR(36), member_id BIGINT, confirmed TINYINT, create_time DATETIME(3))",
                "CREATE TABLE city_game_request (member_id BIGINT, domain VARCHAR(32), request_id VARCHAR(96), board_id VARCHAR(96), action VARCHAR(32), decision VARCHAR(32), points_charged BIGINT, create_time DATETIME(3))",
                "CREATE TABLE city_game_quote (quote_id CHAR(36), board_id VARCHAR(96), action VARCHAR(32), target_id VARCHAR(96), payer_member_id BIGINT, action_cost INT, consumed_request_id VARCHAR(96))",
                "CREATE TABLE city_game_service (board_id VARCHAR(96), service_id VARCHAR(96), property_id VARCHAR(96))",
                "CREATE TABLE city_acquisition_title (board_id VARCHAR(96), point_id VARCHAR(96), title_json VARCHAR(4000), create_time DATETIME(3))",
                "CREATE TABLE city_acquisition_quote (quote_id CHAR(36), board_id VARCHAR(96), quote_json VARCHAR(4000), consumed TINYINT)",
                "CREATE TABLE business_audit_event (id BIGINT AUTO_INCREMENT PRIMARY KEY, domain_type VARCHAR(16), domain_id BIGINT, action VARCHAR(64), relation_id BIGINT, after_json VARCHAR(4000), create_time DATETIME)");
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
        board.put("taskCost", 1);
        when(mapper.selectCurrentBoard("r1")).thenReturn(board);
        CityR491Store store = new CityR491Store(mapper, db.jdbc, db.transactions, () -> 1_763_000_000_000L);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(eq(OWNER), anyString())).thenReturn(new MerchantAccessContext(OWNER, STORE, OWNER, MerchantRoleCode.MERCHANT_OWNER));
        when(access.require(eq(OTHER), anyString())).thenReturn(new MerchantAccessContext(OTHER, 8005L, OTHER, MerchantRoleCode.MERCHANT_OWNER));
        when(access.require(eq(1L), anyString())).thenThrow(new ServiceException("no", 403));
        service = new MerchantCityValueReportService(store, access);
    }

    void visit(String id, String point, String at, String kind, String reason, Integer rating) {
        visit(id, point, at, null, kind, reason, rating);
    }

    void visit(String id, String point, String at, String chosenAt, String kind, String reason, Integer rating) {
        db.jdbc.update("INSERT INTO city_value_visit VALUES (?,?,?,?,?,?,?,?,?,?,?)", id, 9004L, "b1", point, "p9".equals(point) ? 79L : 71L,
                Timestamp.from(Instant.parse(at)), chosenAt == null ? null : Timestamp.from(Instant.parse(chosenAt)),
                kind == null ? 0 : 1, kind, reason, rating);
    }

    static Timestamp ts(String at) { return Timestamp.from(Instant.parse(at)); }

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
        assertEquals(Map.of("status", "MISSING_COVERAGE"), ((Map<String, Object>) report.get("visitCost")).get("netPoints"));
        assertEquals(Map.of("status", "MISSING_COVERAGE"), report.get("timeToFirstEffectiveChoice"));

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

    @Test
    @SuppressWarnings("unchecked")
    void goalsCostAndFirstChoiceComeFromTheBoardRowsOfThisStoresPoints() {
        // p1 carries a NEUTRAL goal property and an operable one; p9 belongs to another store.
        db.jdbc.update("INSERT INTO city_game_property VALUES ('b1','g1','p1','NEUTRAL'), ('b1','o1','p1','OPERABLE'), ('b1','g9','p9','NEUTRAL')");
        db.jdbc.update("INSERT INTO city_game_participation VALUES ('b1','tA',1,1,?), ('b1','tA',2,1,?), ('b1','tB',3,1,?), ('b1','tB',4,0,?)",
                ts("2026-11-13T00:10:00Z"), ts("2026-11-13T00:20:00Z"), ts("2026-11-13T00:10:00Z"), ts("2026-11-13T00:10:00Z"));
        db.jdbc.update("INSERT INTO city_game_task_attempt VALUES ('b1','g1','tA',1,'t1',?), ('b1','g1','tB',3,'t2',?), ('b1','o1','tA',1,'t3',?), ('b1','g9','tA',1,'t4',?), ('b1','g1','tC',5,'t5',?)",
                ts("2026-11-13T01:00:00Z"), ts("2026-11-13T01:00:00Z"), ts("2026-11-13T01:30:00Z"), ts("2026-11-13T01:00:00Z"), ts("2026-11-15T01:00:00Z"));
        // A paid service visit at o1 (via service s1), a challenge at o1, a refused one, and one on another store.
        db.jdbc.update("INSERT INTO city_game_service VALUES ('b1','s1','o1')");
        db.jdbc.update("INSERT INTO city_game_request VALUES (1,'game','r1','b1','ACCEPT_SERVICE_VISIT','APPLIED',30,?), (3,'game','r2','b1','START_CHALLENGE','APPLIED',50,?), (3,'game','r3','b1','START_CHALLENGE','REJECTED',0,?), (1,'game','r4','b1','START_CHALLENGE','APPLIED',70,?)",
                ts("2026-11-13T02:00:00Z"), ts("2026-11-13T02:10:00Z"), ts("2026-11-13T02:20:00Z"), ts("2026-11-13T02:30:00Z"));
        db.jdbc.update("INSERT INTO city_game_quote VALUES ('q1','b1','ACCEPT_SERVICE_VISIT','s1',1,2,'r1'), ('q2','b1','START_CHALLENGE','o1',3,3,'r2'), ('q3','b1','START_CHALLENGE','o1',3,3,'r3'), ('q4','b1','START_CHALLENGE','g9',1,3,'r4')");
        // A self-paid acquisition of p2 (points 500 -> 380, 2 units).
        db.jdbc.update("INSERT INTO city_acquisition_title VALUES ('b1','p2','{\"quoteId\":\"aq1\",\"pointsBefore\":500,\"pointsAfter\":380}',?)", ts("2026-11-13T03:00:00Z"));
        db.jdbc.update("INSERT INTO city_acquisition_quote VALUES ('aq1','b1','{\"actionCost\":2}',1)");
        visit("v6", "p2", "2026-11-13T05:00:00Z", "2026-11-13T05:00:30Z", "SERVICE_RATING", null, 90);
        visit("v7", "p2", "2026-11-13T06:00:00Z", "2026-11-13T06:01:30Z", "EXIT", "no_time", null);

        Map<String, Object> report = service.report(OWNER, q("requestId", "rq-goals"));
        Map<String, Map<String, Object>> funnel = (Map<String, Map<String, Object>>) report.get("funnel");
        assertEquals(Map.of("status", "MEASURED", "count", 2), funnel.get("publicGoal"));
        assertEquals(Map.of("status", "MEASURED", "count", 1), funnel.get("cooperativeGoal"));
        Map<String, Object> cost = (Map<String, Object>) report.get("visitCost");
        // service 30 + challenge 50 + acquisition 120 points; units 2 + 3 + 2 + three tasks x 1.
        assertEquals(Map.of("status", "MEASURED", "sum", 200L, "count", 6, "unit", "MEMBER_POINTS"), cost.get("netPoints"));
        assertEquals(Map.of("status", "MEASURED", "sum", 10L, "count", 6, "unit", "CITY_ACTION_UNITS"), cost.get("budget"));
        assertEquals(Map.of("status", "MEASURED", "visitCount", 2, "medianMillis", 60_000L), report.get("timeToFirstEffectiveChoice"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void benefitIsVerifiedOnlyFromTheVisitsOwnCityNodeRedemptionAudit() {
        visit("v6", "p2", "2026-11-13T05:00:00Z", "2026-11-13T05:00:30Z", "SERVICE_RATING", null, 90);
        // v1 (02:00) and v6 (05:00) are rated visits at the same POI 71. Its single redemption for this player happened at
        // 04:30, so it belongs to v1 (the last rated visit before it) and v6 has no redemption of its own.
        db.jdbc.update("INSERT INTO business_audit_event(domain_type, domain_id, action, relation_id, after_json, create_time) VALUES ('CITY_NODE', 71, 'CITY_NODE_REDEEM', 9004, ?, ?)",
                "{\"status\":1,\"receiptVersion\":1,\"data\":{\"memberId\":9004,\"poiId\":71,\"couponId\":88,\"couponGranted\":true}}", ts("2026-11-13T04:30:00Z"));
        Map<String, Object> page = service.events(OWNER, q("cursor", ""));
        String snapshot = String.valueOf(page.get("snapshotId"));
        List<Map<String, Object>> successes = ((List<Map<String, Object>>) page.get("events")).stream()
                .filter(e -> "SERVICE_SUCCESS".equals(e.get("kind"))).toList();
        Map<String, Object> first = service.benefit(OWNER, (Long) successes.get(0).get("id"), q("snapshotId", snapshot));
        assertEquals("v1", first.get("visitId"));
        assertEquals("VERIFIED", first.get("evidenceStatus"));
        assertEquals("BUSINESS_AUDIT_CITY_NODE_REDEEM", first.get("sourceKind"));
        assertEquals("COUPON_GRANTED_AT_REDEMPTION", first.get("benefit"));
        assertEquals(88L, first.get("couponId"));
        assertNull(first.get("evidenceReason"));
        Map<String, Object> second = service.benefit(OWNER, (Long) successes.get(1).get("id"), q("snapshotId", snapshot));
        assertEquals("v6", second.get("visitId"));
        assertEquals("SOURCE_UNAVAILABLE", second.get("evidenceStatus"));
        assertEquals("REDEEMED_BEFORE_VISIT", second.get("evidenceReason"));
        assertNull(second.get("sourceRecordId"));
        // The first choice time is the fact time when it was recorded.
        assertEquals("2026-11-13T05:00:30Z", successes.get(1).get("occurredAt"));
    }

    @Test
    void benefitWithAWithheldCouponStaysUnavailableWithItsReason() {
        db.jdbc.update("INSERT INTO business_audit_event(domain_type, domain_id, action, relation_id, after_json, create_time) VALUES ('CITY_NODE', 71, 'CITY_NODE_REDEEM', 9004, ?, ?)",
                "{\"status\":1,\"data\":{\"couponId\":88,\"couponGranted\":false}}", ts("2026-11-13T02:30:00Z"));
        Map<String, Object> page = service.events(OWNER, q("cursor", ""));
        @SuppressWarnings("unchecked")
        Map<String, Object> success = ((List<Map<String, Object>>) page.get("events")).stream()
                .filter(e -> "SERVICE_SUCCESS".equals(e.get("kind"))).findFirst().orElseThrow();
        Map<String, Object> record = service.benefit(OWNER, (Long) success.get("id"), q("snapshotId", String.valueOf(page.get("snapshotId"))));
        assertEquals("SOURCE_UNAVAILABLE", record.get("evidenceStatus"));
        assertEquals("COUPON_NOT_GRANTED", record.get("evidenceReason"));
    }
}
