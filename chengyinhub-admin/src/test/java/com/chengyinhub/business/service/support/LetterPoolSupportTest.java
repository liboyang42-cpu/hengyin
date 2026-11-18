package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F03 打字问答字符池(qa.letterPool)与池子共用规则。 */
class LetterPoolSupportTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode qa(String answerText, int answerLength, String... chars) throws Exception {
        ObjectNode qa = (ObjectNode) JSON.readTree("{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"示例题\"}");
        qa.put("answerText", answerText);
        ObjectNode pool = qa.putObject("letterPool");
        pool.put("enabled", true);
        if (answerLength > 0) pool.put("answerLength", answerLength);
        for (String ch : chars) pool.withArray("chars").add(ch);
        return qa;
    }

    @Test
    void qaPoolMustSpellPrimaryAnswerAndMatchAnswerLength() throws Exception {
        LetterPoolSupport.validateQa(qa("示例、示列", 2, "示", "例", "站", "点"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> LetterPoolSupport.validateQa(qa("示例", 2, "示", "站"))).getMessage().contains("拼不出答案"));
        assertThrows(IllegalArgumentException.class, () -> LetterPoolSupport.validateQa(qa("示例", 3, "示", "例")));
        ObjectNode pick = qa("示例", 2, "示", "例");
        pick.put("mode", "PICK");
        assertThrows(IllegalArgumentException.class, () -> LetterPoolSupport.validateQa(pick));
        ObjectNode disabled = qa("示例", 2);
        ((ObjectNode) disabled.get("letterPool")).put("enabled", false);
        LetterPoolSupport.validateQa(disabled); // 关掉的池子不校验
    }

    @Test
    void qaSubmissionOutsidePoolIsRejectedButAliasesStayAllowed() throws Exception {
        ObjectNode qa = qa("ABC", 3, "A", "B", "C", "X");
        ObjectNode state = JSON.createObjectNode();
        LetterPoolSupport.requireQaChars(qa, state, "XBC");
        assertEquals(LetterPoolSupport.REJECT_MESSAGE, assertThrows(IllegalArgumentException.class,
                () -> LetterPoolSupport.requireQaChars(qa, state, "QBC")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> LetterPoolSupport.requireQaChars(qa, state, "AAC"));
        // 没开池子:什么都不拦(老配置行为不变)
        ObjectNode plain = qa("ABC", 3, "A");
        ((ObjectNode) plain.get("letterPool")).put("enabled", false);
        LetterPoolSupport.requireQaChars(plain, state, "QQQ");
    }

    @Test
    void qaViewTrimsDecoysAndKeepsAnswerChars() throws Exception {
        ObjectNode qa = qa("AB", 2, "A", "B", "X", "Y", "Z");
        ObjectNode state = JSON.createObjectNode();
        state.put(LetterPoolSupport.STATE_SEED, 11L);
        assertEquals(5, LetterPoolSupport.qaView(qa, state).chars().size());
        assertTrue(LetterPoolSupport.recordTrim(state, 2, 2));
        assertFalse(LetterPoolSupport.recordTrim(state, 2, 2), "同一级只记一次");
        LetterPoolSupport.View view = LetterPoolSupport.qaView(qa, state);
        assertEquals(3, view.chars().size());
        assertEquals(2, view.removed());
        assertTrue(view.chars().contains("A") && view.chars().contains("B"));
        assertEquals(2, LetterPoolSupport.qaAnswerLength(qa));
    }

    @Test
    void trimCountDefaultsToThree() {
        assertEquals(3, LetterPoolSupport.trimCount(null));
        assertEquals(5, LetterPoolSupport.trimCount(JSON.getNodeFactory().numberNode(5)));
        assertEquals(3, LetterPoolSupport.trimCount(JSON.getNodeFactory().numberNode(0)));
    }

    @Test
    void spellableCountsRepeatsAndIgnoresSeparators() {
        List<String> pool = List.of("B", "O", "O", "K");
        assertTrue(LetterPoolSupport.spellable(pool, "book", false));
        assertTrue(LetterPoolSupport.spellable(pool, "B-O O K", false));
        assertFalse(LetterPoolSupport.spellable(pool, "BOOOK", false));
        assertFalse(LetterPoolSupport.spellable(pool, "book", true));
    }

    @Test
    void capabilityKeysAreDerivedFromPoolSections() throws Exception {
        ObjectNode snapshot = JSON.createObjectNode();
        ObjectNode configs = snapshot.putObject("nodeConfigs");
        ObjectNode hangman = configs.putObject("11").putObject("hangman");
        hangman.put("enabled", true).put("answer", "ABC").put("inputMode", "pool");
        hangman.putArray("pool").add("A").add("B").add("C");
        configs.putObject("12").set("qa", qa("AB", 2, "A", "B"));
        configs.putObject("13").putObject("hangman").put("enabled", true).put("answer", "ABC");
        StringBuilder keys = new StringBuilder();
        for (JsonNode row : ContentCapabilityRequirements.required(snapshot)) keys.append(row.path("key").asText()).append(',');
        assertTrue(keys.toString().contains("hangman.pool,"), keys.toString());
        assertTrue(keys.toString().contains("qa.letterPool,"), keys.toString());

        ObjectNode legacy = JSON.createObjectNode();
        legacy.putObject("nodeConfigs").putObject("13").putObject("hangman").put("enabled", true).put("answer", "ABC");
        String legacyKeys = ContentCapabilityRequirements.required(legacy).toString();
        assertFalse(legacyKeys.contains("hangman.pool") || legacyKeys.contains("qa.letterPool"), legacyKeys);
    }

    @Test
    void validatorRejectsPoolTrimWithoutPoolAndAcceptsItWithOne() {
        AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
        String hint = ",\"hintLadder\":[{\"level\":1,\"type\":\"POOL_TRIM\",\"payload\":3},{\"level\":2,\"type\":\"ANSWER\"}]";
        String poolQa = "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"示例题\","
                + "\"answerText\":\"AB\",\"letterPool\":{\"enabled\":true,\"chars\":[\"A\",\"B\",\"X\",\"Y\"],\"answerLength\":2}}";
        validator.validateAndNormalize(poolQa + hint + "}");
        String plainQa = "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"示例题\",\"answerText\":\"AB\"}";
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(plainQa + hint + "}")).getMessage().contains("字母池"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(poolQa
                + ",\"hintLadder\":[{\"level\":1,\"type\":\"POOL_TRIM\",\"payload\":0}]}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"示例题\","
                        + "\"answerText\":\"AB\",\"letterPool\":{\"enabled\":true,\"chars\":[\"A\",\"X\"]}}}"));
    }

    @Test
    void poolTrimHelpContentAndUsagePolicyAreSupported() {
        assertEquals("已去掉 3 个干扰字", PlayHelpContent.approved(1, PlayHelpContent.TYPE_POOL_TRIM,
                JSON.createObjectNode().put("level", 1).put("type", "POOL_TRIM"), null));
        assertEquals("已去掉 2 个干扰字", PlayHelpContent.approved(1, PlayHelpContent.TYPE_POOL_TRIM,
                JSON.createObjectNode().put("level", 1).put("type", "POOL_TRIM").put("payload", 2), null));
        com.fasterxml.jackson.databind.node.ArrayNode ladder = JSON.createArrayNode();
        ladder.addObject().put("level", 1).put("type", "POOL_TRIM");
        List<PlayHelpUsagePolicy.LevelSpec> specs = PlayHelpUsagePolicy.levels(ladder);
        assertEquals(1, specs.size());
        assertTrue(specs.get(0).usable(), "POOL_TRIM 是可用层:" + specs.get(0).reason());
    }
}
