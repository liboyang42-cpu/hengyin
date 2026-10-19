package com.chengyinhub.business.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Date;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.chengyinhub.business.domain.NpcChatRequest;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;

/** M0146/M0147: NPC request intent/lease 的真实 MySQL migration + Mapper 行为门禁。 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class NpcChatRequestMySqlIT
{
    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("npc_chat_request");
    private static final long NOW = System.currentTimeMillis();

    private PooledDataSource dataSource;
    private SqlSessionFactory factory;

    @BeforeAll
    static void createDatabase() throws Exception
    {
        try (Connection connection = DriverManager.getConnection(MYSQL.adminUrl(), MYSQL.user(), MYSQL.password());
             Statement statement = connection.createStatement())
        {
            statement.execute("DROP DATABASE IF EXISTS `" + MYSQL.databaseName() + "`");
            statement.execute("CREATE DATABASE `" + MYSQL.databaseName() + "` CHARACTER SET utf8mb4");
        }
    }

    @AfterAll
    static void dropDatabase() throws Exception
    {
        try (Connection connection = DriverManager.getConnection(MYSQL.adminUrl(), MYSQL.user(), MYSQL.password());
             Statement statement = connection.createStatement())
        {
            statement.execute("DROP DATABASE IF EXISTS `" + MYSQL.databaseName() + "`");
        }
    }

    @BeforeEach
    void setUp() throws Exception
    {
        dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver",
                MYSQL.databaseUrl(), MYSQL.user(), MYSQL.password());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement())
        {
            statement.execute("DROP TABLE IF EXISTS npc_chat_request");
            executeMigration(statement, Paths.get("chengyinhub-admin/sql/migration_npc_chat_request_20260722.sql"));
            executeMigration(statement, Paths.get("sql/migration_20261012_bottomc_npc_chat_intent.sql"));
        }
        factory = mapperFactory();
    }

    @AfterEach
    void close()
    {
        if (dataSource != null) dataSource.forceCloseAll();
    }

    @Test
    void fingerprintScopeRoundTrips()
    {
        try (SqlSession session = factory.openSession(true))
        {
            NpcChatRequestMapper mapper = session.getMapper(NpcChatRequestMapper.class);
            NpcChatRequest request = processing(7L, 70L, "round-trip", "lease-a", -60_000L);
            request.setIntentHash(repeat('a'));
            request.setScopeType(2);
            request.setScopeId(701L);
            request.setScene("merchant_npc_chat");
            request.setProfileVersion("profile-v3");
            assertEquals(1, mapper.insertNpcChatRequest(request));

            NpcChatRequest stored = mapper.selectByUserAndRequestId(7L, "round-trip");
            assertEquals(repeat('a'), stored.getIntentHash());
            assertEquals(2, stored.getScopeType());
            assertEquals(701L, stored.getScopeId());
            assertEquals("merchant_npc_chat", stored.getScene());
            assertEquals("profile-v3", stored.getProfileVersion());
            assertEquals("NOT_STARTED", stored.getProviderState());
        }
    }

    @Test
    void lostOrExpiredLeaseCannotConditionallyUpdate()
    {
        try (SqlSession session = factory.openSession(true))
        {
            NpcChatRequestMapper mapper = session.getMapper(NpcChatRequestMapper.class);
            NpcChatRequest active = processing(8L, 80L, "active", "owner", 120_000L);
            assertEquals(1, mapper.insertNpcChatRequest(active));

            NpcChatRequest staleFinish = terminal(8L, "active", "stale-owner");
            assertEquals(0, mapper.finishIfLeaseOwned(staleFinish));
            assertEquals(0, mapper.renewLease(8L, "active", "stale-owner", new Date(NOW + 240_000L)));
            assertEquals(0, mapper.markProviderStarted(8L, "active", "stale-owner"));

            NpcChatRequest expiredStart = processing(8L, 81L, "expired-start", "expired-start-owner", -120_000L);
            assertEquals(1, mapper.insertNpcChatRequest(expiredStart));
            assertEquals(0, mapper.markProviderStarted(8L, "expired-start", "expired-start-owner"),
                    "即使 token 相同，过期 lease 也不得进入 provider");

            NpcChatRequest expiredFinish = processing(8L, 82L, "expired-finish", "expired-finish-owner", -120_000L);
            assertEquals(1, mapper.insertNpcChatRequest(expiredFinish));
            assertEquals(0, mapper.finishIfLeaseOwned(terminal(8L, "expired-finish", "expired-finish-owner")),
                    "过期 lease owner 不得写入迟到终态");

            NpcChatRequest expiredRenew = processing(8L, 83L, "expired-renew", "expired-renew-owner", -120_000L);
            assertEquals(1, mapper.insertNpcChatRequest(expiredRenew));
            assertEquals(0, mapper.renewLease(8L, "expired-renew", "expired-renew-owner",
                    new Date(NOW + 240_000L)), "过期 lease 不得被原 owner 复活");
            assertEquals("PROCESSING", mapper.selectByUserAndRequestId(8L, "active").getStatus());
        }
    }

    @Test
    void onlyExpiredNotStartedRequestIsReclaimed()
    {
        try (SqlSession session = factory.openSession(true))
        {
            NpcChatRequestMapper mapper = session.getMapper(NpcChatRequestMapper.class);
            NpcChatRequest expired = processing(9L, 90L, "expired-not-started", "owner-a", -120_000L);
            assertEquals(1, mapper.insertNpcChatRequest(expired));
            assertEquals(1, mapper.expireProcessingByUserAndSession(9L, 90L, "request timed out"));

            NpcChatRequest stored = mapper.selectByUserAndRequestId(9L, "expired-not-started");
            assertEquals("FAILED", stored.getStatus());
            assertEquals("REQUEST_TIMEOUT", stored.getErrorCode());
            assertEquals(Boolean.TRUE, stored.getRetryable());
            assertNull(stored.getLeaseToken());
            assertNull(stored.getActiveSlot());
        }
    }

    @Test
    void startedAndLegacyRequestsAreNotAutomaticallyRecovered()
    {
        try (SqlSession session = factory.openSession(true))
        {
            NpcChatRequestMapper mapper = session.getMapper(NpcChatRequestMapper.class);
            NpcChatRequest started = processing(10L, 100L, "started", "owner-s", -120_000L);
            started.setProviderState("STARTED");
            assertEquals(1, mapper.insertNpcChatRequest(started));
            assertEquals(0, mapper.expireProcessingByUserAndSession(10L, 100L, "must stay unknown"));
            assertEquals("PROCESSING", mapper.selectByUserAndRequestId(10L, "started").getStatus());

            NpcChatRequest legacy = processing(11L, 110L, "legacy", "owner-l", -120_000L);
            legacy.setIntentHash(null);
            assertEquals(1, mapper.insertNpcChatRequest(legacy));
            assertEquals(0, mapper.expireProcessingByUserAndSession(11L, 110L, "must stay unknown"));
            NpcChatRequest storedLegacy = mapper.selectByUserAndRequestId(11L, "legacy");
            assertEquals("PROCESSING", storedLegacy.getStatus());
            assertNull(storedLegacy.getIntentHash());
        }
    }

    private NpcChatRequest processing(Long userId, Long sessionId, String requestId,
                                      String leaseToken, long leaseOffsetMillis)
    {
        NpcChatRequest request = new NpcChatRequest();
        request.setUserId(userId);
        request.setSessionId(sessionId);
        request.setRequestId(requestId);
        request.setIntentHash(repeat('b'));
        request.setScopeType(3);
        request.setScopeId(300L);
        request.setScene("npc_chat");
        request.setProfileVersion("profile-v1");
        request.setProviderState("NOT_STARTED");
        request.setStatus("PROCESSING");
        request.setSafetyDecision("NOT_RUN");
        request.setRetryAfterSeconds(0);
        request.setRetryable(false);
        request.setAi(true);
        request.setLeaseToken(leaseToken);
        request.setLeaseUntil(new Date(System.currentTimeMillis() + leaseOffsetMillis));
        request.setActiveSlot(1);
        return request;
    }

    private NpcChatRequest terminal(Long userId, String requestId, String leaseToken)
    {
        NpcChatRequest request = new NpcChatRequest();
        request.setUserId(userId);
        request.setRequestId(requestId);
        request.setLeaseToken(leaseToken);
        request.setStatus("SUCCEEDED");
        request.setSafetyDecision("PASS");
        request.setRetryAfterSeconds(0);
        request.setRetryable(false);
        request.setSafeText("safe");
        request.setAi(true);
        return request;
    }

    private String repeat(char character)
    {
        StringBuilder value = new StringBuilder(64);
        for (int i = 0; i < 64; i++) value.append(character);
        return value.toString();
    }

    private SqlSessionFactory mapperFactory() throws Exception
    {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("it", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/NpcChatRequestMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource))
        {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void executeMigration(Statement statement, Path path) throws Exception
    {
        if (!Files.exists(path)) path = Paths.get("..").resolve(path);
        StringBuilder sql = new StringBuilder();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8))
        {
            if (!line.trim().startsWith("--")) sql.append(line).append('\n');
        }
        for (String item : sql.toString().split(";\\s*\\n"))
        {
            if (!item.trim().isEmpty()) statement.execute(item);
        }
    }
}
