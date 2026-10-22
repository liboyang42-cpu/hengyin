package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * F5 故事引擎新增词汇(条件组合 + 新条件 + 新效果)的唯一名词表与形状校验。
 *
 * <p>只收本轮新增的 op;存量 op(EQ…LTE / HAS_TAG / NODE_COMPLETED / ITEM_* / SET / INC / ADD_TAG / FLOOR)
 * 仍由各校验器自己的旧逻辑判,行为与报错文案一字不变。各校验器在旧 op 判完、报「不支持」之前
 * 先问这里,命中就按这里的形状校验 —— 新增 op 只改这一处,不再往 6 份旧名词表里各抄一遍。
 *
 * <p>组合条件(AND / OR / NOT)的子条件回调调用方自己的单条校验,所以叶子仍受各自命名空间约束。
 */
public final class StoryVocabulary {
    public static final String AND = "AND";
    public static final String OR = "OR";
    public static final String NOT = "NOT";
    public static final String VISIT_COUNT = "VISIT_COUNT";
    public static final String ROLE_IN = "ROLE_IN";
    public static final String HAS_CONDITION = "HAS_CONDITION";
    public static final String ITEM_EQUIPPED = "ITEM_EQUIPPED";
    public static final String CHECK_RESULT = "CHECK_RESULT";

    public static final String ADD_ITEM = "ADD_ITEM";
    public static final String GRANT_BADGE = "GRANT_BADGE";
    public static final String GRANT_XP = "GRANT_XP";
    public static final String ADD_CONDITION = "ADD_CONDITION";
    public static final String REMOVE_CONDITION = "REMOVE_CONDITION";
    public static final String EQUIP = "EQUIP";
    public static final String UNEQUIP = "UNEQUIP";
    public static final String TICK_CLOCK = "TICK_CLOCK";

    public static final Set<String> CONDITION_OPS = set(AND, OR, NOT, VISIT_COUNT, ROLE_IN, HAS_CONDITION,
            ITEM_EQUIPPED, CHECK_RESULT);
    public static final Set<String> EFFECT_OPS = set(ADD_ITEM, GRANT_BADGE, GRANT_XP, ADD_CONDITION,
            REMOVE_CONDITION, EQUIP, UNEQUIP, TICK_CLOCK);

    /** 组合最多嵌 3 层(顶层数组不算),防止作者写出读不懂的树,也给运行期递归封顶。 */
    public static final int MAX_CONDITION_DEPTH = 3;
    public static final int MAX_CHILDREN = 10;
    public static final int MAX_VISIT_COUNT = 99;
    public static final int MAX_ITEM_QUANTITY = 99;
    /** 单条 GRANT_XP 上限;XP 只是成长值,不是积分 / 券 / 钱。 */
    public static final int MAX_GRANT_XP = 100;
    public static final int MIN_CLOCK_SEGMENTS = 2;
    public static final int MAX_CLOCK_SEGMENTS = 12;

    /** 检定回执落进本局的事实键(与 TopicRouteRuntimeServiceImpl 结算写入同一前缀)。 */
    public static final String SYS_PASSED_PREFIX = "sys.passed.";
    public static final String CHECK_PASS = "PASS";
    public static final String CHECK_FAIL = "FAIL";

    private static final Set<String> COMPARE = set("EQ", "NE", "GT", "GTE", "LT", "LTE");
    private static final Pattern ITEM_ID = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final Pattern STATUS = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final Pattern BADGE = Pattern.compile("^[a-z][a-z0-9_]{0,47}$");
    private static final Pattern ROLE = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");
    private static final Pattern GRANT_ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    private static final Pattern CLOCK_VAR = Pattern.compile("^counter\\.[a-z][a-z0-9_]{0,47}$");

    private StoryVocabulary() {}

    public static boolean isCondition(String op) { return CONDITION_OPS.contains(op); }

    public static boolean isEffect(String op) { return EFFECT_OPS.contains(op); }

    /**
     * 校验一条新词汇条件。
     *
     * @param nodeIds 本主题节点集合;null = 该侧不核对节点归属
     * @param child   子条件回调(path, condition):AND / OR / NOT 的每个子项交回调用方按其完整规则校验
     */
    public static void validateCondition(JsonNode condition, String path, Set<Long> nodeIds,
                                         BiConsumer<String, JsonNode> child) {
        validateCondition(condition, path, nodeIds, child, 0);
    }

    private static void validateCondition(JsonNode condition, String path, Set<Long> nodeIds,
                                          BiConsumer<String, JsonNode> child, int depth) {
        String op = condition.path("op").asText("");
        switch (op) {
            case AND:
            case OR: {
                fields(condition, path, "op", "conditions");
                if (depth >= MAX_CONDITION_DEPTH) throw fail(path, "条件组合最多嵌套 " + MAX_CONDITION_DEPTH + " 层");
                JsonNode children = condition.get("conditions");
                if (children == null || !children.isArray() || children.size() < 1 || children.size() > MAX_CHILDREN) {
                    throw fail(path, op + " 的 conditions 须为 1 至 " + MAX_CHILDREN + " 条的数组");
                }
                for (int i = 0; i < children.size(); i++) {
                    nested(children.get(i), path + ".conditions[" + i + "]", nodeIds, child, depth + 1);
                }
                return;
            }
            case NOT: {
                fields(condition, path, "op", "condition");
                if (depth >= MAX_CONDITION_DEPTH) throw fail(path, "条件组合最多嵌套 " + MAX_CONDITION_DEPTH + " 层");
                nested(condition.get("condition"), path + ".condition", nodeIds, child, depth + 1);
                return;
            }
            case VISIT_COUNT:
                fields(condition, path, "op", "nodeId", "cmp", "value");
                node(condition, path, nodeIds);
                if (condition.has("cmp") && !COMPARE.contains(condition.path("cmp").asText())) {
                    throw fail(path, "cmp 只能是 EQ、NE、GT、GTE、LT、LTE");
                }
                integer(condition, "value", 0, MAX_VISIT_COUNT, path);
                return;
            case ROLE_IN: {
                fields(condition, path, "op", "values");
                JsonNode values = condition.get("values");
                if (values == null || !values.isArray() || values.size() < 1 || values.size() > MAX_CHILDREN) {
                    throw fail(path, "ROLE_IN 的 values 须为 1 至 " + MAX_CHILDREN + " 个角色代码");
                }
                Set<String> seen = new LinkedHashSet<String>();
                for (JsonNode value : values) {
                    if (!value.isTextual() || !ROLE.matcher(value.asText()).matches() || !seen.add(value.asText())) {
                        throw fail(path, "ROLE_IN 的角色代码须为不重复的大写代码");
                    }
                }
                return;
            }
            case HAS_CONDITION:
                fields(condition, path, "op", "value");
                text(condition, "value", STATUS, path);
                return;
            case ITEM_EQUIPPED:
                fields(condition, path, "op", "itemId");
                text(condition, "itemId", ITEM_ID, path);
                return;
            case CHECK_RESULT:
                fields(condition, path, "op", "nodeId", "result");
                node(condition, path, nodeIds);
                String result = condition.path("result").asText("");
                if (!CHECK_PASS.equals(result) && !CHECK_FAIL.equals(result)) {
                    throw fail(path, "CHECK_RESULT 的 result 只能是 PASS 或 FAIL");
                }
                return;
            default:
                throw fail(path, "op 不支持:" + op);
        }
    }

    private static void nested(JsonNode item, String path, Set<Long> nodeIds, BiConsumer<String, JsonNode> child,
                               int depth) {
        if (item == null || !item.isObject()) throw fail(path, "必须是条件对象");
        String op = item.path("op").asText("");
        if (AND.equals(op) || OR.equals(op) || NOT.equals(op)) validateCondition(item, path, nodeIds, child, depth);
        else child.accept(path, item);
    }

    /** 校验一条新词汇效果。不带任何金额 / 积分 / 券字段:多余字段一律拒。 */
    public static void validateEffect(JsonNode effect, String path) {
        String op = effect.path("op").asText("");
        switch (op) {
            case ADD_ITEM:
                fields(effect, path, "op", "itemId", "quantity");
                text(effect, "itemId", ITEM_ID, path);
                integer(effect, "quantity", 1, MAX_ITEM_QUANTITY, path);
                return;
            case GRANT_BADGE:
                fields(effect, path, "op", "value");
                text(effect, "value", BADGE, path);
                return;
            case GRANT_XP:
                fields(effect, path, "op", "value", "id");
                integer(effect, "value", 1, MAX_GRANT_XP, path);
                if (effect.has("id")) text(effect, "id", GRANT_ID, path);
                return;
            case ADD_CONDITION:
            case REMOVE_CONDITION:
                fields(effect, path, "op", "value");
                text(effect, "value", STATUS, path);
                return;
            case EQUIP:
                fields(effect, path, "op", "itemId", "slot");
                text(effect, "itemId", ITEM_ID, path);
                if (effect.has("slot")) text(effect, "slot", STATUS, path);
                return;
            case UNEQUIP:
                fields(effect, path, "op", "itemId");
                text(effect, "itemId", ITEM_ID, path);
                return;
            case TICK_CLOCK:
                fields(effect, path, "op", "var", "value", "max");
                text(effect, "var", CLOCK_VAR, path);
                integer(effect, "max", MIN_CLOCK_SEGMENTS, MAX_CLOCK_SEGMENTS, path);
                integer(effect, "value", 1, effect.path("max").asInt(), path);
                return;
            default:
                throw fail(path, "op 不支持:" + op);
        }
    }

    private static void node(JsonNode row, String path, Set<Long> nodeIds) {
        JsonNode nodeId = row.get("nodeId");
        if (nodeId == null || !nodeId.isIntegralNumber() || !nodeId.canConvertToLong() || nodeId.asLong() <= 0L) {
            throw fail(path, "nodeId 必须是正整数");
        }
        if (nodeIds != null && !nodeIds.contains(nodeId.asLong())) {
            throw fail(path, "nodeId 引用的节点不属于本主题:nodeId=" + nodeId.asLong());
        }
    }

    private static void integer(JsonNode row, String field, int min, int max, String path) {
        JsonNode value = row.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.asInt() < min || value.asInt() > max) {
            throw fail(path, field + " 须为 " + min + " 至 " + max + " 的整数");
        }
    }

    private static void text(JsonNode row, String field, Pattern pattern, String path) {
        JsonNode value = row.get(field);
        if (value == null || !value.isTextual() || !pattern.matcher(value.asText()).matches()) {
            throw fail(path, field + " 格式不正确");
        }
    }

    private static void fields(JsonNode row, String path, String... allowed) {
        Set<String> names = set(allowed);
        row.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) throw fail(path, "不支持的字段:" + name);
        });
    }

    private static IllegalArgumentException fail(String path, String message) {
        return new IllegalArgumentException(path + " " + message);
    }

    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }
}
