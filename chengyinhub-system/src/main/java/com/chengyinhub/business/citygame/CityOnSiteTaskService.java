package com.chengyinhub.business.citygame;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.service.ai.PhotoCheckVisionResult;
import com.chengyinhub.business.service.ai.PhotoCheckVisionService;
import com.chengyinhub.common.utils.GeoDistanceUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * City Monopoly on-site challenge task (hand-off frame N35, CITY-MONOPOLY-SPEC §2.7 step 2): the {@link CityTaskVerifier}
 * bean. It reuses the two verification hooks that already exist instead of adding a new one:
 * <ul>
 *   <li><b>presence</b>: the City GPS evidence format ({@link CityMonopolyService#parseEvidence}) checked on the server
 *   against the held place: within {@value #PRESENCE_RADIUS_M} m (the approved prize-presence radius), horizontal accuracy
 *   within {@code city.monopoly.claim.max_h_accuracy_m}, no simulated location, and a fresh in-place photo
 *   ({@code claim.photo.max_capture_age_s}, captured within {@value #PRESENCE_RADIUS_M} m of the place);</li>
 *   <li><b>photo check</b>: {@link PhotoCheckVisionService} (server-side vision review with its own consent gate, quota
 *   pool and degraded mode) against a prompt the server put in the challenge quote.</li>
 * </ul>
 * The client never reports its own result. One submission per issued task ({@code UK member/task_ref}, so no retrying
 * for a better grade); the challenge command consumes it once. Outcomes: presence fails → FAIL (no model call);
 * photo rejected → FAIL; photo passes → PASS, or PERFECT at confidence ≥ {@value #PERFECT_CONFIDENCE}; review
 * unavailable (no key, quota, timeout) → NONE, i.e. no modifier and no refund path, never a fake pass.
 * A challenge without {@code taskSubmissionId} skips the task (NONE), so clients that do not show N35 keep working.
 * Tasks never move 积分 themselves.
 */
@Service
public class CityOnSiteTaskService implements CityTaskVerifier {
    /** Approved spec: prizes need presence within 80 m. */
    public static final int PRESENCE_RADIUS_M = 80;
    static final int PERFECT_CONFIDENCE = 90;
    static final String TASK_TYPE = "PHOTO_PRESENCE";
    static final String CHALLENGE_PREFIX = "challenge:";

    private final JdbcTemplate jdbc;
    private final CityMonopolyConfigService configs;
    private final LongSupplier clock;
    private PhotoCheckVisionService vision;
    private TransactionTemplate tx;

    @Autowired
    public CityOnSiteTaskService(JdbcTemplate jdbc, CityMonopolyConfigService configs) {
        this(jdbc, configs, System::currentTimeMillis);
    }

    CityOnSiteTaskService(JdbcTemplate jdbc, CityMonopolyConfigService configs, LongSupplier clock) {
        this.jdbc = jdbc;
        this.configs = configs;
        this.clock = clock;
    }

    @Autowired(required = false) public void setVision(PhotoCheckVisionService vision) { this.vision = vision; }
    @Autowired(required = false) public void setTransactions(PlatformTransactionManager transactions) {
        this.tx = transactions == null ? null : new TransactionTemplate(transactions);
    }

    /** The task block a challenge quote carries (additive: {@code taskRef} as before, plus what N35 needs to render). */
    @Override
    public Map<String, Object> issue(String territoryId, Object title) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskRef", CHALLENGE_PREFIX + territoryId + ":" + UUID.randomUUID());
        task.put("type", TASK_TYPE);
        task.put("prompt", prompt(title));
        task.put("radiusM", PRESENCE_RADIUS_M);
        task.put("optional", true);
        task.put("submitPath", "/api/city-game/territory/challenge/task/submit");
        return task;
    }

    static String prompt(Object title) {
        String t = title == null ? "" : String.valueOf(title).trim();
        if (t.length() > 40) t = t.substring(0, 40);
        return t.isEmpty() ? "在现场拍一张这个地点的门头、招牌或标志性外观" : "在现场拍一张「" + t + "」的门头、招牌或标志性外观";
    }

    @Override
    public boolean supports(String taskRef) {
        return taskRef != null && taskRef.startsWith(CHALLENGE_PREFIX);
    }

    // =================================================================== submit

    record Prepared(Map<String, Object> existing, String territoryId, String quoteId, String prompt, String photoUrl,
                    CityMonopolyRules.Evidence evidence, double distanceM, List<String> reasons) {}

    /**
     * Grades one on-site submission. {@code gate} runs first inside a short transaction (the roam location consent
     * gate); the vision call runs with no transaction held; the result is one insert.
     */
    public Map<String, Object> submit(Long memberId, Map<String, Object> body, LongConsumer gate) {
        CityGameSupport.requireMember(memberId);
        if (body == null) throw new CityGameException(400, "REQUEST_INVALID", "请求体不能为空");
        Prepared p = tx == null ? prepare(memberId, body, gate) : tx.execute(s -> prepare(memberId, body, gate));
        if (p.existing() != null) return wire(p.existing(), true);
        String taskRef = CityMonopolyService.str(body, "taskRef");
        String visionState;
        Integer confidence = null;
        String visionReason = null;
        CityMonopolyRules.TaskResult result;
        List<String> reasons = new ArrayList<>(p.reasons());
        if (!reasons.isEmpty()) {
            visionState = "SKIPPED";
            result = CityMonopolyRules.TaskResult.FAIL;
        } else {
            PhotoCheckVisionResult r = vision == null ? PhotoCheckVisionResult.degraded("照片审核未开放") : vision.review(memberId, p.photoUrl(), p.prompt());
            if (r == null || r.isDegraded()) {
                visionState = "DEGRADED";
                visionReason = r == null ? null : r.getReason();
                result = CityMonopolyRules.TaskResult.NONE;
            } else {
                confidence = r.getConfidence();
                visionReason = r.getReason();
                if (!r.isPass()) {
                    visionState = "FAIL";
                    reasons.add("PHOTO_NOT_MATCHED");
                    result = CityMonopolyRules.TaskResult.FAIL;
                } else {
                    visionState = "PASS";
                    result = r.getConfidence() >= PERFECT_CONFIDENCE ? CityMonopolyRules.TaskResult.PERFECT : CityMonopolyRules.TaskResult.PASS;
                }
            }
        }
        String submissionId = UUID.randomUUID().toString();
        CityMonopolyRules.Evidence ev = p.evidence();
        try {
            jdbc.update("INSERT INTO city_onsite_task_submission(submission_id, member_id, task_ref, territory_id, quote_id, task_type, photo_url, "
                            + "lat, lng, h_acc, distance_m, presence_ok, vision_state, confidence, vision_reason, result, reasons) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    submissionId, memberId, taskRef, p.territoryId(), p.quoteId(), TASK_TYPE, p.photoUrl(), ev.lat(), ev.lng(), ev.hAcc(),
                    Math.min(99_999_999.0, Math.round(p.distanceM() * 10.0) / 10.0), p.reasons().isEmpty() ? 1 : 0, visionState, confidence,
                    visionReason == null ? null : (visionReason.length() > 120 ? visionReason.substring(0, 120) : visionReason),
                    result.name(), String.join(",", reasons));
        } catch (DuplicateKeyException concurrent) {
            return wire(existing(memberId, taskRef), true);
        }
        return wire(existing(memberId, taskRef), false);
    }

    Prepared prepare(long memberId, Map<String, Object> body, LongConsumer gate) {
        if (gate != null) gate.accept(memberId);
        String quoteId = CityMonopolyService.str(body, "quoteId");
        String taskRef = CityMonopolyService.str(body, "taskRef");
        if (quoteId == null || taskRef == null || !supports(taskRef)) throw new CityGameException(400, "TASK_REF_REQUIRED", "缺少任务编号");
        Map<String, Object> prior = existing(memberId, taskRef);
        if (prior != null) return new Prepared(prior, null, quoteId, null, null, null, 0, List.of());
        List<Map<String, Object>> quotes = jdbc.queryForList("SELECT payload_json, expires_at, used_at FROM city_monopoly_quote "
                + "WHERE quote_id = ? AND member_id = ? AND kind = 'CHALLENGE'", quoteId, memberId);
        if (quotes.isEmpty()) throw new CityGameException(404, "QUOTE_NOT_FOUND", "报价不存在");
        Map<String, Object> q = quotes.get(0);
        if (q.get("used_at") != null) throw new CityGameException(409, "QUOTE_USED", "报价已使用");
        if (CityGameSupport.millis(q.get("expires_at")) < clock.getAsLong()) throw new CityGameException(409, "QUOTE_EXPIRED", "报价已过期，请重新报价");
        Map<String, Object> payload = JSON.parseObject(String.valueOf(q.get("payload_json")));
        Object task = payload == null ? null : payload.get("task");
        if (!(task instanceof Map<?, ?> issued) || !taskRef.equals(String.valueOf(issued.get("taskRef")))) {
            throw new CityGameException(409, "TASK_NOT_ISSUED", "该报价没有这个任务");
        }
        String territoryId = String.valueOf(payload.get("territoryId"));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT territory_id, board_id, title, latitude, longitude FROM roam_territory "
                + "WHERE territory_id = ? AND status = 'ACTIVE' AND rules_kind = 'MONOPOLY'", territoryId);
        if (rows.isEmpty()) throw new CityGameException(404, "TERRITORY_NOT_FOUND", "地产不存在");
        Map<String, Object> terr = rows.get(0);
        String photoUrl = CityMonopolyService.str(body, "photoUrl");
        if (photoUrl == null || photoUrl.length() > 500) throw new CityGameException(400, "PHOTO_REQUIRED", "请拍一张现场照片");
        CityMonopolyRules.Evidence ev = CityMonopolyService.parseEvidence(body);
        CityMonopolyConfig c = configs.forBoard((String) terr.get("board_id"));
        double pLat = CityMonopolyService.dbl(terr.get("latitude"));
        double pLng = CityMonopolyService.dbl(terr.get("longitude"));
        double distance = GeoDistanceUtils.haversineMeters(ev.lat(), ev.lng(), pLat, pLng);
        List<String> reasons = presenceReasons(c, ev, pLat, pLng, clock.getAsLong());
        Object prompt = issued.get("prompt");
        return new Prepared(null, territoryId, quoteId, prompt == null ? prompt(terr.get("title")) : String.valueOf(prompt), photoUrl, ev, distance, reasons);
    }

    /** Server-side presence for the task: radius 80 m, accuracy, no mock location, a fresh photo taken at the place. */
    static List<String> presenceReasons(CityMonopolyConfig c, CityMonopolyRules.Evidence ev, double placeLat, double placeLng, long now) {
        List<String> r = new ArrayList<>();
        if (GeoDistanceUtils.haversineMeters(ev.lat(), ev.lng(), placeLat, placeLng) > PRESENCE_RADIUS_M) r.add("OUT_OF_RADIUS");
        if (ev.hAcc() == null || ev.hAcc() < 0 || ev.hAcc() > c.num("city.monopoly.claim.max_h_accuracy_m")) r.add("POOR_ACCURACY");
        if (ev.simulated()) r.add("SIMULATED_LOCATION");
        if (ev.capturedAtMillis() == null || now - ev.capturedAtMillis() > c.num("city.monopoly.claim.photo.max_capture_age_s") * 1000
                || ev.capturedAtMillis() - now > 5_000) {
            r.add("PHOTO_TOO_OLD");
        }
        if (ev.captureLat() == null || ev.captureLng() == null
                || GeoDistanceUtils.haversineMeters(ev.captureLat(), ev.captureLng(), placeLat, placeLng) > PRESENCE_RADIUS_M) {
            r.add("PHOTO_LOCATION_MISMATCH");
        }
        return r;
    }

    private Map<String, Object> existing(long memberId, String taskRef) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM city_onsite_task_submission WHERE member_id = ? AND task_ref = ?", memberId, taskRef);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> wire(Map<String, Object> row, boolean replay) {
        if (row == null) throw new CityGameException(409, "TASK_SUBMISSION_CONFLICT", "任务提交冲突，请重试");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("submissionId", row.get("submission_id"));
        out.put("taskRef", row.get("task_ref"));
        out.put("territoryId", row.get("territory_id"));
        out.put("result", row.get("result"));
        out.put("presenceOk", row.get("presence_ok") instanceof Number && ((Number) row.get("presence_ok")).intValue() == 1);
        out.put("distanceM", CityMonopolyService.dbl(row.get("distance_m")));
        out.put("radiusM", PRESENCE_RADIUS_M);
        out.put("photoState", row.get("vision_state"));
        out.put("confidence", row.get("confidence"));
        out.put("photoReason", row.get("vision_reason"));
        String reasons = row.get("reasons") == null ? "" : String.valueOf(row.get("reasons"));
        out.put("reasons", reasons.isEmpty() ? List.of() : List.of(reasons.split(",")));
        out.put("consumed", row.get("consumed_at") != null);
        out.put("idempotentReplay", replay);
        return out;
    }

    // =================================================================== verify (called by the challenge command)

    @Override
    public CityMonopolyRules.TaskResult verify(long memberId, String taskRef, String submissionId) {
        if (submissionId == null || submissionId.isBlank()) return CityMonopolyRules.TaskResult.NONE;
        if (!supports(taskRef)) return CityMonopolyRules.TaskResult.FAIL;
        int n = jdbc.update("UPDATE city_onsite_task_submission SET consumed_at = ? WHERE submission_id = ? AND member_id = ? AND task_ref = ? "
                + "AND consumed_at IS NULL", new Timestamp(clock.getAsLong()), submissionId, memberId, taskRef);
        if (n != 1) return CityMonopolyRules.TaskResult.FAIL;
        String result = jdbc.queryForObject("SELECT result FROM city_onsite_task_submission WHERE submission_id = ? AND member_id = ?",
                String.class, submissionId, memberId);
        try {
            return CityMonopolyRules.TaskResult.valueOf(result);
        } catch (RuntimeException unknown) {
            return CityMonopolyRules.TaskResult.FAIL;
        }
    }
}
