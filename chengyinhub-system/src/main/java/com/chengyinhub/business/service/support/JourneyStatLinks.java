package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 同所有者的可信动作联动；只变更待提交 State，持久化与回滚由原事务负责。 */
public final class JourneyStatLinks {
    private static final ObjectMapper JSON = new ObjectMapper();
    public enum Reason { TASK_COST, TEMPLATE_EFFECT, CHAPTER_COST, ITEM_USE, FACILITY_EFFECT }
    private JourneyStatLinks() { }

    public static ArrayNode normalize(JsonNode source, JourneyRules rules) {
        ArrayNode out = JSON.createArrayNode();
        if (source == null) return out;
        if (!source.isArray() || source.size() > 32) fail("statLinks 最多配置32条规则");
        Set<String> ids = new HashSet<>();
        for (JsonNode row : source) {
            if (!row.isObject()) fail("statLinks 规则必须是对象");
            Set<String> allowed = ThemeRuntimeConfig.set("id", "sourceKey", "targetKey", "trigger", "allowedReasons", "frequency", "amount", "multiplier", "threshold");
            row.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) fail("statLinks 不支持字段：" + key); });
            String id = text(row, "id");
            if (!id.matches("[a-z][a-z0-9_]{0,31}") || !ids.add(id)) fail("statLinks 规则ID无效或重复");
            JsonNode attribute = JourneyInitialState.numericAttribute(rules, text(row, "sourceKey"));
            if (!"sys.hp".equals(text(row, "targetKey")) || !rules.hasRuntimeState() || !rules.getHp().isEnabled()) fail("statLinks 目标只支持已启用HP");
            String trigger = text(row, "trigger"), frequency = text(row, "frequency");
            if (!ThemeRuntimeConfig.set("PER_POINT", "PER_EVENT", "FIRST_THRESHOLD").contains(trigger)) fail("statLinks 触发类型不支持");
            if (!ThemeRuntimeConfig.set("EVENT", "RUN").contains(frequency)) fail("statLinks 次数范围不支持");
            if ("PER_POINT".equals(trigger)) {
                integer(row, "multiplier", 1, 999);
                if (row.has("amount")) fail("按下降点数规则不能配置固定amount");
            } else {
                integer(row, "amount", 1, 999);
                if (row.has("multiplier")) fail("固定扣减规则不能配置multiplier");
            }
            if ("FIRST_THRESHOLD".equals(trigger)) {
                if (!"RUN".equals(frequency)) fail("首次阈值必须是本局一次");
                integer(row, "threshold", attribute.path("min").asInt(), attribute.path("max").asInt());
            } else if (row.has("threshold")) fail("只有首次阈值可配置threshold");
            JsonNode reasons = row.path("allowedReasons");
            if (!reasons.isArray() || reasons.size() == 0 || reasons.size() > Reason.values().length) fail("statLinks 必须明确可信来源");
            Set<String> seen = new HashSet<>();
            for (JsonNode reason : reasons) {
                if (!reason.isTextual() || !seen.add(reason.asText())) fail("statLinks 来源非法或重复");
                try { Reason.valueOf(reason.asText()); } catch (IllegalArgumentException invalid) { fail("statLinks 来源不支持：" + reason.asText()); }
            }
            out.add(row.deepCopy());
        }
        return out;
    }

    public static ArrayNode apply(JourneyRules rules, TopicRouteEngine.State before, TopicRouteEngine.State after,
                                  String ownerKey, String eventId, Reason reason) {
        return apply(rules, before, after, ownerKey, eventId, Collections.singleton(reason));
    }

    public static ArrayNode apply(JourneyRules rules, TopicRouteEngine.State before, TopicRouteEngine.State after,
                                  String ownerKey, String eventId, Set<Reason> reasons) {
        Map<String,Set<Reason>> byStat = new LinkedHashMap<>();
        for (JsonNode link : rules.getStatLinks()) byStat.put(link.path("sourceKey").asText(), reasons);
        return apply(rules, before, after, ownerKey, eventId, byStat);
    }

    public static ArrayNode apply(JourneyRules rules, TopicRouteEngine.State before, TopicRouteEngine.State after,
                                  String ownerKey, String eventId, Map<String,Set<Reason>> reasonsByStat) {
        ArrayNode audit = JSON.createArrayNode();
        if (rules.getStatLinks().isEmpty()) return audit;
        if (before == null || after == null || before == after || reasonsByStat == null
                || ownerKey == null || !ownerKey.matches("(MEMBER|TEAM|WORLD):[1-9][0-9]{0,18}")
                || eventId == null || eventId.trim().isEmpty() || eventId.length() > 160) fail("联动需要同所有者的动作前后状态与可信事件");
        // 验证全部规则和来源状态后再变更，坏规则不能留下半笔HP或去重标记。
        ArrayNode links = normalize(rules.getStatLinks(), rules);
        ObjectNode eventReasons = JSON.createObjectNode();
        for (Map.Entry<String,Set<Reason>> entry : new TreeMap<>(reasonsByStat).entrySet()) {
            JourneyInitialState.numericAttribute(rules, entry.getKey());
            if (entry.getValue() == null || entry.getValue().isEmpty() || entry.getValue().contains(null)) fail("联动原因必须为非空可信来源");
            ArrayNode values = eventReasons.putArray(entry.getKey());
            for (Reason reason : Reason.values()) if (entry.getValue().contains(reason)) values.add(reason.name());
        }
        for (TopicRouteEngine.State state : new TopicRouteEngine.State[]{before, after}) {
            String boundOwner = state.getThemeRuntime().path("statLinks").path("owner").asText(ownerKey);
            if (!ownerKey.equals(boundOwner)) fail("联动不得跨所有者");
        }
        ObjectNode ledger = after.getThemeRuntime().path("statLinks").isObject()
                ? after.getThemeRuntime().path("statLinks").deepCopy() : JSON.createObjectNode();
        if (ledger.path("events").has(eventId)) {
            if (!eventReasons.equals(ledger.path("events").path(eventId))) fail("联动事件ID不能更换来源");
            return audit;
        }
        if (ledger.path("events").size() >= 10000) fail("本局联动事件已达容量上限");
        int hp = JourneyInitialState.numericValue(after, "sys.hp");
        if (hp < 0 || hp > rules.getHp().getMax()) fail("联动HP必须先完成上下限收口");
        for (JsonNode link : links) {
            String key = link.path("sourceKey").asText(), id = link.path("id").asText();
            JsonNode attribute = JourneyInitialState.numericAttribute(rules, key);
            int previous = JourneyInitialState.numericValue(before, key), current = JourneyInitialState.numericValue(after, key);
            if (previous < attribute.path("min").asInt() || previous > attribute.path("max").asInt()
                    || current < attribute.path("min").asInt() || current > attribute.path("max").asInt()) fail("联动来源必须先完成上下限收口：" + key);
            Set<Reason> reasons = reasonsByStat.getOrDefault(key, Collections.emptySet());
            boolean permitted = false;
            for (JsonNode allowed : link.path("allowedReasons")) if (reasons.contains(Reason.valueOf(allowed.asText()))) permitted = true;
            if (!permitted || current >= previous || ("RUN".equals(link.path("frequency").asText()) && ledger.path("fired").has(id))) continue;
            String trigger = link.path("trigger").asText();
            if ("FIRST_THRESHOLD".equals(trigger) && !(previous > link.path("threshold").asInt() && current <= link.path("threshold").asInt())) continue;
            long requested = "PER_POINT".equals(trigger) ? (long)(previous - current) * link.path("multiplier").asInt() : link.path("amount").asInt();
            int next = ThemeRuntimeEngine.clamp(hp - requested, 0, rules.getHp().getMax());
            ObjectNode row = audit.addObject();
            row.put("ruleId", id); row.put("eventId", eventId); row.put("owner", ownerKey); row.set("reasons", eventReasons.path(key).deepCopy());
            row.put("reason", reasons.size() == 1 ? reasons.iterator().next().name() : "MULTIPLE");
            row.put("sourceKey", key); row.put("sourceBefore", previous); row.put("sourceAfter", current);
            row.put("targetKey", "sys.hp"); row.put("targetBefore", hp); row.put("targetAfter", next); row.put("actualDamage", hp - next);
            hp = next;
            if ("RUN".equals(link.path("frequency").asText())) ledger.with("fired").put(id, true);
        }
        ledger.put("owner", ownerKey);
        ledger.with("events").set(eventId, eventReasons);
        new ThemeRuntimeEngine().changeStat(after, rules, "sys.hp", hp - JourneyInitialState.numericValue(after, "sys.hp"));
        // 完整来源差量归内部审计，不把隐藏属性混入玩家背包日志。
        for (JsonNode row : audit) ledger.withArray("audit").add(row.deepCopy());
        after.getThemeRuntime().set("statLinks", ledger);
        return audit;
    }

    public static ArrayNode apply(JourneyRules rules, TopicRouteEngine.State before, String beforeOwner,
                                  TopicRouteEngine.State after, String afterOwner, String eventId, Reason reason) {
        if (beforeOwner == null || !beforeOwner.equals(afterOwner)) fail("联动不得跨所有者");
        return apply(rules, before, after, afterOwner, eventId, reason);
    }

    /** 每个可信子阶段记录真正下降的counter；合并动作时仍只按最终净下降结算一次。 */
    public static void recordDeclines(JourneyRules rules, TopicRouteEngine.State before, TopicRouteEngine.State after,
                                      Reason reason, Map<String,Set<Reason>> reasonsByStat) {
        if (reason == null || reasonsByStat == null) fail("记录下降需要可信原因");
        if (!rules.isStateEnabled()) return;
        for (JsonNode attribute : rules.getAttributes()) {
            String key = attribute.path("key").asText();
            if (key.startsWith("counter.") && JourneyInitialState.numericValue(before,key) > JourneyInitialState.numericValue(after,key))
                reasonsByStat.computeIfAbsent(key, ignored -> EnumSet.noneOf(Reason.class)).add(reason);
        }
    }

    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.path(key).asText().isEmpty()) fail("statLinks " + key + " 必须是文本");
        return node.path(key).asText();
    }
    private static void integer(JsonNode node, String key, int min, int max) {
        JsonNode value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) fail("statLinks " + key + " 整数或范围不正确");
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
