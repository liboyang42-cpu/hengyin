package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.support.ActivityReviewPolicy;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-3:统一审核台是 TOPIC / ACTIVITY 审核决定的唯一写入口。
 */
class ContentAuditBypassControllerSafetyTest {

    /** Gap 12·03 P0-5 起后台编辑过分级审核 + 门禁:这里给「先发后审 + 门禁放行」,只测本类关心的闸。 */
    static void wireReviewGate(CmsActivityController controller) {
        ActivityReviewPolicy policy = mock(ActivityReviewPolicy.class);
        when(policy.modeOf(any(), any())).thenReturn(PublishSafetyRequest.ReviewMode.POST_REVIEW);
        IContentSafetyGate gate = mock(IContentSafetyGate.class);
        when(gate.decide(any())).thenReturn(SafetyDecision.pass());
        ReflectionTestUtils.setField(controller, "activityReviewPolicy", policy);
        ReflectionTestUtils.setField(controller, "contentSafetyGate", gate);
        SysUser user = new SysUser();
        user.setUserId(1L);
        user.setUserName("ops");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(user, new java.util.HashSet<>()), null, java.util.Collections.emptyList()));
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void activityEditStripsAuditAndPublishDecisionFields() {
        ICmsActivityService service = mock(ICmsActivityService.class);
        CmsActivityController controller = new CmsActivityController();
        ReflectionTestUtils.setField(controller, "cmsActivityService", service);
        wireReviewGate(controller);
        CmsActivity current = new CmsActivity();
        current.setId(9L);
        when(service.selectCmsActivityById(9L)).thenReturn(current);
        when(service.updateCmsActivityByAdmin(any(CmsActivity.class), any(), anyBoolean(), any())).thenReturn(1);

        CmsActivity request = new CmsActivity();
        request.setId(9L);
        request.setName("只改活动文案");
        request.setCheckStatus(1);
        request.setStatus(1L);
        request.setPublishStatus(1L);
        request.setReason("绕过统一审核台");

        controller.edit(request);

        ArgumentCaptor<CmsActivity> captor = ArgumentCaptor.forClass(CmsActivity.class);
        verify(service).updateCmsActivityByAdmin(captor.capture(), any(), anyBoolean(), isNull());
        CmsActivity saved = captor.getValue();
        assertEquals(9L, saved.getId());
        assertEquals("只改活动文案", saved.getName());
        assertNull(saved.getCheckStatus());
        assertNull(saved.getStatus());
        assertNull(saved.getPublishStatus());
        assertNull(saved.getReason());
    }

    @Test
    void activityEditWithOnlyAuditDecisionFailsClosed() {
        ICmsActivityService service = mock(ICmsActivityService.class);
        CmsActivityController controller = new CmsActivityController();
        ReflectionTestUtils.setField(controller, "cmsActivityService", service);
        CmsActivity request = new CmsActivity();
        request.setId(9L);
        request.setCheckStatus(2);
        request.setStatus(2L);
        request.setPublishStatus(0L);
        request.setReason("仅审核字段");

        AjaxResult result = controller.edit(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(service, never()).updateCmsActivity(any(CmsActivity.class));
        verify(service, never()).updateCmsActivityByAdmin(any(CmsActivity.class), any(), anyBoolean(), any());
    }
}
