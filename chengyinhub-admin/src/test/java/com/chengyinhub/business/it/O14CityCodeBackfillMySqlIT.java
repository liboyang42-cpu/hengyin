package com.chengyinhub.business.it;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O-14 city_code 回填(RV3 B-519-1)真 MySQL:真迁移 + 真只读预览,base schema 真建表。
 *
 * <ul>
 *   <li>反例:「南京西路1266号」(上海俱乐部)不得写成南京;「北京路步行街」(广州俱乐部)不得写成北京;
 *       没有结构化来源的同样地址一律留空 —— 地址自由文本绝不参与匹配。</li>
 *   <li>人工值永不覆盖;同一规则重跑零变更;修正后重跑只改写本回填自己写过且未被改动的行。</li>
 *   <li>预览只读,且与真实执行结果逐行一致。</li>
 * </ul>
 * 由 ci/backend-test.sh 的 MYBATIS_ITS 点名执行。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class O14CityCodeBackfillMySqlIT {
    private static final String BACKFILL = "sql/migration_o14_content_city_code_backfill_20261022.sql";
    private static final String PREVIEW = "sql/_precheck_o14_content_city_code_backfill_20261022.sql";
    private static final String[] SCHEMA = {
            "sql/migration_o14_cms_topic_city_code_20261022.sql",
            "sql/migration_o14_cms_activity_city_code_20261022.sql",
            "sql/migration_o14_city_code_backfill_log_20261022.sql" };

    private Connection connect() throws Exception {
        return DriverManager.getConnection(MybatisItDatabase.url("o14_city_backfill"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
    }

    @BeforeEach
    void schemaAndFixtures() throws Exception {
        Path root = Files.exists(Paths.get("sql/base_schema_20260818.sql")) ? Paths.get("") : Paths.get("..");
        String base = Files.readString(root.resolve("sql/base_schema_20260818.sql"));
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS o14_city_code_backfill_log");
            for (String table : new String[] {"cms_activity", "cms_topic", "club"}) {
                st.execute("DROP TABLE IF EXISTS `" + table + "`");
                st.execute(ddl(base, table));
            }
            for (String migration : SCHEMA) {
                run(c, migration);
            }
            st.execute("INSERT INTO club(id, member_id, name, city) VALUES"
                    + " (1,1,'沪团','上海'),(2,1,'穗团',' 广州市 '),(3,1,'浦东团','上海浦东'),(4,1,'京团','北京')");
            st.execute("INSERT INTO cms_topic(id, name, address_name, club_id, city_code) VALUES"
                    + " (1,'静安漫游','南京西路1266号',1,NULL),"
                    + " (2,'越秀漫游','北京路步行街',2,NULL),"
                    + " (3,'无团主题','南京西路',NULL,NULL),"
                    + " (4,'人工主题','南京西路',1,'320500'),"
                    + " (5,'未识别城市','x',3,NULL)");
            st.execute("INSERT INTO cms_activity(id, name, address_name, topic_id, club_id, city_code) VALUES"
                    + " (1,'静安场','南京西路',1,NULL,NULL),"
                    + " (2,'京团办穗主题','x',2,4,NULL),"
                    + " (3,'穗团独立场','北京路',0,2,NULL),"
                    + " (4,'无来源场','北京路',NULL,NULL,NULL)");
        }
    }

    @Test
    void addressSubstringsNeverDecideTheCity() throws Exception {
        try (Connection c = connect()) {
            run(c, BACKFILL);
            assertEquals("310100", topic(c, 1), "南京西路(上海俱乐部) → 上海,不是南京 320100");
            assertEquals("440100", topic(c, 2), "北京路(广州俱乐部) → 广州,不是北京 110100");
            assertNull(topic(c, 3), "只有地址「南京西路」、没有结构化来源 → 留空");
            assertNull(activity(c, 4), "只有地址「北京路」、没有结构化来源 → 留空");
            assertEquals("320500", topic(c, 4), "人工值永不覆盖");
            assertNull(topic(c, 5), "俱乐部城市不在白名单 → 留空");
            assertEquals("310100", activity(c, 1), "继承父主题");
            assertNull(activity(c, 2), "活动俱乐部(北京)与父主题(广州)冲突 → 留空");
            assertEquals("440100", activity(c, 3));
            assertEquals(4, scalar(c, "SELECT COUNT(*) FROM o14_city_code_backfill_log"), "每条写入都有来源标记");
            assertEquals(0, scalar(c, "SELECT COUNT(*) FROM o14_city_code_backfill_log WHERE target_table='cms_topic' AND row_id=4"),
                    "人工值不记在回填名下");
        }
    }

    @Test
    void rerunIsANoOpAndManualEditsAreNeverReclaimed() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            run(c, BACKFILL);
            String before = snapshot(c);
            run(c, BACKFILL);
            assertEquals(before, snapshot(c), "同一规则重跑零变更");

            st.execute("UPDATE cms_topic SET city_code='440300' WHERE id=2");
            run(c, BACKFILL);
            assertEquals("440300", topic(c, 2), "回填写过、后被人工改掉的值归人工");
            assertEquals(0, scalar(c, "SELECT COUNT(*) FROM o14_city_code_backfill_log WHERE target_table='cms_topic' AND row_id=2"));
        }
    }

    @Test
    void correctedRunOverwritesOnlyRowsItWrote() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            run(c, BACKFILL);
            // 模拟旧规则写错:回填名下的主题 1 被写成南京(值与 log 一致 = 仍归回填)
            st.execute("UPDATE cms_topic SET city_code='320100' WHERE id=1");
            st.execute("UPDATE o14_city_code_backfill_log SET city_code='320100' WHERE target_table='cms_topic' AND row_id=1");
            // 来源消失:穗团城市被清空 → 回填名下的活动 3 应清回空
            st.execute("UPDATE club SET city='' WHERE id=2");
            run(c, BACKFILL);
            assertEquals("310100", topic(c, 1), "回填自己写错的值被修正");
            assertNull(activity(c, 3), "回填自己写的值来源消失 → 清回空");
            assertEquals("320500", topic(c, 4), "人工值仍不动");
        }
    }

    @Test
    void previewIsReadOnlyAndMatchesTheRealRun() throws Exception {
        try (Connection c = connect()) {
            String before = snapshot(c);
            List<List<String>> results = query(c, PREVIEW);
            assertEquals(before, snapshot(c), "预览不改任何数据");
            // 结果集:0 版本 / 1 现状 / 2 主题变化 / 3 活动变化 / 4 歧义
            assertEquals(List.of("1:310100:FILL", "2:440100:FILL"), results.get(2));
            assertEquals(List.of("1:310100:FILL", "3:440100:FILL"), results.get(3));
            assertEquals(List.of("cms_activity:2:CLUB_PARENT_CONFLICT", "cms_activity:4:NO_STRUCTURED_SOURCE",
                    "cms_topic:3:NO_STRUCTURED_SOURCE", "cms_topic:5:CLUB_CITY_UNRECOGNIZED"), results.get(4));
            run(c, BACKFILL);
            assertEquals("310100", topic(c, 1));
            assertEquals("440100", topic(c, 2));
            assertEquals("310100", activity(c, 1));
            assertEquals("440100", activity(c, 3));
            assertNull(activity(c, 2));
        }
    }

    // ---------- helpers ----------

    private static String topic(Connection c, long id) throws Exception {
        return string(c, "SELECT city_code FROM cms_topic WHERE id=" + id);
    }

    private static String activity(Connection c, long id) throws Exception {
        return string(c, "SELECT city_code FROM cms_activity WHERE id=" + id);
    }

    private static String snapshot(Connection c) throws Exception {
        return string(c, "SELECT CONCAT_WS('|',"
                + " (SELECT GROUP_CONCAT(id, '=', IFNULL(city_code, '-') ORDER BY id) FROM cms_topic),"
                + " (SELECT GROUP_CONCAT(id, '=', IFNULL(city_code, '-') ORDER BY id) FROM cms_activity),"
                + " (SELECT GROUP_CONCAT(target_table, row_id, '=', city_code, '/', source ORDER BY target_table, row_id)"
                + "  FROM o14_city_code_backfill_log))");
    }

    private static int scalar(Connection c, String sql) throws Exception {
        return Integer.parseInt(string(c, sql));
    }

    private static String string(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    /** 仓库执行器同口径:去整行注释、按分号切(单引号内不切),同一连接逐条执行(临时表要同会话);断言语句必须为真。 */
    private static void run(Connection c, String migration) throws Exception {
        try (Statement st = c.createStatement()) {
            for (String sql : statements(migration)) {
                if (sql.contains("AS assertion_ok")) {
                    try (ResultSet rs = st.executeQuery(sql)) {
                        assertTrue(rs.next() && rs.getInt(1) == 1, "迁移断言不成立: " + migration);
                    }
                } else {
                    st.execute(sql);
                }
            }
        }
    }

    /** 逐条执行只读预览,每个结果集收成「首列:倒数第二个代码列:最后一列」的紧凑行,供逐行比对。 */
    private static List<List<String>> query(Connection c, String preview) throws Exception {
        List<List<String>> out = new ArrayList<>();
        try (Statement st = c.createStatement()) {
            for (String sql : statements(preview)) {
                try (ResultSet rs = st.executeQuery(sql)) {
                    List<String> rows = new ArrayList<>();
                    String label = rs.getMetaData().getColumnLabel(1);
                    int columns = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        if ("target_table".equals(label) && "reason".equals(rs.getMetaData().getColumnLabel(columns))) {
                            rows.add(rs.getString(1) + ":" + rs.getString(2) + ":" + rs.getString(columns));
                        } else if ("id".equals(label)) {
                            rows.add(rs.getString(1) + ":" + rs.getString("proposed_city_code") + ":" + rs.getString("action"));
                        } else {
                            rows.add(rs.getString(1));
                        }
                    }
                    out.add(rows);
                }
            }
        }
        return out;
    }

    private static List<String> statements(String path) throws Exception {
        StringBuilder text = new StringBuilder();
        for (String line : new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
            text.append(line).append('\n');
        }
        return ItSchema.splitStatements(text.toString());
    }

    private static String ddl(String base, String table) {
        int start = base.indexOf("CREATE TABLE `" + table + "` (");
        assertTrue(start >= 0, "base schema 缺表 " + table);
        int engine = base.indexOf("ENGINE=", start);
        return base.substring(start, base.indexOf(';', engine));
    }
}
