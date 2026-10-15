package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Actual service + MyBatis + two MySQL RR transactions; safety/visibility are outside this regression. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityPostCommentIdempotencyMySqlIT {
    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("community_comment_it");
    private static final long MEMBER = 9L;
    private static final long POST = 7L;
    private static final String KEY = "comment-concurrent-intent";
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private CommunityPostMapper realMapper;
    private TransactionTemplate tx;

    @BeforeAll
    void createDatabase() throws Exception {
        try (Connection c = dataSource(MYSQL.adminUrl()).getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = dataSource(MYSQL.databaseUrl());
        jdbc = new JdbcTemplate(dataSource);
        Configuration configuration = new Configuration(new Environment("community-comment-it",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("CommunityPost", CommunityPost.class);
        configuration.addMapper(CommunityPostMapper.class);
        String resource = "mapper/business/CommunityPostMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing " + resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        realMapper = new SqlSessionTemplate(factory).getMapper(CommunityPostMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(15);
    }

    @BeforeEach
    void resetSchema() {
        for (String table : List.of("community_post_outbox", "community_post_comment", "community_post_member_action", "community_post")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("CREATE TABLE community_post (id BIGINT PRIMARY KEY,like_count INT NOT NULL,bookmark_count INT NOT NULL,comment_count INT NOT NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE community_post_member_action (post_id BIGINT,action_type VARCHAR(16)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE community_post_comment (id BIGINT AUTO_INCREMENT PRIMARY KEY,post_id BIGINT NOT NULL,author_id BIGINT NOT NULL,"
                + "root_id BIGINT,parent_id BIGINT,replied_to_member_id BIGINT,client_request_id VARCHAR(96) NOT NULL,body VARCHAR(2000) NOT NULL,"
                + "lifecycle VARCHAR(16) NOT NULL,author_approval_state VARCHAR(16) NOT NULL,like_count INT NOT NULL,version INT NOT NULL,"
                + "published_at DATETIME,deleted_at DATETIME,create_time DATETIME NOT NULL,update_time DATETIME NOT NULL,"
                + "UNIQUE KEY uk_community_post_comment_request(author_id,client_request_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE community_post_outbox (id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id VARCHAR(36) NOT NULL,aggregate_type VARCHAR(32) NOT NULL,"
                + "aggregate_id BIGINT NOT NULL,event_type VARCHAR(64) NOT NULL,payload_json JSON NOT NULL,state VARCHAR(16) NOT NULL,"
                + "attempt_count INT NOT NULL,next_attempt_at DATETIME NOT NULL,create_time DATETIME NOT NULL,update_time DATETIME NOT NULL) ENGINE=InnoDB");
        jdbc.update("INSERT INTO community_post VALUES (?,0,0,0)", POST);
    }

    @AfterAll
    void dropDatabase() throws Exception {
        try (Connection c = dataSource(MYSQL.adminUrl()).getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    @Test
    void concurrentSameBodyRecoversWinnerDespiteEarlierNullSnapshot() throws Exception {
        List<Outcome> outcomes = race("same-body", "same-body");
        assertEquals(List.of(200, 200), outcomes.stream().map(o -> o.code).sorted().toList());
        assertEquals("same-body", jdbc.queryForObject("SELECT body FROM community_post_comment", String.class));
        assertSingleCommentAndEvent();
    }

    @Test
    void concurrentDifferentBodyConflictsWithoutOverwritingWinner() throws Exception {
        List<Outcome> outcomes = race("first-body", "second-body");
        assertEquals(List.of(200, 409), outcomes.stream().map(o -> o.code).sorted().toList());
        String winningBody = outcomes.stream().filter(o -> o.code == 200).findFirst().orElseThrow().body;
        assertEquals(winningBody, jdbc.queryForObject("SELECT body FROM community_post_comment", String.class));
        assertSingleCommentAndEvent();
    }

    private List<Outcome> race(String left, String right) throws Exception {
        CountDownLatch bothSnapshots = new CountDownLatch(2);
        CountDownLatch releaseInsert = new CountDownLatch(1);
        ThreadLocal<Boolean> firstLookup = ThreadLocal.withInitial(() -> true);
        CommunityPostMapper mapper = mock(CommunityPostMapper.class);
        CommunityPost post = new CommunityPost();
        post.setId(POST);
        post.setAuthorId(MEMBER);
        post.setLifecycle("PUBLISHED");
        post.setAudience("PUBLIC");
        post.setCommentPolicy("EVERYONE");
        post.setInteractionEnabled(1);
        post.setReplyApprovalEnabled(0);
        post.setSlowModeSeconds(0);
        when(mapper.selectById(POST, MEMBER)).thenReturn(post);
        doAnswer(inv -> {
            Map<String, Object> row = realMapper.selectCommentByAuthorRequest(inv.getArgument(0), inv.getArgument(1));
            if (firstLookup.get()) {
                firstLookup.set(false);
                assertEquals(null, row, "both transactions must establish their own empty RR snapshot");
                bothSnapshots.countDown();
                assertTrue(releaseInsert.await(10, TimeUnit.SECONDS), "bounded initial-read barrier");
            }
            return row;
        }).when(mapper).selectCommentByAuthorRequest(anyLong(), anyString());
        doAnswer(inv -> realMapper.selectCommentByAuthorRequestForUpdate(inv.getArgument(0), inv.getArgument(1)))
                .when(mapper).selectCommentByAuthorRequestForUpdate(anyLong(), anyString());
        doAnswer(inv -> realMapper.insertComment(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                inv.getArgument(4), inv.getArgument(5), inv.getArgument(6), inv.getArgument(7), inv.getArgument(8), inv.getArgument(9)))
                .when(mapper).insertComment(anyLong(), anyLong(), isNull(), isNull(), isNull(), anyString(), anyString(), anyString(), anyString(), any());
        doAnswer(inv -> realMapper.updateCommentLifecycle(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)))
                .when(mapper).updateCommentLifecycle(anyLong(), anyString(), anyString(), anyInt(), any());
        doAnswer(inv -> realMapper.recomputeCounts(inv.getArgument(0))).when(mapper).recomputeCounts(anyLong());
        doAnswer(inv -> realMapper.insertOutbox(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)))
                .when(mapper).insertOutbox(anyString(), anyLong(), anyString(), anyString(), any());
        IContentSafetyGate safety = mock(IContentSafetyGate.class);
        when(safety.decide(any())).thenReturn(SafetyDecision.pass());
        CommunityPostServiceImpl service = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(), safety,
                mock(IAuditTaskService.class), mock(CommunityTrustService.class), mock(ICommunityMediaReceiptService.class));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> a = pool.submit(() -> send(service, left));
            Future<Outcome> b = pool.submit(() -> send(service, right));
            assertTrue(bothSnapshots.await(10, TimeUnit.SECONDS), "both actual MySQL reads precede either insert");
            releaseInsert.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            releaseInsert.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "no background transaction remains");
        }
    }

    private Outcome send(CommunityPostServiceImpl service, String body) {
        try {
            return tx.execute(status -> {
                assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
                CommunityPostRequests.Comment request = new CommunityPostRequests.Comment();
                request.setClientRequestId(KEY);
                request.setBody(body);
                Map<String, Object> result = service.comment(MEMBER, POST, request);
                assertEquals("PUBLISHED", result.get("lifecycle"));
                assertEquals("VISIBLE", result.get("approvalState"));
                return new Outcome(200, body);
            });
        } catch (ServiceException e) {
            assertEquals(Integer.valueOf(409), e.getCode());
            return new Outcome(e.getCode(), body);
        }
    }

    private void assertSingleCommentAndEvent() {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM community_post_comment", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT comment_count FROM community_post WHERE id=7", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM community_post_outbox", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM community_post_outbox o JOIN community_post_comment c ON JSON_EXTRACT(o.payload_json,'$.commentId')=c.id WHERE o.event_type='POST_COMMENTED' AND o.aggregate_id=c.post_id AND c.lifecycle='PUBLISHED' AND c.version=1", Integer.class));
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }

    private static class Outcome {
        final int code;
        final String body;
        Outcome(int code, String body) { this.code = code; this.body = body; }
    }
}
