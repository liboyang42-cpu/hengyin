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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: real MySQL mapper and service transaction; no fake notification implementation. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_E02_22_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E02_22Test {
    private MysqlIntegrationTestConfig mysql;
    private boolean created;
    private JdbcTemplate jdbc;
    private CommunityPostServiceImpl service;
    private CommunityPostMapper mapper;
    private volatile String fault;
    private volatile java.util.concurrent.CyclicBarrier afterActionLock;

    @BeforeAll
    void setup() throws Exception {
        for (String key : new String[]{"CHENGYIN_TEST_MYSQL_HOST", "CHENGYIN_TEST_MYSQL_PORT",
                "CHENGYIN_TEST_MYSQL_DATABASE_BASE", "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX",
                "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"}) {
            assertNotNull(System.getenv(key), "Explicit authorized scratch configuration required: " + key);
            if (!key.endsWith("PASSWORD")) assertFalse(System.getenv(key).trim().isEmpty(), key);
        }
        mysql = MysqlIntegrationTestConfig.fromEnvironment("e02_22_it");
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(
                admin().queryForObject("select version()", String.class)), "MySQL 8+ required");
        admin().execute("CREATE DATABASE " + mysql.databaseName() + " CHARACTER SET utf8mb4");
        created = true;
        DriverManagerDataSource ds = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(ds);
        schema();
        Configuration c = new Configuration(new Environment("e02-22", new SpringManagedTransactionFactory(), ds));
        c.getTypeAliasRegistry().registerAlias("CommunityPost", CommunityPost.class);
        c.addMapper(CommunityPostMapper.class);
        String resource = "mapper/business/CommunityPostMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            new XMLMapperBuilder(input, c, resource, c.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c))
                .getMapper(CommunityPostMapper.class);
        CommunityPostMapper scheduled = (CommunityPostMapper) java.lang.reflect.Proxy.newProxyInstance(
                CommunityPostMapper.class.getClassLoader(), new Class<?>[]{CommunityPostMapper.class}, (proxyObject, method, args) -> {
                    if ("insertCommandReceipt".equals(method.getName()) && "COMMENT_LIKE".equals(args[2]) && fault != null) {
                        String currentFault = fault;
                        fault = null;
                        if ("UNKNOWN".equals(currentFault)) throw new org.springframework.dao.DataAccessResourceFailureException("synthetic unknown write failure");
                        // Deterministically make another command win before migration INSERT; retain real SQL/returns.
                        mapper.insertCommandReceipt((Long) args[0], (String) args[1], "POST_SHARE", 77L, "{}", (java.util.Date) args[5]);
                        if ("DUPLICATE".equals(currentFault)) throw new org.springframework.dao.DuplicateKeyException("synthetic migration collision after real winner insert");
                    }
                    try {
                        Object result = method.invoke(mapper, args);
                        java.util.concurrent.CyclicBarrier barrier = afterActionLock;
                        if ("selectCommentLikeActionForUpdate".equals(method.getName()) && barrier != null) {
                            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        }
                        return result;
                    } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                });
        CommunityPostServiceImpl target = new CommunityPostServiceImpl(scheduled,
                new CommunityPostLifecyclePolicy(), null, null, null, null);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
                new AnnotationTransactionAttributeSource()));
        service = (CommunityPostServiceImpl) proxy.getProxy();
    }

    @BeforeEach
    void seed() {
        fault = null;
        afterActionLock = null;
        for (String table : new String[]{"community_post_comment_action", "community_post_command_receipt", "community_post_outbox", "community_post_comment", "community_post"}) jdbc.update("delete from " + table);
        jdbc.update("insert into community_post(id,author_id,lifecycle,audience,body,interaction_enabled,version) values (7,11,'PUBLISHED','PUBLIC','synthetic body',1,0)");
        jdbc.update("insert into community_post_comment(id,post_id,author_id,body,lifecycle,author_approval_state,version,like_count) values (17,7,11,'synthetic comment','PUBLISHED','VISIBLE',0,0),(18,7,11,'synthetic other comment','PUBLISHED','VISIBLE',0,0)");
    }

    @AfterAll
    void cleanup() { if (created) admin().execute("DROP DATABASE " + mysql.databaseName()); }

    @Test
    void oldLikeReplayAfterUnlikeKeepsTheActionRemovedAndDoesNotNotifyAgain() {
        service.likeComment(9L, 17L, "synthetic-like-1");
        service.unlikeComment(9L, 17L, "synthetic-unlike-2");
        service.likeComment(9L, 17L, "synthetic-like-1");
        assertEquals(0, actions());
        assertEquals(0, jdbc.queryForObject("select like_count from community_post_comment where id=17", Integer.class));
        assertEquals(1, events("COMMENT_LIKED"));
        assertEquals(1, events("COMMENT_UNLIKED"));
        assertEquals(1, events("NOTIFICATION_REQUESTED"));
        assertReceipt("synthetic-like-1", "COMMENT_LIKE", 17);
    }

    @Test
    void receiptKeepsCommentBindingAndAnotherMemberKeepsAnIndependentNamespace() {
        service.likeComment(9L, 17L, "synthetic-like-1");
        service.unlikeComment(9L, 17L, "synthetic-unlike-2");
        ServiceException conflict = assertThrows(ServiceException.class, () -> service.likeComment(9L, 18L, "synthetic-like-1"));
        assertEquals(Integer.valueOf(409), conflict.getCode());
        service.likeComment(8L, 18L, "synthetic-like-1");
        assertEquals(1, actions());
        assertEquals(8L, jdbc.queryForObject("select member_id from community_post_comment_action", Long.class));
    }

    @Test
    void aNewRequestCanLikeAgainAfterTheOldRequestHasBeenCancelled() {
        service.likeComment(9L, 17L, "synthetic-like-1");
        service.unlikeComment(9L, 17L, "synthetic-unlike-2");
        service.likeComment(9L, 17L, "synthetic-like-3");
        assertEquals(1, actions());
        assertEquals(2, events("COMMENT_LIKED"));
        assertReceipt("synthetic-like-3", "COMMENT_LIKE", 17);
    }

    @Test
    void anExistingLegacyLikeReplayBackfillsIdentityWithoutRepeatingEffects() {
        legacy(17, "synthetic-legacy-1");
        service.likeComment(9L, 17L, "synthetic-legacy-1");
        assertReceipt("synthetic-legacy-1", "COMMENT_LIKE", 17);
        assertEquals(0, events("COMMENT_LIKED"));
        assertEquals(0, events("NOTIFICATION_REQUESTED"));
        service.unlikeComment(9L, 17L, "synthetic-unlike-2");
        service.likeComment(9L, 17L, "synthetic-legacy-1");
        assertEquals(0, actions());
    }

    @Test
    void unlikeBackfillsLegacyIdentityBeforeDeletionEvenWhenParentIsHidden() {
        legacy(17, "synthetic-legacy-1");
        jdbc.update("update community_post set lifecycle='HIDDEN' where id=7");
        service.unlikeComment(9L, 17L, "synthetic-unlike-2");
        assertReceipt("synthetic-legacy-1", "COMMENT_LIKE", 17);
        service.likeComment(9L, 17L, "synthetic-legacy-1");
        assertEquals(0, actions());
        assertEquals(0, events("COMMENT_LIKED"));
    }

    @Test
    void legacyForeignCommandOrAggregateIsPreservedWhileUnlikeStillSucceeds() {
        for (String command : new String[]{"POST_SHARE", "COMMENT_LIKE"}) {
            seed();
            legacy(17, "synthetic-legacy-1");
            mapper.insertCommandReceipt(9L, "synthetic-legacy-1", command, 77L, "{}", new java.util.Date());
            service.unlikeComment(9L, 17L, "synthetic-unlike-2");
            assertEquals(0, actions());
            assertReceipt("synthetic-legacy-1", command, 77);
            ServiceException conflict = assertThrows(ServiceException.class, () -> service.likeComment(9L, 17L, "synthetic-legacy-1"));
            assertEquals(Integer.valueOf(409), conflict.getCode());
        }
    }

    @Test
    void migrationInsertIgnoreAndExplicitDuplicateUseTheActualCurrentWinner() {
        for (String collision : new String[]{"IGNORE", "DUPLICATE"}) {
            seed();
            legacy(17, "synthetic-legacy-1");
            fault = collision;
            service.unlikeComment(9L, 17L, "synthetic-unlike-2");
            assertEquals(0, actions());
            assertReceipt("synthetic-legacy-1", "POST_SHARE", 77);
            ServiceException conflict = assertThrows(ServiceException.class, () -> service.likeComment(9L, 17L, "synthetic-legacy-1"));
            assertEquals(Integer.valueOf(409), conflict.getCode());
        }
    }

    @Test
    void unknownReceiptWriteFailureRollsBackLikeAndAllOutboxEffects() {
        fault = "UNKNOWN";
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> service.likeComment(9L, 17L, "synthetic-like-1"));
        assertEquals(0, actions());
        assertEquals(0, jdbc.queryForObject("select count(*) from community_post_command_receipt", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from community_post_outbox", Integer.class));
        assertEquals(0, jdbc.queryForObject("select like_count from community_post_comment where id=17", Integer.class));
    }

    @Test
    void sameMemberCrossedLegacyRequestsKeepOneSerializedWinnerWithoutPartialEffects() throws Exception {
        legacy(17, "synthetic-cross-a");
        legacy(18, "synthetic-cross-b");
        afterActionLock = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Boolean> a = pool.submit(() -> crossedUnlike(17L, "synthetic-cross-b"));
            java.util.concurrent.Future<Boolean> b = pool.submit(() -> crossedUnlike(18L, "synthetic-cross-a"));
            int successes = (a.get(20, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
                    + (b.get(20, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        } finally {
            afterActionLock = null;
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, actions());
        assertEquals(1, events("COMMENT_UNLIKED"));
        assertEquals(1, jdbc.queryForObject("select sum(like_count) from community_post_comment", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from community_post_command_receipt where command_type='COMMENT_LIKE'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from community_post_command_receipt where command_type='COMMENT_UNLIKE'", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from community_post_command_receipt", Integer.class));
        assertEquals(0, events("COMMENT_LIKED"));
    }

    private boolean crossedUnlike(Long comment, String request) {
        try {
            service.unlikeComment(9L, comment, request);
            return true;
        } catch (ServiceException conflict) {
            assertEquals(Integer.valueOf(409), conflict.getCode());
            return false;
        } catch (org.springframework.dao.ConcurrencyFailureException victim) {
            // MySQL gap-lock deadlock victims must propagate and roll back; no production retry/success masking.
            return false;
        }
    }

    private void legacy(long comment, String request) {
        jdbc.update("insert into community_post_comment_action(comment_id,member_id,action_type,request_id,create_time) values (?,9,'LIKE',?,now())", comment, request);
        jdbc.update("update community_post_comment set like_count=1 where id=?", comment);
    }
    private int actions() { return jdbc.queryForObject("select count(*) from community_post_comment_action", Integer.class); }
    private int events(String type) { return jdbc.queryForObject("select count(*) from community_post_outbox where event_type=?", Integer.class, type); }
    private void assertReceipt(String request, String command, long comment) {
        Map<String, Object> receipt = jdbc.queryForMap("select command_type,aggregate_id from community_post_command_receipt where member_id=9 and request_id=?", request);
        assertEquals(command, receipt.get("command_type"));
        assertEquals(comment, ((Number) receipt.get("aggregate_id")).longValue());
    }
    private JdbcTemplate admin() { return new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password())); }

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
        jdbc.execute("create table community_post_comment(id bigint primary key,post_id bigint,author_id bigint,root_id bigint,parent_id bigint,replied_to_member_id bigint,body varchar(100),lifecycle varchar(16),author_approval_state varchar(16),version int,like_count int,create_time timestamp) engine=InnoDB");
        jdbc.execute("create table community_post_comment_action(id bigint auto_increment primary key,comment_id bigint not null,member_id bigint not null,action_type varchar(16) not null,request_id varchar(96) not null,create_time datetime,unique(comment_id,member_id,action_type),unique(member_id,request_id)) engine=InnoDB");
        jdbc.execute("create table community_post_command_receipt(id bigint auto_increment primary key,member_id bigint not null,request_id varchar(96) not null,command_type varchar(80) not null,aggregate_id bigint not null,result_json json,create_time datetime,unique(member_id,request_id)) engine=InnoDB");
        jdbc.execute("create table community_post_outbox(id bigint auto_increment primary key,event_id varchar(36),aggregate_type varchar(40),aggregate_id bigint,event_type varchar(80),payload_json json,state varchar(20),attempt_count int,next_attempt_at datetime,create_time datetime,update_time datetime) engine=InnoDB");
        jdbc.update("insert into ums_member values (11,'synthetic author',''),(8,'synthetic viewer','')");
    }
}
