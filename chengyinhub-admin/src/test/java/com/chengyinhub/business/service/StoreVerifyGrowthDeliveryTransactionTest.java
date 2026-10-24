package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsRegistrationScanMapper;
import com.chengyinhub.business.mapper.GrowthMissionMapper;
import com.chengyinhub.business.mapper.StoreVerifyGrowthDeliveryMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.GrowthServiceImpl;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.business.service.impl.VerificationRiskService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实 MySQL：核销 CAS 与成长待办必须同一事务提交或回滚。 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreVerifyGrowthDeliveryTransactionTest {
    private String database;
    private boolean createdDatabase;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private ICmsRegistrationService registrationService;
    private IPerkLedgerService perkLedgerService;
    private SqlSessionTemplate sql;
    private DataSourceTransactionManager tx;
    private IGrowthService growthService;

    @BeforeAll
    void databaseAndServices() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("growth_tx");
        if (!config.adminUrl().startsWith("jdbc:mysql://127.0.0.1:")
                && !config.adminUrl().startsWith("jdbc:mysql://localhost:")) {
            throw new IllegalStateException("localhost scratch only");
        }
        database = "chengyin_growth_tx_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        createdDatabase = true;
        DriverManagerDataSource source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"),
                config.user(), config.password());
        db = new JdbcTemplate(source);
        // 核销当前锁读走真实完整报名投影；由既有探店日DDL建结构，不能用缺字段短表伪造拒绝。
        try (Connection connection = source.getConnection()) {
            com.chengyinhub.business.it.ItSchema.apply(connection, "it-schema/explore-alliance-it-schema.sql");
        }
        executeMigration(source, "sql/migration_20260930_registration_first_successful_scan.sql");
        db.execute("CREATE TABLE activity_cancel_manual_refund_case(id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                + "activity_id BIGINT,topic_id BIGINT,parent_order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,payer_member_id BIGINT,"
                + "payer_registration_id BIGINT,case_type VARCHAR(32) NOT NULL DEFAULT 'MIXED_TICKETS',"
                + "status VARCHAR(48),verified_registration_ids_json JSON,unverified_registration_ids_json JSON) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        db.execute("CREATE TABLE growth_mission(id BIGINT PRIMARY KEY AUTO_INCREMENT,mission_code VARCHAR(80) NOT NULL UNIQUE,"
                + "mission_name VARCHAR(120) NOT NULL,mission_desc VARCHAR(255),trigger_event VARCHAR(80) NOT NULL,"
                + "exp_reward INT NOT NULL DEFAULT 0,daily_limit INT NOT NULL DEFAULT 1,status CHAR(1) NOT NULL,"
                + "sort_no INT NOT NULL DEFAULT 0,create_time DATETIME,update_time DATETIME) ENGINE=InnoDB");
        executeMigration(source, "sql/migration_store_verify_growth_delivery_20260913.sql");
        // 与 base_schema 同列的核销台账表(整单核销撤销用例要读写真实 status)。
        db.execute("CREATE TABLE cms_registration_scan(id INT UNSIGNED PRIMARY KEY AUTO_INCREMENT,registration_type INT,"
                + "registration_id INT DEFAULT 0,member_id INT DEFAULT 0,validation_method TINYINT(1) DEFAULT 0,"
                + "question_answer TEXT,feedback_text TEXT,name VARCHAR(255) DEFAULT '',address VARCHAR(255) DEFAULT '',"
                + "latitude VARCHAR(255),longitude VARCHAR(255),template_id BIGINT DEFAULT 0,status INT DEFAULT 0,"
                + "create_by VARCHAR(64),create_time DATETIME,update_by VARCHAR(64),update_time DATETIME)"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(
                new ClassPathResource("mapper/business/CmsRegistrationMapper.xml"),
                new ClassPathResource("mapper/business/GrowthMissionMapper.xml"),
                new ClassPathResource("mapper/business/StoreVerifyGrowthDeliveryMapper.xml"),
                new ClassPathResource("mapper/business/CmsRegistrationScanMapper.xml"),
                new ClassPathResource("mapper/business/MerchantReferralBountyMapper.xml"));
        sql = new SqlSessionTemplate(factory.getObject());
        tx = new DataSourceTransactionManager(source);

        GrowthServiceImpl growthTarget = new GrowthServiceImpl();
        ReflectionTestUtils.setField(growthTarget, "growthMissionMapper", sql.getMapper(GrowthMissionMapper.class));
        ReflectionTestUtils.setField(growthTarget, "storeVerifyGrowthDeliveryMapper",
                sql.getMapper(StoreVerifyGrowthDeliveryMapper.class));
        growthService = transactionalProxy(growthTarget, IGrowthService.class, tx);

        CmsRegistrationScanMapper scanMapper = mock(CmsRegistrationScanMapper.class);
        when(scanMapper.selectCmsRegistrationScanList(any())).thenReturn(Collections.<CmsRegistrationScan>emptyList());
        BusinessAuditEventMapper auditMapper = mock(BusinessAuditEventMapper.class);
        when(auditMapper.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        perkLedgerService = mock(IPerkLedgerService.class);
        when(perkLedgerService.findReplayableChapterRedemptionIdForUpdate(any(), any(), any(), any())).thenReturn(null);

        CmsRegistrationServiceImpl registrationTarget = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(registrationTarget, "cmsRegistrationMapper", sql.getMapper(CmsRegistrationMapper.class));
        com.chengyinhub.business.mapper.CmsActivityMapper activities = mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setId(400L);
        activity.setTopicId(300L);
        activity.setDelFlag(0);
        // 按章核销先过探索票到期闸(PR #18 A包)；本类验章节权限与成长入队，给未到期的场次结束时间。
        activity.setEndDate(new java.util.Date(System.currentTimeMillis() + 86_400_000L));
        when(activities.selectCmsActivityByIdForUpdate(400L)).thenReturn(activity);
        ReflectionTestUtils.setField(registrationTarget, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(registrationTarget, "cmsRegistrationScanMapper", scanMapper);
        ReflectionTestUtils.setField(registrationTarget, "settlementEntryService", mock(ISettlementEntryService.class));
        ReflectionTestUtils.setField(registrationTarget, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(registrationTarget, "merchantReferralBountyService",
                mock(MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(registrationTarget, "verificationRiskService", mock(VerificationRiskService.class));
        ReflectionTestUtils.setField(registrationTarget, "perkLedgerService", perkLedgerService);
        ReflectionTestUtils.setField(registrationTarget, "growthService", growthService);
        registrationService = transactionalProxy(registrationTarget, ICmsRegistrationService.class, tx);
    }

    @BeforeEach
    void setup() {
        db.update("DELETE FROM store_verify_growth_delivery");
        db.update("DELETE FROM growth_mission");
        db.update("DELETE FROM activity_cancel_manual_refund_case");
        db.update("DELETE FROM cms_registration");
        db.update("INSERT INTO growth_mission(mission_code,mission_name,trigger_event,exp_reward,status)"
                + " VALUES('STORE_TX','到店核验','store_verify',7,'0')");
        db.update("INSERT INTO cms_registration(id,member_id,owner_type,owner_id,payment_status,"
                + "registration_status,verification_status,verifier_id,verification_time,registration_no,ticket_id,ticket_price,pay_expire_time,original_amount,payable_amount,total_amount) VALUES(501,9009,1,300,2,2,0,NULL,NULL,'GROWTH-501',1,0,NOW()+INTERVAL 1 DAY,0,0,0)");
    }

    @AfterAll
    void cleanup() {
        if (database == null || admin == null) return;
        if (createdDatabase) { admin.execute("DROP DATABASE " + database); createdDatabase = false; }
        assertEquals(0, admin.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, database));
    }

    @Test
    void verificationAndDeliveryQueueCommitTogether() {
        assertTrue(registrationService.verifyAndSettle(registration(), 77L, 77L, 1));

        assertEquals(1, db.queryForObject("SELECT verification_status FROM cms_registration WHERE id=501", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM store_verify_growth_delivery "
                + "WHERE registration_id=501 AND member_id=9009 AND processed_at IS NULL", Integer.class));
    }

    @Test
    void queueWriteFailureRollsBackVerification() {
        db.execute("ALTER TABLE store_verify_growth_delivery ADD CONSTRAINT ck_queue_write_failure CHECK (missions_json='[]')");
        try {
            assertThrows(RuntimeException.class,
                    () -> registrationService.verifyAndSettle(registration(), 77L, 77L, 1));
            assertEquals(0, db.queryForObject("SELECT verification_status FROM cms_registration WHERE id=501", Integer.class));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM store_verify_growth_delivery", Integer.class));
        } finally {
            db.execute("ALTER TABLE store_verify_growth_delivery DROP CHECK ck_queue_write_failure");
        }
    }

    @Test
    void chapterPermissionRejectionDoesNotEnqueue() {
        when(perkLedgerService.resolveRedeemableChapters(501L, 300L, 77L))
                .thenReturn(Collections.<Long>emptyList());

        // 原订单须是探索票(purchase_kind=3)才过按章核销的票种闸，才能走到本用例要验的章节权限拒绝。
        db.update("UPDATE cms_registration SET owner_type=2,owner_id=400,purchase_kind=3 WHERE id=501");
        CmsRegistration explore = registration();
        explore.setPurchaseKind(3);
        explore.setOwnerType(2L);
        explore.setOwnerId(400L);
        explore.setRegistrationStatus(2);
        assertEquals("该章节不可由您核销", assertThrows(IllegalStateException.class,
                () -> registrationService.redeemExploreChapterAndRecord(explore, 44L, 300L, 77L, 77L, 1)).getMessage());
        org.mockito.Mockito.verify(perkLedgerService).resolveRedeemableChapters(501L, 300L, 77L);

        assertEquals(0, db.queryForObject("SELECT verification_status FROM cms_registration WHERE id=501", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM store_verify_growth_delivery", Integer.class));
    }

    /**
     * PR10 复审:整单核销撤销在真库里把核销台账行标为已撤销(status=2)、作废未发放的到店成长待办;
     * 商家赏金人头口径与默认台账列表都不再算它;重新核销另落一行新台账并重新入队。
     */
    @Test
    void wholeOrderReversalMarksScanLedgerAndDropsPendingGrowth() {
        db.update("DELETE FROM cms_registration_scan");
        db.update("UPDATE cms_registration SET owner_type=2,owner_id=400 WHERE id=501");
        CmsRegistrationScanMapper scans = sql.getMapper(CmsRegistrationScanMapper.class);
        com.chengyinhub.business.mapper.MerchantReferralBountyMapper bounty =
                sql.getMapper(com.chengyinhub.business.mapper.MerchantReferralBountyMapper.class);
        com.chengyinhub.business.mapper.CmsActivityMapper activities = mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setId(400L);
        activity.setMemberId(77L);
        activity.setIsSettlement(0);
        activity.setDelFlag(0);
        when(activities.selectCmsActivityByIdForUpdate(400L)).thenReturn(activity);
        BusinessAuditEventMapper auditMapper = mock(BusinessAuditEventMapper.class);
        when(auditMapper.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        CmsRegistrationServiceImpl target = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", sql.getMapper(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(target, "cmsActivityMapper", activities);
        // Original whole-order fixture has no seat fulfillment; inject the required mapper explicitly.
        var seats = mock(com.chengyinhub.business.mapper.RegistrationSeatMapper.class);
        when(seats.selectByRegistrationForUpdate(501L)).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(target, "registrationSeatMapper", seats);
        ReflectionTestUtils.setField(target, "cmsRegistrationScanMapper", scans);
        ReflectionTestUtils.setField(target, "storeVerifyGrowthDeliveryMapper", sql.getMapper(StoreVerifyGrowthDeliveryMapper.class));
        ReflectionTestUtils.setField(target, "settlementEntryService", mock(ISettlementEntryService.class));
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(target, "merchantReferralBountyService", mock(MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(target, "verificationRiskService", mock(VerificationRiskService.class));
        ReflectionTestUtils.setField(target, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(target, "growthService", growthService);
        ICmsRegistrationService service = transactionalProxy(target, ICmsRegistrationService.class, tx);
        java.util.Date windowStart = new java.util.Date(System.currentTimeMillis() - 3_600_000L);
        java.util.Date windowEnd = new java.util.Date(System.currentTimeMillis() + 3_600_000L);
        CmsRegistration activityTicket = registration();
        activityTicket.setOwnerType(2L);
        activityTicket.setOwnerId(400L);
        activityTicket.setRegistrationStatus(2);

        assertTrue(service.verifyAndSettle(activityTicket, 77L, 77L, 1));
        assertEquals(1, bounty.countVerifiedHeadsByMerchantInWindow(77L, windowStart, windowEnd));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM store_verify_growth_delivery WHERE registration_id=501"
                + " AND processed_at IS NULL", Integer.class));

        assertTrue(service.reverseWholeOrderVerification(501L, "店员扫错票", 1L));
        assertEquals(0, db.queryForObject("SELECT verification_status FROM cms_registration WHERE id=501", Integer.class));
        assertEquals(2, db.queryForObject("SELECT status FROM cms_registration_scan WHERE registration_id=501"
                + " AND registration_type=1", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM store_verify_growth_delivery WHERE registration_id=501",
                Integer.class), "撤销后未发放的到店成长待办不能迟到补发");
        assertEquals(0, bounty.countVerifiedHeadsByMerchantInWindow(77L, windowStart, windowEnd),
                "已撤销的整单核销不能再算商家赏金人头");
        CmsRegistrationScan probe = new CmsRegistrationScan();
        probe.setRegistrationId(501L);
        assertTrue(scans.selectCmsRegistrationScanList(probe).isEmpty(), "默认台账列表不列已撤销行");
        probe.getParams().put("includeReversed", Boolean.TRUE);
        assertEquals(1, scans.selectCmsRegistrationScanList(probe).size(), "证据台显式要求时仍可读回已撤销行");
        assertEquals("该订单未核销或已撤销，无需重复撤销", assertThrows(IllegalStateException.class,
                () -> service.reverseWholeOrderVerification(501L, "重复点击", 1L)).getMessage());

        assertTrue(service.verifyAndSettle(activityTicket, 77L, 77L, 1));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM cms_registration_scan WHERE registration_id=501",
                Integer.class), "重新核销另落新台账行,保留撤销历史");
        assertEquals(1, bounty.countVerifiedHeadsByMerchantInWindow(77L, windowStart, windowEnd));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM store_verify_growth_delivery WHERE registration_id=501"
                + " AND processed_at IS NULL", Integer.class));
        db.update("DELETE FROM cms_registration_scan");
    }

    private CmsRegistration registration() {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(501L);
        registration.setMemberId(9009L);
        registration.setOwnerType(1L);
        registration.setOwnerId(300L);
        registration.setPaymentStatus(2);
        registration.setRegistrationStatus(2);
        return registration;
    }

    private static <T> T transactionalProxy(Object target, Class<T> contract,
                                            DataSourceTransactionManager tx) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return contract.cast(proxy.getProxy());
    }

    private static void executeMigration(DataSource source, String path) throws Exception {
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(path));
        }
    }
}
