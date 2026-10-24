package com.chengyinhub.framework.manager;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import com.chengyinhub.common.utils.spring.SpringUtils;
import java.util.concurrent.ScheduledExecutorService;
import org.mockito.MockedStatic;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShutdownManagerTest
{
    private ConfigurableListableBeanFactory originalBeanFactory;

    @BeforeEach
    void setUp()
    {
        originalBeanFactory = (ConfigurableListableBeanFactory) ReflectionTestUtils.getField(
                SpringUtils.class, "beanFactory");
        ConfigurableListableBeanFactory factory = mock(ConfigurableListableBeanFactory.class);
        when(factory.getBean("scheduledExecutorService")).thenReturn(mock(ScheduledExecutorService.class));
        ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", factory);
    }

    @AfterEach
    void tearDown()
    {
        ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", originalBeanFactory);
    }

    @Test
    void cancelsFutureScheduledWorkAndDrainsOrdinaryAsyncWork() throws Exception
    {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        FutureWork work = new FutureWork();
        ScheduledAnnotationBeanPostProcessor tasks = register(executor, work);
        CountDownLatch auditCompleted = new CountDownLatch(1);
        executor.schedule(auditCompleted::countDown, 10, TimeUnit.MILLISECONDS);
        AsyncManager async = mock(AsyncManager.class);
        doAnswer(call -> {
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS),
                    "future scheduled work must not keep the shared executor alive");
            return null;
        }).when(async).shutdown();
        try (MockedStatic<AsyncManager> singleton = mockStatic(AsyncManager.class))
        {
            singleton.when(AsyncManager::me).thenReturn(async);
            new ShutdownManager(Optional.of(tasks)).destroy();
            verify(async).shutdown();
            assertEquals(0, auditCompleted.getCount(), "ordinary async work must finish");
            assertFalse(work.executed.get(), "future background work must not run at shutdown");
        }
        finally
        {
            tasks.destroy();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void letsAnAlreadyRunningScheduledTaskFinishWithoutInterruption() throws Exception
    {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        RunningWork work = new RunningWork();
        ScheduledAnnotationBeanPostProcessor tasks = register(executor, work);
        AsyncManager async = mock(AsyncManager.class);
        doAnswer(call -> {
            assertFalse(work.interrupted.get());
            work.release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            return null;
        }).when(async).shutdown();
        try (MockedStatic<AsyncManager> singleton = mockStatic(AsyncManager.class))
        {
            assertTrue(work.started.await(2, TimeUnit.SECONDS));
            singleton.when(AsyncManager::me).thenReturn(async);
            new ShutdownManager(Optional.of(tasks)).destroy();
            verify(async).shutdown();
            assertTrue(work.completed.get());
            assertFalse(work.interrupted.get(), "in-flight work must be drained, not interrupted");
        }
        finally
        {
            work.release.countDown();
            tasks.destroy();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void startsAndStopsWhenScheduledWorkIsDisabled()
    {
        AsyncManager async = mock(AsyncManager.class);
        try (MockedStatic<AsyncManager> singleton = mockStatic(AsyncManager.class))
        {
            singleton.when(AsyncManager::me).thenReturn(async);
            try (AnnotationConfigApplicationContext context =
                    new AnnotationConfigApplicationContext(ShutdownManager.class))
            {
                assertNotNull(context.getBean(ShutdownManager.class));
                assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
            }
            verify(async).shutdown();
        }
    }

    private ScheduledAnnotationBeanPostProcessor register(ScheduledThreadPoolExecutor executor, Object bean)
    {
        ScheduledAnnotationBeanPostProcessor tasks = new ScheduledAnnotationBeanPostProcessor();
        tasks.setScheduler(executor);
        tasks.postProcessAfterInitialization(bean, "shutdownRegressionWork");
        tasks.afterSingletonsInstantiated();
        assertFalse(tasks.getScheduledTasks().isEmpty());
        return tasks;
    }

    static class FutureWork
    {
        final AtomicBoolean executed = new AtomicBoolean();

        @Scheduled(initialDelay = 3600000, fixedDelay = 3600000)
        public void run() { executed.set(true); }
    }

    static class RunningWork
    {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean interrupted = new AtomicBoolean();

        @Scheduled(fixedDelay = 3600000)
        public void run()
        {
            started.countDown();
            try
            {
                completed.set(release.await(5, TimeUnit.SECONDS));
            }
            catch (InterruptedException ex)
            {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        }
    }
}
