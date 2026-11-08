package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameCommandReceipt;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
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
import com.chengyinhub.web.service.play.PlayBasicIntentApplication;
import com.chengyinhub.web.service.play.PlayCommandErrorContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B5 未知请求恢复契约:回包丢失后用同一动作号重发必须重放原终态、不重复发奖;
 * 同号换输入、旧期望版本、原局已结束、回执查询都要给稳定机器码与下一步指引。
 */
class PlayIntentRecoveryContractTest
{
    private static final Long PLAYER = 41L;
    private static final Long REGISTRATION = 42L;
    private static final Long ACTIVITY = 43L;
    private static final Long TOPIC = 44L;
    private static final Long CHAPTER = 45L;
    private static final Long NODE = 46L;
    private static final String NODE_LNG = "121.4737";
    private static final String NODE_LAT = "31.2304";

    private IPlayerNodeProgressService progressService;
    private IPointsAwardService pointsAwardService;
    private IPlayerProgressionService progressionService;
    private ICmsMemberTemplateService templateService;
    private ITopicCompletionAwardService completionAwardService;
    private ApiPlayProgressController controller;
    private CmsTopicNode node;

    @BeforeEach
    void setUp()
    {
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        IPerkLedgerService perkLedgerService = mock(IPerkLedgerService.class);
        progressService = mock(IPlayerNodeProgressService.class);
        completionAwardService = mock(ITopicCompletionAwardService.class);
        pointsAwardService = mock(IPointsAwardService.class);
        progressionService = mock(IPlayerProgressionService.class);

        controller = spy(new ApiPlayProgressController());
        doReturn(PLAYER).when(controller).getAppUserId();
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        PlayNodeAccessTestFixtures.setField(controller, "perkLedgerService", perkLedgerService);
        PlayNodeAccessTestFixtures.setField(controller, "shopDayProofService", mock(IShopDayProofService.class));
        PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        PlayNodeAccessTestFixtures.setField(controller, "topicCompletionAwardService", completionAwardService);
        PlayNodeAccessTestFixtures.setField(controller, "pointsAwardService", pointsAwardService);
        PlayNodeAccessTestFixtures.setField(controller, "playerProgressionService", progressionService);
        PlayNodeAccessTestFixtures.setField(controller, "growthService", mock(IGrowthService.class));
        PlayNodeAccessTestFixtures.setField(controller, "xpBudgetService", mock(IXpBudgetService.class));
        PlayNodeAccessTestFixtures.setField(controller, "rewardDropService", mock(IRewardDropService.class));
        PlayNodeAccessTestFixtures.setField(controller, "smsCouponHistoryService", mock(ISmsCouponHistoryService.class));
        PlayNodeAccessTestFixtures.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        templateService = mock(ICmsMemberTemplateService.class);
        PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", templateService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        PlayNodeAccessTestFixtures.setField(controller, "clubLeadMapper",
                mock(com.chengyinhub.business.mapper.ClubLeadMapper.class));
        PlayNodeAccessTestFixtures.setField(controller, "playAttemptLogMapper",
                mock(com.chengyinhub.business.mapper.PlayAttemptLogMapper.class));
        PlayNodeAccessTestFixtures.setField(controller, "memberCollectibleMapper",
                mock(com.chengyinhub.business.mapper.MemberCollectibleMapper.class));
        PlayNodeAccessTestFixtures.setField(controller, "nodeMerchantOverrideService",
                new com.chengyinhub.business.service.impl.NodeMerchantOverrideServiceImpl(
                        mock(com.chengyinhub.business.service.ICmsRegistrationMerchantService.class)));

        node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setChapterId(CHAPTER);
        node.setStatus(1L);
        node.setSortId(1L);
        node.setLongitude(NODE_LNG);
        node.setLatitude(NODE_LAT);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setEndDate(new Date(System.currentTimeMillis() + 86_400_000L));
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

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setName("B5 意图恢复契约");
        topic.setProductType(ProductType.ORIENTEERING);
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
        proof.setStatus(0);
        proof.setCheckinTime(new Date());
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(proof));
        when(progressService.insertPlayerNodeProgress(any())).thenReturn(1);
        when(progressionService.onNodeDone(PLAYER)).thenReturn(Collections.emptyList());
        when(completionAwardService.grantOnce(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new TopicCompletionAwardVO());
        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller, TOPIC);
    }

    private AjaxResult arrive(String actionId, Integer expectedVersion, String longitude, String latitude)
    {
        return BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE),
                longitude, latitude, null, actionId, expectedVersion, "15");
    }

    private void verifyNoCompletion()
    {
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), any());
        verify(progressionService, never()).onNodeDone(anyLong());
    }

    /**
     * 丢回包:端上重发同一动作号,必须拿到与首次一致的终态,且不重复发奖。
     *
     * <p>比较落在结构与标量上而不比 Date 对象:回执以 JSON 落库,重放时 arrivedAt 是已格式化的字符串,
     * 与首次的内存 Date 不是同一 Java 类型——这正是「重放按持久化表示返回」的目的。
     */
    @Test
    @SuppressWarnings("unchecked")
    void lostResponseReplaysIdenticalTerminalBodyWithoutDoubleAward()
    {
        AjaxResult first = arrive("lost-1", null, NODE_LNG, NODE_LAT);
        AjaxResult second = arrive("lost-1", null, NODE_LNG, NODE_LAT);

        assertEquals(200, first.get("code"), "到达本身仍须成功");
        assertEquals(Boolean.TRUE, first.get("intentTerminal"));
        assertEquals(first.keySet(), second.keySet(), "重放不得追加任何标记(如 replayed)");
        assertEquals(first.get("code"), second.get("code"));
        assertEquals(first.get("msg"), second.get("msg"), "重放文案必须与首次一致");
        assertEquals(Boolean.TRUE, second.get("intentTerminal"));
        assertEquals(((java.util.Map<String, Object>) first.get("data")).keySet(),
                ((java.util.Map<String, Object>) second.get("data")).keySet(), "重放数据形状必须一致");
        assertEquals(String.valueOf(NODE),
                String.valueOf(((java.util.Map<String, Object>) second.get("data")).get("nodeId")),
                "重放后 nodeId 数值不变(JSON 往返可能是 Integer)");
        verify(pointsAwardService, times(1)).award(PLAYER, 12L, NODE, "节点打卡");
        verify(progressionService, times(1)).onNodeDone(PLAYER);
    }

    /** 同号换输入:动作号已被另一份输入占用,必须换新号(否则客户端会以为改了就生效)。 */
    @Test
    void sameActionIdWithDifferentInputIsRejectedAsReused()
    {
        AjaxResult first = arrive("reuse-1", null, "121.6", "31.4");
        assertNotEquals(200, first.get("code"), "远处到达先被拒");

        AjaxResult second = arrive("reuse-1", null, NODE_LNG, NODE_LAT);
        assertEquals(409, second.get("code"));
        assertEquals(PlayCommandErrorContract.INTENT_ACTION_REUSED, second.get("errorCode"));
        assertEquals(Boolean.FALSE, second.get("retryable"));
        assertEquals(Boolean.TRUE, second.get("newActionIdRequired"));
        verifyNoCompletion();
    }

    /** 旧期望版本/旧 epoch:必须刷新动作身份,但动作号尚未占用,可复用。 */
    @Test
    void staleExpectedVersionAsksForRefreshWithoutConsumingActionId()
    {
        AjaxResult result = arrive("stale-1", 99, NODE_LNG, NODE_LAT);

        assertEquals(409, result.get("code"));
        assertEquals(PlayCommandErrorContract.INTENT_STALE_STATE, result.get("errorCode"));
        assertEquals(Boolean.FALSE, result.get("retryable"));
        assertEquals(Boolean.TRUE, result.get("refreshRequired"));
        assertEquals(Boolean.TRUE, result.get("intentNotExecuted"));
        assertNull(result.get("newActionIdRequired"), "未写回执的动作号仍可复用");
        verifyNoCompletion();
    }


    /** 原局已结束:保留 409,并给出可机器判定的终止原因。 */
    @Test
    void endedOriginalSessionKeeps409AndIsMachineReadable()
    {
        BasicPlayCommandFixtures.routeVersion(controller, ACTIVITY, TOPIC, 1);
        TopicRouteSessionMapper routes =
                (TopicRouteSessionMapper) PlayNodeAccessTestFixtures.getField(controller, "playCommandRouteMapper");
        TopicRouteSession session = routes.selectCurrentByOwner(ACTIVITY, TOPIC, PLAYER);
        session.setStatus(TopicRouteSession.STATUS_ENDED);

        AjaxResult result = arrive("ended-1", null, NODE_LNG, NODE_LAT);

        assertEquals(409, result.get("code"), "原局结束必须仍是 409");
        assertEquals("原局已结束", result.get("msg"));
        assertEquals(PlayCommandErrorContract.INTENT_SESSION_ENDED, result.get("errorCode"));
        assertEquals(Boolean.FALSE, result.get("retryable"));
        verifyNoCompletion();
    }

    /** 回执查询查无此动作号:明确 UNKNOWN,让端上稍后按同一号再查而不是新建意图。 */
    @Test
    void unknownReceiptIsReportedAsPendingQuery()
    {
        AjaxResult result = controller.basicActionReceipt(
                String.valueOf(ACTIVITY), String.valueOf(TOPIC), String.valueOf(NODE), "never-sent");

        assertEquals(520, result.get("code"));
        assertEquals(PlayCommandErrorContract.INTENT_PENDING, result.get("errorCode"));
        assertEquals("UNKNOWN", result.get("receiptState"));
        assertEquals(Boolean.TRUE, result.get("retryable"));
    }

    /** 占位未结算的回执:不可当结果重放,只能稍后再查。 */
    @Test
    void pendingReceiptNeverReplaysAnUnsettledBody()
    {
        PlayBasicIntentApplication intent =
                (PlayBasicIntentApplication) PlayNodeAccessTestFixtures.owner(controller, PlayBasicIntentApplication.class);
        GameCommandReceipt pending = new GameCommandReceipt();
        pending.setOutcome("PENDING");
        pending.setActorPerspective("PLAYER");
        pending.setAction("PLAY_BASIC_GPS");

        AjaxResult result = intent.basicReplay(pending);

        assertEquals(520, result.get("code"));
        assertEquals(PlayCommandErrorContract.INTENT_PENDING, result.get("errorCode"));
        assertEquals("PENDING", result.get("receiptState"));
        assertEquals(Boolean.TRUE, result.get("retryable"));
        assertFalse(result.containsKey("intentTerminal"), "待确认回执绝不能被标成终态");
        assertFalse(result.containsKey("newActionIdRequired"), "PENDING 不允许客户端换新动作号重发");
    }

    /**
     * 占位回执在 executeBasicIntent 里被重发:同样只是「待确认」,不标终态、不占新动作号,
     * 且不执行副作用。客户端只能按原号稍后再查。
     */
    @Test
    void resendWhileClaimStillPendingStaysUnknown()
    {
        PlayBasicIntentApplication intent =
                (PlayBasicIntentApplication) PlayNodeAccessTestFixtures.owner(controller, PlayBasicIntentApplication.class);
        // 用生产同一份指纹口径造占位回执:kind/scope/版本/身份/输入与本次请求逐项相同。
        String hash = intent.basicHash(intent.basicJson(java.util.Arrays.asList("GPS", ACTIVITY, TOPIC, NODE,
                Integer.valueOf(0), null, Long.valueOf(0L), null, null,
                java.util.Arrays.asList(NODE_LNG, NODE_LAT, "15"))));
        GameCommandReceipt pending = new GameCommandReceipt();
        pending.setActivityId(ACTIVITY);
        pending.setNodeId(NODE);
        pending.setActorMemberId(PLAYER);
        pending.setActorPerspective("PLAYER");
        pending.setOutcome("PENDING");
        pending.setAction("PLAY_BASIC_GPS");
        pending.setRequestHash(hash);
        com.chengyinhub.business.mapper.GameSessionRuntimeMapper receipts =
                (com.chengyinhub.business.mapper.GameSessionRuntimeMapper)
                        PlayNodeAccessTestFixtures.getField(controller, "playCommandReceiptMapper");
        when(receipts.selectReceiptForUpdate(eq(ACTIVITY), eq(PLAYER), anyString())).thenReturn(pending);

        AjaxResult result = arrive("pending-1", null, NODE_LNG, NODE_LAT);

        assertEquals(520, result.get("code"), "PENDING 不得被当成成功或失败");
        assertEquals(PlayCommandErrorContract.INTENT_PENDING, result.get("errorCode"));
        assertEquals("PENDING", result.get("receiptState"));
        assertFalse(result.containsKey("intentTerminal"));
        assertFalse(result.containsKey("newActionIdRequired"));
        verifyNoCompletion();
    }

    /** 查无回执就是未知:不得升级成失败,也不得声称未执行或已完成。 */
    @Test
    void unknownReceiptIsNeverUpgradedToFailure()
    {
        AjaxResult result = controller.basicActionReceipt(
                String.valueOf(ACTIVITY), String.valueOf(TOPIC), String.valueOf(NODE), "never-sent-2");

        assertEquals(520, result.get("code"));
        assertEquals("UNKNOWN", result.get("receiptState"));
        assertFalse(result.containsKey("intentTerminal"), "未知不能升级成终态");
        assertFalse(result.containsKey("intentNotExecuted"), "未知不能断言未执行");
        assertNotEquals("FAILED", result.get("errorCode"));
    }

    /** 指纹冲突:原请求可能早已完成,回包不得声称任何完成状态。 */
    @Test
    void fingerprintConflictNeverClaimsTheOriginalRequestCompleted()
    {
        PlayBasicIntentApplication intent =
                (PlayBasicIntentApplication) PlayNodeAccessTestFixtures.owner(controller, PlayBasicIntentApplication.class);
        GameCommandReceipt applied = new GameCommandReceipt();
        applied.setOutcome("APPLIED");
        applied.setActorPerspective("PLAYER");
        applied.setAction("PLAY_BASIC_GPS");
        applied.setRequestHash("a".repeat(64));

        AjaxResult result = intent.basicReplay(applied, "b".repeat(64));

        assertEquals(409, result.get("code"));
        assertEquals(PlayCommandErrorContract.INTENT_ACTION_REUSED, result.get("errorCode"));
        assertEquals(Boolean.TRUE, result.get("newActionIdRequired"), "原号已归属另一份输入");
        assertFalse(result.containsKey("intentTerminal"), "指纹冲突不得声称原请求已完成");
        assertFalse(result.containsKey("intentNotExecuted"), "冲突时本次请求被拒,但原请求状态未知");
    }

    /** 答错会落一次失误事实,因此不得声称 intentNotExecuted;但该动作号已终态,换答案必须换新号。 */
    @Test
    @SuppressWarnings("unchecked")
    void wrongAnswerIsTerminalButNeverClaimsNoSideEffect()
    {
        node.setTemplateId(900L);
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setValidationMethod(1);
        tpl.setQuestionAnswer("正确答案");
        when(templateService.selectCmsMemberTemplateById(900L)).thenReturn(tpl);

        AjaxResult result = BasicPlayCommandFixtures.answerAt(controller, String.valueOf(ACTIVITY),
                String.valueOf(NODE), "乱写一个", null, NODE_LNG, NODE_LAT, "10");

        assertEquals("答案不正确", result.get("msg"), "既有文案不得变");
        // 答错不是「恢复/传输」错误:原合同判据是 msg=「答案不正确」+ data.answerWrong。
        // 加 errorCode 会让既有客户端把普通答错当成业务拒绝,等于改既有 body 语义
        // (与 ApiPlayProgressCompletionTruthTest 的经典模式不变量一致)。
        assertFalse(result.containsKey("errorCode"), "答错不得携带 errorCode,判据是 msg + data.answerWrong");
        assertFalse(result.containsKey("intentNotExecuted"), "答错已记失误,不得声称无副作用");
        assertEquals(Boolean.TRUE, result.get("intentTerminal"));
        assertEquals(Boolean.TRUE, result.get("newActionIdRequired"));
        assertEquals(Boolean.TRUE, ((java.util.Map<String, Object>) result.get("data")).get("answerWrong"),
                "答错标记必须保留,否则前端无法与业务拒绝区分");
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    /** 越权主题:在写回执之前就拒绝,动作号仍可复用。 */
    @Test
    void outOfScopeTopicIsRejectedBeforeAnyReceipt()
    {
        AjaxResult result = controller.arrive(null, String.valueOf(NODE), NODE_LNG, NODE_LAT, "999",
                "scope-1", 0, "15", 0L, null, null);

        assertEquals("该节点不属于本主题", result.get("msg"), "既有文案不得变");
        assertEquals(PlayCommandErrorContract.INTENT_SCOPE_DENIED, result.get("errorCode"));
        assertEquals(Boolean.FALSE, result.get("retryable"));
        assertNull(result.get("newActionIdRequired"), "未写回执前不应要求换动作号");
        verifyNoCompletion();
    }
}
