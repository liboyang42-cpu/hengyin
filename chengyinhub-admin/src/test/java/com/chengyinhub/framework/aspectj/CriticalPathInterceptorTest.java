package com.chengyinhub.framework.aspectj;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.common.annotation.CriticalPath;
import com.chengyinhub.common.annotation.DeadlockRetry;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.observability.CriticalPathCode;
import com.chengyinhub.common.observability.CriticalPathEvents;
import com.chengyinhub.framework.config.CriticalPathConfig;
import com.chengyinhub.framework.config.DeadlockRetryConfig;
import com.alibaba.druid.pool.GetConnectionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O-11 关键路径失败事件：用与生产相同的装配（AspectJ 自动代理 + 默认顺序事务通知器 + 死锁重试 + 本切面），
 * 证明失败只发一行固定格式事件、原异常原样抛出、嵌套只报一次、重试成功不报，且行内不带异常 message。
 */
class CriticalPathInterceptorTest {
    private static final String SECRET = "member=13800138000 openid=oABC notify=4200001";

    private AnnotationConfigApplicationContext context;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private Level previousLevel;
    private Worker worker;

    @BeforeEach
    void start() {
        logger = (Logger) LoggerFactory.getLogger("com.chengyinhub.critical");
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        context = new AnnotationConfigApplicationContext(Wiring.class);
        worker = context.getBean(Worker.class);
    }

    @AfterEach
    void stop() {
        context.close();
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    void poolTimeoutIsOneFaultEventWithRootCauseAndOriginalExceptionRethrown() {
        long before = CriticalPathEvents.count(CriticalPathCode.CP_REFUND_FAILED, CriticalPathEvents.Kind.FAULT);
        CannotGetJdbcConnectionException failure = new CannotGetJdbcConnectionException(SECRET,
                new java.sql.SQLException(SECRET, new GetConnectionTimeoutException("wait millis 5000, active 20, maxActive 20")));
        assertSame(failure, assertThrows(CannotGetJdbcConnectionException.class, () -> worker.fail(failure)));
        assertEquals(List.of("[CY-CRITICAL] code=CP_REFUND_FAILED kind=FAULT op=review "
                + "cause=CannotGetJdbcConnectionException root=GetConnectionTimeoutException origin="
                + location(failure.getCause().getCause().getStackTrace()[0])), lines());
        assertEquals(Level.ERROR, appender.list.get(0).getLevel());
        assertEquals(before + 1, CriticalPathEvents.count(CriticalPathCode.CP_REFUND_FAILED, CriticalPathEvents.Kind.FAULT));
    }

    @Test
    void redisOutageIsFault() {
        RedisConnectionFailureException failure = new RedisConnectionFailureException(SECRET);
        assertThrows(RedisConnectionFailureException.class, () -> worker.fail(failure));
        assertEquals(List.of("[CY-CRITICAL] code=CP_REFUND_FAILED kind=FAULT op=review "
                + "cause=RedisConnectionFailureException root=RedisConnectionFailureException origin="
                + location(failure.getStackTrace()[0])), lines());
    }

    @Test
    void businessRejectionsAreCountedAsRejectedAtInfo() {
        assertThrows(IllegalStateException.class, () -> worker.fail(new IllegalStateException(SECRET)));
        assertThrows(IllegalArgumentException.class, () -> worker.fail(new IllegalArgumentException(SECRET)));
        assertThrows(ServiceException.class, () -> worker.fail(new ServiceException(SECRET)));
        assertThrows(RuntimeException.class, () -> worker.fail(new RuntimeException(SECRET)));
        assertEquals(4, appender.list.size());
        for (ILoggingEvent event : appender.list) {
            assertEquals(Level.INFO, event.getLevel());
            assertTrue(event.getFormattedMessage().contains(" kind=REJECTED "), event.getFormattedMessage());
        }
    }

    @Test
    void nestedCriticalPathsReportTheSameFailureOnlyOnceWithInnermostCode() {
        NullPointerException failure = assertThrows(NullPointerException.class, worker::outerCallsInner);
        assertEquals(List.of("[CY-CRITICAL] code=CP_SETTLEMENT_ACCRUAL_FAILED kind=FAULT op=payment "
                + "cause=NullPointerException root=NullPointerException origin="
                + location(failure.getStackTrace()[0])), lines());
    }

    @Test
    void wrappedFailureIsNotReportedTwice() {
        NullPointerException inner = new NullPointerException(SECRET);
        assertTrue(CriticalPathEvents.failure(CriticalPathCode.CP_REDEMPTION_FAILED, "coupon", inner));
        assertFalse(CriticalPathEvents.failure(CriticalPathCode.CP_PAYMENT_INBOX_FAILED, "process",
                new IllegalStateException("wrap", inner)));
        assertEquals(1, appender.list.size());
    }

    @Test
    void deadlockRetrySuccessEmitsNothing() {
        assertEquals("ok", worker.deadlockOnce());
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void successEmitsNothingAndReturnValueIsUnchanged() {
        assertEquals("ok", worker.succeed());
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void lineNeverCarriesExceptionMessageOrBusinessData() {
        assertThrows(IllegalStateException.class, () -> worker.fail(new IllegalStateException(SECRET)));
        assertTrue(CriticalPathEvents.failure(CriticalPathCode.CP_CONSENT_WRITE_FAILED, "Bad op! " + SECRET,
                new UnsupportedOperationException(SECRET)));
        for (ILoggingEvent event : appender.list) {
            String line = event.getFormattedMessage();
            assertFalse(line.contains("13800138000") || line.contains("oABC") || line.contains("4200001"), line);
            assertNull(event.getThrowableProxy(), "事件行不能附带堆栈（堆栈 message 可能含业务数据）");
            assertTrue(line.matches("\\[CY-CRITICAL] code=CP_[A-Z_]+ kind=(FAULT|REJECTED) op=[a-z][a-z0-9_]{0,31} "
                    + "cause=[A-Za-z0-9_$]+ root=[A-Za-z0-9_$]+ "
                    + "origin=(unknown|com\\.chengyinhub\\.[A-Za-z0-9_.$]+#[A-Za-z0-9_$<>]+:-?[0-9]+)"), line);
        }
        assertTrue(lines().get(1).contains(" op=invalid "));
    }

    @Test
    void nullInputsNeverThrow() {
        assertFalse(CriticalPathEvents.failure(null, "x", new RuntimeException()));
        assertFalse(CriticalPathEvents.failure(CriticalPathCode.CP_REFUND_FAILED, "x", null));
    }

    @Test
    void realFailureOriginLocatesTheProjectThrowWithoutLeakingSensitiveMessage() {
        NullPointerException root = assertThrows(NullPointerException.class,
                CriticalPathInterceptorTest::failWithSecretMessage);
        assertTrue(CriticalPathEvents.failure(CriticalPathCode.CP_REFUND_FAILED, "review",
                new CannotGetJdbcConnectionException(SECRET, new java.sql.SQLException("fixture wrapper", root))));
        assertEquals(List.of("[CY-CRITICAL] code=CP_REFUND_FAILED kind=FAULT op=review "
                + "cause=CannotGetJdbcConnectionException root=NullPointerException origin="
                + location(root.getStackTrace()[0])), lines());
        assertTrue(root.getStackTrace()[0].getLineNumber() > 0);
        assertTrue(lines().get(0).contains("#failWithSecretMessage:"));
        assertFalse(lines().get(0).contains(SECRET));
        assertFalse(lines().get(0).contains("13800138000"));
        assertFalse(lines().get(0).contains("oABC"));
        assertFalse(lines().get(0).contains("4200001"));
        assertNull(appender.list.get(0).getThrowableProxy());
    }

    @Test
    void absentOutOfBudgetOrUnsafeProjectFramesFallBackToUnknown() {
        StackTraceElement external = new StackTraceElement("java.sql.Driver", "execute", SECRET, 12);
        StackTraceElement project = new StackTraceElement("com.chengyinhub.business.Worker", "execute", SECRET, 34);
        StackTraceElement[] beyondBudget = new StackTraceElement[65];
        java.util.Arrays.fill(beyondBudget, external);
        beyondBudget[64] = project;
        StackTraceElement unsafe = new StackTraceElement("com.chengyinhub.business.Worker", "execute\n" + SECRET, SECRET, 56);
        for (StackTraceElement[] frames : List.of(new StackTraceElement[] {external}, beyondBudget,
                new StackTraceElement[] {unsafe})) {
            NullPointerException failure = new NullPointerException(SECRET);
            failure.setStackTrace(frames);
            assertTrue(CriticalPathEvents.failure(CriticalPathCode.CP_REFUND_FAILED, "review", failure));
            ILoggingEvent event = appender.list.get(appender.list.size() - 1);
            assertEquals("[CY-CRITICAL] code=CP_REFUND_FAILED kind=FAULT op=review "
                    + "cause=NullPointerException root=NullPointerException origin=unknown", event.getFormattedMessage());
            assertNull(event.getThrowableProxy());
        }
        assertEquals(3, appender.list.size());
    }

    private static void failWithSecretMessage() {
        throw new NullPointerException(SECRET);
    }

    private static String location(StackTraceElement frame) {
        return frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber();
    }

    private List<String> lines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    @Configuration
    @EnableAspectJAutoProxy(exposeProxy = true)
    @EnableTransactionManagement
    @Import({CriticalPathConfig.class, DeadlockRetryConfig.class})
    static class Wiring {
        @Bean
        PlatformTransactionManager transactionManager() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:critical;DB_CLOSE_DELAY=-1", "sa", "");
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        Inner inner() {
            return new Inner();
        }

        @Bean
        Worker worker(Inner inner) {
            return new Worker(inner);
        }
    }

    static class Inner {
        @Transactional
        @CriticalPath(value = CriticalPathCode.CP_SETTLEMENT_ACCRUAL_FAILED, op = "payment")
        public void accrue() {
            throw new NullPointerException(SECRET);
        }
    }

    static class Worker {
        private final Inner inner;
        private int deadlocks;

        Worker(Inner inner) {
            this.inner = inner;
        }

        @CriticalPath(value = CriticalPathCode.CP_REFUND_FAILED, op = "review")
        public String fail(RuntimeException failure) {
            throw failure;
        }

        @CriticalPath(value = CriticalPathCode.CP_REFUND_FAILED, op = "review")
        public String succeed() {
            return "ok";
        }

        @Transactional
        @CriticalPath(value = CriticalPathCode.CP_REDEMPTION_FAILED, op = "coupon")
        public void outerCallsInner() {
            inner.accrue();
        }

        @DeadlockRetry
        @Transactional
        @CriticalPath(value = CriticalPathCode.CP_REDEMPTION_FAILED, op = "chapter")
        public String deadlockOnce() {
            if (deadlocks++ == 0) {
                throw new DeadlockLoserDataAccessException("1213", null);
            }
            return "ok";
        }
    }
}
