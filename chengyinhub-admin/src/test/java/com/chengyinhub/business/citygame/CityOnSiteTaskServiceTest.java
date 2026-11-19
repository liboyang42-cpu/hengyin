package com.chengyinhub.business.citygame;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.business.service.ai.PhotoCheckVisionResult;
import com.chengyinhub.business.service.ai.PhotoCheckVisionService;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.chengyinhub.business.citygame.CityGapsR3Test.LAT;
import static com.chengyinhub.business.citygame.CityGapsR3Test.LNG;
import static com.chengyinhub.business.citygame.CityGapsR3Test.NOW;
import static com.chengyinhub.business.citygame.CityGapsR3Test.row;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** CITY-GAPS-R3 item 5: the on-site challenge task (N35) graded on the server: presence ≤ 80 m + photo check. */
class CityOnSiteTaskServiceTest {
    private static final String REF = "challenge:T1:abc";

    private final PhotoCheckVisionService vision = mock(PhotoCheckVisionService.class);
    private final List<Long> gate = new ArrayList<>();
    private final AtomicReference<Map<String, Object>> stored = new AtomicReference<>();

    private SqlRoutingJdbc db(Object usedAt, String issuedRef) {
        SqlRoutingJdbc db = new SqlRoutingJdbc();
        db.on("SELECT * FROM city_onsite_task_submission WHERE member_id = ? AND task_ref = ?",
                a -> stored.get() == null ? new ArrayList<>() : new ArrayList<>(List.of(stored.get())));
        db.rows("FROM city_monopoly_quote WHERE quote_id = ? AND member_id = ? AND kind = 'CHALLENGE'", List.of(row(
                "used_at", usedAt, "expires_at", new Timestamp(NOW + 60_000L),
                "payload_json", JSON.toJSONString(Map.of("territoryId", "T1", "task", Map.of("taskRef", issuedRef, "prompt", "拍下老街咖啡的招牌"))))));
        db.rows("FROM roam_territory WHERE territory_id = ? AND status = 'ACTIVE'", List.of(row("territory_id", "T1", "board_id", "B",
                "title", "老街咖啡", "latitude", LAT, "longitude", LNG)));
        db.on("INSERT INTO city_onsite_task_submission", a -> {
            Map<String, Object> r = new HashMap<>();
            String[] cols = {"submission_id", "member_id", "task_ref", "territory_id", "quote_id", "task_type", "photo_url", "lat", "lng", "h_acc",
                    "distance_m", "presence_ok", "vision_state", "confidence", "vision_reason", "result", "reasons"};
            for (int i = 0; i < cols.length; i++) r.put(cols[i], a[i]);
            stored.set(r);
            return 1;
        });
        return db;
    }

    private CityOnSiteTaskService service(SqlRoutingJdbc db) {
        CityOnSiteTaskService s = new CityOnSiteTaskService(db.jdbc, CityMonopolyConfigService.fixed(CityMonopolyConfig.defaults()), () -> NOW);
        s.setVision(vision);
        return s;
    }

    private static Map<String, Object> body(double lat, double lng) {
        Map<String, Object> ev = new HashMap<>();
        ev.put("lat", lat);
        ev.put("lng", lng);
        ev.put("hAcc", 8.0);
        ev.put("capturedAt", NOW - 20_000L);
        ev.put("captureLat", lat);
        ev.put("captureLng", lng);
        return Map.of("quoteId", "Q1", "taskRef", REF, "photoUrl", "/upload/task.jpg", "evidence", ev);
    }

    private Map<String, Object> submit(SqlRoutingJdbc db, Map<String, Object> body) {
        return service(db).submit(7L, body, gate::add);
    }

    @Test
    void photoAtThePlaceWithHighConfidenceIsPerfect() {
        when(vision.review(eq(7L), eq("/upload/task.jpg"), eq("拍下老街咖啡的招牌"))).thenReturn(PhotoCheckVisionResult.concluded(true, 95, "招牌清晰"));
        Map<String, Object> out = submit(db(null, REF), body(LAT, LNG));
        assertEquals(List.of(7L), gate, "location consent gate runs first");
        assertEquals("PERFECT", out.get("result"));
        assertEquals(true, out.get("presenceOk"));
        assertEquals("PASS", out.get("photoState"));
        assertEquals(80, out.get("radiusM"));
        assertEquals(false, out.get("idempotentReplay"));
    }

    @Test
    void photoPassBelowPerfectConfidenceIsPass() {
        when(vision.review(anyLong(), any(), any())).thenReturn(PhotoCheckVisionResult.concluded(true, 70, "大致能看到"));
        assertEquals("PASS", submit(db(null, REF), body(LAT, LNG)).get("result"));
    }

    @Test
    void rejectedPhotoFails() {
        when(vision.review(anyLong(), any(), any())).thenReturn(PhotoCheckVisionResult.concluded(false, 88, "拍的是天空"));
        Map<String, Object> out = submit(db(null, REF), body(LAT, LNG));
        assertEquals("FAIL", out.get("result"));
        assertEquals(List.of("PHOTO_NOT_MATCHED"), out.get("reasons"));
    }

    @Test
    void unavailablePhotoReviewIsNeverAFakePass() {
        when(vision.review(anyLong(), any(), any())).thenReturn(PhotoCheckVisionResult.degraded("额度用完"));
        Map<String, Object> out = submit(db(null, REF), body(LAT, LNG));
        assertEquals("NONE", out.get("result"), "no modifier, no refund path");
        assertEquals("DEGRADED", out.get("photoState"));
    }

    @Test
    void outsideEightyMetresFailsWithoutCallingTheModel() {
        double away = LAT + 0.0009; // ~100 m north
        Map<String, Object> out = submit(db(null, REF), body(away, LNG));
        assertEquals("FAIL", out.get("result"));
        assertEquals(false, out.get("presenceOk"));
        assertTrue(((List<?>) out.get("reasons")).contains("OUT_OF_RADIUS"));
        assertTrue(((List<?>) out.get("reasons")).contains("PHOTO_LOCATION_MISMATCH"));
        verifyNoInteractions(vision);
    }

    @Test
    void sixtyMetresAwayIsStillPresent() {
        when(vision.review(anyLong(), any(), any())).thenReturn(PhotoCheckVisionResult.concluded(true, 80, "ok"));
        double near = LAT + 0.00054; // ~60 m north
        assertEquals("PASS", submit(db(null, REF), body(near, LNG)).get("result"));
    }

    @Test
    void mockLocationStalePhotoAndPoorAccuracyFail() {
        CityMonopolyConfig c = CityMonopolyConfig.defaults();
        CityMonopolyRules.Evidence ev = new CityMonopolyRules.Evidence(LAT, LNG, 60.0, List.of(), true, false, false, true,
                NOW - 600_000L, LAT, LNG, null, null);
        assertEquals(List.of("POOR_ACCURACY", "SIMULATED_LOCATION", "PHOTO_TOO_OLD"),
                CityOnSiteTaskService.presenceReasons(c, ev, LAT, LNG, NOW));
    }

    @Test
    void oneSubmissionPerTaskNoRetryForABetterGrade() {
        when(vision.review(anyLong(), any(), any())).thenReturn(PhotoCheckVisionResult.concluded(false, 60, "no"));
        SqlRoutingJdbc db = db(null, REF);
        assertEquals("FAIL", submit(db, body(LAT, LNG)).get("result"));
        when(vision.review(anyLong(), any(), any())).thenReturn(PhotoCheckVisionResult.concluded(true, 99, "yes"));
        Map<String, Object> again = submit(db, body(LAT, LNG));
        assertEquals("FAIL", again.get("result"));
        assertEquals(true, again.get("idempotentReplay"));
        verify(vision, times(1)).review(anyLong(), any(), any());
    }

    @Test
    void taskMustBeTheOneIssuedOnAnOpenQuoteOfThisMember() {
        CityGameException wrong = assertThrows(CityGameException.class, () -> submit(db(null, "challenge:T1:other"), body(LAT, LNG)));
        assertEquals("TASK_NOT_ISSUED", wrong.getErrorCode());
        CityGameException used = assertThrows(CityGameException.class, () -> submit(db(new Timestamp(NOW), REF), body(LAT, LNG)));
        assertEquals("QUOTE_USED", used.getErrorCode());
        Map<String, Object> chance = new HashMap<>(body(LAT, LNG));
        chance.put("taskRef", "chance:x");
        assertEquals("TASK_REF_REQUIRED", assertThrows(CityGameException.class, () -> submit(db(null, REF), chance)).getErrorCode());
        verifyNoInteractions(vision);
    }

    @Test
    void verifyConsumesASubmissionOnceAndSkipsWhenNoneWasSent() {
        SqlRoutingJdbc db = new SqlRoutingJdbc();
        int[] remaining = {1};
        db.on("UPDATE city_onsite_task_submission SET consumed_at", a -> remaining[0]-- > 0 ? 1 : 0);
        db.on("SELECT result FROM city_onsite_task_submission", a -> "PERFECT");
        CityOnSiteTaskService s = service(db);
        assertEquals(CityMonopolyRules.TaskResult.NONE, s.verify(7L, REF, null), "older clients that skip N35 keep working");
        assertEquals(CityMonopolyRules.TaskResult.PERFECT, s.verify(7L, REF, "S1"));
        assertEquals(CityMonopolyRules.TaskResult.FAIL, s.verify(7L, REF, "S1"), "a submission counts for one challenge only");
        Object[] args = db.calls("UPDATE city_onsite_task_submission").get(0).args();
        assertEquals(List.of("S1", 7L, REF), List.of(args[1], args[2], args[3]), "bound to member and task in the WHERE clause");
    }

    @Test
    @SuppressWarnings("unchecked")
    void challengeQuoteCarriesTheTaskBlockAndChanceStaysClosed() {
        SqlRoutingJdbc db = new SqlRoutingJdbc();
        db.rows("FROM roam_territory WHERE territory_id = ? AND status = 'ACTIVE' AND rules_kind = 'MONOPOLY'",
                List.of(CityGapsR3Test.territory("T1", 99L, 4)));
        CityMonopolyService monopoly = new CityMonopolyService(db.jdbc, mock(CityGameMapper.class),
                CityMonopolyConfigService.fixed(CityMonopolyConfig.defaults()), null, () -> NOW, faces -> 20);
        monopoly.setTasks(service(db));
        Map<String, Object> quote = monopoly.challengeQuote(7L, Map.of("territoryId", "T1"));
        Map<String, Object> task = (Map<String, Object>) quote.get("task");
        assertTrue(String.valueOf(task.get("taskRef")).startsWith("challenge:T1:"));
        assertEquals("PHOTO_PRESENCE", task.get("type"));
        assertEquals(80, task.get("radiusM"));
        assertEquals(true, task.get("optional"));
        assertEquals("/api/city-game/territory/challenge/task/submit", task.get("submitPath"));
        assertTrue(String.valueOf(task.get("prompt")).contains("老街咖啡"));

        CityGameException e = assertThrows(CityGameException.class, () -> monopoly.chance(7L, Map.of("action", "COMPLETE")));
        assertEquals("TASK_VERIFIER_UNAVAILABLE", e.getErrorCode(), "chance tasks are not graded by the challenge verifier");
    }
}
