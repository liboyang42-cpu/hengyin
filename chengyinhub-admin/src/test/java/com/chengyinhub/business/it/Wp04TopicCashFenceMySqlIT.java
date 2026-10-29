package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Original DDL and real head/detail/adjustment XML + real public owners/transactions.
 * Topic/member mapper adapters execute JDBC row locks and balance SQL; notification/debt are doubles.
 * This is G-side serialization/atomicity coverage, NOT callback, refund allocation or late-SUCCESS closure. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Wp04TopicCashFenceMySqlIT {
    private JdbcTemplate admin, db;
    private String createdDatabase;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private CoopSettlementMapper heads;
    private CoopSettlementDetailMapper details;
    private CoopSettlementAdjustmentMapper adjustments;
    private ICoopSettlementClaimService claims;
    private CmsTopicServiceImpl topic;
    private final AtomicReference<CountDownLatch> topicEntered = new AtomicReference<>(new CountDownLatch(0));

    @BeforeAll
    void schemaAndOriginalMappers() throws Exception {
        assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"));
        assertEquals("1", System.getenv("WP04_TOPIC_CASH_DDL_APPROVED"));
        for (String key : List.of("HOST", "PORT", "USER", "PASSWORD", "DB_SUFFIX")) {
            assertNotNull(System.getenv("CHENGYIN_MYBATIS_IT_" + key), "Explicit scratch connection required: " + key);
        }
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()));
        assertTrue(MybatisItDatabase.port().matches("[0-9]+"));
        assertTrue(Integer.parseInt(MybatisItDatabase.port()) > 0 && Integer.parseInt(MybatisItDatabase.port()) <= 65535);
        assertFalse(MybatisItDatabase.user().isBlank());
        assertTrue(System.getenv("CHENGYIN_MYBATIS_IT_DB_SUFFIX").matches("[A-Za-z0-9_]*"));
        String database = MybatisItDatabase.databaseName("g_fence_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        assertTrue(database.matches("chengyin_mybatis_g_fence_[a-f0-9]{12}[A-Za-z0-9_]*") && database.length() <= 64);
        String prefix = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port() + "/";
        String params = "?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        admin = new JdbcTemplate(new DriverManagerDataSource(prefix + params, MybatisItDatabase.user(), MybatisItDatabase.password()));
        assertTrue(admin.queryForObject("SELECT VERSION()", String.class).startsWith("8."));
        admin.execute("CREATE DATABASE `" + database + "`");
        createdDatabase = database;
        DriverManagerDataSource source = new DriverManagerDataSource(prefix + database + params, MybatisItDatabase.user(), MybatisItDatabase.password());
        db = new JdbcTemplate(source);
        assertEquals(createdDatabase, db.queryForObject("SELECT DATABASE()", String.class));
        P0AcceptanceSchema.baseTables(db, "cms_topic", "coop_settlement", "coop_settlement_detail", "coop_settlement_adjustment",
                "coop_order", "coop_invite", "ums_member", "ums_member_balance_detail");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CoopSettlementMapper.xml"),
                new ClassPathResource("mapper/business/CoopSettlementDetailMapper.xml"),
                new ClassPathResource("mapper/business/CoopSettlementAdjustmentMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        heads = sql.getMapper(CoopSettlementMapper.class); details = sql.getMapper(CoopSettlementDetailMapper.class);
        adjustments = sql.getMapper(CoopSettlementAdjustmentMapper.class);
        manager = new DataSourceTransactionManager(source); tx = new TransactionTemplate(manager); tx.setTimeout(10);
    }

    @AfterAll
    void dropOnlyThisCreatedSchema() {
        if (createdDatabase != null) {
            assertTrue(createdDatabase.matches("chengyin_mybatis_g_fence_[a-f0-9]{12}[A-Za-z0-9_]*"));
            admin.execute("DROP DATABASE `" + createdDatabase + "`");
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, createdDatabase));
            createdDatabase = null; // DriverManagerDataSource has no pool; JDBC operations close their connections.
        }
    }

    @BeforeEach
    void originalHeadAndOwners() {
        topicEntered.set(new CountDownLatch(0));
        for (String table : List.of("coop_settlement_adjustment", "coop_settlement_detail", "coop_settlement", "ums_member_balance_detail", "ums_member", "cms_topic")) {
            db.update("DELETE FROM " + table); // Only this invocation's UUID scratch schema.
        }
        db.update("INSERT INTO cms_topic(id,name,product_type,member_id,is_settlement) VALUES(11,'G topic fence fixture',1,7,1)");
        db.update("INSERT INTO ums_member(id,balance) VALUES(8,100.00)");
        db.update("INSERT INTO coop_settlement(id,topic_id,merchant_id,amount,status,payable_time,del_flag) VALUES(5,11,8,30.00,0,DATE_SUB(NOW(),INTERVAL 1 DAY),0)");
        db.update("INSERT INTO coop_settlement_detail(id,settlement_id,claim_id,registration_id,origin_amount,create_time) VALUES(1,5,31,1,30.00,NOW())");
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicByIdForUpdate(11L)).thenAnswer(call -> {
            topicEntered.get().countDown(); // Before actual JDBC: the worker may now be waiting on this row lock.
            return db.queryForObject("SELECT id FROM cms_topic WHERE id=? FOR UPDATE", (rs, n) -> {
                CmsTopic row = new CmsTopic(); row.setId(rs.getLong(1)); return row;
            }, 11L);
        });
        CoopSettlementClaimServiceImpl owner = new CoopSettlementClaimServiceImpl(null, null, null, null, heads, details, adjustments);
        ReflectionTestUtils.setField(owner, "topicMapper", topics);
        ProxyFactory proxy = new ProxyFactory(owner);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        claims = (ICoopSettlementClaimService) proxy.getProxy();
        topic = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(topic, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(topic, "transactionManager", manager);
        ReflectionTestUtils.setField(topic, "coopSettlementMapper", heads);
        ReflectionTestUtils.setField(topic, "coopSettlementAdjustmentMapper", adjustments);
        // Original five-case fixture has no original-component producer; new protocol is tested separately.
        ReflectionTestUtils.setField(topic, "coopSettlementOriginalMapper", mock(CoopSettlementOriginalMapper.class));
        CoopComplaintMapper complaints = mock(CoopComplaintMapper.class);
        when(complaints.countOpenByTopicId(11L)).thenReturn(0);
        ReflectionTestUtils.setField(topic, "coopComplaintMapper", complaints);
        ReflectionTestUtils.setField(topic, "notifyService", mock(INotifyService.class));
        ReflectionTestUtils.setField(topic, "settlementDebtLedger", mock(SettlementDebtLedger.class));
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        when(members.lockMemberById(8L)).thenAnswer(call -> db.queryForObject("SELECT id FROM ums_member WHERE id=8 FOR UPDATE", Long.class));
        when(members.selectUmsMemberById(8L)).thenAnswer(call -> {
            UmsMember row = new UmsMember(); row.setId(8L);
            row.setBalance(db.queryForObject("SELECT balance FROM ums_member WHERE id=8", BigDecimal.class)); return row;
        });
        when(members.addBalance(eq(8L), any())).thenAnswer(call -> {
            BigDecimal amount = call.getArgument(1);
            return db.update("UPDATE ums_member SET balance=balance+? WHERE id=8", amount);
        });
        ReflectionTestUtils.setField(topic, "umsMemberMapper", members);
        IUmsMemberBalanceDetailService ledger = mock(IUmsMemberBalanceDetailService.class);
        when(ledger.insertUmsMemberBalanceDetail(any())).thenAnswer(call -> {
            UmsMemberBalanceDetail row = call.getArgument(0);
            return db.update("INSERT INTO ums_member_balance_detail(member_id,current_balance,after_balance,change_balance,change_reason,event_type,event_id,change_type,del_flag,create_time) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    row.getMemberId(), row.getCurrentBalance(), row.getAfterBalance(), row.getChangeBalance(), row.getChangeReason(), row.getEventType(), row.getEventId(), row.getChangeType(), row.getDelFlag(), row.getCreateTime());
        });
        ReflectionTestUtils.setField(topic, "umsMemberBalanceDetailService", ledger);
    }

    @Test
    void payoutWaitingForTopicHasNotLockedHeadAndCreditsExactlyOnceAfterRelease() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Integer>> pending = new AtomicReference<>();
        try {
            tx.execute(status -> {
                lockTopic(); topicEntered.set(new CountDownLatch(1));
                pending.set(worker.submit(topic::payoutDueCoopSettlements));
                awaitTopicAndAssertBlocked(pending.get());
                assertEquals(5L, db.queryForObject("SELECT id FROM coop_settlement WHERE id=5 FOR UPDATE NOWAIT", Long.class));
                return null;
            });
            assertEquals(1, pending.get().get(5, TimeUnit.SECONDS));
            assertMoney("130.00", 1); assertEquals(0, topic.payoutDueCoopSettlements()); assertMoney("130.00", 1);
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test
    void adjustmentWaitingForTopicHasNotLockedHeadOrChangedOrigin() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> pending = new AtomicReference<>();
        try {
            tx.execute(status -> {
                lockTopic(); topicEntered.set(new CountDownLatch(1));
                pending.set(worker.submit(() -> claims.appendExecutedAdjustment(31L, "ADJUST:31:1", new BigDecimal("-2.00"))));
                awaitTopicAndAssertBlocked(pending.get());
                assertEquals(5L, db.queryForObject("SELECT id FROM coop_settlement WHERE id=5 FOR UPDATE NOWAIT", Long.class));
                assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM coop_settlement_adjustment", Integer.class));
                return null;
            });
            pending.get().get(5, TimeUnit.SECONDS);
            assertEquals(0, new BigDecimal("-2.00").compareTo(adjustments.sumExecutedBySettlement(5L)));
            assertOriginUnchanged(); assertMoney("100.00", 0);
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test
    void committedOriginalAdjustmentIsReadBeforePayoutWithoutChangingItsAlgorithm() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Integer>> pending = new AtomicReference<>();
        try {
            tx.execute(status -> {
                claims.appendExecutedAdjustment(31L, "ADJUST:31:1", new BigDecimal("-2.00"));
                topicEntered.set(new CountDownLatch(1)); pending.set(worker.submit(topic::payoutDueCoopSettlements));
                awaitTopicAndAssertBlocked(pending.get()); return null;
            });
            assertEquals(1, pending.get().get(5, TimeUnit.SECONDS));
            assertMoney("128.00", 1); assertOriginUnchanged();
            assertEquals(0, topic.payoutDueCoopSettlements()); assertMoney("128.00", 1);
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test
    void originalAdjustmentEventReplaysOnceAndChangedPayloadRollsBack() {
        claims.appendExecutedAdjustment(31L, "ADJUST:31:1", new BigDecimal("-2.00"));
        claims.appendExecutedAdjustment(31L, "ADJUST:31:1", new BigDecimal("-2.00"));
        assertThrows(IllegalStateException.class, () -> claims.appendExecutedAdjustment(31L, "ADJUST:31:1", new BigDecimal("-3.00")));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM coop_settlement_adjustment", Integer.class));
        assertEquals(0, new BigDecimal("-2.00").compareTo(adjustments.sumExecutedBySettlement(5L)));
        assertOriginUnchanged(); assertMoney("100.00", 0);
    }

    @Test
    void failedOuterMutationRollsBackAdjustmentAndCanReplayOriginalEvent() {
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            claims.appendExecutedAdjustment(31L, "ADJUST:31:1", new BigDecimal("-2.00"));
            throw new IllegalStateException("abort original transaction");
        }));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM coop_settlement_adjustment", Integer.class));
        assertOriginUnchanged(); assertMoney("100.00", 0);
        claims.appendExecutedAdjustment(31L, "ADJUST:31:1", new BigDecimal("-2.00"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM coop_settlement_adjustment", Integer.class));
    }

    private void lockTopic() { assertEquals(11L, db.queryForObject("SELECT id FROM cms_topic WHERE id=11 FOR UPDATE", Long.class)); }

    private void awaitTopicAndAssertBlocked(Future<?> future) {
        try {
            assertTrue(topicEntered.get().await(3, TimeUnit.SECONDS), "worker must reach the original topic lock before waiting");
            assertThrows(TimeoutException.class, () -> future.get(150, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private void assertOriginUnchanged() {
        assertEquals(0, new BigDecimal("30.00").compareTo(db.queryForObject("SELECT amount FROM coop_settlement WHERE id=5", BigDecimal.class)));
        assertEquals(0, new BigDecimal("30.00").compareTo(db.queryForObject("SELECT origin_amount FROM coop_settlement_detail WHERE claim_id=31", BigDecimal.class)));
    }

    private void assertMoney(String balance, int ledgerRows) {
        assertEquals(0, new BigDecimal(balance).compareTo(db.queryForObject("SELECT balance FROM ums_member WHERE id=8", BigDecimal.class)));
        assertEquals(ledgerRows, db.queryForObject("SELECT COUNT(*) FROM ums_member_balance_detail WHERE event_type=5 AND event_id=5", Integer.class));
    }
}
