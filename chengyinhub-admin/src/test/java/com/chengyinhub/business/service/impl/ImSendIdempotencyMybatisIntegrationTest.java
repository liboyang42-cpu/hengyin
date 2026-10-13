package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 本地独立库，实际 Service/事务/三个 IM Mapper，内容审核外部服务不出网。 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_IM_SEND_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImSendIdempotencyMybatisIntegrationTest {
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private String schema;
    private IImService service;
    private IWxContentSecurityService safety;
    private IWxContentCallbackInboxService inbox;
    private ImMessageMapper messages;

    @BeforeAll
    void setup() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("CHENGYIN_MYBATIS_IT_PORT", "3306"));
        assertTrue(port > 0 && port <= 65535);
        String user = System.getenv().getOrDefault("CHENGYIN_MYBATIS_IT_USER", "root");
        String password = System.getenv().getOrDefault("CHENGYIN_MYBATIS_IT_PASSWORD", "");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + "mysql" + options, user, password));
        String version = admin.queryForObject("select version()", String.class);
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(version), "需要 MySQL >= 8,实际 " + version);
        schema = "cy_im_retry_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + schema);
        DriverManagerDataSource source = new DriverManagerDataSource(base + schema + options, user, password);
        db = new JdbcTemplate(source);
        Path sql = Paths.get("chengyinhub-admin/sql");
        if (!sql.toFile().isDirectory()) sql = Paths.get("sql");
        new ResourceDatabasePopulator(new FileSystemResource(sql.resolve("migration_im.sql").toFile()),
                new FileSystemResource(sql.resolve("migration_im_send_idempotency_20260913.sql").toFile())).execute(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        messages = session.getMapper(ImMessageMapper.class);
        safety = mock(IWxContentSecurityService.class);
        inbox = mock(IWxContentCallbackInboxService.class);
        ImServiceImpl target = new ImServiceImpl();
        ReflectionTestUtils.setField(target, "conversationMapper", session.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(target, "memberMapper", session.getMapper(ImConversationMemberMapper.class));
        ReflectionTestUtils.setField(target, "messageMapper", messages);
        ReflectionTestUtils.setField(target, "blockMapper", mock(ImBlockMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(target, "wxContentSecurityService", safety);
        ReflectionTestUtils.setField(target, "wxContentCallbackInboxService", inbox);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (IImService) proxy.getProxy();
    }

    @BeforeEach
    void seed() {
        db.update("delete from im_message");
        db.update("delete from im_conversation_member");
        db.update("delete from im_conversation");
        db.update("insert into im_conversation(id,type,biz_key,status) values(100,1,'1_2',0),(101,1,'1_3',0)");
        db.update("insert into im_conversation_member(conversation_id,member_id) values(100,1),(100,2),(101,1),(101,3)");
        reset(safety, inbox);
    }

    @AfterAll
    void cleanup() {
        if (schema != null && admin != null) {
            admin.execute("drop database " + schema);
            assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?", Integer.class, schema));
            System.out.println("scratch cleanup verified " + schema);
        }
    }

    @Test
    void concurrentRetriesCreateOneMessageAndOneUnreadButNewSendStillCreatesAnother() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Long> send = () -> { start.await(); return service.send(1L, 100L, null, 1, "hello", null, "send_concurrent_0001").getId(); };
        try {
            Future<Long> first = workers.submit(send);
            Future<Long> second = workers.submit(send);
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, messages.selectPage(100L, null, 30).size());
            assertEquals(1, unread(2));
            verify(safety).checkText(eq("hello"), any());
            service.send(1L, 100L, null, 1, "hello", null, "send_intentional_0002");
            assertEquals(2, messages.selectPage(100L, null, 30).size());
            assertEquals(2, unread(2));
        } finally { workers.shutdownNow(); }
    }

    @Test
    void retryIdentityCannotChangePayloadOrCrossSenderAndLegacySendsRemainSeparate() {
        String key = "send_identity_0001";
        service.send(1L, 100L, null, 1, "original", null, key);
        assertThrows(ServiceException.class, () -> service.send(1L, 100L, null, 1, "changed", null, key));
        assertEquals(1, unread(2));
        service.send(2L, 100L, null, 1, "other sender", null, key);
        service.send(1L, 101L, null, 1, "other conversation", null, key);
        service.send(1L, 100L, null, 1, "legacy", null);
        service.send(1L, 100L, null, 1, "legacy", null);
        assertEquals(4, messages.selectPage(100L, null, 30).size());
        assertEquals(1, messages.selectPage(101L, null, 30).size());
    }

    @Test
    void failedImageRegistrationRollsBackAndRetryCanSucceedOnlyOnce() {
        when(safety.checkMediaAsync(anyString(), any())).thenReturn("image-check-1");
        when(inbox.register(anyString(), anyInt(), anyLong(), anyString())).thenReturn(false, true);
        String key = "send_image_0000001";
        assertThrows(RuntimeException.class, () -> service.send(1L, 100L, null, 2, "https://cdn/image.jpg", null, key));
        assertEquals(0, messages.selectPage(100L, null, 30).size());
        assertEquals(0, unread(2));
        ImMessage successful = service.send(1L, 100L, null, 2, "https://cdn/image.jpg", null, key);
        assertEquals(successful.getId(), service.send(1L, 100L, null, 2, "https://cdn/image.jpg", null, key).getId());
        assertEquals(1, unread(2));
        verify(inbox, times(2)).register(anyString(), anyInt(), anyLong(), anyString());
    }

    private int unread(long member) {
        return db.queryForObject("select unread_count from im_conversation_member where conversation_id=100 and member_id=?", Integer.class, member);
    }

    @Test
    void locationCardRetryReturnsSanitizedOriginalWithoutRepeatingReview() {
        String card = "{\"cardType\":\"location\",\"name\":\"meeting\",\"lat\":31,\"lng\":121,\"admin\":true}";
        String key = "send_location_0001";
        ImMessage first = service.send(1L, 100L, null, 3, "untrusted title", card, key);
        ImMessage retry = service.send(1L, 100L, null, 3, "untrusted title", card, key);
        assertEquals(first.getId(), retry.getId());
        assertEquals("[位置] meeting", retry.getContent());
        assertFalse(retry.getExtraJson().contains("admin"));
        assertEquals(3, retry.getMsgType());
        assertEquals(1, unread(2));
        verify(safety).checkText(eq("meeting "), any());
        assertThrows(ServiceException.class, () -> service.send(1L, 100L, null, 3,
                "untrusted title", card.replace("meeting", "changed"), key));
        assertEquals(1, messages.selectPage(100L, null, 30).size());
    }

    @Test
    void removedMembershipCannotRetrievePriorSuccessfulSendByRetryKey() {
        String key = "send_revoked_00001";
        service.send(1L, 100L, null, 1, "hello", null, key);
        db.update("delete from im_conversation_member where conversation_id=100 and member_id=1");
        assertThrows(RuntimeException.class, () -> service.send(1L, 100L, null, 1, "hello", null, key));
        assertEquals(1, messages.selectPage(100L, null, 30).size());
        assertEquals(1, unread(2));
    }
}
