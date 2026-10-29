package com.chengyinhub.quartz.util;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.quartz.service.MySqlSchedulerCoordinator;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.*;
import com.chengyinhub.quartz.domain.SysJob;
import com.chengyinhub.quartz.mapper.SysJobMapper;
import com.chengyinhub.quartz.service.impl.SysJobServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.spring.SpringUtils;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL named locks through the shipped Druid pool and strict wall filter. No business/provider calls. */
class SchedulerSessionLockMySqlIT {
    private static DruidDataSource source;
    private static String ownedSchema;
    public static class Target { public void run() { } }
    @BeforeAll static void database() throws Exception {
        assertTrue(List.of("127.0.0.1", "localhost", "::1").contains(MybatisItDatabase.host()), "Task-owned loopback MySQL only");
        String suffix = System.getenv("CHENGYIN_MYBATIS_IT_DB_SUFFIX");
        assertTrue(suffix == null || suffix.matches("[a-zA-Z0-9_]*"));
        assertTrue(MybatisItDatabase.databaseName("scheduler_lock").length() <= 64);
        String schema = MybatisItDatabase.databaseName("scheduler_lock");
        assertTrue(schema.matches("chengyin_mybatis_scheduler_lock[a-zA-Z0-9_]*"));
        try (Connection admin = java.sql.DriverManager.getConnection("jdbc:mysql://" + MybatisItDatabase.host()
                + ":" + MybatisItDatabase.port() + "/mysql?useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password()); Statement statement = admin.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
            ownedSchema = schema;
        }
        source = new DruidDataSource();
        // This fixture already exclusively created its schema; the shared helper would DROP and recreate it.
        source.setUrl("jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port() + "/" + ownedSchema
                + "?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true"); source.setUsername(MybatisItDatabase.user());
        source.setPassword(MybatisItDatabase.password()); source.setMaxActive(8); source.setMinIdle(0); source.setInitialSize(0);
        source.setMaxWait(3000);
        WallConfig config = new WallConfig(); config.setMultiStatementAllow(false);
        WallFilter wall = new WallFilter(); wall.setDbType("mysql"); wall.setConfig(config); source.setProxyFilters(List.of(wall));
        source.init();
        try (Connection connection = source.getConnection()) {
            assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("8.0"));
            assertEquals(MybatisItDatabase.databaseName("scheduler_lock"), connection.getCatalog());
        }
    }
    @AfterAll static void close() throws Exception {
        if (source != null) source.close();
        if (ownedSchema == null) return;
        try (Connection admin = java.sql.DriverManager.getConnection("jdbc:mysql://" + MybatisItDatabase.host()
                + ":" + MybatisItDatabase.port() + "/mysql?useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password()); Statement statement = admin.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + ownedSchema + "`");
            try (java.sql.ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + ownedSchema + "'")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        }
    }
    @Test void separateSessionsExcludeEachOtherCommitDoesNotReleaseAndCloseDoes() throws Exception {
        try (Connection holder = source.getConnection(); Connection observer = source.getConnection()) {
            long first = scalar(holder, "SELECT CONNECTION_ID()"), second = scalar(observer, "SELECT CONNECTION_ID()");
            assertNotEquals(first, second);
            String name = MySqlSessionLock.key("task", holder.getCatalog(), "real-commit-proof");
            assertEquals(1, scalar(holder, "SELECT GET_LOCK('" + name + "',0)"));
            holder.setAutoCommit(false); holder.commit();
            assertEquals(first, scalar(observer, "SELECT IS_USED_LOCK('" + name + "')"));
            assertEquals(0, scalar(observer, "SELECT GET_LOCK('" + name + "',0)"));
            assertEquals(1, scalar(holder, "SELECT RELEASE_LOCK('" + name + "')"));
            holder.rollback(); holder.setAutoCommit(true);
        }
        MySqlSessionLock.Lease first = MySqlSessionLock.acquire(source, "task", "actual-lease", 0);
        assertTrue(first.isOwned()); assertNull(MySqlSessionLock.acquire(source, "task", "actual-lease", 0));
        first.close(); assertFalse(first.isOwned());
        try (MySqlSessionLock.Lease next = MySqlSessionLock.acquire(source, "task", "actual-lease", 0)) { assertNotNull(next); assertTrue(next.isOwned()); }
    }
    @Test void terminatedLeaderStopsAdmissionAndSuccessorReadsConfigButCannotRepeatOldFire() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        MySqlSchedulerCoordinator first = new MySqlSchedulerCoordinator(source, executor), second = new MySqlSchedulerCoordinator(source, executor);
        AtomicInteger reads = new AtomicInteger(), stops = new AtomicInteger(), work = new AtomicInteger();
        first.registerQuartz(reads::incrementAndGet, stops::incrementAndGet); second.registerQuartz(reads::incrementAndGet, stops::incrementAndGet);
        ReflectionTestUtils.setField(first, "running", true); ReflectionTestUtils.setField(second, "running", true);
        try {
            first.maintainLeadership(); second.maintainLeadership();
            assertTrue(first.isLeader()); assertFalse(second.isLeader()); assertEquals(1, reads.get());
            String target = Target.class.getName() + ".run()";
            ReflectionTestUtils.setField(first, "activatedAt", 1L); Date fire = new Date(2L);
            assertTrue(first.execute(target, fire, work::incrementAndGet));
            assertFalse(first.execute(target, fire, work::incrementAndGet));
            long holderId;
            try (Connection observer = source.getConnection()) {
                String leaderKey = MySqlSessionLock.key("leader", observer.getCatalog(), "all");
                holderId = scalar(observer, "SELECT IS_USED_LOCK('" + leaderKey + "')");
            }
            // Separate task-owned unfiltered control connection: the wall intentionally rejects KILL SQL.
            try (Connection control = java.sql.DriverManager.getConnection(source.getUrl(), MybatisItDatabase.user(), MybatisItDatabase.password());
                 Statement statement = control.createStatement()) { statement.execute("KILL CONNECTION " + holderId); }
            first.maintainLeadership(); assertFalse(first.isLeader()); assertEquals(1, stops.get());
            assertFalse(first.execute(target, null, () -> fail("lost leader must reject")));
            second.maintainLeadership(); assertTrue(second.isLeader()); assertEquals(2, reads.get());
            assertFalse(second.execute(target, fire, work::incrementAndGet)); assertEquals(1, work.get());
            assertTrue(second.execute(target, null, work::incrementAndGet)); assertEquals(2, work.get());
        } finally { first.stop(); second.stop(); executor.shutdownNow(); }
    }
    @Test void concurrentDefinitionsSerializeThroughCommitAndHistoricalPausedAliasesRemainVisible() throws Exception {
        java.nio.file.Path schema = java.nio.file.Path.of("../sql/base_schema_20260818.sql");
        if (!java.nio.file.Files.isRegularFile(schema)) schema = java.nio.file.Path.of("sql/base_schema_20260818.sql");
        String actual = java.nio.file.Files.readString(schema);
        java.util.regex.Matcher ddl = java.util.regex.Pattern.compile("CREATE TABLE `sys_job` \\([\\s\\S]*?;").matcher(actual);
        assertTrue(ddl.find(), "Use shipped sys_job DDL");
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) { statement.execute(ddl.group()); }
        Configuration configuration = new Configuration(); configuration.getTypeAliasRegistry().registerAlias("SysJob", SysJob.class);
        configuration.setEnvironment(new Environment("scheduler-it", new SpringManagedTransactionFactory(), source));
        String resource = "mapper/quartz/SysJobMapper.xml";
        try (java.io.InputStream stream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        SqlSessionTemplate sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        SysJobMapper mapper = sql.getMapper(SysJobMapper.class);
        ScheduledExecutorService schedulerThreads = Executors.newSingleThreadScheduledExecutor();
        MySqlSchedulerCoordinator coordinator = new MySqlSchedulerCoordinator(source, schedulerThreads);
        SysJobServiceImpl service = new SysJobServiceImpl();
        org.quartz.Scheduler quartz = mock(org.quartz.Scheduler.class);
        ReflectionTestUtils.setField(service, "jobMapper", mapper); ReflectionTestUtils.setField(service, "scheduler", quartz);
        ReflectionTestUtils.setField(service, "coordinator", coordinator);
        Object old = ReflectionTestUtils.getField(SpringUtils.class, "beanFactory");
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("lockTask", new Target()); factory.registerAlias("lockTask", "lockAlias");
        ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", factory);
        ExecutorService writers = Executors.newFixedThreadPool(2);
        CountDownLatch inserted = new CountDownLatch(1), commit = new CountDownLatch(1), secondEntered = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        try {
            Future<Integer> first = writers.submit(() -> transaction.execute(status -> {
                try { int rows = service.insertJob(job(1L, "lockTask.run()")); inserted.countDown();
                    if (!commit.await(2, TimeUnit.SECONDS)) throw new AssertionError("commit latch"); return rows;
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }));
            assertTrue(inserted.await(2, TimeUnit.SECONDS));
            Future<Integer> second = writers.submit(() -> transaction.execute(status -> {
                secondEntered.countDown();
                try { return service.insertJob(job(2L, "lockAlias.run()")); }
                catch (ServiceException rejected) { throw rejected; }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }));
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS));
            try (Connection observer = source.getConnection()) {
                String key = MySqlSessionLock.key("jobdef", observer.getCatalog(), "definitions");
                assertTrue(scalar(observer, "SELECT IS_USED_LOCK('" + key + "')") > 0, "Definition session survives insert resource close");
            }
            commit.countDown(); assertEquals(1, first.get(3, TimeUnit.SECONDS));
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> second.get(3, TimeUnit.SECONDS));
            assertInstanceOf(ServiceException.class, rejected.getCause()); assertTrue(rejected.getCause().getMessage().contains("任务调用重复"));
            try (Connection observer = source.getConnection(); Statement statement = observer.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT job_id,status,cron_expression FROM sys_job ORDER BY job_id")) {
                assertTrue(rows.next()); assertEquals(1L, rows.getLong(1)); assertEquals("1", rows.getString(2));
                assertEquals("0/5 * * * * ?", rows.getString(3)); assertFalse(rows.next());
            }
            // A historical duplicate inserted in the task fixture is reported, never silently removed or resumed.
            mapper.insertJob(job(3L, "lockAlias.run()"));
            assertThrows(ServiceException.class, service::init); assertEquals(2, mapper.selectJobAll().size());
            assertTrue(mapper.selectJobAll().stream().allMatch(value -> "1".equals(value.getStatus()))); verifyNoInteractions(quartz);
        } finally { commit.countDown(); writers.shutdownNow(); schedulerThreads.shutdownNow();
            ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", old); }
    }
    private static SysJob job(long id, String target) {
        SysJob job = new SysJob(); job.setJobId(id); job.setJobName("session-lock-it"); job.setJobGroup("DEFAULT");
        job.setInvokeTarget(target); job.setCronExpression("0/5 * * * * ?"); job.setMisfirePolicy("0");
        job.setConcurrent("1"); job.setStatus("1"); return job;
    }
    private static long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next()); long value = row.getLong(1); assertFalse(row.wasNull()); return value;
        }
    }
}
