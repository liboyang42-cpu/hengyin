package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IAdvancedGameRuntimeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 提交动作的接线:事务内判定(act)→ 事务外收尾铸卡(finishPendingCardMint)。
 *
 * <p>收尾必须在控制器这一层、act 的显式事务已返回之后做:铸卡要并行等抠图与认物最多 4 秒,放在事务里就是占着库连接等。
 */
class ApiAdvancedPlayControllerTest {

    private static final Long MEMBER_ID = 7L;

    private IAdvancedGameRuntimeService runtimeService;
    private ApiAdvancedPlayController controller;

    @BeforeEach
    void setUp() {
        runtimeService = mock(IAdvancedGameRuntimeService.class);
        controller = new ApiAdvancedPlayController() {
            @Override
            public Long getAppUserId() {
                return MEMBER_ID;
            }
        };
        ReflectionTestUtils.setField(controller, "runtimeService", runtimeService);
    }

    @Test
    void 先判定再收尾铸卡_回包是收尾之后的那一份() {
        Map<String, Object> judged = new HashMap<>();
        Map<String, Object> finished = new HashMap<>();
        finished.put("objectCard", "minted");
        when(runtimeService.act(anyLong(), anyLong(), any(), anyString(), anyString(), anyMap())).thenReturn(judged);
        when(runtimeService.finishPendingCardMint(judged)).thenReturn(finished);

        Map<String, Object> body = new HashMap<>();
        body.put("sessionId", 51);
        body.put("idempotencyKey", "k1");
        body.put("action", "SUBMIT_PHOTO_CHECK");
        AjaxResult result = controller.action(body);

        InOrder order = inOrder(runtimeService);
        order.verify(runtimeService).act(anyLong(), anyLong(), any(), anyString(), anyString(), anyMap());
        order.verify(runtimeService).finishPendingCardMint(judged);
        assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(finished), result.get(AjaxResult.DATA_TAG), "回包必须是收尾之后的那一份,不然当场出卡丢了");
    }
    @Test
    void unknownPhotoKeepsOriginalIdentityAndNeverMintsOrLeaksInternalCause() {
        when(runtimeService.act(anyLong(), anyLong(), any(), anyString(), anyString(), anyMap()))
                .thenThrow(new com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException(
                        "private provider diagnostic", new IllegalStateException("private database diagnostic")));
        AjaxResult response = controller.action(Map.of("sessionId", 51, "version", 0,
                "idempotencyKey", "original-photo", "action", "SUBMIT_PHOTO_CHECK"));
        org.junit.jupiter.api.Assertions.assertEquals(520, response.get("code"));
        org.junit.jupiter.api.Assertions.assertEquals(true, response.get("intentUnknown"));
        org.junit.jupiter.api.Assertions.assertFalse(response.toString().contains("private"));
        org.mockito.Mockito.verify(runtimeService, org.mockito.Mockito.never()).finishPendingCardMint(anyMap());
    }

    @Test
    void 事务内的校验拒绝按4xx下发_客户端据此确认未执行而不是挂结果待确认() {
        when(runtimeService.act(anyLong(), anyLong(), any(), anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalArgumentException("作品名须为 1 至 12 字"));
        AjaxResult response = controller.action(Map.of("sessionId", 51, "version", 3,
                "idempotencyKey", "k2", "action", "SUBMIT_DIY_NAME"));
        assertEquals(400, response.get("code"));
        assertEquals("作品名须为 1 至 12 字", response.get("msg"));
        org.mockito.Mockito.verify(runtimeService, org.mockito.Mockito.never()).finishPendingCardMint(anyMap());
    }

    @Test
    void 拍照动作与提交之后的收尾失败仍是通用错误_不冒充未执行() {
        when(runtimeService.act(anyLong(), anyLong(), any(), anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("照片审核不能在已有业务事务中执行"));
        AjaxResult photo = controller.action(Map.of("sessionId", 51, "version", 3,
                "idempotencyKey", "k3", "action", "SUBMIT_PHOTO_CHECK"));
        assertEquals(500, photo.get("code"));

        Map<String, Object> judged = new HashMap<>();
        org.mockito.Mockito.reset(runtimeService);
        when(runtimeService.act(anyLong(), anyLong(), any(), anyString(), anyString(), anyMap())).thenReturn(judged);
        when(runtimeService.finishPendingCardMint(judged)).thenThrow(new IllegalStateException("铸卡失败"));
        AjaxResult minted = controller.action(Map.of("sessionId", 51, "version", 3,
                "idempotencyKey", "k4", "action", "SUBMIT_SORT"));
        assertEquals(500, minted.get("code"));
    }

}
