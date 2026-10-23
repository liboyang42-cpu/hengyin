package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.PlayPredictBet;
import com.chengyinhub.business.domain.PlayPredictResult;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayPredictMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R9-02 迟到下注竞态:真 MySQL 8 上两个事务**真并发**,不是 Mockito 摆样子。
 *
 * <p>原实现是「请求前检查」:下注事务先快照读 {@code play_predict_result.settle_status},
 * 未揭晓就继续写注。商家结算是一把一次性扫描(UPDATE 状态 → 捞当时的注 → 结完即止),
 * 于是「读状态」与「写注」之间那段窗口里,这一注两边都没人管 —— reward_status 永远停在 0,
 * 商家待办(只列未结算轮次)也看不到它。Mock 单测测不出这个:窗口只有真行锁能关。
 *
 * <p>修复后下注事务在写注之前 {@code SELECT … FOR UPDATE} 锁住轮次行:
 * <ul>
 *   <li>结算先到:锁定读拿到的是**最新已提交**状态(当前读,不是事务快照),直接拒收;</li>
 *   <li>下注先到:结算的条件 UPDATE 必须等它提交,提交后再扫,这一注就在扫描结果里。</li>
 * </ul>
 *
 * <p>两个用例各自实现一条方向,都是「旧实现必红」的形状(负控:去掉 XML 里的
 * {@code for update} 后两条同时变红,已实测)。
 *
 * <p>⚠️ 每次运行拿唯一 UUID scratch 库,结束 DROP 后回读 information_schema 确认为 0。
 */
class PredictLateBetRaceMySqlIT {

    private static final String SUITE = "predict_race_" + UUID.randomUUID().toString().replace("-", "");
    private static final long NODE_ID = 29L;
    private static final long TOPIC_ID = 19L;
    private static final long ACTIVITY_ID = 9L;
    private static final long TEMPLATE_ID = 39L;
    private static final long SESSION_ID = 51L;
    private static final long MEMBER_ID = 7L;
    private static final long OPERATOR_ID = 100L;
    /** 2026-04-01 07:33:20 +08 —— closeAtHour=21 之前,截止闸不该是拒绝理由。 */
    private static final long NOW = 1_775_000_000_000L;
    private static final java.sql.Date PLAY_DAY = java.sql.Date.valueOf("2026-04-01");

    private static SqlSessionFactory factory;
    private static String database;
    private static String adminUrl;

    @BeforeAll
    static void start() throws Exception {
        assertEquals("127.0.0.1", MybatisItDatabase.host(), "只允许显式本机 scratch");
        adminUrl = "jdbc:mysql://127.0.0.1:" + MybatisItDatabase.port()
                + "/?useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select version()")) {
            assertTrue(rs.next());
            String version = rs.getString(1);
            assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(version), "需要 MySQL >= 8,不能把 H2/别的版本当作通过,实际 " + version);
        }
        String url = MybatisItDatabase.url(SUITE);
        database = MybatisItDatabase.databaseName(SUITE);
        Configuration cfg = new Configuration();
        cfg.setEnvironment(new Environment("predict-local", new JdbcTransactionFactory(),
                new PooledDataSource("com.mysql.cj.jdbc.Driver", url,
                        MybatisItDatabase.user(), MybatisItDatabase.password())));
        cfg.getTypeAliasRegistry().registerAlias("PlayAttemptLog", PlayAttemptLog.class);
        for (String name : Arrays.asList("GameRuntimeSessionMapper", "PlayAttemptLogMapper", "PlayPredictMapper")) {
            String path = "mapper/business/" + name + ".xml";
            try (InputStream in = PredictLateBetRaceMySqlIT.class.getClassLoader().getResourceAsStream(path)) {
                new XMLMapperBuilder(in, cfg, path, cfg.getSqlFragments()).parse();
            }
        }
        factory = new SqlSessionFactoryBuilder().build(cfg);
        try (SqlSession session = factory.openSession(true)) {
            createSchema(session.getConnection());
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (database == null) return;
        assertTrue(database.matches("chengyin_mybatis_predict_race_[a-z0-9_]+"), "只许 DROP 本套 scratch");
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = c.createStatement()) {
            st.execute("drop database `" + database + "`");
            try (ResultSet rs = st.executeQuery(
                    "select count(*) from information_schema.schemata where schema_name = '" + database + "'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "scratch 清理必须回读确认");
            }
        }
        System.out.println("Cleaned unique MySQL 8 scratch: " + database);
    }

    @BeforeEach
    void reset() throws Exception {
        try (SqlSession s = factory.openSession(true); Statement st = s.getConnection().createStatement()) {
            st.execute("delete from play_predict_bet");
            st.execute("delete from play_predict_result");
            st.execute("delete from play_attempt_log");
            st.execute("delete from game_runtime_session");
            st.execute(runningSession());
        }
    }

    /**
     * 方向一:下注事务持锁时,结算的条件 UPDATE 必须等它提交;提交后结算扫描必须看到这一注。
     * 旧实现不持任何轮次行锁 —— 这条 UPDATE 当场返回 0 行,这一注永远没人发奖。
     */
    @Test
    void settleWaitsForBetThenScansTheCommittedBet() throws Exception {
        // 轮次已开(先到的人开的),这一注是后来押的:此时挡住结算的只能是对轮次行的
        // 锁定读 —— 下注人自己没有插入动作,不会靠 insert 的隐式锁"蹭"到互斥。
        seedRound(PlayPredictResult.SETTLE_PENDING);
        CountDownLatch betWritten = new CountDownLatch(1);
        CountDownLatch releaseBet = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (SqlSession betSession = factory.openSession(false)) {
            AdvancedGameRuntimeServiceImpl service = service(betSession,
                    new RacingPredictMapper(betSession.getMapper(PlayPredictMapper.class),
                            null, () -> {
                                betWritten.countDown();
                                await(releaseBet);
                            }));
            Future<Map<String, Object>> bet = pool.submit(() ->
                    service.act(MEMBER_ID, SESSION_ID, 0, "race-settle-first", "SUBMIT_PREDICT", payload("b")));

            boolean wroteBet = betWritten.await(5, TimeUnit.SECONDS);
            if (!wroteBet && bet.isDone()) bet.get(5, TimeUnit.SECONDS);
            assertTrue(wroteBet, "下注必须真的走到写注那一步");

            ExecutorService settlePool = Executors.newSingleThreadExecutor();
            try (SqlSession settleSession = factory.openSession(true)) {
                Future<Integer> settle = settlePool.submit(() -> settleSession.getMapper(PlayPredictMapper.class)
                        .settleRound(NODE_ID, PLAY_DAY, "a", PlayPredictResult.SETTLE_DONE, OPERATOR_ID, new Date(NOW)));
                try {
                    assertThrows(TimeoutException.class, () -> settle.get(300, TimeUnit.MILLISECONDS),
                            "结算必须被下注事务的轮次行锁挡住 —— 否则它会漏掉这一注,reward_status 永远是 0");
                } finally {
                    releaseBet.countDown();
                }
                bet.get(5, TimeUnit.SECONDS);
                betSession.commit();
                assertEquals(1, settle.get(5, TimeUnit.SECONDS), "下注提交后结算必须成功");
                List<PlayPredictBet> bets =
                        settleSession.getMapper(PlayPredictMapper.class).selectBetsForRound(NODE_ID, PLAY_DAY);
                assertEquals(1, bets.size(), "下注先提交时,结算的扫描必须包含这一注");
                assertEquals("b", bets.get(0).getOptionKey());
            } finally {
                settlePool.shutdownNow();
            }
        } finally {
            releaseBet.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 方向二:结算已经提交后,带旧快照的迟到下注必须被拒,库里不能留下孤儿注。
     * 旧实现的「请求前快照读」读到的是下注事务开始时的旧版本 —— 拒不掉。
     */
    @Test
    void lateBetAfterCommittedSettleIsRejectedWithoutOrphanBet() throws Exception {
        seedRound(PlayPredictResult.SETTLE_PENDING);
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch settled = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (SqlSession betSession = factory.openSession(false)) {
            AdvancedGameRuntimeServiceImpl service = service(betSession,
                    new RacingPredictMapper(betSession.getMapper(PlayPredictMapper.class), () -> {
                        checked.countDown();
                        await(settled);
                    }, null));
            Future<Map<String, Object>> bet = pool.submit(() ->
                    service.act(MEMBER_ID, SESSION_ID, 0, "race-bet-late", "SUBMIT_PREDICT", payload("b")));

            boolean checkedBet = checked.await(5, TimeUnit.SECONDS);
            if (!checkedBet && bet.isDone()) bet.get(5, TimeUnit.SECONDS);
            assertTrue(checkedBet, "下注必须完成第一次状态查读");
            try (SqlSession settleSession = factory.openSession(true)) {
                assertEquals(1, settleSession.getMapper(PlayPredictMapper.class)
                        .settleRound(NODE_ID, PLAY_DAY, "a", PlayPredictResult.SETTLE_DONE, OPERATOR_ID, new Date(NOW)),
                        "夹具轮次必须真的被结算");
            }
            settled.countDown();

            ExecutionException refused = assertThrows(ExecutionException.class, () -> bet.get(5, TimeUnit.SECONDS),
                    "商家已结算的轮次不能再收这一注");
            assertTrue(refused.getCause() instanceof IllegalStateException,
                    "拒绝理由应是「这一轮已经结束」,实际:" + refused.getCause());
            betSession.rollback();
        } finally {
            settled.countDown();
            pool.shutdownNow();
        }
        assertEquals(0, betCount(), "被拒的迟到下注不能留在库里 —— 它的 reward_status 永远没人扫");
        assertEquals(PlayPredictResult.SETTLE_DONE, round().getSettleStatus(), "结算状态不能被下注回滚");
    }

    // ── 夹具 ──

    private static Map<String, Object> payload(String optionKey) {
        return Collections.singletonMap("optionKey", optionKey);
    }

    private AdvancedGameRuntimeServiceImpl service(SqlSession session, PlayPredictMapper predictMapper) {
        AdvancedGameRuntimeServiceImpl service = new AdvancedGameRuntimeServiceImpl();
        ReflectionTestUtils.setField(service, "transactionManager", new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(session.getConnection(), true)));
        ReflectionTestUtils.setField(service, "sessionMapper", session.getMapper(GameRuntimeSessionMapper.class));
        ReflectionTestUtils.setField(service, "attemptMapper", session.getMapper(PlayAttemptLogMapper.class));
        ReflectionTestUtils.setField(service, "predictMapper", predictMapper);
        // 本套只验证轮次真实行锁竞态；活动时间窗使用合法固定夹具。
        com.chengyinhub.business.mapper.CmsActivityMapper activities =
                org.mockito.Mockito.mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTopicId(TOPIC_ID);
        org.mockito.Mockito.when(activities.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        ReflectionTestUtils.setField(service, "activityMapper", activities);
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(NOW));
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);
        // C包新动作按 activity→registration 当前锁读；本套只验轮次行锁竞态，用C包非资格夹具(均为 mock，不加真库锁)。
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
        return service;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "测试握手超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int betCount() throws Exception {
        try (SqlSession s = factory.openSession(true)) {
            return s.getMapper(PlayPredictMapper.class).selectBetsForRound(NODE_ID, PLAY_DAY).size();
        }
    }

    private PlayPredictResult round() throws Exception {
        try (SqlSession s = factory.openSession(true)) {
            return s.getMapper(PlayPredictMapper.class).selectResult(NODE_ID, PLAY_DAY);
        }
    }

    private void seedRound(int settleStatus) throws Exception {
        try (SqlSession s = factory.openSession(true); Statement st = s.getConnection().createStatement()) {
            st.execute("insert into play_predict_result(node_id, play_day, settle_status, create_time) values("
                    + NODE_ID + ", '" + PLAY_DAY + "', " + settleStatus + ", now())");
        }
    }

    private static String runningSession() {
        return "insert into game_runtime_session(id, activity_id, topic_id, node_id, template_id, owner_type, "
                + "owner_id, config_snapshot_json, state_json, status, score, completed_units, version, "
                + "started_at, deadline_at, create_time, update_time) values("
                + SESSION_ID + ", " + ACTIVITY_ID + ", " + TOPIC_ID + ", " + NODE_ID + ", " + TEMPLATE_ID + ", 1, "
                + MEMBER_ID + ", '" + config().replace("'", "''") + "', '{}', " + GameRuntimeSession.STATUS_RUNNING
                + ", 0, 0, 0, '" + shTime(0) + "', '" + shTime(TimeUnit.HOURS.toMillis(1)) + "', '"
                + shTime(0) + "', '" + shTime(0) + "')";
    }

    /** 连接串固定 serverTimezone=GMT+8,夹具时间戳也按 +08 写,免得时钟被判成过期。 */
    private static String shTime(long offsetMillis) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date(NOW + offsetMillis));
    }

    private static String config() {
        return "{\"schemaVersion\":1,\"predict\":{\"enabled\":true,\"question\":\"今天哪款卖得最多?\","
                + "\"closeAtHour\":21,\"xp\":30,\"options\":["
                + "{\"key\":\"a\",\"label\":\"冰美式\"},{\"key\":\"b\",\"label\":\"燕麦拿铁\"}]}}";
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("create table play_team_member ("
                    + "id bigint not null auto_increment primary key, team_id bigint not null,"
                    + "member_id bigint not null, status tinyint not null default 1,"
                    + "key idx_team_member (team_id, member_id, status)"
                    + ") engine=InnoDB default charset=utf8mb4");
            st.execute("create table topic_route_session (id bigint primary key, member_id bigint, "
                    + "topic_id bigint, activity_id bigint, play_instance_id varchar(64)) engine=InnoDB");
            st.execute("create table game_runtime_session ("
                    + "id bigint not null auto_increment primary key,"
                    + "play_instance_id varchar(64) not null default 'default', "
                    + "activity_id bigint not null default 0, topic_id bigint not null, node_id bigint not null,"
                    + "template_id bigint not null, owner_type tinyint not null, owner_id bigint not null,"
                    + "config_snapshot_json json not null, state_json json not null,"
                    + "status tinyint not null default 0, score int not null default 0,"
                    + "completed_units int not null default 0, version int not null default 0,"
                    + "started_at datetime(3) not null, deadline_at datetime(3) null,"
                    + "completed_at datetime(3) null, create_time datetime(3) not null, update_time datetime(3) not null,"
                    + "unique key uk_runtime_owner (activity_id, topic_id, node_id, owner_type, owner_id)"
                    + ") engine=InnoDB default charset=utf8mb4");
            st.execute("create table play_attempt_log ("
                    + "id bigint not null auto_increment primary key, member_id bigint null,"
                    + "topic_id bigint null, node_id bigint null, activity_id bigint null,"
                    + "attempt_type tinyint null, result tinyint null, wrong_answer varchar(120) null,"
                    + "duration_s int null, result_json json null,"
                    + "idempotency_key varchar(128) null, created_at datetime null,"
                    + "request_fingerprint char(64) character set ascii collate ascii_bin null,"
                    + "unique key uk_play_attempt_idempotency (idempotency_key)"
                    + ") engine=InnoDB default charset=utf8mb4");
            st.execute("create table play_predict_bet ("
                    + "id bigint not null auto_increment primary key, node_id bigint not null,"
                    + "topic_id bigint not null, activity_id bigint not null default 0, play_day date not null,"
                    + "member_id bigint not null, option_key varchar(64) not null,"
                    + "reward_status tinyint not null default 0, create_time datetime not null, update_time datetime null,"
                    + "unique key uk_round_member (node_id, play_day, member_id),"
                    + "key idx_round_option (node_id, play_day, option_key)"
                    + ") engine=InnoDB default charset=utf8mb4");
            st.execute("create table play_predict_result ("
                    + "id bigint not null auto_increment primary key, node_id bigint not null, play_day date not null,"
                    + "settled_option varchar(64) null, settle_status tinyint not null default 0,"
                    + "settled_by bigint null, settled_at datetime null, create_time datetime not null,"
                    + "unique key uk_round (node_id, play_day)"
                    + ") engine=InnoDB default charset=utf8mb4");
        }
    }

    /** 只在真 mapper 外层挂握手钩子;所有 SQL 照常打在真库上。 */
    private static final class RacingPredictMapper implements PlayPredictMapper {

        private final PlayPredictMapper real;
        private final Runnable afterSelectResult;
        private final Runnable afterInsertBet;

        private RacingPredictMapper(PlayPredictMapper real, Runnable afterSelectResult, Runnable afterInsertBet) {
            this.real = real;
            this.afterSelectResult = afterSelectResult;
            this.afterInsertBet = afterInsertBet;
        }

        @Override
        public int insertBet(PlayPredictBet bet) {
            int rows = real.insertBet(bet);
            if (afterInsertBet != null) afterInsertBet.run();
            return rows;
        }

        @Override
        public PlayPredictBet selectBet(Long nodeId, Date playDay, Long memberId) {
            return real.selectBet(nodeId, playDay, memberId);
        }

        @Override
        public List<PlayPredictBet> selectBetsForRound(Long nodeId, Date playDay) {
            return real.selectBetsForRound(nodeId, playDay);
        }

        @Override
        public int markBetSettled(Long id, Integer rewardStatus, Date updateTime) {
            return real.markBetSettled(id, rewardStatus, updateTime);
        }

        @Override
        public int insertResult(PlayPredictResult result) {
            return real.insertResult(result);
        }

        @Override
        public PlayPredictResult selectResult(Long nodeId, Date playDay) {
            PlayPredictResult result = real.selectResult(nodeId, playDay);
            if (afterSelectResult != null) afterSelectResult.run();
            return result;
        }

        @Override
        public PlayPredictResult selectResultForUpdate(Long nodeId, Date playDay) {
            return real.selectResultForUpdate(nodeId, playDay);
        }

        @Override
        public int settleRound(Long nodeId, Date playDay, String settledOption, Integer settleStatus,
                Long settledBy, Date settledAt) {
            return real.settleRound(nodeId, playDay, settledOption, settleStatus, settledBy, settledAt);
        }

        @Override
        public List<PlayPredictResult> selectPendingRounds(List<Long> nodeIds) {
            return real.selectPendingRounds(nodeIds);
        }

        @Override
        public List<PlayPredictResult> selectStaleRounds(Date beforeDay, Integer limit) {
            return real.selectStaleRounds(beforeDay, limit);
        }
    }
}
