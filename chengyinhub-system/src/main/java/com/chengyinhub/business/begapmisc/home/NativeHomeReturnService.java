package com.chengyinhub.business.begapmisc.home;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "I'm back" at the private home, then a one-time HP restore of one in-progress run (app {@code Core/NativeHomeReturnService},
 * {@code NativeHomeReturnRestore}, reform plan W19). Six routes under {@code /api/native/home/return}:
 * {@code policy}, {@code runs}, {@code account}, {@code verify}, {@code restore}, {@code receipt}.
 *
 * <p>The resource is the run's frozen HP ({@code sys.hp} of a {@code topic_route_session} in progress, maximum from the run's
 * frozen journey rules). Its version is the session row version, so any other runtime write is a version conflict and the
 * restore itself is the runtime's own state CAS (version + 1). Restore lands exactly on the maximum.
 *
 * <p>{@code verify} decides arrival on the server from the device fix: home active and its version current, accuracy within
 * policy, fix fresh, within the radius, outside the run's restore cooldown, and (if the policy has a dwell) a second check
 * with the issued {@code challengeId} after the dwell. Only {@code VERIFIED} carries a short-lived single-use proof.
 * Coordinates are used for the decision and never stored. Every verify and restore is stored per (owner, requestId) and
 * replays for the same body; another body is 409 {@code HOME_REQUEST_CONFLICT}.
 */
@Service
public class NativeHomeReturnService {
    static final String RESOURCE = "sys.hp";
    static final String ID = "[A-Za-z0-9._:-]{1,64}";
    static final String RUN_ID = "[1-9][0-9]{0,17}";
    static final int PAGE = 50;
    static final Set<String> VERIFY_KEYS = Set.of("requestId", "homeVersion", "expectedAccountVersion", "scopeType", "scopeId",
            "resourceType", "policyVersion", "latitude", "longitude", "datum", "accuracyMeters", "capturedAtMillis");
    static final Set<String> RESTORE_KEYS = Set.of("requestId", "proofId", "homeVersion", "expectedAccountVersion", "scopeType",
            "scopeId", "resourceType");
    private final JdbcTemplate jdbc;
    private final NativeHomePolicy policy;
    private final NativeHomeService homes;
    private final TopicRouteSessionMapper routes;
    private final TopicRouteRuntimeServiceImpl runtime;
    Clock clock = Clock.systemUTC();

    public NativeHomeReturnService(JdbcTemplate jdbc, NativeHomePolicy policy, NativeHomeService homes,
                                   TopicRouteSessionMapper routes, TopicRouteRuntimeServiceImpl runtime) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.homes = homes;
        this.routes = routes;
        this.runtime = runtime;
    }

    public Map<String, Object> policy() {
        requireEnabled();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("policyVersion", policy.version);
        view.put("maxAccuracyMeters", policy.maxAccuracyMeters);
        view.put("maxFixAgeSeconds", policy.maxFixAgeSeconds);
        view.put("dwellSeconds", policy.dwellSeconds);
        view.put("serverTimeMillis", clock.millis());
        return view;
    }

    /** The caller's in-progress runs with HP above zero, id descending, 50 per page. */
    @Transactional(readOnly = true)
    public Map<String, Object> runs(long memberId, String beforeRunId) {
        requireEnabled();
        if (beforeRunId != null && !beforeRunId.matches(RUN_ID)) throw new HomeRejection(400, "INVALID_HOME_RUN_CURSOR");
        long cursor = beforeRunId == null ? Long.MAX_VALUE : Long.parseLong(beforeRunId);
        List<Map<String, Object>> runs = new ArrayList<>();
        boolean truncated = false;
        while (!truncated) {
            List<Long> ids = jdbc.queryForList("SELECT id FROM topic_route_session WHERE member_id = ? AND status = ? AND id < ?"
                    + " ORDER BY id DESC LIMIT 200", Long.class, memberId, TopicRouteSession.STATUS_RUNNING, cursor);
            if (ids.isEmpty()) break;
            for (Long id : ids) {
                cursor = id;
                Map<String, Object> run = run(routes.selectAccessibleById(id, memberId));
                if (run == null) continue;
                if (runs.size() == PAGE) { truncated = true; break; }
                runs.add(run);
            }
            if (ids.size() < 200) break;
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("runs", runs);
        page.put("truncated", truncated);
        page.put("nextBeforeRunId", truncated ? runs.get(runs.size() - 1).get("runId") : null);
        return page;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> account(long memberId, String runId) {
        requireEnabled();
        if (runId == null || !runId.matches(RUN_ID)) throw new HomeRejection(400, "INVALID_HOME_RUN");
        TopicRouteSession session = routes.selectAccessibleById(Long.parseLong(runId), memberId);
        if (session == null) throw new HomeRejection(404, "HOME_RUN_NOT_FOUND");
        long[] hp = runtime.homeReturnHp(session);
        if (hp == null) throw new HomeRejection(409, "HOME_RUN_UNAVAILABLE");
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("resourceType", RESOURCE);
        account.put("scopeType", "RUN");
        account.put("scopeId", runId);
        account.put("current", hp[0]);
        account.put("maximum", hp[1]);
        account.put("version", session.getVersion().longValue());
        return account;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> verify(long memberId, String raw) {
        requireEnabled();
        Map<String, Object> body = HomeWire.object(raw, VERIFY_KEYS, Set.of("challengeId"), "INVALID_HOME_RETURN_REQUEST");
        String requestId = HomeWire.text(body, "requestId", ID, "INVALID_HOME_RETURN_REQUEST");
        long homeVersion = HomeWire.version(body, "homeVersion", "INVALID_HOME_RETURN_REQUEST");
        long expectedAccount = HomeWire.version(body, "expectedAccountVersion", "INVALID_HOME_RETURN_REQUEST");
        long runId = scope(body);
        String policyVersion = HomeWire.text(body, "policyVersion", ID, "INVALID_HOME_RETURN_REQUEST");
        if (!"WGS84".equals(body.get("datum"))) throw new HomeRejection(400, "INVALID_HOME_RETURN_REQUEST");
        double latitude = HomeWire.coordinate(body, "latitude", 90, "INVALID_HOME_RETURN_REQUEST").doubleValue();
        double longitude = HomeWire.coordinate(body, "longitude", 180, "INVALID_HOME_RETURN_REQUEST").doubleValue();
        BigDecimal accuracy = HomeWire.decimal(body, "accuracyMeters", "INVALID_HOME_RETURN_REQUEST");
        long capturedAt = HomeWire.version(body, "capturedAtMillis", "INVALID_HOME_RETURN_REQUEST");
        String challengeId = body.get("challengeId") == null ? null
                : HomeWire.text(body, "challengeId", "[0-9a-f]{32}", "INVALID_HOME_RETURN_REQUEST");
        if (accuracy.signum() < 0) throw new HomeRejection(400, "INVALID_HOME_RETURN_REQUEST");
        String digest = HomeWire.digest(body);

        Map<String, Object> home = homes.home(memberId, true);
        Map<String, Object> replay = stored("native_home_return_check", memberId, requestId, digest);
        if (replay != null) return replay;
        if (!policy.version.equals(policyVersion)) throw new HomeRejection(409, "HOME_RETURN_POLICY_CHANGED");
        long now = clock.millis();
        long currentHome = ((Number) home.get("version")).longValue();
        Verification v = new Verification(requestId, currentHome);
        TopicRouteSession session = routes.selectAccessibleByIdForUpdate(runId, memberId);
        long[] hp = session == null ? null : runtime.homeReturnHp(session);
        if (!"ACTIVE".equals(home.get("status"))) v.decision = "NO_HOME";
        else if (currentHome != homeVersion) v.decision = "HOME_CHANGED";
        else if (hp == null || hp[0] <= 0) v.decision = "RUN_UNAVAILABLE";
        else if (session.getVersion().longValue() != expectedAccount) throw new HomeRejection(409, "HOME_RETURN_VERSION_CONFLICT");
        else if (home.get("effective_at") != null && now < ((Number) home.get("effective_at")).longValue()) {
            v.cooldown(seconds(((Number) home.get("effective_at")).longValue() - now));
        } else if (accuracy.doubleValue() > policy.maxAccuracyMeters) v.decision = "LOW_ACCURACY";
        else if (capturedAt > now + 5_000 || now - capturedAt > policy.maxFixAgeSeconds * 1000) v.decision = "STALE_FIX";
        else if (HomeWire.meters(((BigDecimal) home.get("latitude")).doubleValue(), ((BigDecimal) home.get("longitude")).doubleValue(),
                latitude, longitude) > policy.radiusMeters) v.decision = "OUT_OF_RADIUS";
        else {
            Long cooldownUntil = cooldownUntil(memberId, runId);
            if (cooldownUntil != null && now < cooldownUntil) v.cooldown(seconds(cooldownUntil - now));
            else if (policy.dwellSeconds > 0 && challengeId == null) {
                v.dwell(HomeWire.randomId(), policy.dwellSeconds, expectedAccount);
            } else if (policy.dwellSeconds > 0) {
                List<Map<String, Object>> first = jdbc.queryForList("SELECT created_at FROM native_home_return_check"
                        + " WHERE member_id = ? AND challenge_id = ? AND run_id = ? AND home_version = ? AND decision = 'DWELL_REQUIRED'"
                        + " ORDER BY created_at LIMIT 1", memberId, challengeId, runId, currentHome);
                if (first.isEmpty()) throw new HomeRejection(409, "HOME_RETURN_CHALLENGE_EXPIRED");
                long started = ((Number) first.get(0).get("created_at")).longValue();
                long due = started + policy.dwellSeconds * 1000;
                if (now > due + (long) (policy.maxFixAgeSeconds * 1000) + policy.proofTtlSeconds * 1000) {
                    throw new HomeRejection(409, "HOME_RETURN_CHALLENGE_EXPIRED");
                }
                if (now < due) v.dwell(challengeId, seconds(due - now), expectedAccount);
                else v.verified(HomeWire.randomId(), now + policy.proofTtlSeconds * 1000, expectedAccount);
            } else v.verified(HomeWire.randomId(), now + policy.proofTtlSeconds * 1000, expectedAccount);
        }
        Map<String, Object> view = v.view();
        jdbc.update("INSERT INTO native_home_return_check (member_id, request_id, payload_digest, decision, run_id, home_version,"
                + " proof_id, challenge_id, expires_at, response_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                memberId, requestId, digest, v.decision, runId, currentHome, v.proofId, v.challengeId, v.expiresAt, json(view), now);
        return parse(json(view)); // the same shape a replay returns
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> restore(long memberId, String raw) {
        requireEnabled();
        Map<String, Object> body = HomeWire.object(raw, RESTORE_KEYS, Set.of(), "INVALID_HOME_RESTORE_REQUEST");
        String requestId = HomeWire.text(body, "requestId", ID, "INVALID_HOME_RESTORE_REQUEST");
        String proofId = HomeWire.text(body, "proofId", ID, "INVALID_HOME_RESTORE_REQUEST");
        long homeVersion = HomeWire.version(body, "homeVersion", "INVALID_HOME_RESTORE_REQUEST");
        long expected = HomeWire.version(body, "expectedAccountVersion", "INVALID_HOME_RESTORE_REQUEST");
        long runId = scope(body);
        String digest = HomeWire.digest(body);

        Map<String, Object> home = homes.home(memberId, true);
        Map<String, Object> replay = stored("native_home_restore_receipt", memberId, requestId, digest);
        if (replay != null) return replay;
        long now = clock.millis();
        TopicRouteSession session = routes.selectAccessibleByIdForUpdate(runId, memberId);
        long[] hp = session == null ? null : runtime.homeReturnHp(session);
        Receipt r = new Receipt(requestId, proofId, hp, session == null ? 0L : session.getVersion().longValue());
        List<Map<String, Object>> proofs = jdbc.queryForList("SELECT home_version, expires_at, consumed_at FROM native_home_return_check"
                + " WHERE member_id = ? AND proof_id = ? AND run_id = ? AND decision = 'VERIFIED' FOR UPDATE", memberId, proofId, runId);
        Map<String, Object> proof = proofs.isEmpty() ? null : proofs.get(0);
        long currentHome = ((Number) home.get("version")).longValue();
        boolean consume = false;
        if (proof == null) r.decision = "PROOF_UNAVAILABLE";
        else if (proof.get("consumed_at") != null) r.decision = "PROOF_CONSUMED";
        else if (((Number) proof.get("expires_at")).longValue() <= now) r.decision = "PROOF_EXPIRED";
        else if (!"ACTIVE".equals(home.get("status")) || currentHome != homeVersion
                || ((Number) proof.get("home_version")).longValue() != currentHome) r.decision = "STALE_HOME";
        else if (hp == null) r.decision = "RUN_UNAVAILABLE";
        else if (r.accountVersion != expected) r.decision = "VERSION_CONFLICT";
        else {
            Long cooldownUntil = cooldownUntil(memberId, runId);
            if (cooldownUntil != null && now < cooldownUntil) {
                r.decision = "COOLDOWN";
                r.retryAfterSeconds = seconds(cooldownUntil - now);
            } else if (hp[0] >= hp[1]) {
                r.decision = "ALREADY_AT_MAX";
                consume = true;
            } else if (runtime.restoreHomeReturnHp(session)) {
                r.decision = "RESTORED";
                r.current = hp[1];
                r.accountVersion = session.getVersion().longValue();
                consume = true;
            } else {
                r.decision = "RUN_UNAVAILABLE";
                r.current = 0;
                r.maximum = 0;
            }
        }
        if (consume && jdbc.update("UPDATE native_home_return_check SET consumed_at = ? WHERE member_id = ? AND proof_id = ?"
                + " AND consumed_at IS NULL", now, memberId, proofId) != 1) {
            throw new HomeRejection(409, "HOME_RUN_BUSY");
        }
        Map<String, Object> view = r.view();
        jdbc.update("INSERT INTO native_home_restore_receipt (member_id, request_id, payload_digest, proof_id, run_id, decision,"
                + " response_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                memberId, requestId, digest, proofId, runId, r.decision, json(view), now);
        return parse(json(view)); // the same shape a replay returns
    }

    /** The stored restore receipt for {@code requestId}, or null (the app reads an absent receipt as "never applied"). */
    @Transactional(readOnly = true)
    public Map<String, Object> receipt(long memberId, String requestId) {
        requireEnabled();
        if (requestId == null || !requestId.matches(ID)) throw new HomeRejection(400, "INVALID_HOME_RESTORE_REQUEST");
        List<String> rows = jdbc.queryForList("SELECT response_json FROM native_home_restore_receipt WHERE member_id = ?"
                + " AND request_id = ?", String.class, memberId, requestId);
        return rows.isEmpty() ? null : parse(rows.get(0));
    }

    private Map<String, Object> run(TopicRouteSession session) {
        long[] hp = session == null ? null : runtime.homeReturnHp(session);
        if (hp == null || hp[0] <= 0) return null;
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("runId", String.valueOf(session.getId()));
        run.put("activityId", String.valueOf(session.getActivityId() == null ? 0L : session.getActivityId()));
        run.put("topicId", String.valueOf(session.getTopicId()));
        run.put("playInstanceId", session.getPlayInstanceId());
        run.put("resourceType", RESOURCE);
        run.put("current", hp[0]);
        run.put("maximum", hp[1]);
        run.put("version", session.getVersion().longValue());
        return run;
    }

    private Long cooldownUntil(long memberId, long runId) {
        List<Long> last = jdbc.queryForList("SELECT MAX(created_at) FROM native_home_restore_receipt WHERE member_id = ?"
                + " AND run_id = ? AND decision = 'RESTORED'", Long.class, memberId, runId);
        Long at = last.isEmpty() ? null : last.get(0);
        return at == null ? null : at + policy.restoreCooldownSeconds * 1000;
    }

    private static long scope(Map<String, Object> body) {
        if (!"RUN".equals(body.get("scopeType")) || !RESOURCE.equals(body.get("resourceType"))) {
            throw new HomeRejection(400, "INVALID_HOME_SCOPE");
        }
        return Long.parseLong(HomeWire.text(body, "scopeId", RUN_ID, "INVALID_HOME_SCOPE"));
    }

    private Map<String, Object> stored(String table, long memberId, String requestId, String digest) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT payload_digest, response_json FROM " + table
                + " WHERE member_id = ? AND request_id = ?", memberId, requestId);
        if (rows.isEmpty()) return null;
        if (!digest.equals(rows.get(0).get("payload_digest"))) throw new HomeRejection(409, "HOME_REQUEST_CONFLICT");
        return parse((String) rows.get(0).get("response_json"));
    }

    private static int seconds(long millis) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, (millis + 999) / 1000));
    }

    private static String json(Map<String, Object> view) {
        try { return HomeWire.JSON.writeValueAsString(view); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Stored responses are read back with integral numbers as Long, so a replay equals the first answer. */
    private static final com.fasterxml.jackson.databind.ObjectMapper STORED = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS).build();

    private static Map<String, Object> parse(String json) {
        try { return STORED.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { }); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void requireEnabled() {
        if (!policy.returnEnabled()) throw new HomeRejection(403, "HOME_RETURN_NOT_APPROVED");
    }

    private static final class Verification {
        final String requestId;
        final long homeVersion;
        String decision, proofId, challengeId;
        Long expiresAt, accountVersion;
        Integer retryAfterSeconds;

        Verification(String requestId, long homeVersion) { this.requestId = requestId; this.homeVersion = homeVersion; }

        void cooldown(int seconds) { decision = "COOLDOWN"; retryAfterSeconds = seconds; }

        void dwell(String challenge, long seconds, long account) {
            decision = "DWELL_REQUIRED"; challengeId = challenge; retryAfterSeconds = (int) Math.max(1, seconds); accountVersion = account;
        }

        void verified(String proof, long expiry, long account) {
            decision = "VERIFIED"; proofId = proof; expiresAt = expiry; accountVersion = account;
        }

        Map<String, Object> view() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("requestId", requestId);
            view.put("decision", decision);
            view.put("proofId", proofId);
            view.put("homeVersion", homeVersion);
            view.put("expiresAtMillis", expiresAt);
            view.put("retryAfterSeconds", retryAfterSeconds);
            view.put("challengeId", challengeId);
            view.put("accountVersion", accountVersion);
            return view;
        }
    }

    private static final class Receipt {
        final String requestId, proofId;
        String decision;
        long current, maximum, accountVersion;
        Integer retryAfterSeconds;

        Receipt(String requestId, String proofId, long[] hp, long accountVersion) {
            this.requestId = requestId;
            this.proofId = proofId;
            this.current = hp == null ? 0 : hp[0];
            this.maximum = hp == null ? 0 : hp[1];
            this.accountVersion = accountVersion;
        }

        Map<String, Object> view() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("requestId", requestId);
            view.put("proofId", proofId);
            view.put("decision", decision);
            view.put("current", current);
            view.put("maximum", maximum);
            view.put("accountVersion", accountVersion);
            view.put("retryAfterSeconds", retryAfterSeconds);
            return view;
        }
    }
}
