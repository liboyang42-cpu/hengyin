package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ServiceException 默认构造没有 code (getCode() == null)。
 * 控制器 catch (ServiceException e) 时绝不能直接拆箱 new AjaxResult(e.getCode(), e.getMessage())，
 * 否则抛 NPE 并被外层/全局兜底吞成「系统繁忙，请稍后重试」。
 */
class ApiTemplateControllerServiceExceptionTest {

    @Test
    void deleteTemplateReturnsServiceExceptionMessageWhenCodeIsNull() throws Exception {
        ITemplatePublishService publishService = mock(ITemplatePublishService.class);
        when(publishService.deleteTemplateWithSync(6001L, 9003L))
                .thenThrow(new ServiceException("模板不存在"));

        ApiTemplateController controller = new ApiTemplateController() {
            @Override
            public Long getAppUserId() {
                return 9003L;
            }
        };
        ReflectionTestUtils.setField(controller, "templatePublishService", publishService);

        AjaxResult direct = controller.deleteTemplate("6001", null);
        assertEquals(500, direct.get(AjaxResult.CODE_TAG));
        assertEquals("模板不存在", direct.get(AjaxResult.MSG_TAG));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        mockMvc.perform(post("/api/template/delete").param("template_id", "6001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("模板不存在"));
    }
}
