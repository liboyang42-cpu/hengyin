package com.chengyinhub.business.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P-10 / P-11 第三轮(业主):答对 / 答错写作者在模板「结果反馈」里配的话(outcomeRules.pass / fail)。
 * 玩法视图只在本次已判出结果之后说「作者给这个结果写了话」,判之前一个字都不给;作者没写时不出这个键。
 */
class AuthoredOutcomeFeedbackTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String raw) throws Exception { return JSON.readTree(raw); }

    private static final String CONFIG = "{\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"江的另一个名字\",\"answerText\":\"吴淞江\"},"
            + "\"outcomeRules\":{\"version\":1,\"pass\":{\"reply\":\" 对，就是吴淞江。 \",\"images\":[\"https://img/a.jpg\"]},\"fail\":{\"reply\":\"再去桥头看看石碑。\"}}}";

    @Test
    void nothingBeforeTheServerHasJudged() throws Exception {
        assertNull(AdvancedGameRuntimeServiceImpl.authoredOutcomeFeedback(json(CONFIG), json("{\"qa\":{\"attempts\":0}}")));
    }

    @Test
    void wrongAnswerCarriesTheAuthorsFailText() throws Exception {
        Map<String, Object> value = AdvancedGameRuntimeServiceImpl.authoredOutcomeFeedback(json(CONFIG),
                json("{\"gameplayDecision\":{\"outcome\":\"FAIL\"}}"));
        assertEquals("FAIL", value.get("outcome"));
        assertEquals("再去桥头看看石碑。", value.get("reply"));
        assertEquals(Collections.emptyList(), value.get("images"));
    }

    @Test
    void rightAnswerCarriesTheAuthorsPassTextAndImages() throws Exception {
        Map<String, Object> value = AdvancedGameRuntimeServiceImpl.authoredOutcomeFeedback(json(CONFIG),
                json("{\"gameplayDecision\":{\"outcome\":\"PASS\"}}"));
        assertEquals("PASS", value.get("outcome"));
        assertEquals("对，就是吴淞江。", value.get("reply"));
        assertEquals(Arrays.asList("https://img/a.jpg"), value.get("images"));
    }

    @Test
    void authorWroteNothingForThisOutcomeOrHasNoOutcomeRules() throws Exception {
        String passOnly = "{\"outcomeRules\":{\"version\":1,\"pass\":{\"reply\":\"好\"},\"fail\":{}}}";
        assertNull(AdvancedGameRuntimeServiceImpl.authoredOutcomeFeedback(json(passOnly), json("{\"gameplayDecision\":{\"outcome\":\"FAIL\"}}")));
        assertNull(AdvancedGameRuntimeServiceImpl.authoredOutcomeFeedback(json("{\"qa\":{\"enabled\":true}}"), json("{\"gameplayDecision\":{\"outcome\":\"PASS\"}}")));
    }
}
