package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real service, mapper XML, InnoDB transactions and independent committed readback. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoopComplaintCloseMybatisIntegrationTest {
    private String database;
    private JdbcTemplate admin, db;
    private SqlSessionTemplate session;
    private DataSourceTransactionManager transactions;
    private CoopComplaintMapper mapper;
    private IUmsMemberService members;
    private IUmsMemberBalanceDetailService details;
    private ICoopComplaintService service;

    @BeforeAll void database() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("f18");
        assertTrue(config.adminUrl().startsWith("jdbc:mysql://127.0.0.1:")
                || config.adminUrl().startsWith("jdbc:mysql://localhost:"), "scratch test is localhost only");
        database = "chengyin_f18_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        admin.execute("CREATE DATABASE " + database);
        DriverManagerDataSource source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"),
                config.user(), config.password());
        db = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CoopComplaintMapper.xml"),
                new ClassPathResource("mapper/business/UmsMemberMapper.xml"),
                new ClassPathResource("mapper/business/UmsMemberBalanceDetailMapper.xml"));
        session = new SqlSessionTemplate(factory.getObject());
        db.execute("CREATE TABLE coop_complaint(id BIGINT PRIMARY KEY, topic_id BIGINT, registration_id BIGINT,"
                + "reporter_member_id BIGINT, respondent_type VARCHAR(30), respondent_member_id BIGINT, status INT,"
                + "advance_amount DECIMAL(14,2), fault_ratio INT, deduct_amount DECIMAL(14,2), reason TEXT,"
                + "handle_note TEXT, handler_id BIGINT, create_time DATETIME, update_time DATETIME, del_flag INT) ENGINE=InnoDB");
        db.execute("CREATE TABLE ums_member(id BIGINT PRIMARY KEY, balance DECIMAL(14,2),"
                + "user_type BIGINT, role VARCHAR(30), phone VARCHAR(30), password VARCHAR(100), level_id BIGINT,"
                + "name VARCHAR(30), wechat VARCHAR(30), status INT, invite_user_id BIGINT, login_ip VARCHAR(30),"
                + "login_time DATETIME, nickname VARCHAR(30), avatar TEXT, sex INT, wx_open_id VARCHAR(100),"
                + "app_open_id VARCHAR(100), union_id VARCHAR(100), apple_id VARCHAR(100), area_id BIGINT, birthday DATE,"
                + "mark TEXT, tag_ids TEXT, case_pics TEXT, case_introducton TEXT, website TEXT, work_address TEXT,"
                + "introduction TEXT, point BIGINT, del_flag INT, create_by VARCHAR(30), create_time DATETIME,"
                + "update_by VARCHAR(30), update_time DATETIME) ENGINE=InnoDB");
        db.execute("CREATE TABLE ums_member_balance_detail(id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT,"
                + "current_balance DECIMAL(14,2), after_balance DECIMAL(14,2), change_balance DECIMAL(14,2),"
                + "change_reason TEXT, event_type BIGINT, event_id BIGINT, change_type BIGINT, del_flag BIGINT,"
                + "create_by VARCHAR(30), create_time DATETIME, update_by VARCHAR(30), update_time DATETIME) ENGINE=InnoDB");
    }

    @AfterAll void cleanup() { if (database != null && admin != null) admin.execute("DROP DATABASE " + database); }

    @BeforeEach void setup() {
        db.update("DELETE FROM ums_member_balance_detail");
        db.update("DELETE FROM coop_complaint");
        db.update("DELETE FROM ums_member");
        db.update("INSERT INTO ums_member(id,balance) VALUES(100,0),(200,100)");
        db.update("INSERT INTO coop_complaint(id,topic_id,reporter_member_id,respondent_member_id,status,del_flag)"
                + " VALUES(1,700,100,200,0,0)");
        mapper = mock(CoopComplaintMapper.class, org.mockito.AdditionalAnswers.delegatesTo(session.getMapper(CoopComplaintMapper.class)));
        UmsMemberServiceImpl memberService = new UmsMemberServiceImpl();
        ReflectionTestUtils.setField(memberService, "umsMemberMapper", session.getMapper(UmsMemberMapper.class));
        members = spy(memberService);
        UmsMemberBalanceDetailServiceImpl detailService = new UmsMemberBalanceDetailServiceImpl();
        ReflectionTestUtils.setField(detailService, "umsMemberBalanceDetailMapper", session.getMapper(UmsMemberBalanceDetailMapper.class));
        details = spy(detailService);
        CoopComplaintServiceImpl target = new CoopComplaintServiceImpl();
        ReflectionTestUtils.setField(target, "complaintCloseReporterNotice", mock(com.chengyinhub.business.service.support.ComplaintCloseReporterNotice.class));
        ReflectionTestUtils.setField(target, "committedOfficialNoticeService", mock(com.chengyinhub.business.service.support.CommittedOfficialNoticeService.class));
        ReflectionTestUtils.setField(target, "coopComplaintMapper", mapper);
        ReflectionTestUtils.setField(target, "umsMemberService", members);
        ReflectionTestUtils.setField(target, "umsMemberBalanceDetailService", details);
        // Cooldown policy is independently tested; these tests assert complaint debit/credit transactions.
        ReflectionTestUtils.setField(target, "playerWithdrawalCooldownService", mock(IPlayerWithdrawalCooldownService.class));
        // 扣划不足记欠款的真库行为由 ComplaintPeriodWithdrawalGateMybatisIntegrationTest 覆盖
        ReflectionTestUtils.setField(target, "settlementDebtLedger", mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (ICoopComplaintService) proxy.getProxy();
    }

    private int close() { return service.close(1L, "merchant", 200L, 100, BigDecimal.ONE, "F18", 999L); }
    private BigDecimal balance() { return db.queryForObject("SELECT balance FROM ums_member WHERE id=200", BigDecimal.class); }
    private int count() { return db.queryForObject("SELECT count(*) FROM ums_member_balance_detail", Integer.class); }

    @Test void concurrentCloseDebitsOnceAndRetryIsRejected() throws Exception {
        CountDownLatch read = new CountDownLatch(2);
        doAnswer(call -> {
            CoopComplaint result = session.getMapper(CoopComplaintMapper.class).selectCoopComplaintById(1L);
            read.countDown();
            assertTrue(read.await(10, TimeUnit.SECONDS), "both callers read the open ticket");
            return result;
        }).when(mapper).selectCoopComplaintById(1L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> request = () -> { try { close(); return true; } catch (IllegalStateException rejected) { return false; } };
            Future<Boolean> a = pool.submit(request), b = pool.submit(request);
            int successes = (a.get(15, TimeUnit.SECONDS) ? 1 : 0) + (b.get(15, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(new BigDecimal("99.00"), balance(), "one ticket must debit exactly one yuan");
            assertEquals(1, successes);
            assertEquals(1, count());
            assertEquals(new BigDecimal("100.00"), db.queryForObject("SELECT current_balance FROM ums_member_balance_detail", BigDecimal.class));
            assertEquals(new BigDecimal("99.00"), db.queryForObject("SELECT after_balance FROM ums_member_balance_detail", BigDecimal.class));
            assertEquals(3, db.queryForObject("SELECT status FROM coop_complaint", Integer.class));
            assertThrows(IllegalStateException.class, this::close);
            assertEquals(1, count());
        } finally { pool.shutdownNow(); }
    }
    @Test void advanceWinsAgainstStaleCloseThenFreshCloseSucceeds() throws Exception {
        compete(true);
        assertEquals(1, db.queryForObject("SELECT status FROM coop_complaint", Integer.class));
        assertEquals(new BigDecimal("5.00"), db.queryForObject("SELECT balance FROM ums_member WHERE id=100", BigDecimal.class));
        assertEquals(new BigDecimal("100.00"), balance());
        assertEquals(1, count());
        close();
        assertEquals(new BigDecimal("99.00"), balance());
        assertEquals(2, count());
        assertThrows(IllegalStateException.class, () -> service.advance(1L, BigDecimal.ONE, 999L));
    }

    @Test void closeWinsAndStaleAdvanceCannotPayReporter() throws Exception {
        compete(false);
        assertEquals(new BigDecimal("99.00"), balance());
        assertEquals(new BigDecimal("0.00"), db.queryForObject("SELECT balance FROM ums_member WHERE id=100", BigDecimal.class));
        assertEquals(1, count());
        assertEquals(3, db.queryForObject("SELECT status FROM coop_complaint", Integer.class));
    }

    private void compete(boolean advanceWins) throws Exception {
        CountDownLatch bothRead = new CountDownLatch(2), winnerCommitted = new CountDownLatch(1);
        doAnswer(call -> {
            CoopComplaint result = session.getMapper(CoopComplaintMapper.class).selectCoopComplaintById(1L);
            bothRead.countDown();
            assertTrue(bothRead.await(10, TimeUnit.SECONDS));
            if (Thread.currentThread().getName().equals("f18-loser")) {
                assertTrue(winnerCommitted.await(10, TimeUnit.SECONDS));
            }
            return result;
        }).when(mapper).selectCoopComplaintById(1L);
        ExecutorService winner = Executors.newSingleThreadExecutor(r -> new Thread(r, "f18-winner"));
        ExecutorService loser = Executors.newSingleThreadExecutor(r -> new Thread(r, "f18-loser"));
        try {
            Future<Integer> first = winner.submit(() -> advanceWins ? service.advance(1L, new BigDecimal("5"), 999L) : close());
            Future<Integer> second = loser.submit(() -> advanceWins ? close() : service.advance(1L, new BigDecimal("5"), 999L));
            assertEquals(1, first.get(15, TimeUnit.SECONDS));
            winnerCommitted.countDown();
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> second.get(15, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof IllegalStateException, rejected.toString());
        } finally { winnerCommitted.countDown(); winner.shutdownNow(); loser.shutdownNow(); }
    }

    @Test void insufficientBalanceKeepsExistingPartialDeductionRule() {
        db.update("UPDATE ums_member SET balance=0.30 WHERE id=200");
        close();
        assertEquals(new BigDecimal("0.00"), balance());
        assertEquals(new BigDecimal("0.30"), db.queryForObject("SELECT deduct_amount FROM coop_complaint", BigDecimal.class));
        assertEquals(new BigDecimal("-0.30"), db.queryForObject("SELECT change_balance FROM ums_member_balance_detail", BigDecimal.class));
        assertEquals(1, count());
    }

    @Test void emptyBalanceClosesWithoutInventingDebit() {
        db.update("UPDATE ums_member SET balance=0 WHERE id=200");
        close();
        assertEquals(0, count());
        assertEquals(new BigDecimal("0.00"), db.queryForObject("SELECT deduct_amount FROM coop_complaint", BigDecimal.class));
        assertEquals(3, db.queryForObject("SELECT status FROM coop_complaint", Integer.class));
    }

    @Test void debitFailureRollsBackClaimAndAllowsRetry() {
        db.execute("CREATE TRIGGER f18_fail_debit BEFORE UPDATE ON ums_member FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected debit failure'");
        try { assertThrows(RuntimeException.class, this::close); }
        finally { db.execute("DROP TRIGGER f18_fail_debit"); }
        assertOpenAndUntouched();
        close();
        assertEquals(new BigDecimal("99.00"), balance());
        assertEquals(1, count());
    }

    @Test void ledgerFailureRollsBackDebitAndClaim() {
        db.execute("CREATE TRIGGER f18_fail_ledger BEFORE INSERT ON ums_member_balance_detail FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected ledger failure'");
        try { assertThrows(RuntimeException.class, this::close); }
        finally { db.execute("DROP TRIGGER f18_fail_ledger"); }
        assertOpenAndUntouched();
        close();
        assertEquals(1, count());
    }

    @Test void finalResultFailureRollsBackDebitLedgerAndClaim() {
        doThrow(new IllegalStateException("injected final result failure")).when(mapper).updateCoopComplaint(any());
        assertThrows(IllegalStateException.class, this::close);
        assertOpenAndUntouched();
    }

    private void assertOpenAndUntouched() {
        assertEquals(new BigDecimal("100.00"), balance());
        assertEquals(0, count());
        assertEquals(0, db.queryForObject("SELECT status FROM coop_complaint", Integer.class));
        assertNull(db.queryForObject("SELECT deduct_amount FROM coop_complaint", BigDecimal.class));
    }

}
