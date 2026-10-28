package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL and actual moderation method; no external notification or runtime account.
 * Explicitly run this IT on approved CI. This does not exercise the HTTP/RBAC boundary.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImModerationUnreadMySqlIT {
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private AuditTaskServiceImpl service;
    private String database;

    @BeforeAll
    void setup() throws Exception {
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()));
        String suite = "w8imunread_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        DriverManagerDataSource source = new DriverManagerDataSource(MybatisItDatabase.url(suite),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        database = MybatisItDatabase.databaseName(suite);
        jdbc = new JdbcTemplate(source);
        assertEquals(database, jdbc.queryForObject("select database()", String.class));
        // Minimal schema for the two production mapper statements under test.
        jdbc.execute("create table im_message(id bigint primary key,conversation_id bigint,sender_id bigint,"
                + "msg_type int,status int,check_status int,create_time datetime(6),visible_at datetime(6)) engine=InnoDB");
        jdbc.execute("create table im_conversation_member(id bigint primary key,conversation_id bigint,member_id bigint,"
                + "unread_count int,last_read_msg_id bigint,last_read_visible_at datetime(6),create_time datetime(6)) engine=InnoDB");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ImMessageMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "imMessageMapper", session.getMapper(ImMessageMapper.class));
        ReflectionTestUtils.setField(service, "imConversationMemberMapper", session.getMapper(ImConversationMemberMapper.class));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        transactions.setTimeout(10);
    }

    @BeforeEach
    void seed() {
        jdbc.update("delete from im_conversation_member");
        jdbc.update("delete from im_message");
        jdbc.update("insert into im_message values(10,100,1,1,?,1,'2026-10-01 10:00:00','2026-10-01 10:00:00')", ImMessage.STATUS_NORMAL);
        // Sender and unrelated conversation must remain untouched; reader has two unread messages.
        jdbc.update("insert into im_conversation_member values "
                + "(1,100,1,5,0,null,'2026-09-01'),(2,100,2,2,0,null,'2026-09-01'),"
                + "(3,200,3,7,0,null,'2026-09-01')");
    }

    private void moderate(boolean down) {
        ReflectionTestUtils.invokeMethod(service, "applyImModeration", 10L, down);
    }

    private void assertState(int status, int unread) {
        assertEquals(status, jdbc.queryForObject("select status from im_message where id=10", Integer.class));
        assertEquals(unread, jdbc.queryForObject("select unread_count from im_conversation_member where id=2", Integer.class));
        assertEquals(5, jdbc.queryForObject("select unread_count from im_conversation_member where id=1", Integer.class));
        assertEquals(7, jdbc.queryForObject("select unread_count from im_conversation_member where id=3", Integer.class));
    }

    @Test
    void repeatedBlockAndRestoreChangeOnlyTheCountedReaderOnce() {
        transactions.executeWithoutResult(tx -> { moderate(true); moderate(true); });
        assertState(ImMessage.STATUS_BLOCKED, 1);
        transactions.executeWithoutResult(tx -> { moderate(false); moderate(false); });
        assertState(ImMessage.STATUS_NORMAL, 2);
    }

    @Test
    void rollbackRestoresBothMessageVisibilityAndUnread() {
        transactions.executeWithoutResult(tx -> { moderate(true); tx.setRollbackOnly(); });
        assertState(ImMessage.STATUS_NORMAL, 2);
    }

    @Test
    void concurrentDuplicateVerdictsHaveOneEffectInEachDirection() throws Exception {
        race(true);
        assertState(ImMessage.STATUS_BLOCKED, 1);
        race(false);
        assertState(ImMessage.STATUS_NORMAL, 2);
    }

    private void race(boolean down) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Void> work = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                transactions.executeWithoutResult(tx -> moderate(down));
                return null;
            };
            var first = pool.submit(work);
            var second = pool.submit(work);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @AfterAll
    void cleanup() {
        if (jdbc != null && database != null) {
            assertTrue(database.matches("chengyin_mybatis_w8imunread_[a-z0-9_]+"));
            jdbc.execute("drop database `" + database + "`");
        }
    }
}
