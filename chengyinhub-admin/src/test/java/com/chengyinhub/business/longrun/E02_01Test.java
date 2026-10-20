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
@EnabledIfEnvironmentVariable(named = "CHENGYIN_E02_01_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E02_01Test {
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
        mysql = MysqlIntegrationTestConfig.fromEnvironment("e02_01_it");
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
                    if ("selectMedia".equals(method.getName()) && edited.compareAndSet(false, true)) {
                        // Parent has already been authorized/read. Commit a private replacement on another connection.
                        edit.execute(status -> {
                            writer.update("update community_post set audience='PRIVATE',lifecycle='CHECKING',body='synthetic private body',version=1 where id=1");
                            writer.update("delete from community_post_reference where post_id=1");
                            writer.update("insert into community_post_reference(id,post_id,reference_type,reference_id,snapshot_json,privacy_snapshot) values (2,1,'POI',22,'{\"title\":\"synthetic private reference\"}','PUBLIC_SAFE')");
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
    void detailKeepsPublicParentAndReferencesInOneSnapshotDuringPrivateEdit() {
        jdbc.update("insert into community_post(id,author_id,body,audience,lifecycle,comment_policy,interaction_enabled,version) values (1,11,'synthetic public body','PUBLIC','PUBLISHED','OFF',0,0)");
        jdbc.update("insert into community_post_reference(id,post_id,reference_type,reference_id,snapshot_json,privacy_snapshot) values (1,1,'POI',21,'{\"title\":\"synthetic public reference\"}','PUBLIC_SAFE')");
        Map<String, Object> result = service.detail(8L, 1L);
        assertTrue(edited.get(), "The independent edit must commit between parent and child reads");
        assertEquals("synthetic public body", ((CommunityPost) result.get("post")).getBody());
        List<?> references = (List<?>) result.get("references");
        assertEquals(1, references.size());
        assertEquals(21L, ((Number) ((Map<?, ?>) references.get(0)).get("reference_id")).longValue());
        // Independent readback proves the private replacement actually committed, rather than a no-op fixture.
        assertEquals("PRIVATE", jdbc.queryForObject("select audience from community_post where id=1", String.class));
        assertEquals("synthetic private body", jdbc.queryForObject("select body from community_post where id=1", String.class));
        assertEquals(22L, jdbc.queryForObject("select reference_id from community_post_reference where post_id=1", Long.class));
        ServiceException error = assertThrows(ServiceException.class, () -> service.detail(8L, 1L));
        assertEquals(Integer.valueOf(404), error.getCode(), "A subsequent request must use the new visibility");
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
        // master 的 selectReferences 另 LEFT JOIN 上架主题投影 topic_id：活动补 del_flag/topic_id 两列及空主题表。
        jdbc.execute("create table cms_activity(id bigint primary key,publish_status int,cancel_time datetime,del_flag int,topic_id bigint)");
        jdbc.execute("create table cms_topic(id bigint primary key,status int,user_status int,del_flag int,is_template int)");
        jdbc.execute("create table community_post_mention(post_id bigint,mentioned_member_id bigint)");
        jdbc.update("insert into ums_member values (11,'synthetic author',''),(8,'synthetic viewer','')");
    }
}
