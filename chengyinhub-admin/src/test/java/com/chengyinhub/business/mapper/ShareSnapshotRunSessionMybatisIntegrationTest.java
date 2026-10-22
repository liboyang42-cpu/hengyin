package com.chengyinhub.business.mapper;

import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第二轮拍板 17/22:真迁移 + 真 MyBatis XML + 真 MySQL。
 * 17 = 足迹分享快照(同足迹复用令牌、超额删最旧、按令牌读、作废只删本人);
 * 22 = 进行中游戏会话(作用域 upsert、枚举只列仍可继续的:报名成功/通行证有效/活动未删未取消未过结束日)。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShareSnapshotRunSessionMybatisIntegrationTest {
    private String database;
    private JdbcTemplate admin, db;
    private RoamShareSnapshotMapper snapshots;
    private PlayerRunSessionMapper runs;

    @BeforeAll void database() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("share_run");
        assertTrue(config.adminUrl().startsWith("jdbc:mysql://127.0.0.1:")
                || config.adminUrl().startsWith("jdbc:mysql://localhost:"), "localhost scratch only");
        database = "chengyin_share_run_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        admin.execute("CREATE DATABASE " + database);
        DriverManagerDataSource source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"), config.user(), config.password());
        db = new JdbcTemplate(source);
        String roam = migration("migration_roam.sql");
        java.util.regex.Matcher sessionDdl = java.util.regex.Pattern.compile(
                "CREATE TABLE IF NOT EXISTS roam_session\\s*\\([\\s\\S]*?\\) ENGINE=InnoDB[^;]*;").matcher(roam);
        assertTrue(sessionDdl.find(), "真实漫游迁移必须包含完整会话DDL");
        db.execute(sessionDdl.group());
        db.execute(migration("migration_20260917_roam_share_snapshot.sql"));
        new ResourceDatabasePopulator(new FileSystemResource(
                "sql/migration_20261012_roam_share_retention.sql")).execute(source);
        new ResourceDatabasePopulator(new FileSystemResource(
                "sql/migration_roam_share_version_20261022.sql")).execute(source);
        db.execute(migration("migration_20260917_player_run_session.sql"));
        db.execute("CREATE TABLE cms_topic(id BIGINT PRIMARY KEY, name VARCHAR(100), img_url VARCHAR(255), del_flag INT)");
        db.execute("CREATE TABLE cms_activity(id BIGINT PRIMARY KEY, topic_id BIGINT, name VARCHAR(100), img_url VARCHAR(255),"
                + " del_flag INT, cancel_time DATETIME NULL, end_date DATETIME NULL)");
        db.execute("CREATE TABLE cms_registration(id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, owner_type INT,"
                + " owner_id BIGINT, registration_status INT, expires_at DATETIME NULL)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new Resource[] {
                new ClassPathResource("mapper/business/RoamShareSnapshotMapper.xml"),
                new ClassPathResource("mapper/business/PlayerRunSessionMapper.xml") });
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        snapshots = sql.getMapper(RoamShareSnapshotMapper.class);
        runs = sql.getMapper(PlayerRunSessionMapper.class);
    }

    private static String migration(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("sql/" + name)), StandardCharsets.UTF_8);
    }

    @AfterAll void cleanup() { if (database != null && admin != null) admin.execute("DROP DATABASE " + database); }

    @BeforeEach void reset() {
        for (String table : new String[] {"roam_share_snapshot", "roam_session", "player_run_session", "cms_topic", "cms_activity", "cms_registration"}) {
            db.execute("DELETE FROM " + table);
        }
    }

    private static final String T1 = "0123456789abcdef0123456789abcdef";
    private static final String T2 = "fedcba9876543210fedcba9876543210";

    @Test void sameFootprintKeepsItsTokenAndUpdatesSnapshot() {
        snapshots.upsertSnapshot(T1, 9L, 100L, "{\"v\":1}");
        snapshots.upsertSnapshot(T2, 9L, 100L, "{\"v\":2}");
        assertEquals(T1, snapshots.selectTokenByMemberTs(9L, 100L), "重复分享同一条足迹不换令牌,已发出去的链接继续有效");
        assertEquals("{\"v\":2}", snapshots.selectSnapshotJsonByToken(T1), "快照按最新裁剪设置更新");
        assertNull(snapshots.selectSnapshotJsonByToken(T2));
        assertEquals(1, snapshots.countByMember(9L));
    }

    @Test void oldestEvictionAndRevokeOnlyTouchTheCaller() throws Exception {
        snapshots.upsertSnapshot(T1, 9L, 1L, "{}");
        Thread.sleep(1100);
        snapshots.upsertSnapshot(T2, 9L, 2L, "{}");
        snapshots.upsertSnapshot("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 8L, 1L, "{}");
        assertEquals(1, snapshots.deleteOldestByMember(9L, 1));
        assertNull(snapshots.selectSnapshotJsonByToken(T1), "删的是最旧那条");
        assertNotNull(snapshots.selectSnapshotJsonByToken(T2));
        assertEquals(1, snapshots.deleteByMember(9L));
        assertNull(snapshots.selectSnapshotJsonByToken(T2), "作废后令牌读不到");
        assertNotNull(snapshots.selectSnapshotJsonByToken("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), "别人的快照不受影响");
    }

    @Test void boundSharesRequireRecentFinishedSessionOwnedByTheSameMember() {
        db.update("INSERT INTO roam_session(id,member_id,status,finished_at) VALUES"
                + "(1,9,1,NOW()),(2,9,1,DATE_SUB(NOW(),INTERVAL 31 DAY)),"
                + "(3,8,1,NOW()),(4,9,0,NOW())");
        for (long session : new long[]{1, 2, 3, 4}) {
            String token = String.format("%032d", session);
            snapshots.upsertOwnedSnapshot(token, 9L, session, session, "{\"session\":" + session + "}");
        }
        assertEquals("{\"session\":1}", snapshots.selectSnapshotJsonByToken(String.format("%032d", 1)));
        for (long session : new long[]{2, 3, 4}) {
            assertNull(snapshots.selectSnapshotJsonByToken(String.format("%032d", session)),
                    "过期、非本人或未结束会话不得公开快照");
        }
        snapshots.upsertSnapshot(T1, 9L, 100L, "{}");
        db.update("UPDATE roam_share_snapshot SET create_time=DATE_SUB(NOW(),INTERVAL 31 DAY) WHERE share_token=?", T1);
        assertNull(snapshots.selectSnapshotJsonByToken(T1), "无可证会话的历史快照也受30天保留限制");
    }

    @Test void runSessionUpsertReadAndClearAreScoped() {
        runs.upsertPaused(9L, 27L, 31L, 42, 1000L);
        runs.upsertPaused(9L, 27L, 31L, 600, 2000L);
        runs.upsertPaused(9L, 0L, 31L, 5, 3000L);
        Map<String, Object> activity = runs.selectScope(9L, 27L, null);
        assertEquals(600, ((Number) activity.get("elapsedSeconds")).intValue(), "同一场只留一行,后写覆盖");
        assertEquals("PAUSED", activity.get("runState"));
        assertEquals(5, ((Number) runs.selectScope(9L, 0L, 31L).get("elapsedSeconds")).intValue(), "自玩与活动场次不串");
        assertNull(runs.selectScope(8L, 27L, null), "别人读不到");
        assertEquals(1, runs.markEnded(9L, 27L, null, 9000L));
        Map<String, Object> ended = runs.selectScope(9L, 27L, null);
        assertEquals("ENDED", ended.get("runState"), "结束留墓碑,另一台设备据此不再还魂");
        assertEquals(9000L, ((Number) ended.get("savedAt")).longValue());
        assertEquals("PAUSED", runs.selectScope(9L, 0L, 31L).get("runState"), "结束活动场次不误伤自玩");
        assertEquals(0, runs.markEnded(9L, 0L, 99L, 9000L), "没同步过的会话不凭空造墓碑");
        runs.upsertPaused(9L, 27L, 31L, 7, 9500L);
        assertEquals("PAUSED", runs.selectScope(9L, 27L, null).get("runState"), "同一场重新开局会覆盖墓碑");
    }

    @Test void listShowsOnlySessionsThatCanStillContinue() {
        db.update("INSERT INTO cms_topic VALUES (31,'梧桐区','t.jpg',0),(32,'已删主题','',2),(33,'自玩过期','',0)");
        db.update("INSERT INTO cms_activity VALUES (27,31,'外滩夜游','a.jpg',0,NULL,DATE_ADD(NOW(), INTERVAL 1 DAY)),"
                + "(28,31,'已取消',NULL,0,NOW(),NULL),(29,31,'已结束',NULL,0,NULL,DATE_SUB(CURDATE(), INTERVAL 1 DAY)),"
                + "(30,31,'没报名',NULL,0,NULL,NULL),(26,31,'今天最后一天',NULL,0,NULL,CURDATE())");
        db.update("INSERT INTO cms_registration(member_id,owner_type,owner_id,registration_status,expires_at) VALUES"
                + "(9,2,27,2,NULL),(9,2,28,2,NULL),(9,2,29,2,NULL),(9,2,30,1,NULL),(9,2,26,2,NULL),"
                + "(9,1,31,2,DATE_ADD(NOW(), INTERVAL 1 DAY)),(9,1,33,2,DATE_SUB(NOW(), INTERVAL 1 DAY)),(9,1,32,2,NULL)");
        for (long activity : new long[] {27, 28, 29, 30, 26}) runs.upsertPaused(9L, activity, 31L, 60, 1L);
        runs.upsertPaused(9L, 0L, 31L, 60, 1L);
        runs.upsertPaused(9L, 0L, 33L, 60, 1L);
        runs.upsertPaused(9L, 0L, 32L, 60, 1L);
        runs.upsertPaused(8L, 27L, 31L, 60, 1L);
        runs.upsertPaused(9L, 0L, 34L, 60, 1L);
        db.update("INSERT INTO cms_topic VALUES (34,'已通关主题','',0)");
        db.update("INSERT INTO cms_registration(member_id,owner_type,owner_id,registration_status,expires_at) VALUES (9,1,34,2,NULL)");
        runs.markEnded(9L, 0L, 34L, 2L);

        List<Map<String, Object>> list = runs.selectContinuableByMember(9L, 20);
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Map<String, Object> row : list) keys.add(row.get("activityId") + ":" + row.get("topicId") + ":" + row.get("title"));
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(
                "27:31:外滩夜游", "26:31:今天最后一天", "0:31:梧桐区")), keys,
                "已结束墓碑/已取消/已过结束日/未报名成功/通行证过期/主题已删 都不能出现在「继续游戏」");
        Map<String, Object> night = list.stream().filter(r -> "外滩夜游".equals(r.get("title"))).findFirst().get();
        assertEquals("a.jpg", night.get("cover"));
    }
}
