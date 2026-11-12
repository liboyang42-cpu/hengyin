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
import com.chengyinhub.business.service.support.ArriveLocationGuard;
import com.chengyinhub.business.service.support.ArriveLocationGuardPolicy;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.play.PlayCommandErrorContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B5 定位拒绝的可解释/可恢复契约:/api/play/arrive 的每一种拒绝都必须带稳定机器码,
 * 瞬时原因(拒权、漂移、低精度、远处)标记 retryable,配置缺合同标记不可重发;
 * 且一律不推进度、不发奖、不判通关。50m/80m 围栏与既有 errorCode 原值一并锁住。
 */
class PlayArriveRecoveryContractTest
{
    private static final Long PLAYER = 41L;
    private static final Long REGISTRATION = 42L;
    private static final Long ACTIVITY = 43L;
    private static final Long TOPIC = 44L;
    private static final Long CHAPTER = 45L;
    private static final Long NODE = 46L;
    private static final String CODE = "STORE-CODE";
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
        node.setCheckinCode(CODE);
        node.setSortId(1L);
        node.setStatus(1L);
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
        topic.setName("B5 定位恢复契约");
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

    private AjaxResult arrive(String longitude, String latitude, String accuracy)
    {
        return BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE),
                longitude, latitude, null, null, null, accuracy);
    }

    private void verifyNoCompletion()
    {
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), any());
        verify(progressionService, never()).onNodeDone(anyLong());
        verify(completionAwardService, never()).grantOnce(anyLong(), anyLong(), anyLong(), any());
    }

    private void assertRejection(AjaxResult result, String errorCode, boolean retryable)
    {
        assertNotEquals(200, result.get("code"), "拒绝不得成功:" + errorCode);
        assertEquals(errorCode, result.get("errorCode"));
        assertEquals(retryable, result.get("retryable"), "retryable 必须如实:" + errorCode);
        assertEquals(Boolean.FALSE, result.get("refreshRequired"), "定位拒绝不要求刷新动作身份");
        assertEquals(Boolean.TRUE, result.get("intentNotExecuted"), "定位拒绝一定未落地");
    }

    /** 拒权(wx.getLocation 被拒 → 端上不带经纬度):可重新授权后重发。 */
    @Test
    void locationPermissionDeniedIsRetryable()
    {
        AjaxResult result = arrive(null, null, "15");

        assertEquals("请开启定位", result.get("msg"), "既有文案不得变");
        assertRejection(result, PlayCommandErrorContract.ARRIVE_LOCATION_MISSING, true);
        verifyNoCompletion();
    }

    /** NaN / Infinity / 越界 / 坏文本:定位无效,重新定位后可重发。 */
    @Test
    void invalidCoordinatesAreRetryable()
    {
        for (String[] pair : new String[][] {
                { "NaN", "NaN" },
                { "Infinity", "31.2304" },
                { "181", "31.2304" },
                { "121.4737", "-91" },
                { "abc", "31.2304" },
        }) {
            AjaxResult result = arrive(pair[0], pair[1], "20");   // 精度合法(≤80m),专门隔离坐标无效这一条
            assertEquals("定位无效", result.get("msg"), "既有文案不得变");
            assertRejection(result, PlayCommandErrorContract.ARRIVE_LOCATION_INVALID, true);
        }
        verifyNoCompletion();
    }

    /** 空精度:保留既有机器码(端上已按它提示),只补 retryable。 */
    @Test
    void blankAccuracyKeepsLegacyCodeAndBecomesRetryable()
    {
        AjaxResult result = arrive(NODE_LNG, NODE_LAT, null);

        assertEquals(ArriveLocationGuard.ACCURACY_MISSING, result.get("errorCode"));
        assertEquals("未获取到定位精度，请重新定位后重试或扫码完成", result.get("msg"));
        assertEquals(Boolean.TRUE, result.get("retryable"));
        assertEquals(Boolean.FALSE, result.get("appealable"), "未上报精度不是作弊判定");
        verifyNoCompletion();
    }

    /** 非法精度:同样保留既有机器码 + 不可申诉 + 可重试。 */
    @Test
    void invalidAccuracyKeepsLegacyCodeAndBecomesRetryable()
    {
        for (String bad : new String[] { "NaN", "-1", "abc" }) {
            AjaxResult result = arrive(NODE_LNG, NODE_LAT, bad);
            assertEquals(ArriveLocationGuard.ACCURACY_INVALID, result.get("errorCode"), "非法精度:" + bad);
            assertEquals(Boolean.TRUE, result.get("retryable"));
            assertEquals(Boolean.FALSE, result.get("appealable"));
        }
        verifyNoCompletion();
    }

    /** 81m 精度:80m 闸原值不动,只说明「到开阔处再来」可重试。 */
    @Test
    void accuracyAboveLimitIsRetryable()
    {
        AjaxResult result = arrive(NODE_LNG, NODE_LAT, "81");

        assertEquals("定位精度不足，请到开阔处或扫码完成", result.get("msg"), "既有文案不得变");
        assertRejection(result, PlayCommandErrorContract.ARRIVE_ACCURACY_LOW, true);
        verifyNoCompletion();
    }

    /** 50m 围栏原值不动:远处可走近重发,且回执已终态,重试必须换新动作号。 */
    @Test
    void outsideGeofenceIsRetryableAndConsumesTheActionId()
    {
        AjaxResult result = arrive("121.6", "31.4", "15");

        assertEquals("还没到这一站附近", result.get("msg"), "既有文案不得变");
        assertRejection(result, PlayCommandErrorContract.ARRIVE_TOO_FAR, true);
        assertEquals(Boolean.TRUE, result.get("intentTerminal"), "拒绝已写入终态回执");
        assertEquals(Boolean.TRUE, result.get("newActionIdRequired"), "动作号已被占用,重试必须换新号");
        verifyNoCompletion();
    }

    /** vm=5 站点缺坐标:配置缺合同,不是玩家可重发解决的问题。 */
    @Test
    void gpsNodeWithoutCoordinateIsBlockedConfig()
    {
        node.setTemplateId(60L);
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setValidationMethod(5);
        when(templateService.selectCmsMemberTemplateById(60L)).thenReturn(tpl);
        node.setLongitude(null);
        node.setLatitude(null);

        AjaxResult result = arrive(NODE_LNG, NODE_LAT, "15");

        assertEquals("站点未设坐标，暂时无法打卡", result.get("msg"), "既有文案不得变");
        assertRejection(result, PlayCommandErrorContract.ARRIVE_COORD_NOT_CONFIGURED, false);
        verifyNoCompletion();
    }

    /** 守卫节流是瞬时拒绝(可原样重发),速度/窗口是位移异常(重发仍命中,可申诉)。 */
    @Test
    void guardThrottleIsRetryableButSpeedIsNot()
    {
        ArriveLocationGuard guard = mock(ArriveLocationGuard.class);
        PlayNodeAccessTestFixtures.setField(controller, "arriveLocationGuard", guard);
        when(guard.check(any(), any(), any(), any(), ArgumentMatchers.anyDouble(),
                ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new ArriveLocationGuard.Result(
                        ArriveLocationGuardPolicy.THROTTLE, "操作太快，请稍后再试", 7L, false));
        AjaxResult throttled = arrive(NODE_LNG, NODE_LAT, "20");
        assertEquals(ArriveLocationGuardPolicy.THROTTLE, throttled.get("errorCode"));
        assertEquals(Boolean.TRUE, throttled.get("retryable"));
        assertEquals(Boolean.FALSE, throttled.get("appealable"));

        when(guard.check(any(), any(), any(), any(), ArgumentMatchers.anyDouble(),
                ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new ArriveLocationGuard.Result(
                        ArriveLocationGuardPolicy.SPEED, "移动速度异常，本次到达未记录；如有误判可申诉", 8L, true));
        AjaxResult blocked = arrive(NODE_LNG, NODE_LAT, "20");
        assertEquals(ArriveLocationGuardPolicy.SPEED, blocked.get("errorCode"));
        assertEquals(Boolean.FALSE, blocked.get("retryable"));
        assertEquals(Boolean.TRUE, blocked.get("appealable"));
        assertEquals("8", blocked.get("guardLogId"));
        verifyNoCompletion();
    }
}
