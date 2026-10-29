package com.chengyinhub.framework.aspectj;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.alibaba.fastjson2.JSON;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.enums.OperatorType;
import com.chengyinhub.common.utils.LogCorrelation;
import com.chengyinhub.framework.config.ThreadPoolConfig;
import com.chengyinhub.framework.manager.AsyncManager;
import com.chengyinhub.system.domain.SysOperLog;
import com.chengyinhub.system.service.ISysOperLogService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LogAspectAuditTest
{
    private final List<SysOperLog> persistedLogs = new ArrayList<>();
    private ScheduledExecutorService originalExecutor;
    private ConfigurableListableBeanFactory originalBeanFactory;
    private ISysOperLogService operLogService;
    private LogAspect aspect;
    private ScheduledExecutorService testExecutorToShutdown;

    @BeforeEach
    void setUp() throws Exception
    {
        originalBeanFactory = (ConfigurableListableBeanFactory) ReflectionTestUtils.getField(SpringUtilsHolder.TYPE,
                "beanFactory");
        ConfigurableListableBeanFactory beanFactory = mock(ConfigurableListableBeanFactory.class);
        operLogService = mock(ISysOperLogService.class);
        when(beanFactory.getBean(ISysOperLogService.class)).thenReturn(operLogService);
        ReflectionTestUtils.setField(SpringUtilsHolder.TYPE, "beanFactory", beanFactory);
        doAnswer(invocation -> {
            persistedLogs.add(invocation.getArgument(0));
            return null;
        }).when(operLogService).insertOperlog(any(SysOperLog.class));

        ScheduledExecutorService directExecutor = mock(ScheduledExecutorService.class);
        when(directExecutor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return mock(ScheduledFuture.class);
        });
        originalExecutor = (ScheduledExecutorService) ReflectionTestUtils.getField(AsyncManager.me(), "executor");
        ReflectionTestUtils.setField(AsyncManager.me(), "executor", directExecutor);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/session/secret-token");
        request.setRemoteAddr("127.0.0.1");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/session/{tokenId}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        aspect = new LogAspect();
    }

    @AfterEach
    void tearDown()
    {
        ReflectionTestUtils.setField(AsyncManager.me(), "executor", originalExecutor);
        if (testExecutorToShutdown != null)
        {
            testExecutorToShutdown.shutdownNow();
        }
        ReflectionTestUtils.setField(SpringUtilsHolder.TYPE, "beanFactory", originalBeanFactory);
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void appSuccessPersistsExactlyOneAuditWithoutSensitivePayload() throws Exception
    {
        AppUser appUser = new AppUser();
        appUser.setUserName("app-user");
        LoginAppUser principal = new LoginAppUser(42L, appUser);
        principal.setToken("secret-token");
        authenticate(principal);

        invokeSuccess(new SensitiveBody("secret-password", "13800138000", "secret-token"),
                new SensitiveBody("response-password", "13900139000", "response-token"));

        assertEquals(1, persistedLogs.size());
        SysOperLog operLog = persistedLogs.get(0);
        assertEquals("app:42", operLog.getOperName());
        assertEquals(0, operLog.getStatus());
        assertEquals(OperatorType.MOBILE.ordinal(), operLog.getOperatorType());
        assertEquals("/api/session/{tokenId}", operLog.getOperUrl());
        assertNull(operLog.getOperParam());
        assertNull(operLog.getJsonResult());
        assertFalse(JSON.toJSONString(operLog).contains("secret-token"));
    }

    @Test
    void successMarkedAsNoChangeByTheControllerPersistsNoAudit() throws Exception
    {
        RequestContextHolder.currentRequestAttributes().setAttribute(LogAspect.SKIP_OPER_LOG_ATTRIBUTE, Boolean.TRUE,
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);

        invokeSuccess(null, null);

        assertEquals(0, persistedLogs.size());
    }

    @Test
    void appFailurePersistsExactlyOneSanitizedFailureAudit() throws Exception
    {
        AppUser appUser = new AppUser();
        appUser.setUserName("app-user");
        authenticate(new LoginAppUser(42L, appUser));

        aspect.doBefore(joinPoint(null), auditAnnotation());
        aspect.doAfterThrowing(joinPoint(null), auditAnnotation(),
                new IllegalStateException("token=secret-token mobile=13800138000"));

        assertEquals(1, persistedLogs.size());
        assertEquals(1, persistedLogs.get(0).getStatus());
        assertEquals(OperatorType.MOBILE.ordinal(), persistedLogs.get(0).getOperatorType());
        assertEquals("IllegalStateException", persistedLogs.get(0).getErrorMsg());
    }

    @Test
    void missingRoutePatternOmitsDynamicUriButKeepsMethodLocation() throws Exception
    {
        AppUser appUser = new AppUser();
        appUser.setUserName("app-user");
        authenticate(new LoginAppUser(42L, appUser));
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        attributes.getRequest().removeAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);

        invokeSuccess(null, null);

        assertEquals(1, persistedLogs.size());
        SysOperLog operLog = persistedLogs.get(0);
        assertNull(operLog.getOperUrl());
        assertTrue(operLog.getMethod().endsWith("AuditTarget.audit()"));
        assertFalse(JSON.toJSONString(operLog).contains("secret-token"));
    }

    @Test
    void backendAnonymousAndSystemCallsAreHandledWithoutPrincipalCasts() throws Exception
    {
        SysUser sysUser = new SysUser();
        sysUser.setUserName("admin-user");
        authenticate(new LoginUser(7L, 2L, sysUser, Collections.emptySet()));
        invokeSuccess(null, null);

        SecurityContextHolder.clearContext();
        invokeSuccess(null, null);

        RequestContextHolder.resetRequestAttributes();
        invokeSuccess(null, null);

        assertEquals(3, persistedLogs.size());
        assertEquals("admin-user", persistedLogs.get(0).getOperName());
        assertEquals(OperatorType.MANAGE.ordinal(), persistedLogs.get(0).getOperatorType());
        assertNull(persistedLogs.get(1).getOperName());
        assertEquals(OperatorType.OTHER.ordinal(), persistedLogs.get(1).getOperatorType());
        assertNull(persistedLogs.get(2).getOperName());
        assertEquals(OperatorType.OTHER.ordinal(), persistedLogs.get(2).getOperatorType());
        assertEquals("unknown", persistedLogs.get(2).getOperIp());
    }

    @Test
    void asyncSubmissionFailureDoesNotChangeTheBusinessResult() throws Exception
    {
        AppUser appUser = new AppUser();
        appUser.setUserName("app-user");
        authenticate(new LoginAppUser(42L, appUser));
        ScheduledExecutorService rejectingExecutor = mock(ScheduledExecutorService.class);
        when(rejectingExecutor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RejectedExecutionException("audit-executor-down"));
        ReflectionTestUtils.setField(AsyncManager.me(), "executor", rejectingExecutor);

        assertDoesNotThrow(() -> invokeSuccess(null, null));
    }

    @Test
    void asyncPersistenceFailureIsLoggedByTheProductionExecutor() throws Exception
    {
        AppUser appUser = new AppUser();
        appUser.setUserName("app-user");
        authenticate(new LoginAppUser(42L, appUser));
        doThrow(new IllegalStateException("audit-db-down")).when(operLogService).insertOperlog(any(SysOperLog.class));

        CountDownLatch errorLogged = new CountDownLatch(1);
        Logger threadsLogger = (Logger) LoggerFactory.getLogger(LogCorrelation.class);
        AppenderBase<ILoggingEvent> appender = new AppenderBase<ILoggingEvent>()
        {
            @Override
            protected void append(ILoggingEvent event)
            {
                if (event.getLevel() == Level.ERROR && event.getFormattedMessage().contains("event=async.failure")
                        && event.getFormattedMessage().contains("errorType=IllegalStateException")
                        && !event.getFormattedMessage().contains("audit-db-down"))
                {
                    errorLogged.countDown();
                }
            }
        };
        appender.start();
        threadsLogger.addAppender(appender);
        try
        {
            testExecutorToShutdown = ReflectionTestUtils.invokeMethod(new ThreadPoolConfig(),
                    "scheduledExecutorService");
            ReflectionTestUtils.setField(AsyncManager.me(), "executor", testExecutorToShutdown);

            invokeSuccess(null, null);

            assertTrue(errorLogged.await(2, TimeUnit.SECONDS));
        }
        finally
        {
            threadsLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private void invokeSuccess(Object requestBody, Object responseBody) throws Exception
    {
        JoinPoint joinPoint = joinPoint(requestBody);
        aspect.doBefore(joinPoint, auditAnnotation());
        aspect.doAfterReturning(joinPoint, auditAnnotation(), responseBody);
    }

    private JoinPoint joinPoint(Object argument)
    {
        JoinPoint joinPoint = mock(JoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("audit");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(new AuditTarget());
        when(joinPoint.getArgs()).thenReturn(argument == null ? new Object[0] : new Object[] { argument });
        return joinPoint;
    }

    private Log auditAnnotation() throws Exception
    {
        return AuditTarget.class.getDeclaredMethod("audit", Object.class).getAnnotation(Log.class);
    }

    private void authenticate(Object principal)
    {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList()));
    }

    private static class AuditTarget
    {
        @Log(title = "audit-test")
        public void audit(Object ignored)
        {
        }
    }

    private static class SensitiveBody
    {
        private final String password;
        private final String mobile;
        private final String token;

        SensitiveBody(String password, String mobile, String token)
        {
            this.password = password;
            this.mobile = mobile;
            this.token = token;
        }

        public String getPassword()
        {
            return password;
        }

        public String getMobile()
        {
            return mobile;
        }

        public String getToken()
        {
            return token;
        }
    }

    @Test
    void actualAsyncManagerInlineExecutionRestoresContextEvenWhenTaskThrows()
    {
        org.slf4j.MDC.put("requestId", "audit_original_123");
        org.slf4j.MDC.put("commandId", "audit-command");
        org.slf4j.MDC.put("Authorization", "private-header-fixture");
        java.util.Map<String, String> before = org.slf4j.MDC.getCopyOfContextMap();
        IllegalStateException original = new IllegalStateException("private-payload-fixture");
        try
        {
            org.junit.jupiter.api.Assertions.assertSame(original, assertThrows(IllegalStateException.class,
                    () -> AsyncManager.me().execute(new java.util.TimerTask() {
                        @Override public void run() {
                            org.junit.jupiter.api.Assertions.assertEquals("audit_original_123", org.slf4j.MDC.get("requestId"));
                            org.junit.jupiter.api.Assertions.assertEquals("audit-command", org.slf4j.MDC.get("commandId"));
                            assertNull(org.slf4j.MDC.get("Authorization"));
                            org.slf4j.MDC.put("requestId", "changed_inside_task");
                            throw original;
                        }
                    })));
            org.junit.jupiter.api.Assertions.assertEquals(before, org.slf4j.MDC.getCopyOfContextMap());
        }
        finally { org.slf4j.MDC.clear(); }
    }

    /** Avoid eager class initialization in the static field reflection helper. */
    private static final class SpringUtilsHolder
    {
        private static final Class<?> TYPE = com.chengyinhub.common.utils.spring.SpringUtils.class;
    }
}
