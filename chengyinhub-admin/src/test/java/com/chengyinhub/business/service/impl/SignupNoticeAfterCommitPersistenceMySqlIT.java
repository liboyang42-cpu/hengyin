package com.chengyinhub.business.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.CommittedBusinessNotice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Public payment callback -> committed notice queue -> independent QR/IM database writes.
 * Registration payment facts use a JDBC fixture adapter. The three production IM mappers,
 * notification dispatcher, producer and existing B intent consumer/mappers are real MySQL.
 * A registration state mapper is a JDBC fixture adapter, not the production financial mapper.
 * No real WX is invoked. Fresh task reconstruction/lost wake is not a real process-kill test.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
public class SignupNoticeAfterCommitPersistenceMySqlIT {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment(
            "signup_notice_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    private JdbcTemplate admin;
    private boolean schemaCreated;
    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private TransactionTemplate transaction;
    private ICmsRegistrationService registrationService;
    private CmsRegistrationServiceImpl registrationTarget;
    private CmsRegistrationMapper registrations;
    private IImService imService;
    private ISystemNotificationIntentService intents;
    private ImNotificationIntentMapper intentMapper;
    private ImServiceImpl imTarget;
    private final List<Runnable> queued = new ArrayList<>();
    private ScheduledExecutorService executor;
    private AlarmService alarm;
    private final AtomicBoolean failIm = new AtomicBoolean();
    private final AtomicBoolean failQr = new AtomicBoolean();
    private final AtomicReference<Throwable> unexpectedImFailure = new AtomicReference<>();
    private Connection unrelatedConnection;
    private WechatVerifiedPaymentFactService fixturePaymentFacts;
    private com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference fixturePaymentReference;

    @BeforeEach
    void setup() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("create database " + mysql.databaseName() + " character set utf8mb4");
        schemaCreated = true;
        source = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        db = new JdbcTemplate(source);
        db.execute("create table registration_fact(id bigint primary key, payment_status int, registration_status int, transaction_id varchar(64), qrcode_url varchar(256), verification_code varchar(64), member_id bigint not null, topic_id bigint not null, parent_order_no varchar(64)) engine=InnoDB");
        db.update("insert into registration_fact values(99,1,1,null,null,null,9,88,null)");
        // Existing JDBC business adapter: local receipt SQL participates in the same IM acceptance transaction.
        // Canonical provider/receipt FK + append-only DDL is verified by A's dedicated production-XML IT, not here.
        db.execute("create table fixture_payment_consumption(registration_id bigint primary key,registration_no varchar(64),"
                + "payment_fact_id bigint unique,payment_fact_hash char(64),transaction_id varchar(64),"
                + "foreign key(registration_id) references registration_fact(id)) engine=InnoDB");
        fixturePaymentFacts = WechatVerifiedFactTestSupport.memoryHistoricalFacts();
        // This suite proves IM persistence, not payment-owner locking. The explicit owner port reads
        // its existing registration fixture; never derive a current intent/owner from provider facts.
        com.chengyinhub.business.service.WechatPaymentOrderOwner fixtureOwner = no -> {
            assertEquals("REG99", no);
            CmsRegistration current = readRegistration();
            return new com.chengyinhub.business.service.WechatPaymentOrderOwner.Snapshot(
                    "REGISTRATION", current.getId(), current.getWechatPaymentAmount(), false,
                    Integer.valueOf(1).equals(current.getPaymentStatus())
                            && Integer.valueOf(1).equals(current.getRegistrationStatus()));
        };
        ReflectionTestUtils.setField(fixturePaymentFacts, "factBusinessOwner", fixtureOwner);
        // Provider evidence exists before any local payment transaction; callbacks only reuse this exact reference.
        fixturePaymentReference = fixturePaymentFacts.recordVerified(originalPaymentFact());
        Path sqlRoot = Paths.get("chengyinhub-admin/sql");
        if (!sqlRoot.toFile().isDirectory()) sqlRoot = Paths.get("sql");
        new ResourceDatabasePopulator(new FileSystemResource(sqlRoot.resolve("migration_im.sql").toFile()),
                new FileSystemResource(sqlRoot.resolve("migration_im_send_idempotency_20260913.sql").toFile()),
                new FileSystemResource(sqlRoot.resolve("migration_20261012_im_media_visibility.sql").toFile())).execute(source);
        db.execute("create table sys_job (job_name varchar(64),job_group varchar(64),invoke_target varchar(255),cron_expression varchar(255),misfire_policy varchar(20),concurrent varchar(20),status varchar(20),create_by varchar(64),create_time datetime,remark text)");
        new ResourceDatabasePopulator(new FileSystemResource(sqlRoot.resolve("migration_20261013_b_im_notification_intent.sql").toFile())).execute(source);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        executor = mock(ScheduledExecutorService.class);
        when(executor.schedule(any(Runnable.class), eq(10L), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
            queued.add(call.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        alarm = mock(AlarmService.class);
        CommittedBusinessNotice notices = new CommittedBusinessNotice(mock(INotifyService.class), alarm, manager, executor);

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"),
                new ClassPathResource("mapper/business/ImNotificationIntentMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        ImConversationMemberMapper members = sql.getMapper(ImConversationMemberMapper.class);
        ImServiceImpl im = spy(new ImServiceImpl());
        imTarget = im;
        intentMapper = sql.getMapper(ImNotificationIntentMapper.class);
        ReflectionTestUtils.setField(im, "notificationIntentMapper", intentMapper);
        ReflectionTestUtils.setField(im, "conversationMapper", sql.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(im, "messageMapper", sql.getMapper(ImMessageMapper.class));
        ReflectionTestUtils.setField(im, "memberMapper", members);
        doAnswer(call -> {
            assertIndependentTaskTransaction();
            Object result;
            try { result = call.callRealMethod(); }
            catch (Throwable fixtureFailure) { unexpectedImFailure.set(fixtureFailure); throw fixtureFailure; }
            if (failIm.get()) throw new IllegalStateException("fixture IM post-insert failure");
            return result;
        }).when(im).deliverSystemNotification(anyLong());
        ProxyFactory imProxy = new ProxyFactory(im);
        imProxy.setInterfaces(IImService.class, ISystemNotificationIntentService.class);
        imProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        imService = (IImService) imProxy.getProxy();
        intents = (ISystemNotificationIntentService) imService;

        registrations = mock(CmsRegistrationMapper.class);
        when(registrations.selectByRegistrationNo("REG99")).thenAnswer(call -> readRegistration());
        when(registrations.selectCmsRegistrationById(anyLong())).thenAnswer(call -> readRegistration(call.getArgument(0), false));
        when(registrations.selectCmsRegistrationByIdForUpdate(anyLong())).thenAnswer(call -> readRegistration(call.getArgument(0), true));
        when(registrations.selectByParentOrderNo(anyString())).thenAnswer(call -> db.queryForList(
                "select id from registration_fact where parent_order_no=? order by id", Long.class, (String) call.getArgument(0))
                .stream().map(id -> readRegistration(id, false)).toList());
        when(registrations.selectByParentOrderNoForUpdate(anyString(), org.mockito.ArgumentMatchers.<Long>nullable(Long.class)))
                .thenAnswer(call -> db.queryForList("select id from registration_fact where parent_order_no=? order by id for update",
                        Long.class, (String) call.getArgument(0)).stream().map(id -> readRegistration(id, true)).toList());
        when(registrations.claimPaymentSuccess(anyLong(), anyString(), any())).thenAnswer(call -> db.update(
                "update registration_fact set payment_status=2, registration_status=2, transaction_id=? where id=? and payment_status=1",
                (String) call.getArgument(1), (Long) call.getArgument(0)));
        when(registrations.repairHistoricalPaidHalfState(anyLong(), anyString(), any(), any())).thenAnswer(call -> db.update(
                "update registration_fact set registration_status=2,transaction_id=? where id=? and payment_status=2 and registration_status=1",
                (String) call.getArgument(1), (Long) call.getArgument(0)));
        when(registrations.updateCmsRegistration(any())).thenAnswer(call -> {
            assertIndependentTaskTransaction();
            CmsRegistration r = call.getArgument(0);
            int rows = db.update("update registration_fact set qrcode_url=?, verification_code=? where id=?", r.getQrcodeUrl(), r.getVerificationCode(), r.getId());
            if (failQr.get()) throw new IllegalStateException("fixture QR post-update failure");
            return rows;
        });
        CmsRegistrationServiceImpl target = new CmsRegistrationServiceImpl();
        registrationTarget = target;
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(target, "verifiedPaymentFacts", fixturePaymentFacts);
        ReflectionTestUtils.setField(target, "coopSettlementClaimService", mock(com.chengyinhub.business.service.ICoopSettlementClaimService.class));
        var consumed = mock(com.chengyinhub.business.mapper.CmsRegistrationPaymentFactMapper.class);
        when(consumed.insertConsumed(anyLong(), anyLong(), anyString())).thenAnswer(call -> {
            Long id = call.getArgument(0); long factId = call.getArgument(1); String hash = call.getArgument(2);
            var proof = fixturePaymentFacts.requireVerified(new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(factId, hash));
            CmsRegistration current = readRegistration(id, true);
            if (proof.facts().kind() != com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT
                    || !current.getRegistrationNo().equals(proof.facts().businessOutTradeNo())
                    || !Integer.valueOf(2).equals(current.getPaymentStatus()) || !Integer.valueOf(2).equals(current.getRegistrationStatus())
                    || !proof.facts().transactionId().equals(current.getTransactionId())) return 0;
            return db.update("insert ignore into fixture_payment_consumption values(?,?,?,?,?)",
                    id, current.getRegistrationNo(), factId, hash, current.getTransactionId());
        });
        when(consumed.selectByRegistrationForUpdate(anyLong())).thenAnswer(call -> {
            var rows = db.query("select * from fixture_payment_consumption where registration_id=? for update",
                    (row, n) -> new com.chengyinhub.business.domain.dto.RegistrationPaymentFactReceipt(row.getLong("registration_id"),
                            row.getString("registration_no"), row.getLong("payment_fact_id"), row.getString("payment_fact_hash"), row.getString("transaction_id")),
                    (Long) call.getArgument(0));
            return rows.isEmpty() ? null : rows.get(0);
        });
        ReflectionTestUtils.setField(target, "registrationPaymentFactMapper", consumed);
        ReflectionTestUtils.setField(target, "imService", imService);
        ReflectionTestUtils.setField(target, "systemNotificationIntents", intents);
        ReflectionTestUtils.setField(target, "alarmService", alarm);
        ReflectionTestUtils.setField(target, "committedBusinessNotice", notices);
        // W3:回调入口走 inbox fence(业务在 fence 自己的事务里提交),这里用直通桩;
        // 「支付事务提交后才投递」的边界由下方 payInsideTransaction 经同一状态机在调用方事务内验证。
        ReflectionTestUtils.setField(target, "wxPaymentCallbackInboxService",
                com.chengyinhub.business.service.PaymentCallbackInboxStubs.passThrough(manager, fixturePaymentFacts));
        ReflectionTestUtils.setField(target, "settlementEntryService", mock(ISettlementEntryService.class));
        ReflectionTestUtils.setField(target, "umsMemberMapper", mock(UmsMemberMapper.class));
        ReflectionTestUtils.setField(target, "cmsPointsSettingService", mock(ICmsPointsSettingService.class));
        ReflectionTestUtils.setField(target, "cmsRegistrationScanMapper", mock(CmsRegistrationScanMapper.class));
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic(); topic.setId(88L);
        when(topics.selectCmsTopicById(88L)).thenReturn(topic);
        when(topics.selectCmsTopicByIdForUpdate(88L)).thenReturn(topic);
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        QrcodeServiceImpl qr = mock(QrcodeServiceImpl.class);
        when(qr.generateQrcode(anyString(), eq(300), eq(300))).thenReturn("fixture-qrcode");
        ReflectionTestUtils.setField(target, "qrcodeService", qr);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(ICmsRegistrationService.class);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        registrationService = (ICmsRegistrationService) proxy.getProxy();
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schemaCreated) {
            admin.execute("drop database " + mysql.databaseName());
            assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?", Integer.class, mysql.databaseName()));
        }
    }

    @Test
    void successfulPaymentQueuesOnlyAfterCommitAndBothIndependentWritesReallyPersist() throws Exception {
        payInsideTransaction(false);
        assertEquals(2, queued.size(), "QR and signup IM must both be queued after commit");
        assertPaid();
        assertEquals(0, messages());
        assertEquals(1, intentCount());
        assertEquals("PENDING", intentState());
        assertNull(db.queryForObject("select qrcode_url from registration_fact where id=99", String.class));
        // A caller transaction is deliberately rolled back around queue execution: REQUIRES_NEW must
        // commit the QR/IM writes and must not accidentally join this transaction.
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            unrelatedConnection = DataSourceUtils.getConnection(source);
            queued.forEach(Runnable::run);
            throw new IllegalStateException("fixture unrelated rollback");
        }));
        assertPaid();
        assertEquals("fixture-qrcode", db.queryForObject("select qrcode_url from registration_fact where id=99", String.class));
        assertTrue(db.queryForObject("select verification_code from registration_fact where id=99", String.class).startsWith("VER"));
        assertNoUnexpectedImFailure();
        assertEquals(1, messages());
        assertEquals(1, db.queryForObject("select unread_count from im_conversation_member where member_id=9", Integer.class));
        assertEquals(88L, new ObjectMapper().readTree(db.queryForObject("select extra_json from im_message", String.class)).path("topicId").asLong());
        assertEquals("报名成功", db.queryForObject("select content from im_message", String.class));
        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));
        assertEquals(2, queued.size(), "replaying an already completed payment must not enqueue again");
        verifyNoInteractions(alarm);
    }

    @Test
    void rollbackOfThePaymentTransactionDoesNotQueueQrOrSendNotice() {
        assertThrows(IllegalStateException.class, () -> payInsideTransaction(true));
        assertTrue(queued.isEmpty());
        assertEquals(1, db.queryForObject("select payment_status from registration_fact where id=99", Integer.class));
        assertEquals(1, db.queryForObject("select registration_status from registration_fact where id=99", Integer.class));
        assertEquals(0, messages());
        assertEquals(0, intentCount());
        verifyNoInteractions(executor, alarm);
    }

    @Test
    void imFailureAfterActualInsertRollsBackOnlyNoticeAndKeepsCommittedPaymentAndQr() {
        failIm.set(true);
        payInsideTransaction(false);
        queued.forEach(Runnable::run);
        assertPaid();
        assertEquals("fixture-qrcode", db.queryForObject("select qrcode_url from registration_fact where id=99", String.class));
        assertEquals(0, messages());
        assertEquals(0, db.queryForObject("select count(*) from im_conversation", Integer.class));
        assertEquals("PENDING", intentState());
        failIm.set(false);
        freshRecoveryTask().deliverPending();
        assertEquals(1, messages());
        assertEquals("DELIVERED", intentState());
        verify(alarm).alarm(eq("业务通知未送达"), contains("registration-signup"));
    }

    @Test
    void qrFailureAfterActualUpdateRollsBackOnlyQrAndDoesNotLoseSignupNotice() {
        failQr.set(true);
        payInsideTransaction(false);
        queued.forEach(Runnable::run);
        assertPaid();
        assertNull(db.queryForObject("select qrcode_url from registration_fact where id=99", String.class));
        assertNoUnexpectedImFailure();
        assertEquals(1, messages());
        verify(alarm).alarm(eq("业务通知未送达"), contains("registration-qrcode"));
    }

    @Test
    void rejectedQueueDoesNotTurnCommittedPaymentIntoFailure() {
        when(executor.schedule(any(Runnable.class), eq(10L), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new RejectedExecutionException("fixture executor stopped"));
        assertDoesNotThrow(() -> payInsideTransaction(false));
        assertPaid();
        assertTrue(queued.isEmpty());
        assertEquals(0, messages());
        verify(alarm, times(2)).alarm(eq("业务通知未送达"), anyString());
        assertEquals("PENDING", intentState());
        freshRecoveryTask().deliverPending();
        assertEquals(1, messages());
        assertEquals("DELIVERED", intentState());
    }

    @Test
    void historicalHalfStateWithoutIntentDoesNotBackfillSignup() {
        db.update("update registration_fact set payment_status=2,registration_status=1,transaction_id='historical' where id=99");
        transaction.executeWithoutResult(status -> assertEquals("SUCCESS", registrationService.applyConfirmedWechatPaymentWithinTransaction(callback())));
        assertPaid();
        assertEquals(0, intentCount());
        assertEquals(0, messages());
        assertEquals(1, queued.size(), "historical QR wake remains; no invented signup IM intent");
        verify(imTarget, never()).deliverSystemNotification(anyLong());
    }

    @Test
    void historicalHalfStateWithOriginalIntentOnlyWakesThatReceipt() {
        Long original = intents.enqueue(9L, ImMessage.MSG_CARD, "报名成功", RegistrationCheckoutServiceImpl.signupNoticeExtra(88L), "registration-signup:99");
        db.update("update registration_fact set payment_status=2,registration_status=1,transaction_id='historical' where id=99");
        transaction.executeWithoutResult(status -> assertEquals("SUCCESS", registrationService.applyConfirmedWechatPaymentWithinTransaction(callback())));
        assertEquals(original, intentId());
        assertEquals(1, intentCount());
        assertEquals(2, queued.size());
        queued.forEach(Runnable::run);
        assertEquals(1, messages());
        assertEquals("DELIVERED", intentState());
    }

    @Test
    void commitBeforeWakeLossRecoversSameIntentWithFreshTaskAndLateWakeDoesNotDuplicate() {
        payInsideTransaction(false);
        Long original = intentId();
        Runnable lateImWake = queued.get(1);
        queued.clear(); // model process/executor losing in-memory wake, not an actual process kill
        freshRecoveryTask().deliverPending();
        assertEquals(original, intentId());
        assertEquals(1, messages());
        lateImWake.run();
        assertEquals(1, messages());
        assertEquals(1, db.queryForObject("select unread_count from im_conversation_member where member_id=9", Integer.class));
        verify(imTarget, never()).sendSystem(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void lostPaymentCommitResponseReplaysWithoutAnotherIntentOrWake() {
        assertThrows(IllegalStateException.class, () -> {
            payInsideTransaction(false); // original payment+intent transaction has actually returned after commit
            throw new IllegalStateException("fixture payment acknowledgement lost");
        });
        Long original = intentId(); queued.clear();
        assertEquals("SUCCESS", registrationService.handleWechatPayCallback(callback()));
        assertPaid();
        assertEquals(original, intentId());
        assertEquals(1, intentCount());
        assertTrue(queued.isEmpty(), "a replay is not a fresh winner and cannot enqueue another wake");
        freshRecoveryTask().deliverPending();
        assertEquals(1, messages());
    }

    @Test
    void twoRecoveryWorkersProduceOneMessageAndOneUnread() throws Exception {
        payInsideTransaction(false); queued.clear();
        Long original = intentId();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Long> deliver = () -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return imService.deliverSystemNotification(original); };
            Future<Long> first = workers.submit(deliver), second = workers.submit(deliver);
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, messages());
            assertEquals(1, db.queryForObject("select unread_count from im_conversation_member where member_id=9", Integer.class));
            assertEquals("DELIVERED", intentState());
        } finally { start.countDown(); workers.shutdownNow(); }
    }

    @Test
    void lostCommitAcknowledgementReadsOriginalReceiptRatherThanSendingAgain() {
        payInsideTransaction(false); queued.clear();
        Long original = intentId();
        IImService lostResponse = mock(IImService.class);
        when(lostResponse.deliverSystemNotification(original)).thenAnswer(call -> {
            imService.deliverSystemNotification(original); // proxy commits before transport/client acknowledgement is lost
            throw new IllegalStateException("fixture lost committed response");
        });
        assertThrows(IllegalStateException.class, () -> lostResponse.deliverSystemNotification(original));
        assertEquals("DELIVERED", intentState());
        Long message = db.queryForObject("select message_id from im_notification_intent where id=?", Long.class, original);
        assertEquals(message, imService.deliverSystemNotification(original));
        freshRecoveryTask().deliverPending();
        assertEquals(1, messages());
        assertEquals(1, db.queryForObject("select unread_count from im_conversation_member where member_id=9", Integer.class));
    }

    @Test
    void sameEventDifferentPayloadIsRejectedWithoutReplacingOriginal() {
        payInsideTransaction(false); queued.clear();
        Long original = intentId();
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> intents.enqueue(19L, ImMessage.MSG_CARD, "changed", "{}", "registration-signup:99"));
        assertEquals(original, intentId());
        assertEquals(1, intentCount());
        assertEquals(9L, db.queryForObject("select recipient from im_notification_intent", Long.class));
        assertEquals(RegistrationCheckoutServiceImpl.signupNoticeExtra(88L), db.queryForObject("select extra_json from im_notification_intent", String.class));
        assertEquals(0, messages());
        freshRecoveryTask().deliverPending();
        assertEquals(1, messages());
    }

    @Test
    void liveRecipientAndTopicChangesDoNotRebuildFrozenDelivery() throws Exception {
        payInsideTransaction(false); queued.clear();
        db.update("update registration_fact set member_id=19,topic_id=888 where id=99");
        freshRecoveryTask().deliverPending();
        assertEquals(9L, db.queryForObject("select receiver_id from im_message", Long.class));
        assertEquals(88L, new ObjectMapper().readTree(db.queryForObject("select extra_json from im_message", String.class)).path("topicId").asLong());
        assertEquals(1, messages());
    }

    @Test
    void intentWriteFailureRollsBackPaymentAndIntentWithNoWake() {
        ISystemNotificationIntentService failed = mock(ISystemNotificationIntentService.class, org.mockito.AdditionalAnswers.delegatesTo(intents));
        doAnswer(call -> {
            intents.enqueue(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4));
            throw new IllegalStateException("fixture intent failure after insert");
        }).when(failed).enqueue(anyLong(), anyInt(), anyString(), anyString(), anyString());
        ReflectionTestUtils.setField(registrationTarget, "systemNotificationIntents", failed);
        assertThrows(RuntimeException.class, () -> payInsideTransaction(false));
        assertEquals(1, db.queryForObject("select payment_status from registration_fact where id=99", Integer.class));
        assertEquals(1, db.queryForObject("select registration_status from registration_fact where id=99", Integer.class));
        assertEquals(0, intentCount());
        assertEquals(0, messages());
        assertTrue(queued.isEmpty());
    }

    @Test
    void concurrentPaymentClaimLoserDoesNotCreateAnotherIntentOrWake() throws Exception {
        CyclicBarrier contenders = new CyclicBarrier(2);
        doAnswer(call -> {
            return db.update("update registration_fact set payment_status=2,registration_status=2,transaction_id=? where id=99 and payment_status=1", (String) call.getArgument(1));
        }).when(registrations).claimPaymentSuccess(eq(99L), anyString(), any());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Callable<String> pay = () -> {
                // Both contenders must be ready before either acquires the original business row lock.
                // The loser cannot rendezvous inside claimPaymentSuccess while the winner holds that lock.
                contenders.await(10, TimeUnit.SECONDS);
                return transaction.execute(status -> registrationService.applyConfirmedWechatPaymentWithinTransaction(callback()));
            };
            Future<String> first = workers.submit(pay), second = workers.submit(pay);
            assertEquals("SUCCESS", first.get(15, TimeUnit.SECONDS));
            assertEquals("SUCCESS", second.get(15, TimeUnit.SECONDS));
            assertEquals(1, intentCount());
            assertEquals(2, queued.size(), "only the winner registers QR+IM wakes");
            assertEquals(0, messages());
            freshRecoveryTask().deliverPending();
            assertEquals(1, messages());
        } finally { workers.shutdownNow(); }
    }

    @Test
    void originalFirstRegistrationIdentityIsKeptWhenSiblingHasSmallerId() {
        db.update("update registration_fact set parent_order_no='PARENT' where id=99");
        db.update("insert into registration_fact values(98,1,1,null,null,null,9,88,'PARENT')");
        transaction.executeWithoutResult(status -> assertEquals("SUCCESS", registrationService.applyConfirmedWechatPaymentWithinTransaction(callback())));
        assertEquals(1, intentCount());
        assertEquals("registration-signup:99", db.queryForObject("select event_key from im_notification_intent", String.class));
        assertEquals(3, queued.size(), "two original QR wakes but only one signup card");
        assertEquals(2, db.queryForObject("select count(*) from registration_fact where payment_status=2", Integer.class));
        freshRecoveryTask().deliverPending();
        assertEquals(1, messages());
    }

    @Test
    void rejectedAmountLeavesPaymentIntentMessageAndWakeUnchanged() {
        Map<String, String> rejected = callback(); rejected.put("total", "6799");
        transaction.executeWithoutResult(status -> assertEquals("FAIL", registrationService.applyConfirmedWechatPaymentWithinTransaction(rejected)));
        assertEquals(1, db.queryForObject("select payment_status from registration_fact where id=99", Integer.class));
        assertEquals(0, intentCount());
        assertEquals(0, messages());
        assertTrue(queued.isEmpty());
        verify(registrations, never()).claimPaymentSuccess(anyLong(), anyString(), any());
    }

    @Test
    void conflictingCompletedPaymentCannotProduceIntentAfterLosingCas() {
        db.update("update registration_fact set payment_status=2,registration_status=2,transaction_id='OTHER' where id=99");
        transaction.executeWithoutResult(status -> assertEquals("FAIL", registrationService.applyConfirmedWechatPaymentWithinTransaction(callback())));
        assertEquals("OTHER", db.queryForObject("select transaction_id from registration_fact where id=99", String.class));
        assertEquals(0, intentCount());
        assertEquals(0, messages());
        assertTrue(queued.isEmpty());
    }

    private void payInsideTransaction(boolean rollback) {
        transaction.execute(status -> {
            // 回调入口不允许在外层未提交事务内领取 inbox;同一支付状态机的事务内入口是它。
            assertEquals("SUCCESS", registrationService.applyConfirmedWechatPaymentWithinTransaction(callback()));
            assertTrue(queued.isEmpty(), "external delivery must not enqueue while payment can still roll back");
            assertEquals(0, messages());
            assertEquals(1, intentCount(), "intent must exist inside the original payment transaction before any wake");
            assertEquals("PENDING", intentState());
            if (rollback) throw new IllegalStateException("fixture payment rollback");
            return null;
        });
    }

    private Map<String, String> callback() {
        Map<String, String> values = new HashMap<>();
        values.put("out_trade_no", "REG99"); values.put("transaction_id", "fixture-tx99");
        values.put("trade_state", "SUCCESS"); values.put("total", "6800");
        var fact = originalPaymentFact();
        var reference = fixturePaymentReference;
        assertEquals(fact, fixturePaymentFacts.requireVerified(reference).facts());
        values.put("verified_fact_id", Long.toString(reference.id())); values.put("verified_fact_hash", reference.hash());
        values.put("wx_out_trade_no", fact.wxOutTradeNo()); values.put("mchid", fact.mchid()); values.put("appid", fact.appid());
        values.put("trade_type", fact.tradeType()); values.put("currency", fact.currency());
        return values;
    }

    private com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact originalPaymentFact() {
        return new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact(
                com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT,
                "fixture-mch", "fixture-app", "REG99", "REG99", "REG99", "fixture-tx99", "fixture-tx99",
                "JSAPI", "CNY", 6800, 6800, java.time.Instant.parse(WechatVerifiedFactTestSupport.SUCCESS_TIME), null, "SUCCESS");
    }

    private CmsRegistration readRegistration() { return readRegistration(99L, false); }

    private CmsRegistration readRegistration(Long id, boolean locked) {
        return db.queryForObject("select * from registration_fact where id=?" + (locked ? " for update" : ""), (row, n) -> {
            CmsRegistration r = new CmsRegistration(); r.setId(row.getLong("id")); r.setRegistrationNo("REG" + r.getId());
            r.setMemberId(row.getLong("member_id")); r.setOwnerType(1L); r.setOwnerId(row.getLong("topic_id"));
            r.setParentOrderNo(row.getString("parent_order_no"));
            r.setWechatPaymentAmount(new BigDecimal("68.00"));
            r.setPaymentStatus(row.getInt("payment_status")); r.setRegistrationStatus(row.getInt("registration_status"));
            r.setTransactionId(row.getString("transaction_id")); r.setQrcodeUrl(row.getString("qrcode_url"));
            return r;
        }, id);
    }

    private int intentCount() { return db.queryForObject("select count(*) from im_notification_intent", Integer.class); }
    private Long intentId() { return db.queryForObject("select id from im_notification_intent where event_key='registration-signup:99'", Long.class); }
    private String intentState() { return db.queryForObject("select status from im_notification_intent where event_key='registration-signup:99'", String.class); }
    private com.chengyinhub.business.task.ImNotificationDeliveryTask freshRecoveryTask() {
        com.chengyinhub.business.task.ImNotificationDeliveryTask task = new com.chengyinhub.business.task.ImNotificationDeliveryTask();
        ReflectionTestUtils.setField(task, "mapper", intentMapper);
        ReflectionTestUtils.setField(task, "imService", imService);
        return task;
    }

    private void assertIndependentTaskTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        if (unrelatedConnection != null) assertNotSame(unrelatedConnection, DataSourceUtils.getConnection(source));
        assertPaid();
    }

    private void assertPaid() {
        assertEquals(2, db.queryForObject("select payment_status from registration_fact where id=99", Integer.class));
        assertEquals(2, db.queryForObject("select registration_status from registration_fact where id=99", Integer.class));
        assertEquals("fixture-tx99", db.queryForObject("select transaction_id from registration_fact where id=99", String.class));
    }

    private int messages() { return db.queryForObject("select count(*) from im_message", Integer.class); }

    private void assertNoUnexpectedImFailure() {
        assertNull(unexpectedImFailure.get(), () -> {
            StringBuilder causes = new StringBuilder("Synthetic MySQL notification fixture failed: ");
            for (Throwable cause = unexpectedImFailure.get(); cause != null; cause = cause.getCause()) {
                causes.append(cause).append("\n");
            }
            return causes.toString();
        });
    }
}
