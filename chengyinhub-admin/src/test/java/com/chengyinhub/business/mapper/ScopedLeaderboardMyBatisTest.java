package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.vo.ScopedLeaderboardEntryVO;
import com.chengyinhub.web.service.play.ScopedLeaderboardPeriods;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BE-14 多作用域榜单只读 SQL 回归（H2 / MySQL 模式）。
 *
 * <p>夹具只用现有真源列：完成事实在 {@code player_node_progress}，资格在 {@code cms_registration}
 * + {@code refund_application}，run 身份在 {@code topic_route_session}，
 * 城市归因在 {@code cms_topic.city_code}，俱乐部范围在 {@code cms_topic.club_id} + {@code club_member}。
 * 断言不写死 JVM 时区：插入与比较都走同一 JDBC 转换。
 */
class ScopedLeaderboardMyBatisTest {

    private static SqlSessionFactory factory;

    private SqlSession session;
    private ScopedLeaderboardMapper mapper;

    @BeforeAll
    static void createSchema() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:scoped_leaderboard;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/ScopedLeaderboardMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession init = factory.openSession(true);
             Statement st = init.getConnection().createStatement()) {
            st.execute("create table ums_member (id bigint primary key, nickname varchar(50), avatar varchar(255), del_flag int default 0)");
            st.execute("create table cms_topic (id bigint primary key, del_flag int default 0, city_code varchar(32), club_id bigint)");
            st.execute("create table club (id bigint primary key, member_id bigint, del_flag int default 0)");
            st.execute("create table club_member (club_id bigint, member_id bigint, status int, del_flag int default 0)");
            st.execute("create table topic_route_session (id bigint primary key, activity_id bigint, topic_id bigint, " +
                    "member_id bigint, play_instance_id varchar(64))");
            st.execute("create table cms_registration (id bigint primary key, member_id bigint, owner_type int, " +
                    "owner_id bigint, payment_status int, registration_status int, payment_time timestamp, " +
                    "payable_amount decimal(10,2) default 0)");
            st.execute("create table refund_application (id bigint primary key, source_type varchar(32), " +
                    "source_id bigint, status int, payout_status int, refund_amount decimal(10,2), del_flag int default 0)");
            st.execute("create table player_node_progress (id bigint primary key, member_id bigint, activity_id bigint, " +
                    "topic_id bigint, node_id bigint, status int, checkin_time timestamp, create_time timestamp, " +
                    "del_flag int default 0, play_instance_id varchar(64) default 'default')");
        }
    }

    @BeforeEach
    void openSession() throws Exception {
        session = factory.openSession(true);
        mapper = session.getMapper(ScopedLeaderboardMapper.class);
        try (Statement st = session.getConnection().createStatement()) {
            for (String table : new String[]{"ums_member", "cms_topic", "club", "club_member",
                    "topic_route_session", "cms_registration", "refund_application", "player_node_progress"}) {
                st.execute("delete from " + table);
            }
        }
    }

    @AfterEach
    void closeSession() {
        if (session != null) session.close();
    }

    @Test
    void allQueryableScopesAgreeOnScoreAndOrderForTheSameSet() throws Exception {
        seedTwoPaidPlayersInOneCityClubTopic();

        for (String scope : new String[]{"world", "quest", "club"}) {
            String scopeId = scopeIdFor(scope);
            List<ScopedLeaderboardEntryVO> rows = mapper.selectScopedTop(
                    scope, scopeId, idOf(scope), null, null, 50);
            assertEquals(2, rows.size(), scope + " 应返回两名有效玩家");
            assertEquals(Long.valueOf(1L), rows.get(0).getMemberId(), scope + " 第一名应为分高者");
            assertEquals(Long.valueOf(2L), rows.get(0).getScore(), scope + " 分数应为完成节点数");
            assertEquals(Long.valueOf(2L), rows.get(1).getMemberId(), scope + " 第二名应为分低者");
            assertEquals(Long.valueOf(1L), rows.get(1).getScore(), scope + " 分数应为完成节点数");
            assertEquals(Integer.valueOf(2), mapper.selectScopedMemberRank(
                    scope, scopeId, idOf(scope), null, null, 2L), scope + " 名次口径应与排序一致");
        }
    }

    @Test
    void weekWindowIncludesSunday2359AndExcludesNextMonday0000() throws Exception {
        Instant sunday2359 = LocalDateTime.of(2026, 11, 8, 15, 59).toInstant(ZoneOffset.UTC); // 23:59 +08:00
        Instant monday0000 = LocalDateTime.of(2026, 11, 8, 16, 0).toInstant(ZoneOffset.UTC);  // 周一 00:00 +08:00
        try (Statement st = session.getConnection().createStatement()) {
            st.execute("insert into ums_member(id,nickname,avatar,del_flag) values (1,'周日','a',0),(2,'周一','b',0)");
            st.execute("insert into cms_topic(id,del_flag,city_code,club_id) values (300,0,'310100',null)");
            insertPaidPass(st, 1, 1, 300, Timestamp.valueOf("2026-11-01 09:00:00"));
            insertPaidPass(st, 2, 2, 300, Timestamp.valueOf("2026-11-01 09:00:00"));
            st.execute("insert into topic_route_session(id,activity_id,topic_id,member_id,play_instance_id) values "
                    + "(1,0,300,1,'p1'),(2,0,300,2,'p2')");
            insertProgress(st, 1, 1, 0, 300, 11, "p1", Timestamp.from(sunday2359));
            insertProgress(st, 2, 2, 0, 300, 12, "p2", Timestamp.from(monday0000));
        }
        Date weekStart = ScopedLeaderboardPeriods.weekStart(Instant.parse("2026-11-08T12:00:00Z"));
        Date weekEnd = ScopedLeaderboardPeriods.weekEnd(weekStart);
        List<ScopedLeaderboardEntryVO> rows = mapper.selectScopedTop("quest", "300", 300L, weekStart, weekEnd, 50);
        assertEquals(1, rows.size(), "周一 00:00 的完成不属于上一周");
        assertEquals(Long.valueOf(1L), rows.get(0).getMemberId(), "周日 23:59 属于本周");
    }

    @Test
    void replayingTheSameRunNeverDoubleCounts() throws Exception {
        try (Statement st = session.getConnection().createStatement()) {
            st.execute("insert into ums_member(id,nickname,avatar,del_flag) values (1,'重放','a',0)");
            st.execute("insert into cms_topic(id,del_flag,city_code,club_id) values (300,0,'310100',null)");
            insertPaidPass(st, 1, 1, 300, Timestamp.valueOf("2026-11-01 09:00:00"));
            // 旧局 p1 两行遗留 + 现局 p2 两行；最新 session 是 p2 ⇒ 只认 p2。
            st.execute("insert into topic_route_session(id,activity_id,topic_id,member_id,play_instance_id) values "
                    + "(1,0,300,1,'p1'),(2,0,300,1,'p2')");
            insertProgress(st, 1, 1, 0, 300, 11, "p1", Timestamp.valueOf("2026-11-08 10:00:00"));
            insertProgress(st, 2, 1, 0, 300, 12, "p1", Timestamp.valueOf("2026-11-08 10:01:00"));
            insertProgress(st, 3, 1, 0, 300, 13, "p2", Timestamp.valueOf("2026-11-08 11:00:00"));
            insertProgress(st, 4, 1, 0, 300, 14, "p2", Timestamp.valueOf("2026-11-08 11:01:00"));
        }
        List<ScopedLeaderboardEntryVO> rows = mapper.selectScopedTop("world", null, null, null, null, 50);
        assertEquals(1, rows.size());
        assertEquals(Long.valueOf(2L), rows.get(0).getScore(), "只认最新一局的节点，重放不累加");
    }

    @Test
    void refundedAndUnpaidRunsAreExcludedButCountedInCoverage() throws Exception {
        try (Statement st = session.getConnection().createStatement()) {
            st.execute("insert into ums_member(id,nickname,avatar,del_flag) values (1,'已付','a',0),(2,'已退','b',0),(3,'未付','c',0)");
            st.execute("insert into cms_topic(id,del_flag,city_code,club_id) values (300,0,'310100',null)");
            insertPaidPass(st, 1, 1, 300, Timestamp.valueOf("2026-11-01 09:00:00"));
            // 已退款：支付状态 4
            st.execute("insert into cms_registration(id,member_id,owner_type,owner_id,payment_status,registration_status," +
                    "payment_time,payable_amount) values (2,2,1,300,4,3,'2026-11-01 09:00:00',9900)");
            // 未支付：只到待支付
            st.execute("insert into cms_registration(id,member_id,owner_type,owner_id,payment_status,registration_status," +
                    "payment_time,payable_amount) values (3,3,1,300,0,1,null,9900)");
            insertProgress(st, 1, 1, 0, 300, 11, "default", Timestamp.valueOf("2026-11-08 10:00:00"));
            insertProgress(st, 2, 2, 0, 300, 12, "default", Timestamp.valueOf("2026-11-08 10:00:00"));
            insertProgress(st, 3, 3, 0, 300, 13, "default", Timestamp.valueOf("2026-11-08 10:00:00"));
        }
        List<ScopedLeaderboardEntryVO> rows = mapper.selectScopedTop("world", null, null, null, null, 50);
        assertEquals(1, rows.size(), "退款/未支付局不得进榜");
        assertEquals(Long.valueOf(1L), rows.get(0).getMemberId());
        assertEquals(2L, mapper.countUnverifiedRuns("world", null, null, null, null),
                "退款/未付局如实计入 coverage，不补 0 也不隐藏");
        assertNull(mapper.selectScopedMemberRow("world", null, null, null, null, 2L),
                "已退款玩家没有有效成绩，不得返回伪造 0 分记录");
    }

    @Test
    void settledFullRefundRevokesTheRunEvenWhenPaymentStatusIsStillPaid() throws Exception {
        try (Statement st = session.getConnection().createStatement()) {
            st.execute("insert into ums_member(id,nickname,avatar,del_flag) values (1,'全退','a',0)");
            st.execute("insert into cms_topic(id,del_flag,city_code,club_id) values (300,0,'310100',null)");
            insertPaidPass(st, 1, 1, 300, Timestamp.valueOf("2026-11-01 09:00:00"));
            // 退款已确认到账（status=1, payout_status=4）且累计退款 ≥ 应付 ⇒ 撤销资格
            st.execute("insert into refund_application(id,source_type,source_id,status,payout_status,refund_amount,del_flag) " +
                    "values (1,'registration',1,1,4,9900,0)");
            insertProgress(st, 1, 1, 0, 300, 11, "default", Timestamp.valueOf("2026-11-08 10:00:00"));
        }
        assertTrue(mapper.selectScopedTop("world", null, null, null, null, 50).isEmpty(),
                "已确认到账的全额退款局不得继续计分");
    }

    @Test
    void refundStillInFlightDoesNotRevokeAnOtherwisePaidRun() throws Exception {
        try (Statement st = session.getConnection().createStatement()) {
            st.execute("insert into ums_member(id,nickname,avatar,del_flag) values (1,'退款处理中','a',0)");
            st.execute("insert into cms_topic(id,del_flag,city_code,club_id) values (300,0,'310100',null)");
            insertPaidPass(st, 1, 1, 300, Timestamp.valueOf("2026-11-01 09:00:00"));
            // 钱未确认到账（payout_status=0 处理中）⇒ 依 PostGameOutcomeMapper 口径不算已退
            st.execute("insert into refund_application(id,source_type,source_id,status,payout_status,refund_amount,del_flag) " +
                    "values (1,'registration',1,1,0,9900,0)");
            insertProgress(st, 1, 1, 0, 300, 11, "default", Timestamp.valueOf("2026-11-08 10:00:00"));
        }
        assertEquals(1, mapper.selectScopedTop("world", null, null, null, null, 50).size(),
                "退款未到账不撤销已完成成绩");
    }

    @Test
    void clubScopeExcludesNonMembersEvenWhenTheyScoredHigher() throws Exception {
        try (Statement st = session.getConnection().createStatement()) {
            st.execute("insert into ums_member(id,nickname,avatar,del_flag) values (1,'成员','a',0),(2,'路人','b',0),(3,'主理人','c',0)");
            st.execute("insert into cms_topic(id,del_flag,city_code,club_id) values (300,0,'310100',900)");
            insertPaidPass(st, 1, 1, 300, Timestamp.valueOf("2026-11-01 09:00:00"));
            insertPaidPass(st, 2, 2, 300, Timestamp.valueOf("2026-11-01 09:00:00"));
            insertPaidPass(st, 3, 3, 300, Timestamp.valueOf("2026-11-01 09:00:00"));
            st.execute("insert into club(id,member_id,del_flag) values (900,3,0)");
            st.execute("insert into club_member(club_id,member_id,status,del_flag) values (900,1,1,0),(900,2,2,0)");
            insertProgress(st, 1, 1, 0, 300, 11, "default", Timestamp.valueOf("2026-11-08 10:00:00"));
            insertProgress(st, 2, 2, 0, 300, 12, "default", Timestamp.valueOf("2026-11-08 10:00:00"));
            insertProgress(st, 3, 2, 0, 300, 13, "default", Timestamp.valueOf("2026-11-08 10:01:00"));
            insertProgress(st, 4, 3, 0, 300, 14, "default", Timestamp.valueOf("2026-11-08 10:02:00"));
        }
        List<ScopedLeaderboardEntryVO> rows = mapper.selectScopedTop("club", "900", 900L, null, null, 50);
        assertEquals(2, rows.size(), "俱乐部榜 = 在册成员(status=1) + 主理人");
        assertEquals(Long.valueOf(1L), rows.get(0).getMemberId());
        assertEquals(Long.valueOf(3L), rows.get(1).getMemberId());
    }

    @Test
    void unrecognisedScopeIsFailClosedAtSqlLevel() throws Exception {
        seedTwoPaidPlayersInOneCityClubTopic();
        // 应用层已白名单校验；这里守第二道闸：任何未识别作用域不得退化成「无过滤全量榜」。
        // （city/country 读 JSON 冻结快照，H2 无 json_extract，放在 ScopedLeaderboardMySqlIT 验。）
        assertTrue(mapper.selectScopedTop("friends", null, null, null, null, 50).isEmpty(),
                "未识别作用域一律 1=0，不得漏谓词");
        assertEquals(0L, mapper.countRankedMembers("friends", null, null, null, null));
        assertTrue(mapper.selectScopedTop("", null, null, null, null, 50).isEmpty(),
                "空作用域同样不得放行");
    }

    @Test
    void memberRankMatchesCompetitionRankingOfTheTopList() throws Exception {
        seedTwoPaidPlayersInOneCityClubTopic();
        assertEquals(Integer.valueOf(1), mapper.selectScopedMemberRank("world", null, null, null, null, 1L));
        assertEquals(Integer.valueOf(2), mapper.selectScopedMemberRank("world", null, null, null, null, 2L));
        assertNull(mapper.selectScopedMemberRank("world", null, null, null, null, 99L),
                "无有效对局的玩家不猜名次");
    }

    private static String scopeIdFor(String scope) {
        if ("quest".equals(scope)) return "300";
        if ("club".equals(scope)) return "900";
        return null;
    }

    private static Long idOf(String scope) {
        if ("quest".equals(scope)) return 300L;
        if ("club".equals(scope)) return 900L;
        return null;
    }

    /** 自玩通行证：owner_type=1 + 主题 id + 已支付 + payment_time 非空。 */
    private static void insertPaidPass(Statement st, long registrationId, long memberId, long topicId,
                                       Timestamp paymentTime) throws Exception {
        st.execute("insert into cms_registration(id,member_id,owner_type,owner_id,payment_status,registration_status," +
                "payment_time,payable_amount) values (" + registrationId + "," + memberId + ",1," + topicId +
                ",2,2,'" + paymentTime + "',9900)");
    }

    private static void insertProgress(Statement st, long id, long memberId, long activityId, long topicId,
                                       long nodeId, String instance, Timestamp at) throws Exception {
        st.execute("insert into player_node_progress(id,member_id,activity_id,topic_id,node_id,status,checkin_time," +
                "create_time,del_flag,play_instance_id) values (" + id + "," + memberId + "," + activityId + "," +
                topicId + "," + nodeId + ",1,'" + at + "','" + at + "',0,'" + instance + "')");
    }

    /** 两名已支付玩家在同一城市、同一俱乐部归属主题上的完成事实（成员 1 两节点，成员 2 一节点）。 */
    private void seedTwoPaidPlayersInOneCityClubTopic() throws Exception {
        try (Statement st = session.getConnection().createStatement()) {
            st.execute("insert into ums_member(id,nickname,avatar,del_flag) values (1,'一','a',0),(2,'二','b',0)");
            st.execute("insert into cms_topic(id,del_flag,city_code,club_id) values (300,0,'310100',900)");
            st.execute("insert into club(id,member_id,del_flag) values (900,2,0)");
            st.execute("insert into club_member(club_id,member_id,status,del_flag) values (900,1,1,0)");
            insertPaidPass(st, 1, 1, 300, Timestamp.valueOf("2026-11-01 09:00:00"));
            insertPaidPass(st, 2, 2, 300, Timestamp.valueOf("2026-11-01 09:00:00"));
            insertProgress(st, 1, 1, 0, 300, 11, "default", Timestamp.valueOf("2026-11-08 10:00:00"));
            insertProgress(st, 2, 1, 0, 300, 12, "default", Timestamp.valueOf("2026-11-08 10:01:00"));
            insertProgress(st, 3, 2, 0, 300, 13, "default", Timestamp.valueOf("2026-11-08 10:02:00"));
        }
    }
}
