package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * F6 RPG P1:主题级灰度开关 {@code journeyRules.rpg} 的唯一校验 / 读取 / 视图入口。
 *
 * <p>形状(全部可选,没配 = 关;老主题规范化结果一字不变):
 * <pre>
 * rpg: {
 *   environment: {enabled, keys:[weather|daypart|weekpart|season]},   T06 环境键(ENV_IN 读)
 *   clocks:      [{var:counter.*, label, segments 2..12, kind danger|progress, visible}],  T08 进度钟
 *   bargain:     {enabled},                                            T09 魔鬼交易(check.bargain 才生效)
 *   momentum:    {enabled, max, init, gainOnFail, gainOnSuccess, maxSpend},  T10 动量(sys.momentum)
 *   aspects:     [{key, label, kind aspect|facet, desc, atStart}]      T11 特质 / 侧面(atStart = 开局自带)
 * }
 * </pre>
 *
 * <p>运行期只写本局 state(与检定 / 推进同一次 CAS),不查库、不碰资金 / 积分 / 券。
 * 进度钟沿用 F5 的 TICK_CLOCK 推进、比较条件读取;特质沿用 F5 的 ADD_CONDITION / REMOVE_CONDITION /
 * HAS_CONDITION —— 这里只补定义(名字、种类、段数),不再造第二套条件 / 效果。
 */
public final class JourneyRpg {
    public static final String SYS_MOMENTUM = "sys.momentum";

    public static final String ENV_WEATHER = "weather";
    public static final String ENV_DAYPART = "daypart";
    public static final String ENV_WEEKPART = "weekpart";
    public static final String ENV_SEASON = "season";
    public static final List<String> ENV_KEYS = Collections.unmodifiableList(
            Arrays.asList(ENV_WEATHER, ENV_DAYPART, ENV_WEEKPART, ENV_SEASON));
    /** 环境未知(未开启 / 天气源失败 / 未配城市)时的统一取值:fail-open,玩法照常,条件按「未知」判。 */
    public static final String ENV_UNKNOWN = "unknown";

    public static final String KIND_ASPECT = "aspect";
    public static final String KIND_FACET = "facet";
    public static final String CLOCK_DANGER = "danger";
    public static final String CLOCK_PROGRESS = "progress";

    public static final int MAX_CLOCKS = 6;
    public static final int MAX_ASPECTS = 12;
    public static final int MAX_LABEL = 20;
    public static final int MAX_DESC = 60;
    public static final int MAX_MOMENTUM = 10;
    public static final int MAX_MOMENTUM_GAIN = 3;

    private static final Pattern CLOCK_VAR = Pattern.compile("^counter\\.[a-z][a-z0-9_]{0,47}$");
    /** 与 StoryVocabulary 的状态名同形:特质就是 statusConditions 里的一个值。 */
    private static final Pattern ASPECT_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final Set<String> RPG_FIELDS = set("environment", "clocks", "bargain", "momentum", "aspects");
    private static final ObjectMapper JSON = new ObjectMapper();

    private JourneyRpg() {}

    // ==================== 校验 / 规范化 ====================

    /**
     * 规范化 {@code journeyRules.rpg}。缺省 / null 返回 null(不输出字段);形状不对抛中文人话。
     *
     * @param checkEnabled 主题是否启用检定:魔鬼交易与动量只挂在检定上,没检定就不许开
     * @param stateEnabled 主题是否启用局状态
     */
    public static ObjectNode normalize(JsonNode source, boolean checkEnabled, boolean stateEnabled) {
        if (source == null || source.isNull() || source.isMissingNode()) return null;
        if (!source.isObject()) throw new IllegalArgumentException("rpg 必须是对象");
        source.fieldNames().forEachRemaining(name -> {
            if (!RPG_FIELDS.contains(name)) throw new IllegalArgumentException("rpg 不支持的字段:" + name);
        });
        if (!checkEnabled && !stateEnabled) {
            throw new IllegalArgumentException("rpg 需要先启用 checkEnabled 或 stateEnabled");
        }
        ObjectNode out = JSON.createObjectNode();
        ObjectNode environment = normalizeEnvironment(source.get("environment"));
        if (environment != null) out.set("environment", environment);
        ArrayNode clocks = normalizeClocks(source.get("clocks"));
        if (clocks != null) out.set("clocks", clocks);
        JsonNode bargain = source.get("bargain");
        if (bargain != null && !bargain.isNull()) {
            if (!bargain.isObject()) throw new IllegalArgumentException("rpg.bargain 必须是对象");
            only(bargain, "rpg.bargain", "enabled");
            boolean on = bool(bargain, "enabled", "rpg.bargain.enabled");
            if (on && !checkEnabled) throw new IllegalArgumentException("rpg.bargain 需要先启用检定 checkEnabled");
            out.putObject("bargain").put("enabled", on);
        }
        ObjectNode momentum = normalizeMomentum(source.get("momentum"), checkEnabled);
        if (momentum != null) out.set("momentum", momentum);
        ArrayNode aspects = normalizeAspects(source.get("aspects"));
        if (aspects != null) out.set("aspects", aspects);
        return out;
    }

    private static ObjectNode normalizeEnvironment(JsonNode source) {
        if (source == null || source.isNull()) return null;
        if (!source.isObject()) throw new IllegalArgumentException("rpg.environment 必须是对象");
        only(source, "rpg.environment", "enabled", "keys");
        ObjectNode out = JSON.createObjectNode();
        out.put("enabled", bool(source, "enabled", "rpg.environment.enabled"));
        ArrayNode keys = out.putArray("keys");
        JsonNode configured = source.get("keys");
        if (configured == null || configured.isNull()) {
            for (String key : ENV_KEYS) keys.add(key);
            return out;
        }
        if (!configured.isArray() || configured.isEmpty()) {
            throw new IllegalArgumentException("rpg.environment.keys 必须是非空数组");
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (JsonNode key : configured) {
            if (!key.isTextual() || !ENV_KEYS.contains(key.asText()) || !seen.add(key.asText())) {
                throw new IllegalArgumentException("rpg.environment.keys 只能是不重复的 weather、daypart、weekpart、season");
            }
        }
        for (String key : ENV_KEYS) if (seen.contains(key)) keys.add(key);
        return out;
    }

    private static ArrayNode normalizeClocks(JsonNode source) {
        if (source == null || source.isNull()) return null;
        if (!source.isArray()) throw new IllegalArgumentException("rpg.clocks 必须是数组");
        if (source.isEmpty()) return null;
        if (source.size() > MAX_CLOCKS) throw new IllegalArgumentException("rpg.clocks 最多 " + MAX_CLOCKS + " 个");
        ArrayNode out = JSON.createArrayNode();
        Set<String> seen = new LinkedHashSet<String>();
        for (JsonNode row : source) {
            if (!row.isObject()) throw new IllegalArgumentException("rpg.clocks 条目必须是对象");
            only(row, "rpg.clocks", "var", "label", "segments", "kind", "visible");
            String var = row.path("var").asText("");
            if (!CLOCK_VAR.matcher(var).matches()) {
                throw new IllegalArgumentException("rpg.clocks 的 var 必须是 counter. 开头的状态键:" + var);
            }
            if (!seen.add(var)) throw new IllegalArgumentException("rpg.clocks 的 var 重复:" + var);
            int segments = integer(row, "segments", "rpg.clocks.segments",
                    StoryVocabulary.MIN_CLOCK_SEGMENTS, StoryVocabulary.MAX_CLOCK_SEGMENTS);
            String kind = row.path("kind").asText(CLOCK_PROGRESS);
            if (!CLOCK_DANGER.equals(kind) && !CLOCK_PROGRESS.equals(kind)) {
                throw new IllegalArgumentException("rpg.clocks 的 kind 只能是 danger 或 progress");
            }
            ObjectNode item = out.addObject();
            item.put("var", var);
            item.put("label", label(row, "rpg.clocks"));
            item.put("segments", segments);
            item.put("kind", kind);
            item.put("visible", row.has("visible") ? bool(row, "visible", "rpg.clocks.visible") : true);
        }
        return out;
    }

    private static ObjectNode normalizeMomentum(JsonNode source, boolean checkEnabled) {
        if (source == null || source.isNull()) return null;
        if (!source.isObject()) throw new IllegalArgumentException("rpg.momentum 必须是对象");
        only(source, "rpg.momentum", "enabled", "max", "init", "gainOnFail", "gainOnSuccess", "maxSpend");
        boolean on = bool(source, "enabled", "rpg.momentum.enabled");
        if (on && !checkEnabled) throw new IllegalArgumentException("rpg.momentum 需要先启用检定 checkEnabled");
        int max = optionalInt(source, "max", "rpg.momentum.max", 1, MAX_MOMENTUM, 6);
        int init = optionalInt(source, "init", "rpg.momentum.init", 0, max, Math.min(2, max));
        int gainOnFail = optionalInt(source, "gainOnFail", "rpg.momentum.gainOnFail", 0, MAX_MOMENTUM_GAIN, 1);
        int gainOnSuccess = optionalInt(source, "gainOnSuccess", "rpg.momentum.gainOnSuccess", 0, MAX_MOMENTUM_GAIN, 0);
        int maxSpend = optionalInt(source, "maxSpend", "rpg.momentum.maxSpend", 1, max, Math.min(3, max));
        ObjectNode out = JSON.createObjectNode();
        out.put("enabled", on);
        out.put("max", max);
        out.put("init", init);
        out.put("gainOnFail", gainOnFail);
        out.put("gainOnSuccess", gainOnSuccess);
        out.put("maxSpend", maxSpend);
        return out;
    }

    private static ArrayNode normalizeAspects(JsonNode source) {
        if (source == null || source.isNull()) return null;
        if (!source.isArray()) throw new IllegalArgumentException("rpg.aspects 必须是数组");
        if (source.isEmpty()) return null;
        if (source.size() > MAX_ASPECTS) throw new IllegalArgumentException("rpg.aspects 最多 " + MAX_ASPECTS + " 个");
        ArrayNode out = JSON.createArrayNode();
        Set<String> seen = new LinkedHashSet<String>();
        for (JsonNode row : source) {
            if (!row.isObject()) throw new IllegalArgumentException("rpg.aspects 条目必须是对象");
            only(row, "rpg.aspects", "key", "label", "kind", "desc", "atStart");
            String key = row.path("key").asText("");
            if (!ASPECT_KEY.matcher(key).matches()) throw new IllegalArgumentException("rpg.aspects 的 key 不合法:" + key);
            if (!seen.add(key)) throw new IllegalArgumentException("rpg.aspects 的 key 重复:" + key);
            String kind = row.path("kind").asText(KIND_ASPECT);
            if (!KIND_ASPECT.equals(kind) && !KIND_FACET.equals(kind)) {
                throw new IllegalArgumentException("rpg.aspects 的 kind 只能是 aspect 或 facet");
            }
            String desc = row.path("desc").asText("").trim();
            if (desc.length() > MAX_DESC) throw new IllegalArgumentException("rpg.aspects 的 desc 不能超过 " + MAX_DESC + " 字");
            ObjectNode item = out.addObject();
            item.put("key", key);
            item.put("label", label(row, "rpg.aspects"));
            item.put("kind", kind);
            if (!desc.isEmpty()) item.put("desc", desc);
            // 文档二 A7「开局自带」:只在打开时输出,老主题规范化结果一字不变。
            if (row.has("atStart") && bool(row, "atStart", "rpg.aspects.atStart")) item.put("atStart", true);
        }
        return out;
    }

    // ==================== 读取 ====================

    public static boolean environmentEnabled(JourneyRules rules) {
        return rules != null && rules.getRpg() != null && rules.getRpg().path("environment").path("enabled").asBoolean(false);
    }

    public static List<String> environmentKeys(JourneyRules rules) {
        List<String> keys = new ArrayList<String>();
        if (!environmentEnabled(rules)) return keys;
        for (JsonNode key : rules.getRpg().path("environment").path("keys")) keys.add(key.asText());
        return keys;
    }

    public static boolean bargainEnabled(JourneyRules rules) {
        return rules != null && rules.isCheckEnabled() && rules.getRpg() != null
                && rules.getRpg().path("bargain").path("enabled").asBoolean(false);
    }

    public static boolean momentumEnabled(JourneyRules rules) {
        return rules != null && rules.isCheckEnabled() && rules.getRpg() != null
                && rules.getRpg().path("momentum").path("enabled").asBoolean(false);
    }

    public static JsonNode momentum(JourneyRules rules) {
        return rules == null || rules.getRpg() == null ? JSON.createObjectNode() : rules.getRpg().path("momentum");
    }

    public static int momentumOf(TopicRouteEngine.State state) {
        Number value = state.getVariables().get(SYS_MOMENTUM);
        return value == null || !Double.isFinite(value.doubleValue()) ? 0 : Math.max(0, value.intValue());
    }

    /** 按规则封顶写回动量;未启用时什么都不做。返回写入后的值。 */
    public static int setMomentum(JourneyRules rules, TopicRouteEngine.State state, int value) {
        if (!momentumEnabled(rules)) return momentumOf(state);
        int bounded = Math.max(0, Math.min(momentum(rules).path("max").asInt(MAX_MOMENTUM), value));
        state.getVariables().put(SYS_MOMENTUM, Integer.valueOf(bounded));
        return bounded;
    }

    // ==================== 运行期 ====================

    /**
     * 建新一局:动量写初值、侧面(facet)作为常驻特质挂上。主题没开对应开关 = 什么都不做,
     * 老主题新局的 state_json 字段集合不变。
     */
    public static void seedRun(JourneyRules rules, TopicRouteEngine.State state) {
        if (rules == null || rules.getRpg() == null || state == null) return;
        if (momentumEnabled(rules)) {
            state.getVariables().put(SYS_MOMENTUM, Integer.valueOf(momentum(rules).path("init").asInt(0)));
        }
        for (JsonNode aspect : rules.getRpg().path("aspects")) {
            if (KIND_FACET.equals(aspect.path("kind").asText()) || startsWith(aspect)) {
                state.getStatusConditions().add(aspect.path("key").asText());
            }
        }
    }

    /**
     * 状态栏 / 角色卡共用的 P1 视图。只读;未启用的块不出现。可见性:visible=false 的钟不下发。
     */
    public static Map<String, Object> view(JourneyRules rules, TopicRouteEngine.State state) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        if (rules == null || rules.getRpg() == null) {
            view.put("enabled", Boolean.FALSE);
            return view;
        }
        view.put("enabled", Boolean.TRUE);
        JsonNode rpg = rules.getRpg();
        if (momentumEnabled(rules)) {
            Map<String, Object> momentum = new LinkedHashMap<String, Object>();
            momentum.put("value", Integer.valueOf(state == null ? momentum(rules).path("init").asInt(0) : momentumOf(state)));
            momentum.put("max", Integer.valueOf(momentum(rules).path("max").asInt()));
            momentum.put("maxSpend", Integer.valueOf(momentum(rules).path("maxSpend").asInt()));
            view.put("momentum", momentum);
        }
        if (rpg.path("clocks").isArray()) {
            List<Map<String, Object>> clocks = new ArrayList<Map<String, Object>>();
            for (JsonNode clock : rpg.path("clocks")) {
                if (!clock.path("visible").asBoolean(true)) continue;
                int segments = clock.path("segments").asInt();
                Number raw = state == null ? null : state.getVariables().get(clock.path("var").asText());
                int value = raw == null || !Double.isFinite(raw.doubleValue()) ? 0
                        : Math.max(0, Math.min(segments, raw.intValue()));
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("var", clock.path("var").asText());
                row.put("label", clock.path("label").asText());
                row.put("kind", clock.path("kind").asText());
                row.put("value", Integer.valueOf(value));
                row.put("segments", Integer.valueOf(segments));
                row.put("filled", Boolean.valueOf(value >= segments));
                clocks.add(row);
            }
            view.put("clocks", clocks);
        }
        if (rpg.path("aspects").isArray()) {
            List<Map<String, Object>> aspects = new ArrayList<Map<String, Object>>();
            List<Map<String, Object>> facets = new ArrayList<Map<String, Object>>();
            for (JsonNode aspect : rpg.path("aspects")) {
                boolean facet = KIND_FACET.equals(aspect.path("kind").asText());
                String key = aspect.path("key").asText();
                // 侧面是常驻的,没建局时也展示;特质只在本局真的挂着时展示。
                boolean active = state == null ? facet || startsWith(aspect) : state.getStatusConditions().contains(key);
                if (!active) continue;
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("key", key);
                row.put("label", aspect.path("label").asText());
                if (aspect.hasNonNull("desc")) row.put("desc", aspect.path("desc").asText());
                (facet ? facets : aspects).add(row);
            }
            view.put("facets", facets);
            view.put("aspects", aspects);
        }
        if (environmentEnabled(rules)) {
            Map<String, Object> environment = new LinkedHashMap<String, Object>();
            for (String key : environmentKeys(rules)) {
                String value = state == null ? null : state.getEnvironment().get(key);
                environment.put(key, value == null ? ENV_UNKNOWN : value);
            }
            view.put("environment", environment);
        }
        if (bargainEnabled(rules)) view.put("bargain", Boolean.TRUE);
        return view;
    }

    /** 开局自带的特质(A7):建局时挂上,之后可被 REMOVE_CONDITION 移除。 */
    public static boolean startsWith(JsonNode aspect) {
        return aspect != null && aspect.path("atStart").asBoolean(false);
    }

    // ==================== 小工具 ====================

    private static void only(JsonNode row, String path, String... allowed) {
        Set<String> names = set(allowed);
        row.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) throw new IllegalArgumentException(path + " 不支持的字段:" + name);
        });
    }

    private static boolean bool(JsonNode row, String field, String path) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return false;
        if (!value.isBoolean()) throw new IllegalArgumentException(path + " 必须是布尔值");
        return value.asBoolean();
    }

    private static int integer(JsonNode row, String field, String path, int min, int max) {
        JsonNode value = row.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.asInt() < min || value.asInt() > max) {
            throw new IllegalArgumentException(path + " 须为 " + min + " 至 " + max + " 的整数");
        }
        return value.asInt();
    }

    private static int optionalInt(JsonNode row, String field, String path, int min, int max, int fallback) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return Math.max(min, Math.min(max, fallback));
        return integer(row, field, path, min, max);
    }

    private static String label(JsonNode row, String path) {
        String label = row.path("label").asText("").trim();
        if (label.isEmpty() || label.length() > MAX_LABEL) {
            throw new IllegalArgumentException(path + " 的 label 必填,且不超过 " + MAX_LABEL + " 字");
        }
        return label;
    }

    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }
}
