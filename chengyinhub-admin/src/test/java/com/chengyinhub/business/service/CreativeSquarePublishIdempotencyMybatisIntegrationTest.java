package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.mapper.UmsCreativeSquareMapper;
import com.chengyinhub.business.service.impl.UmsCreativeSquareServiceImpl;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R10-03 真实 MySQL8:同一发布意图(账号+request_id)在丢响应重试、并发首写、
 * 审核拒绝、同键变载荷下都只落一行;不同账号同键互不影响。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreativeSquarePublishIdempotencyMybatisIntegrationTest {

    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("square_publish_it");
    private static final Long ME = 9L;
    private static final Long OTHER = 10L;
    private static final String RID = "sq_publish_it_00000001";

    private DataSource dataSource;
    private TransactionTemplate tx;
    private IUmsCreativeSquareService service;

    @BeforeAll
    void createDatabaseAndServices() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = dataSource(MYSQL.databaseUrl());
        SqlSessionTemplate template = new SqlSessionTemplate(buildFactory(dataSource));
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        UmsCreativeSquareServiceImpl target = new UmsCreativeSquareServiceImpl();
        ReflectionTestUtils.setField(target, "umsCreativeSquareMapper", template.getMapper(UmsCreativeSquareMapper.class));
        service = target;
    }

    @BeforeEach
    void resetSchema() throws Exception {
        execute("DROP TABLE IF EXISTS ums_creative_square");
        execute("CREATE TABLE ums_creative_square (id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, contents TEXT, pics VARCHAR(2000),"
                + " file_url VARCHAR(2000), address VARCHAR(500), longitude VARCHAR(64), latitude VARCHAR(64), data_id BIGINT, data_type BIGINT,"
                + " like_num BIGINT, nolike_num BIGINT, status INT, is_crowdfunding INT, del_flag INT, check_status INT,"
                + " route_preview_img VARCHAR(2000), request_id VARCHAR(64) NULL, request_signature VARCHAR(64) NULL,"
                + " create_by VARCHAR(64), create_time DATETIME(3), update_by VARCHAR(64), update_time DATETIME(3),"
                + " UNIQUE KEY uk_creative_square_member_request (member_id, request_id)) ENGINE=InnoDB");
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    @Test
    void concurrentSameIntentInsertsExactlyOneRow() throws Exception {
        List<IUmsCreativeSquareService.IdempotentPublishResult.Kind> kinds = new CopyOnWriteArrayList<>();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    IUmsCreativeSquareService.IdempotentPublishResult result = tx.execute(status ->
                            service.insertUmsCreativeSquareIdempotent(post(ME, "并发同意图"), RID, "sig-a"));
                    kinds.add(result.getKind());
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, number("SELECT COUNT(*) FROM ums_creative_square"), "并发同意图只允许一行");
        assertTrue(kinds.contains(IUmsCreativeSquareService.IdempotentPublishResult.Kind.NEW), kinds.toString());
        for (IUmsCreativeSquareService.IdempotentPublishResult.Kind kind : kinds) {
            assertTrue(kind == IUmsCreativeSquareService.IdempotentPublishResult.Kind.NEW
                            || kind == IUmsCreativeSquareService.IdempotentPublishResult.Kind.PENDING
                            || kind == IUmsCreativeSquareService.IdempotentPublishResult.Kind.EXISTING_VISIBLE,
                    "并发结果只允许 NEW/PENDING/EXISTING_VISIBLE: " + kind);
        }
    }

    @Test
    void committedButLostResponseRetryDoesNotInsertAgain() throws Exception {
        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.NEW,
                service.insertUmsCreativeSquareIdempotent(post(ME, "已提交丢响应"), RID, "sig-a").getKind());
        execute("UPDATE ums_creative_square SET status=1 WHERE member_id=9 AND request_id='" + RID + "'");

        IUmsCreativeSquareService.IdempotentPublishResult retry =
                service.insertUmsCreativeSquareIdempotent(post(ME, "已提交丢响应"), RID, "sig-a");

        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.EXISTING_VISIBLE, retry.getKind());
        assertEquals(1, number("SELECT COUNT(*) FROM ums_creative_square"), "重试不得再 insert");
    }

    @Test
    void auditRejectedRetryStaysRejectedWithSingleRow() throws Exception {
        service.insertUmsCreativeSquareIdempotent(post(ME, "违规内容"), RID, "sig-a");
        execute("UPDATE ums_creative_square SET status=2 WHERE member_id=9 AND request_id='" + RID + "'");

        IUmsCreativeSquareService.IdempotentPublishResult retry =
                service.insertUmsCreativeSquareIdempotent(post(ME, "违规内容"), RID, "sig-a");

        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.EXISTING_REJECTED, retry.getKind());
        assertEquals(1, number("SELECT COUNT(*) FROM ums_creative_square"));
    }

    @Test
    void softDeletedIntentIsTerminalAndNeverResurrected() throws Exception {
        service.insertUmsCreativeSquareIdempotent(post(ME, "已删帖"), RID, "sig-a");
        // 先审成可见再软删:status 仍是 1,旧实现会当 EXISTING_VISIBLE 回假成功
        execute("UPDATE ums_creative_square SET status=1 WHERE member_id=9 AND request_id='" + RID + "'");
        execute("UPDATE ums_creative_square SET del_flag=2 WHERE member_id=9 AND request_id='" + RID + "'");

        IUmsCreativeSquareService.IdempotentPublishResult retry =
                service.insertUmsCreativeSquareIdempotent(post(ME, "已删帖"), RID, "sig-a");

        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.EXISTING_DELETED, retry.getKind());
        assertEquals(1, number("SELECT COUNT(*) FROM ums_creative_square"), "不得复活/新增");
        assertEquals(1, number("SELECT COUNT(*) FROM ums_creative_square WHERE del_flag=2"), "删除态保持");
        assertEquals("已删帖", string("SELECT contents FROM ums_creative_square WHERE member_id=9"));
    }

    @Test
    void sameKeyDifferentPayloadConflictsWithoutNewRow() throws Exception {
        service.insertUmsCreativeSquareIdempotent(post(ME, "原始正文"), RID, "sig-a");

        IUmsCreativeSquareService.IdempotentPublishResult conflict =
                service.insertUmsCreativeSquareIdempotent(post(ME, "改过的正文"), RID, "sig-b");

        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.CONFLICT, conflict.getKind());
        assertEquals(1, number("SELECT COUNT(*) FROM ums_creative_square"));
        assertEquals("原始正文", string("SELECT contents FROM ums_creative_square WHERE member_id=9"));
    }

    @Test
    void crossAccountSameKeyGetsOwnRow() throws Exception {
        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.NEW,
                service.insertUmsCreativeSquareIdempotent(post(ME, "A的帖子"), RID, "sig-a").getKind());
        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.NEW,
                service.insertUmsCreativeSquareIdempotent(post(OTHER, "B的帖子"), RID, "sig-a").getKind());

        assertEquals(2, number("SELECT COUNT(*) FROM ums_creative_square"), "意图键按账号隔离");
        assertEquals(1, number("SELECT COUNT(*) FROM ums_creative_square WHERE member_id=10"));
    }

    @Test
    void invalidKeyFailsClosedWithoutWrite() throws Exception {
        assertEquals(IUmsCreativeSquareService.IdempotentPublishResult.Kind.INVALID,
                service.insertUmsCreativeSquareIdempotent(post(ME, "正文"), "short", "sig-a").getKind());
        assertEquals(0, number("SELECT COUNT(*) FROM ums_creative_square"));
    }

    private UmsCreativeSquare post(Long memberId, String contents) {
        UmsCreativeSquare square = new UmsCreativeSquare();
        square.setMemberId(memberId);
        square.setContents(contents);
        square.setPics("");
        square.setStatus(0);
        square.setDelFlag(0);
        return square;
    }

    private int number(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String string(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
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
        Configuration configuration = new Configuration(new Environment("square-publish-it",
                new SpringManagedTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        addMapper(configuration, UmsCreativeSquareMapper.class, "mapper/business/UmsCreativeSquareMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void addMapper(Configuration configuration, Class<?> mapper, String resource) throws Exception {
        configuration.addMapper(mapper);
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("找不到 MyBatis XML: " + resource);
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
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
