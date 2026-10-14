package com.chengyinhub.business.it;

import com.chengyinhub.business.mapper.ClubCrmMapper;
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
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 第二轮拍板第 1 条:俱乐部活动详情的主理人侧统计必须由真 SQL 数出来。
 *
 * <p>夹具(俱乐部 700 · 主题 900 · 场次 A=901 / B=902 · 查看者 7):
 * <ul>
 *   <li>节点:上架 3 个 + 下架 1 个 ⇒ nodeCount=3;</li>
 *   <li>场次 A:已付已核(核销人=7,2 张票)、已付待核、已退款(3 张票,不计);</li>
 *   <li>场次 B:已付已核(核销人=8);</li>
 *   <li>主题单:已付,在场次 A 被带队核销(核销人=7,coop_group_member_verify 记在 A);另一张主题单已付待核;</li>
 *   <li>别的俱乐部的场次(挂同一主题 id)的单:不计。</li>
 * </ul>
 */
class ClubTopicManageStatsMyBatisTest {

    private static final long CLUB = 700L;
    private static final long TOPIC = 900L;
    private static final long SESSION_A = 901L;
    private static final long SESSION_B = 902L;
    private static final long FOREIGN_SESSION = 903L;
    private static final long VIEWER = 7L;

    @Test
    void countsTopicAndSessionScopesFromRealRows() throws Exception {
        try (SqlSession session = factory().openSession(true)) {
            createTables(session.getConnection());
            seed(session.getConnection());
            ClubCrmMapper mapper = session.getMapper(ClubCrmMapper.class);

            Map<String, Object> topic = mapper.selectTopicManageStats(CLUB, TOPIC, null, VIEWER);
            assertEquals(3, num(topic, "nodeCount"), "下架节点不算站");
            assertEquals(6, num(topic, "sessionHeadcount"), "人数按订票数求和:A 2+1 + B 1 + 主题单 1+空按1;退款与别家场次不计");
            assertEquals(2, num(topic, "pendingVerifyCount"));
            assertEquals(2, num(topic, "verifiedByMeCount"), "核销人=查看者本人的两张");

            Map<String, Object> sessionA = mapper.selectTopicManageStats(CLUB, TOPIC, SESSION_A, VIEWER);
            assertEquals(4, num(sessionA, "sessionHeadcount"), "A 的场次单 2+1 人 + 在 A 带队核销的主题单 1 人");
            assertEquals(1, num(sessionA, "pendingVerifyCount"), "说不清属于哪场的待核主题单不进本场");
            assertEquals(2, num(sessionA, "verifiedByMeCount"));

            Map<String, Object> sessionB = mapper.selectTopicManageStats(CLUB, TOPIC, SESSION_B, VIEWER);
            assertEquals(1, num(sessionB, "sessionHeadcount"));
            assertEquals(0, num(sessionB, "verifiedByMeCount"), "别人核销的不算我核销的");

            assertEquals(0, num(mapper.selectTopicManageStats(CLUB, TOPIC, FOREIGN_SESSION, VIEWER),
                    "sessionHeadcount"), "别的俱乐部的场次不得借本主题出数");
            assertNull(mapper.selectTopicManageStats(701L, TOPIC, null, VIEWER), "主题不属于该俱乐部 ⇒ 不回任何数");

            // 本场真状态(核销员核销区的判据):取消 > 游戏总局 > 服务端时钟对排期
            assertEquals("NOT_PREPARED", mapper.selectTopicManageStats(CLUB, TOPIC, SESSION_A, VIEWER).get("sessionStatus"),
                    "没排期、没总局 ⇒ 未开始");
            assertNull(topic.get("sessionStatus"), "主题下两场、没带 activityId ⇒ 说不清是哪场,不给");
            assertNull(mapper.selectTopicManageStats(CLUB, TOPIC, FOREIGN_SESSION, VIEWER).get("sessionStatus"),
                    "别的俱乐部的场次不得借本主题报状态");
            assertEquals("RUNNING", status(mapper, SCHED_TOPIC, 911L), "已过开始未到结束 ⇒ 进行中");
            assertEquals("FINISHED", status(mapper, SCHED_TOPIC, 912L), "已过结束 ⇒ 已结束");
            assertEquals("NOT_PREPARED", status(mapper, SCHED_TOPIC, 913L), "未到开始 ⇒ 未开始");
            assertEquals("PREPARING", status(mapper, SCHED_TOPIC, 914L), "有游戏总局 ⇒ 以总局状态为准,不看排期");
            assertEquals("CANCELLED", status(mapper, SCHED_TOPIC, 915L), "已取消优先于一切");
            assertEquals("FINISHED", status(mapper, SINGLE_TOPIC, null), "主题下恰好一场 ⇒ 不带 activityId 也按那一场");

            // F-W5-02:0 报名的场次 / 主题,left join 落空的那一行不能被数成 1 张待核销
            Map<String, Object> emptySession = mapper.selectTopicManageStats(CLUB, SCHED_TOPIC, 913L, VIEWER);
            assertEquals(0, num(emptySession, "sessionHeadcount"));
            assertEquals(0, num(emptySession, "pendingVerifyCount"), "0 报名场次不得显示「待核销 1」");
            assertEquals(0, num(emptySession, "verifiedByMeCount"));
            assertEquals(0, num(mapper.selectTopicManageStats(CLUB, SCHED_TOPIC, null, VIEWER), "pendingVerifyCount"),
                    "0 报名主题同样不得出幽灵待核销");
            assertEquals(0, num(mapper.selectTopicManageStats(CLUB, TOPIC, FOREIGN_SESSION, VIEWER), "pendingVerifyCount"),
                    "别的俱乐部的场次落空时也不得出幽灵待核销");

            // CU-C-13:承接方(主题有指向它的生效合作单)能看自己承接的场次,只数自己场次的人;主题票不算给它。
            Map<String, Object> undertaker = mapper.selectTopicManageStats(UNDERTAKER, TOPIC, null, VIEWER);
            assertEquals(3, num(undertaker, "nodeCount"));
            assertEquals(2, num(undertaker, "sessionHeadcount"), "只数承接方自己场次 904 的 2 人,不含发起方场次与主题票");
            assertEquals(1, num(mapper.selectTopicManageStats(UNDERTAKER, TOPIC, UNDERTAKER_SESSION, VIEWER),
                    "pendingVerifyCount"));
            assertEquals("NOT_PREPARED", undertaker.get("sessionStatus"), "承接方名下恰好一场 ⇒ 按那一场");
            Map<String, Object> probe = mapper.selectTopicManageStats(UNDERTAKER, TOPIC, SESSION_A, VIEWER);
            assertEquals(0, num(probe, "sessionHeadcount"), "承接方传发起方场次 id 不得读到对方主题票人数");
            assertEquals(0, num(probe, "verifiedByMeCount"));
            assertNull(mapper.selectTopicManageStats(LEGACY_CLUB, TOPIC, null, VIEWER), "旧版 type2 邀约生成的单不算生效承接");
            assertNull(mapper.selectTopicManageStats(CANCELLED_CLUB, TOPIC, null, VIEWER), "已失效合作单不算承接");
        }
    }

    private static final long UNDERTAKER = 702L;
    private static final long UNDERTAKER_SESSION = 904L;
    private static final long LEGACY_CLUB = 703L;
    private static final long CANCELLED_CLUB = 704L;

    private static final long SCHED_TOPIC = 910L;
    private static final long SINGLE_TOPIC = 920L;

    private static Object status(ClubCrmMapper mapper, long topicId, Long activityId) {
        return mapper.selectTopicManageStats(CLUB, topicId, activityId, VIEWER).get("sessionStatus");
    }

    private static int num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("club_topic_manage_stats"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        try (InputStream in = Resources.getResourceAsStream("mapper/business/ClubCrmMapper.xml")) {
            new XMLMapperBuilder(in, cfg, "mapper/business/ClubCrmMapper.xml", cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private void createTables(java.sql.Connection connection) throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, club_id BIGINT, del_flag INT DEFAULT 0,"
                    + " member_id BIGINT)");
            st.execute("CREATE TABLE cms_topic_node (id BIGINT PRIMARY KEY, topic_id BIGINT, status INT)");
            st.execute("CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT, club_id BIGINT,"
                    + " del_flag INT DEFAULT 0, start_date DATETIME NULL, end_date DATETIME NULL, cancel_time DATETIME NULL)");
            st.execute("CREATE TABLE game_session (id BIGINT PRIMARY KEY AUTO_INCREMENT, activity_id BIGINT NOT NULL,"
                    + " status VARCHAR(32) NOT NULL, UNIQUE KEY uk_game_session_activity (activity_id))");
            st.execute("CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, member_id BIGINT, owner_id BIGINT,"
                    + " owner_type INT, payment_status INT, verification_status INT, verifier_id BIGINT, order_num INT)");
            st.execute("CREATE TABLE coop_group_member_verify (id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                    + " topic_id BIGINT, activity_id BIGINT, registration_id BIGINT, verifier_member_id BIGINT)");
            st.execute("CREATE TABLE coop_order (id BIGINT PRIMARY KEY, invite_id BIGINT, topic_id BIGINT,"
                    + " to_id BIGINT, to_type VARCHAR(16), status INT, del_flag INT DEFAULT 0)");
            st.execute("CREATE TABLE coop_invite (id BIGINT PRIMARY KEY, invite_type INT)");
        }
    }

    private void seed(java.sql.Connection connection) throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("INSERT INTO cms_topic (id, club_id, del_flag) VALUES (" + TOPIC + ", " + CLUB + ", 0)");
            st.execute("INSERT INTO cms_topic_node VALUES (1, " + TOPIC + ", 1), (2, " + TOPIC + ", 1),"
                    + " (3, " + TOPIC + ", 1), (4, " + TOPIC + ", 0)");
            st.execute("INSERT INTO cms_activity (id, topic_id, club_id, del_flag) VALUES (" + SESSION_A + ", " + TOPIC + ", " + CLUB + ", 0),"
                    + " (" + SESSION_B + ", " + TOPIC + ", " + CLUB + ", 0),"
                    + " (" + FOREIGN_SESSION + ", " + TOPIC + ", 701, 0)");
            st.execute("INSERT INTO cms_topic (id, club_id, del_flag) VALUES (" + SCHED_TOPIC + ", " + CLUB + ", 0),"
                    + " (" + SINGLE_TOPIC + ", " + CLUB + ", 0)");
            st.execute("INSERT INTO cms_activity (id, topic_id, club_id, del_flag, start_date, end_date, cancel_time) VALUES"
                    + " (911, " + SCHED_TOPIC + ", " + CLUB + ", 0, now() - interval 1 hour, now() + interval 1 hour, NULL),"
                    + " (912, " + SCHED_TOPIC + ", " + CLUB + ", 0, now() - interval 3 hour, now() - interval 1 hour, NULL),"
                    + " (913, " + SCHED_TOPIC + ", " + CLUB + ", 0, now() + interval 1 hour, now() + interval 3 hour, NULL),"
                    + " (914, " + SCHED_TOPIC + ", " + CLUB + ", 0, now() - interval 1 hour, now() + interval 1 hour, NULL),"
                    + " (915, " + SCHED_TOPIC + ", " + CLUB + ", 0, now() - interval 1 hour, now() + interval 1 hour, now()),"
                    + " (921, " + SINGLE_TOPIC + ", " + CLUB + ", 0, now() - interval 3 hour, now() - interval 1 hour, NULL),"
                    + " (922, " + SINGLE_TOPIC + ", " + CLUB + ", 2, now() - interval 1 hour, now() + interval 1 hour, NULL)");
            st.execute("INSERT INTO game_session (activity_id, status) VALUES (914, 'PREPARING'), (915, 'RUNNING')");
            st.execute("INSERT INTO cms_registration VALUES"
                    + " (11, 101, " + SESSION_A + ", 2, 2, 1, " + VIEWER + ", 2),"
                    + " (12, 102, " + SESSION_A + ", 2, 2, 0, NULL, 1),"
                    + " (13, 103, " + SESSION_A + ", 2, 4, 0, NULL, 3),"
                    + " (14, 104, " + SESSION_B + ", 2, 2, 1, 8, 1),"
                    + " (15, 105, " + TOPIC + ", 1, 2, 1, " + VIEWER + ", 1),"
                    + " (16, 106, " + TOPIC + ", 1, 2, 0, NULL, NULL),"
                    + " (17, 107, " + FOREIGN_SESSION + ", 2, 2, 0, NULL, 5)");
            st.execute("INSERT INTO coop_group_member_verify (topic_id, activity_id, registration_id, verifier_member_id)"
                    + " VALUES (" + TOPIC + ", " + SESSION_A + ", 15, " + VIEWER + ")");
            st.execute("INSERT INTO coop_invite VALUES (51, 1), (52, 2), (53, 1)");
            st.execute("INSERT INTO coop_order VALUES (61, 51, " + TOPIC + ", " + UNDERTAKER + ", 'club', 0, 0),"
                    + " (62, 52, " + TOPIC + ", " + LEGACY_CLUB + ", 'club', 0, 0),"
                    + " (63, 53, " + TOPIC + ", " + CANCELLED_CLUB + ", 'club', 1, 0)");
            st.execute("INSERT INTO cms_activity (id, topic_id, club_id, del_flag) VALUES (" + UNDERTAKER_SESSION + ", "
                    + TOPIC + ", " + UNDERTAKER + ", 0)");
            st.execute("INSERT INTO cms_registration VALUES"
                    + " (18, 108, " + UNDERTAKER_SESSION + ", 2, 2, 1, 9, 1),"
                    + " (19, 109, " + UNDERTAKER_SESSION + ", 2, 2, 0, NULL, 1)");
        }
    }
}
