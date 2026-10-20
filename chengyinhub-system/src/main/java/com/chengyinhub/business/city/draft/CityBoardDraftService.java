package com.chengyinhub.business.city.draft;

import com.alibaba.fastjson2.JSON;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import static com.chengyinhub.business.city.draft.CityBoardDraftAuthority.Context;

/** Draft-only. Deliberately no Spring bean, runtime, points or publish dependency. */
public final class CityBoardDraftService {
    public enum Mode { DISABLED, ISOLATED_DRAFT_EVALUATION }
    public static final class Failure extends RuntimeException {
        public final int status;
        public Failure(int status, String code) { super(code); this.status = status; }
    }
    private final Mode mode;
    private final JdbcCityBoardDraftStore store;
    private final CityBoardDraftAuthority authority;
    public CityBoardDraftService() { this.mode = Mode.DISABLED; this.store = null; this.authority = null; }
    public CityBoardDraftService(Mode mode, JdbcCityBoardDraftStore store, CityBoardDraftAuthority authority) {
        this.mode = Objects.requireNonNull(mode); this.store = Objects.requireNonNull(store);
        this.authority = Objects.requireNonNull(authority);
    }
    private Context context() {
        require(mode == Mode.ISOLATED_DRAFT_EVALUATION, 503, "CITY_DRAFT_DISABLED");
        Context context = authority.requireCurrentPlatformEditor();
        require(context != null && context.platformEditor, 403, "PLATFORM_EDITOR_REQUIRED");
        id(context.actorId); return context;
    }
    public Map<String, Object> catalog() {
        Context context = context();
        return CityBoardDraftReferenceValidator.catalog(context, authority.catalog(context));
    }
    public Map<String, Object> validate(Map<String, Object> input) {
        Context context = context();
        Map<String, Object> command = command(input);
        Map<String, Object> payload = map(command.get("payload"));
        Map<String, Object> bindings = validatePayload(context, payload);
        return object("valid", true, "draftOnly", true, "payloadHash", hash(payload), "bindings", bindings);
    }
    public Map<String, Object> save(Map<String, Object> input) {
        Context context = context();
        Map<String, Object> command = command(input);
        Map<String, Object> payload = map(command.get("payload"));
        require(context.regionIds.contains(payload.get("regionId")), 403, "REGION_FORBIDDEN");
        // Persistence checks authorization and exact replay before consulting mutable reference facts.
        return store.save(context, command, hash(command), () -> validatePayload(context, payload));
    }
    public Map<String, Object> reopen(String draftId) {
        return storeRead(context(), id(draftId));
    }
    private Map<String, Object> storeRead(Context context, String draftId) {
        Map<String, Object> result = store.read(draftId);
        require(context.regionIds.contains(map(result.get("payload")).get("regionId")), 403, "REGION_FORBIDDEN");
        verifySnapshot(result); return result;
    }
    public Map<String, Object> preview(Map<String, Object> input) {
        Context context = context();
        keys(input, "draftId", "expectedVersion", "expectedHash");
        Map<String, Object> saved = storeRead(context, id(input.get("draftId")));
        require(number(input.get("expectedVersion"), 1, Integer.MAX_VALUE) == ((Number) saved.get("version")).longValue()
                && digest(input.get("expectedHash")).equals(saved.get("payloadHash")), 409, "CITY_DRAFT_CONFLICT");
        // Explicit projection, never a runtime board or an approval. No write occurs here.
        return object("contract", "CITY_BOARD_PLAYER_PREVIEW_V1", "status", "DRAFT_UNAPPROVED",
                "readOnly", true, "draftId", saved.get("draftId"), "version", saved.get("version"),
                "payloadHash", saved.get("payloadHash"), "platform_rule", saved.get("payload"),
                "merchant_operational_fact", saved.get("bindings"), "player_strategy", Collections.emptyList());
    }
    private Map<String, Object> command(Map<String, Object> input) {
        require(input != null && JSON.toJSONBytes(input).length <= 32768, 400, "CITY_DRAFT_TOO_LARGE");
        keys(input, "draftId", "requestId", "expectedVersion", "expectedHash", "payload");
        id(input.get("draftId")); id(input.get("requestId"));
        long version = number(input.get("expectedVersion"), 0, Integer.MAX_VALUE - 1);
        require(version == 0 ? input.get("expectedHash") == null : isDigest(input.get("expectedHash")), 400, "EXPECTED_HASH_REQUIRED");
        // JSON roundtrip detaches caller-owned objects; canonical ordering is applied by hash().
        return copy(input);
    }
    private Map<String, Object> validatePayload(Context context, Map<String, Object> payload) {
        keys(payload, "classification", "name", "regionId", "timezone", "opensAt", "closesAt", "rule", "servicePoints");
        require("platform_rule".equals(payload.get("classification")), 400, "PLATFORM_RULE_ONLY");
        text(payload.get("name"), 120); id(payload.get("regionId"));
        require(context.regionIds.contains(payload.get("regionId")), 403, "REGION_FORBIDDEN");
        try {
            ZoneId.of(text(payload.get("timezone"), 64));
            Instant start = Instant.parse(text(payload.get("opensAt"), 40));
            Instant end = Instant.parse(text(payload.get("closesAt"), 40));
            require(start.isBefore(end), 400, "INVALID_WINDOW");
        } catch (java.time.DateTimeException e) { throw new Failure(400, "INVALID_TIME"); }
        Map<String, Object> rule = map(payload.get("rule"));
        keys(rule, "rulesRef", "taskRef", "diceRef", "challengeRef", "settlementRef", "actionBudget",
                "maxHoldings", "protectionSeconds", "acquisitionCostPoints", "serviceFeePoints", "taskRewardPoints", "rewardBudgetPoints");
        for (String ref : Arrays.asList("rulesRef", "taskRef", "diceRef", "challengeRef", "settlementRef")) reference(rule.get(ref));
        number(rule.get("actionBudget"), 1, 1000000); number(rule.get("maxHoldings"), 1, 1000000);
        number(rule.get("protectionSeconds"), 0, 31536000);
        for (String key : Arrays.asList("acquisitionCostPoints", "serviceFeePoints", "taskRewardPoints", "rewardBudgetPoints"))
            number(rule.get(key), 0, Integer.MAX_VALUE);
        require(((Number) rule.get("rewardBudgetPoints")).longValue() >= ((Number) rule.get("taskRewardPoints")).longValue(),
                400, "REWARD_EXCEEDS_DRAFT_BUDGET");
        Object points = payload.get("servicePoints");
        require(points instanceof List && !((List<?>) points).isEmpty() && ((List<?>) points).size() <= 50, 400, "SERVICE_POINTS_REQUIRED");
        Set<String> seen = new HashSet<>();
        for (Object value : (List<?>) points) {
            Map<String, Object> point = map(value); keys(point, "placeId", "role");
            require(seen.add(id(point.get("placeId"))), 400, "DUPLICATE_PLACE");
            require("SERVICE".equals(point.get("role")), 400, "UNSUPPORTED_POINT_ROLE");
        }
        // Provider MUST resolve exact hash/version/kind and region; validate task feasibility, licensed
        // public safe places, availability/capacity and economic/policy limits. No client authority flags.
        Map<String, Object> bindings = CityBoardDraftReferenceValidator.validate(context, authority.catalog(context), payload);
        require(bindings != null && !bindings.isEmpty(), 503, "REFERENCE_AUTHORITY_UNAVAILABLE");
        return copy(bindings);
    }
    static void verifySnapshot(Map<String, Object> snapshot) {
        require("DRAFT_UNAPPROVED".equals(snapshot.get("status"))
                && hash(map(snapshot.get("payload"))).equals(snapshot.get("payloadHash")), 500, "CORRUPT_DRAFT");
    }
    static void reference(Object value) {
        Map<String, Object> ref = map(value); keys(ref, "id", "version", "hash");
        id(ref.get("id")); number(ref.get("version"), 1, Integer.MAX_VALUE); digest(ref.get("hash"));
    }
    public static void require(boolean condition, int status, String code) { if (!condition) throw new Failure(status, code); }
    static String id(Object value) { require(value instanceof String && ((String) value).matches("[A-Za-z0-9_-]{1,64}"), 400, "INVALID_ID"); return (String) value; }
    static String text(Object value, int max) { require(value instanceof String && !((String) value).trim().isEmpty() && ((String) value).length() <= max, 400, "INVALID_TEXT"); return (String) value; }
    static boolean isDigest(Object value) { return value instanceof String && ((String) value).matches("[a-f0-9]{64}"); }
    static String digest(Object value) { require(isDigest(value), 400, "INVALID_HASH"); return (String) value; }
    static long number(Object value, long min, long max) {
        require(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long, 400, "INTEGER_REQUIRED");
        long n = ((Number) value).longValue(); require(n >= min && n <= max, 400, "INTEGER_OUT_OF_RANGE"); return n;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> map(Object value) {
        require(value instanceof Map, 400, "OBJECT_REQUIRED"); return (Map<String, Object>) value;
    }
    static void keys(Map<String, Object> value, String... names) {
        require(value != null && value.keySet().equals(new HashSet<>(Arrays.asList(names))), 400, "UNKNOWN_OR_MISSING_FIELD");
    }
    public static Map<String, Object> object(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result;
    }
    @SuppressWarnings("unchecked") public static Map<String, Object> copy(Map<String, Object> value) {
        return JSON.parseObject(JSON.toJSONString(value), Map.class);
    }
    static Object sorted(Object value) {
        if (value instanceof Map) { Map<String, Object> out = new TreeMap<>(); map(value).forEach((k, v) -> out.put(k, sorted(v))); return out; }
        if (value instanceof List) { List<Object> out = new ArrayList<>(); for (Object v : (List<?>) value) out.add(sorted(v)); return out; }
        return value;
    }
    public static String hash(Map<String, Object> value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(JSON.toJSONString(sorted(value)).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : digest) out.append(String.format("%02x", b & 255)); return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
