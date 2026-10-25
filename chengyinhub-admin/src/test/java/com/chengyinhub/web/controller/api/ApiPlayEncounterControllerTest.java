package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.impl.PlayEncounterServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * /api/play/encounter 只读合同:状态取自本局 topic_route_session.state_json(线性/分支同一套),
 * 到店与 requires 双闸、missingKeys、check 的 when/held 公开面,
 * 以及「整个序列化结果里搜不到答案字段」。
 */
class ApiPlayEncounterControllerTest {
    @Test void r5CheckRoutingMarkerUsesFrozenNodeAndNeverLiveDraft() throws Exception {
        ObjectMapper json=new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode frozen=json.createObjectNode();frozen.putObject("themeConfig");
        com.fasterxml.jackson.databind.node.ObjectNode cfg=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(CONFIG);cfg.putObject("outcomeRules").put("version",1);
        frozen.putObject("nodeConfigs").set(String.valueOf(NODE),cfg);session.setConfigSnapshotJson(frozen.toString());
        assertEquals(1,json.valueToTree(data(controller.encounter(TOPIC,null,NODE))).path("config").path("outcomeRulesVersion").asInt());
        cfg.remove("outcomeRules");session.setConfigSnapshotJson(frozen.toString());template.setAdvancedConfigJson(CONFIG.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"outcomeRules\":{\"version\":1}"));
        assertFalse(json.valueToTree(data(controller.encounter(TOPIC,null,NODE))).has("config"));
    }

    private static final Long MEMBER = 71L;
    private static final Long TOPIC = 73L;
    private static final Long NODE = 101L;
    private static final Long TEMPLATE = 501L;
    private static final Long RUN = 9001L;

    private static final String CONFIG = "{\"schemaVersion\":1,"
            + "\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}],"
            + "\"effects\":[{\"op\":\"SET\",\"var\":\"clue.ledger_page\",\"value\":1}],"
            + "\"check\":{\"enabled\":true,\"checkId\":\"ask_lin\",\"skill\":\"察言\",\"tier\":\"medium\","
            + "\"mods\":[{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},"
            + "\"value\":2,\"label\":\"账本页\"},"
            + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},\"value\":1,\"label\":\"慌张\"}],"
            + "\"advantageIf\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}],"
            + "\"disadvantageIf\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"}],"
            + "\"successText\":\"他看了你一眼\",\"failText\":\"他合上了账本\",\"failCostTag\":\"tag.hurried\"},"
            + "\"qa\":{\"enabled\":true},"
            + "\"branch\":{\"enabled\":true,\"startStepId\":\"s1\",\"steps\":["
            + "{\"id\":\"s1\",\"terminal\":false,\"options\":[{\"id\":\"o1\",\"label\":\"推门\",\"nextStepId\":\"s2\"}]},"
            + "{\"id\":\"s2\",\"terminal\":true,\"outcomeCode\":\"LEDGER_OPEN\",\"options\":[]}]}}";

    private ApiPlayEncounterController controller;
    private CmsTopicNode node;
    private CmsMemberTemplate template;
    private TopicRouteSession session;
    private IShopDayProofService shopDayProofService;
    private IPlayerNodeProgressService progressService;
    private TopicRouteSessionMapper routeSessionMapper;
    private ICmsTopicNodeService nodeService;
    private NpcProfileService npcProfileService;
    private ICmsTopicChapterService chapterService;
    private ICmsMemberTemplateService templateService;

    @BeforeEach
    void setUp() {
        nodeService = mock(ICmsTopicNodeService.class);
        templateService = mock(ICmsMemberTemplateService.class);
        routeSessionMapper = mock(TopicRouteSessionMapper.class);
        shopDayProofService = mock(IShopDayProofService.class);
        progressService = mock(IPlayerNodeProgressService.class);
        npcProfileService = mock(NpcProfileService.class);
        chapterService = mock(ICmsTopicChapterService.class);

        PlayEncounterServiceImpl service = new PlayEncounterServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateService", templateService);
        ReflectionTestUtils.setField(service, "topicRouteSessionMapper", routeSessionMapper);
        ReflectionTestUtils.setField(service, "shopDayProofService", shopDayProofService);
        ReflectionTestUtils.setField(service, "npcProfileService", npcProfileService);
        ReflectionTestUtils.setField(service, "playerNodeProgressService", progressService);
        ReflectionTestUtils.setField(service, "cmsTopicChapterService", chapterService);

        controller = spy(new ApiPlayEncounterController());
        doReturn(MEMBER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "playEncounterService", service);

        node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setName("老账房");
        node.setHookText("巷口的风比别处凉");
        node.setTemplateId(TEMPLATE);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(nodeService.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(new ArrayList<CmsTopicNode>(Collections.singletonList(node)));

        template = new CmsMemberTemplate();
        template.setId(TEMPLATE);
        template.setTitle("夜访老账房");
        template.setStoryText("门缝里透出一线灯");
        template.setQuestionName("账本上写着什么");
        template.setQuestionAnswer("流水");
        template.setCorrectAnswer("B");
        template.setAdvancedConfigJson(CONFIG);
        when(templateService.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(template);

        session = new TopicRouteSession();
        session.setId(RUN);
        session.setMemberId(MEMBER);
        session.setTopicId(TOPIC);
        session.setActivityId(0L);
        session.setVersion(3);
        givenState("{}", "[]");
        when(routeSessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session));
        // Current lookup follows this fixture's exact scope, including activity changes in team cases.
        // The legacy list above still controls missing/ambiguous/foreign omitted-activity gates.
        when(routeSessionMapper.selectCurrentByOwner(any(), any(), any())).thenAnswer(invocation -> {
            Long activity = invocation.getArgument(0);
            Long topic = invocation.getArgument(1);
            Long memberId = invocation.getArgument(2);
            return session != null && java.util.Objects.equals(activity, session.getActivityId())
                    && java.util.Objects.equals(topic, session.getTopicId())
                    && java.util.Objects.equals(memberId, session.getMemberId()) ? session : null;
        });

        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(false);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(new ArrayList<>());

        NpcProfileVO npc = new NpcProfileVO();
        npc.setProfileId(5L);
        npc.setName("阿林");
        npc.setAvatar("https://cdn.example.com/a.png");
        npc.setScopeType(NpcProfileService.SCOPE_NODE);
        when(npcProfileService.resolveForNodeOrMerchant(NODE, null)).thenReturn(npc);
        // R11-A:叙事段按 npcId 在「本主题可用 NPC」里解析 —— 节点级启用 NPC 就是这份名单。
        when(npcProfileService.resolveEnabledList(NpcProfileService.SCOPE_NODE, NODE))
                .thenReturn(Collections.singletonList(npc));
    }

    /** state_json 与 TopicRouteEngine.State 同形:variables / tags / completedNodeIds。 */
    private void givenState(String variables, String tags) {
        session.setStateJson("{\"status\":\"ACTIVE\",\"variables\":" + variables
                + ",\"tags\":" + tags + ",\"completedNodeIds\":[]}");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(AjaxResult response) {
        assertEquals(200, response.get("code"));
        return (Map<String, Object>) response.get("data");
    }

    private com.fasterxml.jackson.databind.node.ObjectNode contentSnapshot(String title) throws Exception {
        ObjectMapper json = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode snapshot = json.createObjectNode();
        snapshot.putObject("journeyRules").put("schemaVersion",1); snapshot.put("journeyStory","");
        snapshot.putObject("nodeConfigs").set(String.valueOf(NODE), json.readTree(CONFIG));
        snapshot.putObject("nodeJourney").set(String.valueOf(NODE), json.readTree(CONFIG));
        com.fasterxml.jackson.databind.node.ObjectNode content = snapshot.putObject("nodeAnswers").putObject(String.valueOf(NODE));
        content.put("contentSnapshotVersion", 1).put("templateId", TEMPLATE);
        content.put("questionAnswer", "never-public-answer").put("correctAnswer", "never-public-key");
        content.put("answerReveal", "never-public-reveal");
        content.putObject("nodePresentation").put("id", NODE).put("topicId", TOPIC)
                .put("templateId", TEMPLATE).put("chapterId", 81L).put("name", "原节点")
                .put("hookText", "原钩子").put("imgUrl", "/frozen/node.png");
        content.putObject("templatePresentation").put("title", title).put("storyText", "原故事")
                .put("imgUrl", "/frozen/template.png")
                .put("audioUrl", "/frozen/template.mp3");
        content.put("hint1", "never-public-unused-hint");
        content.put("chapterAudioUrl", "/frozen/chapter.mp3");
        return snapshot;
    }

    @Test
    void publishedContentSurvivesAuthorUnbindDeleteAndMediaChangesWithoutReadingDraft() throws Exception {
        session.setConfigSnapshotJson(contentSnapshot("原游戏").toString());
        node.setTemplateId(null); node.setHookText("作者的新钩子");
        template.setTitle("作者的新标题"); template.setStoryText("作者的新故事");
        when(templateService.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(null);

        Map<String, Object> result = data(controller.encounter(TOPIC, null, NODE));
        Map<?, ?> presentation = (Map<?, ?>) result.get("presentation");
        assertEquals("原游戏", presentation.get("title"));
        assertEquals("原故事", presentation.get("storyText"));
        assertEquals("原钩子", presentation.get("hook"));
        assertTrue(((List<?>) result.get("preload")).contains("/frozen/chapter.mp3"));
        String publicJson = new ObjectMapper().writeValueAsString(result);
        assertFalse(publicJson.contains("never-public-"));
        verify(nodeService).selectCmsTopicNodeById(NODE);
        verify(templateService, never()).selectCmsMemberTemplateById(any());
        verify(chapterService, never()).selectCmsTopicChapterById(any());
    }

    @Test
    void separateOriginalRunContentDoesNotShareMutablePresentation() throws Exception {
        session.setConfigSnapshotJson(contentSnapshot("原局一").toString());
        Map<String, Object> first = data(controller.encounter(TOPIC, null, NODE));
        session.setConfigSnapshotJson(contentSnapshot("原局二").toString());
        session.setId(RUN + 1L);
        Map<String, Object> second = data(controller.encounter(TOPIC, null, NODE));
        assertEquals("原局一", ((Map<?, ?>) first.get("presentation")).get("title"));
        assertEquals("原局二", ((Map<?, ?>) second.get("presentation")).get("title"));
        assertEquals(RUN, first.get("runId"));
        assertEquals(RUN + 1L, second.get("runId"));
    }

    @Test
    void brokenNewContentNeverFallsBackToLiveTemplate() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode snapshot = contentSnapshot("原游戏");
        ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot.path("nodeAnswers").path(String.valueOf(NODE)))
                .remove("templatePresentation");
        session.setConfigSnapshotJson(snapshot.toString());
        assertEquals(500, controller.encounter(TOPIC, null, NODE).get("code"));
        verify(templateService, never()).selectCmsMemberTemplateById(any());
    }

    @Test
    void frozenPresentationDoesNotReconstructACurrentlyRemovedNode() throws Exception {
        session.setConfigSnapshotJson(contentSnapshot("原游戏").toString());
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(null);
        assertEquals(500, controller.encounter(TOPIC, null, NODE).get("code"));
        verify(templateService, never()).selectCmsMemberTemplateById(any());
    }

    @Test
    void notArrivedClearsAllowedActionsButKeepsBeatShape() {
        givenState("{\"clue.ledger_page\":1}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(false);

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        assertEquals(NODE, data.get("nodeId"));
        assertEquals(RUN, data.get("runId"));
        assertEquals(3, data.get("stateVersion"));
        assertEquals(Boolean.FALSE, data.get("arrived"));
        assertEquals(Boolean.FALSE, data.get("locked"));
        assertEquals(Collections.emptyList(), data.get("missingKeys"));
        assertEquals(Collections.emptyList(), data.get("allowedActions"));

        Map<String, Object> presentation = (Map<String, Object>) data.get("presentation");
        assertEquals("夜访老账房", presentation.get("title"));
        assertEquals("巷口的风比别处凉", presentation.get("hook"));
        assertEquals("门缝里透出一线灯", presentation.get("storyText"));
        assertEquals(Boolean.FALSE, presentation.get("revisit"));
        Map<String, Object> npc = (Map<String, Object>) presentation.get("npc");
        assertEquals(5L, npc.get("id"));
        assertEquals("阿林", npc.get("name"));
        assertEquals("https://cdn.example.com/a.png", npc.get("avatar"));
        assertEquals("node", npc.get("role"));
        assertNotNull(data.get("check"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void requiresUnmetLocksAndListsMissingKeys() {
        givenState("{\"counter.trust\":3}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        assertEquals(Boolean.TRUE, data.get("arrived"));
        assertEquals(Boolean.TRUE, data.get("locked"));
        assertEquals(Collections.singletonList("clue.ledger_page"), data.get("missingKeys"));
        assertEquals(Collections.emptyList(), data.get("allowedActions"));

        Map<String, Object> check = (Map<String, Object>) data.get("check");
        List<Map<String, Object>> mods = (List<Map<String, Object>>) check.get("mods");
        assertEquals(Boolean.FALSE, mods.get(0).get("held"));
    }

    /**
     * 阶段 3:力竭时 roll() 会拒,Encounter 必须让前端看得出原因。
     * 摘掉 check.exhausted,前端就只能等玩家点下去吃一个报错 —— 灰着不给理由。
     */
    @SuppressWarnings("unchecked")
    @Test
    void checkViewCarriesExhaustedSoTheClientCanExplainWhyRollIsBlocked() {
        givenState("{\"sys.hp\":0,\"sys.exhausted\":1}", "[]");

        Map<String, Object> check = (Map<String, Object>) data(controller.encounter(TOPIC, null, NODE)).get("check");

        assertEquals(Boolean.TRUE, check.get("exhausted"));
    }

    /**
     * 力竭时 check 不出现在 allowedActions —— roll() 那边会拒,这边还摆着就是「灰着不给理由」。
     * ⚠️ 只摘 check:同站的玩法照常可做,力竭挡的是检定不是整站。
     */
    @SuppressWarnings("unchecked")
    @Test
    void exhaustedNodeDropsCheckFromAllowedActionsButKeepsTheGame() {
        // requires 也要满足,否则整站 locked、allowedActions 恒空,这条断言就测不到力竭。
        givenState("{\"clue.ledger_page\":1,\"sys.hp\":0,\"sys.exhausted\":1}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        List<String> actions = (List<String>) data(controller.encounter(TOPIC, null, NODE)).get("allowedActions");

        assertFalse(actions.contains("check"), "力竭了就别再把检定摆出来");
        assertTrue(actions.contains("qa"), "玩法不受力竭影响");
    }

    /**
     * 2026-09-24 C-05:主题没开检定时,节点即使挂了检定也不摆 —— roll() 会报「本主题未启用检定」。
     * 配对的「开了就摆」在下一条,防这条是「反正从来不摆」蒙过去的。
     */
    @SuppressWarnings("unchecked")
    @Test
    void topicWithoutCheckDoesNotOfferCheck() {
        List<String> actions = actionsWithTopicCheck(false);
        assertFalse(actions.contains("check"), "主题没开检定,摆出来就是一点就错");
    }

    @SuppressWarnings("unchecked")
    @Test
    void topicWithCheckOffersCheck() {
        assertTrue(actionsWithTopicCheck(true).contains("check"));
    }

    @SuppressWarnings("unchecked")
    private List<String> actionsWithTopicCheck(boolean on) {
        session.setConfigSnapshotJson("{\"schemaVersion\":1,\"startNodeId\":101,\"terminalNodeIds\":[101],"
                + "\"edges\":[],\"nodeJourney\":{\"" + NODE + "\":{"
                + "\"check\":{\"enabled\":true,\"checkId\":\"ask_x\",\"tier\":\"easy\"}}},"
                + "\"journeyRules\":{\"schemaVersion\":1,\"checkEnabled\":" + on + "}}");
        givenState("{}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);
        return (List<String>) data(controller.encounter(TOPIC, null, NODE)).get("allowedActions");
    }

    @SuppressWarnings("unchecked")
    @Test
    void checkPresentationUsesTheSameDefaultAsTheRuntime() {
        Map<String, Object> check = (Map<String, Object>) data(controller.encounter(TOPIC, null, NODE)).get("check");
        assertEquals("inline", check.get("present"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void explicitFrozenCheckPresentationSurvivesLaterTemplateEdit() {
        session.setConfigSnapshotJson("{\"nodeJourney\":{\"" + NODE + "\":{"
                + "\"present\":\"fullscreen\",\"check\":{\"enabled\":true,\"checkId\":\"frozen\"}}}}");
        template.setAdvancedConfigJson(CONFIG.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"present\":\"inline\""));
        Map<String, Object> check = (Map<String, Object>) data(controller.encounter(TOPIC, null, NODE)).get("check");
        assertEquals("frozen", check.get("checkId"));
        assertEquals("fullscreen", check.get("present"));
    }

    /** 配对:没力竭时是 false,不是缺字段 —— 前端不用区分「没力竭」和「后端没给」。 */
    @SuppressWarnings("unchecked")
    @Test
    void checkViewSaysNotExhaustedWhenHealthy() {
        givenState("{\"sys.hp\":8}", "[]");

        Map<String, Object> check = (Map<String, Object>) data(controller.encounter(TOPIC, null, NODE)).get("check");

        assertEquals(Boolean.FALSE, check.get("exhausted"));
    }

    @Test
    void missingKeysListsTagValueWhenTagConditionFails() {
        template.setAdvancedConfigJson("{\"schemaVersion\":1,"
                + "\"requires\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"}]}");
        givenState("{}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        assertEquals(Boolean.TRUE, data.get("locked"));
        assertEquals(Collections.singletonList("tag.hurried"), data.get("missingKeys"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void arrivedAndUnlockedExposesActionsAndCheckPublicFace() {
        givenState("{\"clue.ledger_page\":1}", "[\"tag.hurried\"]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        assertEquals(Boolean.TRUE, data.get("arrived"));
        assertEquals(Boolean.FALSE, data.get("locked"));
        assertEquals(Arrays.asList("qa", "check"), data.get("allowedActions"));

        Map<String, Object> check = (Map<String, Object>) data.get("check");
        assertEquals("ask_lin", check.get("checkId"));
        assertEquals("察言", check.get("skill"));
        assertEquals("medium", check.get("tier"));
        List<Map<String, Object>> mods = (List<Map<String, Object>>) check.get("mods");
        assertEquals(2, mods.size());
        Map<String, Object> first = mods.get(0);
        assertEquals("账本页", first.get("label"));
        assertEquals(Integer.valueOf(2), first.get("value"));
        assertEquals(Boolean.TRUE, first.get("held"));
        Map<String, Object> when = (Map<String, Object>) first.get("when");
        assertEquals("clue.ledger_page", when.get("var"));
        assertEquals("GTE", when.get("op"));
        assertEquals(Integer.valueOf(1), when.get("value"));
        assertEquals("慌张", mods.get(1).get("label"));
        assertEquals(Boolean.TRUE, mods.get(1).get("held"));
        assertEquals("HAS_TAG", ((Map<String, Object>) mods.get(1).get("when")).get("op"));
        assertEquals(Boolean.TRUE, check.get("advantage"));
        assertEquals(Boolean.TRUE, check.get("disadvantage"));

        Map<String, Object> outcomeContract = (Map<String, Object>) data.get("outcomeContract");
        assertEquals(Collections.singletonList("LEDGER_OPEN"), outcomeContract.get("outcomeCodes"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void advantageAndDisadvantageEvaluateIndependently() {
        givenState("{\"clue.ledger_page\":1}", "[]"); // advantageIf 满足;disadvantageIf 的 tag.hurried 未置位
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        Map<String, Object> check = (Map<String, Object>) data.get("check");
        assertEquals(Boolean.TRUE, check.get("advantage"));
        assertEquals(Boolean.FALSE, check.get("disadvantage"));
    }

    /**
     * 线性旅程局(topic_route_session.state_json,没有 edges)与分支局读同一份状态形状:
     * NODE_COMPLETED / tag / 变量都按本局状态求值。R4-RUN 起线性主题也会建这样的局。
     */
    @Test
    void linearJourneySessionStateServesTheSameContract() {
        template.setAdvancedConfigJson("{\"schemaVersion\":1,"
                + "\"requires\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":101},"
                + "{\"var\":\"sys.hp\",\"op\":\"GTE\",\"value\":3}]}");
        session.setStateJson("{\"status\":\"ACTIVE\",\"currentNodeId\":102,"
                + "\"variables\":{\"sys.hp\":7,\"sys.luck\":2},\"tags\":[],"
                + "\"completedNodeIds\":[101]}");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        assertEquals(Boolean.FALSE, data.get("locked"));
        assertEquals(Collections.emptyList(), data.get("missingKeys"));
    }

    @Test
    void revisitIsTrueWhenThisNodeWasAlreadyCompleted() {
        givenState("{\"clue.ledger_page\":1}", "[]");
        com.chengyinhub.business.domain.PlayerNodeProgress done =
                new com.chengyinhub.business.domain.PlayerNodeProgress();
        done.setNodeId(NODE);
        done.setStatus(1);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(done));

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        @SuppressWarnings("unchecked")
        Map<String, Object> presentation = (Map<String, Object>) data.get("presentation");
        assertEquals(Boolean.TRUE, presentation.get("revisit"));
    }

    @Test
    void revisitProbeIsScopedToThisRun() {
        givenState("{\"clue.ledger_page\":1}", "[]");

        data(controller.encounter(TOPIC, null, NODE));

        org.mockito.ArgumentCaptor<com.chengyinhub.business.domain.PlayerNodeProgress> captor =
                org.mockito.ArgumentCaptor.forClass(com.chengyinhub.business.domain.PlayerNodeProgress.class);
        org.mockito.Mockito.verify(progressService).selectPlayerNodeProgressList(captor.capture());
        assertEquals(0L, captor.getValue().getActivityId());
        assertEquals(TOPIC, captor.getValue().getTopicId());
        assertEquals(NODE, captor.getValue().getNodeId());
    }

    @Test
    void serializedResponseCarriesNoAnswerFields() throws Exception {
        givenState("{\"clue.ledger_page\":1}", "[\"tag.hurried\"]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        String json = new ObjectMapper().writeValueAsString(controller.encounter(TOPIC, null, NODE));

        assertTrue(json.contains("\"allowedActions\""));
        assertNoAnswerFields(json);
    }

    /** 序列化全文扫描:答案/正确项/揭示文案与判定收尾文案一个字都不许出现。 */
    private static void assertNoAnswerFields(String json) {
        String lower = json.toLowerCase();
        for (String token : Arrays.asList("answerreveal", "questionanswer", "correctanswer",
                "\"answer\"", "\"correct\"", "successtext", "failtext", "persona", "questionname",
                "nextstepid", "answerorder", "\"pairs\"")) {
            assertFalse(lower.contains(token), "响应里出现了答案类字段 " + token + ":" + json);
        }
    }

    @Test
    void nodeTopicMismatchIsBusinessError() {
        node.setTopicId(999L);

        AjaxResult response = controller.encounter(TOPIC, null, NODE);

        assertEquals(500, response.get("code"));
        assertEquals("节点不存在", response.get("msg"));
    }

    @Test
    void runMissingIsBusinessError() {
        when(routeSessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(null));

        AjaxResult response = controller.encounter(TOPIC, null, NODE);

        assertEquals(500, response.get("code"));
        assertEquals("这一局还没开始", response.get("msg"));
    }

    @Test
    void foreignRunIsRejected() {
        session.setMemberId(999L);

        AjaxResult response = controller.encounter(TOPIC, null, NODE);

        assertEquals(500, response.get("code"));
        assertEquals("无权访问这一局", response.get("msg"));
    }

    @Test
    void unusableRunStateIsBusinessError() {
        session.setStateJson(null);

        AjaxResult response = controller.encounter(TOPIC, null, NODE);

        assertEquals(500, response.get("code"));
        assertEquals("本局状态不可用", response.get("msg"));
    }

    @Test
    void missingConfigIsBusinessError() {
        template.setAdvancedConfigJson(null);

        AjaxResult response = controller.encounter(TOPIC, null, NODE);

        assertEquals(500, response.get("code"));
        assertEquals("该节点未配置玩法", response.get("msg"));
    }

    /** B8 冻结:requires / check 读建局 nodeJourney,模板改后不改进行中的局。 */
    @SuppressWarnings("unchecked")
    @Test
    void frozenNodeJourneyControlsRequiresAndCheckAfterTemplateEdit() {
        session.setConfigSnapshotJson("{\"schemaVersion\":1,\"startNodeId\":101,\"terminalNodeIds\":[101],"
                + "\"edges\":[],\"nodeJourney\":{\"" + NODE + "\":{"
                + "\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}],"
                + "\"check\":{\"enabled\":true,\"checkId\":\"ask_frozen\",\"skill\":\"察言\",\"tier\":\"hard\","
                + "\"mods\":[{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},"
                + "\"value\":2,\"label\":\"账本页\"}]}}},"
                + "\"journeyRules\":{\"schemaVersion\":1,\"checkEnabled\":true}}");
        givenState("{\"clue.ledger_page\":1}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);
        // 开局后创作者改模板:requires 改到不可能满足、检定停用、玩法段清空
        template.setAdvancedConfigJson(
                "{\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":99}],"
                        + "\"check\":{\"enabled\":false}}");

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        assertEquals(Boolean.FALSE, data.get("locked"), "requires 读冻结块(clue.ledger_page>=1)");
        assertEquals(Collections.emptyList(), data.get("missingKeys"));
        Map<String, Object> check = (Map<String, Object>) data.get("check");
        assertEquals("ask_frozen", check.get("checkId"), "check 读冻结块");
        assertEquals("hard", check.get("tier"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) ((List<Object>) check.get("mods")).get(0)).get("held"));
        List<String> actions = (List<String>) data.get("allowedActions");
        assertTrue(actions.contains("check"), "冻结块的 check 启用态说了算");
        assertFalse(actions.contains("qa"), "实时模板已清空的玩法段不再下发");
    }

    /**
     * R17-INT 待办 b:到店后排序 / 连线 / 分类 / 对照四段必须出现在 allowedActions ——
     * PLAYABLE_SECTIONS 漏登记,客户端拿不到入口,玩法静默不存在。
     *
     * <p>负控:把任一段从 PLAYABLE_SECTIONS 摘掉,本用例立刻红。
     */
    @SuppressWarnings("unchecked")
    @Test
    void arrivedNodeOffersTheFourPlaySections() {
        givenState("{}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);
        for (String section : Arrays.asList("sort", "match", "classify", "compare")) {
            template.setAdvancedConfigJson("{\"schemaVersion\":1,\"" + section + "\":{\"enabled\":true}}");

            List<String> actions = (List<String>) data(controller.encounter(TOPIC, null, NODE)).get("allowedActions");

            assertEquals(Collections.singletonList(section), actions,
                    section + " 到店后应下发到 allowedActions");
        }
    }

    /**
     * R22-C4:allowedActions 的玩法段名单读建局冻结的 playables(与 check 同源)—— 会话中途
     * 新加玩法不穿透进行中的局;键在(哪怕空对象)就以快照为准,老快照没有该键才回退实时模板。
     *
     * <p>负控:allowedActions 改回读 liveConfig,本用例立刻红(实时新增的 sort 会混进来)。
     */
    @SuppressWarnings("unchecked")
    @Test
    void allowedActionsReadsFrozenPlayableSections() {
        session.setConfigSnapshotJson("{\"schemaVersion\":1,\"startNodeId\":101,\"terminalNodeIds\":[101],"
                + "\"edges\":[],\"nodeJourney\":{\"" + NODE + "\":{\"playables\":{\"qa\":true}}}}");
        givenState("{}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        List<String> actions = (List<String>) data(controller.encounter(TOPIC, null, NODE)).get("allowedActions");

        assertEquals(Collections.singletonList("qa"), actions, "冻结名单说了算,实时新增段不出现");

        // 冻结成空对象 = 建局时没有玩法入口,同样不回退实时配置
        session.setConfigSnapshotJson("{\"schemaVersion\":1,\"startNodeId\":101,\"terminalNodeIds\":[101],"
                + "\"edges\":[],\"nodeJourney\":{\"" + NODE + "\":{\"playables\":{}}}}");

        assertEquals(Collections.emptyList(),
                (List<String>) data(controller.encounter(TOPIC, null, NODE)).get("allowedActions"));
    }

    /** 本次改动前建的老局快照没有 nodeJourney:回退实时模板配置(兼容)。 */
    @Test
    void snapshotWithoutNodeJourneyFallsBackToLiveTemplate() {
        session.setConfigSnapshotJson(
                "{\"schemaVersion\":1,\"startNodeId\":101,\"terminalNodeIds\":[101],\"edges\":[]}");
        givenState("{}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);
        template.setAdvancedConfigJson(
                "{\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":99}]}");

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        assertEquals(Boolean.TRUE, data.get("locked"), "老快照回退读实时 requires");
        assertEquals(Collections.singletonList("clue.ledger_page"), data.get("missingKeys"));
    }

    @Test
    void notLoggedInOrBadParamsRejected() {
        doReturn(0L).when(controller).getAppUserId();
        assertEquals("请先登录", controller.encounter(TOPIC, null, NODE).get("msg"));

        doReturn(MEMBER).when(controller).getAppUserId();
        assertEquals("参数有误", controller.encounter(null, null, NODE).get("msg"));
        assertEquals("参数有误", controller.encounter(TOPIC, null, 0L).get("msg"));
    }

    // ==================== R8 叙事段:enter / outcome / deliver / unlock / revisit ====================

    private static final String NARRATIVE_CONFIG = "{\"schemaVersion\":1,"
            + "\"check\":{\"enabled\":true,\"checkId\":\"ask_lin\",\"skill\":\"察言\",\"tier\":\"easy\"},"
            + "\"enter\":{\"npcId\":5,\"opener\":\"推门时风铃响了一声\",\"aside\":\"他今天没戴眼镜\","
            + "\"questions\":[{\"id\":\"night\",\"q\":\"还在等谁?\",\"a\":\"等一个不回来的人\"},"
            + "{\"id\":\"ledger\",\"q\":\"账本呢?\",\"a\":\"锁在柜里\"}]},"
            + "\"outcome\":{\"title\":\"老账房的夜\",\"npcId\":5,\"line\":\"他认得你了\","
            + "\"lineIfFail\":\"他没认出来\",\"open\":\"风停了\","
            + "\"facts\":[{\"text\":\"算盘换过位置\"},"
            + "{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},\"text\":\"你翻到过那一页\"}]},"
            + "\"deliver\":{\"reward\":\"一杯热茶\",\"claimLater\":true,\"line\":\"茶还温着\"},"
            + "\"unlock\":{\"hook\":\"偏门还亮着\"},"
            + "\"revisit\":{\"changes\":[{\"text\":\"门槛被踩平了\"},"
            + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},\"text\":\"你上回跑着来\"}]}}";

    /** 叙事配置 + 指定本局状态,返回响应 data。 */
    private Map<String, Object> narrativeData(String variables, String tags, String completedNodeIds) {
        template.setAdvancedConfigJson(NARRATIVE_CONFIG);
        session.setStateJson("{\"status\":\"ACTIVE\",\"variables\":" + variables
                + ",\"tags\":" + tags + ",\"completedNodeIds\":" + completedNodeIds + "}");
        return data(controller.encounter(TOPIC, null, NODE));
    }

    /** B5 的检定 settle 回执:测试用它决定 outcome.line 走成功还是失败那句。 */
    private void givenSettledCheck(boolean success) {
        com.chengyinhub.business.domain.TopicRouteAction action =
                new com.chengyinhub.business.domain.TopicRouteAction();
        action.setResponseJson("{\"checkId\":\"ask_lin\",\"settled\":true,\"success\":" + success + "}");
        when(routeSessionMapper.selectActionByAction(RUN, MEMBER, "check:101:ask_lin:settle")).thenReturn(action);
    }

    /**
     * enter / unlock / deliver 到店前就下发;outcome 与 deliver.line 只在本节点完成后才有;
     * questions[].a 只在该问题问过后才有 —— 没问过的连字段都不能出现(null 也是一种泄题)。
     */
    @SuppressWarnings("unchecked")
    @Test
    void enterShipsBeforeArrivalButFutureScriptStaysHidden() {
        Map<String, Object> data = narrativeData("{}", "[]", "[]");

        Map<String, Object> enter = (Map<String, Object>) data.get("enter");
        assertNotNull(enter);
        assertEquals(5L, ((Map<String, Object>) enter.get("npc")).get("id"));
        assertEquals("推门时风铃响了一声", enter.get("opener"));
        assertEquals("他今天没戴眼镜", enter.get("aside"));
        List<Map<String, Object>> questions = (List<Map<String, Object>>) enter.get("questions");
        assertEquals(2, questions.size());
        assertEquals("night", questions.get(0).get("id"));
        assertEquals("还在等谁?", questions.get(0).get("q"));
        assertEquals(Boolean.FALSE, questions.get(0).get("asked"));
        assertFalse(questions.get(0).containsKey("a"), "没问过就不该带答话");

        assertEquals("偏门还亮着", ((Map<String, Object>) data.get("unlock")).get("hook"));
        Map<String, Object> deliver = (Map<String, Object>) data.get("deliver");
        assertEquals("一杯热茶", deliver.get("reward"));
        assertEquals(Boolean.TRUE, deliver.get("claimLater"));
        assertFalse(deliver.containsKey("line"), "没完成就不该有 deliver.line");

        assertFalse(data.containsKey("outcome"), "没完成就不能下发 outcome");
        assertFalse(data.containsKey("revisit"), "没复访就不能下发 changes");
    }

    @SuppressWarnings("unchecked")
    @Test
    void askedQuestionCarriesItsAnswerOnly() {
        Map<String, Object> data = narrativeData("{\"sys.asked.101.night\":1}", "[]", "[]");

        List<Map<String, Object>> questions =
                (List<Map<String, Object>>) ((Map<String, Object>) data.get("enter")).get("questions");
        assertEquals(Boolean.TRUE, questions.get(0).get("asked"));
        assertEquals("等一个不回来的人", questions.get(0).get("a"));
        assertEquals(Boolean.FALSE, questions.get(1).get("asked"));
        assertFalse(questions.get(1).containsKey("a"), "只问过 night,ledger 的答话不能跟着出");
    }

    @SuppressWarnings("unchecked")
    @Test
    void completedNodeShipsOutcomeWithFilteredFactsAndDeliverLine() {
        Map<String, Object> data = narrativeData("{\"clue.ledger_page\":1}", "[]", "[101]");

        Map<String, Object> outcome = (Map<String, Object>) data.get("outcome");
        assertNotNull(outcome);
        assertEquals("老账房的夜", outcome.get("title"));
        assertEquals("他认得你了", outcome.get("line"));
        assertEquals("风停了", outcome.get("open"));
        assertEquals(Arrays.asList("算盘换过位置", "你翻到过那一页"), outcome.get("facts"));
        assertEquals("茶还温着", ((Map<String, Object>) data.get("deliver")).get("line"));
    }

    /** 条件不过的 fact 一个字都不出。 */
    @SuppressWarnings("unchecked")
    @Test
    void outcomeFactsRespectTheirConditions() {
        Map<String, Object> data = narrativeData("{}", "[]", "[101]");

        assertEquals(Collections.singletonList("算盘换过位置"),
                ((Map<String, Object>) data.get("outcome")).get("facts"));
    }

    /** line 按本节点最近一次检定成败选:失败取 lineIfFail,成功取 line。 */
    @SuppressWarnings("unchecked")
    @Test
    void outcomeLineFollowsTheSettledCheck() {
        givenSettledCheck(false);
        assertEquals("他没认出来",
                ((Map<String, Object>) narrativeData("{}", "[]", "[101]").get("outcome")).get("line"));

        givenSettledCheck(true);
        assertEquals("他认得你了",
                ((Map<String, Object>) narrativeData("{}", "[]", "[101]").get("outcome")).get("line"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void revisitChangesOnlyWhenReturningAndConditionPasses() {
        com.chengyinhub.business.domain.PlayerNodeProgress done =
                new com.chengyinhub.business.domain.PlayerNodeProgress();
        done.setNodeId(NODE);
        done.setStatus(1);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(done));

        Map<String, Object> cold = narrativeData("{}", "[]", "[]");
        assertEquals(Collections.singletonList("门槛被踩平了"),
                ((Map<String, Object>) cold.get("revisit")).get("changes"));

        Map<String, Object> hurried = narrativeData("{}", "[\"tag.hurried\"]", "[]");
        assertEquals(Arrays.asList("门槛被踩平了", "你上回跑着来"),
                ((Map<String, Object>) hurried.get("revisit")).get("changes"));
    }

    /**
     * 序列化全文扫描:未完成、没问过的这一拍里,任何「还没演到」的文案都不许出现。
     * 负控:去掉 outcome / deliver.line 的完成闸,本用例立刻红。
     */
    @Test
    void unfinishedSerializationCarriesNoFutureScript() throws Exception {
        template.setAdvancedConfigJson(NARRATIVE_CONFIG);
        session.setStateJson("{\"status\":\"ACTIVE\",\"variables\":{},\"tags\":[],\"completedNodeIds\":[]}");

        String json = new ObjectMapper().writeValueAsString(controller.encounter(TOPIC, null, NODE));

        assertTrue(json.contains("推门时风铃响了一声"), json);
        assertFalse(json.contains("\"outcome\""), json);
        assertFalse(json.contains("老账房的夜"), json);
        assertFalse(json.contains("他认得你了"), json);
        assertFalse(json.contains("茶还温着"), json);
        assertFalse(json.contains("等一个不回来的人"), json);
        assertFalse(json.contains("\"a\":"), json);
    }

    // ==================== R9 叙事段:brief / route ====================

    private static final String BRIEF_CONFIG = "{\"schemaVersion\":1,"
            + "\"enter\":{\"opener\":\"推门时风铃响了一声\"},"
            + "\"brief\":{\"question\":\"账本上写着什么\",\"body\":\"替我去柜台取那本账\","
            + "\"carry\":[{\"text\":\"随身带着签收联\"},"
            + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},\"text\":\"路上慢一点\"},"
            + "{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},\"text\":\"带上那一页\"}]},"
            + "\"route\":{\"walk\":\"约 6 分钟\",\"todo\":\"找到柜台旁带城瘾游戏标识的展示台\"}}";

    /**
     * brief / route 到店前就下发;carry 只给 when 缺省 / null 或条件通过的 text。
     *
     * <p>负控:把 brief() 里的 passingTexts 换成不判条件的遍历,本用例立刻红。
     */
    @SuppressWarnings("unchecked")
    @Test
    void briefAndRouteShipBeforeArrivalWithFilteredCarry() {
        template.setAdvancedConfigJson(BRIEF_CONFIG);
        givenState("{}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(false);

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        assertEquals(Boolean.FALSE, data.get("arrived"));
        Map<String, Object> brief = (Map<String, Object>) data.get("brief");
        assertNotNull(brief);
        assertEquals("账本上写着什么", brief.get("question"));
        assertEquals("替我去柜台取那本账", brief.get("body"));
        assertEquals(Collections.singletonList("随身带着签收联"), brief.get("carry"),
                "条件不过的 carry 一条都不该下发");
        Map<String, Object> route = (Map<String, Object>) data.get("route");
        assertEquals("约 6 分钟", route.get("walk"));
        assertEquals("找到柜台旁带城瘾游戏标识的展示台", route.get("todo"));
    }

    /** 条件通过后 carry 按配置顺序补齐;NODE_COMPLETED 这类与状态无关的条目另行按条件求值。 */
    @SuppressWarnings("unchecked")
    @Test
    void briefCarryFollowsRunStateConditions() {
        template.setAdvancedConfigJson(BRIEF_CONFIG);
        givenState("{\"clue.ledger_page\":1}", "[\"tag.hurried\"]");

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        assertEquals(Arrays.asList("随身带着签收联", "路上慢一点", "带上那一页"),
                ((Map<String, Object>) data.get("brief")).get("carry"));
    }

    /** 老配置(缺 brief / route)的响应里不冒出这两段 —— 只追加,不是恒有。 */
    @Test
    void legacyConfigCarriesNoBriefOrRoute() {
        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        assertFalse(data.containsKey("brief"));
        assertFalse(data.containsKey("route"));
    }

    /**
     * R10-INT 集成收口:建局冻结同样覆盖 brief / route —— 冻结块有值时,开局后改模板不影响这一局。
     *
     * <p>负控:把 PlayEncounterServiceImpl.appendNarrative 的入参从 journey 换回 liveConfig,
     * 本用例立刻红(响应会读实时模板的「账本上写着什么」)。
     */
    @SuppressWarnings("unchecked")
    @Test
    void frozenNodeJourneyCoversBriefAndRoute() {
        session.setConfigSnapshotJson("{\"schemaVersion\":1,\"startNodeId\":101,\"terminalNodeIds\":[101],"
                + "\"edges\":[],\"nodeJourney\":{\"" + NODE + "\":{"
                + "\"brief\":{\"question\":\"冻结的问题\",\"body\":\"冻结的委托\"},"
                + "\"route\":{\"walk\":\"冻结的六分钟\"}}}}");
        template.setAdvancedConfigJson(BRIEF_CONFIG);
        givenState("{}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(false);

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        Map<String, Object> brief = (Map<String, Object>) data.get("brief");
        assertEquals("冻结的问题", brief.get("question"), "brief 读冻结块");
        assertEquals("冻结的委托", brief.get("body"));
        assertEquals("冻结的六分钟", ((Map<String, Object>) data.get("route")).get("walk"),
                "route 读冻结块");
    }

    // ==================== R11-A 叙事冻结与 npc 按 id 解析 ====================

    /**
     * 开局后改模板叙事:进行中的局仍读建局冻结的 enter / outcome。
     *
     * <p>负控:appendNarrative 的入参从 journey 换回 liveConfig,本用例立刻红(会读到模板的
     * 「推门时风铃响了一声」与「老账房的夜」)。
     */
    @SuppressWarnings("unchecked")
    @Test
    void frozenNodeJourneyCoversNarrativeSegmentsAfterTemplateEdit() {
        session.setConfigSnapshotJson("{\"schemaVersion\":1,\"startNodeId\":101,\"terminalNodeIds\":[101],"
                + "\"edges\":[],\"nodeJourney\":{\"" + NODE + "\":{"
                + "\"enter\":{\"npcId\":5,\"opener\":\"冻结的推门声\"},"
                + "\"outcome\":{\"npcId\":5,\"title\":\"冻结的夜\",\"line\":\"冻结的他认得你了\","
                + "\"facts\":[{\"text\":\"冻结的算盘\"}]}}}}");
        template.setAdvancedConfigJson(NARRATIVE_CONFIG);
        session.setStateJson("{\"status\":\"ACTIVE\",\"variables\":{},\"tags\":[],\"completedNodeIds\":[101]}");

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        assertEquals("冻结的推门声", ((Map<String, Object>) data.get("enter")).get("opener"), "enter 读冻结块");
        Map<String, Object> outcome = (Map<String, Object>) data.get("outcome");
        assertEquals("冻结的夜", outcome.get("title"), "outcome 读冻结块");
        assertEquals("冻结的他认得你了", outcome.get("line"));
        assertEquals(Collections.singletonList("冻结的算盘"), outcome.get("facts"));
    }

    /**
     * enter / outcome 的 npc 按各自 npcId 解析:ID 指向本主题外的 NPC 时 npc=null,
     * 绝不回落到节点 NPC / 门店分身。
     *
     * <p>负控:去掉 narrativeNpc 里的本主题可用名单核对(直接回落到 presentation 的 NPC),
     * 本用例立刻红。
     */
    @SuppressWarnings("unchecked")
    @Test
    void narrativeNpcResolvesByIdAndForeignNpcShipsNull() {
        Map<String, Object> data = narrativeData("{}", "[]", "[101]");
        assertEquals(5L, ((Map<String, Object>) ((Map<String, Object>) data.get("enter")).get("npc")).get("id"));
        assertEquals(5L, ((Map<String, Object>) ((Map<String, Object>) data.get("outcome")).get("npc")).get("id"),
                "outcome 的 npcId 同样按 id 解析");

        template.setAdvancedConfigJson(
                "{\"enter\":{\"npcId\":99,\"opener\":\"外来的声音\"},"
                        + "\"outcome\":{\"npcId\":99,\"title\":\"外来的夜\"}}");
        Map<String, Object> foreign = data(controller.encounter(TOPIC, null, NODE));

        assertNull(((Map<String, Object>) foreign.get("enter")).get("npc"), "主题外 npcId 不下发 npc");
        assertNull(((Map<String, Object>) foreign.get("outcome")).get("npc"));
        assertNotNull(((Map<String, Object>) foreign.get("presentation")).get("npc"),
                "presentation 的节点 NPC 照旧,但不回填进叙事段");
    }

    /** 段里没配 npcId = 这一段没有出场者,不回落到节点 NPC / 门店分身。 */
    @SuppressWarnings("unchecked")
    @Test
    void narrativeWithoutNpcIdShipsNullNpc() {
        template.setAdvancedConfigJson("{\"enter\":{\"opener\":\"没人说话\"}}");

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));

        assertNull(((Map<String, Object>) data.get("enter")).get("npc"));
    }

    // ==================== R18-ENC:outcomeContract 结果行 / fallbackPlan / preload ====================

    /** 结果行专用配置:两个终点 code(带玩家可见标签)+ 各段素材 + 一个停用段。 */
    private static final String R18_CONFIG = "{\"schemaVersion\":1,"
            + "\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"title\":\"铜牌写给谁\","
            + "\"imageUrl\":\"https://cdn.example.com/qa.png\",\"audioUrl\":\"https://cdn.example.com/qa.mp3\","
            + "\"options\":[{\"id\":\"a\",\"label\":\"账房\"},{\"id\":\"b\",\"label\":\"掌柜\"}]},"
            + "\"sort\":{\"enabled\":true,\"prompt\":\"按铜牌新旧排\","
            + "\"items\":[{\"id\":\"i1\",\"label\":\"铜牌\",\"img\":\"https://cdn.example.com/i1.png\"}]},"
            + "\"blindTaste\":{\"enabled\":true,\"title\":\"闭眼闻\",\"imageUrl\":\"https://cdn.example.com/blind.png\"},"
            + "\"hiddenObject\":{\"enabled\":false,\"imageUrl\":\"https://cdn.example.com/hidden.png\"},"
            + "\"branch\":{\"enabled\":true,\"startStepId\":\"s1\",\"steps\":["
            + "{\"id\":\"s1\",\"terminal\":false,\"title\":\"门缝里透光\",\"body\":\"结局正文不该提前看到\","
            + "\"options\":[{\"id\":\"o1\",\"label\":\"推门\",\"nextStepId\":\"s2\"}]},"
            + "{\"id\":\"s2\",\"terminal\":true,\"outcomeCode\":\"LEDGER_OPEN\",\"outcomeLabel\":\"账本翻开\",\"options\":[]},"
            + "{\"id\":\"s3\",\"terminal\":true,\"outcomeCode\":\"LEDGER_SEALED\",\"outcomeLabel\":\"账本合上\",\"options\":[]}]}}";

    /** 建局冻结路线图:本站两条边(一条同 code 重复边)+ 一条别的节点的边(不许进结果行)。 */
    private static final String R18_SNAPSHOT = "{\"schemaVersion\":1,\"startNodeId\":101,\"terminalNodeIds\":[103],"
            + "\"edges\":["
            + "{\"id\":\"e1\",\"fromNodeId\":101,\"toNodeId\":102,"
            + "\"trigger\":{\"type\":\"ADVANCED_RESULT\",\"outcomeCode\":\"LEDGER_OPEN\"}},"
            + "{\"id\":\"e2\",\"fromNodeId\":101,\"toNodeId\":103,"
            + "\"trigger\":{\"type\":\"ADVANCED_RESULT\",\"outcomeCode\":\"LEDGER_SEALED\"}},"
            + "{\"id\":\"e3\",\"fromNodeId\":101,\"toNodeId\":102,"
            + "\"trigger\":{\"type\":\"ADVANCED_RESULT\",\"outcomeCode\":\"LEDGER_OPEN\"}},"
            + "{\"id\":\"e9\",\"fromNodeId\":999,\"toNodeId\":103,"
            + "\"trigger\":{\"type\":\"ADVANCED_RESULT\",\"outcomeCode\":\"FOREIGN_CODE\"}}]}";

    /**
     * 到店后结果行 = 块配置声明的 code + 玩家可见标签 + 冻结路线图里本站的下一站;别的节点的边不算。
     *
     * <p>负控:outcomeDestinations 丢掉 fromNodeId 收窄(收全图边),本用例立刻红
     * (FOREIGN_CODE 会混进 outcomeCodes)。
     */
    @SuppressWarnings("unchecked")
    @Test
    void outcomeContractCarriesLabelsAndRouteGraphDestinations() {
        template.setAdvancedConfigJson(R18_CONFIG);
        session.setConfigSnapshotJson(R18_SNAPSHOT);
        givenState("{}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        Map<String, Object> contract = (Map<String, Object>) data(controller.encounter(TOPIC, null, NODE))
                .get("outcomeContract");

        assertEquals(Arrays.asList("LEDGER_OPEN", "LEDGER_SEALED"), contract.get("outcomeCodes"));
        List<Map<String, Object>> outcomes = (List<Map<String, Object>>) contract.get("outcomes");
        assertEquals(2, outcomes.size());
        assertEquals("LEDGER_OPEN", outcomes.get(0).get("outcomeCode"));
        assertEquals("账本翻开", outcomes.get(0).get("label"));
        assertEquals(Collections.singletonList(102L), outcomes.get(0).get("nextNodeIds"), "同 code 多条边去重");
        assertEquals("账本合上", outcomes.get(1).get("label"));
        assertEquals(Collections.singletonList(103L), outcomes.get(1).get("nextNodeIds"));
    }

    /**
     * R22-C1:结果行的 outcomeCodes / 标签读本局建局冻结的 branchOutcomes(与 nextNodeIds 同源);
     * 老快照没有该键才回退实时模板。开局后改模板的终点声明不穿透进行中的局。
     *
     * <p>负控:outcomeContract 的终点声明改回读 liveConfig,本用例立刻红(实时两个 code 会混进来)。
     */
    @SuppressWarnings("unchecked")
    @Test
    void outcomeContractCodesAndLabelsReadFrozenDeclarationsAfterTemplateEdit() {
        session.setConfigSnapshotJson("{\"schemaVersion\":1,\"startNodeId\":101,\"terminalNodeIds\":[101],"
                + "\"edges\":[],\"nodeJourney\":{\"" + NODE + "\":{\"branchOutcomes\":["
                + "{\"outcomeCode\":\"FROZEN_DONE\",\"outcomeLabel\":\"冻结终点\"}]}}}");
        template.setAdvancedConfigJson(R18_CONFIG);
        givenState("{}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        Map<String, Object> contract = (Map<String, Object>) data(controller.encounter(TOPIC, null, NODE))
                .get("outcomeContract");

        assertEquals(Collections.singletonList("FROZEN_DONE"), contract.get("outcomeCodes"));
        List<Map<String, Object>> outcomes = (List<Map<String, Object>>) contract.get("outcomes");
        assertEquals(1, outcomes.size());
        assertEquals("冻结终点", outcomes.get(0).get("label"));
        assertFalse(contract.get("outcomeCodes").toString().contains("LEDGER_OPEN"),
                "实时模板的终点声明不穿透进行中的局");
    }

    /**
     * R22-C1:冻结成空数组(建局时没有终点声明)同样以快照为准,不回退实时模板 —— 与「键在即快照」口径一致。
     */
    @SuppressWarnings("unchecked")
    @Test
    void outcomeContractEmptyFrozenDeclarationsDoNotFallBackToLive() {
        session.setConfigSnapshotJson("{\"schemaVersion\":1,\"startNodeId\":101,\"terminalNodeIds\":[101],"
                + "\"edges\":[],\"nodeJourney\":{\"" + NODE + "\":{\"branchOutcomes\":[]}}}");
        template.setAdvancedConfigJson(R18_CONFIG);
        givenState("{}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        Map<String, Object> contract = (Map<String, Object>) data(controller.encounter(TOPIC, null, NODE))
                .get("outcomeContract");

        assertEquals(Collections.emptyList(), contract.get("outcomeCodes"));
    }

    /** 到店后,没有路线图边(线性快照 / 老快照)时结果行仍给 code 与标签,nextNodeIds 为空数组。 */
    @SuppressWarnings("unchecked")
    @Test
    void outcomeContractWithoutRouteGraphKeepsCodesAndLabels() {
        template.setAdvancedConfigJson(R18_CONFIG);
        session.setConfigSnapshotJson("{\"schemaVersion\":1,\"startNodeId\":101,\"terminalNodeIds\":[101],\"edges\":[]}");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        Map<String, Object> contract = (Map<String, Object>) data(controller.encounter(TOPIC, null, NODE))
                .get("outcomeContract");

        List<Map<String, Object>> outcomes = (List<Map<String, Object>>) contract.get("outcomes");
        assertEquals("账本翻开", outcomes.get(0).get("label"));
        assertEquals(Collections.emptyList(), outcomes.get(0).get("nextNodeIds"));
    }

    /**
     * fallbackPlan 恒定结构:旅程子系统还没有节点级闭店 / 替代地点 / 人工核销配置。
     *
     * <p>负控:fallbackPlan 少写 manualVerify,本用例立刻红。
     */
    @SuppressWarnings("unchecked")
    @Test
    void fallbackPlanShipsFixedShape() {
        Map<String, Object> plan = (Map<String, Object>) data(controller.encounter(TOPIC, null, NODE))
                .get("fallbackPlan");

        assertEquals(3, plan.size());
        assertEquals(Boolean.FALSE, plan.get("canSkip"));
        assertEquals(Boolean.FALSE, plan.get("manualVerify"));
        assertTrue(plan.containsKey("message"));
        assertNull(plan.get("message"));
    }

    /**
     * preload:封面 → 章节音频 → 节点语音 → 玩法素材 → NPC 头像;去重;停用段的素材不进。
     *
     * <p>负控:preload 改成直扫实时的 advancedConfigJson(不走玩家可见投影),本用例立刻红
     * (停用段的 hidden.png 会混进来)。
     */
    @SuppressWarnings("unchecked")
    @Test
    void preloadShipsDedupedVisibleAssetsOnly() {
        template.setAdvancedConfigJson(R18_CONFIG);
        node.setImgUrl("https://cdn.example.com/node.png");
        template.setImgUrl("https://cdn.example.com/tpl.png");
        template.setAudioUrl("https://cdn.example.com/voice.mp3");
        node.setChapterId(77L);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(77L);
        chapter.setAudioUrl("https://cdn.example.com/chapter.mp3");
        when(chapterService.selectCmsTopicChapterById(77L)).thenReturn(chapter);

        List<String> preload = (List<String>) data(controller.encounter(TOPIC, null, NODE)).get("preload");

        assertEquals("https://cdn.example.com/node.png", preload.get(0), "封面在先");
        assertTrue(preload.contains("https://cdn.example.com/tpl.png"));
        assertTrue(preload.contains("https://cdn.example.com/chapter.mp3"));
        assertTrue(preload.contains("https://cdn.example.com/voice.mp3"));
        assertTrue(preload.contains("https://cdn.example.com/qa.png"));
        assertTrue(preload.contains("https://cdn.example.com/qa.mp3"));
        assertTrue(preload.contains("https://cdn.example.com/i1.png"));
        assertTrue(preload.contains("https://cdn.example.com/blind.png"));
        assertTrue(preload.contains("https://cdn.example.com/a.png"), "出场 NPC 头像");
        assertFalse(preload.contains("https://cdn.example.com/hidden.png"), "停用段的素材不进预载");
        assertEquals(preload.size(), new LinkedHashSet<String>(preload).size(), "preload 必须去重");
    }

    /**
     * 到店前:三个字段都下发,但里面不许有任何能推出答案的东西 ——
     * 分支步骤正文 / 选项 / 跳转映射、玩法答案键、终点标签与下一站去向一个都不许出现。
     *
     * <p>负控:去掉 arrived 闸(label 照发 / nextNodeIds 照发),本用例立刻红。
     */
    @SuppressWarnings("unchecked")
    @Test
    void notArrivedShipsThreeFieldsWithoutAnyAnswerDerivedContent() throws Exception {
        template.setAdvancedConfigJson(R18_CONFIG);
        session.setConfigSnapshotJson(R18_SNAPSHOT);
        givenState("{}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(false);

        Map<String, Object> data = data(controller.encounter(TOPIC, null, NODE));
        String json = new ObjectMapper().writeValueAsString(data);

        assertTrue(json.contains("\"outcomeContract\""));
        assertTrue(json.contains("\"fallbackPlan\""));
        assertTrue(json.contains("\"preload\""));
        Map<String, Object> contract = (Map<String, Object>) data.get("outcomeContract");
        assertEquals(Arrays.asList("LEDGER_OPEN", "LEDGER_SEALED"), contract.get("outcomeCodes"),
                "outcomeCodes 保持现状");
        List<Map<String, Object>> outcomes = (List<Map<String, Object>>) contract.get("outcomes");
        assertEquals(2, outcomes.size());
        for (Map<String, Object> row : outcomes) {
            assertNull(row.get("label"), "到店前终点标签不下发");
            assertEquals(Collections.emptyList(), row.get("nextNodeIds"), "到店前下一站去向不下发");
        }
        assertFalse(json.contains("账本翻开"), "终点标签文本不下发");
        assertFalse(json.contains("账本合上"), "终点标签文本不下发");
        assertFalse(json.contains("\"nextStepId\""), "选项到结果的跳转映射不下发");
        assertFalse(json.contains("\"steps\""), "分支步骤不下发");
        assertFalse(json.contains("结局正文不该提前看到"), "终点正文不下发");
        assertNoAnswerFields(json);
    }

    /**
     * 到店后同一份响应必须把标签与下一站补齐(与到店前对照,证明是闸不是删功能)。
     *
     * <p>负控:arrived 闸写反(label 只在未到店时给),本用例立刻红。
     */
    @SuppressWarnings("unchecked")
    @Test
    void arrivedShipsLabelsAndNextNodeIds() {
        template.setAdvancedConfigJson(R18_CONFIG);
        session.setConfigSnapshotJson(R18_SNAPSHOT);
        givenState("{}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        Map<String, Object> contract = (Map<String, Object>) data(controller.encounter(TOPIC, null, NODE))
                .get("outcomeContract");
        List<Map<String, Object>> outcomes = (List<Map<String, Object>>) contract.get("outcomes");

        assertEquals("账本翻开", outcomes.get(0).get("label"));
        assertEquals(Collections.singletonList(102L), outcomes.get(0).get("nextNodeIds"));
    }

    // ==================== R22-A3 八拍:beat 字段 ====================

    private Object beat() {
        return data(controller.encounter(TOPIC, null, NODE)).get("beat");
    }

    /**
     * 未到店:有 brief 段 → brief,否则 route。
     *
     * <p>负控:beat 的未到店分支写反(brief/route 互换),本用例立刻红。
     */
    @Test
    void beatCoversPreArrivalBriefAndRoute() {
        template.setAdvancedConfigJson(BRIEF_CONFIG);
        givenState("{}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(false);

        assertEquals("brief", beat());

        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"route\":{\"walk\":\"约 6 分钟\"}}");

        assertEquals("route", beat());
    }

    /**
     * 到店未完成:有 enter 且一个问题都没问过 → enter,问过 → investigate;
     * 没有 enter 段则直接 investigate。
     *
     * <p>负控:把 anyQuestionAsked 恒返回 false,investigate 断言立刻红。
     */
    @Test
    void beatCoversArrivalEnterAndInvestigate() {
        template.setAdvancedConfigJson(NARRATIVE_CONFIG);
        givenState("{}", "[]");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        assertEquals("enter", beat(), "enter 配了问题且未问过");

        givenState("{\"sys.asked.101.night\":1}", "[]");

        assertEquals("investigate", beat(), "问过问题就进入调查拍");

        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"unlock\":{\"hook\":\"偏门还亮着\"}}");

        assertEquals("investigate", beat(), "没有 enter 段直接调查");
    }

    /**
     * 本局已完成:有 outcome → outcome;没有 outcome 有 deliver → deliver;都没有 → unlock。
     *
     * <p>负控:去掉 unlock 分支的兜底(改成 outcome),unlock 断言立刻红。
     */
    @Test
    void beatCoversCompletedOutcomeDeliverUnlock() {
        template.setAdvancedConfigJson(NARRATIVE_CONFIG);
        session.setStateJson("{\"status\":\"ACTIVE\",\"variables\":{},\"tags\":[],\"completedNodeIds\":[101]}");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        assertEquals("outcome", beat());

        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"deliver\":{\"reward\":\"一杯热茶\"}}");

        assertEquals("deliver", beat(), "没配 outcome 时看交付段");

        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"unlock\":{\"hook\":\"偏门还亮着\"}}");

        assertEquals("unlock", beat(), "两段都没有时收尾到 unlock");
    }

    /**
     * 复访:这一站已有完成事实(progress status=1,与 presentation.revisit 同源)但本局 state
     * 还没登记完成(推进回执未提交)→ revisit;未到店同样成立(完成事实不依赖本次到店)。
     *
     * <p>负控:revisit 分支去掉,本用例立刻红。
     */
    @Test
    void beatReportsRevisitWhenCompletionFactPrecedesRunState() {
        com.chengyinhub.business.domain.PlayerNodeProgress done =
                new com.chengyinhub.business.domain.PlayerNodeProgress();
        done.setNodeId(NODE);
        done.setStatus(1);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(done));
        template.setAdvancedConfigJson(NARRATIVE_CONFIG);
        givenState("{}", "[]");

        assertEquals("revisit", beat());

        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        assertEquals("revisit", beat());
    }

    /** 本局 state 已登记完成(completedNodeIds)优先于完成事实:仍在结算三拍,不落复访。 */
    @Test
    void beatKeepsOutcomeWhenRunStateAlreadyCompleted() {
        com.chengyinhub.business.domain.PlayerNodeProgress done =
                new com.chengyinhub.business.domain.PlayerNodeProgress();
        done.setNodeId(NODE);
        done.setStatus(1);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(done));
        template.setAdvancedConfigJson(NARRATIVE_CONFIG);
        session.setStateJson("{\"status\":\"ACTIVE\",\"variables\":{},\"tags\":[],\"completedNodeIds\":[101]}");
        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        assertEquals("outcome", beat());
    }

    /** 老配置(没有任何叙事段)也要有稳定的这一拍:未到店 route,到店 investigate,完成 unlock。 */
    @Test
    void beatStaysStableWithoutAnyNarrativeSection() {
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"check\":{\"enabled\":true,\"checkId\":\"c\"}}");
        givenState("{}", "[]");

        assertEquals("route", beat());

        when(shopDayProofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);
        assertEquals("investigate", beat());

        session.setStateJson("{\"status\":\"ACTIVE\",\"variables\":{},\"tags\":[],\"completedNodeIds\":[101]}");
        assertEquals("unlock", beat());
    }
}
