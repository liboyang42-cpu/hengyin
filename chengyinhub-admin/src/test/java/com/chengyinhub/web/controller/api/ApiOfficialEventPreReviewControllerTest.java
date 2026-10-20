package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.IOfficialEventService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 官方活动先审后发(2026-10-20):控制器侧白名单不变、文本门禁 PRE_REVIEW、通过后只落待审。 */
class ApiOfficialEventPreReviewControllerTest {

    private IOfficialEventService v1;
    private IOfficialEventV2Service v2;
    private IContentSafetyGate gate;
    private IWxContentSecurityService wxSec;
    private ApiOfficialEventController controller;

    @BeforeEach
    void setUp() {
        v1 = mock(IOfficialEventService.class);
        v2 = mock(IOfficialEventV2Service.class);
        gate = mock(IContentSafetyGate.class);
        wxSec = mock(IWxContentSecurityService.class);
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectConfigByKey("official_publish_whitelist")).thenReturn("101");
        IUmsMemberService members = mock(IUmsMemberService.class);
        UmsMember m = new UmsMember();
        m.setWxOpenId("openid-101");
        when(members.selectUmsMemberById(101L)).thenReturn(m);
        when(gate.decide(any())).thenReturn(SafetyDecision.pass());
        controller = spy(new ApiOfficialEventController());
        ReflectionTestUtils.setField(controller, "officialEventService", v1);
        ReflectionTestUtils.setField(controller, "officialEventV2Service", v2);
        ReflectionTestUtils.setField(controller, "configService", config);
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        ReflectionTestUtils.setField(controller, "contentSafetyGate", gate);
        ReflectionTestUtils.setField(controller, "wxSec", wxSec);
    }

    @Test
    void v1CleanPublishGoesToReviewNotStraightLive() {
        doReturn(101L).when(controller).getAppUserId();
        OfficialEvent body = event();
        OfficialEvent saved = event();
        saved.setId(42L);
        when(v1.submitForReview(body, 101L)).thenReturn(saved);

        AjaxResult result = controller.publish(body);

        assertTrue(result.isSuccess());
        assertEquals("已提交审核，审核通过后公开", result.get(AjaxResult.MSG_TAG));
        assertEquals(42L, result.get(AjaxResult.DATA_TAG));
        verify(v1).submitForReview(body, 101L);
        verify(v1, never()).publish(any(), anyLong());
        ArgumentCaptor<PublishSafetyRequest> safety = ArgumentCaptor.forClass(PublishSafetyRequest.class);
        verify(gate).decide(safety.capture());
        assertEquals(PublishSafetyRequest.ReviewMode.PRE_REVIEW.name(), safety.getValue().getReviewMode());
        assertEquals(AuditTask.BIZ_OFFICIAL_EVENT, safety.getValue().getContentType());
        assertEquals("openid-101", safety.getValue().getOpenid());
        assertTrue(safety.getValue().getText().contains("官方城市漫游"));
    }

    @Test
    void v1RiskyTextIsRefused() {
        doReturn(101L).when(controller).getAppUserId();
        when(gate.decide(any())).thenReturn(SafetyDecision.block("TEXT_RISK"));

        AjaxResult result = controller.publish(event());

        assertFalse(result.isSuccess());
        assertEquals("活动内容包含违规信息，请修改后重试", result.get(AjaxResult.MSG_TAG));
        verify(v1, never()).submitForReview(any(), anyLong());
    }

    @Test
    void v1NonWhitelistedStillRefusedBeforeGate() {
        doReturn(202L).when(controller).getAppUserId();

        AjaxResult result = controller.publish(event());

        assertEquals("无官方发布权限", result.get(AjaxResult.MSG_TAG));
        verify(gate, never()).decide(any());
        verify(v1, never()).submitForReview(any(), anyLong());
    }

    @Test
    void v2ScheduleSuccessReportsSubmittedForReview() {
        doReturn(101L).when(controller).getAppUserId();
        when(v2.schedule(7L, 101L)).thenReturn(null);

        AjaxResult result = controller.scheduleV2(7L);

        assertTrue(result.isSuccess());
        assertEquals("已提交审核，审核通过后进入排期", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void v2NonWhitelistedStillRefused() {
        doReturn(202L).when(controller).getAppUserId();

        AjaxResult result = controller.scheduleV2(7L);

        assertEquals("无官方发布权限", result.get(AjaxResult.MSG_TAG));
        verify(v2, never()).schedule(anyLong(), anyLong());
    }

    private OfficialEvent event() {
        OfficialEvent e = new OfficialEvent();
        e.setTitle("官方城市漫游");
        e.setStory("打卡三个地标");
        return e;
    }
}
