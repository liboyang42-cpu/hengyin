package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.support.TemplateAdoptionService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** MC-025:ServiceException 没带 code 时回 500,不因 Integer 拆箱抛 NPE。 */
class ApiTemplateServiceExceptionCodeTest {

    private ApiTemplateController controller(TemplateAdoptionService adoption) {
        ApiTemplateController c = spy(new ApiTemplateController());
        doReturn(7L).when(c).getAppUserId();
        ReflectionTestUtils.setField(c, "templateAdoptionService", adoption);
        return c;
    }

    @Test
    void serviceExceptionWithoutCodeBecomes500() {
        TemplateAdoptionService adoption = mock(TemplateAdoptionService.class);
        when(adoption.source(any(), any(), any())).thenThrow(new ServiceException("来源模板不可用"));

        AjaxResult r = controller(adoption).adoptionSource("abc", null);

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("来源模板不可用", r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void serviceExceptionCodeIsKept() {
        TemplateAdoptionService adoption = mock(TemplateAdoptionService.class);
        when(adoption.source(any(), any(), any())).thenThrow(new ServiceException("无权", 403));

        assertEquals(403, controller(adoption).adoptionSource("abc", null).get(AjaxResult.CODE_TAG));
    }
}
