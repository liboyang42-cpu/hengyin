package com.chengyinhub.business.city.draft;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/**
 * Pure, proposed CITY/MAP configuration. Parsing establishes structure and internal consistency only.
 * No constructor or result is an approval, membership fact, playable board, points effect or publish command.
 * Deliberately parallel to V1: no Spring bean, endpoint, persistence or runtime dependency.
 */
public final class CityBoardDraftV2 {
    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_JSON_BYTES = 131072;
    public static final long MAX_DURATION_SECONDS = 31622400L; // representation ceiling, not approved policy
    private CityBoardDraftV2() { }

    public enum ReferenceKind { RULES, TASK, DICE, CHALLENGE, SETTLEMENT }
    public enum LotRole { MERCHANT_SERVICE, PUBLIC_OPERABLE, PUBLIC_NEUTRAL }
    public enum CooperationMode { OWNER_COMBINATION, ACCEPTED_COOPERATION }
    public enum ActorKind { SOLO, TEAM }

    public static final class Failure extends IllegalArgumentException {
        public final String code;
        private Failure(String code) { super(code); this.code = code; }
    }
    /** Exact requested selector, never a claim that the referenced content is approved. */
    public static final class Reference {
        public final ReferenceKind kind;
        public final String id, hash, regionId;
        public final long version;
        private Reference(ReferenceKind kind, String id, long version, String hash, String regionId) {
            this.kind = kind; this.id = id; this.version = version; this.hash = hash; this.regionId = regionId;
        }
    }
    public static final class Window {
        public final Instant opensAt, closesAt;
        private Window(Instant opensAt, Instant closesAt) { this.opensAt = opensAt; this.closesAt = closesAt; }
    }
    /** Bounded instance under a stable officialGameId; the permanent game itself has no expiry. */
    public static final class Board {
        public final String boardId, seasonId, regionId, timezone;
        public final long version;
        public final Instant startsAt, freezeAt, endsAt;
        public final List<Window> openWindows;
        private Board(Map<String, Object> m) {
            keys(m, "boardId", "seasonId", "version", "regionId", "timezone", "startsAt", "freezeAt", "endsAt", "openWindows");
            boardId = id(m.get("boardId")); seasonId = id(m.get("seasonId")); version = integer(m.get("version"), 1, Integer.MAX_VALUE);
            regionId = id(m.get("regionId")); timezone = text(m.get("timezone"), 64);
            try { ZoneId.of(timezone); } catch (DateTimeException e) { throw fail("INVALID_TIMEZONE"); }
            startsAt = instant(m.get("startsAt")); freezeAt = instant(m.get("freezeAt")); endsAt = instant(m.get("endsAt"));
            require(startsAt.isBefore(freezeAt) && freezeAt.isBefore(endsAt), "INVALID_BOARD_WINDOW");
            require(Duration.between(startsAt, endsAt).compareTo(Duration.ofSeconds(MAX_DURATION_SECONDS)) <= 0, "BOARD_WINDOW_TOO_LONG");
            List<Window> windows = new ArrayList<>(); Instant previousEnd = null;
            for (Object raw : array(m.get("openWindows"), 1, 64)) {
                Map<String, Object> w = object(raw); keys(w, "opensAt", "closesAt");
                Instant open = instant(w.get("opensAt")), close = instant(w.get("closesAt"));
                require(open.isBefore(close) && !open.isBefore(startsAt) && !close.isAfter(freezeAt), "INVALID_OPEN_WINDOW");
                require(previousEnd == null || !open.isBefore(previousEnd), "OVERLAPPING_OR_UNORDERED_WINDOWS");
                windows.add(new Window(open, close)); previousEnd = close;
            }
            openWindows = immutable(windows);
        }
    }
    public static final class Policy {
        public final long actionBudget, actionRecoverySeconds, maxHoldings, holdingSeconds, protectionSeconds;
        public final long taskExpirySeconds, rewardBudgetPoints, privacyDelaySeconds;
        private Policy(Map<String, Object> m) {
            keys(m, "actionBudget", "actionRecoverySeconds", "maxHoldings", "holdingSeconds", "protectionSeconds",
                    "taskExpirySeconds", "rewardBudgetPoints", "privacyDelaySeconds");
            actionBudget = integer(m.get("actionBudget"), 1, 1000000);
            actionRecoverySeconds = integer(m.get("actionRecoverySeconds"), 1, MAX_DURATION_SECONDS);
            maxHoldings = integer(m.get("maxHoldings"), 1, 50);
            holdingSeconds = integer(m.get("holdingSeconds"), 1, MAX_DURATION_SECONDS);
            protectionSeconds = integer(m.get("protectionSeconds"), 0, holdingSeconds);
            taskExpirySeconds = integer(m.get("taskExpirySeconds"), 1, MAX_DURATION_SECONDS);
            rewardBudgetPoints = integer(m.get("rewardBudgetPoints"), 0, Integer.MAX_VALUE);
            privacyDelaySeconds = integer(m.get("privacyDelaySeconds"), 1, MAX_DURATION_SECONDS);
        }
    }
    /** Physical-place selector is distinct from a game lot and conveys no real-property ownership. */
    public static final class PlaceReference {
        public final String placeId, hash, regionId;
        public final long version;
        private PlaceReference(Map<String, Object> m, String region) {
            keys(m, "placeId", "version", "hash", "regionId");
            placeId = id(m.get("placeId")); version = integer(m.get("version"), 1, Integer.MAX_VALUE);
            hash = digest(m.get("hash")); regionId = id(m.get("regionId")); require(region.equals(regionId), "CROSS_REGION_PLACE");
        }
    }
    public static final class Capability {
        public final String capabilityId;
        public final Reference taskRef;
        public final long serviceFeePoints, taskRewardPoints;
        private Capability(Map<String, Object> m, RefReader refs, Policy policy, LotRole role) {
            keys(m, "capabilityId", "taskRef", "serviceFeePoints", "taskRewardPoints");
            capabilityId = id(m.get("capabilityId")); taskRef = refs.read(m.get("taskRef"), ReferenceKind.TASK);
            serviceFeePoints = integer(m.get("serviceFeePoints"), 0, Integer.MAX_VALUE);
            taskRewardPoints = integer(m.get("taskRewardPoints"), 0, policy.rewardBudgetPoints);
            require(role != LotRole.PUBLIC_NEUTRAL || serviceFeePoints == 0, "NEUTRAL_MUST_BE_FREE");
        }
    }
    public static final class GameLot {
        public final String gameLotId;
        public final PlaceReference placeRef;
        public final LotRole role;
        /** A requested merchant association, not ownership, consent or authorization; absent for public lots. */
        public final String merchantId;
        public final Reference taskRef, rulesRef;
        public final long taskRewardPoints;
        public final Long acquisitionCostPoints;
        public final List<Capability> capabilities;
        private GameLot(Map<String, Object> m, RefReader refs, Policy policy) {
            role = enumeration(m.get("role"), LotRole.class);
            if (role == LotRole.MERCHANT_SERVICE)
                keys(m, "gameLotId", "placeRef", "role", "merchantId", "taskRef", "rulesRef", "taskRewardPoints", "acquisitionCostPoints", "capabilities");
            else if (role == LotRole.PUBLIC_OPERABLE)
                keys(m, "gameLotId", "placeRef", "role", "taskRef", "rulesRef", "taskRewardPoints", "acquisitionCostPoints", "capabilities");
            else keys(m, "gameLotId", "placeRef", "role", "taskRef", "rulesRef", "taskRewardPoints", "capabilities");
            gameLotId = id(m.get("gameLotId")); placeRef = new PlaceReference(object(m.get("placeRef")), refs.region);
            merchantId = role == LotRole.MERCHANT_SERVICE ? id(m.get("merchantId")) : null;
            taskRef = refs.read(m.get("taskRef"), ReferenceKind.TASK); rulesRef = refs.read(m.get("rulesRef"), ReferenceKind.RULES);
            taskRewardPoints = integer(m.get("taskRewardPoints"), 0, policy.rewardBudgetPoints);
            // Not applicable for neutral lots: never invent a zero-cost acquisition quote.
            acquisitionCostPoints = role == LotRole.PUBLIC_NEUTRAL ? null : integer(m.get("acquisitionCostPoints"), 0, Integer.MAX_VALUE);
            List<Capability> values = new ArrayList<>(); Set<String> ids = new HashSet<>();
            for (Object value : array(m.get("capabilities"), 0, 8)) {
                Capability capability = new Capability(object(value), refs, policy, role);
                require(ids.add(capability.capabilityId), "DUPLICATE_CAPABILITY_ID"); values.add(capability);
            }
            capabilities = immutable(values);
        }
    }
    public static final class LotSet {
        public final String setId;
        public final List<String> gameLotIds;
        public final CooperationMode cooperationMode;
        public final Reference rulesRef;
        private LotSet(Map<String, Object> m, Map<String, GameLot> lots, RefReader refs) {
            keys(m, "setId", "gameLotIds", "cooperationMode", "rulesRef");
            setId = id(m.get("setId")); gameLotIds = ids(m.get("gameLotIds"), 2, 50);
            for (String lotId : gameLotIds) {
                require(lots.containsKey(lotId), "UNKNOWN_GAME_LOT");
                require(lots.get(lotId).role != LotRole.PUBLIC_NEUTRAL, "NEUTRAL_NOT_OPERATING_SET_MEMBER");
            }
            cooperationMode = enumeration(m.get("cooperationMode"), CooperationMode.class);
            rulesRef = refs.read(m.get("rulesRef"), ReferenceKind.RULES);
        }
    }
    public static final class Upgrade {
        public final String upgradeId, gameLotId;
        public final long level, costPoints, protectionSeconds;
        public final List<String> capabilityIds;
        public final Reference taskRef, rulesRef;
        private Upgrade(Map<String, Object> m, Map<String, GameLot> lots, RefReader refs, Policy policy) {
            keys(m, "upgradeId", "gameLotId", "level", "capabilityIds", "taskRef", "rulesRef", "costPoints", "protectionSeconds");
            upgradeId = id(m.get("upgradeId")); gameLotId = id(m.get("gameLotId"));
            GameLot lot = lots.get(gameLotId); require(lot != null, "UNKNOWN_GAME_LOT");
            require(lot.role != LotRole.PUBLIC_NEUTRAL, "NEUTRAL_NOT_ACQUIRABLE_OR_UPGRADABLE");
            level = integer(m.get("level"), 1, 8); costPoints = integer(m.get("costPoints"), 0, Integer.MAX_VALUE);
            protectionSeconds = integer(m.get("protectionSeconds"), 0, policy.protectionSeconds);
            capabilityIds = ids(m.get("capabilityIds"), 1, 8);
            Set<String> available = new HashSet<>(); for (Capability capability : lot.capabilities) available.add(capability.capabilityId);
            require(available.containsAll(capabilityIds), "UNKNOWN_LOT_CAPABILITY");
            taskRef = refs.read(m.get("taskRef"), ReferenceKind.TASK); rulesRef = refs.read(m.get("rulesRef"), ReferenceKind.RULES);
        }
    }
    public static final class Draft {
        public final int schemaVersion = SCHEMA_VERSION;
        public final String status = "PROPOSED";
        public final String scope = "CITY";
        public final String entry = "MAP";
        public final String officialGameId, name, canonicalHash;
        public final Board board;
        public final Policy policy;
        public final Reference rulesRef, taskRef, diceRef, challengeRef, settlementRef;
        public final List<GameLot> gameLots;
        public final List<LotSet> sets;
        public final List<Upgrade> upgrades;
        public final List<Reference> requestedReferences;
        private final String canonicalJson;
        private Draft(Map<String, Object> m) {
            keys(m, "schemaVersion", "classification", "status", "scope", "entry", "officialGameId", "name", "board", "ruleRefs", "policy", "gameLots", "sets", "upgrades");
            require(integer(m.get("schemaVersion"), 2, 2) == SCHEMA_VERSION, "SCHEMA_VERSION_REQUIRED");
            require("platform_rule".equals(m.get("classification")), "PLATFORM_RULE_ONLY");
            require("PROPOSED".equals(m.get("status")), "PROPOSAL_ONLY");
            require("CITY".equals(m.get("scope")) && "MAP".equals(m.get("entry")), "CITY_MAP_ONLY");
            officialGameId = id(m.get("officialGameId")); name = text(m.get("name"), 120);
            board = new Board(object(m.get("board"))); policy = new Policy(object(m.get("policy")));
            RefReader refs = new RefReader(board.regionId);
            Map<String, Object> rule = object(m.get("ruleRefs")); keys(rule, "rulesRef", "taskRef", "diceRef", "challengeRef", "settlementRef");
            rulesRef = refs.read(rule.get("rulesRef"), ReferenceKind.RULES); taskRef = refs.read(rule.get("taskRef"), ReferenceKind.TASK);
            diceRef = refs.read(rule.get("diceRef"), ReferenceKind.DICE); challengeRef = refs.read(rule.get("challengeRef"), ReferenceKind.CHALLENGE);
            settlementRef = refs.read(rule.get("settlementRef"), ReferenceKind.SETTLEMENT);
            Map<String, GameLot> lots = new LinkedHashMap<>(); Set<String> places = new HashSet<>(); int operable = 0, neutral = 0;
            for (Object raw : array(m.get("gameLots"), 2, 50)) {
                GameLot lot = new GameLot(object(raw), refs, policy);
                require(!lots.containsKey(lot.gameLotId), "DUPLICATE_GAME_LOT_ID"); lots.put(lot.gameLotId, lot);
                require(places.add(lot.placeRef.placeId), "DUPLICATE_PHYSICAL_PLACE");
                if (lot.role == LotRole.PUBLIC_NEUTRAL) neutral++; else operable++;
            }
            require(neutral > 0, "FREE_NEUTRAL_PATH_REQUIRED"); require(operable >= policy.maxHoldings, "HOLDING_LIMIT_EXCEEDS_LOTS");
            gameLots = immutable(new ArrayList<>(lots.values()));
            List<LotSet> setValues = new ArrayList<>(); Set<String> setIds = new HashSet<>();
            for (Object raw : array(m.get("sets"), 0, 25)) {
                LotSet set = new LotSet(object(raw), lots, refs); require(setIds.add(set.setId), "DUPLICATE_SET_ID"); setValues.add(set);
            }
            sets = immutable(setValues);
            List<Upgrade> upgradeValues = new ArrayList<>(); Set<String> upgradeIds = new HashSet<>(); Map<String, Set<Long>> levels = new HashMap<>();
            for (Object raw : array(m.get("upgrades"), 0, 100)) {
                Upgrade upgrade = new Upgrade(object(raw), lots, refs, policy);
                require(upgradeIds.add(upgrade.upgradeId), "DUPLICATE_UPGRADE_ID");
                Set<Long> lotLevels = levels.get(upgrade.gameLotId);
                if (lotLevels == null) { lotLevels = new HashSet<>(); levels.put(upgrade.gameLotId, lotLevels); }
                require(lotLevels.add(upgrade.level), "DUPLICATE_UPGRADE_LEVEL"); upgradeValues.add(upgrade);
            }
            for (Set<Long> values : levels.values()) for (long i = 1; i <= values.size(); i++) require(values.contains(i), "NONCONTIGUOUS_UPGRADE_LEVELS");
            upgrades = immutable(upgradeValues); requestedReferences = immutable(new ArrayList<>(refs.values.values()));
            canonicalJson = canonical(m); canonicalHash = sha256(canonicalJson);
        }
        public String canonicalJson() { return canonicalJson; }
        /** Structural binding only. Even a matching admission intent still requires server authorization. */
        public void requireBinding(AdmissionIntent intent) {
            require(intent != null && officialGameId.equals(intent.officialGameId) && board.boardId.equals(intent.boardId)
                    && board.version == intent.boardVersion && canonicalHash.equals(intent.boardHash), "ADMISSION_DRAFT_BINDING_MISMATCH");
        }
    }
    /** Untrusted intent. Never adapt this directly to CityContracts.Participation or a trusted Team. */
    public static final class ActorIntent {
        public final ActorKind actorKind;
        public final String memberId, teamId;
        public final Long expectedMembershipVersion;
        private ActorIntent(Map<String, Object> m) {
            actorKind = enumeration(m.get("actorKind"), ActorKind.class);
            if (actorKind == ActorKind.SOLO) keys(m, "actorKind", "memberId");
            else keys(m, "actorKind", "memberId", "teamId", "expectedMembershipVersion");
            memberId = id(m.get("memberId"));
            teamId = actorKind == ActorKind.TEAM ? id(m.get("teamId")) : null;
            expectedMembershipVersion = actorKind == ActorKind.TEAM ? integer(m.get("expectedMembershipVersion"), 1, Integer.MAX_VALUE) : null;
        }
    }
    public static final class AdmissionIntent {
        public final String officialGameId, boardId, boardHash, participationId;
        public final long boardVersion;
        public final ActorIntent actor;
        private AdmissionIntent(Map<String, Object> m) {
            keys(m, "schemaVersion", "officialGameId", "boardId", "boardVersion", "boardHash", "participationId", "actor");
            integer(m.get("schemaVersion"), 2, 2); officialGameId = id(m.get("officialGameId")); boardId = id(m.get("boardId"));
            boardVersion = integer(m.get("boardVersion"), 1, Integer.MAX_VALUE); boardHash = digest(m.get("boardHash"));
            participationId = id(m.get("participationId")); actor = new ActorIntent(object(m.get("actor")));
        }
    }
    public static Draft parse(String json) { return new Draft(parseObject(json)); }
    public static AdmissionIntent parseAdmissionIntent(String json) { return new AdmissionIntent(parseObject(json)); }

    private static final class RefReader {
        final String region;
        final Map<String, Reference> values = new LinkedHashMap<>();
        RefReader(String region) { this.region = region; }
        Reference read(Object value, ReferenceKind expected) {
            Map<String, Object> m = object(value); keys(m, "kind", "id", "version", "hash", "regionId", "scope");
            ReferenceKind kind = enumeration(m.get("kind"), ReferenceKind.class); require(kind == expected, "REFERENCE_KIND_MISMATCH");
            require("CITY".equals(m.get("scope")), "CITY_REFERENCE_ONLY");
            String id = id(m.get("id")), hash = digest(m.get("hash")), regionId = id(m.get("regionId"));
            long version = integer(m.get("version"), 1, Integer.MAX_VALUE); require(region.equals(regionId), "CROSS_REGION_REFERENCE");
            String key = id + ":" + version; Reference previous = values.get(key);
            require(previous == null || (previous.kind == kind && previous.hash.equals(hash)), "CONFLICTING_REFERENCE_BINDING");
            if (previous != null) return previous;
            Reference ref = new Reference(kind, id, version, hash, regionId); values.put(key, ref); return ref;
        }
    }
    private static Failure fail(String code) { return new Failure(code); }
    private static void require(boolean valid, String code) { if (!valid) throw fail(code); }
    private static String id(Object value) {
        require(value instanceof String && ((String) value).matches("[A-Za-z0-9_-]{1,64}"), "INVALID_ID"); return (String) value;
    }
    private static String digest(Object value) {
        require(value instanceof String && ((String) value).matches("[a-f0-9]{64}"), "INVALID_HASH"); return (String) value;
    }
    private static String text(Object value, int max) {
        require(value instanceof String && !((String) value).trim().isEmpty() && ((String) value).length() <= max, "INVALID_TEXT"); return (String) value;
    }
    private static long integer(Object value, long min, long max) {
        require(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long, "INTEGER_REQUIRED");
        long number = ((Number) value).longValue(); require(number >= min && number <= max, "INTEGER_OUT_OF_RANGE"); return number;
    }
    private static Instant instant(Object value) {
        String text = text(value, 30);
        try {
            Instant time = Instant.parse(text);
            require(text.equals(time.toString()) && text.matches("[0-9]{4}-.*Z"), "CANONICAL_UTC_TIME_REQUIRED"); return time;
        } catch (DateTimeException e) { throw fail("INVALID_TIME"); }
    }
    private static <T extends Enum<T>> T enumeration(Object value, Class<T> type) {
        require(value instanceof String, "INVALID_ENUM");
        try { return Enum.valueOf(type, (String) value); } catch (IllegalArgumentException e) { throw fail("INVALID_ENUM"); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        require(value instanceof Map, "OBJECT_REQUIRED"); return (Map<String, Object>) value;
    }
    private static List<?> array(Object value, int min, int max) {
        require(value instanceof List, "ARRAY_REQUIRED"); List<?> list = (List<?>) value;
        require(list.size() >= min && list.size() <= max, "ARRAY_SIZE_OUT_OF_RANGE"); return list;
    }
    private static List<String> ids(Object value, int min, int max) {
        List<String> out = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (Object raw : array(value, min, max)) { String id = id(raw); require(seen.add(id), "DUPLICATE_ID"); out.add(id); }
        return immutable(out);
    }
    private static <T> List<T> immutable(List<T> values) { return Collections.unmodifiableList(new ArrayList<>(values)); }
    private static void keys(Map<String, Object> value, String... expected) {
        require(value.keySet().equals(new HashSet<>(Arrays.asList(expected))), "UNKNOWN_OR_MISSING_FIELD");
    }
    private static Map<String, Object> parseObject(String json) {
        require(json != null && json.length() <= MAX_JSON_BYTES && json.getBytes(StandardCharsets.UTF_8).length <= MAX_JSON_BYTES, "JSON_TOO_LARGE");
        new StrictJson(json).check();
        try { return object(JSON.parse(json, JSONReader.Feature.DisableReferenceDetect)); }
        catch (Failure e) { throw e; }
        catch (RuntimeException e) { throw fail("MALFORMED_JSON"); }
    }
    private static Object sorted(Object value) {
        if (value instanceof Map) {
            Map<String, Object> out = new TreeMap<>(); for (Map.Entry<String, Object> e : object(value).entrySet()) out.put(e.getKey(), sorted(e.getValue())); return out;
        }
        if (value instanceof List) { List<Object> out = new ArrayList<>(); for (Object item : (List<?>) value) out.add(sorted(item)); return out; }
        return value;
    }
    private static String canonical(Map<String, Object> value) { return JSON.toJSONString(sorted(value)); }
    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** Bounded JSON grammar gate: Fastjson's permissive forms/duplicate-key overwrite are not this wire protocol. */
    private static final class StrictJson {
        final String source; int position;
        StrictJson(String source) { this.source = source; }
        void check() { value(0); whitespace(); require(position == source.length(), "MALFORMED_JSON"); }
        void whitespace() { while (position < source.length() && " \n\r\t".indexOf(source.charAt(position)) >= 0) position++; }
        char peek() { whitespace(); require(position < source.length(), "MALFORMED_JSON"); return source.charAt(position); }
        void expect(char c) { require(peek() == c, "MALFORMED_JSON"); position++; }
        void value(int depth) {
            require(depth <= 24, "JSON_TOO_DEEP"); char c = peek();
            if (c == '{') {
                position++; Set<String> names = new HashSet<>();
                if (peek() == '}') { position++; return; }
                while (true) {
                    require(peek() == '"', "MALFORMED_JSON"); String name = string();
                    require(names.add(name), "DUPLICATE_JSON_KEY");
                    // Parser metadata is never part of a proposal; reject before library binding.
                    require(!"$ref".equals(name) && !"@type".equals(name), "UNKNOWN_OR_MISSING_FIELD");
                    expect(':'); value(depth + 1);
                    char next = peek(); position++; if (next == '}') return; require(next == ',', "MALFORMED_JSON");
                }
            } else if (c == '[') {
                position++; if (peek() == ']') { position++; return; }
                while (true) { value(depth + 1); char next = peek(); position++; if (next == ']') return; require(next == ',', "MALFORMED_JSON"); }
            } else if (c == '"') string();
            else if (c == 't') literal("true"); else if (c == 'f') literal("false"); else if (c == 'n') literal("null");
            else number();
        }
        void literal(String value) { require(source.startsWith(value, position), "MALFORMED_JSON"); position += value.length(); }
        String string() {
            int start = position++; boolean ended = false;
            while (position < source.length()) {
                char c = source.charAt(position++);
                if (c == '"') { ended = true; break; }
                require(c >= 32, "MALFORMED_JSON");
                if (c == '\\') {
                    require(position < source.length(), "MALFORMED_JSON"); char escape = source.charAt(position++);
                    if (escape == 'u') {
                        for (int i = 0; i < 4; i++) {
                            require(position < source.length(), "MALFORMED_JSON");
                            char hex = source.charAt(position++);
                            require((hex >= '0' && hex <= '9') || (hex >= 'a' && hex <= 'f')
                                    || (hex >= 'A' && hex <= 'F'), "MALFORMED_JSON");
                        }
                    } else require("\"\\/bfnrt".indexOf(escape) >= 0, "MALFORMED_JSON");
                }
            }
            require(ended, "MALFORMED_JSON");
            String decoded;
            try { decoded = JSON.parseObject(source.substring(start, position), String.class); }
            catch (RuntimeException e) { throw fail("MALFORMED_JSON"); }
            for (int i = 0; i < decoded.length(); i++) {
                char c = decoded.charAt(i);
                if (Character.isHighSurrogate(c)) { require(i + 1 < decoded.length() && Character.isLowSurrogate(decoded.charAt(++i)), "MALFORMED_JSON"); }
                else require(!Character.isLowSurrogate(c), "MALFORMED_JSON");
            }
            return decoded;
        }
        void number() {
            int start = position; if (source.charAt(position) == '-') position++;
            require(position < source.length() && source.charAt(position) >= '0' && source.charAt(position) <= '9', "MALFORMED_JSON");
            if (source.charAt(position) == '0') position++;
            else while (position < source.length() && source.charAt(position) >= '0' && source.charAt(position) <= '9') position++;
            if (position < source.length()) require(".eE".indexOf(source.charAt(position)) < 0, "INTEGER_REQUIRED");
            String value = source.substring(start, position); require(!"-0".equals(value), "NONCANONICAL_INTEGER");
            try { Long.parseLong(value); } catch (NumberFormatException e) { throw fail("INTEGER_OUT_OF_RANGE"); }
        }
    }
}
