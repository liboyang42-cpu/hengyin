package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.support.WithdrawalBalanceAdmissionService;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** DRAFT / NOT_RUN. Real MySQL, unchanged production XML and Spring transactional service proxy. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OWN_C04_21MySqlIT {
    @Configuration @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions { }
    private String schema;
    private long cashId = 7000;
    private boolean created;
    private JdbcTemplate admin, db;
    private DriverManagerDataSource source;
    private AnnotationConfigApplicationContext context;
    private PlatformTransactionManager manager;
    private WithdrawalBalanceAdmissionService admission;
    private ExecutorService otherConnection;

    @BeforeAll void database() throws Exception {
        // Opening the gate must never silently use MysqlIntegrationTestConfig's defaults.
        for (String key : new String[]{"HOST", "PORT", "DATABASE_BASE", "DATABASE_SUFFIX", "USER", "PASSWORD"}) {
            assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_" + key), "Explicit test environment required: " + key);
        }
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("c0421");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        schema = "c0421_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + schema);
        created = true;
        source = new DriverManagerDataSource(config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + schema + "?"), config.user(), config.password());
        db = new JdbcTemplate(source);
        // Fixture DDL only: no application schema or migration is executed by this source delivery.
        db.execute("CREATE TABLE ums_member(id BIGINT PRIMARY KEY, balance DECIMAL(18,2)) ENGINE=InnoDB");
        db.execute("CREATE TABLE merchant_referral_bounty(id BIGINT PRIMARY KEY, inviter_member_id BIGINT, status INT, bounty_amount DECIMAL(18,2), frozen_until DATETIME(3), del_flag CHAR(1), INDEX(inviter_member_id)) ENGINE=InnoDB");
        db.execute("CREATE TABLE player_withdrawal_cooldown(id BIGINT PRIMARY KEY, member_id BIGINT, amount DECIMAL(18,2), cooldown_until DATETIME(3), INDEX(member_id)) ENGINE=InnoDB");
        db.execute("CREATE TABLE member_cash_withdraw(id BIGINT PRIMARY KEY, member_id BIGINT, amount DECIMAL(18,2), status INT, frozen INT, del_flag INT, "
                + "openid VARCHAR(255), out_batch_no VARCHAR(255), out_detail_no VARCHAR(255), transfer_batch_id VARCHAR(255), wechat_detail_id VARCHAR(255), "
                + "fail_reason VARCHAR(1000), channel_error_code VARCHAR(255), create_time DATETIME, finish_time DATETIME, wechat_batch_status VARCHAR(255), "
                + "submit_time DATETIME, last_query_time DATETIME, balance_deduct_id BIGINT, balance_refund_id BIGINT, manual_operator BIGINT, manual_verifier BIGINT, "
                + "manual_note VARCHAR(1000), trace_id VARCHAR(255), audit_by BIGINT, audit_time DATETIME, frozen_reason VARCHAR(255), frozen_by BIGINT, frozen_at DATETIME) ENGINE=InnoDB");
        try (Connection connection = source.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource(java.nio.file.Path.of(
                            System.getProperty("architecture.a.repo", ".."), "chengyinhub-admin/sql/migration_20261023_cash_withdraw_fund_fact.sql")));
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource(java.nio.file.Path.of(
                            System.getProperty("architecture.a.repo", ".."), "chengyinhub-admin/sql/migration_20261024_cash_withdraw_transfer_intent.sql")));
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource(java.nio.file.Path.of(
                            System.getProperty("architecture.a.repo", ".."), "chengyinhub-admin/sql/migration_20261025_cash_withdraw_fund_request_compat.sql")));
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new Resource[]{
                new ClassPathResource("mapper/business/UmsMemberMapper.xml"),
                new ClassPathResource("mapper/business/MerchantReferralBountyMapper.xml"),
                new ClassPathResource("mapper/business/PlayerWithdrawalCooldownMapper.xml"),
                new ClassPathResource("mapper/business/MemberCashWithdrawMapper.xml"),
                new ClassPathResource("mapper/business/CashWithdrawTransferIntentMapper.xml")});
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(UmsMemberMapper.class, () -> sql.getMapper(UmsMemberMapper.class));
        context.registerBean(MerchantReferralBountyMapper.class, () -> sql.getMapper(MerchantReferralBountyMapper.class));
        context.registerBean(PlayerWithdrawalCooldownMapper.class, () -> sql.getMapper(PlayerWithdrawalCooldownMapper.class));
        context.registerBean(MemberCashWithdrawMapper.class, () -> sql.getMapper(MemberCashWithdrawMapper.class));
        context.registerBean(CashWithdrawTransferIntentMapper.class, () -> sql.getMapper(CashWithdrawTransferIntentMapper.class));
        // Legacy display-only dependencies are not invoked by resolveForWithdrawal; no mocked mapper/provider.
        context.registerBean(WithdrawalFrozenAmountResolver.class, () -> new WithdrawalFrozenAmountResolver(null, null));
        context.registerBean(WithdrawalBalanceAdmissionService.class);
        context.refresh();
        admission = context.getBean(WithdrawalBalanceAdmissionService.class);
        manager = context.getBean(PlatformTransactionManager.class);
        assertTrue(AopUtils.isAopProxy(admission), "Must exercise Spring transaction interceptor");
        otherConnection = Executors.newSingleThreadExecutor();
    }
    @BeforeEach void reset() {
        cashId++; // Retain append-only original/fund facts; each cash test uses a new original ID.
        for (String table : new String[]{"player_withdrawal_cooldown", "merchant_referral_bounty", "ums_member"}) db.execute("DELETE FROM " + table);
        db.update("INSERT INTO ums_member(id,balance) VALUES(42,100)");
    }
    @AfterAll void cleanup() throws Exception {
        if (otherConnection != null) { otherConnection.shutdownNow(); assertTrue(otherConnection.awaitTermination(10, TimeUnit.SECONDS)); }
        if (context != null) context.close();
        if (created) admin.execute("DROP DATABASE " + schema);
    }
    private TransactionTemplate rr() {
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx;
    }
    private void money(String expected, BigDecimal actual) { assertNotNull(actual); assertEquals(0, new BigDecimal(expected).compareTo(actual)); }
    private void awaitWriter(Runnable action) {
        Future<?> writer = otherConnection.submit(action);
        try { writer.get(10, TimeUnit.SECONDS); }
        catch (Exception failure) { writer.cancel(true); throw new AssertionError("Independent transaction failed", failure); }
    }
    @Test void bankAdmissionUsesCommittedBalanceAndBothFreezesAfterOuterOldRrSnapshot() {
        rr().executeWithoutResult(outer -> {
            money("100", db.queryForObject("SELECT balance FROM ums_member WHERE id=42", BigDecimal.class));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM player_withdrawal_cooldown", Integer.class));
            awaitWriter(() -> rr().executeWithoutResult(writer -> {
                db.update("UPDATE ums_member SET balance=150 WHERE id=42");
                db.update("INSERT INTO player_withdrawal_cooldown VALUES(1,42,50,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 DAY))");
                db.update("INSERT INTO merchant_referral_bounty VALUES(1,42,1,20,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 DAY),'0')");
            }));
            // Negative control: the original RR read view really remains stale after writer commit.
            money("100", db.queryForObject("SELECT balance FROM ums_member WHERE id=42", BigDecimal.class));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM player_withdrawal_cooldown", Integer.class));
            WithdrawalBalanceAdmissionService.Admission result = admission.debitBank(42L, new BigDecimal("80"));
            assertTrue(result.deducted);
            money("150", result.before); money("70", result.after);
            money("50", result.frozen.getPlayerComplaintCooldown()); money("20", result.frozen.getReferralBounty());
        });
        money("70", db.queryForObject("SELECT balance FROM ums_member WHERE id=42", BigDecimal.class));
    }
    @Test void cashRequiresNewCommitsAndReleasesFinancialRowsBeforeCallerContinues() {
        db.update("INSERT INTO member_cash_withdraw(id,member_id,amount,status,frozen,del_flag,openid,out_batch_no,out_detail_no) VALUES(?,42,30,0,0,0,'openid',?,?)", cashId, "CW" + cashId, "CD" + cashId);
        db.update("INSERT INTO player_withdrawal_cooldown VALUES(1,42,10,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 DAY))");
        db.update("INSERT INTO merchant_referral_bounty VALUES(1,42,1,10,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 DAY),'0')");
        rr().executeWithoutResult(outer -> {
            db.queryForObject("SELECT balance FROM ums_member WHERE id=42", BigDecimal.class);
            WithdrawalBalanceAdmissionService.Admission result = admission.debitCash(cashId,42L,new BigDecimal("30"),"merchant","wx-original","remark");
            assertTrue(result.deducted); money("100",result.before); money("70",result.after);
            // A real independent connection must acquire these locks while outer is still alive.
            awaitWriter(() -> {
                try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
                    connection.setAutoCommit(false);
                    statement.execute("SET SESSION innodb_lock_wait_timeout=2");
                    try (ResultSet row = statement.executeQuery("SELECT balance FROM ums_member WHERE id=42 FOR UPDATE")) {
                        assertTrue(row.next()); money("70", row.getBigDecimal(1));
                    }
                    statement.executeQuery("SELECT id FROM member_cash_withdraw WHERE id=" + cashId + " FOR UPDATE").close();
                    statement.executeQuery("SELECT withdraw_id FROM member_cash_withdraw_transfer_intent WHERE withdraw_id=" + cashId + " FOR UPDATE").close();
                    statement.executeQuery("SELECT id FROM merchant_referral_bounty WHERE inviter_member_id=42 FOR UPDATE").close();
                    statement.executeQuery("SELECT id FROM player_withdrawal_cooldown WHERE member_id=42 FOR UPDATE").close();
                    connection.commit();
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
        });
    }
    @Test void insufficientCashAdmissionFinalizesOriginalRowAndCannotDebitAfterLaterCredit() {
        db.update("INSERT INTO member_cash_withdraw(id,member_id,amount,status,frozen,del_flag,openid,out_batch_no,out_detail_no) VALUES(?,42,120,0,0,0,'openid',?,?)", cashId, "CW" + cashId, "CD" + cashId);
        WithdrawalBalanceAdmissionService.Admission first = admission.debitCash(cashId,42L,new BigDecimal("120"),"merchant","wx-original","remark");
        assertFalse(first.deducted); assertTrue(first.currentRequestEligible);
        assertEquals(MemberCashWithdraw.STATUS_FAILED_NO_DEBIT, db.queryForObject("SELECT status FROM member_cash_withdraw WHERE id=?", Integer.class, cashId));
        awaitWriter(() -> rr().executeWithoutResult(writer -> db.update("UPDATE ums_member SET balance=200 WHERE id=42")));
        WithdrawalBalanceAdmissionService.Admission late = admission.debitCash(cashId,42L,new BigDecimal("120"),"merchant","wx-original","remark");
        assertFalse(late.currentRequestEligible); assertFalse(late.deducted);
        money("200", db.queryForObject("SELECT balance FROM ums_member WHERE id=42", BigDecimal.class));
        assertEquals(MemberCashWithdraw.STATUS_FAILED_NO_DEBIT, db.queryForObject("SELECT status FROM member_cash_withdraw WHERE id=?", Integer.class, cashId));
    }
}
