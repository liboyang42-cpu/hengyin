package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/**
 * Actual MySQL/production audit, station, registration and scan XML plus the caller's Spring transaction.
 * The G recorder boundary is controlled explicitly: empty is legacy/no-V1, never a fabricated proof ID.
 * This class does not claim to verify G attribution SQL, pricing, provider payment or HTTP authorization.
 * G's own real-storage fixture owns immutable proof insert/replay/rollback acceptance.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationStationFulfillmentMySqlIT {
    private DriverManagerDataSource source;
    private JdbcTemplate admin, db;
    private String schema;
    private boolean created;
    private DataSourceTransactionManager manager;
    private CmsRegistrationMapper registrations;
    private CmsActivityMapper activities;
    private CmsRegistrationMerchantMapper stations;
    private CmsRegistrationScanMapper scans;
    private CmsTopicMapper topics;
    private BusinessAuditEventMapper originalAudits, audits;
    private ICoopSettlementClaimService claims;
    private IGrowthService growth;
    private CmsRegistrationServiceImpl target;
    private ICmsRegistrationService service;
    private final AtomicLong sequence = new AtomicLong(100);
    private long registrationId, stationId;
    private static final long TOPIC = 7, MERCHANT = 70, PLAYER = 11, ACTOR = 71;

    @BeforeAll void database() throws Exception {
        assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"));
        for (String key : new String[]{"HOST", "PORT", "DATABASE_BASE", "DATABASE_SUFFIX", "USER", "PASSWORD"})
            assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_" + key));
        assertTrue(List.of("127.0.0.1", "localhost").contains(System.getenv("CHENGYIN_TEST_MYSQL_HOST")));
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("station_fulfillment");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("SELECT VERSION()", String.class)));
        schema = "architecture_a_station_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + schema + "`"); created = true;
        source = new DriverManagerDataSource(config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + schema + "?"),
                config.user(), config.password());
        db = new JdbcTemplate(source); assertEquals(schema, db.queryForObject("SELECT DATABASE()", String.class));
        manager = new DataSourceTransactionManager(source);
        P0AcceptanceSchema.baseTables(db, "cms_topic", "cms_activity", "cms_registration",
                "cms_registration_merchant", "cms_registration_scan", "redemption");
        // The exact current topic projection additions used by RegistrationTopicFenceMySqlIT.
        db.execute("ALTER TABLE cms_topic ADD COLUMN beta_flag TINYINT NOT NULL DEFAULT 0,"
                + " ADD COLUMN circle_theme_code VARCHAR(24), ADD COLUMN source_template_topic_id BIGINT,"
                + " ADD COLUMN complete_rule_json JSON, ADD COLUMN route_mode VARCHAR(20) DEFAULT 'LINEAR',"
                + " ADD COLUMN route_graph_json JSON, ADD COLUMN config_version BIGINT DEFAULT 1,"
                + " ADD COLUMN published_route_graph_json JSON, ADD COLUMN published_config_version BIGINT DEFAULT 0,"
                + " ADD COLUMN game_module_config_json JSON, ADD COLUMN journey_rules_json JSON, ADD COLUMN journey_story_json JSON,"
                + " ADD COLUMN theme_runtime_config_json MEDIUMTEXT, ADD COLUMN published_theme_runtime_config_json MEDIUMTEXT,"
                + " ADD COLUMN team_mode TINYINT NOT NULL DEFAULT 0, ADD COLUMN team_max_members INT NOT NULL DEFAULT 4,"
                + " ADD COLUMN club_coop_open TINYINT NOT NULL DEFAULT 1, ADD COLUMN club_pinned TINYINT NOT NULL DEFAULT 0,"
                + " ADD COLUMN club_member_only TINYINT NOT NULL DEFAULT 0");
        db.execute("ALTER TABLE cms_registration ADD COLUMN first_successful_scan_at DATETIME(3)");
        db.execute("ALTER TABLE cms_registration_merchant ADD COLUMN audit_time DATETIME");
        P0AcceptanceSchema.migrations(source,
                "migration_event_cancel_manual_refund_case_20260826.sql",
                "migration_manual_refund_case_topic_20260918.sql",
                "migration_partial_chapter_aftercare_20261002.sql");
        P0AcceptanceSchema.createTableFromMigration(db, "migration_business_audit_event_20260823.sql");
        // Same original approval-governance ALTER as P0RedemptionChainMySqlIT.
        db.execute("ALTER TABLE business_audit_event ADD COLUMN actor_type VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'"
                + " AFTER owner_member_id, MODIFY COLUMN request_id VARCHAR(64) NOT NULL");
        SqlSessionTemplate sql = P0AcceptanceSchema.sql(source, "CmsTopicMapper", "CmsActivityMapper",
                "CmsRegistrationMapper", "CmsRegistrationMerchantMapper", "CmsRegistrationScanMapper", "BusinessAuditEventMapper");
        registrations = sql.getMapper(CmsRegistrationMapper.class);
        activities = sql.getMapper(CmsActivityMapper.class);
        stations = sql.getMapper(CmsRegistrationMerchantMapper.class);
        scans = sql.getMapper(CmsRegistrationScanMapper.class);
        topics = sql.getMapper(CmsTopicMapper.class);
        originalAudits = sql.getMapper(BusinessAuditEventMapper.class);
        db.update("INSERT INTO cms_topic(id,member_id) VALUES(?,?)", TOPIC, MERCHANT);
    }

    @BeforeEach void fixture() {
        registrationId = sequence.incrementAndGet(); stationId = registrationId + 10000;
        db.update("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,"
                + "original_amount,total_amount,payable_amount,payment_status,registration_status,pay_expire_time,"
                + "verification_status,order_num,create_time) VALUES(?,?,1,?,1,68,?,68,68,68,2,2,NOW()+INTERVAL 1 DAY,0,1,NOW())",
                registrationId, "A-STATION-" + registrationId, TOPIC, PLAYER);
        db.update("INSERT INTO cms_registration_merchant(id,member_id,topic_id,mode,audit_status,del_flag,validation_method)"
                + " VALUES(?,?,?,2,1,0,3)", stationId, MERCHANT, TOPIC);
        audits = mock(BusinessAuditEventMapper.class, delegatesTo(originalAudits));
        claims = mock(ICoopSettlementClaimService.class);
        when(claims.recordConfirmedStationFulfillment(anyLong())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            BusinessAuditEvent event = originalAudits.selectByTarget("CMS_REGISTRATION", registrationId).stream()
                    .filter(row -> call.getArgument(0, Long.class).equals(row.getId())).findFirst().orElseThrow();
            assertEquals("REGISTRATION", event.getDomainType()); assertEquals(Long.valueOf(registrationId), event.getDomainId());
            assertEquals("REGISTRATION_STATION_REDEEM", event.getAction());
            assertEquals(Long.valueOf(stationId), event.getRelationId()); assertEquals(Long.valueOf(MERCHANT), event.getOwnerMemberId());
            assertEquals(Long.valueOf(ACTOR), event.getActorMemberId());
            assertEquals(1, verified()); assertEquals(1, auditCount());
            return Optional.empty(); // Explicit no-V1 boundary; no economic receipt is synthesized.
        });
        growth = mock(IGrowthService.class);
        target = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(target, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(target, "cmsRegistrationMerchantMapper", stations);
        ReflectionTestUtils.setField(target, "cmsRegistrationScanMapper", scans);
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", audits);
        ReflectionTestUtils.setField(target, "coopSettlementClaimService", claims);
        ReflectionTestUtils.setField(target, "settlementEntryService", mock(ISettlementEntryService.class));
        ReflectionTestUtils.setField(target, "merchantReferralBountyService", mock(MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(target, "verificationRiskService", mock(VerificationRiskService.class));
        ReflectionTestUtils.setField(target, "growthService", growth);
        IPerkLedgerService perks = mock(IPerkLedgerService.class);
        when(perks.listRedemptions(anyLong())).thenReturn(List.of());
        ReflectionTestUtils.setField(target, "perkLedgerService", perks);
        ReflectionTestUtils.setField(target, "storeVerifyGrowthDeliveryMapper", mock(StoreVerifyGrowthDeliveryMapper.class));
        ProxyFactory proxy = new ProxyFactory(target); proxy.setInterfaces(ICmsRegistrationService.class);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (ICmsRegistrationService) proxy.getProxy();
    }

    @AfterAll void cleanup() {
        if (created) {
            admin.execute("DROP DATABASE `" + schema + "`");
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                    Integer.class, schema));
        }
    }

    @Test void legacyNoV1CommitsOriginalVerificationAndAuditWithoutInventingAProof() {
        assertTrue(redeem());
        assertEquals("MEMBER", db.queryForObject("SELECT actor_type FROM business_audit_event WHERE target_type='CMS_REGISTRATION' AND target_id=?", String.class, registrationId));
        assertEquals(1, verified()); assertEquals(1, scanCount()); assertEquals(1, auditCount());
        BusinessAuditEvent event = originalAudits.selectByTarget("CMS_REGISTRATION", registrationId).get(0);
        assertTrue(event.getId() > 0);
        verify(claims).recordConfirmedStationFulfillment(event.getId());
    }

    @Test void replayKeepsTheSameAuditAndDoesNotCallRecorderTwice() {
        assertTrue(redeem());
        Long originalId = originalAudits.selectByTarget("CMS_REGISTRATION", registrationId).get(0).getId();
        assertFalse(redeem());
        assertEquals(1, auditCount()); assertEquals(1, scanCount()); assertEquals(1, verified());
        assertEquals(originalId, originalAudits.selectByTarget("CMS_REGISTRATION", registrationId).get(0).getId());
        verify(claims, times(1)).recordConfirmedStationFulfillment(originalId);
    }

    @Test void recorderFailureRollsBackTheRealCasScanAndGeneratedAuditTogether() {
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, verified()); assertEquals(1, scanCount()); assertEquals(1, auditCount());
            assertEquals(call.getArgument(0, Long.class),
                    originalAudits.selectByTarget("CMS_REGISTRATION", registrationId).get(0).getId());
            throw new IllegalStateException("controlled immutable proof write failure");
        }).when(claims).recordConfirmedStationFulfillment(anyLong());

        assertEquals("controlled immutable proof write failure",
                assertThrows(IllegalStateException.class, this::redeem).getMessage());
        unchanged();
        verify(growth, never()).enqueueStoreVerification(anyLong());
    }

    @Test void auditWriterFailureRollsBackCasAndScanBeforeRecorderCanRun() {
        doAnswer(call -> {
            assertEquals(1, originalAudits.insertBusinessAuditEvent(call.getArgument(0)));
            throw new IllegalStateException("controlled failure after physical audit insert");
        }).when(audits).insertBusinessAuditEvent(any());

        assertEquals("controlled failure after physical audit insert",
                assertThrows(IllegalStateException.class, this::redeem).getMessage());
        unchanged(); verifyNoInteractions(claims);
    }

    @Test void missingGeneratedAuditIdRollsBackTheInsertedAuditAndCas() {
        doAnswer(call -> {
            BusinessAuditEvent event = call.getArgument(0);
            int inserted = originalAudits.insertBusinessAuditEvent(event);
            assertTrue(event.getId() > 0);
            event.setId(null); // Simulate broken generated-key delivery, not a fabricated event.
            return inserted;
        }).when(audits).insertBusinessAuditEvent(any());

        assertEquals("核销审计未返回有效原事件ID", assertThrows(IllegalStateException.class, this::redeem).getMessage());
        unchanged(); verifyNoInteractions(claims);
    }

    @Test void currentStationOwnershipWinsOverTheEarlierServerSelection() {
        CmsRegistrationMerchant selected = stations.selectCmsRegistrationMerchantById(stationId);
        db.update("UPDATE cms_registration_merchant SET member_id=? WHERE id=?", MERCHANT + 1, stationId);

        assertThrows(IllegalStateException.class, () -> service.verifyAndSettleForOwnStation(
                registration(), MERCHANT, MERCHANT, selected, ACTOR, "MERCHANT_CHECKIN"));
        unchanged(); verifyNoInteractions(claims);
    }

    @Test void refundWinningBeforeVerificationLeavesAuditScanAndRecorderUntouched() {
        CmsRegistration observed = registration();
        db.update("UPDATE cms_registration SET payment_status=4,registration_status=3 WHERE id=?", registrationId);
        assertThrows(IllegalStateException.class, () -> service.verifyAndSettleForStation(
                observed, MERCHANT, stationId, ACTOR, "MERCHANT_CHECKIN"));
        unchanged(); verifyNoInteractions(claims);
        assertEquals(4, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=?", Integer.class, registrationId));
    }


    @Test void legalStandaloneReversalAndReverificationKeepDistinctOriginalAuditGenerations() {
        long activityId = stationId + 20000;
        db.update("INSERT INTO cms_activity(id,member_id,is_settlement,del_flag) VALUES(?,?,0,0)", activityId, MERCHANT);
        db.update("UPDATE cms_registration SET owner_type=2,owner_id=? WHERE id=?", activityId, registrationId);
        assertTrue(service.verifyAndSettle(registration(), MERCHANT, MERCHANT, 3, ACTOR, "MERCHANT_CHECKIN"));
        BusinessAuditEvent first = originalAudits.selectByTarget("CMS_REGISTRATION", registrationId).stream()
                .filter(event -> "REGISTRATION_REDEEM".equals(event.getAction())).findFirst().orElseThrow();
        assertTrue(service.reverseWholeOrderVerification(registrationId, "原活动票误扫", 1L));
        assertEquals(0, verified());
        assertTrue(service.verifyAndSettle(registration(), MERCHANT, MERCHANT, 3, ACTOR, "MERCHANT_CHECKIN"));
        List<BusinessAuditEvent> generation = originalAudits.selectByTarget("CMS_REGISTRATION", registrationId).stream()
                .filter(event -> "REGISTRATION_REDEEM".equals(event.getAction())).toList();
        assertEquals(2, generation.size());
        BusinessAuditEvent second = generation.stream().filter(event -> !first.getId().equals(event.getId())).findFirst().orElseThrow();
        assertTrue(first.getId() > 0 && second.getId() > 0);
        assertNotEquals(first.getId(), second.getId());
        assertEquals("registration-redeem-" + registrationId, first.getRequestId());
        assertEquals("registration-redeem-" + registrationId + "-r1", second.getRequestId());
        assertEquals(1, verified()); assertEquals(3, auditCount());
        verifyNoInteractions(claims); // Existing activity fulfillment is not a new station V1 contract.
    }

    @Test void stationAttributionDoesNotOpenTheExistingTopicWholeOrderReversalGuard() {
        assertTrue(redeem());
        assertEquals("主题整单核销已计入执行商家结算，请走人工售后处理",
                assertThrows(IllegalStateException.class,
                        () -> service.reverseWholeOrderVerification(registrationId, "不得改变主题售后政策", 1L)).getMessage());
        assertEquals(1, verified()); assertEquals(1, auditCount()); assertEquals(1, scanCount());
        verify(claims, times(1)).recordConfirmedStationFulfillment(anyLong());
    }

    private CmsRegistration registration() { return registrations.selectCmsRegistrationById(registrationId); }
    private boolean redeem() { return service.verifyAndSettleForStation(registration(), MERCHANT, stationId, ACTOR, "MERCHANT_CHECKIN"); }
    private int verified() { return db.queryForObject("SELECT verification_status FROM cms_registration WHERE id=?", Integer.class, registrationId); }
    private int scanCount() { return db.queryForObject("SELECT COUNT(*) FROM cms_registration_scan WHERE registration_id=?", Integer.class, registrationId); }
    private int auditCount() { return db.queryForObject("SELECT COUNT(*) FROM business_audit_event WHERE target_type='CMS_REGISTRATION' AND target_id=?", Integer.class, registrationId); }
    private void unchanged() { assertEquals(0, verified()); assertEquals(0, scanCount()); assertEquals(0, auditCount()); }
}
