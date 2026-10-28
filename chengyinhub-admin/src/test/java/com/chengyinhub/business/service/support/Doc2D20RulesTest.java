package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文档二 L8:规则难度三档(E1)与特质「开局自带」(A7)。 */
class Doc2D20RulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JourneyRulesValidator validator = new JourneyRulesValidator();

    private JsonNode normalize(String raw) throws Exception {
        return JSON.readTree(validator.validateAndNormalize(raw));
    }

    @Test
    void easyPresetOverridesTiersModCapAndMomentum() throws Exception {
        JsonNode out = normalize("{\"schemaVersion\":1,\"checkEnabled\":true,\"difficulty\":\"easy\","
                + "\"tiers\":{\"easy\":{\"dc\":20},\"medium\":{\"dc\":21},\"hard\":{\"dc\":22}},\"modCap\":1,"
                + "\"rpg\":{\"momentum\":{\"enabled\":true,\"max\":10,\"init\":9,\"gainOnFail\":0,\"maxSpend\":1}}}");
        assertEquals("easy", out.path("difficulty").asText());
        assertEquals(8, out.path("tiers").path("easy").path("dc").asInt());
        assertEquals(11, out.path("tiers").path("medium").path("dc").asInt());
        assertEquals(14, out.path("tiers").path("hard").path("dc").asInt());
        assertEquals(8, out.path("modCap").asInt());
        JsonNode momentum = out.path("rpg").path("momentum");
        assertTrue(momentum.path("enabled").asBoolean());
        assertEquals(8, momentum.path("max").asInt());
        assertEquals(3, momentum.path("init").asInt());
        assertEquals(2, momentum.path("gainOnFail").asInt());
        assertEquals(4, momentum.path("maxSpend").asInt());
    }

    @Test
    void standardAndHardPresetsMatchOwnerNumbers() throws Exception {
        JsonNode standard = normalize("{\"schemaVersion\":1,\"checkEnabled\":true,\"difficulty\":\"standard\","
                + "\"rpg\":{\"momentum\":{\"enabled\":true}}}");
        assertEquals(10, standard.path("tiers").path("easy").path("dc").asInt());
        assertEquals(16, standard.path("tiers").path("hard").path("dc").asInt());
        assertEquals(6, standard.path("modCap").asInt());
        assertEquals(6, standard.path("rpg").path("momentum").path("max").asInt());
        assertEquals(2, standard.path("rpg").path("momentum").path("init").asInt());
        assertEquals(1, standard.path("rpg").path("momentum").path("gainOnFail").asInt());
        assertEquals(3, standard.path("rpg").path("momentum").path("maxSpend").asInt());

        JsonNode hard = normalize("{\"schemaVersion\":1,\"checkEnabled\":true,\"difficulty\":\"hard\","
                + "\"rpg\":{\"momentum\":{\"enabled\":true}}}");
        assertEquals(12, hard.path("tiers").path("easy").path("dc").asInt());
        assertEquals(15, hard.path("tiers").path("medium").path("dc").asInt());
        assertEquals(18, hard.path("tiers").path("hard").path("dc").asInt());
        assertEquals(4, hard.path("modCap").asInt());
        assertEquals(4, hard.path("rpg").path("momentum").path("max").asInt());
        assertEquals(0, hard.path("rpg").path("momentum").path("init").asInt());
        assertEquals(2, hard.path("rpg").path("momentum").path("maxSpend").asInt());
    }

    @Test
    void presetWithoutMomentumBlockAddsNone() throws Exception {
        JsonNode out = normalize("{\"schemaVersion\":1,\"checkEnabled\":true,\"difficulty\":\"hard\"}");
        assertFalse(out.has("rpg"));
    }

    @Test
    void legacyRulesWithoutDifficultyStayByteIdentical() {
        String raw = "{\"schemaVersion\":1,\"checkEnabled\":true,\"modCap\":5}";
        String before = validator.validateAndNormalize(raw);
        assertFalse(before.contains("difficulty"));
        assertEquals(before, validator.validateAndNormalize(before));
    }

    @Test
    void unknownDifficultyIsRejectedInChinese() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize("{\"schemaVersion\":1,\"difficulty\":\"nightmare\"}"));
        assertEquals("规则难度只能是轻松、标准或硬核", error.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize("{\"schemaVersion\":1,\"difficulty\":3}"));
    }

    @Test
    void parsedRulesCarryDifficultyAndResolveInfersLegacyTier() {
        JourneyRules chosen = JourneyRules.parse("{\"schemaVersion\":1,\"difficulty\":\"easy\"}");
        assertEquals("easy", chosen.getDifficulty());
        assertEquals("easy", JourneyDifficulty.resolve(chosen));
        JourneyRules legacyHard = JourneyRules.parse(
                "{\"schemaVersion\":1,\"tiers\":{\"easy\":{\"dc\":12},\"medium\":{\"dc\":15},\"hard\":{\"dc\":18}}}");
        assertEquals("hard", JourneyDifficulty.resolve(legacyHard));
        JourneyRules custom = JourneyRules.parse(
                "{\"schemaVersion\":1,\"tiers\":{\"easy\":{\"dc\":9},\"medium\":{\"dc\":15},\"hard\":{\"dc\":18}}}");
        assertEquals("standard", JourneyDifficulty.resolve(custom));
        assertEquals("standard", JourneyDifficulty.resolve(null));
    }

    @Test
    void atStartAspectIsKeptAndSeededIntoNewRun() throws Exception {
        String raw = "{\"schemaVersion\":1,\"checkEnabled\":true,\"rpg\":{\"aspects\":["
                + "{\"key\":\"night_walker\",\"label\":\"夜行者\",\"desc\":\"夜里行动更顺手\",\"atStart\":true},"
                + "{\"key\":\"hurt\",\"label\":\"受了伤\",\"atStart\":false}]}}";
        JsonNode out = normalize(raw);
        assertTrue(out.path("rpg").path("aspects").get(0).path("atStart").asBoolean());
        assertFalse(out.path("rpg").path("aspects").get(1).has("atStart"));
        JourneyRules rules = JourneyRules.parse(raw);
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        JourneyRpg.seedRun(rules, state);
        assertTrue(state.getStatusConditions().contains("night_walker"));
        assertFalse(state.getStatusConditions().contains("hurt"));
    }

    @Test
    void atStartMustBeBoolean() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"checkEnabled\":true,\"rpg\":{\"aspects\":[{\"key\":\"a\",\"label\":\"甲\",\"atStart\":\"yes\"}]}}"));
        assertEquals("rpg.aspects.atStart 必须是布尔值", error.getMessage());
    }
}
