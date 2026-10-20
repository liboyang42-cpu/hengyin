package com.chengyinhub.framework;

import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import com.chengyinhub.framework.web.exception.RuntimeFailureAlarmFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeFailureAlarmFilterTest {
    @Test void actualLettuceTimeoutInsideSpringQueryTimeoutIsClassifiedByItsCause() {
        AlarmService alarms = mock(AlarmService.class);
        RuntimeFailureAlarmFilter filter = new RuntimeFailureAlarmFilter(alarms);
        filter.systemFailure(new IllegalStateException("private-wrapper",
                new org.springframework.dao.QueryTimeoutException("private-command",
                        new io.lettuce.core.RedisCommandTimeoutException("private-redis"))));
        verify(alarms).alarmThrottled("runtime.redis.connection", "缓存连接异常", "type=RedisCommandTimeoutException");
        verifyNoMoreInteractions(alarms);
    }

    @Test void ordinaryJdbcQueryTimeoutIsNeverMisclassifiedAsRedis() {
        AlarmService alarms = mock(AlarmService.class);
        RuntimeFailureAlarmFilter filter = new RuntimeFailureAlarmFilter(alarms);
        filter.systemFailure(new org.springframework.dao.QueryTimeoutException("private-sql",
                new java.sql.SQLTimeoutException("private-jdbc-detail")));
        verify(alarms).alarmThrottled("runtime.request.unhandled", "服务请求异常", "type=QueryTimeoutException");
        verifyNoMoreInteractions(alarms);
    }

    @Test
    void real5xxUsesFixedKeyAndNoRequestSecrets() throws Exception {
        AlarmService alarms = mock(AlarmService.class);
        RuntimeFailureAlarmFilter filter = new RuntimeFailureAlarmFilter(alarms);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/private/data");
        request.setQueryString("sensitive=request-query");
        request.addHeader("Authorization", "sensitive-request-header");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> response.setStatus(503));
        verify(alarms).alarmThrottled("runtime.http.5xx", "HTTP服务失败", "status=503");
        assertEquals(503, response.getStatus());
        verifyNoMoreInteractions(alarms);
    }

    @Test
    void ordinaryAndDeniedRequestsDoNotRaiseRuntimeAlarm() throws Exception {
        for (int status : new int[]{200, 401, 403, 404, 426}) {
            AlarmService alarms = mock(AlarmService.class);
            MockHttpServletResponse response = new MockHttpServletResponse();
            new RuntimeFailureAlarmFilter(alarms).doFilter(new MockHttpServletRequest(), response,
                    (req, res) -> response.setStatus(status));
            verifyNoInteractions(alarms);
        }
    }

    @Test
    void ajaxHandledRedisFailureStillRaisesConnectionAlarmWithoutHttpStatusGuess() throws Exception {
        AlarmService alarms = mock(AlarmService.class);
        RuntimeFailureAlarmFilter filter = new RuntimeFailureAlarmFilter(alarms);
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(advice, "runtimeFailureAlarm", filter);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> advice.handleRuntimeException(
                new RedisConnectionFailureException("sensitive-connection-detail"), request));
        verify(alarms).alarmThrottled("runtime.redis.connection", "缓存连接异常", "type=RedisConnectionFailureException");
        verifyNoMoreInteractions(alarms);
        assertEquals(200, response.getStatus());
    }

    @Test
    void metricsCountActualResponsesAndHandledSystemFailuresSeparately() throws Exception {
        com.chengyinhub.business.metrics.HttpRequestMetrics metrics = new com.chengyinhub.business.metrics.HttpRequestMetrics();
        RuntimeFailureAlarmFilter filter = new RuntimeFailureAlarmFilter(mock(AlarmService.class));
        ReflectionTestUtils.setField(filter, "metrics", metrics);
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> filter.systemFailure(
                new RedisConnectionFailureException("private")));
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> ((MockHttpServletResponse) res).setStatus(503));
        java.util.List<com.chengyinhub.business.metrics.MetricSample> samples = metrics.drain(new java.util.Date());
        assertEquals(2, samples.get(0).getValue().intValueExact());
        assertEquals(0, new java.math.BigDecimal("0.5").compareTo(samples.get(1).getValue()));
        assertEquals(1, samples.get(2).getValue().intValueExact());
    }

    @Test
    void wrappedDatabaseConnectionFailureIsClassifiedWithoutExceptionMessage() {
        AlarmService alarms = mock(AlarmService.class);
        new RuntimeFailureAlarmFilter(alarms).connectionFailure(new IllegalStateException("private-sql",
                new CannotGetJdbcConnectionException("private-jdbc-url")));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(alarms).alarmThrottled(eq("runtime.db.connection"), eq("数据库连接异常"), detail.capture());
        assertEquals("type=CannotGetJdbcConnectionException", detail.getValue());
    }

    @Test
    void alarmFailureNeverReplacesOriginalServerOutcome() throws Exception {
        AlarmService alarms = mock(AlarmService.class);
        doThrow(new IllegalStateException("sink unavailable")).when(alarms).alarmThrottled(anyString(), anyString(), anyString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RuntimeFailureAlarmFilter(alarms).doFilter(new MockHttpServletRequest(), response,
                (req, res) -> response.setStatus(500));
        assertEquals(500, response.getStatus());
    }

    @Test
    void unhandledServletFailureIsPropagatedAfterSafeAlarm() {
        AlarmService alarms = mock(AlarmService.class);
        RuntimeFailureAlarmFilter filter = new RuntimeFailureAlarmFilter(alarms);
        ServletException original = new ServletException("private-provider-detail");
        assertSame(original, assertThrows(ServletException.class, () -> filter.doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> { throw original; })));
        verify(alarms).alarmThrottled("runtime.request.unhandled", "服务请求异常", "type=ServletException");
    }
}
