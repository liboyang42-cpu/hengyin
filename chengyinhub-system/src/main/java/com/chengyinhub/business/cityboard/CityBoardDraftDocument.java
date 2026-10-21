package com.chengyinhub.business.cityboard;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 平台草稿白名单。只保留授权域；玩家策略、商户经营事实、受保护协议字段和未声明的费率/概率/阈值一律拒绝。
 * 缺省不补费用、概率或安全阈值。
 */
public final class CityBoardDraftDocument {

    public static final class Violation {
        public final String code;
        public final String path;
        public final String fieldClass;

        public Violation(String code, String path, String fieldClass) {
            this.code = code;
            this.path = path;
            this.fieldClass = fieldClass;
        }
    }

    public static final int MAX_JSON_CHARS = 65536;
    private static final int MAX_ITEMS = 200;
    private static final Pattern ID = Pattern.compile("\\A[A-Za-z0-9][A-Za-z0-9_-]{0,95}\\z");
    private static final Pattern CURRENCY = Pattern.compile("\\A[A-Z]{3}\\z");
    private static final Pattern NUMBER = Pattern.compile("\\A-?(?:0|[1-9]\\d*)(?:\\.\\d+)?\\z");
    private static final Set<String> TOP = Set.of(
            "boardId", "dice", "gameId", "locationPermits", "permissionMatrix", "pointCost",
            "pointRoles", "points", "protection", "references", "regionId", "rules", "safety",
            "season", "seasonSettlement", "serviceFee", "tasks", "title");
    private static final Set<String> PLACE_DOMAINS = Set.of("POI", "MERCHANT", "PRIVATE_HOME");
    private static final Set<String> REF_DOMAINS = Set.of(
            "POI", "MERCHANT", "THEME_ROLE", "PRIVATE_HOME", "VIRTUAL_OPERATION");
    private static final Set<String> POINT_DOMAINS = Set.of(
            "PLATFORM_VIRTUAL", "POI", "MERCHANT", "THEME_ROLE", "PRIVATE_HOME", "VIRTUAL_OPERATION");
    private static final Map<String, String> FORBIDDEN = forbidden();

    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        JSON.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        JSON.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
    }

    public final ObjectNode canonical;
    public final String json;
    public final String contentHash;
    public final List<Violation> structural;

    private CityBoardDraftDocument(ObjectNode canonical, String json, String contentHash, List<Violation> structural) {
        this.canonical = canonical;
        this.json = json;
        this.contentHash = contentHash;
        this.structural = structural;
    }

    public static ObjectMapper mapper() {
        return JSON;
    }

    public static boolean isId(String value) {
        return value != null && ID.matcher(value).matches();
    }

    public static CityBoardDraftDocument parse(JsonNode raw) {
        List<Violation> errors = new ArrayList<Violation>();
        if (raw == null || raw.isNull() || !raw.isObject()) {
            errors.add(violation("MISSING_PARAM", "document", "platform_rule"));
            return failed(errors);
        }
        if (raw.toString().length() > MAX_JSON_CHARS) {
            errors.add(violation("MISSING_PARAM", "document", "platform_rule"));
            return failed(errors);
        }
        scanForbidden(raw, "", errors);
        if (!errors.isEmpty()) {
            return failed(errors);
        }
        for (String key : unknownKeys(raw, TOP)) {
            errors.add(violation("UNKNOWN_FIELD", key, "platform_rule"));
        }
        if (!errors.isEmpty()) {
            return failed(errors);
        }
        String region = textId(raw.get("regionId"), "regionId", true, errors);
        String gameId = textId(raw.get("gameId"), "gameId", true, errors);
        String boardId = textId(raw.get("boardId"), "boardId", true, errors);
        String title = label(raw.get("title"), "title", 160, true, errors);
        ObjectNode dice = section(raw, "dice", errors, CityBoardDraftDocument::dice);
        ObjectNode pointCost = section(raw, "pointCost", errors, CityBoardDraftDocument::pointCost);
        ObjectNode protection = section(raw, "protection", errors, CityBoardDraftDocument::protection);
        ObjectNode rules = section(raw, "rules", errors, CityBoardDraftDocument::rules);
        ObjectNode safety = section(raw, "safety", errors, CityBoardDraftDocument::safety);
        ObjectNode season = section(raw, "season", errors, CityBoardDraftDocument::season);
        ObjectNode settlement = section(raw, "seasonSettlement", errors, CityBoardDraftDocument::settlement);
        ObjectNode serviceFee = section(raw, "serviceFee", errors, CityBoardDraftDocument::serviceFee);
        ArrayNode locationPermits = array(raw, "locationPermits", errors, CityBoardDraftDocument::permit);
        ArrayNode permissionMatrix = array(raw, "permissionMatrix", errors, (node, path, out) -> matrix(node, path, region, out));
        ArrayNode pointRoles = array(raw, "pointRoles", errors, CityBoardDraftDocument::pointRole);
        ArrayNode points = array(raw, "points", errors, CityBoardDraftDocument::point);
        ArrayNode references = array(raw, "references", errors, CityBoardDraftDocument::reference);
        ArrayNode tasks = array(raw, "tasks", errors, CityBoardDraftDocument::task);
        if (!errors.isEmpty() || region == null || gameId == null || boardId == null || title == null) {
            return failed(errors);
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("boardId", boardId);
        if (dice != null) {
            out.set("dice", dice);
        }
        out.put("gameId", gameId);
        if (locationPermits != null) {
            out.set("locationPermits", locationPermits);
        }
        if (permissionMatrix != null) {
            out.set("permissionMatrix", permissionMatrix);
        }
        if (pointCost != null) {
            out.set("pointCost", pointCost);
        }
        if (pointRoles != null) {
            out.set("pointRoles", pointRoles);
        }
        if (points != null) {
            out.set("points", points);
        }
        if (protection != null) {
            out.set("protection", protection);
        }
        if (references != null) {
            out.set("references", references);
        }
        out.put("regionId", region);
        if (rules != null) {
            out.set("rules", rules);
        }
        if (safety != null) {
            out.set("safety", safety);
        }
        if (season != null) {
            out.set("season", season);
        }
        if (settlement != null) {
            out.set("seasonSettlement", settlement);
        }
        if (serviceFee != null) {
            out.set("serviceFee", serviceFee);
        }
        if (tasks != null) {
            out.set("tasks", tasks);
        }
        out.put("title", title);
        String json = write(out);
        return new CityBoardDraftDocument(out, json, CityBoardHashes.sha256(json), List.of());
    }

    public List<Violation> publicationViolations(CityBoardSourceProbe probe) {
        if (!structural.isEmpty() || canonical == null) {
            return structural;
        }
        List<Violation> out = new ArrayList<Violation>();
        require("dice", "MISSING_PARAM", out);
        require("locationPermits", "MISSING_PARAM", out);
        require("permissionMatrix", "MISSING_PARAM", out);
        require("pointCost", "MISSING_FEE", out);
        require("pointRoles", "MISSING_PARAM", out);
        require("points", "MISSING_PARAM", out);
        require("protection", "MISSING_PARAM", out);
        require("rules", "MISSING_PARAM", out);
        require("safety", "MISSING_PARAM", out);
        require("season", "MISSING_PARAM", out);
        require("seasonSettlement", "SETTLEMENT_MISMATCH", out);
        require("serviceFee", "MISSING_FEE", out);
        require("tasks", "TASK_NOT_COMPLETABLE", out);
        if (!out.isEmpty()) {
            return out;
        }
        economy(out);
        completability(out);
        matrix(out);
        sources(probe == null ? new CityBoardSourceProbe() : probe, out);
        return out;
    }

    public String regionId() {
        return canonical == null ? null : canonical.path("regionId").asText(null);
    }

    public String gameId() {
        return canonical == null ? null : canonical.path("gameId").asText(null);
    }

    public String boardId() {
        return canonical == null ? null : canonical.path("boardId").asText(null);
    }

    public String title() {
        return canonical == null ? null : canonical.path("title").asText(null);
    }

    public String seasonId() {
        JsonNode season = canonical == null ? null : canonical.get("season");
        return season == null ? null : season.path("seasonId").asText(null);
    }

    private void require(String key, String code, List<Violation> out) {
        JsonNode node = canonical.get(key);
        if (node == null || node.isNull() || (node.isArray() && key.equals("tasks") && node.isEmpty())
                || (node.isArray() && key.equals("points") && node.isEmpty())
                || (node.isArray() && key.equals("pointRoles") && node.isEmpty())
                || (node.isArray() && key.equals("permissionMatrix") && node.isEmpty())) {
            out.add(violation(code, key, "platform_rule"));
        }
    }

    private void economy(List<Violation> out) {
        JsonNode budget = canonical.path("season").path("budget");
        JsonNode settlement = canonical.get("seasonSettlement");
        if (!budget.isObject() || settlement == null) {
            return;
        }
        if (!budget.path("currency").asText().equals(settlement.path("budgetCurrency").asText())
                || budget.path("amountMinor").asLong() != settlement.path("budgetAmountMinor").asLong()) {
            out.add(violation("SETTLEMENT_MISMATCH", "seasonSettlement", "platform_rule"));
        }
        JsonNode fee = canonical.get("serviceFee");
        if (fee != null && !fee.path("currency").asText().equals(budget.path("currency").asText())) {
            out.add(violation("INCOMPARABLE_FEE", "serviceFee", "platform_rule"));
        } else if (fee != null && fee.path("amountMinor").asLong() > budget.path("amountMinor").asLong()) {
            out.add(violation("FEE_EXCEEDS_BUDGET", "serviceFee", "platform_rule"));
        }
        JsonNode cost = canonical.get("pointCost");
        if (cost != null && "MINOR".equals(cost.path("unit").asText())) {
            if (!cost.path("currency").asText().equals(budget.path("currency").asText())) {
                out.add(violation("INCOMPARABLE_FEE", "pointCost", "platform_rule"));
            } else if (cost.path("amount").asLong() > budget.path("amountMinor").asLong()) {
                out.add(violation("FEE_EXCEEDS_BUDGET", "pointCost", "platform_rule"));
            }
        }
    }

    private void completability(List<Violation> out) {
        Set<String> roles = ids(canonical.get("pointRoles"), "roleId");
        Set<String> points = ids(canonical.get("points"), "pointId");
        for (JsonNode point : canonical.get("points")) {
            if (!roles.contains(point.path("roleId").asText())) {
                out.add(violation("TASK_NOT_COMPLETABLE", "points." + point.path("pointId").asText(), "platform_rule"));
            }
        }
        JsonNode tasks = canonical.get("tasks");
        if (tasks == null || !tasks.isArray() || tasks.isEmpty()) {
            return;
        }
        for (JsonNode task : tasks) {
            if (!points.contains(task.path("pointId").asText()) || !task.path("steps").isArray() || task.path("steps").isEmpty()) {
                out.add(violation("TASK_NOT_COMPLETABLE", "tasks." + task.path("taskId").asText(), "platform_rule"));
            }
        }
    }

    private void matrix(List<Violation> out) {
        String scope = "region:" + canonical.path("regionId").asText();
        boolean playerRead = false;
        for (JsonNode entry : canonical.get("permissionMatrix")) {
            if (CityBoardPermissions.PLAYER_READ.equals(entry.path("permission").asText())
                    && scope.equals(entry.path("scope").asText())) {
                playerRead = true;
            }
            if (!scope.equals(entry.path("scope").asText())) {
                out.add(violation("PERMISSION_SCOPE", "permissionMatrix", "platform_rule"));
            }
        }
        if (!playerRead) {
            out.add(violation("PERMISSION_MATRIX", "permissionMatrix", "platform_rule"));
        }
    }

    private void sources(CityBoardSourceProbe probe, List<Violation> out) {
        Map<String, JsonNode> refs = new LinkedHashMap<String, JsonNode>();
        JsonNode listed = canonical.get("references");
        if (listed != null) {
            for (JsonNode ref : listed) {
                String key = ref.path("domain").asText() + "|" + ref.path("source").asText() + "|" + ref.path("id").asText();
                refs.put(key, ref);
                addProbe(probe, ref.path("domain").asText(), ref.path("source").asText(), ref.path("id").asText(),
                        "references", ref.path("domain").asText(), out);
            }
        }
        Map<String, Integer> permits = new LinkedHashMap<String, Integer>();
        for (JsonNode permit : canonical.get("locationPermits")) {
            permits.merge(permit.path("pointId").asText(), 1, Integer::sum);
            addProbe(probe, "LOCATION_PERMIT", permit.path("source").asText(), permit.path("permitId").asText(),
                    "locationPermits", "LOCATION_PERMIT", out);
        }
        for (JsonNode point : canonical.get("points")) {
            String domain = point.path("domain").asText();
            String pointId = point.path("pointId").asText();
            if ("PLATFORM_VIRTUAL".equals(domain)) {
                if (permits.containsKey(pointId)) {
                    out.add(violation("LOCATION_PERMIT", "points." + pointId, "platform_rule"));
                }
                continue;
            }
            JsonNode ref = point.get("ref");
            String key = domain + "|" + ref.path("source").asText() + "|" + ref.path("id").asText();
            if (!refs.containsKey(key)) {
                out.add(violation("REFERENCE_MISMATCH", "points." + pointId, domainClass(domain)));
            }
            if (PLACE_DOMAINS.contains(domain) && permits.getOrDefault(pointId, 0) != 1) {
                out.add(violation("LOCATION_PERMIT", "points." + pointId, domainClass(domain)));
            }
            if (!PLACE_DOMAINS.contains(domain) && permits.containsKey(pointId)) {
                out.add(violation("LOCATION_PERMIT", "points." + pointId, domainClass(domain)));
            }
        }
    }

    private static void addProbe(CityBoardSourceProbe probe, String domain, String source, String id, String path,
            String fieldDomain, List<Violation> out) {
        CityBoardSourceProbe.Verdict verdict = probe.probe(domain, source, id);
        if (verdict == CityBoardSourceProbe.Verdict.FOUND) {
            return;
        }
        String code = switch (verdict) {
            case NOT_FOUND -> "SOURCE_NOT_FOUND";
            case DENIED -> "SOURCE_DENIED";
            case FOUND -> "SOURCE_UNAVAILABLE";
            case UNAVAILABLE -> "SOURCE_UNAVAILABLE";
        };
        out.add(violation(code, path, domainClass(fieldDomain)));
    }

    private static String domainClass(String domain) {
        return switch (domain) {
            case "MERCHANT" -> "merchant_operational_fact";
            case "POI" -> "poi";
            case "PRIVATE_HOME" -> "private_home";
            case "THEME_ROLE" -> "theme_role";
            case "VIRTUAL_OPERATION" -> "virtual_operation";
            case "LOCATION_PERMIT" -> "location_permit";
            default -> "platform_rule";
        };
    }

    private static Set<String> ids(JsonNode array, String field) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<String>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                ids.add(node.path(field).asText());
            }
        }
        return ids;
    }

    private interface SectionParser {
        ObjectNode parse(JsonNode node, String path, List<Violation> errors);
    }

    private interface ItemParser {
        ObjectNode parse(JsonNode node, String path, List<Violation> errors);
    }

    private static ObjectNode section(JsonNode raw, String key, List<Violation> errors, SectionParser parser) {
        JsonNode node = raw.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        return parser.parse(node, key, errors);
    }

    private static ArrayNode array(JsonNode raw, String key, List<Violation> errors, ItemParser parser) {
        JsonNode node = raw.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray() || node.size() > MAX_ITEMS) {
            errors.add(violation("MISSING_PARAM", key, "platform_rule"));
            return null;
        }
        ArrayNode out = JSON.createArrayNode();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
        for (int i = 0; i < node.size(); i++) {
            ObjectNode item = parser.parse(node.get(i), key + "[" + i + "]", errors);
            if (item == null) {
                continue;
            }
            String identity = item.has("pointId") ? item.get("pointId").asText()
                    : item.has("taskId") ? item.get("taskId").asText()
                    : item.has("roleId") ? item.get("roleId").asText()
                    : item.has("permission") ? item.get("permission").asText()
                    : item.has("domain") ? item.get("domain").asText() + "|" + item.path("source").asText() + "|" + item.path("id").asText()
                    : item.path("permitId").asText() + "|" + item.path("pointId").asText();
            if (!seen.add(identity)) {
                errors.add(violation("DUPLICATE", key + "[" + i + "]", "platform_rule"));
            }
            out.add(item);
        }
        return out;
    }

    private static ObjectNode dice(JsonNode node, String path, List<Violation> errors) {
        if (!shape(node, path, Set.of("diceId", "faces", "weights"), errors)) {
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        String diceId = textId(node.get("diceId"), path + ".diceId", true, errors);
        ArrayNode faces = integrals(node.get("faces"), path + ".faces", 1, MAX_ITEMS, false, errors);
        ArrayNode weights = node.has("weights")
                ? integrals(node.get("weights"), path + ".weights", 1, MAX_ITEMS, true, errors) : null;
        if (diceId == null || faces == null) {
            return null;
        }
        if (weights != null && weights.size() != faces.size()) {
            errors.add(violation("MISSING_PARAM", path + ".weights", "platform_rule"));
        }
        out.put("diceId", diceId);
        out.set("faces", faces);
        if (weights != null) {
            out.set("weights", weights);
        }
        return out;
    }

    private static ObjectNode pointCost(JsonNode node, String path, List<Violation> errors) {
        if (!shape(node, path, Set.of("amount", "currency", "unit"), errors)) {
            return null;
        }
        String unit = node.path("unit").asText(null);
        if (!"POINT".equals(unit) && !"MINOR".equals(unit)) {
            errors.add(violation("INVALID_FEE", path + ".unit", "platform_rule"));
            return null;
        }
        Long amount = integral(node.get("amount"), path + ".amount", 0, errors);
        ObjectNode out = JSON.createObjectNode();
        out.put("amount", amount == null ? 0 : amount);
        if ("MINOR".equals(unit)) {
            String currency = currency(node.get("currency"), path + ".currency", errors);
            if (amount == null || currency == null) {
                return null;
            }
            out.put("currency", currency);
        } else if (node.has("currency")) {
            errors.add(violation("UNKNOWN_FIELD", path + ".currency", "platform_rule"));
            return null;
        } else if (amount == null) {
            return null;
        }
        out.put("unit", unit);
        return out;
    }

    private static ObjectNode protection(JsonNode node, String path, List<Violation> errors) {
        if (!shape(node, path, Set.of("challengeId", "text"), errors)) {
            return null;
        }
        String challengeId = textId(node.get("challengeId"), path + ".challengeId", true, errors);
        String text = label(node.get("text"), path + ".text", 160, true, errors);
        if (challengeId == null || text == null) {
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("challengeId", challengeId);
        out.put("text", text);
        return out;
    }

    private static ObjectNode rules(JsonNode node, String path, List<Violation> errors) {
        if (!shape(node, path, Set.of("rulesId", "text"), errors)) {
            return null;
        }
        String rulesId = textId(node.get("rulesId"), path + ".rulesId", true, errors);
        String text = label(node.get("text"), path + ".text", 4000, true, errors);
        if (rulesId == null || text == null) {
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("rulesId", rulesId);
        out.put("text", text);
        return out;
    }

    private static ObjectNode safety(JsonNode node, String path, List<Violation> errors) {
        if (!shape(node, path, Set.of("confirmed", "limits", "statement"), errors)) {
            return null;
        }
        String statement = label(node.get("statement"), path + ".statement", 400, true, errors);
        JsonNode confirmed = node.get("confirmed");
        if (confirmed == null || !confirmed.isBoolean() || !confirmed.booleanValue()) {
            errors.add(violation("MISSING_PARAM", path + ".confirmed", "platform_rule"));
        }
        ObjectNode limits = null;
        if (node.has("limits")) {
            limits = limits(node.get("limits"), path + ".limits", errors);
        }
        if (statement == null || confirmed == null || !confirmed.isBoolean() || !confirmed.booleanValue()) {
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("confirmed", true);
        if (limits != null) {
            out.set("limits", limits);
        }
        out.put("statement", statement);
        return out;
    }

    private static ObjectNode limits(JsonNode node, String path, List<Violation> errors) {
        if (!node.isObject() || node.isEmpty()) {
            errors.add(violation("MISSING_PARAM", path, "platform_rule"));
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        List<String> names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        java.util.Collections.sort(names);
        for (String name : names) {
            if (!ID.matcher(name).matches() || FORBIDDEN.containsKey(name)) {
                errors.add(violation("UNKNOWN_FIELD", path + "." + name, "platform_rule"));
                continue;
            }
            Long value = integral(node.get(name), path + "." + name, 0, errors);
            if (value != null) {
                out.put(name, value);
            }
        }
        return out;
    }

    private static ObjectNode season(JsonNode node, String path, List<Violation> errors) {
        if (!shape(node, path, Set.of("budget", "seasonId"), errors)) {
            return null;
        }
        String seasonId = textId(node.get("seasonId"), path + ".seasonId", true, errors);
        ObjectNode budget = node.has("budget") ? money(node.get("budget"), path + ".budget", errors) : null;
        if (!node.has("budget")) {
            errors.add(violation("MISSING_FEE", path + ".budget", "platform_rule"));
        }
        if (seasonId == null || budget == null) {
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        out.set("budget", budget);
        out.put("seasonId", seasonId);
        return out;
    }

    private static ObjectNode settlement(JsonNode node, String path, List<Violation> errors) {
        if (!shape(node, path, Set.of("budgetAmountMinor", "budgetCurrency"), errors)) {
            return null;
        }
        Long amount = integral(node.get("budgetAmountMinor"), path + ".budgetAmountMinor", 0, errors);
        String currency = currency(node.get("budgetCurrency"), path + ".budgetCurrency", errors);
        if (amount == null || currency == null) {
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("budgetAmountMinor", amount);
        out.put("budgetCurrency", currency);
        return out;
    }

    private static ObjectNode serviceFee(JsonNode node, String path, List<Violation> errors) {
        return money(node, path, errors);
    }

    private static ObjectNode money(JsonNode node, String path, List<Violation> errors) {
        if (!shape(node, path, Set.of("amountMinor", "currency"), errors)) {
            return null;
        }
        Long amount = integral(node.get("amountMinor"), path + ".amountMinor", 0, errors);
        String currency = currency(node.get("currency"), path + ".currency", errors);
        if (amount == null || currency == null) {
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("amountMinor", amount);
        out.put("currency", currency);
        return out;
    }

    private static ObjectNode pointRole(JsonNode node, String path, List<Violation> errors) {
        if (!shape(node, path, Set.of("roleId", "title"), errors)) {
            return null;
        }
        String roleId = textId(node.get("roleId"), path + ".roleId", true, errors);
        String title = label(node.get("title"), path + ".title", 160, true, errors);
        if (roleId == null || title == null) {
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("roleId", roleId);
        out.put("title", title);
        return out;
    }

    private static ObjectNode point(JsonNode node, String path, List<Violation> errors) {
        if (!node.isObject()) {
            errors.add(violation("MISSING_PARAM", path, "platform_rule"));
            return null;
        }
        String domain = node.path("domain").asText(null);
        if (domain == null || !POINT_DOMAINS.contains(domain)) {
            errors.add(violation("UNKNOWN_FIELD", path + ".domain", "platform_rule"));
            return null;
        }
        Set<String> allowed = "PLATFORM_VIRTUAL".equals(domain)
                ? Set.of("domain", "latitude", "longitude", "pointId", "roleId", "title")
                : Set.of("domain", "latitude", "longitude", "pointId", "ref", "roleId", "title");
        if (!shape(node, path, allowed, errors)) {
            return null;
        }
        String pointId = textId(node.get("pointId"), path + ".pointId", true, errors);
        String title = label(node.get("title"), path + ".title", 160, true, errors);
        String roleId = textId(node.get("roleId"), path + ".roleId", true, errors);
        DecimalNode latitude = coordinate(node.get("latitude"), path + ".latitude", -90, 90, errors);
        DecimalNode longitude = coordinate(node.get("longitude"), path + ".longitude", -180, 180, errors);
        ObjectNode ref = null;
        if (!"PLATFORM_VIRTUAL".equals(domain)) {
            if (!node.has("ref")) {
                errors.add(violation("REFERENCE_MISMATCH", path + ".ref", domainClass(domain)));
            } else {
                ref = reference(node.get("ref"), path + ".ref", errors);
                if (ref != null && !domain.equals(ref.path("domain").asText())) {
                    errors.add(violation("REFERENCE_MISMATCH", path + ".ref", domainClass(domain)));
                }
            }
        }
        if (pointId == null || title == null || roleId == null || latitude == null || longitude == null) {
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("domain", domain);
        out.set("latitude", latitude);
        out.set("longitude", longitude);
        out.put("pointId", pointId);
        if (ref != null) {
            out.set("ref", ref);
        }
        out.put("roleId", roleId);
        out.put("title", title);
        return out;
    }

    private static ObjectNode reference(JsonNode node, String path, List<Violation> errors) {
        if (!shape(node, path, Set.of("domain", "id", "source"), errors)) {
            return null;
        }
        String domain = node.path("domain").asText(null);
        if (domain == null || !REF_DOMAINS.contains(domain)) {
            errors.add(violation("UNKNOWN_FIELD", path + ".domain", "merchant_operational_fact"));
            return null;
        }
        String source = textId(node.get("source"), path + ".source", true, errors);
        String id = textId(node.get("id"), path + ".id", true, errors);
        if (source == null || id == null) {
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("domain", domain);
        out.put("id", id);
        out.put("source", source);
        return out;
    }

    private static ObjectNode permit(JsonNode node, String path, List<Violation> errors) {
        if (!shape(node, path, Set.of("permitId", "pointId", "source"), errors)) {
            return null;
        }
        String permitId = textId(node.get("permitId"), path + ".permitId", true, errors);
        String pointId = textId(node.get("pointId"), path + ".pointId", true, errors);
        String source = textId(node.get("source"), path + ".source", true, errors);
        if (permitId == null || pointId == null || source == null) {
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("permitId", permitId);
        out.put("pointId", pointId);
        out.put("source", source);
        return out;
    }

    private static ObjectNode matrix(JsonNode node, String path, String region, List<Violation> errors) {
        if (!shape(node, path, Set.of("permission", "scope"), errors)) {
            return null;
        }
        String permission = node.path("permission").asText(null);
        String scope = node.path("scope").asText(null);
        if (permission == null || !CityBoardPermissions.ALL.contains(permission)) {
            errors.add(violation("PERMISSION_MATRIX", path + ".permission", "platform_rule"));
            return null;
        }
        String expected = region == null ? null : "region:" + region;
        if (scope == null || expected == null || !expected.equals(scope)) {
            errors.add(violation("PERMISSION_SCOPE", path + ".scope", "platform_rule"));
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("permission", permission);
        out.put("scope", scope);
        return out;
    }

    private static ObjectNode task(JsonNode node, String path, List<Violation> errors) {
        if (!shape(node, path, Set.of("pointId", "steps", "taskId", "title"), errors)) {
            return null;
        }
        String taskId = textId(node.get("taskId"), path + ".taskId", true, errors);
        String title = label(node.get("title"), path + ".title", 160, true, errors);
        String pointId = textId(node.get("pointId"), path + ".pointId", true, errors);
        ArrayNode steps = labels(node.get("steps"), path + ".steps", errors);
        if (taskId == null || title == null || pointId == null || steps == null) {
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("pointId", pointId);
        out.set("steps", steps);
        out.put("taskId", taskId);
        out.put("title", title);
        return out;
    }

    private static boolean shape(JsonNode node, String path, Set<String> allowed, List<Violation> errors) {
        if (node == null || !node.isObject()) {
            errors.add(violation("MISSING_PARAM", path, "platform_rule"));
            return false;
        }
        boolean ok = true;
        for (String key : unknownKeys(node, allowed)) {
            errors.add(violation("UNKNOWN_FIELD", path + "." + key, "platform_rule"));
            ok = false;
        }
        return ok;
    }

    private static ArrayNode integrals(JsonNode node, String path, int min, int max, boolean positive,
            List<Violation> errors) {
        if (node == null || !node.isArray() || node.size() < min || node.size() > max) {
            errors.add(violation("MISSING_PARAM", path, "platform_rule"));
            return null;
        }
        ArrayNode out = JSON.createArrayNode();
        for (int i = 0; i < node.size(); i++) {
            Long value = integral(node.get(i), path + "[" + i + "]", positive ? 1 : Long.MIN_VALUE, errors);
            if (value == null) {
                return null;
            }
            if (!positive && value < 0) {
                errors.add(violation("INVALID_FEE", path + "[" + i + "]", "platform_rule"));
                return null;
            }
            out.add(value);
        }
        return out;
    }

    private static ArrayNode labels(JsonNode node, String path, List<Violation> errors) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > 20) {
            errors.add(violation("TASK_NOT_COMPLETABLE", path, "platform_rule"));
            return null;
        }
        ArrayNode out = JSON.createArrayNode();
        for (int i = 0; i < node.size(); i++) {
            String text = label(node.get(i), path + "[" + i + "]", 160, true, errors);
            if (text == null) {
                return null;
            }
            out.add(text);
        }
        return out;
    }

    private static Long integral(JsonNode node, String path, long min, List<Violation> errors) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            errors.add(violation(min >= 0 ? "INVALID_FEE" : "MISSING_PARAM", path, "platform_rule"));
            return null;
        }
        long value = node.longValue();
        if (value < min) {
            errors.add(violation("INVALID_FEE", path, "platform_rule"));
            return null;
        }
        return value;
    }

    private static String currency(JsonNode node, String path, List<Violation> errors) {
        if (node == null || !node.isTextual() || !CURRENCY.matcher(node.asText()).matches()) {
            errors.add(violation("INVALID_FEE", path, "platform_rule"));
            return null;
        }
        return node.asText();
    }

    private static DecimalNode coordinate(JsonNode node, String path, double min, double max, List<Violation> errors) {
        if (node == null || !node.isNumber()) {
            errors.add(violation("MISSING_PARAM", path, "platform_rule"));
            return null;
        }
        String text = node.asText();
        if (!NUMBER.matcher(text).matches()) {
            errors.add(violation("MISSING_PARAM", path, "platform_rule"));
            return null;
        }
        BigDecimal decimal = new BigDecimal(text).stripTrailingZeros();
        double value = decimal.doubleValue();
        if (!Double.isFinite(value) || value < min || value > max) {
            errors.add(violation("MISSING_PARAM", path, "platform_rule"));
            return null;
        }
        return DecimalNode.valueOf(new BigDecimal(decimal.toPlainString()));
    }

    private static String textId(JsonNode node, String path, boolean required, List<Violation> errors) {
        if (node == null || node.isNull()) {
            if (required) {
                errors.add(violation("MISSING_PARAM", path, "platform_rule"));
            }
            return null;
        }
        if (!node.isTextual() || !ID.matcher(node.asText()).matches()) {
            errors.add(violation("INVALID_ID", path, "platform_rule"));
            return null;
        }
        return node.asText();
    }

    private static String label(JsonNode node, String path, int max, boolean required, List<Violation> errors) {
        if (node == null || node.isNull() || !node.isTextual()) {
            if (required) {
                errors.add(violation("MISSING_PARAM", path, "platform_rule"));
            }
            return null;
        }
        String value = node.asText();
        if (value.trim().isEmpty() || value.length() > max || hasControl(value)) {
            errors.add(violation("MISSING_PARAM", path, "platform_rule"));
            return null;
        }
        return value;
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x1F || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static void scanForbidden(JsonNode node, String path, List<Violation> errors) {
        if (node instanceof ObjectNode object) {
            Iterator<String> names = object.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                String kind = FORBIDDEN.get(name);
                if (kind != null) {
                    errors.add(violation(codeFor(kind), path + name, kind));
                } else {
                    scanForbidden(object.get(name), path + name + ".", errors);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                scanForbidden(array.get(i), path + "[" + i + "].", errors);
            }
        }
    }

    private static String codeFor(String kind) {
        return switch (kind) {
            case "player_strategy" -> "PLAYER_STRATEGY";
            case "merchant_operational_fact" -> "MERCHANT_FACT";
            case "protected" -> "PROTECTED_FIELD";
            default -> "UNDECLARED_RATE";
        };
    }

    private static List<String> unknownKeys(JsonNode node, Set<String> allowed) {
        List<String> unknown = new ArrayList<String>();
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        });
        return unknown;
    }

    private static Map<String, String> forbidden() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("playerStrategy", "player_strategy");
        values.put("strategy", "player_strategy");
        values.put("bid", "player_strategy");
        values.put("routeChoice", "player_strategy");
        values.put("join", "player_strategy");
        values.put("capture", "player_strategy");
        values.put("reward", "player_strategy");
        values.put("wallet", "player_strategy");
        values.put("hours", "merchant_operational_fact");
        values.put("capacity", "merchant_operational_fact");
        values.put("landRight", "merchant_operational_fact");
        values.put("ownership", "merchant_operational_fact");
        values.put("merchantName", "merchant_operational_fact");
        values.put("probability", "platform_rule");
        values.put("feeRate", "platform_rule");
        values.put("threshold", "platform_rule");
        values.put("owner", "protected");
        values.put("role", "protected");
        values.put("expectedVersion", "protected");
        values.put("requestId", "protected");
        values.put("approvalHash", "protected");
        values.put("contentHash", "protected");
        values.put("rulesReleaseId", "protected");
        values.put("lifecycle", "protected");
        values.put("revision", "protected");
        return Map.copyOf(values);
    }

    private static Violation violation(String code, String path, String fieldClass) {
        return new Violation(code, path, fieldClass);
    }

    private static CityBoardDraftDocument failed(List<Violation> errors) {
        return new CityBoardDraftDocument(null, null, null, List.copyOf(errors));
    }

    public static String canonicalTree(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        return write(sort(node));
    }

    private static JsonNode sort(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<String>();
            object.fieldNames().forEachRemaining(names::add);
            java.util.Collections.sort(names);
            for (String name : names) {
                sorted.set(name, sort(object.get(name)));
            }
            return sorted;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = JSON.createArrayNode();
            array.forEach(child -> copy.add(sort(child)));
            return copy;
        }
        return node;
    }

    private static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("canonical json");
        }
    }
}
