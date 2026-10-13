package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.service.ITopicCompletionAwardService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiPlayOpeningActionTest {
    /**
     * controller 只做转发与回包:登录校验、参数校验、把业务拒绝原样回给客户端。
     * 通关发奖住在 service(见 TopicRouteRuntimeServiceImplTest),controller 自己不判、不发,
     * 也不开事务——否则捕获业务异常会在提交期退化成 UnexpectedRollbackException
     * (负控见 ApiPlayOpeningTransactionTest)。
     */
    @Test
    void completeOpeningForwardsAndSurfacesBusinessRejection() {
        ApiPlayJourneyActionController controller = spy(new ApiPlayJourneyActionController());
        doReturn(7L).when(controller).getAppUserId();
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(controller, "topicRouteRuntimeService", runtime);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topicId", 10L); body.put("activityId", 0L);
        body.put("chapterId", 90L); body.put("expectedVersion", 1);

        TopicRouteState state = new TopicRouteState(); state.setStatus("ACTIVE");
        when(runtime.completeOpening(7L, 10L, 0L, 90L, 1)).thenReturn(state);
        assertEquals(200, controller.completeOpening(body).get("code"));

        when(runtime.completeOpening(7L, 10L, 0L, 90L, 1))
                .thenThrow(new IllegalArgumentException("开局流程不匹配"));
        AjaxResult rejected = controller.completeOpening(body);
        assertNotEquals(200, rejected.get("code"));
        assertEquals("开局流程不匹配", rejected.get("msg"), "业务文案要原样回给客户端");

        doReturn(null).when(controller).getAppUserId();
        assertNotEquals(200, controller.completeOpening(body).get("code"));
        verify(runtime, times(2)).completeOpening(7L, 10L, 0L, 90L, 1);
    }

    /** 发奖依赖不许再回到 controller:那会让非本接口的 completeOpening 调用方拿不到奖励。 */
    @Test
    void controllerDoesNotOwnAwardGranting() {
        for (Field field : ApiPlayJourneyActionController.class.getDeclaredFields()) {
            assertNotEquals(ITopicCompletionAwardService.class, field.getType(),
                    "通关发奖属于 service,不能挂在 controller 上");
        }
    }
}
