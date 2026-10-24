package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
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
    private INotifyService notices;
    private ISettlementEntryService entries;
    private CountDownLatch requestedTopic;
    private static final String HASH = "a".repeat(64), OWNER = "b".repeat(32);

    @BeforeAll void database() throws Exception {
        assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"));
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
        P0AcceptanceSchema.migrations(source, "migration_wx_payment_callback_inbox_outbox_20260725.sql");
        SqlSessionTemplate sql = P0AcceptanceSchema.sql(source, "CmsTopicMapper", "CmsActivityMapper", "CmsRegistrationMapper",
                "RefundApplicationMapper", "WxPaymentCallbackInboxMapper");
        registrations = sql.getMapper(CmsRegistrationMapper.class); activities = sql.getMapper(CmsActivityMapper.class);
        realTopics = sql.getMapper(CmsTopicMapper.class); refunds = sql.getMapper(RefundApplicationMapper.class);
        inbox = sql.getMapper(WxPaymentCallbackInboxMapper.class);
    }

    private <T> T transactional(T target) {
        ProxyFactory factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        @SuppressWarnings("unchecked") T proxy = (T) factory.getProxy(); return proxy;
    }

    @BeforeEach void fixtures() {
        db.update("DELETE FROM wx_payment_callback_inbox"); db.update("DELETE FROM refund_application");
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
        inboxService = transactional(inboxTarget);
        RefundServiceImpl refundTarget = new RefundServiceImpl();
        ReflectionTestUtils.setField(refundTarget, "refundApplicationMapper", refunds);
        ReflectionTestUtils.setField(refundTarget, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(refundTarget, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(refundTarget, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(refundTarget, "wxPaymentCallbackInboxService", inboxService);
        notices = mock(INotifyService.class); ReflectionTestUtils.setField(refundTarget, "notifyService", notices);
        refundService = transactional(refundTarget);
        payments = paymentService();
    }

    private CmsRegistrationServiceImpl paymentService() {
        CmsRegistrationServiceImpl target = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(target, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
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
        return Map.of("outRefundNo", "RF1", "outTradeNo", "REG1", "status", "SUCCESS",
                "refund", "1000", "total", "3000", "currency", "CNY");
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
        return transactional(target);
    }

    private ClubEventOpsServiceImpl clubCancellation(ClubEventOpsMapper series, ITeamGroupService delegate) {
        ClubEventOpsServiceImpl target = new ClubEventOpsServiceImpl(
                mock(com.chengyinhub.business.access.club.IClubAccessService.class),
                new ClubEventScheduleMaterializer(), mock(IClubSessionService.class), series,
                mock(ClubMemberMapper.class), mock(ClubLeadMapper.class), mock(IClubRoleAssignmentService.class), delegate);
        ReflectionTestUtils.setField(target, "cancellationActivityMapper", activities);
        ReflectionTestUtils.setField(target, "cancellationTopicMapper", topics);
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
        verify(series, times(1)).selectSeriesByActivityAnyStatusForUpdate(5L);
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

    @AfterAll void cleanup() {
        if (created) {
            assertTrue(schema.matches("architecture_a_fence_[a-f0-9]{32}"));
            admin.execute("DROP DATABASE `" + schema + "`");
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema));
        }
    }
}
