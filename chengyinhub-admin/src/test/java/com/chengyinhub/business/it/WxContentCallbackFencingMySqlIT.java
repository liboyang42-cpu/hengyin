package com.chengyinhub.business.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.WxContentCallbackInboxServiceImpl;

/**
 * 真实 MySQL 8 + 真实 migration + 真实 Mapper 并发 Gate。
 * Docker 不可用时不引入 Testcontainers；沿用资金 IT 统一约定(CHENGYIN_FUNDS_MYSQL_IT +
 * MysqlIntegrationTestConfig 唯一临时 schema,由 ci/funds-mysql-it-gate.sh 点名执行)。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class WxContentCallbackFencingMySqlIT
{
    private static final String TRACE_HASH = WxContentCallbackInboxServiceImpl.sha256Hex("trace");
    private static final String OWNER_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OWNER_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("wx_content_fencing");

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
        // migration/Mapper/SQL 缺陷必须原样抛出让 Gate 变红。
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement())
        {
            // 仅本地专用 IT 库：先删后建，确保 Gate 验的是当前 migration，而不是旧残留表。
            statement.execute("DROP TABLE IF EXISTS wx_content_callback_inbox");
            executeMigration(statement);
            statement.execute("CREATE TABLE IF NOT EXISTS wx_content_callback_target_gate ("
                    + "id BIGINT NOT NULL PRIMARY KEY, status INT NOT NULL) ENGINE=InnoDB");
            statement.execute("DELETE FROM wx_content_callback_target_gate");
        }
        factory = mapperFactory();
    }

    @AfterEach
    void close()
    {
        if (dataSource != null) dataSource.forceCloseAll();
    }

    @Test
    void expiredLeaseTakeoverFencesOldOwnerFromDoneAndRelease() throws Exception
    {
        seedProcessing(OWNER_A, 1, -1);
        try (SqlSession session = factory.openSession(false))
        {
            WxContentCallbackInboxMapper mapper = session.getMapper(WxContentCallbackInboxMapper.class);
            mapper.selectForUpdate(TRACE_HASH);
            assertEquals(1, mapper.claim(TRACE_HASH, OWNER_B, 1));
            WxContentCallbackInbox claimed = mapper.selectForUpdate(TRACE_HASH);
            assertEquals(2L, claimed.getFenceToken());
            session.commit();
        }
        try (SqlSession session = factory.openSession(true))
        {
            WxContentCallbackInboxMapper mapper = session.getMapper(WxContentCallbackInboxMapper.class);
            assertEquals(0, mapper.markDone(TRACE_HASH, OWNER_A, 1, 1));
            assertEquals(0, mapper.release(TRACE_HASH, OWNER_A, 1));
            assertEquals(1, mapper.markDone(TRACE_HASH, OWNER_B, 2, 1));
        }
    }

    @Test
    void realMapperRowLockBlocksTakeoverThenForcesItToObserveDone() throws Exception
    {
        seedProcessing(OWNER_A, 1, 1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (SqlSession owner = factory.openSession(false))
        {
            WxContentCallbackInboxMapper ownerMapper = owner.getMapper(WxContentCallbackInboxMapper.class);
            WxContentCallbackInbox owned = ownerMapper.selectOwnedForUpdate(TRACE_HASH, OWNER_A, 1);
            assertEquals(1L, owned.getFenceToken());
            Thread.sleep(1100L); // 租约在事务持锁期间过期；接管仍必须等该事务结束。
            Future<Integer> takeover = pool.submit(() -> {
                try (SqlSession other = factory.openSession(true))
                {
                    return other.getMapper(WxContentCallbackInboxMapper.class).claim(TRACE_HASH, OWNER_B, 1);
                }
            });
            Thread.sleep(150L);
            assertFalse(takeover.isDone(), "并发接管必须被真实 Mapper 的 inbox 行锁阻塞");
            assertEquals(1, ownerMapper.markDone(TRACE_HASH, OWNER_A, 1, 1));
            owner.commit();
            assertEquals(0, takeover.get(3, TimeUnit.SECONDS), "完成后接管必须观察 DONE 并失败");
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    @Test
    void businessMutationRollsBackWhenRealMapperDoneCasLosesOwnership() throws Exception
    {
        seedProcessing(OWNER_B, 2, 60);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO wx_content_callback_target_gate VALUES (7,1)");
        }
        try (SqlSession session = factory.openSession(false))
        {
            WxContentCallbackInboxMapper mapper = session.getMapper(WxContentCallbackInboxMapper.class);
            mapper.selectForUpdate(TRACE_HASH);
            try (Statement statement = session.getConnection().createStatement())
            {
                statement.executeUpdate("UPDATE wx_content_callback_target_gate SET status=2 WHERE id=7");
            }
            assertEquals(0, mapper.markDone(TRACE_HASH, OWNER_A, 1, 1));
            session.rollback();
        }
        try (Connection verify = dataSource.getConnection(); Statement statement = verify.createStatement();
             ResultSet result = statement.executeQuery("SELECT status FROM wx_content_callback_target_gate WHERE id=7"))
        {
            result.next();
            assertEquals(1, result.getInt(1), "DONE 失败必须让同事务业务 mutation 一起回滚");
        }
    }

    private SqlSessionFactory mapperFactory() throws Exception
    {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("it", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/WxContentCallbackInboxMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource))
        {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void executeMigration(Statement statement) throws Exception
    {
        Path migration = Paths.get("sql/migration_wx_content_callback_inbox_20260724.sql");
        if (!Files.exists(migration))
        {
            migration = Paths.get("chengyinhub-admin/sql/migration_wx_content_callback_inbox_20260724.sql");
        }
        StringBuilder ddl = new StringBuilder();
        for (String line : Files.readAllLines(migration, StandardCharsets.UTF_8))
        {
            if (!line.trim().startsWith("--")) ddl.append(line).append('\n');
        }
        statement.execute(ddl.toString());
    }

    private void seedProcessing(String owner, long fence, int leaseSeconds) throws Exception
    {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO wx_content_callback_inbox "
                    + "(trace_hash,biz_type,biz_id,verdict,state,owner_token,fence_token,lease_until,attempt_count) VALUES "
                    + "(UNHEX('" + TRACE_HASH + "'),9,7,1,1,UNHEX('" + owner + "')," + fence
                    + ",DATE_ADD(NOW(3),INTERVAL " + leaseSeconds + " SECOND),1)");
        }
    }
}
