package com.chengyinhub.quartz.util;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.quartz.service.MySqlSchedulerCoordinator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL/Druid/strict-wall coordination; manual clocks/barriers only, no Quartz or business effects. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class SchedulerHandoffBoundaryMySqlIT
{
    public static class ProbeTask { public void run(String identity) { } }

    private static final String TARGET = ProbeTask.class.getName() + ".run('work-1')";
    private static final String SAME_TARGET = ProbeTask.class.getName() + ".run(\"work-1\")";
    private static final String OTHER_TARGET = ProbeTask.class.getName() + ".run('work-2')";
    private DruidDataSource source;
    private JdbcTemplate admin;
    private String schema;
    private String user;
    private String password;
    private boolean created;

    @BeforeAll
    void connect() throws Exception
    {
        int port = Integer.parseInt(required("CHENGYIN_MYBATIS_IT_PORT"));
        assertTrue(port > 0 && port <= 65535);
        user = required("CHENGYIN_MYBATIS_IT_USER");
        password = System.getenv("CHENGYIN_MYBATIS_IT_PASSWORD");
        assertNotNull(password, "CHENGYIN_MYBATIS_IT_PASSWORD must be explicitly present (empty is allowed)");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + "mysql" + options, user, password));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        schema = "cy_sched_boundary_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + schema);
        created = true;
        source = new DruidDataSource();
        source.setUrl(base + schema + options);
        source.setUsername(user);
        source.setPassword(password);
        source.setMaxActive(8);
        source.setMinIdle(0);
        source.setInitialSize(0);
        source.setMaxWait(3000);
        WallConfig config = new WallConfig();
        config.setMultiStatementAllow(false);
        WallFilter wall = new WallFilter();
        wall.setDbType("mysql");
        wall.setConfig(config);
        source.setProxyFilters(List.of(wall));
        source.init();
        try (Connection connection = source.getConnection())
        {
            assertEquals(schema, connection.getCatalog());
        }
    }

    @AfterAll
    void cleanup()
    {
        if (source != null) source.close();
        if (created)
        {
            admin.execute("drop database " + schema);
            assertEquals(0, admin.queryForObject(
                    "select count(*) from information_schema.schemata where schema_name=?", Integer.class, schema));
            created = false;
        }
    }

    @Test
    void inFlightTaskSessionSurvivesLeaderDisconnectAndBlocksSameCanonicalSuccessor() throws Exception
    {
        CapturingTimer timer = new CapturingTimer();
        MySqlSchedulerCoordinator first = coordinator(source, timer), second = coordinator(source, timer);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger(), completed = new AtomicInteger(), successorWork = new AtomicInteger();
        assertEquals(CanonicalJobTarget.canonical(TARGET), CanonicalJobTarget.canonical(SAME_TARGET));
        try
        {
            first.maintainLeadership();
            second.maintainLeadership();
            assertTrue(first.isLeader());
            assertFalse(second.isLeader());
            Long leaderId = owner("leader", "all");
            assertNotNull(leaderId);
            Future<Boolean> oldWork = worker.submit(() -> first.execute(TARGET, null, () -> {
                started.incrementAndGet();
                entered.countDown();
                await(finish);
                completed.incrementAndGet();
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS), "old task has actually entered its action");
            Long workId = owner("task", CanonicalJobTarget.canonical(TARGET));
            assertNotNull(workId);
            assertNotEquals(leaderId, workId, "leader and in-flight task must be separate physical sessions");

            killLeader(leaderId);
            second.maintainLeadership();
            assertTrue(second.isLeader(), "successor acquires the server-released leadership session");
            assertTrue(first.isLeader(), "old process has not yet refreshed its local leader flag");
            assertEquals(workId, owner("task", CanonicalJobTarget.canonical(TARGET)));
            assertFalse(second.execute(SAME_TARGET, null, successorWork::incrementAndGet), "same invocation is still locked by old work");
            assertEquals(0, successorWork.get());
            assertEquals(1, started.get());
            assertEquals(0, completed.get());
            assertTrue(second.execute(OTHER_TARGET, null, () -> { }), "independent identity is not globally blocked");
            assertFalse(first.execute(OTHER_TARGET, null, () -> fail("stale leader must not start new work")));
            assertFalse(first.isLeader());

            finish.countDown();
            assertTrue(oldWork.get(10, TimeUnit.SECONDS), "already admitted work may finish while its task session remains owned");
            assertEquals(1, completed.get());
            assertNull(owner("task", CanonicalJobTarget.canonical(TARGET)));
            // A new explicit invocation after release; this is not replay of a persisted business intent/fireTime.
            assertTrue(second.execute(SAME_TARGET, null, successorWork::incrementAndGet));
            assertEquals(1, successorWork.get());
        }
        finally
        {
            finish.countDown();
            worker.shutdownNow();
            try { assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS)); }
            finally
            {
                first.stop();
                second.stop();
                timer.shutdownNow();
                assertNoLocks(TARGET, OTHER_TARGET);
            }
        }
    }

    @Test
    void dequeuedOldEpochCallbackCannotStartAfterTheSameCoordinatorReacquiresLeadership() throws Exception
    {
        CapturingTimer timer = new CapturingTimer();
        MySqlSchedulerCoordinator coordinator = new MySqlSchedulerCoordinator(source, timer);
        AtomicInteger effects = new AtomicInteger(), loaded = new AtomicInteger(), stopped = new AtomicInteger();
        coordinator.registerFixedDelay(TARGET, Duration.ofSeconds(5), Duration.ZERO, effects::incrementAndGet);
        coordinator.registerQuartz(loaded::incrementAndGet, stopped::incrementAndGet);
        ReflectionTestUtils.setField(coordinator, "running", true);
        try
        {
            coordinator.maintainLeadership();
            assertTrue(coordinator.isLeader());
            assertEquals(1, timer.callbacks.size());
            Captured old = timer.callbacks.get(0);
            old.command.run();
            assertEquals(1, effects.get());
            Long originalSession = owner("leader", "all");
            assertNotNull(originalSession);
            killLeader(originalSession);
            coordinator.maintainLeadership();
            assertFalse(coordinator.isLeader());
            assertEquals(1, stopped.get());
            assertTrue(old.isCancelled());
            old.command.run(); // A callback already dequeued before cancellation, not FutureTask.run().
            assertEquals(1, effects.get());

            coordinator.maintainLeadership();
            assertTrue(coordinator.isLeader());
            assertEquals(2, loaded.get());
            assertEquals(2, timer.callbacks.size());
            assertNotEquals(originalSession, owner("leader", "all"));
            old.command.run(); // isLeader=true again: only its originally captured generation must fence it.
            assertEquals(1, effects.get());
            timer.callbacks.get(1).command.run();
            assertEquals(2, effects.get(), "new epoch callback really runs with real task lock SQL");
        }
        finally
        {
            coordinator.stop();
            timer.shutdownNow();
            assertNoLocks(TARGET);
        }
    }

    @Test
    void oldLeaderPausedAfterFirstOwnershipCheckCannotRunAfterLateTaskLockAcquisition() throws Exception
    {
        CapturingTimer timer = new CapturingTimer();
        PausingDataSource pausing = new PausingDataSource(source);
        MySqlSchedulerCoordinator first = coordinator(pausing, timer), second = coordinator(source, timer);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Gate gate = new Gate();
        AtomicInteger oldEffects = new AtomicInteger(), newEffects = new AtomicInteger();
        try
        {
            first.maintainLeadership();
            second.maintainLeadership();
            assertTrue(first.isLeader());
            assertFalse(second.isLeader());
            Long originalSession = owner("leader", "all");
            assertNotNull(originalSession);
            pausing.next.set(gate);
            Future<Boolean> late = worker.submit(() -> first.execute(TARGET, null, oldEffects::incrementAndGet));
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS), "task acquire reached source.getConnection after the first leader check");
            assertNull(owner("task", CanonicalJobTarget.canonical(TARGET)));
            killLeader(originalSession);
            second.maintainLeadership();
            assertTrue(second.isLeader());
            assertTrue(first.isLeader(), "do not refresh old state before exercising the post-acquire ownership check");
            assertTrue(second.execute(SAME_TARGET, null, newEffects::incrementAndGet));
            assertEquals(1, newEffects.get());

            gate.resume.countDown();
            assertFalse(late.get(10, TimeUnit.SECONDS));
            assertEquals(0, oldEffects.get(), "late old execution must be rejected after acquiring a real task session");
            assertNull(owner("task", CanonicalJobTarget.canonical(TARGET)));
            first.maintainLeadership();
            assertFalse(first.isLeader());
        }
        finally
        {
            gate.resume.countDown();
            worker.shutdownNow();
            try { assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS)); }
            finally
            {
                first.stop();
                second.stop();
                timer.shutdownNow();
                assertNoLocks(TARGET);
            }
        }
    }

    private MySqlSchedulerCoordinator coordinator(DataSource dataSource, CapturingTimer timer)
    {
        MySqlSchedulerCoordinator value = new MySqlSchedulerCoordinator(dataSource, timer);
        ReflectionTestUtils.setField(value, "running", true);
        return value;
    }

    private Long owner(String kind, String target) throws Exception
    {
        String key = MySqlSessionLock.key(kind, schema, target);
        try (Connection connection = source.getConnection();
             java.sql.PreparedStatement query = connection.prepareStatement("SELECT IS_USED_LOCK(?)"))
        {
            query.setQueryTimeout(3);
            query.setString(1, key);
            try (ResultSet row = query.executeQuery())
            {
                assertTrue(row.next());
                long id = row.getLong(1);
                return row.wasNull() ? null : id;
            }
        }
    }

    private void killLeader(Long expectedId) throws Exception
    {
        assertNotNull(expectedId);
        assertTrue(expectedId > 0);
        assertEquals(expectedId, owner("leader", "all"), "kill only the observed leader of this random task-owned schema");
        // Strict wall intentionally forbids KILL; separate real loopback control connection, as in existing IT.
        try (Connection control = DriverManager.getConnection(source.getUrl(), user, password);
             Statement statement = control.createStatement())
        {
            statement.setQueryTimeout(3);
            statement.execute("KILL CONNECTION " + expectedId);
        }
        // KILL can return before remote session teardown completes: one blocking server lock acquisition
        // is the release barrier, never a sleep/poll/retry or local ownership override.
        try (MySqlSessionLock.Lease released = MySqlSessionLock.acquire(source, "leader", "all", 3))
        {
            assertNotNull(released, "killed leader must actually release the server lock");
            assertTrue(released.isOwned());
            assertNotEquals(expectedId, owner("leader", "all"));
        }
        assertNull(owner("leader", "all"), "server must actually release leadership, not a mocked local flag");
    }

    private void assertNoLocks(String... targets) throws Exception
    {
        assertNull(owner("leader", "all"));
        for (String target : targets) assertNull(owner("task", CanonicalJobTarget.canonical(target)));
    }

    private static void await(CountDownLatch latch)
    {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "controlled barrier was not released"); }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted controlled task", interrupted);
        }
    }

    private static String required(String name)
    {
        String value = System.getenv(name);
        assertNotNull(value, "must explicitly provide " + name);
        assertFalse(value.isBlank(), "must explicitly provide nonblank " + name);
        return value;
    }

    private static final class Gate
    {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
    }

    /** Does not fake JDBC or SQL results: pause once before obtaining the next real connection. */
    private static final class PausingDataSource extends DelegatingDataSource
    {
        final AtomicReference<Gate> next = new AtomicReference<>();
        PausingDataSource(DataSource delegate) { super(delegate); }
        @Override public Connection getConnection() throws SQLException
        {
            Gate gate = next.getAndSet(null);
            if (gate != null) { gate.entered.countDown(); await(gate.resume); }
            return super.getConnection();
        }
    }

    /** Capture production-created callbacks without a control loop, sleep, retry or background execution. */
    private static final class CapturingTimer extends ScheduledThreadPoolExecutor
    {
        final List<Captured> callbacks = new ArrayList<>();
        CapturingTimer() { super(1); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initial, long delay, TimeUnit unit)
        {
            Captured captured = new Captured(command);
            callbacks.add(captured);
            return captured;
        }
    }

    private static final class Captured extends FutureTask<Void> implements ScheduledFuture<Void>
    {
        final Runnable command;
        Captured(Runnable command) { super(command, null); this.command = command; }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS)); }
    }
}
