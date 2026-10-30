package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancedGameConfigValidatorTest {
    @ParameterizedTest
    @ValueSource(strings = {"-4294967295", "4294967297", "9223372036854775808", "-9223372036854775809",
            "18446744073709551617", "-18446744073709551615",
            "0", "1000", "1.0", "1.5", "999.9", "1e0", "\"1\"", "null"})
    void itemConditionsRejectOutOfRangeAndNonIntegralQuantities(String quantity) {
        for (String op : new String[]{"ITEM_HELD", "ITEM_ACQUIRED"}) {
            String config = "{\"schemaVersion\":1,\"requires\":[{\"op\":\"" + op
                    + "\",\"itemId\":\"supply\",\"quantity\":" + quantity + "}]}";
            assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(config), op + ": " + quantity);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 999})
    void itemConditionsPreserveValidQuantityBoundaries(int quantity) throws Exception {
        for (String op : new String[]{"ITEM_HELD", "ITEM_ACQUIRED"}) {
            String config = "{\"schemaVersion\":1,\"requires\":[{\"op\":\"" + op
                    + "\",\"itemId\":\"supply\",\"quantity\":" + quantity + "}]}";
            String normalized = validator.validateAndNormalize(config);
            assertEquals(quantity, objectMapper.readTree(normalized).path("requires").get(0).path("quantity").asInt());
            assertEquals(normalized, validator.validateAndNormalize(normalized));
        }
    }

    @org.junit.jupiter.api.Test
    void legacyMissingBranchCodesAreStableAndExplicitDuplicatesStillFail() throws Exception {
        AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
        String raw = "{\"schemaVersion\":1,\"branch\":{\"enabled\":true,\"startStepId\":\"start\",\"steps\":["
                + "{\"id\":\"start\",\"terminal\":false,\"options\":[{\"id\":\"a\",\"label\":\"左\",\"nextStepId\":\"left\"},{\"id\":\"b\",\"label\":\"右\",\"nextStepId\":\"right\"}]},"
                + "{\"id\":\"left\",\"terminal\":true,\"options\":[]},{\"id\":\"right\",\"terminal\":true,\"options\":[]}]}}";
        com.fasterxml.jackson.databind.JsonNode saved = new com.fasterxml.jackson.databind.ObjectMapper().readTree(validator.validateAndNormalize(raw));
        assertEquals("left", saved.path("branch").path("steps").get(1).path("outcomeCode").asText());
        assertEquals("right", saved.path("branch").path("steps").get(2).path("outcomeCode").asText());
        assertEquals(saved.toString(), validator.validateAndNormalize(saved.toString()));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(saved.toString().replace("\"outcomeCode\":\"right\"", "\"outcomeCode\":\"left\"")));
    }

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void interactionPolicyPersistsAlongExistingAdvancedConfigAndNormalizesOnce() throws Exception {
        String raw = "{\"schemaVersion\":1,\"interactionRules\":{\"hpCost\":2},\"futurePresentation\":{\"note\":\"keep\"}}";
        JsonNode stored = objectMapper.readTree(validator.validateAndNormalize(raw));
        assertEquals(1, stored.path("interactionRules").path("limit").asInt());
        assertEquals("OWNER", stored.path("interactionRules").path("scope").asText());
        assertEquals("OWNER", stored.path("interactionRules").path("targetScope").asText());
        assertEquals("keep", stored.path("futurePresentation").path("note").asText());
        assertEquals(stored.toString(), validator.validateAndNormalize(stored.toString()));
    }

    @Test
    void interactionPolicyRejectsMalformedPayloadInsteadOfSavingUnexecutedRules() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize("{\"schemaVersion\":1,\"interactionRules\":null}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize("{\"schemaVersion\":1,\"interactionRules\":{\"successCount\":0}}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize("{\"schemaVersion\":1,\"interactionRules\":{\"hpCost\":\"2\"}}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize("{\"schemaVersion\":1,\"interactionRules\":{\"scope\":\"MEMBER\"}}"));
    }

    @Test
    void profileQuestionsNormalizeToRequiredWhileAvatarKeepsItsOwnSetting() throws Exception {
        String raw = "{\"schemaVersion\":1,\"profile\":{\"enabled\":true,"
                + "\"avatar\":{\"enabled\":true,\"required\":false},\"questions\":["
                + "{\"key\":\"name\",\"label\":\"姓名\",\"kind\":\"text\",\"required\":false},"
                + "{\"key\":\"job\",\"label\":\"职业\",\"kind\":\"pick\",\"required\":false,"
                + "\"options\":[{\"key\":\"a\",\"label\":\"甲\"},{\"key\":\"b\",\"label\":\"乙\"}]}]}}";
        JsonNode saved = objectMapper.readTree(validator.validateAndNormalize(raw)).path("profile");
        for (JsonNode question : saved.path("questions")) assertTrue(question.path("required").asBoolean());
        assertFalse(saved.path("avatar").path("required").asBoolean(), "问题必答不改变头像选填配置");
    }

    @Test
    void albumPreservesPublicPhotosWithoutBecomingACompletionMechanic() throws Exception {
        String raw = "{\"schemaVersion\":1,\"album\":{\"enabled\":true,\"images\":[{\"url\":\"https://example.com/a.jpg\",\"line\":\"童年\",\"secret\":\"hidden\"}]}}";
        String saved = validator.validateAndNormalize(raw);
        JsonNode projected = objectMapper.readTree(com.chengyinhub.business.util.AdvancedGamePublicProjection.safeConfigJson(saved));
        assertEquals("童年", projected.path("album").path("images").get(0).path("line").asText());
        assertFalse(projected.path("album").path("images").get(0).has("secret"));
        assertFalse(validator.hasEnabledMechanic(saved), "相册通过故事展示，不应启动通关会话");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(raw.replace("https://example.com/a.jpg", "wxfile://tmp/a.jpg")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize("{\"schemaVersion\":1,\"album\":{\"enabled\":true,\"images\":[]}}"));
    }

    @Test
    void albumKeepsSixPhotosAndRejectsSeventh() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", 1);
        com.fasterxml.jackson.databind.node.ObjectNode album = root.putObject("album");
        album.put("enabled", true);
        com.fasterxml.jackson.databind.node.ArrayNode images = album.putArray("images");
        for (int i = 0; i < 6; i++) images.addObject().put("url", "https://cdn.example.com/" + i + ".jpg");
        JsonNode saved = objectMapper.readTree(validator.validateAndNormalize(root.toString()));
        assertEquals(6, saved.path("album").path("images").size());
        images.addObject().put("url", "https://cdn.example.com/extra.jpg");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(root.toString()));
        assertEquals("相册须配 1 至 6 张照片", error.getMessage());
    }

    @Test
    void d20AcceptsCustomDifficultyAndRejectsInvalidRules() throws Exception {
        String config = "{\"schemaVersion\":1,\"diceRoll\":{\"enabled\":true,\"mode\":\"d20\","
                + "\"dc\":15,\"modifier\":-2,\"rollMode\":\"advantage\","
                + "\"successText\":\"找到线索\",\"failText\":\"继续寻找\"}}";
        JsonNode saved = objectMapper.readTree(validator.validateAndNormalize(config));
        assertEquals(15, saved.path("diceRoll").path("dc").asInt());
        for (String bad : new String[] {
                config.replace("\"dc\":15", "\"dc\":0"),
                config.replace("\"dc\":15", "\"dc\":15.5"),
                config.replace("\"modifier\":-2", "\"modifier\":21"),
                config.replace("\"modifier\":-2", "\"modifier\":\"2\""),
                config.replace("advantage", "anything"),
                config.replace("继续寻找", ""),
                config.replace("d20", "d100") }) {
            assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(bad), bad);
        }
        String publicJson = com.chengyinhub.business.util.AdvancedGamePublicProjection
                .safeConfigJson(config);
        JsonNode projection = objectMapper.readTree(publicJson).path("diceRoll");
        assertEquals("d20", projection.path("mode").asText());
        assertFalse(projection.has("successText"));
        assertFalse(projection.has("failText"));
    }

    @Test
    void validateAndNormalize_acceptsCompleteVersionOneConfig() throws Exception {
        String normalized = validator.validateAndNormalize("{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"timer\": {\"enabled\":true,\"durationSeconds\":600,\"timeoutResult\":\"FAILED\"},\n"
                + "  \"random\": {\"enabled\":true,\"drawCount\":1,\"items\":["
                + "{\"id\":\"clue-a\",\"label\":\"线索 A\",\"weight\":10,\"content\":\"向左\"},"
                + "{\"id\":\"clue-b\",\"label\":\"线索 B\",\"weight\":5,\"content\":\"向右\"}]},\n"
                + "  \"branch\": {\"enabled\":true,\"startStepId\":\"start\",\"steps\":["
                + "{\"id\":\"start\",\"title\":\"起点\",\"body\":\"选择\",\"terminal\":false,"
                + "\"options\":[{\"id\":\"left\",\"label\":\"向左\",\"nextStepId\":\"finish\",\"score\":10}]},"
                + "{\"id\":\"finish\",\"title\":\"终点\",\"body\":\"完成\",\"terminal\":true,"
                + "\"outcomeCode\":\"HISTORY_END\",\"outcomeLabel\":\"历史结局\",\"options\":[]}]},\n"
                + "  \"leaderboard\": {\"enabled\":true,\"metric\":\"ELAPSED_TIME\",\"scope\":\"ACTIVITY\",\"limit\":50},\n"
                + "  \"multiplayer\": {\"enabled\":true,\"mode\":\"ROLE_BASED\",\"minPlayers\":2,\"maxPlayers\":2,"
                + "\"assignment\":\"LEADER\",\"roles\":[{\"id\":\"scout\",\"label\":\"侦察\",\"min\":1,\"max\":2}],"
                + "\"turnOrder\":[\"scout\"]}\n"
                + "}");

        JsonNode json = objectMapper.readTree(normalized);
        assertEquals(1, json.path("schemaVersion").asInt());
        assertEquals("start", json.path("branch").path("startStepId").asText());
        assertEquals("HISTORY_END", json.path("branch").path("steps").get(1).path("outcomeCode").asText());
        assertEquals("历史结局", json.path("branch").path("steps").get(1).path("outcomeLabel").asText());
        assertEquals(2, json.path("random").path("items").size());
        assertTrue(normalized.length() < 65536);
    }

    @Test
    void validateAndNormalize_rejectsUnknownVersion() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize("{\"schemaVersion\":2}"));
        assertEquals("高级玩法配置版本不支持", error.getMessage());
    }

    // ==================== 阶段 2:mistakeTier 根层三档字面量 ====================

    /** 三档字面量都放行;规范化不改写这个根层键。 */
    @Test
    void mistakeTierAcceptsThreeTiers() throws Exception {
        for (String tier : new String[]{"easy", "medium", "hard"}) {
            String normalized = validator.validateAndNormalize(journey("mistakeTier", "\"" + tier + "\""));
            assertEquals(tier, objectMapper.readTree(normalized).path("mistakeTier").asText(),
                    tier + " 必须原样通过");
        }
    }

    /** 不配 mistakeTier = 合法(缺省 easy),老配置一字不改照旧过闸。 */
    @Test
    void mistakeTierAbsentIsValid() throws Exception {
        assertFalse(objectMapper.readTree(
                validator.validateAndNormalize("{\"schemaVersion\":1}")).has("mistakeTier"));
    }

    /**
     * 只有三档:任意其它字符串 / 数字 / 布尔都拒。
     *
     * <p>负控:去掉 validateMistakeTier 的枚举判定,本用例红(坏档位被透传)。
     */
    @Test
    void mistakeTierRejectsAnythingElse() {
        for (String bad : new String[]{"\"nightmare\"", "\"EXTREME\"", "\"\"", "3", "true", "{}"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAndNormalize(journey("mistakeTier", bad)),
                    "mistakeTier=" + bad + " 必须被拒");
            assertTrue(error.getMessage().contains("mistakeTier"), error.getMessage());
        }
    }

    @Test
    void validateAndNormalize_rejectsDanglingBranchTarget() {
        String json = "{\"schemaVersion\":1,\"branch\":{\"enabled\":true,\"startStepId\":\"start\",\"steps\":["
                + "{\"id\":\"start\",\"terminal\":false,\"options\":[{\"id\":\"go\",\"label\":\"走\",\"nextStepId\":\"missing\"}]}]}}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(json));
        assertEquals("分支选项指向了不存在的步骤", error.getMessage());
    }

    @Test
    void validateAndNormalize_rejectsDuplicateItemIds() {
        String json = "{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":1,\"items\":["
                + "{\"id\":\"same\",\"label\":\"A\",\"weight\":1},{\"id\":\"same\",\"label\":\"B\",\"weight\":1}]}}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(json));
        assertEquals("盲盒物品 ID 不能重复", error.getMessage());
    }

    @Test
    void validateAndNormalize_rejectsDrawCountAboveItemCount() {
        String json = "{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":2,\"items\":["
                + "{\"id\":\"only\",\"label\":\"唯一物品\",\"weight\":1}]}}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(json));
        assertEquals("抽取数量不能超过物品数量", error.getMessage());
    }

    @Test
    void validateAndNormalize_rejectsRandomItemAudioUrlAboveFieldLimit() {
        String audioUrl = "/audio/" + repeat('a', 494);
        String json = "{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":1,\"items\":["
                + "{\"id\":\"beat\",\"label\":\"节奏\",\"weight\":1,\"audioUrl\":\"" + audioUrl + "\"}]}}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(json));

        assertEquals("盲盒音频地址最多 500 个字", error.getMessage());
    }

    @Test
    void validateAndNormalize_rejectsNonTextOrUnsafeRandomItemAudioUrl() {
        String objectUrl = "{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":1,\"items\":["
                + "{\"id\":\"beat\",\"label\":\"节奏\",\"weight\":1,\"audioUrl\":{}}]}}";
        String insecureUrl = objectUrl.replace("{}", "\"http://example.test/beat.mp3\"");

        IllegalArgumentException typeError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(objectUrl));
        IllegalArgumentException schemeError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(insecureUrl));

        assertEquals("盲盒音频地址格式不正确", typeError.getMessage());
        assertEquals("盲盒音频地址格式不正确", schemeError.getMessage());
    }

    @Test
    void validateAndNormalize_rejectsTimerOutsideSupportedRange() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize("{\"schemaVersion\":1,\"timer\":{\"enabled\":true,\"durationSeconds\":9,\"timeoutResult\":\"FAILED\"}}"));
        assertEquals("计时时长须为 10 秒至 24 小时", error.getMessage());
    }

    @Test
    void validateAndNormalize_rejectsAutomaticRoleOrderThatCanOverfillRole() {
        String json = "{\"schemaVersion\":1,\"multiplayer\":{\"enabled\":true,"
                + "\"mode\":\"ROLE_BASED\",\"minPlayers\":2,\"maxPlayers\":3,\"assignment\":\"AUTO\","
                + "\"requiredTurns\":1,\"unitScore\":0,\"roles\":["
                + "{\"id\":\"scout\",\"label\":\"侦察\",\"min\":1,\"max\":1},"
                + "{\"id\":\"player\",\"label\":\"队员\",\"min\":1,\"max\":2}],"
                + "\"turnOrder\":[\"scout\",\"player\"]}}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(json));
        assertEquals("自动分配顺序无法覆盖全部允许人数", error.getMessage());
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    // ==================== 旅程体验模组化:requires / effects / check ====================

    /** 带三段的齐全配置:三段都要保留在规范化输出里,一个字都不能丢。 */
    @Test
    void validateAndNormalize_acceptsJourneyModuleSections() throws Exception {
        String normalized = validator.validateAndNormalize(journeyConfig());

        assertNotNull(normalized);
        JsonNode root = objectMapper.readTree(normalized);
        assertEquals(1, root.path("schemaVersion").asInt());
        JsonNode requires = root.path("requires");
        assertEquals(2, requires.size());
        assertEquals("clue.ledger_page", requires.get(0).path("var").asText());
        assertEquals("GTE", requires.get(0).path("op").asText());
        assertEquals(3, requires.get(0).path("value").asInt());
        assertEquals("HAS_TAG", requires.get(1).path("op").asText());
        assertEquals("tag.entered_once", requires.get(1).path("value").asText());
        assertEquals("INC", root.path("effects").get(0).path("op").asText());
        assertEquals("counter.visits", root.path("effects").get(0).path("var").asText());
        assertEquals(-1, root.path("effects").get(0).path("value").asInt());
        JsonNode check = root.path("check");
        assertTrue(check.path("enabled").asBoolean());
        assertEquals("ask_lin", check.path("checkId").asText());
        assertEquals("察言", check.path("skill").asText());
        assertEquals("medium", check.path("tier").asText());
        assertEquals(2, check.path("mods").get(0).path("value").asInt());
        assertEquals("clue.ledger_page", check.path("mods").get(0).path("when").path("var").asText());
        assertEquals("手握签收联 071", check.path("mods").get(0).path("label").asText());
        assertEquals(1, check.path("advantageIf").size());
        assertEquals(1, check.path("disadvantageIf").size());
        assertEquals("relation.lin_trust", check.path("successEffects").get(0).path("var").asText());
        assertEquals("tag.hurried", check.path("failEffects").get(0).path("value").asText());
        assertEquals("林老板把账本推过来", check.path("successText").asText());
        assertEquals("tag.hurried", check.path("failCostTag").asText());
    }

    /** 三段都缺省 = 老配置,必须照旧通过,且 schemaVersion 不升版。 */
    @Test
    void validateAndNormalize_acceptsLegacyConfigWithoutJourneyModules() throws Exception {
        String normalized = validator.validateAndNormalize("{\"schemaVersion\":1,"
                + "\"timer\":{\"enabled\":true,\"durationSeconds\":60,\"timeoutResult\":\"EXPIRED\"}}");

        assertEquals(1, AdvancedGameConfigValidator.SCHEMA_VERSION);
        JsonNode root = objectMapper.readTree(normalized);
        assertFalse(root.has("requires"));
        assertFalse(root.has("effects"));
        assertFalse(root.has("check"));
        assertEquals(60, root.path("timer").path("durationSeconds").asInt());
    }

    @Test
    void journeyRequiresRejectBadVarOpAndValue() {
        // 变量名前缀不在册(类型只能由前缀表达)
        IllegalArgumentException varError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("requires",
                        "[{\"var\":\"ledger_page\",\"op\":\"GTE\",\"value\":1}]")));
        assertTrue(varError.getMessage().contains("var"), varError.getMessage());
        // op 不在册
        IllegalArgumentException opError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("requires",
                        "[{\"var\":\"clue.page\",\"op\":\"exists\",\"value\":1}]")));
        assertTrue(opError.getMessage().contains("op"), opError.getMessage());
        // GTE 必带 value
        IllegalArgumentException valueError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("requires",
                        "[{\"var\":\"clue.page\",\"op\":\"GTE\"}]")));
        assertTrue(valueError.getMessage().contains("value"), valueError.getMessage());
        // value 越界
        IllegalArgumentException rangeError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("requires",
                        "[{\"var\":\"clue.page\",\"op\":\"LTE\",\"value\":100}]")));
        assertTrue(rangeError.getMessage().contains("99"), rangeError.getMessage());
        // NODE_COMPLETED 的 nodeId 必须是正整数
        IllegalArgumentException nodeError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("requires",
                        "[{\"op\":\"NODE_COMPLETED\",\"nodeId\":0}]")));
        assertTrue(nodeError.getMessage().contains("nodeId"), nodeError.getMessage());
        // HAS_TAG 的值必须是 tag. 命名空间
        IllegalArgumentException tagError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("requires",
                        "[{\"op\":\"HAS_TAG\",\"value\":\"hurried\"}]")));
        assertTrue(tagError.getMessage().contains("tag"), tagError.getMessage());
    }

    /** 阶段 1:sys.passed.{nodeId} 是判定型玩法写进本局的成败事实,创作者能在 requires / when 里读它。 */
    @Test
    void journeyRequiresAcceptReadableSysPassed() throws Exception {
        validator.validateAndNormalize(journey("requires",
                "[{\"var\":\"sys.passed.123\",\"op\":\"EQ\",\"value\":1}]"));
    }

    /** 负控:sys.passed 只读,效果里写它发布校验必须红(与 sys.hp 同口径,模板不得直接落笔)。 */
    @Test
    void journeyEffectsRejectWritingSysPassed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("effects",
                        "[{\"op\":\"SET\",\"var\":\"sys.passed.123\",\"value\":1}]")));
        assertTrue(error.getMessage().contains("var"), error.getMessage());
    }

    @Test
    void journeyEffectsRejectBadOpAndValueRules() {
        IllegalArgumentException opError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("effects",
                        "[{\"op\":\"TOGGLE\",\"var\":\"clue.page\",\"value\":1}]")));
        assertTrue(opError.getMessage().contains("op"), opError.getMessage());
        // SET 必带 value
        IllegalArgumentException valueError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("effects",
                        "[{\"op\":\"INC\",\"var\":\"counter.stamps\"}]")));
        assertTrue(valueError.getMessage().contains("value"), valueError.getMessage());
        // 非整数也拦:2.5 落到运行期会被静默截断成 2
        IllegalArgumentException intError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("effects",
                        "[{\"op\":\"INC\",\"var\":\"counter.stamps\",\"value\":2.5}]")));
        assertTrue(intError.getMessage().contains("整数"), intError.getMessage());
        // ADD_TAG 的值必须是 tag. 命名空间
        IllegalArgumentException tagError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("effects",
                        "[{\"op\":\"ADD_TAG\",\"value\":\"hurried\"}]")));
        assertTrue(tagError.getMessage().contains("tag"), tagError.getMessage());
    }

    @Test
    void journeyCheckRejectsCheckIdOutsidePattern() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("check",
                        "{\"enabled\":true,\"checkId\":\"ask lin!\",\"tier\":\"easy\"}")));
        assertTrue(error.getMessage().contains("checkId"), error.getMessage());
    }

    /** 负控用:去掉 tier 枚举校验这条用例立刻红。 */
    @Test
    void journeyCheckRejectsTierOutsideThreeNotches() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("check",
                        "{\"enabled\":true,\"checkId\":\"ask_lin\",\"tier\":\"deadly\"}")));
        assertTrue(error.getMessage().contains("tier"), error.getMessage());
    }

    @Test
    void journeyCheckRejectsTextAboveTwoHundredChars() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("check",
                        "{\"enabled\":true,\"checkId\":\"ask_lin\",\"tier\":\"easy\","
                                + "\"successText\":\"" + repeat('字', 201) + "\"}")));
        assertTrue(error.getMessage().contains("200"), error.getMessage());
    }

    @Test
    void journeyCheckRejectsBadModsAndFailCostTag() {
        IllegalArgumentException modVar = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("check",
                        "{\"enabled\":true,\"checkId\":\"ask_lin\",\"tier\":\"easy\","
                                + "\"mods\":[{\"when\":{\"var\":\"Clue.Page\",\"op\":\"GTE\",\"value\":1},\"value\":1}]}")));
        assertTrue(modVar.getMessage().contains("var"), modVar.getMessage());
        IllegalArgumentException modValue = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("check",
                        "{\"enabled\":true,\"checkId\":\"ask_lin\",\"tier\":\"easy\","
                                + "\"mods\":[{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.polite\"}}]}")));
        assertTrue(modValue.getMessage().contains("value"), modValue.getMessage());
        IllegalArgumentException modWhen = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("check",
                        "{\"enabled\":true,\"checkId\":\"ask_lin\",\"tier\":\"easy\","
                                + "\"mods\":[{\"value\":1}]}")));
        assertTrue(modWhen.getMessage().contains("when"), modWhen.getMessage());
        IllegalArgumentException tagError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("check",
                        "{\"enabled\":true,\"checkId\":\"ask_lin\",\"tier\":\"easy\","
                                + "\"failCostTag\":\"hurried\"}")));
        assertTrue(tagError.getMessage().contains("failCostTag"), tagError.getMessage());
    }

    /** F5:failCostTag 只能是 tag. 命名空间 —— 其他前缀虽是合法键名,挂到玩家身上的代价语义却是错的。 */
    @Test
    void journeyCheckRejectsFailCostTagOutsideTagNamespace() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("check",
                        "{\"enabled\":true,\"checkId\":\"ask_lin\",\"tier\":\"easy\","
                                + "\"failCostTag\":\"clue.hurried\"}")));
        assertTrue(error.getMessage().contains("failCostTag"), error.getMessage());
        assertTrue(error.getMessage().contains("tag."), error.getMessage());
    }

    @Test
    void journeyListsRejectMoreThanSixteenEntries() {
        IllegalArgumentException requiresError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("requires", tooManyConditions(17))));
        assertTrue(requiresError.getMessage().contains("16"), requiresError.getMessage());

        IllegalArgumentException modsError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("check",
                        "{\"enabled\":true,\"checkId\":\"ask_lin\",\"tier\":\"easy\",\"mods\":"
                                + tooManyMods(17) + "}")));
        assertTrue(modsError.getMessage().contains("16"), modsError.getMessage());
    }

    /** 16 条正好合法,边界不是 15。 */
    @Test
    void journeyListsAcceptExactlySixteenEntries() {
        assertNotNull(validator.validateAndNormalize(journey("effects", tooManyEffects(16))));
    }

    /** 关掉的 check 不校验内部字段 —— 与其余各段同一条规矩。 */
    @Test
    void disabledJourneyCheckSkipsInnerFields() throws Exception {
        JsonNode root = objectMapper.readTree(validator.validateAndNormalize(journey("check",
                "{\"enabled\":false,\"checkId\":\"no good!\",\"tier\":\"deadly\"}")));

        assertFalse(root.path("check").path("enabled").asBoolean());
    }

    private String journey(String field, String rawValue) {
        return "{\"schemaVersion\":1,\"" + field + "\":" + rawValue + "}";
    }

    private static String journeyConfig() {
        return "{\"schemaVersion\":1,"
                + "\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":3},"
                + "{\"op\":\"HAS_TAG\",\"value\":\"tag.entered_once\"}],"
                + "\"effects\":[{\"op\":\"INC\",\"var\":\"counter.visits\",\"value\":-1},"
                + "{\"op\":\"SET\",\"var\":\"clue.ledger_page\",\"value\":1}],"
                + "\"check\":{\"enabled\":true,\"checkId\":\"ask_lin\",\"skill\":\"察言\",\"tier\":\"medium\","
                + "\"mods\":[{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":2},"
                + "\"value\":2,\"label\":\"手握签收联 071\"}],"
                + "\"advantageIf\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.polite\"}],"
                + "\"disadvantageIf\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"}],"
                + "\"successEffects\":[{\"op\":\"INC\",\"var\":\"relation.lin_trust\",\"value\":1}],"
                + "\"failEffects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.hurried\"}],"
                + "\"successText\":\"林老板把账本推过来\",\"failText\":\"他没听懂\","
                + "\"failCostTag\":\"tag.hurried\"}}";
    }

    private static String tooManyConditions(int count) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) result.append(',');
            result.append("{\"var\":\"clue.page_").append(i).append("\",\"op\":\"GT\",\"value\":1}");
        }
        return result.append(']').toString();
    }

    private static String tooManyEffects(int count) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) result.append(',');
            result.append("{\"op\":\"SET\",\"var\":\"clue.page_").append(i).append("\",\"value\":1}");
        }
        return result.append(']').toString();
    }

    private static String tooManyMods(int count) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) result.append(',');
            result.append("{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.m").append(i)
                    .append("\"},\"value\":1}");
        }
        return result.append(']').toString();
    }

    /**
     * 迁移里写死的每一份高级玩法配置都必须真能过校验器。
     *
     * 配置不合法时线上表现是「模板照常上架、玩家点进去报错」—— 这类错到不了 CI,
     * 只能在生产被玩家撞见。把 SQL 里**每一条** JSON 逐字读出来跑一遍校验器,合并前就红。
     *
     * 负控:把任一条 durationSeconds 改成 5,本用例应抛「计时时长须为 10 秒至 24 小时」。
     */
    @Test
    void seededNamedMechanicConfigsAreValid() throws Exception {
        Path sql = locate("sql/migration_20260825_named_mechanic_advanced_config.sql");
        String text = new String(Files.readAllBytes(sql), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\('([^']+)',\\s*'(\\{\"schemaVersion\".*?})'\\)").matcher(text);

        int seen = 0;
        boolean sawLeaderboard = false;
        while (m.find()) {
            String title = m.group(1);
            String json = m.group(2);
            seen++;
            String normalized = validator.validateAndNormalize(json);
            assertNotNull(normalized, title);
            JsonNode root = objectMapper.readTree(normalized);
            assertTrue(root.path("timer").path("enabled").asBoolean(),
                    title + ":标题承诺了时长,必须开计时,否则跟修复前没区别");
            // 多人玩法要活动场次 + play_team,单人降级路径未实跑验证,货架模板上不许默认打开。
            assertFalse(root.path("multiplayer").path("enabled").asBoolean(),
                    title + ":multiplayer 未验证单人降级,不得在货架模板默认开启");
            assertTrue(validator.hasEnabledMechanic(json),
                    title + ":hasEnabledMechanic 必须为 true,否则 play 页 hasAdvanced 推不出来,"
                            + "玩法仍是到达即完成");
            sawLeaderboard |= root.path("leaderboard").path("enabled").asBoolean();
        }
        assertEquals(6, seen, "迁移应配置 6 个标题承诺了机制的玩法;提取正则或 SQL 结构变了");
        assertTrue(sawLeaderboard, "「小队竞速点亮」必须开排行榜,否则「竞速」仍无排名");
    }

    /**
     * 「小队竞速点亮」的多人配置必须真能过校验器,且形状对齐 #813 已上线的 18 个模板。
     *
     * 校验器对 AUTO 分配会逐个人数验一遍覆盖性(minPlayers..maxPlayers 每个值都要
     * 让每个角色落在自己的 [min,max] 内),配错在这里就红,不会等到玩家组好队才炸。
     *
     * 负控:把 minPlayers 改成 1,应抛「多人玩法人数须为 2 至 20 人」。
     */
    @Test
    void seededTeamRaceMultiplayerConfigIsValid() throws Exception {
        Path sql = locate("sql/migration_20260826_team_race_multiplayer.sql");
        String text = new String(Files.readAllBytes(sql), StandardCharsets.UTF_8);
        // 配置放在临时表的 INSERT 里 —— --transaction 白名单不放行 SET @var
        Matcher m = Pattern.compile("\\('小队竞速点亮',\\s*'(\\{.*?})'\\)").matcher(text);
        assertTrue(m.find(), "迁移里找不到「小队竞速点亮」的配置字面量,提取正则或 SQL 结构变了");

        String json = m.group(1);
        JsonNode root = objectMapper.readTree(validator.validateAndNormalize(json));
        JsonNode multi = root.path("multiplayer");
        assertTrue(multi.path("enabled").asBoolean(), "「小队」必须真开,否则这份迁移没有意义");
        assertEquals("ROLE_BASED", multi.path("mode").asText(), "形状对齐 #813 的 18 个模板");
        assertEquals(2, multi.path("minPlayers").asInt());
        assertEquals(4, multi.path("maxPlayers").asInt());
        assertEquals(2, multi.path("roles").size());
        // 「竞速」不能因为补了小队而丢掉
        assertTrue(root.path("timer").path("enabled").asBoolean(), "计时不能丢");
        assertTrue(root.path("leaderboard").path("enabled").asBoolean(), "排行榜不能丢");
    }

    @Test
    void coinFlipRequiresWhatToDoOnBothSides() {
        // 「正面 / 反面」本身没有意义,写上要做什么才是一个玩法 —— 所以 action 必填
        String json = "{\"schemaVersion\":1,\"coinFlip\":{\"enabled\":true,"
                + "\"heads\":{\"label\":\"正面\",\"action\":\"这杯店家请\"},"
                + "\"tails\":{\"label\":\"反面\"}}}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(json));
        assertEquals("反面要做什么不能为空", e.getMessage());
    }

    @Test
    void diceRollRejectsMissingFace() {
        // 少一面就有一面掷出来没内容,而且不报错 —— 必须在配置这一层拦住
        StringBuilder faces = new StringBuilder();
        for (int i = 0; i < 5; i++) faces.append(i > 0 ? "," : "").append("\"第 ").append(i + 1).append(" 件事\"");
        String json = "{\"schemaVersion\":1,\"diceRoll\":{\"enabled\":true,\"diceCount\":1,"
                + "\"faces\":[" + faces + "]}}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(json));
        assertEquals("掷骰子必须配满六个面", e.getMessage());
    }

    @Test
    void diceRollRejectsThreeDice() {
        String json = "{\"schemaVersion\":1,\"diceRoll\":{\"enabled\":true,\"diceCount\":3,"
                + "\"faces\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(json));
        assertEquals("掷骰子只能是一颗或两颗", e.getMessage());
    }

    // ==================== R3 三个新玩法:排序 / 连线 / 分类 ====================

    private static final String SORT_ITEMS =
            "\"items\":[{\"id\":\"mocha\",\"label\":\"摩卡\"},"
                    + "{\"id\":\"latte\",\"label\":\"拿铁\"},{\"id\":\"espresso\",\"label\":\"浓缩\"}]";

    /** 合法排序题:answerOrder 必须留在规范化输出里(运行期要靠它判定),hasEnabledMechanic 也要认。 */
    @Test
    void sortAcceptsValidConfigAndKeepsAnswerOrderForRuntime() throws Exception {
        String json = "{\"schemaVersion\":1,\"sort\":{\"enabled\":true,"
                + "\"prompt\":\"按苦味从轻到重排\",\"maxAttempts\":3," + SORT_ITEMS
                + ",\"answerOrder\":[\"latte\",\"mocha\",\"espresso\"]}}";

        JsonNode root = objectMapper.readTree(validator.validateAndNormalize(json));

        assertEquals(3, root.path("sort").path("answerOrder").size());
        assertEquals(3, root.path("sort").path("maxAttempts").asInt());
        assertTrue(validator.hasEnabledMechanic(json));
    }

    /** 缺 answerOrder = 每个位置都没有正确答案,这题永远过不了,且不报错。 */
    @Test
    void sortRejectsMissingAnswerOrder() {
        String json = "{\"schemaVersion\":1,\"sort\":{\"enabled\":true,\"prompt\":\"排一下\","
                + SORT_ITEMS + "}}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(json));

        assertEquals("排序答案必须覆盖全部待排项", error.getMessage());
    }

    /** answerOrder 少一项 = 那个位置永远排不对。 */
    @Test
    void sortRejectsAnswerOrderThatLeavesAnItemOut() {
        String json = "{\"schemaVersion\":1,\"sort\":{\"enabled\":true,\"prompt\":\"排一下\","
                + SORT_ITEMS + ",\"answerOrder\":[\"latte\",\"mocha\"]}}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(json));

        assertEquals("排序答案必须覆盖全部待排项", error.getMessage());
    }

    @Test
    void sortRejectsDanglingAndDuplicateAnswerIds() {
        String dangling = "{\"schemaVersion\":1,\"sort\":{\"enabled\":true,\"prompt\":\"排一下\","
                + SORT_ITEMS + ",\"answerOrder\":[\"latte\",\"mocha\",\"ghost\"]}}";
        assertEquals("排序答案引用了不存在的待排项",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(dangling)).getMessage());

        String duplicate = dangling.replace("\"ghost\"", "\"mocha\"");
        assertEquals("排序答案不能重复引用同一个待排项",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(duplicate)).getMessage());
    }

    @Test
    void sortRejectsDuplicateIdsBadShapesAndOutOfRangeCounts() {
        String duplicate = "{\"schemaVersion\":1,\"sort\":{\"enabled\":true,\"prompt\":\"排一下\","
                + "\"items\":[{\"id\":\"same\",\"label\":\"甲\"},{\"id\":\"same\",\"label\":\"乙\"}],"
                + "\"answerOrder\":[\"same\",\"same\"]}}";
        assertEquals("排序项 id 不能重复",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(duplicate)).getMessage());

        // id 形状:32 位封顶,点号之类的分隔符不接受
        String tooLong = duplicate.replace("\"same\"", "\"" + repeat('a', 33) + "\"");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(tooLong));

        String oneItem = "{\"schemaVersion\":1,\"sort\":{\"enabled\":true,\"prompt\":\"排一下\","
                + "\"items\":[{\"id\":\"only\",\"label\":\"唯一\"}],\"answerOrder\":[\"only\"]}}";
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(oneItem));

        StringBuilder nine = new StringBuilder();
        StringBuilder nineOrder = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            if (i > 0) { nine.append(','); nineOrder.append(','); }
            nine.append("{\"id\":\"i").append(i).append("\",\"label\":\"项").append(i).append("\"}");
            nineOrder.append("\"i").append(i).append("\"");
        }
        String tooMany = "{\"schemaVersion\":1,\"sort\":{\"enabled\":true,\"prompt\":\"排一下\","
                + "\"items\":[" + nine + "],\"answerOrder\":[" + nineOrder + "]}}";
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(tooMany));
    }

    private static final String MATCH_SIDES =
            "\"left\":[{\"id\":\"l1\",\"label\":\"甲\"},{\"id\":\"l2\",\"label\":\"乙\"}],"
                    + "\"right\":[{\"id\":\"r1\",\"label\":\"一\"},{\"id\":\"r2\",\"label\":\"二\"}]";

    @Test
    void matchAcceptsValidConfigAndKeepsPairsForRuntime() throws Exception {
        String json = "{\"schemaVersion\":1,\"match\":{\"enabled\":true,\"prompt\":\"把店和招牌连起来\","
                + MATCH_SIDES + ",\"pairs\":[[\"l1\",\"r2\"],[\"l2\",\"r1\"]]}}";

        JsonNode root = objectMapper.readTree(validator.validateAndNormalize(json));

        assertEquals(2, root.path("match").path("pairs").size());
        assertTrue(validator.hasEnabledMechanic(json));
    }

    @Test
    void matchRejectsMissingPairsAndUnequalSides() {
        String noPairs = "{\"schemaVersion\":1,\"match\":{\"enabled\":true,\"prompt\":\"连一下\","
                + MATCH_SIDES + "}}";
        assertEquals("连线答案必须让每一侧都恰好连到对面一条",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(noPairs)).getMessage());

        String unequal = noPairs.replace(",\"pairs\"", "")
                .replace("{\"id\":\"r2\",\"label\":\"二\"}]",
                        "{\"id\":\"r2\",\"label\":\"二\"},{\"id\":\"r3\",\"label\":\"三\"}]");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(unequal));
    }

    @Test
    void matchRejectsDanglingAndRepeatedPairs() {
        String dangling = "{\"schemaVersion\":1,\"match\":{\"enabled\":true,\"prompt\":\"连一下\","
                + MATCH_SIDES + ",\"pairs\":[[\"l1\",\"ghost\"],[\"l2\",\"r1\"]]}}";
        assertEquals("连线答案引用了不存在的右侧条目",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(dangling)).getMessage());

        String repeated = "{\"schemaVersion\":1,\"match\":{\"enabled\":true,\"prompt\":\"连一下\","
                + MATCH_SIDES + ",\"pairs\":[[\"l1\",\"r1\"],[\"l1\",\"r2\"]]}}";
        assertEquals("连线答案的左侧条目不能重复",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(repeated)).getMessage());

        String badShape = "{\"schemaVersion\":1,\"match\":{\"enabled\":true,\"prompt\":\"连一下\","
                + MATCH_SIDES + ",\"pairs\":[[\"l1\"],[\"l2\",\"r1\"]]}}";
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(badShape));
    }

    private static final String CLASSIFY_BODY =
            "\"bins\":[{\"id\":\"hot\",\"label\":\"热的\"},{\"id\":\"iced\",\"label\":\"冰的\"}],"
                    + "\"items\":[{\"id\":\"americano\",\"label\":\"美式\"},"
                    + "{\"id\":\"frappe\",\"label\":\"星冰乐\"}]";

    @Test
    void classifyAcceptsValidConfigAndKeepsAnswerForRuntime() throws Exception {
        String json = "{\"schemaVersion\":1,\"classify\":{\"enabled\":true,\"prompt\":\"分冷热\","
                + CLASSIFY_BODY + ",\"answer\":{\"americano\":\"hot\",\"frappe\":\"iced\"}}}";

        JsonNode root = objectMapper.readTree(validator.validateAndNormalize(json));

        assertEquals(2, root.path("classify").path("answer").size());
        assertTrue(validator.hasEnabledMechanic(json));
    }

    /** answer 少一张卡片 = 那张永远分不对,而且不报错。 */
    @Test
    void classifyRejectsAnswerThatLeavesAnItemOut() {
        String json = "{\"schemaVersion\":1,\"classify\":{\"enabled\":true,\"prompt\":\"分冷热\","
                + CLASSIFY_BODY + ",\"answer\":{\"americano\":\"hot\"}}}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(json));

        assertEquals("分类答案必须覆盖全部待分类项", error.getMessage());
    }

    @Test
    void classifyRejectsDanglingBinAndUnknownItemAndDuplicateIds() {
        String danglingBin = "{\"schemaVersion\":1,\"classify\":{\"enabled\":true,\"prompt\":\"分冷热\","
                + CLASSIFY_BODY + ",\"answer\":{\"americano\":\"hot\",\"frappe\":\"warm\"}}}";
        assertEquals("分类答案引用了不存在的分类",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(danglingBin)).getMessage());

        String unknownItem = danglingBin.replace("\"frappe\":\"warm\"", "\"ghost\":\"iced\"");
        assertEquals("分类答案引用了不存在的待分类项",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(unknownItem)).getMessage());

        String duplicate = "{\"schemaVersion\":1,\"classify\":{\"enabled\":true,\"prompt\":\"分冷热\","
                + "\"bins\":[{\"id\":\"hot\",\"label\":\"热的\"},{\"id\":\"hot\",\"label\":\"也热的\"}],"
                + "\"items\":[{\"id\":\"americano\",\"label\":\"美式\"},{\"id\":\"frappe\",\"label\":\"星冰乐\"}],"
                + "\"answer\":{\"americano\":\"hot\",\"frappe\":\"hot\"}}}";
        assertEquals("分类题的分类 id 不能重复",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(duplicate)).getMessage());
    }

    /**
     * R3 三段也必须和其他玩法一样带 xp:合法值进规范化输出(运行期靠它加分),
     * 越界值发布期就拒 —— 之前三段整段跳过了 validateXp,商家填 9999 也没人拦。
     */
    @Test
    void newGamesValidateXpRangeAndKeepItForRuntime() throws Exception {
        String sort = "{\"schemaVersion\":1,\"sort\":{\"enabled\":true,\"prompt\":\"排一下\",\"xp\":20,"
                + SORT_ITEMS + ",\"answerOrder\":[\"latte\",\"mocha\",\"espresso\"]}}";
        assertEquals(20, objectMapper.readTree(validator.validateAndNormalize(sort))
                .path("sort").path("xp").asInt());

        String match = "{\"schemaVersion\":1,\"match\":{\"enabled\":true,\"prompt\":\"连一下\",\"xp\":0,"
                + MATCH_SIDES + ",\"pairs\":[[\"l1\",\"r2\"],[\"l2\",\"r1\"]]}}";
        assertEquals(0, objectMapper.readTree(validator.validateAndNormalize(match))
                .path("match").path("xp").asInt());

        String classify = "{\"schemaVersion\":1,\"classify\":{\"enabled\":true,\"prompt\":\"分冷热\","
                + "\"xp\":1000," + CLASSIFY_BODY
                + ",\"answer\":{\"americano\":\"hot\",\"frappe\":\"iced\"}}}";
        assertEquals(1000, objectMapper.readTree(validator.validateAndNormalize(classify))
                .path("classify").path("xp").asInt());

        assertEquals("排序奖励分须为 0 至 1000",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(sort.replace("\"xp\":20", "\"xp\":1001")))
                        .getMessage());
        assertEquals("连线奖励分须为 0 至 1000",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(match.replace("\"xp\":0", "\"xp\":-1")))
                        .getMessage());
        assertEquals("分类奖励分须为 0 至 1000",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(classify.replace("\"xp\":1000", "\"xp\":1001")))
                        .getMessage());
    }

    // ==================== R16 时间轴对照题:两个版本逐条并排 ====================

    private static final String COMPARE_SIDES =
            "\"left\":{\"label\":\"版本 A\",\"items\":["
                    + "{\"id\":\"a1\",\"time\":\"14:00\",\"text\":\"取号\"},"
                    + "{\"id\":\"a2\",\"time\":\"14:10\",\"text\":\"称重\"},"
                    + "{\"id\":\"a3\",\"time\":\"14:20\",\"text\":\"封装\"}]},"
                    + "\"right\":{\"label\":\"版本 B\",\"items\":["
                    + "{\"id\":\"b1\",\"time\":\"14:00\",\"text\":\"取号\"},"
                    + "{\"id\":\"b2\",\"time\":\"14:15\",\"text\":\"称重\"},"
                    + "{\"id\":\"b3\",\"time\":\"14:30\",\"text\":\"送检\"}]}";

    /** 合法对照题:answer 必须留在规范化输出里(运行期靠它判定),hasEnabledMechanic 也要认。 */
    @Test
    void compareAcceptsValidConfigAndKeepsAnswerForRuntime() throws Exception {
        String json = "{\"schemaVersion\":1,\"compare\":{\"enabled\":true,"
                + "\"prompt\":\"找出两版对不上的地方\"," + COMPARE_SIDES
                + ",\"answer\":[\"b3\"],\"maxAttempts\":3,\"xp\":10}}";

        JsonNode root = objectMapper.readTree(validator.validateAndNormalize(json));

        assertEquals(1, root.path("compare").path("answer").size());
        assertEquals(3, root.path("compare").path("maxAttempts").asInt());
        assertTrue(validator.hasEnabledMechanic(json));

        // maxAttempts 是可选的:不配 = 不限次,照旧通过
        String noAttempts = json.replace(",\"maxAttempts\":3", "");
        assertEquals(0, objectMapper.readTree(validator.validateAndNormalize(noAttempts))
                .path("compare").path("maxAttempts").asInt());
    }

    /** 空答案 = 什么都不标就过;引用不存在的条目 = 永远标不对;重复标记 = 集合语义不明。 */
    @Test
    void compareRejectsEmptyDanglingAndRepeatedAnswer() {
        String body = "{\"schemaVersion\":1,\"compare\":{\"enabled\":true,\"prompt\":\"找出两版对不上的地方\","
                + COMPARE_SIDES + ",\"answer\":";
        assertEquals("对照答案不能为空",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(body + "[]}}")).getMessage());
        assertEquals("对照答案引用了不存在的条目",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(body + "[\"ghost\"]}}")).getMessage());
        assertEquals("对照答案不能重复标记同一条目",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(body + "[\"b3\",\"b3\"]}}")).getMessage());
    }

    /** id 必须全段唯一:两侧重名时 marked 集合分不清标的是哪一条。 */
    @Test
    void compareRejectsDuplicateIdsAcrossBothSides() {
        String duplicate = "{\"schemaVersion\":1,\"compare\":{\"enabled\":true,"
                + "\"prompt\":\"找出两版对不上的地方\","
                + "\"left\":{\"label\":\"版本 A\",\"items\":["
                + "{\"id\":\"same\",\"time\":\"14:00\",\"text\":\"取号\"},"
                + "{\"id\":\"a2\",\"time\":\"14:10\",\"text\":\"称重\"}]},"
                + "\"right\":{\"label\":\"版本 B\",\"items\":["
                + "{\"id\":\"same\",\"time\":\"14:00\",\"text\":\"取号\"},"
                + "{\"id\":\"b2\",\"time\":\"14:15\",\"text\":\"送检\"}]},"
                + "\"answer\":[\"same\"]}}";
        assertEquals("对照题两侧条目 id 不能重复",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(duplicate)).getMessage());

        // id 形状:32 位封顶,点号之类的分隔符不接受
        String tooLong = duplicate.replace("\"same\"", "\"" + repeat('a', 33) + "\"");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(tooLong));
    }

    /** 每侧 2 至 20 条:一条谈不上对照,21 条一屏放不下。 */
    @Test
    void compareRejectsSideCountsOutOfRange() {
        String oneItem = "{\"schemaVersion\":1,\"compare\":{\"enabled\":true,"
                + "\"prompt\":\"找出两版对不上的地方\","
                + "\"left\":{\"label\":\"版本 A\",\"items\":["
                + "{\"id\":\"a1\",\"time\":\"14:00\",\"text\":\"取号\"}]},"
                + "\"right\":{\"label\":\"版本 B\",\"items\":["
                + "{\"id\":\"b1\",\"time\":\"14:00\",\"text\":\"取号\"}]},"
                + "\"answer\":[\"b1\"]}}";
        assertEquals("对照题左侧须为 2 至 20 条",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(oneItem)).getMessage());

        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            if (i > 0) rows.append(',');
            rows.append("{\"id\":\"x").append(i).append("\",\"time\":\"14:00\",\"text\":\"项").append(i).append("\"}");
        }
        String tooMany = "{\"schemaVersion\":1,\"compare\":{\"enabled\":true,"
                + "\"prompt\":\"找出两版对不上的地方\","
                + "\"left\":{\"label\":\"版本 A\",\"items\":[" + rows + "]},"
                + "\"right\":{\"label\":\"版本 B\",\"items\":["
                + "{\"id\":\"b1\",\"time\":\"14:00\",\"text\":\"取号\"},"
                + "{\"id\":\"b2\",\"time\":\"14:00\",\"text\":\"取号\"}]},"
                + "\"answer\":[\"x0\"]}}";
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(tooMany));
    }

    /** 文本长度与必填:time / text 缺一不可,label、prompt 各有上限。 */
    @Test
    void compareRejectsOverlongTextsAndMissingTimeOrText() {
        String base = "{\"schemaVersion\":1,\"compare\":{\"enabled\":true,"
                + "\"prompt\":\"找出两版对不上的地方\"," + COMPARE_SIDES + ",\"answer\":[\"b3\"]}}";
        String overlongTime = base.replace("\"14:00\"", "\"" + repeat('a', 17) + "\"");
        assertEquals("对照题条目时间不能超过 16 字",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(overlongTime)).getMessage());

        String overlongText = base.replace("\"取号\"", "\"" + repeat('b', 201) + "\"");
        assertEquals("对照题条目内容不能超过 200 字",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(overlongText)).getMessage());

        String missingTime = base.replace(",\"time\":\"14:00\"", "");
        assertEquals("对照题条目时间不能为空",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(missingTime)).getMessage());

        String missingText = base.replace(",\"text\":\"取号\"", "");
        assertEquals("对照题条目内容不能为空",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(missingText)).getMessage());

        String overlongLabel = base.replace("\"版本 A\"", "\"" + repeat('c', 21) + "\"");
        assertEquals("对照题左侧标题不能超过 20 字",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(overlongLabel)).getMessage());

        String overlongPrompt = base.replace("找出两版对不上的地方", repeat('d', 201));
        assertEquals("对照题题干不能超过 200 字",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(overlongPrompt)).getMessage());

        String noPrompt = base.replace("\"prompt\":\"找出两版对不上的地方\",", "");
        assertEquals("对照题题干不能为空",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(noPrompt)).getMessage());
    }

    /** maxAttempts 配了就得在 1 至 10;xp 与其他玩法同一区间 0 至 1000。 */
    @Test
    void compareValidatesAttemptsRangeAndXp() {
        String base = "{\"schemaVersion\":1,\"compare\":{\"enabled\":true,"
                + "\"prompt\":\"找出两版对不上的地方\"," + COMPARE_SIDES + ",\"answer\":[\"b3\"]";
        for (String bad : new String[] {",\"maxAttempts\":0", ",\"maxAttempts\":11"}) {
            assertEquals("对照题可以交几次须为 1 至 10",
                    assertThrows(IllegalArgumentException.class,
                            () -> validator.validateAndNormalize(base + bad + "}}")).getMessage());
        }
        assertEquals("对照奖励分须为 0 至 1000",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(base + ",\"xp\":-1}}")).getMessage());
        assertEquals("对照奖励分须为 0 至 1000",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validateAndNormalize(base + ",\"xp\":1001}}")).getMessage());
    }

    /**
     * R19-INT 收口 b:对照玩法不在通过类效果的执行 / 冻结名单里(CHOICE_KITS 不含 compare),
     * 手写 effects 没有链路会跑 —— 校验期直接报错,别让商家以为配了有效果。
     * 空数组 / null 是没有内容的形状,照旧放行。
     */
    @Test
    void compareRejectsHandWrittenEffects() {
        String base = "{\"schemaVersion\":1,\"compare\":{\"enabled\":true,"
                + "\"prompt\":\"找出两版对不上的地方\"," + COMPARE_SIDES + ",\"answer\":[\"b3\"]";
        assertEquals("对照题不支持 effects:时间轴玩法没有通过类效果的执行链路,配了会被静默忽略",
                assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                        base + ",\"effects\":[{\"op\":\"INC\",\"var\":\"counter.trust\",\"value\":1}]}}"))
                        .getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(base + ",\"effects\":{\"op\":\"INC\"}}"));
        validator.validateAndNormalize(base + ",\"effects\":[]}}");
        validator.validateAndNormalize(base + ",\"effects\":null}}");
    }

    // ==================== R18:七段节点叙事已迁往章节故事流 ====================

    /**
     * R18:模板里的叙事键不再是模板契约 —— 形状不再由本校验器拒绝,原样透传(只读兼容);
     * 新叙事写在章节故事流,形状校验在 {@link ChapterNarrativeBlocks}。这里用旧合法配置
     * 证明「透传不破坏老数据」,用坏形状证明「不再拦」。
     */
    @Test
    void legacyNarrativeSectionsPassThroughUnchecked() throws Exception {
        JsonNode root = objectMapper.readTree(validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"enter\":{\"npcId\":0,\"questions\":[{\"id\":\"夜里\"}]},"
                        + "\"outcome\":{\"facts\":[{\"text\":\"\"}]},"
                        + "\"deliver\":{\"claimLater\":\"yes\"},"
                        + "\"unlock\":{\"hook\":\"\"},\"revisit\":{\"changes\":[]},"
                        + "\"brief\":{\"carry\":[]},\"route\":{\"walk\":\"\"}}"));

        assertEquals(0, root.path("enter").path("npcId").asInt(), "坏 npcId 不再由模板校验器拒绝");
        assertEquals("夜里", root.path("enter").path("questions").get(0).path("id").asText());
        assertEquals("yes", root.path("deliver").path("claimLater").asText());
        assertEquals(1, root.path("outcome").path("facts").size(), "空文本事实不再被拒绝,原样保留");
        assertEquals(1, AdvancedGameConfigValidator.SCHEMA_VERSION, "schemaVersion 不升版");
    }

    /** 五段齐全的合法旧配置:形状原样保留(只读兼容,运行期不再从模板读)。 */
    @Test
    void narrativeSectionsAcceptCompleteConfig() throws Exception {
        JsonNode root = objectMapper.readTree(validator.validateAndNormalize(narrativeConfig()));

        assertEquals(2, root.path("enter").path("questions").size());
        assertEquals("night", root.path("enter").path("questions").get(0).path("id").asText());
        assertEquals("等一个不回来的人", root.path("enter").path("questions").get(0).path("a").asText());
        assertEquals(123, root.path("outcome").path("npcId").asLong());
        assertEquals(2, root.path("outcome").path("facts").size());
        assertEquals("GTE", root.path("outcome").path("facts").get(1).path("when").path("op").asText());
        assertTrue(root.path("deliver").path("claimLater").asBoolean());
        assertEquals("偏门还亮着", root.path("unlock").path("hook").asText());
        assertEquals(2, root.path("revisit").path("changes").size());
    }

    /** 五段全缺 = 老配置,必须照旧通过,且 schemaVersion 不升版。 */
    @Test
    void narrativeSectionsAreOptionalForLegacyConfig() throws Exception {
        JsonNode root = objectMapper.readTree(validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"timer\":{\"enabled\":true,\"durationSeconds\":60,"
                        + "\"timeoutResult\":\"EXPIRED\"}}"));

        assertEquals(1, AdvancedGameConfigValidator.SCHEMA_VERSION);
        assertFalse(root.has("enter"));
        assertFalse(root.has("outcome"));
        assertFalse(root.has("deliver"));
        assertFalse(root.has("unlock"));
        assertFalse(root.has("revisit"));
    }

    // ==================== R9 叙事段:brief / route ====================

    /** brief / route 齐全:形状原样保留,carry 的 when 保真(只读兼容)。 */
    @Test
    void briefAndRouteAcceptCompleteConfig() throws Exception {
        JsonNode root = objectMapper.readTree(validator.validateAndNormalize(
                "{\"schemaVersion\":1,"
                        + "\"brief\":{\"question\":\"账本上写着什么\",\"body\":\"替我去柜台取那本账\","
                        + "\"carry\":[{\"when\":null,\"text\":\"随身带着签收联\"},"
                        + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},\"text\":\"路上慢一点\"}]},"
                        + "\"route\":{\"walk\":\"约 6 分钟\",\"todo\":\"找到柜台旁带城瘾游戏标识的展示台\"}}"));

        assertEquals("账本上写着什么", root.path("brief").path("question").asText());
        assertEquals("替我去柜台取那本账", root.path("brief").path("body").asText());
        assertEquals(2, root.path("brief").path("carry").size());
        assertEquals("tag.hurried",
                root.path("brief").path("carry").get(1).path("when").path("value").asText());
        assertEquals("约 6 分钟", root.path("route").path("walk").asText());
        assertEquals("找到柜台旁带城瘾游戏标识的展示台", root.path("route").path("todo").asText());
    }

    /** brief / route 全缺 = 老配置,照旧通过。 */
    @Test
    void briefAndRouteAreOptionalForLegacyConfig() throws Exception {
        JsonNode root = objectMapper.readTree(validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"timer\":{\"enabled\":true,\"durationSeconds\":60,"
                        + "\"timeoutResult\":\"EXPIRED\"}}"));

        assertFalse(root.has("brief"));
        assertFalse(root.has("route"));
    }

    /**
     * R22-SEC S16:NODE_COMPLETED 的 nodeId 归属只在调用方给出本主题节点集合时核对;
     * 写错的消息要带出外来 nodeId,发布期才拦得住「永远不成立」的条件。
     *
     * <p>负控:assertCompletedNodeIdsOwned 改回直接 return,本用例第二段立刻红。
     */
    @Test
    void nodeCompletedOwnershipCheckedOnlyWhenNodeSetGiven() {
        String config = "{\"schemaVersion\":1,\"requires\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":501}]}";

        assertNotNull(validator.validateAndNormalize(config), "没给节点集合:只验形状");
        validator.assertCompletedNodeIdsOwned(config, new HashSet<Long>(Collections.singletonList(501L)));
        validator.assertCompletedNodeIdsOwned(config, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.assertCompletedNodeIdsOwned(config,
                        new HashSet<Long>(Collections.singletonList(502L))));
        assertTrue(error.getMessage().contains("501"), error.getMessage());
    }

    private static String narrativeConfig() {
        return "{\"schemaVersion\":1,"
                + "\"enter\":{\"npcId\":123,\"opener\":\"推门时风铃响了一声\",\"aside\":\"他今天没戴眼镜\","
                + "\"questions\":[{\"id\":\"night\",\"q\":\"还在等谁?\",\"a\":\"等一个不回来的人\"},"
                + "{\"id\":\"ledger\",\"q\":\"账本呢?\",\"a\":\"锁在柜里\"}]},"
                + "\"outcome\":{\"title\":\"老账房的夜\",\"npcId\":123,\"line\":\"他认得你了\","
                + "\"lineIfFail\":\"他没认出来\",\"open\":\"风停了\","
                + "\"facts\":[{\"text\":\"算盘换过位置\"},"
                + "{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},\"text\":\"你翻到过那一页\"}]},"
                + "\"deliver\":{\"reward\":\"一杯热茶\",\"line\":\"茶还温着\",\"claimLater\":true},"
                + "\"unlock\":{\"hook\":\"偏门还亮着\"},"
                + "\"revisit\":{\"changes\":[{\"text\":\"门槛被踩平了\"},"
                + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},\"text\":\"你上回跑着来\"}]}}";
    }

    // ==================== 罗盘指向 compass(现场感契约 §3) ====================

    private static String compassConfig(String body) {
        return "{\"schemaVersion\":1,\"compass\":{\"enabled\":true," + body + "}}";
    }

    @Test
    void compassAcceptsFullConfigAtEveryBoundary() throws Exception {
        String normalized = validator.validateAndNormalize(compassConfig(
                "\"kicker\":\"把手机转向钟楼\",\"bearing\":135,\"tolerance\":15,"
                        + "\"holdSeconds\":3,\"hint\":\"它比你想的更靠右\",\"xp\":8"));
        JsonNode json = objectMapper.readTree(normalized);
        assertEquals(135, json.path("compass").path("bearing").asInt());
        assertEquals(15, json.path("compass").path("tolerance").asInt());
        assertEquals(3, json.path("compass").path("holdSeconds").asInt());

        // 边界都取得到:0 是正北、是一个合法目标,不是「没填」
        assertNotNull(validator.validateAndNormalize(compassConfig("\"bearing\":0,\"tolerance\":5,\"holdSeconds\":1")));
        assertNotNull(validator.validateAndNormalize(compassConfig("\"bearing\":359,\"tolerance\":90,\"holdSeconds\":10")));
    }

    @Test
    void compassCountsAsAdvancedMechanic() {
        // 负控:把 hasEnabledMechanic 里 compass 那行摘掉,只配罗盘的节点玩家走到点直接打卡、
        // 玩法静默不存在 —— 本用例立刻红
        assertTrue(validator.hasEnabledMechanic(compassConfig("\"bearing\":10,\"tolerance\":15,\"holdSeconds\":3")));
    }

    @Test
    void compassRejectsMissingOrOutOfRangeBearing() {
        // 没填 bearing 必须拒而不是拿 0(正北)替商家发明一个目标方向
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(compassConfig("\"tolerance\":15,\"holdSeconds\":3")));
        assertEquals("罗盘目标方位须为 0 至 359 度", missing.getMessage());

        for (int bearing : new int[] { -1, 360 }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAndNormalize(compassConfig(
                            "\"bearing\":" + bearing + ",\"tolerance\":15,\"holdSeconds\":3")));
            assertEquals("罗盘目标方位须为 0 至 359 度", error.getMessage());
        }
    }

    @Test
    void compassRejectsOutOfRangeToleranceHoldAndTexts() {
        for (int tol : new int[] { 4, 91 }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAndNormalize(compassConfig(
                            "\"bearing\":10,\"tolerance\":" + tol + ",\"holdSeconds\":3")));
            assertEquals("罗盘容差须为 5 至 90 度", error.getMessage());
        }
        for (int hold : new int[] { 0, 11 }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAndNormalize(compassConfig(
                            "\"bearing\":10,\"tolerance\":15,\"holdSeconds\":" + hold)));
            assertEquals("罗盘保持时长须为 1 至 10 秒", error.getMessage());
        }
        IllegalArgumentException xp = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(compassConfig(
                        "\"bearing\":10,\"tolerance\":15,\"holdSeconds\":3,\"xp\":1001")));
        assertEquals("罗盘指向奖励分须为 0 至 1000", xp.getMessage());

        StringBuilder longKicker = new StringBuilder();
        for (int i = 0; i < 33; i++) longKicker.append('转');
        IllegalArgumentException kicker = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(compassConfig(
                        "\"bearing\":10,\"tolerance\":15,\"holdSeconds\":3,\"kicker\":\"" + longKicker + "\"")));
        assertEquals("罗盘指向标题不能超过 32 字", kicker.getMessage());
    }

    // ==================== 喊一嗓子 shout(现场感契约 §4) ====================

    private static String shoutConfig(String body) {
        return "{\"schemaVersion\":1,\"shout\":{\"enabled\":true," + body + "}}";
    }

    @Test
    void shoutAcceptsFullConfigAtEveryBoundary() throws Exception {
        String normalized = validator.validateAndNormalize(shoutConfig(
                "\"kicker\":\"一起喊,让这儿听见\",\"seconds\":5,\"xp\":10"));
        JsonNode json = objectMapper.readTree(normalized);
        assertEquals(5, json.path("shout").path("seconds").asInt());

        // 边界都取得到:5 是这一屏的下限(再短一句「嗨」就过了),300 与挑战族上限齐平
        assertNotNull(validator.validateAndNormalize(shoutConfig("\"seconds\":300")));
    }

    @Test
    void shoutCountsAsAdvancedMechanic() {
        // 负控:把 hasEnabledMechanic 里 shout 那行摘掉,只配喊一嗓子的节点玩家走到点直接打卡、
        // 玩法静默不存在 —— 本用例立刻红
        assertTrue(validator.hasEnabledMechanic(shoutConfig("\"seconds\":5")));
    }

    @Test
    void shoutRejectsOutOfRangeSecondsAndTexts() {
        for (int seconds : new int[] { 0, 4, 301 }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAndNormalize(shoutConfig("\"seconds\":" + seconds)));
            assertEquals("喊一嗓子时长须为 5 至 300 秒", error.getMessage());
        }
        IllegalArgumentException xp = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(shoutConfig("\"seconds\":5,\"xp\":1001")));
        assertEquals("喊一嗓子奖励分须为 0 至 1000", xp.getMessage());

        StringBuilder longKicker = new StringBuilder();
        for (int i = 0; i < 33; i++) longKicker.append('喊');
        IllegalArgumentException kicker = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(shoutConfig(
                        "\"seconds\":5,\"kicker\":\"" + longKicker + "\"")));
        assertEquals("喊一嗓子标题不能超过 32 字", kicker.getMessage());
    }

    private static Path locate(String relative) {
        Path direct = Paths.get(relative);
        if (Files.exists(direct)) return direct;
        Path fromRoot = Paths.get("chengyinhub-admin").resolve(relative);
        if (Files.exists(fromRoot)) return fromRoot;
        throw new IllegalStateException("找不到 " + relative + ",cwd=" + Paths.get("").toAbsolutePath());
    }

    /**
     * 阶段 3:模板侧条件要能读 sys.exhausted —— 创作者可以写「力竭时这一站直接锁住」。
     * 摘掉 isReadableSysVar 里的那一项,本条必红。
     */
    @Test
    void requiresCanReadSysExhausted() throws Exception {
        String normalized = validator.validateAndNormalize("{\"schemaVersion\":1,"
                + "\"requires\":[{\"var\":\"sys.exhausted\",\"op\":\"EQ\",\"value\":0}],"
                + "\"timer\":{\"enabled\":true,\"durationSeconds\":60,\"timeoutResult\":\"FAILED\"}}");

        assertNotNull(normalized);
    }

    /** 配对:它只读不写 —— effects 里写 sys.exhausted 必须拒(与 sys.hp 同口径)。 */
    @Test
    void effectsCannotWriteSysExhausted() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize("{\"schemaVersion\":1,"
                        + "\"check\":{\"enabled\":true,\"checkId\":\"c1\",\"tier\":\"easy\","
                        + "\"successEffects\":[{\"op\":\"SET\",\"var\":\"sys.exhausted\",\"value\":0}]}}"));

        assertTrue(error.getMessage().contains("var"), "要拒在变量这一关");
    }

    // ==================== 扫码第四档 OVERLAY(AR 契约 §4.1) ====================

    // 2026-10-30 所有玩法都内嵌:单独扫码也默认内嵌,显式 inline 不再被拒;显式 fullscreen 照样生效
    @Test void scanOnlyPresentationDefaultsInlineAndAcceptsBothValues() throws Exception {
        String scan=scanConfig("\"kind\":\"TEXT\",\"reply\":\"欢迎\"");
        assertEquals("inline",AdvancedGameConfigValidator.resolvePresent(objectMapper.readTree(scan)));
        validator.validateAndNormalize(scan.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"present\":\"inline\""));
        assertEquals("fullscreen",AdvancedGameConfigValidator.resolvePresent(objectMapper.readTree(scan.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"present\":\"fullscreen\""))));
    }
    @Test void scanPlusMainGameplayUsesMainDefaultPresentation() throws Exception {
        JsonNode config=objectMapper.readTree("{\"scan\":{\"enabled\":true},\"qa\":{\"enabled\":true}}");
        assertEquals("inline",AdvancedGameConfigValidator.resolvePresent(config));
    }

    private static String scanConfig(String body) {
        return "{\"schemaVersion\":1,\"scan\":{\"enabled\":true," + body + "}}";
    }

    @Test
    void scanAcceptsOverlayKindWithOverlayUrl() throws Exception {
        String normalized = validator.validateAndNormalize(scanConfig(
                "\"kind\":\"OVERLAY\",\"overlayUrl\":\"https://cdn.chengyinhub.com/o/cat.png\",\"overlayScale\":75"));
        JsonNode json = objectMapper.readTree(normalized);
        assertEquals("OVERLAY", json.path("scan").path("kind").asText());
        assertEquals(75, json.path("scan").path("overlayScale").asInt());
    }

    @Test
    void scanRejectsOverlayWithoutPicture() {
        // 选了显形却没配图的配置根本不该存在到玩家手里
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(scanConfig("\"kind\":\"OVERLAY\"")));
        assertEquals("扫码要显形就得配一张叠加图", error.getMessage());
    }

    @Test
    void scanRejectsOverlayPictureThatIsNotHttpsOrTooLong() {
        IllegalArgumentException plain = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(scanConfig(
                        "\"kind\":\"OVERLAY\",\"overlayUrl\":\"http://evil.example.com/a.png\"")));
        assertEquals("扫码显形图地址必须是 https 链接或站内路径", plain.getMessage());

        StringBuilder tooLong = new StringBuilder("https://cdn.chengyinhub.com/");
        for (int i = 0; i < 600; i++) tooLong.append('a');
        IllegalArgumentException longError = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(scanConfig(
                        "\"kind\":\"OVERLAY\",\"overlayUrl\":\"" + tooLong + "\"")));
        assertEquals("扫码显形图地址不能超过 512 字", longError.getMessage());
    }

    @Test
    void scanRejectsOverlayScaleOutOfRange() {
        // 越界是拒而不是悄悄夹值 —— 静默改值又不说,商家看到的和配的不是一回事
        for (int scale : new int[] {10, 101}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAndNormalize(scanConfig(
                            "\"kind\":\"OVERLAY\",\"overlayUrl\":\"https://cdn.chengyinhub.com/a.png\",\"overlayScale\":" + scale)));
            assertEquals("扫码显形图占比须为 20 至 100", error.getMessage());
        }
        String ok = validator.validateAndNormalize(scanConfig(
                "\"kind\":\"OVERLAY\",\"overlayUrl\":\"https://cdn.chengyinhub.com/a.png\""));
        assertNotNull(ok, "不填占比应当放行(运行期按默认 60)");
    }

    // ==================== 显形档的真 AR(2026-09-22 用户定:平面放置 + 图像识别两种都做) ====================

    @Test
    void scanOverlayAcceptsNoneOrPlaneWithoutMarker() throws Exception {
        for (String mode : new String[] {"NONE", "PLANE"}) {
            JsonNode json = objectMapper.readTree(validator.validateAndNormalize(scanConfig(
                    "\"kind\":\"OVERLAY\",\"overlayUrl\":\"https://cdn.chengyinhub.com/a.png\",\"arMode\":\"" + mode + "\"")));
            assertEquals(mode, json.path("scan").path("arMode").asText());
        }
    }

    @Test
    void scanOverlayMarkerNeedsAMarkerPicture() throws Exception {
        // 图像识别靠一张实物照片当识别源 —— 没配就是一个永远识别不到的节点,零报错
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(scanConfig(
                        "\"kind\":\"OVERLAY\",\"overlayUrl\":\"https://cdn.chengyinhub.com/a.png\",\"arMode\":\"MARKER\"")));
        assertEquals("图像识别要配一张识别图", error.getMessage());

        JsonNode ok = objectMapper.readTree(validator.validateAndNormalize(scanConfig(
                "\"kind\":\"OVERLAY\",\"overlayUrl\":\"https://cdn.chengyinhub.com/a.png\","
                        + "\"arMode\":\"MARKER\",\"markerUrl\":\"https://cdn.chengyinhub.com/painting.jpg\"")));
        assertEquals("https://cdn.chengyinhub.com/painting.jpg", ok.path("scan").path("markerUrl").asText());
    }

    @Test
    void scanOverlayMarkerPictureMustBeHttpsOrInSite() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(scanConfig(
                        "\"kind\":\"OVERLAY\",\"overlayUrl\":\"https://cdn.chengyinhub.com/a.png\","
                                + "\"arMode\":\"MARKER\",\"markerUrl\":\"http://evil.example.com/m.jpg\"")));
        assertEquals("识别图地址必须是 https 链接或站内路径", error.getMessage());
    }

    // ==================== 显形档 AR 的 3D 模型(2026-09-23 用户定:有模型用模型,没模型退回立牌) ====================

    @Test
    void scanArAcceptsAGlbModel() throws Exception {
        JsonNode ok = objectMapper.readTree(validator.validateAndNormalize(scanConfig(
                "\"kind\":\"OVERLAY\",\"overlayUrl\":\"https://cdn.chengyinhub.com/a.png\","
                        + "\"arMode\":\"PLANE\",\"modelUrl\":\"https://cdn.chengyinhub.com/cat.glb?Expires=1&Signature=x\"")));
        assertEquals("https://cdn.chengyinhub.com/cat.glb?Expires=1&Signature=x", ok.path("scan").path("modelUrl").asText(),
                "OSS 签名地址带查询串,认 .glb 看的是路径不是整串");
    }

    @Test
    void scanModelNeedsArOnAndMustBeAGlb() {
        // 不开 AR 就没有三维空间,模型放不出来 —— 静默忽略会让作者以为配上了
        IllegalArgumentException noAr = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(scanConfig(
                        "\"kind\":\"OVERLAY\",\"overlayUrl\":\"https://cdn.chengyinhub.com/a.png\","
                                + "\"arMode\":\"NONE\",\"modelUrl\":\"https://cdn.chengyinhub.com/cat.glb\"")));
        assertEquals("开了 AR 才能放 3D 模型", noAr.getMessage());
        IllegalArgumentException notGlb = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(scanConfig(
                        "\"kind\":\"OVERLAY\",\"overlayUrl\":\"https://cdn.chengyinhub.com/a.png\","
                                + "\"arMode\":\"PLANE\",\"modelUrl\":\"https://cdn.chengyinhub.com/cat.fbx\"")));
        assertEquals("3D 模型只支持 .glb", notGlb.getMessage());
        IllegalArgumentException http = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(scanConfig(
                        "\"kind\":\"OVERLAY\",\"overlayUrl\":\"https://cdn.chengyinhub.com/a.png\","
                                + "\"arMode\":\"PLANE\",\"modelUrl\":\"http://evil.example.com/cat.glb\"")));
        assertEquals("3D 模型地址必须是 https 链接或站内路径", http.getMessage());
    }

    @Test
    void scanTreatsBlankOverlayMarkerAndModelUrlsAsNotFilled() throws Exception {
        // 已发出去的小程序会把没配的地址送成 "" —— 当「没填」摘掉,别把一整份文字档扫码判成「格式不对」(同 C-01)
        JsonNode ok = objectMapper.readTree(validator.validateAndNormalize(scanConfig(
                "\"kind\":\"TEXT\",\"reply\":\"到了\",\"overlayUrl\":\"\",\"markerUrl\":\"  \",\"modelUrl\":\"\"")));
        assertFalse(ok.path("scan").has("overlayUrl"));
        assertFalse(ok.path("scan").has("markerUrl"));
        assertFalse(ok.path("scan").has("modelUrl"));
    }

    @Test
    void scanRejectsUnknownArModeAndArOnOtherKinds() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(scanConfig(
                        "\"kind\":\"OVERLAY\",\"overlayUrl\":\"https://cdn.chengyinhub.com/a.png\",\"arMode\":\"GEO\"")));
        assertEquals("显形的 AR 方式只能是不开、平面放置或图像识别", unknown.getMessage());
        // AR 只挂在显形档上:文字档配了 PLANE 是作者误操作,静默忽略会让他以为开成了
        IllegalArgumentException wrongKind = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(scanConfig(
                        "\"kind\":\"TEXT\",\"reply\":\"到了\",\"arMode\":\"PLANE\"")));
        assertEquals("只有显形档能开 AR", wrongKind.getMessage());
    }

    @Test
    void scanRejectsUnknownKindWithFiveWayMessage() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(scanConfig("\"kind\":\"AR\"")));
        assertEquals("扫码回复只能是文字、语音、图片、显形或不回复", error.getMessage());
    }

    // 2026-10-22 主人:扫码参与可以不回复 —— NONE 不要求回复文字,也不带任何回复媒体
    @Test
    void scanNoneNeedsNoReply() throws Exception {
        assertDoesNotThrow(() -> validator.validateAndNormalize(scanConfig("\"kind\":\"NONE\"")));
    }

    // ===== 2026-10-11 选项问答:判断答案对错开关与选项反馈图片 =====
    private static String qaWith(String judge, String fbImgsA, boolean anyCorrect) {
        return "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\"," + judge + "\"title\":\"题\",\"options\":["
                + "{\"id\":\"a\",\"label\":\"甲\",\"correct\":" + anyCorrect + (fbImgsA == null ? "" : ",\"fbImgs\":" + fbImgsA) + "},"
                + "{\"id\":\"b\",\"label\":\"乙\",\"correct\":false}]}}";
    }

    @Test
    void qaJudgeOffNeedsNoCorrectAnswerAndKeepsJudgingSettings() throws Exception {
        AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
        String saved = validator.validateAndNormalize(qaWith("\"judge\":false,\"maxTries\":2,\"reveal\":true,", null, false));
        com.fasterxml.jackson.databind.JsonNode qa = new com.fasterxml.jackson.databind.ObjectMapper().readTree(saved).path("qa");
        assertEquals(false, qa.path("judge").asBoolean(true));
        assertEquals(2, qa.path("maxTries").asInt());
        assertEquals(true, qa.path("reveal").asBoolean());
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(qaWith("", null, false)), "缺省判断开启:仍要正确答案");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(qaWith("\"judge\":\"no\",", null, true)));
    }

    @Test
    void qaOptionFeedbackImagesAreLimitedToThreeValidUrls() {
        AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
        validator.validateAndNormalize(qaWith("", "[\"https://a/1.jpg\",\"/profile/2.jpg\",\"https://a/3.jpg\"]", true));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(qaWith("", "[\"https://a/1.jpg\",\"https://a/2.jpg\",\"https://a/3.jpg\",\"https://a/4.jpg\"]", true)));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(qaWith("", "[\"http://insecure/1.jpg\"]", true)));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(qaWith("", "\"https://a/1.jpg\"", true)));
    }

    /** F2 T03:mods[].var 读 counter.* 属性,与静态 value 二选一。 */
    @Test
    void journeyCheckAcceptsCounterVarModsAndRejectsMixedOrForeignVars() {
        validator.validateAndNormalize(journey("check",
                "{\"enabled\":true,\"checkId\":\"lift\",\"tier\":\"medium\",\"skill\":\"counter.str\","
                        + "\"mods\":[{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.ready\"},\"var\":\"counter.focus\"}]}"));
        IllegalArgumentException mixed = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("check",
                        "{\"enabled\":true,\"checkId\":\"lift\",\"tier\":\"easy\","
                                + "\"mods\":[{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.ready\"},\"var\":\"counter.focus\",\"value\":1}]}")));
        assertTrue(mixed.getMessage().contains("var"), mixed.getMessage());
        IllegalArgumentException foreign = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(journey("check",
                        "{\"enabled\":true,\"checkId\":\"lift\",\"tier\":\"easy\","
                                + "\"mods\":[{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.ready\"},\"var\":\"sys.luck\"}]}")));
        assertTrue(foreign.getMessage().contains("counter."), foreign.getMessage());
    }
}
