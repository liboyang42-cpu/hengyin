package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.WxPaymentCallbackOutbox;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.WechatVerifiedPaymentFactMapper;
import com.chengyinhub.business.mapper.WxPaymentCallbackOutboxMapper;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.ICoopSettlementClaimService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.WechatPaymentOrderOwner;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.JdbcWechatPaymentOrderOwner;
import com.chengyinhub.business.service.impl.WechatPaymentIntentStore;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import com.chengyinhub.business.service.impl.WxPaymentCallbackOutboxServiceImpl;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** Real A CAS/B owner/attempt/fact SQL. No provider calls; evidence is an explicit typed fixture.
 * Expected to fail the serialization assertion on B13/df72 until B's fact writer joins the same mutex.
 * The topic projection is a narrow real-row JDBC adapter; full topic XML is covered by A's existing IT.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class PaymentSuccessCloseSerializationMySqlIT {
    private JdbcTemplate admin, db;
    private DriverManagerDataSource source;
    private String schema;
    private boolean created;
    private DataSourceTransactionManager manager;
    private CmsRegistrationMapper registrations;
    private CmsActivityMapper activities;
    private WechatVerifiedPaymentFactMapper realFacts;
    private WxPaymentCallbackOutboxMapper recoveryOutbox;
    private JdbcWechatPaymentOrderOwner realOwner;
    private static final String MCH = "mch-original", HASH = "a".repeat(64);

    @BeforeAll void database() throws Exception {
        assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"));
        assertEquals("1", System.getenv("B_PAYMENT_INTENT_DDL_APPROVED"));
        assertEquals("1", System.getenv("B_PAYMENT_FACT_DDL_APPROVED"));
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("success_close_serialization");
        assertTrue(List.of("127.0.0.1", "localhost").contains(System.getenv("CHENGYIN_TEST_MYSQL_HOST")));
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        schema = "architecture_a_success_close_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + schema + "`"); created = true;
        source = new DriverManagerDataSource(config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + schema + "?"),
                config.user(), config.password());
        db = new JdbcTemplate(source);
        assertEquals(schema, db.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(db.queryForObject("SELECT VERSION()", String.class)));
        manager = new DataSourceTransactionManager(source); manager.setDefaultTimeout(10);
        db.setQueryTimeout(10);
        P0AcceptanceSchema.baseTables(db, "cms_topic", "cms_activity", "cms_registration");
        db.execute("ALTER TABLE cms_registration ADD COLUMN first_successful_scan_at DATETIME(3)");
        P0AcceptanceSchema.migrations(source, "migration_20261023_wx_pay_order_intent.sql",
                "migration_wx_payment_callback_inbox_outbox_20260725.sql", "migration_20261024_wx_verified_payment_fact.sql",
                "migration_20261024_wx_success_fact_business_index.sql");
        var sql = P0AcceptanceSchema.sql(source, "CmsRegistrationMapper", "CmsActivityMapper", "WechatVerifiedPaymentFactMapper", "WxPaymentCallbackOutboxMapper");
        registrations = sql.getMapper(CmsRegistrationMapper.class);
        activities = sql.getMapper(CmsActivityMapper.class);
        realFacts = sql.getMapper(WechatVerifiedPaymentFactMapper.class);
        recoveryOutbox = sql.getMapper(WxPaymentCallbackOutboxMapper.class);
        realOwner = new JdbcWechatPaymentOrderOwner();
        ReflectionTestUtils.setField(realOwner, "paymentIntentJdbc", db);
        db.update("INSERT INTO cms_topic(id,member_id) VALUES(7,70)");
        db.update("INSERT INTO cms_activity(id,member_id,topic_id) VALUES(5,70,NULL)");
        for (long id : new long[]{1, 2}) {
            db.update("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,member_id,"
                    + "ticket_id,point_used,points_returned,wechat_payment_amount,payment_status,registration_status)"
                    + " VALUES(?,?,?,?,11,NULL,0,0,10.00,1,1)", id, "REG" + id, id == 1 ? 2 : 1, id == 1 ? 5 : 7);
            db.update("INSERT INTO wx_pay_order_intent(mchid,out_trade_no,business_out_trade_no,biz_type,biz_id,attempt_no,"
                    + "appid,trade_type,currency,total_fen,payer_openid,request_description,notify_url,request_hash,closed_confirmed_at)"
                    + " VALUES(?,?,?,'REGISTRATION',?,1,'wx-original','JSAPI','CNY',1000,'fixture-openid','fixture','https://example.invalid/fixture-only',?,NOW(6))",
                    MCH, "REG" + id, "REG" + id, id, HASH);
        }
    }

    private <T> T transactional(T target) {
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        @SuppressWarnings("unchecked") T result = (T) proxy.getProxy(); return result;
    }

    @Test void factPublicationAndOriginalCloseUseOneBusinessMutexInBothCommitOrders() throws Exception {
        CountDownLatch absenceObserved = new CountDownLatch(1), allowClose = new CountDownLatch(1);
        CountDownLatch writerReachedBoundary = new CountDownLatch(1);
        AtomicReference<Thread> writerThread = new AtomicReference<>();
        AtomicBoolean writerRequestedOriginalLock = new AtomicBoolean();
        AtomicInteger writerInserts = new AtomicInteger();
        WechatPaymentOrderOwner owner = businessNo -> {
            if (Thread.currentThread() == writerThread.get()) {
                assertEquals("REG1", businessNo);
                writerRequestedOriginalLock.set(true); writerReachedBoundary.countDown();
            }
            return realOwner.lock(businessNo); // Actual SELECT ... FOR UPDATE on the original domain row.
        };
        var factMapper = mock(WechatVerifiedPaymentFactMapper.class, delegatesTo(realFacts));
        doAnswer(call -> {
            if (Thread.currentThread() == writerThread.get()) {
                // Let old B really commit, so the negative measures premature publication in MySQL.
                // With the fixed owner protocol, insertion can only occur after the close has committed.
                writerReachedBoundary.countDown();
                if (writerRequestedOriginalLock.get())
                    assertEquals(3, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
                writerInserts.incrementAndGet();
            }
            return realFacts.insertIgnore(call.getArgument(0));
        }).when(factMapper).insertIgnore(any());
        var factTarget = new WechatVerifiedPaymentFactService();
        // Autowire actual B fields by their existing types. B13 lacks the owner dependency, so setup still
        // succeeds there; its old writer is allowed to commit and fails the premature-publication assertion below.
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("mapper", factMapper);
        beans.registerSingleton("factReadTransactions", manager);
        beans.registerSingleton("businessOwner", owner);
        beans.registerSingleton("alarmService", mock(AlarmService.class));
        var recoveryTarget = new WxPaymentCallbackOutboxServiceImpl();
        ReflectionTestUtils.setField(recoveryTarget, "mapper", recoveryOutbox);
        WxPaymentCallbackOutboxServiceImpl recovery = transactional(recoveryTarget);
        beans.registerSingleton("outboxMapper", recoveryOutbox);
        beans.registerSingleton("wxPaymentCallbackOutboxService", recovery);
        ICoopSettlementClaimService noForcedTopicOwner = mock(ICoopSettlementClaimService.class);
        doThrow(new AssertionError("closed standalone payment must not enter G admission"))
                .when(noForcedTopicOwner).requirePaidHalfStateCashAdmission(anyLong(), any());
        beans.registerSingleton("coopSettlementClaimService", noForcedTopicOwner);
        var injection = new AutowiredAnnotationBeanPostProcessor(); injection.setBeanFactory(beans);
        injection.processInjection(factTarget);
        AtomicReference<WechatVerifiedPaymentFact.Reference> domainRecoveryReference = new AtomicReference<>();
        AtomicInteger domainRecoveryCalls = new AtomicInteger();
        ProxyFactory factProxy = new ProxyFactory(factTarget); factProxy.setProxyTargetClass(true);
        factProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        factProxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            if ("recordClosedBusinessPaymentRecovery".equals(invocation.getMethod().getName())) {
                Object result = invocation.proceed(); // Real B persistence first; observation never replaces it.
                domainRecoveryReference.set((WechatVerifiedPaymentFact.Reference)invocation.getArguments()[0]);
                assertEquals("REGISTRATION", invocation.getArguments()[1]);
                assertEquals(1L, invocation.getArguments()[2]);
                assertEquals("REG1", invocation.getArguments()[3]);
                domainRecoveryCalls.incrementAndGet();
                return result;
            }
            return invocation.proceed();
        });
        WechatVerifiedPaymentFactService facts = (WechatVerifiedPaymentFactService) factProxy.getProxy();
        var intents = new WechatPaymentIntentStore();
        ReflectionTestUtils.setField(intents, "paymentIntentJdbc", db);
        ReflectionTestUtils.setField(intents, "paymentIntentTransactions", manager);
        ReflectionTestUtils.setField(intents, "businessOwner", owner);
        ReflectionTestUtils.setField(intents, "verifiedFacts", facts);
        IWechatPayService gateway = mock(IWechatPayService.class);
        when(gateway.lockClosablePaymentAttemptForUpdate(anyString(), anyString())).thenAnswer(call -> {
            Boolean closable = ReflectionTestUtils.invokeMethod(intents, "lockClosablePaymentAttemptForUpdate",
                    call.getArgument(0, String.class), call.getArgument(1, String.class), MCH);
            return closable;
        });
        var target = new CmsRegistrationServiceImpl();
        var closeMapper = mock(CmsRegistrationMapper.class, delegatesTo(registrations));
        doAnswer(call -> {
            // Pause at the last boundary, after ALL caller guards (including root's new
            // hasVerifiedSuccessBeforeRelease), immediately before the real close CAS SQL.
            assertEquals(1L, call.getArgument(0, Long.class));
            absenceObserved.countDown();
            assertTrue(allowClose.await(5, TimeUnit.SECONDS));
            return registrations.closeExpiredPaying(call.getArgument(0, Long.class), call.getArgument(1, String.class), call.getArgument(2, Integer.class));
        }).when(closeMapper).closeExpiredPaying(anyLong(), anyString(), anyInt());
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", closeMapper);
        ReflectionTestUtils.setField(target, "verifiedPaymentFacts", facts);
        ReflectionTestUtils.setField(target, "cmsActivityMapper", activities);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenAnswer(call -> db.queryForObject(
                "SELECT id,member_id FROM cms_topic WHERE id=7 FOR UPDATE", (row, n) -> {
                    CmsTopic topic = new CmsTopic(); topic.setId(row.getLong("id")); topic.setMemberId(row.getLong("member_id")); return topic;
                }));
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        IPerkLedgerService perks = mock(IPerkLedgerService.class);
        ReflectionTestUtils.setField(target, "perkLedgerService", perks);
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(gateway);
        ReflectionTestUtils.setField(target, "paymentAttemptGateway", provider);
        // Root54 declares this exact field. Any attempted G admission fails; there is no no-op success fallback.
        ReflectionTestUtils.setField(target, "coopSettlementClaimService", noForcedTopicOwner);
        CmsRegistrationServiceImpl payments = transactional(target);
        CmsRegistration observed = registrations.selectCmsRegistrationById(1L);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<Boolean> closing = null;
        Future<WechatVerifiedPaymentFact.Reference> publishing = null;
        try {
            closing = workers.submit(() -> payments.settleClosedPayingOne(observed, "REG1", "ORGANIZER_CANCEL", "serialization-fixture", 4));
            assertTrue(absenceObserved.await(5, TimeUnit.SECONDS));
            publishing = workers.submit(() -> { writerThread.set(Thread.currentThread()); return facts.recordVerified(fact(1)); });
            assertTrue(writerReachedBoundary.await(5, TimeUnit.SECONDS));
            if (!writerRequestedOriginalLock.get()) {
                assertNotNull(publishing.get(10, TimeUnit.SECONDS));
                allowClose.countDown();
                assertTrue(closing.get(10, TimeUnit.SECONDS));
                assertEquals(3, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
                assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class),
                        "SUCCESS committed after the LAST absent-success guard and before the original close CAS");
            }
            assertTrue(writerRequestedOriginalLock.get(), "B writer must request the same original row before publishing SUCCESS");
            assertEquals(0, writerInserts.get());
            assertFalse(publishing.isDone(), "writer must wait behind the close guard/CAS transaction");
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
            // Prove the owner/attempt rows really remain locked; neither worker holds a lock across an SDK call.
            try (Connection observer = source.getConnection()) {
                observer.setAutoCommit(false);
                try {
                    var registrationBusy = assertThrows(java.sql.SQLException.class, () -> {
                        try (PreparedStatement probe = observer.prepareStatement("SELECT id FROM cms_registration WHERE id=1 FOR UPDATE NOWAIT")) {
                            probe.executeQuery().close();
                        }
                    });
                    assertEquals(3572, registrationBusy.getErrorCode());
                    var attemptBusy = assertThrows(java.sql.SQLException.class, () -> {
                        try (PreparedStatement probe = observer.prepareStatement("SELECT out_trade_no FROM wx_pay_order_intent WHERE biz_type='REGISTRATION' AND biz_id=1 FOR UPDATE NOWAIT")) {
                            probe.executeQuery().close();
                        }
                    });
                    assertEquals(3572, attemptBusy.getErrorCode());
                } finally { observer.rollback(); }
            }
            allowClose.countDown();
            assertTrue(closing.get(10, TimeUnit.SECONDS));
            var late = publishing.get(10, TimeUnit.SECONDS);
            assertEquals(1, writerInserts.get());
            assertEquals(fact(1), facts.requireVerified(late).facts(), "late verified SUCCESS survives an already-closed local order");
            assertEquals(3, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
            assertEquals(4, db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=1", Integer.class));
            assertEquals(2, db.queryForObject("SELECT owner_type FROM cms_registration WHERE id=1", Integer.class));
            assertNull(db.queryForObject("SELECT topic_id FROM cms_activity WHERE id=5", Long.class));
            assertClosedSuccessRecoveryIsQueryable(late);
            assertEquals(late, facts.recordVerified(fact(1)), "same original fact replay must not create a second recovery identity");
            assertClosedSuccessRecoveryIsQueryable(late);
            assertTrue(recovery.claimForRetry(10).isEmpty(), "manual late-success decisions must never enter the automatic dispatcher");
            // A's root54 caller patch must classify the same closed original before any G admission.
            // A failed outer business transaction must retain B's independently committed recovery identity.
            Map<String, String> originalFacts = new java.util.HashMap<>(Map.of("out_trade_no", "REG1", "wx_out_trade_no", "REG1",
                    "transaction_id", "TX1", "trade_state", "SUCCESS", "total", "1000", "mchid", MCH,
                    "appid", "wx-original", "trade_type", "JSAPI", "currency", "CNY"));
            originalFacts.put("verified_fact_id", Long.toString(late.id())); originalFacts.put("verified_fact_hash", late.hash());
            int recoveryCallsBeforeInline = domainRecoveryCalls.get();
            assertThrows(IllegalStateException.class, () -> new org.springframework.transaction.support.TransactionTemplate(manager)
                    .executeWithoutResult(tx -> {
                        assertEquals("FAIL", payments.applyConfirmedWechatPaymentWithinTransaction(originalFacts));
                        throw new IllegalStateException("fixture caller rollback after closed-order rejection");
                    }));
            assertEquals(recoveryCallsBeforeInline + 1, domainRecoveryCalls.get());
            assertEquals(late, domainRecoveryReference.get());
            assertClosedSuccessRecoveryIsQueryable(late);
            assertEquals(3, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1", Integer.class));
            assertEquals(4, db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=1", Integer.class));
            verifyNoInteractions(noForcedTopicOwner);
            // Opposite order: publication commits first, so the exact original close must be refused.
            var first = facts.recordVerified(fact(2));
            assertFalse(payments.settleClosedPayingOne(registrations.selectCmsRegistrationById(2L), "REG2", "ORGANIZER_CANCEL", "serialization-fixture", 4));
            assertEquals(fact(2), facts.requireVerified(first).facts());
            assertEquals(1, db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=2", Integer.class));
            assertEquals(1, db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=2", Integer.class));
            assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM wx_pay_order_intent WHERE attempt_no=1 AND request_hash=?", Integer.class, HASH));
            assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_outbox", Integer.class));
            verifyNoInteractions(noForcedTopicOwner);
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_inbox", Integer.class));
            verify(perks).releaseOnRefund(1L, "ORGANIZER_CANCEL");
            verify(perks, never()).releaseOnRefund(eq(2L), anyString());
            verify(gateway).lockClosablePaymentAttemptForUpdate("REG1", "REG1");
            verify(gateway).lockClosablePaymentAttemptForUpdate("REG2", "REG2");
            verifyNoMoreInteractions(gateway);
        } finally {
            allowClose.countDown();
            if (closing != null && !closing.isDone()) closing.cancel(true);
            if (publishing != null && !publishing.isDone()) publishing.cancel(true);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "owned workers must exit before schema cleanup");
        }
    }

    private void assertClosedSuccessRecoveryIsQueryable(WechatVerifiedPaymentFact.Reference original) {
        // Existing durable audit + outbox only. This is a manual decision task, never a fabricated
        // paid/ordinary inbox DONE receipt, a refund instruction, or a G settlement placeholder.
        String key = "PAYMENT_SUCCESS_AFTER_CLOSE:" + original.hash();
        var rows = db.queryForList("SELECT o.biz_type,o.biz_id,o.state,CAST(o.money_affecting AS UNSIGNED) AS money_affecting,f.id,f.fact_hash,"
                + "f.business_out_trade_no,f.wx_out_trade_no,f.transaction_id,f.mchid"
                + " FROM wx_payment_callback_outbox o JOIN wx_verified_payment_fact_rejection a"
                + " ON a.original_fact_id=? AND a.candidate_fact_hash=? AND a.rejection_reason='CLOSED_BUSINESS_SUCCESS'"
                + " JOIN wx_verified_payment_fact f ON f.id=a.original_fact_id AND f.fact_hash=a.candidate_fact_hash"
                + " WHERE o.idempotency_key=?", original.id(), original.hash(), key);
        assertEquals(1, rows.size(), "the exact late success must be discoverable as one retained manual recovery task");
        var row = rows.getFirst();
        assertEquals(WxPaymentCallbackOutbox.BIZ_TYPE_PAYMENT_QUERY, ((Number)row.get("biz_type")).intValue());
        assertEquals(1L, ((Number)row.get("biz_id")).longValue());
        assertEquals(WxPaymentCallbackOutbox.STATE_MANUAL_REQUIRED, ((Number)row.get("state")).intValue());
        assertEquals(1, ((Number)row.get("money_affecting")).intValue());
        assertEquals(original.id(), ((Number)row.get("id")).longValue());
        assertEquals(original.hash(), row.get("fact_hash"));
        assertEquals("REG1", row.get("business_out_trade_no"));
        assertEquals("REG1", row.get("wx_out_trade_no"));
        assertEquals("TX1", row.get("transaction_id"));
        assertEquals(MCH, row.get("mchid"));
    }

    private WechatVerifiedPaymentFact fact(long id) {
        return new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT, MCH, "wx-original", "REG" + id,
                "REG" + id, "REG" + id, "TX" + id, "TX" + id, "JSAPI", "CNY", 1000, 1000,
                Instant.parse("2026-10-24T00:00:00Z"), HASH, "SUCCESS");
    }

    @AfterAll void cleanup() {
        if (created) {
            assertTrue(schema.matches("architecture_a_success_close_[a-f0-9]{32}"));
            admin.execute("DROP DATABASE `" + schema + "`");
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema));
        }
    }
}
