package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IAdvancedGameRuntimeService;
import com.chengyinhub.business.service.IOrientSessionService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.support.OrientSessionException;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.metrics.GameSloMetrics;
import com.chengyinhub.web.metrics.GameSloResult;
import com.chengyinhub.web.metrics.GameSloRoute;
import com.chengyinhub.web.service.play.PlayBasicCommandApplication;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OPS-01 真实调用点：四条路径的埋点必须落在实际控制器入口上，且不改变原返回/原异常。
 *
 * <p>每个断言都比对同一 MeterRegistry 上的固定序列，证明成功/拒绝/故障分得开，
 * 且 HTTP 200 的业务失败不会记成成功。
 */
class GameSloCallSiteTest
{
    private SimpleMeterRegistry registry;
    private GameSloMetrics metrics;

    @BeforeEach
    void setUp()
    {
        registry = new SimpleMeterRegistry();
        metrics = new GameSloMetrics(registry);
    }

    @Test
    void advancedStartCountsSuccessAndBusinessRejectionWithoutChangingResponse()
    {
        IAdvancedGameRuntimeService runtime = mock(IAdvancedGameRuntimeService.class);
        Map<String, Object> view = new HashMap<>();
        view.put("sessionId", 7L);
        when(runtime.start(anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(view);

        ApiAdvancedPlayController controller = new ApiAdvancedPlayController()
        {
            @Override
            public Long getAppUserId()
            {
                return 9L;
            }
        };
        ReflectionTestUtils.setField(controller, "runtimeService", runtime);
        ReflectionTestUtils.setField(controller, "gameSlo", metrics);

        AjaxResult success = controller.start(Map.of("activityId", 4, "topicId", 1, "nodeId", 2, "teamId", 5));
        assertEquals(200, success.get("code"));
        assertEquals(1.0, count(GameSloRoute.ADVANCED_START, GameSloResult.SUCCESS));

        when(runtime.start(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new IllegalArgumentException("队伍与本场归属不匹配"));
        AjaxResult rejected = controller.start(Map.of("activityId", 4, "topicId", 1, "nodeId", 2, "teamId", 5));
        // 原返回不变：仍是 500 + 原文案，埋点没有把它改写成别的业务结果。
        assertEquals(500, rejected.get("code"));
        assertEquals("队伍与本场归属不匹配", rejected.get("msg"));
        assertEquals(1.0, count(GameSloRoute.ADVANCED_START, GameSloResult.REJECTED));
        assertEquals(1.0, count(GameSloRoute.ADVANCED_START, GameSloResult.SUCCESS), "拒绝不得计成功");

        org.mockito.Mockito.doThrow(new org.springframework.dao.CannotAcquireLockException("db"))
                .when(runtime).start(anyLong(), anyLong(), anyLong(), anyLong(), any());
        AjaxResult fault = controller.start(Map.of("activityId", 4, "topicId", 1, "nodeId", 2, "teamId", 5));
        // 原行为就是把 RuntimeException 收敛成 error(msg)；这里只验证它被记成 fault，而不是改写成成功。
        assertEquals(500, fault.get("code"));
        assertEquals(1.0, count(GameSloRoute.ADVANCED_START, GameSloResult.FAULT));
        assertEquals(1.0, count(GameSloRoute.ADVANCED_START, GameSloResult.SUCCESS), "故障不得计成功");
    }

    @Test
    void orientStartCountsAckRequiredAsRejected()
    {
        IOrientSessionService orient = mock(IOrientSessionService.class);
        when(orient.start(anyLong(), anyLong(), any(), anyString()))
                .thenThrow(new OrientSessionException("ORIENT_ACK_REQUIRED", 409, "请先确认本局规则"));

        ApiOrientSessionController controller = new ApiOrientSessionController()
        {
            @Override
            public Long getAppUserId()
            {
                return 11L;
            }
        };
        ReflectionTestUtils.setField(controller, "orientSessionService", orient);
        ReflectionTestUtils.setField(controller, "gameSlo", metrics);

        AjaxResult result = controller.start(5L, 1L, "key-12345678");
        assertEquals(409, result.get("code"));
        assertEquals("ORIENT_ACK_REQUIRED", result.get("err"));
        assertEquals(1.0, count(GameSloRoute.ORIENT_START, GameSloResult.REJECTED));
        assertEquals(0.0, count(GameSloRoute.ORIENT_START, GameSloResult.SUCCESS));

        // 未登录走同一条 call 的 401 分支：仍记 rejected，不能记成成功。
        ApiOrientSessionController anonymous = new ApiOrientSessionController()
        {
            @Override
            public Long getAppUserId()
            {
                return null;
            }
        };
        ReflectionTestUtils.setField(anonymous, "orientSessionService", orient);
        ReflectionTestUtils.setField(anonymous, "gameSlo", metrics);
        AjaxResult notLoggedIn = anonymous.start(5L, 1L, "key-12345678");
        assertEquals(401, notLoggedIn.get("code"));
        assertEquals("ORIENT_LOGIN_REQUIRED", notLoggedIn.get("err"));
        assertEquals(2.0, count(GameSloRoute.ORIENT_START, GameSloResult.REJECTED));
        assertEquals(0.0, count(GameSloRoute.ORIENT_START, GameSloResult.SUCCESS));
    }

    @Test
    void arriveNeverCountsHttp200BusinessFailureAsSuccess()
    {
        PlayBasicCommandApplication application = mock(PlayBasicCommandApplication.class);
        when(application.arrive(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.nullable(String.class), any(), any(), any()))
                .thenReturn(com.chengyinhub.web.service.play.PlayCommandErrorContract
                        .rejected("还没到这一站附近", "ARRIVE_TOO_FAR"));

        ApiPlayProgressController controller = new ApiPlayProgressController()
        {
            @Override
            public Long getAppUserId()
            {
                return 13L;
            }
        };
        ReflectionTestUtils.setField(controller, "playBasicCommandApplication", application);
        ReflectionTestUtils.setField(controller, "gameSlo", metrics);

        AjaxResult rejected = controller.arrive("1", "2", "121.4", "31.2", "3", "act", 0, "10", 4L, "rt", 0);
        // 控制器仍按原行为返回；指标必须把它记成拒绝，不能记成成功。
        assertEquals("ARRIVE_TOO_FAR", rejected.get("errorCode"));
        assertEquals(1.0, count(GameSloRoute.ARRIVE, GameSloResult.REJECTED));
        assertEquals(0.0, count(GameSloRoute.ARRIVE, GameSloResult.SUCCESS));

        when(application.arrive(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.nullable(String.class), any(), any(), any()))
                .thenReturn(AjaxResult.success("已到达，打卡成功"));
        AjaxResult success = controller.arrive("1", "2", "121.4", "31.2", "3", "act", 0, "10", 4L, "rt", 0);
        assertEquals(200, success.get("code"));
        assertEquals(1.0, count(GameSloRoute.ARRIVE, GameSloResult.SUCCESS));
        assertEquals(1.0, count(GameSloRoute.ARRIVE, GameSloResult.REJECTED), "拒绝计数不得被成功覆盖");
    }

    @Test
    void openingCompleteCountsLoginRejectionAndSuccess()
    {
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime =
                mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        when(runtime.completeOpening(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new com.chengyinhub.business.domain.route.TopicRouteState());

        ApiPlayJourneyActionController controller = new ApiPlayJourneyActionController()
        {
            @Override
            public Long getAppUserId()
            {
                return 17L;
            }
        };
        ReflectionTestUtils.setField(controller, "topicRouteRuntimeService", runtime);
        ReflectionTestUtils.setField(controller, "gameSlo", metrics);

        AjaxResult success = controller.completeOpening(Map.of("topicId", 1, "chapterId", 2));
        assertEquals(200, success.get("code"));
        assertEquals(1.0, count(GameSloRoute.OPENING_COMPLETE, GameSloResult.SUCCESS));

        when(runtime.completeOpening(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException("开局流程不匹配"));
        AjaxResult rejected = controller.completeOpening(Map.of("topicId", 1, "chapterId", 2));
        assertEquals("开局流程不匹配", rejected.get("msg"));
        assertEquals(1.0, count(GameSloRoute.OPENING_COMPLETE, GameSloResult.REJECTED));
    }

    @Test
    void payCallbackCountsAckOnlyWhenWechatCanStopRetrying()
    {
        IWechatPayService gateway = mock(IWechatPayService.class);
        ApiRegistrationController controller = new ApiRegistrationController();
        ReflectionTestUtils.setField(controller, "wechatPayService", gateway);
        ReflectionTestUtils.setField(controller, "gameSlo", metrics);
        Map<String, String> headers = Map.of("Wechatpay-Serial", "serial");

        when(gateway.handlePayCallback(anyString(), anyMap())).thenReturn("{\"code\":\"SUCCESS\"}");
        ResponseEntity<?> ack = controller.payCallback("{}", headers);
        assertEquals(204, ack.getStatusCode().value());
        assertEquals(1.0, count(GameSloRoute.PAY_CALLBACK, GameSloResult.SUCCESS));

        when(gateway.handlePayCallback(anyString(), anyMap())).thenReturn("{\"code\":\"FAIL\"}");
        ResponseEntity<?> fail = controller.payCallback("{}", headers);
        assertEquals(500, fail.getStatusCode().value());
        assertEquals(1.0, count(GameSloRoute.PAY_CALLBACK, GameSloResult.FAULT));
        assertEquals(1.0, count(GameSloRoute.PAY_CALLBACK, GameSloResult.SUCCESS), "未确认回调不得计成功");

        // 退款回调不在这条路径的观测里：它走 refundCallback，不应改动支付回调计数。
        when(gateway.handleRefundCallback(anyString(), anyMap())).thenReturn("{\"code\":\"SUCCESS\"}");
        controller.refundCallback("{}", headers);
        assertEquals(1.0, count(GameSloRoute.PAY_CALLBACK, GameSloResult.SUCCESS));
        assertEquals(1.0, count(GameSloRoute.PAY_CALLBACK, GameSloResult.FAULT));
    }

    private double count(GameSloRoute route, GameSloResult result)
    {
        String name = switch (route.family())
        {
            case START -> GameSloMetrics.START_TOTAL;
            case ARRIVE -> GameSloMetrics.ARRIVE_TOTAL;
            case PAY -> GameSloMetrics.PAY_CALLBACK_TOTAL;
            case REWARD -> throw new IllegalArgumentException("reward route has no HTTP counter");
        };
        Counter counter = registry.find(name)
                .tag(GameSloMetrics.TAG_ROUTE, route.tag())
                .tag(GameSloMetrics.TAG_RESULT, result.tag())
                .counter();
        return counter == null ? 0d : counter.count();
    }
}
