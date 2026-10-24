package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.notify.WxSubscribeChannel;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.CommittedOfficialNoticeService;
import com.chengyinhub.business.task.CommittedOfficialNoticeTask;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real canonical IM migrations, mappers, consumer and new typed owner on controlled MySQL.
 * A JDBC complaint fact adapter proves transaction coupling, not the complete financial producer. */
final class CommittedOfficialNoticeMySqlFixture implements AutoCloseable {
    final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment(
            "committed_notice_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    final WxSubscribeChannel wx = mock(WxSubscribeChannel.class);
    final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    final List<Runnable> wakes = new CopyOnWriteArrayList<>();
    JdbcTemplate admin, db;
    DataSourceTransactionManager manager;
    TransactionTemplate tx;
    SqlSessionTemplate sql;
    CommittedOfficialNoticeWxMapper mapper;
    ImNotificationIntentMapper imIntents;
    ImServiceImpl imTarget;
    IImService im;
    ISystemNotificationIntentService intents;
    CommittedOfficialNoticeService notice;
    Path sqlRoot;
    boolean created;

    void open() throws Exception {
        for (String key : List.of("CHENGYIN_TEST_MYSQL_PORT", "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"))
            assertNotNull(System.getenv(key), "controlled runner must supply " + key);
        admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("create database " + mysql.databaseName() + " character set utf8mb4"); created = true;
        DriverManagerDataSource source = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        db = new JdbcTemplate(source);
        sqlRoot = Paths.get("chengyinhub-admin/sql");
        if (!sqlRoot.toFile().isDirectory()) sqlRoot = Paths.get("sql");
        new ResourceDatabasePopulator(new FileSystemResource(sqlRoot.resolve("migration_im.sql")),
                new FileSystemResource(sqlRoot.resolve("migration_im_send_idempotency_20260913.sql")),
                new FileSystemResource(sqlRoot.resolve("migration_20261012_im_media_visibility.sql"))).execute(source);
        db.execute("create table sys_job(job_name varchar(64),job_group varchar(64),invoke_target varchar(255),"
                + "cron_expression varchar(255),misfire_policy varchar(20),concurrent varchar(20),status varchar(20),"
                + "create_by varchar(64),create_time datetime,remark text) engine=InnoDB");
        new ResourceDatabasePopulator(new FileSystemResource(sqlRoot.resolve("migration_20261013_b_im_notification_intent.sql")),
                new FileSystemResource(sqlRoot.resolve("migration_20261024_d_committed_official_notice_wx.sql"))).execute(source);
        db.execute("create table complaint_notice_fact(id bigint primary key,status int,recipient bigint,"
                + "title varchar(200),body text) engine=InnoDB");
        db.update("insert into complaint_notice_fact values(1,0,100,'投诉处理有进展',"
                + "'工单#1已先行垫付 ¥50，请在投诉记录查看处理进度。')");
        manager = new DataSourceTransactionManager(source); tx = new TransactionTemplate(manager);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"),
                new ClassPathResource("mapper/business/ImNotificationIntentMapper.xml"),
                new ClassPathResource("mapper/business/CommittedOfficialNoticeWxMapper.xml"));
        sql = new SqlSessionTemplate(factory.getObject());
        mapper = sql.getMapper(CommittedOfficialNoticeWxMapper.class);
        imIntents = sql.getMapper(ImNotificationIntentMapper.class);
        imTarget = spy(new ImServiceImpl());
        ReflectionTestUtils.setField(imTarget, "notificationIntentMapper", imIntents);
        ReflectionTestUtils.setField(imTarget, "conversationMapper", sql.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(imTarget, "messageMapper", sql.getMapper(ImMessageMapper.class));
        ReflectionTestUtils.setField(imTarget, "memberMapper", sql.getMapper(ImConversationMemberMapper.class));
        ProxyFactory imProxy = new ProxyFactory(imTarget);
        imProxy.setInterfaces(IImService.class, ISystemNotificationIntentService.class);
        imProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        im = (IImService) imProxy.getProxy(); intents = (ISystemNotificationIntentService) im;
        when(executor.schedule(any(Runnable.class), eq(10L), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
            wakes.add(call.getArgument(0)); return mock(ScheduledFuture.class);
        });
        when(wx.send(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "WX must have no database transaction");
            return true;
        });
        notice = freshOwner(mapper);
    }

    CommittedOfficialNoticeService freshOwner(CommittedOfficialNoticeWxMapper source) {
        CommittedOfficialNoticeService target = new CommittedOfficialNoticeService(source, intents, im, wx, manager, executor);
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (CommittedOfficialNoticeService) proxy.getProxy();
    }

    Long winner() {
        return tx.execute(status -> {
            Map<String, Object> original = db.queryForMap("select * from complaint_notice_fact where id=1");
            if (db.update("update complaint_notice_fact set status=1 where id=1 and status=0") != 1)
                throw new IllegalStateException("original CAS lost");
            return notice.enqueueOfficial("complaint-advance:1", ((Number) original.get("recipient")).longValue(),
                    (String) original.get("title"), (String) original.get("body"));
        });
    }

    Long enqueue(String key, long recipient, String title, String body) {
        return tx.execute(status -> notice.enqueueOfficial(key, recipient, title, body));
    }

    int count(String table) { return db.queryForObject("select count(*) from " + table, Integer.class); }
    String state(Long id) { return db.queryForObject("select wx_state from committed_official_notice_wx where id=?", String.class, id); }
    void recover() { new CommittedOfficialNoticeTask(freshOwner(mapper)).deliverPending(); }

    void oneMessage() {
        assertEquals(1, count("im_message"));
        assertEquals(1, db.queryForObject("select unread_count from im_conversation_member where member_id=100", Integer.class));
        assertEquals("DELIVERED", db.queryForObject("select status from im_notification_intent", String.class));
        assertEquals(db.queryForObject("select id from im_message", Long.class),
                db.queryForObject("select message_id from im_notification_intent", Long.class));
    }

    @Override public void close() {
        if (created) {
            admin.execute("drop database " + mysql.databaseName());
            assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?",
                    Integer.class, mysql.databaseName()));
            System.out.println("COMMITTED_OFFICIAL_NOTICE_CLEAN " + mysql.databaseName());
        }
    }
}
