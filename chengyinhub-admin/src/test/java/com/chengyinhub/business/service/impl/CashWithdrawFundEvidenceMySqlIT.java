package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CashWithdrawFundFact;
import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.WithdrawalBalanceAdmissionService;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import com.chengyinhub.common.config.WxPayConfig;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NOT RUN by A. Explicit MySQL test, no H2 fallback and no conditional skip.
 * Real production mapper SQL / admission / balance write / Spring transaction interceptors.
 * Channel is a deterministic stub: this is not a WeChat or complete backend integration test.
 * Run only in the approved controlled environment with explicit CHENGYIN_TEST_MYSQL_* bindings.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CashWithdrawFundEvidenceMySqlIT {
    private JdbcTemplate admin, db;
    private String schema;
    private boolean created;
    private DriverManagerDataSource source;
    private PlatformTransactionManager manager;
    private MemberCashWithdrawMapper cash;
    private UmsMemberMapper memberMapper;
    private WithdrawalFrozenAmountResolver frozen;
    private WithdrawalBalanceAdmissionService admission;
    private IUmsMemberService members;
    private IWechatPayService channel;
    private CashWithdrawServiceImpl target;
    private ICashWithdrawService service;
    private final AtomicLong memberSequence = new AtomicLong(1000);
    private Long memberId;

    @BeforeAll void database() throws Exception {
        for (String key : new String[]{"HOST", "PORT", "DATABASE_BASE", "DATABASE_SUFFIX", "USER", "PASSWORD"})
            assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_" + key), "Explicit controlled test binding required: " + key);
        MysqlIntegrationTestConfig cfg = MysqlIntegrationTestConfig.fromEnvironment("architecture_a");
        admin = new JdbcTemplate(new DriverManagerDataSource(cfg.adminUrl(), cfg.user(), cfg.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        schema = "architecture_a_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + schema); created = true;
        source = new DriverManagerDataSource(cfg.databaseUrl().replace("/" + cfg.databaseName() + "?", "/" + schema + "?"), cfg.user(), cfg.password());
        db = new JdbcTemplate(source);
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("architecture-day-a-fixture.sql"));
            Path root = Path.of(System.getProperty("architecture.a.repo", "..")).toAbsolutePath();
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_20261023_cash_withdraw_fund_fact.sql")));
        }
        manager = new DataSourceTransactionManager(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new Resource[]{new ClassPathResource("mapper/business/MemberCashWithdrawMapper.xml"),
                new ClassPathResource("mapper/business/UmsMemberMapper.xml"),
                new ClassPathResource("mapper/business/MerchantReferralBountyMapper.xml"),
                new ClassPathResource("mapper/business/PlayerWithdrawalCooldownMapper.xml")});
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        cash = sql.getMapper(MemberCashWithdrawMapper.class); memberMapper = sql.getMapper(UmsMemberMapper.class);
        frozen = new WithdrawalFrozenAmountResolver(null, null);
        ReflectionTestUtils.setField(frozen, "bountyMapper", sql.getMapper(MerchantReferralBountyMapper.class));
        ReflectionTestUtils.setField(frozen, "cooldownMapper", sql.getMapper(PlayerWithdrawalCooldownMapper.class));
        admission = admissionFor(cash);
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T original) {
        ProxyFactory proxy = new ProxyFactory(original); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
    private WithdrawalBalanceAdmissionService admissionFor(MemberCashWithdrawMapper mapper) {
        return transactional(new WithdrawalBalanceAdmissionService(memberMapper, frozen, mapper));
    }

    @BeforeEach void service() {
        memberId = memberSequence.incrementAndGet();
        db.update("INSERT INTO ums_member(id,balance) VALUES(?,100)", memberId);
        // Read adapter avoids requiring unrelated member-profile tables in this focused fixture.
        members = mock(IUmsMemberService.class);
        when(members.selectUmsMemberById(memberId)).thenAnswer(i -> {
            UmsMember m = new UmsMember(); m.setId(memberId); m.setWxOpenId("unit-channel-openid");
            m.setBalance(balance()); return m;
        });
        UmsMemberServiceImpl realBalanceWriter = new UmsMemberServiceImpl();
        ReflectionTestUtils.setField(realBalanceWriter, "umsMemberMapper", memberMapper);
        when(members.addBalance(anyLong(), any())).thenAnswer(i ->
                realBalanceWriter.addBalance(i.getArgument(0), i.getArgument(1)));
        channel = mock(IWechatPayService.class);
        target = new CashWithdrawServiceImpl();
        ReflectionTestUtils.setField(target, "mapper", cash);
        ReflectionTestUtils.setField(target, "umsMemberService", members);
        ReflectionTestUtils.setField(target, "wechatPayService", channel);
        ReflectionTestUtils.setField(target, "alarmService", mock(AlarmService.class));
        ReflectionTestUtils.setField(target, "transactionManager", manager);
        ReflectionTestUtils.setField(target, "balanceAdmissionService", admission);
        // Display precheck stub only; locked admission uses the real frozen mappers above.
        WithdrawalFrozenAmountResolver precheck = mock(WithdrawalFrozenAmountResolver.class);
        MerchantReferralBountyService bounty = mock(MerchantReferralBountyService.class);
        IPlayerWithdrawalCooldownService cooldown = mock(IPlayerWithdrawalCooldownService.class);
        when(bounty.frozenAmount(memberId)).thenReturn(BigDecimal.ZERO);
        when(cooldown.frozenAmount(memberId)).thenReturn(BigDecimal.ZERO);
        WithdrawalFrozenAmountResolver.Frozen displaySnapshot =
                new WithdrawalFrozenAmountResolver(bounty, cooldown).resolve(memberId);
        when(precheck.resolve(memberId)).thenReturn(displaySnapshot);
        ReflectionTestUtils.setField(target, "frozenAmountResolver", precheck);
        WxPayConfig config = new WxPayConfig(); ReflectionTestUtils.setField(config, "appid", "unit-channel-appid");
        ReflectionTestUtils.setField(target, "wxPayConfig", config);
        service = transactional(target);
    }

    @AfterAll void cleanup() {
        // Drop only this suite's freshly created random schema, never shared tables or other resources.
        if (created) admin.execute("DROP DATABASE " + schema);
    }
    private BigDecimal balance() { return db.queryForObject("SELECT balance FROM ums_member WHERE id=?", BigDecimal.class, memberId); }
    private void money(String expected) { assertEquals(0, new BigDecimal(expected).compareTo(balance())); }
    private MemberCashWithdraw original() {
        MemberCashWithdraw w = new MemberCashWithdraw(); w.setMemberId(memberId); w.setAmount(new BigDecimal("30.00"));
        w.setStatus(0); w.setDelFlag(0); w.setCreateTime(new Date());
        assertEquals(1, cash.insertMemberCashWithdraw(w));
        w.setOutBatchNo("CW" + w.getId()); w.setOutDetailNo("CD" + w.getId());
        assertEquals(1, cash.updateMemberCashWithdraw(w)); return cash.selectById(w.getId());
    }
    private void state(Long id, int value) { db.update("UPDATE member_cash_withdraw SET status=? WHERE id=?", value, id); }
    private void fact(MemberCashWithdraw w, String kind) {
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            cash.selectByIdForUpdate(w.getId());
            CashWithdrawFundFact f = CashWithdrawFundFact.of(w, kind);
            if ("FINAL_FAIL".equals(kind)) f.setChannelDetailStatus("FAIL");
            assertEquals(1, cash.insertFundFact(f));
        });
    }
    private int count(Long id, String kind) {
        return db.queryForObject("SELECT COUNT(*) FROM member_cash_withdraw_fund_fact WHERE withdrawal_id=? AND kind=?", Integer.class, id, kind);
    }
    private MemberCashWithdraw accepted() {
        when(channel.transferToBalance(any(), any(), any(), any(), any(), any())).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "No financial transaction across network work");
            assertEquals(1, count(Long.valueOf(i.getArgument(0, String.class).substring(2)), "SUBMIT_INTENT"));
            return Map.of("ok", "true", "batchStatus", "ACCEPTED");
        });
        return service.withdraw(memberId, new BigDecimal("30.00"));
    }

    @Test void noDebitEvidenceRejectsWithoutChangingStateOrMoney() {
        MemberCashWithdraw w = original(); state(w.getId(), 3);
        assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "maker", "调查", 3));
        assertEquals(3, cash.selectById(w.getId()).getStatus()); assertEquals(0, count(w.getId(), "REFUND")); money("100");
    }
    @Test void debitCommitReplyLostKeepsOriginalEvidenceAndNeverResubmitsOrRedebits() {
        MemberCashWithdraw w = original();
        assertTrue(admission.debitCash(w.getId(), memberId, w.getAmount()).deducted); // committed; caller loses reply
        assertFalse(admission.debitCash(w.getId(), memberId, w.getAmount()).currentRequestEligible);
        MemberCashWithdraw debited = cash.selectById(w.getId());
        assertNotNull(debited.getBalanceDeductId()); assertEquals(1, count(w.getId(), "DEBIT")); money("70");
        state(w.getId(), 3);
        assertTrue(service.manualRefund(w.getId(), "maker", "扣款已提交但没有提交意图", 3)); money("70");
        assertTrue(service.manualRefund(w.getId(), "checker", "复核", 4)); money("100");
        assertEquals(w.getOutBatchNo(), cash.selectById(w.getId()).getOutBatchNo());
        assertEquals(1, count(w.getId(), "REFUND")); verifyNoInteractions(channel);
    }
    @Test void evidenceBindFailureRollsBackTheDebitAndItsFact() {
        MemberCashWithdraw w = original(); MemberCashWithdrawMapper failing = spy(cash);
        doReturn(0).when(failing).bindDebitEvidence(any());
        assertThrows(IllegalStateException.class, () -> admissionFor(failing).debitCash(w.getId(), memberId, w.getAmount()));
        money("100"); assertEquals(0, count(w.getId(), "DEBIT")); assertNull(cash.selectById(w.getId()).getBalanceDeductId());
    }
    @Test void providerAcceptedButErrorOrNotFoundCannotRefund() {
        when(channel.transferToBalance(any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("ok", "false", "ambiguous", "true", "error", "SYSTEM_ERROR"));
        MemberCashWithdraw w = service.withdraw(memberId, new BigDecimal("30.00")); money("70");
        assertEquals(1, count(w.getId(), "SUBMIT_INTENT")); state(w.getId(), 3);
        for (String result : new String[]{"PROCESSING", "UNKNOWN", "NOT_FOUND"}) {
            when(channel.queryTransferResult(w.getOutBatchNo(), w.getOutDetailNo())).thenReturn(Map.of("result", result));
            service.requery(w.getId());
            assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "maker", "调查", 3));
        }
        assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }
    @Test void missingRejectionCertificateRemainsUnknown() {
        when(channel.transferToBalance(any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("ok", "false", "error", "SYSTEM_ERROR"));
        MemberCashWithdraw w = service.withdraw(memberId, new BigDecimal("30.00"));
        assertEquals(0, w.getStatus()); assertEquals(0, count(w.getId(), "NOT_ACCEPTED")); money("70");
    }
    @Test void thrownExceptionAfterSubmissionNeverCreatesRefundPermission() {
        when(channel.transferToBalance(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("provider accepted but response lost"));
        MemberCashWithdraw w = service.withdraw(memberId, new BigDecimal("30.00"));
        assertEquals(0, w.getStatus()); assertEquals(1, count(w.getId(), "SUBMIT_INTENT"));
        assertEquals(0, count(w.getId(), "NOT_ACCEPTED")); assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }
    @Test void positiveNonAcceptanceRefundsOnce() {
        when(channel.transferToBalance(any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("ok", "false", "ambiguous", "false", "error", "not submitted"));
        assertThrows(IllegalStateException.class, () -> service.withdraw(memberId, new BigDecimal("30.00")));
        MemberCashWithdraw w = cash.selectByMember(memberId).get(0);
        assertEquals(2, w.getStatus()); assertNotNull(w.getBalanceRefundId());
        assertEquals(1, count(w.getId(), "NOT_ACCEPTED")); assertEquals(1, count(w.getId(), "REFUND")); money("100");
        java.util.List<CashWithdrawFundFact> facts = cash.selectFundFacts(w.getId());
        Long intent = facts.stream().filter(f -> "SUBMIT_INTENT".equals(f.getKind())).findFirst().orElseThrow().getId();
        assertEquals(intent, facts.stream().filter(f -> "NOT_ACCEPTED".equals(f.getKind())).findFirst().orElseThrow().getSubmissionIntentId());
    }
    @Test void closedWithoutFinalDetailCannotAuthorizeRefund() {
        MemberCashWithdraw w = accepted();
        when(channel.queryTransferResult(w.getOutBatchNo(), w.getOutDetailNo()))
                .thenReturn(Map.of("result", "FAIL", "batchStatus", "CLOSED"));
        service.requery(w.getId()); state(w.getId(), 3);
        assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "maker", "CLOSED仍待核查", 3));
        assertEquals(0, count(w.getId(), "FINAL_FAIL")); assertEquals(1, count(w.getId(), "UNKNOWN"));
        assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }
    @Test void earlierUnknownThenNewLocalRejectionCannotEraseSubmissionHistory() {
        when(channel.transferToBalance(any(), any(), any(), any(), any(), any())).thenAnswer(i -> {
            String batch = i.getArgument(0); String detail = i.getArgument(1);
            Long id = Long.valueOf(batch.substring(2));
            when(channel.queryTransferResult(batch, detail)).thenReturn(Map.of("result", "UNKNOWN"));
            service.requery(id); // Prior ambiguous observation commits before the later local rejection.
            return Map.of("ok", "false", "ambiguous", "false", "error", "local rejection");
        });
        assertThrows(IllegalStateException.class, () -> service.withdraw(memberId, new BigDecimal("30.00")));
        MemberCashWithdraw w = cash.selectByMember(memberId).get(0); state(w.getId(), 3);
        assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "maker", "仍待核查", 3));
        assertThrows(IllegalStateException.class, () -> service.withdraw(memberId, new BigDecimal("30.00")));
        verify(channel, times(1)).transferToBalance(any(), any(), any(), any(), any(), any());
        assertEquals(1, count(w.getId(), "SUBMIT_INTENT")); assertEquals(1, count(w.getId(), "UNKNOWN"));
        assertEquals(0, count(w.getId(), "NOT_ACCEPTED")); assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }
    @Test void submissionIntentCommitReplyLostNeverInvokesChannelOrAuthorizesRefund() {
        ReflectionTestUtils.setField(target, "transactionManager", loseCommitReplyAt(1));
        assertThrows(org.springframework.transaction.TransactionSystemException.class,
                () -> service.withdraw(memberId, new BigDecimal("30.00")));
        MemberCashWithdraw w = cash.selectByMember(memberId).get(0); state(w.getId(), 3);
        assertEquals(1, count(w.getId(), "SUBMIT_INTENT"));
        assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "maker", "提交意图回包丢失", 3));
        verifyNoInteractions(channel); assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }
    @Test void finalFailureRefundsOnceAndRepeatedQueryHasNoEconomicEffect() {
        MemberCashWithdraw w = accepted();
        when(channel.queryTransferResult(w.getOutBatchNo(), w.getOutDetailNo()))
                .thenReturn(Map.of("result", "FAIL", "detailStatus", "FAIL", "detailId", "provider-detail"));
        service.requery(w.getId()); service.requery(w.getId());
        assertEquals(2, cash.selectById(w.getId()).getStatus()); assertEquals(1, count(w.getId(), "REFUND")); money("100");
    }
    @Test void outerTransactionIsSuspendedBeforeSubmittingToChannel() {
        MemberCashWithdraw w = new TransactionTemplate(manager).execute(status -> {
            MemberCashWithdraw result = accepted(); status.setRollbackOnly(); return result;
        });
        assertNotNull(w); money("70"); assertEquals(1, count(w.getId(), "SUBMIT_INTENT"));
    }
    @Test void lateSuccessDuringInvestigationOrReviewBlocksCredit() {
        MemberCashWithdraw w = accepted(); state(w.getId(), 3);
        when(channel.queryTransferResult(w.getOutBatchNo(), w.getOutDetailNo()))
                .thenReturn(Map.of("result", "SUCCESS", "detailStatus", "SUCCESS"));
        service.requery(w.getId());
        assertEquals(1, cash.selectById(w.getId()).getStatus());
        assertFalse(service.manualRefund(w.getId(), "maker", "旧页面", 3));
        assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }
    @Test void manualCreditThenExceptionRollsBackWholeFinancialTransaction() {
        MemberCashWithdraw w = original(); admission.debitCash(w.getId(), memberId, w.getAmount()); state(w.getId(), 3);
        assertTrue(service.manualRefund(w.getId(), "maker", "未提交", 3));
        when(members.addBalance(anyLong(), any())).thenAnswer(i -> {
            memberMapper.addBalance(i.getArgument(0), i.getArgument(1)); throw new IllegalStateException("failure after SQL write");
        });
        assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "checker", "复核", 4));
        assertEquals(4, cash.selectById(w.getId()).getStatus()); assertNull(cash.selectById(w.getId()).getBalanceRefundId());
        assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }
    @Test void automaticCreditThenExceptionRollsBackWholeFinancialTransaction() {
        MemberCashWithdraw w = accepted();
        when(members.addBalance(anyLong(), any())).thenAnswer(i -> {
            memberMapper.addBalance(i.getArgument(0), i.getArgument(1)); throw new IllegalStateException("failure after SQL write");
        });
        when(channel.queryTransferResult(w.getOutBatchNo(), w.getOutDetailNo())).thenReturn(Map.of("result", "FAIL", "detailStatus", "FAIL"));
        service.requery(w.getId());
        assertEquals(3, cash.selectById(w.getId()).getStatus()); assertNull(cash.selectById(w.getId()).getBalanceRefundId());
        assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }
    @Test void committedRefundWithLostCommitReplyNeverReopensOrCreditsAgain() {
        MemberCashWithdraw w = accepted();
        ReflectionTestUtils.setField(target, "transactionManager", loseCommitReplyAt(2));
        when(channel.queryTransferResult(w.getOutBatchNo(), w.getOutDetailNo()))
                .thenReturn(Map.of("result", "FAIL", "detailStatus", "FAIL"));
        service.requery(w.getId()); // FINAL_FAIL commit succeeds, REFUND commits but its acknowledgement is lost.
        assertEquals(2, cash.selectById(w.getId()).getStatus()); assertNotNull(cash.selectById(w.getId()).getBalanceRefundId());
        assertFalse(service.manualRefund(w.getId(), "checker", "迟到重试", 4));
        assertEquals(1, count(w.getId(), "REFUND")); money("100");
    }
    private PlatformTransactionManager loseCommitReplyAt(int targetCommit) {
        java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();
        return new PlatformTransactionManager() {
            public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return manager.getTransaction(definition);
            }
            public void commit(org.springframework.transaction.TransactionStatus status) {
                manager.commit(status); // Actual database commit precedes the injected lost acknowledgement.
                if (commits.incrementAndGet() == targetCommit)
                    throw new org.springframework.transaction.TransactionSystemException("commit acknowledged by DB but reply lost");
            }
            public void rollback(org.springframework.transaction.TransactionStatus status) { manager.rollback(status); }
        };
    }
    @Test void automaticAndTwoPersonManualRaceHasExactlyOneEconomicRefund() throws Exception {
        MemberCashWithdraw w = accepted(); fact(w, "FINAL_FAIL"); state(w.getId(), 3);
        CountDownLatch start = new CountDownLatch(1); ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> automatic = workers.submit(() -> {
                await(start); ReflectionTestUtils.invokeMethod(target, "refundFailedClaim", w, new Date());
            });
            Future<?> manual = workers.submit(() -> {
                await(start);
                if (service.manualRefund(w.getId(), "maker", "最终失败", 3))
                    service.manualRefund(w.getId(), "checker", "复核", 4);
            });
            start.countDown(); automatic.get(10, TimeUnit.SECONDS); manual.get(10, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
        assertEquals(2, cash.selectById(w.getId()).getStatus()); assertEquals(1, count(w.getId(), "DEBIT"));
        assertEquals(1, count(w.getId(), "REFUND")); money("100");
    }
    @Test void concurrentDistinctCheckersCanCommitOnlyOneRefund() throws Exception {
        MemberCashWithdraw w = accepted(); fact(w, "FINAL_FAIL"); state(w.getId(), 3);
        assertTrue(service.manualRefund(w.getId(), "maker", "最终失败", 3));
        CountDownLatch start = new CountDownLatch(1); ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = workers.submit(() -> { await(start); return service.manualRefund(w.getId(), "checkerA", "复核", 4); });
            Future<Boolean> second = workers.submit(() -> { await(start); return service.manualRefund(w.getId(), "checkerB", "复核", 4); });
            start.countDown();
            assertEquals(1, (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
        assertEquals(2, cash.selectById(w.getId()).getStatus()); assertEquals(1, count(w.getId(), "REFUND")); money("100");
    }
    private void await(CountDownLatch start) {
        try { assertTrue(start.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    @Test void persistedFactsCannotBeEditedOrDeleted() {
        MemberCashWithdraw w = original(); admission.debitCash(w.getId(), memberId, w.getAmount());
        assertThrows(RuntimeException.class, () -> db.update("UPDATE member_cash_withdraw_fund_fact SET amount=1 WHERE withdrawal_id=?", w.getId()));
        assertThrows(RuntimeException.class, () -> db.update("DELETE FROM member_cash_withdraw_fund_fact WHERE withdrawal_id=?", w.getId()));
        assertEquals(1, count(w.getId(), "DEBIT")); money("70");
    }
}
