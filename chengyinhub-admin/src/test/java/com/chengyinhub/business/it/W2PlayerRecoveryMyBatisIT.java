package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.web.controller.api.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runs only in the isolated development/test MySQL supplied by the coordinator. */
class W2PlayerRecoveryMyBatisIT {
    private static JdbcTemplate jdbc;
    private static SqlSessionTemplate sql;
    private static TransactionTemplate tx;

    @BeforeAll
    static void setup() throws Exception {
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_HOST"), "Explicit test database configuration is required");
        com.chengyinhub.business.service.MysqlIntegrationTestConfig target =
                com.chengyinhub.business.service.MysqlIntegrationTestConfig.fromEnvironment("w2_player_recovery");
        try (Connection connection = java.sql.DriverManager.getConnection(target.adminUrl(), target.user(), target.password());
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + target.databaseName() + "`");
            statement.execute("CREATE DATABASE `" + target.databaseName() + "` CHARACTER SET utf8mb4");
        }
        DriverManagerDataSource ds = new DriverManagerDataSource(target.databaseUrl(), target.user(), target.password());
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("create table ums_member(id bigint primary key, nickname varchar(100), avatar varchar(255))");
        jdbc.execute("insert into ums_member(id) values(1),(2)");
        String base = new String(Files.readAllBytes(Paths.get("../sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
        for (String table : new String[]{"cms_topic_like", "cms_activity_like", "club_post_like", "npc_chat_request"}) {
            int start = base.indexOf("CREATE TABLE `" + table + "`");
            int engine = base.indexOf("\n) ENGINE=", start);
            assertTrue(start >= 0 && engine > start);
            jdbc.execute(base.substring(start, base.indexOf(';', engine)));
        }
        jdbc.execute("create table club_rows(id bigint primary key)");
        jdbc.execute("insert into club_rows values(7)");
        jdbc.execute("create table club_post(id bigint primary key, like_count int not null)");
        jdbc.execute("insert into club_post values(9,0)");
        try (Connection connection = ds.getConnection()) {
            ItSchema.applyMigrations(connection, "sql/migration_merchant_review_20260823.sql",
                    "../sql/migration_npc_chat_request_hash_20261013.sql", "../sql/migration_ai_theme_draft_request_20261013.sql");
        }
        Configuration config = new Configuration(new Environment("test", new SpringManagedTransactionFactory(), ds));
        for (Class<?> type : new Class<?>[]{UmsMember.class, CmsTopicLike.class, CmsActivityLike.class, ClubPostLike.class})
            config.getTypeAliasRegistry().registerAlias(type.getSimpleName(), type);
        for (String name : new String[]{"UmsMember", "CmsTopicLike", "CmsActivityLike", "ClubPostLike", "NpcChatRequest", "AiThemeDraftRequest", "MerchantReview"}) {
            String resource = "mapper/business/" + name + "Mapper.xml";
            try (InputStream input = W2PlayerRecoveryMyBatisIT.class.getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
    }

    @Test
    void concurrentExplicitFavoritesAreIdempotentAndOtherMemberIsUntouched() throws Exception {
        CmsTopicLikeServiceImpl topicService = new CmsTopicLikeServiceImpl();
        ReflectionTestUtils.setField(topicService, "cmsTopicLikeMapper", sql.getMapper(CmsTopicLikeMapper.class));
        ApiTopicController topic = new ApiTopicController() { @Override public Long getAppUserId() { return 1L; } };
        ReflectionTestUtils.setField(topic, "cmsTopicLikeService", topicService);
        ReflectionTestUtils.setField(topic, "favoriteMemberMapper", sql.getMapper(UmsMemberMapper.class));
        CmsActivityLikeServiceImpl activityService = new CmsActivityLikeServiceImpl();
        ReflectionTestUtils.setField(activityService, "cmsActivityLikeMapper", sql.getMapper(CmsActivityLikeMapper.class));
        ApiActivityController activity = new ApiActivityController() { @Override public Long getAppUserId() { return 1L; } };
        ReflectionTestUtils.setField(activity, "cmsActivityLikeService", activityService);
        ReflectionTestUtils.setField(activity, "favoriteMemberMapper", sql.getMapper(UmsMemberMapper.class));
        for (String table : new String[]{"cms_topic_like", "cms_activity_like"})
            jdbc.update("insert into " + table + "(member_id,data_id,like_type) values(2,11,1)");
        parallel(() -> topic.topicLike("11", "1", "1"));
        parallel(() -> activity.activityLike("11", "1", "1"));
        for (String table : new String[]{"cms_topic_like", "cms_activity_like"})
            assertEquals(1, jdbc.queryForObject("select count(*) from " + table + " where member_id=1 and data_id=11", Integer.class));
        parallel(() -> topic.topicLike("11", "0", "0"));
        parallel(() -> activity.activityLike("11", "0", "0"));
        for (String table : new String[]{"cms_topic_like", "cms_activity_like"}) {
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table + " where member_id=1", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from " + table + " where member_id=2", Integer.class));
        }
        assertEquals(false, ((java.util.Map<?, ?>)topic.topicLikeState("11").get("data")).get("liked"));
        assertEquals(400, topic.topicLike("11", "1", "bad").get("code"));
        assertEquals(400, activity.activityLike("11", "1", "bad").get("code"));
        parallel(() -> topic.topicLike("11", "1", "1"), () -> topic.topicLike("11", "1"));
        parallel(() -> activity.activityLike("11", "1", "1"), () -> activity.activityLike("11", "1"));
        for (String table : new String[]{"cms_topic_like", "cms_activity_like"}) {
            assertTrue(jdbc.queryForObject("select count(*) from " + table + " where member_id=1", Integer.class) <= 1);
            assertEquals(1, jdbc.queryForObject("select count(*) from " + table + " where member_id=2", Integer.class));
        }
        tx.execute(status -> topic.topicLike("12", "1"));
        tx.execute(status -> topic.topicLike("12", "1"));
        assertEquals(0, jdbc.queryForObject("select count(*) from cms_topic_like where member_id=1 and data_id=12", Integer.class),
                "Old clients retain toggle behavior");
    }

    @Test
    void clubTargetUsesCurrentLockReadAndDoesNotDoubleAdjustCounter() throws Exception {
        IClubService clubs = mock(IClubService.class);
        Club club = new Club(); club.setId(7L);
        when(clubs.selectClubById(7L)).thenReturn(club);
        when(clubs.selectClubByIdForUpdate(7L)).thenAnswer(c -> {
            jdbc.queryForObject("select id from club_rows where id=7 for update", Long.class); return club;
        });
        IClubPostService posts = mock(IClubPostService.class);
        ClubPost post = new ClubPost(); post.setId(9L); post.setClubId(7L);
        when(posts.selectClubPostById(9L)).thenReturn(post);
        when(posts.incrementLikeCount(eq(9L), anyInt())).thenAnswer(c -> jdbc.update("update club_post set like_count=like_count+? where id=9", c.getArgument(1, Integer.class)));
        ClubPostLikeServiceImpl likes = new ClubPostLikeServiceImpl();
        ReflectionTestUtils.setField(likes, "clubPostLikeMapper", sql.getMapper(ClubPostLikeMapper.class));
        ApiClubController controller = new ApiClubController() { @Override public Long getAppUserId() { return 1L; } };
        ReflectionTestUtils.setField(controller, "clubService", clubs);
        ReflectionTestUtils.setField(controller, "clubPostService", posts);
        ReflectionTestUtils.setField(controller, "clubPostLikeService", likes);
        ClubPostLike input = new ClubPostLike(); input.setPostId(9L); input.setTarget(1);
        parallel(() -> controller.postLike(input));
        assertEquals(1, jdbc.queryForObject("select like_count from club_post where id=9", Integer.class));
        input.setTarget(0);
        parallel(() -> controller.postLike(input));
        assertEquals(0, jdbc.queryForObject("select like_count from club_post where id=9", Integer.class));
        assertEquals(false, ((java.util.Map<?, ?>)controller.postLikeState(input).get("data")).get("liked"));
        input.setTarget(null);
        tx.execute(status -> controller.postLike(input));
        assertEquals(1, jdbc.queryForObject("select like_count from club_post where id=9", Integer.class));
        tx.execute(status -> controller.postLike(input));
        assertEquals(0, jdbc.queryForObject("select like_count from club_post where id=9", Integer.class));
    }

    @Test
    void fingerprintsAndDraftResultArePersistedAndIsolatedByUser() {
        NpcChatRequestMapper npc = sql.getMapper(NpcChatRequestMapper.class);
        NpcChatRequest request = new NpcChatRequest(); request.setUserId(1L); request.setRequestId("npc-original");
        request.setSessionId(-11L); request.setStatus("SUCCEEDED"); request.setSafetyDecision("PASS"); request.setRetryable(false); request.setAi(true); request.setRequestHash(String.join("", java.util.Collections.nCopies(64, "a")));
        assertEquals(1, npc.insertNpcChatRequest(request));
        assertEquals(request.getRequestHash(), npc.selectByUserAndRequestId(1L, request.getRequestId()).getRequestHash());
        assertNull(npc.selectByUserAndRequestId(2L, request.getRequestId()));
        AiThemeDraftRequestMapper drafts = sql.getMapper(AiThemeDraftRequestMapper.class);
        AiThemeDraftRequest draft = new AiThemeDraftRequest(); draft.setUserId(1L); draft.setRequestId("draft-original");
        draft.setRequestHash(String.join("", java.util.Collections.nCopies(64, "b"))); assertEquals(1, drafts.insertRequest(draft));
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> drafts.insertRequest(draft));
        draft.setStatus("SUCCEEDED"); draft.setResultJson("{\"traceId\":\"original-trace\"}");
        assertEquals(1, drafts.finishRequest(draft));
        assertEquals(0, drafts.finishRequest(draft));
        assertEquals(draft.getResultJson(), drafts.selectByUserAndRequestId(1L, draft.getRequestId()).getResultJson());
        assertNull(drafts.selectByUserAndRequestId(2L, draft.getRequestId()));
    }

    @Test
    void photoLowAndPendingFiltersRunBeforePaginationAndPublicNeverIncludesHidden() {
        for (int id=1; id<=25; id++) jdbc.update("insert into merchant_review(merchant_row_id,merchant_owner_member_id,registration_id,redemption_id,author_member_id,rating,content,images_json,status,create_request_id,create_fingerprint,create_time,update_time) values(7,2,?, ?,1,?,'意见',?,?,?,repeat('a',64),now(),now())",
                id, id, id == 25 ? 2 : 5, id == 25 ? "[\"image\"]" : "[]", id == 24 ? "HIDDEN" : "VISIBLE", "review-"+id);
        MerchantReviewMapper reviews = sql.getMapper(MerchantReviewMapper.class);
        for (String filter : new String[]{"photo", "low"}) {
            assertEquals(1L, reviews.countFilteredReviews(7L, false, filter));
            assertEquals(2, reviews.selectFilteredReviews(7L, false, filter, 0, 1).get(0).getRating());
            assertTrue(reviews.selectFilteredReviews(7L, false, filter, 1, 1).isEmpty());
        }
        assertEquals(24L, reviews.countFilteredReviews(7L, true, "pending"));
        assertEquals(0L, reviews.countFilteredReviews(8L, true, "photo"));
    }

    private static void parallel(Supplier<AjaxResult> action) throws Exception {
        parallel(action, action);
    }

    private static void parallel(Supplier<AjaxResult> first, Supplier<AjaxResult> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<AjaxResult> aCall = () -> { start.await(); return tx.execute(status -> first.get()); };
        Callable<AjaxResult> bCall = () -> { start.await(); return tx.execute(status -> second.get()); };
        try {
            Future<AjaxResult> a = executor.submit(aCall), b = executor.submit(bCall);
            start.countDown();
            assertEquals(200, a.get(10, TimeUnit.SECONDS).get("code"));
            assertEquals(200, b.get(10, TimeUnit.SECONDS).get("code"));
        } finally { executor.shutdownNow(); }
    }
}
