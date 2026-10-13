package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.mapper.SettlementRuleMapper;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.impl.SettlementEntryServiceImpl;
import com.chengyinhub.business.service.impl.SettlementRuleServiceImpl;
import com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R9-40 真实 MyBatis/MySQL8 使用期闸门:start 前拒绝且零副作用、start 后可核销一次、
 * end 后拒、并发只结算一次,以及 claimUnusedCoupon SQL 自身对 start_time 的约束。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponLifecycleMybatisIntegrationTest {

    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("coupon_lifecycle_it");

    private DataSource dataSource;
    private ISmsCouponHistoryService couponHistoryService;
    private SmsCouponHistoryMapper historyMapper;

    @BeforeAll
    void createDatabaseAndServices() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = dataSource(MYSQL.databaseUrl());
        SqlSessionTemplate template = new SqlSessionTemplate(buildFactory(dataSource));
        PlatformTransactionManager tx = new DataSourceTransactionManager(dataSource);

        SettlementRuleServiceImpl ruleService = new SettlementRuleServiceImpl();
        ReflectionTestUtils.setField(ruleService, "settlementRuleMapper", template.getMapper(SettlementRuleMapper.class));
        SettlementEntryServiceImpl settlementService = new SettlementEntryServiceImpl();
        ReflectionTestUtils.setField(settlementService, "settlementDetailMapper", template.getMapper(SettlementDetailMapper.class));
        ReflectionTestUtils.setField(settlementService, "settlementRuleService", ruleService);

        SmsCouponHistoryServiceImpl target = new SmsCouponHistoryServiceImpl();
        ReflectionTestUtils.setField(target, "smsCouponHistoryMapper", template.getMapper(SmsCouponHistoryMapper.class));
        ReflectionTestUtils.setField(target, "smsCouponMapper", template.getMapper(SmsCouponMapper.class));
        ReflectionTestUtils.setField(target, "settlementEntryService", settlementService);
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", template.getMapper(BusinessAuditEventMapper.class));
        couponHistoryService = transactionalProxy(target, ISmsCouponHistoryService.class, tx);
        historyMapper = template.getMapper(SmsCouponHistoryMapper.class);
    }

    @BeforeEach
    void resetSchema() throws Exception {
        execute("DROP TABLE IF EXISTS business_audit_event", "DROP TABLE IF EXISTS settlement_detail",
                "DROP TABLE IF EXISTS settlement_rule", "DROP TABLE IF EXISTS sms_coupon_history",
                "DROP TABLE IF EXISTS sms_coupon");
        execute(
                "CREATE TABLE sms_coupon (id BIGINT PRIMARY KEY, name VARCHAR(100), coupon_type INT, platform INT, amount DECIMAL(12,2), min_point DECIMAL(12,2),"
                        + "start_time DATETIME(3), end_time DATETIME(3), use_type INT, publish_count BIGINT, use_count BIGINT, receive_count BIGINT,"
                        + "enable_time DATETIME(3), code VARCHAR(100), scope_type INT, member_id BIGINT, merchant_id BIGINT, status INT, del_flag INT,"
                        + "description VARCHAR(500), create_by VARCHAR(64), create_time DATETIME(3), update_by VARCHAR(64), update_time DATETIME(3)) ENGINE=InnoDB",
                "CREATE TABLE sms_coupon_history (id BIGINT PRIMARY KEY AUTO_INCREMENT, coupon_id BIGINT, member_id BIGINT, coupon_code VARCHAR(100), qrcode_url VARCHAR(500),"
                        + "get_type INT, get_time DATETIME(3), use_status INT, use_time DATETIME(3), order_id BIGINT, order_sn VARCHAR(100),"
                        + "start_time DATETIME(3), end_time DATETIME(3), note VARCHAR(500), del_flag INT, create_by VARCHAR(64), create_time DATETIME(3),"
                        + "update_by VARCHAR(64), update_time DATETIME(3)) ENGINE=InnoDB",
                "CREATE TABLE settlement_rule (id BIGINT PRIMARY KEY AUTO_INCREMENT, merchant_id BIGINT, biz_type VARCHAR(64), rule_name VARCHAR(100),"
                        + "settlement_cycle VARCHAR(64), platform_commission_rate DECIMAL(8,4), merchant_rate DECIMAL(8,4), creator_rate DECIMAL(8,4),"
                        + "inviter_rate DECIMAL(8,4), guarantee_base_rate DECIMAL(8,4), guarantee_step_rate DECIMAL(8,4), guarantee_max_merchant INT,"
                        + "unit_price DECIMAL(18,2), entitlement_max_usage INT, entitlement_days INT,"
                        + "min_settlement_amount DECIMAL(12,2), refund_deadline_hours INT, allow_refund_after_verified INT, auto_approve_refund INT,"
                        + "status INT, del_flag INT, create_time DATETIME(3), update_time DATETIME(3)) ENGINE=InnoDB",
                "CREATE TABLE settlement_detail (id BIGINT PRIMARY KEY AUTO_INCREMENT, claim_id BIGINT NULL, entry_kind VARCHAR(24) NULL,"
                        + "event_key VARCHAR(128) NULL, batch_id BIGINT, merchant_id BIGINT, source_type VARCHAR(64),"
                        + "source_id BIGINT, receiver_type VARCHAR(64), receiver_id BIGINT, gross_amount DECIMAL(12,2), commission_amount DECIMAL(12,2),"
                        + "settlement_amount DECIMAL(12,2), status INT, fail_reason VARCHAR(500), idempotency_key VARCHAR(255),"
                        + "create_time DATETIME(3), update_time DATETIME(3), UNIQUE KEY uk_settlement_idempotency(idempotency_key),"
                        + "UNIQUE KEY uk_settlement_detail_claim_event(claim_id, entry_kind, event_key)) ENGINE=InnoDB",
                "CREATE TABLE business_audit_event (id BIGINT PRIMARY KEY AUTO_INCREMENT, domain_type VARCHAR(32) NOT NULL, domain_id BIGINT NOT NULL,"
                        + "owner_member_id BIGINT NOT NULL, actor_type VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',"
                        + "actor_member_id BIGINT NOT NULL, actor_role_code VARCHAR(64) NOT NULL,"
                        + "action VARCHAR(64) NOT NULL, target_type VARCHAR(64), target_id BIGINT, relation_id BIGINT,"
                        + "before_json TEXT, after_json TEXT, reason VARCHAR(500), request_id VARCHAR(64), create_time DATETIME(3) NOT NULL) ENGINE=InnoDB");
        execute("INSERT INTO sms_coupon(id,name,amount,use_count,member_id,scope_type,merchant_id,status,del_flag)"
                        + " VALUES (30,'使用期券',10,0,99,1,77,1,0)");
        // 时间夹具必须经 JDBC 绑定(JVM LOCAL 往返一致),不能用 DB 的 NOW()/DATE_SUB:
        // 本机 MySQL 8 的 time_zone=SYSTEM/UTC 而 JVM 是 PDT,差 7 小时,DB 表达式造的
        // 「1 秒前/后」在服务端(JVM 时钟)看会整体偏到未来,边界样例会被环境时差反噬。
        java.util.Date now = new java.util.Date();
        // 10 未开始(+1d~+3d);11 已开始(-1s~+3d);12 已结束(-2d~-1s);13 合法历史(start NULL);
        // 14 已开始用于并发
        insertHistory(10L, "not-started", 0, addSeconds(now, 86_400), addSeconds(now, 3 * 86_400));
        insertHistory(11L, "started", 0, addSeconds(now, -1), addSeconds(now, 3 * 86_400));
        insertHistory(12L, "ended", 0, addSeconds(now, -2 * 86_400), addSeconds(now, -1));
        insertHistory(13L, "legacy-null-start", 0, null, addSeconds(now, 86_400));
        insertHistory(14L, "concurrent", 0, addSeconds(now, -86_400), addSeconds(now, 86_400));
    }

    private static java.util.Date addSeconds(java.util.Date base, long seconds) {
        return new java.util.Date(base.getTime() + seconds * 1000L);
    }

    private void insertHistory(long id, String code, Integer useStatus,
                               java.util.Date startTime, java.util.Date endTime) throws Exception {
        try (Connection c = dataSource.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO sms_coupon_history(id,coupon_id,member_id,coupon_code,use_status,start_time,end_time,del_flag)"
                             + " VALUES (?,30,99,?,?,?,?,0)")) {
            ps.setLong(1, id);
            ps.setString(2, code);
            ps.setInt(3, useStatus);
            if (startTime == null) {
                ps.setNull(4, java.sql.Types.TIMESTAMP);
            } else {
                ps.setTimestamp(4, new java.sql.Timestamp(startTime.getTime()));
            }
            ps.setTimestamp(5, new java.sql.Timestamp(endTime.getTime()));
            ps.executeUpdate();
        }
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    @Test
    void futureStartRejectedWithZeroSideEffects() throws Exception {
        assertEquals(ISmsCouponHistoryService.RedemptionResult.NOT_STARTED,
                couponHistoryService.redeemCoupon(10L, 99L, 77L, 99L, "MERCHANT_OWNER"));

        assertEquals(0, number("SELECT use_status FROM sms_coupon_history WHERE id=10"));
        assertEquals(0, number("SELECT COUNT(*) FROM sms_coupon_history WHERE id=10 AND use_time IS NOT NULL"));
        assertEquals(0, number("SELECT use_count FROM sms_coupon WHERE id=30"));
        assertEquals(0, number("SELECT COUNT(*) FROM settlement_detail"));
        assertEquals(0, number("SELECT COUNT(*) FROM business_audit_event"));
    }

    @Test
    void endedCouponStillRejectedAndMarkedExpired() throws Exception {
        assertEquals(ISmsCouponHistoryService.RedemptionResult.EXPIRED,
                couponHistoryService.redeemCoupon(12L, 99L, 77L, 99L, "MERCHANT_OWNER"));

        assertEquals(2, number("SELECT use_status FROM sms_coupon_history WHERE id=12"));
        assertEquals(0, number("SELECT use_count FROM sms_coupon WHERE id=30"));
        assertEquals(0, number("SELECT COUNT(*) FROM settlement_detail"));
        assertEquals(0, number("SELECT COUNT(*) FROM business_audit_event"));
    }

    @Test
    void startedAndLegacyNullStartRedeemExactlyOnce() throws Exception {
        assertEquals(ISmsCouponHistoryService.RedemptionResult.REDEEMED,
                couponHistoryService.redeemCoupon(11L, 99L, 77L, 99L, "MERCHANT_OWNER"));
        assertEquals(ISmsCouponHistoryService.RedemptionResult.REDEEMED,
                couponHistoryService.redeemCoupon(13L, 99L, 77L, 99L, "MERCHANT_OWNER"));

        assertEquals(1, number("SELECT use_status FROM sms_coupon_history WHERE id=11"));
        assertEquals(1, number("SELECT use_status FROM sms_coupon_history WHERE id=13"));
        assertEquals(2, number("SELECT use_count FROM sms_coupon WHERE id=30"));
        assertEquals(2, number("SELECT COUNT(*) FROM settlement_detail WHERE source_type='coupon'"));
        assertEquals(2, number("SELECT COUNT(*) FROM business_audit_event WHERE action='COUPON_REDEEM'"));
    }

    @Test
    void claimSqlItselfRefusesNotStartedCoupon() throws Exception {
        assertEquals(0, historyMapper.claimUnusedCoupon(10L, new java.util.Date()),
                "claimUnusedCoupon 必须在 SQL 原子条件里拒绝 start 前的券");
        assertEquals(0, number("SELECT use_status FROM sms_coupon_history WHERE id=10"));

        assertEquals(1, historyMapper.claimUnusedCoupon(11L, new java.util.Date()));
        assertEquals(1, number("SELECT use_status FROM sms_coupon_history WHERE id=11"));
    }

    @Test
    void startAndEndBoundariesAreInclusiveOnTheExactInstant() throws Exception {
        java.util.Date boundary = new java.util.Date();
        insertHistory(20L, "exact-start-end", 0, boundary, boundary);
        assertEquals(1, historyMapper.claimUnusedCoupon(20L, boundary),
                "start=end=useTime 的精确边界必须包含端点(沿用现有截止包含语义)");

        java.util.Date oneMsBefore = new java.util.Date(boundary.getTime() - 1L);
        insertHistory(21L, "one-ms-before-start", 0, boundary, addSeconds(boundary, 60));
        assertEquals(0, historyMapper.claimUnusedCoupon(21L, oneMsBefore),
                "早于 start 1ms 必须拒绝");

        insertHistory(22L, "end-exact", 0, addSeconds(boundary, -60), boundary);
        assertEquals(1, historyMapper.claimUnusedCoupon(22L, boundary),
                "end=useTime 的精确边界必须包含端点");

        insertHistory(23L, "one-ms-after-end", 0, addSeconds(boundary, -60), oneMsBefore);
        assertEquals(0, historyMapper.claimUnusedCoupon(23L, boundary),
                "晚于 end 1ms 必须拒绝");
    }

    @Test
    void concurrentRedemptionOnStartedCouponSettlesOnce() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ISmsCouponHistoryService.RedemptionResult> a = pool.submit(() -> redeemAfter(ready, start));
            Future<ISmsCouponHistoryService.RedemptionResult> b = pool.submit(() -> redeemAfter(ready, start));
            ready.await();
            start.countDown();
            Set<ISmsCouponHistoryService.RedemptionResult> results = new HashSet<>();
            results.add(a.get());
            results.add(b.get());
            assertEquals(EnumSet.of(ISmsCouponHistoryService.RedemptionResult.REDEEMED,
                    ISmsCouponHistoryService.RedemptionResult.ALREADY_USED), results);
            assertEquals(1, number("SELECT use_status FROM sms_coupon_history WHERE id=14"));
            assertEquals(1, number("SELECT use_count FROM sms_coupon WHERE id=30"));
            assertEquals(1, number("SELECT COUNT(*) FROM settlement_detail WHERE source_type='coupon' AND source_id=14"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lastAvailableCouponConcurrentGrantCannotOverIssue() throws Exception {
        execute("UPDATE sms_coupon SET publish_count=1, receive_count=0 WHERE id=30");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> { ready.countDown(); start.await(); return couponHistoryService.grantCouponById(7001L, 30L); });
            Future<Boolean> b = pool.submit(() -> { ready.countDown(); start.await(); return couponHistoryService.grantCouponById(7002L, 30L); });
            org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (a.get(10, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
                    + (b.get(10, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, number("SELECT receive_count FROM sms_coupon WHERE id=30"));
            assertEquals(0, number("SELECT publish_count-receive_count FROM sms_coupon WHERE id=30"));
            assertEquals(1, number("SELECT COUNT(*) FROM sms_coupon_history WHERE member_id IN (7001,7002)"));
            org.junit.jupiter.api.Assertions.assertFalse(couponHistoryService.grantCouponById(7003L, 30L));
            assertEquals(1, number("SELECT receive_count FROM sms_coupon WHERE id=30"));
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test
    void unlimitedCouponKeepsGrantingAndMaintainsActualReceiveCount() throws Exception {
        for (String count : new String[]{"NULL", "0", "-1"}) {
            execute("UPDATE sms_coupon SET publish_count=" + count + ", receive_count=NULL WHERE id=30");
            org.junit.jupiter.api.Assertions.assertTrue(couponHistoryService.grantCouponById(7001L, 30L));
            org.junit.jupiter.api.Assertions.assertTrue(couponHistoryService.grantCouponById(7002L, 30L));
            assertEquals(2, number("SELECT receive_count FROM sms_coupon WHERE id=30"));
        }
        assertEquals(6, number("SELECT COUNT(*) FROM sms_coupon_history WHERE member_id IN (7001,7002)"));
    }

    @Test
    void failedHistoryInsertRollsBackReservedStockAndCanRetry() throws Exception {
        execute("UPDATE sms_coupon SET publish_count=1, receive_count=0 WHERE id=30",
                "ALTER TABLE sms_coupon_history ADD CONSTRAINT reject_test_member CHECK (member_id <> 7001)");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> couponHistoryService.grantCouponById(7001L, 30L));
        assertEquals(0, number("SELECT receive_count FROM sms_coupon WHERE id=30"));
        assertEquals(0, number("SELECT COUNT(*) FROM sms_coupon_history WHERE member_id=7001"));
        execute("ALTER TABLE sms_coupon_history DROP CHECK reject_test_member");
        org.junit.jupiter.api.Assertions.assertTrue(couponHistoryService.grantCouponById(7001L, 30L));
        assertEquals(1, number("SELECT receive_count FROM sms_coupon WHERE id=30"));
        assertEquals(1, number("SELECT COUNT(*) FROM sms_coupon_history WHERE member_id=7001"));
    }

    @Test
    void schemaDeletedCouponsNeverRedeemOrRewriteHistoricalUseState() throws Exception {
        for (int deleted : new int[]{1, 2}) {
            for (int state : new int[]{0, 1, 2}) {
                execute("UPDATE sms_coupon_history SET del_flag=" + deleted + ",use_status=" + state + " WHERE id=11");
                assertEquals(ISmsCouponHistoryService.RedemptionResult.NOT_FOUND,
                        couponHistoryService.redeemCoupon(11L, 99L, 77L, 99L, "MERCHANT_OWNER"));
                assertEquals(state, number("SELECT use_status FROM sms_coupon_history WHERE id=11"));
                assertEquals(0, historyMapper.claimUnusedCoupon(11L, new java.util.Date()));
            }
        }
        assertEquals(0, number("SELECT use_count FROM sms_coupon WHERE id=30"));
        assertEquals(0, number("SELECT COUNT(*) FROM settlement_detail"));
        assertEquals(0, number("SELECT COUNT(*) FROM business_audit_event"));
    }

    @Test
    void historyListDelFlagFilterExcludesSoftDeletedRowsAndNullMeansUnfiltered() throws Exception {
        execute("UPDATE sms_coupon_history SET coupon_code='same-code' WHERE id IN (11,13)",
                "UPDATE sms_coupon_history SET del_flag=2 WHERE id=11",
                "UPDATE sms_coupon_history SET del_flag=NULL WHERE id=13");
        SmsCouponHistory live = new SmsCouponHistory();
        live.setCouponCode("same-code");
        live.setDelFlag(0);
        java.util.List<SmsCouponHistory> rows = historyMapper.selectSmsCouponHistoryList(live);
        // 真实 XML:del_flag=2 行不得被选中;历史 NULL 按未删处理。
        assertEquals(1, rows.size());
        assertEquals(13L, rows.get(0).getId());
        // 负控:不传 delFlag 的调用方(后台 RuoYi 列表/导出)语义不变,仍能看到软删行。
        SmsCouponHistory all = new SmsCouponHistory();
        all.setCouponCode("same-code");
        assertEquals(2, historyMapper.selectSmsCouponHistoryList(all).size());
    }

    @Test
    void revokeAfterInitialReadIsResolvedFromCurrentFactWithoutExpiryOrRedemptionSideEffects() throws Exception {
        Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(couponHistoryService);
        SmsCouponHistoryMapper real = historyMapper;
        for (long id : new long[]{11L, 12L}) {
            SmsCouponHistoryMapper interleaved = org.mockito.Mockito.mock(SmsCouponHistoryMapper.class,
                    org.mockito.AdditionalAnswers.delegatesTo(real));
            org.mockito.Mockito.doAnswer(call -> {
                SmsCouponHistory before = real.selectSmsCouponHistoryById(id);
                // 独立连接在原普通读后提交撤销；后续真实CAS/当前读必须看到del_flag=2。
                execute("UPDATE sms_coupon_history SET del_flag=2 WHERE id=" + id);
                return before;
            }).when(interleaved).selectSmsCouponHistoryById(id);
            ReflectionTestUtils.setField(target, "smsCouponHistoryMapper", interleaved);
            try {
                assertEquals(ISmsCouponHistoryService.RedemptionResult.NOT_FOUND,
                        couponHistoryService.redeemCoupon(id, 99L, 77L, 99L, "MERCHANT_OWNER"));
                assertEquals(0, number("SELECT use_status FROM sms_coupon_history WHERE id=" + id));
                assertEquals(2, number("SELECT del_flag FROM sms_coupon_history WHERE id=" + id));
            } finally {
                ReflectionTestUtils.setField(target, "smsCouponHistoryMapper", real);
            }
        }
        assertEquals(0, number("SELECT use_count FROM sms_coupon WHERE id=30"));
        assertEquals(0, number("SELECT COUNT(*) FROM settlement_detail"));
        assertEquals(0, number("SELECT COUNT(*) FROM business_audit_event"));
    }

    private ISmsCouponHistoryService.RedemptionResult redeemAfter(CountDownLatch ready,
                                                                   CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return couponHistoryService.redeemCoupon(14L, 99L, 77L, 99L, "MERCHANT_OWNER");
    }

    private int number(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
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
        Configuration configuration = new Configuration(new Environment("coupon-lifecycle-it",
                new SpringManagedTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        addMapper(configuration, SmsCouponMapper.class, "mapper/business/SmsCouponMapper.xml");
        addMapper(configuration, SmsCouponHistoryMapper.class, "mapper/business/SmsCouponHistoryMapper.xml");
        addMapper(configuration, BusinessAuditEventMapper.class, "mapper/business/BusinessAuditEventMapper.xml");
        addMapper(configuration, SettlementRuleMapper.class, "mapper/business/SettlementRuleMapper.xml");
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

    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(Object target, Class<T> serviceInterface,
                                     PlatformTransactionManager transactionManager) {
        TransactionInterceptor interceptor = new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(serviceInterface);
        proxyFactory.addAdvice(interceptor);
        return (T) proxyFactory.getProxy();
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
