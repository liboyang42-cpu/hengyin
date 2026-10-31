package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ClubGovernanceBan;
import com.chengyinhub.business.domain.ClubPostLike;
import com.chengyinhub.business.it.ItSchema;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.ClubGovernanceMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubPostLikeMapper;
import com.chengyinhub.business.mapper.ClubPostMapper;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.ClubMemberServiceImpl;
import com.chengyinhub.business.service.impl.ClubParticipationGuardImpl;
import com.chengyinhub.business.service.impl.ClubPostLikeServiceImpl;
import com.chengyinhub.business.service.impl.ClubPostServiceImpl;
import com.chengyinhub.business.service.impl.ClubServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** 真 MySQL/XML/Service/事务代理；仅调用身份固定，latch 只控制 club 锁的竞争顺序。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClubPostLikeAuthorizationMySqlIT {
    private static final String SUITE = "club_post_like_auth";
    private static final Long CLUB = 73L, VIEWER = 42L, POST = 81L;
    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private String database;
    private DataSourceTransactionManager transactions;
    private ClubMapper clubMapper;
    private ClubMemberMapper memberMapper;
    private ClubGovernanceMapper governanceMapper;
    private ClubPostMapper postMapper;
    private ClubPostLikeMapper likeMapper;

    @BeforeAll
    void openDatabase() throws Exception {
        source = new DriverManagerDataSource(MybatisItDatabase.url(SUITE),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        database = MybatisItDatabase.databaseName(SUITE);
        db = new JdbcTemplate(source);
        assertEquals(database, db.queryForObject("select database()", String.class));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(db.queryForObject("select version()", String.class)));
        String base = read("../sql/base_schema_20260818.sql");
        for (String table : List.of("club", "club_member", "club_post", "club_post_like")) {
            String head = "CREATE TABLE `" + table + "` (";
            int start = base.indexOf(head);
            assertTrue(start >= 0, "真实基线缺少 " + table);
            int tail = base.indexOf("\n) ENGINE=", start);
            db.execute(base.substring(start, base.indexOf(";\n", tail)));
        }
        try (Connection connection = source.getConnection()) {
            ItSchema.applyMigrations(connection,
                    "sql/migration_club_open_settings_20260909.sql",
                    "sql/migration_20261012_club_join_review.sql",
                    "sql/migration_club_post_announcement_20260823.sql",
                    "sql/migration_club_post_reference_20260910.sql",
                    "sql/migration_club_role_governance_20260823.sql",
                    "sql/migration_20261029_club_post_repost.sql");
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ClubMapper.xml"),
                new ClassPathResource("mapper/business/ClubMemberMapper.xml"),
                new ClassPathResource("mapper/business/ClubGovernanceMapper.xml"),
                new ClassPathResource("mapper/business/ClubPostMapper.xml"),
                new ClassPathResource("mapper/business/ClubPostLikeMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        clubMapper = sql.getMapper(ClubMapper.class);
        memberMapper = sql.getMapper(ClubMemberMapper.class);
        governanceMapper = sql.getMapper(ClubGovernanceMapper.class);
        postMapper = sql.getMapper(ClubPostMapper.class);
        likeMapper = sql.getMapper(ClubPostLikeMapper.class);
        transactions = new DataSourceTransactionManager(source);
        transactions.setDefaultTimeout(10);
    }

    @AfterAll
    void dropDatabase() throws Exception {
        if (database == null) return;
        try (Connection admin = DriverManager.getConnection("jdbc:mysql://" + MybatisItDatabase.host()
                + ":" + MybatisItDatabase.port() + "/?useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password()); Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            try (var left = statement.executeQuery("select count(*) from information_schema.schemata"
                    + " where schema_name='" + database + "'")) {
                assertTrue(left.next());
                assertEquals(0, left.getInt(1));
            }
        }
    }

    @Test
    void repeatableReadNegativeControlReallyWritesAfterPrivateMemberRemoval() throws Exception {
        AjaxResult response = race(true, null, true);
        assertEquals(200, response.get(AjaxResult.CODE_TAG), "RR 负控必须确实重现旧快照授权");
        assertEquals(true, ((Map<?, ?>) response.get(AjaxResult.DATA_TAG)).get("liked"));
        assertLikeState(1);
    }

    @Test
    void controllerReadCommittedRefusesPrivateMemberRemovedWhileWaitingForClubInEveryMode() throws Exception {
        for (Boolean target : new Boolean[] {true, false, null}) {
            AjaxResult response = race(false, null, target);
            assertEquals(500, response.get(AjaxResult.CODE_TAG));
            assertEquals("这个俱乐部只对成员开放", response.get(AjaxResult.MSG_TAG));
            assertLikeState(Boolean.TRUE.equals(target) ? 0 : 1);
        }
    }

    @Test
    void controllerReadCommittedRefusesNewClubAndPlatformBansWithoutUnexpectedRollbackOrLikeEffects() throws Exception {
        for (String source : new String[] {ClubGovernanceBan.SOURCE_CLUB, ClubGovernanceBan.SOURCE_PLATFORM}) {
            for (Boolean target : new Boolean[] {true, false, null}) {
                AjaxResult response = race(false, source, target);
                assertEquals(403, response.get(AjaxResult.CODE_TAG));
                assertEquals("你当前无法参与该俱乐部", response.get(AjaxResult.MSG_TAG));
                assertLikeState(Boolean.TRUE.equals(target) ? 0 : 1);
                assertEquals(1, db.queryForObject("select count(*) from club_governance_ban"
                        + " where club_id=? and target_member_id=? and status='ACTIVE'", Integer.class, CLUB, VIEWER));
            }
        }
    }

    private AjaxResult race(boolean repeatableReadControl, String banSource, Boolean target) throws Exception {
        boolean initiallyLiked = !Boolean.TRUE.equals(target);
        seed(banSource != null, initiallyLiked);
        CountDownLatch clubHeld = new CountDownLatch(1), releaseChange = new CountDownLatch(1);
        CountDownLatch firstRead = new CountDownLatch(1), lockAttempted = new CountDownLatch(1);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        AtomicInteger actualIsolation = new AtomicInteger();

        ClubServiceImpl clubs = spy(new ClubServiceImpl());
        ReflectionTestUtils.setField(clubs, "clubMapper", clubMapper);
        doAnswer(call -> {
            assertEquals(0L, firstRead.getCount(), "先普通读帖子，再等 club 锁");
            lockAttempted.countDown();
            Object result = call.callRealMethod();
            lockAcquired.countDown();
            return result;
        }).when(clubs).selectClubByIdForUpdate(CLUB);
        ClubPostServiceImpl posts = spy(new ClubPostServiceImpl());
        ReflectionTestUtils.setField(posts, "clubPostMapper", postMapper);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            actualIsolation.set(DataSourceUtils.getConnection(source).getTransactionIsolation());
            firstRead.countDown();
            return result;
        }).when(posts).selectClubPostById(POST);
        ClubPostLikeServiceImpl likes = spy(new ClubPostLikeServiceImpl());
        ReflectionTestUtils.setField(likes, "clubPostLikeMapper", likeMapper);
        ClubMemberServiceImpl members = new ClubMemberServiceImpl();
        ReflectionTestUtils.setField(members, "clubMemberMapper", memberMapper);
        ClubParticipationGuardImpl guard = new ClubParticipationGuardImpl();
        ReflectionTestUtils.setField(guard, "clubGovernanceMapper", governanceMapper);
        ApiClubController controller = new ApiClubController() {
            @Override public Long getAppUserId() { return VIEWER; }
        };
        ReflectionTestUtils.setField(controller, "clubService", clubs);
        ReflectionTestUtils.setField(controller, "clubPostService", posts);
        ReflectionTestUtils.setField(controller, "clubPostLikeService", likes);
        ReflectionTestUtils.setField(controller, "clubMemberService", members);
        ReflectionTestUtils.setField(controller, "clubParticipationGuard", proxy(guard, IClubParticipationGuard.class));
        ApiClubController endpoint = proxy(controller, ApiClubController.class);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> change = workers.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertNotNull(clubMapper.selectClubByIdForUpdate(CLUB));
                clubHeld.countDown();
                await(releaseChange);
                // 与 removeMemberForBan / quitMemberAndLeaveGroup 相同的 club 锁与真实 mapper 写序。
                if (banSource != null) assertEquals(1, governanceMapper.insertClubGovernanceBan(ban(banSource)));
                assertEquals(1, memberMapper.deleteByClubAndMember(CLUB, VIEWER));
                assertEquals(1, clubMapper.updateMemberCount(CLUB, -1));
            }));
            assertTrue(clubHeld.await(5, TimeUnit.SECONDS), "变更事务没有拿到 club 锁");
            Future<AjaxResult> like = workers.submit(() -> {
                if (!repeatableReadControl) return request(endpoint, target);
                // 仅负控强制外层 RR；正常测试必须让 controller 自身的事务注解真正生效。
                TransactionTemplate rr = new TransactionTemplate(transactions);
                rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                return rr.execute(status -> request(endpoint, target));
            });
            assertTrue(lockAttempted.await(5, TimeUnit.SECONDS), "点赞请求没有到达 club 锁");
            assertFalse(lockAcquired.await(200, TimeUnit.MILLISECONDS), "变更提交前点赞不可越过 club 锁");
            assertFalse(like.isDone());
            releaseChange.countDown();
            change.get(10, TimeUnit.SECONDS);
            AjaxResult response = like.get(10, TimeUnit.SECONDS);
            assertEquals(repeatableReadControl ? Connection.TRANSACTION_REPEATABLE_READ
                    : Connection.TRANSACTION_READ_COMMITTED, actualIsolation.get());
            assertEquals(0, db.queryForObject("select count(*) from club_member where club_id=? and member_id=?",
                    Integer.class, CLUB, VIEWER), "拒绝必须发生在已提交的真实成员移除之后");
            if (!repeatableReadControl) {
                verifyNoInteractions(likes);
                verify(posts, never()).incrementLikeCount(any(), anyInt());
                assertFalse(response.containsKey(AjaxResult.DATA_TAG));
            }
            return response;
        } finally {
            releaseChange.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "测试事务线程未结束，不可清理 schema");
        }
    }

    private AjaxResult request(ApiClubController endpoint, Boolean target) {
        ClubPostLike body = new ClubPostLike();
        body.setPostId(POST);
        body.setLiked(target);
        try {
            return endpoint.postLike(body);
        } catch (ServiceException refused) {
            // 必须在 controller 事务代理回滚之后映射；UnexpectedRollbackException 不会被这里吞掉。
            return new GlobalExceptionHandler().handleServiceException(refused, new MockHttpServletRequest());
        }
    }

    private void seed(boolean publicClub, boolean liked) {
        for (String table : List.of("club_governance_ban", "club_post_like", "club_post", "club_member", "club")) {
            db.update("delete from " + table);
        }
        db.update("insert into club(id,member_id,name,status,member_count,public_visible,del_flag)"
                + " values(?,9,'点赞授权并发',1,1,?,0)", CLUB, publicClub ? 1 : 0);
        db.update("insert into club_member(club_id,member_id,role,status,del_flag) values(?,?,0,1,0)", CLUB, VIEWER);
        db.update("insert into club_post(id,club_id,author_member_id,type,like_count,del_flag) values(?,?,9,0,?,0)",
                POST, CLUB, liked ? 1 : 0);
        if (liked) db.update("insert into club_post_like(post_id,member_id) values(?,?)", POST, VIEWER);
    }

    private void assertLikeState(int expected) {
        assertEquals(expected, db.queryForObject("select count(*) from club_post_like where post_id=? and member_id=?",
                Integer.class, POST, VIEWER));
        assertEquals(expected, db.queryForObject("select like_count from club_post where id=?", Integer.class, POST));
    }

    private ClubGovernanceBan ban(String banSource) {
        Date now = new Date();
        ClubGovernanceBan ban = new ClubGovernanceBan();
        ban.setClubId(CLUB);
        ban.setTargetMemberId(VIEWER);
        ban.setStatus(ClubGovernanceBan.STATUS_ACTIVE);
        ban.setSourceType(banSource);
        ban.setBanReason("并发参与禁用");
        ban.setBannedByMemberId(9L);
        ban.setBannedAt(now);
        ban.setExpiresAt(ClubGovernanceBan.SOURCE_CLUB.equals(banSource)
                ? new Date(now.getTime() + 86_400_000L) : null);
        ban.setBanRequestId("like-race-ban");
        ban.setVersion(0);
        ban.setCreateTime(now);
        ban.setUpdateTime(now);
        return ban;
    }

    private <T> T proxy(Object target, Class<T> type) {
        ProxyFactory factory = new ProxyFactory(target);
        if (type.isInterface()) factory.setInterfaces(type);
        else factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "并发屏障超时");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
