package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 思维阁的到站同步端点。
 *
 * ★ 这一层只做两件事:把 {code, encryptedData, iv} 换成**服务端解密的**今日步数,
 *   再把 claim 转给 service。客户端报的任何步数都不采信(与 playkit-walk
 *   「步数只能同步不能填」同一条口径)。
 *
 * ⚠️ controller 不开 @Transactional:syncThoughts 自带事务,再包一层的话捕获它抛出的
 *   业务异常会在外层提交期变成 UnexpectedRollbackException(2026-09-22 开局端点踩过,
 *   负控见 ApiPlayOpeningTransactionTest)。
 */
class ApiPlayThoughtSyncTest {

    @Test
    void forwardsDecryptedStepsAndClaimToService() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        ApiPlayJourneyActionController controller = controllerWith(runtime, 3120);

        TopicRouteState state = new TopicRouteState();
        state.setStatus("ACTIVE");
        when(runtime.syncThoughts(anyLong(), anyLong(), anyLong(), anyList(), anyInt(), anyLong())).thenReturn(state);

        assertEquals(200, controller.syncThoughts(body(Arrays.asList("afternoon"))).get("code"));

        ArgumentCaptor<List> claim = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Integer> steps = ArgumentCaptor.forClass(Integer.class);
        verify(runtime).syncThoughts(eq(7L), eq(10L), eq(0L), claim.capture(), steps.capture(), anyLong());
        assertEquals(Arrays.asList("afternoon"), claim.getValue());
        assertEquals(Integer.valueOf(3120), steps.getValue(), "步数必须是服务端解密出来的那个");
    }

    @Test
    void businessRejectionComesBackAsMessage() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        ApiPlayJourneyActionController controller = controllerWith(runtime, 3120);
        when(runtime.syncThoughts(anyLong(), anyLong(), anyLong(), anyList(), anyInt(), anyLong()))
                .thenThrow(new IllegalStateException("本局还没开始"));

        AjaxResult rejected = controller.syncThoughts(body(null));
        assertNotEquals(200, rejected.get("code"));
        assertEquals("本局还没开始", rejected.get("msg"));
    }

    @Test
    void requiresLogin() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        ApiPlayJourneyActionController controller = controllerWith(runtime, 3120);
        doReturn(null).when(controller).getAppUserId();

        assertNotEquals(200, controller.syncThoughts(body(null)).get("code"));
        verifyNoInteractions(runtime);
    }

    /** 客户端报的步数一概不采信 —— 端点签名里就不该有「步数」这个入参。 */
    @Test
    void clientReportedStepsAreNotAccepted() throws Exception {
        for (java.lang.reflect.Method m : ApiPlayJourneyActionController.class.getDeclaredMethods()) {
            if (!"syncThoughts".equals(m.getName())) continue;
            for (Class<?> type : m.getParameterTypes()) {
                assertNotEquals(int.class, type, "步数只能服务端解密,不能由客户端传进来");
                assertNotEquals(Integer.class, type, "步数只能服务端解密,不能由客户端传进来");
            }
        }
    }

    /**
     * 2026-09-22 用户裁决:伏笔不能只靠微信步数 —— 没授权微信运动(没带密文)也要能领取念头。
     * 此时不解密,把「没有读数」(-1)交给 service,service 只处理 claim、不碰步数基线。
     * 负控:把 hasWeRunCipher 判断删掉 ⇒ 解密桩被调用,本用例红。
     */
    @Test
    void claimWithoutWeRunCipherSkipsDecryptAndPassesNoReading() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        ApiPlayJourneyActionController controller = controllerWith(runtime, 3120);
        when(runtime.syncThoughts(anyLong(), anyLong(), anyLong(), anyList(), anyInt(), anyLong())).thenReturn(new TopicRouteState());
        Map<String, Object> body = body(Arrays.asList("afternoon"));
        body.remove("code"); body.remove("encryptedData"); body.remove("iv");

        assertEquals(200, controller.syncThoughts(body).get("code"));
        verify(controller, never()).decryptTodaySteps(any(Map.class));
        verify(runtime).syncThoughts(eq(7L), eq(10L), eq(0L), eq(Arrays.asList("afternoon")), eq(-1), eq(0L));
    }

    /** F-R2S-01:微信换钥失败/读数过期不能变成 500 或拦住认领 —— 按「没有读数」继续,步数基线不动。 */
    @Test
    void unreadableWeRunCipherFallsBackToClaimWithoutSteps() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        ApiPlayJourneyActionController controller = controllerWith(runtime, 3120);
        doThrow(new RuntimeException("code2session failed")).when(controller).decryptTodaySteps(any(Map.class));
        when(runtime.syncThoughts(anyLong(), anyLong(), anyLong(), anyList(), anyInt(), anyLong())).thenReturn(new TopicRouteState());

        AjaxResult result = controller.syncThoughts(body(Arrays.asList("afternoon")));
        assertEquals(200, result.get("code"));
        assertEquals("步数暂未同步，念头已记录", result.get("msg"));
        verify(runtime).syncThoughts(eq(7L), eq(10L), eq(0L), eq(Arrays.asList("afternoon")), eq(-1), eq(0L));

        doThrow(new IllegalArgumentException("微信运动读数已过期，请重新同步")).when(controller).decryptTodaySteps(any(Map.class));
        assertEquals(200, controller.syncThoughts(body(null)).get("code"));
    }

    /** controller 不许自己开事务(见类注释)。 */
    @Test
    void controllerDoesNotOpenItsOwnTransaction() throws Exception {
        java.lang.reflect.Method m = ApiPlayJourneyActionController.class
                .getDeclaredMethod("syncThoughts", Map.class);
        assertNull(m.getAnnotation(org.springframework.transaction.annotation.Transactional.class),
                "syncThoughts 自带事务,controller 再包一层会让业务异常退化成 500");
    }

    // ==================== 脚手架 ====================

    private static Map<String, Object> body(List<String> claim) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topicId", 10L);
        body.put("activityId", 0L);
        body.put("code", "js-code");
        body.put("encryptedData", "cipher");
        body.put("iv", "iv");
        if (claim != null) body.put("claim", claim);
        return body;
    }

    /** 解密链路整段替身:真解密由 WeRunDecryptor 静态方法做,这里只钉住「步数来自服务端」。 */
    private ApiPlayJourneyActionController controllerWith(ITopicRouteRuntimeService runtime, int todaySteps) {
        ApiPlayJourneyActionController controller = spy(new ApiPlayJourneyActionController());
        doReturn(7L).when(controller).getAppUserId();
        doReturn(new com.chengyinhub.business.service.support.WeRunDecryptor.WeRunResult(todaySteps, 1790409600L)).when(controller).decryptTodaySteps(any(Map.class));
        ReflectionTestUtils.setField(controller, "topicRouteRuntimeService", runtime);
        return controller;
    }
}
