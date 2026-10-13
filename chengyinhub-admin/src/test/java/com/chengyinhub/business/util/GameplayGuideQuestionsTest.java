package com.chengyinhub.business.util;

import com.chengyinhub.business.service.support.GameplaySupportMatrixTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Arrays;
import java.util.Iterator;
import static org.junit.jupiter.api.Assertions.*;

class GameplayGuideQuestionsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void basicQuestionUsesFrozenOptionsButNeverFrozenAnswers() throws Exception {
        JsonNode answer = json.readTree("{\"validationMethod\":1,\"questionA\":\"甲\",\"questionB\":\"乙\",\"questionAnswer\":\"AB\",\"correctAnswer\":\"secret\",\"answerReveal\":\"secret\",\"configHash\":\"secret\"}");
        ObjectNode config = json.createObjectNode();
        JsonNode view = AdvancedGamePublicProjection.guideQuestions(config, answer);
        assertEquals(json.readTree("{\"basic\":{\"mode\":\"PICK\",\"options\":[{\"id\":\"A\",\"label\":\"甲\"},{\"id\":\"B\",\"label\":\"乙\"}]}}"), view);
        ((ObjectNode) answer).put("validationMethod", 3);
        assertEquals(json.readTree("{\"basic\":{\"mode\":\"TYPE\",\"options\":[]}}"), AdvancedGamePublicProjection.guideQuestions(config, answer));
        assertNoSecrets(view);
    }

    @ParameterizedTest @ValueSource(strings = {"qaText", "qaPick", "sort", "match", "classify"})
    void objectiveQuestionReusesPublicProjectionWithoutAnswersOrEffects(String kind) throws Exception {
        ObjectNode config = GameplaySupportMatrixTest.config(kind);
        String section = kind.startsWith("qa") ? "qa" : kind;
        ObjectNode source = (ObjectNode) config.path(section);
        source.put("passed", true).put("configHash", "secret").put("answerKey", "secret");
        source.putArray("effects").addObject().put("var", "sys.hp").put("value", 99);
        String before = config.toString();
        JsonNode view = AdvancedGamePublicProjection.guideQuestions(config, json.createObjectNode().put("validationMethod", 1));
        assertTrue(view.has(section));
        assertEquals(1, view.size());
        assertNoSecrets(view);
        assertEquals(before, config.toString());
        if ("qa".equals(section)) assertEquals(source.path("mode"), view.path("qa").path("mode"));
        else assertEquals(source.path("prompt"), view.path(section).path("prompt"));
    }

    @Test void disabledAndNonObjectiveTypesDoNotBecomeGuideQuestions() {
        ObjectNode config = json.createObjectNode();
        config.putObject("qa").put("enabled", false).put("answerText", "secret");
        config.putObject("ballShake").put("enabled", true).put("goal", 10);
        assertEquals(0, AdvancedGamePublicProjection.guideQuestions(config, json.createObjectNode().put("validationMethod", 4)).size());
    }

    private void assertNoSecrets(JsonNode view) {
        Iterator<String> names = view.fieldNames();
        while (names.hasNext()) assertFalse(Arrays.asList("answer", "answerKey", "answerText", "answerOrder", "pairs", "correct", "correctAnswer", "questionAnswer", "answerReveal", "passed", "effects", "fb", "configHash").contains(names.next()));
        for (JsonNode child : view) assertNoSecrets(child);
    }
}
