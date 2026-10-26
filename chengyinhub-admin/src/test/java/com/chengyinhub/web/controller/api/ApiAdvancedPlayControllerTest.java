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
 * <p>收尾必须在控制器这一层、act 返回之后做:act 是 {@code @Transactional},它一返回,
 * 代理就已经提交并归还了连接;铸卡要并行等抠图与认物最多 4 秒,放在事务里就是占着库连接等。
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
}
