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
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private ICmsRegistrationService registrationService;
    private IPerkLedgerService perkLedgerService;

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

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(
                new ClassPathResource("mapper/business/CmsRegistrationMapper.xml"),
                new ClassPathResource("mapper/business/GrowthMissionMapper.xml"),
                new ClassPathResource("mapper/business/StoreVerifyGrowthDeliveryMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        DataSourceTransactionManager tx = new DataSourceTransactionManager(source);

        GrowthServiceImpl growthTarget = new GrowthServiceImpl();
        ReflectionTestUtils.setField(growthTarget, "growthMissionMapper", sql.getMapper(GrowthMissionMapper.class));
        ReflectionTestUtils.setField(growthTarget, "storeVerifyGrowthDeliveryMapper",
                sql.getMapper(StoreVerifyGrowthDeliveryMapper.class));
        IGrowthService growthService = transactionalProxy(growthTarget, IGrowthService.class, tx);

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
        admin.execute("DROP DATABASE " + database);
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

        db.update("UPDATE cms_registration SET owner_type=2,owner_id=400 WHERE id=501");
        CmsRegistration explore = registration();
        explore.setOwnerType(2L);
        explore.setOwnerId(400L);
        explore.setRegistrationStatus(2);
        assertEquals("该章节不可由您核销", assertThrows(IllegalStateException.class,
                () -> registrationService.redeemExploreChapterAndRecord(explore, 44L, 300L, 77L, 77L, 1)).getMessage());
        org.mockito.Mockito.verify(perkLedgerService).resolveRedeemableChapters(501L, 300L, 77L);

        assertEquals(0, db.queryForObject("SELECT verification_status FROM cms_registration WHERE id=501", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM store_verify_growth_delivery", Integer.class));
    }

    private CmsRegistration registration() {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(501L);
        registration.setMemberId(9009L);
        registration.setOwnerType(1L);
        registration.setOwnerId(300L);
        registration.setPaymentStatus(2);
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
