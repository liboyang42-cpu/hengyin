package com.chengyinhub.business.longrun;

import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.domain.SmsCoupon;

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

import static org.junit.jupiter.api.Assertions.assertEquals;

/** NOT_RUN: real MySQL current-status gate after an independently committed template update. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E04_10Test {

    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("e04_10_it");

    private DataSource dataSource;
    private ISmsCouponHistoryService couponHistoryService;
    private SmsCouponMapper realCoupons;
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
        couponHistoryService = transactionalProxy(target, ISmsCouponHistoryService.class, tx);
        realCoupons = template.getMapper(SmsCouponMapper.class);
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
        execute("INSERT INTO sms_coupon_history(id,coupon_id,member_id,coupon_code,use_status,del_flag)"
                + " VALUES (14,30,7002,'e04-10-unused',0,0)");
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
    void invalidationCommittedAfterTemplateReadRollsBackClaimCountAndSettlement() throws Exception {
        installStatusCommitAfterTemplateRead(3);
        try {
            IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> couponHistoryService.redeemCoupon(14L,99L,77L,99L,"MERCHANT_OWNER"));
            assertEquals("优惠券核销计数更新失败", failure.getMessage());
        } finally {
            ReflectionTestUtils.setField(target, "smsCouponMapper", realCoupons);
        }
        assertEquals(3, number("SELECT status FROM sms_coupon WHERE id=30"));
        assertEquals(0, number("SELECT use_status FROM sms_coupon_history WHERE id=14"));
        assertEquals(0, number("SELECT COUNT(*) FROM sms_coupon_history WHERE id=14 AND use_time IS NOT NULL"));
        assertEquals(0, number("SELECT use_count FROM sms_coupon WHERE id=30"));
        assertEquals(0, number("SELECT COUNT(*) FROM settlement_detail"));
        assertEquals(0, number("SELECT COUNT(*) FROM business_audit_event"));
    }

    @Test
    void stopCommittedAfterTemplateReadStillAllowsPreviouslyIssuedCouponRedemption() throws Exception {
        redeemAfterStatusCommitAndAssertSuccess(4);
    }

    @Test
    void nullStatusCommittedAfterTemplateReadKeepsLegacyRedemptionCompatibility() throws Exception {
        redeemAfterStatusCommitAndAssertSuccess(null);
    }

    private void redeemAfterStatusCommitAndAssertSuccess(Integer status) throws Exception {
        installStatusCommitAfterTemplateRead(status);
        try {
            assertEquals(ISmsCouponHistoryService.RedemptionResult.REDEEMED,
                    couponHistoryService.redeemCoupon(14L,99L,77L,99L,"MERCHANT_OWNER"));
        } finally {
            ReflectionTestUtils.setField(target, "smsCouponMapper", realCoupons);
        }
        if (status == null) {
            assertEquals(1, number("SELECT COUNT(*) FROM sms_coupon WHERE id=30 AND status IS NULL"));
        } else {
            assertEquals(status.intValue(), number("SELECT status FROM sms_coupon WHERE id=30"));
        }
        assertEquals(1, number("SELECT use_status FROM sms_coupon_history WHERE id=14"));
        assertEquals(1, number("SELECT use_count FROM sms_coupon WHERE id=30"));
        assertEquals(1, number("SELECT COUNT(*) FROM settlement_detail WHERE source_type='coupon' AND source_id=14"));
        assertEquals(1, number("SELECT COUNT(*) FROM business_audit_event WHERE action='COUPON_REDEEM' AND target_id=14"));
    }

    // Timing proxy only: production XML performs every read/write; a distinct JDBC connection commits
    // the template change after the original read but before history CAS and the current coupon UPDATE.
    private void installStatusCommitAfterTemplateRead(Integer status) {
        java.util.concurrent.atomic.AtomicBoolean committed = new java.util.concurrent.atomic.AtomicBoolean();
        SmsCouponMapper timed = (SmsCouponMapper) java.lang.reflect.Proxy.newProxyInstance(
                SmsCouponMapper.class.getClassLoader(), new Class<?>[]{SmsCouponMapper.class}, (proxy, method, args) -> {
                    if ("incrementUseCount".equals(method.getName())) {
                        Connection bound = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
                        try (Statement statement = bound.createStatement()) {
                            org.junit.jupiter.api.Assertions.assertFalse(bound.getAutoCommit());
                            try (ResultSet claim = statement.executeQuery("SELECT use_status FROM sms_coupon_history WHERE id=14")) {
                                org.junit.jupiter.api.Assertions.assertTrue(claim.next());
                                assertEquals(1, claim.getInt(1));
                            }
                            try (ResultSet entries = statement.executeQuery("SELECT COUNT(*) FROM settlement_detail WHERE source_type='coupon' AND source_id=14")) {
                                org.junit.jupiter.api.Assertions.assertTrue(entries.next());
                                assertEquals(1, entries.getInt(1));
                            }
                        } finally {
                            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(bound, dataSource);
                        }
                    }
                    Object result;
                    try {
                        result = method.invoke(realCoupons, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if ("selectSmsCouponById".equals(method.getName()) && committed.compareAndSet(false,true)) {
                        assertEquals(Integer.valueOf(1), ((SmsCoupon) result).getStatus());
                        try (Connection c = dataSource.getConnection(); java.sql.PreparedStatement ps = c.prepareStatement(
                                "UPDATE sms_coupon SET status=?, update_time=NOW() WHERE id=30")) {
                            org.junit.jupiter.api.Assertions.assertTrue(c.getAutoCommit());
                            if (status == null) ps.setNull(1, java.sql.Types.INTEGER);
                            else ps.setInt(1,status);
                            assertEquals(1, ps.executeUpdate());
                        }
                    }
                    return result;
                });
        ReflectionTestUtils.setField(target, "smsCouponMapper", timed);
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
        Configuration configuration = new Configuration(new Environment("e04-10-it",
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
