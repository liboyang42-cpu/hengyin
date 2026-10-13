package com.chengyinhub.business.it;

import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第二组圈层种子必须在 CI 的 Oracle MySQL 8.0.45 上真实落出完整、可重放的数据。 */
class CircleThemeSeedMySqlIT {
    private static final String SEED =
            "sql/migration_seed_circle_theme_batch2_20260821.sql";
    private static final String REPAIR =
            "sql/migration_seed_circle_theme_batch2_repair_20260821.sql";

    @Test
    void seedCreatesFiveTemplatesAndFortyExactlyCategorizedCandidates() throws Exception {
        String url = MybatisItDatabase.url("circle_theme_seed");
        try (Connection connection = DriverManager.getConnection(
                url, MybatisItDatabase.user(), MybatisItDatabase.password())) {
            String version = scalarString(connection, "SELECT VERSION()");
            assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(version), "需要 Oracle MySQL >= 8,实际 " + version);

            ItSchema.apply(connection, "it-schema/explore-alliance-it-schema.sql");
            ItSchema.applyMigrations(connection,
                    "sql/migration_product_role_20260716.sql",
                    "sql/migration_topic_template_meta_20260726.sql",
                    "sql/migration_circle_theme_batch2_20260821.sql");
            seedProductionCategories(connection);

            executeSeedInTransaction(connection);
            assertSeedFacts(connection);

            executeSeedInTransaction(connection);
            assertSeedFacts(connection);
        }
    }

    @Test
    void repairConvergesTheExactProductionPreimage() throws Exception {
        String url = MybatisItDatabase.url("circle_theme_seed_repair");
        try (Connection connection = DriverManager.getConnection(
                url, MybatisItDatabase.user(), MybatisItDatabase.password())) {
            String version = scalarString(connection, "SELECT VERSION()");
            assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(version), "需要 Oracle MySQL >= 8,实际 " + version);

            ItSchema.apply(connection, "it-schema/explore-alliance-it-schema.sql");
            ItSchema.applyMigrations(connection,
                    "sql/migration_product_role_20260716.sql",
                    "sql/migration_topic_template_meta_20260726.sql",
                    "sql/migration_circle_theme_batch2_20260821.sql");
            seedProductionCategories(connection);
            executeSeedInTransaction(connection);
            prepareOldProductionPreimage(connection);

            assertEquals(6, oldCategoryMappingCount(connection));
            assertEquals(40, scalarInt(connection,
                    "SELECT COUNT(*) FROM cms_topic_chapter_template_meta m "
                            + "JOIN cms_topic t ON t.id=m.topic_id WHERE t.is_template=1 "
                            + "AND t.del_flag=0 AND m.effort_prep_hours=0.17"));

            executeRepairInTransaction(connection);

            assertSeedFacts(connection);
            assertEquals(0, oldCategoryMappingCount(connection));
            assertEquals(40, scalarInt(connection,
                    "SELECT COUNT(*) FROM cms_topic_chapter_template_meta m "
                            + "JOIN cms_topic t ON t.id=m.topic_id WHERE t.is_template=1 "
                            + "AND t.del_flag=0 AND m.effort_prep_hours=0.16"));
        }
    }

    private static void seedProductionCategories(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sys_category ("
                    + "id INT NOT NULL AUTO_INCREMENT, category_name VARCHAR(64) NOT NULL, "
                    + "type INT NOT NULL, status TINYINT NOT NULL, del_flag TINYINT NOT NULL, "
                    + "PRIMARY KEY (id), UNIQUE KEY uk_category_name (category_name)) "
                    + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            statement.executeUpdate("INSERT INTO sys_category(category_name,type,status,del_flag) VALUES "
                    + "('美食烹饪',1,1,0),('运动健身',1,1,0),('娱乐达人',1,1,0),"
                    + "('生活美学',1,1,0),('礼品纪念',1,1,0)");
        }
    }

    private static void executeSeedInTransaction(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        try {
            ItSchema.applyMigrations(connection, SEED);
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void prepareOldProductionPreimage(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE cms_topic_chapter SET category_id=("
                    + "SELECT id FROM sys_category WHERE category_name='娱乐达人') "
                    + "WHERE circle_candidate_code IN ('A5','H6','H7','K7','K8','O8') "
                    + "AND topic_id IN (SELECT id FROM cms_topic WHERE is_template=1 AND del_flag=0)");
            statement.executeUpdate("UPDATE cms_topic_chapter_template_meta SET effort_prep_hours=0.17 "
                    + "WHERE topic_id IN (SELECT id FROM cms_topic WHERE is_template=1 AND del_flag=0)");
        }
    }

    private static int oldCategoryMappingCount(Connection connection) throws Exception {
        return scalarInt(connection,
                "SELECT COUNT(*) FROM cms_topic_chapter ch "
                        + "JOIN cms_topic t ON t.id=ch.topic_id "
                        + "JOIN sys_category c ON c.id=ch.category_id "
                        + "WHERE t.is_template=1 AND t.del_flag=0 "
                        + "AND ch.circle_candidate_code IN ('A5','H6','H7','K7','K8','O8') "
                        + "AND c.category_name='娱乐达人'");
    }

    private static void executeRepairInTransaction(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        try {
            ItSchema.applyMigrations(connection, REPAIR);
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void assertSeedFacts(Connection connection) throws Exception {
        assertEquals(5, scalarInt(connection,
                "SELECT COUNT(*) FROM cms_topic WHERE is_template=1 AND del_flag=0 "
                        + "AND circle_theme_code IN ('AFTER_WORK','SOLO','PARENT_CHILD','PHOTO','NIGHT')"));
        assertEquals(5, scalarInt(connection,
                "SELECT COUNT(*) FROM product_role r JOIN cms_topic t ON t.id=r.topic_id "
                        + "WHERE t.is_template=1 AND t.del_flag=0 AND r.slot='CONTENT' AND r.status=1"));
        assertEquals(5, scalarInt(connection,
                "SELECT COUNT(*) FROM cms_topic_template_meta m JOIN cms_topic t ON t.id=m.topic_id "
                        + "WHERE t.is_template=1 AND t.del_flag=0"));
        assertEquals(40, scalarInt(connection,
                "SELECT COUNT(*) FROM cms_topic_chapter ch JOIN cms_topic t ON t.id=ch.topic_id "
                        + "WHERE t.is_template=1 AND t.del_flag=0"));
        assertEquals(0, scalarInt(connection,
                "SELECT COUNT(*) FROM cms_topic_chapter ch JOIN cms_topic t ON t.id=ch.topic_id "
                        + "WHERE t.is_template=1 AND t.del_flag=0 AND ch.category_id IS NULL"));
        assertEquals(40, scalarInt(connection,
                "SELECT COUNT(*) FROM cms_topic_chapter_template_meta m "
                        + "JOIN cms_topic t ON t.id=m.topic_id WHERE t.is_template=1 AND t.del_flag=0"));

        Map<String, String> expected = new LinkedHashMap<>();
        add(expected, "美食烹饪", "A1", "A6", "A8", "O1", "O2", "K4", "K5",
                "H4", "H5", "H8", "N1", "N2", "N7", "N8");
        add(expected, "运动健身", "A2", "K1");
        add(expected, "娱乐达人", "A7", "O7", "N4", "N5", "N6");
        add(expected, "生活美学", "A3", "A4", "A5", "O3", "O4", "O6", "K2",
                "K6", "K8", "H1", "H2", "H3", "N3");
        add(expected, "礼品纪念", "O5", "O8", "K3", "K7", "H6", "H7");

        Map<String, String> actual = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT ch.circle_candidate_code,c.category_name "
                             + "FROM cms_topic_chapter ch JOIN cms_topic t ON t.id=ch.topic_id "
                             + "JOIN sys_category c ON c.id=ch.category_id "
                             + "WHERE t.is_template=1 AND t.del_flag=0 ORDER BY ch.circle_candidate_code")) {
            while (rows.next()) {
                actual.put(rows.getString(1), rows.getString(2));
            }
        }
        assertEquals(expected, actual);
    }

    private static void add(Map<String, String> mapping, String category, String... codes) {
        for (String code : codes) {
            mapping.put(code, category);
        }
    }

    private static int scalarInt(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static String scalarString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
