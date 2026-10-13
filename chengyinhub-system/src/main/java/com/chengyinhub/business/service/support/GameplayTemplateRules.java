package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 模板只保存使用规则；实际节点和结算身份由主题冻结时绑定到既有设施运行。 */
public final class GameplayTemplateRules {
    private static final Set<String> FIELDS = ThemeRuntimeConfig.set("hpCost", "luckCost", "actionCost", "statCosts", "resourceCosts", "costOptions", "limit", "scope", "targetScope", "hpDelta", "statChanges", "when", "effects", "grants", "grantOptions", "worldEffects", "publicOnce", "requireArrival");
    private GameplayTemplateRules() { }

    public static boolean hasSettlementRules(JsonNode block) {
        JsonNode rules=block.path("interactionRules");
        return rules.isObject() && (rules.size()>1 || rules.size()==1 && !rules.has("resourceSlots"));
    }

    /** 只校验模板独立可判断的形状；主题物资、属性、范围引用在 merge 后沿原发布闸校验。 */
    public static ObjectNode normalize(JsonNode source) {
        return normalize(source, null);
    }
    public static ObjectNode normalize(JsonNode source, JsonNode tools) {
        if (source != null && source.isObject() && source.has("resourceSlots")) {
            if (tools != null && !tools.isObject()) normalizeTools(tools);
            ObjectNode validation = TemplateResourceSlots.validationCopy(source, tools, "模板");
            ObjectNode normalized = normalizeLiteral(validation.get("rules"));
            if (tools != null) normalizeTools(validation.get("tools"));
            ObjectNode result = ((ObjectNode) source).deepCopy();
            if (result.size() > 1) for (String field : Arrays.asList("limit", "scope", "targetScope")) if (!result.has(field)) result.set(field, normalized.get(field));
            return result;
        }
        return normalizeLiteral(source);
    }
    private static ObjectNode normalizeLiteral(JsonNode source) {
        return normalizeLiteral(source,false);
    }
    private static ObjectNode normalizeLiteral(JsonNode source,boolean resolved) {
        if (source == null || source.isMissingNode()) return null;
        if (!source.isObject()) fail("须为对象");
        source.fieldNames().forEachRemaining(field -> { if (!FIELDS.contains(field)) fail("不支持字段：" + field); });
        ObjectNode rules = ((ObjectNode) source).deepCopy();
        if (!rules.has("limit")) rules.put("limit", 1);
        if (!rules.has("scope")) rules.put("scope", "OWNER");
        if (!rules.has("targetScope")) rules.put("targetScope", "OWNER");
        number(rules, "limit", 1, 99);
        option(rules, "scope", "OWNER", "WORLD");
        option(rules, "targetScope", "OWNER", "PARTICIPANTS", "WORLD");
        GameplayRuntimeConfig.costOptions(rules, null);
        for (String field : Arrays.asList("hpCost", "luckCost", "actionCost")) if (rules.has(field)) number(rules, field, 0, 999);
        if (rules.has("hpDelta")) number(rules, "hpDelta", -999, 999);
        ThemeRuntimeConfig.statCosts(rules);
        Set<String> statKeys = new HashSet<>();
        for (JsonNode change : ThemeRuntimeConfig.rows(rules, "statChanges", 32)) {
            fields(change, "statKey", "delta"); ThemeRuntimeConfig.counterKey(change, "statKey"); number(change, "delta", -999, 999);
            if (!statKeys.add(change.path("statKey").asText())) fail("数值变化重复");
        }
        Set<String> itemIds = new HashSet<>();
        amounts(rules, "resourceCosts", itemIds); amounts(rules, "grants", itemIds);
        if (rules.has("publicOnce") && (!rules.path("publicOnce").isBoolean() || rules.path("publicOnce").asBoolean() && (!"WORLD".equals(rules.path("scope").asText()) || rules.path("worldEffects").size() == 0)))
            fail("「公开线索只首次生效」须用于全场共用次数且有公开线索的工具");
        if (rules.has("requireArrival") && !rules.path("requireArrival").isBoolean()) fail("requireArrival 须为开关");
        if (rules.has("grantOptions")) {
            // 任选一组奖励：编译为本节点任务的配包选项（既有 quests[].choices），与固定奖励二选一
            if (rules.has("grants")) fail("任选奖励与固定奖励不能同时配置");
            List<JsonNode> options = ThemeRuntimeConfig.rows(rules, "grantOptions", 4);
            if (options.size() < 2) fail("grantOptions 须为 2 至 4 组");
            for (JsonNode option : options) {
                fields(option, "label", "grants");
                if (option.has("label") && (!option.path("label").isTextual() || option.path("label").asText().trim().isEmpty() || option.path("label").asText().length() > 20)) fail("grantOptions.label 文本不合法");
                if (option.path("grants").size() == 0) fail("grantOptions 每组至少一项奖励");
                amounts(option, "grants", itemIds);
            }
        }
        for (JsonNode group : rules.path("costOptions")) for (JsonNode cost : group.path("resourceCosts")) itemIds.add(cost.path("itemId").asText());
        // 复用设施的事件 DSL；不能靠模板绕过 sys 写入、脚本或效果边界。
        ObjectNode wrapper = JsonNodeFactory.instance.objectNode();
        ObjectNode event = wrapper.putArray("events").addObject().put("id", "template_rule").put("content", "template_rule");
        if (resolved) TemplateEffects.validateToolEffects(rules.path("effects"), itemIds);
        else { if(rules.has("effects"))event.set("effects",rules.get("effects")); ThemeRuntimeConfig.events(wrapper,itemIds); }
        if (rules.has("worldEffects")) {
            for (JsonNode effect : ThemeRuntimeConfig.rows(rules, "worldEffects", 20)) if (!effect.path("var").asText().startsWith("clue.")) fail("公共事实只允许显式 clue 状态");
            event.set("effects", rules.get("worldEffects")); ThemeRuntimeConfig.events(wrapper, itemIds);
        }
        ThemeRuntimeConfig.conditions(rules.path("when"), conditionItems(rules.path("when"), itemIds));
        return rules;
    }

    /** 交换与投票适配已有公共世界工具；终局投票显式引用主题结局。 */
    public static ObjectNode normalizeTools(JsonNode source, JsonNode slots) {
        if (source == null || slots == null || slots.isMissingNode()) return normalizeTools(source);
        if (!source.isObject()) return normalizeTools(source);
        ObjectNode declaration = JsonNodeFactory.instance.objectNode(); declaration.set("resourceSlots", slots);
        ObjectNode validation = TemplateResourceSlots.validationCopy(declaration, source, "模板", false);
        normalizeTools(validation.get("tools"));
        return ((ObjectNode) source).deepCopy();
    }
    public static ObjectNode normalizeTools(JsonNode source) {
        if (source == null || source.isMissingNode()) return null;
        fields(source, "allowTrade", "votePolicy", "decidesEnding", "plans", "fallback");
        ObjectNode tools = ((ObjectNode) source).deepCopy();
        if (tools.has("allowTrade") && !tools.path("allowTrade").isBoolean()) fail("allowTrade 须为开关");
        if (tools.has("decidesEnding") && !tools.path("decidesEnding").isBoolean()) fail("decidesEnding 须为开关");
        if (tools.has("votePolicy")) option(tools, "votePolicy", "MAJORITY", "UNANIMOUS", "MAJORITY_ACCEPTANCE");
        List<JsonNode> plans = ThemeRuntimeConfig.rows(tools, "plans", 30);
        boolean decidesEnding = tools.path("decidesEnding").asBoolean();
        if (decidesEnding && plans.isEmpty()) fail("decidesEnding 须配置提案");
        if (tools.has("fallback")) {
            if (plans.isEmpty()) fail("无方案收场须与投票选项一起配置");
            JsonNode fallback = tools.get("fallback"); if (!fallback.isObject()) fail("无方案收场格式不正确");
            fields(fallback, "title", "summary"); text(fallback, "title", 80, true); if (fallback.has("summary")) text(fallback, "summary", 2000, false);
        }
        // 表决方式是共享场次的主题级规则；模板里旧存的 votePolicy 仍校验取值，采用时与主题一致性在合并时检查
        Set<String> planKeys=new HashSet<>();
        for (JsonNode plan : plans) {
            fields(plan, "title", "summary", "materialCost", "when", "endingKey", "planKey");
            if (plan.has("planKey")) key(plan, "planKey");
            if (plan.has("planKey") && !planKeys.add(plan.path("planKey").asText())) fail("方案 planKey 重复");
            if (decidesEnding && plan.has("endingKey")) key(plan, "endingKey"); else if (!decidesEnding && plan.has("endingKey")) fail("非终局提案不能配置 endingKey");
            text(plan, "title", 80, true); if (plan.has("summary")) text(plan, "summary", 2000, false);
            Set<String> items = new HashSet<>(); amounts(plan, "materialCost", items);
            ThemeRuntimeConfig.conditions(plan.path("when"), conditionItems(plan.path("when"), items));
        }
        return tools;
    }

    public static void assertCompatible(JsonNode block) {
        JsonNode rules = block.path("interactionRules");
        if(block.path("outcomeRules").path("version").asInt()==1 && block.path("interactionTools").path("plans").size()>0) {
            rules.fieldNames().forEachRemaining(field->{if(!Arrays.asList("resourceSlots","limit","scope","targetScope").contains(field))fail("R5 投票工具不能叠加旧节点费用或独立效果");});return;
        }
        if (block.has("interactionRules") && !(rules.isObject() && rules.size() == 1 && rules.has("resourceSlots")) && block.path("interactionTools").path("plans").size() > 0) fail("投票工具不能叠加节点费用、次数或效果规则");
    }

    /** 摘要算法见 TemplateResourceSlots.slotDigest；调用方使用服务端摘要确认绑定。 */
    public static String slotDigest(JsonNode slots) { return TemplateResourceSlots.slotDigest(slots); }

    public static String planDigest(JsonNode plans) {
        ObjectNode canonical = JsonNodeFactory.instance.objectNode().put("count", plans.size());
        ArrayNode rows = canonical.putArray("plans");
        for (JsonNode plan : plans) rows.addObject().put("planKey", plan.path("planKey").asText()).put("title", plan.path("title").asText());
        return TemplateResourceSlots.digest(canonical.toString());
    }

    /** 返回可冻结的节点配置副本；调用方先对作者主题执行 assertJourneyReferences，再保存此副本。 */
    public static ObjectNode resolveNodeConfigs(ObjectNode theme, JsonNode nodeConfigs, Map<Long, Long> templateIds) {
        if (nodeConfigs == null || !nodeConfigs.isObject()) fail("节点配置须为对象");
        if (theme != null) TemplateResourceSlots.validateBindings(theme);
        ObjectNode resolved = ((ObjectNode) nodeConfigs).deepCopy();
        nodeConfigs.fields().forEachRemaining(row -> {
            if (!row.getValue().path("interactionRules").has("resourceSlots")) return;
            String node = row.getKey();
            try {
                ObjectNode validation=TemplateResourceSlots.validationBlock(row.getValue(), node);
                normalizeLiteral(validation.get("interactionRules"));normalizeTools(validation.get("interactionTools"));
            }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("节点 " + node + "：" + invalid.getMessage(), invalid); }
            resolved.set(node, TemplateResourceSlots.resolve(row.getValue(), theme, templateIds == null ? null : templateIds.get(realNodeId(node)), node));
        });
        return resolved;
    }

    /** realActKeys 必须来自本主题真实节点绑定；不创建幕、任务或作者指定的运行身份。 */
    public static ObjectNode merge(ObjectNode frozenTheme, JsonNode nodeConfigs, Map<Long, String> realActKeys) {
        return merge(frozenTheme, nodeConfigs, realActKeys, Collections.emptyMap());
    }

    /** templateIds 必须取自真实节点绑定或服务端冻结快照，不读取作者配置中的模板身份。 */
    public static ObjectNode merge(ObjectNode frozenTheme, JsonNode nodeConfigs, Map<Long, String> realActKeys, Map<Long, Long> templateIds) {
        return merge(frozenTheme,nodeConfigs,realActKeys,templateIds,null);
    }
    public static ObjectNode merge(ObjectNode frozenTheme, JsonNode nodeConfigs, Map<Long, String> realActKeys, Map<Long, Long> templateIds, JsonNode journeyStory) {
        return merge(frozenTheme,nodeConfigs,realActKeys,templateIds,journeyStory,null,false);
    }
    /** finalChapter = 本主题最终章 {id,name}：决定结局的表决方案未单独链接结局章时，以最终章收场、结果内容取方案本身。 */
    public static ObjectNode merge(ObjectNode frozenTheme, JsonNode nodeConfigs, Map<Long, String> realActKeys, Map<Long, Long> templateIds, JsonNode journeyStory, JsonNode finalChapter) {
        return merge(frozenTheme,nodeConfigs,realActKeys,templateIds,journeyStory,finalChapter,false);
    }
    public static ObjectNode mergeFrozen(ObjectNode frozenTheme, JsonNode nodeConfigs, Map<Long,String> realActKeys, JsonNode journeyStory) {
        return merge(frozenTheme,nodeConfigs,realActKeys,Collections.emptyMap(),journeyStory,null,true);
    }
    private static ObjectNode merge(ObjectNode frozenTheme, JsonNode nodeConfigs, Map<Long, String> realActKeys, Map<Long, Long> templateIds, JsonNode journeyStory, JsonNode finalChapter, boolean alreadyFrozen) {
        ObjectNode result = frozenTheme == null ? null : frozenTheme.deepCopy();
        if (nodeConfigs == null || nodeConfigs.isMissingNode()) return result;
        if (!nodeConfigs.isObject()) fail("节点配置须为对象");
        ObjectNode resolvedConfigs = resolveNodeConfigs(frozenTheme, nodeConfigs, templateIds);
        if (result != null) {
            if (result.has("nodeConfigs")) result.set("nodeConfigs", resolvedConfigs.deepCopy());
        }
        List<Map.Entry<Long, ObjectNode>> configured = new ArrayList<>();
        resolvedConfigs.fields().forEachRemaining(row -> {
            assertCompatible(row.getValue());
            if (!row.getValue().has("interactionRules")) return;
            if (!row.getKey().matches("[1-9][0-9]{0,18}")) fail("节点标识不合法");
            final long nodeId;
            try { nodeId = Long.parseLong(row.getKey()); } catch (NumberFormatException invalid) { throw new IllegalArgumentException("interactionRules 节点标识越界", invalid); }
            if(row.getValue().path("outcomeRules").path("version").asInt()==1) {
                if(row.getValue().path("interactionTools").path("plans").size()>0)return;
                if(frozenTheme!=null)for(JsonNode plan:frozenTheme.path("gameplayRuntime").path("plans"))if(plan.path("operationNodeId").asLong()==nodeId)return;
            }
            configured.add(new AbstractMap.SimpleImmutableEntry<>(nodeId, normalizeLiteral(row.getValue().get("interactionRules"),true)));
        });
        if (result != null && result.path("gameplayRuntime").isObject()) removeDisabledAdapters((ObjectNode) result.get("gameplayRuntime"), resolvedConfigs, realActKeys);
        result = mergeTools(result, resolvedConfigs, realActKeys, templateIds, journeyStory, finalChapter, nodeConfigs,alreadyFrozen);
        if(result!=null && result.has("nodeConfigs"))result.set("nodeConfigs",resolveNodeConfigs(frozenTheme,result.path("nodeConfigs"),templateIds));
        if(result!=null)for(JsonNode scene:result.path("scenes"))if(scene.has("nodeConfigs"))
            ((ObjectNode)scene).set("nodeConfigs",resolveNodeConfigs(frozenTheme,scene.path("nodeConfigs"),templateIds));
        if (result != null) result.remove("templateBindings");
        if (configured.isEmpty()) {GameplayOutcomeRules.assertBindings(result,resolvedConfigs);return result;}
        if (result == null || !result.path("gameplayRuntime").isObject()) fail("需要主题已配置通用玩法的真实幕与任务");
        ObjectNode game = (ObjectNode) result.get("gameplayRuntime");
        ArrayNode facilities;
        if (!game.has("facilities")) facilities = game.putArray("facilities");
        else if (game.get("facilities").isArray()) facilities = (ArrayNode) game.get("facilities");
        else { fail("设施清单须为数组"); return null; }
        Set<String> acts = new HashSet<>();
        for (JsonNode act : game.path("acts")) acts.add(act.path("actKey").asText());
        for (Map.Entry<Long, ObjectNode> entry : configured) {
            long nodeId = entry.getKey(); String actKey = realActKeys == null ? null : realActKeys.get(nodeId);
            if (actKey == null || !acts.contains(actKey)) fail("节点 " + nodeId + " 未绑定真实幕");
            String key = "tpl_node_" + nodeId;
            ObjectNode firstUse = null;
            for (JsonNode quest : game.path("quests")) if (quest.path("nodeId").asLong() == nodeId) {
                if (firstUse != null) fail("节点 " + nodeId + " 绑定多个任务，首次使用不明确");
                firstUse = (ObjectNode) quest;
            }
            boolean generated = false;
            for (int i = facilities.size() - 1; i >= 0; i--) {
                JsonNode existing = facilities.get(i);
                if (existing.path("operationNodeId").asLong() != nodeId && !key.equals(existing.path("facilityKey").asText()) && !key.equals(existing.path("settlementSlot").asText())) continue;
                boolean identity = existing.path("operationNodeId").isIntegralNumber() && existing.path("operationNodeId").asLong() == nodeId
                        && key.equals(existing.path("facilityKey").asText()) && key.equals(existing.path("settlementSlot").asText())
                        && actKey.equals(existing.path("actKey").asText())
                        && ("PARTICIPANTS".equals(existing.path("targetScope").asText()) || firstUse != null && key.equals(firstUse.path("facilityKey").asText()));
                if (!identity || generated) fail("节点 " + nodeId + " 已配置设施，不能重复绑定模板规则");
                generated = true; facilities.remove(i);
            }
            ObjectNode facility = entry.getValue().deepCopy();
            facility.put("facilityKey", key); facility.put("settlementSlot", key); facility.put("operationNodeId", nodeId); facility.put("actKey", actKey);
            // 效果对象须经可信到达：沿用本节点在章节里绑定的到达地点（quests[].locationKey），由既有收件队伍到达核验执行
            JsonNode requireArrival = facility.remove("requireArrival");
            if (requireArrival != null && requireArrival.asBoolean()) {
                String location = null;
                for (JsonNode quest : game.path("quests")) if (quest.path("nodeId").asLong() == nodeId && quest.hasNonNull("locationKey")) location = quest.path("locationKey").asText();
                if (location == null || location.isEmpty()) fail("节点 " + nodeId + " 要求效果对象已到达，请先在章节里设置该站点的到达地点");
                facility.put("arrivalLocationKey", location);
            }
            JsonNode grantOptions = facility.remove("grantOptions");
            if (grantOptions != null) {
                if (firstUse == null || "PARTICIPANTS".equals(facility.path("targetScope").asText())) fail("节点 " + nodeId + " 任选奖励需要本节点的真实任务");
                if (!templateChoices(firstUse)) fail("节点 " + nodeId + " 任务已配置配包");
                ArrayNode choices = firstUse.putArray("choices"); int index = 0;
                for (JsonNode option : grantOptions) {
                    ObjectNode choice = choices.addObject().put("choiceKey", "opt_" + (++index));
                    if (option.has("label")) choice.set("label", option.get("label").deepCopy());
                    choice.set("grants", option.get("grants").deepCopy());
                }
            } else if (firstUse != null && firstUse.path("choices").size() > 0 && templateChoices(firstUse)) firstUse.remove("choices");
            if (!"PARTICIPANTS".equals(facility.path("targetScope").asText())) {
                if (firstUse == null || !actKey.equals(firstUse.path("actKey").asText())) fail("节点 " + nodeId + " 缺少首次使用的真实任务");
                if (firstUse.has("facilityKey") && (!generated || !key.equals(firstUse.path("facilityKey").asText()))) fail("节点 " + nodeId + " 任务已绑定设施");
                firstUse.put("facilityKey", key);
            } else if (generated && firstUse != null && key.equals(firstUse.path("facilityKey").asText())) {
                firstUse.remove("facilityKey");
            }
            facilities.add(facility);
        }
        // 保持原设施范围、物资类型、幕关联和槽冲突的唯一校验真源。
        Set<String> items = new HashSet<>(); for (JsonNode item : result.path("items")) items.add(item.path("id").asText());
        GameplayRuntimeConfig.validate(result, items, false);
        GameplayOutcomeRules.assertBindings(result,resolvedConfigs);
        return result;
    }

    private static void removeDisabledAdapters(ObjectNode game, JsonNode nodeConfigs, Map<Long, String> realActKeys) {
        if (!game.path("facilities").isArray() || realActKeys == null) return;
        ArrayNode facilities = (ArrayNode) game.get("facilities");
        for (int i = facilities.size() - 1; i >= 0; i--) {
            JsonNode existing = facilities.get(i); long nodeId = existing.path("operationNodeId").asLong(); String key = "tpl_node_" + nodeId;
            JsonNode block = nodeConfigs.get(Long.toString(nodeId));
            if (block == null || block.has("interactionRules") || !realActKeys.containsKey(nodeId)) continue;
            if (!key.equals(existing.path("facilityKey").asText()) || !key.equals(existing.path("settlementSlot").asText()) || !realActKeys.get(nodeId).equals(existing.path("actKey").asText())) continue;
            ObjectNode bound = null; int count = 0;
            for (JsonNode quest : game.path("quests")) if (quest.path("nodeId").asLong() == nodeId) {
                count++; if (key.equals(quest.path("facilityKey").asText())) bound = (ObjectNode) quest;
            }
            if (!"PARTICIPANTS".equals(existing.path("targetScope").asText()) && (count != 1 || bound == null)) continue;
            facilities.remove(i); if (bound != null) bound.remove("facilityKey");
        }
    }

    private static ObjectNode mergeTools(ObjectNode theme, JsonNode nodeConfigs, Map<Long, String> realActKeys, Map<Long, Long> templateIds, JsonNode journeyStory, JsonNode finalChapter, JsonNode declarations, boolean alreadyFrozen) {
        Map<Long, ObjectNode> toolsByNode = new LinkedHashMap<>();
        nodeConfigs.fields().forEachRemaining(row -> {
            if (!row.getValue().has("interactionTools")) return;
            long nodeId = realNodeId(row.getKey());
            toolsByNode.put(nodeId, normalizeTools(row.getValue().get("interactionTools")));
        });
        if (toolsByNode.isEmpty() && (theme == null || !theme.path("gameplayRuntime").has("templateTools"))) return theme;
        if (theme == null || !theme.path("gameplayRuntime").isObject()) fail("互动工具需要主题真实公共世界与任务");
        ObjectNode game = (ObjectNode) theme.get("gameplayRuntime");
        Map<String,JsonNode> frozenPlans=new HashMap<>();for(JsonNode plan:game.path("plans"))frozenPlans.put(plan.path("endingKey").asText(),plan.deepCopy());
        if (game.has("templateTools")) {
            validateMetadata(game);
            JsonNode prior = game.get("templateTools");
            Set<String> generated = new HashSet<>(); for (JsonNode row : prior.path("nodes")) for (JsonNode key : row.path("planKeys")) generated.add(key.asText());
            ArrayNode plans = game.path("plans").isArray() ? (ArrayNode) game.get("plans") : null;
            if (plans != null) for (int i = plans.size() - 1; i >= 0; i--) if (generated.contains(plans.get(i).path("endingKey").asText())) plans.remove(i);
            restoreBaseline(game, prior, "allowTrade", "baselineAllowTrade"); restoreBaseline(game, prior, "votePolicy", "baselineVotePolicy");
            game.remove("templateTools");
        }
        if (toolsByNode.isEmpty()) { validateGame(theme); return theme; }
        ObjectNode metadata = JsonNodeFactory.instance.objectNode();
        if (game.has("allowTrade")) metadata.set("baselineAllowTrade", game.get("allowTrade").deepCopy());
        if (game.has("votePolicy")) metadata.set("baselineVotePolicy", game.get("votePolicy").deepCopy());
        ArrayNode sources = metadata.putArray("nodes");
        if (game.has("plans") && !game.path("plans").isArray()) fail("提案清单须为数组");
        ArrayNode plans = game.has("plans") ? (ArrayNode) game.get("plans") : game.putArray("plans");
        Set<String> acts = new HashSet<>(); for (JsonNode act : game.path("acts")) acts.add(act.path("actKey").asText());
        String finalActKey = game.path("acts").size() == 0 ? "" : game.path("acts").path(game.path("acts").size() - 1).path("actKey").asText();
        ArrayNode finalOutcomes = JsonNodeFactory.instance.arrayNode(); boolean storyCatalog = false; JsonNode fallbackOutcome = null;
        if (!alreadyFrozen) game.remove("storyEndings");
        for (Map.Entry<Long, ObjectNode> entry : toolsByNode.entrySet()) {
            long nodeId = entry.getKey(); ObjectNode tools = entry.getValue();
            if (realActKeys == null || !acts.contains(realActKeys.get(nodeId))) fail("互动工具节点 " + nodeId + " 未绑定真实幕");
            ObjectNode source = sources.addObject().put("nodeId", nodeId);
            if (tools.has("allowTrade")) {
                source.set("allowTrade", tools.get("allowTrade"));
                if (tools.path("allowTrade").asBoolean()) game.put("allowTrade", true);
            }
            ArrayNode keys = source.putArray("planKeys");
            if (tools.path("plans").size() == 0) continue;
            // 是否收场由采用位置决定：节点在最终章（最后一幕）时，执行的方案即本局结果；中间章节执行后继续。
            // 旧模板显式 decidesEnding 的链接/结局目录保持原合同。
            boolean legacyLinked = false;
            if (tools.path("decidesEnding").asBoolean()) {
                legacyLinked = theme.path("templateBindings").path(String.valueOf(nodeId)).has("planEndings");
                for (JsonNode plan : tools.path("plans")) legacyLinked |= plan.has("endingKey") && !plan.has("planKey");
            }
            boolean frozenTerminal = false;
            for (JsonNode previous : frozenPlans.values()) frozenTerminal |= previous.path("operationNodeId").asLong() == nodeId && previous.path("terminal").asBoolean();
            // 已冻结的本局沿用冻结时的收场判定，不因重读章节而改变
            // 旧编辑器必填表决方式：带表决方式且未声明决定结局的旧模板仍是普通共同方案（兼容）；新模板不写表决方式，按采用章节决定
            boolean legacyOrdinary = tools.has("votePolicy") && !tools.path("decidesEnding").asBoolean();
            boolean decidesEnding = alreadyFrozen ? frozenTerminal : legacyLinked || !legacyOrdinary && finalChapter != null && finalChapter.path("id").asLong() > 0 && finalActKey.equals(realActKeys.get(nodeId));
            if (decidesEnding) source.put("decidesEnding", true);
            if (decidesEnding && tools.has("fallback")) {
                if (fallbackOutcome != null && !fallbackOutcome.equals(tools.get("fallback"))) fail("同一主题的终局表决须使用相同的无方案收场");
                fallbackOutcome = tools.get("fallback");
            }
            ObjectNode firstUse = null;
            for (JsonNode quest : game.path("quests")) if (quest.path("nodeId").asLong() == nodeId) {
                if (firstUse != null) fail("投票工具节点绑定多个任务"); firstUse = (ObjectNode) quest;
            }
            if (firstUse == null || firstUse.has("facilityKey") || firstUse.has("costOptions") || firstUse.path("hpCost").asInt() != 0 || firstUse.path("luckCost").asInt() != 0 || firstUse.path("actionCost").asInt() != 0
                    || firstUse.path("statCosts").size() != 0 || firstUse.path("resourceCosts").size() != 0 || firstUse.path("grants").size() != 0 || firstUse.path("choices").size() != 0) fail("投票工具须绑定唯一免费且无独立奖励的真实任务");
            String policy = tools.has("votePolicy") ? tools.path("votePolicy").asText() : game.path("votePolicy").asText("MAJORITY");
            if (game.has("votePolicy") && !policy.equals(game.path("votePolicy").asText())) fail("节点 " + nodeId + " 模板的表决方式与主题的表决方式不一致，请在主题进行方式里统一");
            game.put("votePolicy", policy); source.put("votePolicy", policy);
            for (JsonNode manual : plans) if (manual.path("operationNodeId").asLong() == nodeId) fail("互动工具节点 " + nodeId + " 已有手工提案，不能混合绑定");
            int option = 0;
            for (JsonNode configured : tools.path("plans")) {
                option++;
                boolean storyLinked = decidesEnding && (configured.has("planKey") || !configured.has("endingKey"));
                String key = decidesEnding && !storyLinked ? configured.path("endingKey").asText() : "tpl_vote_" + nodeId + "_" + option;
                for (JsonNode existing : plans) if (key.equals(existing.path("endingKey").asText())) fail("互动工具提案标识与既有提案冲突");
                ObjectNode plan = (ObjectNode) configured.deepCopy(); plan.put("endingKey", key); plan.put("operationNodeId", nodeId); plan.put("terminal", decidesEnding);
                plan.remove("planKey");
                if (storyLinked) {
                    String planKey = configured.path("planKey").asText(String.valueOf(option));
                    JsonNode previous=frozenPlans.get(key);
                    if(alreadyFrozen) {
                        if(previous==null || !previous.has("storyEndingId") || previous.path("operationNodeId").asLong()!=nodeId)fail("节点 "+nodeId+" 方案 "+planKey+" 缺少已冻结的结局链接");
                        plan.set("storyEndingId",previous.path("storyEndingId").deepCopy());plan.set("endingChapterId",previous.path("endingChapterId").deepCopy());
                        plans.add(plan);keys.add(key);continue;
                    }
                    JsonNode binding = theme.path("templateBindings").path(String.valueOf(nodeId));
                    if (!Objects.equals(templateIds.get(nodeId), binding.path("templateId").asLong())) fail("节点 " + nodeId + " 方案 " + planKey + " 模板身份不匹配");
                    JsonNode slots = declarations.path(String.valueOf(nodeId)).path("interactionRules").path("resourceSlots");
                    if (!slotDigest(slots.isMissingNode() ? JsonNodeFactory.instance.arrayNode() : slots).equals(binding.path("slotDigest").asText())) fail("节点 " + nodeId + " 方案 " + planKey + " 声明摘要不匹配");
                    boolean linked = binding.has("planEndings");
                    if (linked && !planDigest(tools.path("plans")).equals(binding.path("planDigest").asText())) fail("节点 " + nodeId + " 方案 " + planKey + " 已变化，需要重新链接结局");
                    JsonNode chapter = binding.path("planEndings").path(planKey);
                    if (!chapter.isTextual()) {
                        // 未单独链接结局章：以最终章收场，结果内容就是本方案（名称与说明）；不要求每个方案各建一章。
                        if (finalChapter == null || finalChapter.path("id").asLong() <= 0) fail("节点 " + nodeId + " 方案 " + planKey + " 需要最终章");
                        if (!finalActKey.equals(realActKeys.get(nodeId))) fail("节点 " + nodeId + " 决定结局的表决须放在最终章");
                        plan.put("storyEndingId", key).put("endingChapterId", finalChapter.path("id").asLong());
                        ObjectNode outcome = finalOutcomes.addObject().put("code", key).put("title", plan.path("title").asText());
                        if (!plan.path("summary").asText("").isEmpty()) outcome.put("summary", plan.path("summary").asText());
                        outcome.put("chapterId", finalChapter.path("id").asLong()).put("fallback", false);
                        plans.add(plan); keys.add(key); continue;
                    }
                    JsonNode ending = null;
                    for (JsonNode candidate : journeyStory == null ? JsonNodeFactory.instance.arrayNode() : journeyStory.path("ending").path("endings"))
                        if (candidate.path("chapterId").asLong() == TemplateResourceSlots.nodeId(chapter.asText())) {
                            if (ending != null) fail("节点 " + nodeId + " 方案 " + planKey + " 结局章对应多个结局");
                            ending = candidate;
                        }
                    if (ending == null) fail("节点 " + nodeId + " 方案 " + planKey + " 结局章未登记在 journeyStory");
                    plan.put("storyEndingId", ending.path("code").asText()).put("endingChapterId", ending.path("chapterId").asLong());
                    storyCatalog = true;
                }
                plans.add(plan); keys.add(key);
            }
        }
        if (!alreadyFrozen && (storyCatalog || finalOutcomes.size() > 0)) {
            ArrayNode catalog = game.putArray("storyEndings"); boolean hasFallback = false;
            for (JsonNode candidate : journeyStory == null ? JsonNodeFactory.instance.arrayNode() : journeyStory.path("ending").path("endings")) {
                ObjectNode row = catalog.addObject();
                for (String field : Arrays.asList("code","title","summary","chapterId")) if(candidate.has(field))row.set(field,candidate.get(field).deepCopy());
                row.put("fallback",candidate.path("fallback").asBoolean()); hasFallback |= candidate.path("fallback").asBoolean();
            }
            catalog.addAll(finalOutcomes);
            if (!hasFallback) {
                // 没有方案获接受并执行时的收场：取表决工具写的「无方案执行」内容，未写则用最终章名称。
                if (finalChapter == null || finalChapter.path("id").asLong() <= 0) fail("终局表决需要最终章作为默认收场");
                String title = fallbackOutcome == null ? finalChapter.path("name").asText("") : fallbackOutcome.path("title").asText();
                ObjectNode fallback = catalog.addObject().put("code", "final_" + finalChapter.path("id").asLong()).put("title", title.trim().isEmpty() ? "最终章" : title);
                if (fallbackOutcome != null && !fallbackOutcome.path("summary").asText("").isEmpty()) fallback.put("summary", fallbackOutcome.path("summary").asText());
                fallback.put("chapterId", finalChapter.path("id").asLong()).put("fallback", true);
            }
        }
        game.set("templateTools", metadata); validateGame(theme); ThemeRuntimeConfig.assertPlanEndings(theme); return theme;
    }

    /** 本节点任务的配包是否全部来自模板任选奖励（opt_ 前缀）；主题原有配包不被模板覆盖或清除。 */
    private static boolean templateChoices(JsonNode quest) {
        for (JsonNode choice : quest.path("choices")) if (!choice.path("choiceKey").asText().startsWith("opt_")) return false;
        return true;
    }

    private static void restoreBaseline(ObjectNode game, JsonNode metadata, String field, String baseline) {
        if (metadata.has(baseline)) game.set(field, metadata.get(baseline).deepCopy()); else game.remove(field);
    }
    private static void validateGame(ObjectNode theme) {
        Set<String> items = new HashSet<>(); for (JsonNode item : theme.path("items")) items.add(item.path("id").asText());
        GameplayRuntimeConfig.validate(theme, items, false);
    }

    /** 内部来源记录也走严格发布闸；只有记录中的完整实际身份可被重新生成或删除。 */
    static void validateMetadata(JsonNode game) {
        JsonNode metadata = game.get("templateTools"); if (metadata == null) return;
        fields(metadata, "baselineAllowTrade", "baselineVotePolicy", "nodes");
        if (metadata.has("baselineAllowTrade") && !metadata.path("baselineAllowTrade").isBoolean()) fail("工具基线交换开关不合法");
        if (metadata.has("baselineVotePolicy")) option(metadata, "baselineVotePolicy", "MAJORITY", "UNANIMOUS", "MAJORITY_ACCEPTANCE");
        if (!metadata.path("nodes").isArray() || metadata.path("nodes").size() == 0) fail("工具来源须为非空节点数组");
        Set<Long> nodes = new HashSet<>(); Set<String> planKeys = new HashSet<>();
        for (JsonNode source : ThemeRuntimeConfig.rows(metadata, "nodes", 500)) {
            fields(source, "nodeId", "allowTrade", "votePolicy", "decidesEnding", "planKeys"); number(source, "nodeId", 1, Long.MAX_VALUE);
            long nodeId = source.path("nodeId").asLong(); if (!nodes.add(nodeId)) fail("工具来源节点重复");
            if (source.has("decidesEnding") && !source.path("decidesEnding").isBoolean()) fail("工具终局开关不合法");
            if (source.has("allowTrade") && !source.path("allowTrade").isBoolean()) fail("工具来源交换开关不合法");
            if (source.path("allowTrade").asBoolean() && !game.path("allowTrade").asBoolean()) fail("工具交换开关与运行配置不一致");
            if (source.has("votePolicy")) option(source, "votePolicy", "MAJORITY", "UNANIMOUS", "MAJORITY_ACCEPTANCE");
            List<JsonNode> keys = ThemeRuntimeConfig.rows(source, "planKeys", 30);
            if (!source.path("planKeys").isArray()) fail("工具来源提案须为数组");
            if (!keys.isEmpty()) {
                option(source, "votePolicy", "MAJORITY", "UNANIMOUS", "MAJORITY_ACCEPTANCE");
                if (!source.path("votePolicy").asText().equals(game.path("votePolicy").asText())) fail("工具表决策略与运行配置不一致");
            }
            for (JsonNode key : keys) {
                if (!key.isTextual() || !(source.path("decidesEnding").asBoolean() ? key.asText().matches("[a-z][a-z0-9_]{0,31}") : key.asText().matches("tpl_vote_" + nodeId + "_([1-9]|[12][0-9]|30)")) || !planKeys.add(key.asText())) fail("工具来源提案标识无效或重复");
                JsonNode found = null;
                for (JsonNode plan : game.path("plans")) if (key.asText().equals(plan.path("endingKey").asText())) {
                    if (found != null) fail("工具来源对应多个提案"); found = plan;
                }
                if (found == null || !found.path("operationNodeId").isIntegralNumber() || !found.path("operationNodeId").canConvertToLong() || found.path("operationNodeId").asLong() != nodeId
                        || !found.path("terminal").isBoolean() || found.path("terminal").asBoolean() != source.path("decidesEnding").asBoolean()) fail("工具来源与实际提案身份不一致");
            }
        }
    }

    /** 在通用主题 nodeId 重映射后调用，只翻译有内部来源证明的生成提案 key。 */
    public static void remapToolIdentities(ObjectNode theme, Map<Long, Long> nodeMap) {
        if (theme == null || !theme.path("gameplayRuntime").path("templateTools").isObject()) return;
        ObjectNode game = (ObjectNode) theme.get("gameplayRuntime");
        for (JsonNode source : game.path("templateTools").path("nodes")) {
            long mapped = source.path("nodeId").asLong();
            if (source.path("decidesEnding").asBoolean()) continue;
            for (int i = 0; i < source.path("planKeys").size(); i++) {
                String oldKey = source.path("planKeys").get(i).asText(); java.util.regex.Matcher match = java.util.regex.Pattern.compile("^tpl_vote_([1-9][0-9]*)_([1-9]|[12][0-9]|30)$").matcher(oldKey);
                if (!match.matches()) fail("复制工具提案标识不合法");
                Long actual = nodeMap == null ? null : nodeMap.get(realNodeId(match.group(1)));
                if (actual == null || actual != mapped) fail("复制工具来源没有实际目标节点");
                String nextKey = "tpl_vote_" + mapped + "_" + match.group(2);
                for (JsonNode plan : game.path("plans")) if (oldKey.equals(plan.path("endingKey").asText())) {
                    if (plan.path("operationNodeId").asLong() != mapped || plan.path("terminal").asBoolean(true)) fail("复制工具提案身份不一致");
                    ((ObjectNode) plan).put("endingKey", nextKey);
                }
                ((ArrayNode) source.get("planKeys")).set(i, JsonNodeFactory.instance.textNode(nextKey));
            }
        }
        validateMetadata(game);
    }

    private static long realNodeId(String key) {
        if (!key.matches("[1-9][0-9]{0,18}")) fail("节点标识不合法");
        try { return Long.parseLong(key); } catch (NumberFormatException invalid) { throw new IllegalArgumentException("interactionRules 节点标识越界", invalid); }
    }

    private static Set<String> conditionItems(JsonNode node, Set<String> initial) {
        Set<String> ids = new HashSet<>(initial);
        if (node.isArray()) for (JsonNode condition : node) if (condition.has("itemId")) { key(condition, "itemId"); ids.add(condition.path("itemId").asText()); }
        return ids;
    }
    private static void amounts(JsonNode rules, String field, Set<String> all) {
        Set<String> seen = new HashSet<>();
        for (JsonNode row : ThemeRuntimeConfig.rows(rules, field, 200)) {
            fields(row, "itemId", "quantity"); key(row, "itemId"); number(row, "quantity", 1, 999);
            if (!seen.add(row.path("itemId").asText())) fail(field + " 物资重复"); all.add(row.path("itemId").asText());
        }
    }
    private static void fields(JsonNode row, String... names) {
        if (!row.isObject()) fail("配置条目须为对象"); Set<String> allowed = ThemeRuntimeConfig.set(names);
        row.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) fail("不支持字段：" + name); });
    }
    private static void key(JsonNode row, String field) { if (!row.path(field).isTextual() || !row.path(field).asText().matches("[a-z][a-z0-9_]{0,31}")) fail(field + " 标识不合法"); }
    private static void option(JsonNode row, String field, String... allowed) { if (!row.path(field).isTextual() || !Arrays.asList(allowed).contains(row.path(field).asText())) fail(field + " 范围不合法"); }
    private static void number(JsonNode row, String field, long min, long max) {
        JsonNode value = row.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < min || value.longValue() > max) fail(field + " 整数或范围不合法");
    }
    private static void text(JsonNode row, String field, int max, boolean required) {
        JsonNode value = row.path(field);
        if (!value.isTextual() || value.asText().length() > max || required && value.asText().trim().isEmpty()) fail(field + " 文本不合法");
    }
    private static void fail(String detail) { throw new IllegalArgumentException("模板互动配置 " + detail); }
}
