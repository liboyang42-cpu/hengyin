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
import static org.mockito.ArgumentMatchers.anyString;
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
        pass.setMemberId(PLAYER); pass.setOwnerType(2L); pass.setOwnerId(ACTIVITY);
        pass.setRegistrationStatus(2); pass.setPaymentStatus(2);
        pass.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        when(perkLedgerService.isExploreCompleted(REGISTRATION, TOPIC)).thenReturn(true);
        assertEquals(true, com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "isCompletedCompletionFact",
                topic, 0, Collections.singletonList(pass), TOPIC, 2, 1));
        when(perkLedgerService.isExploreCompleted(REGISTRATION, TOPIC)).thenReturn(false);
        assertEquals(false, com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "isCompletedCompletionFact",
                topic, 1, Collections.singletonList(pass), TOPIC, 2, 2));
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
        PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService",
                mock(com.chengyinhub.business.service.IPuzzlePlayService.class));
        doReturn(PLAYER).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationMapper", registrationMapper);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "perkLedgerService", perkLedgerService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "shopDayProofService", proofService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicCompletionAwardService", completionAwardService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "pointsAwardService", pointsAwardService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerProgressionService", progressionService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "growthService", mock(IGrowthService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "xpBudgetService", mock(IXpBudgetService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "rewardDropService", mock(IRewardDropService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "smsCouponHistoryService", mock(ISmsCouponHistoryService.class));
        // 上传事实证明由 FileOssUploadUtils 专属测试覆盖;本类只验到店凭证/通关口径合同。
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "photoUploadFactVerifier",
                (com.chengyinhub.web.service.play.PlayPhotoCommandApplication.PlayPhotoUploadFactVerifier) (picUrl, memberId) -> { });
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        templateService = mock(ICmsMemberTemplateService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", templateService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "clubLeadMapper",
                mock(com.chengyinhub.business.mapper.ClubLeadMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playAttemptLogMapper",
                mock(com.chengyinhub.business.mapper.PlayAttemptLogMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "memberCollectibleMapper",
                mock(com.chengyinhub.business.mapper.MemberCollectibleMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "nodeMerchantOverrideService",
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
        BasicPlayCommandFixtures.scope(controller, TOPIC);
    }

    @SuppressWarnings("unchecked")
    @Test
    void shopDayCheckinAndPhotoOnlyRecordProofAndNeverCompleteTheTopic()
    {
        topic.setProductType(ProductType.FREE_EXPLORE);

        AjaxResult checkin = BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, null, String.valueOf(NODE));
        AjaxResult photo = BasicPlayCommandFixtures.photo(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "photo.jpg", null);

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

        AjaxResult result = BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null);

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

        AjaxResult result = BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null);

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

        AjaxResult result = BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null);

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

        AjaxResult missing = BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null);
        assertEquals("请开启定位", missing.get("msg"));
        AjaxResult outside = BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "120", "30", null, null, null, "10");
        assertEquals("还没到这一站附近", outside.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());

        AjaxResult arrived = BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "121.5", "31.2", null, null, null, "10");
        assertEquals(200, arrived.get("code"));
        verify(templateService, never()).selectCmsMemberTemplateById(0L);

        node.setTemplateId(999L);
        AjaxResult dangling = BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "121.5", "31.2", null, null, null, "10");
        assertEquals("该站配置异常，暂时无法打卡", dangling.get("msg"));
    }

    /** 2026-10-30 复核:玩法没过就到达,要拿到明确的业务拒绝(不是异常),否则小程序把到达记成「结果待确认」锁死按钮。 */
    @Test
    void classicArriveBeforeTheGameIsPassedGetsAClearRefusal()
    {
        topic.setProductType(ProductType.ORIENTEERING);
        node.setLongitude("121.5"); node.setLatitude("31.2");
        com.chengyinhub.business.service.IAdvancedGameRuntimeService games = mock(com.chengyinhub.business.service.IAdvancedGameRuntimeService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "advancedGameRuntimeService", games);
        String a = String.valueOf(ACTIVITY), n = String.valueOf(NODE);

        when(games.completeGate(eq(PLAYER), any(), any(), eq(NODE))).thenThrow(new com.chengyinhub.common.exception.ServiceException("请先完成高级玩法"));
        AjaxResult early = BasicPlayCommandFixtures.arrive(controller, a, n, "121.5", "31.2", null, null, null, "10");
        assertEquals("ADVANCED_FIRST", early.get("errorCode"));
        assertEquals("请先完成高级玩法", early.get("msg"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void classicArriveStillCompletesThroughTheOriginalProgressPath()
    {
        topic.setProductType(ProductType.ORIENTEERING);

        AjaxResult result = BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null);

        assertEquals(200, result.get("code"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) result.get("data")).get("completed"));
        verify(pointsAwardService).award(PLAYER, 12L, NODE, "节点打卡");
        verify(progressionService).onNodeDone(PLAYER);
    }

    @Test
    void genericGpsUsesFrozenCoordinatesAndOnlyItsValidatedOriginCreatesProof() throws Exception {
        topic.setProductType(ProductType.ORIENTEERING);node.setTemplateId(101L);node.setLongitude("120.0");node.setLatitude("30.0");
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime=mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"topicRouteRuntimeService",runtime);
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
        when(runtime.nodeAnswers(PLAYER,TOPIC,ACTIVITY,NODE)).thenReturn(json.readTree("{\"validationMethod\":5}"));when(runtime.canPlay(eq(PLAYER),eq(TOPIC),eq(ACTIVITY),eq(NODE),anyString())).thenReturn(true);when(runtime.genericPlayer(PLAYER,TOPIC,ACTIVITY)).thenReturn(true);
        when(runtime.nodeArrivalConfig(PLAYER,TOPIC,ACTIVITY,NODE)).thenReturn(json.readTree("{\"longitude\":\"121.47\",\"latitude\":\"31.23\"}"));when(runtime.recordPlayerArrival(eq(PLAYER),eq(TOPIC),eq(ACTIVITY),eq(NODE),anyString(),eq(0),eq("GPS"))).thenReturn(Collections.singletonMap("version",1));
        assertEquals(500,BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "NaN", "31.23", null, null, null, "10").get("code"));assertEquals(500,BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "120", "30", null, null, null, "10").get("code"));
        BasicPlayCommandFixtures.frozenRun(controller,ACTIVITY,TOPIC,NODE,0,json.readTree("{\"validationMethod\":5}"));
        Map<String,Object> command=Map.of("routeActionId","gps-current-proof","expectedRouteVersion",0);
        AjaxResult result=BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "121.47", "31.23", null, (String)command.get("routeActionId"), (Integer)command.get("expectedRouteVersion"), "10");assertEquals(200,result.get("code"));assertEquals(Boolean.FALSE,((Map<?,?>)result.get("data")).get("done"));
        verify(runtime).recordPlayerArrival(PLAYER,TOPIC,ACTIVITY,NODE,(String)command.get("routeActionId"),(Integer)command.get("expectedRouteVersion"),"GPS");verify(progressService,never()).insertPlayerNodeProgress(any());verify(pointsAwardService,never()).award(anyLong(),anyLong(),anyLong(),any());
    }

    @Test
    void genericPlayerScanRecordsCurrentRunArrivalWithoutLegacyAwards() {
        topic.setProductType(ProductType.ORIENTEERING);
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime=mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"topicRouteRuntimeService",runtime);
        when(runtime.canPlay(eq(PLAYER),eq(TOPIC),eq(ACTIVITY),eq(NODE),anyString())).thenReturn(true);
        when(runtime.genericPlayer(PLAYER,TOPIC,ACTIVITY)).thenReturn(true);
        when(runtime.recordPlayerArrival(eq(PLAYER),eq(TOPIC),eq(ACTIVITY),eq(NODE),anyString(),eq(0))).thenReturn(Collections.singletonMap("version",1));
        BasicPlayCommandFixtures.frozenRun(controller,ACTIVITY,TOPIC,NODE,0,new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        Map<String,Object> command=Map.of("routeActionId","scan-current-proof","expectedRouteVersion",0);
        AjaxResult result=BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, (String)command.get("routeActionId"), (Integer)command.get("expectedRouteVersion"), null, String.valueOf(NODE));
        assertEquals(200,result.get("code"));assertEquals(Boolean.FALSE,((Map<?,?>)result.get("data")).get("done"));
        verify(runtime).recordPlayerArrival(PLAYER,TOPIC,ACTIVITY,NODE,(String)command.get("routeActionId"),(Integer)command.get("expectedRouteVersion"));
        verify(progressService,never()).insertPlayerNodeProgress(any());
        verify(pointsAwardService,never()).award(anyLong(),anyLong(),anyLong(),any());
        verify(completionAwardService,never()).grantOnce(anyLong(),anyLong(),anyLong(),any());
    }

    @Test
    void lastOpeningGameDoesNotAwardTheThemeBeforeDeparture()
    {
        topic.setProductType(ProductType.ORIENTEERING);
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime =
                mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", runtime);
        when(runtime.canPlay(eq(PLAYER),eq(TOPIC),eq(ACTIVITY),eq(NODE),anyString())).thenReturn(true);
        com.chengyinhub.business.domain.route.TopicRouteState state =
                new com.chengyinhub.business.domain.route.TopicRouteState();
        state.setRouteMode("LINEAR"); state.setStatus("ACTIVE");
        state.setOpening(Collections.singletonMap("complete", false));
        when(runtime.state(PLAYER, TOPIC, ACTIVITY)).thenReturn(state);
        BasicPlayCommandFixtures.frozenRun(controller,ACTIVITY,TOPIC,NODE,0,new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        var coordinator = mock(com.chengyinhub.web.service.TopicRouteCompletionCoordinator.class);
        when(coordinator.advanceOriginal(eq(PLAYER),eq(TOPIC),eq(ACTIVITY),eq(NODE),eq(99L),anyString(),eq(0),any(),isNull(),isNull())).thenReturn(state);
        PlayNodeAccessTestFixtures.setField(controller,"topicRouteCompletionCoordinator",coordinator);
        AjaxResult result = BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null);
        assertEquals(200, result.get("code"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) result.get("data")).get("completed"));
        verify(completionAwardService, never()).grantOnce(anyLong(), anyLong(), anyLong(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void classicCheckinStillCompletesThroughTheOriginalProgressPath()
    {
        topic.setProductType(ProductType.ORIENTEERING);

        AjaxResult result = BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, null, String.valueOf(NODE));

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
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "nodeMedalClaimMapper", medalMapper);

        AjaxResult result = BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, null, String.valueOf(NODE));

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
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "nodeMedalClaimMapper", medalMapper);

        AjaxResult result = BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, null, String.valueOf(NODE));

        assertEquals(200, result.get("code"));
        org.junit.jupiter.api.Assertions.assertNull(
                ((Map<String, Object>) result.get("data")).get("medalRank"));
    }

    // ---------- C-22 答错与业务拒绝必须可区分(2026-09-16) ----------

    /** 正控:真答错 → 回执带 data.answerWrong 标记,前端才允许计一次错。 */
    @SuppressWarnings("unchecked")
    @Test
    void classicLocatedStationJudgesTheAnswerOnlyInsideTheFiftyMetreFence()
    {
        // 实走 A20(业主 2026-10-30 拍板「执行 50 米」):老路径到达即完成,作答时随带定位按 arrive 同一围栏判
        topic.setProductType(ProductType.ORIENTEERING);
        node.setTemplateId(900L); node.setLongitude("121.4737"); node.setLatitude("31.2304");
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setValidationMethod(1); tpl.setQuestionAnswer("正确答案");
        when(templateService.selectCmsMemberTemplateById(900L)).thenReturn(tpl);
        String a = String.valueOf(ACTIVITY), n = String.valueOf(NODE);

        AjaxResult noFix = BasicPlayCommandFixtures.answer(controller, a, n, "乱写一个", null);
        assertEquals("ARRIVE_FIRST", noFix.get("errorCode"));
        AjaxResult far = BasicPlayCommandFixtures.answerAt(controller, a, n, "乱写一个", null, "121.4837", "31.2304", "10");
        assertEquals("ARRIVE_FIRST", far.get("errorCode"));
        AjaxResult blurry = BasicPlayCommandFixtures.answerAt(controller, a, n, "乱写一个", null, "121.4737", "31.2304", "200");
        assertEquals("ARRIVE_FIRST", blurry.get("errorCode"));
        verify(progressService, never()).insertPlayerNodeProgress(any());

        AjaxResult near = BasicPlayCommandFixtures.answerAt(controller, a, n, "乱写一个", null, "121.4739", "31.2304", "10");
        assertEquals(null, near.get("errorCode"));
        assertEquals("答案不正确", near.get("msg"), "围栏内照常判题");
    }

    @Test
    void wrongAnswerResponseCarriesTheAnswerWrongMarker()
    {
        topic.setProductType(ProductType.ORIENTEERING);
        node.setTemplateId(900L);
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setValidationMethod(1);
        tpl.setQuestionAnswer("正确答案");
        tpl.setFeedbackText("暗号正确，隐藏奖励已解锁");
        when(templateService.selectCmsMemberTemplateById(900L)).thenReturn(tpl);

        AjaxResult result = BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "乱写一个", null);

        assertEquals(500, result.get("code"));
        assertEquals("答案不正确", result.get("msg"), "完成反馈不能作为答错文案");
        com.chengyinhub.business.mapper.PlayAttemptLogMapper attemptMapper =
                (com.chengyinhub.business.mapper.PlayAttemptLogMapper)
                        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "playAttemptLogMapper");
        verify(attemptMapper).insertPlayAttemptLog(org.mockito.ArgumentMatchers.argThat(log ->
                Integer.valueOf(0).equals(log.getResult()) && "乱写一个".equals(log.getWrongAnswer())));
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(completionAwardService, never()).grantOnce(anyLong(), anyLong(), anyLong(), any());
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), any());
        assertEquals(Boolean.TRUE, ((Map<String, Object>) result.get("data")).get("answerWrong"),
                "答错必须带标记,否则前端的错次/揭示无法与前端的业务拒绝区分");
    }

    @Test
    void merchantCompletionFeedbackCannotReplaceWrongAnswerMessage() {
        com.chengyinhub.business.service.INodeMerchantOverrideService overrides =
                mock(com.chengyinhub.business.service.INodeMerchantOverrideService.class);
        when(overrides.forNode(NODE)).thenReturn(
                new com.chengyinhub.business.domain.vo.NodeMerchantOverride(99L, null,
                        "商家隐藏奖励已解锁", null));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "nodeMerchantOverrideService", overrides);
        wrongAnswerResponseCarriesTheAnswerWrongMarker();
    }

    @Test
    void freeStoryAnswerAdvancesThoughtStateUsingTheServerProgressReceipt() {
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":false,\"thoughts\":["
                + "{\"key\":\"echo\",\"name\":\"回声\",\"need\":1}]}");
        node.setTemplateId(900L);
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setValidationMethod(1); tpl.setQuestionAnswer("答案");
        tpl.setFeedbackText("暗号正确，隐藏奖励已解锁");
        when(templateService.selectCmsMemberTemplateById(900L)).thenReturn(tpl);
        when(progressService.insertPlayerNodeProgress(any())).thenAnswer(call -> {
            ((PlayerNodeProgress) call.getArgument(0)).setId(901L);
            return 1;
        });
        com.chengyinhub.web.service.TopicRouteCompletionCoordinator coordinator =
                mock(com.chengyinhub.web.service.TopicRouteCompletionCoordinator.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"topicRouteCompletionCoordinator",coordinator);
        com.chengyinhub.business.domain.route.TopicRouteState state =
                new com.chengyinhub.business.domain.route.TopicRouteState();
        state.setRouteMode("LINEAR"); state.setStatus("ACTIVE");
        when(coordinator.advanceOriginal(eq(PLAYER),eq(TOPIC),eq(ACTIVITY),eq(NODE),eq(99L),eq("answer-1"),eq(2),
                eq("player-node-progress:901"),isNull(),isNull())).thenReturn(state);
        BasicPlayCommandFixtures.frozenRun(controller,ACTIVITY,TOPIC,NODE,2,new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("validationMethod",1).put("questionAnswer","答案"));
        AjaxResult result = BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "答案", null, "answer-1", 2);
        assertEquals(200, result.get("code"));
        assertEquals("答对了！", result.get("msg"));
        assertEquals("暗号正确，隐藏奖励已解锁", tpl.getFeedbackText());
        verify(coordinator).advanceOriginal(PLAYER,TOPIC,ACTIVITY,NODE,99L,"answer-1",2,"player-node-progress:901",null,null);
        verify(proofService,never()).hasArrived(anyLong(),anyLong(),anyLong());
    }

    /** 负控:业务拒绝(下架站)不许带 answerWrong —— 否则连碰两次业务错误就解锁「揭示答案」。 */
    @Test
    void businessRefusalResponseDoesNotCarryTheAnswerWrongMarker()
    {
        topic.setProductType(ProductType.ORIENTEERING);
        node.setStatus(0L);   // 已下架且本人无完成行 → 在更早的下架闸被拒

        AjaxResult result = BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "随便", null);

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

        AjaxResult result = BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, REGISTRATION, String.valueOf(NODE));

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
        assertEquals(200, BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, null, String.valueOf(NODE)).get("code"));
        verify(registrationMapper).markFirstSuccessfulScan(eq(REGISTRATION + 1), eq(PLAYER), eq(2L), eq(ACTIVITY), any());
        verify(registrationMapper, never()).markFirstSuccessfulScan(eq(REGISTRATION), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void ticketOutsideTheCurrentQualificationCannotMarkAnOrderOrArrival() {
        topic.setProductType(ProductType.FREE_EXPLORE);
        AjaxResult result = BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, REGISTRATION + 9, String.valueOf(NODE));
        assertEquals(500, result.get("code"));
        verify(proofService, never()).markArrived(anyLong(), anyLong(), any());
        verifyOnlyEligibilityReads();
    }

    @Test
    void rejectedAdvancedCompletionDoesNotWriteFirstScan() {
        topic.setProductType(ProductType.ORIENTEERING);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced =
                mock(com.chengyinhub.business.service.IAdvancedGameRuntimeService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "advancedGameRuntimeService", advanced);
        when(advanced.completeGate(PLAYER, ACTIVITY, TOPIC, NODE))
                .thenThrow(new com.chengyinhub.common.exception.ServiceException("请先完成本章玩法"));
        org.junit.jupiter.api.Assertions.assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, null, String.valueOf(NODE)));
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
        assertEquals(200, BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, null, String.valueOf(NODE)).get("code"));
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
        org.junit.jupiter.api.Assertions.assertNotEquals(200, BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, null, String.valueOf(NODE)).get("code"));
        verify(registrationMapper, org.mockito.Mockito.never())
                .markFirstSuccessfulScan(anyLong(), anyLong(), anyLong(), anyLong(), any());
        assertEquals(new Date(1000L), current.getFirstSuccessfulScanAt());
    }

    @Test
    void failedCodeAndGpsArrivalNeverMarkFirstSuccessfulScan() {
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        assertEquals(500, BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), "WRONG-CODE", null, null, null, null, String.valueOf(NODE)).get("code"));
        verifyOnlyEligibilityReads();
        topic.setProductType(ProductType.FREE_EXPLORE);
        BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "121.5", "31.2", null, null, null, "10");
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
        assertEquals(402, BasicPlayCommandFixtures.checkin(controller, null, CODE, String.valueOf(TOPIC), null, null, expired.getId(), String.valueOf(NODE)).get("code"));
        verifyOnlyEligibilityReads();
        assertEquals(200, BasicPlayCommandFixtures.checkin(controller, null, CODE, String.valueOf(TOPIC), null, null, active.getId(), String.valueOf(NODE)).get("code"));
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
        AjaxResult result = BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, REGISTRATION, String.valueOf(NODE));
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

    @Test void genericFrozenTextAndChoiceAnswersProduceOnlyControlledCurrentProof() throws Exception {
        topic.setProductType(ProductType.ORIENTEERING);node.setTemplateId(9L);
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime=mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"topicRouteRuntimeService",runtime);when(runtime.canPlay(eq(PLAYER),eq(TOPIC),eq(ACTIVITY),eq(NODE),anyString())).thenReturn(true);when(runtime.genericPlayer(PLAYER,TOPIC,ACTIVITY)).thenReturn(true);
        CmsMemberTemplate live=new CmsMemberTemplate();live.setValidationMethod(1);live.setQuestionAnswer("changed");when(templateService.selectCmsMemberTemplateById(9L)).thenReturn(live);
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
        when(runtime.nodeAnswers(PLAYER,TOPIC,ACTIVITY,NODE)).thenReturn(json.readTree("{\"validationMethod\":1,\"questionAnswer\":\"Ａ B\"}"));
        PlayerNodeProgress proof=new PlayerNodeProgress();proof.setStatus(0);when(proofService.markArrived(PLAYER,ACTIVITY,node)).thenReturn(proof);
        BasicPlayCommandFixtures.frozenRun(controller,ACTIVITY,TOPIC,NODE,0,json.readTree("{\"validationMethod\":1,\"questionAnswer\":\"Ａ B\"}"));
        AjaxResult wrong=BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "changed", null);assertEquals(500,wrong.get("code"));verify(runtime,never()).recordPlayerArrival(anyLong(),anyLong(),anyLong(),anyLong(),any(),any(),anyString());
        Map<String,Object> command=Map.of("routeActionId","answer-current-proof","expectedRouteVersion",0);
        AjaxResult text=BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "ab", null, (String)command.get("routeActionId"), (Integer)command.get("expectedRouteVersion"));assertEquals(200,text.get("code"));verify(runtime).recordPlayerArrival(PLAYER,TOPIC,ACTIVITY,NODE,(String)command.get("routeActionId"),(Integer)command.get("expectedRouteVersion"),"ANSWER");
        when(runtime.nodeAnswers(PLAYER,TOPIC,ACTIVITY,NODE)).thenReturn(json.readTree("{\"validationMethod\":3,\"correctAnswer\":\"C\"}"));
        when(runtime.canPlay(PLAYER,TOPIC,ACTIVITY,NODE,"pick-proof")).thenReturn(true);
        BasicPlayCommandFixtures.frozenRun(controller,ACTIVITY,TOPIC,NODE,3,json.readTree("{\"validationMethod\":3,\"correctAnswer\":\"C\"}"));
        AjaxResult pick=BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "c", null, "pick-proof", 3);assertEquals(200,pick.get("code"));verify(runtime).recordPlayerArrival(PLAYER,TOPIC,ACTIVITY,NODE,"pick-proof",3,"ANSWER");verify(progressService,never()).insertPlayerNodeProgress(any());verify(pointsAwardService,never()).award(anyLong(),anyLong(),anyLong(),any());
    }

    /** 实走 A20(业主 2026-10-30 拍板「执行 50 米」):本局路线里有坐标的站,未经 GPS 记过到达不得作答;到过就照常判题。 */
    @Test void locatedGenericStationRefusesAnswersUntilTheGpsArrivalIsRecorded() throws Exception {
        topic.setProductType(ProductType.ORIENTEERING);node.setTemplateId(9L);
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime=mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"topicRouteRuntimeService",runtime);
        when(runtime.canPlay(eq(PLAYER),eq(TOPIC),eq(ACTIVITY),eq(NODE),anyString())).thenReturn(true);
        when(runtime.genericPlayer(PLAYER,TOPIC,ACTIVITY)).thenReturn(true);
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
        when(runtime.nodeArrivalConfig(PLAYER,TOPIC,ACTIVITY,NODE)).thenReturn(json.readTree("{\"longitude\":\"121.47\",\"latitude\":\"31.23\"}"));
        when(runtime.nodeAnswers(PLAYER,TOPIC,ACTIVITY,NODE)).thenReturn(json.readTree("{\"validationMethod\":3,\"correctAnswer\":\"C\"}"));
        PlayerNodeProgress proof=new PlayerNodeProgress();proof.setStatus(0);when(proofService.markArrived(PLAYER,ACTIVITY,node)).thenReturn(proof);
        BasicPlayCommandFixtures.frozenRun(controller,ACTIVITY,TOPIC,NODE,3,json.readTree("{\"validationMethod\":3,\"correctAnswer\":\"C\"}"));

        when(proofService.hasArrived(PLAYER,ACTIVITY,NODE)).thenReturn(false);
        AjaxResult refused=BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "c", null, "pick-early", 3);
        assertEquals(500,refused.get("code"));
        assertEquals("ARRIVE_FIRST",refused.get("errorCode"));
        verify(runtime,never()).recordPlayerArrival(anyLong(),anyLong(),anyLong(),anyLong(),any(),any(),anyString());

        when(proofService.hasArrived(PLAYER,ACTIVITY,NODE)).thenReturn(true);
        AjaxResult answered=BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "c", null, "pick-arrived", 3);
        assertEquals(200,answered.get("code"));
    }

    @Test void originalPublishedAnswerSurvivesUnbindButDoesNotGrantLegacyCompletionOrRewards() throws Exception {
        topic.setProductType(ProductType.ORIENTEERING);node.setTemplateId(null);
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime=mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        PlayNodeAccessTestFixtures.setField(controller,"topicRouteRuntimeService",runtime);
        when(runtime.canPlay(eq(PLAYER),eq(TOPIC),eq(ACTIVITY),eq(NODE),anyString())).thenReturn(true);
        when(runtime.genericPlayer(PLAYER,TOPIC,ACTIVITY)).thenReturn(true);
        var content=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        content.put("contentSnapshotVersion",1).put("templateId",9L).put("validationMethod",1).put("questionAnswer","original-answer");
        content.putObject("nodePresentation").put("id",NODE).put("topicId",TOPIC).put("templateId",9L);
        content.putObject("templatePresentation").put("questionName","original-question");
        when(runtime.nodeAnswers(PLAYER,TOPIC,ACTIVITY,NODE)).thenReturn(content);
        BasicPlayCommandFixtures.frozenRun(controller,ACTIVITY,TOPIC,NODE,0,content);
        PlayerNodeProgress proof=new PlayerNodeProgress();proof.setStatus(0);when(proofService.markArrived(PLAYER,ACTIVITY,node)).thenReturn(proof);
        assertEquals(200,BasicPlayCommandFixtures.answer(controller,String.valueOf(ACTIVITY),String.valueOf(NODE),
                "original-answer",null,"frozen-unbound",0).get("code"));
        verify(runtime).recordPlayerArrival(PLAYER,TOPIC,ACTIVITY,NODE,"frozen-unbound",0,"ANSWER");
        verify(templateService,never()).selectCmsMemberTemplateById(any());
        verify(progressService,never()).insertPlayerNodeProgress(any());
        verify(pointsAwardService,never()).award(anyLong(),anyLong(),anyLong(),any());
    }

    // ---------- N-02 排行榜前置证明:每种完成方式都落 player_node_progress(status=1) ----------
    // 线性排行榜按 status=1 行计分。下面四条逐一钉住扫码/拍照/答题/高级玩法门四种完成入口
    // 都写出同一形状的完成行;个人玩法(genericPlayer)的扫码/答题只落 status=0 到店凭证
    // (见 genericPlayerScanRecordsCurrentRunArrivalWithoutLegacyAwards),其完成事实在
    // topic_route_session,排行榜对这类主题改读会话(LeaderboardRankPolicy)。

    private PlayerNodeProgress onlyInsertedProgressRow() {
        org.mockito.ArgumentCaptor<PlayerNodeProgress> inserted =
                org.mockito.ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(progressService).insertPlayerNodeProgress(inserted.capture());
        PlayerNodeProgress row = inserted.getValue();
        assertEquals(PLAYER, row.getMemberId());
        assertEquals(ACTIVITY, row.getActivityId());
        assertEquals(TOPIC, row.getTopicId());
        assertEquals(NODE, row.getNodeId());
        assertEquals(Integer.valueOf(1), row.getStatus(), "完成行必须 status=1,排行榜才计分");
        org.junit.jupiter.api.Assertions.assertNotNull(row.getCheckinTime(), "完成时间是同分先完成者的裁判");
        return row;
    }

    @Test
    void leaderboardProofScanCompletionWritesCompletedProgressRow() {
        topic.setProductType(ProductType.ORIENTEERING);

        assertEquals(200, BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, null, String.valueOf(NODE)).get("code"));

        org.junit.jupiter.api.Assertions.assertNull(onlyInsertedProgressRow().getPicUrl());
    }

    @Test
    void leaderboardProofPhotoCompletionWritesCompletedProgressRow() {
        topic.setProductType(ProductType.ORIENTEERING);
        node.setTemplateId(77L);
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setValidationMethod(com.chengyinhub.common.constant.ValidationMethod.PHOTO);
        when(templateService.selectCmsMemberTemplateById(77L)).thenReturn(tpl);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());

        assertEquals(200, BasicPlayCommandFixtures.photo(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "photo.jpg", null).get("code"));

        assertEquals("photo.jpg", onlyInsertedProgressRow().getPicUrl());
    }

    @Test
    void leaderboardProofAnswerCompletionWritesCompletedProgressRow() {
        topic.setProductType(ProductType.ORIENTEERING);
        node.setTemplateId(78L);
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setValidationMethod(1);
        tpl.setQuestionAnswer("暗号");
        when(templateService.selectCmsMemberTemplateById(78L)).thenReturn(tpl);
        com.chengyinhub.business.service.IPuzzlePlayService puzzles =
                mock(com.chengyinhub.business.service.IPuzzlePlayService.class);
        when(puzzles.solvedScore(PLAYER, TOPIC, NODE, ACTIVITY)).thenReturn(100);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService", puzzles);

        assertEquals(200, BasicPlayCommandFixtures.answer(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), "暗号", null).get("code"));

        onlyInsertedProgressRow();
    }

    @Test
    void leaderboardProofAdvancedGameplayCompletionWritesCompletedProgressRow() {
        topic.setProductType(ProductType.ORIENTEERING);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced =
                mock(com.chengyinhub.business.service.IAdvancedGameRuntimeService.class);
        when(advanced.completeGate(PLAYER, ACTIVITY, TOPIC, NODE)).thenReturn("PASS");
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "advancedGameRuntimeService", advanced);

        assertEquals(200, BasicPlayCommandFixtures.checkin(controller, String.valueOf(ACTIVITY), CODE, null, null, null, null, String.valueOf(NODE)).get("code"));

        verify(advanced).completeGate(PLAYER, ACTIVITY, TOPIC, NODE);
        onlyInsertedProgressRow();
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
