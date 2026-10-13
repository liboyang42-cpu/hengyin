package com.chengyinhub.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 资金 IT 的**类间隔离契约**:每个 IT 类一个独立 schema,而且这条隔离是被观测出来的,不是被相信的。
 *
 * <p>为什么要有这份契约:2026-08-19 的 #741 上,{@code RegistrationFundsMybatisIntegrationTest}
 * 60 条全 ERROR。当时的第一判断是「同一个 schema 里多个 IT 类互相踩」。真查下来**不是** ——
 * {@link MysqlIntegrationTestConfig} 早就按 {@code base + "_" + suite + suffix} 给每个类算了独立库名,
 * 12 个套件的 suite 各不相同,information_schema 也证实每类开跑时自己的库是空的(tables=0)。
 * 真正的成因在别处(见 gate 脚本里那条 {@code -am} 的说明)。
 *
 * <p>但「这次不是它」不等于「它不会是」。类间污染在**两个方向**上都危险:
 * <ul>
 *   <li>该绿却红:A 类留下的表让 B 类的建表撞名(就是当时误判的那个形状);</li>
 *   <li>★该红却绿:A 类留下的**数据**让 B 类某个 fail-closed 前置意外满足 —— 闸被架空,而且一声不吭。</li>
 * </ul>
 * 后者是这份契约真正盯的东西:资金域这三套是看门狗,看门狗被喂饱了比看门狗叫错更贵。
 *
 * <p>三条用例分别是三个方向的负控,缺一条这份契约就退化成「跑过了」:
 * <ol>
 *   <li>{@link #sharedSchemaReallyCollides()} —— 故意共用 schema **必须**复现 "already exists";
 *       它红不了,说明这份契约根本没在测隔离。</li>
 *   <li>{@link #isolatedSchemasCannotSeeEachOthersTables()} —— 真查 information_schema,
 *       不是「没报错就算隔离」。</li>
 *   <li>{@link #pollutionCannotSatisfyAnotherSuitesFailClosedPrecondition()} —— 反向污染:
 *       同库时 A 的遗留数据让 B 的 fail-closed 闸不再报警(该红却绿),隔离后 B 照旧红。</li>
 * </ol>
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MysqlItSchemaIsolationTest {

    /** 两个「假想 IT 类」各自的配置:suite 不同 ⇒ 库名不同,这正是现行隔离的机制。 */
    private static final MysqlIntegrationTestConfig SUITE_A =
            MysqlIntegrationTestConfig.fromEnvironment("iso_probe_a");
    private static final MysqlIntegrationTestConfig SUITE_B =
            MysqlIntegrationTestConfig.fromEnvironment("iso_probe_b");
    /** 同一个 suite 取两份配置 = 两个类共用一个 schema 的世界,用来把故障形状真造出来。 */
    private static final MysqlIntegrationTestConfig SHARED_ONE =
            MysqlIntegrationTestConfig.fromEnvironment("iso_probe_shared");
    private static final MysqlIntegrationTestConfig SHARED_TWO =
            MysqlIntegrationTestConfig.fromEnvironment("iso_probe_shared");

    private final List<MysqlIntegrationTestConfig> created = new ArrayList<>();

    // ---------- ① 能红:共用 schema 必须真的撞 ----------
    @Test
    void sharedSchemaReallyCollides() throws Exception {
        assertEquals(SHARED_ONE.databaseName(), SHARED_TWO.databaseName(),
                "这条用例的前提就是两份配置落在同一个库上");
        freshDatabase(SHARED_ONE);

        exec(SHARED_ONE, "CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, product_type INT) ENGINE=InnoDB");
        SQLException collision = assertThrows(SQLSyntaxErrorException.class,
                () -> exec(SHARED_TWO, "CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, club_id BIGINT) ENGINE=InnoDB"),
                "共用 schema 时第二个类的建表**必须**撞名;不撞说明这份契约没在测隔离");
        assertTrue(collision.getMessage().contains("already exists"),
                "撞名的原文要能对上线上那条日志:" + collision.getMessage());
    }

    // ---------- ② 隔离真生效:真查 information_schema ----------
    @Test
    void isolatedSchemasCannotSeeEachOthersTables() throws Exception {
        assertNotEquals(SUITE_A.databaseName(), SUITE_B.databaseName(),
                "两个 suite 必须落在不同库名上,否则下面查什么都没意义");
        freshDatabase(SUITE_A);
        freshDatabase(SUITE_B);

        exec(SUITE_A, "CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, product_type INT) ENGINE=InnoDB");

        assertEquals(1, tableCountIn(SUITE_A, SUITE_A.databaseName(), "cms_topic"),
                "A 自己的库里应该看得见自己建的表");
        // ★ 判据是 information_schema 的行数,不是「B 建表没报错」——后者在同库时也可能恰好不报错。
        assertEquals(0, tableCountIn(SUITE_B, SUITE_B.databaseName(), "cms_topic"),
                "B 的库里不许看见 A 建的表");
        assertEquals(SUITE_B.databaseName(), currentDatabase(SUITE_B),
                "B 的连接默认库必须就是 B 自己的库");
    }

    // ---------- ③ ★反向污染:A 的遗留数据不许把 B 的 fail-closed 前置喂饱 ----------
    @Test
    void pollutionCannotSatisfyAnotherSuitesFailClosedPrecondition() throws Exception {
        // 建模的是真实存在的那一类闸:RegistrationFundsMybatisIntegrationTest
        // #migrationFailsClosedWhenHistoricalInviteRewardCannotResolveInvitee ——
        // 「历史邀请奖励解析不出被邀请人就必须 fail-closed」。
        // 闸的前置是**一条数据不存在**;而「不存在」正是最容易被别的类的遗留数据悄悄满足掉的前提。

        // 场景 1:同一个库。A 先留下一行 ums_member(9001),B 随后插入一条指向 9001 的历史奖励。
        freshDatabase(SHARED_ONE);
        createInviteRewardTables(SHARED_ONE);
        exec(SHARED_ONE, "INSERT INTO ums_member(id) VALUES (9001)");                       // A 的遗留
        exec(SHARED_TWO, "INSERT INTO ums_member_points_detail(id, invite_member_id) VALUES (1, 9001)"); // B 的用例数据
        assertEquals(0, unresolvableInviteRewards(SHARED_TWO),
                "同库时 A 的遗留把 B 的 fail-closed 前置喂饱了 —— 闸不再报警(该红却绿),这正是要防的");

        // 场景 2:隔离之后。B 在自己的库里插同一条历史奖励,被邀请人**不存在** ⇒ 闸必须照旧红。
        freshDatabase(SUITE_B);
        createInviteRewardTables(SUITE_B);
        exec(SUITE_B, "INSERT INTO ums_member_points_detail(id, invite_member_id) VALUES (1, 9001)");
        assertEquals(1, unresolvableInviteRewards(SUITE_B),
                "隔离之后 B 必须照旧看见「解析不出被邀请人」这条 —— 修的不是「不炸了」,是「不再能靠污染蒙混过关」");
    }

    // ---------- 基础设施 ----------

    /** 建模那道 fail-closed 闸的读法:数出「奖励指向的被邀请人在本库里不存在」的行数,>0 即该红。 */
    private int unresolvableInviteRewards(MysqlIntegrationTestConfig config) throws Exception {
        return queryInt(config,
                "select count(*) from ums_member_points_detail d"
                        + " left join ums_member m on m.id = d.invite_member_id"
                        + " where d.invite_member_id is not null and m.id is null");
    }

    private void createInviteRewardTables(MysqlIntegrationTestConfig config) throws Exception {
        exec(config, "CREATE TABLE ums_member (id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        exec(config, "CREATE TABLE ums_member_points_detail (id BIGINT PRIMARY KEY, invite_member_id BIGINT) ENGINE=InnoDB");
    }

    private void freshDatabase(MysqlIntegrationTestConfig config) throws Exception {
        try (Connection admin = DriverManager.getConnection(
                config.adminUrl(), config.user(), config.password());
             Statement s = admin.createStatement()) {
            // 每条用例自己重建,绝不接受「环境里恰好剩下什么」——这份契约本身也不许依赖顺序。
            s.execute("DROP DATABASE IF EXISTS " + config.databaseName());
            s.execute("CREATE DATABASE " + config.databaseName() + " CHARACTER SET utf8mb4");
        }
        created.add(config);
    }

    private void exec(MysqlIntegrationTestConfig config, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(
                config.databaseUrl(), config.user(), config.password());
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private int tableCountIn(MysqlIntegrationTestConfig connectAs, String schema, String table) throws Exception {
        try (Connection c = DriverManager.getConnection(
                connectAs.databaseUrl(), connectAs.user(), connectAs.password());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "select count(*) from information_schema.tables where table_schema='"
                             + schema + "' and table_name='" + table + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String currentDatabase(MysqlIntegrationTestConfig config) throws Exception {
        try (Connection c = DriverManager.getConnection(
                config.databaseUrl(), config.user(), config.password());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select database()")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private int queryInt(MysqlIntegrationTestConfig config, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(
                config.databaseUrl(), config.user(), config.password());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @AfterAll
    void dropProbeDatabases() throws Exception {
        for (MysqlIntegrationTestConfig config : created) {
            try (Connection admin = DriverManager.getConnection(
                    config.adminUrl(), config.user(), config.password());
                 Statement s = admin.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS " + config.databaseName());
            }
        }
    }
}
