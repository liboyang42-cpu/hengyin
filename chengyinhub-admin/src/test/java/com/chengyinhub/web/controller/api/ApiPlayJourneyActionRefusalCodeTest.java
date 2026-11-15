package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * SMOKE-500-R4：剧情动作的业务拒绝按类型回码（参数 400、状态 409），文案原样；
 * completeOpening 的状态类拒绝维持原 500，避免客户端把它当成路线冲突而重载。
 */
class ApiPlayJourneyActionRefusalCodeTest
{
    private ApiPlayJourneyActionController controller;
    private ITopicRouteRuntimeService runtime;

    @BeforeEach
    void setUp()
    {
        controller = spy(new ApiPlayJourneyActionController());
        doReturn(7L).when(controller).getAppUserId();
        runtime = mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(controller, "topicRouteRuntimeService", runtime);
    }

    private static void assertRefusal(AjaxResult r, int code, String msg)
    {
        assertEquals(code, r.get(AjaxResult.CODE_TAG));
        assertEquals(msg, r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void askWithMissingTopicIs400()
    {
        when(runtime.ask(7L, 0L, null, 992021L, "smoke")).thenThrow(new IllegalArgumentException("参数有误"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodeId", 992021L);
        body.put("questionId", "smoke");

        assertRefusal(controller.ask(body), HttpStatus.BAD_REQUEST, "参数有误");
    }

    @Test
    void thoughtSyncWithoutPassIs409()
    {
        when(runtime.syncThoughts(anyLong(), anyLong(), anyLong(), any(), anyInt(), anyLong()))
                .thenThrow(new IllegalStateException("请先购买自玩通行证"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topicId", 992001L);
        body.put("claim", List.of("smoke"));

        assertRefusal(controller.syncThoughts(body), HttpStatus.CONFLICT, "请先购买自玩通行证");
    }

    @Test
    void openingMismatchIs400ButStateRefusalKeepsOriginalCode()
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topicId", 992001L); body.put("activityId", 992034L);
        body.put("chapterId", 992011L); body.put("expectedVersion", 0);
        when(runtime.completeOpening(7L, 992001L, 992034L, 992011L, 0))
                .thenThrow(new IllegalArgumentException("开局流程不匹配"));
        assertRefusal(controller.completeOpening(body), HttpStatus.BAD_REQUEST, "开局流程不匹配");

        org.mockito.Mockito.doThrow(new IllegalStateException("本章开局已完成"))
                .when(runtime).completeOpening(7L, 992001L, 992034L, 992011L, 0);
        assertRefusal(controller.completeOpening(body), HttpStatus.ERROR, "本章开局已完成");
    }
}
