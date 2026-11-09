package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.vo.ScopedLeaderboardEntryVO;
import com.chengyinhub.business.mapper.ScopedLeaderboardMapper;
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
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BE-14 多作用域榜单在真 MySQL + 真 mapper XML 上的口径回归。
 *
 * <p>【为什么还要这一套】H2 的 MySQL 模式能挡住大部分语法，挡不住真实方言的
 * ONLY_FULL_GROUP_BY、`rank` 这类保留字、以及派生表 + 聚合的求值差异；而本卡全部要害都在 SQL 里。
 *
 * <p>建表：{@code cms_topic}/{@code cms_registration}/{@code refund_application}/{@code topic_route_session}
 * 用仓库里已有的**生产真实 DDL** 脚本；{@code ums_member}/{@code club}/{@code club_member}/
 * {@code player_node_progress} 见 {@code it-schema/scoped-leaderboard-it-schema.sql}（列定义抄自
 * {@code sql/base_schema_20260818.sql} 与 {@code migration_theme_runtime_20261004.sql}）。
 *
 * <p>库由 {@link MybatisItDatabase} 分配（一类一库，DROP+CREATE）；连不上即失败，不 skip。
 */
class ScopedLeaderboardMySqlIT {

    private static final long CITY_TOPIC = 300L;
    private static final long UNATTRIBUTED_TOPIC = 301L;
    private static final long CLUB = 900L;

    private static final long PAID_ACE = 1L;       // 在册成员，两节点
    private static final long PAID_MID = 2L;       // 主理人，一节点
    private static final long REFUNDED = 3L;       // payment_status=4
    private static final long SETTLED_REFUND = 4L; // 支付状态仍 2，但退款已确认到账且全额
    private static final long NOT_IN_CLUB = 5L;    // 已支付且分更高，但不是本团成员
    private static final long NO_CITY = 6L;        // 已支付，但主题未标注城市
    private static final long UNATTRIBUTED = 99L;  // 有完成事实但没有任何报名（旧局）
    /** 该局冻结快照里的地理归属；这是 city/country 唯一的归因来源。 */
    private static final String FROZEN_SNAPSHOT_SHANGHAI_CN =
            "{\"themeConfig\":{\"contentGeography\":{\"cityCode\":\"310100\",\"countryCode\":\"CN\"}}}";
    private static final String FROZEN_SNAPSHOT_BEIJING_CN =
            "{\"themeConfig\":{\"contentGeography\":{\"cityCode\":\"110100\",\"countryCode\":\"CN\"}}}";

    @Test
    void scopedBoardUsesRealMySqlDialectAndExistingFacts() throws Exception {
        SqlSessionFactory factory = factory();
        try (SqlSession session = factory.openSession(true);
             Statement st = session.getConnection().createStatement()) {
            ensureSchema(st.getConnection());
            seed(st);
            ScopedLeaderboardMapper mapper = session.getMapper(ScopedLeaderboardMapper.class);

            // ① 内容作用域（世界/城市/单关）看到同一批有效对局；
            //    路人不是本团成员但分最高 ⇒ 世界第一是他，俱乐部榜第一是成员。
            for (String scope : new String[]{"world", "quest"}) {
                List<ScopedLeaderboardEntryVO> rows = mapper.selectScopedTop(
                        scope, scopeIdFor(scope), idOf(scope), null, null, 50);
                assertEquals("world".equals(scope) ? 4 : 3, rows.size(), scope + " 应含全部有效付费对局");
                assertEquals(Long.valueOf(NOT_IN_CLUB), rows.get(0).getMemberId(), scope + " 分最高者在前");
                assertEquals(Long.valueOf(3L), rows.get(0).getScore(), scope + " 分数=完成节点数");
                assertEquals(Long.valueOf(PAID_ACE), rows.get(1).getMemberId(), scope + " 第二名");
                assertEquals(Integer.valueOf(2), mapper.selectScopedMemberRank(
                        scope, scopeIdFor(scope), idOf(scope), null, null, PAID_ACE), scope + " 名次");
            }

            // ② 俱乐部作用域只有本团在册成员 + 主理人；非成员（哪怕分最高）不出现。
            List<ScopedLeaderboardEntryVO> clubRows = mapper.selectScopedTop("club", String.valueOf(CLUB), CLUB,
                    null, null, 50);
            assertEquals(2, clubRows.size(), "俱乐部榜只含在册成员与主理人");
            assertEquals(Long.valueOf(PAID_ACE), clubRows.get(0).getMemberId(), "成员两节点排第一");
            assertEquals(Long.valueOf(PAID_MID), clubRows.get(1).getMemberId(), "主理人一节点排第二");
            assertEquals(2L, mapper.countRankedMembers("club", String.valueOf(CLUB), CLUB, null, null),
                    "俱乐部榜单人数只数本团");

            // ③ city/country 只认该局冻结快照：北京局不进上海榜；没有冻结地理的局只计入未归因。
            List<ScopedLeaderboardEntryVO> shanghai = mapper.selectScopedTop("city", "310100", null, null, null, 50);
            assertEquals(2, shanghai.size(), "上海榜 = 冻结到 310100 的两名有效玩家");
            assertEquals(Long.valueOf(PAID_ACE), shanghai.get(0).getMemberId());
            assertTrue(mapper.selectScopedTop("city", "330100", null, null, null, 50).isEmpty(),
                    "没有任何局冻结到该城市 ⇒ 空榜，不猜");
            assertEquals(3, mapper.selectScopedTop("country", "CN", null, null, null, 50).size(),
                    "国家榜按冻结 countryCode 汇总");
            assertTrue(mapper.selectScopedTop("country", "US", null, null, null, 50).isEmpty());
            assertEquals(1L, mapper.countUnattributedRuns("city", "310100", null, null, null),
                    "报名有效但没有冻结 cityCode 的局只进未归因");
            assertEquals(0L, mapper.countUnattributedRuns("world", null, null, null, null),
                    "非地理作用域没有「未归因地理」概念");

            // ④ 已退款 / 已确认到账全额退款 / 无法归因的旧局：不进排名，只进 coverage。
            assertTrue(mapper.countUnverifiedRuns("world", null, null, null, null) >= 3L,
                    "已退款/已确认全额退款/无法归因的局必须计入 coverage");
            assertEquals(4L, mapper.countRankedMembers("world", null, null, null, null));

            // ⑤ 世界榜仍是全量有效局（含没有冻结地理的 NO_CITY）。
            assertTrue(mapper.selectScopedTop("city", "330100", null, null, null, 50).isEmpty(),
                    "未标注城市的主题不得出现在别的城市");
            assertEquals(2, mapper.selectScopedTop("city", "310100", null, null, null, 50).size());

            // ⑥ 本周窗口（2026-11-02 00:00 +08:00 起）覆盖四条 fixture checkin_time。
            Date weekStart = ScopedLeaderboardPeriods.weekStart(Instant.parse("2026-11-08T12:00:00Z"));
            Date weekEnd = ScopedLeaderboardPeriods.weekEnd(weekStart);
            assertEquals(4L, mapper.countRankedMembers("world", null, null, weekStart, weekEnd),
                    "四人都落在本周窗内，跨边界另有用例");
        }
    }

    @Test
    void weekBoundaryOnRealMySqlExcludesNextMondayMidnight() throws Exception {
        SqlSessionFactory factory = factory();
        try (SqlSession session = factory.openSession(true);
             Statement st = session.getConnection().createStatement()) {
            ensureSchema(st.getConnection());
            seed(st);
            Date weekStart = ScopedLeaderboardPeriods.weekStart(Instant.parse("2026-11-08T12:00:00Z"));
            Date weekEnd = ScopedLeaderboardPeriods.weekEnd(weekStart);
            // 用与 JDBC 参数同一套转换生成墙钟字面量：不写死 JVM/服务端时区，
            // 只断「窗口右边界前 1 分钟算本周、正好在右边界不算」。
            java.text.SimpleDateFormat wallClock = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            wallClock.setTimeZone(java.util.TimeZone.getTimeZone(ScopedLeaderboardPeriods.SERVER_ZONE));
            String lastMinute = wallClock.format(new Date(weekEnd.getTime() - 60_000L));
            String boundary = wallClock.format(weekEnd);
            st.execute("delete from player_node_progress where member_id <> " + PAID_ACE);
            st.execute("update player_node_progress set checkin_time='" + lastMinute
                    + "' where member_id = " + PAID_ACE);
            st.execute("update player_node_progress set checkin_time='" + boundary
                    + "' where member_id = " + PAID_ACE + " and node_id <> 11");
            List<ScopedLeaderboardEntryVO> rows = mapper(session).selectScopedTop(
                    "quest", String.valueOf(CITY_TOPIC), CITY_TOPIC, weekStart, weekEnd, 50);
            assertEquals(1, rows.size(), "右边界（下周一 00:00）的完成不属于上一周");
            assertEquals(Long.valueOf(PAID_ACE), rows.get(0).getMemberId(), "边界前 1 分钟属于本周");
            assertEquals(Long.valueOf(1L), rows.get(0).getScore(), "只数窗口内的那一个节点");
        }
    }

    private static ScopedLeaderboardMapper mapper(SqlSession session) {
        return session.getMapper(ScopedLeaderboardMapper.class);
    }

    private static String scopeIdFor(String scope) {
        switch (scope) {
            case "quest": return String.valueOf(CITY_TOPIC);
            case "club": return String.valueOf(CLUB);
            case "city": return "310100";
            default: return null;
        }
    }

    private static Long idOf(String scope) {
        if ("quest".equals(scope)) return CITY_TOPIC;
        if ("club".equals(scope)) return CLUB;
        return null;
    }

    private static SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        PooledDataSource ds = new PooledDataSource("com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("scoped_leaderboard"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        String resource = "mapper/business/ScopedLeaderboardMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    /** 真 DDL 来自仓库脚本；只在空库上补四张本用例需要的表。 */
    private static void ensureSchema(Connection connection) throws Exception {
        try (Statement st = connection.createStatement()) {
            for (String table : new String[]{"cms_topic", "cms_registration", "refund_application",
                    "topic_route_session", "player_node_progress", "club", "club_member", "ums_member"}) {
                st.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
        }
        try (Statement st = connection.createStatement()) {
            st.execute("DROP TABLE IF EXISTS community_post");
        }
        ItSchema.apply(connection, "it-schema/cms-topic-it-schema.sql");
        ItSchema.apply(connection, "it-schema/post-game-outcome-it-schema.sql");
        ItSchema.apply(connection, "it-schema/scoped-leaderboard-it-schema.sql");
    }

    private static void seed(Statement st) throws Exception {
        st.execute("insert into ums_member(id,nickname,avatar,del_flag) values "
                + "(" + PAID_ACE + ",'王牌','a',0),(" + PAID_MID + ",'中游','b',0),"
                + "(" + REFUNDED + ",'已退','c',0),(" + SETTLED_REFUND + ",'全退','d',0),"
                + "(" + NOT_IN_CLUB + ",'路人','e',0),(" + NO_CITY + ",'无城市','f',0)");
        st.execute("insert into cms_topic(id,name,del_flag,city_code,club_id) values "
                + "(" + CITY_TOPIC + ",'上海城市定向',0,'310100'," + CLUB + "),"
                + "(" + UNATTRIBUTED_TOPIC + ",'未标注城市',0,null,null)");
        st.execute("insert into club(id,member_id,name,status,del_flag) values ("
                + CLUB + "," + PAID_MID + ",'本团',1,0)");
        st.execute("insert into club_member(club_id,member_id,role,status,del_flag) values "
                + "(" + CLUB + "," + PAID_ACE + ",0,1,0),(" + CLUB + "," + NOT_IN_CLUB + ",0,2,0)");

        paidPass(st, PAID_ACE, CITY_TOPIC);
        paidPass(st, PAID_MID, CITY_TOPIC);
        paidPass(st, NOT_IN_CLUB, CITY_TOPIC);
        paidPass(st, NO_CITY, UNATTRIBUTED_TOPIC);
        refundedPass(st, REFUNDED);
        long settledRegistrationId = paidPass(st, SETTLED_REFUND, CITY_TOPIC);
        st.execute("insert into refund_application(refund_no,source_type,source_id,status,payout_status," +
                "refund_amount,del_flag) values ('RF-BE14', 'registration', " + settledRegistrationId
                + ", 1, 4, 99.00, 0)");

        progress(st, PAID_ACE, 11, "2026-11-08 10:00:00");
        progress(st, PAID_ACE, 12, "2026-11-08 10:01:00");
        progress(st, PAID_MID, 13, "2026-11-08 10:02:00");
        progress(st, NOT_IN_CLUB, 14, "2026-11-08 10:03:00");
        progress(st, NOT_IN_CLUB, 15, "2026-11-08 10:04:00");
        progress(st, NOT_IN_CLUB, 16, "2026-11-08 10:05:00");
        progress(st, REFUNDED, 17, "2026-11-08 10:06:00");
        progress(st, SETTLED_REFUND, 18, "2026-11-08 10:07:00");
        progressOnTopic(st, NO_CITY, UNATTRIBUTED_TOPIC, 20, "2026-11-08 10:09:00");
        // 每局一份带冻结地理的 session（route 局才有快照；没有快照的局 city/country 只能是 UNKNOWN）
        frozenSession(st, PAID_ACE, CITY_TOPIC, FROZEN_SNAPSHOT_SHANGHAI_CN);
        frozenSession(st, PAID_MID, CITY_TOPIC, FROZEN_SNAPSHOT_SHANGHAI_CN);
        frozenSession(st, NOT_IN_CLUB, CITY_TOPIC, FROZEN_SNAPSHOT_BEIJING_CN);
        frozenSession(st, REFUNDED, CITY_TOPIC, FROZEN_SNAPSHOT_SHANGHAI_CN);
        frozenSession(st, SETTLED_REFUND, CITY_TOPIC, FROZEN_SNAPSHOT_SHANGHAI_CN);
        // NO_CITY 故意没有 session ⇒ 报名有效但地理 UNKNOWN。

        // 一份无法归因到任何订单的旧局（有完成事实、没有任何报名）
        st.execute("insert into player_node_progress(member_id,activity_id,topic_id,node_id,status,checkin_time," +
                "create_time,del_flag,play_instance_id) values (" + UNATTRIBUTED + ",0," + CITY_TOPIC
                + ",19,1,'2026-11-08 10:08:00','2026-11-08 10:08:00',0,'default')");
    }

    /** 自玩通行证：owner_type=1 + 主题 + 已支付 + payment_time 非空；返回自增 id。 */
    private static long paidPass(Statement st, long memberId, long topicId) throws Exception {
        String registrationNo = "REG-" + memberId + "-" + topicId;
        st.execute("insert into cms_registration(registration_no,owner_type,owner_id,ticket_id,ticket_price," +
                "member_id,original_amount,total_amount,payable_amount,payment_status,registration_status," +
                "payment_time,pay_expire_time) values ('" + registrationNo + "',1," + topicId + ",0,99.00," +
                memberId + ",99.00,99.00,99.00,2,2,'2026-11-01 09:00:00','2026-11-30 23:59:59')");
        return registrationId(st, registrationNo);
    }

    private static void refundedPass(Statement st, long memberId) throws Exception {
        st.execute("insert into cms_registration(registration_no,owner_type,owner_id,ticket_id,ticket_price," +
                "member_id,original_amount,total_amount,payable_amount,payment_status,registration_status," +
                "payment_time,pay_expire_time) values ('REF-" + memberId + "',1," + CITY_TOPIC + ",0,99.00," +
                memberId + ",99.00,99.00,99.00,4,3,'2026-11-01 09:00:00','2026-11-30 23:59:59')");
    }

    private static long registrationId(Statement st, String registrationNo) throws Exception {
        try (ResultSet keys = st.executeQuery(
                "select id from cms_registration where registration_no = '" + registrationNo + "'")) {
            keys.next();
            return keys.getLong(1);
        }
    }

    /** 一局一份冻结快照；play_instance_id 固定 default，与 player_node_progress 的 run 身份对齐。 */
    private static void frozenSession(Statement st, long memberId, long topicId, String snapshot)
            throws Exception {
        st.execute("insert into topic_route_session(activity_id,topic_id,member_id,play_instance_id," +
                "config_version,config_snapshot_json,state_json,status,seed,create_time,update_time) values (0,"
                + topicId + "," + memberId + ",'default',1,'" + snapshot + "','{}',0,1,now(),now())");
    }

    private static void progress(Statement st, long memberId, long nodeId, String at) throws Exception {
        progressOnTopic(st, memberId, CITY_TOPIC, nodeId, at);
    }

    private static void progressOnTopic(Statement st, long memberId, long topicId, long nodeId, String at)
            throws Exception {
        st.execute("insert into player_node_progress(member_id,activity_id,topic_id,node_id,status,checkin_time," +
                "create_time,del_flag,play_instance_id) values (" + memberId + ",0," + topicId + "," + nodeId +
                ",1,'" + at + "','" + at + "',0,'default')");
    }
}
