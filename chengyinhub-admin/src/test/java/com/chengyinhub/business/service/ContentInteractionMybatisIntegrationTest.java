package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.web.controller.api.ApiCommentController;
import com.chengyinhub.web.controller.api.ApiCreativeSquareController;
import com.chengyinhub.common.core.domain.AjaxResult;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentInteractionMybatisIntegrationTest {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("content_it");
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private ApiCommentController comments;
    private ApiCreativeSquareController squares;
    private IWxContentSecurityService security;
    private SqlSessionTemplate session;
    private UmsCommentServiceImpl commentService;

    public static class CommentApi extends ApiCommentController {
        @Override public Long getAppUserId() { return 1L; }
    }
    public static class SquareApi extends ApiCreativeSquareController {
        @Override public Long getAppUserId() { return 1L; }
    }

    @BeforeAll void setup() throws Exception {
        new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                .execute("CREATE DATABASE " + mysql.databaseName());
        dataSource = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(dataSource);
        String base = new String(Files.readAllBytes(Paths.get("../sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
        for (String table : new String[]{"ums_comment", "ums_comment_like", "ums_creative_square", "ums_creative_square_like", "ums_member_points_detail", "cms_points_setting"}) {
            int start = base.indexOf("CREATE TABLE `" + table + "` (");
            jdbc.execute(base.substring(start, base.indexOf(";", start)));
        }
        jdbc.execute("CREATE TABLE ums_member (id BIGINT PRIMARY KEY, point BIGINT NOT NULL)");
        // 评价资格只读这几列;整表 DDL 的注释里带分号,按分号切会切坏。
        jdbc.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, del_flag TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, del_flag TINYINT DEFAULT 0, topic_id BIGINT)");
        jdbc.execute("CREATE TABLE cms_registration (id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_type INT, owner_id BIGINT NOT NULL,"
                + " member_id BIGINT NOT NULL, payment_status TINYINT NOT NULL DEFAULT 0, verification_status TINYINT DEFAULT 0, topic_id BIGINT)");
        // base_schema_20260818.sql 早于 R10-03 广场发布幂等:mapper 现按 request_id/request_signature 读写,
        // 建表后必须补跑这条迁移,否则整套 BadSqlGrammar(Unknown column 'request_id')。
        String publishMigration = new String(Files.readAllBytes(
                Paths.get("../chengyinhub-admin/sql/migration_20260913_creative_square_publish_request_id.sql")),
                StandardCharsets.UTF_8);
        jdbc.execute(publishMigration.substring(publishMigration.indexOf("ALTER TABLE")));
        Configuration config = new Configuration(new Environment("content", new SpringManagedTransactionFactory(), dataSource));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (Class<?> type : new Class<?>[]{UmsCommentMapper.class, UmsCommentLikeMapper.class, UmsCreativeSquareMapper.class, UmsCreativeSquareLikeMapper.class, UmsMemberMapper.class, UmsMemberPointsDetailMapper.class, CmsPointsSettingMapper.class}) {
            config.addMapper(type);
            String resource = "mapper/business/" + type.getSimpleName() + ".xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        commentService = spy(new UmsCommentServiceImpl());
        ReflectionTestUtils.setField(commentService, "umsCommentMapper", session.getMapper(UmsCommentMapper.class));
        UmsMemberServiceImpl members = spy(new UmsMemberServiceImpl());
        ReflectionTestUtils.setField(members, "umsMemberMapper", session.getMapper(UmsMemberMapper.class));
        doAnswer(invocation -> {
            UmsMember member = new UmsMember(); member.setId(1L);
            member.setPoint(jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
            return member;
        }).when(members).selectUmsMemberById(1L);
        UmsMemberPointsDetailServiceImpl details = new UmsMemberPointsDetailServiceImpl();
        ReflectionTestUtils.setField(details, "umsMemberPointsDetailMapper", session.getMapper(UmsMemberPointsDetailMapper.class));
        CmsPointsSettingServiceImpl settings = new CmsPointsSettingServiceImpl();
        ReflectionTestUtils.setField(settings, "cmsPointsSettingMapper", session.getMapper(CmsPointsSettingMapper.class));
        UmsCommentLikeServiceImpl commentLikes = new UmsCommentLikeServiceImpl();
        ReflectionTestUtils.setField(commentLikes, "umsCommentLikeMapper", session.getMapper(UmsCommentLikeMapper.class));
        CommentApi commentApi = new CommentApi();
        // 手工 new 不走 @Value 默认值,旧写门禁显式开闸(与 Spring 运行时默认一致)。
        ReflectionTestUtils.setField(commentApi, "legacyCommunityWritesEnabled", true);
        ReflectionTestUtils.setField(commentApi, "umsCommentService", commentService);
        ReflectionTestUtils.setField(commentApi, "umsMemberService", members);
        ReflectionTestUtils.setField(commentApi, "umsCommentLikeService", commentLikes);
        security = mock(IWxContentSecurityService.class);
        ReflectionTestUtils.setField(commentApi, "wxContentSecurityService", security);
        ContentInteractionGuard guard = proxy(new ContentInteractionGuard(session.getMapper(UmsCreativeSquareMapper.class), session.getMapper(UmsCommentMapper.class)));
        ReflectionTestUtils.setField(commentApi, "contentInteractionGuard", guard);
        CommentPublicationService publisher = proxy(new CommentPublicationService(guard, commentService, members, settings, details, session.getMapper(UmsMemberMapper.class), session.getMapper(UmsCommentMapper.class)));
        ReflectionTestUtils.setField(commentApi, "commentPublicationService", publisher);
        comments = proxy(commentApi);
        UmsCreativeSquareLikeServiceImpl squareLikes = new UmsCreativeSquareLikeServiceImpl();
        ReflectionTestUtils.setField(squareLikes, "umsCreativeSquareLikeMapper", session.getMapper(UmsCreativeSquareLikeMapper.class));
        UmsCreativeSquareServiceImpl squareService = new UmsCreativeSquareServiceImpl();
        ReflectionTestUtils.setField(squareService, "umsCreativeSquareMapper", session.getMapper(UmsCreativeSquareMapper.class));
        SquareApi squareApi = new SquareApi();
        ReflectionTestUtils.setField(squareApi, "legacyCommunityWritesEnabled", true);
        ReflectionTestUtils.setField(squareApi, "umsCreativeSquareLikeService", squareLikes);
        ReflectionTestUtils.setField(squareApi, "umsCreativeSquareService", squareService);
        ReflectionTestUtils.setField(squareApi, "contentInteractionGuard", guard);
        squares = proxy(squareApi);
    }

    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    @AfterAll void cleanup() {
        if (dataSource != null) new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                .execute("DROP DATABASE " + mysql.databaseName());
    }

    @BeforeEach void reset() {
        for (String table : new String[]{"ums_comment", "ums_comment_like", "ums_creative_square", "ums_creative_square_like", "ums_member_points_detail", "cms_points_setting", "ums_member", "cms_topic", "cms_activity", "cms_registration"}) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO ums_member VALUES (1,100)");
        jdbc.update("INSERT INTO ums_creative_square(id, member_id, status, del_flag) VALUES(10,1,1,0)");
        // 3-11:主题/活动评价要求对象真实存在;首评积分要求已支付或已核销的报名。默认让玩家 1 参与过主题 10 与活动 10。
        jdbc.update("INSERT INTO cms_topic(id, del_flag) VALUES(10,0)");
        jdbc.update("INSERT INTO cms_activity(id, del_flag) VALUES(10,0)");
        register(1, 10, 2, 0);
        register(2, 10, 2, 0);
        org.mockito.Mockito.reset(security, commentService);
    }

    @Test void missingRewardConfigurationStillPublishesTheCommentSuccessfully() {
        AjaxResult result = add(null);
        assertEquals(200, result.get("code"));
        assertEquals(1, count("ums_comment"));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM ums_comment", Integer.class));
        assertEquals(100L, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
        assertEquals(0, count("ums_member_points_detail"));
    }

@Test void aRealRewardLedgerFailureRollsBackBothPublicationAndPoints() {
        jdbc.update("INSERT INTO cms_points_setting(id,title,value) VALUES(4,'comment',5)");
        jdbc.execute("CREATE TRIGGER fail_comment_reward BEFORE INSERT ON ums_member_points_detail FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced reward failure'");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> review("1", "10"));
            verify(security, never()).submitMediaCheck(anyInt(), anyLong(), nullable(String.class), nullable(String.class));
            assertEquals(0, count("ums_comment"));
            assertEquals(0, count("ums_member_points_detail"));
            assertEquals(100L, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
        } finally { jdbc.execute("DROP TRIGGER fail_comment_reward"); }
        assertEquals(200, review("1", "10").get("code"));
        assertEquals(1, count("ums_comment"));
        assertEquals(1, count("ums_member_points_detail"));
        assertEquals(105L, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
    }

@Test void deletedPostRejectsNewLikesAndCommentsFromAnOldPage() {
        assertEquals(200, squares.ceativeSquareDelete("10").get("code"));
        assertNotEquals(200, squares.creativeSquareLike("10", "1").get("code"));
        assertNotEquals(200, add(null).get("code"));
        assertEquals(0, count("ums_creative_square_like"));
        assertEquals(0, count("ums_comment"));
    }

@Test void deletedCommentRejectsLikesAndRepliesIncludingItsDescendants() {
        jdbc.update("INSERT INTO ums_comment(id,member_id,owner_type,owner_id,contents,status,del_flag) VALUES(20,1,3,10,'parent',1,0)");
        jdbc.update("INSERT INTO ums_comment(id,member_id,owner_type,owner_id,reply_id,contents,status,del_flag) VALUES(21,1,3,10,20,'child',1,0)");
        assertEquals(200, comments.deleteComment("20").get("code"));
        assertNotEquals(200, comments.commentLike("20").get("code"));
        assertNotEquals(200, comments.commentLike("21").get("code"));
        assertNotEquals(200, add("20").get("code"));
        assertNotEquals(200, add("21").get("code"));
        assertEquals(0, count("ums_comment_like"));
        assertEquals(2, count("ums_comment"));
    }

@Test void zeroOrMissingRewardWorksForBothFirstCommentsAndFirstReplies() {
        for (boolean configured : new boolean[]{false, true}) {
            for (boolean reply : new boolean[]{false, true}) {
                jdbc.update("DELETE FROM ums_comment"); jdbc.update("DELETE FROM cms_points_setting");
                if (configured) jdbc.update("INSERT INTO cms_points_setting(id,title,value) VALUES(4,'comment',0)");
                if (reply) jdbc.update("INSERT INTO ums_comment(id,member_id,owner_type,owner_id,status,del_flag) VALUES(20,2,3,10,1,0)");
                assertEquals(200, add(reply ? "20" : null).get("code"));
                assertEquals(reply ? 2 : 1, count("ums_comment"));
                assertEquals(100L, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
                assertEquals(0, count("ums_member_points_detail"));
            }
        }
    }

    @Test void publicContentStillSupportsCommentsRepliesAndLikesWithoutPostRewards() {
        jdbc.update("INSERT INTO cms_points_setting(id,title,value) VALUES(4,'comment',5)");
        AjaxResult first = add(null); assertEquals(200, first.get("code"));
        Long id = ((UmsComment) first.get("data")).getId();
        assertEquals(200, add(id.toString()).get("code"));
        AjaxResult liked = comments.commentLike(id.toString());
        assertEquals(200, liked.get("code"));
        assertEquals(1, count("ums_comment_like"));
        // 3-20:回包带终态,前端照抄,不自己猜是点赞还是取消
        assertEquals(java.util.Collections.singletonMap("isLiked", 1), liked.get("data"));
        AjaxResult unliked = comments.commentLike(id.toString());
        assertEquals(200, unliked.get("code"));
        assertEquals(0, count("ums_comment_like"));
        assertEquals(java.util.Collections.singletonMap("isLiked", 0), unliked.get("data"));
        for (String type : new String[]{"1", "2"}) {
            assertEquals(200, squares.creativeSquareLike("10", type).get("code"));
            assertEquals(1, count("ums_creative_square_like"));
            assertEquals(200, squares.creativeSquareLike("10", type).get("code"));
            assertEquals(0, count("ums_creative_square_like"));
        }
        // 3-11 裁决 A:帖文评论没有「参与」概念,不再发首评积分。
        assertEquals(2, count("ums_comment")); assertEquals(0, count("ums_member_points_detail"));
        assertEquals(100L, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
    }

    @Test void hiddenOrMissingTargetsAndForeignReplyParentsAreRejected() {
        jdbc.update("INSERT INTO ums_comment(id,member_id,owner_type,owner_id,status,del_flag) VALUES(20,2,3,10,1,0)");
        jdbc.update("UPDATE ums_creative_square SET status=2 WHERE id=10");
        assertNotEquals(200, squares.creativeSquareLike("10", "2").get("code"));
        assertNotEquals(200, add(null).get("code"));
        assertNotEquals(200, comments.commentLike("20").get("code"));
        jdbc.update("UPDATE ums_creative_square SET status=1 WHERE id=10");
        jdbc.update("UPDATE ums_comment SET status=2 WHERE id=20");
        assertNotEquals(200, add("20").get("code"));
        assertNotEquals(200, comments.commentLike("20").get("code"));
        jdbc.update("UPDATE ums_comment SET status=1, owner_id=11 WHERE id=20");
        assertNotEquals(200, add("20").get("code"));
        assertNotEquals(200, add("999").get("code"));
        assertNotEquals(200, squares.creativeSquareLike("999", "1").get("code"));
        assertNotEquals(200, comments.commentLike("999").get("code"));
        assertEquals(1, count("ums_comment")); assertEquals(0, count("ums_comment_like"));
        assertEquals(0, count("ums_creative_square_like"));
    }

    @Test void anotherMembersContentCannotBeDeleted() {
        jdbc.update("UPDATE ums_creative_square SET member_id=2 WHERE id=10");
        jdbc.update("INSERT INTO ums_comment(id,member_id,owner_type,owner_id,status,del_flag) VALUES(20,2,3,10,1,0)");
        assertNotEquals(200, squares.ceativeSquareDelete("10").get("code"));
        assertNotEquals(200, comments.deleteComment("20").get("code"));
        assertEquals(0, jdbc.queryForObject("SELECT del_flag FROM ums_creative_square WHERE id=10", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT del_flag FROM ums_comment WHERE id=20", Integer.class));
    }

@Test void aCommittedDeleteWinsAgainstAWaitingPostLikeOrComment() throws Exception {
        assertBlockedByMutation("UPDATE ums_creative_square SET del_flag=2 WHERE id=10",
                () -> squares.creativeSquareLike("10", "1"));
        jdbc.update("UPDATE ums_creative_square SET del_flag=0 WHERE id=10");
        assertBlockedByMutation("UPDATE ums_creative_square SET del_flag=2 WHERE id=10", () -> add(null));
        assertEquals(0, count("ums_creative_square_like"));
        assertEquals(0, count("ums_comment"));
    }

    @Test void aCommittedHideWinsAgainstWaitingCommentLikesAndReplies() throws Exception {
        jdbc.update("INSERT INTO ums_comment(id,member_id,owner_type,owner_id,status,del_flag) VALUES(20,2,3,10,1,0)");
        assertBlockedByMutation("UPDATE ums_comment SET status=2 WHERE id=20", () -> comments.commentLike("20"));
        jdbc.update("UPDATE ums_comment SET status=1 WHERE id=20");
        assertBlockedByMutation("UPDATE ums_comment SET status=2 WHERE id=20", () -> add("20"));
        assertEquals(0, count("ums_comment_like")); assertEquals(1, count("ums_comment"));
    }

    private void assertBlockedByMutation(String mutation, Callable<AjaxResult> action) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate(mutation);
            CountDownLatch entered = new CountDownLatch(1);
            Future<AjaxResult> request = worker.submit(() -> { entered.countDown(); return action.call(); });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> request.get(200, TimeUnit.MILLISECONDS));
            } finally { connection.commit(); }
            assertNotEquals(200, request.get(5, TimeUnit.SECONDS).get("code"));
        } finally { worker.shutdownNow(); }
    }

    @Test void concurrentFirstCommentsAwardOnceAndConcurrentLikesRemainAToggle() throws Exception {
        jdbc.update("INSERT INTO cms_points_setting(id,title,value) VALUES(4,'comment',5)");
        concurrently(() -> add(null));
        assertEquals(2, count("ums_comment")); assertEquals(0, count("ums_member_points_detail"));
        assertEquals(100L, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
        concurrently(() -> squares.creativeSquareLike("10", "1"));
        assertEquals(0, count("ums_creative_square_like"));
        String id = jdbc.queryForObject("SELECT MIN(id) FROM ums_comment", Long.class).toString();
        concurrently(() -> comments.commentLike(id));
        assertEquals(0, count("ums_comment_like"));
    }

    private void concurrently(Callable<AjaxResult> action) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<AjaxResult> attempt = () -> {
            ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return action.call();
        };
        try {
            Future<AjaxResult> first = workers.submit(attempt);
            Future<AjaxResult> second = workers.submit(attempt);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            assertEquals(200, first.get(10, TimeUnit.SECONDS).get("code"));
            assertEquals(200, second.get(10, TimeUnit.SECONDS).get("code"));
        } finally { workers.shutdownNow(); }
    }

@Test void mediaIsSubmittedOnlyAfterPublicationIsVisibleToAnotherConnection() throws Exception {
        doAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 java.sql.ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM ums_comment WHERE status=1")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
            return null;
        }).when(security).submitMediaCheck(anyInt(), anyLong(), nullable(String.class), nullable(String.class));
        assertEquals(200, add(null).get("code"));
        verify(security).submitMediaCheck(anyInt(), anyLong(), nullable(String.class), nullable(String.class));
    }

    @Test void rejectedTextNeverPublishesOrAwardsPoints() {
        when(security.checkText(eq("hello"), nullable(String.class))).thenReturn("内容不合规");
        assertNotEquals(200, add(null).get("code"));
        assertEquals(0, count("ums_comment")); assertEquals(0, count("ums_member_points_detail"));
        verify(security, never()).submitMediaCheck(anyInt(), anyLong(), nullable(String.class), nullable(String.class));
    }

@org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"1", "2"})
    void concurrentFirstCommentsRewardOnceForTopicsAndActivitiesToo(String type) throws Exception {
        jdbc.update("INSERT INTO cms_points_setting(id,title,value) VALUES(4,'comment',5)");
        CountDownLatch readers = new CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger firstSnapshots = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.List<UmsComment> rows = (java.util.List<UmsComment>) invocation.callRealMethod();
            if (rows.size() == 1) firstSnapshots.incrementAndGet();
            // 旧实现允许两事务各看到自身首评后一起发奖；加互斥后第二请求在此之前等待。
            readers.countDown(); readers.await(2, TimeUnit.SECONDS);
            return rows;
        }).when(commentService).selectUmsCommentList(any(UmsComment.class));
        concurrently(() -> comments.addComment(type, "10", "0", "hello", null, null));
        assertEquals(2, count("ums_comment"));
        assertEquals(1, count("ums_member_points_detail"), "ownerType=" + type + ", firstSnapshots=" + firstSnapshots.get());
        assertEquals(105L, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
        assertEquals(1, firstSnapshots.get());
    }

@Test void firstCommentsOnDifferentOwnerTypesStillEachEarnTheirOwnReward() throws Exception {
        jdbc.update("INSERT INTO cms_points_setting(id,title,value) VALUES(4,'comment',5)");
        java.util.concurrent.atomic.AtomicInteger owner = new java.util.concurrent.atomic.AtomicInteger();
        concurrently(() -> comments.addComment(Integer.toString(owner.incrementAndGet()), "10", "0", "hello", null, null));
        assertEquals(2, count("ums_comment")); assertEquals(2, count("ums_member_points_detail"));
        assertEquals(110L, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
        assertEquals(java.util.Arrays.asList(100L, 105L), jdbc.queryForList("SELECT current_points FROM ums_member_points_detail ORDER BY id", Long.class));
        assertEquals(java.util.Arrays.asList(105L, 110L), jdbc.queryForList("SELECT after_points FROM ums_member_points_detail ORDER BY id", Long.class));
    }

    @Test void reviewsOfMissingOrDeletedTopicsAndActivitiesAreRejectedWithoutPoints() {
        jdbc.update("INSERT INTO cms_points_setting(id,title,value) VALUES(4,'comment',5)");
        register(1, 999, 2, 1);
        register(2, 999, 2, 1);
        assertNotEquals(200, review("1", "999").get("code"));
        assertNotEquals(200, review("2", "999").get("code"));
        jdbc.update("UPDATE cms_topic SET del_flag=2 WHERE id=10");
        jdbc.update("UPDATE cms_activity SET del_flag=2 WHERE id=10");
        assertNotEquals(200, review("1", "10").get("code"));
        assertNotEquals(200, review("2", "10").get("code"));
        assertEquals(0, count("ums_comment")); assertEquals(0, count("ums_member_points_detail"));
        assertEquals(100L, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
    }

    @Test void nonParticipantsMayStillReviewButEarnNoFirstReviewPoints() {
        jdbc.update("INSERT INTO cms_points_setting(id,title,value) VALUES(4,'comment',5)");
        jdbc.update("DELETE FROM cms_registration");
        register(1, 10, 0, 0); // 待支付不算参与
        register(2, 10, 4, 0); // 已退款未核销不算参与
        assertEquals(200, review("1", "10").get("code"));
        assertEquals(200, review("2", "10").get("code"));
        assertEquals(2, count("ums_comment")); assertEquals(0, count("ums_member_points_detail"));
        assertEquals(100L, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,-1", "1,6", "2,-1", "2,6"})
    void outOfRangeReviewRatingsNeverPublishOrAwardPoints(String type, String rating) {
        jdbc.update("INSERT INTO cms_points_setting(id,title,value) VALUES(4,'comment',5)");
        assertNotEquals(200, comments.addComment(type, "10", rating, "hello", null, null).get("code"));
        assertEquals(0, count("ums_comment"));
        assertEquals(0, count("ums_member_points_detail"));
        assertEquals(100L, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
        verify(security, never()).submitMediaCheck(anyInt(), anyLong(), nullable(String.class), nullable(String.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,0", "1,5", "2,0", "2,5"})
    void reviewRatingBoundariesStillPublishAndEarnTheFirstReviewReward(String type, String rating) {
        jdbc.update("INSERT INTO cms_points_setting(id,title,value) VALUES(4,'comment',5)");
        assertEquals(200, comments.addComment(type, "10", rating, "hello", null, null).get("code"));
        assertEquals(Long.valueOf(rating), jdbc.queryForObject("SELECT rating FROM ums_comment", Long.class));
        assertEquals(1, count("ums_member_points_detail"));
        assertEquals(105L, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
    }

    @Test void anotherMembersPaidTicketCannotEarnMeFirstReviewPoints() {
        jdbc.update("INSERT INTO cms_points_setting(id,title,value) VALUES(4,'comment',5)");
        jdbc.update("DELETE FROM cms_registration");
        // 会员 2 买了主题 10 的票,会员 1 没报名:参与判定必须按评价人自己的报名,不能拿别人的票换分。
        registerFor(2, 1, 10, 2, 0);
        assertEquals(200, review("1", "10").get("code"));
        assertEquals(1, count("ums_comment"));
        assertEquals(0, count("ums_member_points_detail"), "别人的已支付票不能让评价人拿首评积分");
        assertEquals(100L, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
    }

    @Test void topicReviewCountsAVerifiedTicketOfAnActivityUnderThatTopic() {
        jdbc.update("INSERT INTO cms_points_setting(id,title,value) VALUES(4,'comment',5)");
        jdbc.update("DELETE FROM cms_registration");
        jdbc.update("INSERT INTO cms_activity(id, del_flag, topic_id) VALUES(11,0,10)");
        register(2, 11, 4, 1); // 核销后退款:已核销即参与过
        assertEquals(200, review("1", "10").get("code"));
        assertEquals(1, count("ums_member_points_detail"));
        assertEquals(105L, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class));
    }

    private void register(int ownerType, long ownerId, int paymentStatus, int verificationStatus) {
        registerFor(1, ownerType, ownerId, paymentStatus, verificationStatus);
    }

    private void registerFor(int memberId, int ownerType, long ownerId, int paymentStatus, int verificationStatus) {
        jdbc.update("INSERT INTO cms_registration(owner_type, owner_id, member_id, payment_status, verification_status) VALUES (?,?,?,?,?)",
                ownerType, ownerId, memberId, paymentStatus, verificationStatus);
    }
    private AjaxResult review(String ownerType, String ownerId) { return comments.addComment(ownerType, ownerId, "5", "hello", null, null); }
    private AjaxResult add(String replyId) { return comments.addComment("3", "10", "0", "hello", replyId, null); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
}
