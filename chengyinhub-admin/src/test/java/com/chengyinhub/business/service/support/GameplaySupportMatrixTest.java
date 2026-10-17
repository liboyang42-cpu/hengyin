package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

public class GameplaySupportMatrixTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Test void participantSelectionCapabilityIsOnlyWorldSharedFacilityAndRequiresPayer() throws Exception {
        assertEquals(JSON.readTree("{\"toolKind\":\"FACILITY\",\"targetScope\":\"PARTICIPANTS\",\"payerRequired\":true,\"runtimeScope\":\"TEAM\",\"worldMode\":\"WORLD_SHARED\"}"),GameplaySupportMatrix.view().path("participantSelection"));
    }
    @Test void zeroHpPolicyListsMatchAuthorFixtureAndGenericPublishDomain() throws Exception {
        assertEquals(Arrays.asList("BLOCK_HP_COST","CHALLENGE","BLOCK_PAID","THEME_FAIL","WAIT","STEPS"),GameplaySupportMatrix.zeroHpPolicies(true));
        assertTrue(GameplaySupportMatrix.zeroHpPolicies(false).contains("LEGACY_END"));
        assertThrows(UnsupportedOperationException.class,()->GameplaySupportMatrix.zeroHpPolicies(true).add("LEGACY_END"));
        JsonNode fixture=JSON.readTree(new String(Files.readAllBytes(Paths.get("../chengyinhub-xcx/tests/unit/fixtures/gameplay-support-matrix.json")),StandardCharsets.UTF_8));
        assertEquals(GameplaySupportMatrix.view().path("zeroHpPolicies"),fixture.path("data").path("zeroHpPolicies"));
    }
    @Test void missingChallengeCanBeReselectedInDraftButSensorStillCannotPublishAsNormalTask() throws Exception {
        ObjectNode theme = theme("TEAM"), sensor = JSON.createObjectNode();
        sensor.putObject("ballShake").put("enabled", true).put("target", 100);
        assertDoesNotThrow(() -> GameplaySupportMatrix.validateNode(theme, 11, "恢复挑战", sensor, 0, true));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> GameplaySupportMatrix.validateNode(theme, 11, "恢复挑战", sensor, 0)).getMessage().contains("完成证明"));
        ObjectNode unsupported = JSON.createObjectNode(); unsupported.putObject("estimate").put("enabled", true);
        assertThrows(IllegalArgumentException.class, () -> GameplaySupportMatrix.validateNode(theme, 11, "站点", unsupported, 0, true));
    }
    @Test void r5ProofIsVersionGatedAndDoesNotLoosenLegacyFreeRetry() throws Exception {
        ObjectNode r5=config("qaText");r5.with("qa").put("maxTries",2);assertThrows(IllegalArgumentException.class,()->GameplaySupportMatrix.validateNode(theme("PLAYER"),11,"QA",r5,0));
        r5.putObject("outcomeRules").put("version",1).putObject("pass");r5.with("outcomeRules").putObject("fail");assertDoesNotThrow(()->GameplaySupportMatrix.validateNode(theme("PLAYER"),11,"QA",r5,0));
        ObjectNode coin=JSON.createObjectNode();coin.putObject("coinFlip").put("enabled",true);coin.set("outcomeRules",r5.path("outcomeRules").deepCopy());assertDoesNotThrow(()->GameplaySupportMatrix.validateNode(theme("PLAYER"),11,"coin",coin,0));
        assertNull(GameplaySupportMatrix.basicCompletionKind(coin,4));
        coin.remove("outcomeRules");assertThrows(IllegalArgumentException.class,()->GameplaySupportMatrix.validateNode(theme("PLAYER"),11,"coin",coin,0));
        ObjectNode photo=JSON.createObjectNode();photo.putObject("photoCheck").put("enabled",true);photo.set("outcomeRules",r5.path("outcomeRules").deepCopy());assertDoesNotThrow(()->GameplaySupportMatrix.validateNode(theme("PLAYER"),11,"photo",photo,0));photo.remove("outcomeRules");assertThrows(IllegalArgumentException.class,()->GameplaySupportMatrix.validateNode(theme("PLAYER"),11,"photo",photo,0));
    }
    private static ObjectNode theme(String scope) {
        ObjectNode theme = JSON.createObjectNode();
        ObjectNode game = theme.putObject("gameplayRuntime").put("runtimeScope", scope);
        if ("TEAM".equals(scope)) game.put("worldMode", "WORLD_SHARED");
        game.putArray("quests").addObject().put("nodeId", 11);
        return theme;
    }
    public static ObjectNode config(String key) throws Exception {
        String section = key.startsWith("qa") ? "qa" : key;
        ObjectNode config = JSON.createObjectNode().put("schemaVersion", 1);
        ObjectNode block = config.putObject(section).put("enabled", true);
        switch (key) {
            case "qaText": block.put("mode", "TYPE").put("title", "输入口令").put("answerText", "key").put("maxTries", 0); break;
            case "qaPick":
                block.put("mode", "PICK").put("title", "选择口令").put("maxTries", 0);
                block.set("options", JSON.readTree("[{\"id\":\"a\",\"label\":\"甲\",\"correct\":true},{\"id\":\"b\",\"label\":\"乙\",\"correct\":false}]")); break;
            case "sort":
                block.put("prompt", "排列").put("maxAttempts", 0);
                block.set("items", JSON.readTree("[{\"id\":\"a\",\"label\":\"甲\"},{\"id\":\"b\",\"label\":\"乙\"},{\"id\":\"c\",\"label\":\"丙\"}]"));
                block.set("answerOrder", JSON.readTree("[\"a\",\"b\",\"c\"]")); break;
            case "match":
                block.put("prompt", "配对");
                block.set("left", JSON.readTree("[{\"id\":\"l1\",\"label\":\"甲\"},{\"id\":\"l2\",\"label\":\"乙\"}]"));
                block.set("right", JSON.readTree("[{\"id\":\"r1\",\"label\":\"一\"},{\"id\":\"r2\",\"label\":\"二\"}]"));
                block.set("pairs", JSON.readTree("[[\"l1\",\"r1\"],[\"l2\",\"r2\"]]")); break;
            case "classify":
                block.put("prompt", "分类");
                block.set("bins", JSON.readTree("[{\"id\":\"x\",\"label\":\"甲\"},{\"id\":\"y\",\"label\":\"乙\"}]"));
                block.set("items", JSON.readTree("[{\"id\":\"a\",\"label\":\"一\"},{\"id\":\"b\",\"label\":\"二\"}]"));
                block.set("answer", JSON.readTree("{\"a\":\"x\",\"b\":\"y\"}")); break;
            case "scan": block.put("kind", "TEXT").put("reply", "确认"); break;
            default: throw new IllegalArgumentException(key);
        }
        return config;
    }
    public static ObjectNode answers(String key) throws Exception {
        String section = key.startsWith("qa") ? "qa" : key;
        ObjectNode answers = JSON.createObjectNode();
        String value;
        switch (key) {
            case "qaText": value = "{\"input\":\"key\"}"; break;
            case "qaPick": value = "{\"optionId\":\"a\"}"; break;
            case "sort": value = "{\"order\":[\"a\",\"b\",\"c\"]}"; break;
            case "match": value = "{\"pairs\":[[\"l1\",\"r1\"],[\"l2\",\"r2\"]]}"; break;
            case "classify": value = "{\"placement\":{\"a\":\"x\",\"b\":\"y\"}}"; break;
            default: throw new IllegalArgumentException(key);
        }
        answers.set(section, JSON.readTree(value)); return answers;
    }

    @Test void tableCoversTheRealCatalogAndCannotBeChangedThroughReadback() throws Exception {
        Path catalog = Paths.get("chengyinhub-xcx/pages/publish/utils/publish/node-game-catalog.js");
        if (!Files.exists(catalog)) catalog = Paths.get("..").resolve(catalog);
        String source = new String(Files.readAllBytes(catalog), StandardCharsets.UTF_8);
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        for (JsonNode row : GameplaySupportMatrix.view().path("types")) rows.put(row.path("key").asText(), row);
        Matcher entries = Pattern.compile("\\{ key: '([^']+)'[^\\n]*section: '([^']*)'([^\\n]*)").matcher(source.substring(0, source.indexOf("const MODIFIERS")));
        int count = 0;
        while (entries.find()) {
            JsonNode row = rows.remove(entries.group(1)); assertNotNull(row, entries.group(1)); assertEquals(entries.group(2), row.path("section").asText());
            Matcher mode = Pattern.compile("mode: '([^']+)'").matcher(entries.group(3));
            if (mode.find()) assertEquals(mode.group(1), row.path("mode").asText()); count++;
        }
        assertEquals(32, count); assertTrue(rows.isEmpty());
        Set<String> addons = new LinkedHashSet<>(); for (JsonNode row : GameplaySupportMatrix.view().path("addons")) addons.add(row.path("key").asText());
        // R5（2026-10-10 主人裁定）：作者端只再提供 4 个附加规则（限时/时段/排行榜/多人协作-待定义）；
        // 闭眼盲品、今日城市签改为玩法「形式」（在 ALL 里），沉默点单/作品命名/治愈音乐角/跨日慢任务不再提供但服务端继续支持存量。
        Matcher modifiers = Pattern.compile("key: '([^']+)'").matcher(source.substring(source.indexOf("const MODIFIERS"), source.indexOf("const ALL")));
        count = 0; while (modifiers.find()) { assertTrue(addons.remove(modifiers.group(1)), modifiers.group(1)); count++; }
        assertEquals(4, count);
        for (String form : java.util.Arrays.asList("blindTaste", "dailySign")) assertTrue(source.contains("{ key: '" + form + "', section: '" + form + "'"), form);
        assertEquals(new LinkedHashSet<>(java.util.Arrays.asList("blindTaste", "silentOrder", "diyName", "musicCorner", "dailySign", "slowTask")), addons);
        GameplaySupportMatrix.view().remove("types"); assertEquals(32, GameplaySupportMatrix.view().path("types").size());
    }

    @Test void originalEffectDeclarationsDoNotInventACompletionProofBridge() {
        for(String key:java.util.Arrays.asList("profile","check","branch","pricePair","hiddenObject")) {
            ObjectNode config=JSON.createObjectNode();config.putObject(key).put("enabled",true);
            config.putObject("interactionRules").putArray("resourceSlots").addObject().put("key","energy");
            config.putArray("effects").addObject().put("var","energy").put("op","INC").put("value",2);
            ObjectNode theme=theme("TEAM");theme.with("gameplayRuntime").remove("quests");
            assertDoesNotThrow(()->GameplaySupportMatrix.validateNode(theme,11,"站点",config,8));
            config.with("interactionRules").put("limit",2);
            assertThrows(IllegalArgumentException.class,()->GameplaySupportMatrix.validateNode(theme,11,"站点",config,8));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"costs", "costOptions", "limitsScope", "targetScope", "trade", "vote", "decidesEnding"})
    void unsupportedMainNamesTheActualTool(String tool) {
        ObjectNode config = JSON.createObjectNode(); config.putObject("estimate").put("enabled", true);
        ObjectNode rule = config.putObject("interactionRules"), tools = config.putObject("interactionTools");
        switch (tool) {
            case "costs": rule.put("hpCost", 1); break;
            case "costOptions": rule.putArray("costOptions").addObject().put("hpCost", 1); break;
            case "limitsScope": rule.put("scope", "WORLD"); break;
            case "targetScope": rule.put("targetScope", "WORLD"); break;
            case "resourceSlots": rule.putArray("resourceSlots").addObject().put("key", "energy"); break;
            case "trade": tools.put("allowTrade", true); break;
            case "vote": tools.put("votePolicy", "MAJORITY"); break;
            case "decidesEnding": tools.put("decidesEnding", true); break;
        }
        rejected(theme("TEAM"), config, 8, "estimate", tool);
    }

    @ParameterizedTest @ValueSource(strings = {"limitsScope", "targetScope", "trade", "vote", "decidesEnding"})
    void ownerModeRejectsTeamTools(String tool) throws Exception {
        ObjectNode config = config("scan");
        if ("limitsScope".equals(tool)) config.putObject("interactionRules").put("scope", "WORLD");
        else if ("targetScope".equals(tool)) config.putObject("interactionRules").put("targetScope", "PARTICIPANTS");
        else config.putObject("interactionTools").put("trade".equals(tool) ? "allowTrade" : "decidesEnding".equals(tool) ? "decidesEnding" : "votePolicy", "vote".equals(tool) ? "MAJORITY" : "true");
        if ("trade".equals(tool) || "decidesEnding".equals(tool)) config.with("interactionTools").put("trade".equals(tool) ? "allowTrade" : "decidesEnding", true);
        rejected(theme("PLAYER"), config, 4, "scan", tool);
    }

    @Test void everyUnsupportedCatalogTypeAndUnknownTypeIsRejected() {
        for (JsonNode row : GameplaySupportMatrix.view().path("types")) if (!row.path("completionProof").asBoolean() && !row.path("section").asText().isEmpty()) {
            ObjectNode config = JSON.createObjectNode(); ObjectNode section = config.putObject(row.path("section").asText()).put("enabled", true);
            if (row.has("mode")) section.set("mode", row.get("mode")); config.putObject("interactionRules").put("hpCost", 1);
            rejected(theme("TEAM"), config, 0, row.path("key").asText(), "costs");
        }
        ObjectNode unknown = JSON.createObjectNode(); unknown.putObject("unregisteredGame").put("enabled", true);
        rejected(theme("TEAM"), unknown, 4, "unregisteredGame", "genericSettlement");
    }

    @Test void addonsKeepARealProofButCannotCreateOne() throws Exception {
        for (JsonNode row : GameplaySupportMatrix.view().path("addons")) {
            String key = row.path("key").asText(); ObjectNode config = config("scan"); config.putObject(key).put("enabled", true);
            assertDoesNotThrow(() -> GameplaySupportMatrix.validateNode(theme("PLAYER"), 11, "站点", config, 4));
            ObjectNode alone = JSON.createObjectNode(); alone.putObject(key).put("enabled", true);
            rejected(theme("TEAM"), alone, 0, key, "genericSettlement");
        }
        assertEquals("GPS", GameplaySupportMatrix.basicCompletionKind(JSON.createObjectNode(), 5));
    }

    @Test void independentTeamsCannotEnableSharedTradeVoteOrTargets() throws Exception {
        ObjectNode independent = theme("TEAM"); independent.with("gameplayRuntime").put("worldMode", "TEAM_INDEPENDENT");
        ObjectNode scan = config("scan"); scan.putObject("interactionTools").put("allowTrade", true); rejected(independent, scan, 4, "scan", "trade");
        scan.with("interactionTools").remove("allowTrade"); scan.with("interactionTools").put("votePolicy", "MAJORITY"); rejected(independent, scan, 4, "scan", "vote");
        scan.remove("interactionTools"); scan.putObject("interactionRules").put("targetScope", "WORLD"); rejected(independent, scan, 4, "scan", "targetScope");
    }

    @Test void paidAttemptAndWrongScanProofAreRejected() throws Exception {
        ObjectNode qa = config("qaText"); qa.with("qa").put("maxTries", 2); rejected(theme("TEAM"), qa, 1, "qaText", "costs");
        ObjectNode sort = config("sort"); sort.with("sort").put("maxAttempts", 2); rejected(theme("TEAM"), sort, 0, "sort", "costs");
        rejected(theme("TEAM"), config("scan"), 0, "scan", "genericSettlement");
    }

    @Test void challengeIsSeparateFromTasksFeesAndUnsupportedGames() throws Exception {
        ObjectNode theme = theme("TEAM"); theme.with("gameplayRuntime").remove("quests");
        ObjectNode challenge = theme.with("gameplayRuntime").putObject("survival").put("zeroHpPolicy", "CHALLENGE").putObject("zeroChallenge").put("challengeNodeId", 11);
        ObjectNode sensor = JSON.createObjectNode(); sensor.putObject("ballShake").put("enabled", true).put("goal", 10);
        assertDoesNotThrow(() -> GameplaySupportMatrix.validateNode(theme, 11, "站点", sensor, 0));
        ObjectNode configs = JSON.createObjectNode(); configs.set("11", sensor);
        String message = assertThrows(IllegalArgumentException.class, () -> GameplaySupportMatrix.validateChallenge(theme, configs)).getMessage();
        assertTrue(message.contains("11") && message.contains("shake") && message.contains("zeroChallenge"), message);
        challenge.putArray("alternativeNodeIds").add(12); configs.set("12", config("qaPick"));
        assertDoesNotThrow(() -> GameplaySupportMatrix.validateChallenge(theme, configs));
        assertDoesNotThrow(() -> GameplaySupportMatrix.validateNode(theme, 12, "备选", configs.path("12"), 3));
        sensor.putObject("interactionRules").put("hpCost", 1); rejected(theme, sensor, 0, "shake", "costs");
        ObjectNode estimate = JSON.createObjectNode(); estimate.putObject("estimate").put("enabled", true); rejected(theme, estimate, 8, "estimate", "zeroChallenge");
    }

    @ParameterizedTest @ValueSource(strings = {"pass.costs", "fail.costs", "pass.effects", "fail.effects", "option.costs", "option.effects"})
    void v2RecoveryRefusesOrdinaryOutcomeCostsAndRewards(String path) throws Exception {
        ObjectNode theme = theme("PLAYER"); theme.with("gameplayRuntime").remove("quests");
        theme.with("gameplayRuntime").putObject("survival").put("zeroHpPolicy", "CHALLENGE")
                .putObject("zeroChallenge").put("version", 2).put("challengeNodeId", 11).put("gameplayKey", "qa");
        ObjectNode config = config("qaPick");
        ObjectNode outcomes = config.putObject("outcomeRules").put("version", 1);
        outcomes.putObject("pass").put("reply", "恢复成功"); outcomes.putObject("fail").put("reply", "再试一次");
        assertDoesNotThrow(() -> GameplaySupportMatrix.validateNode(theme, 11, "恢复题组", config, 0));
        String[] parts = path.split("\\.");
        ObjectNode after = "option".equals(parts[0]) ? ((ObjectNode) config.path("qa").path("options").get(0)).putObject("after") : outcomes.with(parts[0]);
        if ("costs".equals(parts[1])) after.putArray("costs").addObject().put("statKey", "sys.hp").put("amount", 1);
        else after.putArray("effects").addObject().put("var", "counter.energy").put("op", "INC").put("value", 1);
        String message = assertThrows(IllegalArgumentException.class,
                () -> GameplaySupportMatrix.validateNode(theme, 11, "恢复题组", config, 0)).getMessage();
        assertTrue(message.contains("11") && message.contains("归零挑战不能配置普通结果费用或奖励"), message);
    }

    @Test void voteAndIndependentFeesCannotBeCombinedAndLegacyNodesRemainUntouched() throws Exception {
        ObjectNode config = config("scan"); config.putObject("interactionRules").put("hpCost", 1);
        config.putObject("interactionTools").putArray("plans").addObject().put("title", "投票");
        rejected(theme("TEAM"), config, 4, "scan", "vote");
        assertDoesNotThrow(() -> GameplaySupportMatrix.validateNode(JSON.createObjectNode(), 11, "旧站", JSON.createObjectNode().set("estimate", JSON.createObjectNode().put("enabled", true)), 8));
    }
    private static void rejected(JsonNode theme, JsonNode config, int method, String type, String tool) {
        String message = assertThrows(IllegalArgumentException.class, () -> GameplaySupportMatrix.validateNode(theme, 11, "站点", config, method)).getMessage();
        assertTrue(message.contains("11") && message.contains(type) && message.contains(tool), message);
    }
}
