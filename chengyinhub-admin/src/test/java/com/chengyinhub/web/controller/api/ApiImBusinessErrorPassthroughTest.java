package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IImService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/** 3-22:私信业务异常(拉黑、违规等)要把后端原话给用户;非业务异常仍只给通用文案,不泄露内部信息。 */
class ApiImBusinessErrorPassthroughTest {
    private IImService service;
    private ApiImController controller;

    @BeforeEach
    void setUp() {
        service = mock(IImService.class);
        controller = spy(new ApiImController());
        doReturn(42L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "imService", service);
    }

    @Test
    void blockReportAndStartPassBusinessMessagesThrough() {
        doThrow(new ServiceException("参数有误")).when(service).block(42L, 42L);
        assertEquals("参数有误", controller.block("42").get(AjaxResult.MSG_TAG));

        doThrow(new ServiceException("消息不存在")).when(service).report(42L, 9L, "用户举报");
        assertEquals("消息不存在", controller.report("9", "用户举报").get(AjaxResult.MSG_TAG));

        when(service.startSingle(42L, 7L)).thenThrow(new ServiceException("对方暂不可联系"));
        AjaxResult start = controller.start("7");
        assertEquals(500, start.get(AjaxResult.CODE_TAG));
        assertEquals("对方暂不可联系", start.get(AjaxResult.MSG_TAG));
    }

    @Test
    void unexpectedFailuresStillUseTheGenericMessage() {
        doThrow(new IllegalStateException("jdbc url leaked")).when(service).block(42L, 7L);
        assertEquals("操作失败，请稍后重试", controller.block("7").get(AjaxResult.MSG_TAG));
    }
}
