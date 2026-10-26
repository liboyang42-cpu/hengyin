package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL/Spring transactions and current repository DDL; WeChat and non-cash after-effects are isolated. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefundRetryAndRemainderMySqlIT {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("refund_retry_remainder_it");
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private boolean schemaCreated;
    private IRefundService service;
    private RefundServiceImpl refundTarget;
    private DataSourceTransactionManager transactions;
    private IRefundPayoutService payouts;
    private RefundApplicationMapper refunds;
    private IWechatPayService wechat;
    private SettlementDetailMapper settlements;
    private CmsRegistrationMapper realRegistrations;
    private ExploreBundleMapper realBundles;
    private ExploreBundleRefundMapper realHolds;
    private IFreeExploreBundleRefundService realF3;

    @BeforeAll
    void setup() throws Exception {
        admin = new JdbcTemplate(source(mysql.adminUrl()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("create database " + mysql.databaseName() + " character set utf8mb4");
        schemaCreated = true;
        DriverManagerDataSource dataSource = source(mysql.databaseUrl());
        db = new JdbcTemplate(dataSource);
        db.execute("create table refund_fulfillment_sync_probe (refund_id bigint primary key, queue_at datetime(6) null) engine=InnoDB");
        Path root = Files.exists(Paths.get("sql/base_schema_20260818.sql")) ? Paths.get("") : Paths.get("..");
        String snapshot = new String(Files.readAllBytes(root.resolve("sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
        for (String table : new String[] {"cms_registration", "cms_topic", "cms_activity", "refund_application", "wx_payment_callback_inbox"}) {
            String start = "CREATE TABLE `" + table + "` (";
            int from = snapshot.indexOf(start);
            int closing = snapshot.indexOf("\n) ENGINE", from);
            int to = snapshot.indexOf(';', closing);
            assertTrue(from >= 0 && closing > from && to > closing, "missing source DDL: " + table);
            db.execute(snapshot.substring(from, to + 1));
        }
        new ResourceDatabasePopulator(
                new FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_20260930_registration_first_successful_scan.sql").toFile()),
                new FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_merchant_aftercare_timeout_20260917.sql").toFile())).execute(dataSource);

        paid(999L, null, "1.00");
        db.update("update cms_registration set payment_status=4,registration_status=3 where id=999");
        db.update("insert into refund_application(refund_no,source_type,source_id,paid_amount,refund_amount,status,payout_status,idempotency_key) values('RF999','registration',999,1.00,1.00,1,1,'registration:999')");
        var legacyCashBeforeMigration = db.queryForMap("select refund_no,source_id,paid_amount,refund_amount,status,payout_status,update_time from refund_application where source_id=999");
        new ResourceDatabasePopulator(
                new FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_w8_refund_fulfillment_recovery_20261026.sql").toFile()),
                new FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_w8_refund_fulfillment_recovery_20261026.sql").toFile())).execute(dataSource);
        assertEquals(legacyCashBeforeMigration, db.queryForMap("select refund_no,source_id,paid_amount,refund_amount,status,payout_status,update_time from refund_application where source_id=999"));
        assertEquals(1, db.queryForObject("select count(*) from refund_application where source_id=999 and fulfillment_sync_queue_at is not null", Integer.class));
        new ResourceDatabasePopulator(
                new FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_doc2_f3_bundle_ledger_20261022.sql").toFile()),
                new FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_doc2_f3_refund_hold_20261026.sql").toFile())).execute(dataSource);

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsRegistrationMapper.xml"),
                new ClassPathResource("mapper/business/RefundApplicationMapper.xml"),
                new ClassPathResource("mapper/business/WxPaymentCallbackInboxMapper.xml"),
                new ClassPathResource("mapper/business/ExploreBundleMapper.xml"),
                new ClassPathResource("mapper/business/ExploreBundleRefundMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        refunds = sql.getMapper(RefundApplicationMapper.class);
        settlements = mock(SettlementDetailMapper.class);
        RefundServiceImpl target = com.chengyinhub.business.service.support.RefundF3TestSupport.nonBundle(new RefundServiceImpl());
        refundTarget = target;
        ReflectionTestUtils.setField(target, "refundApplicationMapper", refunds);
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", sql.getMapper(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(target, "settlementDetailMapper", settlements);
        ReflectionTestUtils.setField(target, "cmsActivityMapper", mock(CmsActivityMapper.class));
        ReflectionTestUtils.setField(target, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(target, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(target, "firstReviewPointsClawback", mock(FirstReviewPointsClawbackService.class));
        ReflectionTestUtils.setField(target, "merchantReferralBountyService", mock(MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(target, "notifyService", mock(INotifyService.class));
        ReflectionTestUtils.setField(target, "alarmService", mock(AlarmService.class));
        WxPaymentCallbackInboxServiceImpl inbox = new WxPaymentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(inbox, "mapper", sql.getMapper(WxPaymentCallbackInboxMapper.class));
        ReflectionTestUtils.setField(target, "wxPaymentCallbackInboxService", inbox);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(IRefundService.class);
        transactions = new DataSourceTransactionManager(dataSource);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (IRefundService) proxy.getProxy();
        realRegistrations = sql.getMapper(CmsRegistrationMapper.class);
        realBundles = sql.getMapper(ExploreBundleMapper.class);
        realHolds = sql.getMapper(ExploreBundleRefundMapper.class);
        ProxyFactory f3Proxy = new ProxyFactory(new FreeExploreBundleRefundServiceImpl(realRegistrations, refunds, realBundles, realHolds));
        f3Proxy.setInterfaces(IFreeExploreBundleRefundService.class);
        f3Proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        realF3 = (IFreeExploreBundleRefundService) f3Proxy.getProxy();
        wechat = mock(IWechatPayService.class);
        RefundPayoutServiceImpl payout = new RefundPayoutServiceImpl();
        ReflectionTestUtils.setField(payout, "refundApplicationMapper", refunds);
        ReflectionTestUtils.setField(payout, "cmsRegistrationMapper", sql.getMapper(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(payout, "refundService", service);
        ReflectionTestUtils.setField(payout, "wechatPayService", wechat);
        payouts = payout;
    }

    @BeforeEach
    void clear() {
        com.chengyinhub.business.service.support.RefundF3TestSupport.nonBundle(refundTarget);
        for (String table : new String[] {"explore_bundle_refund_hold", "explore_bundle_exit_case", "explore_bundle_waiver",
                "explore_bundle_reservation", "explore_bundle_unit", "explore_bundle_grant", "explore_bundle_order"}) {
            db.update("delete from " + table);
        }
        db.update("delete from refund_fulfillment_sync_probe");
        db.update("delete from wx_payment_callback_inbox");
        db.update("delete from refund_application");
        db.update("delete from cms_registration");
        reset(wechat, settlements);
        when(settlements.selectSettledDetails(anyString(), anyLong())).thenReturn(Collections.emptyList());
        when(wechat.submitRefund(anyString(), anyString(), any(), any(), any())).thenReturn(RefundRequestResult.ACCEPTED);
    }

    @AfterAll
    void cleanup() {
        if (admin != null && schemaCreated) {
            admin.execute("drop database if exists " + mysql.databaseName());
            assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?", Integer.class, mysql.databaseName()));
        }
    }

    @Test
    void fabricatedEmptyPreparationCannotBypassForfeitedUnitGuardOrPersistFinancialIntent() {
        actualF3Parent("FORFEITED");
        var forged = new com.chengyinhub.business.service.support.FreeExploreRefundContext(
                99L, new BigDecimal("68.00"), java.util.List.of(), java.util.Set.of());
        assertThrows(RuntimeException.class, () -> service.savePreparedRegistrationRefund(request("68.00"), forged, null));
        assertEquals(0, db.queryForObject("select count(*) from refund_application", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from explore_bundle_refund_hold", Integer.class));
        assertEquals("FORFEITED", unitState(100L));
        assertEquals(2, db.queryForObject("select payment_status from cms_registration where id=99", Integer.class));
    }

    @Test
    void actualParentUnitsIncludeForfeitureWithoutRedemptionAndBlockCashRefund() {
        actualF3Parent("FORFEITED");
        assertThrows(RuntimeException.class, () -> service.saveBackOfficeRefund(request("68.00")));
        assertEquals(0, db.queryForObject("select count(*) from refund_application", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from explore_bundle_refund_hold", Integer.class));
        assertEquals("AVAILABLE", unitState(99L));
        assertEquals("FORFEITED", unitState(100L));
        assertEquals(2, db.queryForObject("select count(*) from cms_registration where payment_status=2", Integer.class));
        verify(wechat, never()).submitRefund(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void actualFundsAndF3FreezeSurviveUnknownThenCompleteOriginalSuccessExactlyOnce() {
        actualF3Parent("AVAILABLE");
        bookedUnit(99L, 9L);
        paid(101L, null, "10.00");
        db.update("update cms_registration set member_id=11 where id=101");
        bundleUnit(101L, 11L, "10.00", "AVAILABLE");
        RefundApplication app = save("68.00");
        assertEquals(app.getId(), realHolds.selectByRegistration(99L).getRefundId());
        assertEquals(app.getId(), realHolds.selectByRegistration(100L).getRefundId());
        assertEquals("REFUND_PENDING", unitState(99L)); assertEquals("REFUND_PENDING", unitState(100L));
        assertEquals("AVAILABLE", unitState(101L));
        assertEquals(1, service.reviewRefund(review(app.getId(), 1), 7L));
        assertEquals(0, service.reviewRefund(review(app.getId(), 1), 7L));
        assertEquals("CANCELLED", db.queryForObject("select status from explore_bundle_reservation where registration_id=99", String.class));
        assertEquals(1, db.queryForObject("select version from explore_bundle_reservation where registration_id=99", Integer.class));
        when(wechat.submitRefund(anyString(), anyString(), any(), any(), any())).thenReturn(RefundRequestResult.UNKNOWN);
        payouts.payoutAfterReview(app.getId());
        inFundsTransaction(() -> realF3.syncRefundOutcome(app.getId()));
        assertEquals("APPROVED", realHolds.selectByRegistration(99L).getState());
        assertEquals("REFUND_PENDING", unitState(99L));
        assertEquals(RefundApplication.PAYOUT_DISPATCH_PENDING, refunds.selectRefundApplicationById(app.getId()).getPayoutStatus());
        assertTrue(service.handleRefundNotify(facts(app)));
        inFundsTransaction(() -> realF3.syncRefundOutcome(app.getId()));
        assertEquals("SUCCEEDED", realHolds.selectByRegistration(99L).getState());
        assertEquals("REFUNDED", unitState(99L)); assertEquals("REFUNDED", unitState(100L));
        Integer version = realHolds.selectByRegistration(99L).getVersion();
        assertTrue(service.handleRefundNotify(facts(app)));
        inFundsTransaction(() -> realF3.syncRefundOutcome(app.getId()));
        assertEquals(version, realHolds.selectByRegistration(99L).getVersion());
        assertEquals("AVAILABLE", unitState(101L));
        assertEquals(1, db.queryForObject("select count(*) from refund_application", Integer.class));
        verify(wechat, times(1)).submitRefund(eq("REG99"), eq(app.getRefundNo()), any(), any(), any());
    }

    @Test
    void oldPendingIntentCannotBeApprovedAfterActualUnitForfeiture() {
        actualF3Parent("FORFEITED");
        RefundApplication legacy = request("68.00");
        legacy.setMemberId(9L); legacy.setStatus(0); legacy.setDelFlag(0); legacy.setPaidAmount(new BigDecimal("68.00"));
        legacy.setPayoutStatus(5); legacy.setRefundNo("RF99"); legacy.setIdempotencyKey("registration:99");
        assertEquals(1, refunds.insertRefundApplication(legacy));
        assertThrows(RuntimeException.class, () -> service.reviewRefund(review(legacy.getId(), 1), 7L));
        assertEquals(0, refunds.selectRefundApplicationById(legacy.getId()).getStatus());
        assertEquals(2, db.queryForObject("select payment_status from cms_registration where id=99", Integer.class));
        assertEquals("FORFEITED", unitState(100L));
        verify(wechat, never()).submitRefund(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void actualPendingReplacementTransfersFreezeAndLateOldSuccessRequiresReconciliation() {
        actualF3Parent("AVAILABLE");
        RefundApplication old = save("68.00");
        RefundApplication replacement = new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
            var context = service.prepareRegistrationFullRefund(99L, new BigDecimal("68.00"));
            assertEquals(1, service.supersedePendingRegistrationRefunds(99L, "full cancellation"));
            RefundApplication next = request("68.00");
            assertEquals(1, service.savePreparedRegistrationRefund(next, context, old.getId()));
            assertEquals(1, service.reviewRefund(review(next.getId(), 1), 7L));
            return next;
        });
        assertNotNull(replacement);
        assertEquals(replacement.getId(), realHolds.selectByRegistration(99L).getRefundId());
        assertEquals("REFUND_PENDING", unitState(99L));
        assertEquals(2, refunds.selectRefundApplicationById(old.getId()).getStatus());
        assertTrue(service.handleRefundNotify(facts(old)));
        inFundsTransaction(() -> realF3.syncRefundOutcome(old.getId()));
        assertEquals("RECONCILIATION_REQUIRED", realHolds.selectByRegistration(99L).getState());
        assertEquals(replacement.getId(), realHolds.selectByRegistration(99L).getRefundId());
        assertEquals("REFUND_PENDING", unitState(99L));
        assertTrue(service.handleRefundNotify(facts(replacement)));
        inFundsTransaction(() -> realF3.syncRefundOutcome(replacement.getId()));
        assertEquals("RECONCILIATION_REQUIRED", realHolds.selectByRegistration(99L).getState());
        assertEquals(2, db.queryForObject("select count(*) from refund_application", Integer.class));
    }

    @Test
    void actualZeroCashCompletesWithoutFinancialIntentAndCashChildCannotImpersonateZero() {
        paid(99L, null, "0.00"); bundleUnit(99L, 9L, "0.00", "AVAILABLE"); useActualF3();
        inFundsTransaction(() -> {
            var context = service.prepareRegistrationFullRefund(99L, BigDecimal.ZERO);
            assertEquals(1, realRegistrations.claimRegistrationRefunded(99L, "cancel", new java.util.Date()));
            service.completeZeroCashRegistrationRefund(context);
        });
        assertEquals("ZERO_CASH_COMPLETED", realHolds.selectByRegistration(99L).getState());
        assertNull(realHolds.selectByRegistration(99L).getRefundId());
        assertEquals(0, db.queryForObject("select count(*) from refund_application", Integer.class));
        paid(200L, "CASH-GROUP", "68.00"); paid(201L, "CASH-GROUP", "0.00");
        bundleUnit(200L, 9L, "68.00", "AVAILABLE"); bundleUnit(201L, 9L, "0.00", "AVAILABLE");
        assertThrows(RuntimeException.class, () -> inFundsTransaction(() -> service.prepareRegistrationFullRefund(201L, BigDecimal.ZERO)));
        assertEquals("AVAILABLE", unitState(200L)); assertEquals("AVAILABLE", unitState(201L));
    }

    @Test
    void realRootAndHistoryLocksSerializeRefundAgainstUnitForfeiture() throws Exception {
        assertRefundWinsUnitRace("FORFEITED", null);
    }

    @Test
    void realRootAndHistoryLocksSerializeRefundAgainstRedemptionUnitCas() throws Exception {
        assertRefundWinsUnitRace("REDEEMED", 445L);
    }

    private void assertRefundWinsUnitRace(String attemptedState, Long redemptionId) throws Exception {
        actualF3Parent("AVAILABLE");
        CountDownLatch prepared = new CountDownLatch(1), release = new CountDownLatch(1), penaltyEntered = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> refund = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
                var context = service.prepareRegistrationFullRefund(99L, new BigDecimal("68.00"));
                prepared.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                RefundApplication app = request("68.00");
                service.savePreparedRegistrationRefund(app, context, null);
                service.reviewRefund(review(app.getId(), 1), 7L);
                return app.getId();
            }));
            assertTrue(prepared.await(10, TimeUnit.SECONDS));
            Future<Boolean> penalty = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
                penaltyEntered.countDown();
                realRegistrations.selectByParentOrderNoForUpdate("F3-PARENT", 20L);
                var unit = realBundles.selectAllUnitsForUpdate(100L, 10L).get(0);
                if (!"AVAILABLE".equals(unit.getStatus())) return false;
                return realBundles.casUnitStatus(unit.getId(), unit.getVersion(), "AVAILABLE", attemptedState, redemptionId, null, "RACE_ATTEMPT") == 1;
            }));
            assertTrue(penaltyEntered.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> penalty.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            assertNotNull(refund.get(10, TimeUnit.SECONDS));
            assertFalse(penalty.get(10, TimeUnit.SECONDS));
            assertEquals("REFUND_PENDING", unitState(100L));
            assertEquals(1, db.queryForObject("select count(*) from refund_application", Integer.class));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private void actualF3Parent(String childState) {
        paid(99L, "F3-PARENT", "68.00"); paid(100L, "F3-PARENT", "0.00");
        db.update("update cms_registration set member_id=10 where id=100");
        bundleUnit(99L, 9L, "68.00", "AVAILABLE"); bundleUnit(100L, 10L, "0.00", childState);
        useActualF3();
    }
    private void useActualF3() { ReflectionTestUtils.setField(refundTarget, "freeExploreBundleRefundService", realF3); }
    private void inFundsTransaction(Runnable work) {
        new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> { work.run(); return null; });
    }
    private String unitState(Long registrationId) {
        return db.queryForObject("select status from explore_bundle_unit where registration_id=?", String.class, registrationId);
    }
    private void bookedUnit(long registrationId, long holder) {
        Long unitId = db.queryForObject("select id from explore_bundle_unit where registration_id=?", Long.class, registrationId);
        long booking = 2000 + registrationId;
        db.update("insert into explore_bundle_reservation(id,unit_id,registration_id,holder_member_id,chapter_id,offer_id,merchant_id,start_at,status,idempotency_key,request_fingerprint,version,create_time) values(?,?,?,?,701,801,901,date_add(now(),interval 2 day),'BOOKED',?,?,0,now())",
                booking, unitId, registrationId, holder, "booking:" + registrationId, "a".repeat(64));
        db.update("update explore_bundle_unit set status='RESERVED', reservation_id=? where id=?", booking, unitId);
    }

    private void bundleUnit(long registrationId, long holder, String value, String state) {
        String hash = "a".repeat(64);
        db.update("insert into explore_bundle_order(id,registration_id,holder_member_id,topic_id,product_version,quota_version,policy_version,contract_version,settlement_rule_version,snapshot_json,paid_amount,valid_from,valid_to,idempotency_key,request_fingerprint,create_time) values(?,?,?,70,?,?,'p1',?,'s1','{}',?,now(),date_add(now(),interval 1 day),?,?,now())",
                registrationId, registrationId, holder, hash, hash, hash, value, "bundle:" + registrationId, hash);
        db.update("insert into explore_bundle_grant(id,bundle_order_id,registration_id,holder_member_id,topic_id,chapter_id,grant_no,grant_type,units_total,valid_from,valid_to,create_time) values(?,?,?,?,70,701,1,'PURCHASE',1,now(),date_add(now(),interval 1 day),now())",
                registrationId, registrationId, registrationId, holder);
        db.update("insert into explore_bundle_unit(grant_id,registration_id,holder_member_id,chapter_id,unit_seq,unit_code,unit_value,status,version,create_time) values(?,?,?,701,1,?,?,?,0,now())",
                registrationId, registrationId, holder, "U" + registrationId, value, state);
    }

    @Test
    void committedChannelSuccessSurvivesSeparateFulfillmentFailureAndRetry() {
        paid(99L, null, "68.00");
        RefundApplication request = save("20.00");
        assertEquals(1, service.reviewRefund(review(request.getId(), 1), 7L));
        assertTrue(service.handleRefundNotify(facts(request)));
        assertEquals(RefundApplication.PAYOUT_SUCCESS, refunds.selectRefundApplicationById(request.getId()).getPayoutStatus());
        IFreeExploreBundleRefundService f3 = mock(IFreeExploreBundleRefundService.class);
        doAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            db.update("insert into refund_fulfillment_sync_probe(refund_id) values(?)", request.getId());
            throw new IllegalStateException("isolated F3 write failed");
        }).when(f3).syncRefundOutcome(request.getId());
        RegistrationRefundFulfillmentSyncService syncTarget = new RegistrationRefundFulfillmentSyncService();
        ReflectionTestUtils.setField(syncTarget, "freeExploreBundleRefundService", f3);
        ProxyFactory proxy = new ProxyFactory(syncTarget);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        RegistrationRefundFulfillmentSyncService sync = (RegistrationRefundFulfillmentSyncService) proxy.getProxy();
        assertThrows(IllegalStateException.class, () -> sync.sync(request.getId()));
        assertEquals(0, db.queryForObject("select count(*) from refund_fulfillment_sync_probe", Integer.class));
        assertEquals(RefundApplication.PAYOUT_SUCCESS, refunds.selectRefundApplicationById(request.getId()).getPayoutStatus());
        assertEquals(1, refunds.selectRefundApplicationById(request.getId()).getStatus());
        assertTrue(refunds.selectRegistrationRefundRecoveryBatch(request.getId(), 200, Collections.emptyList()).contains(request.getId()));
        doAnswer(call -> {
            db.update("insert into refund_fulfillment_sync_probe(refund_id) values(?)", request.getId());
            return Collections.emptyList();
        }).when(f3).syncRefundOutcome(request.getId());
        // A failing caller transaction is suspended; successful F3 recovery commits separately.
        assertThrows(IllegalStateException.class,
                () -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
                    sync.sync(request.getId());
                    throw new IllegalStateException("caller failed after independent recovery");
                }));
        assertEquals(1, db.queryForObject("select count(*) from refund_fulfillment_sync_probe", Integer.class));
        assertEquals(RefundApplication.PAYOUT_SUCCESS, refunds.selectRefundApplicationById(request.getId()).getPayoutStatus());
        verify(f3, times(2)).syncRefundOutcome(request.getId());
        verify(wechat, never()).submitRefund(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void realDurableFairBatchReaches1201AfterRestartAndRetriesOneFailureWithoutChangingCashClock() {
        java.util.List<Object[]> registrations = new java.util.ArrayList<>(), seed = new java.util.ArrayList<>();
        for (int i = 1; i <= 1201; i++) {
            long source = 10000L + i;
            registrations.add(new Object[] {source, "REGFAIR" + source});
            seed.add(new Object[] {"RF" + source, source, "registration:" + source});
        }
        db.batchUpdate("insert into cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,original_amount,total_amount,payable_amount,wechat_payment_amount,payment_status,registration_status,verification_status) values(?,?,2,20,1,1.00,9,1.00,1.00,1.00,1.00,4,3,0)", registrations);
        db.batchUpdate("insert into refund_application(refund_no,source_type,source_id,paid_amount,refund_amount,status,del_flag,payout_status,idempotency_key) values(?,'registration',?,1.00,1.00,1,0,1,?)", seed);
        java.util.List<Long> ids = db.queryForList("select id from refund_application order by id", Long.class);
        java.util.List<java.util.Map<String, Object>> cashBefore = db.queryForList(
                "select id,status,payout_status,refund_no,refund_amount,update_time from refund_application order by id");
        db.update("insert into refund_fulfillment_sync_probe(refund_id,queue_at) select id,fulfillment_sync_queue_at from refund_application");
        Long failOnce = ids.get(6);
        java.util.Map<Long, Integer> attempts = new java.util.HashMap<>();
        RegistrationRefundFulfillmentSyncService sync = mock(RegistrationRefundFulfillmentSyncService.class);
        doAnswer(call -> {
            Long id = call.getArgument(0);
            int count = attempts.merge(id, 1, Integer::sum);
            if (failOnce.equals(id) && count == 1) throw new IllegalStateException("one F3 failure");
            return null;
        }).when(sync).sync(anyLong());
        recoveryWithRealMapper(sync).reconcile();
        assertEquals(1000, attempts.size());
        assertFalse(attempts.containsKey(ids.get(1000)));
        assertEquals(1000, db.queryForObject("select count(*) from refund_application a join refund_fulfillment_sync_probe p on p.refund_id=a.id where a.fulfillment_sync_queue_at <> p.queue_at", Integer.class));
        // Reconstruct the worker: fairness survives only in the real MySQL marker/index, not in this object.
        recoveryWithRealMapper(sync).reconcile();
        assertTrue(attempts.containsKey(ids.get(1000)));
        assertTrue(attempts.containsKey(ids.get(1200)));
        assertEquals(Integer.valueOf(2), attempts.get(failOnce));
        assertEquals(1201, attempts.size());
        assertEquals(1201, db.queryForObject("select count(*) from refund_application a join refund_fulfillment_sync_probe p on p.refund_id=a.id where a.fulfillment_sync_queue_at <> p.queue_at", Integer.class));
        assertEquals(cashBefore, db.queryForList(
                "select id,status,payout_status,refund_no,refund_amount,update_time from refund_application order by id"));
        verify(wechat, never()).submitRefund(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void realRecoveryQueryKeepsSuccessManualRejectedAndUnknownIdsAcrossRestart() {
        paid(99L, null, "68.00");
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (int payout : new int[] {0, 1, 2, 3, 4, 5, 6}) {
            RefundApplication fact = request("1.00");
            fact.setStatus(1); fact.setDelFlag(0); fact.setPayoutStatus(payout);
            fact.setRefundNo("RF99-scan-" + payout); fact.setIdempotencyKey("recovery-scan:" + payout);
            assertEquals(1, refunds.insertRefundApplication(fact));
            ids.add(fact.getId());
        }
        RefundApplication rejected = request("1.00");
        rejected.setStatus(2); rejected.setDelFlag(0); rejected.setPayoutStatus(5);
        rejected.setRefundNo("RF99-rejected"); rejected.setIdempotencyKey("recovery-scan:rejected");
        assertEquals(1, refunds.insertRefundApplication(rejected)); ids.add(rejected.getId());
        for (String excluded : new String[] {"pending", "deleted", "other-source"}) {
            RefundApplication fact = request("1.00");
            fact.setStatus("pending".equals(excluded) ? 0 : 1);
            fact.setDelFlag("deleted".equals(excluded) ? 1 : 0);
            if ("other-source".equals(excluded)) fact.setSourceType("order");
            fact.setPayoutStatus(1); fact.setRefundNo("RF99-excluded-" + excluded);
            fact.setIdempotencyKey("recovery-excluded:" + excluded);
            assertEquals(1, refunds.insertRefundApplication(fact));
        }
        java.util.List<java.util.Map<String, Object>> before = db.queryForList(
                "select id, status, payout_status, refund_no, refund_amount, update_time from refund_application order by id");
        Long upper = refunds.selectRegistrationRefundRecoveryUpperBound();
        assertEquals(ids.get(ids.size() - 1), upper);
        assertEquals(ids, refunds.selectRegistrationRefundRecoveryBatch(upper, 200, Collections.emptyList()));
        assertEquals(ids.subList(0, 2), refunds.selectRegistrationRefundRecoveryBatch(upper, 2, Collections.emptyList()));
        assertEquals(ids.subList(2, ids.size()), refunds.selectRegistrationRefundRecoveryBatch(upper, 200, ids.subList(0, 2)));
        RegistrationRefundFulfillmentSyncService failing = mock(RegistrationRefundFulfillmentSyncService.class);
        doThrow(new IllegalStateException("F3 unavailable")).when(failing).sync(anyLong());
        RegistrationRefundFulfillmentRecovery recovery = recoveryWithRealMapper(failing);
        assertDoesNotThrow(recovery::reconcile);
        RegistrationRefundFulfillmentSyncService restartedSync = mock(RegistrationRefundFulfillmentSyncService.class);
        recoveryWithRealMapper(restartedSync).reconcile();
        for (Long id : ids) verify(restartedSync).sync(id);
        verifyNoMoreInteractions(restartedSync);
        assertEquals(before, db.queryForList(
                "select id, status, payout_status, refund_no, refund_amount, update_time from refund_application order by id"));
        verify(wechat, never()).submitRefund(anyString(), anyString(), any(), any(), any());
    }

    private RegistrationRefundFulfillmentRecovery recoveryWithRealMapper(RegistrationRefundFulfillmentSyncService sync) {
        RegistrationRefundFulfillmentRecovery recovery = new RegistrationRefundFulfillmentRecovery();
        ReflectionTestUtils.setField(recovery, "refundApplicationMapper", refunds);
        ReflectionTestUtils.setField(recovery, "fulfillmentSyncService", sync);
        return recovery;
    }

    @Test
    void zeroInsertReceiptRollsBackRealRefundRowAndOuterTransaction() {
        assertLostInsertReceiptRollsBack(false);
    }

    @Test
    void missingGeneratedKeyRollsBackRealRefundRowAndOuterTransaction() {
        assertLostInsertReceiptRollsBack(true);
    }

    /** Delegate the INSERT to real MyBatis/MySQL, then lose only its caller-visible receipt. */
    private void assertLostInsertReceiptRollsBack(boolean loseKey) {
        paid(99L, null, "68.00");
        RefundApplicationMapper lostReceipt = (RefundApplicationMapper) java.lang.reflect.Proxy.newProxyInstance(
                RefundApplicationMapper.class.getClassLoader(), new Class<?>[] {RefundApplicationMapper.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(refunds, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if ("insertRefundApplication".equals(method.getName())) {
                        assertEquals(1, result);
                        RefundApplication inserted = (RefundApplication) args[0];
                        assertNotNull(inserted.getId(), "real useGeneratedKeys must execute before fault injection");
                        assertTrue(inserted.getId() > 0);
                        assertEquals(1, db.queryForObject("select count(*) from refund_application", Integer.class));
                        if (loseKey) inserted.setId(null);
                        else return 0;
                    }
                    return result;
                });
        ReflectionTestUtils.setField(refundTarget, "refundApplicationMapper", lostReceipt);
        java.util.concurrent.atomic.AtomicBoolean downstreamReached = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
                        // A preceding cancellation write must roll back together with the actual refund INSERT.
                        assertEquals(1, db.update("update cms_registration set registration_status=3 where id=99"));
                        RefundApplication request = request("20.00");
                        service.saveBackOfficeRefund(request);
                        downstreamReached.set(true);
                        service.reviewRefund(review(request.getId(), 1), 7L);
                        return null;
                    }));
            assertTrue(failure.getMessage().contains("写入回执异常"));
        } finally {
            ReflectionTestUtils.setField(refundTarget, "refundApplicationMapper", refunds);
        }
        assertFalse(downstreamReached.get(), "approval and dispatch must not follow an invalid receipt");
        assertEquals(0, db.queryForObject("select count(*) from refund_application", Integer.class));
        assertEquals(2, db.queryForObject("select registration_status from cms_registration where id=99", Integer.class));
        assertEquals(2, db.queryForObject("select payment_status from cms_registration where id=99", Integer.class));
        verify(settlements, never()).voidPendingBySource(anyString(), anyLong());
        verify(wechat, never()).submitRefund(anyString(), anyString(), any(), any(), any());
        // Retrying only after the failed transaction rolled back creates one valid original intent.
        RefundApplication retry = save("20.00");
        assertTrue(retry.getId() > 0);
        assertEquals("RF99", retry.getRefundNo());
        assertEquals("registration:99", retry.getIdempotencyKey());
        assertEquals(1, db.queryForObject("select count(*) from refund_application", Integer.class));
        assertEquals(0, refunds.selectRefundApplicationById(retry.getId()).getStatus());
        assertEquals(RefundApplication.PAYOUT_DISPATCH_PENDING,
                refunds.selectRefundApplicationById(retry.getId()).getPayoutStatus());
    }

    @Test
    void lockedHistoryKeepsApprovedRefundFactAfterProviderFailureWithoutChangingFunds() {
        paid(99L, null, "68.00");
        RefundApplication request = save("20.00");
        assertTrue(lockedFacts(99L).hasPendingOrApproved());
        assertEquals(0, lockedFacts(99L).refunds().get(0).reviewStatus());
        assertEquals(1, service.reviewRefund(review(request.getId(), 1), 7L));
        when(wechat.submitRefund(anyString(), anyString(), any(), any(), any()))
                .thenReturn(RefundRequestResult.DEFINITIVE_FAILED);
        payouts.payoutAfterReview(request.getId());
        for (int repeat = 0; repeat < 2; repeat++) {
            var facts = lockedFacts(99L);
            assertTrue(facts.hasPendingOrApproved());
            assertFalse(facts.unknownHistory());
            assertEquals(1, facts.refunds().get(0).reviewStatus());
            assertEquals(RefundApplication.PAYOUT_PENDING_MANUAL, facts.refunds().get(0).payoutStatus());
            assertEquals(new BigDecimal("20.00"), facts.refunds().get(0).refundAmount());
        }
        assertEquals(1, db.queryForObject("select count(*) from refund_application", Integer.class));
        assertEquals(4, db.queryForObject("select payment_status from cms_registration where id=99", Integer.class));
        verify(wechat, times(1)).submitRefund(eq("REG99"), eq(request.getRefundNo()),
                eq(new BigDecimal("20.00")), eq(new BigDecimal("68.00")), any());
    }

    private com.chengyinhub.business.service.support.RegistrationRefundFacts lockedFacts(Long registrationId) {
        return new org.springframework.transaction.support.TransactionTemplate(
                new DataSourceTransactionManager(db.getDataSource())).execute(status -> {
            assertEquals(registrationId, db.queryForObject(
                    "select id from cms_registration where id=? for update", Long.class, registrationId));
            return com.chengyinhub.business.service.support.RegistrationRefundFacts.fromHistory(
                    registrationId, service.selectRegistrationHistoryForUpdate(registrationId));
        });
    }

    @Test
    void forgedRefundIdentityIsPersistedAsOriginalBuyerAndScopeThroughPayoutReadback() {
        paid(99L, null, "68.00");
        RefundApplication request = request("20.00");
        request.setMemberId(72L);
        request.setOwnerType(3L);
        request.setOwnerId(9007199254740993L);
        request.setMerchantId(999L);
        assertEquals(1, service.saveBackOfficeRefund(request));
        RefundApplication persisted = refunds.selectRefundApplicationById(request.getId());
        assertEquals(9L, persisted.getMemberId());
        assertEquals(2L, persisted.getOwnerType());
        assertEquals(20L, persisted.getOwnerId());
        assertNull(persisted.getMerchantId(), "missing authoritative merchant is not caller-provided merchant");
        RefundApplication buyer = new RefundApplication();
        buyer.setSourceType("registration");
        buyer.setSourceId(99L);
        buyer.setMemberId(9L);
        assertEquals(1, service.selectRefundList(buyer).size());
        buyer.setMemberId(72L);
        assertTrue(service.selectRefundList(buyer).isEmpty());
        assertEquals(1, service.reviewRefund(review(request.getId(), 1), 7L));
        payouts.payoutAfterReview(request.getId());
        verify(wechat).submitRefund(eq("REG99"), eq(request.getRefundNo()),
                eq(new BigDecimal("20.00")), eq(new BigDecimal("68.00")), any());
        assertTrue(service.handleRefundNotify(facts(request)));
        assertEquals(RefundApplication.PAYOUT_SUCCESS,
                refunds.selectRefundApplicationById(request.getId()).getPayoutStatus());
        assertEquals(9L, refunds.selectRefundApplicationById(request.getId()).getMemberId());
    }

    @Test
    void rejectionThenTwentyAndFortyEightUseDistinctRefundNumbersAndCallbacks() {
        paid(99L, null, "68.00");
        RefundApplication rejected = save("20.00");
        assertEquals(1, service.reviewRefund(review(rejected.getId(), 2), 7L));
        RefundApplication first = save("20.00");
        assertEquals("RF99-" + rejected.getId(), first.getRefundNo());
        assertEquals(1, service.reviewRefund(review(first.getId(), 1), 7L));
        payouts.payoutAfterReview(first.getId());
        RefundApplication second = save("48.00");
        assertEquals("RF99-" + first.getId(), second.getRefundNo());
        assertNotEquals(first.getRefundNo(), second.getRefundNo());
        assertEquals(1, service.reviewRefund(review(second.getId(), 1), 8L));
        payouts.payoutAfterReview(second.getId());
        verify(wechat).submitRefund(eq("REG99"), eq(first.getRefundNo()), eq(new BigDecimal("20.00")), eq(new BigDecimal("68.00")), any());
        verify(wechat).submitRefund(eq("REG99"), eq(second.getRefundNo()), eq(new BigDecimal("48.00")), eq(new BigDecimal("68.00")), any());
        assertTrue(service.handleRefundNotify(facts(first)));
        assertEquals(RefundApplication.PAYOUT_SUCCESS, refunds.selectRefundApplicationById(first.getId()).getPayoutStatus());
        assertEquals(RefundApplication.PAYOUT_PROCESSING, refunds.selectRefundApplicationById(second.getId()).getPayoutStatus());
        assertTrue(service.handleRefundNotify(facts(second)));
        assertTrue(service.handleRefundNotify(facts(first)));
        assertEquals(2, db.queryForObject("select count(distinct biz_id) from wx_payment_callback_inbox where biz_type=2", Integer.class));
        assertEquals(new BigDecimal("68.00"), db.queryForObject("select sum(refund_amount) from refund_application where status=1", BigDecimal.class));
        assertEquals(0, service.reviewRefund(review(first.getId(), 1), 7L));
        assertEquals(0, service.reviewRefund(review(second.getId(), 1), 8L));
        assertThrows(RuntimeException.class, () -> save("0.01"));
        assertEquals(3, db.queryForObject("select count(*) from refund_application", Integer.class));
    }

    /** F-L4-02:玩家/后台待审核申请不能挡住主办方取消的全额退款;取代后新退款单真实落库、可批准,且旧单为驳回。 */
    @Test
    void pendingApplicationIsSupersededBySystemFullRefundInsteadOfBlockingCancellation() {
        paid(99L, null, "68.00");
        RefundApplication pending = save("20.00");
        assertThrows(RuntimeException.class, () -> service.saveRefund(request("68.00")));
        assertEquals(1, service.supersedePendingRegistrationRefunds(99L, "主办方取消活动,全额退款"));
        RefundApplication full = request("68.00");
        assertEquals(1, service.saveRefund(full));
        assertEquals("RF99-" + pending.getId(), full.getRefundNo());
        assertEquals(1, service.reviewRefund(review(full.getId(), 1), 0L));
        assertEquals(Integer.valueOf(2), refunds.selectRefundApplicationById(pending.getId()).getStatus());
        assertEquals(Integer.valueOf(1), refunds.selectRefundApplicationById(full.getId()).getStatus());
        assertEquals(0, service.supersedePendingRegistrationRefunds(99L, "again"));
        assertEquals(new BigDecimal("68.00"), db.queryForObject("select sum(refund_amount) from refund_application where status=1", BigDecimal.class));
    }

    @Test
    void twoLegacyPendingEightyRefundsOnOneParentCanApproveOnlyOne() throws Exception {
        paid(99L, "P99", "100.00");
        paid(100L, "P99", "0.00");
        RefundApplication a = legacyPending("legacy-a");
        RefundApplication b = legacyPending("legacy-b");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = workers.submit(() -> approveAfter(start, a.getId(), 7L));
            Future<Boolean> second = workers.submit(() -> approveAfter(start, b.getId(), 8L));
            start.countDown();
            int approved = (first.get(15, TimeUnit.SECONDS) ? 1 : 0) + (second.get(15, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, approved);
            assertEquals(new BigDecimal("80.00"), db.queryForObject("select sum(refund_amount) from refund_application where status=1", BigDecimal.class));
            assertEquals(1, db.queryForObject("select count(*) from refund_application where status=0", Integer.class));
        } finally { workers.shutdownNow(); }
    }

    @Test
    void parentRemainingCashSubtractsBothPayerAndSiblingApprovedHistory() {
        paid(99L, "P99", "100.00");
        paid(100L, "P99", "0.00");
        for (long id : new long[] {99L, 100L}) {
            db.update("update cms_registration set payment_status=4, registration_status=3 where id=?", id);
            RefundApplication history = request(id == 99L ? "40.00" : "30.00");
            history.setSourceId(id); history.setStatus(1); history.setDelFlag(0);
            history.setPayoutStatus(RefundApplication.PAYOUT_SUCCESS);
            history.setIdempotencyKey("historical-approved-" + id); history.setRefundNo("RF" + id);
            assertEquals(1, refunds.insertRefundApplication(history));
        }
        assertThrows(ServiceException.class, () -> save("30.01"));
        RefundApplication remainder = save("30.00");
        assertEquals(1, service.reviewRefund(review(remainder.getId(), 1), 7L));
        assertEquals(new BigDecimal("100.00"), db.queryForObject("select sum(refund_amount) from refund_application where status=1", BigDecimal.class));
    }

    @Test
    void failedApprovalRollsBackApplicationAndRegistrationTogether() {
        paid(99L, null, "68.00");
        RefundApplication request = save("20.00");
        doThrow(new IllegalStateException("fixture after-write failure")).when(settlements).voidPendingBySource("registration", 99L);
        assertThrows(IllegalStateException.class, () -> service.reviewRefund(review(request.getId(), 1), 7L));
        assertEquals(0, refunds.selectRefundApplicationById(request.getId()).getStatus());
        assertEquals(2, db.queryForObject("select payment_status from cms_registration where id=99", Integer.class));
        assertEquals(2, db.queryForObject("select registration_status from cms_registration where id=99", Integer.class));
    }

    private static Map<String, String> facts(RefundApplication refund) {
        return Map.of("outRefundNo", refund.getRefundNo(), "outTradeNo", "REG99", "status", "SUCCESS",
                "refund", Long.toString(refund.getRefundAmount().movePointRight(2).longValueExact()),
                "total", "6800", "currency", "CNY");
    }

    private boolean approveAfter(CountDownLatch start, Long id, Long operator) throws InterruptedException {
        start.await();
        try { return service.reviewRefund(review(id, 1), operator) == 1; }
        catch (ServiceException | IllegalArgumentException | IllegalStateException expectedBusinessRejection) { return false; }
    }

    private RefundApplication legacyPending(String key) {
        RefundApplication request = request("80.00");
        request.setStatus(0); request.setDelFlag(0); request.setPaidAmount(new BigDecimal("100.00"));
        request.setPayoutStatus(RefundApplication.PAYOUT_DISPATCH_PENDING);
        request.setIdempotencyKey(key); request.setRefundNo("RF99-" + (key.endsWith("a") ? "9001" : "9002"));
        assertEquals(1, refunds.insertRefundApplication(request));
        return request;
    }

    private RefundApplication save(String amount) {
        RefundApplication request = request(amount);
        assertEquals(1, service.saveBackOfficeRefund(request));
        assertNotNull(request.getId());
        return request;
    }

    private RefundApplication request(String amount) {
        RefundApplication request = new RefundApplication();
        request.setSourceType("registration"); request.setSourceId(99L);
        request.setRefundAmount(new BigDecimal(amount)); request.setReason("fixture refund");
        return request;
    }

    private RefundReviewDTO review(Long id, int status) {
        RefundReviewDTO review = new RefundReviewDTO();
        review.setId(id); review.setStatus(status); review.setReviewReason("fixture review");
        return review;
    }

    private void paid(Long id, String parent, String cash) {
        db.update("insert into cms_registration(id,registration_no,parent_order_no,owner_type,owner_id,ticket_id,ticket_price,member_id,original_amount,total_amount,payable_amount,wechat_payment_amount,payment_status,registration_status,verification_status,pay_expire_time) values(?,?,?,2,20,1,?,9,?,?,?, ?,2,2,0,date_add(now(),interval 1 day))", id, "REG" + id, parent, cash, cash, cash, cash, cash);
    }

    private DriverManagerDataSource source(String url) {
        return new DriverManagerDataSource(url, mysql.user(), mysql.password());
    }
}
