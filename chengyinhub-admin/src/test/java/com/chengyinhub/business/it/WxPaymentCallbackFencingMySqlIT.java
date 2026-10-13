package com.chengyinhub.business.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

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
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.WxPaymentCallbackInbox;
import com.chengyinhub.business.domain.WxPaymentCallbackOutbox;
import com.chengyinhub.business.mapper.WxPaymentCallbackInboxMapper;
import com.chengyinhub.business.mapper.WxPaymentCallbackOutboxMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.WxPaymentCallbackInboxServiceImpl;
import com.chengyinhub.business.service.impl.WxPaymentCallbackOutboxServiceImpl;

/**
 * 真实 MySQL 8 + 真实 migration + 真实 Mapper 的支付回调 fencing Gate。
 * Docker 不可用时不引入 Testcontainers;沿用资金 IT 统一约定(CHENGYIN_FUNDS_MYSQL_IT +
 * MysqlIntegrationTestConfig 唯一临时 schema,由 ci/funds-mysql-it-gate.sh 点名执行)。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class WxPaymentCallbackFencingMySqlIT
{
    private static final String NOTIFY_HASH = WxPaymentCallbackInboxServiceImpl.sha256Hex("wx-notify-it-1");
    private static final String OWNER_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OWNER_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("wx_payment_fencing");

    private PooledDataSource dataSource;
    private SqlSessionFactory factory;

    @BeforeAll
    static void createDatabase() throws Exception
    {
        try (Connection admin = DriverManager.getConnection(MYSQL.adminUrl(), MYSQL.user(), MYSQL.password());
             Statement statement = admin.createStatement())
        {
            // 先 DROP 再 CREATE:不接受"环境里恰好剩下一个同名库",隔离必须从零开始。
            statement.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            statement.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
    }

    @AfterAll
    static void dropDatabase() throws Exception
    {
        try (Connection admin = DriverManager.getConnection(MYSQL.adminUrl(), MYSQL.user(), MYSQL.password());
             Statement statement = admin.createStatement())
        {
            statement.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    @BeforeEach
    void setUp() throws Exception
    {
        dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver",
                MYSQL.databaseUrl(), MYSQL.user(), MYSQL.password());
        try (Connection probe = dataSource.getConnection())
        {
            probe.isValid(2);
        }
        catch (Exception unavailable)
        {
            // 门控已开就代表「这轮要跑」,连不上库必须红,不能装作跑过。
            throw new IllegalStateException(
                    "已开启 CHENGYIN_FUNDS_MYSQL_IT 但 IT 库不可达,不得跳过:"
                            + unavailable.getMessage(), unavailable);
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement())
        {
            // 仅本地专用 IT 库:先删后建,确保 Gate 验的是当前 migration,而不是旧残留表。
            statement.execute("DROP TABLE IF EXISTS wx_payment_callback_inbox");
            statement.execute("DROP TABLE IF EXISTS wx_payment_callback_outbox");
            executeMigration(statement);
        }
        factory = mapperFactory();
    }

    @AfterEach
    void close()
    {
        if (dataSource != null) dataSource.forceCloseAll();
    }

    @Test
    void duplicateNotifyDeliveryLandsExactlyOneInboxRow() throws Exception
    {
        try (SqlSession session = factory.openSession(true))
        {
            WxPaymentCallbackInboxMapper mapper = session.getMapper(WxPaymentCallbackInboxMapper.class);
            WxPaymentCallbackInboxServiceImpl service = new WxPaymentCallbackInboxServiceImpl();
            ReflectionTestUtils.setField(service, "mapper", mapper);

            assertTrue(service.register("wx-notify-it-1", WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT, 7L,
                    "TRANSACTION.SUCCESS"));
            assertFalse(service.register("wx-notify-it-1", WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT, 7L,
                    "TRANSACTION.SUCCESS"));

            assertNotNull(mapper.selectByNotifyHash(NOTIFY_HASH));
            assertEquals(1, count("wx_payment_callback_inbox"), "同一 notify_hash 重复投递必须只落一行");
            assertTrue(service.isRegistered("wx-notify-it-1"));
            assertFalse(service.isRegistered("wx-notify-other"));
        }
    }

    @Test
    void expiredLeaseTakeoverFencesOldOwnerFromDoneAndRelease() throws Exception
    {
        seedProcessing(OWNER_A, 1, -1);
        try (SqlSession session = factory.openSession(false))
        {
            WxPaymentCallbackInboxMapper mapper = session.getMapper(WxPaymentCallbackInboxMapper.class);
            mapper.selectForUpdate(NOTIFY_HASH);
            assertEquals(1, mapper.claim(NOTIFY_HASH, OWNER_B));
            WxPaymentCallbackInbox claimed = mapper.selectForUpdate(NOTIFY_HASH);
            assertEquals(2L, claimed.getFenceToken());
            session.commit();
        }
        try (SqlSession session = factory.openSession(true))
        {
            WxPaymentCallbackInboxMapper mapper = session.getMapper(WxPaymentCallbackInboxMapper.class);
            assertEquals(0, mapper.markDone(NOTIFY_HASH, OWNER_A, 1));
            assertEquals(0, mapper.release(NOTIFY_HASH, OWNER_A, 1));
            assertEquals(1, mapper.markDone(NOTIFY_HASH, OWNER_B, 2));
        }
    }

    @Test
    void duplicateOutboxEnqueueLandsExactlyOneRow() throws Exception
    {
        try (SqlSession session = factory.openSession(true))
        {
            WxPaymentCallbackOutboxServiceImpl service = outboxService(
                    session.getMapper(WxPaymentCallbackOutboxMapper.class));

            assertTrue(service.enqueue(WxPaymentCallbackOutbox.BIZ_TYPE_PAYMENT_QUERY, 7L, "OTN-1", false));
            assertFalse(service.enqueue(WxPaymentCallbackOutbox.BIZ_TYPE_PAYMENT_QUERY, 7L, "OTN-1", false));

            assertEquals(1, count("wx_payment_callback_outbox"), "同一 idempotency_key 重复登记必须只落一行");
        }
    }

    @Test
    void moneyAffectingOutboxIsNeverAutoClaimed() throws Exception
    {
        try (SqlSession session = factory.openSession(true))
        {
            WxPaymentCallbackOutboxMapper mapper = session.getMapper(WxPaymentCallbackOutboxMapper.class);
            WxPaymentCallbackOutboxServiceImpl service = outboxService(mapper);

            assertTrue(service.enqueue(WxPaymentCallbackOutbox.BIZ_TYPE_TRANSFER_QUERY, 9L, "BATCH-M-1", true));
            assertTrue(service.enqueue(WxPaymentCallbackOutbox.BIZ_TYPE_PAYMENT_QUERY, 7L, "OTN-2", false));
            assertEquals(WxPaymentCallbackOutbox.STATE_MANUAL_REQUIRED, stateOf("BATCH-M-1"),
                    "涉及金额变化的动作登记即人工态,不进自动派发队列");

            // 对抗性 fixture:模拟一条绕过 enqueue 直接落 pending 的资金行(历史数据/未来改动),
            // claim 必须同时被 SQL 与 Java 两层拒收 —— 只靠「登记即人工态」不是真防线。
            seedMoneyAffectingPending("BATCH-M-0");

            List<WxPaymentCallbackOutbox> claimed = service.claimForRetry(10);
            assertEquals(1, claimed.size());
            assertEquals("OTN-2", claimed.get(0).getIdempotencyKey());

            assertEquals(WxPaymentCallbackOutbox.STATE_MANUAL_REQUIRED, stateOf("BATCH-M-1"));
            assertEquals(WxPaymentCallbackOutbox.STATE_PENDING, stateOf("BATCH-M-0"),
                    "pending 的资金行也不能被自动领取");
            forceDue("BATCH-M-1");
            assertTrue(service.claimForRetry(10).isEmpty(),
                    "money_affecting=1 的记录即使到期也不得被自动领取");
            assertEquals(WxPaymentCallbackOutbox.STATE_MANUAL_REQUIRED, stateOf("BATCH-M-1"));
            assertEquals(WxPaymentCallbackOutbox.STATE_PENDING, stateOf("BATCH-M-0"));
        }
    }

    @Test
    void autoRetryStopsAtMaxAttemptsAndEscalatesToManual() throws Exception
    {
        try (SqlSession session = factory.openSession(true))
        {
            WxPaymentCallbackOutboxMapper mapper = session.getMapper(WxPaymentCallbackOutboxMapper.class);
            WxPaymentCallbackOutboxServiceImpl service = outboxService(mapper);
            assertTrue(service.enqueue(WxPaymentCallbackOutbox.BIZ_TYPE_PAYMENT_QUERY, 7L, "OTN-3", false));

            WxPaymentCallbackOutbox first = service.claimForRetry(10).get(0);
            assertEquals(1, first.getAttemptCount());
            assertTrue(service.retryOrEscalate(first.getId(), first.getOwnerToken(), first.getFenceToken(),
                    "WX_TIMEOUT_1"));
            assertEquals(WxPaymentCallbackOutbox.STATE_RETRYABLE, stateOf("OTN-3"));

            forceDue("OTN-3");
            WxPaymentCallbackOutbox second = service.claimForRetry(10).get(0);
            assertEquals(2, second.getAttemptCount());
            assertTrue(service.retryOrEscalate(second.getId(), second.getOwnerToken(), second.getFenceToken(),
                    "WX_TIMEOUT_2"));

            forceDue("OTN-3");
            WxPaymentCallbackOutbox third = service.claimForRetry(10).get(0);
            assertEquals(3, third.getAttemptCount());
            assertTrue(service.retryOrEscalate(third.getId(), third.getOwnerToken(), third.getFenceToken(),
                    "WX_TIMEOUT_3"));

            assertEquals(WxPaymentCallbackOutbox.STATE_MANUAL_REQUIRED, stateOf("OTN-3"),
                    "达到 max_attempts=3 必须转人工,不允许无限自动重试");
            forceDue("OTN-3");
            assertTrue(service.claimForRetry(10).isEmpty(), "人工态记录不得再被自动领取");
        }
    }

    private WxPaymentCallbackOutboxServiceImpl outboxService(WxPaymentCallbackOutboxMapper mapper)
    {
        WxPaymentCallbackOutboxServiceImpl service = new WxPaymentCallbackOutboxServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        return service;
    }

    private SqlSessionFactory mapperFactory() throws Exception
    {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("it", new JdbcTransactionFactory(), dataSource));
        for (String resource : new String[] {
                "mapper/business/WxPaymentCallbackInboxMapper.xml",
                "mapper/business/WxPaymentCallbackOutboxMapper.xml" })
        {
            try (InputStream input = Resources.getResourceAsStream(resource))
            {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void executeMigration(Statement statement) throws Exception
    {
        Path migration = Paths.get("sql/migration_wx_payment_callback_inbox_outbox_20260725.sql");
        if (!Files.exists(migration))
        {
            migration = Paths.get(
                    "chengyinhub-admin/sql/migration_wx_payment_callback_inbox_outbox_20260725.sql");
        }
        StringBuilder ddl = new StringBuilder();
        for (String line : Files.readAllLines(migration, StandardCharsets.UTF_8))
        {
            if (!line.trim().startsWith("--")) ddl.append(line).append('\n');
        }
        // 只在「行尾分号」断句:COMMENT 字符串里有分号(如「仅查询类;涉及金额变化」),
        // 按裸分号拆会把字符串劈成非法 SQL。
        for (String statementSql : ddl.toString().split(";\\s*\\n"))
        {
            if (!statementSql.trim().isEmpty()) statement.execute(statementSql);
        }
    }

    private void seedProcessing(String owner, long fence, int leaseSeconds) throws Exception
    {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO wx_payment_callback_inbox "
                    + "(notify_hash,biz_type,biz_id,event_type,state,owner_token,fence_token,lease_until,attempt_count) VALUES "
                    + "(UNHEX('" + NOTIFY_HASH + "'),1,7,'TRANSACTION.SUCCESS',1,UNHEX('" + owner + "')," + fence
                    + ",DATE_ADD(NOW(3),INTERVAL " + leaseSeconds + " SECOND),1)");
        }
    }

    private void seedMoneyAffectingPending(String idempotencyKey) throws Exception
    {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO wx_payment_callback_outbox "
                    + "(biz_type,biz_id,idempotency_key,money_affecting,state,fence_token,attempt_count,max_attempts,"
                    + "next_attempt_at,create_time,update_time) VALUES "
                    + "(3,9,'" + idempotencyKey + "',1,0,0,0,3,"
                    + "DATE_SUB(NOW(3), INTERVAL 1 SECOND),NOW(3),NOW(3))");
        }
    }

    private void forceDue(String idempotencyKey) throws Exception
    {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE wx_payment_callback_outbox "
                    + "SET next_attempt_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) "
                    + "WHERE idempotency_key = '" + idempotencyKey + "'");
        }
    }

    private int stateOf(String idempotencyKey) throws Exception
    {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT state FROM wx_payment_callback_outbox "
                     + "WHERE idempotency_key = '" + idempotencyKey + "'"))
        {
            assertTrue(result.next(), "期望存在出站记录:" + idempotencyKey);
            return result.getInt(1);
        }
    }

    private int count(String table) throws Exception
    {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table))
        {
            result.next();
            return result.getInt(1);
        }
    }

}
