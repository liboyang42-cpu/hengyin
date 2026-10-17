package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 发布闸与作者端共用的通用结算能力表；旧玩法的判定不等于通用结算证明。 */
public final class GameplaySupportMatrix {
    private static final ObjectNode MATRIX = JsonNodeFactory.instance.objectNode();
    private static final Map<String, JsonNode> TYPES = new LinkedHashMap<>();
    private static final List<String> GENERIC_ZERO_HP_POLICIES=Collections.unmodifiableList(Arrays.asList("BLOCK_HP_COST","CHALLENGE","BLOCK_PAID","THEME_FAIL","WAIT","STEPS"));
    private static final List<String> LEGACY_ZERO_HP_POLICIES=Collections.unmodifiableList(Arrays.asList("BLOCK_HP_COST","CHALLENGE","LEGACY_END","BLOCK_PAID"));
    static {
        MATRIX.put("schemaVersion", 1);
        ObjectNode policies=MATRIX.putObject("zeroHpPolicies");
        GENERIC_ZERO_HP_POLICIES.forEach(policies.putArray("generic")::add);
        LEGACY_ZERO_HP_POLICIES.forEach(policies.putArray("legacy")::add);
        MATRIX.putObject("participantSelection").put("toolKind","FACILITY").put("targetScope","PARTICIPANTS")
                .put("payerRequired",true).put("runtimeScope","TEAM").put("worldMode","WORLD_SHARED");
        add("album", "album", null, "PRESENTATION", false, false);
        add("qaText", "qa", "TYPE", "PROOF", true, false);
        add("qaPick", "qa", "PICK", "PROOF", true, false);
        add("qaShot", "qa", "SHOT", "NO_BRIDGE", false, false);
        add("branch", "branch", null, "NO_BRIDGE", false, false);
        for (String key : Arrays.asList("sort", "match", "classify")) add(key, key, null, "PROOF", true, false);
        add("estimate", "estimate", null, "NO_BRIDGE", false, false);
        add("pricePair", "pricePair", null, "NO_BRIDGE", false, false);
        add("hidden", "hiddenObject", null, "NO_BRIDGE", false, false);
        for (String key : Arrays.asList("predict", "random", "profile", "note")) add(key, key, null, "NO_BRIDGE", false, false);
        add("objectCard", "photoCheck", "CARD", "NO_BRIDGE", false, false);
        add("bingo", "", null, "PRESENTATION", false, false);
        add("steps", "steps", null, "NO_BRIDGE", true, false);
        add("react", "reaction", null, "NO_BRIDGE", true, false);
        add("shake", "ballShake", null, "NO_BRIDGE", true, false);
        add("quiet", "quietHold", null, "NO_BRIDGE", true, false);
        for (String key : Arrays.asList("compass", "shout", "countdown", "stopwatch")) add(key, key, null, "NO_BRIDGE", true, false);
        add("photoCheck", "photoCheck", null, "NO_BRIDGE", false, false);
        add("check", "check", null, "NO_BRIDGE", false, false);
        add("d20", "diceRoll", "d20", "NO_BRIDGE", false, false);
        add("typeIn", "typeIn", null, "NO_BRIDGE", true, false);
        add("coin", "coinFlip", null, "NO_BRIDGE", false, false);
        add("dice", "diceRoll", "d6", "NO_BRIDGE", false, false);
        add("scan", "scan", null, "PROOF", false, false);
        for (String key : Arrays.asList("timer", "leaderboard", "multiplayer", "timeWindow", "blindTaste", "silentOrder", "diyName", "musicCorner", "dailySign", "slowTask"))
            add(key, key, null, "PRESENTATION", false, true);
        for(String key:Arrays.asList("qaText","qaPick","sort","match","classify","estimate","pricePair","random","blindTaste","react","shake","quiet","compass","shout","countdown","stopwatch","typeIn","check","d20","coin","dice","profile","note","dailySign","hidden","steps","qaShot","photoCheck","objectCard","album","predict"))((ObjectNode)TYPES.get(key)).put("r5CompletionProof",true);
        MATRIX.putObject("outcomeRules").put("version",1).put("nextStepId","SETTLED_FEEDBACK").put("failParticipants","UNSUPPORTED").put("vote","TEAM_PASS_OWNER_AFTER");
        MATRIX.putObject("legacyProofs").put("GPS", 5).put("qaText", 1).put("qaPick", 3).put("scan", 4);
        ObjectNode constraints = MATRIX.putObject("constraints");
        constraints.put("PROOF_REQUIRED", "附加项不能独立产生完成证明；branch/random 可随可信主玩法呈现，但不能独立结算");
        constraints.put("OWNER_OR_TEAM", "个人和独立队伍仅 OWNER 范围和目标；WORLD/PARTICIPANTS 仅 TEAM/WORLD_SHARED");
        constraints.put("WORLD_SHARED_ONLY", "交易与投票仅 TEAM/WORLD_SHARED；投票不能叠加节点费用、次数或独立奖励");
        constraints.put("CHALLENGE_ONLY", "传感器仅归零挑战且须有客观题备选；挑战不收费用、不发普通奖励、不推进章节");
        constraints.put("FREE_RETRY", "普通 qa TYPE/PICK 的 maxTries、sort 的 maxAttempts 必须为 0");
    }
    private GameplaySupportMatrix() { }

    private static void add(String key, String section, String mode, String category, boolean challenge, boolean addon) {
        ObjectNode row = MATRIX.withArray(addon ? "addons" : "types").addObject().put("key", key).put("section", section).put("category", category);
        if (mode != null) row.put("mode", mode);
        boolean proof = "PROOF".equals(category);
        row.put("r5CompletionProof",false);
        row.put("completionProof", proof).put("requiresProof", addon);
        row.put("effectSlotSupport",true);
        ArrayNode effectScopes=row.putArray("effectSlotScopes").add("OWNER");
        if(proof)effectScopes.add("WORLD").add("PARTICIPANTS");
        ObjectNode tools = row.putObject("tools");
        String normal = addon ? "PROOF_REQUIRED" : proof ? "OWNER_OR_TEAM" : "UNSUPPORTED";
        for (String tool : Arrays.asList("costs", "costOptions", "limitsScope", "targetScope", "resourceSlots")) tools.put(tool, normal);
        tools.put("trade", addon ? "PROOF_REQUIRED" : proof ? "WORLD_SHARED_ONLY" : "UNSUPPORTED");
        tools.put("vote", addon ? "PROOF_REQUIRED" : proof ? "WORLD_SHARED_ONLY" : "UNSUPPORTED");
        tools.put("decidesEnding", tools.path("vote").asText());
        tools.put("zeroChallenge", addon ? "PROOF_REQUIRED" : challenge ? proof ? "SUPPORTED" : "CHALLENGE_ONLY" : "UNSUPPORTED");
        TYPES.put(key, row);
    }

    /** 返回副本，调用方不能修改发布策略。 */
    public static ObjectNode view() { return MATRIX.deepCopy(); }

    public static List<String> zeroHpPolicies(boolean generic) { return generic?GENERIC_ZERO_HP_POLICIES:LEGACY_ZERO_HP_POLICIES; }

    public static boolean addon(String section) {
        JsonNode row = TYPES.get(section);
        return row != null && row.path("requiresProof").asBoolean() || "branch".equals(section) || "random".equals(section);
    }

    public static boolean r5CompletionProof(JsonNode config) {
        if(config.path("outcomeRules").path("version").asInt()!=1)return false;
        for(JsonNode row:TYPES.values())if(row.path("r5CompletionProof").asBoolean() && config.path(row.path("section").asText()).path("enabled").asBoolean() && (!row.has("mode") || row.path("mode").asText().equals(config.path(row.path("section").asText()).path("mode").asText())))return true;
        return false;
    }
    public static String basicCompletionKind(JsonNode config, int method) {
        if(config.has("outcomeRules"))return null;
        Iterator<Map.Entry<String, JsonNode>> fields = config.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue().path("enabled").asBoolean() && !"scan".equals(field.getKey()) && !addon(field.getKey())) return null;
        }
        return method == 4 ? "SCAN" : method == 5 ? "GPS" : method == 1 || method == 3 ? "ANSWER" : null;
    }

    public static void validateNode(JsonNode theme, long nodeId, String nodeName, JsonNode config, int method) {
        validateNode(theme, nodeId, nodeName, config, method, false);
    }

    public static void validateNode(JsonNode theme, long nodeId, String nodeName, JsonNode config, int method, boolean draft) {
        JsonNode game = theme.path("gameplayRuntime");
        boolean challenge = GameplaySurvivalEngine.challengeNode(theme, nodeId);
        List<JsonNode> rules = new ArrayList<>();
        if (GameplayTemplateRules.hasSettlementRules(config)) rules.add(config.path("interactionRules"));
        boolean referenced = challenge || config.has("interactionTools");
        for (JsonNode quest : game.path("quests")) if (quest.path("nodeId").asLong() == nodeId) { referenced = true; rules.add(quest); }
        for (JsonNode facility : game.path("facilities")) if (facility.path("operationNodeId").asLong() == nodeId) { referenced = true; rules.add(facility); }
        for (JsonNode plan : game.path("plans")) if (plan.path("operationNodeId").asLong() == nodeId) referenced = true;
        if (!referenced && rules.isEmpty()) return;
        boolean r5=config.path("outcomeRules").path("version").asInt()==1 || challenge && game.path("survival").path("zeroChallenge").path("version").asInt()==2;
        List<String> enabled = new ArrayList<>();
        List<String> secondary = new ArrayList<>();
        config.fields().forEachRemaining(field -> {
            if (!field.getValue().path("enabled").asBoolean()) return;
            if (addon(field.getKey()) && !(r5 && TYPES.containsKey(field.getKey()) && TYPES.get(field.getKey()).path("r5CompletionProof").asBoolean())) secondary.add(field.getKey());
            else enabled.add(type(field.getKey(), field.getValue()));
        });
        if (enabled.isEmpty()) {
            String basic = challenge ? null : basicCompletionKind(config, method);
            enabled.add("SCAN".equals(basic) ? "scan" : "GPS".equals(basic) ? "GPS" : "ANSWER".equals(basic) ? method == 1 ? "qaText" : "qaPick" : secondary.isEmpty() ? "none" : secondary.get(0));
        }
        String tool = firstTool(rules, config.path("interactionTools"), challenge);
        if (challenge && game.path("survival").path("zeroChallenge").path("version").asInt()==2)
            GameplayOutcomeRules.visit(config, (path, after) -> {
                if (!after.path("costs").isEmpty() || !after.path("effects").isEmpty())
                    fail(nodeId, nodeName, game.path("survival").path("zeroChallenge").path("gameplayKey").asText(), path, "归零挑战不能配置普通结果费用或奖励");
            });
        for (String key : enabled) {
            JsonNode row = TYPES.get(key);
            boolean proof = "GPS".equals(key) || row != null && row.path(r5?"r5CompletionProof":"completionProof").asBoolean();
            if (challenge) {
                if (row == null || "UNSUPPORTED".equals(row.path("tools").path("zeroChallenge").asText()) && !(r5 && row.path("r5CompletionProof").asBoolean())) fail(nodeId, nodeName, key, "zeroChallenge", "没有归零挑战恢复桥");
                if (!rules.isEmpty() || config.has("interactionTools")) fail(nodeId, nodeName, key, tool, "归零挑战不能配置普通结算工具");
            } else if (!proof && !(draft && !game.path("survival").has("zeroChallenge") && row != null
                    && "CHALLENGE_ONLY".equals(row.path("tools").path("zeroChallenge").asText())))
                fail(nodeId, nodeName, key, tool, "尚无通用结算完成证明");
            if (!r5 && !challenge && ("qaText".equals(key) || "qaPick".equals(key)) && config.path("qa").path("maxTries").asInt() > 0) fail(nodeId, nodeName, key, "costs", "须允许免费重答 maxTries=0");
            if (!r5 && !challenge && "sort".equals(key) && config.path("sort").path("maxAttempts").asInt() > 0) fail(nodeId, nodeName, key, "costs", "须允许免费重答 maxAttempts=0");
            if ("scan".equals(key) && !challenge && method != 4) fail(nodeId, nodeName, key, tool, "扫码证明须使用 validationMethod=4");
            if (!"TEAM".equals(game.path("runtimeScope").asText()) || !"WORLD_SHARED".equals(game.path("worldMode").asText())) {
                for (JsonNode rule : rules) {
                    if (rule.has("scope") && !"OWNER".equals(rule.path("scope").asText())) fail(nodeId, nodeName, key, "limitsScope", "跨队范围仅支持 TEAM/WORLD_SHARED");
                    if (rule.has("targetScope") && !"OWNER".equals(rule.path("targetScope").asText())) fail(nodeId, nodeName, key, "targetScope", "跨队目标仅支持 TEAM/WORLD_SHARED");
                }
                JsonNode tools = config.path("interactionTools");
                if (tools.path("allowTrade").asBoolean()) fail(nodeId, nodeName, key, "trade", "仅支持 TEAM/WORLD_SHARED");
                if (tools.path("plans").size() > 0 || tools.has("votePolicy") || tools.path("decidesEnding").asBoolean()) fail(nodeId, nodeName, key, tools.path("decidesEnding").asBoolean() ? "decidesEnding" : "vote", "仅支持 TEAM/WORLD_SHARED");
                for (JsonNode plan : game.path("plans")) if (plan.path("operationNodeId").asLong() == nodeId) fail(nodeId, nodeName, key, "vote", "仅支持 TEAM/WORLD_SHARED");
            }
            try { GameplayTemplateRules.assertCompatible(config); }
            catch (IllegalArgumentException invalid) { fail(nodeId, nodeName, key, "vote", invalid.getMessage()); }
        }
    }

    public static void validateChallenge(JsonNode theme, JsonNode configs) {
        JsonNode challenge = theme.path("gameplayRuntime").path("survival").path("zeroChallenge");
        if (!challenge.isObject()) return;
        long nodeId = challenge.path("challengeNodeId").asLong();
        JsonNode primary = configs.path(String.valueOf(nodeId));
        for (JsonNode row : MATRIX.path("types")) {
            if (challenge.path("version").asInt()==2 && !challenge.path("gameplayKey").asText().equals(row.path("section").asText()))continue;
            if (!primary.path(row.path("section").asText()).path("enabled").asBoolean()
                    || !"CHALLENGE_ONLY".equals(row.path("tools").path("zeroChallenge").asText()) || "typeIn".equals(row.path("key").asText())) continue;
            boolean alternative = false;
            for (JsonNode id : challenge.path("alternativeNodeIds")) {
                JsonNode config = configs.path(id.asText());
                for (JsonNode proof : MATRIX.path("types")) if (proof.path("completionProof").asBoolean() && !"scan".equals(proof.path("key").asText())) {
                    JsonNode section = config.path(proof.path("section").asText());
                    alternative |= section.path("enabled").asBoolean() && (!proof.has("mode") || proof.path("mode").asText().equals(section.path("mode").asText()));
                }
            }
            if (!alternative) fail(nodeId, null, row.path("key").asText(), "zeroChallenge", "传感器须配置服务端客观题备选节点");
        }
    }

    private static String type(String section, JsonNode config) {
        for (JsonNode row : MATRIX.path("types")) if (section.equals(row.path("section").asText()) && (!row.has("mode") || row.path("mode").asText().equals(config.path("mode").asText()))) return row.path("key").asText();
        return section + (config.has("mode") ? ":" + config.path("mode").asText() : "");
    }
    private static String firstTool(List<JsonNode> rules, JsonNode tools, boolean challenge) {
        for (JsonNode rule : rules) {
            if (rule.has("costOptions")) return "costOptions";
            for (String cost : Arrays.asList("hpCost", "luckCost", "actionCost", "statCosts", "resourceCosts")) if (rule.has(cost)) return "costs";
            if (rule.has("resourceSlots")) return "resourceSlots";
            if (rule.has("targetScope")) return "targetScope";
            if (rule.has("limit") || rule.has("scope")) return "limitsScope";
        }
        if (tools.path("allowTrade").asBoolean()) return "trade";
        if (tools.path("decidesEnding").asBoolean()) return "decidesEnding";
        if (tools.has("votePolicy") || tools.path("plans").size() > 0) return "vote";
        return challenge ? "zeroChallenge" : "genericSettlement";
    }
    private static void fail(long nodeId, String name, String type, String tool, String reason) {
        throw new IllegalArgumentException("节点 " + nodeId + (name == null ? "" : "（" + name + "）") + " 玩法 " + type + " 工具 " + tool + "：" + reason);
    }
}
