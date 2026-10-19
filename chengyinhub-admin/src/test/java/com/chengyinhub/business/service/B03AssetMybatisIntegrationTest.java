package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.domain.MerchantPayableBatch;
import com.chengyinhub.business.mapper.MerchantPayableBatchMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.impl.MerchantPayableServiceImpl;
import com.chengyinhub.business.service.impl.PointsAwardServiceImpl;
import com.chengyinhub.business.service.impl.UmsMemberPointsDetailServiceImpl;
import com.chengyinhub.business.service.impl.UmsMemberServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Opt-in test for the authorized MySQL test host only. Never run in Cloud.
 * Real service proxies + real MyBatis XML + independent JDBC readback; only the 5-point rule is a stub.
 * Temporary fixture DDL lives solely in the isolated test database; no application migration is added.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class B03AssetMybatisIntegrationTest {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("b03_assets_it");
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private TransactionTemplate requiresNew;
    private UmsMemberMapper members;
    private MerchantPayableBatchMapper batches;
    private IPointsAwardService points;

    @BeforeAll
    void setup() throws Exception {
        // Require an explicit target and an isolated suffix rather than inherit localhost/default-schema guesses.
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_HOST"), "Configure the authorized test MySQL host explicitly");
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX"), "Configure an isolated test suffix");
        assertFalse(System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX").trim().isEmpty());
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        admin.execute("DROP DATABASE IF EXISTS " + mysql.databaseName());
        admin.execute("CREATE DATABASE " + mysql.databaseName() + " CHARACTER SET utf8mb4");
        dataSource = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(dataSource);
        String base = new String(Files.readAllBytes(Paths.get("../sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
        for (String table : new String[]{"ums_member", "ums_member_points_detail", "merchant_payable_batch"}) {
            int start = base.indexOf("CREATE TABLE `" + table + "` (");
            assertTrue(start >= 0, "Missing fixture table " + table);
            jdbc.execute(base.substring(start, base.indexOf(';', start)));
        }
        // The runtime member reader also consumes this post-base-schema column.
        jdbc.execute("ALTER TABLE ums_member ADD COLUMN app_open_id VARCHAR(128) NULL");
        Configuration configuration = new Configuration(new Environment("b03-assets-it",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (Class<?> type : new Class<?>[]{UmsMemberMapper.class, UmsMemberPointsDetailMapper.class,
                MerchantPayableBatchMapper.class}) {
            configuration.addMapper(type);
            String resource = "mapper/business/" + type.getSimpleName() + ".xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        members = session.getMapper(UmsMemberMapper.class);
        batches = session.getMapper(MerchantPayableBatchMapper.class);
        UmsMemberPointsDetailServiceImpl details = new UmsMemberPointsDetailServiceImpl();
        ReflectionTestUtils.setField(details, "umsMemberPointsDetailMapper", session.getMapper(UmsMemberPointsDetailMapper.class));
        UmsMemberServiceImpl memberService = new UmsMemberServiceImpl();
        ReflectionTestUtils.setField(memberService, "umsMemberMapper", members);
        ICmsPointsSettingService settings = mock(ICmsPointsSettingService.class);
        CmsPointsSetting rule = new CmsPointsSetting();
        rule.setValue(new BigDecimal("5"));
        when(settings.selectCmsPointsSettingList(any())).thenReturn(Collections.singletonList(rule));
        PointsAwardServiceImpl target = new PointsAwardServiceImpl();
        ReflectionTestUtils.setField(target, "cmsPointsSettingService", settings);
        ReflectionTestUtils.setField(target, "umsMemberService", memberService);
        ReflectionTestUtils.setField(target, "umsMemberMapper", members);
        ReflectionTestUtils.setField(target, "umsMemberPointsDetailService", details);
        manager = new DataSourceTransactionManager(dataSource);
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(20);
        requiresNew = new TransactionTemplate(manager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.setTimeout(20);
        points = proxy(target, IPointsAwardService.class);
    }

    @AfterAll
    void cleanup() {
        if (dataSource != null) {
            new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                    .execute("DROP DATABASE IF EXISTS " + mysql.databaseName());
        }
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM ums_member_points_detail");
        jdbc.update("DELETE FROM ums_member");
        jdbc.update("DELETE FROM merchant_payable_batch");
        jdbc.update("INSERT INTO ums_member(id,point) VALUES (1,100)");
        jdbc.update("INSERT INTO merchant_payable_batch(id,merchant_member_id,period_ym,amount_total,status,invoice_status,confirmed_by)"
                + " VALUES (1,500,'2026-09',100,'CONFIRMED','NONE','ops_a')");
    }

    @Test
    void priorRepeatableReadAndMemberCacheDoNotCorruptRewardSnapshotAfterAnotherBusinessDeducts() {
        tx.executeWithoutResult(status -> {
            assertEquals(Long.valueOf(100), members.selectUmsMemberById(1L).getPoint());
            requiresNew.executeWithoutResult(other -> assertEquals(1, members.deductPoints(1L, 30)));
            // Plain read stays at 100 in the outer transaction. Award must see the committed 70 instead.
            assertEquals(Long.valueOf(100), members.selectUmsMemberById(1L).getPoint());
            assertEquals(5, points.award(1L, 12L, 101L, "节点打卡"));
        });
        assertEquals(75, number("SELECT point FROM ums_member WHERE id=1"));
        assertEquals(70, number("SELECT current_points FROM ums_member_points_detail"));
        assertEquals(75, number("SELECT after_points FROM ums_member_points_detail"));
        assertEquals(5, number("SELECT SUM(change_points) FROM ums_member_points_detail"));
    }

    @Test
    void concurrentDifferentEventsHaveAContinuousLedgerAndConserveBalance() throws Exception {
        List<Integer> awards = awardConcurrently(101L, 102L);
        assertEquals(10, awards.get(0) + awards.get(1));
        assertEquals(110, number("SELECT point FROM ums_member WHERE id=1"));
        assertEquals(10, number("SELECT SUM(change_points) FROM ums_member_points_detail"));
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_points_detail WHERE current_points=100 AND after_points=105"));
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_points_detail WHERE current_points=105 AND after_points=110"));
    }

    @Test
    void concurrentSameEventStillCommitsOnlyOneReward() throws Exception {
        List<Integer> awards = awardConcurrently(101L, 101L);
        assertEquals(5, awards.get(0) + awards.get(1));
        assertEquals(105, number("SELECT point FROM ums_member WHERE id=1"));
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_points_detail"));
        assertEquals(5, number("SELECT SUM(change_points) FROM ums_member_points_detail"));
    }

    @Test
    void nullPointAndMissingMemberDoNotCreatePhantomLedgerCredit() {
        jdbc.update("UPDATE ums_member SET point=NULL WHERE id=1");
        assertThrows(ServiceException.class, () -> points.award(1L, 12L, 101L, "节点打卡"));
        assertNull(jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
        assertThrows(ServiceException.class, () -> points.award(2L, 12L, 101L, "节点打卡"));
        assertEquals(0, number("SELECT COUNT(*) FROM ums_member_points_detail"));
    }

    @Test
    void concurrentClawbackToNonPositiveAmountBlocksAllThreeProgressions() {
        for (String action : new String[]{"confirm", "paid", "invoice"}) {
            jdbc.update("UPDATE merchant_payable_batch SET amount_total=100,status=?,invoice_status='NONE',frozen=0 WHERE id=1",
                    "confirm".equals(action) ? "PENDING" : "CONFIRMED");
            MerchantPayableBatchMapper interleaved = mock(MerchantPayableBatchMapper.class,
                    org.mockito.AdditionalAnswers.delegatesTo(batches));
            doAnswer(invocation -> {
                MerchantPayableBatch snapshot = batches.selectById(1L);
                requiresNew.executeWithoutResult(other -> assertEquals(1,
                        batches.deductForClawback(1L, new BigDecimal("120"))));
                return snapshot;
            }).when(interleaved).selectById(1L);
            IMerchantPayableService service = payable(interleaved);
            boolean changed = "confirm".equals(action) ? service.confirmBatch(1L, "ops_b")
                    : "paid".equals(action) ? service.markBatchPaid(1L, "ops_b", "TEST-VOUCHER")
                    : service.markBatchInvoiceIssued(1L, "TEST-INVOICE");
            assertFalse(changed, action + " must honor the current non-positive amount");
            Map<String, Object> current = jdbc.queryForMap("SELECT * FROM merchant_payable_batch WHERE id=1");
            assertEquals(0, new BigDecimal("-20").compareTo((BigDecimal) current.get("amount_total")));
            assertEquals("confirm".equals(action) ? "PENDING" : "CONFIRMED", current.get("status"));
            assertEquals("NONE", current.get("invoice_status"));
            assertNull(current.get("paid_at"));
            assertNull(current.get("pay_voucher_no"));
        }
    }

    @Test
    void concurrentFreezePreventsBlueInvoiceEvenAfterTheServiceSawAnUnfrozenBatch() {
        MerchantPayableBatchMapper interleaved = mock(MerchantPayableBatchMapper.class,
                org.mockito.AdditionalAnswers.delegatesTo(batches));
        doAnswer(invocation -> {
            MerchantPayableBatch snapshot = batches.selectById(1L);
            requiresNew.executeWithoutResult(other -> {
                MerchantPayableBatch freeze = new MerchantPayableBatch();
                freeze.setId(1L);
                freeze.setFrozenBy("ops_freeze");
                freeze.setFrozenReason("test fixture");
                assertEquals(1, batches.freeze(freeze));
            });
            return snapshot;
        }).when(interleaved).selectById(1L);
        assertFalse(payable(interleaved).markBatchInvoiceIssued(1L, "TEST-INVOICE"));
        assertEquals(1, number("SELECT frozen FROM merchant_payable_batch WHERE id=1"));
        assertEquals("NONE", jdbc.queryForObject("SELECT invoice_status FROM merchant_payable_batch WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT invoice_no FROM merchant_payable_batch WHERE id=1", String.class));
    }

    @Test
    void unchangedPositiveBatchesCanStillConfirmPayAndIssueAnInvoice() {
        jdbc.update("UPDATE merchant_payable_batch SET status='PENDING' WHERE id=1");
        IMerchantPayableService service = payable(batches);
        assertTrue(service.confirmBatch(1L, "ops_a"));
        assertTrue(service.markBatchPaid(1L, "ops_b", "TEST-VOUCHER"));
        assertTrue(service.markBatchInvoiceIssued(1L, "TEST-INVOICE"));
        Map<String, Object> current = jdbc.queryForMap("SELECT * FROM merchant_payable_batch WHERE id=1");
        assertEquals("PAID", current.get("status"));
        assertEquals("ISSUED", current.get("invoice_status"));
        assertEquals("TEST-VOUCHER", current.get("pay_voucher_no"));
        assertNotNull(current.get("paid_at"));
    }

    private List<Integer> awardConcurrently(long eventA, long eventB) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch snapshots = new CountDownLatch(2);
        try {
            Future<Integer> a = pool.submit(() -> concurrentAward(eventA, snapshots));
            Future<Integer> b = pool.submit(() -> concurrentAward(eventB, snapshots));
            return java.util.Arrays.asList(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "Concurrent test workers did not stop");
        }
    }

    private int concurrentAward(long eventId, CountDownLatch snapshots) {
        try {
            return tx.execute(status -> {
                assertEquals(Long.valueOf(100), members.selectUmsMemberById(1L).getPoint());
                snapshots.countDown();
                try {
                    assertTrue(snapshots.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return points.award(1L, 12L, eventId, "节点打卡");
            });
        } catch (DuplicateKeyException sameEventConflict) {
            // The real unique key loser rolls back the increment; independent JDBC assertions enforce conservation.
            return 0;
        }
    }

    private IMerchantPayableService payable(MerchantPayableBatchMapper mapper) {
        MerchantPayableServiceImpl target = new MerchantPayableServiceImpl();
        ReflectionTestUtils.setField(target, "batchMapper", mapper);
        return proxy(target, IMerchantPayableService.class);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Object target, Class<T> api) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(api);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private long number(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
