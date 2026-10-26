package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2-B10:主题数值规则 journeyRules 的单一校验入口契约。
 *
 * <p>每条约束一例非法必须被拒(中文人话),缺省值补齐,空/缺省 = 未启用返回 null。
 */
class JourneyRulesValidatorTest {
    @Test
    void thoughtOnlyThemeHasRuntimeStateWithoutEnablingChecks() {
        JourneyRules rules = JourneyRules.parse("{\"schemaVersion\":1,\"checkEnabled\":false,"
                + "\"thoughts\":[{\"key\":\"afternoon\",\"name\":\"那个下午\",\"need\":120}]}");
        assertTrue(rules.hasRuntimeState());
        assertFalse(rules.isCheckEnabled());
        assertFalse(JourneyRules.parse(null).hasRuntimeState());
    }

    private final JourneyRulesValidator validator = new JourneyRulesValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String config(String body) {
        return "{\"schemaVersion\":1,\"checkEnabled\":true" + body + "}";
    }

    private IllegalArgumentException rejected(String json) {
        return assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(json));
    }

    @Test
    void rejectsCoercedSchemaVersionsAndOverflowingThoughtThresholds() {
        for (String version : new String[] { "\"1\"", "1.5", "4294967297", "true" }) {
            rejected("{\"schemaVersion\":" + version + "}");
        }
        for (String need : new String[] { "4294967297", "18446744073709551617", "1.5", "\"10\"" }) {
            rejected(config(",\"thoughts\":[{\"key\":\"clue\",\"name\":\"线索\",\"need\":" + need + "}]"));
        }
    }

    @Test
    void thoughtNodeReferencesRejectCoercionAndPreserveExactLongStrings() throws Exception {
        String prefix = ",\"thoughts\":[{\"key\":\"clue\",\"name\":\"线索\",\"need\":10,"
                + "\"when\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":";
        for (String id : new String[] { "1.5", "true", "0", "-1", "18446744073709551617",
                "\"9223372036854775808\"", "\"1e3\"", "\"01\"" }) {
            rejected(config(prefix + id + "}]}]"));
        }
        for (String id : new String[] { "1", "\"9007199254740993\"", "9223372036854775807" }) {
            JsonNode normalized = objectMapper.readTree(validator.validateAndNormalize(config(prefix + id + "}]}]")));
            assertEquals(Long.parseLong(id.replace("\"", "")),
                    normalized.path("thoughts").get(0).path("when").get(0).path("nodeId").longValue());
        }
    }

    @Test
    void preservesGenericInitialAttributesWithoutEnablingLegacyChecks() throws Exception {
        String raw = "{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":["
                + "{\"key\":\"counter.focus\",\"label\":\"专注\",\"initial\":2,\"min\":0,\"max\":8,\"visible\":true},"
                + "{\"key\":\"relation.guide\",\"label\":\"向导\",\"initial\":0,\"min\":-1,\"max\":1,\"visible\":false}]}";
        JsonNode result = objectMapper.readTree(validator.validateAndNormalize(raw));
        assertTrue(result.path("stateEnabled").asBoolean());
        assertFalse(result.path("checkEnabled").asBoolean());
        assertEquals("专注", result.path("attributes").get(0).path("label").asText());
        assertEquals(2, result.path("attributes").get(0).path("initial").asInt());
        assertFalse(result.path("attributes").get(1).path("visible").asBoolean());
        assertEquals(result, objectMapper.readTree(validator.validateAndNormalize(result.toString())));
    }

    @Test
    void rejectsInitialAttributeCollisionAndInvalidBounds() {
        String attr = "{\"key\":\"counter.focus\",\"label\":\"专注\",\"initial\":2,\"min\":0,\"max\":8}";
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                config(",\"attributes\":[" + attr + "," + attr + "]")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                config(",\"attributes\":[" + attr.replace("counter.focus", "sys.hp") + "]")));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                config(",\"attributes\":[" + attr.replace("\"max\":8", "\"max\":1") + "]")));
    }

    // ---------- 通过 / 缺省补齐 / 未启用 ----------

    @Test
    void blankMeansDisabledAndReturnsNull() {
        assertNull(validator.validateAndNormalize(null));
        assertNull(validator.validateAndNormalize(""));
        assertNull(validator.validateAndNormalize("   "));
    }

    @Test
    void missingFieldsAreFilledWithContractDefaults() throws Exception {
        String normalized = validator.validateAndNormalize("{\"schemaVersion\":1,\"checkEnabled\":true}");

        JsonNode json = objectMapper.readTree(normalized);
        assertEquals(1, json.path("schemaVersion").asInt());
        assertTrue(json.path("checkEnabled").asBoolean());
        assertEquals(10, json.path("hp").path("max").asInt());
        assertEquals(10, json.path("hp").path("init").asInt());
        assertEquals(3, json.path("luck").path("max").asInt());
        assertEquals(3, json.path("luck").path("init").asInt());
        assertEquals(10, json.path("tiers").path("easy").path("dc").asInt());
        assertEquals(1, json.path("tiers").path("easy").path("damage").asInt());
        assertEquals(13, json.path("tiers").path("medium").path("dc").asInt());
        assertEquals(16, json.path("tiers").path("hard").path("dc").asInt());
        assertEquals(2, json.path("recovery").path("stationEndIfHurt").asInt());
        assertEquals(6, json.path("recovery").path("stageRestHpFloor").asInt());
        assertEquals(1, json.path("recovery").path("stageRestLuckFloor").asInt());
        assertEquals(5, json.path("recovery").path("exhaustRestHp").asInt());
        assertEquals(1, json.path("fumbleExtraDamage").asInt());
        assertEquals(6, json.path("modCap").asInt());
        assertFalse(json.path("recovery").has("exhaustTag"), "没填 exhaustTag 不该被补出一个默认键");
    }

    /** 关闭已配的规则要能落库:显式 checkEnabled=false 归一化后给非 null(否则关不掉)。 */
    @Test
    void explicitDisableIsNormalizedNotDropped() throws Exception {
        String normalized = validator.validateAndNormalize("{\"schemaVersion\":1,\"checkEnabled\":false}");

        assertNotNull(normalized);
        assertFalse(objectMapper.readTree(normalized).path("checkEnabled").asBoolean());
    }

    /** 缺省 init 跟着 max 收口,不能被默认的 10/3 顶出越界。 */
    @Test
    void defaultInitIsClampedToMax() throws Exception {
        String normalized = validator.validateAndNormalize(
                config(",\"hp\":{\"max\":6},\"luck\":{\"max\":1}"));

        JsonNode json = objectMapper.readTree(normalized);
        assertEquals(6, json.path("hp").path("init").asInt());
        assertEquals(1, json.path("luck").path("init").asInt());
    }

    @Test
    void unknownFieldsAreNotProjectedIntoNormalizedOutput() throws Exception {
        String normalized = validator.validateAndNormalize(
                config(",\"whatever\":{\"secret\":\"x\"}"));

        assertFalse(normalized.contains("whatever"));
    }

    // ---------- 逐条约束:一例非法被拒 ----------

    @Test
    void rejectsUnknownVersion() {
        assertEquals("旅程数值规则版本不支持",
                rejected("{\"schemaVersion\":2}").getMessage());
    }

    @Test
    void rejectsMalformedJsonAndNonObject() {
        assertEquals("旅程数值规则不是合法 JSON", rejected("{not-json").getMessage());
        assertEquals("旅程数值规则必须是对象", rejected("[]").getMessage());
    }

    @Test
    void rejectsHpRangeViolations() {
        assertTrue(rejected(config(",\"hp\":{\"max\":0}")).getMessage().contains("hp.max 须为 1 至 20"));
        assertTrue(rejected(config(",\"hp\":{\"max\":21}")).getMessage().contains("hp.max 须为 1 至 20"));
        assertTrue(rejected(config(",\"hp\":{\"max\":6,\"init\":7}")).getMessage().contains("hp.init 须为 1 至 6"));
    }

    @Test
    void rejectsLuckRangeViolations() {
        assertTrue(rejected(config(",\"luck\":{\"max\":6}")).getMessage().contains("luck.max 须为 0 至 5"));
        assertTrue(rejected(config(",\"luck\":{\"max\":2,\"init\":3}")).getMessage().contains("luck.init 须为 0 至 2"));
    }

    @Test
    void rejectsDcOutOfRangeAndOutOfOrder() {
        assertTrue(rejected(config(",\"tiers\":{\"easy\":{\"dc\":4}}")).getMessage()
                .contains("tiers.easy.dc 须为 5 至 25"));
        assertTrue(rejected(config(",\"tiers\":{\"hard\":{\"dc\":26}}")).getMessage()
                .contains("tiers.hard.dc 须为 5 至 25"));
        assertTrue(rejected(config(",\"tiers\":{\"easy\":{\"dc\":20},\"medium\":{\"dc\":13}}")).getMessage()
                .contains("三档难度的 dc 必须 easy < medium < hard"));
    }

    @Test
    void rejectsDamageOutOfRange() {
        assertTrue(rejected(config(",\"tiers\":{\"easy\":{\"damage\":6}}")).getMessage()
                .contains("tiers.easy.damage 须为 0 至 5"));
    }

    @Test
    void rejectsRecoveryOutOfRange() {
        assertTrue(rejected(config(",\"recovery\":{\"stationEndIfHurt\":11}")).getMessage()
                .contains("recovery.stationEndIfHurt 须为 0 至 10"));
        assertTrue(rejected(config(",\"recovery\":{\"stageRestLuckFloor\":4}")).getMessage()
                .contains("recovery.stageRestLuckFloor 须为 0 至 3"));
        assertTrue(rejected(config(",\"recovery\":{\"exhaustRestHp\":-1}")).getMessage()
                .contains("recovery.exhaustRestHp 须为 0 至 10"));
    }

    @Test
    void rejectsExhaustTagThatIsNotATagStateKey() {
        assertTrue(rejected(config(",\"recovery\":{\"exhaustTag\":\"clue.injured\"}")).getMessage()
                .contains("recovery.exhaustTag"));
        assertTrue(rejected(config(",\"recovery\":{\"exhaustTag\":\"tag.Injured\"}")).getMessage()
                .contains("recovery.exhaustTag"));
        assertTrue(rejected(config(",\"recovery\":{\"exhaustTag\":\"tag.\"}")).getMessage()
                .contains("recovery.exhaustTag"));
    }

    @Test
    void rejectsFumbleExtraDamageAndModCapOutOfRange() {
        assertTrue(rejected(config(",\"fumbleExtraDamage\":4")).getMessage()
                .contains("fumbleExtraDamage 须为 0 至 3"));
        assertTrue(rejected(config(",\"modCap\":11")).getMessage().contains("modCap 须为 0 至 10"));
    }

    @Test
    void rejectsNonIntegerAndNonBooleanValues() {
        assertTrue(rejected(config(",\"tiers\":{\"easy\":{\"dc\":10.5}}")).getMessage()
                .contains("tiers.easy.dc 必须是整数"));
        assertTrue(rejected(config(",\"hp\":{\"enabled\":\"yes\"}")).getMessage()
                .contains("hp.enabled 必须是布尔值"));
    }

    // ---------- POJO 解析(运行期读侧入口) ----------

    @Test
    void parseReturnsNormalizedPojo() {
        JourneyRules rules = JourneyRules.parse("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"recovery\":{\"exhaustTag\":\"tag.injured\"}}");

        assertTrue(rules.isCheckEnabled());
        assertEquals(1, rules.getSchemaVersion());
        assertEquals(10, rules.getHp().getMax());
        assertEquals("tag.injured", rules.getRecovery().getExhaustTag());
    }

    @Test
    void parseBlankReturnsDisabledDefaultObject() {
        JourneyRules rules = JourneyRules.parse(null);

        assertFalse(rules.isCheckEnabled());
        assertNotNull(rules.getHp());
        assertNull(rules.getRecovery().getExhaustTag());
    }

    // ==================== 思维阁(2026-09-22 定层) ====================
    // 念头是主题级、跨章节的累积状态,所以定义住在 journeyRules,与 hp/luck 同层。
    // 效果目前只有一种:想通了 → 幸运上限 +1。将来要别的效果再加字段(加字段不破存量)。

    @Test
    void keepsThoughtsWithNameAndStepThreshold() throws Exception {
        String raw = config(",\"thoughts\":[{\"key\":\"afternoon\",\"name\":\"那个永远不会结束的下午\","
                + "\"desc\":\"你总觉得，有一个下午从来没有结束过。\",\"need\":120}]");
        JsonNode result = objectMapper.readTree(validator.validateAndNormalize(raw));
        JsonNode thought = result.path("thoughts").get(0);
        assertEquals("afternoon", thought.path("key").asText());
        assertEquals("那个永远不会结束的下午", thought.path("name").asText());
        assertEquals(120, thought.path("need").asInt());
    }

    @Test
    void rejectsThoughtWithoutNameOrThreshold() {
        assertTrue(rejected(config(",\"thoughts\":[{\"key\":\"a\",\"need\":10}]"))
                .getMessage().contains("念头"), "没名字的念头玩家看不懂自己得到了什么");
        assertTrue(rejected(config(",\"thoughts\":[{\"key\":\"a\",\"name\":\"x\",\"need\":0}]"))
                .getMessage().contains("念头"), "阈值为 0 的念头一得到就想通,等于没有这个过程");
    }

    // 2026-09-22 用户裁决:伏笔不能只靠微信步数 —— 答出特定选项(选项效果贴的标签)也能得到 / 想通。
    @Test
    void keepsThoughtObtainedAndSettledByChosenOptionTags() throws Exception {
        String raw = config(",\"thoughts\":[{\"key\":\"window\",\"name\":\"那扇窗\","
                + "\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.saw_window\"}],"
                + "\"doneWhen\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.opened_window\"}]}]");
        JsonNode thought = objectMapper.readTree(validator.validateAndNormalize(raw)).path("thoughts").get(0);
        assertEquals("tag.saw_window", thought.path("when").get(0).path("value").asText());
        assertEquals("tag.opened_window", thought.path("doneWhen").get(0).path("value").asText());
        assertTrue(thought.path("need").isMissingNode(), "只靠选项想通的念头不带步数阈值");
    }

    @Test
    void keepsThoughtWithBothStepsAndOptionTag() throws Exception {
        String raw = config(",\"thoughts\":[{\"key\":\"window\",\"name\":\"那扇窗\",\"need\":3000,"
                + "\"doneWhen\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.opened_window\"}]}]");
        JsonNode thought = objectMapper.readTree(validator.validateAndNormalize(raw)).path("thoughts").get(0);
        assertEquals(3000, thought.path("need").asInt());
        assertEquals(1, thought.path("doneWhen").size());
    }

    @Test
    void rejectsThoughtThatCanNeverSettleOrUsesUnknownCondition() {
        assertTrue(rejected(config(",\"thoughts\":[{\"key\":\"a\",\"name\":\"x\","
                + "\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.b\"}]}]"))
                .getMessage().contains("想通"), "既没步数也没条件 ⇒ 这个念头永远想不通");
        assertTrue(rejected(config(",\"thoughts\":[{\"key\":\"a\",\"name\":\"x\","
                + "\"doneWhen\":[{\"op\":\"HAS_TAG\",\"value\":\"hurried\"}]}]"))
                .getMessage().contains("tag."), "标签必须是 tag. 开头 —— 选项效果只会贴这种");
        assertTrue(rejected(config(",\"thoughts\":[{\"key\":\"a\",\"name\":\"x\","
                + "\"doneWhen\":[{\"op\":\"EVAL\",\"value\":\"1\"}]}]"))
                .getMessage().contains("念头"), "不认识的条件不许静默当成没配");
    }

    @Test
    void rejectsDuplicateThoughtKeys() {
        assertTrue(rejected(config(",\"thoughts\":["
                + "{\"key\":\"a\",\"name\":\"甲\",\"need\":10},"
                + "{\"key\":\"a\",\"name\":\"乙\",\"need\":20}]"))
                .getMessage().contains("念头"), "同 key 的两个念头会让进度互相覆盖");
    }

    @Test
    void thoughtsAreOptionalAndAbsentByDefault() throws Exception {
        JsonNode result = objectMapper.readTree(validator.validateAndNormalize(config("")));
        assertTrue(result.path("thoughts").isMissingNode() || result.path("thoughts").size() == 0,
                "没配思维阁的主题不该凭空多出一个空数组");
    }

    @Test
    void parsedRulesCarryThoughtsToRuntime() {
        // ⚠️ JourneyRules 没开 @JsonIgnoreProperties:validator 输出里多一个字段而 POJO 没有,
        //    配了思维阁的主题一 parse 就抛「旅程数值规则无法解析」——写侧绿、读侧炸。
        JourneyRules rules = JourneyRules.parse(config(
                ",\"thoughts\":[{\"key\":\"afternoon\",\"name\":\"那个下午\",\"need\":120}]"));
        assertEquals(1, rules.getThoughts().size());
        assertEquals("afternoon", rules.getThoughts().get(0).path("key").asText());
        assertEquals(120, rules.getThoughts().get(0).path("need").asInt());
    }

    /** F2 T04:natAuto 只在作者显式配置时进规范化结果;老配置的规范化 JSON 一字不变。 */
    @Test
    void natAutoIsOnlyEmittedWhenExplicitlyConfigured() throws Exception {
        String legacy = validator.validateAndNormalize(config(""));
        assertFalse(objectMapper.readTree(legacy).has("natAuto"), legacy);
        String off = validator.validateAndNormalize(config(",\"natAuto\":false"));
        assertFalse(objectMapper.readTree(off).path("natAuto").asBoolean(true), off);
        assertTrue(rejected(config(",\"natAuto\":\"no\"")).getMessage().contains("natAuto"));
    }
}
