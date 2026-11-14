package com.chengyinhub.business.begapmisc.home;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiNativeHomeController;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * be-gap-misc {@code /api/native/home} and {@code /api/native/home/return/*} on H2 (MySQL mode). The run runtime is mocked:
 * {@code homeReturnHp} / {@code restoreHomeReturnHp} are the two hooks the service uses on {@code TopicRouteRuntimeServiceImpl}.
 */
class NativeHomeServicesTest {
    static final long ME = 9009, OTHER = 9004, RUN = 41;
    static final String HOME_LAT = "31.230416", HOME_LON = "121.473701";
    final AtomicLong now = new AtomicLong(Instant.parse("2026-11-19T02:00:00Z").toEpochMilli());
    JdbcTemplate jdbc;
    TopicRouteSessionMapper routes;
    TopicRouteRuntimeServiceImpl runtime;
    NativeHomeService homes;
    NativeHomeReturnService returns;
    TopicRouteSession session;
    long[] hp;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:home_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE native_private_home (member_id BIGINT PRIMARY KEY, version BIGINT NOT NULL DEFAULT 0,"
                + " status VARCHAR(8) NOT NULL DEFAULT 'DELETED', label VARCHAR(40), latitude DECIMAL(9,6), longitude DECIMAL(9,6),"
                + " datum VARCHAR(8), changed_at BIGINT, effective_at BIGINT, last_set_at BIGINT, updated_at BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE native_private_home_mutation (member_id BIGINT, request_id VARCHAR(64), operation VARCHAR(8),"
                + " payload_digest CHAR(64), decision VARCHAR(24), result_version BIGINT, created_at BIGINT, PRIMARY KEY (member_id, request_id))");
        jdbc.execute("CREATE TABLE native_home_return_check (member_id BIGINT, request_id VARCHAR(64), payload_digest CHAR(64),"
                + " decision VARCHAR(24), run_id BIGINT, home_version BIGINT, proof_id CHAR(32), challenge_id CHAR(32), expires_at BIGINT,"
                + " consumed_at BIGINT, response_json TEXT, created_at BIGINT, PRIMARY KEY (member_id, request_id), UNIQUE (proof_id))");
        jdbc.execute("CREATE TABLE native_home_restore_receipt (member_id BIGINT, request_id VARCHAR(64), payload_digest CHAR(64),"
                + " proof_id VARCHAR(64), run_id BIGINT, decision VARCHAR(24), response_json TEXT, created_at BIGINT, PRIMARY KEY (member_id, request_id))");
        jdbc.execute("CREATE TABLE topic_route_session (id BIGINT PRIMARY KEY, member_id BIGINT, status INT)");
        jdbc.update("INSERT INTO topic_route_session VALUES (?, ?, 0)", RUN, ME);
        policy(0);
        session = new TopicRouteSession();
        session.setId(RUN); session.setMemberId(ME); session.setActivityId(992031L); session.setTopicId(992001L);
        session.setPlayInstanceId("run-a"); session.setStatus(0); session.setVersion(7);
        hp = new long[] {3, 10};
        when(routes.selectAccessibleById(RUN, ME)).thenAnswer(i -> session);
        when(routes.selectAccessibleByIdForUpdate(RUN, ME)).thenAnswer(i -> session);
        when(runtime.homeReturnHp(any())).thenAnswer(i -> hp == null ? null : hp.clone());
        when(runtime.restoreHomeReturnHp(any())).thenAnswer(i -> {
            session.setVersion(session.getVersion() + 1); hp[0] = hp[1]; return true;
        });
    }

    void policy(long dwellSeconds) {
        NativeHomePolicy policy = new NativeHomePolicy(true, true, "hr-test-1", 86400, 0, 150, 100, 120, dwellSeconds, 120, 21600);
        routes = routes == null ? mock(TopicRouteSessionMapper.class) : routes;
        runtime = runtime == null ? mock(TopicRouteRuntimeServiceImpl.class) : runtime;
        homes = new NativeHomeService(jdbc, policy);
        homes.clock = clock();
        returns = new NativeHomeReturnService(jdbc, policy, homes, routes, runtime);
        returns.clock = clock();
    }

    Clock clock() {
        return new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
    }

    static String put(String requestId, long expected, String label, String lat, String lon) {
        return "{\"datum\":\"WGS84\",\"expectedVersion\":" + expected + ",\"label\":\"" + label + "\",\"latitude\":" + lat
                + ",\"longitude\":" + lon + ",\"requestId\":\"" + requestId + "\"}";
    }

    String verifyBody(String requestId, long homeVersion, long account, String lat, String lon, String accuracy, long capturedAt, String challenge) {
        return "{\"accuracyMeters\":" + accuracy + ",\"capturedAtMillis\":" + capturedAt + (challenge == null ? "" : ",\"challengeId\":\"" + challenge + "\"")
                + ",\"datum\":\"WGS84\",\"expectedAccountVersion\":" + account + ",\"homeVersion\":" + homeVersion + ",\"latitude\":" + lat
                + ",\"longitude\":" + lon + ",\"policyVersion\":\"hr-test-1\",\"requestId\":\"" + requestId + "\",\"resourceType\":\"sys.hp\","
                + "\"scopeId\":\"" + RUN + "\",\"scopeType\":\"RUN\"}";
    }

    static String restore(String requestId, String proofId, long homeVersion, long account) {
        return "{\"expectedAccountVersion\":" + account + ",\"homeVersion\":" + homeVersion + ",\"proofId\":\"" + proofId
                + "\",\"requestId\":\"" + requestId + "\",\"resourceType\":\"sys.hp\",\"scopeId\":\"" + RUN + "\",\"scopeType\":\"RUN\"}";
    }

    @Test
    void getWithoutHomeIsTheDeletedSnapshotAtVersionZero() {
        Map<String, Object> view = homes.snapshot(ME);
        assertEquals(0L, view.get("version"));
        assertEquals("DELETED", view.get("status"));
        assertNull(view.get("latitude"));
        assertNull(view.get("changedAt"));
    }

    @Test
    void putSavesThenReplaysThenConflictsAndCoolsDown() {
        Map<String, Object> saved = homes.set(ME, put("h-1", 0, "家", HOME_LAT, HOME_LON));
        assertEquals("SAVED", saved.get("decision"));
        assertEquals(1L, saved.get("version"));
        assertEquals(saved, homes.set(ME, put("h-1", 0, "家", "31.2304160", HOME_LON)), "same values replay");
        HomeRejection conflict = assertThrows(HomeRejection.class, () -> homes.set(ME, put("h-1", 0, "家2", HOME_LAT, HOME_LON)));
        assertEquals("HOME_REQUEST_CONFLICT", conflict.getMessage());
        Map<String, Object> stale = homes.set(ME, put("h-2", 0, "家", HOME_LAT, HOME_LON));
        assertEquals("VERSION_CONFLICT", stale.get("decision"));
        assertEquals(1L, stale.get("version"));
        Map<String, Object> cooldown = homes.set(ME, put("h-3", 1, "新家", "31.1", "121.4"));
        assertEquals("HOME_CHANGE_COOLDOWN", cooldown.get("decision"));
        assertEquals(1L, cooldown.get("version"));
        Map<String, Object> view = homes.snapshot(ME);
        assertEquals("ACTIVE", view.get("status"));
        assertEquals(new BigDecimal(HOME_LAT), view.get("latitude"));
        assertEquals("家", view.get("label"));
        assertTrue(((Long) view.get("effectiveAt")) >= ((Long) view.get("changedAt")));
        assertEquals("DELETED", homes.snapshot(OTHER).get("status"), "owner only");
    }

    @Test
    void deleteClearsEveryCoordinateAndKeepsTheVersionLine() {
        homes.set(ME, put("h-1", 0, "家", HOME_LAT, HOME_LON));
        Map<String, Object> deleted = homes.delete(ME, "{\"expectedVersion\":1,\"requestId\":\"d-1\"}");
        assertEquals("DELETED", deleted.get("decision"));
        assertEquals(2L, deleted.get("version"));
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM native_private_home WHERE member_id = ?", ME);
        assertNull(row.get("LATITUDE"));
        assertNull(row.get("LABEL"));
        assertEquals("DELETED", homes.snapshot(ME).get("status"));
    }

    @Test
    void strictInputIsRefusedBeforeAnyWrite() {
        for (String bad : List.of(put("h-1", 0, "家", "31.1234567", HOME_LON), put("h-1", 0, "家", "91", HOME_LON),
                put("h 1", 0, "家", HOME_LAT, HOME_LON), put("h-1", 0, "  ", HOME_LAT, HOME_LON),
                "{\"expectedVersion\":0,\"requestId\":\"h-1\",\"ownerId\":9004}", "[]", "{\"requestId\":\"x\",\"requestId\":\"y\",\"expectedVersion\":0}")) {
            HomeRejection refused = assertThrows(HomeRejection.class, () -> homes.set(ME, bad), bad);
            assertEquals(400, refused.code);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM native_private_home_mutation", Integer.class));
    }

    @Test
    void offByDefaultForBothFeatures() {
        NativeHomePolicy off = new NativeHomePolicy(false, true, "v", 0, 0, 1, 1, 1, 0, 1, 0);
        assertEquals(403, assertThrows(HomeRejection.class, () -> new NativeHomeService(jdbc, off).snapshot(ME)).code);
        NativeHomeReturnService closed = new NativeHomeReturnService(jdbc, off, homes, routes, runtime);
        assertEquals("HOME_RETURN_NOT_APPROVED", assertThrows(HomeRejection.class, closed::policy).getMessage());
    }

    @Test
    void policyRunsAndAccountAreServerFacts() {
        Map<String, Object> policy = returns.policy();
        assertEquals("hr-test-1", policy.get("policyVersion"));
        assertEquals(now.get(), policy.get("serverTimeMillis"));
        Map<String, Object> runs = returns.runs(ME, null);
        List<?> items = (List<?>) runs.get("runs");
        assertEquals(1, items.size());
        Map<?, ?> run = (Map<?, ?>) items.get(0);
        assertEquals("41", run.get("runId"));
        assertEquals("992031", run.get("activityId"));
        assertEquals(3L, run.get("current"));
        assertEquals(10L, run.get("maximum"));
        assertEquals(7L, run.get("version"));
        assertEquals(false, runs.get("truncated"));
        assertNull(runs.get("nextBeforeRunId"));
        assertEquals(0, ((List<?>) returns.runs(OTHER, null).get("runs")).size());
        Map<String, Object> account = returns.account(ME, "41");
        assertEquals("RUN", account.get("scopeType"));
        assertEquals("41", account.get("scopeId"));
        assertEquals(404, assertThrows(HomeRejection.class, () -> returns.account(OTHER, "41")).code);
        hp = null;
        assertEquals(0, ((List<?>) returns.runs(ME, null).get("runs")).size(), "a run without HP is not listed");
    }

    @Test
    void verifyThenRestoreLandsExactlyOnTheMaximumOnce() {
        homes.set(ME, put("h-1", 0, "家", HOME_LAT, HOME_LON));
        Map<String, Object> v = returns.verify(ME, verifyBody("v-0001", 1, 7, "31.230900", "121.473701", "20", now.get() - 10_000, null));
        assertEquals("VERIFIED", v.get("decision"), v.toString());
        assertEquals(7L, v.get("accountVersion"));
        String proof = (String) v.get("proofId");
        assertEquals(v, returns.verify(ME, verifyBody("v-0001", 1, 7, "31.2309", "121.473701", "20.0", now.get() - 10_000, null)));
        Map<String, Object> r = returns.restore(ME, restore("r-0001", proof, 1, 7));
        assertEquals("RESTORED", r.get("decision"), r.toString());
        assertEquals(10L, r.get("current"));
        assertEquals(10L, r.get("maximum"));
        assertEquals(8L, r.get("accountVersion"));
        assertNull(r.get("retryAfterSeconds"));
        assertEquals(r, returns.restore(ME, restore("r-0001", proof, 1, 7)), "replay");
        assertEquals(r, returns.receipt(ME, "r-0001"));
        assertNull(returns.receipt(ME, "r-never"));
        assertNull(returns.receipt(OTHER, "r-0001"), "owner only");
        assertEquals("PROOF_CONSUMED", returns.restore(ME, restore("r-0002", proof, 1, 8)).get("decision"));
        verify(runtime, times(1)).restoreHomeReturnHp(any());
        Map<String, Object> again = returns.verify(ME, verifyBody("v-0002", 1, 8, HOME_LAT, HOME_LON, "5", now.get(), null));
        assertEquals("COOLDOWN", again.get("decision"));
        assertTrue((Long) again.get("retryAfterSeconds") > 0);
        assertNull(again.get("proofId"));
    }

    @Test
    void verifyRefusalsAreDistinctDecisions() {
        assertEquals("NO_HOME", returns.verify(ME, verifyBody("v-0001", 0, 7, HOME_LAT, HOME_LON, "5", now.get(), null)).get("decision"));
        homes.set(ME, put("h-1", 0, "家", HOME_LAT, HOME_LON));
        assertEquals("HOME_CHANGED", returns.verify(ME, verifyBody("v-0002", 0, 7, HOME_LAT, HOME_LON, "5", now.get(), null)).get("decision"));
        assertEquals("LOW_ACCURACY", returns.verify(ME, verifyBody("v-0003", 1, 7, HOME_LAT, HOME_LON, "100.5", now.get(), null)).get("decision"));
        assertEquals("STALE_FIX", returns.verify(ME, verifyBody("v-0004", 1, 7, HOME_LAT, HOME_LON, "5", now.get() - 121_000, null)).get("decision"));
        assertEquals("OUT_OF_RADIUS", returns.verify(ME, verifyBody("v-0005", 1, 7, "31.240416", HOME_LON, "5", now.get(), null)).get("decision"));
        assertEquals("HOME_RETURN_VERSION_CONFLICT",
                assertThrows(HomeRejection.class, () -> returns.verify(ME, verifyBody("v-0006", 1, 6, HOME_LAT, HOME_LON, "5", now.get(), null))).getMessage());
        hp = new long[] {0, 10};
        assertEquals("RUN_UNAVAILABLE", returns.verify(ME, verifyBody("v-0007", 1, 7, HOME_LAT, HOME_LON, "5", now.get(), null)).get("decision"));
        assertEquals(409, assertThrows(HomeRejection.class,
                () -> returns.verify(ME, verifyBody("v-0007", 1, 7, HOME_LAT, HOME_LON, "6", now.get(), null))).code, "same id, new body");
    }

    @Test
    void dwellNeedsASecondCheckWithTheSameChallenge() {
        policy(60);
        homes.set(ME, put("h-1", 0, "家", HOME_LAT, HOME_LON));
        Map<String, Object> first = returns.verify(ME, verifyBody("v-0001", 1, 7, HOME_LAT, HOME_LON, "5", now.get(), null));
        assertEquals("DWELL_REQUIRED", first.get("decision"));
        String challenge = (String) first.get("challengeId");
        assertEquals(60L, first.get("retryAfterSeconds"));
        now.addAndGet(30_000);
        Map<String, Object> early = returns.verify(ME, verifyBody("v-0002", 1, 7, HOME_LAT, HOME_LON, "5", now.get(), challenge));
        assertEquals("DWELL_REQUIRED", early.get("decision"));
        assertEquals(challenge, early.get("challengeId"));
        now.addAndGet(31_000);
        Map<String, Object> done = returns.verify(ME, verifyBody("v-0003", 1, 7, HOME_LAT, HOME_LON, "5", now.get(), challenge));
        assertEquals("VERIFIED", done.get("decision"));
        assertNull(done.get("challengeId"));
        assertEquals("HOME_RETURN_CHALLENGE_EXPIRED", assertThrows(HomeRejection.class,
                () -> returns.verify(ME, verifyBody("v-0004", 1, 7, HOME_LAT, HOME_LON, "5", now.get(), "0123456789abcdef0123456789abcdef"))).getMessage());
    }

    @Test
    void restoreDecisionsForBadProofsStaleHomesAndVersions() {
        homes.set(ME, put("h-1", 0, "家", HOME_LAT, HOME_LON));
        assertEquals("PROOF_UNAVAILABLE", returns.restore(ME, restore("r-0001", "nope", 1, 7)).get("decision"));
        String proof = (String) returns.verify(ME, verifyBody("v-0001", 1, 7, HOME_LAT, HOME_LON, "5", now.get(), null)).get("proofId");
        Map<String, Object> conflict = returns.restore(ME, restore("r-0002", proof, 1, 6));
        assertEquals("VERSION_CONFLICT", conflict.get("decision"));
        assertEquals(7L, conflict.get("accountVersion"));
        assertEquals("PROOF_UNAVAILABLE", returns.restore(OTHER, restore("r-0003", proof, 1, 7)).get("decision"), "another owner");
        now.addAndGet(121_000);
        assertEquals("PROOF_EXPIRED", returns.restore(ME, restore("r-0004", proof, 1, 7)).get("decision"));
        now.addAndGet(86_400_000);
        String fresh = (String) returns.verify(ME, verifyBody("v-0002", 1, 7, HOME_LAT, HOME_LON, "5", now.get(), null)).get("proofId");
        homes.delete(ME, "{\"expectedVersion\":1,\"requestId\":\"d-1\"}");
        assertEquals("STALE_HOME", returns.restore(ME, restore("r-0005", fresh, 1, 7)).get("decision"));
        verify(runtime, never()).restoreHomeReturnHp(any());
    }

    @Test
    void alreadyAtMaxConsumesTheProofWithoutAWrite() {
        homes.set(ME, put("h-1", 0, "家", HOME_LAT, HOME_LON));
        hp = new long[] {10, 10};
        String proof = (String) returns.verify(ME, verifyBody("v-0001", 1, 7, HOME_LAT, HOME_LON, "5", now.get(), null)).get("proofId");
        Map<String, Object> r = returns.restore(ME, restore("r-0001", proof, 1, 7));
        assertEquals("ALREADY_AT_MAX", r.get("decision"));
        assertEquals(7L, r.get("accountVersion"));
        assertEquals("PROOF_CONSUMED", returns.restore(ME, restore("r-0002", proof, 1, 7)).get("decision"));
        verify(runtime, never()).restoreHomeReturnHp(any());
    }

    @Test
    void controllerAnswersAjaxResultWithTheReasonCode() {
        ApiNativeHomeController controller = new ApiNativeHomeController(homes, returns) {
            @Override public Long getAppUserId() { return ME; }
        };
        assertEquals(200, controller.home(null).get(AjaxResult.CODE_TAG));
        AjaxResult bad = controller.setHome("{}", null);
        assertEquals(400, bad.get(AjaxResult.CODE_TAG));
        assertEquals("HOME_REQUEST_INVALID", bad.get(AjaxResult.MSG_TAG));
        assertEquals(200, controller.setHome(put("h-1", 0, "家", HOME_LAT, HOME_LON), null).get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.deleteHome("{\"expectedVersion\":1,\"requestId\":\"d-1\"}", null).get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.policy(null).get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.runs(null, null).get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.account("41", null).get(AjaxResult.CODE_TAG));
        assertEquals("INVALID_HOME_RUN_CURSOR", controller.runs("x", null).get(AjaxResult.MSG_TAG));
        assertEquals(200, controller.verify(verifyBody("v-0001", 2, 7, HOME_LAT, HOME_LON, "5", now.get(), null), null).get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.restore(restore("r-0001", "nope", 2, 7), null).get(AjaxResult.CODE_TAG));
        AjaxResult absent = controller.receipt("r-never", null);
        assertEquals(200, absent.get(AjaxResult.CODE_TAG));
        assertFalse(absent.containsKey(AjaxResult.DATA_TAG));
    }
}
