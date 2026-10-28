package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.vo.NodeMerchantOverride;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.INodeMerchantOverrideService;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real projection/helper only; publication, storage, Spring and HTTP are separate acceptance. */
class PlayFrozenContentProjectionTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode content() {
        ObjectNode content = json.createObjectNode().put("contentSnapshotVersion", 1).put("templateId", 31L);
        content.put("validationMethod", 3).put("questionAnswer", "secret-answer")
                .put("correctAnswer", "B").put("answerReveal", "secret-reveal");
        content.put("questionA", "原选项A").put("questionB", "原选项B");
        content.putObject("nodePresentation").put("id", 11L).put("topicId", 21L)
                .put("templateId", 31L).put("name", "原节点").put("hookText", "原钩子");
        content.putObject("templatePresentation").put("title", "原游戏").put("questionName", "原题干");
        content.put("hint1", "原已用提示").put("hint2", "secret-unused-hint");
        return content;
    }

    private CmsTopicNode node() {
        CmsTopicNode node = new CmsTopicNode(); node.setId(11L); node.setTopicId(21L);
        node.setName("作者新名字"); node.setTemplateId(null); node.setStatus(1L);
        node.setCheckinCode("original-server-credential"); return node;
    }

    private PlayNodeQueryApplication.NodeProjection read(CmsTopicNode node) {
        PlayNodeQueryApplication.NodeProjection read = new PlayNodeQueryApplication.NodeProjection(
                7L, true, 21L, 0L, null, true, false, null, List.of(node), List.of(),
                Map.of(), Map.of(), Set.of(), 10, Set.of(), new ArrayList<>(), null, 1, null, false, null);
        read.playableNodeIds = new HashSet<>(); read.puzzleHintLevelByNode = Map.of(11L, 1);
        return read;
    }

    @Test void originalPersonalRunNeverReadsCurrentPublicationAndMissingAnswerStaysMissing() {
        PlayNodeContext context = new PlayNodeContext();
        var runtime = mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        var drafts = mock(ICmsMemberTemplateService.class);
        ReflectionTestUtils.setField(context, "topicRouteRuntimeService", runtime);
        ReflectionTestUtils.setField(context, "cmsMemberTemplateService", drafts);
        var session = new com.chengyinhub.business.domain.TopicRouteSession();
        session.setId(91L);
        PlayOriginalRun original = new PlayOriginalRun(session, 91L, null, null);
        CmsTopicNode node = node(); node.setTemplateId(41L);
        // 有答案冻结(nodeAnswers 键在)的局:缺本节点仍是缺,不读当前模板。
        session.setConfigSnapshotJson("{\"nodeAnswers\":{}}");
        CmsMemberTemplate template = context.effectiveTemplate(7L, 21L, 0L, node, original);
        assertNull(template.getValidationMethod()); assertNull(template.getQuestionAnswer());
        session.setConfigSnapshotJson("{\"nodeAnswers\":{\"11\":{\"validationMethod\":1,\"questionAnswer\":\"original-answer\"}}}");
        assertEquals("original-answer", context.effectiveTemplate(7L, 21L, 0L, node, original).getQuestionAnswer());
        verifyNoInteractions(runtime, drafts);
    }

    /** D1(2026-10-27 用户决策):答案冻结前开的老个人局(快照无 nodeAnswers 键)回退当前模板继续游玩。 */
    @Test void legacyPersonalRunWithoutAnswerFreezeFallsBackToCurrentTemplate() {
        PlayNodeContext context = new PlayNodeContext();
        var runtime = mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        var drafts = mock(ICmsMemberTemplateService.class);
        ReflectionTestUtils.setField(context, "topicRouteRuntimeService", runtime);
        ReflectionTestUtils.setField(context, "cmsMemberTemplateService", drafts);
        CmsMemberTemplate current = new CmsMemberTemplate(); current.setValidationMethod(1); current.setQuestionAnswer("current-answer");
        when(drafts.selectCmsMemberTemplateById(41L)).thenReturn(current);
        var session = new com.chengyinhub.business.domain.TopicRouteSession();
        session.setId(91L); session.setConfigSnapshotJson("{}");
        CmsTopicNode node = node(); node.setTemplateId(41L);
        assertEquals("current-answer", context.effectiveTemplate(7L, 21L, 0L, node,
                new PlayOriginalRun(session, 91L, null, null)).getQuestionAnswer());
        verifyNoInteractions(runtime);
    }

    @Test void sharedOriginalContentRejectsAnotherEpochWithoutLiveFallback() {
        PlayNodeContext context = new PlayNodeContext();
        var runtime = mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(context, "topicRouteRuntimeService", runtime);
        ObjectNode snapshot = json.createObjectNode().put("runtimeInstance", "shared-run").put("authorityEpoch", 3);
        when(runtime.queryNodeContentSnapshot(7L, 21L, 0L, 11L)).thenReturn(snapshot);
        assertThrows(IllegalStateException.class, () -> context.frozenAnswer(7L, 21L, 0L, 11L,
                new PlayOriginalRun(null, 91L, "shared-run", 2)));
        assertNotNull(context.frozenAnswer(7L, 21L, 0L, 11L,
                new PlayOriginalRun(null, 91L, "shared-run", 3)));
    }

    @Test void originalQuestionAndUsedHintSurviveUnbindWithoutPublicSecretsOrAuthorReads() throws Exception {
        PlayNodeContext context = new PlayNodeContext();
        PlayNodeQueryApplication query = new PlayNodeQueryApplication();
        ICmsMemberTemplateService drafts = mock(ICmsMemberTemplateService.class);
        INodeMerchantOverrideService overrides = mock(INodeMerchantOverrideService.class);
        when(overrides.forNode(11L)).thenReturn(NodeMerchantOverride.empty());
        ReflectionTestUtils.setField(query, "playNodeContext", context);
        ReflectionTestUtils.setField(query, "cmsMemberTemplateService", drafts);
        ReflectionTestUtils.setField(query, "nodeMerchantOverrideService", overrides);
        CmsTopicNode original = node();
        PlayNodeQueryApplication.NodeTask task = query.projectNodeBeforeRuntime(read(original), original, content());

        assertEquals("原节点", task.view().get("name"));
        assertEquals("原游戏", task.view().get("gameTitle"));
        assertEquals("原题干", task.view().get("question"));
        assertEquals("原选项B", ((Map<?, ?>) task.view().get("options")).get("B"));
        assertEquals(List.of("原已用提示"), task.view().get("usedHints"));
        String response = json.writeValueAsString(task.view());
        assertFalse(response.contains("secret-")); assertFalse(response.contains("questionAnswer"));
        assertFalse(response.contains("correctAnswer")); assertFalse(response.contains("answerReveal"));
        assertFalse(response.contains("original-server-credential"));
        assertNull(original.getTemplateId()); assertEquals("作者新名字", original.getName());
        assertEquals(1L, task.node().getStatus());
        assertEquals("original-server-credential", task.node().getCheckinCode());
        verifyNoInteractions(drafts);
    }

    @Test void sourceIdentityAndDamageAreNotReplacedWithTheCurrentBinding() {
        PlayNodeContext context = new PlayNodeContext(); ObjectNode content = content();
        CmsMemberTemplate first = context.frozenTemplateView(node(), content);
        first.setQuestionAnswer("mutated-view");
        assertEquals("secret-answer", context.frozenTemplateView(node(), content).getQuestionAnswer());
        assertEquals(31L, context.frozenTemplateView(node(), content).getId());
        ((ObjectNode) content.path("nodePresentation")).put("topicId", 22L);
        assertThrows(IllegalStateException.class, () -> context.frozenNodeView(node(), content));
        ObjectNode damaged = content(); damaged.remove("templatePresentation");
        assertThrows(IllegalStateException.class, () -> context.frozenTemplateView(node(), damaged));
    }

    @Test void legalNonTemplateAndOldAnswerSnapshotsHaveDistinctExplicitBehavior() {
        PlayNodeContext context = new PlayNodeContext(); ObjectNode nonTemplate = content();
        nonTemplate.remove("templateId"); nonTemplate.remove("templatePresentation");
        ((ObjectNode) nonTemplate.path("nodePresentation")).remove("templateId");
        assertNull(context.frozenTemplateView(node(), nonTemplate));
        ObjectNode old = json.createObjectNode().put("validationMethod", 1).put("questionAnswer", "old-answer");
        CmsTopicNode live = node(); live.setTemplateId(99L);
        assertEquals(99L, context.frozenTemplateView(live, old).getId());
        assertEquals("old-answer", context.frozenTemplateView(live, old).getQuestionAnswer());
        assertFalse(NodeJourneySnapshot.hasContent(old));
    }

    @Test void newSnapshotCannotBorrowALaterNodeAndEachReadIsADefensiveCopy() {
        ObjectNode snapshot = json.createObjectNode(); snapshot.putObject("nodeAnswers").set("11", content());
        var first = NodeJourneySnapshot.answer(snapshot.toString(), 11L);
        ((ObjectNode) first.path("templatePresentation")).put("title", "caller-mutated");
        assertEquals("原游戏", NodeJourneySnapshot.answer(snapshot.toString(), 11L).path("templatePresentation").path("title").asText());
        assertThrows(IllegalStateException.class, () -> NodeJourneySnapshot.answer(snapshot.toString(), 12L));
        ObjectNode old = json.createObjectNode(); old.putObject("nodeAnswers").putObject("11").put("validationMethod", 1);
        assertNull(NodeJourneySnapshot.answer(old.toString(), 12L));
    }
}
