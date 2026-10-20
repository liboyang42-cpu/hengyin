package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/** Independent, audit-only iPhone provider. This code never writes legacy steps, score or completion. */
@Component
public class NativeStepProtocol {
    public static final String PROVIDER = "IOS_CMPEDOMETER_V1";
    public static final String ISSUE = "ISSUE_NATIVE_STEP_CHALLENGE";
    public static final String SUBMIT = "SUBMIT_NATIVE_STEPS";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long TTL = 120000L, CLOCK_SLACK = 5000L, ISSUE_INTERVAL = 30000L;
    private static final int MAX_CHALLENGES_PER_DAY = 240, MAX_DAY_STEPS = 100000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    @Value("${chengyin.native-steps.enabled:false}")
    private boolean enabled;
    @Autowired(required = false)
    private NativeStepAssertionVerifier verifier;

    public static boolean isNative(String action) { return ISSUE.equals(action) || SUBMIT.equals(action); }

    public void act(String action, Map<String, Object> payload, ObjectNode state,
                    GameRuntimeSession session, long accountId, String idempotencyKey, Instant now) {
        requireReady();
        if (!Integer.valueOf(GameRuntimeSession.OWNER_PLAYER).equals(session.getOwnerType())
                || !Long.valueOf(accountId).equals(session.getOwnerId())) fail("NATIVE_STEPS_UNSUPPORTED_OWNER");
        validateShape(action, payload, idempotencyKey);
        String key = (String) payload.get("deviceKeyId");
        if (!verifier.isEnrolledIPhoneKey(accountId, key)) fail("NATIVE_STEPS_DEVICE_NOT_ENROLLED");
        ObjectNode nativeState = state.with("nativeSteps");
        if (nativeState.has("deviceKeyId") && !key.equals(nativeState.path("deviceKeyId").asText())) {
            fail("NATIVE_STEPS_DEVICE_CHANGED");
        }
        if (ISSUE.equals(action)) issue(nativeState, session, accountId, key, now);
        else submit(nativeState, session, accountId, key, payload, idempotencyKey, now);
    }

    private void requireReady() {
        if (!enabled) fail("NATIVE_STEPS_DISABLED");
        if (verifier == null || !verifier.isReady()) fail("NATIVE_STEPS_VERIFIER_UNAVAILABLE");
    }

    private void issue(ObjectNode state, GameRuntimeSession session, long account, String key, Instant now) {
        long ms = now.toEpochMilli();
        if (state.has("lastIssuedAt") && ms - state.path("lastIssuedAt").asLong() < ISSUE_INTERVAL) {
            fail("NATIVE_STEPS_RATE_LIMITED");
        }
        LocalDate date = now.atZone(ZONE).toLocalDate();
        String day = date.toString();
        if (!day.equals(state.path("dayKey").asText())) {
            state.remove(Arrays.asList("baselineSteps", "lastSteps", "lastSampleEndAt", "acceptedDelta", "challenge"));
            state.put("dayKey", day);
            state.put("issuedCount", 0);
        }
        int count = state.path("issuedCount").asInt();
        if (count >= MAX_CHALLENGES_PER_DAY) fail("NATIVE_STEPS_RATE_LIMITED");
        long dayStart = date.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long dayEnd = date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        byte[] nonce = new byte[32]; random.nextBytes(nonce);
        StringBuilder id = new StringBuilder();
        for (byte b : nonce) id.append(String.format(Locale.ROOT, "%02x", b & 255));
        ObjectNode c = state.putObject("challenge");
        c.put("challengeId", id.toString()); c.put("provider", PROVIDER);
        c.put("accountId", account); c.put("sessionId", session.getId());
        c.put("attemptStartedAt", session.getStartedAt().getTime());
        c.put("sessionVersion", session.getVersion() + 1); c.put("deviceKeyId", key);
        c.put("issuedAt", ms); c.put("expiresAt", Math.min(ms + TTL, dayEnd));
        c.put("timeZone", ZONE.getId()); c.put("dayKey", day);
        c.put("dayStartAt", dayStart); c.put("dayEndAt", dayEnd);
        state.put("deviceKeyId", key); state.put("lastIssuedAt", ms); state.put("issuedCount", count + 1);
    }

    private void submit(ObjectNode state, GameRuntimeSession session, long account, String key,
                        Map<String, Object> payload, String idempotencyKey, Instant now) {
        JsonNode c = state.path("challenge");
        long ms = now.toEpochMilli();
        if (!c.isObject() || !c.path("challengeId").asText().equals(payload.get("challengeId"))) {
            fail("NATIVE_STEPS_CHALLENGE_INVALID");
        }
        if (c.path("consumed").asBoolean()) fail("NATIVE_STEPS_CHALLENGE_REPLAY");
        if (ms < c.path("issuedAt").asLong() || ms >= c.path("expiresAt").asLong()) fail("NATIVE_STEPS_CHALLENGE_EXPIRED");
        if (c.path("accountId").asLong() != account || c.path("sessionId").asLong() != session.getId()
                || c.path("attemptStartedAt").asLong() != session.getStartedAt().getTime()
                || c.path("sessionVersion").asInt() != session.getVersion()
                || !key.equals(c.path("deviceKeyId").asText())
                || !PROVIDER.equals(c.path("provider").asText())) fail("NATIVE_STEPS_BINDING_MISMATCH");
        String today = now.atZone(ZONE).toLocalDate().toString();
        if (!today.equals(c.path("dayKey").asText()) || !today.equals(payload.get("dayKey"))) fail("NATIVE_STEPS_DAY_CHANGED");
        long start = number(payload, "sampleStartAt"), end = number(payload, "sampleEndAt"), steps = number(payload, "cumulativeSteps");
        if (start != c.path("dayStartAt").asLong() || end < start || end >= c.path("dayEndAt").asLong()
                || end < c.path("issuedAt").asLong() - CLOCK_SLACK || end > ms + CLOCK_SLACK
                || ms - end > TTL || steps < 0 || steps > MAX_DAY_STEPS) fail("NATIVE_STEPS_SAMPLE_INVALID");
        long delta = 0;
        if (state.has("baselineSteps")) {
            long elapsed = end - state.path("lastSampleEndAt").asLong();
            delta = steps - state.path("lastSteps").asLong();
            if (elapsed < ISSUE_INTERVAL || delta < 0 || delta > elapsed / 1000L * 5L + 10L) {
                fail("NATIVE_STEPS_COUNTER_INVALID");
            }
        }
        String canonical = canonical(c, start, end, steps, session.getVersion(), idempotencyKey);
        long counter = verifier.verifyAndConsume(new NativeStepAssertionVerifier.Request(account,
                session.getId(), key, canonical), (String) payload.get("assertion"));
        if (counter <= state.path("assertionCounter").asLong(0)) fail("NATIVE_STEPS_ASSERTION_REPLAY");
        if (!state.has("baselineSteps")) state.put("baselineSteps", steps);
        state.put("lastSteps", steps); state.put("lastSampleEndAt", end);
        state.put("acceptedDelta", state.path("acceptedDelta").asLong(0) + delta);
        state.put("assertionCounter", counter); ((ObjectNode) c).put("consumed", true);
    }

    /** UTF-8, LF separators, decimal integers, no final LF. Hash these bytes with SHA-256 for App Attest. */
    public static String canonical(JsonNode c, long start, long end, long steps, int version, String key) {
        return String.join("\n", "CHENGYIN_NATIVE_STEPS_V1", c.path("provider").asText(),
                c.path("accountId").asText(), c.path("sessionId").asText(), c.path("attemptStartedAt").asText(),
                c.path("deviceKeyId").asText(), c.path("challengeId").asText(), c.path("issuedAt").asText(),
                c.path("expiresAt").asText(), c.path("timeZone").asText(), c.path("dayKey").asText(),
                c.path("dayStartAt").asText(), c.path("dayEndAt").asText(), Long.toString(start),
                Long.toString(end), Long.toString(steps), Integer.toString(version), key);
    }

    public static String requestDigest(String action, Map<String, Object> payload, Integer version, String key) {
        validateShape(action, payload, key);
        try {
            return TimeWindowSchedule.sha256(action + "\n" + version + "\n" + key + "\n"
                    + JSON.writeValueAsString(new TreeMap<>(payload)));
        } catch (java.io.IOException e) { throw new IllegalArgumentException("原生计步请求无效", e); }
    }

    private static void validateShape(String action, Map<String, Object> p, String key) {
        if (!isNative(action)) fail("NATIVE_STEPS_ACTION_INVALID");
        Set<String> allowed = new HashSet<>(Arrays.asList("provider", "deviceKeyId"));
        if (SUBMIT.equals(action)) allowed.addAll(Arrays.asList("challengeId", "dayKey", "sampleStartAt",
                "sampleEndAt", "cumulativeSteps", "assertion"));
        if (!p.keySet().equals(allowed) || !PROVIDER.equals(p.get("provider"))) fail("NATIVE_STEPS_PROVIDER_UNSUPPORTED");
        if (!(p.get("deviceKeyId") instanceof String) || !((String) p.get("deviceKeyId")).matches("[A-Za-z0-9+/=_-]{1,256}")) fail("NATIVE_STEPS_DEVICE_INVALID");
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,64}")) fail("NATIVE_STEPS_IDEMPOTENCY_INVALID");
        if (SUBMIT.equals(action)) {
            if (!(p.get("challengeId") instanceof String) || !((String) p.get("challengeId")).matches("[a-f0-9]{64}")
                    || !(p.get("dayKey") instanceof String) || !((String) p.get("dayKey")).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                    || !(p.get("assertion") instanceof String) || ((String) p.get("assertion")).length() > 16384
                    || !((String) p.get("assertion")).matches("[A-Za-z0-9+/=_-]+")) fail("NATIVE_STEPS_PROOF_INVALID");
            number(p, "sampleStartAt"); number(p, "sampleEndAt"); number(p, "cumulativeSteps");
        }
    }

    private static long number(Map<String, Object> p, String key) {
        Object n = p.get(key);
        if (!(n instanceof Integer || n instanceof Long)) fail("NATIVE_STEPS_SAMPLE_INVALID");
        return ((Number) n).longValue();
    }

    public Map<String, Object> view(JsonNode state, GameRuntimeSession session, long account) {
        Map<String, Object> view = new LinkedHashMap<>();
        boolean personal = Integer.valueOf(GameRuntimeSession.OWNER_PLAYER).equals(session.getOwnerType())
                && Long.valueOf(account).equals(session.getOwnerId());
        String reason = !enabled ? "NATIVE_STEPS_DISABLED"
                : verifier == null || !verifier.isReady() ? "NATIVE_STEPS_VERIFIER_UNAVAILABLE"
                : !personal ? "NATIVE_STEPS_UNSUPPORTED_OWNER" : "NATIVE_STEPS_AUDIT_ONLY";
        if ("NATIVE_STEPS_AUDIT_ONLY".equals(reason) && state.has("deviceKeyId")) {
            try {
                if (!verifier.isEnrolledIPhoneKey(account, state.path("deviceKeyId").asText())) reason = "NATIVE_STEPS_DEVICE_NOT_ENROLLED";
            } catch (RuntimeException unavailable) {
                // Native readiness can fail closed without taking away the unchanged legacy mini view.
                reason = "NATIVE_STEPS_VERIFIER_UNAVAILABLE";
            }
        }
        boolean ready = "NATIVE_STEPS_AUDIT_ONLY".equals(reason);
        view.put("protocolVersion", 1); view.put("provider", PROVIDER); view.put("enabled", ready);
        view.put("rewardEnabled", false); view.put("status", ready ? "AUDIT_ONLY" : "DISABLED");
        view.put("reasonCode", reason);
        view.put("accountId", account); view.put("sessionId", session.getId()); view.put("sessionVersion", session.getVersion());
        view.put("deviceKeyId", state.has("deviceKeyId") ? state.path("deviceKeyId").asText() : null);
        if (ready && state.has("challenge")) view.put("challenge", JSON.convertValue(state.path("challenge"), Map.class));
        if (ready && state.has("baselineSteps")) {
            view.put("baselineSteps", state.path("baselineSteps").asLong());
            view.put("acceptedDelta", state.path("acceptedDelta").asLong());
            view.put("lastSampleEndAt", state.path("lastSampleEndAt").asLong());
        }
        return view;
    }

    public static void fail(String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reasonCode", reason); data.put("provider", PROVIDER); data.put("rewardEnabled", false);
        throw new ServiceException("iPhone 计步暂不可用，请刷新状态后重试", 409).setData(data);
    }
}
