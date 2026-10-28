package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayCheckinCodeService;
import com.chengyinhub.business.service.IPlayCheckinCodeService.ConsumeResult;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 游戏打卡一次性码：已用过/已过期立即失败；无效签名才回落旧静态码。 */
class ApiPlayCheckinLiveCodeTest {

    private static final Long PLAYER = 41L;
    private static final Long NODE = 77L;
    private static final String LIVE = "v1.77.play_checkin.1.2.sig";
    private static final String STATIC = "STORE-CODE";

    private IPlayCheckinCodeService liveCodes;
    private ICmsTopicNodeService nodeService;
    private ICmsActivityService activityService;
    private ApiPlayProgressController controller;

    @BeforeEach
    void setUp() {
        liveCodes = mock(IPlayCheckinCodeService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        activityService = mock(ICmsActivityService.class);
        controller = spy(new ApiPlayProgressController());
        doReturn(PLAYER).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playCheckinCodeService", liveCodes);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        CmsTopicNode target = new CmsTopicNode(); target.setId(NODE); target.setTopicId(44L);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(target);
        CmsActivity activity = new CmsActivity(); activity.setId(43L); activity.setTopicId(44L);
        when(activityService.selectCmsActivityById(43L)).thenReturn(activity);
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(ticket()));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrations);
        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller, 44L);
    }

    private static CmsRegistration ticket() {
        CmsRegistration row = new CmsRegistration(); row.setId(91L); row.setMemberId(PLAYER);
        row.setOwnerType(2L); row.setOwnerId(43L); row.setRegistrationStatus(2); row.setPaymentStatus(2);
        return row;
    }

    @Test void sharedRouteScanOnlyRecordsArrivalAndNeverRunsPersonalAwardChain() {
        com.chengyinhub.business.service.IGameSessionRuntimeService runtime=mock(com.chengyinhub.business.service.IGameSessionRuntimeService.class);
        com.chengyinhub.business.service.IShopDayProofService proofs=mock(com.chengyinhub.business.service.IShopDayProofService.class);
        com.chengyinhub.business.service.ICmsTopicService topics=mock(com.chengyinhub.business.service.ICmsTopicService.class);
        com.chengyinhub.business.service.ITopicRouteRuntimeService routes=mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        ICmsRegistrationService registrations=mock(ICmsRegistrationService.class);IPlayerNodeProgressService progress=mock(IPlayerNodeProgressService.class);ISmsCouponHistoryService coupons=mock(ISmsCouponHistoryService.class);
        for(Object[] binding:new Object[][]{{"gameSessionRuntimeService",runtime},{"shopDayProofService",proofs},{"cmsTopicService",topics},{"topicRouteRuntimeService",routes},{"cmsRegistrationService",registrations},{"playerNodeProgressService",progress},{"smsCouponHistoryService",coupons}})com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,(String)binding[0],binding[1]);
        CmsTopicNode node=new CmsTopicNode();node.setId(NODE);node.setTopicId(44L);node.setStatus(1L);node.setMerchantMemberId(310L);node.setNodeAuditStatus(1);when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        CmsActivity activity=new CmsActivity();activity.setId(43L);activity.setTopicId(44L);activity.setStartDate(new Date(System.currentTimeMillis()-60000));activity.setEndDate(new Date(System.currentTimeMillis()+60000));when(activityService.selectCmsActivityById(43L)).thenReturn(activity);
        CmsRegistration registration=ticket();when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(registration));
        com.chengyinhub.business.mapper.CmsRegistrationMapper registrationMapper=mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class);com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"cmsRegistrationMapper",registrationMapper);
        CurrentPlayControllerFixtures.delegate(registrationMapper, controller); // C包新使用资格走当前锁读
        when(registrationMapper.markFirstSuccessfulScan(any(),any(),any(),any(),any())).thenReturn(1);
        com.chengyinhub.business.domain.CmsTopic topic=new com.chengyinhub.business.domain.CmsTopic();topic.setId(44L);topic.setProductType(1);when(topics.selectCmsTopicById(44L)).thenReturn(topic);
        when(routes.queryNodeContentSnapshot(PLAYER,44L,43L,NODE)).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("runtimeInstance","shared-fixture").put("authorityEpoch",4));
        when(runtime.routeSessionStatus(43L)).thenReturn("RUNNING");when(routes.canPlay(any(),any(),any(),any(),any())).thenReturn(true);
        when(runtime.sharedRouteSnapshot(PLAYER,44L,43L,NODE)).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("teamId",88).put("runtimeInstance","shared-fixture").put("authorityEpoch",4));
        com.chengyinhub.business.domain.GameSession original = new com.chengyinhub.business.domain.GameSession(); original.setId(70L); original.setRevision(2);
        when(((com.chengyinhub.business.mapper.GameSessionRuntimeMapper)com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller,"playCommandReceiptMapper")).selectSessionByActivity(43L)).thenReturn(original);
        when(((com.chengyinhub.business.mapper.GameSessionRuntimeMapper)com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller,"playCommandReceiptMapper")).selectSessionByActivityForUpdate(43L)).thenReturn(original);
        when(liveCodes.inspect(LIVE)).thenReturn(ConsumeResult.ok(NODE));when(liveCodes.consume(LIVE)).thenReturn(ConsumeResult.ok(NODE));
        com.chengyinhub.business.domain.PlayerNodeProgress proof=new com.chengyinhub.business.domain.PlayerNodeProgress();proof.setStatus(0);proof.setCheckinTime(new Date());when(proofs.markArrived(PLAYER,43L,node)).thenReturn(proof);
        AjaxResult response=BasicPlayCommandFixtures.checkin(controller,"43",LIVE,null,null,null,91L,String.valueOf(NODE));assertEquals(200,response.get(AjaxResult.CODE_TAG));assertEquals(Boolean.TRUE,((java.util.Map<?,?>)response.get("data")).get("arrived"));assertEquals(Boolean.FALSE,((java.util.Map<?,?>)response.get("data")).get("done"));
        verify(liveCodes).consume(LIVE);verify(proofs).markArrived(PLAYER,43L,node);verifyNoInteractions(progress,coupons);
    }

    @Test
    void usedLiveCodeDoesNotFallBackToStaticLookup() {
        when(liveCodes.inspect(LIVE)).thenReturn(ConsumeResult.used());

        AjaxResult r = BasicPlayCommandFixtures.checkin(controller,"43",LIVE,null,null,null,null,String.valueOf(NODE));

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("这张码已用过", r.get(AjaxResult.MSG_TAG));
        verify(nodeService, never()).selectCmsTopicNodeList(any());
        verify(nodeService).selectCmsTopicNodeById(NODE); // 原目标身份预检，不能再查静态码
    }

    @Test
    void expiredLiveCodeDoesNotFallBackToStaticLookup() {
        when(liveCodes.inspect(LIVE)).thenReturn(ConsumeResult.expired());

        AjaxResult r = BasicPlayCommandFixtures.checkin(controller,"43",LIVE,null,null,null,null,String.valueOf(NODE));

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("这张码已过期", r.get(AjaxResult.MSG_TAG));
        verify(nodeService, never()).selectCmsTopicNodeList(any());
    }

    @Test
    void unsignedStaticCodeStillResolvesByCheckinCode() {
        when(liveCodes.inspect(STATIC)).thenReturn(ConsumeResult.invalid());
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(java.util.Collections.emptyList());

        AjaxResult r = BasicPlayCommandFixtures.checkin(controller,"43",STATIC,null,null,null,null,String.valueOf(NODE));

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("无效的打卡码", r.get(AjaxResult.MSG_TAG));
        verify(nodeService).selectCmsTopicNodeList(any());
    }

    @Test
    void missingActivityRejectsOriginalTargetBeforeCodeConsumption() {
        when(liveCodes.inspect(LIVE)).thenReturn(ConsumeResult.ok(NODE));
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(44L);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(activityService.selectCmsActivityById(43L)).thenReturn(null);

        AjaxResult r = BasicPlayCommandFixtures.checkin(controller,"43",LIVE,null,null,null,null,String.valueOf(NODE));

        assertEquals("该节点不属于本次活动", r.get(AjaxResult.MSG_TAG));
        verify(liveCodes, never()).consume(any());
        verify(nodeService).selectCmsTopicNodeById(NODE);
        verify(nodeService, never()).selectCmsTopicNodeList(any());
    }

    @Test
    void activityEndTimeRejectsScanBeforeCodeConsumptionOrWrites() {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        IPlayerNodeProgressService progress = mock(IPlayerNodeProgressService.class);
        ISmsCouponHistoryService coupons = mock(ISmsCouponHistoryService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrations);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progress);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "smsCouponHistoryService", coupons);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(44L);
        when(liveCodes.inspect(LIVE)).thenReturn(ConsumeResult.ok(NODE));
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        CmsRegistration registration = ticket();
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(registration));
        CmsActivity activity = new CmsActivity();
        activity.setId(43L);
        activity.setTopicId(44L);
        long now = System.currentTimeMillis();
        activity.setStartDate(new Date(now - 3600000L));
        activity.setEndDate(new Date(now + 60000L));
        assertNull(com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "activityTimeError", activity));
        activity.setEndDate(new Date(now - 1000L));
        assertEquals("活动已结束", com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "activityTimeError", activity));
        when(activityService.selectCmsActivityById(43L)).thenReturn(activity);

        AjaxResult r = BasicPlayCommandFixtures.checkin(controller,"43",LIVE,null,null,null,91L,String.valueOf(NODE));

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("活动已结束", r.get(AjaxResult.MSG_TAG));
        verify(liveCodes, never()).consume(any());
        verify(registrations, org.mockito.Mockito.times(2)).selectCmsRegistrationList(any());
        verifyNoMoreInteractions(registrations);
        verifyNoInteractions(progress, coupons);
    }

    /**
     * 第二轮拍板第 6 条:扫商家游戏码的人必须是该活动的有效报名玩家。
     * 非报名人拿到码(截图转发/路过扫)→ 拒绝,不推进、不发券。
     */
    @Test
    void nonRegistrantScanningMerchantGameCodeGetsNoCoupon() {
        com.chengyinhub.business.service.ICmsRegistrationService registrations =
                mock(com.chengyinhub.business.service.ICmsRegistrationService.class);
        com.chengyinhub.business.service.ISmsCouponHistoryService coupons =
                mock(com.chengyinhub.business.service.ISmsCouponHistoryService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrations);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "smsCouponHistoryService", coupons);
        when(liveCodes.inspect(LIVE)).thenReturn(ConsumeResult.ok(NODE));
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(44L);
        node.setTemplateId(707L);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setId(43L);
        activity.setTopicId(44L);
        when(activityService.selectCmsActivityById(43L)).thenReturn(activity);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(java.util.Collections.emptyList());

        AjaxResult r = BasicPlayCommandFixtures.checkin(controller,"43",LIVE,null,null,null,null,String.valueOf(NODE));

        assertEquals("无权读取本次游玩命令", r.get(AjaxResult.MSG_TAG));
        verify(liveCodes, never()).consume(any());
        org.mockito.ArgumentCaptor<com.chengyinhub.business.domain.CmsRegistration> probe =
                org.mockito.ArgumentCaptor.forClass(com.chengyinhub.business.domain.CmsRegistration.class);
        verify(registrations).selectCmsRegistrationList(probe.capture());
        assertEquals(PLAYER, probe.getValue().getMemberId(), "报名闸查的是扫码玩家本人");
        assertEquals(Long.valueOf(43L), probe.getValue().getOwnerId());
        verify(coupons, never()).grantCouponById(any(), any());
    }
}
