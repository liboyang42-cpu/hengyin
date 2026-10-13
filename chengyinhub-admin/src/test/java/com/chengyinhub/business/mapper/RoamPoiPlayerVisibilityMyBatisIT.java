package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RoamPoi;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-13 + C-17 真库验证(真实 MyBatis + 专用本地 MySQL fixture):
 *
 * <p>C-17:玩家侧 {@code /api/roam/pois}(selectPoisNear)与 {@code /api/poi/discover} /
 * {@code /api/roam/shop/visit}(selectPlayerPoisByIds)必须与 {@code cityNodeContentApproved}
 * 同口径 —— 未过审的商家投放(status=1 也在架)不得出现在地图上、不得被发现/打卡;
 * 而后台治理与官方活动仍走 selectPoisByIds,看得到全量(两条查询不许混用)。</p>
 *
 * <p>C-13:found 必须按账号投影(roam_poi_found 地标发现 + roam_poi_reward_log 据点到店),
 * 换设备/清缓存后同一账号仍带 found=true,别的账号与未登录(memberId=0)恒 false。</p>
 *
 * <p>负控(实测口径):① 删掉 selectPoisNear 的 cityNodeContentApproved include ⇒ 待审/驳回行
 * 出现在结果里,第 2 条断言红;② 把 found 的两个 exists 换成常量 false ⇒ 第 1 条红;
 * ③ 把 selectPlayerPoisByIds 改回 selectPoisByIds 别名 ⇒ 第 4 条红;
 * ④ 删掉 pending_redeem 的 exists(CU-M-54)⇒ 待核销那三条断言红。</p>
 */
class RoamPoiPlayerVisibilityMyBatisIT {

    private static final long PLAYER = 77L;
    private static final long STRANGER = 88L;

    private static final String DEFAULT_JDBC_URL =
            com.chengyinhub.business.it.MybatisItDatabase.url("roam_poi_player");
    private static final String JDBC_URL = System.getProperty("chengyin.mybatis.it.jdbcUrl", DEFAULT_JDBC_URL);

    private static final String[] FIXTURE_DDL = {
            "DROP TABLE IF EXISTS roam_poi_reward_log",
            "DROP TABLE IF EXISTS roam_poi_found",
            "DROP TABLE IF EXISTS npc_profile",
            "DROP TABLE IF EXISTS roam_poi",
            "CREATE TABLE roam_poi (id BIGINT PRIMARY KEY, name VARCHAR(100), type INT,"
                    + " lat DECIMAL(12,7), lng DECIMAL(12,7), radius_m INT, address VARCHAR(100), description TEXT,"
                    + " xp INT, coupon_template_id BIGINT, city_code VARCHAR(30), status INT, audit_status INT,"
                    + " application_type INT, applicant_merchant_id BIGINT, audit_reason TEXT, create_time DATETIME,"
                    + " merchant_id BIGINT, template_id BIGINT, node_level INT, tags TEXT, cover_img TEXT, sort INT)",
            "CREATE TABLE roam_poi_found (id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, poi_id BIGINT,"
                    + " session_id BIGINT, found_at DATETIME, UNIQUE KEY(member_id, poi_id))",
            "CREATE TABLE roam_poi_reward_log (id BIGINT PRIMARY KEY AUTO_INCREMENT, poi_id BIGINT, member_id BIGINT,"
                    + " coupon_id BIGINT, coupon_history_id BIGINT, trigger_lat DECIMAL(10,6), trigger_lng DECIMAL(10,6),"
                    + " status TINYINT, create_time DATETIME, UNIQUE KEY uk_poi_member(poi_id, member_id))",
            "CREATE TABLE npc_profile (id BIGINT PRIMARY KEY AUTO_INCREMENT, scope_type INT, scope_id BIGINT,"
                    + " avatar VARCHAR(255), enabled INT, audit_status INT, del_flag VARCHAR(2), sort INT)",
    };

    /** 坐标都落在同一个 1km 包围盒里(31.22–31.24 / 121.46–121.49),可见性只由审核/status/found 决定。 */
    private static final String[] FIXTURE_DML = {
            // ① 地标(type=1,历史遗留 application_type NULL)在架 + 玩家已发现(roam_poi_found)
            "INSERT INTO roam_poi(id,name,type,lat,lng,status,application_type,audit_status)"
                    + " VALUES (1,'已发现地标',1,31.2300000,121.4737000,1,NULL,NULL)",
            // ② 待审投放(type=2,app=1,audit=0):status=1 也在架 —— 只有审核闸能挡
            "INSERT INTO roam_poi(id,name,type,lat,lng,status,application_type,audit_status)"
                    + " VALUES (2,'待审据点',2,31.2301000,121.4737000,1,1,0)",
            // ③ 驳回投放:同上
            "INSERT INTO roam_poi(id,name,type,lat,lng,status,application_type,audit_status)"
                    + " VALUES (3,'驳回据点',2,31.2302000,121.4737000,1,1,2)",
            // ④ 已批准据点:玩家已到店(roam_poi_reward_log status=0 待核销)
            "INSERT INTO roam_poi(id,name,type,lat,lng,status,application_type,audit_status)"
                    + " VALUES (4,'已到店据点',2,31.2303000,121.4737000,1,1,1)",
            // ⑤ 认领已上架(app=2,认领不改内容准入):audit_status 仍为 0 也必须可见
            "INSERT INTO roam_poi(id,name,type,lat,lng,status,application_type,audit_status)"
                    + " VALUES (5,'认领据点',2,31.2304000,121.4737000,1,2,0)",
            // ⑥ 已下线:status=0 一律不可见
            "INSERT INTO roam_poi(id,name,type,lat,lng,status,application_type,audit_status)"
                    + " VALUES (6,'下线据点',2,31.2305000,121.4737000,0,1,1)",
            // ⑦ 已批准 + 别的账号到过店:对本玩家 found 必须仍是 false
            "INSERT INTO roam_poi(id,name,type,lat,lng,status,application_type,audit_status)"
                    + " VALUES (7,'别家到店',2,31.2306000,121.4737000,1,1,1)",
            // ⑧ 已批准 + 本玩家「已打卡待核销」:status=0 且配了券(CU-M-54 的独立一档)
            "INSERT INTO roam_poi(id,name,type,lat,lng,status,application_type,audit_status)"
                    + " VALUES (8,'待核销据点',2,31.2307000,121.4737000,1,1,1)",
            "INSERT INTO roam_poi_found(member_id,poi_id,session_id,found_at)"
                    + " VALUES (77,1,9,'2026-09-01 10:00:00')",
            "INSERT INTO roam_poi_reward_log(poi_id,member_id,coupon_id,status,create_time)"
                    + " VALUES (4,77,NULL,0,'2026-09-02 10:00:00')",
            "INSERT INTO roam_poi_reward_log(poi_id,member_id,coupon_id,status,create_time)"
                    + " VALUES (7,88,NULL,1,'2026-09-03 10:00:00')",
            "INSERT INTO roam_poi_reward_log(poi_id,member_id,coupon_id,status,create_time)"
                    + " VALUES (8,77,500,0,'2026-09-04 10:00:00')",
    };

    @Test
    void playerQueriesHideUnapprovedNodesAndProjectFoundByAccount() throws Exception {
        SqlSessionFactory factory = factory();
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            try {
                for (String ddl : FIXTURE_DDL) statement.execute(ddl);
                for (String dml : FIXTURE_DML) statement.execute(dml);
                RoamMapper mapper = session.getMapper(RoamMapper.class);

                // ---------- C-17:地图图层只出「已批准 / 认领 / 历史遗留」在架据点 ----------
                Map<Long, Boolean> player = foundFlags(mapper.selectPoisNear(31.22, 31.24, 121.46, 121.49, PLAYER));
                assertEquals(Arrays.asList(1L, 4L, 5L, 7L, 8L), sortedKeys(player),
                        "待审/驳回/下线不得进玩家图层;已批准/认领/历史遗留必须在(删掉审核 include 这条必红)");

                // ---------- CU-M-54:「已打卡但券未核销」单独一档,不能与「已完成」混为一谈 ----------
                List<RoamPoi> pendingRows = mapper.selectPoisNear(31.22, 31.24, 121.46, 121.49, PLAYER);
                Map<Long, Boolean> pending = new HashMap<>();
                for (RoamPoi row : pendingRows) pending.put(row.getId(), row.getPendingRedeem());
                assertEquals(Boolean.TRUE, pending.get(8L), "本玩家 status=0 且配了券 = 待核销(同一口径同 selectPendingCityNodeCouponId)");
                assertEquals(Boolean.FALSE, pending.get(4L), "到店但该据点没券:不算待核销");
                assertEquals(Boolean.FALSE, pending.get(7L), "别人核销过的记录不是我的待核销");
                assertTrue(pending.get(1L) == null || Boolean.FALSE.equals(pending.get(1L)),
                        "只有发现记录(roam_poi_found)的地点不存在待核销");

                // ---------- C-13:found 按账号投影,两个真源都算 ----------
                assertEquals(Boolean.TRUE, player.get(1L), "roam_poi_found 的地标发现必须投影成 found");
                assertEquals(Boolean.TRUE, player.get(4L), "roam_poi_reward_log 的到店记录必须投影成 found");
                assertEquals(Boolean.FALSE, player.get(5L), "没发现的据点 found 必须是 false,不能整批为真");
                assertEquals(Boolean.FALSE, player.get(7L), "别人到过店的据点只对我 found=false,但据点本身对我可见");

                Map<Long, Boolean> stranger = foundFlags(mapper.selectPoisNear(31.22, 31.24, 121.46, 121.49, STRANGER));
                assertEquals(Boolean.FALSE, stranger.get(1L), "别人的发现不能算我的:found 必须按 memberId 收窄");
                assertEquals(Boolean.FALSE, stranger.get(4L), "别人的到店记录不能算我的");

                Map<Long, Boolean> anonymous = foundFlags(mapper.selectPoisNear(31.22, 31.24, 121.46, 121.49, 0L));
                assertTrue(anonymous.values().stream().noneMatch(Boolean.TRUE::equals),
                        "未登录(memberId=0)不得下发任何 found=true");

                // ---------- C-17:按 id 的玩家侧查询同口径;后台/官方活动查询保持全量 ----------
                List<RoamPoi> playerByIds = mapper.selectPlayerPoisByIds(Arrays.asList(2L, 3L, 4L, 5L));
                assertEquals(Arrays.asList(4L, 5L), ids(playerByIds),
                        "待审/驳回按 id 也不得可见(discover/shop visit 走的这条)");

                List<RoamPoi> adminByIds = mapper.selectPoisByIds(Arrays.asList(2L, 3L, 4L));
                assertEquals(3, adminByIds.size(), "后台治理/官方活动链路必须仍能看到全量,不许被玩家口径误伤");

                // 已下线网点:两条玩家查询都不放行
                assertTrue(mapper.selectPlayerPoisByIds(Arrays.asList(6L)).isEmpty(), "status=0 不得被发现/打卡");
                assertFalse(foundFlags(mapper.selectPoisNear(31.22, 31.24, 121.46, 121.49, PLAYER)).containsKey(6L));
            } finally {
                // 只回放前 4 条(全是 DROP;4 之后是 CREATE —— 反向跑 CREATE 会撞 1050)
                for (int i = 3; i >= 0; i--) statement.execute(FIXTURE_DDL[i]);
            }
        }
    }

    private static Map<Long, Boolean> foundFlags(List<RoamPoi> rows) {
        Map<Long, Boolean> flags = new HashMap<>();
        for (RoamPoi row : rows) flags.put(row.getId(), row.getFound());
        return flags;
    }

    private static List<Long> sortedKeys(Map<Long, Boolean> flags) {
        List<Long> keys = new java.util.ArrayList<>(flags.keySet());
        java.util.Collections.sort(keys);
        return keys;
    }

    private static List<Long> ids(List<RoamPoi> rows) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (RoamPoi row : rows) ids.add(row.getId());
        java.util.Collections.sort(ids);
        return ids;
    }

    private static SqlSessionFactory factory() throws Exception {
        requireDedicatedLocalFixture(JDBC_URL);
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("RoamPoi", RoamPoi.class);
        configuration.getTypeAliasRegistry().registerAlias("RoamSession",
                com.chengyinhub.business.domain.RoamSession.class);
        PooledDataSource dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", JDBC_URL,
                System.getProperty("chengyin.mybatis.it.user",
                        com.chengyinhub.business.it.MybatisItDatabase.user()),
                System.getProperty("chengyin.mybatis.it.password",
                        com.chengyinhub.business.it.MybatisItDatabase.password()));
        configuration.setEnvironment(new Environment("roam-poi-player-it", new JdbcTransactionFactory(), dataSource));
        try (InputStream input = Resources.getResourceAsStream("mapper/business/RoamMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/business/RoamMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 与姊妹 IT 同一守卫口径:只准本机 + chengyin_mybatis_* 专用 fixture 库。 */
    static void requireDedicatedLocalFixture(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.matches(
                "^jdbc:mysql://127\\.0\\.0\\.1:\\d{1,5}/chengyin_mybatis_[a-z0-9_]+(?:\\?.*)?$")) {
            throw new IllegalStateException("拒绝非专用本地 fixture JDBC:仅允许 127.0.0.1:<port>/chengyin_mybatis_*");
        }
    }
}
