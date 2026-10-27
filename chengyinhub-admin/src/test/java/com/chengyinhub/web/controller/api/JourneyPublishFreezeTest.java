package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayerActivityRoleMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.IRewardDropService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.ITopicCompletionAwardService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import com.chengyinhub.business.service.impl.NodeMerchantOverrideServiceImpl;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.impl.PlayJourneyStoryServiceImpl;
import com.chengyinhub.business.service.impl.PuzzlePlayServiceImpl;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.web.service.PreferencePlayService;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * R18-FREEZE V8:新发布不改写进行中的一局。
 *
 * <p>开局(真 {@link TopicRouteRuntimeServiceImpl} 建局冻结)→ 作者改模板答案 / 检定难度 /
 * 结局条件 → 同一局提交按旧配置判定;老局快照没有 nodeAnswers 键时回退实时模板(兼容)。
 * 持久层是 mock,controller / runtime 是真实现 —— 集成风格,不需要真库。
 */
class JourneyPublishFreezeTest {

    private static final Long MEMBER = 7L;
    private static final Long TOPIC = 10L;
    private static final Long ACTIVITY = 20L;
    private static final Long NODE = 1L;
    private static final Long TEMPLATE = 5L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TopicRouteSessionMapper routeMapper = mock(TopicRouteSessionMapper.class);
    private final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
    private final CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
    private final CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
    private final PlayerNodeProgressMapper progressMapper = mock(PlayerNodeProgressMapper.class);
    private final CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
    private final CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
    private final PlayerActivityRoleMapper roleMapper = mock(PlayerActivityRoleMapper.class);
    private final IShopDayProofService shopDayProofService = mock(IShopDayProofService.class);

    private final ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
    private final ICmsActivityService activityService = mock(ICmsActivityService.class);
    private final ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
    private final ICmsTopicService topicService = mock(ICmsTopicService.class);
    private final ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);
    private final IPlayerNodeProgressService progressService = mock(IPlayerNodeProgressService.class);
    private final IPlayerProgressionService progressionService = mock(IPlayerProgressionService.class);
    private final IGrowthService growthService = mock(IGrowthService.class);
    private final IXpBudgetService xpBudgetService = mock(IXpBudgetService.class);
    private final IRewardDropService rewardDropService = mock(IRewardDropService.class);
    private final ISmsCouponHistoryService couponHistoryService = mock(ISmsCouponHistoryService.class);
    private final ITopicCompletionAwardService completionAwardService = mock(ITopicCompletionAwardService.class);
    private final IPointsAwardService pointsAwardService = mock(IPointsAwardService.class);
    private final UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
    private final PlayAttemptLogMapper puzzleAttemptMapper = mock(PlayAttemptLogMapper.class);
    private final PuzzlePlayServiceImpl puzzlePlayService = new PuzzlePlayServiceImpl(
            mock(ICmsMemberTemplateService.class), puzzleAttemptMapper,
            mock(PlayerNodeProgressMapper.class), memberMapper,
                mock(com.chengyinhub.business.service.ICmsTopicNodeService.class),
                mock(com.chengyinhub.business.service.support.PlayNodeAccessService.class),
                mock(com.chengyinhub.business.service.ICmsPointsSettingService.class),
                mock(com.chengyinhub.business.service.IUmsMemberService.class),
                mock(com.chengyinhub.business.service.IUmsMemberPointsDetailService.class));
    private final PreferencePlayService preferencePlayService = mock(PreferencePlayService.class);
    private final ApiPlayProgressController controller = spy(new ApiPlayProgressController());

    private TopicRouteRuntimeServiceImpl runtime;
    private TopicRouteSession[] stored;
    private List<TopicRouteAction> actions;
    private List<CmsTopicNode> liveNodes;

    private CmsTopic topic;
    private CmsTopicNode node;
    private CmsMemberTemplate template;

    @BeforeEach
    void setUp() {
        runtime = new TopicRouteRuntimeServiceImpl(routeMapper, topicMapper, nodeMapper, templateMapper,
                progressMapper, activityMapper, registrationMapper, roleMapper, shopDayProofService,
                () -> 12345L, () -> 12, Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        stored = new TopicRouteSession[1];
        actions = new ArrayList<TopicRouteAction>();

        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setRouteMode("LINEAR");
        topic.setProductType(1);
        topic.setPublishedConfigVersion(1L);
        topic.setJourneyRulesJson(rulesJson(8, 1));
        topic.setJourneyStoryJson(storyJson("FB"));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setStatus(1L);
        activity.setDelFlag(0);
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);

        CmsRegistration pass = new CmsRegistration();
        pass.setId(9_000_001L); // 真实报名行必有主键；C包当前资格按原ID锁读
        pass.setMemberId(MEMBER); pass.setOwnerType(2L); pass.setOwnerId(ACTIVITY);
        pass.setRegistrationStatus(2);
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(pass));
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(pass));

        node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setTemplateId(TEMPLATE);
        node.setSortId(1L);
        node.setStatus(1L);
        node.setChapterId(3L);
        node.setName("旧书店");
        liveNodes = Collections.singletonList(node);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenAnswer(inv -> liveNodes);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(nodeService.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenAnswer(inv -> liveNodes);

        template = new CmsMemberTemplate();
        template.setId(TEMPLATE);
        template.setValidationMethod(1);
        template.setQuestionAnswer("旧答案");
        template.setAdvancedConfigJson(checkConfig("lantern", "easy"));
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template));
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(template);
        when(templateService.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(template);

        when(routeMapper.selectByOwner(anyLong(), eq(TOPIC), eq(MEMBER), eq("default")))
                .thenAnswer(inv -> stored[0]);
        when(routeMapper.selectAccessibleByIdForUpdate(99L, MEMBER)).thenAnswer(inv -> stored[0]);
        when(routeMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenAnswer(inv -> java.util.Collections.singletonList(stored[0]));
        when(routeMapper.insertSession(any(TopicRouteSession.class))).thenAnswer(inv -> {
            TopicRouteSession value = inv.getArgument(0);
            value.setId(99L);
            stored[0] = value;
            return 1;
        });
        when(routeMapper.insertAction(any(TopicRouteAction.class))).thenAnswer(inv -> {
            TopicRouteAction value = inv.getArgument(0);
            value.setId(600L + actions.size());
            actions.add(value);
            return 1;
        });
        when(routeMapper.selectActionByAction(anyLong(), anyLong(), anyString())).thenAnswer(inv -> actions.stream()
                .filter(a -> inv.getArgument(0).equals(a.getSessionId())
                        && inv.getArgument(1).equals(a.getMemberId())
                        && inv.getArgument(2).equals(a.getActionId()))
                .findFirst().orElse(null));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);
        when(routeMapper.updateActionResponse(anyLong(), anyString())).thenReturn(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.emptyList());
        when(shopDayProofService.hasArrived(anyLong(), anyLong(), anyLong())).thenReturn(true);

        wireController();
        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller, TOPIC);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.delegateLocks(activityMapper, registrationMapper);
    }

    private void wireController() {
        doReturn(MEMBER).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", templateService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", runtime);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerProgressionService", progressionService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "growthService", growthService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "xpBudgetService", xpBudgetService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "rewardDropService", rewardDropService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "smsCouponHistoryService", couponHistoryService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicCompletionAwardService", completionAwardService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "pointsAwardService", pointsAwardService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService", puzzlePlayService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "preferencePlayService", preferencePlayService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "clubLeadMapper", mock(ClubLeadMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playAttemptLogMapper", mock(PlayAttemptLogMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "memberCollectibleMapper", mock(MemberCollectibleMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "nodeMerchantOverrideService",
                new NodeMerchantOverrideServiceImpl(mock(ICmsRegistrationMerchantService.class)));
        when(progressService.insertPlayerNodeProgress(any(PlayerNodeProgress.class))).thenReturn(1);
        when(progressService.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.emptyList());
        when(progressionService.onNodeDone(anyLong())).thenReturn(new ArrayList<>());
        when(completionAwardService.grantOnce(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new TopicCompletionAwardVO());
        when(memberMapper.lockMemberById(anyLong())).thenReturn(MEMBER);
        when(puzzleAttemptMapper.insertPlayAttemptLog(any())).thenReturn(1);
    }

    /** 开局:线性主题已经启用旅程规则,state() 建局并冻结答案 / 数值规则 / 剧情。 */
    private void startRun() {
        runtime.state(MEMBER, TOPIC, ACTIVITY);
        assertNotNull(stored[0], "开局必须建局");
    }

    // ==================== 1. 模板答案按开局快照判定 ====================

    @SuppressWarnings("unchecked")
    @Test
    void answerSubmitUsesTemplateAnswerFrozenAtRunStart() throws Exception {
        startRun();
        JsonNode frozen = NodeJourneySnapshot.answer(stored[0].getConfigSnapshotJson(), NODE);
        assertNotNull(frozen, "开局建局必须冻结 nodeAnswers");
        assertEquals(1, frozen.path("validationMethod").asInt());
        assertEquals("旧答案", frozen.path("questionAnswer").asText());

        // 作者重新保存模板答案(同一节点,新答案)
        template.setQuestionAnswer("新答案");

        AjaxResult live = BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "新答案", null, null, null);
        assertEquals(500, live.get("code"), "新答案不在开局快照里,同一局不得按新配置判定");
        AjaxResult frozenOk = BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "旧答案", null, null, null);
        assertEquals(200, frozenOk.get("code"), "按开局冻结的旧答案必须判对");
        Map<String, Object> data = (Map<String, Object>) frozenOk.get("data");
        assertEquals(Boolean.TRUE, data.get("firstTime"));
    }

    /** 兼容口径:本次改动前建的老局快照没有 nodeAnswers 键,仍回退实时模板(老局行为不变)。 */
    @Test
    void oldRunSnapshotWithoutNodeAnswersFallsBackToLiveTemplate() throws Exception {
        startRun();
        ObjectNode snapshot = (ObjectNode) JSON.readTree(stored[0].getConfigSnapshotJson());
        snapshot.remove(NodeJourneySnapshot.NODE_ANSWERS_KEY);
        stored[0].setConfigSnapshotJson(JSON.writeValueAsString(snapshot));
        template.setQuestionAnswer("修订答案");

        AjaxResult result = BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "修订答案", null, null, null);

        assertEquals(200, result.get("code"), "老局快照没有冻结键时必须回退实时模板");
    }

    /** D1 反向口径:快照有 nodeAnswers 键(冻结后开的局)即使缺本节点也不得回退实时模板。 */
    @Test
    void frozenRunMissingNodeAnswerNeverFallsBackToLiveTemplate() throws Exception {
        startRun();
        ObjectNode snapshot = (ObjectNode) JSON.readTree(stored[0].getConfigSnapshotJson());
        snapshot.putObject(NodeJourneySnapshot.NODE_ANSWERS_KEY);
        stored[0].setConfigSnapshotJson(JSON.writeValueAsString(snapshot));
        template.setQuestionAnswer("修订答案");

        AjaxResult result = BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "修订答案", null, null, null);

        assertEquals(500, result.get("code"), "有答案冻结的局缺节点答案时必须拒绝,不得按当前模板判定");
    }

    /** 完成方式闸(反作弊:答题节点不得用「到达」绕过)按开局快照判定。 */
    @Test
    void arriveGateUsesValidationMethodFrozenAtRunStart() {
        startRun();

        // 作者把节点从答题(1)改成 GPS 到点(5);节点没有坐标
        template.setValidationMethod(5);

        AjaxResult result = BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null);

        assertEquals(500, result.get("code"));
        assertEquals("该站需完成对应任务后才能打卡", result.get("msg"),
                "老局仍按开局冻结的答题闸拒绝;若读实时会给出坐标类错误而不是这道闸");
    }

    /** 谜题揭示内容(reveal)按开局快照判定。 */
    @SuppressWarnings("unchecked")
    @Test
    void puzzleRevealUsesAnswerFrozenAtRunStart() {
        template.setValidationMethod(3);
        template.setCorrectAnswer("A");
        template.setAnswerReveal("旧揭示");
        startRun();

        template.setAnswerReveal("新揭示");

        AjaxResult result = BasicPlayCommandFixtures.revealPuzzle(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null);

        assertEquals(200, result.get("code"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("旧揭示", data.get("answerReveal"), "揭示内容按开局快照");
    }

    /** 偏好题组配置(preferenceJson)按开局快照判定。 */
    @Test
    void preferenceLoadUsesPreferenceJsonFrozenAtRunStart() {
        template.setValidationMethod(6);
        template.setPreferenceJson("{\"schemaVersion\":1,\"stub\":\"old\"}");
        startRun();

        template.setPreferenceJson("{\"schemaVersion\":1,\"stub\":\"new\"}");

        controller.preference(NODE, String.valueOf(ACTIVITY), null);

        ArgumentCaptor<CmsMemberTemplate> captor = ArgumentCaptor.forClass(CmsMemberTemplate.class);
        org.mockito.Mockito.verify(preferencePlayService).load(eq(MEMBER), eq(TOPIC), eq(NODE), captor.capture());
        assertEquals("{\"schemaVersion\":1,\"stub\":\"old\"}", captor.getValue().getPreferenceJson(),
                "偏好题组配置按开局快照");
    }

    // ==================== 2. 检定难度按开局快照判定 ====================

    @Test
    void checkRollUsesDifficultyFrozenAtRunStart() {
        startRun();

        // 作者改难度:模板 check 档位 easy→hard,主题数值规则 easy dc 8→18
        template.setAdvancedConfigJson(checkConfig("lantern", "hard"));
        topic.setJourneyRulesJson(rulesJson(18, 5));

        PlayCheckReceipt receipt = runtime.roll(MEMBER, TOPIC, NODE, "lantern");

        assertEquals(8, receipt.getDc(), "DC 按开局冻结的数值规则");
        assertEquals("easy", receipt.getTier(), "档位按开局冻结的模板 check");
        assertEquals(Boolean.TRUE, receipt.getSuccess(),
                "固定 d20=12:对旧 easy DC 8 成功;若读实时 hard DC 18 会失败");
    }

    // ==================== 3. 结局条件按开局快照判定 ====================

    @Test
    void endingUsesStoryConditionsFrozenAtRunStart() throws Exception {
        startRun();

        seedClueA(stored[0]);
        stored[0].setStatus(TopicRouteSession.STATUS_COMPLETED);
        // 作者重新保存剧情:旧的 clue.a==1 结局被删;实时读会取兜底 FB
        topic.setJourneyStoryJson(replacementStoryJson());

        PlayJourneyStoryServiceImpl storyService = new PlayJourneyStoryServiceImpl(topicMapper,
                mock(CmsTopicChapterMapper.class), nodeMapper, routeMapper, mock(ThemeStateKeyService.class),
                mock(NpcProfileService.class));
        Map<String, Object> view = storyService.ending(MEMBER, TOPIC);

        assertEquals("OLD", view.get("code"), "结局条件按开局冻结的剧情配置");
    }

    // ==================== 夹具 ====================

    /** 只有固定的 d20=12 才让「旧 easy dc=8 成功 / 新 hard dc=18 失败」可区分。 */
    private static String rulesJson(int easyDc, int easyDamage) {
        return "{\"schemaVersion\":1,\"checkEnabled\":true,\"tiers\":{"
                + "\"easy\":{\"dc\":" + easyDc + ",\"damage\":" + easyDamage + "},"
                + "\"medium\":{\"dc\":" + (easyDc + 1) + ",\"damage\":2},"
                + "\"hard\":{\"dc\":" + (easyDc + 2) + ",\"damage\":3}}}";
    }

    private static String checkConfig(String checkId, String tier) {
        return "{\"check\":{\"enabled\":true,\"checkId\":\"" + checkId + "\",\"skill\":\"观察\","
                + "\"tier\":\"" + tier + "\",\"successText\":\"看得仔细\",\"failText\":\"看漏了\"}}";
    }

    /** 开局冻结的剧情:非兜底结局按 clue.a==1 命中 code=OLD。 */
    private static String storyJson(String fallbackCode) {
        return "{\"schemaVersion\":1,\"ending\":{\"endings\":["
                + "{\"code\":\"OLD\",\"when\":[{\"var\":\"clue.a\",\"op\":\"EQ\",\"value\":1}],\"title\":\"旧结局\"},"
                + "{\"code\":\"" + fallbackCode + "\",\"fallback\":true,\"title\":\"兜底\"}]}}";
    }

    /** 作者重存后的剧情:旧 OLD 条件被删,实时读会取兜底 FB(冻结不认它)。 */
    private static String replacementStoryJson() {
        return "{\"schemaVersion\":1,\"ending\":{\"endings\":["
                + "{\"code\":\"LIVE\",\"when\":[{\"var\":\"clue.b\",\"op\":\"EQ\",\"value\":1}],\"title\":\"新结局\"},"
                + "{\"code\":\"FB\",\"fallback\":true,\"title\":\"兜底\"}]}}";
    }

    /** 把本局状态里的 clue.a 置 1(结局条件用),不经过任何写接口。 */
    private static void seedClueA(TopicRouteSession session) throws Exception {
        ObjectNode state = (ObjectNode) JSON.readTree(session.getStateJson());
        state.putObject("variables").put("clue.a", 1);
        session.setStateJson(JSON.writeValueAsString(state));
    }
}
