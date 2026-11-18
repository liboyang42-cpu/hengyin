package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.impl.AdvancedActionJudgement.*;
import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.business.service.support.GameplayOutcomeRules;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.PublishedHelpDefaults;
import com.chengyinhub.business.service.support.TemplateEffects;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 无对错选择题(qa PICK + judge:false):城市路线 SH01–SH05、XA01、XM01 共 14 题用的形状。
 * 玩家选任一项都算完成;选中项的反馈(fb)显示给玩家;选项上的 ADD_TAG 结果标签记进本局,供结局规则读取。
 * 夹具全部为中性数据。
 */
class QaNoRightAnswerChoiceTest {
    private final ObjectMapper json = new ObjectMapper();

    private static final String CONFIG = "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"judge\":false,"
            + "\"title\":\"示例站点先做哪一种\",\"maxTries\":0,\"xp\":0,\"multi\":false,\"options\":["
            + "{\"id\":\"A3\",\"label\":\"先听自述\",\"fb\":\"甲的回复\",\"correct\":false,"
            + "\"effects\":[{\"var\":\"\",\"op\":\"ADD_TAG\",\"value\":\"tag.order_self\"}]},"
            + "{\"id\":\"B3\",\"label\":\"先看材料\",\"fb\":\"乙的回复\",\"correct\":false,"
            + "\"effects\":[{\"var\":\"\",\"op\":\"ADD_TAG\",\"value\":\"tag.order_all\"}]}]},"
            + "\"outcomeRules\":{\"version\":1,\"pass\":{\"reply\":\"继续下一站\"},\"fail\":{\"reply\":\"本关没有错误答案\"}}}";

    private AdvancedActionJudgement judge() {
        return new AdvancedActionJudgement(json, () -> new Date(100_000), bound -> 0, () -> 42);
    }

    @Test
    void validatorAcceptsWithoutAnAnswerKeyButStillRequiresOptions() throws Exception {
        AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
        JsonNode saved = json.readTree(validator.validateAndNormalize(CONFIG));
        assertFalse(saved.path("qa").path("judge").asBoolean(true));
        GameplayOutcomeRules.validateBlock(saved);
        String noOptions = CONFIG.replaceAll("\"options\":\\[.*\\]\\}\\,\"outcomeRules\"", "\"options\":[]},\"outcomeRules\"");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(noOptions));
    }

    @Test
    void everyOptionCompletesWithItsOwnReplyAndChoice() throws Exception {
        JsonNode config = json.readTree(CONFIG);
        for (String[] row : new String[][]{{"A3", "甲的回复"}, {"B3", "乙的回复"}}) {
            Plan plan = judge().evaluate(Action.SUBMIT_QA, new Input(config, json.createObjectNode(), Map.of("optionId", row[0]), 0, 7L));
            JsonNode qa = plan.state().path("qa");
            assertTrue(qa.path("finished").asBoolean());
            assertTrue(qa.path("passed").asBoolean(), "任何一项都算完成");
            assertEquals(row[1], qa.path("lastFeedback").asText(), "显示选中项自己的话");
            assertEquals(List.of(new ChoiceRef("qa", row[0])), plan.choices());
            ObjectNode decision = judge().serverGameplayDecision("SUBMIT_QA", config, plan.state());
            assertEquals("PASS", decision.path("outcome").asText());
            assertEquals(row[0], decision.path("selection").path("optionId").asText());
        }
        assertThrows(IllegalArgumentException.class, () -> judge().evaluate(Action.SUBMIT_QA,
                new Input(config, json.createObjectNode(), Map.of("optionIds", List.of("A3", "B3")), 0, 7L)), "只能选一项");
    }

    @Test
    void chosenOptionTagIsFrozenAndRecordedForEndingRules() throws Exception {
        JsonNode config = json.readTree(CONFIG);
        ObjectNode frozenBlock = json.createObjectNode();
        NodeJourneySnapshot.copyMechanicSegments(config, frozenBlock);
        JsonNode options = frozenBlock.path(NodeJourneySnapshot.CHOICE_EFFECTS_KEY).path("qa").path("options");
        JsonNode chosen = null;
        for (JsonNode option : options) if ("B3".equals(option.path("id").asText())) chosen = option;
        assertNotNull(chosen, "选项结果冻结进本局快照");
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        TemplateEffects.apply(chosen.path("effects"), JourneyRules.parse(null), state);
        assertTrue(state.getTags().contains("tag.order_all"));
        assertFalse(state.getTags().contains("tag.order_self"), "只记选中那一项的标签");
    }

    @Test
    void publishDefaultsDoNotAddASkipOrGenericFailToAChoiceWithoutAnswer() throws Exception {
        ObjectNode frozen = (ObjectNode) json.readTree("{\"nodeAnswers\":{\"31\":{}},\"nodeConfigs\":{\"31\":" + CONFIG + "}}");
        PublishedHelpDefaults.apply(frozen, true);
        assertTrue(frozen.path("nodeAnswers").path("31").path("hintLadder").isMissingNode());
        assertEquals("本关没有错误答案", frozen.path("nodeConfigs").path("31").path("outcomeRules").path("fail").path("reply").asText());
    }
}
