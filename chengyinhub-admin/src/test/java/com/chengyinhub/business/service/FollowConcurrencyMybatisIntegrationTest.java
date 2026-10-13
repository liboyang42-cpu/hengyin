package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.UmsMemberFollowMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.UmsMemberFollowServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R10-07 真实 MySQL8 并发闭环:两个并发独立事务首次关注只落一行;
 * 加唯一索引(迁移目标态)后仍单行;失败回滚不残留,重试可恢复。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FollowConcurrencyMybatisIntegrationTest {

    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("follow_concurrency_it");
    private static final int PAIRS = 24;

    private DataSource dataSource;
    private PlatformTransactionManager tx;
    private IUmsMemberFollowService service;

    @BeforeAll
    void createDatabaseAndServices() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = dataSource(MYSQL.databaseUrl());
        SqlSessionTemplate template = new SqlSessionTemplate(buildFactory(dataSource));
        tx = new DataSourceTransactionManager(dataSource);

        UmsMemberFollowServiceImpl target = new UmsMemberFollowServiceImpl();
        ReflectionTestUtils.setField(target, "umsMemberFollowMapper", template.getMapper(UmsMemberFollowMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberMapper", template.getMapper(UmsMemberMapper.class));
        service = transactionalProxy(target, IUmsMemberFollowService.class, tx);
    }

    @BeforeEach
    void resetSchema() throws Exception {
        execute("DROP TABLE IF EXISTS ums_member_follow", "DROP TABLE IF EXISTS ums_member");
        execute("CREATE TABLE ums_member (id BIGINT PRIMARY KEY, status INT, del_flag INT) ENGINE=InnoDB",
                "CREATE TABLE ums_member_follow (id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, follow_member_id BIGINT,"
                        + " create_by VARCHAR(64), create_time DATETIME(3), update_by VARCHAR(64), update_time DATETIME(3)) ENGINE=InnoDB");
        StringBuilder sql = new StringBuilder("INSERT INTO ums_member(id,status,del_flag) VALUES (9,1,0),(88,1,0)");
        for (int i = 0; i < PAIRS; i++) {
            sql.append(",(").append(1000 + i).append(",1,0)");
            // P3-4 建立新关系要校验 target 存在:并发用例的 target 也必须是真实会员
            sql.append(",(").append(2000 + i).append(",1,0)");
        }
        execute(sql.toString());
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    @Test
    void concurrentFirstFollowInsertsExactlyOneRowPerPair() throws Exception {
        List<IUmsMemberFollowService.ApplyResult> results = raceFollow(0);

        assertEquals(0, duplicatePairCount(), "并发首次关注不得产生重复关系行");
        assertEquals(PAIRS, number("SELECT COUNT(*) FROM ums_member_follow"), "每对关系只落一行");
        for (IUmsMemberFollowService.ApplyResult result : results) {
            assertTrue(result == IUmsMemberFollowService.ApplyResult.FOLLOWED
                            || result == IUmsMemberFollowService.ApplyResult.ALREADY_FOLLOWED,
                    "并发首次关注只允许 关注成功 / 已是关注: " + result);
        }
    }

    @Test
    void uniqueIndexFromMigrationKeepsConcurrencySafe() throws Exception {
        execute("ALTER TABLE ums_member_follow ADD UNIQUE INDEX uk_member_follow_pair (member_id, follow_member_id)");

        List<IUmsMemberFollowService.ApplyResult> results = raceFollow(0);

        assertEquals(0, duplicatePairCount(), "有唯一索引时并发首次关注也不得重复");
        assertEquals(PAIRS, number("SELECT COUNT(*) FROM ums_member_follow"));
        for (IUmsMemberFollowService.ApplyResult result : results) {
            assertTrue(result == IUmsMemberFollowService.ApplyResult.FOLLOWED
                            || result == IUmsMemberFollowService.ApplyResult.ALREADY_FOLLOWED,
                    "唯一索引目标态下不允许异常结果: " + result);
        }
    }

    @Test
    void rolledBackAttemptLeavesNoRowAndRetryRecovers() throws Exception {
        TransactionTemplate template = new TransactionTemplate(tx);
        try {
            template.execute(status -> {
                service.applyFollowState(9L, 88L, Boolean.TRUE);
                throw new IllegalStateException("模拟中途失败");
            });
        } catch (IllegalStateException expected) {
            // 回滚路径
        }

        assertEquals(0, number("SELECT COUNT(*) FROM ums_member_follow"), "失败事务不得残留关系行");

        assertEquals(IUmsMemberFollowService.ApplyResult.FOLLOWED,
                service.applyFollowState(9L, 88L, Boolean.TRUE));
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_follow WHERE member_id=9 AND follow_member_id=88"),
                "回滚后重试必须能恢复并只落一行");
    }

    @Test
    void concurrentFollowAndUnfollowConvergeWithoutDuplicate() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ConcurrentLinkedQueue<IUmsMemberFollowService.ApplyResult> results = new ConcurrentLinkedQueue<>();
        try {
            Future<?> follow = pool.submit(() -> {
                ready.countDown();
                awaitQuietly(start);
                results.add(service.applyFollowState(9L, 88L, Boolean.TRUE));
                return null;
            });
            Future<?> unfollow = pool.submit(() -> {
                ready.countDown();
                awaitQuietly(start);
                results.add(service.applyFollowState(9L, 88L, Boolean.FALSE));
                return null;
            });
            ready.await();
            start.countDown();
            follow.get();
            unfollow.get();
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, duplicatePairCount(), "同 pair 并发关注/取消不得产生重复行");
        assertTrue(number("SELECT COUNT(*) FROM ums_member_follow WHERE member_id=9 AND follow_member_id=88") <= 1);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private List<IUmsMemberFollowService.ApplyResult> raceFollow(int offset) throws Exception {
        CountDownLatch ready = new CountDownLatch(2 * PAIRS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2 * PAIRS);
        List<IUmsMemberFollowService.ApplyResult> results = new ArrayList<>();
        try {
            List<Future<IUmsMemberFollowService.ApplyResult>> futures = new ArrayList<>();
            for (int i = 0; i < PAIRS; i++) {
                final Long memberId = (long) (1000 + offset + i);
                final Long targetId = (long) (2000 + i);
                for (int t = 0; t < 2; t++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        return service.applyFollowState(memberId, targetId, Boolean.TRUE);
                    }));
                }
            }
            ready.await();
            start.countDown();
            for (Future<IUmsMemberFollowService.ApplyResult> future : futures) {
                results.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        return results;
    }

    private int duplicatePairCount() throws Exception {
        return number("SELECT COUNT(*) FROM (SELECT member_id, follow_member_id FROM ums_member_follow"
                + " GROUP BY member_id, follow_member_id HAVING COUNT(*) > 1) dup");
    }

    private int number(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void execute(String... sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : sql) {
                s.execute(statement);
            }
        }
    }

    private SqlSessionFactory buildFactory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("follow-concurrency-it",
                new SpringManagedTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        addMapper(configuration, UmsMemberFollowMapper.class, "mapper/business/UmsMemberFollowMapper.xml");
        addMapper(configuration, UmsMemberMapper.class, "mapper/business/UmsMemberMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void addMapper(Configuration configuration, Class<?> mapper, String resource) throws Exception {
        configuration.addMapper(mapper);
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("找不到 MyBatis XML: " + resource);
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(Object target, Class<T> iface, PlatformTransactionManager transactionManager) {
        TransactionInterceptor interceptor = new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(iface);
        factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }
}
