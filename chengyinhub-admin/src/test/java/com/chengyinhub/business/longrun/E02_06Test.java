package com.chengyinhub.business.longrun;

import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: real MySQL XML + Spring transaction proxy; decorator only schedules a committed edit. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_E02_06_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E02_06Test {
    private MysqlIntegrationTestConfig mysql;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource ds;
    private boolean created;
    private CommunityPostServiceImpl service;
    private CommunityPostMapper mapper;
    private final AtomicBoolean edited = new AtomicBoolean();

    @BeforeAll
    void setup() throws Exception {
        for (String key : new String[]{"CHENGYIN_TEST_MYSQL_HOST", "CHENGYIN_TEST_MYSQL_PORT",
                "CHENGYIN_TEST_MYSQL_DATABASE_BASE", "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX",
                "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"}) {
            assertNotNull(System.getenv(key), "Explicit authorized scratch configuration required: " + key);
            if (!key.endsWith("PASSWORD")) assertFalse(System.getenv(key).trim().isEmpty(), key);
        }
        mysql = MysqlIntegrationTestConfig.fromEnvironment("e02_06_it");
        JdbcTemplate admin = admin();
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(
                admin.queryForObject("select version()", String.class)), "MySQL 8+ required");
        // No DROP IF EXISTS: a collision must fail without taking ownership of an existing schema.
        admin.execute("CREATE DATABASE " + mysql.databaseName() + " CHARACTER SET utf8mb4");
        created = true;
        ds = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(ds);
        schema();
        Configuration c = new Configuration(new Environment("b05-visibility", new SpringManagedTransactionFactory(), ds));
        c.getTypeAliasRegistry().registerAlias("CommunityPost", CommunityPost.class);
        c.addMapper(CommunityPostMapper.class);
        String resource = "mapper/business/CommunityPostMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            new XMLMapperBuilder(input, c, resource, c.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c)).getMapper(CommunityPostMapper.class);
        JdbcTemplate writer = new JdbcTemplate(new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password()));
        org.springframework.transaction.support.TransactionTemplate edit = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(writer.getDataSource()));
        edit.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        CommunityPostMapper interleaved = (CommunityPostMapper) java.lang.reflect.Proxy.newProxyInstance(
                CommunityPostMapper.class.getClassLoader(), new Class<?>[]{CommunityPostMapper.class}, (proxy, method, args) -> {
                    if ("selectComments".equals(method.getName()) && edited.compareAndSet(false, true)) {
                        // Parent has already been authorized/read. Commit another author edit on a separate connection.
                        edit.execute(status -> {
                            writer.update("update community_post set audience='PRIVATE',lifecycle='CHECKING',version=1 where id=1");
                            writer.update("insert into community_post_comment(id,post_id,author_id,body,lifecycle,author_approval_state,version,like_count) values (20,1,11,'synthetic private comment','PUBLISHED','VISIBLE',0,0)");
                            return null;
                        });
                    }
                    try { return method.invoke(mapper, args); }
                    catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                });
        CommunityPostServiceImpl target = new CommunityPostServiceImpl(interleaved,
                new CommunityPostLifecyclePolicy(), null, null, null, null);
        org.springframework.aop.framework.ProxyFactory proxy = new org.springframework.aop.framework.ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        service = (CommunityPostServiceImpl) proxy.getProxy();
    }

    @AfterAll
    void cleanup() {
        if (created) admin().execute("DROP DATABASE " + mysql.databaseName());
    }

    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
    }

    @Test
    void commentsKeepParentAuthorizationAndThreadRowsInOneSnapshot() {
        jdbc.update("insert into community_post(id,author_id,body,audience,lifecycle,comment_policy,interaction_enabled,version) values (1,11,'synthetic public body','PUBLIC','PUBLISHED','OFF',0,0)");
        jdbc.update("insert into community_post_comment(id,post_id,author_id,body,lifecycle,author_approval_state,version,like_count) values (10,1,11,'synthetic public comment','PUBLISHED','VISIBLE',0,0)");
        Map<String, Object> page = service.comments(8L, 1L, null, 20);
        assertTrue(edited.get(), "The independent private edit must commit before thread selection");
        List<?> comments = (List<?>) page.get("items");
        assertEquals(1, comments.size());
        assertEquals("synthetic public comment", ((Map<?, ?>) comments.get(0)).get("body"));
        assertEquals(10L, ((Number) page.get("nextCursor")).longValue());
        assertEquals("PRIVATE", jdbc.queryForObject("select audience from community_post where id=1", String.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from community_post_comment where post_id=1", Integer.class));
        ServiceException error = assertThrows(ServiceException.class, () -> service.comments(8L, 1L, 10L, 20));
        assertEquals(Integer.valueOf(404), error.getCode(), "A new cursor request cannot bypass current parent visibility");
        // Preserve the author's existing pending-approval review capability.
        jdbc.update("insert into community_post_comment(id,post_id,author_id,body,lifecycle,author_approval_state,version,like_count) values (30,1,8,'synthetic pending approval','PUBLISHED','PENDING',0,0)");
        List<?> authorRows = (List<?>) service.comments(11L, 1L, null, 20).get("items");
        assertEquals(3, authorRows.size());
        assertEquals("PENDING", ((Map<?, ?>) authorRows.get(0)).get("author_approval_state"));
    }

    private void schema() {
        jdbc.execute("create table community_post(id bigint primary key,author_id bigint,community_id bigint,client_request_id varchar(96),body varchar(100),title varchar(100),post_type varchar(32),audience varchar(16),comment_policy varchar(16),reply_approval_enabled int,slow_mode_seconds int,lifecycle varchar(16),location_precision varchar(16),city_code varchar(32),poi_name varchar(100),approximate_geohash varchar(16),recommendation_eligible int,search_eligible int,interaction_enabled int,disclosure_type varchar(24),safety_labels_json varchar(100),guideline_version_id bigint,like_count int,comment_count int,bookmark_count int,share_count int,published_at timestamp,edited_at timestamp,deleted_at timestamp,version int,create_time timestamp,update_time timestamp)");
        jdbc.execute("create table ums_member(id bigint primary key,nickname varchar(100),avatar varchar(100))");
        jdbc.execute("create table ums_member_follow(member_id bigint,follow_member_id bigint)");
        jdbc.execute("create table im_block(member_id bigint,blocked_member_id bigint)");
        jdbc.execute("create table community_member_mute(member_id bigint,muted_member_id bigint)");
        jdbc.execute("create table club_member(member_id bigint,club_id bigint,status int,del_flag int)");
        jdbc.execute("create table community_post_member_action(post_id bigint,member_id bigint,action_type varchar(32))");
        jdbc.execute("create table community_post_topic(post_id bigint,topic_code varchar(64))");
        jdbc.execute("create table community_post_enforcement(id bigint primary key,post_id bigint,target_member_id bigint,enforcement_type varchar(32),rule_code varchar(64),guideline_version_id bigint,reason_code varchar(64),previous_lifecycle varchar(16),evidence_snapshot_json varchar(255),operator_user_id bigint,starts_at timestamp,ends_at timestamp,status varchar(16),version int,create_time timestamp,update_time timestamp,constraint chk_community_enforcement_target check (post_id is not null or target_member_id is not null))");
        jdbc.execute("create table community_post_revision(id bigint auto_increment primary key,post_id bigint,editor_member_id bigint,snapshot_version int,immutable_snapshot_json json,edit_reason varchar(500),review_state varchar(16),create_time datetime,unique(post_id,snapshot_version))");
        jdbc.execute("create table community_post_media(id bigint,post_id bigint,media_type varchar(32),derived_object_key varchar(255),mime_type varchar(100),width int,height int,duration_ms bigint,sort_order int,alt_text varchar(255),review_state varchar(16),retention_state varchar(16))");
        jdbc.execute("create table community_post_reference(id bigint,post_id bigint,reference_type varchar(32),reference_id bigint,snapshot_json json,privacy_snapshot varchar(32),invalidated_at datetime)");
        jdbc.execute("create table community_post_mention(post_id bigint,mentioned_member_id bigint)");
        jdbc.execute("create table community_post_comment(id bigint primary key,post_id bigint,author_id bigint,root_id bigint,parent_id bigint,replied_to_member_id bigint,body varchar(100),lifecycle varchar(16),author_approval_state varchar(16),version int,like_count int,create_time timestamp)");
        jdbc.execute("create table community_post_comment_action(comment_id bigint,member_id bigint,action_type varchar(32))");
        jdbc.update("insert into ums_member values (11,'synthetic author',''),(8,'synthetic viewer','')");
    }
}
