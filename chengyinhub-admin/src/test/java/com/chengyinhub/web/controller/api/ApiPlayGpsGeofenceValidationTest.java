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
 * R10-10:GPS 到达围栏必须对玩家输入与站点配置做有限数与经纬范围校验。
 *
 * <p>旧写法只 {@code Double.parseDouble} 后比较 {@code dist > 50};Java 接受 "NaN",
 * 而 {@code NaN > 50} 恒 false ⇒ 无效坐标绕过围栏直接 {@code advanceProgress} 发进度和奖励。
 * 本测试钉住:NaN/Infinity/越界/坏文本/缺失/站点配置非法全部拒绝且不落进度不发奖;
 * 合法近点仍通过、远点仍拒(围栏没被放宽)。
 */
class ApiPlayGpsGeofenceValidationTest
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
    private static final String VALID_ACCURACY = "15";

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
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "perkLedgerService", perkLedgerService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "shopDayProofService", mock(IShopDayProofService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicCompletionAwardService", completionAwardService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "pointsAwardService", pointsAwardService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerProgressionService", progressionService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "growthService", mock(IGrowthService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "xpBudgetService", mock(IXpBudgetService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "rewardDropService", mock(IRewardDropService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "smsCouponHistoryService", mock(ISmsCouponHistoryService.class));
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
        node.setLongitude(NODE_LNG);
        node.setLatitude(NODE_LAT);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        // 探店日票(EXPLORE_PASS)按已定规则缺结束时间暂禁新使用；本类验 GPS 围栏，夹具给未到期的结束时间。
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

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setName("GPS 围栏真源测试");
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

    /** 坐标判定路径必须带精度(O-01 已删空精度放行);本组用例只验坐标,精度给合法值 */
    private AjaxResult arrive(String longitude, String latitude)
    {
        return BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), longitude, latitude, null, null, null, VALID_ACCURACY);
    }

    private void verifyNoProgressAndNoReward()
    {
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), any());
        verify(progressionService, never()).onNodeDone(anyLong());
        verify(completionAwardService, never()).grantOnce(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void nanCoordinatesAreRejectedWithoutProgressOrReward()
    {
        AjaxResult result = arrive("NaN", "NaN");

        assertNotEquals(200, result.get("code"), "NaN 经纬度不得进入围栏后的推进");
        assertEquals("定位无效", result.get("msg"));
        verifyNoProgressAndNoReward();
    }

    @Test
    void infiniteCoordinatesAreRejectedWithoutProgressOrReward()
    {
        for (String[] pair : new String[][] {
                { "Infinity", "31.2304" },
                { "-Infinity", "31.2304" },
                { "121.4737", "Infinity" },
        }) {
            AjaxResult result = arrive(pair[0], pair[1]);
            assertNotEquals(200, result.get("code"), "Infinity 不得通过:" + pair[0] + "," + pair[1]);
        }
        verifyNoProgressAndNoReward();
    }

    @Test
    void outOfRangeCoordinatesAreRejectedWithoutProgressOrReward()
    {
        for (String[] pair : new String[][] {
                { "181", "31.2304" },
                { "-181", "31.2304" },
                { "121.4737", "91" },
                { "121.4737", "-91" },
        }) {
            AjaxResult result = arrive(pair[0], pair[1]);
            assertNotEquals(200, result.get("code"), "越界经纬度不得通过:" + pair[0] + "," + pair[1]);
            assertEquals("定位无效", result.get("msg"));
        }
        verifyNoProgressAndNoReward();
    }

    @Test
    void badTextAndMissingCoordinatesAreRejectedWithoutProgressOrReward()
    {
        assertNotEquals(200, arrive("abc", "31.2304").get("code"));
        assertNotEquals(200, arrive("121.4737", "3o.23").get("code"));
        assertNotEquals(200, arrive("", "31.2304").get("code"));
        assertNotEquals(200, arrive("121.4737", "").get("code"));
        assertEquals("请开启定位", arrive(null, null).get("msg"));
        verifyNoProgressAndNoReward();
    }

    @Test
    void invalidNodeCoordinatesAreRejectedWithoutProgressOrReward()
    {
        for (String[] pair : new String[][] {
                { "NaN", "31.2304" },
                { "121.4737", "Infinity" },
                { "200", "31.2304" },
                { "121.4737", "95" },
        }) {
            node.setLongitude(pair[0]);
            node.setLatitude(pair[1]);
            AjaxResult result = arrive(NODE_LNG, NODE_LAT);
            assertNotEquals(200, result.get("code"), "站点配置无效不得通过:" + pair[0] + "," + pair[1]);
        }
        node.setLongitude(NODE_LNG);
        node.setLatitude(NODE_LAT);
        verifyNoProgressAndNoReward();
    }

    @Test
    void legalNearCoordinatesStillAdvanceAndAward()
    {
        AjaxResult result = arrive(NODE_LNG, NODE_LAT);

        assertEquals(200, result.get("code"));
        verify(pointsAwardService).award(PLAYER, 12L, NODE, "节点打卡");
        verify(progressionService).onNodeDone(PLAYER);
    }

    @Test
    void farCoordinatesAreStillRejectedWithoutProgressOrReward()
    {
        AjaxResult result = arrive("121.6", "31.4");

        assertNotEquals(200, result.get("code"));
        assertEquals("还没到这一站附近", result.get("msg"));
        verifyNoProgressAndNoReward();
    }

    private AjaxResult arriveWithAccuracy(String accuracy)
    {
        return BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), NODE_LNG, NODE_LAT, null, null, null, accuracy);
    }

    /** O-01 终态:空精度不再放行,无论守卫是否装配、开关如何;拒绝在守卫之前,不落守卫表、不可申诉。 */
    @Test
    void blankAccuracyIsRejectedWithMachineCodeWithoutProgressOrReward()
    {
        for (String blank : new String[] { null, "", "  " }) {
            AjaxResult result = arriveWithAccuracy(blank);
            assertNotEquals(200, result.get("code"), "空精度不得放行:[" + blank + "]");
            assertEquals("ARRIVE_ACCURACY_MISSING", result.get("errorCode"));
            assertEquals(Boolean.FALSE, result.get("appealable"));
        }
        // 完整身份的合法命令省略精度时仍须命中原精度拒绝规则
        AjaxResult legacy = BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), NODE_LNG, NODE_LAT, null, null, null);
        assertEquals("ARRIVE_ACCURACY_MISSING", legacy.get("errorCode"));
        verifyNoProgressAndNoReward();
    }

    @Test
    void blankAccuracyIsRejectedBeforeGuardEvenWhenGuardInstalled()
    {
        com.chengyinhub.business.service.support.ArriveLocationGuard guard =
                mock(com.chengyinhub.business.service.support.ArriveLocationGuard.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "arriveLocationGuard", guard);

        AjaxResult result = arriveWithAccuracy(null);
        assertNotEquals(200, result.get("code"));
        assertEquals("ARRIVE_ACCURACY_MISSING", result.get("errorCode"));
        verify(guard, never()).check(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any());
        verifyNoProgressAndNoReward();
    }

    @Test
    void validAccuracyBehaviourIsUnchangedWithGuardInstalled()
    {
        com.chengyinhub.business.service.support.ArriveLocationGuard guard =
                mock(com.chengyinhub.business.service.support.ArriveLocationGuard.class);
        when(guard.check(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new com.chengyinhub.business.service.support.ArriveLocationGuard.Result(null, null, null, false));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "arriveLocationGuard", guard);

        assertEquals(200, arriveWithAccuracy("30").get("code"));
        verify(guard).check(PLAYER, ACTIVITY, TOPIC, NODE, Double.parseDouble(NODE_LAT), Double.parseDouble(NODE_LNG), 30d);
        verify(progressionService).onNodeDone(PLAYER);
    }

    @Test
    void guardRejectionReturnsAppealReferenceWithoutProgressOrReward()
    {
        com.chengyinhub.business.service.support.ArriveLocationGuard guard =
                mock(com.chengyinhub.business.service.support.ArriveLocationGuard.class);
        when(guard.check(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any()))
                .thenReturn(new com.chengyinhub.business.service.support.ArriveLocationGuard.Result(
                        "ARRIVE_GUARD_SPEED", "移动速度异常，本次到达未记录；如有误判可申诉", 9007199254740993L, true));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "arriveLocationGuard", guard);

        AjaxResult result = arriveWithAccuracy("20");
        assertNotEquals(200, result.get("code"));
        assertEquals("ARRIVE_GUARD_SPEED", result.get("errorCode"));
        assertEquals("9007199254740993", result.get("guardLogId"));
        assertEquals("0", result.get("routeSessionId"));
        assertEquals(String.valueOf(NODE), result.get("nodeId"));
        assertEquals(Boolean.TRUE, result.get("appealable"));
        assertEquals("play_arrive_guard", result.get("appealBizType"));
        verify(guard).check(PLAYER, ACTIVITY, TOPIC, NODE, Double.parseDouble(NODE_LAT), Double.parseDouble(NODE_LNG), 20d);
        verifyNoProgressAndNoReward();
    }

    @Test
    void guardIsNotConsultedWhenGeofenceAlreadyRejects()
    {
        com.chengyinhub.business.service.support.ArriveLocationGuard guard =
                mock(com.chengyinhub.business.service.support.ArriveLocationGuard.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "arriveLocationGuard", guard);

        assertEquals("还没到这一站附近", arrive("121.6", "31.4").get("msg"));
        assertEquals("请开启定位", arrive(null, null).get("msg"));
        verify(guard, never()).check(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any());
    }

    @Test
    void accuracyWithinLimitStillAdvances()
    {
        assertEquals(200, arriveWithAccuracy("30").get("code"));
        verify(progressionService).onNodeDone(PLAYER);
    }

    @Test
    void accuracyAboveLimitIsRejectedBeforeDistanceCheckWithoutProgressOrReward()
    {
        AjaxResult result = arriveWithAccuracy("81");

        assertNotEquals(200, result.get("code"));
        assertEquals("定位精度不足，请到开阔处或扫码完成", result.get("msg"));
        verifyNoProgressAndNoReward();
    }

    @Test
    void invalidAccuracyIsRejectedAsInvalidLocation()
    {
        for (String bad : new String[] { "NaN", "Infinity", "-1", "abc" }) {
            AjaxResult result = arriveWithAccuracy(bad);
            assertNotEquals(200, result.get("code"), "非法精度不得通过:" + bad);
            assertEquals("定位无效", result.get("msg"));
            assertEquals("ARRIVE_ACCURACY_INVALID", result.get("errorCode"));
            assertEquals(Boolean.FALSE, result.get("appealable"));
        }
        verifyNoProgressAndNoReward();
    }

    @Test
    void accuracyIsIgnoredWhenNodeHasNoCoordinate()
    {
        node.setLongitude(null);
        node.setLatitude(null);

        assertEquals(200, BasicPlayCommandFixtures.arrive(controller, String.valueOf(ACTIVITY), String.valueOf(NODE), null, null, null, null, null, "500").get("code"), "无坐标节点不走坐标判定,精度闸不适用");
    }

    private void stubGpsValidationNode()
    {
        node.setTemplateId(60L);
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setValidationMethod(5);
        when(templateService.selectCmsMemberTemplateById(60L)).thenReturn(tpl);
    }

    @Test
    void gpsValidationNodeMissingSiteCoordinateIsRejectedWithoutProgressOrReward()
    {
        stubGpsValidationNode();

        // 经、纬都缺
        node.setLongitude(null);
        node.setLatitude(null);
        AjaxResult both = arrive(NODE_LNG, NODE_LAT);
        assertNotEquals(200, both.get("code"), "vm=5 缺经纬不得当普通线上节点放行");
        assertEquals("站点未设坐标，暂时无法打卡", both.get("msg"));

        // 只缺纬
        node.setLongitude(NODE_LNG);
        node.setLatitude(null);
        AjaxResult latOnly = arrive(NODE_LNG, NODE_LAT);
        assertNotEquals(200, latOnly.get("code"), "vm=5 只缺纬同样拒绝");

        // 只缺经
        node.setLongitude(null);
        node.setLatitude(NODE_LAT);
        AjaxResult lngOnly = arrive(NODE_LNG, NODE_LAT);
        assertNotEquals(200, lngOnly.get("code"), "vm=5 只缺经同样拒绝");

        verifyNoProgressAndNoReward();
    }

    @Test
    void gpsValidationNodeWithSiteCoordinateAndNearPlayerStillAdvances()
    {
        stubGpsValidationNode();

        AjaxResult result = arrive(NODE_LNG, NODE_LAT);

        assertEquals(200, result.get("code"), "vm=5 有站点坐标且玩家在附近必须放行");
        verify(pointsAwardService).award(PLAYER, 12L, NODE, "节点打卡");
        verify(progressionService).onNodeDone(PLAYER);
    }

    @Test
    void gpsValidationNodeWithFarPlayerIsStillRejected()
    {
        stubGpsValidationNode();

        AjaxResult result = arrive("121.6", "31.4");

        assertNotEquals(200, result.get("code"));
        assertEquals("还没到这一站附近", result.get("msg"));
        verifyNoProgressAndNoReward();
    }

    @Test
    void plainOnlineNodeWithoutCoordinatesStillArrives()
    {
        // 正控:普通无验证(未配模板)的纯线上节点没有坐标不是漏配,不能被 vm=5 规则误禁。
        node.setTemplateId(null);
        node.setLongitude(null);
        node.setLatitude(null);

        AjaxResult result = arrive(null, null);

        assertEquals(200, result.get("code"));
        verify(pointsAwardService).award(PLAYER, 12L, NODE, "节点打卡");
    }

    @Test
    void danglingTemplateIdNeverFallsThroughToArriveCompletion()
    {
        // G-R1:templateId 指向的模板行读不到(悬空引用)时,不能因 arriveValidationMethod=null
        // 就当普通线上节点放行;vm=5 节点更是缺坐标也照样完成。缺合同必须清楚失败且不推进。
        node.setTemplateId(61L);
        when(templateService.selectCmsMemberTemplateById(61L)).thenReturn(null);
        node.setLongitude(null);
        node.setLatitude(null);

        AjaxResult result = arrive(NODE_LNG, NODE_LAT);

        assertEquals(500, result.get("code"));
        assertEquals("该站配置异常，暂时无法打卡", result.get("msg"));
        verifyNoProgressAndNoReward();
    }
}
