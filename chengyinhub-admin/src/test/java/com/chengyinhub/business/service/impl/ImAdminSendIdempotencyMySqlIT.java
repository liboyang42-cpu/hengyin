package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Dedicated VPS only: explicit credentials, real IM mappers and transaction proxy. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class ImAdminSendIdempotencyMySqlIT {
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private String schema;
    private boolean schemaCreated;
    private ImServiceImpl target;
    private IImService service;
    private ImConversationMapper conversations;
    private ImConversationMemberMapper members;
    private ImMessageMapper messages;
    private DataSourceTransactionManager manager;

    private String env(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "explicit test environment required: " + name);
        return value;
    }

    @BeforeAll
    void setup() throws Exception {
        int port = Integer.parseInt(env("CHENGYIN_MYBATIS_IT_PORT"));
        assertTrue(port > 0 && port <= 65535);
        String user = env("CHENGYIN_MYBATIS_IT_USER");
        assertFalse(user.trim().isEmpty());
        String password = env("CHENGYIN_MYBATIS_IT_PASSWORD");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + "mysql" + options, user, password));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        schema = "cy_im_admin_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + schema);
        schemaCreated = true;
        DriverManagerDataSource source = new DriverManagerDataSource(base + schema + options, user, password);
        db = new JdbcTemplate(source);
        Path sql = Paths.get("chengyinhub-admin/sql");
        if (!Files.isDirectory(sql)) sql = Paths.get("sql");
        new ResourceDatabasePopulator(new FileSystemResource(sql.resolve("migration_im.sql").toFile()),
                new FileSystemResource(sql.resolve("migration_im_send_idempotency_20260913.sql").toFile()),
                // master 的可见时刻游标(visible_at / last_read_visible_at)由该迁移补齐。
                new FileSystemResource(sql.resolve("migration_20261012_im_media_visibility.sql").toFile())).execute(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        conversations = session.getMapper(ImConversationMapper.class);
        members = session.getMapper(ImConversationMemberMapper.class);
        messages = session.getMapper(ImMessageMapper.class);
        manager = new DataSourceTransactionManager(source);
        target = new ImServiceImpl();
        ReflectionTestUtils.setField(target, "conversationMapper", conversations);
        ReflectionTestUtils.setField(target, "memberMapper", members);
        ReflectionTestUtils.setField(target, "messageMapper", messages);
        ReflectionTestUtils.setField(target, "blockMapper", mock(ImBlockMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(target, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (IImService) proxy.getProxy();
    }

    @BeforeEach
    void seed() {
        ReflectionTestUtils.setField(target, "conversationMapper", conversations);
        ReflectionTestUtils.setField(target, "memberMapper", members);
        ReflectionTestUtils.setField(target, "messageMapper", messages);
        db.update("delete from im_message");
        db.update("delete from im_conversation_member");
        db.update("delete from im_conversation");
        db.update("insert into im_conversation(id,type,biz_key,status) values(100,3,'cs_2',0)");
        db.update("insert into im_conversation_member(conversation_id,member_id) values(100,2)");
    }

    @AfterAll
    void cleanup() {
        if (schemaCreated && admin != null && schema != null) admin.execute("drop database if exists " + schema);
    }

    @Test
    void concurrentDuplicateUsesOneMessageAndOneUnread() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Long> send = () -> { start.await(); return service.sendFromAdmin(1L, 2L, 1, "hello", null, "request_0001"); };
            Future<Long> a = workers.submit(send), b = workers.submit(send);
            start.countDown();
            assertEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(1, db.queryForObject("select count(*) from im_message", Integer.class));
            assertEquals(1, db.queryForObject("select unread_count from im_conversation_member where conversation_id=100 and member_id=2", Integer.class));
        } finally { workers.shutdownNow(); }
    }

    @Test
    void payloadConflictHasNoAdditionalDatabaseEffect() {
        Long first = service.sendFromAdmin(1L, 2L, 1, "hello", null, "request_0001");
        assertThrows(ServiceException.class, () -> service.sendFromAdmin(1L, 2L, 1, "changed", null, "request_0001"));
        assertEquals(first, service.sendFromAdmin(1L, 2L, 1, "hello", null, "request_0001"));
        assertEquals(1, db.queryForObject("select count(*) from im_message", Integer.class));
        assertEquals("hello", db.queryForObject("select last_msg_text from im_conversation where id=100", String.class));
        assertEquals(1, db.queryForObject("select unread_count from im_conversation_member where conversation_id=100 and member_id=2", Integer.class));
    }

    @Test
    void outerRollbackRemovesMessageReceiptAndUnreadTogether() {
        new TransactionTemplate(manager).execute(status -> {
            service.sendFromAdmin(1L, 2L, 1, "hello", null, "request_0001");
            status.setRollbackOnly(); return null;
        });
        assertEquals(0, db.queryForObject("select count(*) from im_message", Integer.class));
        assertEquals(0, db.queryForObject("select unread_count from im_conversation_member where conversation_id=100 and member_id=2", Integer.class));
        assertNotNull(service.sendFromAdmin(1L, 2L, 1, "hello", null, "request_0001"));
        assertEquals(1, db.queryForObject("select count(*) from im_message", Integer.class));
    }
    @Test
    void receiptSqlUsesAllThreeKeysAndDoesNotChangePersistentState() {
        Long id = service.sendFromAdmin(1L, 2L, 1, "hello", null, "request_0001");
        String key = db.queryForObject("select client_message_id from im_message where id=?", String.class, id);
        assertNotNull(messages.selectByClientMessageId(100L, 0L, key));
        assertNull(messages.selectByClientMessageId(101L, 0L, key));
        assertNull(messages.selectByClientMessageId(100L, 2L, key));
        assertNull(messages.selectByClientMessageId(100L, 0L, "other_request"));
        assertEquals(String.valueOf(id), service.adminSendReceipt(1L, 2L, "request_0001").get("messageId"));
        assertEquals(false, service.adminSendReceipt(3L, 2L, "request_0001").get("found"));
        assertEquals(false, service.adminSendReceipt(1L, 9L, "request_0001").get("found"));
        assertEquals(1, db.queryForObject("select count(*) from im_conversation", Integer.class));
        assertEquals(1, db.queryForObject("select count(*) from im_conversation_member", Integer.class));
        assertEquals(1, db.queryForObject("select count(*) from im_message", Integer.class));
        assertEquals(1, db.queryForObject("select unread_count from im_conversation_member where conversation_id=100 and member_id=2", Integer.class));
    }

}
