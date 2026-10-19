package com.chengyinhub.business.forward20261015;

import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.PlayerGrowth;
import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.GrowthMissionProgressMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.PlayerGrowthMapper;
import com.chengyinhub.business.mapper.TopicCompletionXpAwardMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.ITopicCompletionAwardService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.PlayerProgressionServiceImpl;
import com.chengyinhub.business.service.impl.TopicCompletionAwardServiceImpl;
import com.chengyinhub.framework.aspectj.DeadlockRetryInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * CY-FORWARD3-20261015 / F3-GROW-01. Source delivery only: NOT_RUN.
 * Uses the production service transaction advice, real MyBatis XML and InnoDB unique keys.
 * The existing MySQL IT opt-in must point to a disposable test server with create/drop-schema rights.
 * Calls the real topic-completion outer service for its marker-first lock order, with non-growth
 * reward collaborators mocked. Upstream member/points lock-order retries
 * and missing TOPIC_CLEAR configuration are separate limits, not claimed as solved here.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlayerGrowthConcurrencyMySqlIT {
    // 固定并发计数用例的逻辑日,不作为生产日界集成验证。
    private static final LocalDate CHECKIN_DAY = LocalDate.of(2030, 1, 15);
    private String database;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private DriverManagerDataSource source;
    private DataSourceTransactionManager transactions;
    private PlayerGrowthMapper growth;
    private PlayerBadgeMapper badges;
    private GrowthBadgeMapper definitions;
    private ICmsTopicChapterService chapters;
    private boolean workersTerminated = true;

    @BeforeAll
    void setup() throws Exception {
        for (String key : new String[] {"HOST", "PORT", "DATABASE_BASE", "DATABASE_SUFFIX", "USER", "PASSWORD"}) {
            String name = "CHENGYIN_TEST_MYSQL_" + key;
            assertTrue(System.getenv().containsKey(name), "必须显式绑定独占测试配置: " + name);
        }
        assertFalse(System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX").trim().isEmpty(),
                "独占测试 schema suffix 必须非空");
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("f3_growth");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(
                admin.queryForObject("select version()", String.class)), "需要真实 MySQL 8+，不能用 H2/MariaDB 替代");
        assertTrue(config.databaseName().length() <= 47, "配置测试库名前缀最多47字符，预留UUID独占后缀");
        database = config.databaseName() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        admin.execute("create database " + database);
        source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"),
                config.user(), config.password());
        db = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        // Extract the two existing definitions, including uk_member/uk_member_badge, without new DDL policy.
        Path schema = Paths.get("sql/base_schema_20260818.sql");
        if (!Files.exists(schema)) schema = Paths.get("../sql/base_schema_20260818.sql");
        String ddl = new String(Files.readAllBytes(schema), StandardCharsets.UTF_8);
        createFromBaseline(ddl, "player_growth");
        createFromBaseline(ddl, "player_badge");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/PlayerGrowthMapper.xml"),
                new ClassPathResource("mapper/business/PlayerBadgeMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        growth = session.getMapper(PlayerGrowthMapper.class);
        badges = session.getMapper(PlayerBadgeMapper.class);
    }

    @BeforeEach
    void reset() {
        db.update("delete from player_badge");
        db.update("delete from player_growth");
        definitions = mock(GrowthBadgeMapper.class);
        when(definitions.selectByCode(anyString())).thenAnswer(call -> {
            String code = call.getArgument(0);
            GrowthBadge definition = new GrowthBadge();
            definition.setBadgeCode(code);
            definition.setBadgeName(code);
            definition.setUnlockRuleJson("{\"threshold\":" +
                    ("MILE_50".equals(code) ? 50 : "MILE_10".equals(code) ? 10 :
                            "STREAK_7".equals(code) ? 7 : "STREAK_3".equals(code) ? 3 : 1) + "}");
            return definition;
        });
        chapters = mock(ICmsTopicChapterService.class);
        when(chapters.selectCmsTopicChapterList(any())).thenAnswer(call -> {
            CmsTopicChapter probe = call.getArgument(0);
            CmsTopicChapter chapter = new CmsTopicChapter();
            chapter.setTotalMileage(new BigDecimal(probe.getTopicId() == 11L ? "2.25" : "3.75"));
            return Collections.singletonList(chapter);
        });
    }

    @AfterAll
    void cleanup() {
        if (database != null && admin != null) {
            assertTrue(workersTerminated, "工作线程未终止，保留独占schema供总控检查，禁止竞争删表");
            admin.execute("drop database " + database);
            assertEquals(0, admin.queryForObject(
                    "select count(*) from information_schema.schemata where schema_name=?", Integer.class, database));
        }
    }

    @Test
    void twoFirstNodeCallsCreateOneRowAndCountBoth() throws Exception {
        contend(service -> service.onNodeDone(1L), service -> service.onNodeDone(1L), false);
        assertGrowth(2, "0.00", 1);
        assertEquals(1, db.queryForObject("select count(*) from player_growth where member_id=1", Integer.class));
        assertEquals(1, badges.countByMemberAndCode(1L, "FIRST_STEP"));
    }

    @Test
    void existingNodeCallsAccumulateAcrossLevelBoundary() throws Exception {
        seed(4, "8.50", 2);
        contend(service -> service.onNodeDone(1L), service -> service.onNodeDone(1L), false);
        assertGrowth(6, "8.50", 2);
        assertEquals(2, growth.selectByMember(1L).getLevel());
    }

    @Test
    void nodeAndTopicKeepBothIndependentCounters() throws Exception {
        seed(4, "8.50", 2);
        contend(service -> service.onNodeDone(1L), service -> service.onTopicComplete(1L, 11L), false);
        assertGrowth(5, "10.75", 2);
        assertEquals(2, growth.selectByMember(1L).getLevel());
        assertNotNull(badges.selectOne(1L, "MILE_10", 0L));
        assertNotNull(badges.selectOne(1L, "TOPIC_CLEAR", 11L));
    }

    @Test
    void topicThenNodeKeepsMileageAndExistingStreak() throws Exception {
        seed(9, "8.50", 6);
        contend(service -> service.onTopicComplete(1L, 11L), service -> service.onNodeDone(1L), false);
        assertGrowth(10, "10.75", 6);
        assertEquals(3, growth.selectByMember(1L).getLevel());
    }

    @Test
    void differentFirstTopicsAccumulateMileageWithoutDuplicateGrowthRow() throws Exception {
        contend(service -> service.onTopicComplete(1L, 11L), service -> service.onTopicComplete(1L, 22L), false);
        assertGrowth(0, "6.00", 0);
        assertNotNull(badges.selectOne(1L, "TOPIC_CLEAR", 11L));
        assertNotNull(badges.selectOne(1L, "TOPIC_CLEAR", 22L));
    }

    @Test
    void repeatableReadSnapshotCannotLoseLaterNodeIncrement() throws Exception {
        seed(4, "8.50", 2);
        contend(service -> service.onNodeDone(1L), service -> service.onNodeDone(1L), true);
        assertGrowth(6, "8.50", 2);
    }

    @Test
    void differentOuterTopicMarkersDoNotIntroduceGrowthBadgeGapLockCycle() throws Exception {
        seed(4, "8.50", 2);
        // Reverse topic IDs: second's uncommitted FINISH_11 sorts before first's FINISH_22.
        // A missing TOPIC_CLEAR FOR UPDATE in RR can wait on that marker while second waits on growth.
        contend(service -> assertTrue(completion(service, badges).grantOnce(1L, 22L, 0L, null).isFirstCompletion()),
                service -> assertTrue(completion(service, badges).grantOnce(1L, 11L, 0L, null).isFirstCompletion()), true);
        assertGrowth(4, "14.50", 2);
        assertEquals(2, badges.countByMemberAndCode(1L, "TOPIC_CLEAR"));
        assertNotNull(badges.selectOne(1L, "TOPIC_FINISH_11", 11L));
        assertNotNull(badges.selectOne(1L, "TOPIC_FINISH_22", 22L));
    }

    @Test
    void sameTopicOuterUniqueMarkerCallsGrowthOnlyOnce() throws Exception {
        // 重复请求在 TOPIC_FINISH 判重上排队，与首请求随后插 TOPIC_CLEAR/MILE_* 的间隙锁成环，InnoDB 选它为牺牲者。
        // 生产边界的 @DeadlockRetry 在事务外层整笔重来一次：首请求已提交，重试读到既有事实 ⇒ firstCompletion=false。
        AtomicInteger markerInserts = new AtomicInteger();
        Future<TopicCompletionAwardVO> two = sameTopicDuplicate(true, markerInserts);
        assertFalse(two.get(5, TimeUnit.SECONDS).isFirstCompletion(), "重复通关经重试后只确认既有事实");
        assertEquals(3, markerInserts.get(), "第二请求的通关事实插入被死锁回滚后由重试边界再走一次");
        assertGrowth(4, "10.75", 2);
        assertEquals(1, badges.countByMemberAndCode(1L, "TOPIC_CLEAR"));
        assertNotNull(badges.selectOne(1L, "TOPIC_FINISH_11", 11L));
    }

    @Test
    void sameTopicDuplicateWithoutRetryBoundaryIsDeadlockVictim() throws Exception {
        // 负控：去掉重试边界（只有事务拦截器）时，同一时序下重复请求以死锁回滚报错，不是 firstCompletion=false。
        AtomicInteger markerInserts = new AtomicInteger();
        Future<TopicCompletionAwardVO> two = sameTopicDuplicate(false, markerInserts);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> two.get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof DeadlockLoserDataAccessException, String.valueOf(failure.getCause()));
        assertEquals(2, markerInserts.get(), "无重试边界时不会再插第二次");
        assertGrowth(4, "10.75", 2);
        assertEquals(1, badges.countByMemberAndCode(1L, "TOPIC_CLEAR"));
    }

    /** 首请求持成长锁时让第二请求撞上未提交的 TOPIC_FINISH_11；返回第二请求，首请求已断言为首次通关。 */
    private Future<TopicCompletionAwardVO> sameTopicDuplicate(boolean retryBoundary, AtomicInteger markerInserts)
            throws Exception {
        seed(4, "8.50", 2);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondMarkerAttempted = new CountDownLatch(1);
        AtomicInteger growthReads = new AtomicInteger();
        PlayerGrowthMapper observedGrowth = (PlayerGrowthMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PlayerGrowthMapper.class}, (proxy, method, args) -> {
                    Object result = invoke(growth, method, args);
                    if ("selectByMemberForUpdate".equals(method.getName())) {
                        growthReads.incrementAndGet();
                        firstLocked.countDown();
                        await(releaseFirst);
                    }
                    return result;
                });
        PlayerBadgeMapper observedBadges = (PlayerBadgeMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PlayerBadgeMapper.class}, (proxy, method, args) -> {
                    if ("insertPlayerBadge".equals(method.getName())
                            && ((PlayerBadge) args[0]).getBadgeCode().startsWith("TOPIC_FINISH_")
                            && markerInserts.incrementAndGet() == 2) secondMarkerAttempted.countDown();
                    return invoke(badges, method, args);
                });
        ITopicCompletionAwardService completion = completion(service(observedGrowth), observedBadges, retryBoundary);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<TopicCompletionAwardVO> one = workers.submit(() -> completion.grantOnce(1L, 11L, 0L, null));
        Future<TopicCompletionAwardVO> two;
        try {
            assertTrue(firstLocked.await(5, TimeUnit.SECONDS));
            two = workers.submit(() -> completion.grantOnce(1L, 11L, 0L, null));
            assertTrue(secondMarkerAttempted.await(5, TimeUnit.SECONDS));
            assertFalse(two.isDone(), "首个通关事实尚未提交时，第二请求不能确认通关");
            releaseFirst.countDown();
            assertTrue(one.get(5, TimeUnit.SECONDS).isFirstCompletion());
            try {
                two.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException expectedInNegativeControl) {
                // 结果由调用方断言
            }
        } finally {
            releaseFirst.countDown();
            stopWorkers(workers);
        }
        assertEquals(1, growthReads.get(), "唯一实际 caller 只在首次TOPIC_FINISH事实成立时调用成长");
        return two;
    }

    @Test
    void existingTopicClearLeavesAllGrowthFieldsUntouched() {
        seed(4, "8.50", 2);
        db.update("insert into player_badge(member_id,badge_code,source_id,del_flag) values(1,'TOPIC_CLEAR',11,0)");
        PlayerGrowth before = growth.selectByMember(1L);
        assertTrue(service(growth).onTopicComplete(1L, 11L).isEmpty());
        assertGrowth(4, "8.50", 2);
        PlayerGrowth after = growth.selectByMember(1L);
        assertEquals(before.getUpdateTime(), after.getUpdateTime(), "重复确保建行不得刷新旧时间字段");
        assertEquals(before.getCreateTime(), after.getCreateTime());
        assertEquals(before.getLastCheckinDate(), after.getLastCheckinDate());
        assertEquals(before.getLevel(), after.getLevel());
    }

    @Test
    void badgeFailureRollsBackFirstRowAndCounter() {
        when(definitions.selectByCode("FIRST_STEP")).thenThrow(new IllegalStateException("badge configuration unavailable"));
        assertThrows(IllegalStateException.class, () -> service(growth).onNodeDone(1L));
        assertNull(growth.selectByMember(1L), "建行和计数必须与徽章失败一起回滚");
        assertEquals(0, badges.countByMemberAndCode(1L, "FIRST_STEP"));
    }

    @Test
    void ordinaryGetGrowthDoesNotWaitForWriterOrCreateMissingRows() throws Exception {
        seed(4, "8.50", 2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<?> writer = workers.submit(() -> new TransactionTemplate(transactions).execute(status -> {
            growth.ensureGrowthRow(1L, new java.util.Date());
            growth.selectByMemberForUpdate(1L);
            locked.countDown();
            await(release);
            return null;
        }));
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            Future<PlayerGrowth> read = workers.submit(() -> service(growth).getGrowth(1L));
            assertEquals(4, read.get(3, TimeUnit.SECONDS).getTotalCheckins());
            assertEquals(1, service(growth).getGrowth(999L).getLevel());
            assertEquals(0, db.queryForObject("select count(*) from player_growth where member_id=999", Integer.class));
            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            stopWorkers(workers);
        }
    }

    private void contend(Consumer<IPlayerProgressionService> first, Consumer<IPlayerProgressionService> second,
            boolean primeRepeatableReadSnapshot) throws Exception {
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondPassedEnsure = new CountDownLatch(1);
        AtomicInteger ensures = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        PlayerGrowthMapper observed = (PlayerGrowthMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PlayerGrowthMapper.class}, (proxy, method, args) -> {
                    int ensure = "ensureGrowthRow".equals(method.getName()) ? ensures.incrementAndGet() : 0;
                    if (ensure == 2) secondAttempted.countDown();
                    Object result = invoke(growth, method, args);
                    if (ensure == 2) secondPassedEnsure.countDown();
                    if ("selectByMemberForUpdate".equals(method.getName()) && reads.incrementAndGet() == 1) {
                        firstLocked.countDown();
                        await(releaseFirst);
                    }
                    return result;
                });
        IPlayerProgressionService service = service(observed);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<?> one = workers.submit(() -> {
            TransactionTemplate outer = new TransactionTemplate(transactions);
            outer.setIsolationLevel(primeRepeatableReadSnapshot ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                    : TransactionDefinition.ISOLATION_READ_COMMITTED);
            outer.execute(status -> {
                first.accept(service);
                return null;
            });
        });
        try {
            assertTrue(firstLocked.await(5, TimeUnit.SECONDS), "首写者未取得成长锁");
            Future<?> two = workers.submit(() -> {
                TransactionTemplate outer = new TransactionTemplate(transactions);
                outer.setIsolationLevel(primeRepeatableReadSnapshot ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                        : TransactionDefinition.ISOLATION_READ_COMMITTED);
                outer.execute(status -> {
                    if (primeRepeatableReadSnapshot) {
                        assertEquals(4, service.getGrowth(1L).getTotalCheckins());
                        assertNull(badges.selectOne(1L, "TOPIC_CLEAR", 11L), "在首写者提交前建立旧快照");
                    }
                    second.accept(service);
                    return null;
                });
            });
            assertTrue(secondAttempted.await(5, TimeUnit.SECONDS), "次写者未尝试建行/取得成长锁");
            assertFalse(secondPassedEnsure.await(250, TimeUnit.MILLISECONDS),
                    "负控:首事务提交前，次写者不得穿过成长行锁");
            assertFalse(two.isDone());
            releaseFirst.countDown();
            one.get(5, TimeUnit.SECONDS);
            two.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            stopWorkers(workers);
        }
    }

    private IPlayerProgressionService service(PlayerGrowthMapper mapper) {
        PlayerProgressionServiceImpl target = new PlayerProgressionServiceImpl() {
            @Override
            protected Date currentCheckinTime() {
                return Date.from(CHECKIN_DAY.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant());
            }
        };
        ReflectionTestUtils.setField(target, "playerGrowthMapper", mapper);
        ReflectionTestUtils.setField(target, "playerBadgeMapper", badges);
        ReflectionTestUtils.setField(target, "growthBadgeMapper", definitions);
        ReflectionTestUtils.setField(target, "cmsTopicChapterService", chapters);
        ReflectionTestUtils.setField(target, "ruleEvaluator", new RuleEvaluator());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (IPlayerProgressionService) proxy.getProxy();
    }

    private ITopicCompletionAwardService completion(IPlayerProgressionService progression, PlayerBadgeMapper markers) {
        return completion(progression, markers, false);
    }

    /** retryBoundary=true 时按生产顺序在事务拦截器外层挂死锁重试（DeadlockRetryConfig.ORDER 高于事务通知器）。 */
    private ITopicCompletionAwardService completion(IPlayerProgressionService progression, PlayerBadgeMapper markers,
            boolean retryBoundary) {
        TopicCompletionAwardServiceImpl target = new TopicCompletionAwardServiceImpl();
        ICmsTopicService topics = mock(ICmsTopicService.class);
        when(topics.selectCmsTopicById(anyLong())).thenAnswer(call -> {
            CmsTopic topic = new CmsTopic();
            topic.setId(call.getArgument(0));
            topic.setName("growth fixture");
            return topic;
        });
        IXpBudgetService budget = mock(IXpBudgetService.class);
        when(budget.computeBudget(anyLong())).thenReturn(Collections.<String, Object>singletonMap("remain", 0));
        ReflectionTestUtils.setField(target, "topicService", topics);
        ReflectionTestUtils.setField(target, "playerBadgeMapper", markers);
        ReflectionTestUtils.setField(target, "playerProgressionService", progression);
        ReflectionTestUtils.setField(target, "pointsAwardService", mock(IPointsAwardService.class));
        ReflectionTestUtils.setField(target, "officialEventV2Service", mock(IOfficialEventV2Service.class));
        ReflectionTestUtils.setField(target, "growthMissionProgressMapper", mock(GrowthMissionProgressMapper.class));
        ReflectionTestUtils.setField(target, "xpBudgetService", budget);
        ReflectionTestUtils.setField(target, "xpAwardMapper", mock(TopicCompletionXpAwardMapper.class));
        ProxyFactory proxy = new ProxyFactory(target);
        if (retryBoundary) proxy.addAdvice(new DeadlockRetryInterceptor());
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (ITopicCompletionAwardService) proxy.getProxy();
    }

    private Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private void seed(int checkins, String mileage, int streak) {
        db.update("insert into player_growth(member_id,level,total_checkins,total_mileage,streak_days,last_checkin_date,"
                + "create_time,update_time) values(1,?,?,?, ?,?,'2026-01-01 00:00:00','2026-01-02 00:00:00')",
                checkins / 5 + 1, checkins, new BigDecimal(mileage), streak, java.sql.Date.valueOf(CHECKIN_DAY));
    }

    private void assertGrowth(int checkins, String mileage, int streak) {
        PlayerGrowth row = growth.selectByMember(1L);
        assertNotNull(row);
        assertEquals(checkins, row.getTotalCheckins());
        assertEquals(0, new BigDecimal(mileage).compareTo(row.getTotalMileage()));
        assertEquals(streak, row.getStreakDays());
    }

    private void createFromBaseline(String ddl, String table) {
        int start = ddl.indexOf("CREATE TABLE `" + table + "`");
        assertTrue(start >= 0, "基线表定义缺失: " + table);
        int end = ddl.indexOf(';', start);
        db.execute(ddl.substring(start, end + 1));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "并发测试屏障超时");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private void stopWorkers(ExecutorService workers) {
        workers.shutdownNow();
        try {
            boolean stopped = workers.awaitTermination(5, TimeUnit.SECONDS);
            workersTerminated &= stopped;
            assertTrue(stopped, "并发工作线程未终止，禁止继续清理schema");
        } catch (InterruptedException interrupted) {
            workersTerminated = false;
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
