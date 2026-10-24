package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CashWithdrawFundFact;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.WithdrawalBalanceAdmissionService;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import com.chengyinhub.common.config.WxPayConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
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
        assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"));
        assertEquals("1", System.getenv("B_PAYMENT_INTENT_DDL_APPROVED"));
        assertTrue(java.util.List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()));
        String suite = "awithdraw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        schema = MybatisItDatabase.databaseName(suite);
        assertTrue(schema.matches("chengyin_mybatis_awithdraw_[a-z0-9_]+") && schema.length() <= 64);
        source = new DriverManagerDataSource(MybatisItDatabase.url(suite), MybatisItDatabase.user(), MybatisItDatabase.password());
        created = true; db = new JdbcTemplate(source); admin = db;
        assertEquals(schema, db.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(db.queryForObject("select version()", String.class).startsWith("8."));
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("architecture-day-a-fixture.sql"));
            Path root = Path.of(System.getProperty("architecture.a.repo", "..")).toAbsolutePath();
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_20261023_cash_withdraw_fund_fact.sql")));
            // A real pre-upgrade original/facts remain intact across the additive migration.
            db.update("INSERT INTO ums_member(id,balance) VALUES(1,70)");
            db.update("INSERT INTO member_cash_withdraw(id,member_id,openid,amount,out_batch_no,out_detail_no,status,del_flag,create_time) VALUES(1,1,'legacy-openid',30,'CW1','CD1',3,0,CURRENT_TIMESTAMP)");
            db.update("INSERT INTO member_cash_withdraw_fund_fact(withdrawal_id,member_id,amount,out_batch_no,out_detail_no,kind,balance_before,balance_after,create_time) VALUES(1,1,30,'CW1','CD1','DEBIT',100,70,CURRENT_TIMESTAMP)");
            Long debit = db.queryForObject("SELECT id FROM member_cash_withdraw_fund_fact WHERE withdrawal_id=1 AND kind='DEBIT'", Long.class);
            db.update("UPDATE member_cash_withdraw SET balance_deduct_id=? WHERE id=1", debit);
            db.update("INSERT INTO member_cash_withdraw_fund_fact(withdrawal_id,member_id,amount,out_batch_no,out_detail_no,kind,create_time) VALUES(1,1,30,'CW1','CD1','SUBMIT_INTENT',CURRENT_TIMESTAMP)");
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_20261024_cash_withdraw_transfer_intent.sql")));
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
        installService(admission);
    }

    private void installService(WithdrawalBalanceAdmissionService cashAdmission) {
        target = new CashWithdrawServiceImpl();
        ReflectionTestUtils.setField(target, "mapper", cash);
        ReflectionTestUtils.setField(target, "umsMemberService", members);
        ReflectionTestUtils.setField(target, "wechatPayService", channel);
        ReflectionTestUtils.setField(target, "alarmService", mock(AlarmService.class));
        ReflectionTestUtils.setField(target, "transactionManager", manager);
        ReflectionTestUtils.setField(target, "balanceAdmissionService", cashAdmission);
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
        ReflectionTestUtils.setField(config, "merchantId", "unit-merchant");
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
        w.setOpenid("unit-channel-openid"); w.setStatus(0); w.setDelFlag(0); w.setCreateTime(new Date());
        assertEquals(1, cash.insertMemberCashWithdraw(w));
        w.setOutBatchNo("CW" + w.getId()); w.setOutDetailNo("CD" + w.getId());
        assertEquals(1, cash.updateMemberCashWithdraw(w)); return cash.selectById(w.getId());
    }
    private void state(Long id, int value) { db.update("UPDATE member_cash_withdraw SET status=? WHERE id=?", value, id); }
    private void fact(MemberCashWithdraw w, String kind) {
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            cash.selectByIdForUpdate(w.getId());
            CashWithdrawFundFact f = CashWithdrawFundFact.of(w, kind);
            if ("FINAL_FAIL".equals(kind)) {
                CashWithdrawTransferIntent intent = cash.selectFundFacts(w.getId()).stream()
                        .filter(row -> "SUBMIT_INTENT".equals(row.getKind())).findFirst().orElseThrow().transferIntent();
                f.bindTransferIntent(intent); f.setSubmissionIntentId(intent.submissionIntentId());
                f.setChannelDetailStatus("FAIL"); f.setChannelBatchStatus("FINISHED");
                f.setChannelBatchId("provider-batch-" + w.getId()); f.setChannelDetailId("provider-detail");
            }
            assertEquals(1, cash.insertFundFact(f));
        });
    }
    private int count(Long id, String kind) {
        return db.queryForObject("SELECT COUNT(*) FROM member_cash_withdraw_fund_fact WHERE withdrawal_id=? AND kind=?", Integer.class, id, kind);
    }
    private MemberCashWithdraw accepted() {
        when(channel.transferToBalance(any(CashWithdrawTransferIntent.class))).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "No financial transaction across network work");
            CashWithdrawTransferIntent intent = i.getArgument(0);
            assertNotNull(intent.submissionIntentId());
            assertEquals(1, count(intent.withdrawalId(), "SUBMIT_INTENT"));
            assertEquals(intent.requestHash(), db.queryForObject("SELECT request_hash FROM member_cash_withdraw_fund_fact WHERE id=?", String.class, intent.submissionIntentId()));
            return Map.of("ok", "true", "batchStatus", "ACCEPTED", "batchId", "provider-batch-" + intent.withdrawalId());
        });
        return service.withdraw(memberId, new BigDecimal("30.00"));
    }

    private CashWithdrawTransferIntent requestFor(MemberCashWithdraw w) {
        return CashWithdrawTransferIntent.fromRequest(w.getId(), "unit-merchant", "unit-channel-appid",
                w.getOutBatchNo(), w.getOutDetailNo(), w.getAmount(), "unit-channel-openid", "城瘾分润提现");
    }
    private CashWithdrawTransferIntent originalIntent(MemberCashWithdraw w) {
        return argThat(i -> i != null && w.getId().equals(i.withdrawalId())
                && w.getOutBatchNo().equals(i.outBatchNo()) && w.getOutDetailNo().equals(i.outDetailNo()));
    }
    private Map<String, String> observation(MemberCashWithdraw w, Map<String, String> values) {
        CashWithdrawTransferIntent intent = cash.selectFundFacts(w.getId()).stream()
                .filter(f -> "SUBMIT_INTENT".equals(f.getKind())).findFirst().orElseThrow().transferIntent();
        Map<String, String> observed = new java.util.HashMap<>();
        observed.put("mchid", intent.mchid()); observed.put("appid", intent.appid());
        observed.put("outBatchNo", intent.outBatchNo()); observed.put("outDetailNo", intent.outDetailNo());
        observed.put("openid", intent.openid()); observed.put("transferAmount", Long.toString(intent.amountFen()));
        observed.put("batchId", "provider-batch-" + w.getId()); observed.put("detailId", "provider-detail");
        observed.put("batchStatus", "FINISHED"); observed.putAll(values); return observed;
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
    /** Inject process exit after the real REQUIRES_NEW debit committed, bypassing withdraw's Exception catch. */
    private MemberCashWithdraw processExitAfterDebitCommit() {
        WithdrawalBalanceAdmissionService interrupted = mock(WithdrawalBalanceAdmissionService.class);
        when(interrupted.debitCash(anyLong(), eq(memberId), any())).thenAnswer(i -> {
            assertTrue(admission.debitCash(i.getArgument(0), i.getArgument(1), i.getArgument(2)).deducted);
            throw new AssertionError("simulated process exit before SUBMIT_INTENT");
        });
        installService(interrupted);
        AssertionError exit = assertThrows(AssertionError.class,
                () -> service.withdraw(memberId, new BigDecimal("30.00")));
        assertEquals("simulated process exit before SUBMIT_INTENT", exit.getMessage());
        MemberCashWithdraw w = cash.selectByMember(memberId).get(0);
        assertEquals(0, w.getStatus()); assertNotNull(w.getBalanceDeductId());
        assertEquals(1, count(w.getId(), "DEBIT")); assertEquals(0, count(w.getId(), "SUBMIT_INTENT"));
        assertEquals(0, count(w.getId(), "UNKNOWN")); money("70"); verifyNoInteractions(channel);
        installService(admission); // Fresh service instance observes only committed original/facts.
        return w;
    }
    @Test void processExitBeforeIntentRecoversOriginalThroughTwoPeopleWithoutChannelQuery() {
        MemberCashWithdraw w = processExitAfterDebitCommit();
        service.requery(w.getId());
        MemberCashWithdraw recovered = cash.selectById(w.getId());
        assertEquals(3, recovered.getStatus()); assertNull(recovered.getLastQueryTime());
        assertEquals(0, count(w.getId(), "UNKNOWN")); assertEquals(0, count(w.getId(), "SUBMIT_INTENT"));
        assertNull(ReflectionTestUtils.invokeMethod(target, "recordSubmissionIntent", w.getId(), requestFor(w)));
        assertTrue(service.manualRefund(w.getId(), "maker", "原扣款未提交", 3));
        service.requery(w.getId());
        assertEquals(4, cash.selectById(w.getId()).getStatus()); money("70");
        assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "maker", "同人", 4));
        assertTrue(service.manualRefund(w.getId(), "checker", "复核", 4));
        service.requery(w.getId());
        assertFalse(service.manualRefund(w.getId(), "other", "重复", 4));
        assertEquals(2, cash.selectById(w.getId()).getStatus());
        assertEquals(w.getOutBatchNo(), cash.selectById(w.getId()).getOutBatchNo());
        assertEquals(w.getOutDetailNo(), cash.selectById(w.getId()).getOutDetailNo());
        assertEquals(1, cash.selectByMember(memberId).size()); assertEquals(1, count(w.getId(), "DEBIT"));
        assertEquals(1, count(w.getId(), "REFUND")); assertEquals(0, count(w.getId(), "UNKNOWN"));
        verify(members, times(1)).addBalance(memberId, new BigDecimal("30.00"));
        verifyNoInteractions(channel); money("100");
    }
    @Test void concurrentNeverSubmittedRecoveryAndCheckersPreserveOneOriginalRefund() throws Exception {
        MemberCashWithdraw w = processExitAfterDebitCommit();
        ExecutorService workers = Executors.newFixedThreadPool(3); CountDownLatch recover = new CountDownLatch(1);
        try {
            Future<?> first = workers.submit(() -> { await(recover); service.requery(w.getId()); });
            Future<?> second = workers.submit(() -> { await(recover); service.requery(w.getId()); });
            recover.countDown(); first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            assertEquals(3, cash.selectById(w.getId()).getStatus()); money("70");
            assertTrue(service.manualRefund(w.getId(), "maker", "未提交", 3));
            CountDownLatch verify = new CountDownLatch(1);
            Future<Boolean> a = workers.submit(() -> { await(verify); return service.manualRefund(w.getId(), "checkerA", "复核", 4); });
            Future<Boolean> b = workers.submit(() -> { await(verify); return service.manualRefund(w.getId(), "checkerB", "复核", 4); });
            Future<?> again = workers.submit(() -> { await(verify); service.requery(w.getId()); });
            verify.countDown();
            assertEquals(1, (a.get(10, TimeUnit.SECONDS) ? 1 : 0) + (b.get(10, TimeUnit.SECONDS) ? 1 : 0));
            again.get(10, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
        assertEquals(2, cash.selectById(w.getId()).getStatus());
        assertEquals(1, cash.selectByMember(memberId).size()); assertEquals(1, count(w.getId(), "DEBIT"));
        assertEquals(1, count(w.getId(), "REFUND")); assertEquals(0, count(w.getId(), "SUBMIT_INTENT"));
        assertEquals(0, count(w.getId(), "UNKNOWN"));
        verifyNoInteractions(channel); money("100");
    }
    @Test void recoveryRacingOriginalIntentClaimCannotBothAuthorizeRefundAndSubmission() throws Exception {
        MemberCashWithdraw w = processExitAfterDebitCommit();
        when(channel.queryTransferResult(originalIntent(w))).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, count(w.getId(), "SUBMIT_INTENT"));
            return Map.of("result", "UNKNOWN", "error", "NOT_FOUND");
        });
        ExecutorService workers = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        CashWithdrawTransferIntent intent;
        try {
            Future<?> recover = workers.submit(() -> { await(start); service.requery(w.getId()); });
            Future<CashWithdrawTransferIntent> submit = workers.submit(() -> {
                await(start); return ReflectionTestUtils.invokeMethod(target, "recordSubmissionIntent", w.getId(), requestFor(w));
            });
            start.countDown(); recover.get(10, TimeUnit.SECONDS); intent = submit.get(10, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
        if (intent == null) {
            assertEquals(3, cash.selectById(w.getId()).getStatus());
            assertTrue(service.manualRefund(w.getId(), "maker", "未提交", 3));
            assertTrue(service.manualRefund(w.getId(), "checker", "复核", 4));
            assertEquals(1, count(w.getId(), "REFUND")); assertEquals(0, count(w.getId(), "UNKNOWN"));
            verifyNoInteractions(channel); money("100");
        } else {
            assertEquals(1, count(w.getId(), "SUBMIT_INTENT")); assertEquals(1, count(w.getId(), "UNKNOWN"));
            assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "maker", "不可证未提交", null));
            assertEquals(0, count(w.getId(), "REFUND")); money("70");
        }
        assertEquals(1, cash.selectByMember(memberId).size()); assertEquals(1, count(w.getId(), "DEBIT"));
        verify(channel, never()).transferToBalance(any(CashWithdrawTransferIntent.class));
    }
    @Test void oldUnknownWithoutIntentRemainsInvestigationAndCannotRefund() {
        MemberCashWithdraw w = original();
        assertTrue(admission.debitCash(w.getId(), memberId, w.getAmount()).deducted);
        fact(w, "UNKNOWN"); state(w.getId(), 3);
        when(channel.queryTransferResult(originalIntent(w)))
                .thenReturn(Map.of("result", "UNKNOWN", "error", "NOT_FOUND"));
        service.requery(w.getId());
        assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "maker", "旧未知", 3));
        assertEquals(3, cash.selectById(w.getId()).getStatus());
        assertEquals(0, count(w.getId(), "SUBMIT_INTENT")); assertEquals(1, count(w.getId(), "UNKNOWN"));
        assertEquals(0, count(w.getId(), "REFUND")); money("70");
        verifyNoInteractions(channel);
        verify(members, never()).addBalance(anyLong(), any());
    }
    @Test void evidenceBindFailureRollsBackTheDebitAndItsFact() {
        MemberCashWithdraw w = original(); MemberCashWithdrawMapper failing = spy(cash);
        doReturn(0).when(failing).bindDebitEvidence(any());
        assertThrows(IllegalStateException.class, () -> admissionFor(failing).debitCash(w.getId(), memberId, w.getAmount()));
        money("100"); assertEquals(0, count(w.getId(), "DEBIT")); assertNull(cash.selectById(w.getId()).getBalanceDeductId());
    }
    @Test void providerAcceptedButErrorOrNotFoundCannotRefund() {
        when(channel.transferToBalance(any(CashWithdrawTransferIntent.class)))
                .thenReturn(Map.of("ok", "false", "ambiguous", "true", "error", "SYSTEM_ERROR"));
        MemberCashWithdraw w = service.withdraw(memberId, new BigDecimal("30.00")); money("70");
        assertEquals(1, count(w.getId(), "SUBMIT_INTENT")); state(w.getId(), 3);
        for (String result : new String[]{"PROCESSING", "UNKNOWN", "NOT_FOUND"}) {
            when(channel.queryTransferResult(originalIntent(w))).thenReturn(Map.of("result", result));
            service.requery(w.getId());
            assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "maker", "调查", 3));
        }
        assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }
    @Test void missingRejectionCertificateRemainsUnknown() {
        when(channel.transferToBalance(any(CashWithdrawTransferIntent.class)))
                .thenReturn(Map.of("ok", "false", "error", "SYSTEM_ERROR"));
        MemberCashWithdraw w = service.withdraw(memberId, new BigDecimal("30.00"));
        assertEquals(0, w.getStatus()); assertEquals(0, count(w.getId(), "NOT_ACCEPTED")); money("70");
    }
    @Test void thrownExceptionAfterSubmissionNeverCreatesRefundPermission() {
        when(channel.transferToBalance(any(CashWithdrawTransferIntent.class)))
                .thenThrow(new IllegalArgumentException("provider accepted but response lost"));
        MemberCashWithdraw w = service.withdraw(memberId, new BigDecimal("30.00"));
        assertEquals(0, w.getStatus()); assertEquals(1, count(w.getId(), "SUBMIT_INTENT"));
        assertEquals(0, count(w.getId(), "NOT_ACCEPTED")); assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }
    @Test void positiveNonAcceptanceRefundsOnce() {
        when(channel.transferToBalance(any(CashWithdrawTransferIntent.class)))
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
        when(channel.queryTransferResult(originalIntent(w)))
                .thenReturn(observation(w, Map.of("result", "FAIL", "batchStatus", "CLOSED")));
        service.requery(w.getId()); state(w.getId(), 3);
        assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "maker", "CLOSED仍待核查", 3));
        assertEquals(0, count(w.getId(), "FINAL_FAIL")); assertEquals(1, count(w.getId(), "UNKNOWN"));
        assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }
    @Test void earlierUnknownThenNewLocalRejectionCannotEraseSubmissionHistory() {
        when(channel.transferToBalance(any(CashWithdrawTransferIntent.class))).thenAnswer(i -> {
            CashWithdrawTransferIntent sent = i.getArgument(0);
            Long id = sent.withdrawalId();
            when(channel.queryTransferResult(originalIntent(cash.selectById(id)))).thenReturn(Map.of("result", "UNKNOWN"));
            service.requery(id); // Prior ambiguous observation commits before the later local rejection.
            return Map.of("ok", "false", "ambiguous", "false", "error", "local rejection");
        });
        assertThrows(IllegalStateException.class, () -> service.withdraw(memberId, new BigDecimal("30.00")));
        MemberCashWithdraw w = cash.selectByMember(memberId).get(0); state(w.getId(), 3);
        assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "maker", "仍待核查", 3));
        assertThrows(IllegalStateException.class, () -> service.withdraw(memberId, new BigDecimal("30.00")));
        verify(channel, times(1)).transferToBalance(any(CashWithdrawTransferIntent.class));
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
        when(channel.queryTransferResult(originalIntent(w)))
                .thenReturn(observation(w, Map.of("result", "FAIL", "detailStatus", "FAIL", "detailId", "provider-detail")));
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
        when(channel.queryTransferResult(originalIntent(w)))
                .thenReturn(observation(w, Map.of("result", "SUCCESS", "detailStatus", "SUCCESS")));
        service.requery(w.getId());
        assertEquals(1, cash.selectById(w.getId()).getStatus());
        assertFalse(service.manualRefund(w.getId(), "maker", "旧页面", 3));
        assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }
    @Test void manualCreditThenExceptionRollsBackWholeFinancialTransaction() {
        MemberCashWithdraw w = original(); admission.debitCash(w.getId(), memberId, w.getAmount()); state(w.getId(), 3);
        assertTrue(service.manualRefund(w.getId(), "maker", "未提交", 3));
        doAnswer(i -> {
            memberMapper.addBalance(i.getArgument(0), i.getArgument(1)); throw new IllegalStateException("failure after SQL write");
        }).when(members).addBalance(anyLong(), any());
        assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "checker", "复核", 4));
        assertEquals(4, cash.selectById(w.getId()).getStatus()); assertNull(cash.selectById(w.getId()).getBalanceRefundId());
        assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }
    @Test void automaticCreditThenExceptionRollsBackWholeFinancialTransaction() {
        MemberCashWithdraw w = accepted();
        doAnswer(i -> {
            memberMapper.addBalance(i.getArgument(0), i.getArgument(1)); throw new IllegalStateException("failure after SQL write");
        }).when(members).addBalance(anyLong(), any());
        when(channel.queryTransferResult(originalIntent(w))).thenReturn(observation(w, Map.of("result", "FAIL", "detailStatus", "FAIL")));
        service.requery(w.getId());
        assertEquals(3, cash.selectById(w.getId()).getStatus()); assertNull(cash.selectById(w.getId()).getBalanceRefundId());
        assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }
    @Test void committedRefundWithLostCommitReplyNeverReopensOrCreditsAgain() {
        MemberCashWithdraw w = accepted();
        ReflectionTestUtils.setField(target, "transactionManager", loseCommitReplyAt(3));
        when(channel.queryTransferResult(originalIntent(w)))
                .thenReturn(observation(w, Map.of("result", "FAIL", "detailStatus", "FAIL")));
        service.requery(w.getId()); // Preparation and FINAL_FAIL commit; REFUND commits but its acknowledgement is lost.
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

    @Test void callerAlwaysUsesPersistedOriginalParametersAfterCurrentConfigChanges() {
        MemberCashWithdraw w = accepted();
        CashWithdrawTransferIntent original = cash.selectFundFacts(w.getId()).stream()
                .filter(f -> "SUBMIT_INTENT".equals(f.getKind())).findFirst().orElseThrow().transferIntent();
        WxPayConfig current = (WxPayConfig) ReflectionTestUtils.getField(target, "wxPayConfig");
        ReflectionTestUtils.setField(current, "merchantId", "new-merchant");
        ReflectionTestUtils.setField(current, "appid", "new-appid");
        when(channel.queryTransferResult(originalIntent(w))).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            CashWithdrawTransferIntent queried = i.getArgument(0);
            assertEquals(original.submissionIntentId(), queried.submissionIntentId());
            assertEquals(original.requestHash(), queried.requestHash());
            assertEquals("unit-merchant", queried.mchid()); assertEquals("unit-channel-appid", queried.appid());
            assertEquals("unit-channel-openid", queried.openid()); assertEquals(3000L, queried.amountFen());
            return observation(w, Map.of("result", "SUCCESS", "detailStatus", "SUCCESS"));
        });
        service.requery(w.getId());
        assertEquals(1, cash.selectById(w.getId()).getStatus()); assertEquals(1, count(w.getId(), "SUCCESS"));
        assertEquals(0, count(w.getId(), "REFUND")); money("70");
        assertEquals(original.requestHash(), db.queryForObject("SELECT request_hash FROM member_cash_withdraw_fund_fact WHERE id=?",
                String.class, original.submissionIntentId()));
        verify(members, times(1)).selectUmsMemberById(memberId);
    }

    static java.util.stream.Stream<Arguments> mismatchedTerminalFields() {
        return java.util.stream.Stream.of("SUCCESS", "FAIL").flatMap(result ->
                java.util.stream.Stream.of("mchid", "appid", "outBatchNo", "outDetailNo", "transferAmount", "openid", "batchId", "detailId")
                        .map(field -> Arguments.of(result, field)));
    }
    @ParameterizedTest @MethodSource("mismatchedTerminalFields")
    void foreignOrIncompleteTerminalFactsCannotMoveMoney(String result, String field) {
        MemberCashWithdraw w = accepted();
        Map<String, String> wrong = observation(w, Map.of("result", result, "detailStatus", result));
        if ("detailId".equals(field)) wrong.remove(field);
        else wrong.put(field, "transferAmount".equals(field) ? "3001" : "foreign-" + field);
        when(channel.queryTransferResult(originalIntent(w))).thenReturn(wrong);
        service.requery(w.getId());
        assertEquals(0, cash.selectById(w.getId()).getStatus());
        assertEquals(0, count(w.getId(), "SUCCESS")); assertEquals(0, count(w.getId(), "FINAL_FAIL"));
        assertEquals(1, count(w.getId(), "UNKNOWN")); assertEquals(0, count(w.getId(), "REFUND"));
        state(w.getId(), 3);
        assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "maker", "不匹配渠道观察", 3));
        assertEquals(w.getOutBatchNo(), cash.selectById(w.getId()).getOutBatchNo());
        assertEquals(w.getOutDetailNo(), cash.selectById(w.getId()).getOutDetailNo());
        verify(members, never()).addBalance(anyLong(), any()); money("70");
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING", "HASH"})
    void historicalIncompleteIntentIsNotRecreatedFromCurrentConfiguration(String mode) {
        MemberCashWithdraw w = original(); admission.debitCash(w.getId(), memberId, w.getAmount());
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            cash.selectByIdForUpdate(w.getId());
            CashWithdrawFundFact legacy = CashWithdrawFundFact.of(w, "SUBMIT_INTENT");
            if ("HASH".equals(mode)) {
                legacy.bindTransferIntent(requestFor(w)); legacy.setRequestHash("0".repeat(64));
            }
            assertEquals(1, cash.insertFundFact(legacy));
        });
        service.requery(w.getId());
        assertEquals(3, cash.selectById(w.getId()).getStatus());
        assertEquals(1, count(w.getId(), "SUBMIT_INTENT")); assertEquals(0, count(w.getId(), "REFUND"));
        assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "maker", "历史证据不足", 3));
        verifyNoInteractions(channel); money("70");
    }

    @Test void finalFailureWithoutOriginalIntentAssociationNeverAuthorizesManualCredit() {
        MemberCashWithdraw w = accepted();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            cash.selectByIdForUpdate(w.getId());
            CashWithdrawFundFact unbound = CashWithdrawFundFact.of(w, "FINAL_FAIL");
            unbound.setChannelDetailStatus("FAIL"); unbound.setChannelBatchStatus("FINISHED");
            unbound.setChannelBatchId("provider-batch-" + w.getId()); unbound.setChannelDetailId("provider-detail");
            assertEquals(1, cash.insertFundFact(unbound));
        });
        state(w.getId(), 3);
        assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "maker", "旧失败没有原意图关联", 3));
        assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }

    @Test void matchingLateSuccessDuringSecondPersonReviewPreventsCredit() {
        MemberCashWithdraw w = accepted(); fact(w, "FINAL_FAIL"); state(w.getId(), 3);
        assertTrue(service.manualRefund(w.getId(), "maker", "原失败待复核", 3));
        when(channel.queryTransferResult(originalIntent(w)))
                .thenReturn(observation(w, Map.of("result", "SUCCESS", "detailStatus", "SUCCESS")));
        service.requery(w.getId());
        assertEquals(1, cash.selectById(w.getId()).getStatus());
        assertFalse(service.manualRefund(w.getId(), "checker", "迟到复核", 4));
        assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }

    @ParameterizedTest @ValueSource(strings = {"SUCCESS", "FAIL"})
    void acceptedBatchEvidenceArrivingDuringQueryIsRecheckedInsideOriginalRowLock(String result) {
        when(channel.transferToBalance(any(CashWithdrawTransferIntent.class)))
                .thenReturn(Map.of("ok", "false", "ambiguous", "true"));
        MemberCashWithdraw w = service.withdraw(memberId, new BigDecimal("30.00"));
        Long intentId = cash.selectFundFacts(w.getId()).stream().filter(f -> "SUBMIT_INTENT".equals(f.getKind()))
                .findFirst().orElseThrow().getId();
        when(channel.queryTransferResult(originalIntent(w))).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(target, "recordChannelFact", w.getId(), "ACCEPTED",
                    Map.of("batchId", "provider-batch-" + w.getId(), "batchStatus", "ACCEPTED"), intentId)));
            Map<String, String> stale = observation(w, Map.of("result", result, "detailStatus", result));
            stale.put("batchId", "foreign-consistent-batch"); return stale;
        });
        service.requery(w.getId());
        assertEquals(0, cash.selectById(w.getId()).getStatus()); assertEquals(0, count(w.getId(), "FINAL_FAIL"));
        assertEquals(0, count(w.getId(), "SUCCESS")); assertEquals(0, count(w.getId(), "REFUND")); money("70");
    }

    @Test void intentInsertFailureRollsBackItsCompleteParametersWithoutLosingCommittedDebit() {
        MemberCashWithdrawMapper failing = spy(cash);
        doAnswer(i -> {
            CashWithdrawFundFact fact = i.getArgument(0); int inserted = cash.insertFundFact(fact);
            if ("SUBMIT_INTENT".equals(fact.getKind())) throw new IllegalStateException("failure after original intent INSERT");
            return inserted;
        }).when(failing).insertFundFact(any());
        ReflectionTestUtils.setField(target, "mapper", failing);
        assertThrows(IllegalStateException.class, () -> service.withdraw(memberId, new BigDecimal("30.00")));
        MemberCashWithdraw w = cash.selectByMember(memberId).get(0);
        assertEquals(1, count(w.getId(), "DEBIT")); assertEquals(0, count(w.getId(), "SUBMIT_INTENT"));
        verifyNoInteractions(channel); money("70");
        service.requery(w.getId());
        assertTrue(service.manualRefund(w.getId(), "maker", "未提交回补", 3));
        assertTrue(service.manualRefund(w.getId(), "checker", "复核", 4));
        assertEquals(1, count(w.getId(), "REFUND")); money("100");
    }

    @Test void unknownPastSixHoursKeepsOriginalBusinessIdentityAndCannotBeRefundedByTwoPeople() {
        when(channel.transferToBalance(any(CashWithdrawTransferIntent.class)))
                .thenReturn(Map.of("ok", "false", "ambiguous", "true"));
        MemberCashWithdraw w = service.withdraw(memberId, new BigDecimal("30.00"));
        db.update("UPDATE member_cash_withdraw SET submit_time=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 7 HOUR) WHERE id=?", w.getId());
        when(channel.queryTransferResult(originalIntent(w))).thenReturn(Map.of("result", "UNKNOWN", "error", "NOT_FOUND"));
        service.syncPendingWithdrawals(1000); // This class's private schema retains append-only prior originals.
        assertEquals(3, cash.selectById(w.getId()).getStatus());
        assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "maker", "超时不等于失败", 3));
        assertThrows(IllegalStateException.class, () -> service.manualRefund(w.getId(), "checker", "第二人也不得回补", 3));
        assertThrows(IllegalStateException.class, () -> service.withdraw(memberId, new BigDecimal("30.00")));
        assertEquals(1, cash.selectByMember(memberId).size()); assertEquals(1, count(w.getId(), "DEBIT"));
        assertEquals(1, count(w.getId(), "SUBMIT_INTENT")); assertEquals(0, count(w.getId(), "REFUND"));
        assertEquals(w.getOutBatchNo(), cash.selectById(w.getId()).getOutBatchNo());
        assertEquals(w.getOutDetailNo(), cash.selectById(w.getId()).getOutDetailNo()); money("70");
        verify(channel, times(1)).transferToBalance(any(CashWithdrawTransferIntent.class));
        verify(members, never()).addBalance(anyLong(), any());
    }

    @Test void additiveMigrationPreservesLegacyMoneyAndFactsWithoutInventingAnOriginalContext() {
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM member_cash_withdraw_fund_fact WHERE withdrawal_id=1", Integer.class));
        assertNull(db.queryForObject("SELECT request_hash FROM member_cash_withdraw_fund_fact WHERE withdrawal_id=1 AND kind='SUBMIT_INTENT'", String.class));
        assertEquals(0, new BigDecimal("70").compareTo(db.queryForObject("SELECT balance FROM ums_member WHERE id=1", BigDecimal.class)));
        service.requery(1L);
        assertThrows(IllegalStateException.class, () -> service.manualRefund(1L, "maker", "旧参数不补造", 3));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM member_cash_withdraw_fund_fact WHERE withdrawal_id=1", Integer.class));
        assertEquals("CW1", cash.selectById(1L).getOutBatchNo()); assertEquals("CD1", cash.selectById(1L).getOutDetailNo());
        assertEquals(0, new BigDecimal("70").compareTo(db.queryForObject("SELECT balance FROM ums_member WHERE id=1", BigDecimal.class)));
        verifyNoInteractions(channel); verify(members, never()).addBalance(anyLong(), any());
    }

    /** Real A caller/record and B gateway; only the SDK/client credential binding is a deterministic fixture. */
    @ParameterizedTest @ValueSource(strings = {"SUCCESS", "FAIL"})
    void actualImmutableDtoFlowsThroughActualGatewayAndOriginalFinancialReceipt(String outcome) throws Exception {
        com.wechat.pay.java.service.transferbatch.TransferBatchService sdk =
                mock(com.wechat.pay.java.service.transferbatch.TransferBatchService.class);
        WechatPayServiceImpl gateway = new WechatPayServiceImpl();
        // Reuse B's exact narrow fixture binding; this does not prove real certificate/client initialization.
        Class<?> binding = Class.forName(WechatPayServiceImpl.class.getName() + "$TransferClientBinding");
        java.lang.reflect.Constructor<?> constructor = binding.getDeclaredConstructor(
                com.wechat.pay.java.service.transferbatch.TransferBatchService.class, String.class);
        constructor.setAccessible(true);
        ReflectionTestUtils.setField(gateway, "transferClientBinding", constructor.newInstance(sdk, "unit-merchant"));
        ReflectionTestUtils.setField(target, "wechatPayService", gateway);
        when(sdk.initiateBatchTransfer(any())).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            com.wechat.pay.java.service.transferbatch.model.InitiateBatchTransferRequest sent = i.getArgument(0);
            Long id = Long.valueOf(sent.getOutBatchNo().substring(2));
            CashWithdrawTransferIntent stored = cash.selectFundFacts(id).stream()
                    .filter(f -> "SUBMIT_INTENT".equals(f.getKind())).findFirst().orElseThrow().transferIntent();
            stored.requireValid();
            assertEquals(1, count(id, "DEBIT")); assertEquals(1, count(id, "SUBMIT_INTENT"));
            assertEquals(stored.appid(), sent.getAppid()); assertEquals(stored.outBatchNo(), sent.getOutBatchNo());
            assertEquals(CashWithdrawTransferIntent.BATCH_NAME, sent.getBatchName()); assertEquals(1, sent.getTotalNum());
            assertEquals(stored.amountFen(), sent.getTotalAmount()); assertEquals(stored.effectiveRemark(), sent.getBatchRemark());
            assertEquals(1, sent.getTransferDetailList().size());
            var detail = sent.getTransferDetailList().get(0);
            assertEquals(stored.outDetailNo(), detail.getOutDetailNo()); assertEquals(stored.openid(), detail.getOpenid());
            assertEquals(stored.amountFen(), detail.getTransferAmount()); assertEquals(stored.effectiveRemark(), detail.getTransferRemark());
            var accepted = new com.wechat.pay.java.service.transferbatch.model.InitiateBatchTransferResponse();
            accepted.setOutBatchNo(sent.getOutBatchNo()); accepted.setBatchId("provider-batch-" + id);
            accepted.setBatchStatus("ACCEPTED"); return accepted;
        });
        MemberCashWithdraw w = service.withdraw(memberId, new BigDecimal("30.00"));
        assertEquals(0, w.getStatus()); money("70");
        var batch = new com.wechat.pay.java.service.transferbatch.model.TransferBatchGet();
        batch.setMchid("unit-merchant"); batch.setAppid("unit-channel-appid"); batch.setOutBatchNo(w.getOutBatchNo());
        batch.setBatchId("provider-batch-" + w.getId()); batch.setBatchStatus("FINISHED"); batch.setTotalAmount(3000L); batch.setTotalNum(1);
        var batchResult = new com.wechat.pay.java.service.transferbatch.model.TransferBatchEntity(); batchResult.setTransferBatch(batch);
        var detail = new com.wechat.pay.java.service.transferbatch.model.TransferDetailEntity();
        detail.setMchid("unit-merchant"); detail.setAppid("unit-channel-appid"); detail.setOutBatchNo(w.getOutBatchNo());
        detail.setOutDetailNo(w.getOutDetailNo()); detail.setOpenid("unit-channel-openid"); detail.setTransferAmount(3000L);
        detail.setBatchId(batch.getBatchId()); detail.setDetailId("provider-detail"); detail.setDetailStatus(outcome);
        when(sdk.getTransferBatchByOutNo(any())).thenReturn(batchResult);
        when(sdk.getTransferDetailByOutNo(any())).thenReturn(detail);
        service.requery(w.getId()); service.requery(w.getId());
        assertEquals("SUCCESS".equals(outcome) ? 1 : 2, cash.selectById(w.getId()).getStatus());
        assertEquals(1, count(w.getId(), "DEBIT")); assertEquals(1, count(w.getId(), "SUBMIT_INTENT"));
        assertEquals(1, count(w.getId(), "ACCEPTED"));
        assertEquals("FAIL".equals(outcome) ? 1 : 0, count(w.getId(), "REFUND"));
        assertEquals(1, count(w.getId(), "FAIL".equals(outcome) ? "FINAL_FAIL" : "SUCCESS"));
        money("FAIL".equals(outcome) ? "100" : "70");
        verify(sdk, times(1)).initiateBatchTransfer(any()); verifyNoInteractions(channel);
    }
}
