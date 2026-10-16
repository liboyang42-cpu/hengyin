package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.MerchantPayableBatch;
import com.chengyinhub.business.mapper.MerchantPayableBatchMapper;
import com.chengyinhub.business.service.IMerchantPayableService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.MerchantPayableServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN. Opt-in real MySQL + actual service transactions; no mapper state/result stubs. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OWN_C04_20MySqlIT {
    private MysqlIntegrationTestConfig mysql;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private MerchantPayableBatchMapper batches;
    private boolean created;

    @BeforeAll void setup() throws Exception {
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_HOST"), "Explicit authorized test host required");
        String suffix = System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX");
        assertNotNull(suffix, "Isolated test suffix required");
        assertFalse(suffix.trim().isEmpty());
        mysql = MysqlIntegrationTestConfig.fromEnvironment("c04_pay_guard");
        JdbcTemplate admin = admin();
        admin.execute("DROP DATABASE IF EXISTS `" + mysql.databaseName() + "`");
        admin.execute("CREATE DATABASE `" + mysql.databaseName() + "` CHARACTER SET utf8mb4");
        created = true;
        DriverManagerDataSource source = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(source);
        Path base = Paths.get("sql/base_schema_20260818.sql");
        if (!Files.isRegularFile(base)) base = Paths.get("../sql/base_schema_20260818.sql");
        List<String> statements = new ArrayList<>();
        ScriptUtils.splitSqlScript(new String(Files.readAllBytes(base), StandardCharsets.UTF_8), ";", statements);
        List<String> ddl = new ArrayList<>();
        for (String statement : statements) {
            if (statement.trim().matches("(?is)^CREATE\\s+TABLE\\s+`merchant_payable_batch`\\s*\\(.*")) ddl.add(statement);
        }
        assertEquals(1, ddl.size(), "Require the one complete real batch DDL, never split at a quoted semicolon");
        jdbc.execute(ddl.get(0));
        Configuration cfg = new Configuration(new Environment("c04-pay-guard", new SpringManagedTransactionFactory(), source));
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        cfg.addMapper(MerchantPayableBatchMapper.class);
        String resource = "mapper/business/MerchantPayableBatchMapper.xml";
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(xml);
            new XMLMapperBuilder(xml, cfg, resource, cfg.getSqlFragments()).parse();
        }
        batches = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg)).getMapper(MerchantPayableBatchMapper.class);
        manager = new DataSourceTransactionManager(source);
    }

    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
    }
    @AfterAll void cleanup() {
        if (created) admin().execute("DROP DATABASE IF EXISTS `" + mysql.databaseName() + "`");
    }
    @BeforeEach void pending() {
        jdbc.update("DELETE FROM merchant_payable_batch");
        jdbc.update("INSERT INTO merchant_payable_batch(id,merchant_member_id,period_ym,amount_total,status,invoice_status) VALUES (1,500,'2026-09',100,'PENDING','NONE')");
    }

    @Test void stalePendingReadCannotBypassSameActorConfirmation() throws Exception {
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch confirmed = new CountDownLatch(1);
        MerchantPayableBatchMapper paused = (MerchantPayableBatchMapper) Proxy.newProxyInstance(
                MerchantPayableBatchMapper.class.getClassLoader(), new Class<?>[]{MerchantPayableBatchMapper.class},
                (proxy, method, args) -> {
                    Object actual;
                    try { actual = method.invoke(batches, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                    if (method.getName().equals("selectById")) {
                        assertEquals(MerchantPayableBatch.STATUS_PENDING, ((MerchantPayableBatch) actual).getStatus());
                        read.countDown();
                        assertTrue(confirmed.await(10, TimeUnit.SECONDS), "B confirmation did not finish");
                    }
                    return actual;
                });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = workers.submit(() -> payable(paused).markBatchPaid(1L, "ops", "voucher"));
            assertTrue(read.await(10, TimeUnit.SECONDS), "A did not read PENDING");
            Future<Boolean> b = workers.submit(() -> payable(batches).confirmBatch(1L, "ops"));
            assertTrue(b.get(15, TimeUnit.SECONDS), "B actual transaction must commit confirmation");
            confirmed.countDown();
            assertFalse(a.get(15, TimeUnit.SECONDS));
            Map<String,Object> row = jdbc.queryForMap("SELECT * FROM merchant_payable_batch WHERE id=1");
            assertEquals("CONFIRMED", row.get("status"));
            assertEquals("ops", row.get("confirmed_by"));
            assertNull(row.get("paid_by"));
            assertNull(row.get("paid_at"));
            assertNull(row.get("pay_voucher_no"));
        } finally {
            confirmed.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS), "Fixture workers did not stop");
        }
    }

    @Test void confirmedDifferentActorStillRecordsPaymentFact() {
        IMerchantPayableService service = payable(batches);
        assertTrue(service.confirmBatch(1L, "maker"));
        assertTrue(service.markBatchPaid(1L, "checker", "voucher"));
        Map<String,Object> row = jdbc.queryForMap("SELECT * FROM merchant_payable_batch WHERE id=1");
        assertEquals("PAID", row.get("status"));
        assertEquals("maker", row.get("confirmed_by"));
        assertEquals("checker", row.get("paid_by"));
        assertEquals("voucher", row.get("pay_voucher_no"));
        assertNotNull(row.get("paid_at"));
    }

    private IMerchantPayableService payable(MerchantPayableBatchMapper mapper) {
        MerchantPayableServiceImpl target = new MerchantPayableServiceImpl();
        ReflectionTestUtils.setField(target, "batchMapper", mapper);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(IMerchantPayableService.class);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (IMerchantPayableService) proxy.getProxy();
    }
}
