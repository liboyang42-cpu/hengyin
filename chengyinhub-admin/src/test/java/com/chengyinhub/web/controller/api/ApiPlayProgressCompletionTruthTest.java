package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.IRewardDropService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.ITopicCompletionAwardService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 2-4 公共游玩入口契约:探店日的 GPS/照片/progress 不能猜出通关,经典模式保持原行为。 */
class ApiPlayProgressCompletionTruthTest
{
    @Test
    void freeLinearThoughtSessionCannotOverridePaidPassCompletion() {
        topic.setProductType(ProductType.FREE_EXPLORE);
        CmsRegistration pass = new CmsRegistration();
        pass.setId(REGISTRATION);
        pass.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        when(perkLedgerService.isExploreCompleted(REGISTRATION, TOPIC)).thenReturn(true);
        assertEquals(true, ReflectionTestUtils.invokeMethod(controller, "isCompletedCompletionFact",
                topic, 0, pass, TOPIC, 2, 1));
        when(perkLedgerService.isExploreCompleted(REGISTRATION, TOPIC)).thenReturn(false);
        assertEquals(false, ReflectionTestUtils.invokeMethod(controller, "isCompletedCompletionFact",
                topic, 1, pass, TOPIC, 2, 2));
    }

    private static final Long PLAYER = 41L;
    private static final Long REGISTRATION = 42L;
    private static final Long ACTIVITY = 43L;
    private static final Long TOPIC = 44L;
    private static final Long CHAPTER = 45L;
    private static final Long NODE = 46L;
    private static final String CODE = "STORE-CODE";

    private ICmsTopicService topicService;
    private ICmsMemberTemplateService templateService;
    private IPerkLedgerService perkLedgerService;
    private IShopDayProofService proofService;
    private IPlayerNodeProgressService progressService;
    private ITopicCompletionAwardService completionAwardService;
    private IPointsAwardService pointsAwardService;
    private IPlayerProgressionService progressionService;
    private ApiPlayProgressController controller;
    private CmsTopic topic;
    private CmsTopicNode node;
    private ICmsTopicNodeService nodeService;
    private ICmsRegistrationService registrationService;
    private com.chengyinhub.business.mapper.CmsRegistrationMapper registrationMapper;

    @BeforeEach
    void setUp()
    {
        nodeService = mock(ICmsTopicNodeService.class);
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        registrationService = mock(ICmsRegistrationService.class);
        registrationMapper = mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class);
        when(registrationMapper.markFirstSuccessfulScan(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(1);
        topicService = mock(ICmsTopicService.class);
        perkLedgerService = mock(IPerkLedgerService.class);
        proofService = mock(IShopDayProofService.class);
        progressService = mock(IPlayerNodeProgressService.class);
        completionAwardService = mock(ITopicCompletionAwardService.class);
        pointsAwardService = mock(IPointsAwardService.class);
        progressionService = mock(IPlayerProgressionService.class);

        controller = spy(new ApiPlayProgressController());
        doReturn(PLAYER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", registrationService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "perkLedgerService", perkLedgerService);
        ReflectionTestUtils.setField(controller, "shopDayProofService", proofService);
        ReflectionTestUtils.setField(controller, "playerNodeProgressService", progressService);
        ReflectionTestUtils.setField(controller, "topicCompletionAwardService", completionAwardService);
        ReflectionTestUtils.setField(controller, "pointsAwardService", pointsAwardService);
        ReflectionTestUtils.setField(controller, "playerProgressionService", progressionService);
        ReflectionTestUtils.setField(controller, "growthService", mock(IGrowthService.class));
        ReflectionTestUtils.setField(controller, "xpBudgetService", mock(IXpBudgetService.class));
        ReflectionTestUtils.setField(controller, "rewardDropService", mock(IRewardDropService.class));
        ReflectionTestUtils.setField(controller, "smsCouponHistoryService", mock(ISmsCouponHistoryService.class));
        // 上传事实证明由 FileOssUploadUtils 专属测试覆盖;本类只验到店凭证/通关口径合同。
        ReflectionTestUtils.setField(controller, "photoUploadFactVerifier",
                (ApiPlayProgressController.PlayPhotoUploadFactVerifier) (picUrl, memberId) -> { });
        ReflectionTestUtils.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        templateService = mock(ICmsMemberTemplateService.class);
        ReflectionTestUtils.setField(controller, "cmsMemberTemplateService", templateService);
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        ReflectionTestUtils.setField(controller, "clubLeadMapper",
                mock(com.chengyinhub.business.mapper.ClubLeadMapper.class));
        ReflectionTestUtils.setField(controller, "playAttemptLogMapper",
                mock(com.chengyinhub.business.mapper.PlayAttemptLogMapper.class));
        ReflectionTestUtils.setField(controller, "memberCollectibleMapper",
                mock(com.chengyinhub.business.mapper.MemberCollectibleMapper.class));
        ReflectionTestUtils.setField(controller, "nodeMerchantOverrideService",
                new com.chengyinhub.business.service.impl.NodeMerchantOverrideServiceImpl(
                        mock(com.chengyinhub.business.service.ICmsRegistrationMerchantService.class)));

        node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setChapterId(CHAPTER);
        node.setCheckinCode(CODE);
        node.setSortId(1L);
        node.setStatus(1L);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

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
        topic.setName("通关真源测试");
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        RegistrationEntitlement pending = new RegistrationEntitlement();
        pending.setRegistrationId(REGISTRATION);
        pending.setChapterId(CHAPTER);
        pending.setStatus(0);
        when(perkLedgerService.listEntitlements(REGISTRATION)).thenReturn(Collections.singletonList(pending));

        PlayerNodeProgress proof = new PlayerNodeProgress();
        proof.setMemberId(PLAYER);
        proof.setActivityId(ACTIVITY);
        proof.setTopicId(TOPIC);
        proof.setNodeId(NODE);
        // 夹具模拟「完成写入之后」的进度行:经典路径 insert 恒 status=1;done 计数只认 status==1。
        // mode2 的到店凭证语义由 storeVoucher 系列测试单独覆盖(status=0 不算完成)。
        proof.setStatus(1);
        proof.setCheckinTime(new Date());
        when(proofService.markArrived(PLAYER, ACTIVITY, node)).thenReturn(proof);
        when(proofService.submitPhoto(PLAYER, ACTIVITY, NODE, "photo.jpg")).thenAnswer(invocation -> {
            proof.setPicUrl("photo.jpg");
            return proof;
        });

        when(progressService.insertPlayerNodeProgress(any())).thenReturn(1);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(proof));
        when(progressionService.onNodeDone(PLAYER)).thenReturn(Collections.emptyList());
        when(completionAwardService.grantOnce(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new TopicCompletionAwardVO());
        CurrentPlayControllerFixtures.install(controller);
    }

    @SuppressWarnings("unchecked")
    @Test
    void shopDayCheckinAndPhotoOnlyRecordProofAndNeverCompleteTheTopic()
    {
        topic.setProductType(ProductType.FREE_EXPLORE);

        AjaxResult checkin = controller.checkin(String.valueOf(ACTIVITY), CODE, null);
        AjaxResult photo = controller.photo(String.valueOf(ACTIVITY), String.valueOf(NODE), "photo.jpg", null);

        assertEquals(200, checkin.get("code"));
        assertEquals(Boolean.FALSE, ((Map<String, Object>) checkin.get("data")).get("done"));
        assertEquals(200, photo.get("code"));
        assertEquals(Boolean.FALSE, ((Map<String, Object>) photo.get("data")).get("done"));
        verify(proofService).markArrived(PLAYER, ACTIVITY, node);
        verify(proofService).submitPhoto(PLAYER, ACTIVITY, NODE, "photo.jpg");
        verify(perkLedgerService, never()).isExploreCompleted(anyLong(), anyLong());
        verify(completionAwardService, never()).grantOnce(anyLong(), anyLong(), anyLong(), any());
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), any());
        verify(progressionService, never()).onNodeDone(anyLong());
    }

    /**
     * ★ C1「进店用玩家扫店内静态码,不用 GPS(不准)」:③ 的 /arrive 必须整条拒绝。
     *
     * <p>此前这里是正控(GPS 到点即写到店凭证)。改判的理由是进店事实一旦有第二条来源,
     * 就出现两个真源:GPS 那条用的是<b>客户端传的 lat/lng</b>(可伪造),而
     * {@code player_node_progress.checkin_time} 正是 G2 硬门 {@code queue_joined_at} 的取值来源
     * ——「手机说它在附近」会被当成「玩家在店门口扫了码」,把等待时长 P90 抬高数小时,
     * 全程零告警零资损。审计 5 S1 同向:漫游客户端就是这条 GPS 路径的现成调用方。
     *
     * <p>2026-08-20 收窄口径:C1 的立论对象是「店」——本条固定给节点<b>坐标</b>,
     * 代表真实门店点位;纯线上节点的例外见下一条,两边互为负控。
     */
    @Test
    void shopDayArriveIsRefusedSoGpsCanNeverManufactureAnInStoreFact()
    {
        topic.setProductType(ProductType.FREE_EXPLORE);
        node.setLongitude("121.4737");
        node.setLatitude("31.2304");
        when(perkLedgerService.isExploreCompleted(REGISTRATION, TOPIC)).thenReturn(false);

        AjaxResult result = controller.arrive(String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null);

        assertEquals(500, result.get("code"));
        assertEquals("请扫店内的码", result.get("msg"));
        // 不写 player_node_progress ⇒ queue_joined_at / in_store_load / 七段漏斗 A 都不被污染
        verify(proofService, never()).markArrived(anyLong(), anyLong(), any());
        verify(perkLedgerService, never()).isExploreCompleted(anyLong(), anyLong());
        verify(completionAwardService, never()).grantOnce(anyLong(), anyLong(), anyLong(), any());
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), any());
        verify(progressionService, never()).onNodeDone(anyLong());
    }

    /**
     * 商家节点即使暂缺坐标,也不许 /arrive:到店事实仍只有 /checkin 一条来源。
     * (没有这条,「纯线上例外」会被「商家忘填坐标」静默扩大成商家节点全放行。)
     */
    @Test
    void shopDayArriveIsStillRefusedForMerchantNodeWithoutCoordinates()
    {
        topic.setProductType(ProductType.FREE_EXPLORE);
        node.setMerchantMemberId(777L);

        AjaxResult result = controller.arrive(String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null);

        assertEquals(500, result.get("code"));
        assertEquals("请扫店内的码", result.get("msg"));
        verify(proofService, never()).markArrived(anyLong(), anyLong(), any());
    }

    /**
     * ★ 纯线上节点例外(2026-08-20 拍板「模板不能把通关绑死在商家扫码上」):
     * 无承接商家、无坐标的节点(《生活不掉线》G0—G6 游戏节)允许 /arrive 自记凭证。
     * 语义必须与 /checkin 的 ③ 分支逐字一致:只记 shop-day 凭证,done=false,
     * 不发分、不判通关 —— 完成经济仍由章节核销承担。
     */
    @SuppressWarnings("unchecked")
    @Test
    void shopDayArriveOnPureOnlineNodeRecordsProofOnlyAndNeverAwards()
    {
        topic.setProductType(ProductType.FREE_EXPLORE);
        // 默认夹具节点即纯线上形状:merchant_member_id=null、无坐标

        AjaxResult result = controller.arrive(String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null);

        assertEquals(200, result.get("code"));
        assertEquals(Boolean.FALSE, ((Map<String, Object>) result.get("data")).get("done"));
        verify(proofService).markArrived(PLAYER, ACTIVITY, node);
        verify(completionAwardService, never()).grantOnce(anyLong(), anyLong(), anyLong(), any());
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), any());
        verify(progressionService, never()).onNodeDone(anyLong());
    }

    /**
     * 负控:上面那条拒绝必须<b>只</b>针对 ③。①② 的 /arrive(到点即完成 / GPS 到达)一字不改 ——
     * 否则「删了 ③ 分支」就变成「删了整个端点」,而 ①② 的到点即完成节点会失去唯一完成路径。
     */
    @Test
    void zeroTemplateIsAPlainStopAndStillRequiresValidLocation()
    {
        topic.setProductType(ProductType.ORIENTEERING);
        node.setTemplateId(0L);
        node.setLongitude("121.5");
        node.setLatitude("31.2");

        AjaxResult missing = controller.arrive(String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null);
        assertEquals("请开启定位", missing.get("msg"));
        AjaxResult outside = controller.arrive(String.valueOf(ACTIVITY), String.valueOf(NODE), "120", "30", null);
        assertEquals("还没到这一站附近", outside.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());

        AjaxResult arrived = controller.arrive(String.valueOf(ACTIVITY), String.valueOf(NODE), "121.5", "31.2", null);
        assertEquals(200, arrived.get("code"));
        verify(templateService, never()).selectCmsMemberTemplateById(0L);

        node.setTemplateId(999L);
        AjaxResult dangling = controller.arrive(String.valueOf(ACTIVITY), String.valueOf(NODE), "121.5", "31.2", null);
        assertEquals("该站配置异常，暂时无法打卡", dangling.get("msg"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void classicArriveStillCompletesThroughTheOriginalProgressPath()
    {
        topic.setProductType(ProductType.ORIENTEERING);

        AjaxResult result = controller.arrive(String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null);

        assertEquals(200, result.get("code"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) result.get("data")).get("completed"));
        verify(pointsAwardService).award(PLAYER, 12L, NODE, "节点打卡");
        verify(progressionService).onNodeDone(PLAYER);
    }

    @Test
    void lastOpeningGameDoesNotAwardTheThemeBeforeDeparture()
    {
        topic.setProductType(ProductType.ORIENTEERING);
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime =
                mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(controller, "topicRouteRuntimeService", runtime);
        when(runtime.canPlay(PLAYER, TOPIC, ACTIVITY, NODE, null)).thenReturn(true);
        com.chengyinhub.business.domain.route.TopicRouteState state =
                new com.chengyinhub.business.domain.route.TopicRouteState();
        state.setRouteMode("LINEAR"); state.setStatus("ACTIVE");
        state.setOpening(Collections.singletonMap("complete", false));
        when(runtime.state(PLAYER, TOPIC, ACTIVITY)).thenReturn(state);
        AjaxResult result = controller.arrive(String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null);
        assertEquals(200, result.get("code"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) result.get("data")).get("completed"));
        verify(completionAwardService, never()).grantOnce(anyLong(), anyLong(), anyLong(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void classicCheckinStillCompletesThroughTheOriginalProgressPath()
    {
        topic.setProductType(ProductType.ORIENTEERING);

        AjaxResult result = controller.checkin(String.valueOf(ACTIVITY), CODE, null);

        assertEquals(200, result.get("code"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) result.get("data")).get("completed"));
        verify(pointsAwardService).award(PLAYER, 12L, NODE, "节点打卡");
        verify(progressionService).onNodeDone(PLAYER);
        verify(completionAwardService).grantOnce(PLAYER, TOPIC, ACTIVITY, null);
        verify(perkLedgerService, never()).isExploreCompleted(anyLong(), anyLong());
    }

    // ---------- C-21 名次奖牌回传事实(2026-09-16) ----------

    /** 正控:夺到金牌 → 回执带 medalRank=1,前端据此弹「专属徽章」。 */
    @SuppressWarnings("unchecked")
    @Test
    void classicCheckinResponseCarriesMedalRankWhenClaimSucceeds()
    {
        topic.setProductType(ProductType.ORIENTEERING);
        com.chengyinhub.business.mapper.NodeMedalClaimMapper medalMapper =
                mock(com.chengyinhub.business.mapper.NodeMedalClaimMapper.class);
        when(medalMapper.insertClaim(anyLong(), anyInt(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(1);
        ReflectionTestUtils.setField(controller, "nodeMedalClaimMapper", medalMapper);

        AjaxResult result = controller.checkin(String.valueOf(ACTIVITY), CODE, null);

        assertEquals(200, result.get("code"));
        assertEquals(Integer.valueOf(1), ((Map<String, Object>) result.get("data")).get("medalRank"));
    }

    /** 负控:三档占满(insert 全 0)→ medalRank 必须是 null —— 第 4 名起不得再弹「专属徽章」。 */
    @SuppressWarnings("unchecked")
    @Test
    void classicCheckinResponseMedalRankIsNullWhenPodiumFull()
    {
        topic.setProductType(ProductType.ORIENTEERING);
        com.chengyinhub.business.mapper.NodeMedalClaimMapper medalMapper =
                mock(com.chengyinhub.business.mapper.NodeMedalClaimMapper.class);
        when(medalMapper.insertClaim(anyLong(), anyInt(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(0);
        ReflectionTestUtils.setField(controller, "nodeMedalClaimMapper", medalMapper);

        AjaxResult result = controller.checkin(String.valueOf(ACTIVITY), CODE, null);

        assertEquals(200, result.get("code"));
        org.junit.jupiter.api.Assertions.assertNull(
                ((Map<String, Object>) result.get("data")).get("medalRank"));
    }

    // ---------- C-22 答错与业务拒绝必须可区分(2026-09-16) ----------

    /** 正控:真答错 → 回执带 data.answerWrong 标记,前端才允许计一次错。 */
    @SuppressWarnings("unchecked")
    @Test
    void wrongAnswerResponseCarriesTheAnswerWrongMarker()
    {
        topic.setProductType(ProductType.ORIENTEERING);
        node.setTemplateId(900L);
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setValidationMethod(1);
        tpl.setQuestionAnswer("正确答案");
        tpl.setFeedbackText("再看一眼门口的牌子");
        when(templateService.selectCmsMemberTemplateById(900L)).thenReturn(tpl);

        AjaxResult result = controller.answer(String.valueOf(ACTIVITY), String.valueOf(NODE), "乱写一个", null);

        assertEquals(500, result.get("code"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) result.get("data")).get("answerWrong"),
                "答错必须带标记,否则前端的错次/揭示无法与前端的业务拒绝区分");
    }

    @Test
    void freeStoryAnswerAdvancesThoughtStateUsingTheServerProgressReceipt() {
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":false,\"thoughts\":["
                + "{\"key\":\"echo\",\"name\":\"回声\",\"need\":1}]}");
        node.setTemplateId(900L);
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setValidationMethod(1); tpl.setQuestionAnswer("答案");
        when(templateService.selectCmsMemberTemplateById(900L)).thenReturn(tpl);
        when(progressService.insertPlayerNodeProgress(any())).thenAnswer(call -> {
            ((PlayerNodeProgress) call.getArgument(0)).setId(901L);
            return 1;
        });
        com.chengyinhub.web.service.TopicRouteCompletionCoordinator coordinator =
                mock(com.chengyinhub.web.service.TopicRouteCompletionCoordinator.class);
        ReflectionTestUtils.setField(controller,"topicRouteCompletionCoordinator",coordinator);
        com.chengyinhub.business.domain.route.TopicRouteState state =
                new com.chengyinhub.business.domain.route.TopicRouteState();
        state.setRouteMode("LINEAR"); state.setStatus("ACTIVE");
        when(coordinator.advance(eq(PLAYER),eq(TOPIC),eq(ACTIVITY),eq(NODE),eq("answer-1"),eq(2),
                eq("player-node-progress:901"),isNull(),isNull())).thenReturn(state);
        assertEquals(200, controller.answer(String.valueOf(ACTIVITY),String.valueOf(NODE),"答案",null,
                "answer-1",2).get("code"));
        verify(coordinator).advance(PLAYER,TOPIC,ACTIVITY,NODE,"answer-1",2,"player-node-progress:901",null,null);
        verify(proofService,never()).hasArrived(anyLong(),anyLong(),anyLong());
    }

    /** 负控:业务拒绝(下架站)不许带 answerWrong —— 否则连碰两次业务错误就解锁「揭示答案」。 */
    @Test
    void businessRefusalResponseDoesNotCarryTheAnswerWrongMarker()
    {
        topic.setProductType(ProductType.ORIENTEERING);
        node.setStatus(0L);   // 已下架且本人无完成行 → 在更早的下架闸被拒

        AjaxResult result = controller.answer(String.valueOf(ACTIVITY), String.valueOf(NODE), "随便", null);

        assertNotEquals(200, result.get("code"));
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("data"),
                "业务拒绝不得带答错标记");
    }
    @Test
    void firstShopDayScanCompletesOnlyTheTicketActuallyChosenWithoutCompletingPlay() {
        topic.setProductType(ProductType.FREE_EXPLORE);
        CmsRegistration selected = scanTicket(REGISTRATION);
        CmsRegistration newer = scanTicket(REGISTRATION + 1);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(java.util.Arrays.asList(newer, selected));

        AjaxResult result = controller.checkin(String.valueOf(ACTIVITY), CODE, null, null, null, REGISTRATION);

        assertEquals(200, result.get("code"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) result.get("data")).get("done"));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(proofService, registrationMapper);
        order.verify(proofService).markArrived(PLAYER, ACTIVITY, node);
        order.verify(registrationMapper).markFirstSuccessfulScan(eq(REGISTRATION), eq(PLAYER), eq(2L), eq(ACTIVITY), any());
        verify(registrationMapper, never()).markFirstSuccessfulScan(eq(REGISTRATION + 1), anyLong(), anyLong(), anyLong(), any());
        verify(perkLedgerService, never()).isExploreCompleted(anyLong(), anyLong());
    }

    @Test
    void oldEntryWithoutTicketUsesOneCurrentQualificationOnly() {
        topic.setProductType(ProductType.FREE_EXPLORE);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(java.util.Arrays.asList(scanTicket(REGISTRATION + 1), scanTicket(REGISTRATION)));
        assertEquals(200, controller.checkin(String.valueOf(ACTIVITY), CODE, null).get("code"));
        verify(registrationMapper).markFirstSuccessfulScan(eq(REGISTRATION + 1), eq(PLAYER), eq(2L), eq(ACTIVITY), any());
        verify(registrationMapper, never()).markFirstSuccessfulScan(eq(REGISTRATION), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void ticketOutsideTheCurrentQualificationCannotMarkAnOrderOrArrival() {
        topic.setProductType(ProductType.FREE_EXPLORE);
        AjaxResult result = controller.checkin(String.valueOf(ACTIVITY), CODE, null, null, null, REGISTRATION + 9);
        assertEquals(500, result.get("code"));
        verify(proofService, never()).markArrived(anyLong(), anyLong(), any());
        verifyOnlyEligibilityReads();
    }

    @Test
    void rejectedAdvancedCompletionDoesNotWriteFirstScan() {
        topic.setProductType(ProductType.ORIENTEERING);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced =
                mock(com.chengyinhub.business.service.IAdvancedGameRuntimeService.class);
        ReflectionTestUtils.setField(controller, "advancedGameRuntimeService", advanced);
        when(advanced.completeGate(PLAYER, ACTIVITY, TOPIC, NODE))
                .thenThrow(new com.chengyinhub.common.exception.ServiceException("请先完成本章玩法"));
        org.junit.jupiter.api.Assertions.assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> controller.checkin(String.valueOf(ACTIVITY), CODE, null));
        verifyOnlyEligibilityReads();
    }

    @Test
    void repeatedScanKeepsTheFirstTimestampAndReadsCasFailureCurrently() {
        topic.setProductType(ProductType.FREE_EXPLORE);
        Date first = new Date(1000L);
        CmsRegistration current = scanTicket(REGISTRATION);
        current.setFirstSuccessfulScanAt(first);
        when(registrationMapper.markFirstSuccessfulScan(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(0);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(REGISTRATION)).thenReturn(current);
        assertEquals(200, controller.checkin(String.valueOf(ACTIVITY), CODE, null).get("code"));
        assertEquals(first, current.getFirstSuccessfulScanAt());
        // C包当前资格锁读一次 + 首扫 CAS 失败后按原ID回读当前行一次。
        verify(registrationMapper, org.mockito.Mockito.times(2)).selectCmsRegistrationByIdForUpdate(REGISTRATION);
    }

    @Test
    void qualificationCancelledDuringScanRejectsTheWholeTransactionalWrite() {
        topic.setProductType(ProductType.FREE_EXPLORE);
        CmsRegistration current = scanTicket(REGISTRATION);
        current.setRegistrationStatus(3);
        current.setFirstSuccessfulScanAt(new Date(1000L));
        when(registrationMapper.markFirstSuccessfulScan(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(0);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(REGISTRATION)).thenReturn(current);
        // C包当前资格锁读在任何写入前即拒绝已取消的原报名(回错误回包而非事务中途抛错)，整笔不写。
        org.junit.jupiter.api.Assertions.assertNotEquals(200, controller.checkin(String.valueOf(ACTIVITY), CODE, null).get("code"));
        verify(registrationMapper, org.mockito.Mockito.never())
                .markFirstSuccessfulScan(anyLong(), anyLong(), anyLong(), anyLong(), any());
        assertEquals(new Date(1000L), current.getFirstSuccessfulScanAt());
    }

    @Test
    void failedCodeAndGpsArrivalNeverMarkFirstSuccessfulScan() {
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        assertEquals(500, controller.checkin(String.valueOf(ACTIVITY), "WRONG-CODE", null).get("code"));
        verifyOnlyEligibilityReads();
        topic.setProductType(ProductType.FREE_EXPLORE);
        controller.arrive(String.valueOf(ACTIVITY), String.valueOf(NODE), "121.5", "31.2", null);
        verifyOnlyEligibilityReads();
    }

    @Test
    void selfPlayUsesOnlyTheChosenUnexpiredPass() {
        topic.setProductType(ProductType.FREE_EXPLORE);
        CmsRegistration expired = scanTicket(REGISTRATION + 1);
        expired.setOwnerType(1L);
        expired.setOwnerId(TOPIC);
        expired.setPurchaseKind(PurchaseKind.SELF_PLAY_PASS); // 自玩通行证真实品类(探索票属活动场次)
        expired.setExpiresAt(new Date(1000L));
        CmsRegistration active = scanTicket(REGISTRATION);
        active.setOwnerType(1L);
        active.setOwnerId(TOPIC);
        active.setPurchaseKind(PurchaseKind.SELF_PLAY_PASS);
        active.setExpiresAt(new Date(System.currentTimeMillis() + 86400000L));
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(java.util.Arrays.asList(expired, active));
        when(proofService.markArrived(PLAYER, 0L, node)).thenReturn(new PlayerNodeProgress());
        assertEquals(402, controller.checkin(null, CODE, String.valueOf(TOPIC), null, null, expired.getId()).get("code"));
        verifyOnlyEligibilityReads();
        assertEquals(200, controller.checkin(null, CODE, String.valueOf(TOPIC), null, null, active.getId()).get("code"));
        verify(registrationMapper).markFirstSuccessfulScan(eq(REGISTRATION), eq(PLAYER), eq(1L), eq(TOPIC), any());
    }

    @Test
    void selectedRedeemedTicketCannotBorrowAnotherTicketsUnredeemedChapter() {
        topic.setProductType(ProductType.FREE_EXPLORE);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(java.util.Arrays.asList(scanTicket(REGISTRATION + 1), scanTicket(REGISTRATION)));
        RegistrationEntitlement redeemed = new RegistrationEntitlement();
        redeemed.setRegistrationId(REGISTRATION);
        redeemed.setChapterId(CHAPTER);
        redeemed.setStatus(1);
        when(perkLedgerService.listEntitlements(REGISTRATION)).thenReturn(Collections.singletonList(redeemed));
        AjaxResult result = controller.checkin(String.valueOf(ACTIVITY), CODE, null, null, null, REGISTRATION);
        assertEquals(500, result.get("code"));
        verify(proofService, never()).markArrived(anyLong(), anyLong(), any());
        verifyOnlyEligibilityReads();
    }

    private CmsRegistration scanTicket(Long id) {
        CmsRegistration row = new CmsRegistration();
        row.setId(id);
        row.setMemberId(PLAYER);
        row.setOwnerType(2L);
        row.setOwnerId(ACTIVITY);
        row.setRegistrationStatus(2);
        row.setPaymentStatus(2);
        row.setVerificationStatus(0);
        row.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        return row;
    }


    /** 失败/越权路径对订单只允许 C 包当前资格的两种读(列表、按原ID锁读)；任何写入或其他调用都判红。 */
    private void verifyOnlyEligibilityReads() {
        java.util.List<String> allowed = java.util.Arrays.asList("selectCmsRegistrationList", "selectCmsRegistrationByIdForUpdate");
        for (org.mockito.invocation.Invocation call : org.mockito.Mockito.mockingDetails(registrationMapper).getInvocations()) {
            org.junit.jupiter.api.Assertions.assertTrue(allowed.contains(call.getMethod().getName()),
                    "订单 mapper 出现资格读以外的调用: " + call.getMethod().getName());
        }
    }
}
