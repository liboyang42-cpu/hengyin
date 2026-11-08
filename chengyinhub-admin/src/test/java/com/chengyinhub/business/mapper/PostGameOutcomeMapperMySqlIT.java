package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.vo.PostGameOutcomeReportVO;
import com.chengyinhub.business.it.ItSchema;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.service.impl.PostGameOutcomeReportServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完局维护与有效复报：**真实 MySQL + 真实 MyBatis** 执行 {@code PostGameOutcomeMapper.xml} 的全部新增 SQL。
 *
 * <p>为什么不能只靠 mock 单测：mock 只证明「服务拿到某形状的行会怎么算」，
 * 证明不了 SQL 本身。本卡交付过的两个真实缺陷都藏在这一层：
 * <ol>
 *   <li>把 {@code game_runtime_session}（节点小游戏）当成整局完成 —— mock 里我「喂」给服务什么行，
 *       服务就按什么算，SQL 到底 union 了谁完全没被验证；</li>
 *   <li>退款水位用了全表 {@code max(update_time)} —— 只有在真表里同时存在 registration 与 order
 *       两种来源的退款行时，宽口径与窄口径才会露出差别。</li>
 * </ol>
 *
 * <p>建表不走自造 schema：{@code it-schema/post-game-outcome-it-schema.sql} 逐字摘录自
 * {@code sql/base_schema_20260818.sql} 与三份仓库迁移，列名/类型/可空性/唯一键/CHECK 与生产一致，
 * 否则「列写错也能过」的宽松表会把真库上的失败藏起来。
 *
 * <p>刻意保留 {@code game_runtime_session} 表并往里插 status=1 的行 ——
 * 要证明的是「即使节点小游戏已完成，整局完成仍是 0」，而不是「表不在所以查不到」。
 *
 * <p>设备/权限：本套只在 {@link MybatisItDatabase} 分配的
 * {@code chengyin_mybatis_post_game_outcome*} 一次性 scratch 库里跑，建库与删库都由它负责；
 * 类末尾的 DROP 带库名前缀断言 + 回读确认，绝不碰别的库。
 *
 * <p>⚠️ surefire 默认只收 {@code *Test.java}，本 {@code *IT} 需显式 {@code -Dtest} 点名执行。
 */
class PostGameOutcomeMapperMySqlIT {

    private static final String SUITE = "post_game_outcome";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    // 六类结果的 metricCode：服务实现里是包内常量，这里用字面量并在用例中断言集合完整。
    private static final String COMPLETION = "OFFICIAL_COMPLETION";
    private static final String REWARD = "REWARD_ISSUANCE";
    private static final String PRIVATE_PHOTO = "PRIVATE_PHOTO";
    private static final String PUBLIC_SHARE = "PUBLIC_SHARE";
    private static final String RECO_CLICK = "RECOMMENDATION_CLICK";
    private static final String SECOND_REGISTRATION = "SECOND_VALID_REGISTRATION";
    private static final String CLIENT_OBSERVED = "CLIENT_OBSERVED_EVENT";

    private static final long TOPIC = 9001L;
    private static final long NODE = 5001L;
    private static final long TEMPLATE = 7001L;
    private static final long GUIDELINE_VERSION = 1L;

    /** 子表优先的删除顺序：community_post 有 FK 指向 community_guideline_version。 */
    private static final List<String> CHILD_FIRST_TABLES = Arrays.asList(
            "community_post_media", "community_post", "community_guideline_version",
            "biz_event_log", "game_runtime_session", "topic_route_session",
            "player_badge", "topic_completion_xp_award",
            "cms_topic_completion_coupon_reward_log", "roam_poi_reward_log",
            "refund_application", "cms_registration", "roam_session");

    /** 已成熟的 cohort：回溯 60 天，30 日窗必然已走完。 */
    private static LocalDate matureCohort;
    private static LocalDate nowDate;

    private static PooledDataSource dataSource;
    private static SqlSessionFactory factory;
    private static String database;
    private static String adminUrl;

    /** 每个用例一条 autocommit 会话：fixture 插入与 mapper 读取走同一连接，结束时关闭避免池泄漏。 */
    private SqlSession session;

    @BeforeAll
    static void start() throws Exception {
        adminUrl = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                + "/?useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(),
                MybatisItDatabase.password());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select version()")) {
            assertTrue(rs.next(), "必须能读到 MySQL 版本");
            // 报表 SQL 用了 CTE-free 的 union/子查询与 date()，对 8.x 是稳的；换 H2/5.7 语义不同，必须拒绝假绿。
            assertTrue(rs.getString(1).startsWith("8.0."),
                    "必须使用 MySQL 8.0,不能把 H2/别的版本当作通过,实际=" + rs.getString(1));
        }

        dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", MybatisItDatabase.url(SUITE),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        database = MybatisItDatabase.databaseName(SUITE);

        Configuration cfg = new Configuration();
        cfg.setEnvironment(new Environment("post-game-outcome", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/PostGameOutcomeMapper.xml";
        try (InputStream in = PostGameOutcomeMapperMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("mapper XML 不在 classpath:" + resource);
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(cfg);

        // 建表只做一次（重复 apply 会撞 community_post -> guideline_version 的 FK）。
        // ★ 先在正确顺序里把本次用到的 13 张表删干净，再交给 ItSchema：
        //   ItSchema.apply 内部的 DROP 循环按**文件顺序**执行，而 CREATE 需要父表先建、
        //   DROP 需要子表先删 —— 同一份顺序不可能同时满足两边。
        //   先由这里按子表优先删一遍，ItSchema 的 DROP IF EXISTS 就全部变成空操作，
        //   只剩它按文件顺序（父表在前）建表。这样既不用弱化 schema（保留 FK/CHECK），
        //   也不用改共享的 ItSchema。
        try (SqlSession session = factory.openSession(true);
             Statement st = session.getConnection().createStatement()) {
            for (String table : CHILD_FIRST_TABLES) {
                st.execute("drop table if exists `" + table + "`");
            }
            ItSchema.apply(session.getConnection(), "it-schema/post-game-outcome-it-schema.sql");
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (dataSource != null) dataSource.forceCloseAll();
        if (database == null || adminUrl == null) return;
        assertTrue(database.matches("chengyin_mybatis_post_game_outcome[a-z0-9_]*"),
                "只许 DROP 本套 scratch 库:" + database);
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(),
                MybatisItDatabase.password());
             Statement st = c.createStatement()) {
            st.execute("drop database `" + database + "`");
            try (ResultSet rs = st.executeQuery(
                    "select count(*) from information_schema.schemata where schema_name = '"
                            + database + "'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "scratch 清理必须回读确认");
            }
        }
    }

    /** 每个用例从空表开始：共用库里的残留数据会让 fail-closed 断言意外满足。 */
    @BeforeEach
    void resetFixture() throws Exception {
        session = factory.openSession(true);
        try (Statement st = session.getConnection().createStatement()) {
            // 先删孩子再删父（community_post_media / community_post / guideline_version 有依赖顺序）。
            for (String table : Arrays.asList("community_post_media", "community_post",
                    "community_guideline_version", "biz_event_log", "game_runtime_session",
                    "topic_route_session", "player_badge", "topic_completion_xp_award",
                    "cms_topic_completion_coupon_reward_log", "roam_poi_reward_log",
                    "refund_application", "cms_registration", "roam_session")) {
                st.execute("delete from " + table);
            }
        }
    }

    @AfterEach
    void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    // ------------------------------------------------------------------
    // cohort：全历史首笔 / 窗口边界 / 父单去重 / 退款到账与 pending
    // ------------------------------------------------------------------

    /**
     * 首笔报名按<b>全历史</b>判定：某人更早就有有效报名时，cohort 当天这笔不算「首笔」，
     * 因此他既不进本 cohort 的分母，也不进分子。
     */
    @Test
    void historicalFirstRegistrationBeforeCohortKeepsMemberOutOfThisCohort() throws Exception {
        LocalDate first = matureCohort.minusDays(10);
        insertPaid(1001L, matureCohort.minusDays(10), 1, "ORD-OLD", "50.00");      // 全历史首笔
        insertPaid(1001L, matureCohort, 2, "ORD-COHORT", "50.00");                  // cohort 当天
        insertPaid(1001L, matureCohort.plusDays(5), 3, "ORD-WINDOW", "50.00");      // 窗内

        PostGameOutcomeReportVO report = matureService().generate(matureCohort, 30);

        assertEquals(0L, report.getCohort().eligibleMembers(),
                "首笔在 cohort 之前 ⇒ 该会员属于更早的 cohort，不进本次分母");
        // 分母为 0 ⇒ 本 cohort 不适用，复报分子无定义（null），不能宣称「0 人复报」。
        // 与单测合同一致；有效分母 > 0 且无人复报仍必须是 0（见 serviceAssemblesFullReportAgainstRealMapper 与
        // secondRegistration* 用例），未成熟仍为 null（见 immatureCohortReportsNullRetentionNotZero）。
        assertNull(report.getCohort().retainedMembers(),
                "不进分母的人也不得进分子；分母为 0 时分子是未定义，不是 0");
        assertTrue(first.isBefore(matureCohort));
    }

    /**
     * 观察窗右开：{@code windowEnd} 当天 00:00 的第二笔不算，前一天 23:59:59 的算。
     *
     * <p>windowEnd = cohort + windowDays + 1 天的 00:00（Asia/Shanghai）。
     */
    @Test
    void windowBoundaryIsHalfOpenAtExclusiveEnd() throws Exception {
        LocalDate windowEndDate = matureCohort.plusDays(31);
        insertPaid(1002L, matureCohort, 11, "ORD-B1", "60.00");
        insertPaid(1002L, windowEndDate, 12, "ORD-B2", "60.00");                 // 正好窗末 ⇒ 不算

        insertPaid(1003L, matureCohort, 13, "ORD-C1", "60.00");
        insertPaid(1003L, windowEndDate.minusDays(1), 14, "ORD-C2", "60.00");    // 窗末前一天 ⇒ 算

        PostGameOutcomeReportVO report = matureService().generate(matureCohort, 30);

        assertEquals(2L, report.getCohort().eligibleMembers());
        assertEquals(Long.valueOf(1), report.getCohort().retainedMembers(),
                "只有窗内那一笔算第二次有效报名（右开区间）");
        assertEquals(0L, report.getCohort().lateFactCount());
    }

    /**
     * 同 {@code parent_order_no} 的多行（同单多席/多票）不是「另一笔报名」：
     * 代购多席不自动等于买家多次报名。
     */
    @Test
    void sameParentOrderDoesNotCountAsSecondRegistration() throws Exception {
        insertPaid(1004L, matureCohort, 21, "ORD-SEAT", "200.00");
        insertPaid(1004L, matureCohort.plusDays(3), 22, "ORD-SEAT", "200.00");
        // 另一个人有真正不同父单，作为对照，证明查询本身没有把分子压成 0。
        insertPaid(1005L, matureCohort, 23, "ORD-D1", "80.00");
        insertPaid(1005L, matureCohort.plusDays(3), 24, "ORD-D2", "80.00");

        PostGameOutcomeReportVO report = matureService().generate(matureCohort, 30);

        assertEquals(2L, report.getCohort().eligibleMembers());
        assertEquals(Long.valueOf(1), report.getCohort().retainedMembers(),
                "同父单的两行不算第二笔；只有不同父单的那位计入");
    }

    /**
     * 退款两种状态必须分开：
     * <ul>
     *   <li>已确认到账（{@code status=1, payout_status in (1,4)}）且累计 ≥ 实付 ⇒ 全退，排除出分母；</li>
     *   <li>{@code status=0} 待审 / {@code payout_status in (2,3,5,6)} 钱未确认 ⇒ 仍进分母，单列 pending。</li>
     * </ul>
     *
     * <p>并且验证「退款先到账、{@code payment_status} 后翻转」这条顺序：
     * payment_status 仍是 2 的单，只要退款已到账也必须排除（并集口径）。
     */
    @Test
    void settledRefundExcludesButPendingRefundStaysInDenominator() throws Exception {
        long settledReg = insertPaid(1006L, matureCohort, 31, "ORD-R1", "100.00");
        insertRefund("registration", settledReg, "100.00", 1, 1);      // 到账 ⇒ 全退

        long pendingReview = insertPaid(1007L, matureCohort, 32, "ORD-R2", "100.00");
        insertRefund("registration", pendingReview, "100.00", 0, 0);   // 待审

        long pendingPayout = insertPaid(1008L, matureCohort, 33, "ORD-R3", "100.00");
        insertRefund("registration", pendingPayout, "100.00", 1, 2);   // 已批但待人工/未确认到账

        long settledBeforeStatusFlip = insertPaid(1009L, matureCohort, 34, "ORD-R4", "100.00");
        insertRefund("registration", settledBeforeStatusFlip, "100.00", 1, 4); // payment_status 仍 2

        long partial = insertPaid(1010L, matureCohort, 35, "ORD-R5", "100.00");
        insertRefund("registration", partial, "30.00", 1, 1);          // 部分退 ⇒ 仍在分母

        PostGameOutcomeReportVO report = matureService().generate(matureCohort, 30);

        assertEquals(3L, report.getCohort().eligibleMembers(),
                "待审 + 待人工 + 部分退 三位仍在分母；两位全退排除");
        assertEquals(2L, report.getCohort().fullRefundExcluded(),
                "已到账全退两笔（含 payment_status 未翻转的那笔）");
        assertEquals(2L, report.getCohort().pendingRefundMembers(),
                "待审与已批未到账各一");
        assertEquals(1L, report.getCohort().partialRefundMembers());
    }

    // ------------------------------------------------------------------
    // 完成口径：节点小游戏 ≠ 整局完成；路线终局按会员归属
    // ------------------------------------------------------------------

    /**
     * ★ 核心回归：节点小游戏已完成（{@code game_runtime_session.status=1}），
     * 但路线仍在进行（{@code topic_route_session.status=0}）⇒ 官方整局完成必须是 0。
     *
     * <p>表在库里、行是真的 status=1 —— 证明的是查询不再读它，而不是表不存在。
     * 同时客户端上报的 {@code play_arrive_success}（到点成功）只能出现在观测项。
     */
    @Test
    void nodeMinigameCompletionWhileRouteRunningYieldsZeroOfficialCompletion() throws Exception {
        insertPaid(1101L, matureCohort, 41, "ORD-N1", "90.00");
        insertNodeMinigameCompleted(1101L, matureCohort.plusDays(2));
        insertRouteSession(1101L, 0, null, matureCohort.plusDays(2));     // 路线进行中
        insertEvent(1101L, "play_arrive_success", "evt-arrive-1", matureCohort.plusDays(2));

        PostGameOutcomeReportVO report = matureService().generate(matureCohort, 30);

        PostGameOutcomeReportVO.MetricCount completion = report.getMetrics().get(COMPLETION);
        assertNotNull(completion);
        assertEquals(0L, completion.members(), "节点小游戏通关不得计入整局完成");
        assertEquals(0L, completion.facts(), "节点小游戏不得产生整局完成事实");
        assertTrue(completion.serverFinal(), "完成项仍是服务端事实口径");

        PostGameOutcomeReportVO.MetricCount observed = report.getMetrics().get(CLIENT_OBSERVED);
        assertEquals(1L, observed.members(), "客户端到点上报被记录");
        assertFalse(observed.serverFinal(), "客户端到点上报不是服务端终局");
    }

    /**
     * 路线走到终点（{@code status=1} 且 {@code completed_at} 非空）才算整局完成，按会话 owner 会员归属；
     * {@code status=1} 但 {@code completed_at} 为空的行明确排除（不能用当前时间猜完成时刻）。
     */
    @Test
    void routeFinalStateCountsPerMemberAndNullCompletedAtIsExcluded() throws Exception {
        insertPaid(1102L, matureCohort, 51, "ORD-T1", "90.00");
        insertRouteSession(1102L, 1, matureCohort.plusDays(4), matureCohort.plusDays(1));  // 真完成

        insertPaid(1103L, matureCohort, 52, "ORD-T2", "90.00");
        insertRouteSession(1103L, 1, null, matureCohort.plusDays(1));                       // 状态 1 但无完成时刻

        PostGameOutcomeReportVO report = matureService().generate(matureCohort, 30);

        PostGameOutcomeReportVO.MetricCount completion = report.getMetrics().get(COMPLETION);
        assertEquals(1L, completion.members(), "只有 completed_at 非空的那位算整局完成");
        assertEquals(1L, completion.facts());
        assertNotNull(completion.firstOccurredAt());
    }

    // ------------------------------------------------------------------
    // 六类结果不互冒 + 私照不公开
    // ------------------------------------------------------------------

    /**
     * 同一会员同时具备六类事实时，每类只数自己的行；私照永不公开，客户端观测不算完成。
     */
    @Test
    void sixOutcomeKindsDoNotCrossCountAndPrivatePhotosStayPrivate() throws Exception {
        long member = 1201L;
        insertPaid(member, matureCohort, 61, "ORD-K1", "70.00");
        insertBadge(member, matureCohort.plusDays(2));
        insertXpAward(member, matureCohort.plusDays(2));
        insertPoiReward(member, matureCohort.plusDays(2));
        long privatePost = insertPost(member, "PRIVATE", "PUBLISHED", matureCohort.plusDays(3), "req-priv");
        insertMedia(member, privatePost, "media-req-1", "hash-priv", matureCohort.plusDays(3));
        insertPost(member, "PUBLIC", "PUBLISHED", matureCohort.plusDays(3), "req-pub");
        insertEvent(member, "finish_route_recommendation_click", "evt-reco-1", matureCohort.plusDays(3));
        insertEvent(member, "mission_complete", "evt-mission-1", matureCohort.plusDays(3));

        PostGameOutcomeReportVO report = matureService().generate(matureCohort, 30);

        assertEquals(1L, report.getMetrics().get(COMPLETION).facts(), "完成只数通关勋章");
        assertEquals(1L, report.getMetrics().get(COMPLETION).members());

        assertEquals(2L, report.getMetrics().get(REWARD).facts(), "奖励 = 通关 XP + 据点券，共 2 条");
        assertEquals(1L, report.getMetrics().get(PRIVATE_PHOTO).facts(), "私照 1 条");
        assertFalse(report.getMetrics().get(PRIVATE_PHOTO).publicVisible(), "私照默认不公开");
        assertEquals(1L, report.getMetrics().get(PUBLIC_SHARE).facts(), "公开帖只有 PUBLIC 那条");
        assertTrue(report.getMetrics().get(PUBLIC_SHARE).publicVisible());
        assertEquals(1L, report.getMetrics().get(RECO_CLICK).facts(), "推荐点击 1 条");
        assertEquals(1L, report.getMetrics().get(CLIENT_OBSERVED).facts(), "客户端观测 1 条");
        assertFalse(report.getMetrics().get(CLIENT_OBSERVED).serverFinal());

        // 类别集合必须完整且互不替代。
        assertTrue(report.getMetricCodes().containsAll(Arrays.asList(
                COMPLETION, REWARD, PRIVATE_PHOTO, PUBLIC_SHARE, RECO_CLICK,
                SECOND_REGISTRATION, CLIENT_OBSERVED)));
    }

    // ------------------------------------------------------------------
    // 水位按范围
    // ------------------------------------------------------------------

    /**
     * 退款水位必须只反映本报表真正读的那部分（{@code source_type='registration' and del_flag=0}）。
     *
     * <p>构造「订单来源退款更晚」的场景：宽口径 {@code max(update_time)} 会取到它，
     * 从而谎称「cohort 排除已按最新退款刷新」。窄口径必须取 registration 那条。
     */
    @Test
    void refundWatermarkIsScopedToRegistrationRefundsOnly() throws Exception {
        long reg = insertPaid(1301L, matureCohort, 71, "ORD-W1", "100.00");
        LocalDateTime registrationRefundAt = matureCohort.plusDays(2).atTime(10, 0);
        LocalDateTime orderRefundAt = matureCohort.plusDays(20).atTime(10, 0);
        insertRefundAt("registration", reg, "10.00", 1, 1, registrationRefundAt, 0);
        // 订单来源退款：与本报表无关，但 update_time 更晚。
        insertRefundAt("order", 7777L, "99.00", 1, 1, orderRefundAt, 0);
        // 已软删的 registration 退款同样不该抬高水位。
        insertRefundAt("registration", reg, "5.00", 1, 1, orderRefundAt.plusDays(1), 1);

        insertRouteSession(1301L, 1, matureCohort.plusDays(3), matureCohort.plusDays(1));

        Map<String, Date> watermarks = watermarksFromMapper();

        Date refundWatermark = watermarks.get("refund_application.registration.update_time");
        assertNotNull(refundWatermark, "必须有退款水位");
        assertEquals(Timestamp.valueOf(registrationRefundAt).getTime(), refundWatermark.getTime(),
                "退款水位必须限定 registration 来源且排除软删行");

        assertTrue(watermarks.containsKey("topic_route_session.completed_at"),
                "路线终局必须有自己的水位");
        assertEquals(Timestamp.valueOf(matureCohort.plusDays(3).atTime(0, 0)).getTime(),
                watermarks.get("topic_route_session.completed_at").getTime());
        assertFalse(watermarks.containsKey("game_runtime_session.completed_at"),
                "节点小游戏已不是本报表来源，不应继续出现水位");
    }

    // ------------------------------------------------------------------
    // 服务装配：真实 mapper + 完整一次报告
    // ------------------------------------------------------------------

    /**
     * 按真实 mapper 组装一次完整报告，核对关键分子/分母与派生比率。
     */
    @Test
    void serviceAssemblesFullReportAgainstRealMapper() throws Exception {
        insertPaid(1401L, matureCohort, 81, "ORD-F1", "100.00");
        insertPaid(1401L, matureCohort.plusDays(10), 82, "ORD-F2", "100.00");   // 第二次有效报名
        insertPaid(1402L, matureCohort, 83, "ORD-F3", "100.00");                // 只报名一次

        PostGameOutcomeReportVO report = matureService().generate(matureCohort, 30);

        assertTrue(report.isMature(), "回溯 60 天的 cohort 必须已成熟");
        assertEquals(2L, report.getCohort().eligibleMembers());
        assertEquals(Long.valueOf(1), report.getCohort().retainedMembers());
        assertEquals(0, report.getCohort().retentionRate().compareTo(
                new java.math.BigDecimal("0.5000")), "1/2 的比率必须是 0.5000");
        assertNotNull(report.getComputedAt());
        assertFalse(report.isHistoricalReplayable(), "即时只读计算不是可回放快照");
        assertEquals("POST_GAME_OUTCOME_V2_20261108", report.getBasisVersion());
        assertTrue(report.getUnavailableSources().containsKey("GAME_SESSION_ACTIVITY_FINISHED"),
                "无法按会员归属的来源必须显式声明");
        assertEquals(matureCohort.toString(), report.getCohortDate());

        // 六类 + 观测项齐全，且每类都带来源声明（读者能据此复算）。
        for (String code : Arrays.asList(COMPLETION, REWARD, PRIVATE_PHOTO, PUBLIC_SHARE,
                RECO_CLICK, SECOND_REGISTRATION, CLIENT_OBSERVED)) {
            PostGameOutcomeReportVO.MetricCount metric = report.getMetrics().get(code);
            assertNotNull(metric, "缺少指标:" + code);
            assertNotNull(metric.sourceTables(), "指标必须声明来源表:" + code);
            assertNotNull(metric.identityBasis(), "指标必须声明身份口径:" + code);
        }
    }

    /**
     * 未成熟 cohort：不得把「还不知道」报成 0%。
     */
    @Test
    void immatureCohortReportsNullRetentionNotZero() throws Exception {
        insertPaid(1501L, nowDate, 91, "ORD-I1", "100.00");
        insertPaid(1501L, nowDate.plusDays(1), 92, "ORD-I2", "100.00");

        PostGameOutcomeReportVO report = matureService().generate(nowDate, 30);

        assertFalse(report.isMature());
        assertNull(report.getCohort().retainedMembers(), "未成熟不得展示为 0");
        assertNull(report.getCohort().retentionRate(), "未成熟不得展示 0% 留存");
        assertEquals(1L, report.getCohort().notMatureMembers());
        assertEquals(1L, report.getCohort().eligibleMembers());
    }

    // ------------------------------------------------------------------
    // fixture helpers
    // ------------------------------------------------------------------

    private PostGameOutcomeReportServiceImpl matureService() {
        // 走生产构造器（Clock 为 Asia/Shanghai 系统时钟），不注入测试时钟 —— 报表按真实「现在」判成熟。
        return new PostGameOutcomeReportServiceImpl(mapper(), null);
    }

    private PostGameOutcomeMapper mapper() {
        return session.getMapper(PostGameOutcomeMapper.class);
    }

    private Map<String, Date> watermarksFromMapper() {
        List<Map<String, Object>> rows = mapper().selectSourceWatermarks();
        Map<String, Date> byName = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object name = row.get("sourceName");
            Object value = row.get("watermarkAt");
            if (name instanceof String && value instanceof Date) {
                byName.put((String) name, (Date) value);
            }
        }
        return byName;
    }

    private long insertPaid(long memberId, LocalDate paymentDate, int seq, String parentOrderNo,
                            String payable) throws Exception {
        long registrationId = 100_000L + memberId * 100 + seq;
        String sql = "insert into cms_registration(id,registration_no,parent_order_no,owner_type,owner_id,"
                + "ticket_id,ticket_name,ticket_price,member_id,participate_date,order_num,original_amount,"
                + "total_amount,payable_amount,payment_status,payment_type,payment_time,registration_status,"
                + "pay_expire_time,verification_status,points_returned,create_time,update_time) "
                + "values(?,?,?,1,?,?,?,?,?,?,1,?,?,?,2,1,?,2,?,0,0,?,?)";
        try (PreparedStatement ps = session.getConnection().prepareStatement(sql)) {
            LocalDateTime at = paymentDate.atTime(10, 0);
            Timestamp ts = Timestamp.valueOf(at);
            ps.setLong(1, registrationId);
            ps.setString(2, "REG-" + registrationId);
            ps.setString(3, parentOrderNo);
            ps.setLong(4, TOPIC);
            ps.setLong(5, 1L);
            ps.setString(6, "标准票");
            ps.setBigDecimal(7, new java.math.BigDecimal(payable));
            ps.setLong(8, memberId);
            ps.setString(9, paymentDate.toString());
            ps.setBigDecimal(10, new java.math.BigDecimal(payable));
            ps.setBigDecimal(11, new java.math.BigDecimal(payable));
            ps.setBigDecimal(12, new java.math.BigDecimal(payable));
            ps.setTimestamp(13, ts);
            ps.setTimestamp(14, Timestamp.valueOf(paymentDate.plusDays(1).atTime(0, 0)));
            ps.setTimestamp(15, ts);
            ps.setTimestamp(16, ts);
            ps.executeUpdate();
        }
        return registrationId;
    }

    private void insertRefund(String sourceType, long sourceId, String amount, int status,
                              int payoutStatus) throws Exception {
        insertRefundAt(sourceType, sourceId, amount, status, payoutStatus,
                matureCohort.plusDays(2).atTime(12, 0), 0);
    }

    private void insertRefundAt(String sourceType, long sourceId, String amount, int status,
                                int payoutStatus, LocalDateTime updateTime, int delFlag) throws Exception {
        String sql = "insert into refund_application(refund_no,source_type,source_id,owner_type,owner_id,"
                + "member_id,paid_amount,refund_amount,status,del_flag,payout_status,adjustment_created,"
                + "create_time,update_time) values(?,?,?,1,?,?,?,?,?,?,?,0,?,?)";
        try (PreparedStatement ps = session.getConnection().prepareStatement(sql)) {
            String no = "RF-" + sourceType + "-" + sourceId + "-" + payoutStatus + "-" + delFlag
                    + "-" + updateTime.toLocalTime().toString().replace(":", "");
            ps.setString(1, no);
            ps.setString(2, sourceType);
            ps.setLong(3, sourceId);
            ps.setLong(4, TOPIC);
            ps.setLong(5, 1L);
            ps.setBigDecimal(6, new java.math.BigDecimal(amount));
            ps.setBigDecimal(7, new java.math.BigDecimal(amount));
            ps.setInt(8, status);
            ps.setInt(9, delFlag);
            ps.setInt(10, payoutStatus);
            ps.setTimestamp(11, Timestamp.valueOf(updateTime));
            ps.setTimestamp(12, Timestamp.valueOf(updateTime));
            ps.executeUpdate();
        }
    }

    private void insertBadge(long memberId, LocalDate obtainDate) throws Exception {
        String sql = "insert into player_badge(member_id,badge_code,badge_name,source_type,source_id,"
                + "obtain_time,del_flag,create_time) values(?,?,?,2,?,?,0,?)";
        try (PreparedStatement ps = session.getConnection().prepareStatement(sql)) {
            Timestamp ts = Timestamp.valueOf(obtainDate.atTime(15, 0));
            ps.setLong(1, memberId);
            ps.setString(2, "TOPIC_FINISH_" + TOPIC);
            ps.setString(3, "通关");
            ps.setLong(4, TOPIC);
            ps.setTimestamp(5, ts);
            ps.setTimestamp(6, ts);
            ps.executeUpdate();
        }
    }

    private void insertXpAward(long memberId, LocalDate grantedDate) throws Exception {
        String sql = "insert into topic_completion_xp_award(member_id,topic_id,xp_amount,granted_at,create_time)"
                + " values(?,?,?,?,?)";
        try (PreparedStatement ps = session.getConnection().prepareStatement(sql)) {
            Timestamp ts = Timestamp.valueOf(grantedDate.atTime(15, 30));
            ps.setLong(1, memberId);
            ps.setLong(2, TOPIC);
            ps.setInt(3, 10);
            ps.setTimestamp(4, ts);
            ps.setTimestamp(5, ts);
            ps.executeUpdate();
        }
    }

    private void insertPoiReward(long memberId, LocalDate createDate) throws Exception {
        String sql = "insert into roam_poi_reward_log(poi_id,member_id,coupon_id,coupon_history_id,"
                + "status,create_time) values(?,?,?,?,1,?)";
        try (PreparedStatement ps = session.getConnection().prepareStatement(sql)) {
            Timestamp ts = Timestamp.valueOf(createDate.atTime(16, 0));
            ps.setLong(1, 1L);
            ps.setLong(2, memberId);
            ps.setLong(3, 5L);
            ps.setLong(4, 55L);
            ps.setTimestamp(5, ts);
            ps.executeUpdate();
        }
    }

    private void insertNodeMinigameCompleted(long memberId, LocalDate completedDate) throws Exception {
        String sql = "insert into game_runtime_session(activity_id,topic_id,node_id,template_id,owner_type,"
                + "owner_id,config_snapshot_json,state_json,status,score,completed_units,version,started_at,"
                + "completed_at,create_time,update_time) values(0,?,?,?,1,?,'{}','{}',1,10,1,1,?,?,?,?)";
        try (PreparedStatement ps = session.getConnection().prepareStatement(sql)) {
            Timestamp ts = Timestamp.valueOf(completedDate.atTime(11, 0));
            ps.setLong(1, TOPIC);
            ps.setLong(2, NODE);
            ps.setLong(3, TEMPLATE);
            ps.setLong(4, memberId);
            ps.setTimestamp(5, ts);
            ps.setTimestamp(6, ts);
            ps.setTimestamp(7, ts);
            ps.setTimestamp(8, ts);
            ps.executeUpdate();
        }
    }

    private void insertRouteSession(long memberId, int status, LocalDate completedDate,
                                    LocalDate startedDate) throws Exception {
        String sql = "insert into topic_route_session(activity_id,topic_id,member_id,play_instance_id,"
                + "config_version,config_snapshot_json,state_json,status,seed,completed_at,create_time,"
                + "update_time) values(0,?,?,'default',1,'{}','{}',?,1,?,?,?)";
        try (PreparedStatement ps = session.getConnection().prepareStatement(sql)) {
            Timestamp created = Timestamp.valueOf(startedDate.atTime(9, 0));
            ps.setLong(1, TOPIC);
            ps.setLong(2, memberId);
            ps.setInt(3, status);
            ps.setTimestamp(4, completedDate == null ? null
                    : Timestamp.valueOf(completedDate.atTime(0, 0)));
            ps.setTimestamp(5, created);
            ps.setTimestamp(6, created);
            ps.executeUpdate();
        }
    }

    private long insertPost(long memberId, String audience, String lifecycle, LocalDate publishedDate,
                            String clientRequestId) throws Exception {
        ensureGuidelineVersion();
        String sql = "insert into community_post(author_id,client_request_id,post_type,body,audience,"
                + "comment_policy,lifecycle,location_precision,recommendation_eligible,search_eligible,"
                + "interaction_enabled,disclosure_type,safety_labels_json,guideline_version_id,"
                + "published_at,create_time,update_time) "
                + "values(?,?,'MOMENT','正文',?,'EVERYONE',?,'NONE',1,1,1,'NONE','[]',?,?,?,?)";
        try (PreparedStatement ps = session.getConnection().prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            Timestamp ts = Timestamp.valueOf(publishedDate.atTime(13, 0));
            ps.setLong(1, memberId);
            ps.setString(2, clientRequestId);
            ps.setString(3, audience);
            ps.setString(4, lifecycle);
            ps.setLong(5, GUIDELINE_VERSION);
            ps.setTimestamp(6, ts);
            ps.setTimestamp(7, ts);
            ps.setTimestamp(8, ts);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private void insertMedia(long memberId, long postId, String uploadRequestId, String contentHash,
                             LocalDate createDate) throws Exception {
        String sql = "insert into community_post_media(owner_member_id,upload_request_id,post_id,media_type,"
                + "object_key,mime_type,byte_size,content_hash,upload_state,review_state,retention_state,"
                + "create_time,update_time) values(?,?,?,'IMAGE','k/1.jpg','image/jpeg',1024,?,"
                + "'ATTACHED','PASS','ACTIVE',?,?)";
        try (PreparedStatement ps = session.getConnection().prepareStatement(sql)) {
            Timestamp ts = Timestamp.valueOf(createDate.atTime(14, 0));
            ps.setLong(1, memberId);
            ps.setString(2, uploadRequestId);
            ps.setLong(3, postId);
            ps.setString(4, contentHash);
            ps.setTimestamp(5, ts);
            ps.setTimestamp(6, ts);
            ps.executeUpdate();
        }
    }

    private void ensureGuidelineVersion() throws Exception {
        try (PreparedStatement ps = session.getConnection().prepareStatement(
                "insert into community_guideline_version(id,version_code,title,summary,content_json,"
                             + "applies_to,status,effective_at,create_time,update_time) "
                             + "values(?,'v1','规范','简述','{}','PLAZA','ACTIVE',now(),now(),now())")
        ) {
            ps.setLong(1, GUIDELINE_VERSION);
            try {
                ps.executeUpdate();
            } catch (java.sql.SQLIntegrityConstraintViolationException duplicate) {
                // 同一用例里可能插两条帖文；版本行复用即可。
            }
        }
    }

    private void insertEvent(long memberId, String eventName, String idempotencyKey,
                             LocalDate occurredDate) throws Exception {
        String sql = "insert into biz_event_log(event_name,member_id,anonymous_id,session_id,source,"
                + "page_path,biz_type,biz_id,city_code,properties_json,idempotency_key,actor_scope,"
                + "occurred_at,create_time) values(?,?,'','s-1','app','/pages/play/finish',"
                + "'topic',?,'310000','{}',?,?,?,?)";
        try (PreparedStatement ps = session.getConnection().prepareStatement(sql)) {
            Timestamp ts = Timestamp.valueOf(occurredDate.atTime(17, 0));
            ps.setString(1, eventName);
            ps.setLong(2, memberId);
            ps.setLong(3, TOPIC);
            ps.setString(4, idempotencyKey);
            ps.setString(5, "MEMBER:" + memberId);
            ps.setTimestamp(6, ts);
            ps.setTimestamp(7, ts);
            ps.executeUpdate();
        }
    }
}
