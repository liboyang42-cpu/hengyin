package com.chengyinhub.business.it;

import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.GrowthMissionProgressMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.PlayerGrowthMapper;
import com.chengyinhub.business.mapper.TopicCompletionXpAwardMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.ITopicCompletionAwardService;
import com.chengyinhub.business.service.ITopicCompletionCouponRewardService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.PlayerProgressionServiceImpl;
import com.chengyinhub.business.service.impl.TopicCompletionAwardServiceImpl;
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
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 同一会员同一主题的并发重复通关(玩家最后一站打卡与商家扫码同时到、连点、弱网重试)。
 * 真 MySQL InnoDB:第二个请求必须排在会员行锁上,等首个提交后按「已通关」返回,
 * 而不是在未提交的通关勋章行上与首个请求随后写入的里程/通关徽章互相等待、被选为死锁牺牲者
 * (F3 候选 PlayerGrowthConcurrencyMySqlIT 在 d592334a 上的失败形态)。
 * 走真实通关服务的事务切面、真实 MyBatis XML 与 uk_member_badge;积分/券/活动证据/XP 协作者为桩。
 * 需要可建删 schema 的一次性测试库(真库手动回归入口按登记注入 CHENGYIN_TEST_MYSQL_*)。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopicCompletionMemberLockMySqlIT {
    private static final long MEMBER = 1L;
    private static final long TOPIC = 11L;
    private String database;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private DriverManagerDataSource source;
    private DataSourceTransactionManager transactions;
    private PlayerGrowthMapper growth;
    private PlayerBadgeMapper badges;
    private UmsMemberMapper members;
    private boolean workersTerminated = true;

    @BeforeAll
    void setup() throws Exception {
        for (String key : new String[] {"HOST", "PORT", "DATABASE_BASE", "DATABASE_SUFFIX", "USER", "PASSWORD"}) {
            String name = "CHENGYIN_TEST_MYSQL_" + key;
            assertTrue(System.getenv().containsKey(name), "必须显式绑定独占测试配置: " + name);
        }
        assertFalse(System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX").trim().isEmpty(),
                "独占测试 schema suffix 必须非空");
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("tcml");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(
                admin.queryForObject("select version()", String.class)), "需要真实 MySQL 8+，不能用 H2/MariaDB 替代");
        assertTrue(config.databaseName().length() <= 47, "测试库名前缀最多47字符，预留独占后缀");
        database = config.databaseName() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        admin.execute("create database " + database);
        source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"),
                config.user(), config.password());
        db = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        // 只取基线里已有的三张表定义(含 uk_member_badge / uk_member),不另造 DDL。
        Path schema = Paths.get("sql/base_schema_20260818.sql");
        if (!Files.exists(schema)) schema = Paths.get("../sql/base_schema_20260818.sql");
        String ddl = new String(Files.readAllBytes(schema), StandardCharsets.UTF_8);
        createFromBaseline(ddl, "ums_member");
        createFromBaseline(ddl, "player_badge");
        createFromBaseline(ddl, "player_growth");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/PlayerGrowthMapper.xml"),
                new ClassPathResource("mapper/business/PlayerBadgeMapper.xml"),
                new ClassPathResource("mapper/business/UmsMemberMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        growth = session.getMapper(PlayerGrowthMapper.class);
        badges = session.getMapper(PlayerBadgeMapper.class);
        members = session.getMapper(UmsMemberMapper.class);
    }

    @BeforeEach
    void reset() {
        db.update("delete from player_badge");
        db.update("delete from player_growth");
        db.update("delete from ums_member");
        db.update("insert into ums_member(id) values(?)", MEMBER);
        db.update("insert into player_growth(member_id,level,total_checkins,total_mileage,streak_days,"
                + "create_time,update_time) values(?,1,4,8.50,2,'2026-01-01 00:00:00','2026-01-02 00:00:00')", MEMBER);
    }

    @AfterAll
    void cleanup() {
        if (database != null && admin != null) {
            assertTrue(workersTerminated, "工作线程未终止，保留独占schema供检查，禁止竞争删表");
            admin.execute("drop database " + database);
            assertEquals(0, admin.queryForObject(
                    "select count(*) from information_schema.schemata where schema_name=?", Integer.class, database));
        }
    }

    /** 连点/重试形态:两笔都在 READ COMMITTED(同打卡路径),第二笔排在会员行上,首笔提交后按「已通关」返回。 */
    @Test
    void duplicateCompletionWaitsOnMemberRowAndReportsAlreadyCompleted() throws Exception {
        Race race = new Race();
        TopicCompletionAwardVO[] results = race.run(
                () -> inTransaction(TransactionDefinition.ISOLATION_READ_COMMITTED,
                        () -> race.completion.grantOnce(MEMBER, TOPIC, 0L, null)),
                () -> inTransaction(TransactionDefinition.ISOLATION_READ_COMMITTED,
                        () -> race.completion.grantOnce(MEMBER, TOPIC, 0L, null)));

        assertTrue(results[0].isFirstCompletion());
        assertFalse(results[1].isFirstCompletion(), "重复通关必须按「已通关」返回，不能报死锁或唯一键冲突");
        assertCompletedOnce(race);
    }

    /** 商家核销形态:第二笔在 REPEATABLE READ 里先有普通读(旧快照)再进通关;撞唯一键后的回查必须是当前读。 */
    @Test
    void repeatableReadCallerWithOldSnapshotStillSeesCommittedCompletion() throws Exception {
        Race race = new Race();
        AtomicInteger staleFinishRows = new AtomicInteger(-1);
        TopicCompletionAwardVO[] results = race.run(
                () -> inTransaction(TransactionDefinition.ISOLATION_READ_COMMITTED,
                        () -> race.completion.grantOnce(MEMBER, TOPIC, 0L, null)),
                () -> inTransaction(TransactionDefinition.ISOLATION_REPEATABLE_READ, () -> {
                    // 首笔尚未提交:这次普通读建立的快照里没有通关勋章。
                    assertEquals(0, finishRows());
                    TopicCompletionAwardVO second = race.completion.grantOnce(MEMBER, TOPIC, 0L, null);
                    // 同一事务的普通读仍看不到首笔已提交的勋章 ⇒ 若回查用普通读,这里会误抛唯一键冲突。
                    staleFinishRows.set(finishRows());
                    return second;
                }));

        assertTrue(results[0].isFirstCompletion());
        assertFalse(results[1].isFirstCompletion());
        assertEquals(0, staleFinishRows.get(), "负控前提:第二笔的快照确实早于首笔提交");
        assertCompletedOnce(race);
    }

    private void assertCompletedOnce(Race race) {
        assertEquals(1, finishRows());
        assertEquals(1, badges.countByMemberAndCode(MEMBER, "TOPIC_CLEAR"));
        assertEquals(1, badges.countByMemberAndCode(MEMBER, "MILE_10"));
        assertEquals(1, race.growthUpdates.get(), "成长只在首次通关事实成立时累计一次");
        assertEquals(0, new BigDecimal("10.75").compareTo(growth.selectByMember(MEMBER).getTotalMileage()));
    }

    private int finishRows() {
        return db.queryForObject("select count(*) from player_badge where member_id=? and badge_code=?",
                Integer.class, MEMBER, "TOPIC_FINISH_" + TOPIC);
    }

    private TopicCompletionAwardVO inTransaction(int isolation, Callable<TopicCompletionAwardVO> body) {
        TransactionTemplate outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(isolation);
        return outer.execute(status -> {
            try {
                return body.call();
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    /** 首笔在通关里累计成长时停住(已写通关勋章、持有会员行锁),第二笔此时进来。 */
    private final class Race {
        private final CountDownLatch firstHeld = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch secondLockRequested = new CountDownLatch(1);
        private final AtomicInteger lockRequests = new AtomicInteger();
        private final AtomicInteger finishInserts = new AtomicInteger();
        private final AtomicInteger growthUpdates = new AtomicInteger();
        private final ITopicCompletionAwardService completion;

        private Race() {
            PlayerGrowthMapper observedGrowth = (PlayerGrowthMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {PlayerGrowthMapper.class}, (proxy, method, args) -> {
                        if ("updatePlayerGrowth".equals(method.getName()) && growthUpdates.incrementAndGet() == 1) {
                            firstHeld.countDown();
                            await(releaseFirst);
                        }
                        return invoke(growth, method, args);
                    });
            PlayerBadgeMapper observedBadges = (PlayerBadgeMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {PlayerBadgeMapper.class}, (proxy, method, args) -> {
                        if ("insertPlayerBadge".equals(method.getName())
                                && ((PlayerBadge) args[0]).getBadgeCode().startsWith("TOPIC_FINISH_")) {
                            finishInserts.incrementAndGet();
                        }
                        return invoke(badges, method, args);
                    });
            IUmsMemberService locker = mock(IUmsMemberService.class);
            when(locker.lockMemberById(anyLong())).thenAnswer(call -> {
                if (lockRequests.incrementAndGet() == 2) secondLockRequested.countDown();
                return members.lockMemberById(call.getArgument(0));
            });
            completion = completion(progression(observedGrowth, observedBadges), observedBadges, locker);
        }

        private TopicCompletionAwardVO[] run(Callable<TopicCompletionAwardVO> first,
                                             Callable<TopicCompletionAwardVO> second) throws Exception {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<TopicCompletionAwardVO> one = workers.submit(first);
                assertTrue(firstHeld.await(10, TimeUnit.SECONDS), "首笔未进入成长累计");
                assertEquals(1, finishInserts.get());
                Future<TopicCompletionAwardVO> two = workers.submit(second);
                assertTrue(secondLockRequested.await(10, TimeUnit.SECONDS), "第二笔未请求会员行锁");
                Thread.sleep(300);
                assertFalse(two.isDone(), "首笔提交前，第二笔必须排在会员行锁上");
                assertEquals(1, finishInserts.get(), "第二笔不得在未提交的通关勋章行上等待");
                releaseFirst.countDown();
                return new TopicCompletionAwardVO[] {one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)};
            } finally {
                releaseFirst.countDown();
                stopWorkers(workers);
            }
        }
    }

    private IPlayerProgressionService progression(PlayerGrowthMapper growthMapper, PlayerBadgeMapper badgeMapper) {
        GrowthBadgeMapper definitions = mock(GrowthBadgeMapper.class);
        when(definitions.selectByCode(anyString())).thenAnswer(call -> {
            String code = call.getArgument(0);
            GrowthBadge definition = new GrowthBadge();
            definition.setBadgeCode(code);
            definition.setBadgeName(code);
            definition.setUnlockRuleJson("{\"threshold\":" + ("MILE_50".equals(code) ? 50 : "MILE_10".equals(code) ? 10 : 1) + "}");
            return definition;
        });
        ICmsTopicChapterService chapters = mock(ICmsTopicChapterService.class);
        when(chapters.selectCmsTopicChapterList(any())).thenAnswer(call -> {
            CmsTopicChapter chapter = new CmsTopicChapter();
            chapter.setTotalMileage(new BigDecimal("2.25"));
            return Collections.singletonList(chapter);
        });
        PlayerProgressionServiceImpl target = new PlayerProgressionServiceImpl();
        ReflectionTestUtils.setField(target, "playerGrowthMapper", growthMapper);
        ReflectionTestUtils.setField(target, "playerBadgeMapper", badgeMapper);
        ReflectionTestUtils.setField(target, "growthBadgeMapper", definitions);
        ReflectionTestUtils.setField(target, "cmsTopicChapterService", chapters);
        ReflectionTestUtils.setField(target, "ruleEvaluator", new RuleEvaluator());
        return transactional(target, IPlayerProgressionService.class);
    }

    private ITopicCompletionAwardService completion(IPlayerProgressionService progression, PlayerBadgeMapper markers,
                                                    IUmsMemberService locker) {
        TopicCompletionAwardServiceImpl target = new TopicCompletionAwardServiceImpl();
        ICmsTopicService topics = mock(ICmsTopicService.class);
        when(topics.selectCmsTopicById(anyLong())).thenAnswer(call -> {
            CmsTopic topic = new CmsTopic();
            topic.setId(call.getArgument(0));
            topic.setName("completion lock fixture");
            return topic;
        });
        IXpBudgetService budget = mock(IXpBudgetService.class);
        when(budget.computeBudget(anyLong())).thenReturn(Collections.<String, Object>singletonMap("remain", 0));
        ReflectionTestUtils.setField(target, "topicService", topics);
        ReflectionTestUtils.setField(target, "playerBadgeMapper", markers);
        ReflectionTestUtils.setField(target, "playerProgressionService", progression);
        ReflectionTestUtils.setField(target, "pointsAwardService", mock(IPointsAwardService.class));
        ReflectionTestUtils.setField(target, "couponRewardService", mock(ITopicCompletionCouponRewardService.class));
        ReflectionTestUtils.setField(target, "officialEventV2Service", mock(IOfficialEventV2Service.class));
        ReflectionTestUtils.setField(target, "xpBudgetService", budget);
        ReflectionTestUtils.setField(target, "growthService", mock(IGrowthService.class));
        ReflectionTestUtils.setField(target, "xpAwardMapper", mock(TopicCompletionXpAwardMapper.class));
        ReflectionTestUtils.setField(target, "growthMissionProgressMapper", mock(GrowthMissionProgressMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberService", locker);
        return transactional(target, ITopicCompletionAwardService.class);
    }

    private <T> T transactional(Object target, Class<T> type) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
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
            boolean stopped = workers.awaitTermination(10, TimeUnit.SECONDS);
            workersTerminated &= stopped;
            assertTrue(stopped, "并发工作线程未终止，禁止继续清理schema");
        } catch (InterruptedException interrupted) {
            workersTerminated = false;
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
