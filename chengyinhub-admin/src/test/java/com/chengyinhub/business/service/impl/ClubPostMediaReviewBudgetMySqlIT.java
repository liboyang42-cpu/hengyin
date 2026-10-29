package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostMediaReviewOutbox;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.ClubPostMapper;
import com.chengyinhub.business.mapper.ClubPostMediaReviewOutboxMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hosted/approved MySQL only. Real outbox/inbox XML, schema CHECKs and Spring transactions;
 * provider and target reads are explicit doubles, not HTTP/WeChat or a full publication fixture. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class ClubPostMediaReviewBudgetMySqlIT {
    private static final long POST = 81L;
    private static final long MEMBER = 42L;
    private static final String MEDIA = "https://cdn.example.test/club/original.jpg";
    private static final String HASH = WxContentCallbackInboxServiceImpl.sha256Hex(MEDIA);
    private static MysqlIntegrationTestConfig mysql;
    private static boolean created;
    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private DataSourceTransactionManager manager;
    private ClubPostMediaReviewOutboxMapper mapper;
    private ClubPostMediaReviewTransactionService transactions;
    private WxContentCallbackInboxMapper callbackMapper;
    private IWxContentCallbackInboxService callbacks;
    private IWxContentSecurityService checker;
    private ClubPostMediaReviewServiceImpl service;

    @BeforeAll
    static void createScratch() throws Exception {
        Map<String,String> env = new HashMap<>(System.getenv());
        for (String key : new String[]{"CHENGYIN_TEST_MYSQL_HOST", "CHENGYIN_TEST_MYSQL_PORT",
                "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"}) {
            if (!env.containsKey(key)) throw new IllegalStateException("Missing explicit IT setting: " + key);
        }
        if (!"127.0.0.1".equals(env.get("CHENGYIN_TEST_MYSQL_HOST"))
                && !"localhost".equals(env.get("CHENGYIN_TEST_MYSQL_HOST"))) {
            throw new IllegalStateException("Media budget scratch requires an approved loopback MySQL");
        }
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE", "arch_h");
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX", "_" + UUID.randomUUID().toString().replace("-", ""));
        mysql = MysqlIntegrationTestConfig.from(env, "media_budget");
        try (Connection c = DriverManager.getConnection(mysql.adminUrl(), mysql.user(), mysql.password());
             Statement st = c.createStatement()) {
            assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(c.getMetaData().getDatabaseProductVersion()));
            // No IF NOT EXISTS / initial DROP: refuse an occupied identity.
            st.execute("CREATE DATABASE " + mysql.databaseName() + " CHARACTER SET utf8mb4");
            created = true;
        }
    }

    @AfterAll
    static void dropOwnScratch() throws Exception {
        if (!created) return;
        try (Connection c = DriverManager.getConnection(mysql.adminUrl(), mysql.user(), mysql.password());
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE " + mysql.databaseName());
            try (ResultSet rows = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"
                    + mysql.databaseName() + "'")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        source = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        db = new JdbcTemplate(source);
        db.execute("DROP TABLE IF EXISTS club_post_media_review_outbox");
        db.execute("DROP TABLE IF EXISTS wx_content_callback_inbox");
        db.execute("DROP TABLE IF EXISTS club_post");
        executeMigration("migration_club_role_media_review_outbox_20260824.sql");
        executeMigration("migration_club_post_media_review_budget_20261024.sql");
        executeMigration("migration_wx_content_callback_inbox_20260724.sql");
        // Read-target fixture only: no invented HOLD field; retain original del_flag/version/images.
        db.execute("CREATE TABLE club_post(id BIGINT PRIMARY KEY,version INT NOT NULL,del_flag INT NOT NULL,images VARCHAR(500)) ENGINE=InnoDB");
        db.update("INSERT INTO club_post VALUES (?,3,0,?)", POST, MEDIA);
        manager = new DataSourceTransactionManager(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/ClubPostMediaReviewOutboxMapper.xml"),
                new ClassPathResource("mapper/business/WxContentCallbackInboxMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        mapper = sql.getMapper(ClubPostMediaReviewOutboxMapper.class);
        callbackMapper = sql.getMapper(WxContentCallbackInboxMapper.class);
        transactions = proxy(new ClubPostMediaReviewTransactionService(mapper));
        WxContentCallbackClaimService claim = new WxContentCallbackClaimService();
        ReflectionTestUtils.setField(claim, "mapper", callbackMapper);
        WxContentCallbackMutationService mutation = new WxContentCallbackMutationService();
        ReflectionTestUtils.setField(mutation, "mapper", callbackMapper);
        WxContentCallbackInboxServiceImpl inbox = new WxContentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(inbox, "mapper", callbackMapper);
        ReflectionTestUtils.setField(inbox, "claimService", proxy(claim));
        ReflectionTestUtils.setField(inbox, "mutationService", proxy(mutation));
        callbacks = proxy(inbox);
        ClubPostMapper posts = mock(ClubPostMapper.class);
        when(posts.selectClubPostById(POST)).thenAnswer(call -> db.queryForObject(
                "SELECT * FROM club_post WHERE id=?", (rs, n) -> {
                    ClubPost p = new ClubPost(); p.setId(rs.getLong("id")); p.setVersion(rs.getInt("version"));
                    p.setDelFlag(rs.getInt("del_flag")); p.setImages(rs.getString("images")); return p;
                }, POST));
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        UmsMember member = new UmsMember(); member.setId(MEMBER); member.setWxOpenId("original-openid");
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        checker = mock(IWxContentSecurityService.class);
        when(checker.isEnabled()).thenReturn(true);
        service = new ClubPostMediaReviewServiceImpl(mapper, transactions, posts, members, checker, callbacks, mock(AlarmService.class));
    }

    @Test
    void lastAllowedClaimSucceedsAfterFourthKnownLocalFailure() {
        long id = seed(3);
        db.update("UPDATE club_post_media_review_outbox SET attempt_count=3 WHERE id=?", id);
        ClubPostMediaReviewOutbox fourth = transactions.claim(id);
        assertEquals(4, fourth.getAttemptCount());
        assertTrue(transactions.retryTransient(fourth, ClubPostMediaReviewOutbox.PRE_DISPATCH_UNAVAILABLE));
        db.update("UPDATE club_post_media_review_outbox SET next_attempt_at=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?", id);
        assertNotNull(transactions.claim(id));
        ClubPostMediaReviewOutbox fifth = owned(id);
        assertEquals(5, fifth.getAttemptCount());
        assertTrue(callbacks.register("original-final", 3, POST, MEDIA));
        assertTrue(transactions.markSubmitted(fifth, "original-final"));
        assertNull(transactions.claim(id));
        assertEquals(2L, number("state", id)); assertEquals(5L, number("attempt_count", id));
        assertContent(0);
    }

    @Test
    void finalKnownLocalFailureStopsWithoutSixthAttemptOrContentChange() {
        long id = seed(3);
        db.update("UPDATE club_post_media_review_outbox SET attempt_count=4 WHERE id=?", id);
        ClubPostMediaReviewOutbox fifth = transactions.claim(id);
        assertTrue(transactions.retryTransient(fifth, ClubPostMediaReviewOutbox.PRE_DISPATCH_UNAVAILABLE));
        assertStopped(id, ClubPostMediaReviewOutbox.ATTEMPTS_EXHAUSTED, 5);
        assertEquals(0, service.retryDue()); assertNull(transactions.claim(id));
        verify(checker, never()).submitMediaCheckOne(anyInt(), anyLong(), anyString(), anyString());
        assertContent(0);
    }

    @Test
    void concurrentFinalClaimsHaveOneWinnerAndCannotConsumeSixthAttempt() throws Exception {
        long id = seed(3);
        db.update("UPDATE club_post_media_review_outbox SET attempt_count=4 WHERE id=?", id);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<ClubPostMediaReviewOutbox> call = () -> {
                ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return transactions.claim(id);
            };
            Future<ClubPostMediaReviewOutbox> a = workers.submit(call), b = workers.submit(call);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            ClubPostMediaReviewOutbox one = a.get(10, TimeUnit.SECONDS), two = b.get(10, TimeUnit.SECONDS);
            assertEquals(1, (one == null ? 0 : 1) + (two == null ? 0 : 1));
            assertEquals(5L, number("attempt_count", id));
        } finally { start.countDown(); workers.shutdownNow(); }
    }

    @Test
    void expiredProcessingBelowBudgetIsUnknownNotAFreeRetry() {
        long id = seed(3); ClubPostMediaReviewOutbox first = transactions.claim(id);
        expire(id); assertEquals(0, service.retryDue());
        assertStopped(id, ClubPostMediaReviewOutbox.OUTCOME_UNKNOWN, 1);
        assertNull(transactions.claim(id)); assertContent(0);
        assertFalse(transactions.retryTransient(first, ClubPostMediaReviewOutbox.PRE_DISPATCH_UNAVAILABLE));
    }

    @Test
    void expiredProcessingAtBudgetAndLegacyAmbiguousRetriesAreRetainedWithoutDispatch() {
        long id = seed(3), legacy = seed(4), exhausted = seed(5);
        db.update("UPDATE club_post_media_review_outbox SET attempt_count=4 WHERE id=?", id);
        transactions.claim(id); expire(id);
        db.update("UPDATE club_post_media_review_outbox SET state=3,attempt_count=1,last_error_code='WX_NO_RECEIPT' WHERE id=?", legacy);
        db.update("UPDATE club_post_media_review_outbox SET attempt_count=5 WHERE id=?", exhausted);
        assertEquals(0, service.retryDue());
        assertStopped(id, ClubPostMediaReviewOutbox.OUTCOME_UNKNOWN, 5);
        assertStopped(legacy, ClubPostMediaReviewOutbox.OUTCOME_UNKNOWN, 1);
        assertStopped(exhausted, ClubPostMediaReviewOutbox.ATTEMPTS_EXHAUSTED, 5);
        assertEquals(3L, db.queryForObject("SELECT COUNT(*) FROM club_post_media_review_outbox", Long.class).longValue());
        verify(checker, never()).submitMediaCheckOne(anyInt(), anyLong(), anyString(), anyString());
        assertContent(0);
    }

    @Test
    void lostReplyDoesNotRepeatOrReviveContentTakenDownDuringTheCall() {
        long id = seed(3); AtomicInteger calls = new AtomicInteger();
        when(checker.submitMediaCheckOne(3, POST, MEDIA, "original-openid")).thenAnswer(call -> {
            calls.incrementAndGet(); db.update("UPDATE club_post SET del_flag=2 WHERE id=?", POST); return null;
        });
        assertFalse(service.dispatchOne(id)); assertFalse(service.dispatchOne(id)); assertEquals(0, service.retryDue());
        assertEquals(1, calls.get()); assertStopped(id, ClubPostMediaReviewOutbox.OUTCOME_UNKNOWN, 1);
        assertContent(2);
    }

    @Test
    void lateOriginalTraceAndCallbackSurviveExpiredLeaseWithoutResubmission() {
        long id = seed(3); String trace = "known-original-trace"; AtomicInteger calls = new AtomicInteger();
        when(checker.submitMediaCheckOne(3, POST, MEDIA, "original-openid")).thenAnswer(call -> {
            calls.incrementAndGet(); assertTrue(callbacks.register(trace, 3, POST, MEDIA));
            expire(id); assertEquals(1, transactions.retireUnsafe(50)); return trace;
        });
        assertFalse(service.dispatchOne(id));
        assertStopped(id, ClubPostMediaReviewOutbox.OUTCOME_UNKNOWN, 1);
        assertTrue(WxContentCallbackInboxServiceImpl.sha256Hex(trace).equalsIgnoreCase(
                db.queryForObject("SELECT HEX(trace_hash) FROM club_post_media_review_outbox WHERE id=?", String.class, id)));
        assertTrue(callbacks.process(trace, true, new IWxContentCallbackInboxService.Mutation() {
            public boolean isCurrent(WxContentCallbackInbox row) { return HASH.equalsIgnoreCase(row.getMediaHash()); }
            public boolean apply(WxContentCallbackInbox row, boolean risky) {
                return risky && db.update("UPDATE club_post SET del_flag=2 WHERE id=?", row.getBizId()) == 1;
            }
        }));
        assertEquals(2, callbackMapper.selectByTraceHash(WxContentCallbackInboxServiceImpl.sha256Hex(trace)).getState());
        assertFalse(service.dispatchOne(id)); assertEquals(1, calls.get()); assertContent(2);
        ClubPostMediaReviewOutbox original = manualSnapshot(id);
        assertFalse(transactions.markUnknown(original, "different-trace"), "原 trace 不得覆盖");
        original.setOwnerToken("ffffffffffffffffffffffffffffffff");
        assertFalse(transactions.markUnknown(original, trace), "即使同 fence，非原 owner 不得附加结果");
    }

    @Test
    void storedSmallerBudgetRemainsBindingAndLegacyTwentyCannotRaiseFive() {
        long smaller = seed(3), legacy = seed(4);
        db.update("UPDATE club_post_media_review_outbox SET max_attempts=2,attempt_count=1 WHERE id=?", smaller);
        ClubPostMediaReviewOutbox second = transactions.claim(smaller);
        assertEquals(2, second.getAttemptCount());
        assertTrue(transactions.retryTransient(second, ClubPostMediaReviewOutbox.PRE_DISPATCH_UNAVAILABLE));
        assertStopped(smaller, ClubPostMediaReviewOutbox.ATTEMPTS_EXHAUSTED, 2);
        db.update("UPDATE club_post_media_review_outbox SET max_attempts=20,attempt_count=5 WHERE id=?", legacy);
        assertNull(transactions.claim(legacy));
        assertStopped(legacy, ClubPostMediaReviewOutbox.ATTEMPTS_EXHAUSTED, 5);
        assertContent(0);
    }

    private long seed(int version) {
        Integer inserted = new TransactionTemplate(manager).execute(status ->
                transactions.enqueueUpdatedMedia(POST, version, MEMBER, MEDIA));
        assertNotNull(inserted); assertEquals(1, inserted.intValue());
        return mapper.selectByKey(POST, version, HASH).getId();
    }

    private ClubPostMediaReviewOutbox owned(long id) {
        String token = db.queryForObject("SELECT HEX(owner_token) FROM club_post_media_review_outbox WHERE id=?", String.class, id);
        return mapper.selectOwned(id, token);
    }

    private ClubPostMediaReviewOutbox manualSnapshot(long id) {
        ClubPostMediaReviewOutbox row = new ClubPostMediaReviewOutbox(); row.setId(id);
        row.setOwnerToken(db.queryForObject("SELECT HEX(owner_token) FROM club_post_media_review_outbox WHERE id=?", String.class, id));
        row.setFenceToken(number("fence_token", id)); return row;
    }

    private void expire(long id) {
        db.update("UPDATE club_post_media_review_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?", id);
    }

    private long number(String column, long id) {
        // Test-only closed column set.
        if (!java.util.Set.of("state", "attempt_count", "fence_token").contains(column)) throw new IllegalArgumentException();
        return db.queryForObject("SELECT " + column + " FROM club_post_media_review_outbox WHERE id=?", Long.class, id);
    }

    private void assertStopped(long id, String code, int attempts) {
        assertEquals(5L, number("state", id)); assertEquals((long) attempts, number("attempt_count", id));
        assertEquals(code, db.queryForObject("SELECT last_error_code FROM club_post_media_review_outbox WHERE id=?", String.class, id));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM club_post_media_review_outbox WHERE id=? AND lease_until IS NOT NULL", Long.class, id).longValue());
    }

    private void assertContent(int delFlag) {
        assertEquals(delFlag, db.queryForObject("SELECT del_flag FROM club_post WHERE id=?", Integer.class, POST).intValue());
        assertEquals(3, db.queryForObject("SELECT version FROM club_post WHERE id=?", Integer.class, POST).intValue());
        assertEquals(MEDIA, db.queryForObject("SELECT images FROM club_post WHERE id=?", String.class, POST));
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private void executeMigration(String name) throws Exception {
        Path p = Path.of("sql", name);
        if (!Files.exists(p)) p = Path.of("chengyinhub-admin", "sql", name);
        String fixedScript = Files.readString(p).replaceAll("(?m)^\\s*--.*$", "");
        // These three fixed scripts have no procedures or semicolons inside string literals.
        try (Connection c = source.getConnection(); Statement st = c.createStatement()) {
            for (String sql : fixedScript.split(";")) {
                if (sql.isBlank()) continue;
                if (st.execute(sql)) try (ResultSet rows = st.getResultSet()) {
                    if (rows.getMetaData().getColumnCount() == 1
                            && "assertion_ok".equalsIgnoreCase(rows.getMetaData().getColumnLabel(1))) {
                        assertTrue(rows.next() && rows.getBoolean(1), "Migration metadata assertion failed: " + name);
                    }
                }
            }
        }
    }
}
