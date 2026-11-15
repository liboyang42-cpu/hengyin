package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.support.BasicTextAnswer;
import com.chengyinhub.common.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** B5(acceptance-r3):文字题标准答案也要读 advanced_config_json.qa.answerText。 */
class BasicTextAnswerExpectedAnswerTest {
    private static final String SEED_992101 = "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\","
            + "\"title\":\"钟楼正面刻着哪一年？\",\"answerText\":\"1921\",\"maxTries\":0}}";

    @Test
    void fallsBackToAdvancedQaAnswerTextWhenQuestionAnswerIsBlank() {
        assertEquals("1921", BasicTextAnswer.expectedAnswer(null, SEED_992101));
        assertEquals("1921", BasicTextAnswer.expectedAnswer("  ", SEED_992101));
        assertTrue(BasicTextAnswer.matches(BasicTextAnswer.expectedAnswer(null, SEED_992101), " 1921 "));
    }

    @Test
    void questionAnswerColumnStillWins() {
        assertEquals("钟楼", BasicTextAnswer.expectedAnswer("钟楼", SEED_992101));
    }

    @Test
    void noAnswerAnywhereIsNullSoTheCallerReportsConfigInvalid() {
        assertNull(BasicTextAnswer.expectedAnswer(null, null));
        assertNull(BasicTextAnswer.expectedAnswer(null, "{\"qa\":{\"mode\":\"TYPE\"}}"));
        assertNull(BasicTextAnswer.expectedAnswer(null, "{\"qa\":{\"answerText\":\"  \"}}"));
        assertNull(BasicTextAnswer.expectedAnswer(null, "not json"));
    }

    @Test
    void publishFreezeCarriesTheFallbackAnswerIntoNodeAnswers() {
        TopicRoutePublicationCompiler compiler = Mockito.mock(TopicRoutePublicationCompiler.class, Mockito.CALLS_REAL_METHODS);
        CmsTopicNode node = new CmsTopicNode(); node.setId(992021L); node.setTemplateId(992101L);
        CmsMemberTemplate template = new CmsMemberTemplate(); template.setId(992101L);
        template.setValidationMethod(1); template.setAdvancedConfigJson(SEED_992101);
        ObjectNode nodeAnswers = JsonUtils.getObjectMapper().createObjectNode();
        ReflectionTestUtils.invokeMethod(compiler, "freezeNodeAnswers", nodeAnswers,
                Collections.singletonList(node), Collections.singletonMap(992101L, template));
        assertEquals("1921", nodeAnswers.path("992021").path("questionAnswer").asText());
    }
}
