package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 通用运行的受控配置合同；尚无运行入口的动作在发布时拒绝。 */
public final class GameplayRuntimeConfig {
    /**
     * 玩法运行时 storyEndings 的条数上限。与旅程故事 JourneyStoryValidator.MAX_ITEMS(16)是两条独立配置链,
     * 不会互相导入,各自在保存时校验;两者不同是有意的,改动任一侧前先确认产品口径。
     */
    static final int MAX_STORY_ENDINGS = 100;
    private GameplayRuntimeConfig() { }

    /** 参数为完整 themeRuntimeConfig JSON，返回其中已校验的运行段。 */
    public static ObjectNode parse(String rawThemeConfig) {
        ObjectNode config = ThemeRuntimeConfig.parseDraft(rawThemeConfig);
        return config == null || !config.has("gameplayRuntime") ? null : (ObjectNode) config.get("gameplayRuntime").deepCopy();
    }
    public static String normalize(String rawThemeConfig) { return ThemeRuntimeConfig.normalize(rawThemeConfig); }
    public static String getRuntimeScope(JsonNode themeConfig) {
        return themeConfig == null ? "PLAYER" : themeConfig.path("gameplayRuntime").path("runtimeScope").asText("PLAYER");
    }
    public static String getWorldMode(JsonNode themeConfig) {
        return themeConfig == null ? null : themeConfig.path("gameplayRuntime").path("worldMode").asText(null);
    }

    static void validate(ObjectNode themeConfig, Set<String> items, boolean draft) {
        if (!themeConfig.has("gameplayRuntime")) return;
        JsonNode game = themeConfig.get("gameplayRuntime");
        fields(game, "runtimeScope", "worldMode", "releaseCode", "ruleVersion", "storyVersion", "routeVersion", "survival", "initialGrants", "acts", "quests", "facilities", "plans", "allowTrade", "votePolicy", "arrivalTtlSeconds", "templateTools", "storyEndings");
        option(game, "runtimeScope", "PLAYER", "TEAM");
        boolean world = "WORLD_SHARED".equals(game.path("worldMode").asText());
        if ("TEAM".equals(game.path("runtimeScope").asText())) option(game, "worldMode", "TEAM_INDEPENDENT", "WORLD_SHARED");
        else if (game.has("worldMode")) fail("PLAYER 不适用 worldMode");
        for (String field : Arrays.asList("releaseCode", "ruleVersion", "storyVersion", "routeVersion")) if (game.has(field)) text(game, field, 64);
        survival(game.path("survival"));
        if (game.has("allowTrade") && (!game.path("allowTrade").isBoolean() || (!world && game.path("allowTrade").asBoolean()))) fail("allowTrade 须为公共世界开关");
        if (game.has("votePolicy")) { if (!world) fail("votePolicy 仅用于公共世界"); option(game,"votePolicy","MAJORITY","UNANIMOUS","MAJORITY_ACCEPTANCE"); }
        if (game.has("arrivalTtlSeconds")) number(game,"arrivalTtlSeconds",300,3600);
        GameplayTemplateRules.validateMetadata(game);
        Set<String> resources = new HashSet<>(), transferable = new HashSet<>();
        for (JsonNode item : themeConfig.path("items")) if ("ITEM".equals(item.path("kind").asText())) {
            resources.add(item.path("id").asText()); if (!item.path("wear").asBoolean()) transferable.add(item.path("id").asText());
        }
        amounts(game, "initialGrants", items);
        Set<String> acts = ids(game, "acts", "actKey", 200), quests = ids(game, "quests", "questKey", 500);
        Set<String> facilities = ids(game, "facilities", "facilityKey", 100);
        for (JsonNode recovery : game.path("survival").path("recoveries")) stringRefs(recovery,"actKeys",acts);
        for (JsonNode ration : game.path("survival").path("rations")) {ref(ration,"actKey",acts);ref(ration,"itemId",resources);}
        ids(game, "plans", "endingKey", 30);
        Set<String> ownerSlots = new HashSet<>(), worldSlots = new HashSet<>();
        Map<String, List<String>> dependencies = new HashMap<>();
        int questIndex = 0;
        for (JsonNode quest : rows(game, "quests", 500)) {
            try {
            fields(quest, "questKey", "actKey", "nodeId", "locationKey", "prerequisites", "settlementSlot", "hpCost", "luckCost", "statCosts", "resourceCosts", "actionCost", "costOptions", "choices", "grants", "when", "exclusiveGroup", "extraSlot", "extraOnly", "facilityKey", "approvedFallbackNodeIds", "guideKey");
            ref(quest, "actKey", acts); number(quest, "nodeId", 1, Long.MAX_VALUE); key(quest, "locationKey"); key(quest, "settlementSlot");
            Set<Long> fallbackIds=new HashSet<>();
            for(JsonNode id:rows(quest,"approvedFallbackNodeIds",20)) if(!id.isIntegralNumber() || !id.canConvertToLong() || id.asLong()<1 || !fallbackIds.add(id.asLong()))fail("备用点ID无效或重复");
            if(quest.has("guideKey"))key(quest,"guideKey");
            // 互斥由单独的group表达，任务槽自身必须唯一。
            if (!ownerSlots.add(quest.path("settlementSlot").asText())) fail("quests.settlementSlot 重复");
            costs(quest, resources); amounts(quest, "grants", items); conditions(quest.path("when"), items);
            for (String field : Arrays.asList("exclusiveGroup", "extraSlot")) if (quest.has(field)) key(quest, field);
            if (quest.has("extraOnly") && !quest.path("extraOnly").isBoolean()) fail("quests.extraOnly 须为开关");
            if (quest.has("facilityKey")) ref(quest, "facilityKey", facilities);
            List<String> prior = stringRefs(quest, "prerequisites", quests);
            dependencies.put(quest.path("questKey").asText(), prior);
            ids(quest, "choices", "choiceKey", 20);
            for (JsonNode choice : rows(quest, "choices", 20)) { fields(choice, "choiceKey", "grants", "label"); if (choice.has("label")) text(choice, "label", 20); amounts(choice, "grants", items); }
            if (quest.path("choices").size() > 0 && quest.path("grants").size() > 0) fail("quests 奖励不能同时配置配包与直接grants");
            } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("gameplayRuntime.quests[" + questIndex + "](" + quest.path("questKey").asText() + ")：" + invalid.getMessage(), invalid); }
            questIndex++;
        }
        for (String quest : dependencies.keySet()) visit(quest, dependencies, new HashSet<>(), new HashSet<>());
        Set<String> boundQuests = new HashSet<>();
        for (JsonNode act : rows(game, "acts", 200)) {
            fields(act, "actKey", "questKeys", "completionRule", "hostUnlock");
            if (act.has("hostUnlock") && !act.get("hostUnlock").isBoolean()) fail("acts.hostUnlock 须为开关");
            List<String> members = stringRefs(act, "questKeys", quests);
            boolean emptyDraft = draft && members.isEmpty();
            if (members.isEmpty() && !draft) fail("acts.questKeys 不能为空");
            for (String member : members) {
                if (!boundQuests.add(member)) fail("任务不能同时绑定多个幕");
                for (JsonNode quest : game.path("quests")) if (member.equals(quest.path("questKey").asText()) && !act.path("actKey").asText().equals(quest.path("actKey").asText())) fail("任务actKey与幕清单不一致");
            }
            int exclusiveCapacity = 0, extraCapacity = 0;
            Set<String> exclusiveGroups = new HashSet<>(), extraSlots = new HashSet<>();
            for (JsonNode quest : game.path("quests")) if (members.contains(quest.path("questKey").asText()) && !quest.path("extraOnly").asBoolean()) {
                if (!quest.has("exclusiveGroup") || exclusiveGroups.add(quest.path("exclusiveGroup").asText())) exclusiveCapacity++;
                if (!quest.has("extraSlot") || extraSlots.add(quest.path("extraSlot").asText())) extraCapacity++;
            }
            JsonNode rule = act.path("completionRule"); fields(rule, "type", "quantity"); option(rule, "type", "ALL", "ANY_N");
            if ("ANY_N".equals(rule.path("type").asText())) number(rule, "quantity", 1, emptyDraft ? 500 : members.size());
            else if (rule.has("quantity")) fail("ALL 不适用quantity");
            int required = "ALL".equals(rule.path("type").asText()) ? exclusiveCapacity : rule.path("quantity").asInt();
            if (!emptyDraft && required > ("ANY_N".equals(rule.path("type").asText()) ? exclusiveCapacity : Math.min(exclusiveCapacity, extraCapacity))) fail("acts.completionRule 与互斥/额外槽冲突，无法完成本幕");
        }
        if (!boundQuests.equals(quests)) fail("quests 必须在相应幕的questKeys中声明");
        for (JsonNode facility : rows(game, "facilities", 100)) {
            fields(facility, "facilityKey", "actKey", "scope", "targetScope", "settlementSlot", "limit", "hpCost", "luckCost", "statCosts", "resourceCosts", "actionCost", "costOptions", "hpDelta", "statChanges", "when", "effects", "grants", "arrivalLocationKey", "operationNodeId", "worldEffects", "publicOnce");
            if(facility.has("operationNodeId"))number(facility,"operationNodeId",1,Long.MAX_VALUE);
            if(facility.has("arrivalLocationKey"))key(facility,"arrivalLocationKey");
            ref(facility, "actKey", acts); key(facility, "settlementSlot"); number(facility, "limit", 1, 99);
            option(facility, "scope", "OWNER", "WORLD"); option(facility, "targetScope", "OWNER", "PARTICIPANTS", "WORLD");
            if (!world && (!"OWNER".equals(facility.path("scope").asText()) || !"OWNER".equals(facility.path("targetScope").asText()))) fail("共享设施范围只适用于WORLD_SHARED");
            Set<String> slots = "WORLD".equals(facility.path("scope").asText()) ? worldSlots : ownerSlots;
            if (!slots.add(facility.path("settlementSlot").asText())) fail("设施结算槽重复或与任务冲突");
            costs(facility, resources); amounts(facility, "grants", items); conditions(facility.path("when"), items);
            if (facility.has("hpDelta")) number(facility, "hpDelta", -999, 999);
            Set<String> changes = new HashSet<>();
            for (JsonNode change : rows(facility, "statChanges", 32)) {
                fields(change, "statKey", "delta"); ThemeRuntimeConfig.counterKey(change, "statKey"); number(change, "delta", -999, 999);
                if (!changes.add(change.path("statKey").asText())) fail("设施数值变化重复");
            }
            // 继续用既有主题事件保护：这里也不允许effects写sys.hp。
            ObjectNode eventRoot = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            ObjectNode event = eventRoot.putArray("events").addObject(); event.put("id", "facility"); event.put("content", "facility");
            String adapter="tpl_node_"+facility.path("operationNodeId").asLong();
            if(adapter.equals(facility.path("facilityKey").asText()) && adapter.equals(facility.path("settlementSlot").asText()))TemplateEffects.validateToolEffects(facility.path("effects"), items);
            else { if(facility.has("effects"))event.set("effects",facility.get("effects"));ThemeRuntimeConfig.events(eventRoot,items); }
            if(facility.has("worldEffects")) {
                if(!world)fail("worldEffects 仅用于公共世界");
                for(JsonNode effect:rows(facility,"worldEffects",20))if(!effect.path("var").asText().startsWith("clue."))fail("公共设施事实只允许显式clue状态");
                event.set("effects",facility.get("worldEffects"));ThemeRuntimeConfig.events(eventRoot,items);
            }
        }
        for (JsonNode plan : rows(game, "plans", 30)) {
            fields(plan, "endingKey", "title", "summary", "materialCost", "when", "operationNodeId", "terminal", "storyEndingId", "endingChapterId");
            if(plan.has("storyEndingId")) { text(plan,"storyEndingId",64); number(plan,"endingChapterId",1,Long.MAX_VALUE); }
            if(plan.has("terminal") && !plan.path("terminal").isBoolean())fail("方案terminal须为开关");
            text(plan, "title", 80); if (plan.has("summary")) text(plan, "summary", 2000);
            amounts(plan, "materialCost", transferable); conditions(plan.path("when"), items); number(plan, "operationNodeId", 1, Long.MAX_VALUE);
            if(!world)fail("公共提案仅用于WORLD_SHARED");
        }
        Set<String> storyCodes=new HashSet<>();int storyFallbacks=0;
        for(JsonNode ending:rows(game,"storyEndings",MAX_STORY_ENDINGS)) {
            fields(ending,"code","title","summary","chapterId","fallback");text(ending,"code",64);text(ending,"title",80);if(ending.has("summary"))text(ending,"summary",2000);
            if(!storyCodes.add(ending.path("code").asText()))fail("剧情结局标识重复");
            if(ending.has("chapterId"))number(ending,"chapterId",1,Long.MAX_VALUE);
            if(!ending.path("fallback").isBoolean())fail("剧情结局fallback须为开关");if(ending.path("fallback").asBoolean())storyFallbacks++;
        }
        if(!EndingFallbackRule.hasSingleFallback(game.has("storyEndings"),storyFallbacks))fail("剧情结局须有唯一默认收尾");
        for(JsonNode quest:game.path("quests"))if(quest.has("facilityKey")) {
            for(JsonNode facility:game.path("facilities"))if(quest.path("facilityKey").asText().equals(facility.path("facilityKey").asText())) {
                if(quest.has("costOptions") && facility.has("costOptions"))fail("任务与绑定设施不能同时配置任选费用");
                if(!quest.path("actKey").equals(facility.path("actKey")))fail("任务与所绑定设施必须属于同一幕");
                if("PARTICIPANTS".equals(facility.path("targetScope").asText()))fail("参与集合设施请独立配置，不能绑定单队任务");
            }
        }
    }

    private static void survival(JsonNode survival) {
        if (survival.isMissingNode()) return;
        fields(survival, "zeroHpPolicy", "zeroChallenge", "zeroWait", "zeroSteps", "zeroMessage", "actionLimitPolicy", "chapterRecovery", "consumeOnNoEffect", "recoveries", "rations");
        if (survival.has("zeroMessage")) text(survival, "zeroMessage", 2000);
        if(survival.has("consumeOnNoEffect") && !survival.path("consumeOnNoEffect").isBoolean())fail("consumeOnNoEffect 须为开关");
        ids(survival,"recoveries","recoveryKey",20);
        for(JsonNode row:rows(survival,"recoveries",20)) {
            fields(row,"recoveryKey","kind","limit","hpDelta","hpFloor","actKeys");option(row,"kind","REST","RESCUE");number(row,"limit",1,99);
            if("REST".equals(row.path("kind").asText())) {number(row,"hpDelta",1,999);if(row.has("hpFloor"))fail("休整不适用hpFloor");}
            else {number(row,"hpFloor",1,999);if(row.has("hpDelta"))fail("救助不适用hpDelta");}
            Set<String> seen=new HashSet<>();for(JsonNode act:rows(row,"actKeys",200))if(!act.isTextual() || !act.asText().matches("[a-z][a-z0-9_]{0,31}") || !seen.add(act.asText()))fail("恢复幕引用无效");
        }
        ids(survival,"rations","rationKey",200);
        for(JsonNode row:rows(survival,"rations",200)) {
            fields(row,"rationKey","actKey","itemId","quantity","fallbackHpCost","fallback");key(row,"actKey");key(row,"itemId");number(row,"quantity",1,999);
            if(row.has("fallbackHpCost"))number(row,"fallbackHpCost",0,999);
            if(row.has("fallback")) {
                JsonNode fallback=row.path("fallback");fields(fallback,"statKey","amount");number(fallback,"amount",0,999);
                String stat=fallback.path("statKey").asText();
                if(!stat.matches("counter\\.[a-z][a-z0-9_]{0,47}"))fail("章末配给不足时扣自定义数值请选择已定义的数值；扣生命请用 fallbackHpCost");
                if(row.path("fallbackHpCost").asInt()>0)fail("章末配给不足时的扣减只能设置一处");
            }
        }
        if (survival.has("zeroHpPolicy")) option(survival, "zeroHpPolicy", "BLOCK_PAID", "BLOCK_HP_COST", "LEGACY_END", "CHALLENGE", "THEME_FAIL", "WAIT", "STEPS");
        if ("CHALLENGE".equals(survival.path("zeroHpPolicy").asText())) {
            JsonNode challenge = survival.path("zeroChallenge");
            fields(challenge, "version", "challengeNodeId", "gameplayKey", "requiredCompletions", "withinSeconds", "thenPolicy", "alternativeNodeIds", "restoreTo", "limit");
            if (challenge.has("version")) {
                number(challenge, "version", 2, 2);
                option(challenge, "gameplayKey", "pricePair", "blindTaste", "check", "diceRoll", "coinFlip", "random", "estimate", "qa", "sort", "match", "classify", "typeIn", "ballShake", "reaction", "quietHold", "shout", "compass", "stopwatch", "countdown");
                number(challenge, "requiredCompletions", 1, 99); number(challenge, "withinSeconds", 1, 86400);
                option(challenge, "thenPolicy", "THEME_FAIL", "BLOCK_HP_COST");
            } else for (String field : Arrays.asList("gameplayKey", "requiredCompletions", "withinSeconds", "thenPolicy"))
                if (challenge.has(field)) fail("zeroChallenge 扩展字段需要 version=2");
            number(challenge, "challengeNodeId", 1, Long.MAX_VALUE);
            number(challenge, "restoreTo", 1, 20);
            number(challenge, "limit", 1, 99);
            if (challenge.has("alternativeNodeIds") && !challenge.path("alternativeNodeIds").isArray()) fail("zeroChallenge.alternativeNodeIds 须为数组");
            Set<Long> seen = new HashSet<>();
            seen.add(challenge.path("challengeNodeId").asLong());
            for (JsonNode alternative : rows(challenge, "alternativeNodeIds", 3)) {
                if (!alternative.isIntegralNumber() || !alternative.canConvertToLong() || alternative.asLong() < 1 || !seen.add(alternative.asLong())) fail("zeroChallenge.alternativeNodeIds 无效或重复");
            }
        } else if (survival.has("zeroChallenge")) fail("zeroChallenge 只适用于 CHALLENGE");
        for (String policy : Arrays.asList("WAIT", "STEPS")) {
            String field = "WAIT".equals(policy) ? "zeroWait" : "zeroSteps";
            if (policy.equals(survival.path("zeroHpPolicy").asText())) {
                JsonNode recovery = survival.path(field);
                String quantity = "WAIT".equals(policy) ? "minutes" : "steps";
                fields(recovery, "version", quantity, "restoreTo", "limit", "thenPolicy");
                number(recovery, "version", 1, 1); number(recovery, quantity, 1, "WAIT".equals(policy) ? 10080 : 100000);
                number(recovery, "restoreTo", 1, 20); number(recovery, "limit", 1, 99);
                option(recovery, "thenPolicy", "THEME_FAIL", "BLOCK_HP_COST");
            } else if (survival.has(field)) fail(field + " 与 zeroHpPolicy 不匹配");
        }
        if (survival.has("actionLimitPolicy")) option(survival, "actionLimitPolicy", "BLOCK_OVERFLOW", "LEGACY_END");
        if (survival.has("chapterRecovery") && (!survival.get("chapterRecovery").isBoolean() || survival.get("chapterRecovery").asBoolean())) fail("gameplayRuntime.survival.chapterRecovery 首版只支持false");
    }
    private static void costs(JsonNode node, Set<String> items) {
        costOptions(node, items);
        for (String field : Arrays.asList("hpCost", "luckCost", "actionCost")) if (node.has(field)) number(node, field, 0, 999);
        ThemeRuntimeConfig.statCosts(node); amounts(node, "resourceCosts", items);
    }
    static void costOptions(JsonNode node, Set<String> resources) {
        if (!node.has("costOptions")) return;
        for (String field : Arrays.asList("hpCost", "luckCost", "actionCost", "statCosts", "resourceCosts"))
            if (node.has(field)) fail("任选费用与固定费用不能同时配置");
        List<JsonNode> options = rows(node, "costOptions", 4);
        if (options.size() < 2) fail("costOptions 须为 2 至 4 组");
        for (JsonNode option : options) {
            fields(option, "label", "hpCost", "luckCost", "actionCost", "statCosts", "resourceCosts");
            if (option.has("label") && (!option.path("label").isTextual() || option.path("label").asText().length() > 20)) fail("costOptions.label 文本不合法");
            boolean positive = false;
            for (String field : Arrays.asList("hpCost", "luckCost", "actionCost")) if (option.has(field)) {
                number(option, field, 0, 999);
                positive |= option.path(field).asInt() > 0;
            }
            ThemeRuntimeConfig.statCosts(option);
            for (JsonNode stat : option.path("statCosts")) positive |= stat.path("amount").asInt() > 0;
            amounts(option, "resourceCosts", resources);
            positive |= option.path("resourceCosts").size() > 0;
            if (!positive) fail("costOptions 每组至少一项正数费用");
        }
    }
    /** W2 发布/运行闸：校验 survival.zeroChallenge 的节点均属于本主题。 */
    public static void assertChallengeNodes(JsonNode survival, Set<Long> topicNodeIds) {
        JsonNode challenge = survival.path("zeroChallenge");
        if (!"CHALLENGE".equals(survival.path("zeroHpPolicy").asText())) return;
        if (!topicNodeIds.contains(challenge.path("challengeNodeId").asLong())) fail("zeroChallenge.challengeNodeId 不属于本主题");
        for (JsonNode id : challenge.path("alternativeNodeIds"))
            if (!topicNodeIds.contains(id.asLong())) fail("zeroChallenge.alternativeNodeIds 不属于本主题");
    }
    /** W2 冻结节点闸：传入以节点 ID 为键的 nodeConfigs；体能/传感器主挑战须有服务端题组备选。 */
    public static void assertChallengeAlternative(JsonNode survival, JsonNode nodeConfigs) {
        if (!"CHALLENGE".equals(survival.path("zeroHpPolicy").asText())) return;
        if (nodeConfigs == null || !nodeConfigs.isObject()) fail("zeroChallenge 节点配置不存在");
        JsonNode challenge = survival.path("zeroChallenge");
        JsonNode primary = nodeConfigs.path(challenge.path("challengeNodeId").asText());
        if (!primary.isObject()) fail("zeroChallenge 主挑战节点配置不存在");
        if (challenge.path("version").asInt() == 2 && !primary.path(challenge.path("gameplayKey").asText()).path("enabled").asBoolean())
            fail("zeroChallenge.gameplayKey 与主挑战节点不匹配");
        if(challenge.path("version").asInt()==2 && "check".equals(challenge.path("gameplayKey").asText()) && primary.path("outcomeRules").path("version").asInt()!=1)fail("zeroChallenge check 须启用 outcomeRules.version=1");
        boolean physical = false;
        for (String kind : Arrays.asList("ballShake", "shout", "steps", "compass", "quietHold", "reaction", "blindTaste", "hiddenObject", "coinFlip", "scan", "countdown", "stopwatch"))
            physical |= primary.path(kind).path("enabled").asBoolean() && (challenge.path("version").asInt()!=2 || Arrays.asList("ballShake","reaction","quietHold","compass","shout","countdown","stopwatch").contains(kind) && kind.equals(challenge.path("gameplayKey").asText()));
        if (!physical) return;
        for (JsonNode id : challenge.path("alternativeNodeIds")) {
            JsonNode alternative = nodeConfigs.path(id.asText());
            for (String kind : Arrays.asList("qa", "sort", "match", "classify"))
                if (alternative.path(kind).path("enabled").asBoolean()) return;
        }
        fail("zeroChallenge 体能或传感器主挑战须配置至少一个服务端题组备选节点");
    }
    private static void amounts(JsonNode node, String field, Set<String> items) {
        Set<String> seen = new HashSet<>();
        for (JsonNode row : rows(node, field, 200)) {
            fields(row, "itemId", "quantity"); if (items == null) key(row, "itemId"); else ref(row, "itemId", items); number(row, "quantity", 1, 999);
            if (!seen.add(row.path("itemId").asText())) fail(field + " 物资重复");
        }
    }
    private static void conditions(JsonNode node, Set<String> items) { ThemeRuntimeConfig.conditions(node, items); }
    private static List<JsonNode> rows(JsonNode node, String field, int limit) { return ThemeRuntimeConfig.rows(node, field, limit); }
    private static Set<String> ids(JsonNode node, String field, String key, int limit) {
        Set<String> ids = new HashSet<>();
        for (JsonNode row : rows(node, field, limit)) { key(row, key); if (!ids.add(row.path(key).asText())) fail(field + "." + key + " 重复"); }
        return ids;
    }
    private static List<String> stringRefs(JsonNode node, String field, Set<String> valid) {
        List<String> out = new ArrayList<>();
        for (JsonNode value : rows(node, field, 500)) {
            if (!value.isTextual() || !valid.contains(value.asText()) || out.contains(value.asText())) fail(field + " 引用无效或重复");
            out.add(value.asText());
        }
        return out;
    }
    private static void visit(String key, Map<String,List<String>> dependencies, Set<String> active, Set<String> done) {
        if (done.contains(key)) return;
        if (!active.add(key)) fail("quests.prerequisites 不能形成循环");
        for (String prior : dependencies.get(key)) visit(prior, dependencies, active, done);
        active.remove(key); done.add(key);
    }
    private static void fields(JsonNode node, String... fields) {
        if (!node.isObject()) fail("gameplayRuntime 条目须为对象");
        Set<String> allowed = ThemeRuntimeConfig.set(fields);
        node.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) fail("gameplayRuntime 不支持字段：" + field); });
    }
    private static void key(JsonNode node, String field) {
        if (!node.path(field).isTextual() || !node.path(field).asText().matches("[a-z][a-z0-9_]{0,31}")) fail(field + " 标识不合法");
    }
    private static void text(JsonNode node, String field, int max) {
        if (!node.path(field).isTextual() || node.path(field).asText().trim().isEmpty() || node.path(field).asText().length() > max) fail(field + " 文本不合法");
    }
    private static void number(JsonNode node, String field, long min, long max) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < min || value.longValue() > max) fail(field + " 整数或范围不合法");
    }
    private static void ref(JsonNode node, String field, Set<String> ids) {
        if (!node.path(field).isTextual() || !ids.contains(node.path(field).asText())) fail(field + " 引用不存在");
    }
    private static void option(JsonNode node, String field, String... options) {
        if (!node.path(field).isTextual() || !ThemeRuntimeConfig.set(options).contains(node.path(field).asText())) fail(field + " 选项不支持");
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
