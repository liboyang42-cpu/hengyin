package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.CommittedBusinessNotice;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** Actual XML/current reads and Spring transactions; no provider or G allocation/payout acceptance. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class RegistrationTopicFenceMySqlIT {
    private DriverManagerDataSource source;
    private JdbcTemplate admin, db;
    private String schema;
    private boolean created;
    private DataSourceTransactionManager manager;
    private CmsRegistrationMapper registrations;
    private CmsActivityMapper activities;
    private CmsTopicMapper realTopics, topics;
    private RefundApplicationMapper refunds;
    private WxPaymentCallbackInboxMapper inbox, mutationInbox;
    private WxPaymentCallbackMutationService mutations;
    private WxPaymentCallbackInboxServiceImpl inboxService;
    private RefundServiceImpl refundService;
    private CmsRegistrationServiceImpl payments;
    private CmsRegistrationServiceImpl paymentTarget;
    private INotifyService notices;
    private ISettlementEntryService entries;
    private WechatVerifiedPaymentFactService verifiedFacts;
    private ICoopSettlementClaimService refundOwner;
    private CmsRegistrationPaymentFactMapper originalPaymentReceipts, paymentReceipts;
    private CountDownLatch requestedTopic;
    private static final String HASH = "a".repeat(64), OWNER = "b".repeat(32);

    @BeforeAll void database() throws Exception {
        assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"));
        assertEquals("1", System.getenv("B_PAYMENT_FACT_DDL_APPROVED"));
        assertEquals("1", System.getenv("A_REGISTRATION_PAYMENT_FACT_DDL_APPROVED"));
        for (String key : new String[]{"HOST", "PORT", "DATABASE_BASE", "DATABASE_SUFFIX", "USER", "PASSWORD"})
            assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_" + key));
        assertTrue(List.of("127.0.0.1", "localhost").contains(System.getenv("CHENGYIN_TEST_MYSQL_HOST")));
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("topic_fence");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("SELECT VERSION()", String.class)));
        schema = "architecture_a_fence_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + schema + "`"); created = true;
        source = new DriverManagerDataSource(config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + schema + "?"),
                config.user(), config.password());
        db = new JdbcTemplate(source); assertEquals(schema, db.queryForObject("SELECT DATABASE()", String.class));
        manager = new DataSourceTransactionManager(source);
        P0AcceptanceSchema.baseTables(db, "cms_topic", "cms_activity", "cms_registration", "refund_application");
        // Only current mapper projection columns absent from the frozen base snapshot. Test-owned schema only.
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
        db.execute("ALTER TABLE refund_application ADD COLUMN platform_takeover_at DATETIME");
        P0AcceptanceSchema.migrations(source, "migration_20260919_cms_registration_selfplay_ticket_columns_nullable.sql", "migration_20261023_wx_pay_order_intent.sql",
                "migration_wx_payment_callback_inbox_outbox_20260725.sql",
                "migration_20261024_wx_verified_payment_fact.sql");
        SqlSessionTemplate sql = P0AcceptanceSchema.sql(source, "CmsTopicMapper", "CmsActivityMapper", "CmsRegistrationMapper",
                "RefundApplicationMapper", "WxPaymentCallbackInboxMapper", "WechatVerifiedPaymentFactMapper", "CmsRegistrationPaymentFactMapper");
        registrations = sql.getMapper(CmsRegistrationMapper.class); activities = sql.getMapper(CmsActivityMapper.class);
        realTopics = sql.getMapper(CmsTopicMapper.class); refunds = sql.getMapper(RefundApplicationMapper.class);
        inbox = sql.getMapper(WxPaymentCallbackInboxMapper.class);
        originalPaymentReceipts = sql.getMapper(CmsRegistrationPaymentFactMapper.class);
        verifiedFacts = WechatVerifiedFactTestSupport.realFacts(source);
    }

    private <T> T transactional(T target) {
        ProxyFactory factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        @SuppressWarnings("unchecked") T proxy = (T) factory.getProxy(); return proxy;
    }

    @BeforeEach void fixtures() throws Exception {
        // Entire schema is this class's CI UUID scratch database. Recreate only this new fixture table,
        // never disable its immutable triggers while the business executes or delete retained receipts.
        db.execute("DROP TABLE IF EXISTS cms_registration_payment_fact");
        P0AcceptanceSchema.migrations(source, "migration_20261024_registration_payment_fact.sql");
        paymentReceipts = mock(CmsRegistrationPaymentFactMapper.class, delegatesTo(originalPaymentReceipts));
        db.update("DELETE FROM wx_payment_callback_fact_link");
        db.update("DELETE FROM wx_payment_callback_inbox");
        db.update("DELETE FROM wx_verified_payment_fact_rejection"); db.update("DELETE FROM wx_verified_payment_fact");
        db.update("DELETE FROM wx_payment_callback_outbox"); db.update("DELETE FROM wx_pay_order_intent");
        db.update("DELETE FROM refund_application");
        db.update("DELETE FROM cms_registration"); db.update("DELETE FROM cms_activity"); db.update("DELETE FROM cms_topic");
        db.update("INSERT INTO cms_topic(id,member_id) VALUES(7,70),(8,80)");
        db.update("INSERT INTO cms_activity(id,member_id,topic_id) VALUES(5,70,7)");
        original(1L, 1, 7L, null, "30.00");
        db.update("INSERT INTO refund_application(id,refund_no,source_type,source_id,owner_type,owner_id,member_id,"
                + "refund_amount,paid_amount,status,payout_status,del_flag) VALUES(9,'RF1','registration',1,1,7,11,10,30,1,0,0)");
        requestedTopic = new CountDownLatch(1);
        topics = mock(CmsTopicMapper.class, delegatesTo(realTopics));
        doAnswer(call -> { requestedTopic.countDown(); return realTopics.selectCmsTopicByIdForUpdate(call.getArgument(0)); })
                .when(topics).selectCmsTopicByIdForUpdate(anyLong());
        mutationInbox = mock(WxPaymentCallbackInboxMapper.class, delegatesTo(inbox));
        WxPaymentCallbackMutationService mutationTarget = new WxPaymentCallbackMutationService();
        ReflectionTestUtils.setField(mutationTarget, "mapper", mutationInbox);
        ReflectionTestUtils.setField(mutationTarget, "registrationMapper", registrations);
        ReflectionTestUtils.setField(mutationTarget, "activityMapper", activities);
        ReflectionTestUtils.setField(mutationTarget, "topicMapper", topics);
        ReflectionTestUtils.setField(mutationTarget, "refundMapper", refunds);
        mutations = transactional(mutationTarget);
        WxPaymentCallbackClaimService claim = new WxPaymentCallbackClaimService();
        ReflectionTestUtils.setField(claim, "mapper", inbox);
        WxPaymentCallbackInboxServiceImpl inboxTarget = new WxPaymentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(inboxTarget, "mapper", inbox);
        ReflectionTestUtils.setField(inboxTarget, "claimService", transactional(claim));
        ReflectionTestUtils.setField(inboxTarget, "mutationService", mutations);
        ReflectionTestUtils.setField(inboxTarget, "alarmService", mock(AlarmService.class));
        ReflectionTestUtils.setField(inboxTarget, "verifiedFacts", verifiedFacts);
        inboxService = transactional(inboxTarget);
        RefundServiceImpl refundTarget = new RefundServiceImpl();
        ReflectionTestUtils.setField(refundTarget, "refundApplicationMapper", refunds);
        ReflectionTestUtils.setField(refundTarget, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(refundTarget, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(refundTarget, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(refundTarget, "wxPaymentCallbackInboxService", inboxService);
        ReflectionTestUtils.setField(refundTarget, "verifiedPaymentFacts", verifiedFacts);
        ReflectionTestUtils.setField(refundTarget, "alarmService", mock(AlarmService.class));
        // This A seam fixture has no G original components. G's real ledger has its own dedicated IT.
        refundOwner = mock(ICoopSettlementClaimService.class);
        when(refundOwner.hasFrozenOriginalRefundComponents(anyLong())).thenReturn(false);
        ReflectionTestUtils.setField(refundTarget, "coopSettlementClaimService", refundOwner);
        notices = mock(INotifyService.class); ReflectionTestUtils.setField(refundTarget, "notifyService", notices);
        refundService = transactional(refundTarget);
        payments = paymentService();
    }

    private CmsRegistrationServiceImpl paymentService() {
        CmsRegistrationServiceImpl target = new CmsRegistrationServiceImpl();
        paymentTarget = target;
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(target, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(target, "verifiedPaymentFacts", verifiedFacts);
        ReflectionTestUtils.setField(target, "coopSettlementClaimService", mock(ICoopSettlementClaimService.class));
        ReflectionTestUtils.setField(target, "registrationPaymentFactMapper", paymentReceipts);
        ReflectionTestUtils.setField(target, "wxPaymentCallbackInboxService", inboxService);
        ReflectionTestUtils.setField(target, "alarmService", mock(AlarmService.class));
        entries = mock(ISettlementEntryService.class); ReflectionTestUtils.setField(target, "settlementEntryService", entries);
        UmsMemberMapper members = mock(UmsMemberMapper.class); UmsMember member = new UmsMember(); member.setId(11L);
        when(members.selectUmsMemberById(11L)).thenReturn(member);
        ReflectionTestUtils.setField(target, "umsMemberMapper", members);
        ReflectionTestUtils.setField(target, "cmsPointsSettingService", mock(ICmsPointsSettingService.class));
        ReflectionTestUtils.setField(target, "cmsRegistrationScanMapper", mock(CmsRegistrationScanMapper.class));
        ISystemNotificationIntentService intents = mock(ISystemNotificationIntentService.class);
        when(intents.findIntentId(anyString())).thenReturn(null);
        when(intents.enqueue(anyLong(), anyInt(), anyString(), anyString(), anyString())).thenReturn(1L);
        ReflectionTestUtils.setField(target, "systemNotificationIntents", intents);
        ReflectionTestUtils.setField(target, "committedBusinessNotice", mock(CommittedBusinessNotice.class));
        ReflectionTestUtils.setField(target, "imService", mock(IImService.class));
        return transactional(target);
    }

    private void original(Long id, int ownerType, Long ownerId, String parent, String cash) {
        db.update("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,member_id,ticket_id,ticket_price,"
                + "original_amount,total_amount,payable_amount,wechat_payment_amount,parent_order_no,pay_expire_time,registration_status,payment_status)"
                + " VALUES(?,?,?,?,11,1,0,?,?,?, ?,?,DATE_ADD(NOW(),INTERVAL 1 HOUR),1,0)",
                id, "REG" + id, ownerType, ownerId, cash, cash, cash, cash, parent);
    }
    private WxPaymentCallbackClaim ownedPayment() {
        db.update("INSERT INTO wx_payment_callback_inbox(notify_hash,biz_type,biz_id,event_type,state,owner_token,fence_token,lease_until)"
                + " VALUES(UNHEX(?),1,1,'SUCCESS',1,UNHEX(?),1,DATE_ADD(NOW(),INTERVAL 60 SECOND))", HASH, OWNER);
        return WxPaymentCallbackClaim.acquired(HASH, OWNER, 1);
    }
    private IWxPaymentCallbackInboxService.Mutation paymentMutation(boolean failAfterWrite) {
        return new IWxPaymentCallbackInboxService.Mutation() {
            public boolean isCurrent(WxPaymentCallbackInbox row) { return true; }
            public boolean apply(WxPaymentCallbackInbox row) {
                assertEquals(1, registrations.claimPaymentSuccess(1L, "TX1", new java.util.Date()));
                if (failAfterWrite) throw new IllegalStateException("fixture after actual payment CAS");
                return true;
            }
        };
    }
    private Map<String, String> refundFacts() {
        return refundFacts("TX1");
    }

    private Map<String, String> refundFacts(String providerTransaction) {
        // This refund fixture's original local payment identity is TX1; a candidate provider may disagree.
        db.update("UPDATE cms_registration SET transaction_id='TX1' WHERE id=1");
        WechatVerifiedPaymentFact fact = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,
                "mch-origin", "wx-origin", "REG1", "WXREG1", "RF1", "WXREF1", providerTransaction, "JSAPI", "CNY",
                3000, 1000, java.time.Instant.parse("2026-10-24T00:00:00Z"), null, "SUCCESS");
        var reference = verifiedFacts.recordVerified(fact);
        Map<String, String> facts = new java.util.HashMap<>(Map.of("outRefundNo", "RF1", "outTradeNo", "REG1", "status", "SUCCESS",
                "refund", "1000", "total", "3000", "currency", "CNY"));
        facts.put("verified_fact_id", Long.toString(reference.id())); facts.put("verified_fact_hash", reference.hash());
        facts.put("mchid", fact.mchid()); facts.put("appid", fact.appid()); facts.put("tradeType", fact.tradeType());
        facts.put("transactionId", fact.transactionId()); facts.put("refundId", fact.providerId()); facts.put("wxOutTradeNo", fact.wxOutTradeNo());
        return facts;
    }

    private Map<String, String> paymentFacts() {
        WechatVerifiedFactTestSupport.freezeRegistrationAttempt(source, 1L, "REG1", "WXREG1",
                "mch-origin", "wx-origin", "JSAPI", 3000L, HASH);
        WechatVerifiedPaymentFact fact = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                "mch-origin", "wx-origin", "REG1", "WXREG1", "WXREG1", "TX1", "TX1", "JSAPI", "CNY",
                3000, 3000, java.time.Instant.parse("2026-10-24T00:00:00Z"), HASH, "SUCCESS");
        var reference = verifiedFacts.recordVerified(fact);
        Map<String, String> facts = new java.util.HashMap<>(Map.of("out_trade_no", "REG1", "transaction_id", "TX1",
                "trade_state", "SUCCESS", "total", "3000", "wx_out_trade_no", "WXREG1"));
        facts.put("verified_fact_id", Long.toString(reference.id())); facts.put("verified_fact_hash", reference.hash());
        facts.put("mchid", fact.mchid()); facts.put("appid", fact.appid()); facts.put("trade_type", fact.tradeType());
        facts.put("currency", fact.currency()); return facts;
    }

    /** This RR scenario holds only an ordinary read view, never the original business row lock. */
    private Map<String, String> independentProducerPaymentFacts() {
        try (var producer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            return producer.submit(this::paymentFacts).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
            throw new AssertionError("Independent original fact producer failed", failed);
        }
    }

    /** Actual topic lock remains held while a third connection proves registration/refund locks are still free. */
    private <T> T afterTopicRelease(Callable<T> action, boolean checkRefund) throws Exception {
        return afterTopicRelease(action, checkRefund, () -> { });
    }

    private <T> T afterTopicRelease(Callable<T> action, boolean checkRefund, Runnable beforeRelease) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<T> result = null;
        try {
            try (Connection holder = source.getConnection(); PreparedStatement lock = holder.prepareStatement("SELECT id FROM cms_topic WHERE id=7 FOR UPDATE")) {
                holder.setAutoCommit(false); lock.setQueryTimeout(5);
                try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
                result = worker.submit(action);
                assertTrue(requestedTopic.await(5, TimeUnit.SECONDS), "actual caller must request topic before business mutation");
                try (Connection observer = source.getConnection()) {
                    observer.setAutoCommit(false);
                    try {
                        try (PreparedStatement query = observer.prepareStatement("SELECT id FROM cms_activity WHERE id=5 FOR UPDATE NOWAIT");
                             var rows = query.executeQuery()) { assertTrue(rows.next()); }
                        try (PreparedStatement query = observer.prepareStatement("SELECT id FROM cms_registration WHERE id=1 FOR UPDATE NOWAIT");
                             var rows = query.executeQuery()) { assertTrue(rows.next()); }
                        if (checkRefund) try (PreparedStatement query = observer.prepareStatement("SELECT id FROM refund_application WHERE id=9 FOR UPDATE NOWAIT");
                                             var rows = query.executeQuery()) { assertTrue(rows.next()); }
                    } finally { observer.rollback(); }
                }
                assertFalse(result.isDone(), "topic fence must still block the actual caller");
                beforeRelease.run();
                holder.commit();
            }
            return result.get(10, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow(); assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS), "owned worker exited before cleanup");
        }
    }

    @Test void creationTakesTopicBeforeTheClaimCanLockItsActivityAndNeverChangesEconomicsOnClaimFailure() throws Exception {
        IClubWaitlistOrderBridge claim = mock(IClubWaitlistOrderBridge.class);
        IllegalStateException stopped = new IllegalStateException("fixture stops after real claim activity lock");
        when(claim.claimForOrder(anyLong(), any(), any())).thenAnswer(call -> {
            assertNotNull(activities.selectCmsActivityByIdForUpdate(5L));
            throw stopped;
        });
        CmsRegistrationServiceImpl target = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(target, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(target, "clubWaitlistOrderBridge", claim);
        CmsRegistrationServiceImpl creation = transactional(target);
        com.chengyinhub.business.domain.dto.RegistrationRequest request = new com.chengyinhub.business.domain.dto.RegistrationRequest();
        request.setOwnerType(2); request.setOwnerId(5L);
        assertTrue(afterTopicRelease(() -> {
            assertSame(stopped, assertThrows(IllegalStateException.class,
                    () -> creation.create(11L, request, "claim-original", "a".repeat(64))));
            return true;
        }, true, () -> verify(claim, never()).claimForOrder(anyLong(), any(), any())));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM cms_registration", Integer.class));
        assertEquals(0, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM refund_application", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_inbox", Integer.class));
    }

    @Test void paymentInboxWaitsForTopicWithoutHoldingRegistration() throws Exception {
        WxPaymentCallbackClaim claim = ownedPayment();
        assertTrue(afterTopicRelease(() -> mutations.apply(claim, paymentMutation(false)), false));
        assertEquals(2, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(2, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
    }

    @Test void actualRefundNotifyWaitsForTopicThenCommitsTerminalAndDoneExactlyOnce() throws Exception {
        assertTrue(afterTopicRelease(() -> refundService.handleRefundNotify(refundFacts()), true));
        assertEquals(RefundApplication.PAYOUT_SUCCESS, db.queryForObject("SELECT payout_status FROM refund_application WHERE id=9", Integer.class));
        assertEquals(2, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertTrue(refundService.handleRefundNotify(refundFacts()));
        verify(notices, times(1)).notifyRefund(eq(11L), anyString(), eq("10.00"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_inbox", Integer.class));
    }

    @Test void refundTerminalRollsBackWhenDoneWriteFailsAndOriginalRetryCanComplete() {
        doReturn(0).when(mutationInbox).markDone(anyString(), anyString(), anyLong());
        assertFalse(refundService.handleRefundNotify(refundFacts()));
        assertEquals(0, db.queryForObject("SELECT payout_status FROM refund_application WHERE id=9", Integer.class));
        assertEquals(3, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        verifyNoInteractions(notices);
        doAnswer(call -> inbox.markDone(call.getArgument(0), call.getArgument(1), call.getArgument(2)))
                .when(mutationInbox).markDone(anyString(), anyString(), anyLong());
        assertTrue(refundService.handleRefundNotify(refundFacts()));
        assertEquals(RefundApplication.PAYOUT_SUCCESS, db.queryForObject("SELECT payout_status FROM refund_application WHERE id=9", Integer.class));
        assertEquals(2, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        verify(notices, times(1)).notifyRefund(eq(11L), anyString(), eq("10.00"));
    }

    @Test void committedTerminalAfterTheOldSnapshotIsRecognizedFromTheCurrentLockedRefund() {
        AtomicBoolean won = new AtomicBoolean();
        doAnswer(call -> {
            if (won.compareAndSet(false, true))
                new JdbcTemplate(new DriverManagerDataSource(source.getUrl(), source.getUsername(), source.getPassword()))
                        .update("UPDATE refund_application SET payout_status=1 WHERE id=9");
            return realTopics.selectCmsTopicByIdForUpdate(call.getArgument(0));
        }).when(topics).selectCmsTopicByIdForUpdate(7L);
        assertTrue(refundService.handleRefundNotify(refundFacts()));
        assertEquals(RefundApplication.PAYOUT_SUCCESS, db.queryForObject("SELECT payout_status FROM refund_application WHERE id=9", Integer.class));
        assertEquals(2, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        verifyNoInteractions(notices);
    }

    @Test void zeroPaymentLocksTopicBeforeCompletingEveryCurrentParentOrderRow() throws Exception {
        db.update("UPDATE cms_registration SET parent_order_no='P1',payable_amount=0,wechat_payment_amount=0 WHERE id=1");
        original(2L, 1, 7L, "P1", "0.00");
        assertEquals("SUCCESS", afterTopicRelease(() -> payments.completeZeroAmountPayment("REG2"), false));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM cms_registration WHERE payment_status=2 AND registration_status=2", Integer.class));
        verify(entries, times(2)).recordOnPayment(any(CmsRegistration.class));
    }

    @Test void currentRegistrationOwnerChangedBeforeLockRejectsWithoutPaymentOrDone() {
        WxPaymentCallbackClaim claim = ownedPayment();
        AtomicBoolean changed = new AtomicBoolean();
        doAnswer(call -> {
            if (changed.compareAndSet(false, true))
                new JdbcTemplate(new DriverManagerDataSource(source.getUrl(), source.getUsername(), source.getPassword()))
                        .update("UPDATE cms_registration SET member_id=22 WHERE id=1");
            return realTopics.selectCmsTopicByIdForUpdate(call.getArgument(0));
        }).when(topics).selectCmsTopicByIdForUpdate(7L);
        assertThrows(RuntimeException.class, () -> mutations.apply(claim, paymentMutation(false)));
        assertEquals(0, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(1, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
    }

    @Test void currentActivityParentChangedBeforeLockRejectsWithoutPaymentOrDone() {
        db.update("UPDATE cms_registration SET owner_type=2,owner_id=5 WHERE id=1");
        WxPaymentCallbackClaim claim = ownedPayment();
        AtomicBoolean changed = new AtomicBoolean();
        doAnswer(call -> {
            if (changed.compareAndSet(false, true)) {
                // Independent committed fixture writer, before this caller owns any activity/registration row.
                new JdbcTemplate(new DriverManagerDataSource(source.getUrl(), source.getUsername(), source.getPassword()))
                        .update("UPDATE cms_activity SET topic_id=8 WHERE id=5");
            }
            return realTopics.selectCmsTopicByIdForUpdate(call.getArgument(0));
        }).when(topics).selectCmsTopicByIdForUpdate(7L);
        assertThrows(RuntimeException.class, () -> mutations.apply(claim, paymentMutation(false)));
        assertEquals(0, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(1, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(8L, db.queryForObject("SELECT topic_id FROM cms_activity WHERE id=5", Long.class));
    }

    @Test void standaloneActivityDoesNotLockAnUnrelatedTopicWithTheSameNumericId() throws Exception {
        db.update("UPDATE cms_activity SET id=7,topic_id=NULL WHERE id=5");
        db.update("UPDATE cms_registration SET owner_type=2,owner_id=7,payable_amount=0,wechat_payment_amount=0 WHERE id=1");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection holder = source.getConnection(); PreparedStatement lock = holder.prepareStatement("SELECT id FROM cms_topic WHERE id=7 FOR UPDATE")) {
            holder.setAutoCommit(false);
            try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
            assertEquals("SUCCESS", worker.submit(() -> payments.completeZeroAmountPayment("REG1")).get(5, TimeUnit.SECONDS));
            holder.rollback();
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS)); }
        verify(topics, never()).selectCmsTopicByIdForUpdate(anyLong());
        assertEquals(2, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
    }

    @Test void reviewStatusCasWaitsForTopicWithoutHoldingRefundOrRegistration() throws Exception {
        db.update("UPDATE refund_application SET status=0 WHERE id=9");
        RefundReviewDTO request = new RefundReviewDTO(); request.setId(9L); request.setStatus(2); request.setReviewReason("denied");
        assertEquals(1, afterTopicRelease(() -> refundService.reviewRefund(request, 21L), true));
        assertEquals(2, db.queryForObject("SELECT status FROM refund_application WHERE id=9", Integer.class));
        assertEquals(0, db.queryForObject("SELECT payout_status FROM refund_application WHERE id=9", Integer.class));
        verifyNoInteractions(notices);
    }

    private TeamGroupServiceImpl cancellation() {
        TeamGroupServiceImpl target = new TeamGroupServiceImpl();
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(target, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(target, "refundService", mock(IRefundService.class));
        ReflectionTestUtils.setField(target, "cancellationTransactions", manager);
        ReflectionTestUtils.setField(target, "verifiedPaymentFacts", verifiedFacts);
        ReflectionTestUtils.setField(target, "imService", mock(IImService.class));
        return transactional(target);
    }

    private ClubEventOpsServiceImpl clubCancellation(ClubEventOpsMapper series, ITeamGroupService delegate) {
        ClubEventOpsServiceImpl target = new ClubEventOpsServiceImpl(
                mock(com.chengyinhub.business.access.club.IClubAccessService.class),
                new ClubEventScheduleMaterializer(), mock(IClubSessionService.class), series,
                mock(ClubMemberMapper.class), mock(ClubLeadMapper.class), mock(IClubRoleAssignmentService.class), delegate);
        ReflectionTestUtils.setField(target, "cancellationActivityMapper", activities);
        ReflectionTestUtils.setField(target, "cancellationTopicMapper", topics);
        ReflectionTestUtils.setField(target, "cancellationTransactions", manager);
        return transactional(target);
    }

    @Test void outerActivityCancellationWaitsForTopicBeforeHoldingActivityOrOrderRows() throws Exception {
        TeamGroupServiceImpl cancellation = cancellation();
        assertEquals(0, afterTopicRelease(() -> cancellation.cancelActivityByOwner(5L, 70L, "reason"), false).getRefundedOrders());
        assertNotNull(db.queryForObject("SELECT cancel_time FROM cms_activity WHERE id=5", java.util.Date.class));
        assertEquals(0, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
    }

    @Test void outerClubWrapperCannotEnterSeriesBeforeItsTopicFence() throws Exception {
        ClubEventOpsMapper series = mock(ClubEventOpsMapper.class);
        ClubEventOpsServiceImpl wrapper = clubCancellation(series, cancellation());
        assertEquals(0, afterTopicRelease(() -> wrapper.cancelActivityByOwnerWithClubWrapUp(70L, 5L, "reason"), false,
                () -> verify(series, never()).selectSeriesByActivityAnyStatusForUpdate(anyLong())).getRefundedOrders());
        verify(series, times(2)).selectSeriesByActivityAnyStatusForUpdate(5L);
        assertNotNull(db.queryForObject("SELECT cancel_time FROM cms_activity WHERE id=5", java.util.Date.class));
    }

    @Test void clubWrapperChangedParentFailsBeforeRoleCampaignOrRefundEffects() {
        ClubEventOpsMapper series = mock(ClubEventOpsMapper.class);
        ITeamGroupService delegate = mock(ITeamGroupService.class);
        ClubEventOpsServiceImpl wrapper = clubCancellation(series, delegate);
        AtomicBoolean changed = new AtomicBoolean();
        doAnswer(call -> {
            if (changed.compareAndSet(false, true))
                new JdbcTemplate(new DriverManagerDataSource(source.getUrl(), source.getUsername(), source.getPassword()))
                        .update("UPDATE cms_activity SET topic_id=8 WHERE id=5");
            return realTopics.selectCmsTopicByIdForUpdate(call.getArgument(0));
        }).when(topics).selectCmsTopicByIdForUpdate(7L);
        assertThrows(RuntimeException.class, () -> wrapper.cancelActivityByOwnerWithClubWrapUp(70L, 5L, "reason"));
        assertNull(db.queryForObject("SELECT cancel_time FROM cms_activity WHERE id=5", java.util.Date.class));
        assertEquals(8L, db.queryForObject("SELECT topic_id FROM cms_activity WHERE id=5", Long.class));
        verifyNoInteractions(delegate); verify(series, never()).insertCampaign(any());
    }

    @Test void selfRefundCannotUseMemberSnapshotFromBeforeTheParentFence() {
        db.update("UPDATE cms_registration SET payment_status=2,registration_status=2 WHERE id=1");
        TeamGroupServiceImpl cancellation = cancellation();
        AtomicBoolean changed = new AtomicBoolean();
        doAnswer(call -> {
            if (changed.compareAndSet(false, true))
                new JdbcTemplate(new DriverManagerDataSource(source.getUrl(), source.getUsername(), source.getPassword()))
                        .update("UPDATE cms_registration SET member_id=22 WHERE id=1");
            return realTopics.selectCmsTopicByIdForUpdate(call.getArgument(0));
        }).when(topics).selectCmsTopicByIdForUpdate(7L);
        assertThrows(RuntimeException.class, () -> cancellation.cancelPaidRegistration(1L, 11L));
        assertEquals(2, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(22L, db.queryForObject("SELECT member_id FROM cms_registration WHERE id=1", Long.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM refund_application WHERE source_id=1 AND id<>9", Integer.class));
    }

    @Test void actualPaymentServiceBindsTheDurableFactBeforeAtomicBusinessAndDone() {
        Map<String, String> facts = paymentFacts();
        assertEquals("SUCCESS", payments.handleWechatPayCallback(facts));
        assertEquals("TX1", db.queryForObject("SELECT transaction_id FROM cms_registration WHERE id=1", String.class));
        assertEquals(2, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(2, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(Long.valueOf(facts.get("verified_fact_id")), db.queryForObject("SELECT fact_id FROM wx_payment_callback_fact_link", Long.class));
        assertEquals(facts.get("verified_fact_hash"), db.queryForObject("SELECT fact_hash FROM wx_payment_callback_fact_link", String.class));
        assertEquals("SUCCESS", payments.handleWechatPayCallback(facts));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
        verify(entries, times(1)).recordOnPayment(any(CmsRegistration.class));
    }

    @Test void mapSuccessWithoutDurableReferenceHasNoPaymentOrInboxEffect() {
        assertEquals("FAIL", payments.handleWechatPayCallback(Map.of("out_trade_no", "REG1", "transaction_id", "TX1",
                "trade_state", "SUCCESS", "total", "3000")));
        assertEquals(0, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
        verifyNoInteractions(entries);
    }

    @Test void failedPaymentDoneKeepsTheOriginalFactAndRetryCommitsOneOriginalTerminal() {
        Map<String, String> facts = paymentFacts();
        doReturn(0).when(mutationInbox).markDone(anyString(), anyString(), anyLong());
        assertEquals("FAIL", payments.handleWechatPayCallback(facts));
        assertEquals(0, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(3, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
        doAnswer(call -> inbox.markDone(call.getArgument(0), call.getArgument(1), call.getArgument(2)))
                .when(mutationInbox).markDone(anyString(), anyString(), anyLong());
        assertEquals("SUCCESS", payments.handleWechatPayCallback(facts));
        assertEquals(2, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(2, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
        assertEquals(Long.valueOf(facts.get("verified_fact_id")), db.queryForObject("SELECT fact_id FROM wx_payment_callback_fact_link", Long.class));
    }

    @Test void oldUnboundDoneCannotBeUpgradedIntoANewVerifiedPaymentAck() {
        inboxService.register("PAY:TX1:SUCCESS", 1, 1L, "SUCCESS");
        db.update("UPDATE wx_payment_callback_inbox SET state=2");
        assertEquals("FAIL", payments.handleWechatPayCallback(paymentFacts()));
        assertEquals(0, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(2, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_fact_link", Integer.class));
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM wx_verified_payment_fact_rejection WHERE rejection_reason='INBOX_BINDING'", Integer.class));
        verifyNoInteractions(entries);
    }

    @Test void inlineConfirmedPaymentCanReadTheCommittedFactCreatedAfterItsOlderBusinessSnapshot() {
        var caller = new org.springframework.transaction.support.TransactionTemplate(manager);
        caller.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        caller.executeWithoutResult(tx -> {
            assertEquals(0, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
            Map<String, String> facts = independentProducerPaymentFacts(); // Separate producer, no suspension of the old business RR view
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class),
                    "ordinary RR read must actually demonstrate the older snapshot");
            assertEquals("SUCCESS", payments.applyConfirmedWechatPaymentWithinTransaction(facts));
        });
        assertEquals(2, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals("TX1", db.queryForObject("SELECT transaction_id FROM cms_registration WHERE id=1", String.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact WHERE business_out_trade_no='REG1' AND transaction_id='TX1'", Integer.class));
    }

    @Test void paymentRefundObservationWithoutOriginalVerifiedRefundCannotReturnPointsOrRevokeWholeOrder() {
        db.update("UPDATE cms_registration SET payment_status=2,registration_status=2,point_used=100,points_returned=0 WHERE id=1");
        ReflectionTestUtils.setField(paymentTarget, "merchantReferralBountyService", mock(MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(paymentTarget, "firstReviewPointsClawback", mock(FirstReviewPointsClawbackService.class));
        UmsMemberMapper members = (UmsMemberMapper) ReflectionTestUtils.getField(paymentTarget, "umsMemberMapper");
        when(members.addPoints(anyLong(), anyInt())).thenReturn(1);
        assertEquals("FAIL", payments.handleWechatPayCallback(Map.of("out_trade_no", "REG1", "transaction_id", "TX1",
                "trade_state", "REFUND", "total", "3000")));
        assertEquals(2, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(2, db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(0, db.queryForObject("SELECT points_returned FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(3, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        verify(members, never()).addPoints(anyLong(), anyInt()); verifyNoInteractions(entries);
    }

    @Test void internallyConsistentRefundFactForAnotherPaymentTransactionCannotCompleteThisRefund() {
        assertFalse(refundService.handleRefundNotify(refundFacts("TX2")));
        assertEquals("TX1", db.queryForObject("SELECT transaction_id FROM cms_registration WHERE id=1", String.class));
        assertEquals(0, db.queryForObject("SELECT payout_status FROM refund_application WHERE id=9", Integer.class));
        assertEquals(3, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertEquals("TX2", db.queryForObject("SELECT transaction_id FROM wx_verified_payment_fact WHERE fact_kind='REFUND'", String.class));
        verifyNoInteractions(notices);
    }

    @Test void gOwnerFailureRollsBackSuccessAndDoneWhileKeepingTheOriginalProviderFact() {
        Map<String, String> facts = refundFacts();
        doAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, db.queryForObject("SELECT payout_status FROM refund_application WHERE id=9", Integer.class));
            // Controlled participant SQL, not a simulation of G allocation or wallet algorithms.
            db.update("UPDATE cms_topic SET member_id=71 WHERE id=7");
            throw new IllegalStateException("fixture owner failed after its joined SQL write");
        }).when(refundOwner).reverseCompletedRegistrationRefund(9L);
        assertFalse(refundService.handleRefundNotify(facts));
        assertEquals(0, db.queryForObject("SELECT payout_status FROM refund_application WHERE id=9", Integer.class));
        assertEquals(70L, db.queryForObject("SELECT member_id FROM cms_topic WHERE id=7", Long.class));
        assertEquals(3, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
        verifyNoInteractions(notices);
        doNothing().when(refundOwner).reverseCompletedRegistrationRefund(9L);
        assertTrue(refundService.handleRefundNotify(facts));
        assertEquals(1, db.queryForObject("SELECT payout_status FROM refund_application WHERE id=9", Integer.class));
        assertEquals(2, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_fact_link", Integer.class));
    }

    @Test void manualSecondReviewAndOwnerJoinOneTransactionAndExactReplayKeepsTheSameRefund() {
        db.update("UPDATE refund_application SET payout_status=3,payout_handler_id=41,payout_method='offline_transfer',"
                + "payout_receipt_url='/profile/refund-origin-receipt.png',payout_handled_at=NOW() WHERE id=9");
        doThrow(new IllegalStateException("fixture joined owner unavailable"))
                .when(refundOwner).reverseCompletedRegistrationRefund(9L);
        assertThrows(RuntimeException.class, () -> refundService.verifyManualPayout(9L, 42L));
        assertEquals(3, db.queryForObject("SELECT payout_status FROM refund_application WHERE id=9", Integer.class));
        assertNull(db.queryForObject("SELECT payout_reviewer2_id FROM refund_application WHERE id=9", Long.class));
        verifyNoInteractions(notices);
        doNothing().when(refundOwner).reverseCompletedRegistrationRefund(9L);
        assertEquals(1, refundService.verifyManualPayout(9L, 42L));
        assertEquals(4, db.queryForObject("SELECT payout_status FROM refund_application WHERE id=9", Integer.class));
        assertEquals(42L, db.queryForObject("SELECT payout_reviewer2_id FROM refund_application WHERE id=9", Long.class));
        assertEquals(0, refundService.verifyManualPayout(9L, 42L)); // Existing rows return contract stays unchanged.
        verify(refundOwner, times(3)).reverseCompletedRegistrationRefund(9L); // failed, first commit, exact replay
        assertEquals(0, refundService.verifyManualPayout(9L, 43L));
        verify(refundOwner, times(3)).reverseCompletedRegistrationRefund(9L); // Different reviewer cannot claim the original receipt.
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
    }

    @Test void closedTopicCannotRepairPaidHalfStateAndAckAwayItsOriginalVerifiedRecoveryIdentity() {
        db.update("UPDATE cms_topic SET is_settlement=1 WHERE id=7");
        db.update("UPDATE cms_registration SET payment_status=2,registration_status=1,transaction_id='TX1' WHERE id=1");
        Map<String, String> facts = paymentFacts();
        assertEquals("FAIL", payments.handleWechatPayCallback(facts));
        assertEquals(2, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(1, db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals("TX1", db.queryForObject("SELECT transaction_id FROM cms_registration WHERE id=1", String.class));
        assertEquals(3, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(Long.valueOf(facts.get("verified_fact_id")), db.queryForObject("SELECT fact_id FROM wx_payment_callback_fact_link", Long.class));
        assertEquals(facts.get("verified_fact_hash"), db.queryForObject("SELECT fact_hash FROM wx_payment_callback_fact_link", String.class));
        assertEquals("FAIL", payments.handleWechatPayCallback(facts));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_fact_link", Integer.class));
        verifyNoInteractions(entries); // G reconciliation must explicitly own any later contribution or payout.
    }

    @Test void completedPayerCannotHideItsPaidHalfStateSiblingAfterTopicClose() {
        db.update("UPDATE cms_topic SET is_settlement=1 WHERE id=7");
        db.update("UPDATE cms_registration SET parent_order_no='PN1',payment_status=2,registration_status=2,transaction_id='TX1' WHERE id=1");
        original(2L, 1, 7L, "PN1", "0.00");
        db.update("UPDATE cms_registration SET payment_status=2,registration_status=1,transaction_id='TX1' WHERE id=2");
        Map<String, String> facts = paymentFacts();
        assertEquals("FAIL", payments.handleWechatPayCallback(facts));
        assertEquals(2, db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(1, db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=2", Integer.class));
        assertEquals(3, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(Long.valueOf(facts.get("verified_fact_id")), db.queryForObject("SELECT fact_id FROM wx_payment_callback_fact_link", Long.class));
        verifyNoInteractions(entries);
    }

    @Test void originalSuccessTerminalReplayCallsTheSameOwnerBeforeDoneWithoutRenotifying() {
        Map<String, String> facts = refundFacts();
        db.update("UPDATE refund_application SET payout_status=1 WHERE id=9");
        doAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
            assertEquals(1, db.queryForObject("SELECT payout_status FROM refund_application WHERE id=9", Integer.class));
            return null; // Narrow legacy-owner fixture, not a G financial receipt.
        }).when(refundOwner).reverseCompletedRegistrationRefund(9L);
        assertTrue(refundService.handleRefundNotify(facts));
        assertEquals(2, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        verify(refundOwner).reverseCompletedRegistrationRefund(9L);
        verifyNoInteractions(notices);
        assertTrue(refundService.handleRefundNotify(facts));
        verify(refundOwner).reverseCompletedRegistrationRefund(9L); // DONE repeats do not execute a second mutation.
    }

    private String inline(Map<String, String> facts) {
        return new org.springframework.transaction.support.TransactionTemplate(manager)
                .execute(status -> payments.applyConfirmedWechatPaymentWithinTransaction(facts));
    }

    @Test void inlinePaymentCommitsItsExactFactReferenceWithoutCreatingOrClaimingAnInbox() {
        Map<String, String> facts = paymentFacts();
        assertEquals("SUCCESS", inline(facts));
        assertEquals("SUCCESS", inline(facts));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM cms_registration_payment_fact", Integer.class));
        assertEquals(1L, db.queryForObject("SELECT registration_id FROM cms_registration_payment_fact", Long.class));
        assertEquals("REG1", db.queryForObject("SELECT registration_no FROM cms_registration_payment_fact", String.class));
        assertEquals("TX1", db.queryForObject("SELECT transaction_id FROM cms_registration_payment_fact", String.class));
        assertEquals(Long.valueOf(facts.get("verified_fact_id")), db.queryForObject("SELECT payment_fact_id FROM cms_registration_payment_fact", Long.class));
        assertEquals(facts.get("verified_fact_hash"), db.queryForObject("SELECT payment_fact_hash FROM cms_registration_payment_fact", String.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_fact_link", Integer.class));
        assertEquals(2, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
    }

    @Test void inlineReceiptWriteFailureAfterInsertRollsBackBusinessAndReceiptButKeepsProviderEvidence() {
        Map<String, String> facts = paymentFacts();
        doAnswer(call -> {
            assertEquals(1, originalPaymentReceipts.insertConsumed(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            throw new IllegalStateException("fixture failed after actual receipt insert");
        }).when(paymentReceipts).insertConsumed(anyLong(), anyLong(), anyString());
        assertThrows(RuntimeException.class, () -> inline(facts));
        assertEquals(0, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(1, db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM cms_registration_payment_fact", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_inbox", Integer.class));
        doAnswer(call -> originalPaymentReceipts.insertConsumed(call.getArgument(0), call.getArgument(1), call.getArgument(2)))
                .when(paymentReceipts).insertConsumed(anyLong(), anyLong(), anyString());
        assertEquals("SUCCESS", inline(facts));
        assertEquals(Long.valueOf(facts.get("verified_fact_id")), db.queryForObject("SELECT payment_fact_id FROM cms_registration_payment_fact", Long.class));
    }

    @Test void aDifferentVerifiedMerchantFactCannotReplaceTheOriginalConsumedReference() {
        Map<String, String> original = paymentFacts(); assertEquals("SUCCESS", inline(original));
        Map<String, String> candidate = otherMerchantFacts(original);
        assertThrows(RuntimeException.class, () -> inline(candidate));
        assertEquals(Long.valueOf(original.get("verified_fact_id")), db.queryForObject("SELECT payment_fact_id FROM cms_registration_payment_fact", Long.class));
        assertEquals(original.get("verified_fact_hash"), db.queryForObject("SELECT payment_fact_hash FROM cms_registration_payment_fact", String.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM cms_registration_payment_fact", Integer.class));
    }

    @Test void inlineParentOrderKeepsOnePayerReceiptAndCannotConsumeTheFactOnASibling() {
        db.update("UPDATE cms_registration SET parent_order_no='PN1' WHERE id=1");
        original(2L, 1, 7L, "PN1", "0.00");
        Map<String, String> facts = paymentFacts(); assertEquals("SUCCESS", inline(facts));
        assertEquals(2, db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=2", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM cms_registration_payment_fact", Integer.class));
        assertEquals(0, originalPaymentReceipts.insertConsumed(2L, Long.parseLong(facts.get("verified_fact_id")), facts.get("verified_fact_hash")));
        assertEquals(1L, db.queryForObject("SELECT registration_id FROM cms_registration_payment_fact", Long.class));
    }

    @Test void nonSuccessInlineObservationCannotCreateABusinessConsumptionReceipt() {
        Map<String, String> facts = new java.util.HashMap<>(paymentFacts()); facts.put("trade_state", "USERPAYING");
        assertEquals("FAIL", inline(facts));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM cms_registration_payment_fact", Integer.class));
        assertEquals(0, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
    }

    @Test void consumedReceiptRejectsUpdateAndDeleteAndRetainsItsExactOriginalReference() {
        Map<String, String> facts = paymentFacts(); assertEquals("SUCCESS", inline(facts));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> db.update("UPDATE cms_registration_payment_fact SET transaction_id='OTHER' WHERE registration_id=1"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> db.update("DELETE FROM cms_registration_payment_fact WHERE registration_id=1"));
        assertEquals("TX1", db.queryForObject("SELECT transaction_id FROM cms_registration_payment_fact", String.class));
        assertEquals(Long.valueOf(facts.get("verified_fact_id")), db.queryForObject("SELECT payment_fact_id FROM cms_registration_payment_fact", Long.class));
    }

    @Test void closedInlineRetainsTheOriginalProviderFactWithoutPretendingLocalConsumptionOrDone() {
        Map<String, String> facts = paymentFacts();
        db.update("UPDATE cms_topic SET is_settlement=1 WHERE id=7");
        db.update("UPDATE cms_registration SET payment_status=2,registration_status=1,transaction_id='TX1' WHERE id=1");
        assertEquals("FAIL", inline(facts));
        assertEquals(1, db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM cms_registration_payment_fact", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
        verifyNoInteractions(entries);
    }

    private Map<String, String> otherMerchantFacts(Map<String, String> original) {
        var other = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                "mch-other", "wx-other", "REG1", "WXREG1", "WXREG1", "TX1", "TX1", "JSAPI", "CNY",
                3000, 3000, java.time.Instant.parse("2026-10-24T00:00:00Z"), null, "SUCCESS");
        var otherReference = verifiedFacts.recordVerified(other);
        Map<String, String> candidate = new java.util.HashMap<>(original);
        candidate.put("mchid", other.mchid()); candidate.put("appid", other.appid());
        candidate.put("verified_fact_id", Long.toString(otherReference.id())); candidate.put("verified_fact_hash", otherReference.hash());
        return candidate;
    }

    @Test void normalCallbackCannotChooseAnotherFactAfterInlineConsumption() {
        Map<String, String> original = paymentFacts(); assertEquals("SUCCESS", inline(original));
        assertEquals("FAIL", payments.handleWechatPayCallback(otherMerchantFacts(original)));
        assertEquals(Long.valueOf(original.get("verified_fact_id")), db.queryForObject("SELECT payment_fact_id FROM cms_registration_payment_fact", Long.class));
        assertEquals(original.get("verified_fact_hash"), db.queryForObject("SELECT payment_fact_hash FROM cms_registration_payment_fact", String.class));
        assertEquals(3, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
    }

    @Test void normalAndInlineReplayShareOneExactBusinessConsumptionIdentity() {
        Map<String, String> original = paymentFacts();
        assertEquals("SUCCESS", payments.handleWechatPayCallback(original));
        assertEquals("SUCCESS", inline(original));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM cms_registration_payment_fact", Integer.class));
        assertEquals(Long.valueOf(original.get("verified_fact_id")), db.queryForObject("SELECT payment_fact_id FROM cms_registration_payment_fact", Long.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(2, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
    }

    private IWechatPayService closedAttemptGate() {
        IWechatPayService gateway = mock(IWechatPayService.class);
        // The B adapter/SDK/intent SQL are tested by B. This seam uses the real canonical fact reader
        // and proves A has already taken its topic/order locks before invoking the narrow port.
        when(gateway.lockPaymentAttemptForUpdate("REG1", "REG1")).thenReturn(true);
        when(gateway.lockClosablePaymentAttemptForUpdate("REG1", "REG1")).thenAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(0L, requestedTopic.getCount());
            return !verifiedFacts.hasConfirmedExternalSuccess("mch-origin", "REG1");
        });
        var attempts = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(attempts.getObject()).thenReturn(gateway);
        ReflectionTestUtils.setField(paymentTarget, "paymentAttemptGateway", attempts);
        return gateway;
    }

    private Map<String, String> closedPaymentObservation() {
        return new java.util.HashMap<>(Map.of("out_trade_no", "REG1", "wx_out_trade_no", "REG1", "trade_state", "CLOSED"));
    }

    @Test void durablePaymentSuccessBlocksAStaleClosedCallbackBeforeAnyFailureCas() {
        paymentFacts();
        db.update("UPDATE cms_registration SET payment_status=1,point_used=20,points_returned=0 WHERE id=1");
        IWechatPayService gateway = closedAttemptGate();
        CmsRegistrationMapper writes = mock(CmsRegistrationMapper.class, delegatesTo(registrations));
        ReflectionTestUtils.setField(paymentTarget, "cmsRegistrationMapper", writes);

        assertEquals("FAIL", payments.handleWechatPayCallback(closedPaymentObservation()));

        verify(writes, never()).claimPaymentFailure(anyLong(), any());
        verify(writes, never()).claimPointsReturn(anyLong(), anyString(), any());
        verify(gateway).lockClosablePaymentAttemptForUpdate("REG1", "REG1");
        verify(gateway, never()).lockPaymentAttemptForUpdate(anyString(), anyString());
        assertEquals(1, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(0, db.queryForObject("SELECT points_returned FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact WHERE fact_kind='PAYMENT'", Integer.class));
        assertEquals(3, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM cms_registration_payment_fact", Integer.class));
    }

    @Test void originalClosedCallbackWithoutDurableSuccessStillCommitsItsFirstFailure() {
        db.update("UPDATE cms_registration SET payment_status=1,point_used=0 WHERE id=1");
        closedAttemptGate();

        assertEquals("SUCCESS", payments.handleWechatPayCallback(closedPaymentObservation()));

        assertEquals(3, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(1, db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(2, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM cms_registration_payment_fact", Integer.class));
    }

    @Test void originalClosedAttemptReleasesItsResourcesOnceThroughTheDomainOwner() {
        db.update("UPDATE cms_registration SET payment_status=1,ticket_id=NULL,point_used=0 WHERE id=1");
        IWechatPayService gateway = closedAttemptGate();
        IPerkLedgerService perks = mock(IPerkLedgerService.class);
        ReflectionTestUtils.setField(paymentTarget, "perkLedgerService", perks);
        CmsRegistration observed = registrations.selectCmsRegistrationById(1L);

        assertTrue(payments.settleClosedPayingOne(observed, "REG1", "ORGANIZER_CANCEL", "fixture", 4));
        assertFalse(payments.settleClosedPayingOne(observed, "REG1", "ORGANIZER_CANCEL", "fixture", 4));

        assertEquals(4, db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=1", Integer.class));
        verify(perks, times(1)).releaseOnRefund(1L, "ORGANIZER_CANCEL");
        verify(gateway, times(2)).lockClosablePaymentAttemptForUpdate("REG1", "REG1");
        verify(gateway, never()).lockPaymentAttemptForUpdate(anyString(), anyString());
    }

    @Test void closedAttemptCannotHoldRegistrationBeforeTopicAndSeesASuccessCommittedWhileWaiting() throws Exception {
        db.update("UPDATE cms_registration SET payment_status=1,ticket_id=NULL,point_used=0 WHERE id=1");
        closedAttemptGate();
        CmsRegistration observed = registrations.selectCmsRegistrationById(1L);
        CmsRegistrationMapper writes = mock(CmsRegistrationMapper.class, delegatesTo(registrations));
        ReflectionTestUtils.setField(paymentTarget, "cmsRegistrationMapper", writes);

        assertFalse(afterTopicRelease(() -> payments.settleClosedPayingOne(observed, "REG1", "ORGANIZER_CANCEL", "fixture", 4),
                false, () -> paymentFacts()));

        verify(writes, never()).closeExpiredPaying(anyLong(), anyString(), anyInt());
        assertEquals(1, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact WHERE fact_kind='PAYMENT'", Integer.class));
    }

    @Test void staleObservedWxAttemptCannotCloseOrReleaseTheOriginalOrder() {
        db.update("UPDATE cms_registration SET payment_status=1,ticket_id=NULL,point_used=0 WHERE id=1");
        IWechatPayService gateway = closedAttemptGate();
        when(gateway.lockPaymentAttemptForUpdate("REG1", "WX-OLD")).thenReturn(true);
        when(gateway.lockClosablePaymentAttemptForUpdate("REG1", "WX-OLD")).thenReturn(false);
        IPerkLedgerService perks = mock(IPerkLedgerService.class);
        ReflectionTestUtils.setField(paymentTarget, "perkLedgerService", perks);

        assertFalse(payments.settleClosedPayingOne(registrations.selectCmsRegistrationById(1L),
                "WX-OLD", "ORGANIZER_CANCEL", "fixture", 4));

        verify(gateway).lockClosablePaymentAttemptForUpdate("REG1", "WX-OLD");
        verify(gateway, never()).lockPaymentAttemptForUpdate(anyString(), anyString());
        verifyNoInteractions(perks);
        assertEquals(1, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(1, db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=1", Integer.class));
    }

    @Test void closedResourceReleaseFailureRollsBackTheOriginalCloseCas() {
        db.update("UPDATE cms_registration SET payment_status=1,ticket_id=NULL,point_used=0 WHERE id=1");
        closedAttemptGate();
        IPerkLedgerService perks = mock(IPerkLedgerService.class);
        doThrow(new IllegalStateException("fixture release failed")).when(perks).releaseOnRefund(1L, "ORGANIZER_CANCEL");
        ReflectionTestUtils.setField(paymentTarget, "perkLedgerService", perks);

        assertThrows(RuntimeException.class, () -> payments.settleClosedPayingOne(registrations.selectCmsRegistrationById(1L),
                "REG1", "ORGANIZER_CANCEL", "fixture", 4));

        verify(perks).releaseOnRefund(1L, "ORGANIZER_CANCEL");
        assertEquals(1, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(1, db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=1", Integer.class));
    }

    @Test void changedOriginalOrderIdentityFailsBeforeAttemptGuardOrCloseCas() {
        db.update("UPDATE cms_registration SET payment_status=1 WHERE id=1");
        IWechatPayService gateway = closedAttemptGate();
        CmsRegistration observed = registrations.selectCmsRegistrationById(1L);
        db.update("UPDATE cms_registration SET member_id=22 WHERE id=1");

        assertThrows(RuntimeException.class, () -> payments.settleClosedPayingOne(observed, "REG1", "ORGANIZER_CANCEL", "fixture", 4));

        verify(gateway, never()).lockClosablePaymentAttemptForUpdate(anyString(), anyString());
        assertEquals(1, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(1, db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=1", Integer.class));
    }

    @Test void durableRefundSuccessBlocksStaleAbnormalAndTheOriginalSuccessCanStillReplay() {
        assertDurableRefundSuccessBlocksFailure("ABNORMAL");
    }

    @Test void durableRefundSuccessBlocksStaleClosedAndTheOriginalSuccessCanStillReplay() {
        assertDurableRefundSuccessBlocksFailure("CLOSED");
    }

    private void assertDurableRefundSuccessBlocksFailure(String status) {
        Map<String, String> success = refundFacts();
        Map<String, String> stale = new java.util.HashMap<>(success);
        stale.put("status", status); stale.remove("verified_fact_id"); stale.remove("verified_fact_hash");

        assertFalse(refundService.handleRefundNotify(stale));

        assertEquals(0, db.queryForObject("SELECT payout_status FROM refund_application WHERE id=9", Integer.class));
        assertEquals(3, db.queryForObject("SELECT state FROM wx_payment_callback_inbox WHERE event_type=?", Integer.class, status));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact WHERE fact_kind='REFUND'", Integer.class));
        verifyNoInteractions(refundOwner, notices);
        assertTrue(refundService.handleRefundNotify(success));
        assertEquals(1, db.queryForObject("SELECT payout_status FROM refund_application WHERE id=9", Integer.class));
        assertEquals(2, db.queryForObject("SELECT state FROM wx_payment_callback_inbox WHERE event_type='SUCCESS'", Integer.class));
        verify(refundOwner).reverseCompletedRegistrationRefund(9L);
        verify(notices, times(1)).notifyRefund(eq(11L), anyString(), eq("10.00"));
    }

    @Test void abnormalWithoutPersistedSuccessKeepsTheExistingManualInvestigationPath() {
        assertUnconfirmedRefundFailureProjectsToManual("ABNORMAL");
    }

    @Test void closedWithoutPersistedSuccessKeepsTheExistingManualInvestigationPath() {
        assertUnconfirmedRefundFailureProjectsToManual("CLOSED");
    }

    private void assertUnconfirmedRefundFailureProjectsToManual(String status) {
        Map<String, String> facts = new java.util.HashMap<>(Map.of("outRefundNo", "RF1", "outTradeNo", "REG1", "status", status,
                "refund", "1000", "total", "3000", "currency", "CNY", "mchid", "mch-origin", "refundId", "WXREF1"));

        assertTrue(refundService.handleRefundNotify(facts));

        assertEquals(2, db.queryForObject("SELECT payout_status FROM refund_application WHERE id=9", Integer.class));
        assertEquals(2, db.queryForObject("SELECT state FROM wx_payment_callback_inbox", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
        verifyNoInteractions(refundOwner, notices);
    }

    @AfterAll void cleanup() {
        if (created) {
            assertTrue(schema.matches("architecture_a_fence_[a-f0-9]{32}"));
            admin.execute("DROP DATABASE `" + schema + "`");
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema));
        }
    }
}
