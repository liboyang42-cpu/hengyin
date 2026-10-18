package com.chengyinhub.business.service;

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
import static org.junit.jupiter.api.Assertions.*;

/** Real XML/service read boundary; no HTTP, OSS, Redis, mock mapper or write-service claim. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_B05_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class B05CommunityVisibilityMySqlIT {
    private MysqlIntegrationTestConfig mysql;
    private JdbcTemplate jdbc;
    private boolean created;
    private CommunityPostServiceImpl service;
    private CommunityPostMapper mapper;

    @BeforeAll
    void setup() throws Exception {
        for (String key : new String[]{"CHENGYIN_TEST_MYSQL_HOST", "CHENGYIN_TEST_MYSQL_PORT",
                "CHENGYIN_TEST_MYSQL_DATABASE_BASE", "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX",
                "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"}) {
            assertNotNull(System.getenv(key), "Explicit authorized scratch configuration required: " + key);
            if (!key.endsWith("PASSWORD")) assertFalse(System.getenv(key).trim().isEmpty(), key);
        }
        mysql = MysqlIntegrationTestConfig.fromEnvironment("b05_visibility_it");
        JdbcTemplate admin = admin();
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(
                admin.queryForObject("select version()", String.class)), "MySQL 8+ required");
        // No DROP IF EXISTS: a collision must fail without taking ownership of an existing schema.
        admin.execute("CREATE DATABASE " + mysql.databaseName() + " CHARACTER SET utf8mb4");
        created = true;
        DriverManagerDataSource ds = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
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
        // Only detail/revisions are called; these paths use the real mapper and lifecycle policy.
        // Write-side collaborators are deliberately absent and no fake success is supplied.
        service = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(), null, null, null, null);
    }

    @AfterAll
    void cleanup() {
        if (created) admin().execute("DROP DATABASE " + mysql.databaseName());
    }

    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
    }

    @BeforeEach
    void fixture() {
        jdbc.update("delete from community_post_revision");
        jdbc.update("delete from community_post");
        jdbc.update("delete from club_member");
        jdbc.update("insert into community_post(id,author_id,community_id,client_request_id,body,post_type,audience,comment_policy,lifecycle,location_precision,recommendation_eligible,search_eligible,interaction_enabled,safety_labels_json,version) values (1,11,55,'synthetic-e02','synthetic old body','MOMENT','PUBLIC','OFF','PUBLISHED','NONE',1,1,1,'[]',0)");
        assertEquals(1, mapper.insertRevision(1L, 11L, 0, "{\"body\":\"synthetic old body\"}", "synthetic edit", "PASS", new java.util.Date()));
        assertEquals(1, jdbc.queryForObject("select count(*) from community_post_revision where post_id=1", Integer.class));
    }

    @Test
    void deletedOldDetailAndRevisionReadsRejectBothIdentities() {
        assertEquals("synthetic old body", post(11L).getBody());
        assertEquals("synthetic old body", post(8L).getBody());
        assertEquals(1, service.revisions(11L, 1L).size());
        denied(403, () -> service.revisions(8L, 1L));
        assertEquals(1, mapper.transitionCas(1L, "PUBLISHED", "DELETED", 0, null,
                new java.util.Date(), 0, 0, 0, new java.util.Date()));
        for (Long viewer : new Long[]{11L, 8L}) {
            denied(404, () -> service.detail(viewer, 1L));
            denied(404, () -> service.revisions(viewer, 1L));
        }
        Map<String, Object> row = jdbc.queryForMap("select lifecycle,version,body from community_post where id=1");
        assertEquals("DELETED", row.get("lifecycle"));
        assertEquals(1, ((Number) row.get("version")).intValue());
        assertEquals("synthetic old body", row.get("body"));
        assertEquals(1, jdbc.queryForObject("select count(*) from community_post_revision where post_id=1 and immutable_snapshot_json like '%synthetic old body%'", Integer.class));
    }

    @Test
    void legitimateAuthorDraftKeepsDetailAndHistoryWhileOtherViewerIsDenied() {
        jdbc.update("update community_post set lifecycle='DRAFT',audience='PRIVATE' where id=1");
        assertEquals("DRAFT", post(11L).getLifecycle());
        List<Map<String, Object>> history = service.revisions(11L, 1L);
        assertEquals(1, history.size());
        assertEquals(0, ((Number) history.get(0).get("snapshot_version")).intValue());
        assertFalse(history.get(0).containsKey("immutable_snapshot_json"));
        denied(404, () -> service.detail(8L, 1L));
        denied(404, () -> service.revisions(8L, 1L));
        assertEquals("DRAFT", jdbc.queryForObject("select lifecycle from community_post where id=1", String.class));
    }

    @Test
    void leavingCommunityClosesOldDetailWithoutRemovingAuthorWorkspace() {
        jdbc.update("update community_post set audience='COMMUNITY' where id=1");
        jdbc.update("insert into club_member(member_id,club_id,status,del_flag) values (8,55,1,0)");
        assertEquals("COMMUNITY", post(8L).getAudience());
        denied(403, () -> service.revisions(8L, 1L));
        for (int[] boundary : new int[][]{{0,0},{1,1}}) {
            jdbc.update("update club_member set status=?,del_flag=? where member_id=8 and club_id=55", boundary[0], boundary[1]);
            assertEquals(0, mapper.countCommunityMember(8L, 55L));
            denied(404, () -> service.detail(8L, 1L));
            denied(404, () -> service.revisions(8L, 1L));
            assertEquals("COMMUNITY", post(11L).getAudience());
            assertEquals(1, service.revisions(11L, 1L).size());
            Map<String, Object> row = jdbc.queryForMap("select status,del_flag from club_member where member_id=8 and club_id=55");
            assertEquals(boundary[0], ((Number) row.get("status")).intValue());
            assertEquals(boundary[1], ((Number) row.get("del_flag")).intValue());
        }
        assertEquals("COMMUNITY", jdbc.queryForObject("select audience from community_post where id=1", String.class));
    }

    private CommunityPost post(Long viewer) {
        return (CommunityPost) service.detail(viewer, 1L).get("post");
    }

    private void denied(int code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(Integer.valueOf(code), assertThrows(ServiceException.class, action).getCode());
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
        jdbc.execute("create table cms_activity(id bigint primary key,publish_status int,cancel_time datetime)");
        jdbc.execute("create table community_post_mention(post_id bigint,mentioned_member_id bigint)");
        jdbc.update("insert into ums_member values (11,'synthetic author',''),(8,'synthetic viewer','')");
    }
}
