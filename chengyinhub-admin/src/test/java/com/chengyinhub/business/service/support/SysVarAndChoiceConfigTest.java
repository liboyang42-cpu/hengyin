package com.chengyinhub.business.service.support;

import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R14-PLAY 配置面:选择结果 effects(分支选项 / 问答选项 / 五个通过类玩法)、check 的
 * critEffects / fumbleEffects、以及只读系统变量 sys.* 在条件里的白名单。
 *
 * <p>负控:sys.* 在 effects 里被放行、未知 sys 变量被放行、新 effects 字段漏过校验,
 * 任一发生本类对应用例立红。
 */
class SysVarAndChoiceConfigTest {

    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 选择结果 effects ====================

    @Test
    void branchOptionEffectsAndQaOptionEffectsAccepted() throws Exception {
        String config = "{\"schemaVersion\":1,\"branch\":{\"enabled\":true,\"startStepId\":\"ask\",\"steps\":["
                + "{\"id\":\"ask\",\"options\":[{\"id\":\"a\",\"label\":\"甲\",\"nextStepId\":\"done\","
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"counter.disposition\",\"value\":2}]}]},"
                + "{\"id\":\"done\",\"terminal\":true,\"outcomeCode\":\"done\",\"options\":[]}]},"
                + "\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"title\":\"题\",\"options\":["
                + "{\"id\":\"one\",\"label\":\"一\",\"correct\":true,"
                + "\"effects\":[{\"op\":\"INC\",\"var\":\"counter.attitude\",\"value\":-1}]},"
                + "{\"id\":\"two\",\"label\":\"二\",\"correct\":false}]}}";

        JsonNode root = objectMapper.readTree(validator.validateAndNormalize(config));

        assertEqualsValue(2, root.path("branch").path("steps").get(0).path("options").get(0)
                .path("effects").get(0).path("value"));
        assertEqualsValue(-1, root.path("qa").path("options").get(0).path("effects").get(0).path("value"));
    }

    /** 负控:放过 sys.hp 这类保留变量写效果里,本用例红。 */
    @Test
    void optionEffectsStillRejectSystemVariables() {
        String config = "{\"schemaVersion\":1,\"branch\":{\"enabled\":true,\"startStepId\":\"ask\",\"steps\":["
                + "{\"id\":\"ask\",\"options\":[{\"id\":\"a\",\"label\":\"甲\",\"nextStepId\":\"done\","
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"sys.hp\",\"value\":1}]}]},"
                + "{\"id\":\"done\",\"terminal\":true,\"outcomeCode\":\"done\",\"options\":[]}]}}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(config));
        assertTrue(error.getMessage().contains("var"), error.getMessage());
    }

    @Test
    void kitPassEffectsAcceptedForAllFiveKits() throws Exception {
        String config = "{\"schemaVersion\":1,"
                + "\"sort\":{\"enabled\":true,\"prompt\":\"排\",\"items\":[{\"id\":\"a\",\"label\":\"甲\"},"
                + "{\"id\":\"b\",\"label\":\"乙\"}],\"answerOrder\":[\"a\",\"b\"],"
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"clue.notes_restored\",\"value\":1}]},"
                + "\"match\":{\"enabled\":true,\"prompt\":\"连\","
                + "\"left\":[{\"id\":\"l1\",\"label\":\"左一\"},{\"id\":\"l2\",\"label\":\"左二\"}],"
                + "\"right\":[{\"id\":\"r1\",\"label\":\"右一\"},{\"id\":\"r2\",\"label\":\"右二\"}],"
                + "\"pairs\":[[\"l1\",\"r1\"],[\"l2\",\"r2\"]],"
                + "\"effects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.connected\"}]},"
                + "\"classify\":{\"enabled\":true,\"prompt\":\"分\","
                + "\"bins\":[{\"id\":\"b1\",\"label\":\"箱一\"},{\"id\":\"b2\",\"label\":\"箱二\"}],"
                + "\"items\":[{\"id\":\"i1\",\"label\":\"卡一\"},{\"id\":\"i2\",\"label\":\"卡二\"}],"
                + "\"answer\":{\"i1\":\"b1\",\"i2\":\"b2\"},"
                + "\"effects\":[{\"op\":\"INC\",\"var\":\"counter.classified\",\"value\":1}]},"
                + "\"hiddenObject\":{\"enabled\":true,\"title\":\"找\",\"imageUrl\":\"https://x/y.png\","
                + "\"hotspots\":[{\"id\":\"h1\",\"label\":\"一\",\"x\":0.2,\"y\":0.2,\"r\":0.1},"
                + "{\"id\":\"h2\",\"label\":\"二\",\"x\":0.8,\"y\":0.8,\"r\":0.1},"
                + "{\"id\":\"h3\",\"label\":\"三\",\"x\":0.5,\"y\":0.5,\"r\":0.1}],"
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"clue.all_found\",\"value\":1}]},"
                + "\"pricePair\":{\"enabled\":true,\"title\":\"猜\",\"items\":["
                + "{\"id\":\"p1\",\"name\":\"甲\",\"imageUrl\":\"https://x/1.png\"},"
                + "{\"id\":\"p2\",\"name\":\"乙\",\"imageUrl\":\"https://x/2.png\",\"correct\":true},"
                + "{\"id\":\"p3\",\"name\":\"丙\",\"imageUrl\":\"https://x/3.png\"}],\"xp\":5,"
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"clue.guess_hit\",\"value\":1}]}}";

        JsonNode root = objectMapper.readTree(validator.validateAndNormalize(config));

        assertEqualsValue(1, root.path("sort").path("effects").get(0).path("value"));
        assertEqualsValue(1, root.path("classify").path("effects").get(0).path("value"));
        assertEqualsValue(1, root.path("pricePair").path("effects").get(0).path("value"));
        assertTrue(root.path("hiddenObject").path("effects").isArray());
    }

    @Test
    void kitPassEffectsRejectBadVarNamespace() {
        String config = "{\"schemaVersion\":1,\"sort\":{\"enabled\":true,\"prompt\":\"排\","
                + "\"items\":[{\"id\":\"a\",\"label\":\"甲\"},{\"id\":\"b\",\"label\":\"乙\"}],"
                + "\"answerOrder\":[\"a\",\"b\"],"
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"ledger_page\",\"value\":1}]}}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(config));
        assertTrue(error.getMessage().contains("var"), error.getMessage());
    }

    @Test
    void critAndFumbleEffectsAcceptedAndValidated() throws Exception {
        String config = "{\"schemaVersion\":1,\"check\":{\"enabled\":true,\"checkId\":\"c1\","
                + "\"tier\":\"easy\","
                + "\"critEffects\":[{\"op\":\"SET\",\"var\":\"clue.crit\",\"value\":1}],"
                + "\"fumbleEffects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.fumbled\"}]}}";

        JsonNode root = objectMapper.readTree(validator.validateAndNormalize(config));

        assertTrue(root.path("check").path("critEffects").isArray());
        assertTrue(root.path("check").path("fumbleEffects").isArray());

        String bad = config.replace("{\"op\":\"SET\",\"var\":\"clue.crit\",\"value\":1}",
                "{\"op\":\"SET\",\"var\":\"sys.luck\",\"value\":2}");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(bad));
        assertTrue(error.getMessage().contains("sys.luck"), error.getMessage());
    }

    // ==================== 只读系统变量 ====================

    @Test
    void conditionsAcceptReadableSystemVariables() throws Exception {
        String config = "{\"schemaVersion\":1,\"requires\":["
                + "{\"var\":\"sys.hp\",\"op\":\"LTE\",\"value\":3},"
                + "{\"var\":\"sys.luck\",\"op\":\"GTE\",\"value\":1},"
                + "{\"var\":\"sys.asked.5009.night\",\"op\":\"EQ\",\"value\":1},"
                + "{\"var\":\"sys.mistakes.5009\",\"op\":\"GTE\",\"value\":2},"
                + "{\"var\":\"sys.outcome.5009\",\"op\":\"NE\",\"value\":3}],"
                + "\"check\":{\"enabled\":true,\"checkId\":\"c1\",\"tier\":\"easy\","
                + "\"mods\":[{\"when\":{\"var\":\"sys.outcome.5009\",\"op\":\"EQ\",\"value\":1},\"value\":2}],"
                + "\"advantageIf\":[{\"var\":\"sys.hp\",\"op\":\"GTE\",\"value\":5}],"
                + "\"disadvantageIf\":[{\"var\":\"sys.mistakes.5009\",\"op\":\"GTE\",\"value\":1}]},"
                + "\"outcome\":{\"facts\":[{\"when\":{\"var\":\"sys.outcome.5009\",\"op\":\"EQ\",\"value\":1},"
                + "\"text\":\"选了更正\"}]},"
                + "\"revisit\":{\"changes\":[{\"when\":{\"var\":\"sys.luck\",\"op\":\"LTE\",\"value\":0},"
                + "\"text\":\"运气用光了\"}]},"
                + "\"brief\":{\"carry\":[{\"when\":{\"var\":\"sys.asked.5009.night\",\"op\":\"EQ\",\"value\":1},"
                + "\"text\":\"带上一句回话\"}]}}";

        assertNotNull(validator.validateAndNormalize(config));
    }

    /**
     * 负控:白名单外的 sys.* 被放行(比如 sys.call),本用例红。
     *
     * <p>⚠️ sys.exhausted 2026-09-22(阶段 3)起**已进只读白名单** —— 力竭要有后果,
     * 创作者得能写「力竭时这一站直接锁住」。它移到了下面 conditionsAcceptExhausted;
     * 效果侧仍然拒,见 effectsStillOnlyAllowSysLuck。
     */
    @Test
    void conditionsRejectUnknownSystemVariables() {
        for (String bad : new String[] { "sys.call", "sys.damage.5009", "sys.mistakes.0",
                "sys.asked.5009.", "sys.outcome.-1", "sys.hp.max" }) {
            String config = "{\"schemaVersion\":1,\"requires\":[{\"var\":\"" + bad
                    + "\",\"op\":\"EQ\",\"value\":1}]}";
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAndNormalize(config), bad);
            assertTrue(error.getMessage().contains("sys") || error.getMessage().contains("var"),
                    error.getMessage());
        }
    }

    /** 阶段 3:sys.exhausted 是可读的系统变量。把它从只读白名单摘掉,本用例红。 */
    @Test
    void conditionsAcceptExhausted() {
        assertNotNull(validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"requires\":[{\"var\":\"sys.exhausted\",\"op\":\"EQ\",\"value\":0}]}"));
    }

    /** 负控:effects 里放行 sys.* 时本用例红 —— 效果只许写具名键(sys.luck 除外)。 */
    @Test
    void effectsStillOnlyAllowSysLuck() {
        for (String bad : new String[] { "sys.hp", "sys.exhausted", "sys.outcome.5009", "sys.asked.5009.night" }) {
            String config = "{\"schemaVersion\":1,\"effects\":[{\"op\":\"SET\",\"var\":\"" + bad
                    + "\",\"value\":1}]}";
            assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(config), bad);
        }
        assertNotNull(validator.validateAndNormalize("{\"schemaVersion\":1,\"effects\":["
                + "{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":2}]}"));
    }

    // ==================== 公开投影 ====================

    /** 负控:投影里出现 critEffects / 选项 effects / 玩法段 effects 时本用例红。 */
    @Test
    void publicProjectionNeverCarriesChoiceEffects() throws Exception {
        String config = "{\"schemaVersion\":1,\"branch\":{\"enabled\":true,\"startStepId\":\"ask\",\"steps\":["
                + "{\"id\":\"ask\",\"options\":[{\"id\":\"a\",\"label\":\"甲\",\"nextStepId\":\"done\","
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"counter.disposition\",\"value\":1}]}]},"
                + "{\"id\":\"done\",\"terminal\":true,\"outcomeCode\":\"done\",\"options\":[]}]},"
                + "\"check\":{\"enabled\":true,\"checkId\":\"c1\",\"tier\":\"easy\","
                + "\"critEffects\":[{\"op\":\"SET\",\"var\":\"clue.crit\",\"value\":1}],"
                + "\"fumbleEffects\":[{\"op\":\"SET\",\"var\":\"clue.fumble\",\"value\":1}]},"
                + "\"sort\":{\"enabled\":true,\"prompt\":\"排\",\"items\":[{\"id\":\"a\",\"label\":\"甲\"},"
                + "{\"id\":\"b\",\"label\":\"乙\"}],\"answerOrder\":[\"a\",\"b\"],"
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"clue.sorted\",\"value\":1}]}}";

        String safe = AdvancedGamePublicProjection.safeConfigJson(config);

        assertNotNull(safe);
        assertFalse(safe.contains("critEffects"), safe);
        assertFalse(safe.contains("fumbleEffects"), safe);
        assertFalse(safe.contains("clue.sorted"), safe);
        assertFalse(safe.contains("counter.disposition"), safe);
    }

    private static void assertEqualsValue(int expected, JsonNode node) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, node.asInt());
    }
}
