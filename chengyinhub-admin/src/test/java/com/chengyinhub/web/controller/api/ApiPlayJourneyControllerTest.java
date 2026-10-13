package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IPlayJourneyStoryService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /api/play/journey/{stage-end,ending} HTTP 合同:登录会员、参数、业务错误映射与委托。
 */
class ApiPlayJourneyControllerTest {

    private static final Long MEMBER = 71L;
    private static final Long TOPIC = 73L;
    private static final Long CHAPTER = 11L;

    private IPlayJourneyStoryService service;
    private ApiPlayJourneyController controller;

    @BeforeEach
    void setUp() {
        service = mock(IPlayJourneyStoryService.class);
        controller = spy(new ApiPlayJourneyController());
        doReturn(MEMBER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "playJourneyStoryService", service);
    }

    @Test
    void stageEndDelegatesToService() {
        Map<String, Object> view = Collections.<String, Object>singletonMap("open", "本章完");
        when(service.stageEnd(MEMBER, TOPIC, null, CHAPTER)).thenReturn(view);

        AjaxResult response = controller.stageEnd(TOPIC, null, CHAPTER);

        assertEquals(200, response.get("code"));
        assertEquals(view, response.get("data"));
    }

    @Test
    void endingDelegatesToService() {
        Map<String, Object> view = Collections.<String, Object>singletonMap("code", "correct");
        when(service.ending(MEMBER, TOPIC, null)).thenReturn(view);

        AjaxResult response = controller.ending(TOPIC, null);

        assertEquals(200, response.get("code"));
        assertEquals(view, response.get("data"));
    }

    @Test
    void businessErrorsBecomeErrorResult() {
        when(service.stageEnd(MEMBER, TOPIC, null, CHAPTER)).thenThrow(new ServiceException("本章节还有站点未完成"));
        when(service.ending(MEMBER, TOPIC, null)).thenThrow(new ServiceException("本局尚未完成"));

        assertEquals("本章节还有站点未完成", controller.stageEnd(TOPIC, null, CHAPTER).get("msg"));
        assertEquals(500, controller.stageEnd(TOPIC, null, CHAPTER).get("code"));
        assertEquals("本局尚未完成", controller.ending(TOPIC, null).get("msg"));
    }

    @Test
    void notLoggedInIsRejectedBeforeAnyServiceCall() {
        doReturn(0L).when(controller).getAppUserId();

        assertEquals("请先登录", controller.stageEnd(TOPIC, null, CHAPTER).get("msg"));
        assertEquals("请先登录", controller.ending(TOPIC, null).get("msg"));
        verify(service, never()).stageEnd(anyLong(), anyLong(), anyLong());
    }

    @Test
    void badParamsAreRejectedBeforeAnyServiceCall() {
        assertEquals("参数有误", controller.stageEnd(null, null, CHAPTER).get("msg"));
        assertEquals("参数有误", controller.stageEnd(TOPIC, null, 0L).get("msg"));
        assertEquals("参数有误", controller.ending(null, null).get("msg"));
        assertEquals("参数有误", controller.ending(0L, null).get("msg"));
        verify(service, never()).ending(anyLong(), anyLong());
    }
}
