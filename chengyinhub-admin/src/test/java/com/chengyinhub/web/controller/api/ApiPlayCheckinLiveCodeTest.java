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
        ReflectionTestUtils.setField(controller, "playCheckinCodeService", liveCodes);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        CurrentPlayControllerFixtures.install(controller);
    }

    @Test
    void usedLiveCodeDoesNotFallBackToStaticLookup() {
        when(liveCodes.inspect(LIVE)).thenReturn(ConsumeResult.used());

        AjaxResult r = controller.checkin("43", LIVE, null);

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("这张码已用过", r.get(AjaxResult.MSG_TAG));
        verify(nodeService, never()).selectCmsTopicNodeList(any());
        verify(nodeService, never()).selectCmsTopicNodeById(any());
    }

    @Test
    void expiredLiveCodeDoesNotFallBackToStaticLookup() {
        when(liveCodes.inspect(LIVE)).thenReturn(ConsumeResult.expired());

        AjaxResult r = controller.checkin("43", LIVE, null);

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("这张码已过期", r.get(AjaxResult.MSG_TAG));
        verify(nodeService, never()).selectCmsTopicNodeList(any());
    }

    @Test
    void unsignedStaticCodeStillResolvesByCheckinCode() {
        when(liveCodes.inspect(STATIC)).thenReturn(ConsumeResult.invalid());
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(java.util.Collections.emptyList());

        AjaxResult r = controller.checkin("43", STATIC, null);

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("无效的打卡码", r.get(AjaxResult.MSG_TAG));
        verify(nodeService).selectCmsTopicNodeList(any());
    }

    @Test
    void consumedLiveCodeLoadsNodeByIdNotByStaticCode() {
        when(liveCodes.inspect(LIVE)).thenReturn(ConsumeResult.ok(NODE));
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(44L);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(activityService.selectCmsActivityById(43L)).thenReturn(null);

        AjaxResult r = controller.checkin("43", LIVE, null);

        assertEquals("活动不存在", r.get(AjaxResult.MSG_TAG));
        verify(liveCodes, never()).consume(any());
        verify(nodeService).selectCmsTopicNodeById(NODE);
        verify(nodeService, never()).selectCmsTopicNodeList(any());
    }

    @Test
    void activityEndTimeRejectsScanBeforeCodeConsumptionOrWrites() {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        IPlayerNodeProgressService progress = mock(IPlayerNodeProgressService.class);
        ISmsCouponHistoryService coupons = mock(ISmsCouponHistoryService.class);
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", registrations);
        ReflectionTestUtils.setField(controller, "playerNodeProgressService", progress);
        ReflectionTestUtils.setField(controller, "smsCouponHistoryService", coupons);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(44L);
        when(liveCodes.inspect(LIVE)).thenReturn(ConsumeResult.ok(NODE));
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        CmsRegistration registration = new CmsRegistration();
        registration.setId(91L);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(registration));
        CmsActivity activity = new CmsActivity();
        activity.setId(43L);
        activity.setTopicId(44L);
        long now = System.currentTimeMillis();
        activity.setStartDate(new Date(now - 3600000L));
        activity.setEndDate(new Date(now + 60000L));
        assertNull(ReflectionTestUtils.invokeMethod(controller, "activityTimeError", activity));
        activity.setEndDate(new Date(now - 1000L));
        assertEquals("活动已结束", ReflectionTestUtils.invokeMethod(controller, "activityTimeError", activity));
        when(activityService.selectCmsActivityById(43L)).thenReturn(activity);

        AjaxResult r = controller.checkin("43", LIVE, null, null, null, 91L);

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("活动已结束", r.get(AjaxResult.MSG_TAG));
        verify(liveCodes, never()).consume(any());
        verify(registrations).selectCmsRegistrationList(any());
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
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", registrations);
        ReflectionTestUtils.setField(controller, "smsCouponHistoryService", coupons);
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

        AjaxResult r = controller.checkin("43", LIVE, null);

        assertEquals("请先报名并完成支付", r.get(AjaxResult.MSG_TAG));
        verify(liveCodes, never()).consume(any());
        org.mockito.ArgumentCaptor<com.chengyinhub.business.domain.CmsRegistration> probe =
                org.mockito.ArgumentCaptor.forClass(com.chengyinhub.business.domain.CmsRegistration.class);
        verify(registrations).selectCmsRegistrationList(probe.capture());
        assertEquals(PLAYER, probe.getValue().getMemberId(), "报名闸查的是扫码玩家本人");
        assertEquals(Long.valueOf(43L), probe.getValue().getOwnerId());
        verify(coupons, never()).grantCouponById(any(), any());
    }
}
