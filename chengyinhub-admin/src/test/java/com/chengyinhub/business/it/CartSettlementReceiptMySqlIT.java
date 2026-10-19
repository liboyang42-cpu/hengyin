package com.chengyinhub.business.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import com.chengyinhub.business.domain.CartSettlementReceipt;
import com.chengyinhub.business.mapper.CartSettlementReceiptMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;

/** M0137/M0139: cart settlement receipt 的真实 MySQL migration + Mapper 行为门禁。 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class CartSettlementReceiptMySqlIT
{
    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("cart_settlement_receipt");

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
            statement.execute("DROP TABLE IF EXISTS oms_cart_settlement_order");
            statement.execute("DROP TABLE IF EXISTS oms_cart_settlement_receipt");
            executeMigration(statement, Paths.get("sql/migration_20261012_bottoma_cart_settlement_receipt.sql"));
        }
        factory = mapperFactory();
    }

    @AfterEach
    void close()
    {
        if (dataSource != null) dataSource.forceCloseAll();
    }

    @Test
    void requestIdIsCaseSensitiveAndMemberScoped()
    {
        try (SqlSession session = factory.openSession(true))
        {
            CartSettlementReceiptMapper mapper = session.getMapper(CartSettlementReceiptMapper.class);
            assertEquals(1, mapper.insertRequest(receipt(11L, "Claim-A", 'a')));
            assertEquals(1, mapper.insertRequest(receipt(11L, "claim-a", 'b')),
                    "ascii_bin 必须把大小写不同的 requestId 视为两个请求");
            assertEquals(1, mapper.insertRequest(receipt(12L, "Claim-A", 'c')),
                    "requestId 的唯一性必须限定在 member 内");

            assertNotNull(mapper.selectOwned(11L, "Claim-A"));
            assertNotNull(mapper.selectOwned(11L, "claim-a"));
            assertNotNull(mapper.selectOwned(12L, "Claim-A"));
            assertNull(mapper.selectOwned(12L, "claim-a"), "不得读取其他 member 的回执");
        }
    }

    @Test
    void concurrentClaimHasExactlyOneWinner() throws Exception
    {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Future<Integer> first = pool.submit(() -> claimOnce(ready, start));
            Future<Integer> second = pool.submit(() -> claimOnce(ready, start));
            ready.await();
            start.countDown();
            assertEquals(1, first.get() + second.get(), "insert ignore + 唯一键必须产生且仅产生一个 claim owner");
            assertEquals(1L, count("oms_cart_settlement_receipt"));
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    @Test
    void succeededReceiptReturnsEveryChildOrderInSortOrder()
    {
        try (SqlSession session = factory.openSession(true))
        {
            CartSettlementReceiptMapper mapper = session.getMapper(CartSettlementReceiptMapper.class);
            CartSettlementReceipt seed = receipt(21L, "multi-order", 'd');
            assertEquals(1, mapper.insertRequest(seed));
            assertEquals(1, mapper.insertOrder(seed.getId(), 902L, 2));
            assertEquals(1, mapper.insertOrder(seed.getId(), 900L, 0));
            assertEquals(1, mapper.insertOrder(seed.getId(), 901L, 1));
            assertEquals(1, mapper.markSucceeded(seed.getId(), hash('e'), 900L, 66L));

            assertEquals(Arrays.asList(900L, 901L, 902L), mapper.selectOrderIds(seed.getId()));
            CartSettlementReceipt stored = mapper.selectOwned(21L, "multi-order");
            assertEquals("SUCCEEDED", stored.getStatus());
            assertEquals(900L, stored.getFirstOrderId());
            assertEquals(66L, stored.getTotalPoints());
        }
    }

    @Test
    void rollbackLeavesNeitherReceiptNorOrderMapping()
    {
        try (SqlSession session = factory.openSession(false))
        {
            CartSettlementReceiptMapper mapper = session.getMapper(CartSettlementReceiptMapper.class);
            CartSettlementReceipt seed = receipt(31L, "rollback", 'f');
            assertEquals(1, mapper.insertRequest(seed));
            assertEquals(1, mapper.insertOrder(seed.getId(), 1001L, 0));
            session.rollback();
        }
        assertEquals(0L, count("oms_cart_settlement_receipt"));
        assertEquals(0L, count("oms_cart_settlement_order"));
    }

    private int claimOnce(CountDownLatch ready, CountDownLatch start) throws Exception
    {
        try (SqlSession session = factory.openSession(true))
        {
            ready.countDown();
            start.await();
            return session.getMapper(CartSettlementReceiptMapper.class)
                    .insertRequest(receipt(41L, "one-winner", '1'));
        }
    }

    private CartSettlementReceipt receipt(Long memberId, String requestId, char hashCharacter)
    {
        CartSettlementReceipt receipt = new CartSettlementReceipt();
        receipt.setMemberId(memberId);
        receipt.setRequestId(requestId);
        receipt.setRequestHash(hash(hashCharacter));
        return receipt;
    }

    private String hash(char value)
    {
        char[] chars = new char[64];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private long count(String table)
    {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            result.next();
            return result.getLong(1);
        }
        catch (Exception failure)
        {
            throw new IllegalStateException(failure);
        }
    }

    private SqlSessionFactory mapperFactory() throws Exception
    {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("it", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/CartSettlementReceiptMapper.xml";
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
