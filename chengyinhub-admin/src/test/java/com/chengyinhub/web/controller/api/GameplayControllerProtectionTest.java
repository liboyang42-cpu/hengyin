package com.chengyinhub.web.controller.api;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;

import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.framework.aspectj.RateLimiterAspect;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameplayControllerProtectionTest {
    private static final Class<?>[] CONTROLLERS = {ApiThemeRuntimeController.class,
            ApiGameSessionController.class, ApiAdvancedPlayController.class, ApiPlayProgressController.class};

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test void zeroHpSyncUsesPlayerIdentityAndOnlyStepsAcceptsCiphertext() {
        ApiGameSessionController shared=new ApiGameSessionController();
        assertEquals("PLAYER",org.springframework.test.util.ReflectionTestUtils.invokeMethod(shared,"perspectiveFor","ZERO_HP_STEPS"));assertEquals("PLAYER",org.springframework.test.util.ReflectionTestUtils.invokeMethod(shared,"perspectiveFor","ZERO_HP_SYNC"));
        ApiThemeRuntimeController controller=new ApiThemeRuntimeController();ApiThemeRuntimeController.Action body=new ApiThemeRuntimeController.Action();body.operation="QUEST_START";body.weRun=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("steps",9999);
        assertThrows(IllegalArgumentException.class,()->controller.gameplayAction(body));body.operation="ZERO_HP_STEPS";body.choiceKey="fake";assertThrows(IllegalArgumentException.class,()->controller.gameplayAction(body));
    }

    @Test
    void everyWriteAndPreviewHasItsOwnGenerousMemberBudget() {
        int protectedMethods = 0;
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                PostMapping post = method.getAnnotation(PostMapping.class);
                if (post == null) continue;
                String path = post.value()[0];
                if (Arrays.asList("/my-completed", "/growth", "/achievements").contains(path)) continue;
                RateLimiter limiter = method.getAnnotation(RateLimiter.class);
                assertNotNull(limiter, controller.getSimpleName() + path);
                assertEquals(LimitType.MEMBER, limiter.limitType());
                assertEquals(10, limiter.time());
                int expected = path.endsWith("preview") ? 60 :
                        Arrays.asList("/replay", "/shared/export/anchor").contains(path) ? 10 : 120;
                assertEquals(expected, limiter.count(), path);
                protectedMethods++;
            }
        }
        assertEquals(22, protectedMethods);
    }

    @Test
    void staffCommandAndAnchorHavePayloadFreeAuditAnnotations() {
        for (Method method : ApiGameSessionController.class.getDeclaredMethods()) {
            PostMapping post = method.getAnnotation(PostMapping.class);
            if (post == null || !Arrays.asList("/command", "/shared/export/anchor").contains(post.value()[0])) continue;
            Log audit = method.getAnnotation(Log.class);
            assertNotNull(audit);
            assertFalse(audit.isSaveRequestData());
            assertFalse(audit.isSaveResponseData());
        }
    }

    @Test
    void getViewsThatInitializeOrSyncRouteSessionsAlsoHaveMemberBudgets() {
        int protectedReads = 0;
        for (Class<?> controller : Arrays.asList(ApiThemeRuntimeController.class, ApiPlayProgressController.class)) {
            for (Method method : controller.getDeclaredMethods()) {
                GetMapping get = method.getAnnotation(GetMapping.class);
                if (get == null || !Arrays.asList("/state", "/nodes", "/route-state").contains(get.value()[0])) continue;
                RateLimiter limiter = method.getAnnotation(RateLimiter.class);
                assertNotNull(limiter);
                assertEquals(LimitType.MEMBER, limiter.limitType());
                assertEquals(120, limiter.count());
                assertEquals(10, limiter.time());
                protectedReads++;
            }
        }
        assertEquals(3, protectedReads);
    }

    @Test
    void sameNatUsersHaveDifferentKeysAndChangingIpDoesNotResetUserBudget() {
        RateLimiterAspect aspect = new RateLimiterAspect();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                RateLimiter limiter = method.getAnnotation(RateLimiter.class);
                if (limiter == null) continue;
                JoinPoint point = mock(JoinPoint.class);
                MethodSignature signature = mock(MethodSignature.class);
                when(point.getSignature()).thenReturn(signature);
                when(signature.getMethod()).thenReturn(method);
                authenticate(101L, "127.0.0.1");
                String first = aspect.getCombineKey(limiter, point);
                authenticate(102L, "127.0.0.1");
                assertNotEquals(first, aspect.getCombineKey(limiter, point));
                authenticate(101L, "127.0.0.2");
                assertEquals(first, aspect.getCombineKey(limiter, point));
                assertTrue(first.contains("m101-"));
                assertTrue(first.endsWith(controller.getName() + "-" + method.getName()));
            }
        }
    }

    private void authenticate(long memberId, String address) {
        AppUser user = new AppUser();
        user.setUserId(memberId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginAppUser(memberId, user), null, Collections.emptyList()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
