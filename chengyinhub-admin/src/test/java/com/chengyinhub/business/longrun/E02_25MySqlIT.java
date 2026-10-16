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
@EnabledIfEnvironmentVariable(named = "CHENGYIN_E02_25_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E02_25MySqlIT {
    private MysqlIntegrationTestConfig mysql;
    private boolean created;
    private JdbcTemplate jdbc;
    private CommunityPostServiceImpl service;
    private CommunityPostMapper mapper;
    private SqlSessionTemplate sessions;
    private org.springframework.transaction.support.TransactionTemplate repeatableRead;
    private volatile Race race;

    private static class Race {
        final java.util.concurrent.CountDownLatch emptyRead = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch winnerCommitted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean paused = new java.util.concurrent.atomic.AtomicBoolean();
        volatile Thread loser;
        volatile boolean oldSnapshotStillEmpty;
        volatile boolean realDuplicate;
        volatile Map<String, Object> currentWinner;
    }

    @BeforeAll
    void setup() throws Exception {
        for (String key : new String[]{"CHENGYIN_TEST_MYSQL_HOST", "CHENGYIN_TEST_MYSQL_PORT",
                "CHENGYIN_TEST_MYSQL_DATABASE_BASE", "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX",
                "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"}) {
            assertNotNull(System.getenv(key), "Explicit authorized scratch configuration required: " + key);
            if (!key.endsWith("PASSWORD")) assertFalse(System.getenv(key).trim().isEmpty(), key);
        }
        mysql = MysqlIntegrationTestConfig.fromEnvironment("e02_25_it");
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(
                admin().queryForObject("select version()", String.class)), "MySQL 8+ required");
        admin().execute("CREATE DATABASE " + mysql.databaseName() + " CHARACTER SET utf8mb4");
        created = true;
        DriverManagerDataSource ds = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(ds);
        schema();
        Configuration c = new Configuration(new Environment("e02-25", new SpringManagedTransactionFactory(), ds));
        c.getTypeAliasRegistry().registerAlias("CommunityPost", CommunityPost.class);
        c.addMapper(CommunityPostMapper.class);
        String resource = "mapper/business/CommunityPostMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            new XMLMapperBuilder(input, c, resource, c.getSqlFragments()).parse();
        }
        sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c));
        mapper = sessions.getMapper(CommunityPostMapper.class);
        CommunityPostMapper scheduled = (CommunityPostMapper) java.lang.reflect.Proxy.newProxyInstance(
                CommunityPostMapper.class.getClassLoader(), new Class<?>[]{CommunityPostMapper.class}, (proxyObject, method, args) -> {
                    Race current = race;
                    boolean isLoser = current != null && Thread.currentThread() == current.loser;
                    try {
                        Object result = method.invoke(mapper, args);
                        if (isLoser && "selectCommentByAuthorRequest".equals(method.getName())
                                && current.paused.compareAndSet(false, true)) {
                            assertNull(result, "T1 must genuinely see no request before T2 starts");
                            current.emptyRead.countDown();
                            assertTrue(current.winnerCommitted.await(20, java.util.concurrent.TimeUnit.SECONDS), "T2 must commit");
                            sessions.clearCache();
                            assertNull(mapper.selectCommentByAuthorRequest(9L, "synthetic-rr-reply"),
                                    "Clearing MyBatis cache must not refresh the established RR snapshot");
                            current.oldSnapshotStillEmpty = true;
                        }
                        if (isLoser && "selectCommentByAuthorRequestForUpdate".equals(method.getName())) {
                            @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) result;
                            current.currentWinner = row;
                        }
                        return result;
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        if (isLoser && "insertComment".equals(method.getName())
                                && e.getCause() instanceof org.springframework.dao.DuplicateKeyException) current.realDuplicate = true;
                        throw e.getCause();
                    }
                });
        com.chengyinhub.business.service.IContentSafetyGate safety = org.mockito.Mockito.mock(com.chengyinhub.business.service.IContentSafetyGate.class);
        org.mockito.Mockito.when(safety.decide(org.mockito.ArgumentMatchers.any())).thenReturn(com.chengyinhub.business.domain.dto.SafetyDecision.pass());
        CommunityPostServiceImpl target = new CommunityPostServiceImpl(scheduled,
                new CommunityPostLifecyclePolicy(), safety, null, null, null);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(ds);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (CommunityPostServiceImpl) proxy.getProxy();
        repeatableRead = new org.springframework.transaction.support.TransactionTemplate(manager);
        repeatableRead.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        repeatableRead.setTimeout(30);
    }

    @BeforeEach
    void seed() {
        race = null;
        for (String table : new String[]{"community_post_outbox", "community_post_comment", "community_post"}) jdbc.update("delete from " + table);
        jdbc.update("insert into community_post(id,author_id,lifecycle,audience,body,comment_policy,slow_mode_seconds,interaction_enabled,version) values (7,11,'PUBLISHED','PUBLIC','synthetic body','EVERYONE',0,1,0),(8,11,'PUBLISHED','PUBLIC','synthetic other post','EVERYONE',0,1,0)");
        jdbc.update("insert into community_post_comment(id,post_id,author_id,client_request_id,body,lifecycle,author_approval_state,version,like_count) values (17,7,11,'synthetic-parent17','parent17','PUBLISHED','VISIBLE',0,0),(18,7,11,'synthetic-parent18','parent18','PUBLISHED','VISIBLE',0,0),(27,8,11,'synthetic-parent27','parent27','PUBLISHED','VISIBLE',0,0)");
    }

    @AfterAll
    void cleanup() { if (created) admin().execute("DROP DATABASE " + mysql.databaseName()); }

    @Test
    void exactWinnerIsRecoveredAfterTheLosingTransactionEstablishedRepeatableRead() throws Exception {
        runRace(7L, 17L, " 同意 ", 7L, 17L, "同意", true);
    }

    @Test
    void differentCommittedPostBodyOrParentRemainConflictAfterCurrentRead() throws Exception {
        for (int difference = 0; difference < 3; difference++) {
            seed();
            runRace(7L, 17L, "同意", difference == 0 ? 8L : 7L,
                    difference == 0 ? 27L : difference == 2 ? 18L : 17L,
                    difference == 1 ? "另一正文" : "同意", false);
        }
    }

    private void runRace(Long loserPost, Long loserParent, String loserBody,
                         Long winnerPost, Long winnerParent, String winnerBody, boolean exact) throws Exception {
        Race current = new Race();
        race = current;
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Map<String, Object>> loser = worker.submit(() -> {
                current.loser = Thread.currentThread();
                return repeatableRead.execute(status -> {
                    assertEquals("REPEATABLE-READ", jdbc.queryForObject("select @@transaction_isolation", String.class));
                    return service.comment(9L, loserPost, request(loserParent, loserBody));
                });
            });
            assertTrue(current.emptyRead.await(15, java.util.concurrent.TimeUnit.SECONDS), "T1 must establish empty read view first");
            // This thread has a separate connection/transaction; service returns only after the winner commits.
            assertEquals("PUBLISHED", service.comment(9L, winnerPost, request(winnerParent, winnerBody)).get("lifecycle"));
            Map<String, Object> winner = jdbc.queryForMap("select id,post_id,parent_id,body,lifecycle from community_post_comment where author_id=9 and client_request_id='synthetic-rr-reply'");
            assertEquals(winnerPost.longValue(), ((Number) winner.get("post_id")).longValue());
            assertEquals(winnerParent.longValue(), ((Number) winner.get("parent_id")).longValue());
            assertEquals(winnerBody.trim(), winner.get("body"));
            current.winnerCommitted.countDown();
            if (exact) {
                assertEquals("PUBLISHED", loser.get(30, java.util.concurrent.TimeUnit.SECONDS).get("lifecycle"));
            } else {
                java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> loser.get(30, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof ServiceException);
                assertEquals(Integer.valueOf(409), ((ServiceException) failure.getCause()).getCode());
            }
            assertTrue(current.oldSnapshotStillEmpty, "Ordinary SQL must remain blind to the winner under RR");
            assertTrue(current.realDuplicate, "The loser must reach the actual MySQL unique-key conflict");
            assertNotNull(current.currentWinner, "Production current-read SQL must recover the committed winner");
            assertEquals(((Number) winner.get("id")).longValue(), ((Number) current.currentWinner.get("id")).longValue());
            assertEquals(1, jdbc.queryForObject("select count(*) from community_post_comment where author_id=9 and client_request_id='synthetic-rr-reply'", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from community_post_outbox where event_type='POST_COMMENTED'", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from community_post_outbox where event_type='NOTIFICATION_REQUESTED'", Integer.class));
            int expectedCount = winnerPost.equals(7L) ? 3 : 2;
            assertEquals(expectedCount, jdbc.queryForObject("select comment_count from community_post where id=?", Integer.class, winnerPost));
        } finally {
            current.winnerCommitted.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS), "No worker may outlive schema cleanup");
            race = null;
        }
    }

    private com.chengyinhub.business.domain.dto.CommunityPostRequests.Comment request(Long parent, String body) {
        com.chengyinhub.business.domain.dto.CommunityPostRequests.Comment request = new com.chengyinhub.business.domain.dto.CommunityPostRequests.Comment();
        request.setClientRequestId("synthetic-rr-reply");
        request.setParentId(parent);
        request.setBody(body);
        return request;
    }
    private JdbcTemplate admin() { return new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password())); }

    private void schema() {
        jdbc.execute("create table community_post(id bigint primary key,author_id bigint,community_id bigint,client_request_id varchar(96),body varchar(100),title varchar(100),post_type varchar(32),audience varchar(16),comment_policy varchar(16),reply_approval_enabled int,slow_mode_seconds int,lifecycle varchar(16),location_precision varchar(16),city_code varchar(32),poi_name varchar(100),approximate_geohash varchar(16),recommendation_eligible int,search_eligible int,interaction_enabled int,disclosure_type varchar(24),safety_labels_json varchar(100),guideline_version_id bigint,like_count int,comment_count int,bookmark_count int,share_count int,published_at timestamp,edited_at timestamp,deleted_at timestamp,version int,create_time timestamp,update_time timestamp) engine=InnoDB");
        jdbc.execute("create table ums_member(id bigint primary key,nickname varchar(100),avatar varchar(100),wx_open_id varchar(100))");
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
        jdbc.execute("create table community_post_comment(id bigint auto_increment primary key,post_id bigint not null,author_id bigint not null,root_id bigint,parent_id bigint,replied_to_member_id bigint,client_request_id varchar(96) not null,body varchar(2000),lifecycle varchar(16),author_approval_state varchar(16),version int,like_count int,published_at datetime,create_time datetime,update_time datetime,unique(author_id,client_request_id)) engine=InnoDB");
        jdbc.execute("create table community_post_comment_action(id bigint auto_increment primary key,comment_id bigint not null,member_id bigint not null,action_type varchar(16) not null,request_id varchar(96) not null,create_time datetime,unique(comment_id,member_id,action_type),unique(member_id,request_id)) engine=InnoDB");
        jdbc.execute("create table community_post_command_receipt(id bigint auto_increment primary key,member_id bigint not null,request_id varchar(96) not null,command_type varchar(80) not null,aggregate_id bigint not null,result_json json,create_time datetime,unique(member_id,request_id)) engine=InnoDB");
        jdbc.execute("create table community_post_outbox(id bigint auto_increment primary key,event_id varchar(36),aggregate_type varchar(40),aggregate_id bigint,event_type varchar(80),payload_json json,state varchar(20),attempt_count int,next_attempt_at datetime,create_time datetime,update_time datetime) engine=InnoDB");
        jdbc.update("insert into ums_member(id,nickname,avatar) values (11,'synthetic author',''),(8,'synthetic viewer','')");
    }
}
