package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.SettlementBatch;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.dto.SettlementGenerateDTO;
import com.chengyinhub.business.mapper.SettlementBatchMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.service.impl.SettlementServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-6-S块 1.1 对抗验证复现的"并发幽灵批次"红绿钉：真实 MySQL + 真实 @Transactional 代理，
 * 两个线程同时对同一商家调用 generateBatch（不同 idempotencyKey，模拟运营双击/两人同时点按钮）。
 *
 * <p>修复前：两次调用各自读到同一份待结算快照、各自 insertSettlementBatch 写死
 * total_amount=100，只有一个能在 bindBatch 里真正绑到明细行 —— 另一个留下"声称应结100元、
 * 实际零绑定明细"的幽灵批次。修复后：败者在 bindBatch 里绑到 0 行，直接抛异常回滚，
 * settlement_batch 里只应留下一行、金额与实际绑定的 settlement_detail 之和一致。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettlementGenerateBatchMybatisIntegrationTest {

    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("settlement_generate_batch_it");

    private DataSource dataSource;
    private ISettlementService settlementService;

    @BeforeAll
    void createDatabaseAndService() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            // ★ 2026-08-19:先 DROP 再 CREATE,不接受"环境里恰好剩下一个同名库"。
            // 原来是 CREATE DATABASE IF NOT EXISTS ⇒ 本类默默继承上一次(可能是崩在半路的那次)留下的表,
            // 于是 @BeforeEach 建表撞 "Table 'xxx' already exists" —— #741 的资金闸就是这么连红的:
            // 本地怎么跑都绿,只有 runner 上那个库有残留。库名本来就一类一个(见 MysqlIntegrationTestConfig),
            // 这里补的是另一半:**每次开跑都从零建**,隔离才是完整的。
            // 同一条规矩在 business/it/ExploreItDatabase 已经这么写了,这里对齐。
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = dataSource(MYSQL.databaseUrl());
        SqlSessionTemplate template = new SqlSessionTemplate(buildFactory(dataSource));
        SettlementBatchMapper batchMapper = template.getMapper(SettlementBatchMapper.class);
        SettlementDetailMapper detailMapper = template.getMapper(SettlementDetailMapper.class);

        SettlementServiceImpl target = new SettlementServiceImpl();
        ReflectionTestUtils.setField(target, "settlementBatchMapper", batchMapper);
        ReflectionTestUtils.setField(target, "settlementDetailMapper", detailMapper);

        DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(ISettlementService.class);
        proxyFactory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        settlementService = (ISettlementService) proxyFactory.getProxy();
    }

    @BeforeEach
    void resetSchema() throws Exception {
        execute("DROP TABLE IF EXISTS settlement_detail", "DROP TABLE IF EXISTS settlement_batch",
                "DROP TABLE IF EXISTS cms_registration", "DROP TABLE IF EXISTS cms_topic");
        execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, end_date DATETIME NULL) ENGINE=InnoDB",
                "CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, owner_type BIGINT, owner_id BIGINT) ENGINE=InnoDB");
        execute(
                "CREATE TABLE settlement_batch ("
                        + "id BIGINT PRIMARY KEY AUTO_INCREMENT, batch_no VARCHAR(64) NOT NULL, merchant_id BIGINT NOT NULL,"
                        + "period_start DATETIME, period_end DATETIME, total_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00,"
                        + "detail_count BIGINT NOT NULL DEFAULT 0, success_count BIGINT NOT NULL DEFAULT 0,"
                        + "failed_count BIGINT NOT NULL DEFAULT 0, status TINYINT NOT NULL DEFAULT 0,"
                        + "idempotency_key VARCHAR(128) NOT NULL, reviewer_id BIGINT, reviewed_at DATETIME,"
                        + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP, update_time DATETIME,"
                        + "UNIQUE KEY uk_batch_no(batch_no), UNIQUE KEY uk_idempotency_key(idempotency_key)"
                        + ") ENGINE=InnoDB",
                "CREATE TABLE settlement_detail ("
                        + "id BIGINT PRIMARY KEY AUTO_INCREMENT, claim_id BIGINT NULL, entry_kind VARCHAR(24) NULL,"
                        + "event_key VARCHAR(128) NULL, batch_id BIGINT NOT NULL, merchant_id BIGINT NOT NULL,"
                        + "source_type VARCHAR(32) NOT NULL, source_id BIGINT NOT NULL, receiver_type VARCHAR(32) NOT NULL,"
                        + "receiver_id BIGINT NOT NULL, gross_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00,"
                        + "commission_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00, settlement_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00,"
                        + "status TINYINT NOT NULL DEFAULT 0, fail_reason VARCHAR(255), idempotency_key VARCHAR(160) NOT NULL,"
                        + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP, update_time DATETIME,"
                        + "UNIQUE KEY uk_detail_idempotency(idempotency_key),"
                        + "UNIQUE KEY uk_settlement_detail_claim_event(claim_id, entry_kind, event_key),"
                        + "UNIQUE KEY uk_source_receiver(source_type, source_id, receiver_type, receiver_id),"
                        + "KEY idx_batch(batch_id), KEY idx_merchant_status(merchant_id, status)"
                        + ") ENGINE=InnoDB");
        execute("INSERT INTO settlement_detail "
                + "(batch_id, merchant_id, source_type, source_id, receiver_type, receiver_id, "
                + "gross_amount, commission_amount, settlement_amount, status, idempotency_key) VALUES "
                + "(0, 30, 'order', 1, 'merchant', 30, 100.00, 0.00, 100.00, 0, 'DETAIL-1'), "
                + "(0, 30, 'order', 2, 'merchant', 30, 100.00, 0.00, 100.00, 0, 'DETAIL-2')");
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    /**
     * 红绿钉：两个真实线程用不同 idempotencyKey 并发调用 generateBatch(merchant=30)。
     * 修复前会两个都成功，产生一个 total_amount=200 却零绑定明细的幽灵批次；
     * 修复后必须恰好一个成功、另一个抛 IllegalStateException 回滚，且成功那个的
     * total_amount/detail_count 与实际绑定的 settlement_detail 之和/条数完全一致。
     */
    @Test
    void concurrentGenerateBatchDoesNotLeaveGhostBatch() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> failureA = new AtomicReference<>();
        AtomicReference<Throwable> failureB = new AtomicReference<>();
        try {
            Future<Long> a = pool.submit(() -> generateAfter(ready, start, "concurrent-a", failureA));
            Future<Long> b = pool.submit(() -> generateAfter(ready, start, "concurrent-b", failureB));
            ready.await();
            start.countDown();
            Long idA = a.get();
            Long idB = b.get();

            int successCount = 0;
            successCount += idA != null ? 1 : 0;
            successCount += idB != null ? 1 : 0;
            assertEquals(1, successCount, "同一商家的两次并发 generateBatch 必须恰好一个成功");

            Throwable loserError = idA == null ? failureA.get() : failureB.get();
            assertNotNull(loserError, "败者必须抛异常触发事务回滚，而不是静默产生幽灵批次");
            assertTrue(loserError instanceof IllegalStateException, "败者失败原因必须是「无待结算明细」，不是别的异常");

            assertEquals(1, number("SELECT COUNT(*) FROM settlement_batch WHERE merchant_id=30"),
                    "败者的占位批次必须随事务一起回滚，settlement_batch 里不能残留幽灵批次");

            Long winnerId = idA != null ? idA : idB;
            BigDecimal batchTotal = decimal("SELECT total_amount FROM settlement_batch WHERE id=" + winnerId);
            long batchDetailCount = number("SELECT detail_count FROM settlement_batch WHERE id=" + winnerId);
            BigDecimal boundDetailSum = decimal(
                    "SELECT COALESCE(SUM(settlement_amount),0) FROM settlement_detail WHERE batch_id=" + winnerId);
            long boundDetailCount = number("SELECT COUNT(*) FROM settlement_detail WHERE batch_id=" + winnerId);

            assertEquals(0, batchTotal.compareTo(boundDetailSum),
                    "批次 total_amount 必须等于它实际绑定明细金额之和，不能凭读快照虚报");
            assertEquals(boundDetailCount, batchDetailCount,
                    "批次 detail_count 必须等于它实际绑定的明细条数");
            assertEquals(0, new BigDecimal("200.00").compareTo(batchTotal),
                    "赢家必须绑到全部两条待结算明细，金额=200");
            assertEquals(2, boundDetailCount);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void singleCallStillWorksNormally() {
        SettlementGenerateDTO request = new SettlementGenerateDTO();
        request.setMerchantId(30L);
        request.setBatchNo("S-SINGLE-001");
        request.setIdempotencyKey("single-call");

        Long batchId = settlementService.generateBatch(request);

        assertNotNull(batchId);
        SettlementBatch batch = settlementService.selectBatchList(query(30L)).stream()
                .filter(b -> b.getId().equals(batchId)).findFirst().orElse(null);
        assertNotNull(batch);
        assertEquals(0, new BigDecimal("200.00").compareTo(batch.getTotalAmount()));
        assertEquals(2L, batch.getDetailCount());
        SettlementDetail detailQuery = new SettlementDetail();
        detailQuery.setBatchId(batchId);
        assertEquals(2, settlementService.selectDetailList(detailQuery).size());
    }

    /**
     * FX-13(9-15 拍板):主题级票(owner_type=1)只能在主题 end_date + 7 天之后进结算批次。
     * 修复前 selectPendingByMerchant/bindBatch 只看 merchant/batch_id/status,主题还没结束就能被归批、
     * confirmBatch 后置 status=1 —— 退款时 voidPendingBySource 作废不到它。
     * 未满 7 天(含 end_date 为空)的主题票留在 batch_id=0;满 7 天的主题票、场次票照常归批。
     */
    @Test
    void topicTicketsStayOutOfBatchUntilSevenDaysAfterTopicEnd() throws Exception {
        execute("INSERT INTO cms_topic (id, end_date) VALUES "
                        + "(501, DATE_ADD(NOW(), INTERVAL 3 DAY)), (502, DATE_SUB(NOW(), INTERVAL 6 DAY)), "
                        + "(503, NULL), (504, DATE_SUB(NOW(), INTERVAL 8 DAY))",
                "INSERT INTO cms_registration (id, owner_type, owner_id) VALUES "
                        + "(9001, 1, 501), (9002, 1, 502), (9003, 1, 503), (9004, 1, 504), (9005, 2, 501)",
                "INSERT INTO settlement_detail (batch_id, merchant_id, source_type, source_id, receiver_type, receiver_id, "
                        + "gross_amount, commission_amount, settlement_amount, status, idempotency_key) VALUES "
                        + "(0, 30, 'registration', 9001, 'merchant', 30, 10.00, 0.00, 10.00, 0, 'REG-NOT-ENDED'), "
                        + "(0, 30, 'registration', 9002, 'merchant', 30, 10.00, 0.00, 10.00, 0, 'REG-ENDED-6D'), "
                        + "(0, 30, 'registration', 9003, 'merchant', 30, 10.00, 0.00, 10.00, 0, 'REG-NO-END'), "
                        + "(0, 30, 'registration', 9004, 'merchant', 30, 10.00, 0.00, 10.00, 0, 'REG-ENDED-8D'), "
                        + "(0, 30, 'registration', 9005, 'merchant', 30, 10.00, 0.00, 10.00, 0, 'REG-ACTIVITY')");
        SettlementGenerateDTO request = new SettlementGenerateDTO();
        request.setMerchantId(30L);
        request.setBatchNo("S-FX13");
        request.setIdempotencyKey("fx13");

        Long batchId = settlementService.generateBatch(request);

        assertEquals(0, number("SELECT COUNT(*) FROM settlement_detail WHERE batch_id<>0 AND idempotency_key IN "
                + "('REG-NOT-ENDED','REG-ENDED-6D','REG-NO-END')"), "主题 end_date+7 天前的主题票不能进结算批次");
        assertEquals(4, number("SELECT COUNT(*) FROM settlement_detail WHERE batch_id=" + batchId),
                "两条 order + 满 7 天的主题票 + 场次票照常归批");
        assertEquals(0, new BigDecimal("220.00").compareTo(
                decimal("SELECT total_amount FROM settlement_batch WHERE id=" + batchId)));
    }

    private SettlementBatch query(Long merchantId) {
        SettlementBatch query = new SettlementBatch();
        query.setMerchantId(merchantId);
        return query;
    }

    private Long generateAfter(CountDownLatch ready, CountDownLatch start, String idempotencyKey,
                                AtomicReference<Throwable> failure) {
        ready.countDown();
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        SettlementGenerateDTO request = new SettlementGenerateDTO();
        request.setMerchantId(30L);
        request.setBatchNo("S-" + idempotencyKey);
        request.setIdempotencyKey(idempotencyKey);
        try {
            return settlementService.generateBatch(request);
        } catch (Throwable t) {
            failure.set(t);
            return null;
        }
    }

    private int number(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private BigDecimal decimal(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            BigDecimal value = rs.getBigDecimal(1);
            return value == null ? BigDecimal.ZERO : value;
        }
    }

    private void execute(String... sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : sql) {
                s.execute(statement);
            }
        }
    }

    private SqlSessionFactory buildFactory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("settlement-generate-batch-it",
                new SpringManagedTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        addMapper(configuration, SettlementBatchMapper.class, "mapper/business/SettlementBatchMapper.xml");
        addMapper(configuration, SettlementDetailMapper.class, "mapper/business/SettlementDetailMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void addMapper(Configuration configuration, Class<?> mapper, String resource) throws Exception {
        configuration.addMapper(mapper);
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到 MyBatis XML: " + resource);
            }
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }
}
