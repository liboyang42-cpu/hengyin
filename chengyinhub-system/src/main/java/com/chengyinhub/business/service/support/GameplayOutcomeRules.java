package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.BiConsumer;

/** 明确注册结果来源；客户端只写配置，不决定运行结果。 */
public final class GameplayOutcomeRules {
    private GameplayOutcomeRules() { }
    static final List<String> MAIN_GAMEPLAYS = Collections.unmodifiableList(Arrays.asList("qa", "pricePair", "blindTaste", "predict", "photoCheck", "coinFlip", "diceRoll", "random", "sort", "match", "classify", "estimate", "hiddenObject", "profile", "album", "note", "dailySign", "steps", "reaction", "ballShake", "quietHold", "compass", "shout", "countdown", "stopwatch", "typeIn", "check"));

    public static ObjectNode normalize(JsonNode source, JsonNode slots) {
        ThemeRuntimeConfig.fields(source, ThemeRuntimeConfig.set("version", "pass", "fail"));
        ThemeRuntimeConfig.integer(source, "version", 1, 1);
        Map<String, String> kinds = slots != null && !slots.isMissingNode() ? TemplateResourceSlots.declarations(slots, "模板") : null;
        after(source.path("pass"), kinds);
        if (source.has("fail")) after(source.get("fail"), kinds);
        return ((ObjectNode) source).deepCopy();
    }

    public static void validateBlock(JsonNode block) {
        if (!block.has("outcomeRules")) return;
        if (block.path("interactionTools").path("plans").size() > 0) assertPlan(block);
        JsonNode slots = block.path("interactionRules").get("resourceSlots");
        normalize(block.get("outcomeRules"), slots);
        if (block.path("outcomeRules").has("fail") && "PARTICIPANTS".equals(block.path("interactionRules").path("targetScope").asText())) fail("interactionRules.targetScope: FAIL settlement has no frozen participant selection");
        Map<String, String> kinds = slots == null ? null : TemplateResourceSlots.declarations(slots, "模板");
        for(JsonNode step:block.path("branch").path("steps"))for(JsonNode option:step.path("options"))if(option.has("after"))fail("结果反馈分支不能再次配置 after");
        List<String> mains = new ArrayList<>();
        for (String kit : MAIN_GAMEPLAYS)
            if (block.path(kit).path("enabled").asBoolean()) mains.add(kit);
        if (mains.size() != 1) fail("须且仅须一个主玩法，扫码和分支不是主玩法");
        boolean once = Arrays.asList("profile", "album", "note", "dailySign").contains(mains.get(0));
        if (once == block.path("outcomeRules").has("fail")) fail(once ? "完成型玩法不配置 fail" : "须配置 fail");
        Set<String> steps = new HashSet<>();
        for (JsonNode step : block.path("branch").path("steps")) steps.add(step.path("id").asText());
        final boolean[] costs = {false};
        visit(block, (path, row) -> {
            after(row, kinds);
            costs[0] |= !row.path("costs").isEmpty();
            if (row.has("nextStepId") && !steps.contains(row.path("nextStepId").asText())) fail(path + " nextStepId 不属于本节点 branch.steps");
            if(path.startsWith("random.items") && block.path("random").path("drawCount").asInt()>1)fail("多张抽卡请用顶层通过/没通过之后");
            if (path.startsWith("qa.options") && block.path("qa").path("multi").asBoolean()) fail("多选题仅使用顶层 pass/fail after");
        });
        if (costs[0]) {
            JsonNode legacy = block.path("interactionRules");
            boolean paid = legacy.path("costOptions").size() > 0 || legacy.path("resourceCosts").size() > 0;
            for (String field : Arrays.asList("hpCost", "luckCost", "actionCost")) paid |= legacy.path(field).asInt() > 0;
            for (JsonNode cost : legacy.path("statCosts")) paid |= cost.path("amount").asInt() > 0;
            if (paid) fail("结果费用不能与旧 interactionRules 费用叠加，请明确迁移");
        }
    }

    public static void assertBindings(JsonNode theme,JsonNode nodes) {
        if(theme==null || !theme.isObject())return;
        for (JsonNode plan : theme.path("gameplayRuntime").path("plans"))
            if (nodes.path(plan.path("operationNodeId").asText()).path("outcomeRules").path("version").asInt() == 1)
                {assertPlan(nodes.path(plan.path("operationNodeId").asText()));if(paid(plan))fail("PLAN 结果费用不能叠加旧数值费用");}
        for(JsonNode quest:theme.path("gameplayRuntime").path("quests")) {
            JsonNode block=nodes.path(quest.path("nodeId").asText());if(!block.has("outcomeRules"))continue;
            final boolean[] hasCosts={false};visit(block,(path,after)->hasCosts[0]|=!after.path("costs").isEmpty());
            if(hasCosts[0] && paid(quest))fail("gameplayRuntime.quests: outcome costs conflict with existing task costs");
            if(quest.has("facilityKey"))for(JsonNode facility:theme.path("gameplayRuntime").path("facilities"))if(quest.path("facilityKey").equals(facility.path("facilityKey")))assertFacility(block,facility,hasCosts[0]);
        }
        for(JsonNode facility:theme.path("gameplayRuntime").path("facilities"))if(facility.has("operationNodeId")) {
            JsonNode block=nodes.path(facility.path("operationNodeId").asText());if(!block.has("outcomeRules"))continue;
            final boolean[] hasCosts={false};visit(block,(path,after)->hasCosts[0]|=!after.path("costs").isEmpty());assertFacility(block,facility,hasCosts[0]);
        }
    }
    private static void assertPlan(JsonNode block) {
        if(!"OWNER".equals(block.path("interactionRules").path("targetScope").asText("OWNER")))fail("PLAN after targetScope 仅支持 OWNER");
        if(paid(block.path("interactionRules")))fail("PLAN 结果费用不能叠加旧数值费用");
    }
    private static void assertFacility(JsonNode block,JsonNode facility,boolean hasCosts) {
        if(hasCosts && paid(facility))fail("gameplayRuntime.facilities: outcome costs conflict with existing facility costs");
        if(block.path("outcomeRules").has("fail") && "PARTICIPANTS".equals(facility.path("targetScope").asText()))fail("gameplayRuntime.facilities.targetScope: FAIL settlement has no frozen participant selection");
    }
    private static boolean paid(JsonNode rule) {
        for(String field:Arrays.asList("hpCost","luckCost","actionCost"))if(rule.path(field).asInt()>0)return true;
        return !rule.path("statCosts").isEmpty() || !rule.path("resourceCosts").isEmpty() || !rule.path("costOptions").isEmpty();
    }

    public static void visit(JsonNode block, BiConsumer<String, JsonNode> visitor) {
        if (!block.has("outcomeRules")) return;
        for (String outcome : Arrays.asList("pass", "fail")) if (block.path("outcomeRules").has(outcome))
            visitor.accept("outcomeRules." + outcome, block.path("outcomeRules").get(outcome));
        for (String kit : Arrays.asList("qa", "blindTaste", "predict"))
            for (JsonNode option : block.path(kit).path("options")) if (option.has("after")) visitor.accept(kit + ".options[].after", option.get("after"));
        for (String kit : Arrays.asList("pricePair", "random"))
            for (JsonNode item : block.path(kit).path("items")) if (item.has("after")) visitor.accept(kit + ".items[].after", item.get("after"));
        for (JsonNode step : block.path("branch").path("steps")) for (JsonNode option : step.path("options"))
            if (option.has("after")) visitor.accept("branch.steps[].options[].after", option.get("after"));
        for (String side : Arrays.asList("heads", "tails")) if (block.path("coinFlip").path(side).has("after"))
            visitor.accept("coinFlip." + side + ".after", block.path("coinFlip").path(side).get("after"));
        for (JsonNode face : block.path("diceRoll").path("faces")) if (face.has("after")) visitor.accept("diceRoll.faces[].after", face.get("after"));
    }

    static void restore(JsonNode authored, JsonNode normalized) {
        List<JsonNode> originals = new ArrayList<>(); visit(authored, (path, row) -> originals.add(row));
        int[] index = {0};
        visit(normalized, (path, row) -> { ((ObjectNode) row).removeAll(); ((ObjectNode) row).setAll((ObjectNode) originals.get(index[0]++).deepCopy()); });
    }

    public static JsonNode select(JsonNode block, JsonNode decision) {
        normalize(block.path("outcomeRules"), null);
        String outcome = decision.path("outcome").asText();
        if (!Arrays.asList("PASS", "FAIL").contains(outcome)) fail("可信结果须为 PASS 或 FAIL");
        JsonNode selected = block.path("outcomeRules").path("PASS".equals(outcome) ? "pass" : "fail");
        JsonNode selection = decision.path("selection");
        String kit = selection.path("kit").asText();
        JsonNode row = null;
        if ("coinFlip".equals(kit)) row = block.path(kit).path(selection.path("side").asText());
        else if ("diceRoll".equals(kit) && !"d20".equals(block.path(kit).path("mode").asText())) {
            int face = selection.path("face").asInt();
            if (face > 0 && face <= block.path(kit).path("faces").size()) row = block.path(kit).path("faces").get(face - 1);
        } else if (Arrays.asList("qa", "pricePair", "blindTaste", "predict", "random").contains(kit)) {
            String id = selection.path("optionId").asText();
            String field = Arrays.asList("pricePair", "random").contains(kit) ? "items" : "options";
            String identity = Arrays.asList("blindTaste", "predict").contains(kit) ? "key" : "id";
            for (JsonNode candidate : block.path(kit).path(field)) if (id.equals(candidate.path(identity).asText())) row = candidate;
        } else if ("branch".equals(kit)) {
            for (JsonNode step : block.path("branch").path("steps")) if (selection.path("stepId").asText().equals(step.path("id").asText()))
                for (JsonNode option : step.path("options")) if (selection.path("optionId").asText().equals(option.path("id").asText())) row = option;
        }
        if (row != null && row.has("after")) selected = row.get("after");
        after(selected, null);
        return selected.deepCopy();
    }

    private static void after(JsonNode row, Map<String, String> kinds) {
        ThemeRuntimeConfig.fields(row, ThemeRuntimeConfig.set("reply", "images", "costs", "effects", "nextStepId"));
        images(row);
        for (String text : Arrays.asList("reply", "nextStepId")) if (row.has(text)) {
            if (!row.path(text).isTextual() || row.path(text).asText().length() > ("reply".equals(text) ? 2000 : 64)) fail(text + " 文本不合法");
        }
        if(row.has("nextStepId") && !row.path("nextStepId").asText().matches("[A-Za-z0-9_-]{1,64}"))fail("nextStepId 文本不合法");
        Set<String> seen = new HashSet<>();
        for (JsonNode cost : ThemeRuntimeConfig.rows(row, "costs", 20)) {
            ThemeRuntimeConfig.fields(cost, ThemeRuntimeConfig.set("statKey", "amount"));
            target(cost.path("statKey"), kinds); ThemeRuntimeConfig.integer(cost, "amount", 1, 999);
            if (!seen.add(cost.path("statKey").asText())) fail("结果费用目标重复");
        }
        for (JsonNode effect : ThemeRuntimeConfig.rows(row, "effects", 20)) {
            ThemeRuntimeConfig.fields(effect, ThemeRuntimeConfig.set("var", "op", "value"));
            target(effect.path("var"), kinds); ThemeRuntimeConfig.enumeration(effect, "op", "INC", "FLOOR");
            ThemeRuntimeConfig.integer(effect, "value", 1, 999);
            if ("sys.luck".equals(effect.path("var").asText()) && (!"INC".equals(effect.path("op").asText()) || effect.path("value").asInt() > 3)) fail("幸运仅支持 INC 1至3");
        }
    }

    /** 成功/失败反馈配图：最多 3 张，https、站内路径或 media-asset 引用，与其它玩法配图同一口径。 */
    private static void images(JsonNode row) {
        if (!row.has("images")) return;
        JsonNode images = row.get("images");
        if (!images.isArray() || images.size() > 3) fail("反馈图片最多 3 张");
        for (JsonNode image : images) {
            String value = image.isTextual() ? image.asText().trim() : "";
            boolean localPath = value.startsWith("/") && !value.startsWith("//") && value.indexOf('\\') < 0;
            if (value.isEmpty() || value.length() > 512 || (!localPath && !value.startsWith("https://") && !value.matches("media-asset:[1-9][0-9]{0,18}"))) fail("反馈图片地址必须是 https 链接或站内路径");
        }
    }

    private static void target(JsonNode key, Map<String, String> kinds) {
        if (!key.isTextual()) fail("结果数值标识须为文字");
        if (kinds != null) {
            if (!"VALUE".equals(kinds.get(key.asText()))) fail("结果仅允许已声明 VALUE 槽：" + key.asText());
        } else if (!Arrays.asList("sys.hp", "sys.luck").contains(key.asText()) && !key.asText().matches("counter\\.[a-z][a-z0-9_]{0,47}")) fail("结果数值目标无效：" + key.asText());
    }
    private static void fail(String detail) { throw new IllegalArgumentException("玩法结果 " + detail); }
}
