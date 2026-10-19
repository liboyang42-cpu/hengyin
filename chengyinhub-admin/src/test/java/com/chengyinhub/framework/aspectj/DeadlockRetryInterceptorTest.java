package com.chengyinhub.framework.aspectj;

import com.chengyinhub.common.annotation.DeadlockRetry;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.framework.config.DeadlockRetryConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 死锁重试边界：用与生产相同的装配（AspectJ 自动代理 + 默认顺序的事务通知器 + DeadlockRetryConfig），
 * 证明重试包在事务外层、每次是一笔新事务，且只在最外层、只对锁冲突、最多 2 次。
 */
class DeadlockRetryInterceptorTest {
    private AnnotationConfigApplicationContext context;
    private Worker worker;
    private Outer outer;

    @BeforeEach
    void start() {
        context = new AnnotationConfigApplicationContext(Wiring.class);
        worker = context.getBean(Worker.class);
        outer = context.getBean(Outer.class);
    }

    @AfterEach
    void stop() {
        context.close();
    }

    @Test
    void deadlockRollsBackWholeTransactionThenRetriesOnceInFreshTransaction() {
        assertEquals("ok", worker.failFirst(new DeadlockLoserDataAccessException("1213", null)));
        assertEquals(Arrays.asList("attempt1:tx", "rolled_back", "attempt2:tx", "committed"), worker.events());
    }

    @Test
    void lockWaitTimeoutIsRetriedOnceToo() {
        assertEquals("ok", worker.failFirst(new CannotAcquireLockException("1205")));
        assertEquals(Arrays.asList("attempt1:tx", "rolled_back", "attempt2:tx", "committed"), worker.events());
    }

    @Test
    void givesUpAfterTwoAttemptsAndRethrowsTheLockFailure() {
        assertThrows(DeadlockLoserDataAccessException.class, worker::alwaysDeadlock);
        assertEquals(Arrays.asList("attempt1:tx", "rolled_back", "attempt2:tx", "rolled_back"), worker.events());
    }

    @Test
    void otherFailuresAreNotRetried() {
        assertThrows(DataIntegrityViolationException.class,
                () -> worker.failFirst(new DataIntegrityViolationException("dup")));
        assertEquals(Arrays.asList("attempt1:tx", "rolled_back"), worker.events());
    }

    @Test
    void methodsWithoutTheAnnotationAreNotRetried() {
        assertThrows(DeadlockLoserDataAccessException.class, worker::unannotatedDeadlock);
        assertEquals(Arrays.asList("attempt1:tx", "rolled_back"), worker.events());
    }

    @Test
    void innerParticipantDoesNotRetryAndLeavesItToTheOuterBoundary() {
        assertThrows(DeadlockLoserDataAccessException.class, () -> outer.callInside(worker));
        assertEquals(Arrays.asList("attempt1:tx", "rolled_back"), worker.events(),
                "外层已有事务时内层不得重试：失败的是整笔外层事务");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rateLimiterCountsOnceAcrossTheRetry() {
        // master 的打卡端点同时挂 @RateLimiter（Redis 计数不随事务回滚）：限流必须在重试外层，只计一次。
        RedisTemplate<Object, Object> redis = context.getBean(RedisTemplate.class);
        assertEquals("ok", worker.limitedFailFirst());
        assertEquals(Arrays.asList("attempt1:tx", "rolled_back", "attempt2:tx", "committed"), worker.events());
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void retryAdvisorIsOrderedOutsideTheDefaultTransactionAdvisor() {
        assertTrue(DeadlockRetryConfig.ORDER < org.springframework.core.Ordered.LOWEST_PRECEDENCE);
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({DeadlockRetryConfig.class, RateLimiterAspect.class})
    static class Wiring {
        @Bean
        @SuppressWarnings("unchecked")
        RedisTemplate<Object, Object> redisTemplate() {
            RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
            when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
            return redis;
        }

        @Bean
        @SuppressWarnings("unchecked")
        RedisScript<Long> limitScript() {
            return mock(RedisScript.class);
        }

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:deadlock_retry;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        Worker worker() {
            return new Worker();
        }

        @Bean
        Outer outer() {
            return new Outer();
        }
    }

    static class Worker {
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());
        private int attempts;

        /** 经代理转发到目标对象（CGLIB 代理实例自身的字段未初始化）。 */
        public List<String> events() {
            return events;
        }

        @DeadlockRetry
        @Transactional(rollbackFor = Exception.class)
        public String failFirst(RuntimeException failure) {
            if (begin() == 1) throw failure;
            return "ok";
        }

        @DeadlockRetry
        @Transactional(rollbackFor = Exception.class)
        public void alwaysDeadlock() {
            begin();
            throw new DeadlockLoserDataAccessException("1213", null);
        }

        @RateLimiter(time = 10, count = 120)
        @DeadlockRetry
        @Transactional(rollbackFor = Exception.class)
        public String limitedFailFirst() {
            if (begin() == 1) throw new DeadlockLoserDataAccessException("1213", null);
            return "ok";
        }

        @Transactional(rollbackFor = Exception.class)
        public void unannotatedDeadlock() {
            begin();
            throw new DeadlockLoserDataAccessException("1213", null);
        }

        private int begin() {
            attempts++;
            boolean active = TransactionSynchronizationManager.isActualTransactionActive();
            events.add("attempt" + attempts + (active ? ":tx" : ":no-tx"));
            if (active && !outerOwnsTransaction()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        events.add(status == STATUS_COMMITTED ? "committed" : "rolled_back");
                    }
                });
            }
            return attempts;
        }

        /** Outer 在自己事务里登记收尾事件，Worker 不重复登记。 */
        private boolean outerOwnsTransaction() {
            return Boolean.TRUE.equals(Outer.ACTIVE.get());
        }
    }

    static class Outer {
        static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

        @Transactional(rollbackFor = Exception.class)
        public void callInside(Worker worker) {
            ACTIVE.set(true);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    ACTIVE.remove();
                    worker.events().add(status == STATUS_COMMITTED ? "committed" : "rolled_back");
                }
            });
            worker.alwaysDeadlock();
        }
    }
}
