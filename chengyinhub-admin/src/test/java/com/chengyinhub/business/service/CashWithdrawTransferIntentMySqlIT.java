package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.business.it.ItSchema;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.CashWithdrawServiceImpl;
import com.chengyinhub.business.service.support.WithdrawalBalanceAdmissionService;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL, production XML and transaction proxies. Provider calls are doubles; no channel or money is used. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CashWithdrawTransferIntentMySqlIT {
    private long ID = 6L, MEMBER = 41L;
    private static final BigDecimal AMOUNT = new BigDecimal("30.00");
    private String schema;
    private boolean created;
    private JdbcTemplate admin, db;
    private DriverManagerDataSource source;
    private DataSourceTransactionManager transactions;
    private MemberCashWithdrawMapper cash;
    private CashWithdrawTransferIntentMapper intents;
    private UmsMemberMapper members;
    private WithdrawalFrozenAmountResolver frozen;
    private ICashWithdrawService service;
    private IWechatPayService provider;
    private IUmsMemberService memberService;
    private final AtomicInteger refunds = new AtomicInteger();
    private volatile boolean failAfterBalanceWrite;

    @BeforeAll
    void database() throws Exception {
        for (String key : new String[]{"HOST", "PORT", "DATABASE_BASE", "DATABASE_SUFFIX", "USER", "PASSWORD"})
            assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_" + key), "Explicit isolated test configuration required: " + key);
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("cashintent");
        assertEquals("127.0.0.1", System.getenv("CHENGYIN_TEST_MYSQL_HOST"));
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        schema = config.databaseName();
        admin.execute("CREATE DATABASE " + schema);
        created = true;
        source = new DriverManagerDataSource(config.databaseUrl(), config.user(), config.password());
        db = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        db.execute("CREATE TABLE ums_member(id BIGINT PRIMARY KEY, balance DECIMAL(18,2)) ENGINE=InnoDB");
        db.execute("CREATE TABLE merchant_referral_bounty(id BIGINT PRIMARY KEY, inviter_member_id BIGINT, status INT, bounty_amount DECIMAL(18,2), frozen_until DATETIME(3), del_flag CHAR(1), INDEX(inviter_member_id)) ENGINE=InnoDB");
        db.execute("CREATE TABLE player_withdrawal_cooldown(id BIGINT PRIMARY KEY, member_id BIGINT, amount DECIMAL(18,2), cooldown_until DATETIME(3), INDEX(member_id)) ENGINE=InnoDB");
        db.execute("CREATE TABLE member_cash_withdraw(id BIGINT PRIMARY KEY, member_id BIGINT, amount DECIMAL(18,2), status INT, frozen INT, del_flag INT, "
                + "openid VARCHAR(255), out_batch_no VARCHAR(255), out_detail_no VARCHAR(255), transfer_batch_id VARCHAR(255), wechat_detail_id VARCHAR(255), "
                + "fail_reason VARCHAR(1000), channel_error_code VARCHAR(255), create_time DATETIME, finish_time DATETIME, wechat_batch_status VARCHAR(255), "
                + "submit_time DATETIME, last_query_time DATETIME, balance_deduct_id BIGINT, balance_refund_id BIGINT, manual_operator VARCHAR(64), manual_verifier VARCHAR(64), "
                + "manual_note VARCHAR(1000), trace_id VARCHAR(255), audit_by BIGINT, audit_time DATETIME, frozen_reason VARCHAR(255), frozen_by VARCHAR(64), frozen_at DATETIME) ENGINE=InnoDB");
        try (Connection connection = source.getConnection()) {
            ItSchema.applyMigrations(connection, "sql/migration_20261023_cash_withdraw_fund_fact.sql",
                    "sql/migration_20261024_cash_withdraw_transfer_intent.sql",
                    "sql/migration_20261025_cash_withdraw_fund_request_compat.sql");
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/UmsMemberMapper.xml"),
                new ClassPathResource("mapper/business/MemberCashWithdrawMapper.xml"),
                new ClassPathResource("mapper/business/CashWithdrawTransferIntentMapper.xml"),
                new ClassPathResource("mapper/business/MerchantReferralBountyMapper.xml"),
                new ClassPathResource("mapper/business/PlayerWithdrawalCooldownMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        cash = sql.getMapper(MemberCashWithdrawMapper.class);
        intents = sql.getMapper(CashWithdrawTransferIntentMapper.class);
        members = sql.getMapper(UmsMemberMapper.class);
        frozen = new WithdrawalFrozenAmountResolver(null, null);
        ReflectionTestUtils.setField(frozen, "bountyMapper", sql.getMapper(MerchantReferralBountyMapper.class));
        ReflectionTestUtils.setField(frozen, "cooldownMapper", sql.getMapper(PlayerWithdrawalCooldownMapper.class));
    }

    @BeforeEach
    void reset() {
        // Each case retains prior append-only originals; no ledger row is edited/deleted for test cleanup.
        ID++; MEMBER++;
        db.update("INSERT INTO ums_member VALUES(?,100)", MEMBER);
        db.update("INSERT INTO member_cash_withdraw(id,member_id,amount,status,frozen,del_flag,openid,out_batch_no,out_detail_no,create_time) VALUES(?,?,30,0,0,0,'original-openid',?,?,now())", ID, MEMBER, "CW" + ID, "CD" + ID);
        failAfterBalanceWrite = false;
        refunds.set(0);
        provider = mock(IWechatPayService.class);
        memberService = mock(IUmsMemberService.class);
        when(memberService.addBalance(eq(MEMBER), any())).thenAnswer(call -> {
            refunds.incrementAndGet();
            boolean changed = members.addBalance(MEMBER, call.getArgument(1)) == 1;
            if (failAfterBalanceWrite) throw new IllegalStateException("after actual balance UPDATE");
            return changed;
        });
        CashWithdrawServiceImpl target = new CashWithdrawServiceImpl();
        ReflectionTestUtils.setField(target, "mapper", cash);
        ReflectionTestUtils.setField(target, "transferIntents", intents);
        ReflectionTestUtils.setField(target, "transactionManager", transactions);
        ReflectionTestUtils.setField(target, "umsMemberService", memberService);
        ReflectionTestUtils.setField(target, "wechatPayService", provider);
        ReflectionTestUtils.setField(target, "alarmService", mock(AlarmService.class));
        service = transactional(target, ICashWithdrawService.class);
    }

    @AfterAll
    void cleanup() {
        if (created) {
            assertEquals(MysqlIntegrationTestConfig.fromEnvironment("cashintent").databaseName(), schema);
            assertTrue(schema.matches("[A-Za-z0-9_]{1,64}"));
            admin.execute("DROP DATABASE " + schema);
        }
    }

    @Test
    void debitAndImmutableIntentCommitTogetherAndReplayNeverDebitsAgain() throws Exception {
        WithdrawalBalanceAdmissionService admission = admission(intents);
        assertTrue(debit(admission).deducted);
        CashWithdrawTransferIntent first = intents.selectByWithdrawId(ID);
        assertDoesNotThrow(() -> first.requireMatches(cash.selectById(ID)));
        try (Connection connection = source.getConnection()) {
            JdbcTemplate independent = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            money("70", independent.queryForObject("SELECT balance FROM ums_member WHERE id=?", BigDecimal.class, MEMBER));
            assertEquals(1, independent.queryForObject("SELECT COUNT(*) FROM member_cash_withdraw_transfer_intent WHERE withdraw_id=?", Integer.class, ID));
        }
        assertTrue(debit(admission).deducted);
        money("70", balance());
        assertEquals(first.requestHash(), intents.selectByWithdrawId(ID).requestHash());
        assertEquals(1, countIntents());
    }

    @Test
    void intentInsertFailureAfterActualInsertRollsBackBothDebitAndEvidence() {
        CashWithdrawTransferIntentMapper failing = mock(CashWithdrawTransferIntentMapper.class, delegatesTo(intents));
        doAnswer(call -> { intents.insert(call.getArgument(0)); throw new IllegalStateException("after actual evidence INSERT"); })
                .when(failing).insert(any());
        assertThrows(IllegalStateException.class, () -> debit(admission(failing)));
        money("100", balance());
        assertEquals(0, countIntents());
        assertEquals(MemberCashWithdraw.STATUS_PROCESSING, cash.selectById(ID).getStatus());
    }

    @Test
    void missingOriginalTransferIdentityRollsBackDebitAndLeavesNoEvidence() {
        db.update("UPDATE member_cash_withdraw SET openid=NULL WHERE id=?", ID);
        assertThrows(IllegalStateException.class, () -> debit(admission(intents)));
        money("100", balance());
        assertEquals(0, countIntents());
    }

    @Test
    void submitClaimIsExactlyOnceAndCannotUseAChangedHashOrFrozenOriginal() {
        debit(admission(intents));
        CashWithdrawTransferIntent original = intents.selectByWithdrawId(ID);
        assertEquals(0, intents.markSubmitted(ID, "0".repeat(64)));
        db.update("UPDATE member_cash_withdraw SET frozen=1 WHERE id=?", ID);
        assertEquals(0, intents.markSubmitted(ID, original.requestHash()));
        db.update("UPDATE member_cash_withdraw SET frozen=0 WHERE id=?", ID);
        assertEquals(1, intents.markSubmitted(ID, original.requestHash()));
        assertEquals(0, intents.markSubmitted(ID, original.requestHash()));
        assertTrue(intents.selectByWithdrawId(ID).submitted());
        money("70", balance());
    }

    @Test
    void absentCorruptOrUnknownEvidenceCannotEnterManualRefundReview() {
        db.update("UPDATE member_cash_withdraw SET status=3 WHERE id=?", ID);
        assertThrows(IllegalStateException.class, () -> service.manualRefund(ID, "maker", "missing evidence"));
        money("100", balance());
        db.update("UPDATE member_cash_withdraw SET status=0 WHERE id=?", ID);
        submitted();
        db.update("UPDATE member_cash_withdraw SET status=3 WHERE id=?", ID);
        when(provider.queryTransferResult(any(CashWithdrawTransferIntent.class))).thenReturn(Map.of("result", "UNKNOWN"));
        assertThrows(IllegalStateException.class, () -> service.manualRefund(ID, "maker", "unknown"));
        db.update("UPDATE member_cash_withdraw_transfer_intent SET request_hash=? WHERE withdraw_id=?", "0".repeat(64), ID);
        assertThrows(IllegalStateException.class, () -> service.manualRefund(ID, "maker", "corrupt evidence"));
        money("70", balance());
        assertEquals(MemberCashWithdraw.STATUS_FAILED_MANUAL, cash.selectById(ID).getStatus());
        assertEquals(0, refunds.get());
    }

    @Test
    void mismatchedFailureCannotRefundButMatchingFailureStillRefundsExactlyOnce() {
        CashWithdrawTransferIntent intent = submitted();
        Map<String, String> wrong = facts(intent, "FAIL");
        wrong.put("appid", "another-app");
        when(provider.queryTransferResult(any(CashWithdrawTransferIntent.class))).thenReturn(wrong);
        service.requery(ID);
        money("70", balance());
        assertNotEquals(MemberCashWithdraw.STATUS_FAILED_REFUNDED, cash.selectById(ID).getStatus());
        assertEquals(0, refunds.get());
        db.update("UPDATE member_cash_withdraw SET status=0, transfer_batch_id='original-batch', wechat_detail_id='original-detail' WHERE id=?", ID);
        for (String terminal : new String[]{"FAIL", "SUCCESS"}) {
            for (String field : new String[]{"batchId", "detailId"}) {
                Map<String, String> drifted = facts(intent, terminal);
                drifted.put(field, "another-provider-id");
                when(provider.queryTransferResult(any(CashWithdrawTransferIntent.class))).thenReturn(drifted);
                service.requery(ID);
                money("70", balance());
                MemberCashWithdraw original = cash.selectById(ID);
                assertEquals(MemberCashWithdraw.STATUS_PROCESSING, original.getStatus(), terminal + " " + field);
                assertEquals("original-batch", original.getTransferBatchId());
                assertEquals("original-detail", original.getWechatDetailId());
                assertEquals(0, refunds.get(), field + " drift must not credit the original debit");
            }
        }
        when(provider.queryTransferResult(any(CashWithdrawTransferIntent.class))).thenAnswer(call -> {
            // The query read D1, then a separate committed observer records D2 before its response reaches the claim.
            try (Connection independent = source.getConnection()) {
                assertTrue(independent.getAutoCommit(), "the competing receipt must commit independently");
                try (java.sql.PreparedStatement update = independent.prepareStatement(
                        "UPDATE member_cash_withdraw SET wechat_detail_id=? WHERE id=?")) {
                    update.setString(1, "concurrent-detail");
                    update.setLong(2, ID);
                    assertEquals(1, update.executeUpdate());
                }
            }
            return facts(intent, "FAIL");
        });
        service.requery(ID);
        MemberCashWithdraw concurrent = cash.selectById(ID);
        assertEquals("concurrent-detail", concurrent.getWechatDetailId(), "the stale failure claim must not replace D2 with D1");
        assertEquals("original-batch", concurrent.getTransferBatchId());
        assertEquals(MemberCashWithdraw.STATUS_PROCESSING, concurrent.getStatus());
        money("70", balance());
        assertEquals(0, refunds.get());

        db.update("UPDATE member_cash_withdraw SET wechat_detail_id='original-detail' WHERE id=?", ID);
        doReturn(facts(intent, "FAIL")).when(provider).queryTransferResult(any(CashWithdrawTransferIntent.class));
        service.requery(ID);
        money("100", balance());
        assertEquals(MemberCashWithdraw.STATUS_FAILED_REFUNDED, cash.selectById(ID).getStatus());
        assertEquals(1, refunds.get());
    }

    @Test
    void twoConcurrentDistinctCheckersCanCreditTheSameOriginalDebitOnlyOnce() throws Exception {
        CashWithdrawTransferIntent intent = submitted();
        db.update("UPDATE member_cash_withdraw SET status=3 WHERE id=?", ID);
        when(provider.queryTransferResult(any(CashWithdrawTransferIntent.class))).thenReturn(facts(intent, "FAIL"));
        assertTrue(service.manualRefund(ID, "maker", "verified original failure"));
        assertEquals(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW, cash.selectById(ID).getStatus());
        money("70", balance());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> one = workers.submit(() -> checker(start, "checker-one"));
            Future<Boolean> two = workers.submit(() -> checker(start, "checker-two"));
            start.countDown();
            assertEquals(1, (one.get(15, TimeUnit.SECONDS) ? 1 : 0) + (two.get(15, TimeUnit.SECONDS) ? 1 : 0));
        } finally {
            start.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        money("100", balance());
        assertEquals(1, refunds.get());
        MemberCashWithdraw row = cash.selectById(ID);
        assertEquals(MemberCashWithdraw.STATUS_FAILED_REFUNDED, row.getStatus());
        assertEquals("maker", row.getManualOperator());
        assertTrue(Set.of("checker-one", "checker-two").contains(row.getManualVerifier()));
        assertEquals(intent.requestHash(), intents.selectByWithdrawId(ID).requestHash());
    }

    @Test
    void refundExceptionAfterActualBalanceWriteRollsBackCreditAndFinalCas() {
        CashWithdrawTransferIntent intent = submitted();
        db.update("UPDATE member_cash_withdraw SET status=3 WHERE id=?", ID);
        when(provider.queryTransferResult(any(CashWithdrawTransferIntent.class))).thenReturn(facts(intent, "FAIL"));
        assertTrue(service.manualRefund(ID, "maker", "prepare"));
        failAfterBalanceWrite = true;
        assertThrows(IllegalStateException.class, () -> service.manualRefund(ID, "checker", "confirm"));
        money("70", balance());
        MemberCashWithdraw row = cash.selectById(ID);
        assertEquals(MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW, row.getStatus());
        assertEquals("maker", row.getManualOperator());
        assertNull(row.getManualVerifier());
        failAfterBalanceWrite = false;
        assertTrue(service.manualRefund(ID, "checker", "retry original evidence"));
        money("100", balance());
    }

    @Test
    void secondCheckerRechecksCurrentChannelAndCannotRefundALateSuccess() {
        CashWithdrawTransferIntent intent = submitted();
        db.update("UPDATE member_cash_withdraw SET status=3 WHERE id=?", ID);
        when(provider.queryTransferResult(any(CashWithdrawTransferIntent.class))).thenReturn(facts(intent, "FAIL"));
        assertTrue(service.manualRefund(ID, "maker", "prepare"));
        when(provider.queryTransferResult(any(CashWithdrawTransferIntent.class))).thenReturn(facts(intent, "SUCCESS"));
        assertFalse(service.manualRefund(ID, "checker", "late success"));
        money("70", balance());
        assertEquals(0, refunds.get());
        service.requery(ID);
        assertEquals(MemberCashWithdraw.STATUS_SUCCESS, cash.selectById(ID).getStatus());
        money("70", balance());
    }

    private boolean checker(CountDownLatch start, String actor) throws Exception {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try { return service.manualRefund(ID, actor, "concurrent verification"); }
        catch (IllegalStateException alreadyProcessed) { return false; }
    }

    private WithdrawalBalanceAdmissionService admission(CashWithdrawTransferIntentMapper mapper) {
        return transactional(new WithdrawalBalanceAdmissionService(members, frozen, cash, mapper), WithdrawalBalanceAdmissionService.class);
    }

    private WithdrawalBalanceAdmissionService.Admission debit(WithdrawalBalanceAdmissionService admission) {
        return admission.debitCash(ID, MEMBER, AMOUNT, "merchant", "wx-original", "original remark");
    }

    private CashWithdrawTransferIntent submitted() {
        // A pre-integration master-only original: persist the real debit and original snapshot, without inventing ledger facts.
        return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
            MemberCashWithdraw row = cash.selectByIdForUpdate(ID);
            assertEquals(1, members.deductWithdrawableBalance(MEMBER, AMOUNT, BigDecimal.ZERO));
            CashWithdrawTransferIntent original = CashWithdrawTransferIntent.create(row, "merchant", "wx-original",
                    "original remark", new BigDecimal("100"), new BigDecimal("70"));
            assertEquals(1, intents.insert(original));
            assertEquals(1, intents.markSubmitted(ID, original.requestHash()));
            return intents.selectByWithdrawId(ID);
        });
    }

    private <T> T transactional(Object target, Class<T> type) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }

    private Map<String, String> facts(CashWithdrawTransferIntent intent, String result) {
        Map<String, String> facts = new HashMap<>();
        facts.put("mchid", intent.mchid()); facts.put("appid", intent.appid());
        facts.put("outBatchNo", intent.outBatchNo()); facts.put("outDetailNo", intent.outDetailNo());
        facts.put("openid", intent.openid()); facts.put("transferAmount", Long.toString(intent.amountFen()));
        facts.put("batchId", "original-batch"); facts.put("detailId", "original-detail");
        facts.put("batchStatus", "FINISHED"); facts.put("detailStatus", result); facts.put("result", result);
        facts.put("failReasonCode", "ACCOUNT_FROZEN");
        return facts;
    }

    private BigDecimal balance() { return db.queryForObject("SELECT balance FROM ums_member WHERE id=?", BigDecimal.class, MEMBER); }
    private int countIntents() { return db.queryForObject("SELECT COUNT(*) FROM member_cash_withdraw_transfer_intent WHERE withdraw_id=?", Integer.class, ID); }
    private void money(String expected, BigDecimal actual) { assertNotNull(actual); assertEquals(0, new BigDecimal(expected).compareTo(actual)); }
}
