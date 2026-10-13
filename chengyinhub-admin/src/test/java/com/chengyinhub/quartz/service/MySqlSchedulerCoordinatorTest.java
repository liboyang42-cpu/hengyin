package com.chengyinhub.quartz.service;

import com.chengyinhub.quartz.util.MySqlSessionLock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MySqlSchedulerCoordinatorTest {
    public static class Target { public void run() { } public void other() { } }
    private final String target = Target.class.getName() + ".run()";
    private MySqlSchedulerCoordinator coordinator(SchedulerJdbcFixture jdbc, List<Runnable> callbacks) {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        when(executor.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(call -> { callbacks.add(call.getArgument(0)); return mock(ScheduledFuture.class); });
        MySqlSchedulerCoordinator value = new MySqlSchedulerCoordinator(jdbc.source, executor);
        ReflectionTestUtils.setField(value, "running", true); return value;
    }
    @Test void onlyLeaderLoadsPlansAndSameFireTimeCannotRunTwiceEvenAfterHandoff() {
        SchedulerJdbcFixture jdbc = new SchedulerJdbcFixture();
        MySqlSchedulerCoordinator first = coordinator(jdbc, new ArrayList<>()), second = coordinator(jdbc, new ArrayList<>());
        AtomicInteger loads = new AtomicInteger(), work = new AtomicInteger();
        first.registerQuartz(loads::incrementAndGet, () -> { }); second.registerQuartz(loads::incrementAndGet, () -> { });
        first.maintainLeadership(); second.maintainLeadership();
        assertTrue(first.isLeader()); assertFalse(second.isLeader()); assertEquals(1, loads.get());
        ReflectionTestUtils.setField(first, "activatedAt", 100L);
        Date fire = new Date(101L);
        assertTrue(first.execute(target, fire, work::incrementAndGet));
        assertFalse(first.execute(target, fire, work::incrementAndGet));
        first.stop(); second.maintainLeadership();
        assertTrue(second.isLeader()); assertEquals(2, loads.get());
        assertFalse(second.execute(target, fire, work::incrementAndGet)); assertEquals(1, work.get()); second.stop();
    }
    @Test void lostLeaderCancelsPlansAndQueuedOldGenerationCannotRestartWork() {
        SchedulerJdbcFixture jdbc = new SchedulerJdbcFixture(); List<Runnable> callbacks = new ArrayList<>();
        MySqlSchedulerCoordinator owner = coordinator(jdbc, callbacks);
        ReflectionTestUtils.setField(owner, "running", false);
        AtomicInteger work = new AtomicInteger(), stopped = new AtomicInteger(), reads = new AtomicInteger();
        owner.registerFixedDelay(target, Duration.ofSeconds(5), Duration.ZERO, work::incrementAndGet);
        owner.registerQuartz(reads::incrementAndGet, stopped::incrementAndGet);
        ReflectionTestUtils.setField(owner, "running", true); owner.maintainLeadership();
        assertEquals(1, callbacks.size()); callbacks.get(0).run(); assertEquals(1, work.get());
        jdbc.loseAllSessions(); owner.maintainLeadership(); assertFalse(owner.isLeader()); assertEquals(1, stopped.get());
        owner.maintainLeadership(); assertTrue(owner.isLeader()); assertEquals(2, reads.get());
        callbacks.get(0).run(); assertEquals(1, work.get()); callbacks.get(1).run(); assertEquals(2, work.get()); owner.stop();
    }
    @Test void oldInFlightTaskSessionBlocksSuccessorAndExceptionsRemainVisible() {
        SchedulerJdbcFixture jdbc = new SchedulerJdbcFixture(); MySqlSchedulerCoordinator owner = coordinator(jdbc, new ArrayList<>());
        owner.maintainLeadership();
        String canonical = com.chengyinhub.quartz.util.CanonicalJobTarget.canonical(target);
        try (MySqlSessionLock.Lease previous = MySqlSessionLock.acquire(jdbc.source, "task", canonical, 0)) {
            assertFalse(owner.execute(target, null, () -> fail("previous work still owns lock")));
        }
        IllegalStateException original = new IllegalStateException("task-boom");
        assertSame(original, assertThrows(IllegalStateException.class, () -> owner.execute(target, null, () -> { throw original; })));
        assertTrue(owner.execute(target, null, () -> { })); owner.stop();
    }
    @Test void workSessionLossDoesNotReportSuccessfulExecution() {
        SchedulerJdbcFixture jdbc = new SchedulerJdbcFixture(); MySqlSchedulerCoordinator owner = coordinator(jdbc, new ArrayList<>());
        owner.maintainLeadership();
        IllegalStateException unknown = assertThrows(IllegalStateException.class,
                () -> owner.execute(target, null, jdbc::loseAllSessions));
        assertTrue(unknown.getMessage().contains("需核实")); owner.maintainLeadership(); assertFalse(owner.isLeader()); owner.stop();
    }
    @Test void disabledDoesNotAcquireDatabaseSessions() {
        SchedulerJdbcFixture jdbc = new SchedulerJdbcFixture(); MySqlSchedulerCoordinator disabled = coordinator(jdbc, new ArrayList<>());
        ReflectionTestUtils.setField(disabled, "jobsEnabled", false); disabled.start(); disabled.maintainLeadership();
        assertFalse(disabled.execute(target, null, () -> fail("disabled"))); verifyNoInteractions(jdbc.source); disabled.stop();
    }
}
