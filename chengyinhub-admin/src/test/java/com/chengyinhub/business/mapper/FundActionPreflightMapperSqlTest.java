package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.FundActionPreflight;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FundActionPreflightMapperSqlTest {

    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:fund_preflight_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(FundActionPreflightMapper.class);
        String resource = "mapper/business/FundActionPreflightMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true); Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table fund_action_preflight("
                    + "id bigint auto_increment primary key,member_id bigint not null,action_type varchar(32) not null,"
                    + "request_id varchar(64) not null,request_fingerprint char(64) not null,"
                    + "counterparty_key_hash char(64),challenge_nonce varchar(64) not null,challenge_token_hash char(64) not null,"
                    + "state tinyint not null,amount decimal(14,2),currency varchar(8) not null,"
                    + "display_name varchar(128),display_account_mask varchar(64),display_phone_mask varchar(32),"
                    + "context_initiated_at timestamp(3) not null,context_where varchar(128),context_device varchar(64),"
                    + "risk_level varchar(16) not null,risk_reason_codes varchar(500),"
                    + "expires_at timestamp(3) not null,confirmed_at timestamp(3),rejected_at timestamp(3),consumed_at timestamp(3),"
                    + "create_time timestamp(3) not null,update_time timestamp(3) not null,"
                    + "unique(member_id,action_type,request_id),unique(challenge_token_hash))");
        }
    }

    @Test
    void confirmRequiresExactBindingFreshnessAndPendingState() {
        try (SqlSession session = factory.openSession(true)) {
            FundActionPreflightMapper mapper = session.getMapper(FundActionPreflightMapper.class);
            FundActionPreflight row = pending("intent-1", "fingerprint-1", "token-1", 60_000L);
            assertEquals(1, mapper.insert(row));
            Date now = new Date();

            assertEquals(0, mapper.confirmIfPending(row.getId(), 999L, "BANK_WITHDRAWAL", "intent-1",
                    "fingerprint-1", "token-1", now));
            assertEquals(0, mapper.confirmIfPending(row.getId(), 9201L, "BANK_WITHDRAWAL", "intent-1",
                    "wrong-fingerprint", "token-1", now));
            assertEquals(0, mapper.confirmIfPending(row.getId(), 9201L, "BANK_WITHDRAWAL", "intent-1",
                    "fingerprint-1", "wrong-token", now));
            assertEquals(1, mapper.confirmIfPending(row.getId(), 9201L, "BANK_WITHDRAWAL", "intent-1",
                    "fingerprint-1", "token-1", now));
            assertEquals(0, mapper.confirmIfPending(row.getId(), 9201L, "BANK_WITHDRAWAL", "intent-1",
                    "fingerprint-1", "token-1", now));

            FundActionPreflight expired = pending("intent-expired", "fingerprint-2", "token-2", -60_000L);
            mapper.insert(expired);
            assertEquals(0, mapper.confirmIfPending(expired.getId(), 9201L, "BANK_WITHDRAWAL", "intent-expired",
                    "fingerprint-2", "token-2", now));
        }
    }

    @Test
    void rejectAndConsumeEachHaveOneCasWinner() {
        try (SqlSession session = factory.openSession(true)) {
            FundActionPreflightMapper mapper = session.getMapper(FundActionPreflightMapper.class);
            Date now = new Date();
            FundActionPreflight confirmed = pending("intent-consume", "fingerprint-3", "token-3", 60_000L);
            mapper.insert(confirmed);
            assertEquals(1, mapper.confirmIfPending(confirmed.getId(), 9201L, "BANK_WITHDRAWAL", "intent-consume",
                    "fingerprint-3", "token-3", now));
            assertEquals(1, mapper.consumeIfConfirmed(confirmed.getId(), 9201L, "BANK_WITHDRAWAL", "intent-consume",
                    "fingerprint-3", now));
            assertEquals(0, mapper.consumeIfConfirmed(confirmed.getId(), 9201L, "BANK_WITHDRAWAL", "intent-consume",
                    "fingerprint-3", now));

            FundActionPreflight rejected = pending("intent-reject", "fingerprint-4", "token-4", 60_000L);
            mapper.insert(rejected);
            assertEquals(1, mapper.rejectIfPending(rejected.getId(), 9201L, "token-4", now));
            assertEquals(0, mapper.rejectIfPending(rejected.getId(), 9201L, "token-4", now));
            assertEquals(0, mapper.confirmIfPending(rejected.getId(), 9201L, "BANK_WITHDRAWAL", "intent-reject",
                    "fingerprint-4", "token-4", now));
        }
    }

    @Test
    void serverBlockedRiskCannotBeConfirmedByDirectApiCall() {
        try (SqlSession session = factory.openSession(true)) {
            FundActionPreflightMapper mapper = session.getMapper(FundActionPreflightMapper.class);
            FundActionPreflight blocked = pending("intent-blocked", "fingerprint-blocked", "token-blocked", 60_000L);
            blocked.setRiskLevel("BLOCKED");
            mapper.insert(blocked);

            assertEquals(0, mapper.confirmIfPending(blocked.getId(), 9201L, "BANK_WITHDRAWAL", "intent-blocked",
                    "fingerprint-blocked", "token-blocked", new Date()));
        }
    }

    @Test
    void expiredRearmRotatesChallengeWithoutReplacingOriginalOperationContext() {
        try (SqlSession session = factory.openSession(true)) {
            FundActionPreflightMapper mapper = session.getMapper(FundActionPreflightMapper.class);
            FundActionPreflight expired = pending("intent-rearm", "fingerprint-rearm", "token-old", -60_000L);
            Date originalWhen = new Date(System.currentTimeMillis() - 120_000L);
            expired.setContextInitiatedAt(originalWhen);
            expired.setContextWhere("原始位置");
            expired.setContextDevice("原始设备");
            mapper.insert(expired);
            Date now = new Date();

            assertEquals(1, mapper.rearmExpired(expired.getId(), 9201L, FundActionPreflight.STATE_PENDING,
                    "nonce-new", "token-new", new Date(now.getTime() + 60_000L), now));
            FundActionPreflight rearmed = mapper.selectById(expired.getId());
            assertEquals(originalWhen, rearmed.getContextInitiatedAt());
            assertEquals("原始位置", rearmed.getContextWhere());
            assertEquals("原始设备", rearmed.getContextDevice());
        }
    }

    private FundActionPreflight pending(String requestId, String fingerprint, String tokenHash, long ttlMillis) {
        Date now = new Date();
        FundActionPreflight row = new FundActionPreflight();
        row.setMemberId(9201L);
        row.setActionType("BANK_WITHDRAWAL");
        row.setRequestId(requestId);
        row.setRequestFingerprint(fingerprint);
        row.setChallengeNonce("nonce-" + requestId);
        row.setChallengeTokenHash(tokenHash);
        row.setState(FundActionPreflight.STATE_PENDING);
        row.setCurrency("CNY");
        row.setRiskLevel("STATIC");
        row.setContextInitiatedAt(now);
        row.setExpiresAt(new Date(now.getTime() + ttlMillis));
        row.setCreateTime(now);
        row.setUpdateTime(now);
        return row;
    }
}
