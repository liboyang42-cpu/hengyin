package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.FundActionPreflight;
import com.chengyinhub.business.domain.dto.BankWithdrawalPreflightRequest;
import com.chengyinhub.business.exception.FundActionPreflightException;
import com.chengyinhub.business.mapper.FundActionPreflightMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored only: NOT_RUN. The gated nested class creates only its own disposable fixture schema. */
class OWN_C01_51Test {
    private static final long MEMBER = 7L;
    private static final long CHALLENGE = 91L;

    @Test
    void consumeLoserUsesOwnedCurrentReadAndAcceptsOnlyEquivalentConsumedIntent() {
        BankWithdrawalPreflightRequest request = request();
        FundActionPreflightMapper mapper = mock(FundActionPreflightMapper.class);
        when(mapper.selectOwnedByIdForUpdate(CHALLENGE, MEMBER)).thenReturn(row(request, 3));
        assertDoesNotThrow(() -> service(mapper).consumeBankWithdrawal(MEMBER, CHALLENGE, request));
        verify(mapper).selectOwnedByIdForUpdate(CHALLENGE, MEMBER);
        verify(mapper, never()).selectById(anyLong());
    }

    @Test
    void consumeWinnerDoesNotPerformAnyFallbackRead() {
        BankWithdrawalPreflightRequest request = request();
        FundActionPreflightMapper mapper = mock(FundActionPreflightMapper.class);
        when(mapper.consumeIfConfirmed(eq(CHALLENGE), eq(MEMBER), eq("BANK_WITHDRAWAL"),
                eq(request.getRequestId()), anyString(), any(Date.class))).thenReturn(1);
        service(mapper).consumeBankWithdrawal(MEMBER, CHALLENGE, request);
        verify(mapper, never()).selectOwnedByIdForUpdate(anyLong(), anyLong());
        verify(mapper, never()).selectById(anyLong());
    }

    @Test
    void consumedReplayStillRejectsWrongOwnerActionRequestAndFingerprint() {
        for (String mismatch : new String[]{"missing", "owner", "action", "request", "fingerprint"}) {
            BankWithdrawalPreflightRequest request = request();
            FundActionPreflight row = row(request, 3);
            if ("owner".equals(mismatch)) row.setMemberId(8L);
            if ("action".equals(mismatch)) row.setActionType("OTHER_ACTION");
            if ("request".equals(mismatch)) row.setRequestId("different-intent");
            if ("fingerprint".equals(mismatch)) row.setRequestFingerprint("different-fingerprint");
            FundActionPreflightMapper mapper = mock(FundActionPreflightMapper.class);
            when(mapper.selectOwnedByIdForUpdate(CHALLENGE, MEMBER))
                    .thenReturn("missing".equals(mismatch) ? null : row);
            FundActionPreflightException error = assertThrows(FundActionPreflightException.class,
                    () -> service(mapper).consumeBankWithdrawal(MEMBER, CHALLENGE, request));
            assertEquals("missing".equals(mismatch) || "owner".equals(mismatch)
                    ? "PREFLIGHT_NOT_FOUND" : "PREFLIGHT_BINDING_MISMATCH", error.getBusinessCode());
        }
    }

    @Test
    void unconsumedExpiredChallengeRetainsExistingExpiryRejection() {
        BankWithdrawalPreflightRequest request = request();
        FundActionPreflight row = row(request, 1);
        row.setExpiresAt(new Date(System.currentTimeMillis() - 60_000L));
        FundActionPreflightMapper mapper = mock(FundActionPreflightMapper.class);
        when(mapper.selectOwnedByIdForUpdate(CHALLENGE, MEMBER)).thenReturn(row);
        FundActionPreflightException error = assertThrows(FundActionPreflightException.class,
                () -> service(mapper).consumeBankWithdrawal(MEMBER, CHALLENGE, request));
        assertEquals("PREFLIGHT_EXPIRED", error.getBusinessCode());
    }

    private static FundActionPreflightServiceImpl service(FundActionPreflightMapper mapper) {
        return new FundActionPreflightServiceImpl(mapper, new FundChallengeTokenCodec("own-c01-51-fixture-only"),
                120L, new BigDecimal("500.00"));
    }

    private static BankWithdrawalPreflightRequest request() {
        BankWithdrawalPreflightRequest request = new BankWithdrawalPreflightRequest();
        request.setRequestId("own-c01-51-" + UUID.randomUUID().toString());
        request.setAmount(new BigDecimal("50.00"));
        request.setRealname("Fixture User"); request.setBankName("Fixture Bank");
        request.setBankAccount("0000000000000000"); request.setMobilephone("00000000000");
        return request;
    }

    private static FundActionPreflight row(BankWithdrawalPreflightRequest request, int state) {
        FundActionPreflight row = new FundActionPreflight();
        row.setId(CHALLENGE); row.setMemberId(MEMBER); row.setActionType("BANK_WITHDRAWAL");
        row.setRequestId(request.getRequestId());
        row.setRequestFingerprint(FundActionPreflightPolicy.bankWithdrawalFingerprint(MEMBER,
                request.getAmount(), request.getRealname(), request.getBankName(), request.getBankAccount(), request.getMobilephone()));
        row.setState(state); row.setChallengeNonce("fixture-nonce");
        row.setChallengeTokenHash("0000000000000000000000000000000000000000000000000000000000000000");
        Date now = new Date(); row.setContextInitiatedAt(now); row.setRiskLevel("STATIC");
        row.setExpiresAt(new Date(now.getTime() + 600_000L)); row.setAmount(request.getAmount());
        row.setCurrency("CNY"); row.setCreateTime(now); row.setUpdateTime(now);
        return row;
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "CHENGYIN_RUN_OWN_C01_51_MYSQL", matches = "true")
    class RealMysqlRepeatableRead {
        @Test
        void committedWinnerRemainsInvisibleToOldSnapshotButOwnedLockingReadAllowsEquivalentReplay() throws Exception {
            // Gate closed: this vector is disabled, not a claimed pass. Gate open: missing config fails.
            for (String name : new String[]{"CHENGYIN_TEST_MYSQL_HOST", "CHENGYIN_TEST_MYSQL_PORT",
                    "CHENGYIN_TEST_MYSQL_DATABASE_BASE", "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"}) {
                assertNotNull(System.getenv(name), "explicit test environment required: " + name);
            }
            assertEquals("127.0.0.1", System.getenv("CHENGYIN_TEST_MYSQL_HOST"), "only explicit loopback scratch MySQL");
            String suite = "own_c01_51_" + UUID.randomUUID().toString().replace("-", "");
            MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment(suite);
            String database = mysql.databaseName();
            boolean created = false;
            try {
                try (Connection admin = DriverManager.getConnection(mysql.adminUrl(), mysql.user(), mysql.password());
                     Statement statement = admin.createStatement()) {
                    try (ResultSet version = statement.executeQuery("select version()")) {
                        assertTrue(version.next());
                        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(version.getString(1)), "real MySQL >= 8 required");
                    }
                    // No IF NOT EXISTS: never adopt/drop a pre-existing schema.
                    statement.execute("create database `" + database + "`");
                    created = true;
                }
                try (Connection fixture = DriverManager.getConnection(mysql.databaseUrl(), mysql.user(), mysql.password());
                     Statement statement = fixture.createStatement()) {
                    statement.execute(FIXTURE_TABLE);
                }
                Configuration configuration = new Configuration(new Environment("own-c01-51-scratch",
                        new JdbcTransactionFactory(), new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                        mysql.databaseUrl(), mysql.user(), mysql.password())));
                String resource = "mapper/business/FundActionPreflightMapper.xml";
                try (InputStream xml = OWN_C01_51Test.class.getClassLoader().getResourceAsStream(resource)) {
                    assertNotNull(xml);
                    new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
                }
                SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
                BankWithdrawalPreflightRequest request = request();
                FundActionPreflight fixture = row(request, 1);
                try (SqlSession setup = factory.openSession(false)) {
                    assertEquals(1, setup.getMapper(FundActionPreflightMapper.class).insert(fixture));
                    setup.commit();
                }
                Long id = fixture.getId();
                try (SqlSession loser = factory.openSession(TransactionIsolationLevel.REPEATABLE_READ);
                     SqlSession winner = factory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                    assertEquals(Connection.TRANSACTION_REPEATABLE_READ, loser.getConnection().getTransactionIsolation());
                    assertEquals(Connection.TRANSACTION_REPEATABLE_READ, winner.getConnection().getTransactionIsolation());
                    FundActionPreflightMapper oldReader = loser.getMapper(FundActionPreflightMapper.class);
                    FundActionPreflightMapper committedWinner = winner.getMapper(FundActionPreflightMapper.class);
                    assertEquals(1, oldReader.selectById(id).getState().intValue()); // establishes RR read view
                    assertEquals(1, committedWinner.consumeIfConfirmed(id, MEMBER, "BANK_WITHDRAWAL",
                            request.getRequestId(), fixture.getRequestFingerprint(), new Date()));
                    winner.commit();
                    assertEquals(0, oldReader.consumeIfConfirmed(id, MEMBER, "BANK_WITHDRAWAL",
                            request.getRequestId(), fixture.getRequestFingerprint(), new Date()));
                    loser.clearCache(); // force actual SQL; this is not a MyBatis cache demonstration
                    assertEquals(1, oldReader.selectById(id).getState().intValue(), "negative control: RR old snapshot");
                    assertDoesNotThrow(() -> service(oldReader).consumeBankWithdrawal(MEMBER, id, request));
                    assertEquals(3, oldReader.selectOwnedByIdForUpdate(id, MEMBER).getState().intValue());
                    assertNull(oldReader.selectOwnedByIdForUpdate(id, MEMBER + 1), "SQL ownership predicate remains enforced");
                    loser.rollback();
                }
            } finally {
                if (created) {
                    // The exact randomized schema was created above; no configured existing database is touched.
                    try (Connection admin = DriverManager.getConnection(mysql.adminUrl(), mysql.user(), mysql.password());
                         Statement statement = admin.createStatement()) {
                        statement.execute("drop database `" + database + "`");
                        try (java.sql.PreparedStatement check = admin.prepareStatement(
                                "select count(*) from information_schema.schemata where schema_name=?")) {
                            check.setString(1, database);
                            try (ResultSet result = check.executeQuery()) {
                                assertTrue(result.next()); assertEquals(0, result.getInt(1));
                            }
                        }
                    }
                }
            }
        }
    }

    // Task-local fixture schema only; no production migration/API/schema contract change.
    private static final String FIXTURE_TABLE = "create table fund_action_preflight ("
            + "id bigint not null auto_increment primary key, member_id bigint not null, action_type varchar(32) not null,"
            + "request_id varchar(64) not null, request_fingerprint char(64) not null, counterparty_key_hash char(64),"
            + "challenge_nonce varchar(64) not null, challenge_token_hash char(64) not null, state tinyint not null,"
            + "amount decimal(14,2), currency varchar(8), display_name varchar(128), display_account_mask varchar(64),"
            + "display_phone_mask varchar(32),context_initiated_at datetime(3) not null,context_where varchar(128),"
            + "context_device varchar(64),risk_level varchar(16),risk_reason_codes varchar(500),expires_at datetime(3) not null,"
            + "confirmed_at datetime(3),rejected_at datetime(3),consumed_at datetime(3),create_time datetime(3),update_time datetime(3),"
            + "unique key uk_intent(member_id,action_type,request_id)) engine=InnoDB";
}
