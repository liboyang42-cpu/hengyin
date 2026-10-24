package com.chengyinhub.business.service;

import com.chengyinhub.web.service.verify.CityNodeRedemptionApplication;

import com.chengyinhub.business.mapper.CmsRegistrationScanMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.web.controller.api.ApiVerifyController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
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
import java.util.HashSet;
import java.util.Set;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** VerifyAnswer 与优惠券核销的真实 MyBatis/数据库并发门禁。 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationEquityMybatisIntegrationTest {

    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("equity_core_it");

    private DataSource dataSource;
    private ISmsCouponHistoryService couponHistoryService;

    @BeforeAll
    void createDatabaseAndServices() throws Exception {
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
        PlatformTransactionManager tx = new DataSourceTransactionManager(dataSource);

        SettlementRuleServiceImpl ruleService = new SettlementRuleServiceImpl();
        ReflectionTestUtils.setField(ruleService, "settlementRuleMapper", template.getMapper(SettlementRuleMapper.class));
        SettlementEntryServiceImpl settlementService = new SettlementEntryServiceImpl();
        ReflectionTestUtils.setField(settlementService, "settlementDetailMapper", template.getMapper(SettlementDetailMapper.class));
        ReflectionTestUtils.setField(settlementService, "settlementRuleService", ruleService);

        SmsCouponHistoryServiceImpl couponTarget = new SmsCouponHistoryServiceImpl();
        ReflectionTestUtils.setField(couponTarget, "smsCouponHistoryMapper", template.getMapper(SmsCouponHistoryMapper.class));
        ReflectionTestUtils.setField(couponTarget, "smsCouponMapper", template.getMapper(SmsCouponMapper.class));
        ReflectionTestUtils.setField(couponTarget, "settlementEntryService", settlementService);
        ReflectionTestUtils.setField(couponTarget, "businessAuditEventMapper", template.getMapper(BusinessAuditEventMapper.class));
        couponHistoryService = transactionalProxy(couponTarget, ISmsCouponHistoryService.class, tx);
    }

    @BeforeEach
    void resetSchema() throws Exception {
        execute("DROP TABLE IF EXISTS ums_member", "DROP TABLE IF EXISTS business_audit_event", "DROP TABLE IF EXISTS settlement_detail", "DROP TABLE IF EXISTS settlement_rule",
                "DROP TABLE IF EXISTS sms_coupon_history", "DROP TABLE IF EXISTS sms_coupon",
                "DROP TABLE IF EXISTS cms_registration_merchant", "DROP TABLE IF EXISTS cms_member_template",
                "DROP TABLE IF EXISTS cms_topic_node",
                "DROP TABLE IF EXISTS cms_registration_scan", "DROP TABLE IF EXISTS cms_registration",
                "DROP TABLE IF EXISTS cms_activity", "DROP TABLE IF EXISTS roam_poi_reward_log");
        String baseline = java.nio.file.Files.readString(java.nio.file.Paths.get("../sql/base_schema_20260818.sql"));
        java.util.regex.Matcher memberDdl = java.util.regex.Pattern.compile(
                "CREATE TABLE `ums_member` \\([\\s\\S]*?\\) ENGINE=InnoDB[^;]*;").matcher(baseline);
        assertTrue(memberDdl.find(), "真实基线必须提供完整会员表，owner锁不得以mock绕过");
        execute(memberDdl.group(), "INSERT INTO ums_member(id,role,status,del_flag) VALUES(7001,'player',1,0)");
        execute(
                "CREATE TABLE roam_poi_reward_log (poi_id BIGINT, member_id BIGINT, coupon_id BIGINT, status INT, PRIMARY KEY(poi_id,member_id)) ENGINE=InnoDB",
                "CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT) ENGINE=InnoDB",
                "CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, owner_type BIGINT, owner_id BIGINT) ENGINE=InnoDB",
                "CREATE TABLE cms_registration_scan (id VARCHAR(64) PRIMARY KEY, registration_type INT, registration_id BIGINT, member_id BIGINT,"
                        + "validation_method INT, question_answer VARCHAR(500), feedback_text VARCHAR(500), status INT, name VARCHAR(500), address TEXT,"
                        + "latitude VARCHAR(500), longitude VARCHAR(500), template_id BIGINT, create_by VARCHAR(64), create_time DATETIME(3),"
                        + "update_by VARCHAR(64), update_time DATETIME(3)) ENGINE=InnoDB",
                "CREATE TABLE cms_member_template (id BIGINT PRIMARY KEY, question_answer VARCHAR(500), coupon_id BIGINT, del_flag INT) ENGINE=InnoDB",
                "CREATE TABLE cms_topic_node (id BIGINT PRIMARY KEY, topic_id BIGINT, template_id BIGINT) ENGINE=InnoDB",
                "CREATE TABLE cms_registration_merchant (id BIGINT PRIMARY KEY, topic_id BIGINT, member_id BIGINT, question_answer VARCHAR(500),"
                        + "coupon_id BIGINT, del_flag INT) ENGINE=InnoDB",
                "CREATE TABLE sms_coupon (id BIGINT PRIMARY KEY, name VARCHAR(100), coupon_type INT, platform INT, amount DECIMAL(12,2), min_point DECIMAL(12,2),"
                        + "start_time DATETIME(3), end_time DATETIME(3), use_type INT, publish_count BIGINT, use_count BIGINT, receive_count BIGINT,"
                        + "enable_time DATETIME(3), code VARCHAR(100), scope_type INT, member_id BIGINT, merchant_id BIGINT, status INT, del_flag INT,"
                        + "description VARCHAR(500), create_by VARCHAR(64), create_time DATETIME(3), update_by VARCHAR(64), update_time DATETIME(3)) ENGINE=InnoDB",
                "CREATE TABLE sms_coupon_history (id BIGINT PRIMARY KEY, coupon_id BIGINT, member_id BIGINT, coupon_code VARCHAR(100), qrcode_url VARCHAR(500),"
                        + "get_type INT, get_time DATETIME(3), use_status INT, use_time DATETIME(3), order_id BIGINT, order_sn VARCHAR(100),"
                        + "start_time DATETIME(3), end_time DATETIME(3), note VARCHAR(500), del_flag INT, create_by VARCHAR(64), create_time DATETIME(3),"
                        + "update_by VARCHAR(64), update_time DATETIME(3)) ENGINE=InnoDB",
                "CREATE TABLE settlement_rule (id BIGINT PRIMARY KEY AUTO_INCREMENT, merchant_id BIGINT, biz_type VARCHAR(64), rule_name VARCHAR(100),"
                        + "settlement_cycle VARCHAR(64), platform_commission_rate DECIMAL(8,4), merchant_rate DECIMAL(8,4), creator_rate DECIMAL(8,4),"
                        + "inviter_rate DECIMAL(8,4), guarantee_base_rate DECIMAL(8,4), guarantee_step_rate DECIMAL(8,4), guarantee_max_merchant INT,"
                        // 商业化定价三列(migration_commerce_order_fields_and_verification_review_20260805.sql)。
                        // 漏了这三列 → SettlementRuleMapper 的 selectVo 报 Unknown column,funds IT 整套红。
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
                        // actor_type 与生产 migration_approval_governance_20260828.sql 同形:
                        // VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'。这张表在 IT 里是手写 DDL,
                        // 加列的迁移不会自动传导过来 —— 漏了这行,mapper 一插就 Unknown column。
                        + "owner_member_id BIGINT NOT NULL, actor_type VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',"
                        + "actor_member_id BIGINT NOT NULL, actor_role_code VARCHAR(64) NOT NULL,"
                        + "action VARCHAR(64) NOT NULL, target_type VARCHAR(64), target_id BIGINT, relation_id BIGINT,"
                        + "before_json TEXT, after_json TEXT, reason VARCHAR(500), request_id VARCHAR(64), create_time DATETIME(3) NOT NULL) ENGINE=InnoDB");
        execute("INSERT INTO cms_registration(id,owner_type,owner_id) VALUES (11,1,1)",
                "INSERT INTO cms_member_template(id,question_answer,coupon_id,del_flag) VALUES (10,'北纬31',NULL,0)",
                "INSERT INTO cms_topic_node(id,topic_id,template_id) VALUES (100,1,10)",
                "INSERT INTO cms_registration_scan(id,registration_type,registration_id,member_id,status,template_id) VALUES ('scan-1',0,11,99,0,10)",
                "INSERT INTO sms_coupon(id,name,amount,use_count,member_id,scope_type,merchant_id,status,del_flag) VALUES (30,'并发核销券',10,0,99,1,77,1,0)",
                "INSERT INTO sms_coupon_history(id,coupon_id,member_id,coupon_code,use_status,end_time,del_flag)"
                        + " VALUES (20,30,7,'COU-20',0,DATE_ADD(NOW(),INTERVAL 1 DAY),0)");
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    @Test
    void concurrentCouponRedemptionSettlesAndCountsOnce() throws Exception {
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
            assertEquals(1, number("SELECT use_status FROM sms_coupon_history WHERE id=20"));
            assertEquals(1, number("SELECT use_count FROM sms_coupon WHERE id=30"));
            assertEquals(1, number("SELECT COUNT(*) FROM settlement_detail WHERE source_type='coupon' AND source_id=20"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void couponCountFailureRollsBackClaimAndSettlement() throws Exception {
        execute("ALTER TABLE sms_coupon ADD CONSTRAINT ck_no_use_count CHECK (use_count = 0)");
        assertThrows(RuntimeException.class, () -> couponHistoryService.redeemCoupon(20L, 99L, 77L, 99L, "MERCHANT_OWNER"));
        assertEquals(0, number("SELECT use_status FROM sms_coupon_history WHERE id=20"));
        assertEquals(0, number("SELECT COUNT(*) FROM settlement_detail"));
    }

    @Test
    void couponGrantHistoryFailureRollsBackReceiveCountThroughProxy() throws Exception {
        execute("UPDATE sms_coupon SET publish_count=5,receive_count=0 WHERE id=30",
                "ALTER TABLE sms_coupon_history ADD CONSTRAINT ck_grant_history_fail CHECK (get_type <> 0)");

        assertThrows(RuntimeException.class, () -> couponHistoryService.grantCouponById(99L, 30L));

        assertEquals(0, number("SELECT receive_count FROM sms_coupon WHERE id=30"));
        assertEquals(1, number("SELECT COUNT(*) FROM sms_coupon_history"));
    }

    @Test
    void expiryBatchOnlyTransitionsUnusedCouponAndNeverOverwritesRedeemed() throws Exception {
        execute("INSERT INTO sms_coupon_history(id,coupon_id,member_id,coupon_code,use_status,end_time,del_flag) "
                        + "VALUES (21,30,99,'unused-expired',0,DATE_SUB(NOW(),INTERVAL 1 DAY),0)",
                "INSERT INTO sms_coupon_history(id,coupon_id,member_id,coupon_code,use_status,end_time,del_flag) "
                        + "VALUES (22,30,99,'redeemed-expired',1,DATE_SUB(NOW(),INTERVAL 1 DAY),0)");

        assertEquals(1, couponHistoryService.updateExpiredCouponsByMemberId(99L));

        assertEquals(2, number("SELECT use_status FROM sms_coupon_history WHERE id=21"));
        assertEquals(1, number("SELECT use_status FROM sms_coupon_history WHERE id=22"));
    }

    private ISmsCouponHistoryService.RedemptionResult redeemAfter(CountDownLatch ready,
                                                                    CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return couponHistoryService.redeemCoupon(20L, 99L, 77L, 99L, "MERCHANT_OWNER");
    }

    @Test
    void cityNodeFrozenCouponSurvivesStockFailureAndConcurrentRetry() throws Exception {
        execute("ALTER TABLE sms_coupon_history MODIFY id BIGINT NOT NULL AUTO_INCREMENT",
                "INSERT INTO roam_poi_reward_log VALUES (51,7001,30,0)",
                "UPDATE sms_coupon SET publish_count=1, receive_count=1 WHERE id=30");
        SqlSessionTemplate session = new SqlSessionTemplate(buildFactory(dataSource));
        RoamMapper realMapper = session.getMapper(RoamMapper.class);
        // Detail and identity are preconditions; reward CAS, coupon stock/history and audit use real XML.
        RoamMapper mapper = mock(RoamMapper.class, invocation -> {
            try {
                return invocation.getMethod().invoke(realMapper, invocation.getArguments());
            } catch (java.lang.reflect.InvocationTargetException failed) {
                throw failed.getCause();
            }
        });
        CityNodeVO detail = new CityNodeVO();
        detail.setMerchantId(77L);
        detail.setStatus(0);
        detail.setCouponId(999L); // Current template changed; entitlement remains coupon 30.
        org.mockito.Mockito.doReturn(detail).when(mapper).selectMerchantNodeDetail(51L);
        ApiVerifyController target = new ApiVerifyController() {
            @Override public Long getAppUserId() { return 99L; }
        };
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(99L, MerchantPermission.VERIFY)).thenReturn(
                new MerchantAccessContext(99L, 77L, 99L, MerchantRoleCode.MERCHANT_OWNER));
        ReflectionTestUtils.setField(target, "merchantAccessService", access);
        CityNodeRedemptionApplication application = new CityNodeRedemptionApplication(
                access, mapper, couponHistoryService, session.getMapper(BusinessAuditEventMapper.class), mock(com.chengyinhub.business.service.impl.QrcodeServiceImpl.class), "city-node-it-secret");
        ProxyFactory factory = new ProxyFactory(application);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        ReflectionTestUtils.setField(target, "cityNodeRedemptionApplication", (CityNodeRedemptionApplication) factory.getProxy());
        ApiVerifyController controller = target;
        String code = VerifyDynCodeUtil.issue(51L, "citynode_7001", System.currentTimeMillis(),
                60000L, 77L, "city-node-it-secret");
        com.chengyinhub.common.exception.ServiceException exhausted = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class, () -> controller.redeemCityNodeCode(code));
        assertEquals("券库存不足,核销失败", exhausted.getMessage(), "首轮必须真实到达库存回滚，不得把SQL错误当成功负控");
        assertEquals(0, number("SELECT status FROM roam_poi_reward_log WHERE poi_id=51"));
        assertEquals(0, number("SELECT COUNT(*) FROM sms_coupon_history WHERE member_id=7001"));
        assertEquals(0, number("SELECT COUNT(*) FROM business_audit_event WHERE domain_type='CITY_NODE'"));
        execute("UPDATE sms_coupon SET publish_count=2 WHERE id=30");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> first = pool.submit(() -> { start.await(); return controller.redeemCityNodeCode(code).get(AjaxResult.CODE_TAG); });
            Future<Object> second = pool.submit(() -> { start.await(); return controller.redeemCityNodeCode(code).get(AjaxResult.CODE_TAG); });
            start.countDown();
            Object a = first.get(), b = second.get();
            assertEquals(1, (Integer.valueOf(200).equals(a) ? 1 : 0) + (Integer.valueOf(200).equals(b) ? 1 : 0));
        } finally { pool.shutdownNow(); }
        assertEquals(1, number("SELECT status FROM roam_poi_reward_log WHERE poi_id=51"));
        assertEquals(1, number("SELECT COUNT(*) FROM sms_coupon_history WHERE member_id=7001 AND coupon_id=30"));
        assertEquals(0, number("SELECT COUNT(*) FROM sms_coupon_history WHERE coupon_id=999"));
        assertEquals(2, number("SELECT receive_count FROM sms_coupon WHERE id=30"));
        assertEquals(1, number("SELECT COUNT(*) FROM business_audit_event WHERE domain_type='CITY_NODE'"));
    }

    @Test
    void cityNodeOriginalReceiptSurvivesResponseLossExpiryAndCouponChangeWithoutAnotherGrant() throws Exception {
        execute("ALTER TABLE sms_coupon_history MODIFY id BIGINT NOT NULL AUTO_INCREMENT",
                "INSERT INTO roam_poi_reward_log VALUES (51,7001,30,0)",
                "UPDATE sms_coupon SET publish_count=2, receive_count=1 WHERE id=30");
        SqlSessionTemplate session = new SqlSessionTemplate(buildFactory(dataSource));
        RoamMapper realMapper = session.getMapper(RoamMapper.class);
        RoamMapper mapper = mock(RoamMapper.class, invocation -> {
            try { return invocation.getMethod().invoke(realMapper, invocation.getArguments()); }
            catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
        });
        CityNodeVO detail = new CityNodeVO(); detail.setMerchantId(77L); detail.setStatus(0); detail.setCouponId(999L);
        org.mockito.Mockito.doReturn(detail).when(mapper).selectMerchantNodeDetail(51L);
        org.mockito.Mockito.doReturn(detail).when(mapper).selectPlayerMerchantNodeDetail(51L, 0L);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(99L, MerchantPermission.VERIFY)).thenReturn(
                new MerchantAccessContext(99L, 77L, 99L, MerchantRoleCode.MERCHANT_OWNER));
        CityNodeRedemptionApplication target = new CityNodeRedemptionApplication(access, mapper, couponHistoryService,
                session.getMapper(BusinessAuditEventMapper.class), new com.chengyinhub.business.service.impl.QrcodeServiceImpl(), "city-node-it-secret");
        ProxyFactory factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        java.util.concurrent.atomic.AtomicLong actor = new java.util.concurrent.atomic.AtomicLong(7001L);
        ApiVerifyController controller = new ApiVerifyController() { @Override public Long getAppUserId() { return actor.get(); } };
        ReflectionTestUtils.setField(controller, "cityNodeRedemptionApplication", (CityNodeRedemptionApplication) factory.getProxy());
        AjaxResult issued = controller.issueCityNodeCode(51L); assertEquals(200, issued.get("code"));
        String code = (String) ((java.util.Map<?, ?>) issued.get("data")).get("code");
        assertTrue(VerifyDynCodeUtil.verify(code, System.currentTimeMillis(), "city-node-it-secret").valid);
        actor.set(99L); AjaxResult original = controller.redeemCityNodeCode(code); assertEquals(200, original.get("code"));
        assertEquals(30L, ((java.util.Map<?, ?>) original.get("data")).get("couponId"));
        execute("UPDATE sms_coupon SET status=3 WHERE id=30");
        String expired = VerifyDynCodeUtil.issue(51L, "citynode_7001", 1L, 1L, 999L, "city-node-it-secret");
        assertEquals(original, controller.cityNodeReceipt(expired), "丢失响应后只能读保存的原结论，不能用当前停发状态改写");
        assertEquals(original, controller.cityNodeReceipt(code));
        assertEquals("已过期", controller.redeemCityNodeCode(expired).get("msg"), "原结果回读不能放松新核销TTL");
        assertEquals(1, number("SELECT status FROM roam_poi_reward_log WHERE poi_id=51 AND member_id=7001"));
        assertEquals(1, number("SELECT COUNT(*) FROM sms_coupon_history WHERE member_id=7001 AND coupon_id=30"));
        assertEquals(2, number("SELECT receive_count FROM sms_coupon WHERE id=30"));
        assertEquals(1, number("SELECT COUNT(*) FROM business_audit_event WHERE domain_type='CITY_NODE'"));
        org.mockito.Mockito.doThrow(new com.chengyinhub.common.exception.ServiceException("商家身份无效或权限不足", 403))
                .when(access).require(99L, MerchantPermission.VERIFY);
        com.chengyinhub.common.exception.ServiceException revoked = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class, () -> controller.cityNodeReceipt(code));
        assertEquals(Integer.valueOf(403), revoked.getCode());
        assertEquals(1, number("SELECT COUNT(*) FROM sms_coupon_history WHERE member_id=7001 AND coupon_id=30"));
        assertEquals(1, number("SELECT COUNT(*) FROM business_audit_event WHERE domain_type='CITY_NODE'"));
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
        Configuration configuration = new Configuration(new Environment("equity-it",
                new SpringManagedTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        addMapper(configuration, RoamMapper.class, "mapper/business/RoamMapper.xml");
        addMapper(configuration, CmsRegistrationScanMapper.class, "mapper/business/CmsRegistrationScanMapper.xml");
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
