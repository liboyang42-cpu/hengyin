package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.MerchantReferralBountyMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.common.annotation.DeadlockRetry;
import com.chengyinhub.framework.config.DeadlockRetryConfig;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真 MySQL 1213:赏金 savepoint 里的死锁必须让外层 {@code @DeadlockRetry} 整笔重试,赏金恰好入账一次。
 *
 * <p>装配:仓库现行 DDL({@code sql/base_schema_20260818.sql})+ 未改动的 MerchantReferralBounty / UmsMember /
 * UmsMemberBalanceDetail mapper XML + 真 Spring 事务与 {@link DeadlockRetryConfig} 切面。{@link IUmsMemberService}
 * 只是门面,查人走 JDBC、加余额转到真 {@code UmsMemberMapper.addBalance}。外层 {@link Verifier} 代替
 * {@code redeemExploreChapterAndRecord}:先改一行核销记录,再调 {@code onVerification}。
 *
 * <p>由 {@code ci/backend-test.sh} 的 MYBATIS_ITS 点名执行(CI backend job 的 mysql:8.0.45,一类一库)。
 *
 * <p>修复前:第一个用例里核销那次被 InnoDB 选为死锁牺牲者,异常被 savepoint 外的 catch 吞掉,
 * 外层「成功」提交了一笔空事务 —— 核销行与赏金全丢,attempts=1。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantReferralBountyDeadlockRetryMySqlIT {
    private static final long MERCHANT = 500L;
    private static final long INVITER = 100L;
    private static final long BOUNTY_ID = 7L;

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import(DeadlockRetryConfig.class)
    static class Wiring { }

    private JdbcTemplate db;
    private DriverManagerDataSource source;
    private AnnotationConfigApplicationContext context;
    private Verifier verifier;
    private ExecutorService pool;

    @BeforeAll
    void setup() throws Exception {
        source = new DriverManagerDataSource(MybatisItDatabase.url("referral_deadlock"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        db = new JdbcTemplate(source);
        assertTrue(com.chengyinhub.business.service.MysqlIntegrationTestConfig.isMysql8OrNewer(
                db.queryForObject("select version()", String.class)), "needs MySQL 8+ InnoDB");
        Path root = Files.exists(Paths.get("sql/base_schema_20260818.sql")) ? Paths.get("") : Paths.get("..");
        String snapshot = new String(Files.readAllBytes(root.resolve("sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
        for (String table : new String[] {"cms_registration", "cms_registration_scan", "ums_member",
                "ums_member_balance_detail", "merchant_referral_bounty"}) {
            String start = "CREATE TABLE `" + table + "` (";
            int from = snapshot.indexOf(start);
            int closing = snapshot.indexOf("\n) ENGINE", from);
            // Statement ends at the first ';' that closes the ENGINE line: table COMMENTs may contain ';'.
            int to = snapshot.indexOf(";\n", closing);
            assertTrue(from >= 0 && closing > from && to > closing, "missing source DDL: " + table);
            db.execute(snapshot.substring(from, to + 1));
        }
        // Test-only tables: the verification row the outer transaction writes, and ballast that makes the
        // competing transaction heavier so InnoDB deterministically picks the verification as deadlock victim.
        db.execute("CREATE TABLE it_verify_log(id BIGINT PRIMARY KEY, verified INT NOT NULL) ENGINE=InnoDB");
        db.execute("CREATE TABLE it_ballast(id BIGINT PRIMARY KEY, v INT NOT NULL) ENGINE=InnoDB");
        java.util.List<Object[]> ballast = new java.util.ArrayList<>();
        for (long id = 1; id <= 200; id++) ballast.add(new Object[] {id});
        db.batchUpdate("INSERT INTO it_ballast(id, v) VALUES(?, 0)", ballast);

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/MerchantReferralBountyMapper.xml"),
                new ClassPathResource("mapper/business/UmsMemberMapper.xml"),
                new ClassPathResource("mapper/business/UmsMemberBalanceDetailMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        UmsMemberMapper members = sql.getMapper(UmsMemberMapper.class);

        context = new AnnotationConfigApplicationContext();
        context.register(Wiring.class);
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(Verifier.class);
        context.refresh();
        verifier = context.getBean(Verifier.class);
        assertTrue(AopUtils.isAopProxy(verifier), "Must exercise the retry and transaction interceptors");

        IUmsMemberService memberService = mock(IUmsMemberService.class);
        when(memberService.selectUmsMemberById(anyLong())).thenAnswer(i -> db.query(
                "SELECT id, balance, invite_user_id, create_time FROM ums_member WHERE id=?", rs -> {
                    if (!rs.next()) return null;
                    UmsMember m = new UmsMember();
                    m.setId(rs.getLong("id"));
                    m.setBalance(rs.getBigDecimal("balance"));
                    m.setInviteUserId(rs.getString("invite_user_id"));
                    m.setCreateTime(rs.getTimestamp("create_time"));
                    return m;
                }, i.<Long>getArgument(0)));
        when(memberService.addBalance(anyLong(), any()))
                .thenAnswer(i -> members.addBalance(i.getArgument(0), i.getArgument(1)) == 1);

        MerchantReferralBountyService bounty = new MerchantReferralBountyService();
        ReflectionTestUtils.setField(bounty, "transactionManager", context.getBean(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(bounty, "bountyMapper", sql.getMapper(MerchantReferralBountyMapper.class));
        ReflectionTestUtils.setField(bounty, "umsMemberService", memberService);
        ReflectionTestUtils.setField(bounty, "balanceDetailMapper", sql.getMapper(UmsMemberBalanceDetailMapper.class));
        ReflectionTestUtils.setField(bounty, "umsMemberMapper", members);
        verifier.use(db, bounty);
        pool = Executors.newFixedThreadPool(2);
    }

    @BeforeEach
    void seed() {
        for (String table : new String[] {"it_verify_log", "merchant_referral_bounty", "ums_member_balance_detail",
                "cms_registration_scan", "cms_registration", "ums_member"}) {
            db.execute("DELETE FROM " + table);
        }
        verifier.reset();
        db.update("INSERT INTO ums_member(id, balance, invite_user_id, create_time) VALUES(?, 0, '', NOW())", INVITER);
        db.update("INSERT INTO ums_member(id, balance, invite_user_id, create_time) VALUES(?, 0, ?, NOW() - INTERVAL 10 DAY)",
                MERCHANT, String.valueOf(INVITER));
        db.update("INSERT INTO merchant_referral_bounty(id, merchant_member_id, inviter_member_id, merchant_registered_at, "
                + "window_expires_at, status, del_flag, create_time, update_time) "
                + "VALUES(?, ?, ?, NOW() - INTERVAL 10 DAY, NOW() + INTERVAL 20 DAY, 0, '0', NOW(), NOW())",
                BOUNTY_ID, MERCHANT, INVITER);
        // Three distinct players verified by this merchant inside the window, each paid ¥40 (≥ ¥30 floor).
        for (long reg = 1; reg <= 3; reg++) {
            db.update("INSERT INTO cms_registration(id, registration_no, owner_type, owner_id, ticket_id, ticket_price, member_id, "
                    + "original_amount, total_amount, payable_amount, pay_expire_time, payment_status, registration_status, verifier_id) "
                    + "VALUES(?, ?, 2, 1, 1, 40, ?, 40, 40, 40, NOW() + INTERVAL 1 DAY, 2, 2, ?)",
                    reg, "DL1-" + reg, 1000 + reg, MERCHANT);
            db.update("INSERT INTO cms_registration_scan(registration_type, registration_id, member_id, status, create_time) "
                    + "VALUES(1, ?, ?, 0, NOW() - INTERVAL 1 DAY)", reg, MERCHANT);
        }
        db.update("INSERT INTO it_verify_log(id, verified) VALUES(1, 0), (2, 0)");
        db.update("UPDATE it_ballast SET v = 0");
    }

    @AfterAll
    void cleanup() throws Exception {
        if (pool != null) {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        if (context != null) context.close();
    }

    @Test
    void deadlockInsideBountySavepointRetriesWholeVerificationAndPaysExactlyOnce() throws Exception {
        CountDownLatch competitorHoldsInviter = new CountDownLatch(1);
        CountDownLatch verificationHoldsRow = new CountDownLatch(1);
        verifier.signalRowLocked(verificationHoldsRow);
        Future<String> competitor = pool.submit(() -> {
            try (Connection c = source.getConnection(); Statement st = c.createStatement()) {
                c.setAutoCommit(false);
                try {
                    st.executeUpdate("UPDATE it_ballast SET v = v + 1");          // heavier trx ⇒ not the victim
                    st.executeQuery("SELECT balance FROM ums_member WHERE id=" + INVITER + " FOR UPDATE").close();
                    competitorHoldsInviter.countDown();
                    // Once verification holds it_verify_log#1 and we hold the inviter row, the cycle forms whichever
                    // of the two cross requests comes second; InnoDB then rolls back the lighter verification.
                    if (!verificationHoldsRow.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("verification never locked its row");
                    }
                    st.executeQuery("SELECT verified FROM it_verify_log WHERE id=1 FOR UPDATE").close();
                    return "acquired";
                } catch (SQLException failure) {
                    return "competitor failed: " + failure.getErrorCode() + " " + failure.getMessage();
                } finally {
                    c.rollback();
                }
            }
        });
        assertTrue(competitorHoldsInviter.await(10, TimeUnit.SECONDS), "competitor never took the inviter lock");

        Future<?> verification = pool.submit(() -> verifier.verify(1L));
        verification.get(60, TimeUnit.SECONDS);
        assertEquals("acquired", competitor.get(60, TimeUnit.SECONDS), "InnoDB must have chosen the verification as victim");

        assertEquals(2, verifier.attempts(), "1213 inside the savepoint must reach @DeadlockRetry: exactly one retry");
        assertEquals(1, (int) db.queryForObject("SELECT verified FROM it_verify_log WHERE id=1", Integer.class),
                "verification row applied exactly once");
        assertPaidExactlyOnce();
    }

    @Test
    void twoConcurrentVerificationsAccrueTheBountyExactlyOnce() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        Future<?> first = pool.submit(() -> { go.await(); verifier.verify(1L); return null; });
        Future<?> second = pool.submit(() -> { go.await(); verifier.verify(2L); return null; });
        go.countDown();
        first.get(60, TimeUnit.SECONDS);
        second.get(60, TimeUnit.SECONDS);

        assertEquals(2, (int) db.queryForObject("SELECT SUM(verified) FROM it_verify_log", Integer.class),
                "both verifications committed");
        assertPaidExactlyOnce();
    }

    private void assertPaidExactlyOnce() {
        assertEquals(1, (int) db.queryForObject("SELECT status FROM merchant_referral_bounty WHERE id=?", Integer.class, BOUNTY_ID));
        assertEquals(0, new BigDecimal("30.00").compareTo(
                db.queryForObject("SELECT balance FROM ums_member WHERE id=?", BigDecimal.class, INVITER)), "¥30 credited once");
        assertEquals(1, (int) db.queryForObject("SELECT COUNT(*) FROM ums_member_balance_detail WHERE member_id=? "
                + "AND event_type=? AND event_id=?", Integer.class, INVITER,
                MerchantReferralBountyService.EVENT_TYPE_REFERRAL_BOUNTY, BOUNTY_ID), "one balance ledger row");
        assertEquals(1, (int) db.queryForObject("SELECT COUNT(*) FROM ums_member_balance_detail", Integer.class));
    }

    /** 代替核销收口 redeemExploreChapterAndRecord:同样的 @DeadlockRetry + @Transactional 外层边界。 */
    static class Verifier {
        private final AtomicInteger attempts = new AtomicInteger();
        private JdbcTemplate jdbc;
        private MerchantReferralBountyService bounty;
        private volatile CountDownLatch rowLocked;

        public void use(JdbcTemplate jdbc, MerchantReferralBountyService bounty) {
            this.jdbc = jdbc;
            this.bounty = bounty;
        }

        public void reset() {
            attempts.set(0);
            rowLocked = null;
        }

        public void signalRowLocked(CountDownLatch latch) {
            rowLocked = latch;
        }

        public int attempts() {
            return attempts.get();
        }

        @DeadlockRetry
        @Transactional(rollbackFor = Exception.class)
        public void verify(long verificationId) {
            attempts.incrementAndGet();
            assertEquals(1, jdbc.update("UPDATE it_verify_log SET verified = verified + 1 WHERE id=?", verificationId));
            CountDownLatch latch = rowLocked;
            if (latch != null) latch.countDown();
            bounty.onVerification(MERCHANT);
        }
    }
}
