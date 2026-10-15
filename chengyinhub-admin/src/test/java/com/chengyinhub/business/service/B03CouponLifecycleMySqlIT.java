package com.chengyinhub.business.service;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** E04: real source-receipt locking and reverse-redemption SQL/transaction gaps. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class B03CouponLifecycleMySqlIT {

    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("e04_coupon_it");

    private DataSource dataSource;
    private ISmsCouponHistoryService couponHistoryService;
    private SmsCouponHistoryMapper historyMapper;
    private SmsCouponHistoryServiceImpl target;
    private boolean databaseCreated;

    @BeforeAll
    void createDatabaseAndServices() throws Exception {
        requireExplicitScratchEnvironment();
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet version = s.executeQuery("SELECT VERSION()")) {
                version.next();
                org.junit.jupiter.api.Assertions.assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(version.getString(1)));
            }
            // CREATE without IF NOT EXISTS refuses to touch an existing schema.
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
            databaseCreated = true;
        }
        dataSource = dataSource(MYSQL.databaseUrl());
        SqlSessionTemplate template = new SqlSessionTemplate(buildFactory(dataSource));
        PlatformTransactionManager tx = new DataSourceTransactionManager(dataSource);

        SettlementRuleServiceImpl ruleService = new SettlementRuleServiceImpl();
        ReflectionTestUtils.setField(ruleService, "settlementRuleMapper", template.getMapper(SettlementRuleMapper.class));
        SettlementEntryServiceImpl settlementService = new SettlementEntryServiceImpl();
        ReflectionTestUtils.setField(settlementService, "settlementDetailMapper", template.getMapper(SettlementDetailMapper.class));
        ReflectionTestUtils.setField(settlementService, "settlementRuleService", ruleService);

        target = new SmsCouponHistoryServiceImpl();
        ReflectionTestUtils.setField(target, "smsCouponHistoryMapper", template.getMapper(SmsCouponHistoryMapper.class));
        ReflectionTestUtils.setField(target, "smsCouponMapper", template.getMapper(SmsCouponMapper.class));
        ReflectionTestUtils.setField(target, "settlementEntryService", settlementService);
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", template.getMapper(BusinessAuditEventMapper.class));
        ReflectionTestUtils.setField(target, "couponSourceGrantMapper", template.getMapper(com.chengyinhub.business.mapper.CouponSourceGrantMapper.class));
        ReflectionTestUtils.setField(target, "rewardDropLogMapper", template.getMapper(com.chengyinhub.business.mapper.RewardDropLogMapper.class));
        couponHistoryService = transactionalProxy(target, ISmsCouponHistoryService.class, tx);
        historyMapper = template.getMapper(SmsCouponHistoryMapper.class);
    }

    @BeforeEach
    void resetSchema() throws Exception {
        execute("DROP TABLE IF EXISTS coupon_source_grant", "DROP TABLE IF EXISTS reward_drop_log", "DROP TABLE IF EXISTS business_audit_event", "DROP TABLE IF EXISTS settlement_detail",
                "DROP TABLE IF EXISTS settlement_rule", "DROP TABLE IF EXISTS sms_coupon_history",
                "DROP TABLE IF EXISTS sms_coupon");
        execute(
                "CREATE TABLE coupon_source_grant (source_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, source_id BIGINT NOT NULL, member_id BIGINT NOT NULL, coupon_id BIGINT NOT NULL, history_id BIGINT NULL, created_at DATETIME NOT NULL, delivered_at DATETIME NULL, PRIMARY KEY(source_type,source_id), UNIQUE KEY uk_coupon_source_history(history_id)) ENGINE=InnoDB",
                "CREATE TABLE reward_drop_log (id BIGINT PRIMARY KEY, member_id BIGINT, topic_id BIGINT, node_id BIGINT, activity_id BIGINT, scene_key VARCHAR(64), drop_type INT, param_json TEXT) ENGINE=InnoDB",
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
        execute("INSERT INTO reward_drop_log(id,member_id,drop_type,param_json) VALUES (51,7001,3,'{\"couponTemplateId\":30}')",
                "INSERT INTO coupon_source_grant(source_type,source_id,member_id,coupon_id,created_at) VALUES ('reward_drop',51,7001,30,NOW())",
                "UPDATE sms_coupon SET publish_count=2,receive_count=0 WHERE id=30",
                "INSERT INTO sms_coupon_history(id,coupon_id,member_id,coupon_code,use_status,del_flag) VALUES (14,30,7002,'reverse-e04',1,0)",
                "UPDATE sms_coupon SET use_count=1 WHERE id=30",
                "INSERT INTO settlement_detail(id,source_type,source_id,batch_id,status) VALUES (81,'coupon',14,0,0),(82,'coupon',14,9,0),(83,'coupon',14,0,1),(84,'other',14,0,0)");
    }

    @AfterAll
    void dropDatabase() throws Exception {
        if (!databaseCreated) return;
        try (Connection c = dataSource(MYSQL.adminUrl()).getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE " + MYSQL.databaseName());
        }
    }

    private void requireExplicitScratchEnvironment() {
        for (String key : new String[]{"CHENGYIN_TEST_MYSQL_HOST", "CHENGYIN_TEST_MYSQL_PORT",
                "CHENGYIN_TEST_MYSQL_DATABASE_BASE", "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX", "CHENGYIN_TEST_MYSQL_USER"}) {
            String value = System.getenv(key);
            org.junit.jupiter.api.Assertions.assertTrue(value != null && !value.trim().isEmpty(), key + " must be explicit");
        }
        org.junit.jupiter.api.Assertions.assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_PASSWORD"));
        org.junit.jupiter.api.Assertions.assertEquals("e04_scratch", System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_BASE"));
    }

    @Test
    void concurrentSameSourceReturnsOneOriginalHistoryAndConsumesOneStock() throws Exception {
        Object real = ReflectionTestUtils.getField(target, "couponSourceGrantMapper");
        installReadBarrier("couponSourceGrantMapper", com.chengyinhub.business.mapper.CouponSourceGrantMapper.class,
                real, "selectForUpdate", false);
        java.util.List<Long> results;
        try {
            results = race(() -> couponHistoryService.grantCouponBySource(7001L,30L,"reward_drop",51L));
        } finally {
            ReflectionTestUtils.setField(target, "couponSourceGrantMapper", real);
        }
        org.junit.jupiter.api.Assertions.assertNotNull(results.get(0));
        assertEquals(results.get(0), results.get(1));
        assertEquals(1, number("SELECT receive_count FROM sms_coupon WHERE id=30"));
        assertEquals(1, number("SELECT COUNT(*) FROM sms_coupon_history WHERE member_id=7001"));
        assertEquals(results.get(0).longValue(), longNumber("SELECT history_id FROM coupon_source_grant WHERE source_id=51"));
        assertEquals(results.get(0), couponHistoryService.grantCouponBySource(7001L,30L,"reward_drop",51L));
        assertEquals(1, number("SELECT receive_count FROM sms_coupon WHERE id=30"));
    }

    @Test
    void receiptConstraintFailureRollsBackHistoryAndStockBeforeRetry() throws Exception {
        execute("ALTER TABLE coupon_source_grant ADD CONSTRAINT e04_reject_delivery CHECK (history_id IS NULL)");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> couponHistoryService.grantCouponBySource(7001L,30L,"reward_drop",51L));
        assertEquals(0, number("SELECT receive_count FROM sms_coupon WHERE id=30"));
        assertEquals(0, number("SELECT COUNT(*) FROM sms_coupon_history WHERE member_id=7001"));
        assertEquals(1, number("SELECT COUNT(*) FROM coupon_source_grant WHERE history_id IS NULL AND delivered_at IS NULL"));
        execute("ALTER TABLE coupon_source_grant DROP CHECK e04_reject_delivery");
        Long id = couponHistoryService.grantCouponBySource(7001L,30L,"reward_drop",51L);
        org.junit.jupiter.api.Assertions.assertNotNull(id);
        assertEquals(id.longValue(), longNumber("SELECT history_id FROM coupon_source_grant WHERE source_id=51"));
        assertEquals(1, number("SELECT receive_count FROM sms_coupon WHERE id=30"));
        assertEquals(1, number("SELECT COUNT(*) FROM sms_coupon_history WHERE member_id=7001"));
    }

    @Test
    void concurrentReverseCasDeletesOnlyUnbatchedPendingSettlementOnce() throws Exception {
        installReadBarrier("smsCouponHistoryMapper", SmsCouponHistoryMapper.class,
                historyMapper, "selectSmsCouponHistoryById", true);
        java.util.List<Boolean> results;
        try {
            results = race(() -> couponHistoryService.reverseRedemption(14L,"E04 synthetic reversal",77L));
        } finally {
            ReflectionTestUtils.setField(target, "smsCouponHistoryMapper", historyMapper);
        }
        assertEquals(1, (results.get(0) ? 1 : 0) + (results.get(1) ? 1 : 0));
        assertEquals(0, number("SELECT use_status FROM sms_coupon_history WHERE id=14"));
        assertEquals(0, number("SELECT use_count FROM sms_coupon WHERE id=30"));
        assertEquals(0, number("SELECT COUNT(*) FROM settlement_detail WHERE id=81"));
        assertEquals(3, number("SELECT COUNT(*) FROM settlement_detail WHERE id IN (82,83,84)"));
        assertEquals(1, number("SELECT COUNT(*) FROM business_audit_event WHERE action='COUPON_REDEEM_REVERSE' AND target_id=14 AND actor_type='SYS_USER'"));
    }

    @Test
    void auditConstraintFailureRollsBackRealReverseSqlIncludingSettlementDeletion() throws Exception {
        execute("ALTER TABLE business_audit_event ADD CONSTRAINT e04_reject_reverse CHECK (action <> 'COUPON_REDEEM_REVERSE')");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> couponHistoryService.reverseRedemption(14L,"E04 synthetic reversal",77L));
        assertEquals(1, number("SELECT use_status FROM sms_coupon_history WHERE id=14"));
        assertEquals(1, number("SELECT use_count FROM sms_coupon WHERE id=30"));
        assertEquals(4, number("SELECT COUNT(*) FROM settlement_detail"));
        assertEquals(0, number("SELECT COUNT(*) FROM business_audit_event"));
        execute("ALTER TABLE business_audit_event DROP CHECK e04_reject_reverse");
        org.junit.jupiter.api.Assertions.assertTrue(couponHistoryService.reverseRedemption(14L,"E04 retry",77L));
        assertEquals(0, number("SELECT use_status FROM sms_coupon_history WHERE id=14"));
        assertEquals(0, number("SELECT use_count FROM sms_coupon WHERE id=30"));
        assertEquals(3, number("SELECT COUNT(*) FROM settlement_detail"));
        assertEquals(1, number("SELECT COUNT(*) FROM business_audit_event"));
    }

    // Instrument timing only: every read/write still invokes the production MyBatis mapper.
    // Source contenders both reach the receipt lock; reverse contenders both read used=1 before CAS.
    private void installReadBarrier(String field, Class<?> mapperType, Object real,
                                    String methodName, boolean afterRead) {
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        Object timed = java.lang.reflect.Proxy.newProxyInstance(mapperType.getClassLoader(),
                new Class<?>[]{mapperType}, (proxy, method, args) -> {
                    boolean gated = methodName.equals(method.getName());
                    if (gated && !afterRead) barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    Object result;
                    try {
                        result = method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if (gated && afterRead) barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return result;
                });
        ReflectionTestUtils.setField(target, field, timed);
    }

    private <T> java.util.List<T> race(java.util.concurrent.Callable<T> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<T> contender = () -> {
                ready.countDown();
                org.junit.jupiter.api.Assertions.assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                return action.call();
            };
            Future<T> a = pool.submit(contender);
            Future<T> b = pool.submit(contender);
            org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            return java.util.Arrays.asList(a.get(15, java.util.concurrent.TimeUnit.SECONDS), b.get(15, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            start.countDown();
            pool.shutdownNow();
            org.junit.jupiter.api.Assertions.assertTrue(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private long longNumber(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            org.junit.jupiter.api.Assertions.assertTrue(rs.next());
            long value = rs.getLong(1);
            org.junit.jupiter.api.Assertions.assertFalse(rs.wasNull());
            return value;
        }
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
        addMapper(configuration, com.chengyinhub.business.mapper.CouponSourceGrantMapper.class, "mapper/business/CouponSourceGrantMapper.xml");
        addMapper(configuration, com.chengyinhub.business.mapper.RewardDropLogMapper.class, "mapper/business/RewardDropLogMapper.xml");
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
