package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.PlayerTopicTag;
import com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.TopicOnsiteMetricsMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPlayerTopicTagService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.impl.NodeMerchantOverrideServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.PreferencePlayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.service.impl.NpcProfileService;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * B5 体验层的两条出参契约（都是「服务端发什么名字 / 发不发」，不是绑定串）。
 *
 * <p>1) 答案类机密：{@code TemplateSecrets} 把 {@code answerReveal} 与
 * {@code questionAnswer/correctAnswer/hint1/hint2} 并列为「一律剥离」。B5 只批了题面与选项，
 * 没批揭示文案 —— 进店后下发 {@code answerReveal} 等于答题前就能抓包读到答案揭示。
 *
 * <p>2) 任务卡字段名：wxml 读的是归一化名 {@code node.rule}/{@code node.photoDesc}，
 * 而 checkin 回包若只发原始名 {@code ruleInstructions}/{@code photoRequireDesc}，
 * 前端两处裸 {@code Object.assign} 合并后归一化键仍是进店前那个空串 ——
 * 没配游戏的店整张任务卡就是这两样，等于弹一张空卡。归一化只能有一处，放在写侧。
 */
class ShopDayExperienceLayerContractTest
{
    private static final Long PLAYER = 61L;
    private static final Long REGISTRATION = 62L;
    private static final Long ACTIVITY = 63L;
    private static final Long TOPIC = 64L;
    private static final Long CHAPTER = 65L;
    private static final Long NODE = 66L;
    private static final Long TEMPLATE = 67L;
    private static final String CODE = "STORE-CODE-B5";

    private ApiPlayProgressController controller;
    private CmsTopic topic;
    private CmsTopicNode node;
    private CmsMemberTemplate template;
    private IPlayerNodeProgressService progressService;
    private IShopDayProofService proofService;
    private IPlayerTopicTagService tagService;

    @BeforeEach
    void setUp()
    {
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);
        proofService = mock(IShopDayProofService.class);
        tagService = mock(IPlayerTopicTagService.class);
        progressService = mock(IPlayerNodeProgressService.class);

        controller = spy(new ApiPlayProgressController());
        doReturn(PLAYER).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        when(registrationMapper.markFirstSuccessfulScan(eq(REGISTRATION), eq(PLAYER),
                eq(2L), eq(ACTIVITY), any(Date.class))).thenReturn(1);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationMapper", registrationMapper);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", templateService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "shopDayProofService", proofService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerTopicTagService", tagService);
        PreferencePlayService preferencePlayService = new PreferencePlayService();
        ReflectionTestUtils.setField(preferencePlayService, "tagService", tagService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "preferencePlayService", preferencePlayService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "clubLeadMapper",
                mock(com.chengyinhub.business.mapper.ClubLeadMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "perkLedgerService", mock(IPerkLedgerService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        var fixtureHintSettings = mock(ICmsPointsSettingService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "umsMemberPointsDetailService",
                mock(IUmsMemberPointsDetailService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicOnsiteMetricsMapper", mock(TopicOnsiteMetricsMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicTemplateMetaMapper", mock(CmsTopicTemplateMetaMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playAttemptLogMapper", mock(PlayAttemptLogMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService",
                mock(com.chengyinhub.business.service.IPuzzlePlayService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "memberCollectibleMapper", mock(MemberCollectibleMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "nodeMerchantOverrideService",
                new NodeMerchantOverrideServiceImpl(mock(ICmsRegistrationMerchantService.class)));
        // 2026-09-08 自由探索卡片详情:节点上新挂了「本店权益」与「门店分身摘要」两段。
        // 本用例关心的是探店日节点**不泄题**,权益/分身取空即可 —— 但依赖必须装上,
        // 否则 FREE_COLLECT 分支一进来就 NPE,七条断言全变成「因为没接线而红」,
        // 看着像泄题门禁挂了,其实是装配漏了。
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "merchantChapterOfferMapper",
                mock(MerchantChapterOfferMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "coopPerkMapper", mock(CoopPerkMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "npcProfileService", mock(NpcProfileService.class));

        node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setChapterId(CHAPTER);
        node.setLongitude("121.47");
        node.setLatitude("31.23");
        node.setCheckinCode(CODE);
        node.setTemplateId(TEMPLATE);
        node.setSortId(1L);
        node.setStatus(1L);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        template = new CmsMemberTemplate();
        template.setId(TEMPLATE);
        template.setValidationMethod(3);
        template.setQuestionName("这家店的招牌是什么？");
        template.setQuestionA("A");
        template.setQuestionB("B");
        template.setCorrectAnswer("A");
        template.setAnswerReveal("答案是 A —— 招牌在门口第二块木牌上");
        template.setFeedbackText("再看看门口那块木牌");
        template.setMedalName("旧模板勋章");
        template.setMedalImg("/legacy-medal.png");
        template.setMedalStyle("glow");
        template.setCouponId(88L);
        template.setRuleInstructions("进店后找店员报暗号");
        template.setPhotoRequireDesc("拍一张能看见招牌的照片");
        when(templateService.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(template);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        // 探店日票(EXPLORE_PASS)按已定规则缺结束时间暂禁新使用；本类不验到期，夹具给未到期的结束时间。
        activity.setEndDate(new java.util.Date(System.currentTimeMillis() + 86_400_000L));
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);

        CmsRegistration registration = new CmsRegistration();
        registration.setId(REGISTRATION);
        registration.setMemberId(PLAYER);
        registration.setOwnerType(2L);
        registration.setOwnerId(ACTIVITY);
        registration.setRegistrationStatus(2);
        registration.setPaymentStatus(2);
        registration.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(registration));

        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setName("探店日体验层契约");
        topic.setProductType(ProductType.FREE_EXPLORE);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        PlayerNodeProgress arrived = new PlayerNodeProgress();
        arrived.setMemberId(PLAYER);
        arrived.setActivityId(ACTIVITY);
        arrived.setTopicId(TOPIC);
        arrived.setNodeId(NODE);
        arrived.setStatus(0);
        arrived.setCheckinTime(new Date());
        when(proofService.markArrived(PLAYER, ACTIVITY, node)).thenReturn(arrived);
        when(proofService.hasArrived(PLAYER, ACTIVITY, NODE)).thenReturn(true);
        when(progressService.selectPlayerNodeProgressList(any()))
                .thenReturn(Collections.singletonList(arrived));
        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller, TOPIC);
        com.chengyinhub.web.controller.api.NodeHintUnlockTestFixtures.pricing(controller, fixtureHintSettings);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstNode(AjaxResult result)
    {
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        return ((List<Map<String, Object>>) data.get("nodes")).get(0);
    }

    /** ③ 已进店的答题节点：题面/选项放行，答案揭示与反馈话术一律剥。 */
    @Test
    void pureOnlineStoryShowsGameplayWithoutInventingStoreArrival() {
        node.setLongitude(null);
        node.setLatitude(null);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        Map<String, Object> m = firstNode(controller.nodes(ACTIVITY, null));
        assertEquals(3, m.get("validationMethod"));
        assertTrue(m.containsKey("question"));
        assertFalse(m.containsKey("answerReveal"));
        assertFalse(m.containsKey("feedbackText"));
        assertFalse(m.containsKey("arrived"));
    }

    @Test
    void shopDayNodesNeverShipAnswerRevealEvenAfterArrival()
    {
        Map<String, Object> m = firstNode(controller.nodes(ACTIVITY, null));

        assertEquals(Boolean.TRUE, m.get("arrived"), "前提:已进店(否则整组剥离会让本断言恒真)");
        assertEquals(Boolean.TRUE, m.get("hasGame"), "前提:该节点确实配了游戏");
        assertTrue(m.containsKey("question"), "题面是 B5 批准的,不得连坐剥掉");
        assertTrue(m.containsKey("options"), "选项是 B5 批准的,不得连坐剥掉");
        assertFalse(m.containsKey("answerReveal"), "答案揭示属 TemplateSecrets 机密,③ 一律不下发");
        assertFalse(m.containsKey("feedbackText"), "反馈话术随答案揭示一并剥(答错时由 /answer 单发)");
        assertFalse(m.containsKey("medalName"), "探店日节点不得承诺经典勋章");
        assertFalse(m.containsKey("medalImg"));
        assertFalse(m.containsKey("medalStyle"));
        assertFalse(m.containsKey("couponId"), "探店日节点不得承诺经典发券");
        assertFalse(m.containsKey("coupon"));
    }

    @Test
    void arrivedSensorNodeExposesAppContractAndCountsAsGame()
    {
        template.setValidationMethod(7);
        template.setSensorType("steps");
        template.setSensorConfig("{\"targetSteps\":120}");

        Map<String, Object> m = firstNode(controller.nodes(ACTIVITY, null));

        assertEquals(Boolean.TRUE, m.get("arrived"));
        assertEquals(Boolean.TRUE, m.get("hasGame"));
        assertEquals(7, m.get("validationMethod"));
        assertEquals("steps", m.get("sensorType"));
        assertEquals("{\"targetSteps\":120}", m.get("sensorConfig"));
    }

    @Test
    void arrivedFilterShotNodeExposesAppContractAndCountsAsGame()
    {
        template.setValidationMethod(2);
        template.setSensorType("filter_shot");
        template.setSensorConfig("{\"filterStyle\":\"pet_pov\"}");

        Map<String, Object> m = firstNode(controller.nodes(ACTIVITY, null));

        assertEquals(Boolean.TRUE, m.get("arrived"));
        assertEquals(Boolean.TRUE, m.get("hasGame"));
        assertEquals(2, m.get("validationMethod"));
        assertEquals("filter_shot", m.get("sensorType"));
        assertEquals("{\"filterStyle\":\"pet_pov\"}", m.get("sensorConfig"));
    }

    @Test
    void invalidPersistedFilterConfigIsNotExposedAsPlayableContract()
    {
        template.setValidationMethod(2);
        template.setSensorType("filter_shot");
        template.setSensorConfig("{\"filterStyle\":\"unknown\"}");

        Map<String, Object> m = firstNode(controller.nodes(ACTIVITY, null));

        assertEquals(Boolean.FALSE, m.get("hasGame"));
        assertFalse(m.containsKey("validationMethod"));
        assertFalse(m.containsKey("sensorType"));
        assertFalse(m.containsKey("sensorConfig"));
    }

    @Test
    void sensorContractIsHiddenUntilArrival()
    {
        template.setValidationMethod(7);
        template.setSensorType("steps");
        template.setSensorConfig("{\"targetSteps\":120}");
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());

        Map<String, Object> m = firstNode(controller.nodes(ACTIVITY, null));

        assertEquals(Boolean.FALSE, m.get("arrived"));
        assertEquals(Boolean.TRUE, m.get("hasGame"));
        assertFalse(m.containsKey("validationMethod"));
        assertFalse(m.containsKey("sensorType"));
        assertFalse(m.containsKey("sensorConfig"));
    }

    /** ③ 未进店时连题面与选项也不下发，避免远程转发店内码前先抓题。 */
    @Test
    void shopDayNodesDoNotShipQuestionBeforeArrival()
    {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());

        Map<String, Object> m = firstNode(controller.nodes(ACTIVITY, null));

        assertEquals(Boolean.FALSE, m.get("arrived"));
        assertFalse(m.containsKey("question"));
        assertFalse(m.containsKey("options"));
        assertFalse(m.containsKey("answerReveal"));
        assertFalse(m.containsKey("feedbackText"));
    }

    /** ①经典定向启用服务端谜题事实：提示/揭示不预下发，但既有奖励展示不受影响。 */
    @Test
    void classicOrienteeringPuzzleKeepsSecretsServerSide()
    {
        topic.setProductType(ProductType.ORIENTEERING);

        Map<String, Object> m = firstNode(controller.nodes(ACTIVITY, null));

        assertEquals(Boolean.TRUE, m.get("puzzleScoring"));
        assertFalse(m.containsKey("answerReveal"), "抓包不能绕过揭示事实再拿 100 解谜分");
        assertFalse(m.containsKey("hint1"), "提示正文只允许在 /puzzle/hint 记录事实后返回");
        assertTrue(m.containsKey("feedbackText"));
        assertEquals("旧模板勋章", m.get("medalName"), "经典定向既有奖励展示不受谜题计分影响");
        assertEquals(88L, m.get("couponId"));
    }

    @Test
    void classicArrivalCannotBypassPreferenceTask()
    {
        topic.setProductType(ProductType.ORIENTEERING);
        template.setValidationMethod(6);

        AjaxResult result = BasicPlayCommandFixtures.arrive(controller, ACTIVITY.toString(), NODE.toString(), null, null, null);

        assertEquals(500, result.get("code"));
        assertEquals("该站需完成对应任务后才能打卡", result.get("msg"));
    }

    /** 扫码进店回包必须发归一化名，任务卡与三步条读的就是这两个键。 */
    @SuppressWarnings("unchecked")
    @Test
    void shopDayCheckinShipsNormalizedTaskCardFieldNames()
    {
        AjaxResult result = BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, null, String.valueOf(NODE));

        assertEquals(200, result.get("code"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("进店后找店员报暗号", data.get("rule"));
        assertEquals("拍一张能看见招牌的照片", data.get("photoDesc"));
        // 原始名不再出现在这条线上：一个字段两个名字就是两个真源，下一个人必然读错那一个
        assertFalse(data.containsKey("ruleInstructions"));
        assertFalse(data.containsKey("photoRequireDesc"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void shopDayCheckinShipsFilterShotContractAfterArrival()
    {
        template.setValidationMethod(2);
        template.setSensorType("filter_shot");
        template.setSensorConfig("{\"filterStyle\":\"night_vision\"}");

        AjaxResult result = BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, null, String.valueOf(NODE));

        assertEquals(200, result.get("code"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(Boolean.TRUE, data.get("hasGame"));
        assertEquals(2, data.get("validationMethod"));
        assertEquals("filter_shot", data.get("sensorType"));
        assertEquals("{\"filterStyle\":\"night_vision\"}", data.get("sensorConfig"));
    }

    /** 偏好题只公开“本站有游戏”的入口，不把评分分支提前塞进 /nodes；题组另走进店后的 GET。 */
    @SuppressWarnings("unchecked")
    @Test
    void preferenceTemplateIsDiscoverableWithoutLeakingItsScoringContract()
    {
        template.setValidationMethod(6);
        template.setQuestionName(null);
        template.setPreferenceJson("{\"privateScoring\":true}");

        Map<String, Object> listed = firstNode(controller.nodes(ACTIVITY, null));
        assertEquals(Boolean.TRUE, listed.get("hasGame"));
        assertEquals(6, listed.get("validationMethod"));
        assertFalse(listed.containsKey("preferenceJson"));

        AjaxResult checkin = BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, null, String.valueOf(NODE));
        Map<String, Object> data = (Map<String, Object>) checkin.get("data");
        assertEquals(Boolean.TRUE, data.get("hasGame"));
        assertEquals(6, data.get("validationMethod"));
        assertFalse(data.containsKey("preferenceJson"));
    }

    /** 真 GET 入口必须只消费模板声明且已确认的标签，并且不把评分结果分支提前发给客户端。 */
    @SuppressWarnings("unchecked")
    @Test
    void preferenceGetConsumesConfirmedTagsAtTheRealPlayerEntry()
    {
        template.setValidationMethod(6);
        template.setPreferenceJson("{\"steps\":[{\"key\":\"q\",\"type\":\"single\",\"title\":\"选\","
                + "\"options\":[{\"key\":\"A\",\"text\":\"紧凑\",\"scores\":{\"compact\":2}},"
                + "{\"key\":\"B\",\"text\":\"开放\",\"scores\":{\"compact\":1}}]}],"
                + "\"dimensions\":[\"compact\"],\"results\":{\"compact\":{\"title\":\"减负\","
                + "\"body\":\"因为{{choices}}\",\"nextStep\":\"行动\",\"nextStepDays\":7}},"
                + "\"tagConsumes\":[\"space_constraints\"]}");
        PlayerTopicTag confirmed = new PlayerTopicTag();
        confirmed.setId(71L);
        confirmed.setTagCode("space_constraints");
        confirmed.setTagValue("compact");
        confirmed.setStatus(1);
        when(tagService.confirmedForConsumer(org.mockito.ArgumentMatchers.eq(PLAYER),
                org.mockito.ArgumentMatchers.eq(TOPIC), org.mockito.ArgumentMatchers.anySet()))
                .thenReturn(Collections.singletonList(confirmed));

        AjaxResult response = controller.preference(NODE, String.valueOf(ACTIVITY), null);

        assertEquals(200, response.get("code"));
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertEquals(1, ((List<?>) data.get("inheritedTags")).size());
        assertTrue(data.containsKey("steps"));
        assertFalse(data.containsKey("results"));
        assertFalse(data.containsKey("tagOutput"));
    }
}
