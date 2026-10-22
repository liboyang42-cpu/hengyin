package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Explicit dedicated VPS gate; real mapper, transaction proxy and independent connections. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_CONSENT_IDEMPOTENCY_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
class ConsentRecordIdempotencyMySqlIT {
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private String schema;
    private boolean schemaCreated;
    private ConsentRecordMapper mapper;
    private DataSourceTransactionManager manager;
    private IConsentRecordService service;

    @BeforeAll
    void setup() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("CHENGYIN_MYBATIS_IT_PORT", "3306"));
        assertTrue(port > 0 && port <= 65535);
        String user = System.getenv().getOrDefault("CHENGYIN_MYBATIS_IT_USER", "root");
        String password = System.getenv().getOrDefault("CHENGYIN_MYBATIS_IT_PASSWORD", "");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + "mysql" + options, user, password));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        schema = "cy_consent_replay_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + schema);
        schemaCreated = true;
        DriverManagerDataSource source = new DriverManagerDataSource(base + schema + options, user, password);
        db = new JdbcTemplate(source);
        Path sql = Paths.get("chengyinhub-admin/sql");
        if (!Files.isDirectory(sql)) sql = Paths.get("sql");
        new ResourceDatabasePopulator(
                new FileSystemResource(sql.resolve("migration_compliance_consent_deregister_20260711.sql").toFile()),
                new FileSystemResource(sql.resolve("migration_merchant_onsite_consent_scope_20260815.sql").toFile()))
                .execute(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ConsentRecordMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ConsentRecordMapper.class);
        manager = new DataSourceTransactionManager(source);
        service = proxied(mapper);
    }

    private IConsentRecordService proxied(ConsentRecordMapper delegate) {
        ConsentRecordServiceImpl target = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(target, "consentRecordMapper", delegate);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        proxy.addAdvice((MethodInterceptor) invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "both public record overloads require a real transaction");
            return invocation.proceed();
        });
        IConsentRecordService result = (IConsentRecordService) proxy.getProxy();
        assertTrue(AopUtils.isAopProxy(result));
        return result;
    }

    @BeforeEach
    void cleanRows() {
        db.update("delete from consent_record");
    }

    @AfterAll
    void cleanup() {
        if (schemaCreated && admin != null && schema != null) admin.execute("drop database if exists " + schema);
    }

    private int record(IConsentRecordService target, String key, Long memberId, String event) {
        return target.record(memberId, "privacy_policy", "v1.1", "app_launch", event, key, "127.0.0.1");
    }

    private void assertConflict(ServiceException error) {
        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
        assertEquals("请求标识已用于其他同意事件", error.getMessage());
    }

    @Test
    void originalEventRemainsImmutableAndBothProxyEntriesAreTransactional() {
        assertEquals(1, record(service, "original", 1001L, "AGREE"));
        Map<String, Object> before = db.queryForMap("select * from consent_record where request_id='original'");
        assertEquals(0, record(service, "original", 1001L, "AGREE"));
        assertConflict(assertThrows(ServiceException.class, () -> record(service, "original", 1001L, "REVOKE")));
        assertConflict(assertThrows(ServiceException.class, () -> record(service, "original", 2002L, "AGREE")));
        assertEquals(before, db.queryForMap("select * from consent_record where request_id='original'"));
        assertEquals(1, service.record(1001L, "merchant_onsite_data_sharing", "v1.0", "merchant_redeem",
                "MERCHANT", 3003L, "AGREE", "scoped", "127.0.0.1"));
        assertEquals(0, service.record(1001L, "merchant_onsite_data_sharing", "v1.0", "merchant_redeem",
                "MERCHANT", 3003L, "AGREE", "scoped", "127.0.0.2"));
        assertConflict(assertThrows(ServiceException.class, () -> service.record(1001L,
                "merchant_onsite_data_sharing", "v1.0", "merchant_redeem", "MERCHANT", 3004L,
                "AGREE", "scoped", "127.0.0.1")));
        assertEquals(2, db.queryForObject("select count(*) from consent_record", Integer.class));
    }

    private void concurrentDuplicates(boolean conflictingSecond) throws Exception {
        record(service, "parallel", 1001L, "AGREE");
        Map<String, Object> before = db.queryForMap("select * from consent_record where request_id='parallel'");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Map<Long, Long> workerConnections = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<String> trace = new ConcurrentLinkedQueue<>();
        ConsentRecordMapper observed = mock(ConsentRecordMapper.class, delegatesTo(mapper));
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            Long connectionId = db.queryForObject("select connection_id()", Long.class);
            workerConnections.put(Thread.currentThread().getId(), connectionId);
            trace.add(connectionId + ":insert-ready");
            ready.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS), "both transactions must start before INSERT");
            trace.add(connectionId + ":insert-enter");
            try {
                return mapper.insertConsentRecord(invocation.getArgument(0));
            } catch (RuntimeException failure) {
                trace.add(connectionId + ":insert-" + failure.getClass().getSimpleName());
                throw failure;
            }
        }).when(observed).insertConsentRecord(any());
        doAnswer(invocation -> {
            Long connectionId = workerConnections.get(Thread.currentThread().getId());
            trace.add(connectionId + ":current-read-enter");
            try {
                return mapper.selectByRequestIdForShare(invocation.getArgument(0));
            } finally {
                trace.add(connectionId + ":current-read-exit");
            }
        }).when(observed).selectByRequestIdForShare(anyString());
        IConsentRecordService concurrent = proxied(observed);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = workers.submit(() -> record(concurrent, "parallel", 1001L, "AGREE"));
            Future<Integer> second = workers.submit(() -> {
                try {
                    return record(concurrent, "parallel", 1001L, conflictingSecond ? "REVOKE" : "AGREE");
                } catch (ServiceException conflict) {
                    assertConflict(conflict);
                    return HttpStatus.CONFLICT;
                }
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS), "two transactions must reach INSERT: " + trace);
            assertEquals(2L, workerConnections.values().stream().distinct().count());
            start.countDown();
            int firstResult = first.get(10, TimeUnit.SECONDS);
            int secondResult = second.get(10, TimeUnit.SECONDS);
            trace.add("completed:" + firstResult + "/" + secondResult);
            assertEquals(0, firstResult);
            assertEquals(conflictingSecond ? HttpStatus.CONFLICT : 0, secondResult);
            verify(observed, times(2)).insertConsentRecord(any());
            verify(observed, times(2)).selectByRequestIdForShare("parallel");
            // DriverManagerDataSource opens a new independent connection for the final readback.
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            try (Connection connection = db.getDataSource().getConnection()) {
                JdbcTemplate readback = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                assertFalse(workerConnections.containsValue(readback.queryForObject("select connection_id()", Long.class)));
                assertEquals(1, readback.queryForObject("select count(*) from consent_record", Integer.class));
                assertEquals(before, readback.queryForMap("select * from consent_record where request_id='parallel'"));
            }
        } finally {
            start.countDown();
            System.out.println("consent-replay fixture trace: " + trace);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentIdenticalReplaysReturnOriginal() throws Exception {
        concurrentDuplicates(false);
    }

    @Test
    void concurrentDifferentEventRejectsOnlyConflictingReplay() throws Exception {
        concurrentDuplicates(true);
    }

    private void replayAfterOldSnapshot(boolean conflict) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            Runnable replay = () -> transaction.execute(status -> {
                assertEquals(0, db.queryForObject("select count(*) from consent_record", Integer.class));
                try {
                    assertEquals(1, worker.submit(() -> record(service, "late-commit", 1001L, "AGREE"))
                            .get(5, TimeUnit.SECONDS).intValue());
                } catch (Exception e) {
                    throw new AssertionError("independent writer did not commit", e);
                }
                assertEquals(0, db.queryForObject("select count(*) from consent_record", Integer.class), "outer RR snapshot must remain old");
                assertEquals(0, record(service, "late-commit", 1001L, conflict ? "REVOKE" : "AGREE"));
                return null;
            });
            if (conflict) assertConflict(assertThrows(ServiceException.class, replay::run));
            else replay.run();
            assertEquals(1, db.queryForObject("select count(*) from consent_record", Integer.class));
            assertEquals("AGREE", db.queryForObject("select event_type from consent_record", String.class));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void identicalReplayUsesCurrentOriginalDespiteEarlierRepeatableReadSnapshot() throws Exception {
        replayAfterOldSnapshot(false);
    }

    @Test
    void conflictingReplayUsesCurrentOriginalDespiteEarlierRepeatableReadSnapshot() throws Exception {
        replayAfterOldSnapshot(true);
    }
}
