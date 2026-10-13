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
@EnabledIfEnvironmentVariable(named = "CHENGYIN_IM_FOUNDATION_B_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImFoundationBMybatisIntegrationTest {
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private String schema;
    private javax.sql.DataSource source;
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
        schema = "cy_im_b_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + schema);
        source = new DriverManagerDataSource(base + schema + options, user, password);
        db = new JdbcTemplate(source);
        Path sql = Paths.get("chengyinhub-admin/sql");
        if (!sql.toFile().isDirectory()) sql = Paths.get("sql");
        new ResourceDatabasePopulator(new FileSystemResource(sql.resolve("migration_im.sql").toFile()),
                new FileSystemResource(sql.resolve("migration_im_send_idempotency_20260913.sql").toFile())).execute(source);
        db.execute("create table sys_job (job_name varchar(64),job_group varchar(64),invoke_target varchar(255),cron_expression varchar(255),misfire_policy varchar(20),concurrent varchar(20),status varchar(20),create_by varchar(64),create_time datetime,remark text)");
        Path root = sql.resolve("migration_20261013_b_im_notification_intent.sql");
        new ResourceDatabasePopulator(new FileSystemResource(root.toFile())).execute(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"),
                new ClassPathResource("mapper/business/ImNotificationIntentMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        messages = session.getMapper(ImMessageMapper.class);
        safety = mock(IWxContentSecurityService.class);
        inbox = mock(IWxContentCallbackInboxService.class);
        ImServiceImpl target = new ImServiceImpl();
        ReflectionTestUtils.setField(target, "conversationMapper", session.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(target, "memberMapper", session.getMapper(ImConversationMemberMapper.class));
        ReflectionTestUtils.setField(target, "messageMapper", messages);
        ReflectionTestUtils.setField(target, "notificationIntentMapper", session.getMapper(ImNotificationIntentMapper.class));
        ReflectionTestUtils.setField(target, "blockMapper", mock(ImBlockMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(target, "wxContentSecurityService", safety);
        ReflectionTestUtils.setField(target, "wxContentCallbackInboxService", inbox);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (IImService) proxy.getProxy();
    }

    @BeforeEach void seed() {
        db.update("delete from im_notification_intent"); db.update("delete from im_message");
        db.update("delete from im_conversation_member"); db.update("delete from im_conversation");
        db.update("insert into im_conversation(id,type,biz_key,status) values(100,1,'1_2',0),(200,2,'sys_1',0)");
        db.update("insert into im_conversation_member(conversation_id,member_id) values(100,1),(100,2),(200,1)");
    }
    @AfterAll void cleanup() {
        if (schema != null && admin != null) admin.execute("drop database " + schema);
    }
    private JdbcTemplate independent() { return new JdbcTemplate(source); }

    @Test void oldReadToDoesNotLoseNewUnreadAndIncrementRecoversAllSegments() {
        db.update("insert into im_message(id,conversation_id,sender_id,msg_type,content,status) values"
                + "(81,100,2,1,'first',0),(82,100,2,1,'second',0),(83,100,1,1,'mine',0),(84,100,2,1,'new',0)");
        service.markRead(1L,100L,82L);
        assertEquals(82L, independent().queryForObject("select last_read_msg_id from im_conversation_member where conversation_id=100 and member_id=1", Long.class));
        assertEquals(1, independent().queryForObject("select unread_count from im_conversation_member where conversation_id=100 and member_id=1", Integer.class));
        service.markRead(1L,100L,81L);
        assertEquals(82L, independent().queryForObject("select last_read_msg_id from im_conversation_member where conversation_id=100 and member_id=1", Long.class));
        java.util.Map<String,Object> first = service.listMessages(1L,100L,0L,2,80L);
        assertEquals(82L, first.get("nextAfter")); assertEquals(true, first.get("hasMore"));
        java.util.Map<String,Object> second = service.listMessages(1L,100L,0L,2,82L);
        assertEquals(84L, second.get("nextAfter"));
    }
    @Test void originalIntentAndCallerBusinessTransactionRollbackTogether() {
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
        assertThrows(RuntimeException.class, () -> tx.execute(status -> {
            service.enqueueSystemNotification(1L,3,"报名成功","{}","registration-signup:1");
            throw new RuntimeException("caller rollback");
        }));
        assertEquals(0, independent().queryForObject("select count(*) from im_notification_intent",Integer.class));
        assertEquals(0, independent().queryForObject("select count(*) from im_message",Integer.class));
    }
    @Test void twoWorkersAndLateReplayProduceOneOriginalMessageAndOneUnread() throws Exception {
        Long intent = service.enqueueSystemNotification(1L,3,"报名成功","{}","registration-signup:1");
        assertEquals(intent, service.enqueueSystemNotification(1L,3,"报名成功","{}","registration-signup:1"));
        ExecutorService workers = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Long> deliver = () -> { start.await(); return service.deliverSystemNotification(intent); };
            Future<Long> one = workers.submit(deliver), two = workers.submit(deliver); start.countDown();
            Long message = one.get(10,TimeUnit.SECONDS); assertEquals(message,two.get(10,TimeUnit.SECONDS));
            assertEquals(message, service.deliverSystemNotification(intent));
            assertEquals(1, independent().queryForObject("select count(*) from im_message",Integer.class));
            assertEquals(1, independent().queryForObject("select unread_count from im_conversation_member where conversation_id=200 and member_id=1",Integer.class));
            assertEquals("DELIVERED", independent().queryForObject("select status from im_notification_intent where id=?",String.class,intent));
            assertEquals(message, independent().queryForObject("select message_id from im_notification_intent where id=?",Long.class,intent));
        } finally { workers.shutdownNow(); }
    }
    @Test void originalEventContentConflictIsRejectedAndSourceRemainsUntouched() {
        Long intent = service.enqueueSystemNotification(1L,3,"报名成功","{}","registration-signup:1");
        assertThrows(ServiceException.class, () -> service.enqueueSystemNotification(2L,3,"changed","{}","registration-signup:1"));
        assertEquals("报名成功", independent().queryForObject("select content from im_notification_intent where id=?",String.class,intent));
        assertEquals(1, independent().queryForObject("select count(*) from im_notification_intent",Integer.class));
        assertEquals(0, independent().queryForObject("select count(*) from im_message",Integer.class));
    }
}
