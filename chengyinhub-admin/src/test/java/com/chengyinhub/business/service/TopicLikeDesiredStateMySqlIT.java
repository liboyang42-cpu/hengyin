package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.CmsTopicLikeMapper;
import com.chengyinhub.business.service.impl.CmsTopicLikeServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL unique key and current XML under repeated requests; only a newly owned disposable schema. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopicLikeDesiredStateMySqlIT {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("topic_like_state_it");
    private JdbcTemplate admin, db;
    private DriverManagerDataSource source;
    private boolean created;
    private ICmsTopicLikeService service;
    private String migration;

    @BeforeAll void setup() throws Exception {
        admin = new JdbcTemplate(source(mysql.adminUrl()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("create database " + mysql.databaseName() + " character set utf8mb4");
        created = true;
        source = source(mysql.databaseUrl());
        db = new JdbcTemplate(source);
        Path root = Files.exists(Path.of("sql/base_schema_20260818.sql")) ? Path.of("") : Path.of("..");
        String base = Files.readString(root.resolve("sql/base_schema_20260818.sql"));
        int start = base.indexOf("CREATE TABLE `cms_topic_like` (");
        int end = base.indexOf(';', base.indexOf("\n) ENGINE", start));
        assertTrue(start >= 0 && end > start);
        db.execute(base.substring(start, end + 1));
        migration = Files.readString(root.resolve("chengyinhub-admin/sql/migration_20261012_topic_like_unique.sql"));
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsTopicLikeMapper.xml"));
        CmsTopicLikeServiceImpl target = new CmsTopicLikeServiceImpl();
        ReflectionTestUtils.setField(target, "cmsTopicLikeMapper",
                new SqlSessionTemplate(factory.getObject()).getMapper(CmsTopicLikeMapper.class));
        service = target;
    }

    @BeforeEach void resetOwnedRows() throws Exception {
        db.execute("delete from cms_topic_like");
        applyMigration();
    }

    @AfterAll void cleanup() {
        if (created) {
            admin.execute("drop database " + mysql.databaseName());
            assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?",
                    Integer.class, mysql.databaseName()));
        }
    }

    @Test void migrationIsIdempotentAndItsIndexShapeIsExact() throws Exception {
        applyMigration();
        service.setLiked(101L, 42L, true);
        applyMigration();
        assertEquals(1, count(101L));
        assertEquals(2, db.queryForObject("select count(*) from information_schema.statistics where table_schema=database()"
                + " and table_name='cms_topic_like' and index_name='uk_topic_like_member_data' and non_unique=0", Integer.class));
    }

    @Test void historicalDuplicatePairsBlockBeforeDdlAndAreNeverDiscarded() {
        db.execute("alter table cms_topic_like drop index uk_topic_like_member_data");
        db.execute("insert into cms_topic_like(member_id,data_id,like_type) values(101,42,1),(101,42,1)");
        assertThrows(AssertionError.class, this::applyMigration);
        assertEquals(2, count(101L));
        assertEquals(0, db.queryForObject("select count(*) from information_schema.statistics where table_schema=database()"
                + " and table_name='cms_topic_like' and index_name='uk_topic_like_member_data'", Integer.class));
    }

    @Test @Timeout(30)
    void concurrentRepeatRequestsHaveOneLikeAndCancelOnlyItsOwner() throws Exception {
        service.setLiked(102L, 42L, true);
        repeatConcurrently(true);
        assertEquals(1, count(101L));
        assertEquals(1, count(102L));
        repeatConcurrently(false);
        assertEquals(0, count(101L));
        assertEquals(1, count(102L));
        service.setLiked(101L, 42L, false);
        assertEquals(1, count(102L));
    }

    private void repeatConcurrently(boolean liked) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) tasks.add(workers.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                for (int j = 0; j < 3; j++) service.setLiked(101L, 42L, liked);
            }));
            start.countDown();
            for (Future<?> task : tasks) task.get(15, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private int count(Long member) {
        return db.queryForObject("select count(*) from cms_topic_like where member_id=? and data_id=42", Integer.class, member);
    }

    private void applyMigration() throws Exception {
        // ScriptUtils handles comments/quoted semicolons. Every standalone SELECT in this forward is a scalar assertion.
        long expected = migration.lines().filter(line -> line.trim().equals("-- runner: assert=scalar-true")).count();
        assertEquals(2L, expected, "New migration assertions require an explicit test contract update");
        AtomicInteger observed = new AtomicInteger();
        try (Connection connection = source.getConnection()) {
            Connection checked = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                Object result;
                try { result = method.invoke(connection, args); }
                catch (InvocationTargetException e) { throw e.getCause(); }
                if (!method.getName().equals("createStatement")) return result;
                Statement statement = (Statement) result;
                return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                        (statementProxy, operation, parameters) -> {
                    Object value;
                    try { value = operation.invoke(statement, parameters); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                    if (operation.getName().equals("execute") && parameters != null
                            && parameters[0] instanceof String sql
                            && sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT ")) {
                        assertEquals(Boolean.TRUE, value, "runner assertion must return a result");
                        try (ResultSet rows = statement.getResultSet()) {
                            assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertFalse(rows.next());
                        }
                        observed.incrementAndGet();
                    }
                    return value;
                });
            });
            ScriptUtils.executeSqlScript(checked, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
            assertEquals(expected, observed.get(), "All scalar assertions must actually run and be checked");
        }
    }

    private DriverManagerDataSource source(String url) {
        return new DriverManagerDataSource(url, mysql.user(), mysql.password());
    }
}
