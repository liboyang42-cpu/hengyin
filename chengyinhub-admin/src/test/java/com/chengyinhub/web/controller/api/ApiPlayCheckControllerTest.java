package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 检定四端点只做登录解析与转发;幂等与前置都在服务端。 */
class ApiPlayCheckControllerTest {
    private static final long MEMBER = 7L, TOPIC = 10L, NODE = 1L;

    private final ITopicRouteRuntimeService service = mock(ITopicRouteRuntimeService.class);
    private final ApiPlayCheckController controller = spy(new ApiPlayCheckController());

    @BeforeEach
    void setUp() {
        doReturn(MEMBER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "topicRouteRuntimeService", service);
    }

    @Test
    void rollRerollSettleForwardTopicNodeAndCheckId() {
        PlayCheckReceipt receipt = new PlayCheckReceipt();
        receipt.setCheckId("ask_arong");
        when(service.roll(MEMBER, TOPIC, null, NODE, "ask_arong")).thenReturn(receipt);
        when(service.reroll(MEMBER, TOPIC, null, NODE, "ask_arong")).thenReturn(receipt);
        when(service.settle(MEMBER, TOPIC, null, NODE, "ask_arong")).thenReturn(receipt);

        assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(receipt), controller.roll("10", null, "1", "ask_arong").get(AjaxResult.DATA_TAG));
        assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(receipt), controller.reroll("10", null, "1", "ask_arong").get(AjaxResult.DATA_TAG));
        assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(receipt), controller.settle("10", null, "1", "ask_arong").get(AjaxResult.DATA_TAG));
    }

    @Test
    void restTakesNoCheckId() {
        PlayCheckReceipt receipt = new PlayCheckReceipt();
        receipt.setExhausted(false);
        when(service.rest(MEMBER, TOPIC, null, NODE)).thenReturn(receipt);

        AjaxResult result = controller.rest("10", null, "1");

        assertEquals(Integer.valueOf(200), result.get(AjaxResult.CODE_TAG));
        assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(receipt), result.get(AjaxResult.DATA_TAG));
    }

    @Test
    void anonymousCallIsRejectedBeforeTouchingTheRuntime() {
        doReturn(0L).when(controller).getAppUserId();

        AjaxResult result = controller.roll("10", null, "1", "ask_arong");

        assertEquals("请先登录", result.get(AjaxResult.MSG_TAG));
        verify(service, never()).roll(MEMBER, TOPIC, null, NODE, "ask_arong");
    }

    @Test
    void rejectedCheckIsReturnedAsBusinessError() {
        when(service.roll(MEMBER, TOPIC, null, NODE, "ask_arong"))
                .thenThrow(new IllegalStateException("请先扫码确认到店"));

        AjaxResult result = controller.roll("10", null, "1", "ask_arong");

        assertEquals("请先扫码确认到店", result.get(AjaxResult.MSG_TAG));
    }
}
