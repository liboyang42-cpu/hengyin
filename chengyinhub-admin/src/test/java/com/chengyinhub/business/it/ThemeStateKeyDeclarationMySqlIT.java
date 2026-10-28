package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Source-only handoff: real canonical tables, real Mapper and proxied declaration writer.
 * Does not substitute for G's bundle/hash/compiler or H's runtime-consumer acceptance. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ThemeStateKeyDeclarationMySqlIT {
    private final String suite = "statekeydecl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private MysqlIntegrationTestConfig mysql;
    private JdbcTemplate admin, db;
    private ThemeStateKeyMapper keys;
    private ThemeStateKeyService service;
    private TransactionTemplate tx;
    private boolean created;

    @BeforeAll void canonicalOwnedSchema() throws Exception {
        assertEquals("127.0.0.1", System.getenv("CHENGYIN_TEST_MYSQL_HOST"));
        for (String variable : List.of("CHENGYIN_TEST_MYSQL_PORT", "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"))
            assertNotNull(System.getenv(variable), "explicit controlled connection required: " + variable);
        mysql = MysqlIntegrationTestConfig.fromEnvironment(suite);
        admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("CREATE DATABASE `" + mysql.databaseName() + "` CHARACTER SET utf8mb4");
        created = true;
        DriverManagerDataSource source = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        db = new JdbcTemplate(source);
        P0AcceptanceSchema.baseTables(db, "cms_topic");
        P0AcceptanceSchema.createTableFromMigration(db, "migration_theme_state_key_20260916.sql");
        P0AcceptanceSchema.migrations(source, "migration_20261022_theme_state_key_attribute_def.sql");
        SqlSessionTemplate sql = P0AcceptanceSchema.sql(source, "ThemeStateKeyMapper");
        keys = sql.getMapper(ThemeStateKeyMapper.class);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        ProxyFactory proxy = new ProxyFactory(new ThemeStateKeyService(keys)); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (ThemeStateKeyService) proxy.getProxy();
    }

    @BeforeEach void originalDeclarations() {
        db.update("DELETE FROM theme_state_key"); db.update("DELETE FROM cms_topic");
        db.update("INSERT INTO cms_topic(id,member_id,name,del_flag) VALUES(100,7,'A',0),(200,8,'B',0)");
        service.register(100L, List.of(declaration("counter.power", "A", 1)));
        service.register(200L, List.of(declaration("counter.power", "Other", 2)));
    }

    @AfterAll void dropOnlyCreatedSchemaAndReadBackAbsence() {
        if (!created) return;
        assertTrue(mysql.databaseName().matches("[A-Za-z0-9_]+_" + suite + "[A-Za-z0-9_]*"));
        admin.execute("DROP DATABASE `" + mysql.databaseName() + "`");
        assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class, mysql.databaseName()));
    }

    @Test void currentDeclarationsIgnoreAnOlderRrViewAndMybatisCachedList() throws Exception {
        ExecutorService editor = Executors.newSingleThreadExecutor();
        try {
            tx.execute(status -> {
                assertEquals("A", keys.selectByTopicId(100L).get(0).getLabel());
                await(editor.submit(() -> service.updateDefinition(100L, "counter.power", declaration("counter.power", "B", 2))));
                assertEquals("A", keys.selectByTopicId(100L).get(0).getLabel(), "keep the old RR/cache counterexample");
                assertEquals(Long.valueOf(100L), keys.lockTopicForStateKeyWrite(100L));
                List<ThemeStateKey> captured = service.listCurrentDeclarations(100L);
                assertEquals("B", captured.get(0).getLabel()); assertEquals(Integer.valueOf(2), captured.get(0).getInitialValue());
                assertEquals(Long.valueOf(100L), captured.get(0).getTopicId());
                assertEquals("A", keys.selectByTopicId(100L).get(0).getLabel(), "current SQL must not change isolation to RC");
                return null;
            });
        } finally { stop(editor); }
    }

    @Test void emptyDeclarationsStillFenceNewKeysWhileAnotherTopicCanBeEdited() throws Exception {
        assertTrue(service.delete(100L, "counter.power"));
        ExecutorService editor = Executors.newFixedThreadPool(2);
        @SuppressWarnings("unchecked") Future<?>[] blocked = new Future<?>[1];
        try {
            tx.execute(status -> {
                assertEquals(Long.valueOf(100L), keys.lockTopicForStateKeyWrite(100L));
                assertTrue(keys.selectByTopicIdForUpdate(100L).isEmpty());
                blocked[0] = editor.submit(() -> service.register(100L, List.of(declaration("counter.new_key", "New", 3))));
                awaitTopicLockWait(); assertFalse(blocked[0].isDone());
                await(editor.submit(() -> service.updateDefinition(200L, "counter.power", declaration("counter.power", "Independent", 4))));
                assertTrue(keys.selectByTopicIdForUpdate(100L).isEmpty());
                return null;
            });
            await(blocked[0]);
            assertEquals("counter.new_key", keys.selectByTopicId(100L).get(0).getStateKey());
            assertEquals("Independent", keys.selectByTopicId(200L).get(0).getLabel());
        } finally { stop(editor); }
    }

    @Test void registerUpdateAndDeleteAllWaitBehindTheSamePublicationTopicFence() throws Exception {
        ExecutorService editor = Executors.newSingleThreadExecutor();
        try {
            for (String operation : List.of("register", "update", "delete")) {
                service.register(100L, List.of(declaration("counter.power", "A", 1)));
                @SuppressWarnings("unchecked") Future<?>[] blocked = new Future<?>[1];
                tx.execute(status -> {
                    assertEquals(Long.valueOf(100L), keys.lockTopicForStateKeyWrite(100L));
                    List<ThemeStateKey> frozen = keys.selectByTopicIdForUpdate(100L);
                    blocked[0] = editor.submit(() -> {
                        if (operation.equals("register")) service.register(100L, List.of(declaration("counter.power", "B", 2)));
                        else if (operation.equals("update")) service.updateDefinition(100L, "counter.power", declaration("counter.power", "B", 2));
                        else service.delete(100L, "counter.power");
                    });
                    awaitTopicLockWait(); assertFalse(blocked[0].isDone());
                    assertEquals("A", frozen.get(0).getLabel()); assertEquals(Integer.valueOf(1), frozen.get(0).getInitialValue());
                    return null;
                });
                await(blocked[0]);
                if (operation.equals("delete")) assertTrue(keys.selectByTopicId(100L).isEmpty());
                else assertEquals("B", keys.selectByTopicId(100L).get(0).getLabel());
            }
        } finally { stop(editor); }
    }

    @Test void definitionUpdateUsesTheCurrentReplacementIdentityInsteadOfAnOldCachedRow() throws Exception {
        ExecutorService editor = Executors.newSingleThreadExecutor();
        try {
            tx.execute(status -> {
                Long oldId = keys.selectByTopicIdAndStateKey(100L, "counter.power").getId();
                await(editor.submit(() -> tx.execute(other -> {
                    assertTrue(service.delete(100L, "counter.power"));
                    service.register(100L, List.of(declaration("counter.power", "Replacement", 2))); return null;
                })));
                ThemeStateKey updated = service.updateDefinition(100L, "counter.power", declaration("counter.power", "Final", 3));
                assertNotEquals(oldId, updated.getId());
                assertEquals(updated.getId(), keys.selectByTopicIdAndStateKeyForUpdate(100L, "counter.power").getId());
                assertEquals("Final", updated.getLabel()); return null;
            });
        } finally { stop(editor); }
    }

    @Test void originalTransactionRollbackRetainsDeclarationsAfterAnActualDelete() {
        tx.execute(status -> {
            assertTrue(service.delete(100L, "counter.power"));
            assertNull(keys.selectByTopicIdAndStateKeyForUpdate(100L, "counter.power"));
            status.setRollbackOnly(); return null;
        });
        assertEquals("A", keys.selectByTopicIdAndStateKey(100L, "counter.power").getLabel());
        assertEquals("Other", keys.selectByTopicIdAndStateKey(200L, "counter.power").getLabel());
    }

    @Test void nonexistentTopicCannotPersistOrChangeDeclarations() {
        assertThrows(RuntimeException.class, () -> service.listCurrentDeclarations(100L), "no implicit new publication transaction");
        TransactionTemplate readOnly = new TransactionTemplate(tx.getTransactionManager()); readOnly.setReadOnly(true);
        assertThrows(RuntimeException.class, () -> readOnly.execute(status -> service.listCurrentDeclarations(100L)));
        assertThrows(RuntimeException.class, () -> service.register(999L, List.of(declaration("counter.power", "Orphan", 1))));
        assertThrows(RuntimeException.class, () -> service.updateDefinition(999L, "counter.power", new ThemeStateKey()));
        assertThrows(RuntimeException.class, () -> service.delete(999L, "counter.power"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM theme_state_key WHERE topic_id=999", Integer.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM theme_state_key", Integer.class));
    }

    private void awaitTopicLockWait() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waits = admin.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                    + "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID "
                    + "WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='cms_topic'", Integer.class, mysql.databaseName());
            if (waits != null && waits > 0) return;
            try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
        fail("no physical wait on this task's cms_topic fence; do not accept a slow thread as lock evidence");
    }
    private static ThemeStateKey declaration(String name, String label, int initial) {
        ThemeStateKey row = new ThemeStateKey(); row.setStateKey(name); row.setKeyType("counter"); row.setScope("run");
        row.setLabel(label); row.setInitialValue(initial); row.setMinValue(0); row.setMaxValue(10); return row;
    }
    private static void await(Future<?> future) {
        try { future.get(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        catch (ExecutionException | TimeoutException e) { throw new IllegalStateException(e); }
    }
    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
}
