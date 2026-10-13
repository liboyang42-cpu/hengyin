package com.chengyinhub.business.it;

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

import org.junit.jupiter.api.Test;

/**
 * MyBatis 真库 IT 家族的**类间隔离契约**:一类一库这件事,是被观测出来的,不是被相信的。
 *
 * <p><b>为什么要有它。</b>2026-08-19 之前,这个家族的十六个 IT 类共用一个长期库
 * {@code chengyin_mybatis_it}。共用在两个方向上都危险,而且第二个方向更贵:
 * <ul>
 *   <li>该绿却红:A 类建的表挡住 B 类的建表(资金 IT 那次撞的就是这个形状);</li>
 *   <li>★ 该红却绿:A 类留下的**数据**让 B 类的 fail-closed 前置意外满足 —— 闸被喂饱,一声不吭。</li>
 * </ul>
 * 「现在是绿的」不能当作「现在是对的」:共用库里,后者只差一个执行顺序。
 *
 * <p>三条用例就是三个方向的负控,缺一条这份契约就退化成「跑过了」:
 * <ol>
 *   <li>{@link #sharedSchemaReallyCollides()} —— 故意共用**必须**复现撞名,它红不了说明契约没在测隔离;</li>
 *   <li>{@link #isolatedSchemasCannotSeeEachOthersTables()} —— 真查 information_schema,不是「没报错就算隔离」;</li>
 *   <li>{@link #pollutionCannotSatisfyAnotherSuitesFailClosedPrecondition()} —— 反向污染:
 *       同库时 A 的遗留数据让 B 的一致性闸不再报警(该红却绿),隔离后 B 照旧红。</li>
 * </ol>
 */
class MybatisItSchemaIsolationIT {

    /** 两个「假想 IT 类」各自的 suite ⇒ 各自一个库,这正是现行隔离的机制。 */
    private static final String SUITE_A = "iso_probe_a";
    private static final String SUITE_B = "iso_probe_b";
    /** 同一个 suite 取两次 = 两个类落在同一个库的世界,用来把故障形状真造出来。 */
    private static final String SUITE_SHARED = "iso_probe_shared";

    // ---------- ① 能红:共用 schema 必须真的撞 ----------
    @Test
    void sharedSchemaReallyCollides() throws Exception {
        String first = MybatisItDatabase.url(SUITE_SHARED);
        String second = MybatisItDatabase.url(SUITE_SHARED);
        assertEquals(first, second, "这条用例的前提就是两次取用落在同一个库上");

        exec(first, "DROP TABLE IF EXISTS cms_topic");
        exec(first, "CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, product_type INT) ENGINE=InnoDB");
        SQLException collision = assertThrows(SQLSyntaxErrorException.class,
                () -> exec(second, "CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, club_id BIGINT) ENGINE=InnoDB"),
                "共用库时第二个类的建表**必须**撞名;不撞说明这份契约没在测隔离");
        assertTrue(collision.getMessage().contains("already exists"),
                "撞名原文要能对上线上那条日志:" + collision.getMessage());
    }

    // ---------- ② 隔离真生效:真查 information_schema ----------
    @Test
    void isolatedSchemasCannotSeeEachOthersTables() throws Exception {
        String a = MybatisItDatabase.url(SUITE_A);
        String b = MybatisItDatabase.url(SUITE_B);
        assertNotEquals(MybatisItDatabase.databaseName(SUITE_A), MybatisItDatabase.databaseName(SUITE_B),
                "两个 suite 必须落在不同库名上,否则下面查什么都没意义");

        exec(a, "DROP TABLE IF EXISTS iso_probe_marker");
        exec(a, "CREATE TABLE iso_probe_marker (id BIGINT PRIMARY KEY) ENGINE=InnoDB");

        assertEquals(1, tableCount(a, MybatisItDatabase.databaseName(SUITE_A), "iso_probe_marker"),
                "A 自己的库里应该看得见自己建的表");
        // ★ 判据是 information_schema 的行数,不是「B 建表没报错」—— 后者在同库时也可能恰好不报错。
        assertEquals(0, tableCount(b, MybatisItDatabase.databaseName(SUITE_B), "iso_probe_marker"),
                "B 的库里不许看见 A 建的表");
        assertEquals(MybatisItDatabase.databaseName(SUITE_B), currentDatabase(b),
                "B 的连接默认库必须就是 B 自己的库");
    }

    // ---------- ③ ★反向污染:A 的遗留数据不许把 B 的 fail-closed 前置喂饱 ----------
    @Test
    void pollutionCannotSatisfyAnotherSuitesFailClosedPrecondition() throws Exception {
        // 建模本家族真实存在的那一类闸:「活动挂在一个查不到的主题上」属于数据不一致,必须被查出来。
        // 闸的前置是**一条数据不存在**,而「不存在」正是最容易被别的类的遗留数据悄悄满足掉的前提。
        String shared = MybatisItDatabase.url(SUITE_SHARED);
        prepareConsistencyTables(shared);
        exec(shared, "DELETE FROM cms_topic_probe");
        exec(shared, "INSERT INTO cms_topic_probe(id) VALUES (9001)");                    // A 的遗留
        exec(shared, "INSERT INTO cms_activity_probe(id, topic_id) VALUES (1, 9001)");     // B 的用例数据
        assertEquals(0, danglingActivities(shared),
                "同库时 A 的遗留把 B 的一致性闸喂饱了 —— 闸不再报警(该红却绿),这正是要防的");

        // 隔离之后:B 在自己的库里插同一条活动,主题**不存在** ⇒ 闸必须照旧红。
        String isolated = MybatisItDatabase.url(SUITE_B);
        prepareConsistencyTables(isolated);
        exec(isolated, "INSERT INTO cms_activity_probe(id, topic_id) VALUES (1, 9001)");
        assertEquals(1, danglingActivities(isolated),
                "隔离之后 B 必须照旧看见「活动挂在不存在的主题上」这一条 —— "
                        + "修的不是「不炸了」,是「不再能靠污染蒙混过关」");
    }

    // ---------- 基础设施 ----------

    /** 建模那道一致性闸的读法:数出「活动指向的主题在本库里不存在」的行数,>0 即该红。 */
    private int danglingActivities(String url) throws Exception {
        return queryInt(url,
                "select count(*) from cms_activity_probe a"
                        + " left join cms_topic_probe t on t.id = a.topic_id"
                        + " where a.topic_id is not null and t.id is null");
    }

    /**
     * ⚠️ 这里**故意**用 IF NOT EXISTS、且不清行 —— 建模的是「fixture 不自足」的那一类 IT:
     * 它只保证表在,不保证表里没有别人的行。整个家族在 2026-08-19 之前就是这个形状,
     * 而反向污染恰恰只能从这种 fixture 上进来。若这里改成 DROP+CREATE,第三条用例就测不到东西了。
     */
    private void prepareConsistencyTables(String url) throws Exception {
        exec(url, "CREATE TABLE IF NOT EXISTS cms_topic_probe (id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        exec(url, "CREATE TABLE IF NOT EXISTS cms_activity_probe (id BIGINT PRIMARY KEY, topic_id BIGINT) ENGINE=InnoDB");
        exec(url, "DELETE FROM cms_activity_probe");
    }

    private void exec(String url, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private int tableCount(String connectVia, String schema, String table) throws Exception {
        return queryInt(connectVia, "select count(*) from information_schema.tables"
                + " where table_schema='" + schema + "' and table_name='" + table + "'");
    }

    private String currentDatabase(String url) throws Exception {
        try (Connection c = DriverManager.getConnection(url, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select database()")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private int queryInt(String url, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
