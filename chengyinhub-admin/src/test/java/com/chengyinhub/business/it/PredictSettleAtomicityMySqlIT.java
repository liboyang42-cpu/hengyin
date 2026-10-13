package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.MemberGrowth;
import com.chengyinhub.business.domain.PlayPredictBet;
import com.chengyinhub.business.domain.PlayPredictResult;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.MemberGrowthMapper;
import com.chengyinhub.business.mapper.PlayPredictMapper;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.IPlayPredictService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.GrowthServiceImpl;
import com.chengyinhub.business.service.impl.PlayPredictServiceImpl;
import com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 竞猜结算的原子性:真 MySQL 8 上「结算中途中断」只有两种结局 —— 全成、或全不成。
 *
 * <p>旧实现 {@code PlayPredictServiceImpl.settle} 不是事务:轮次 CAS 提交后,
 * 逐注落状态 + 发成长值/券是各自提交的。进程/连接在中间断掉就留下**半结算孤儿注** ——
 * 轮次已标结算,但有注停在 {@code reward_status=0};而重跑会被 {@code settleRound} 的
 * CAS 拒掉,商家待办也不再列这一轮:既没人发奖,也没有任何信号。
 *
 * <p>本 IT 用**真 Spring 事务**({@code ProxyFactory} + {@code TransactionInterceptor} +
 * {@code AnnotationTransactionAttributeSource}),因此它验证的正是
 * {@code PlayPredictServiceImpl.settle} 上那行 {@code @Transactional} 本身 ——
 * 注解被摘掉时,下面第一条用例会退回负控现场(局部提交)而变红。
 *
 * <p>两个用例:
 * <ul>
 *   <li>{@code midSettleFailureRollsBack...}:中断后整轮回滚(轮次仍待结算、三注仍是 0、
 *       没有成长值/券),重跑后每注恰好发一次奖,再重跑被 CAS 拒绝且不再发奖;</li>
 *   <li>{@code withoutTransactionBoundary...}:同一个中断、同一份代码,只把事务边界去掉,
 *       现场立刻变成「轮次已结算、中奖注状态已改但券没发」的半结算 —— 负控。</li>
 * </ul>
 *
 * <p>⚠️ 每次运行拿唯一 UUID scratch 库,结束 DROP 后回读 information_schema 确认为 0。
 */
class PredictSettleAtomicityMySqlIT {

    private static final String SUITE = "settle_tx_" + UUID.randomUUID().toString().replace("-", "");
    private static final long NODE_ID = 29L;
    private static final long TEMPLATE_ID = 39L;
    private static final long OPERATOR_ID = 100L;
    private static final long WINNER_1 = 11L;
    private static final long WINNER_2 = 12L;
    private static final long LOSER = 13L;
    private static final long COUPON_ID = 501L;
    private static final int WIN_XP = 30;
    private static final java.sql.Date PLAY_DAY = java.sql.Date.valueOf("2026-04-01");
    /** 第几次发券时注入中断(按 member 而不是调用序号 —— 扫描顺序不保证,按人不按次序)。 */
    private static final long SABOTAGE_MEMBER = WINNER_2;

    private static final String ADVANCED_CONFIG =
            "{\"schemaVersion\":1,\"predict\":{\"enabled\":true,\"question\":\"今天哪款卖得最多?\","
                    + "\"closeAtHour\":21,\"options\":["
                    + "{\"key\":\"a\",\"label\":\"冰美式\"},{\"key\":\"b\",\"label\":\"燕麦拿铁\"}]}}";
    private static final String REWARD_RULES =
            "[{\"outcomeCode\":\"PREDICT_WON\",\"couponId\":" + COUPON_ID + ",\"xp\":" + WIN_XP + "}]";
    private static final String COUPON_SEED =
            "insert into sms_coupon(id, name, coupon_type, platform, amount, min_point, use_type,"
                    + " publish_count, use_count, receive_count, scope_type, member_id, status, del_flag, create_time)"
                    + " values(" + COUPON_ID + ", '竞猜中奖券', 0, 0, 0.00, 0.00, 0, 0, 0, 0, 0, 0, 1, 0, now())";

    private static SqlSessionTemplate sql;
    private static PooledDataSource dataSource;
    private static DataSourceTransactionManager transactionManager;
    /** 走 @Transactional 代理:AnnotationTransactionAttributeSource 从实现类方法上读事务属性。 */
    private static IPlayPredictService transactionalService;
    /** 同一个 target,不套事务拦截器 —— 复现旧实现「分步各自提交」的形状。 */
    private static PlayPredictServiceImpl rawTarget;
    private static String database;
    private static String jdbcUrl;
    private static String adminUrl;
    private static volatile boolean sabotage;

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
        jdbcUrl = MybatisItDatabase.url(SUITE);
        database = MybatisItDatabase.databaseName(SUITE);
        dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        Configuration cfg = new Configuration();
        cfg.setEnvironment(new Environment("predict-settle-tx", new SpringManagedTransactionFactory(), dataSource));
        cfg.getTypeAliasRegistry().registerAlias("MemberGrowth", MemberGrowth.class);
        cfg.getTypeAliasRegistry().registerAlias("SmsCoupon", SmsCoupon.class);
        cfg.getTypeAliasRegistry().registerAlias("SmsCouponHistory", SmsCouponHistory.class);
        for (String name : Arrays.asList("PlayPredictMapper", "MemberGrowthMapper",
                "SmsCouponMapper", "SmsCouponHistoryMapper")) {
            String path = "mapper/business/" + name + ".xml";
            try (InputStream in = PredictSettleAtomicityMySqlIT.class.getClassLoader().getResourceAsStream(path)) {
                new XMLMapperBuilder(in, cfg, path, cfg.getSqlFragments()).parse();
            }
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        sql = new SqlSessionTemplate(factory);
        try (Connection c = DriverManager.getConnection(jdbcUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = c.createStatement()) {
            createSchema(st);
        }
        wireServices();
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (dataSource != null) dataSource.forceCloseAll();
        if (database == null) return;
        assertTrue(database.matches("chengyin_mybatis_settle_tx_[a-z0-9_]+"), "只许 DROP 本套 scratch");
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
        sabotage = false;
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("delete from play_predict_bet");
            st.execute("delete from play_predict_result");
            st.execute("delete from member_growth");
            st.execute("delete from sms_coupon_history");
            st.execute("delete from sms_coupon");
            st.execute(COUPON_SEED);
        }
    }

    /**
     * 正例:结算中途中断必须整轮回滚,重跑每注恰好发一次。
     * 把实现上的 {@code @Transactional} 摘掉,本用例在「失败后三注必须仍是 0」处变红。
     */
    @Test
    void midSettleFailureRollsBackEverythingThenRetryRewardsEachBetExactlyOnce() throws Exception {
        seedRound();
        seedBet(WINNER_1, "a");
        seedBet(WINNER_2, "a");
        seedBet(LOSER, "b");

        sabotage = true;
        assertThrows(IllegalStateException.class,
                () -> transactionalService.settle(NODE_ID, PLAY_DAY, "a", OPERATOR_ID),
                "为 winner2 发券时注入的中断必须抛出来,否则这一轮根本没有失败");

        assertEquals(PlayPredictResult.SETTLE_PENDING, intOf(
                "select settle_status from play_predict_result where node_id = " + NODE_ID),
                "中断后轮次必须回滚到待结算");
        assertEquals(0, intOf("select count(*) from play_predict_bet where reward_status <> 0"),
                "三注都必须原地回滚,不能留下状态已改的注");
        assertEquals(0, intOf("select count(*) from member_growth"),
                "成长值写入必须随事务回滚");
        assertEquals(0, intOf("select count(*) from sms_coupon_history"),
                "券发放必须随事务回滚");
        assertEquals(0, intOf("select receive_count from sms_coupon where id = " + COUPON_ID),
                "发券计数必须随事务回滚");

        sabotage = false;
        Map<String, Object> summary = transactionalService.settle(NODE_ID, PLAY_DAY, "a", OPERATOR_ID);
        assertEquals(2, summary.get("winners"));
        assertEquals(2, summary.get("couponsGranted"));
        assertEquals(PlayPredictResult.SETTLE_DONE, intOf(
                "select settle_status from play_predict_result where node_id = " + NODE_ID));
        assertEquals(PlayPredictBet.REWARD_WON, rewardOf(WINNER_1));
        assertEquals(PlayPredictBet.REWARD_WON, rewardOf(WINNER_2));
        assertEquals(PlayPredictBet.REWARD_LOST, rewardOf(LOSER));
        assertEquals(WIN_XP, intOf("select exp_value from member_growth where member_id = " + WINNER_1),
                "重跑后每注恰好发一次奖:成长值应是 " + WIN_XP + ",不是累加两次");
        assertEquals(WIN_XP, intOf("select exp_value from member_growth where member_id = " + WINNER_2),
                "重跑后每注恰好发一次奖:成长值应是 " + WIN_XP + ",不是累加两次");
        assertEquals(1, intOf("select count(*) from sms_coupon_history where member_id = " + WINNER_1));
        assertEquals(1, intOf("select count(*) from sms_coupon_history where member_id = " + WINNER_2));
        assertEquals(0, intOf("select count(*) from sms_coupon_history where member_id = " + LOSER));
        assertEquals(2, intOf("select receive_count from sms_coupon where id = " + COUPON_ID));

        assertThrows(IllegalStateException.class,
                () -> transactionalService.settle(NODE_ID, PLAY_DAY, "a", OPERATOR_ID),
                "已结算的轮次重跑必须被 CAS 拒绝");
        assertEquals(WIN_XP, intOf("select exp_value from member_growth where member_id = " + WINNER_1),
                "被拒的重跑不得再发一次奖");
        assertEquals(1, intOf("select count(*) from sms_coupon_history where member_id = " + WINNER_2),
                "被拒的重跑不得再发一张券");
    }

    /**
     * 负控:同一个中断、同一份实现,只把事务边界去掉 —— 必须留下「轮次已结算、
     * 中奖注状态已改但券没发」的半结算现场,且重跑救不回来。
     * 这条要是变绿,说明中断根本没有留下破坏,上面的正例也就不能证明任何事。
     */
    @Test
    void withoutTransactionBoundaryTheSameInterruptionLeavesHalfSettledOrphans() throws Exception {
        seedRound();
        seedBet(WINNER_1, "a");
        seedBet(WINNER_2, "a");
        seedBet(LOSER, "b");

        sabotage = true;
        assertThrows(IllegalStateException.class,
                () -> rawTarget.settle(NODE_ID, PLAY_DAY, "a", OPERATOR_ID));

        assertEquals(PlayPredictResult.SETTLE_DONE, intOf(
                "select settle_status from play_predict_result where node_id = " + NODE_ID),
                "没有事务边界时,轮次 CAS 早已提交");
        assertEquals(PlayPredictBet.REWARD_WON, rewardOf(SABOTAGE_MEMBER),
                "这一注在中奖后、发券前被中断,状态已经改成中奖");
        assertEquals(0, intOf("select count(*) from sms_coupon_history where member_id = " + SABOTAGE_MEMBER),
                "半结算现场:状态说中了奖,券根本没发");
        int pendingBets = intOf("select count(*) from play_predict_bet where reward_status = 0");
        int coupons = intOf("select count(*) from sms_coupon_history");
        assertTrue(pendingBets > 0 || coupons < 2,
                "没有事务边界时,这一轮必须留下不一致现场(还有注没人结/有人没拿到奖),"
                        + "实际 pendingBets=" + pendingBets + " coupons=" + coupons);

        assertThrows(IllegalStateException.class,
                () -> rawTarget.settle(NODE_ID, PLAY_DAY, "a", OPERATOR_ID),
                "轮次已标结算,重跑只会撞上 CAS —— 半结算现场没人能修");
        assertEquals(PlayPredictBet.REWARD_WON, rewardOf(SABOTAGE_MEMBER));
        assertEquals(0, intOf("select count(*) from sms_coupon_history where member_id = " + SABOTAGE_MEMBER),
                "重跑救不回来:这一注永远停在「中了奖但没拿到奖」");
    }

    // ── 夹具 ──

    private static void wireServices() {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTemplateId(TEMPLATE_ID);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);

        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE_ID);
        template.setAdvancedConfigJson(ADVANCED_CONFIG);
        template.setRewardRulesJson(REWARD_RULES);
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template);

        GrowthServiceImpl growthService = new GrowthServiceImpl();
        ReflectionTestUtils.setField(growthService, "memberGrowthMapper", sql.getMapper(MemberGrowthMapper.class));

        // 发券走真实实现与真表；现契约仅落动态核销凭证，已不生成静态二维码。
        SmsCouponHistoryServiceImpl couponReal = new SmsCouponHistoryServiceImpl();
        ReflectionTestUtils.setField(couponReal, "smsCouponHistoryMapper",
                sql.getMapper(SmsCouponHistoryMapper.class));
        ReflectionTestUtils.setField(couponReal, "smsCouponMapper", sql.getMapper(SmsCouponMapper.class));

        ISmsCouponHistoryService couponGate = mock(ISmsCouponHistoryService.class);
        when(couponGate.grantCouponById(anyLong(), anyLong())).thenAnswer(invocation -> {
            Long memberId = invocation.getArgument(0);
            if (sabotage && SABOTAGE_MEMBER == memberId) {
                throw new IllegalStateException("模拟结算中途进程中断");
            }
            return couponReal.grantCouponById(memberId, invocation.getArgument(1));
        });

        PlayPredictServiceImpl target = new PlayPredictServiceImpl();
        ReflectionTestUtils.setField(target, "predictMapper", sql.getMapper(PlayPredictMapper.class));
        ReflectionTestUtils.setField(target, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(target, "templateMapper", templateMapper);
        ReflectionTestUtils.setField(target, "growthService", growthService);
        ReflectionTestUtils.setField(target, "couponHistoryService", couponGate);
        rawTarget = target;

        transactionManager = new DataSourceTransactionManager(dataSource);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(IPlayPredictService.class);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        transactionalService = (IPlayPredictService) proxyFactory.getProxy();
    }

    private static void createSchema(Statement st) throws SQLException {
        // member_growth / sms_coupon / sms_coupon_history 的 DDL 照抄 sql/base_schema_20260818.sql
        // (生产真源),逐列保留 —— selectSmsCouponVo 会整行取 coupon,缺列在这张表上就是假绿。
        st.execute("create table member_growth ("
                + "id bigint not null auto_increment primary key, member_id bigint not null,"
                + "level_no int not null default 1, exp_value int not null default 0,"
                + "last_active_time datetime null, create_time datetime null, update_time datetime null,"
                + "unique key uk_member_growth_member (member_id)"
                + ") engine=InnoDB default charset=utf8mb4");
        st.execute("create table sms_coupon ("
                + "id bigint not null auto_increment primary key, name varchar(100) null,"
                + "coupon_type int null, platform int null, amount decimal(10,2) null,"
                + "min_point decimal(10,2) null, start_time datetime null, end_time datetime null,"
                + "use_type int null, publish_count int null, use_count int null, receive_count int null,"
                + "enable_time datetime null, code varchar(64) null, scope_type int default 0,"
                + "member_id bigint default 0, merchant_id bigint null, status int null,"
                + "del_flag tinyint default 0, description varchar(500) null,"
                + "create_by varchar(64) default '', create_time datetime null,"
                + "update_by varchar(64) default '', update_time datetime null,"
                + "publish_minute varchar(16) generated always as (date_format(create_time, '%Y%m%d%H%i')) stored,"
                + "per_member_limit int null,"
                + "unique key uk_coupon_dedup (member_id, coupon_type, publish_minute)"
                + ") engine=InnoDB default charset=utf8mb4");
        st.execute("create table sms_coupon_history ("
                + "id bigint not null auto_increment primary key, coupon_id bigint not null,"
                + "member_id bigint not null, coupon_code varchar(64) not null,"
                + "qrcode_url varchar(255) null, get_type int default 0, get_time datetime null,"
                + "use_status int default 0, use_time datetime null, order_id bigint null,"
                + "order_sn varchar(64) null, start_time datetime null, end_time datetime null,"
                + "note varchar(500) null, del_flag tinyint default 0, create_by varchar(64) null,"
                + "create_time datetime null, update_by varchar(64) null, update_time datetime null"
                + ") engine=InnoDB default charset=utf8mb4");
        // 竞猜两张表与 PredictLateBetRaceMySqlIT 同一份手写 DDL(生产列 + 同一组索引)。
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

    private static void seedRound() throws Exception {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("insert into play_predict_result(node_id, play_day, settle_status, create_time) values("
                    + NODE_ID + ", '" + PLAY_DAY + "', " + PlayPredictResult.SETTLE_PENDING + ", now())");
        }
    }

    private static void seedBet(long memberId, String optionKey) throws Exception {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("insert into play_predict_bet(node_id, topic_id, activity_id, play_day, member_id,"
                    + " option_key, reward_status, create_time) values("
                    + NODE_ID + ", 19, 9, '" + PLAY_DAY + "', " + memberId + ", '" + optionKey + "', "
                    + PlayPredictBet.REWARD_PENDING + ", now())");
        }
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(jdbcUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
    }

    private static int intOf(String sql) throws Exception {
        try (Connection c = open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "断言查询必须有结果:" + sql);
            return rs.getInt(1);
        }
    }

    private static int rewardOf(long memberId) throws Exception {
        return intOf("select reward_status from play_predict_bet where member_id = " + memberId);
    }
}
