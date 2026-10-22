package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.MerchantReferralBounty;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.mapper.MerchantReferralBountyMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.business.service.support.SavepointLockFailures;
import com.chengyinhub.common.annotation.DeadlockRetry;
import com.chengyinhub.framework.config.DeadlockRetryConfig;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 赏金 savepoint 内的锁冲突必须交给外层 {@code @DeadlockRetry},不能被「赏金不连累核销」的 catch 吞掉。
 *
 * <p>装配与生产同序:AspectJ 自动代理 + 默认顺序事务通知器 + {@link DeadlockRetryConfig}。外层 {@link Verifier}
 * 代替 {@code redeemExploreChapterAndRecord}:先写一行核销,再调 {@code onVerification}。赏金的 CAS / 加余额 / 写流水
 * 落在 H2 真表上,随真实事务提交或回滚,所以「最终只记一次」看的是库里的终态,而不是 mock 的调用次数。
 *
 * <p>死锁注入:在流水写入时先对同一连接执行 {@code ROLLBACK},模拟 InnoDB 1213 回滚整笔物理事务、savepoint 随之失效,
 * 再抛 1213。Spring 回滚到 savepoint 失败,抛出的是挂着原异常的 {@link TransactionSystemException} —— 与 MySQL 上看到的一致。
 * 修复前:这里被 catch 吞掉,外层不重试,核销行和赏金都没了,调用却「成功」返回。
 */
class MerchantReferralBountyDeadlockRetryTest {

    private static final long MERCHANT = 500L;
    private static final long INVITER = 100L;
    private static final long BOUNTY_ID = 7L;

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private Verifier verifier;
    /** 第一次写流水时注入的失败;null = 不注入。 */
    private final AtomicReference<RuntimeException> injectOnce = new AtomicReference<>();
    private final AtomicInteger detailInserts = new AtomicInteger();

    @BeforeEach
    void start() {
        context = new AnnotationConfigApplicationContext(Wiring.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute("DROP TABLE IF EXISTS verify_log");
        jdbc.execute("DROP TABLE IF EXISTS bounty");
        jdbc.execute("DROP TABLE IF EXISTS member");
        jdbc.execute("DROP TABLE IF EXISTS balance_detail");
        jdbc.execute("CREATE TABLE verify_log(id BIGINT AUTO_INCREMENT PRIMARY KEY, registration_id BIGINT)");
        jdbc.execute("CREATE TABLE bounty(id BIGINT PRIMARY KEY, status INT)");
        jdbc.execute("CREATE TABLE member(id BIGINT PRIMARY KEY, balance DECIMAL(18,2))");
        jdbc.execute("CREATE TABLE balance_detail(id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT, change_balance DECIMAL(18,2))");
        jdbc.update("INSERT INTO bounty VALUES(?, 0)", BOUNTY_ID);
        jdbc.update("INSERT INTO member VALUES(?, 0)", INVITER);

        verifier = context.getBean(Verifier.class);
        verifier.use(jdbc, bountyService(context.getBean(PlatformTransactionManager.class)));
    }

    @AfterEach
    void stop() {
        context.close();
    }

    @Test
    void deadlockInsideSavepointPropagatesAndOuterRetriesOnceThenAppliesBountyExactlyOnce() {
        injectOnce.set(mysqlDeadlock());

        verifier.verify(9001L);

        assertEquals(2, verifier.attempts(), "外层 @DeadlockRetry 必须恰好再跑一次");
        assertEquals(2, detailInserts.get(), "第一次尝试在写流水时死锁,第二次重新写");
        assertEquals(1, count("verify_log"), "核销行不能丢,也不能写两次");
        assertEquals(1, (int) jdbc.queryForObject("SELECT status FROM bounty WHERE id=?", Integer.class, BOUNTY_ID));
        assertEquals(0, new BigDecimal("30.00").compareTo(balance()), "赏金只到账一次 ¥30");
        assertEquals(1, count("balance_detail"), "流水只有一条");
    }

    @Test
    void lockWaitTimeoutInsideSavepointAlsoGoesToOuterRetry() {
        injectOnce.set(new CannotAcquireLockException("Lock wait timeout exceeded",
                new SQLException("Lock wait timeout exceeded", "HY000", 1205)));

        verifier.verify(9001L);

        assertEquals(2, verifier.attempts());
        assertEquals(1, count("verify_log"));
        assertEquals(0, new BigDecimal("30.00").compareTo(balance()));
        assertEquals(1, count("balance_detail"));
    }

    @Test
    void ordinaryBusinessFailureIsStillIsolatedToTheSavepointAndSwallowed() {
        injectOnce.set(new IllegalStateException("流水写入失败"));

        assertDoesNotThrow(() -> verifier.verify(9001L));

        assertEquals(1, verifier.attempts(), "非锁冲突不重试");
        assertEquals(1, count("verify_log"), "核销照常提交");
        assertEquals(0, (int) jdbc.queryForObject("SELECT status FROM bounty WHERE id=?", Integer.class, BOUNTY_ID),
                "savepoint 已回滚 CAS,留待下次核销再判");
        assertEquals(0, BigDecimal.ZERO.compareTo(balance()), "savepoint 已回滚加余额");
        assertEquals(0, count("balance_detail"));
    }

    @Test
    void deadlockThatOutlivesTheRetryBudgetFailsTheVerificationInsteadOfCommittingHalf() {
        RuntimeException deadlock = mysqlDeadlock();
        // 每次都死锁:两次尝试用完后必须把 1213 抛给调用方,库里什么都不留。
        verifier.use(jdbc, bountyService(context.getBean(PlatformTransactionManager.class), deadlock));

        assertThrows(DeadlockLoserDataAccessException.class, () -> verifier.verify(9001L));

        assertEquals(2, verifier.attempts());
        assertEquals(0, count("verify_log"));
        assertEquals(0, BigDecimal.ZERO.compareTo(balance()));
        assertEquals(0, count("balance_detail"));
    }

    // ---------- SavepointLockFailures 的识别口径 ----------

    @Test
    void helperFindsDeadlockBehindFailedSavepointRollback() {
        DeadlockLoserDataAccessException deadlock = new DeadlockLoserDataAccessException("1213", null);
        TransactionSystemException rollbackFailed = new TransactionSystemException("Could not roll back to JDBC savepoint",
                new SQLException("SAVEPOINT SAVEPOINT_1 does not exist", "42000", 1305));
        rollbackFailed.initApplicationException(deadlock);

        assertSame(deadlock, assertThrows(DeadlockLoserDataAccessException.class,
                () -> SavepointLockFailures.rethrowIfLockFailure(rollbackFailed)));
    }

    @Test
    void helperTranslatesUntranslatedMysqlLockCodes() {
        assertThrows(DeadlockLoserDataAccessException.class, () -> SavepointLockFailures.rethrowIfLockFailure(
                new RuntimeException(new SQLException("Deadlock found", "40001", 1213))));
        assertThrows(DeadlockLoserDataAccessException.class, () -> SavepointLockFailures.rethrowIfLockFailure(
                new RuntimeException(new SQLException("serialization failure", "40001", 0))));
        assertThrows(CannotAcquireLockException.class, () -> SavepointLockFailures.rethrowIfLockFailure(
                new RuntimeException(new SQLException("Lock wait timeout", "HY000", 1205))));
    }

    @Test
    void helperIgnoresEverythingElse() {
        assertDoesNotThrow(() -> SavepointLockFailures.rethrowIfLockFailure(null));
        assertDoesNotThrow(() -> SavepointLockFailures.rethrowIfLockFailure(new IllegalStateException("biz")));
        assertDoesNotThrow(() -> SavepointLockFailures.rethrowIfLockFailure(
                new DuplicateKeyException("uk_merchant", new SQLException("Duplicate entry", "23000", 1062))));
        assertDoesNotThrow(() -> SavepointLockFailures.rethrowIfLockFailure(new TransactionSystemException(
                "Could not roll back to JDBC savepoint", new SQLException("gone", "42000", 1305))));
    }

    // ---------- fixture ----------

    private DeadlockLoserDataAccessException mysqlDeadlock() {
        return new DeadlockLoserDataAccessException("Deadlock found when trying to get lock",
                new SQLException("Deadlock found when trying to get lock; try restarting transaction", "40001", 1213));
    }

    private MerchantReferralBountyService bountyService(PlatformTransactionManager manager) {
        return bountyService(manager, null);
    }

    /** @param alwaysFail 非 null 时每次写流水都注入它(用于「重试也失败」) */
    private MerchantReferralBountyService bountyService(PlatformTransactionManager manager, RuntimeException alwaysFail) {
        MerchantReferralBountyMapper bountyMapper = mock(MerchantReferralBountyMapper.class);
        IUmsMemberService umsMemberService = mock(IUmsMemberService.class);
        UmsMemberBalanceDetailMapper detailMapper = mock(UmsMemberBalanceDetailMapper.class);
        UmsMemberMapper umsMemberMapper = mock(UmsMemberMapper.class);

        when(bountyMapper.selectByMerchantMemberId(MERCHANT)).thenAnswer(i -> currentBounty());
        when(bountyMapper.selectByMerchantMemberIdForUpdate(MERCHANT)).thenAnswer(i -> currentBounty());
        when(bountyMapper.countVerifiedHeadsByMerchantInWindow(eq(MERCHANT), any(), any())).thenReturn(3);
        when(bountyMapper.sumVerifiedPaidByMerchantInWindow(eq(MERCHANT), any(), any())).thenReturn(new BigDecimal("100.00"));
        when(bountyMapper.selectPaidIdsByInviterForUpdate(INVITER)).thenReturn(Collections.emptyList());
        when(bountyMapper.selectVerifiedRegistrationIdsByMerchantInWindow(eq(MERCHANT), any(), any(), anyInt()))
                .thenReturn(Arrays.asList(11L, 22L, 33L));
        when(bountyMapper.markPaidIfPending(eq(BOUNTY_ID), any(), any(), any(), any(), any()))
                .thenAnswer(i -> jdbc.update("UPDATE bounty SET status=1 WHERE id=? AND status=0", BOUNTY_ID));

        when(umsMemberMapper.selectBalanceForUpdate(INVITER)).thenAnswer(i -> balance());
        when(umsMemberService.selectUmsMemberById(INVITER)).thenAnswer(i -> {
            UmsMember inviter = new UmsMember();
            inviter.setId(INVITER);
            inviter.setBalance(balance());
            return inviter;
        });
        when(umsMemberService.addBalance(eq(INVITER), any())).thenAnswer(i ->
                jdbc.update("UPDATE member SET balance=balance+? WHERE id=?", i.<BigDecimal>getArgument(1), INVITER) == 1);
        when(detailMapper.insertUmsMemberBalanceDetail(any())).thenAnswer(i -> {
            detailInserts.incrementAndGet();
            RuntimeException failure = alwaysFail != null ? alwaysFail : injectOnce.getAndSet(null);
            if (failure instanceof DeadlockLoserDataAccessException) {
                // InnoDB 1213:整笔物理事务已回滚,savepoint 随之失效。
                jdbc.execute("ROLLBACK");
            }
            if (failure != null) {
                throw failure;
            }
            UmsMemberBalanceDetail d = i.getArgument(0);
            return jdbc.update("INSERT INTO balance_detail(member_id, change_balance) VALUES(?, ?)",
                    d.getMemberId(), d.getChangeBalance());
        });

        MerchantReferralBountyService service = new MerchantReferralBountyService();
        ReflectionTestUtils.setField(service, "transactionManager", manager);
        ReflectionTestUtils.setField(service, "bountyMapper", bountyMapper);
        ReflectionTestUtils.setField(service, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(service, "balanceDetailMapper", detailMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", umsMemberMapper);
        return service;
    }

    private MerchantReferralBounty currentBounty() {
        MerchantReferralBounty b = new MerchantReferralBounty();
        b.setId(BOUNTY_ID);
        b.setMerchantMemberId(MERCHANT);
        b.setInviterMemberId(INVITER);
        b.setMerchantRegisteredAt(daysFromNow(-10));
        b.setWindowExpiresAt(daysFromNow(20));
        b.setStatus(jdbc.queryForObject("SELECT status FROM bounty WHERE id=?", Integer.class, BOUNTY_ID));
        return b;
    }

    private BigDecimal balance() {
        return jdbc.queryForObject("SELECT balance FROM member WHERE id=?", BigDecimal.class, INVITER);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static Date daysFromNow(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTime();
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import(DeadlockRetryConfig.class)
    static class Wiring {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:referral_deadlock_retry;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        Verifier verifier() {
            return new Verifier();
        }
    }

    /** 代替核销收口 redeemExploreChapterAndRecord:同样的 @DeadlockRetry + @Transactional 外层边界。 */
    static class Verifier {
        private final AtomicInteger attempts = new AtomicInteger();
        private JdbcTemplate jdbc;
        private MerchantReferralBountyService bounty;

        public void use(JdbcTemplate jdbc, MerchantReferralBountyService bounty) {
            this.jdbc = jdbc;
            this.bounty = bounty;
            attempts.set(0);
        }

        public int attempts() {
            return attempts.get();
        }

        @DeadlockRetry
        @Transactional(rollbackFor = Exception.class)
        public void verify(long registrationId) {
            attempts.incrementAndGet();
            jdbc.update("INSERT INTO verify_log(registration_id) VALUES(?)", registrationId);
            bounty.onVerification(MERCHANT);
        }
    }
}
