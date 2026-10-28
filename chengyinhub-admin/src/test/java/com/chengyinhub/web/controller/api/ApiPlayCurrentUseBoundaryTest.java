package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 实际answer入口接原资格；普通免费报名与探索已购票分别验证，不制造统一付费要求。 */
class ApiPlayCurrentUseBoundaryTest {
    private ApiPlayProgressController controller;
    private CmsActivity activity;
    private CmsRegistration original,current;
    private CmsRegistrationMapper registrations;
    private IPlayerNodeProgressService progress;
    private ITopicRouteRuntimeService routes;
    @BeforeEach void setup() {
        controller=spy(new ApiPlayProgressController());doReturn(7L).when(controller).getAppUserId();
        CmsActivityMapper activities=mock(CmsActivityMapper.class);registrations=mock(CmsRegistrationMapper.class);
        ICmsActivityService activityService=mock(ICmsActivityService.class);
        ICmsTopicNodeService nodes=mock(ICmsTopicNodeService.class);ICmsTopicService topics=mock(ICmsTopicService.class);
        progress=mock(IPlayerNodeProgressService.class);routes=mock(ITopicRouteRuntimeService.class);
        activity=new CmsActivity();activity.setId(9L);activity.setTopicId(29L);
        activity.setStartDate(new Date(0L));activity.setEndDate(new Date(System.currentTimeMillis()+86400000L));
        when(activities.selectCmsActivityByIdForUpdate(9L)).thenReturn(activity);
        when(activityService.selectCmsActivityById(9L)).thenReturn(activity);
        CmsTopicNode node=new CmsTopicNode();node.setId(39L);node.setTopicId(29L);node.setStatus(1L);
        when(nodes.selectCmsTopicNodeById(39L)).thenReturn(node);
        CmsTopic topic=new CmsTopic();topic.setId(29L);topic.setProductType(1);
        when(topics.selectCmsTopicById(29L)).thenReturn(topic);
        when(routes.canPlay(anyLong(),anyLong(),anyLong(),anyLong(),nullable(String.class))).thenReturn(true);
        original=new CmsRegistration();original.setId(19L);original.setMemberId(7L);original.setOwnerType(2L);original.setOwnerId(9L);
        original.setRegistrationStatus(2);original.setPaymentStatus(2);original.setPurchaseKind(3);current=original;
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(original));
        when(registrations.selectCmsRegistrationByIdForUpdate(19L)).thenAnswer(call->current);
        com.chengyinhub.business.mapper.ClubLeadMapper noLead = mock(com.chengyinhub.business.mapper.ClubLeadMapper.class);
        when(noLead.selectByActivity(anyLong())).thenReturn(null);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"clubLeadMapper",noLead);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"currentPlayActivityMapper",activities);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"cmsRegistrationMapper",registrations);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"cmsActivityService",activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"cmsTopicNodeService",nodes);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"cmsTopicService",topics);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"playerNodeProgressService",progress);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"topicRouteRuntimeService",routes);
        ICmsRegistrationService registrationService=mock(ICmsRegistrationService.class);
        when(registrationService.selectCmsRegistrationList(any())).thenAnswer(call->registrations.selectCmsRegistrationList(call.getArgument(0)));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"cmsRegistrationService",registrationService);
        BasicPlayCommandFixtures.scope(controller,29L);
    }
    private AjaxResult answer() { return BasicPlayCommandFixtures.answer(controller, "9", "39", "answer", null, null, null); }
    @Test void expiredExploreOrderCannotReachOriginalAnswerMechanism() {
        activity.setEndDate(new Date(0L));AjaxResult result=answer();assertEquals(500,result.get("code"));
        assertTrue(String.valueOf(result.get("msg")).contains("结束"));verifyNoInteractions(progress);
    }
    @Test void missingEndRejectsAndCorrectionRestoresOriginalEntry() {
        activity.setEndDate(null);assertEquals(500,answer().get("code"));
        activity.setEndDate(new Date(System.currentTimeMillis()+86400000L));
        assertEquals("该节点未配置答题",answer().get("msg"));
    }
    @Test void refundedCurrentRowDoesNotUseOldPaidSnapshot() {
        current=new CmsRegistration();current.setId(19L);current.setMemberId(7L);current.setOwnerType(2L);current.setOwnerId(9L);
        current.setRegistrationStatus(2);current.setPaymentStatus(4);current.setPurchaseKind(3);
        assertEquals(500,answer().get("code"));verifyNoInteractions(progress);
    }
    @Test void ordinaryFreeRegistrationReachesSameOriginalAnswerEntry() {
        current.setPurchaseKind(1);current.setPaymentStatus(0);
        assertEquals("该节点未配置答题",answer().get("msg"));
        verify(registrations).selectCmsRegistrationByIdForUpdate(19L);
    }

    // 2026-10-14 用户已定：自玩通行证在本局内有效。已开局由路线运行时判定(含不建局的普通线性路线)。
    private void expiredSelfPlayPass() {
        current=new CmsRegistration();current.setId(19L);current.setMemberId(7L);current.setOwnerType(1L);current.setOwnerId(29L);
        current.setRegistrationStatus(2);current.setPaymentStatus(0);current.setPurchaseKind(2);current.setExpiresAt(new Date(1000L));
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(current));
    }
    private AjaxResult selfPlayAnswer() { return BasicPlayCommandFixtures.answer(controller, null, "39", "answer", "29"); }
    @Test void expiredSelfPlayPassStillFinishesARouteAlreadyStarted() {
        expiredSelfPlayPass();
        when(routes.hasRunningSession(7L,29L,0L)).thenReturn(true);
        assertEquals("该节点未配置答题",selfPlayAnswer().get("msg"),"已开局：中途到期仍可打完");
    }
    @Test void expiredSelfPlayPassCannotStartANewRoute() {
        expiredSelfPlayPass();
        when(routes.hasRunningSession(7L,29L,0L)).thenReturn(false);
        AjaxResult result=selfPlayAnswer();
        assertEquals(402,result.get("code"),"未开局：到期后不能新开局，仍回购票态 402");
    }
}
