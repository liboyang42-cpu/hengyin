package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MyBatis + 本地 MySQL 集成测试:俱乐部「本团贡献榜」的 SQL 口径。
 *
 * 【为什么这个测试必须打真库,mock 单测不够】
 * 本轮改造的全部要害就在一句 SQL 里 —— 排序键从 ums_member.point(全局积分)换成本团贡献。
 * mock 掉 mapper 的单测只能证明「Service 拿到数以后算得对」,它对
 * 「SQL 到底 select 的是哪一列、join 的是哪张表」一无所知 —— 病灶原样保留也照样绿。
 * 所以口径必须在真 MySQL 上,用真数据跑真 SQL 来验。
 *
 * 【本用例怎么保证「不是假绿」】
 * 数据刻意构造成【全局积分与本团贡献完全反序】:
 *   全局积分 point:  103(9999) > 102(500) > 101(1) > 100(0)
 *   本团贡献 score:  101(40)   > 102(20)  > 100(5) > 103(0)
 * ⇒ 若 SQL 退回按 m.point 排,断言的名次会整体翻转、立刻红。
 * 负控实测(2026-07-16):把 selectClubContribution 换回 `order by ifnull(m.point,0) desc`,
 * 本用例 contributionOrder_notGlobalPoints 红;撤销后绿。
 *
 * 本地库缺失时自动 skip(不破坏 CI/常规 mvn test)。手动跑法:
 *   1) brew services start mysql
 *   2) CREATE DATABASE chengyin_mybatis_it;
 *      CREATE USER 'ituser'@'127.0.0.1' IDENTIFIED BY 'itpass'; GRANT ALL ON chengyin_mybatis_it.* TO 'ituser'@'127.0.0.1';
 *   ⚠️ 2026-08-19 起改成**一类一库**:库由 MybatisItDatabase 自己 DROP+CREATE(见该类注释),
 *      本地不再需要预先建 chengyin_mybatis_it、也不再用 ituser —— 默认走 root(本机空口令),
 *      连接信息用 CHENGYIN_MYBATIS_IT_HOST/PORT/USER/PASSWORD 覆盖。
 *      (建表由本用例的 ensureSchema() 自己完成,列定义抄自 migration_club.sql /
 *       migration_p2_player_growth_badge.sql / migration_play_progress_points.sql)
 *   3) mvn -pl chengyinhub-admin -am test -Dtest=ClubLeaderboardContributionMyBatisIT
 */
class ClubLeaderboardContributionMyBatisIT {

    private static final long CLUB = 9001L;      // 本团
    private static final long OTHER_CLUB = 9002L; // 别的团(用来证明跨团数据不泄漏进来)

    private static final long OWNER = 100L;  // 团长:自己没通关,但名下有被通关的团 ⇒ 只有发起分
    private static final long ACE   = 101L;  // 通关 2 场 + 名下 1 个被通关的团 ⇒ 分最高
    private static final long MID   = 102L;  // 通关 1 场
    private static final long ZERO  = 103L;  // 全局积分最高、本团零贡献 ⇒ 必须垫底
    private static final long SPLIT = 104L;  // 同一个团分两个活动玩(3月+6月)⇒ 用时不能跨活动串成一段
    private static final long SOLO  = 105L;  // 只打卡一次就通关 ⇒ MAX-MIN=0,不能算成「0 配速=无限快」

    private static final long T_OWNER_10KM = 1001L; // OWNER 发起, 10km, 被 ACE/MID 通关
    private static final long T_ACE_5KM    = 1002L; // ACE   发起,  5km, 被 ACE 通关
    private static final long T_OTHER_99KM = 1003L; // 别的团, 99km, ACE 通关过 ⇒ 不该计入本团
    private static final long T_DRAFT_50KM = 1004L; // OWNER 发起, 50km, 无人通关 ⇒ 草稿团,不给发起分

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("ClubMember", ClubMember.class);
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("club_leaderboard"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        try (InputStream in = Resources.getResourceAsStream("mapper/business/ClubMemberMapper.xml")) {
            new XMLMapperBuilder(in, cfg, "mapper/business/ClubMemberMapper.xml", cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    /** 列定义抄自真实迁移;cms_topic 是与 CoopMapperMyBatisIT 共用的表,只补本用例要的列(加列不影响它)。 */
    private void ensureSchema(Statement st) throws Exception {
        // cms_topic 用生产真实 DDL(空库上本套原来因缺它而红,2026-08-14 实测)
            ItSchema.apply(st.getConnection(), "it-schema/cms-topic-it-schema.sql");
            st.execute("DROP TABLE IF EXISTS club"); st.execute("CREATE TABLE club ("
                + " id BIGINT(20) NOT NULL AUTO_INCREMENT, member_id BIGINT(20) NOT NULL,"
                + " name VARCHAR(64) NULL, status TINYINT(1) NOT NULL DEFAULT 0,"
                + " member_count INT NOT NULL DEFAULT 0, del_flag INT NOT NULL DEFAULT 0,"
                + " PRIMARY KEY (id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        st.execute("DROP TABLE IF EXISTS club_member"); st.execute("CREATE TABLE club_member ("
                + " id BIGINT(20) NOT NULL AUTO_INCREMENT, club_id BIGINT(20) NOT NULL,"
                + " member_id BIGINT(20) NOT NULL, role TINYINT(1) NOT NULL DEFAULT 0,"
                + " status TINYINT(1) NOT NULL DEFAULT 1, join_time DATETIME NULL,"
                + " del_flag INT NOT NULL DEFAULT 0, PRIMARY KEY (id),"
                + " UNIQUE KEY uk_club_member_uniq (club_id, member_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        st.execute("DROP TABLE IF EXISTS ums_member"); st.execute("CREATE TABLE ums_member ("
                + " id BIGINT(20) NOT NULL AUTO_INCREMENT, nickname VARCHAR(64) NULL,"
                + " avatar VARCHAR(255) NULL, point BIGINT NULL DEFAULT 0,"
                + " PRIMARY KEY (id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        st.execute("DROP TABLE IF EXISTS player_badge"); st.execute("CREATE TABLE player_badge ("
                + " id BIGINT NOT NULL AUTO_INCREMENT, member_id BIGINT NOT NULL,"
                + " badge_code VARCHAR(64) NOT NULL, badge_name VARCHAR(128) DEFAULT NULL,"
                + " source_type INT DEFAULT NULL, source_id BIGINT DEFAULT 0,"
                + " obtain_time DATETIME DEFAULT NULL, del_flag INT DEFAULT 0,"
                + " create_time DATETIME DEFAULT NULL, PRIMARY KEY (id),"
                + " UNIQUE KEY uk_member_badge (member_id, badge_code, source_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        st.execute("DROP TABLE IF EXISTS player_node_progress"); st.execute("CREATE TABLE player_node_progress ("
                + " id BIGINT NOT NULL AUTO_INCREMENT, member_id BIGINT NOT NULL,"
                + " activity_id BIGINT NOT NULL, topic_id BIGINT DEFAULT NULL, node_id BIGINT NOT NULL,"
                + " status TINYINT DEFAULT 1, checkin_time DATETIME DEFAULT NULL,"
                + " del_flag INT DEFAULT 0, create_time DATETIME DEFAULT NULL, PRIMARY KEY (id),"
                + " UNIQUE KEY uk_member_activity_node (member_id, activity_id, node_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        // 共用的 cms_topic 建于 CoopMapperMyBatisIT,可能缺本用例要的两列。
        addColumnIfMissing(st, "cms_topic", "club_id", "BIGINT NULL");
        addColumnIfMissing(st, "cms_topic", "total_mileage", "DECIMAL(10,2) NULL");
    }

    /** MySQL 8 没有 ADD COLUMN IF NOT EXISTS(那是 MariaDB 专属,MySQL 8 上直接 1064),只能先查 information_schema。 */
    private void addColumnIfMissing(Statement st, String table, String col, String type) throws Exception {
        java.sql.ResultSet rs = st.executeQuery(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                        + " AND TABLE_NAME = '" + table + "' AND COLUMN_NAME = '" + col + "'");
        rs.next();
        boolean exists = rs.getInt(1) > 0;
        rs.close();
        if (!exists) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + col + " " + type);
        }
    }

    private static final String ALL_MEMBERS = OWNER + "," + ACE + "," + MID + "," + ZERO + "," + SPLIT + "," + SOLO;

    private void seed(Statement st) throws Exception {
        st.execute("DELETE FROM club WHERE id IN (" + CLUB + "," + OTHER_CLUB + ")");
        st.execute("DELETE FROM club_member WHERE club_id IN (" + CLUB + "," + OTHER_CLUB + ")");
        st.execute("DELETE FROM ums_member WHERE id IN (" + ALL_MEMBERS + ")");
        st.execute("DELETE FROM cms_topic WHERE id IN ("
                + T_OWNER_10KM + "," + T_ACE_5KM + "," + T_OTHER_99KM + "," + T_DRAFT_50KM + ")");
        st.execute("DELETE FROM player_badge WHERE member_id IN (" + ALL_MEMBERS + ")");
        st.execute("DELETE FROM player_node_progress WHERE member_id IN (" + ALL_MEMBERS + ")");

        st.execute("INSERT INTO club (id, member_id, name, status) VALUES (" + CLUB + "," + OWNER + ",'本团',1)");
        st.execute("INSERT INTO club (id, member_id, name, status) VALUES (" + OTHER_CLUB + ",777,'别的团',1)");

        // 团长(OWNER)只存在于 club.member_id,不在 club_member —— 复刻线上「群主不入成员表」的形态,
        // 顺带验证 SQL 里那个 union 分支还在(去掉它团长会整个从榜上消失)。
        st.execute("INSERT INTO club_member (club_id, member_id, status, join_time) VALUES "
                + "(" + CLUB + "," + ACE + ",1,'2026-01-01 00:00:00'),"
                + "(" + CLUB + "," + MID + ",1,'2026-01-02 00:00:00'),"
                + "(" + CLUB + "," + ZERO + ",1,'2026-01-03 00:00:00'),"
                + "(" + CLUB + "," + SPLIT + ",1,'2026-01-04 00:00:00'),"
                + "(" + CLUB + "," + SOLO + ",1,'2026-01-05 00:00:00')");

        // ★ 全局积分刻意与本团贡献【完全反序】:退回按 m.point 排 ⇒ 名次翻转 ⇒ 测试红。
        st.execute("INSERT INTO ums_member (id, nickname, avatar, point) VALUES "
                + "(" + OWNER + ",'团长','a0.png',0),"
                + "(" + ACE   + ",'阿斯','a1.png',1),"
                + "(" + MID   + ",'中游','a2.png',500),"
                + "(" + ZERO  + ",'积分王','a3.png',9999),"
                + "(" + SPLIT + ",'分两次','a4.png',0),"
                + "(" + SOLO  + ",'一次过','a5.png',0)");

        st.execute("INSERT INTO cms_topic (id, member_id, club_id, total_mileage, del_flag) VALUES "
                + "(" + T_OWNER_10KM + "," + OWNER + "," + CLUB       + ",10.00,0),"
                + "(" + T_ACE_5KM    + "," + ACE   + "," + CLUB       + ",5.00,0),"
                + "(" + T_OTHER_99KM + "," + ACE   + "," + OTHER_CLUB + ",99.00,0),"
                + "(" + T_DRAFT_50KM + "," + OWNER + "," + CLUB       + ",50.00,0)");

        // 通关徽章。ZERO 一个都没有;ACE 那张 T_OTHER_99KM 属别的团,不该被算进本团。
        st.execute("INSERT INTO player_badge (member_id, badge_code, source_id, del_flag) VALUES "
                + "(" + ACE   + ",'TOPIC_CLEAR'," + T_OWNER_10KM + ",0),"
                + "(" + ACE   + ",'TOPIC_CLEAR'," + T_ACE_5KM    + ",0),"
                + "(" + ACE   + ",'TOPIC_CLEAR'," + T_OTHER_99KM + ",0),"
                + "(" + MID   + ",'TOPIC_CLEAR'," + T_OWNER_10KM + ",0),"
                + "(" + SPLIT + ",'TOPIC_CLEAR'," + T_ACE_5KM    + ",0),"
                + "(" + SOLO  + ",'TOPIC_CLEAR'," + T_OWNER_10KM + ",0)");

        // 节点打卡 ⇒ 实测用时(每人每团 MAX-MIN)。
        //  ACE  T_OWNER_10KM: 10:00→11:00 = 60min;T_ACE_5KM: 10:00→10:30 = 30min ⇒ 合计 90min / 15km = 6.0
        //  MID  T_OWNER_10KM: 10:00→12:00 = 120min                              ⇒ 120min / 10km = 12.0
        //  ZERO 在 T_OWNER_10KM 上有打卡但【没有通关徽章】⇒ 用时必须为 0(验证用时子查询确实按徽章过滤)
        st.execute("INSERT INTO player_node_progress (member_id, activity_id, topic_id, node_id, checkin_time, del_flag) VALUES "
                + "(" + ACE + ",1," + T_OWNER_10KM + ",1,'2026-03-01 10:00:00',0),"
                + "(" + ACE + ",1," + T_OWNER_10KM + ",2,'2026-03-01 11:00:00',0),"
                + "(" + ACE + ",2," + T_ACE_5KM    + ",3,'2026-03-02 10:00:00',0),"
                + "(" + ACE + ",2," + T_ACE_5KM    + ",4,'2026-03-02 10:30:00',0),"
                + "(" + MID + ",1," + T_OWNER_10KM + ",1,'2026-03-01 10:00:00',0),"
                + "(" + MID + ",1," + T_OWNER_10KM + ",2,'2026-03-01 12:00:00',0),"
                + "(" + ZERO + ",1," + T_OWNER_10KM + ",1,'2026-03-01 10:00:00',0),"
                + "(" + ZERO + ",1," + T_OWNER_10KM + ",2,'2026-03-01 23:00:00',0),"
                // SPLIT 同一个团(T_ACE_5KM)分两个【活动】玩:活动10 三月 20min、活动11 六月 40min。
                // 进度按 activity_id 隔离(见 migration_play_progress_points.sql:13),所以正确用时 = 20+40 = 60min。
                // 若按 (member,topic) 整体取 MAX-MIN,会算成「六月最后一次 - 三月第一次」= 三个月 ≈ 132480min。
                + "(" + SPLIT + ",10," + T_ACE_5KM + ",1,'2026-03-01 10:00:00',0),"
                + "(" + SPLIT + ",10," + T_ACE_5KM + ",2,'2026-03-01 10:20:00',0),"
                + "(" + SPLIT + ",11," + T_ACE_5KM + ",1,'2026-06-01 10:00:00',0),"
                + "(" + SPLIT + ",11," + T_ACE_5KM + ",2,'2026-06-01 10:40:00',0),"
                // SOLO 只打卡一次就通关(单节点团)⇒ MAX-MIN = 0
                + "(" + SOLO + ",20," + T_OWNER_10KM + ",1,'2026-03-01 10:00:00',0)");
    }

    private SqlSessionFactory factoryOrSkip() {
        try {
            SqlSessionFactory f = factory();
            try (SqlSession probe = f.openSession()) {
                probe.getConnection().isValid(2);
            }
            return f;
        } catch (Throwable t) {
            // ★ 2026-08-14:原本是 Assumptions.assumeTrue(false, ...) —— 库连不上就整套 skip,
            // 而 skip 在构建里算**绿**。永远不会红的测试比没有测试更危险:它让人以为验过了。
            // 现在缺库 = 红。跑法见 ci/backend-test.sh(CI 的 backend job 自带 MySQL 服务)。
            throw new IllegalStateException("IT 前置不满足(库不可达 / Mapper XML / schema 配置有误),本套必须真跑不得跳过:" + t.getMessage(), t);
        }
    }

    private Map<String, Object> rowOf(List<Map<String, Object>> rows, long memberId) {
        for (Map<String, Object> r : rows) {
            if (((Number) r.get("memberId")).longValue() == memberId) return r;
        }
        return null;
    }

    private static BigDecimal dec(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    /** ★ 核心口径:每个成员的四项原始度量,逐一断言。 */
    @Test
    void contributionMetrics_scopedToThisClubOnly() throws Exception {
        SqlSessionFactory f = factoryOrSkip();
        try (SqlSession s = f.openSession(true)) {
            Statement st = s.getConnection().createStatement();
            ensureSchema(st);
            seed(st);

            List<Map<String, Object>> rows = s.getMapper(ClubMemberMapper.class).selectClubContribution(CLUB, null);
            assertEquals(6, rows.size(), "5 名成员 + 团长(经 union 并入),团长不在 club_member 也必须在榜上");

            Map<String, Object> ace = rowOf(rows, ACE);
            assertNotNull(ace);
            assertEquals(2L, ((Number) ace.get("clearCount")).longValue(), "ACE 通关本团 2 场(别的团那场不算)");
            assertEquals(0, new BigDecimal("15.00").compareTo(dec(ace.get("mileage"))),
                    "ACE 里程 = 10+5 = 15km;若把别的团那 99km 算进来就是 114 —— 跨团泄漏");
            assertEquals(90L, ((Number) ace.get("durationMin")).longValue(), "ACE 实测用时 60+30=90min");
            assertEquals(1L, ((Number) ace.get("hostedCount")).longValue(), "ACE 名下 T_ACE_5KM 被通关 ⇒ 发起数 1");

            Map<String, Object> mid = rowOf(rows, MID);
            assertNotNull(mid);
            assertEquals(1L, ((Number) mid.get("clearCount")).longValue());
            assertEquals(0, new BigDecimal("10.00").compareTo(dec(mid.get("mileage"))));
            assertEquals(120L, ((Number) mid.get("durationMin")).longValue());
            assertEquals(0L, ((Number) mid.get("hostedCount")).longValue(), "MID 没发起过团");

            Map<String, Object> owner = rowOf(rows, OWNER);
            assertNotNull(owner, "团长必须在榜上(union 分支)");
            assertEquals(0L, ((Number) owner.get("clearCount")).longValue(), "团长自己一场没通关");
            assertEquals(1L, ((Number) owner.get("hostedCount")).longValue(),
                    "团长名下两个团:T_OWNER_10KM 被通关(计1)、T_DRAFT_50KM 无人通关(不计)"
                            + " —— 若为 2 则草稿团在刷发起分");

            Map<String, Object> zero = rowOf(rows, ZERO);
            assertNotNull(zero);
            assertEquals(0L, ((Number) zero.get("clearCount")).longValue(), "ZERO 全局积分 9999 但本团零通关");
            assertEquals(0, BigDecimal.ZERO.compareTo(dec(zero.get("mileage"))));
            assertEquals(0L, ((Number) zero.get("durationMin")).longValue(),
                    "ZERO 有节点打卡但无通关徽章 ⇒ 用时不计(用时子查询必须按徽章过滤)");
        }
    }

    /**
     * ★ 负控靶心:名次必须由本团贡献决定,不能由全局积分决定。
     * 数据构造成两者完全反序,所以「SQL 退回按 m.point 排」这个具体的回归会让本用例红。
     */
    @Test
    void contributionOrder_notGlobalPoints() throws Exception {
        SqlSessionFactory f = factoryOrSkip();
        try (SqlSession s = f.openSession(true)) {
            Statement st = s.getConnection().createStatement();
            ensureSchema(st);
            seed(st);

            List<Map<String, Object>> rows = s.getMapper(ClubMemberMapper.class).selectClubContribution(CLUB, null);

            // 按本设计的权重(10/1/5)算出综合分,复刻 Service 的口径
            BigDecimal aceScore   = score(rowOf(rows, ACE));    // 10*2 + 15 + 5*1 = 40
            BigDecimal midScore   = score(rowOf(rows, MID));    // 10*1 + 10 + 0   = 20
            BigDecimal ownerScore = score(rowOf(rows, OWNER));  // 0    + 0  + 5*1 = 5
            BigDecimal zeroScore  = score(rowOf(rows, ZERO));   // 0

            assertEquals(0, new BigDecimal("40").compareTo(aceScore), "ACE 综合分");
            assertEquals(0, new BigDecimal("20").compareTo(midScore), "MID 综合分");
            assertEquals(0, new BigDecimal("5").compareTo(ownerScore), "团长综合分(仅发起分)");
            assertEquals(0, BigDecimal.ZERO.compareTo(zeroScore), "积分王本团零贡献");

            // 贡献序:ACE > MID > OWNER > ZERO
            assertTrue(aceScore.compareTo(midScore) > 0 && midScore.compareTo(ownerScore) > 0
                            && ownerScore.compareTo(zeroScore) > 0,
                    "贡献序必须是 ACE > MID > OWNER > ZERO");

            // 全局积分序:ZERO > MID > ACE > OWNER —— 与贡献序【完全相反】。
            // 这两条一起把「排序键还是全局积分」这个回归钉死:它同时满足不了。
            long pAce = pointOf(st, ACE), pMid = pointOf(st, MID), pZero = pointOf(st, ZERO);
            assertTrue(pZero > pMid && pMid > pAce, "前提:全局积分与贡献反序(积分王最高、ACE 很低)");
            assertTrue(aceScore.compareTo(zeroScore) > 0,
                    "★ 全局积分 9999 的人必须输给积分 1 但真跑过团的人 —— 这正是本次改造的意义");
        }
    }

    /**
     * 用时必须【按活动分段再求和】,不能把同一个团的多次游玩串成一段。
     * player_node_progress 的进度是按 activity_id 隔离的(migration_play_progress_points.sql:13),
     * 同一个团可以挂多个活动(cms_activity.topic_id),所以同一 (member, topic) 会有跨月的多组打卡。
     */
    @Test
    void duration_segmentedPerActivity_notSpanningMonths() throws Exception {
        SqlSessionFactory f = factoryOrSkip();
        try (SqlSession s = f.openSession(true)) {
            Statement st = s.getConnection().createStatement();
            ensureSchema(st);
            seed(st);

            List<Map<String, Object>> rows = s.getMapper(ClubMemberMapper.class).selectClubContribution(CLUB, null);
            long dur = ((Number) rowOf(rows, SPLIT).get("durationMin")).longValue();
            assertEquals(60L, dur,
                    "同一团分两个活动玩:三月 20min + 六月 40min = 60min。"
                            + "若得到 ~132480(三个月)⇒ 用时子查询把跨活动的打卡串成了一段");
        }
    }

    /** 单节点团/只打卡一次 ⇒ MAX-MIN=0。0 用时不能被当成「跑得无限快」。 */
    @Test
    void singleCheckin_zeroDuration() throws Exception {
        SqlSessionFactory f = factoryOrSkip();
        try (SqlSession s = f.openSession(true)) {
            Statement st = s.getConnection().createStatement();
            ensureSchema(st);
            seed(st);

            List<Map<String, Object>> rows = s.getMapper(ClubMemberMapper.class).selectClubContribution(CLUB, null);
            Map<String, Object> solo = rowOf(rows, SOLO);
            assertEquals(0L, ((Number) solo.get("durationMin")).longValue(), "只打卡一次 ⇒ 用时 0");
            assertEquals(0, new BigDecimal("10.00").compareTo(dec(solo.get("mileage"))), "但里程照算 10km");
            // ⇒ Service 必须把「0 用时」也判成无配速,否则 0/10 = 0.0 min/km 会屠榜。
            // 该断言在 ClubLeaderboardWeightingTest.zeroDuration_paceIsNull。
        }
    }

    @Test
    void currentCompletionBadges_contributeToAllMetrics() throws Exception {
        SqlSessionFactory f = factoryOrSkip();
        try (SqlSession s = f.openSession(true)) {
            Statement st = s.getConnection().createStatement();
            ensureSchema(st);
            seed(st);
            st.execute("UPDATE player_badge SET source_type=2, badge_code=CONCAT('TOPIC_FINISH_',source_id)");

            List<Map<String, Object>> rows = s.getMapper(ClubMemberMapper.class).selectClubContribution(CLUB, null);
            Map<String, Object> ace = rowOf(rows, ACE);
            assertEquals(2L, ((Number) ace.get("clearCount")).longValue());
            assertEquals(0, new BigDecimal("15.00").compareTo(dec(ace.get("mileage"))));
            assertEquals(90L, ((Number) ace.get("durationMin")).longValue());
            assertEquals(1L, ((Number) ace.get("hostedCount")).longValue());
            assertEquals(1L, ((Number) rowOf(rows, OWNER).get("hostedCount")).longValue());
            assertEquals(60L, ((Number) rowOf(rows, SPLIT).get("durationMin")).longValue());
        }
    }

    @Test
    void legacyAndCurrentBadges_doNotDoubleCountOrAcceptUnrelatedBadges() throws Exception {
        SqlSessionFactory f = factoryOrSkip();
        try (SqlSession s = f.openSession(true)) {
            Statement st = s.getConnection().createStatement();
            ensureSchema(st);
            seed(st);
            st.execute("UPDATE cms_topic SET total_mileage=10 WHERE id=" + T_ACE_5KM);
            st.execute("INSERT INTO player_badge (member_id,badge_code,source_type,source_id,del_flag)"
                    + " SELECT member_id,CONCAT('TOPIC_FINISH_',source_id),2,source_id,0 FROM player_badge");
            st.execute("INSERT INTO player_badge (member_id,badge_code,source_type,source_id,del_flag) VALUES "
                    + "(" + ZERO + ",'TOPIC_FINISH_99999',2," + T_OWNER_10KM + ",0),"
                    + "(" + ZERO + ",'TOPIC_FINISH_" + T_OWNER_10KM + "',1," + T_OWNER_10KM + ",0),"
                    + "(" + ZERO + ",'TOPIC_FINISH_" + T_ACE_5KM + "',2," + T_ACE_5KM + ",1)");

            List<Map<String, Object>> rows = s.getMapper(ClubMemberMapper.class).selectClubContribution(CLUB, null);
            Map<String, Object> ace = rowOf(rows, ACE);
            assertEquals(2L, ((Number) ace.get("clearCount")).longValue());
            assertEquals(0, new BigDecimal("20.00").compareTo(dec(ace.get("mileage"))), "双格式不重复,同里程不同主题仍分别累计");
            assertEquals(90L, ((Number) ace.get("durationMin")).longValue());
            assertEquals(1L, ((Number) ace.get("hostedCount")).longValue());
            assertEquals(1L, ((Number) rowOf(rows, OWNER).get("hostedCount")).longValue());
            Map<String, Object> zero = rowOf(rows, ZERO);
            assertEquals(0L, ((Number) zero.get("clearCount")).longValue());
            assertEquals(0, BigDecimal.ZERO.compareTo(dec(zero.get("mileage"))));
            assertEquals(0L, ((Number) zero.get("durationMin")).longValue());
        }
    }

    @Test
    void currentBadge_weekWindowAppliesToClearDurationAndHost() throws Exception {
        SqlSessionFactory f = factoryOrSkip();
        try (SqlSession s = f.openSession(true)) {
            Statement st = s.getConnection().createStatement();
            ensureSchema(st);
            seed(st);
            st.execute("UPDATE player_badge SET obtain_time='2026-02-01 00:00:00'");
            st.execute("INSERT INTO player_badge (member_id,badge_code,source_type,source_id,obtain_time,del_flag) VALUES "
                    + "(" + ACE + ",'TOPIC_FINISH_" + T_OWNER_10KM + "',2," + T_OWNER_10KM + ",'2026-03-01 11:00:00',0)");

            List<Map<String, Object>> rows = s.getMapper(ClubMemberMapper.class).selectClubContribution(
                    CLUB, java.sql.Timestamp.valueOf("2026-02-20 00:00:00"));
            Map<String, Object> ace = rowOf(rows, ACE);
            assertEquals(1L, ((Number) ace.get("clearCount")).longValue());
            assertEquals(0, new BigDecimal("10.00").compareTo(dec(ace.get("mileage"))));
            assertEquals(60L, ((Number) ace.get("durationMin")).longValue());
            assertEquals(0L, ((Number) ace.get("hostedCount")).longValue());
            assertEquals(1L, ((Number) rowOf(rows, OWNER).get("hostedCount")).longValue());
            assertEquals(0L, ((Number) rowOf(rows, MID).get("clearCount")).longValue());
        }
    }

    private BigDecimal score(Map<String, Object> r) {
        return new BigDecimal("10").multiply(dec(r.get("clearCount")))
                .add(dec(r.get("mileage")))
                .add(new BigDecimal("5").multiply(dec(r.get("hostedCount"))));
    }

    private long pointOf(Statement st, long memberId) throws Exception {
        java.sql.ResultSet rs = st.executeQuery("SELECT point FROM ums_member WHERE id = " + memberId);
        rs.next();
        long p = rs.getLong(1);
        rs.close();
        return p;
    }
}
