package com.chengyinhub.framework.aspectj;

import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.service.IPlayTeamService;
import com.chengyinhub.business.service.impl.PlayTeamServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeamInviteAttemptAspectTest {
    private IPlayTeamService service;
    private PlayTeamServiceImpl target;
    private RedisTemplate<Object, Object> redis;
    private MockHttpServletRequest request;
    private final Map<String, Long> counts = new HashMap<>();

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        redis = mock(RedisTemplate.class);
        RedisScript<Long> script = mock(RedisScript.class);
        when(redis.execute(eq(script), anyList(), any(), any())).thenAnswer(call -> {
            String key = ((List<Object>) call.getArgument(1)).get(0).toString();
            long value = counts.getOrDefault(key, 0L) + 1;
            counts.put(key, value);
            return value;
        });
        TeamInviteAttemptAspect aspect = new TeamInviteAttemptAspect();
        ReflectionTestUtils.setField(aspect, "redisTemplate", redis);
        ReflectionTestUtils.setField(aspect, "limitScript", script);
        target = mock(PlayTeamServiceImpl.class);
        when(target.join(anyString(), anyLong())).thenThrow(new IllegalArgumentException("invalid code"));
        AspectJProxyFactory proxy = new AspectJProxyFactory(target);
        proxy.addAspect(aspect);
        service = proxy.getProxy();
        request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test void invalidAttemptsConsumeUserBudgetEvenAcrossIpsAndRotatingCodeGuesses() {
        for (int i = 0; i < 10; i++) {
            request.setRemoteAddr("198.51.100." + (i + 1));
            final String code = "guess" + i;
            assertThrows(IllegalArgumentException.class, () -> service.join(code, 7L));
        }
        request.setRemoteAddr("198.51.100.99");
        assertThrows(ServiceException.class, () -> service.join("VALID2", 7L));
        verify(target, times(10)).join(anyString(), eq(7L));
    }

    @Test void ipBudgetCannotBeBypassedWithDifferentUsersOrSpoofedForwardHeaders() {
        for (long user = 1; user <= 60; user++) {
            request.addHeader("X-Forwarded-For", "203.0.113." + user);
            final long actor = user;
            assertThrows(IllegalArgumentException.class, () -> service.join("WRONG2", actor));
        }
        assertThrows(ServiceException.class, () -> service.join("VALID2", 61L));
        verify(target, times(60)).join(anyString(), anyLong());
    }

    @Test void successfulJoinsAndAnonymousLookupUseTheSameIpBudget() {
        doReturn(new PlayTeam()).when(target).join(anyString(), anyLong());
        for (int i = 0; i < 10; i++) assertNotNull(service.join("VALID2", 7L));
        assertThrows(ServiceException.class, () -> service.join("VALID2", 7L));
        for (int i = 0; i < 49; i++) service.info(null, "WRONG2", 0L);
        assertThrows(ServiceException.class, () -> service.info(null, "VALID2", 0L));
        verify(target, times(49)).info(null, "WRONG2", 0L);
    }

    @Test void redisFailureFailsClosedBeforeInvitationLookup() {
        reset(redis);
        assertThrows(ServiceException.class, () -> service.join("VALID2", 7L));
        verify(target, never()).join(anyString(), anyLong());
    }
}
