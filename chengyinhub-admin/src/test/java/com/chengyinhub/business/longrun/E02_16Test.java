package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: real MySQL mapper and service transaction; no fake notification implementation. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_E02_16_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E02_16Test {
    private MysqlIntegrationTestConfig mysql;
    private boolean created;
    private JdbcTemplate jdbc;
    private CommunityPostServiceImpl service;

    @BeforeAll
    void setup() throws Exception {
        for (String key : new String[]{"CHENGYIN_TEST_MYSQL_HOST", "CHENGYIN_TEST_MYSQL_PORT",
                "CHENGYIN_TEST_MYSQL_DATABASE_BASE", "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX",
                "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"}) {
            assertNotNull(System.getenv(key), "Explicit authorized scratch configuration required: " + key);
            if (!key.endsWith("PASSWORD")) assertFalse(System.getenv(key).trim().isEmpty(), key);
        }
        mysql = MysqlIntegrationTestConfig.fromEnvironment("e02_16_it");
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(
                admin().queryForObject("select version()", String.class)), "MySQL 8+ required");
        admin().execute("CREATE DATABASE " + mysql.databaseName() + " CHARACTER SET utf8mb4");
        created = true;
        DriverManagerDataSource ds = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(ds);
        schema();
        Configuration c = new Configuration(new Environment("e02-16", new SpringManagedTransactionFactory(), ds));
        c.getTypeAliasRegistry().registerAlias("CommunityPost", CommunityPost.class);
        c.addMapper(CommunityPostMapper.class);
        String resource = "mapper/business/CommunityPostMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            new XMLMapperBuilder(input, c, resource, c.getSqlFragments()).parse();
        }
        CommunityPostMapper mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c))
                .getMapper(CommunityPostMapper.class);
        CommunityPostServiceImpl target = new CommunityPostServiceImpl(mapper,
                new CommunityPostLifecyclePolicy(), null, null, null, null);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
                new AnnotationTransactionAttributeSource()));
        service = (CommunityPostServiceImpl) proxy.getProxy();
    }

    @BeforeEach
    void seed() {
        jdbc.update("delete from community_post_command_receipt");
        jdbc.update("delete from community_notification_actor");
        jdbc.update("delete from community_notification");
        jdbc.update("delete from community_post");
        jdbc.update("insert into community_post(id,author_id,lifecycle,audience,body,interaction_enabled,version) values (7,11,'PUBLISHED','PUBLIC','synthetic body',1,0)");
        jdbc.update("insert into community_notification(id,recipient_member_id,event_id,post_id,actor_member_id,notification_type,aggregate_key,actor_count,payload_json,create_time,update_time) values (41,8,'e02-16-event',7,11,'INTERACTION','7:LIKE',1,'{\"actorId\":11,\"marker\":\"synthetic-private-marker\"}',now(),now())");
        jdbc.update("insert into community_notification_actor(recipient_member_id,notification_type,aggregate_key,actor_member_id,last_event_id,create_time,update_time) values (8,'INTERACTION','7:LIKE',11,'e02-16-event',now(),now())");
    }

    @AfterAll
    void cleanup() { if (created) admin().execute("DROP DATABASE " + mysql.databaseName()); }

    @Test
    void foreignNotificationIdCannotMarkReadOrCreateCommandReceipt() {
        assertTrue(items(9L).isEmpty());
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.readNotification(9L, 41L, "foreign-read"));
        assertEquals(Integer.valueOf(404), error.getCode());
        assertNull(readAt());
        assertEquals(0, receiptCount());
        assertEquals(1, items(8L).size());
    }

    @Test
    void recipientReplayIsScopedToMemberAndDoesNotDuplicateReceiptOrTimestamp() {
        service.readNotification(8L, 41L, "same-client-request");
        Timestamp first = readAt();
        assertNotNull(first);
        service.readNotification(8L, 41L, "same-client-request");
        assertEquals(first, readAt());
        assertEquals(1, receiptCount());
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.readNotification(9L, 41L, "same-client-request"));
        assertEquals(Integer.valueOf(404), error.getCode(), "A's request ID must not authorize B replay");
        assertEquals(1, receiptCount());
        assertEquals(first, readAt());
        assertTrue(items(9L).isEmpty());
    }

    @Test
    void withdrawnParentRemovesPreviouslyVisiblePayloadAndForeignReadStillCannotAcknowledge() {
        List<Map<String, Object>> visible = items(8L);
        assertEquals(1, visible.size());
        assertTrue(visible.get(0).get("payload_json").toString().contains("synthetic-private-marker"));
        for (String lifecycle : new String[]{"HIDDEN", "DELETED"}) {
            jdbc.update("update community_post set lifecycle=?,version=version+1 where id=7", lifecycle);
            Map<String, Object> result = service.notifications(8L, null, 20);
            assertTrue(((List<?>) result.get("items")).isEmpty(), lifecycle);
            assertNull(result.get("nextCursor"), "No cursor through a suppressed-only page");
            ServiceException error = assertThrows(ServiceException.class,
                    () -> service.readNotification(9L, 41L, "foreign-" + lifecycle));
            assertEquals(Integer.valueOf(404), error.getCode());
            assertNull(readAt());
            assertEquals(0, receiptCount());
            assertEquals(1, jdbc.queryForObject("select count(*) from community_notification", Integer.class),
                    "Visibility must suppress a still-persisted notification, not rely on asynchronous erasure");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Long member) {
        return (List<Map<String, Object>>) service.notifications(member, null, 20).get("items");
    }
    private Timestamp readAt() {
        return jdbc.queryForObject("select read_at from community_notification where id=41", Timestamp.class);
    }
    private int receiptCount() {
        return jdbc.queryForObject("select count(*) from community_post_command_receipt", Integer.class);
    }
    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
    }

    private void schema() {
        jdbc.execute("create table community_post(id bigint primary key,author_id bigint,community_id bigint,client_request_id varchar(96),body varchar(100),title varchar(100),post_type varchar(32),audience varchar(16),comment_policy varchar(16),reply_approval_enabled int,slow_mode_seconds int,lifecycle varchar(16),location_precision varchar(16),city_code varchar(32),poi_name varchar(100),approximate_geohash varchar(16),recommendation_eligible int,search_eligible int,interaction_enabled int,disclosure_type varchar(24),safety_labels_json varchar(100),guideline_version_id bigint,like_count int,comment_count int,bookmark_count int,share_count int,published_at timestamp,edited_at timestamp,deleted_at timestamp,version int,create_time timestamp,update_time timestamp)");
        jdbc.execute("create table ums_member_follow(member_id bigint,follow_member_id bigint)");
        jdbc.execute("create table im_block(member_id bigint,blocked_member_id bigint)");
        jdbc.execute("create table community_member_mute(member_id bigint,muted_member_id bigint)");
        jdbc.execute("create table club_member(member_id bigint,club_id bigint,status int,del_flag int)");
        jdbc.execute("create table community_post_member_action(post_id bigint,member_id bigint,action_type varchar(32))");
        jdbc.execute("create table community_post_topic(post_id bigint,topic_code varchar(64))");
        jdbc.execute("create table community_post_comment(id bigint primary key,post_id bigint,lifecycle varchar(16),author_approval_state varchar(16)) engine=InnoDB");
        jdbc.execute("create table community_notification(id bigint primary key,recipient_member_id bigint,event_id varchar(64),post_id bigint,comment_id bigint,actor_member_id bigint,notification_type varchar(32),aggregate_key varchar(128),actor_count int,payload_json json,read_at datetime,create_time datetime,update_time datetime) engine=InnoDB");
        jdbc.execute("create table community_notification_actor(recipient_member_id bigint,notification_type varchar(32),aggregate_key varchar(128),actor_member_id bigint,last_event_id varchar(64),create_time datetime,update_time datetime,primary key(recipient_member_id,notification_type,aggregate_key,actor_member_id)) engine=InnoDB");
        jdbc.execute("create table community_post_command_receipt(id bigint auto_increment primary key,member_id bigint,request_id varchar(96),command_type varchar(64),aggregate_id bigint,result_json json,create_time datetime,unique(member_id,request_id)) engine=InnoDB");

    }
}
