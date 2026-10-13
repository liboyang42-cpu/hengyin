package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IPlayJourneyReadService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * /api/play/journey 只读接口的登录闸与业务错误透出;读侧口径由 service 单测覆盖。
 */
class ApiPlayJourneyReadControllerTest {

    private static final long MEMBER = 71L;
    private static final long TOPIC = 73L;

    private ApiPlayJourneyReadController controller;
    private IPlayJourneyReadService service;

    @BeforeEach
    void setUp() {
        service = mock(IPlayJourneyReadService.class);
        controller = spy(new ApiPlayJourneyReadController());
        ReflectionTestUtils.setField(controller, "playJourneyReadService", service);
        doReturn(MEMBER).when(controller).getAppUserId();
    }

    @Test
    void casebookPassesTopicIdThrough() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("topicId", TOPIC);
        when(service.casebook(MEMBER, TOPIC, null)).thenReturn(data);

        AjaxResult response = controller.casebook(String.valueOf(TOPIC), null);

        assertEquals(200, response.get("code"));
        assertEquals(data, response.get("data"));
    }

    @Test
    void backpackRejectsAnonymous() {
        doReturn(null).when(controller).getAppUserId();

        AjaxResult response = controller.backpack(String.valueOf(TOPIC), null);

        assertEquals(500, response.get("code"));
        assertEquals("请先登录", response.get("msg"));
        verifyNoInteractions(service);
    }

    @Test
    void casebookSurfacesServiceBusinessError() {
        doThrow(new ServiceException("这一局还没开始")).when(service).casebook(anyLong(), anyLong(), org.mockito.ArgumentMatchers.isNull());

        AjaxResult response = controller.casebook(String.valueOf(TOPIC), null);

        assertEquals(500, response.get("code"));
        assertEquals("这一局还没开始", response.get("msg"));
    }

    @Test
    void casebookTreatsBlankTopicIdAsZero() {
        doThrow(new ServiceException("参数有误")).when(service).casebook(MEMBER, 0L, null);

        AjaxResult response = controller.casebook(null, null);

        assertEquals(500, response.get("code"), "topicId 缺失走业务错误,而不是 NPE");
        verify(service).casebook(MEMBER, 0L, null);
    }
}
