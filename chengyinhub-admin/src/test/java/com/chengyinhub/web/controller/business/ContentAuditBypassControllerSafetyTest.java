package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-3:统一审核台是 TOPIC / ACTIVITY 审核决定的唯一写入口。
 */
class ContentAuditBypassControllerSafetyTest {

    @Test
    void activityEditStripsAuditAndPublishDecisionFields() {
        ICmsActivityService service = mock(ICmsActivityService.class);
        CmsActivityController controller = new CmsActivityController();
        ReflectionTestUtils.setField(controller, "cmsActivityService", service);
        when(service.updateCmsActivity(any(CmsActivity.class))).thenReturn(1);

        CmsActivity request = new CmsActivity();
        request.setId(9L);
        request.setName("只改活动文案");
        request.setCheckStatus(1);
        request.setStatus(1L);
        request.setPublishStatus(1L);
        request.setReason("绕过统一审核台");

        controller.edit(request);

        ArgumentCaptor<CmsActivity> captor = ArgumentCaptor.forClass(CmsActivity.class);
        verify(service).updateCmsActivity(captor.capture());
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
    }
}
