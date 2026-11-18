package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.support.PlayHelpContent;
import com.chengyinhub.business.service.support.PlayHelpUsagePolicy;
import com.chengyinhub.business.service.support.PublishedHelpDefaults;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F01 发布兜底:城市定向每道有判定的题冻结后都有「L1 方向 / L2 关键步骤 / L3 答案 / 跳过」,
 * 每级 {@code cost:{points:0}};只改冻结副本。F02:没写错答话术的 fail 补通用一句。
 * (编译器在 freezeCurrentPublication 末尾调用 {@link PublishedHelpDefaults#apply}。)
 */
class TopicRoutePublicationCompilerHintLadderTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void hint1Hint2NodeGetsThreeLevelsPlusSkip() throws Exception {
        ObjectNode frozen = frozen("{\"validationMethod\":1,\"hint1\":\"看门牌\",\"hint2\":\"数第二行\",\"questionAnswer\":\"ABC\"}", "{}");

        PublishedHelpDefaults.apply(frozen, true);

        JsonNode ladder = frozen.path("nodeAnswers").path("31").path("hintLadder");
        assertEquals(4, ladder.size());
        assertEquals(List.of("TEXT", "TEXT", "ANSWER", "SKIP"), types(ladder));
        for (JsonNode level : ladder) assertEquals(0, level.path("cost").path("points").asInt(-1));
        // 运行期按冻结答案取内容:L1/L2 取 hint1/hint2,L3 取标准答案。
        JsonNode answer = frozen.path("nodeAnswers").path("31");
        assertEquals("看门牌", PlayHelpContent.approved(1, "TEXT", ladder.get(0), answer));
        assertEquals("数第二行", PlayHelpContent.approved(2, "TEXT", ladder.get(1), answer));
        assertEquals("ABC", PlayHelpContent.approved(3, "ANSWER", ladder.get(2), answer));
        // 与运行期同一解析:每层都可用(无 xp 等坏代价)。
        for (PlayHelpUsagePolicy.LevelSpec spec : PlayHelpUsagePolicy.levels(ladder)) assertTrue(spec.usable());
    }

    @Test
    void existingLadderWithoutSkipGetsAnswerThenSkipAppended() throws Exception {
        ObjectNode frozen = frozen("{\"validationMethod\":1,\"answerReveal\":\"答案是 ABC\"}",
                "{\"hintLadder\":[{\"level\":1,\"type\":\"TEXT\",\"payload\":\"方向\"},{\"level\":2,\"type\":\"TEXT\",\"payload\":\"步骤\"}]}");

        PublishedHelpDefaults.apply(frozen, true);

        JsonNode ladder = frozen.path("nodeAnswers").path("31").path("hintLadder");
        assertEquals(List.of("TEXT", "TEXT", "ANSWER", "SKIP"), types(ladder));
        assertEquals(3, ladder.get(2).path("level").asInt());
        assertEquals(4, ladder.get(3).path("level").asInt());
        assertFalse(frozen.path("nodeConfigs").path("31").path("hintLadder").get(0).has("cost"), "作者原阶梯块不被改写");
    }

    @Test
    void existingSkipStaysLastWhenAnswerIsInserted() throws Exception {
        ObjectNode frozen = frozen("{\"validationMethod\":1,\"questionAnswer\":\"ABC\"}",
                "{\"hintLadder\":[{\"level\":1,\"type\":\"TEXT\",\"payload\":\"方向\"},{\"level\":2,\"type\":\"SKIP\"}]}");

        PublishedHelpDefaults.apply(frozen, true);

        JsonNode ladder = frozen.path("nodeAnswers").path("31").path("hintLadder");
        assertEquals(List.of("TEXT", "ANSWER", "SKIP"), types(ladder));
    }

    @Test
    void completeLadderIsLeftAlone() throws Exception {
        ObjectNode frozen = frozen("{\"validationMethod\":1,\"questionAnswer\":\"ABC\"}",
                "{\"hintLadder\":[{\"level\":1,\"type\":\"ANSWER\"},{\"level\":2,\"type\":\"SKIP\"}]}");

        PublishedHelpDefaults.apply(frozen, true);

        assertTrue(frozen.path("nodeAnswers").path("31").path("hintLadder").isMissingNode());
    }

    @Test
    void photoNodeGetsHintsAndSkipButNoAnswer() throws Exception {
        ObjectNode frozen = frozen("{\"validationMethod\":2,\"hint1\":\"找门口那块牌\"}", "{}");

        PublishedHelpDefaults.apply(frozen, true);

        assertEquals(List.of("TEXT", "SKIP"), types(frozen.path("nodeAnswers").path("31").path("hintLadder")));
    }

    @Test
    void choiceWithoutCorrectAnswerAndStoryNodesGetNoAnswerLevel() throws Exception {
        ObjectNode frozen = frozen("{\"validationMethod\":3}", "{}");
        PublishedHelpDefaults.apply(frozen, true);
        assertEquals(List.of("SKIP"), types(frozen.path("nodeAnswers").path("31").path("hintLadder")));

        ObjectNode story = frozen("{}", "{}");
        PublishedHelpDefaults.apply(story, true);
        assertTrue(story.path("nodeAnswers").path("31").path("hintLadder").isMissingNode(), "没有对错的站不生成跳过");
    }

    @Test
    void advancedTypeQuestionUsesItsAnswerAsTheAnswerPayload() throws Exception {
        ObjectNode frozen = frozen("{}", "{\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"answerText\":\"ABC、甲乙丙\"}}");

        PublishedHelpDefaults.apply(frozen, true);

        JsonNode ladder = frozen.path("nodeAnswers").path("31").path("hintLadder");
        assertEquals(List.of("ANSWER", "SKIP"), types(ladder));
        assertEquals("ABC", ladder.get(0).path("payload").asText());
    }

    @Test
    void nonOrienteeringTopicsAreUnchanged() throws Exception {
        ObjectNode frozen = frozen("{\"validationMethod\":1,\"hint1\":\"看门牌\",\"questionAnswer\":\"ABC\"}", "{}");

        PublishedHelpDefaults.apply(frozen, false);

        assertTrue(frozen.path("nodeAnswers").path("31").path("hintLadder").isMissingNode());
    }

    @Test
    void failWithoutReplyGetsTheGenericWrongReplyEverywhere() throws Exception {
        ObjectNode frozen = (ObjectNode) json.readTree("{\"nodeAnswers\":{},"
                + "\"nodeConfigs\":{\"31\":{\"outcomeRules\":{\"version\":1,\"pass\":{},\"fail\":{}}},"
                + "\"32\":{\"outcomeRules\":{\"version\":1,\"pass\":{},\"fail\":{\"reply\":\"作者自己的话\"}}}},"
                + "\"themeDefinition\":{\"scenes\":[{\"nodeConfigs\":{\"31\":{\"outcomeRules\":{\"version\":1,\"pass\":{},\"fail\":{\"reply\":\" \"}}}}}]}}");

        PublishedHelpDefaults.apply(frozen, false);

        assertEquals(PublishedHelpDefaults.DEFAULT_FAIL_REPLY,
                frozen.path("nodeConfigs").path("31").path("outcomeRules").path("fail").path("reply").asText());
        assertEquals("作者自己的话", frozen.path("nodeConfigs").path("32").path("outcomeRules").path("fail").path("reply").asText());
        assertEquals(PublishedHelpDefaults.DEFAULT_FAIL_REPLY, frozen.path("themeDefinition").path("scenes").get(0)
                .path("nodeConfigs").path("31").path("outcomeRules").path("fail").path("reply").asText());
        assertFalse(PublishedHelpDefaults.DEFAULT_FAIL_REPLY.endsWith("。"), "OR-027 提示句末不带句号");
    }

    private ObjectNode frozen(String answer, String block) throws Exception {
        return (ObjectNode) json.readTree("{\"nodeAnswers\":{\"31\":" + answer + "},\"nodeConfigs\":{\"31\":" + block + "}}");
    }

    private static List<String> types(JsonNode ladder) {
        java.util.List<String> types = new java.util.ArrayList<>();
        for (JsonNode level : ladder) types.add(level.path("type").asText());
        return types;
    }
}
