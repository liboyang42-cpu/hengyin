package com.chengyinhub.business.mapper;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantAftercareSchemaRetryTest {

    @Test
    void productionCandidateRepairAcceptsOnlyTheExactEmptyLegacyShape() throws Exception {
        String repair = new String(Files.readAllBytes(Paths.get(
                "sql/migration_merchant_z_candidate_schema_repair_20260826.sql")),
                StandardCharsets.UTF_8).toLowerCase().replaceAll("\\s+", " ");

        assertTrue(repair.contains("@merchant_aftercare_legacy_shape_ok"));
        assertTrue(repair.contains("select count(*) from merchant_aftercare_response")
                && repair.contains("@merchant_aftercare_before_rows = 0"),
                "旧候选表只有在空表前镜像下才允许补不可空 merchant_id");
        assertTrue(repair.contains("add column `merchant_id` bigint not null"));
        assertTrue(repair.contains("drop index `uk_merchant_aftercare_request`"));
        assertTrue(repair.contains("unique key `uk_merchant_aftercare_request` (`merchant_id`, `request_id`)"));
        assertTrue(repair.contains("key `idx_merchant_aftercare_refund_lookup` "
                + "(`refund_application_id`, `merchant_id`, `id`)"));
        assertTrue(repair.contains("-- runner: assert=scalar-true"));
    }

    @Test
    void createCommittedBeforeReadbackCanBeRetriedWithoutTableExistsError() throws Exception {
        String migration = new String(Files.readAllBytes(Paths.get(
                "sql/migration_merchant_operator_aftercare_20260823.sql")), StandardCharsets.UTF_8);
        String create = dynamicCreate(migration)
                .replace(" CHARACTER SET ascii COLLATE ascii_bin", "");

        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:merchant_aftercare_retry;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            runAttempt(connection, create);
            assertTrue(tableExists(connection));

            // 模拟 CREATE 已提交、最终 readback 尚未执行就断线；重跑必须走 SELECT 1。
            runAttempt(connection, create);
            runAttempt(connection, create);
            assertEquals(java.util.Arrays.asList("refund_application_id", "merchant_id", "id"),
                    indexColumns(connection, "idx_merchant_aftercare_refund_lookup"));

            statement.execute("insert into merchant_aftercare_response "
                    + "(refund_application_id,merchant_id,merchant_owner_member_id,actor_member_id,actor_role_code,"
                    + "decision,request_id,create_time) values "
                    + "(1,77,900,88,'MERCHANT_MANAGER','AGREE','same-request',current_timestamp),"
                    + "(1,78,900,89,'MERCHANT_MANAGER','AGREE','same-request',current_timestamp)");
            assertThrows(SQLException.class, () -> statement.execute(
                    "insert into merchant_aftercare_response "
                            + "(refund_application_id,merchant_id,merchant_owner_member_id,actor_member_id,actor_role_code,"
                            + "decision,request_id,create_time) values "
                            + "(1,77,900,88,'MERCHANT_MANAGER','AGREE','same-request',current_timestamp)"));
            try (ResultSet count = statement.executeQuery("select count(*) from merchant_aftercare_response")) {
                assertTrue(count.next());
                assertEquals(2, count.getInt(1));
            }
        }
    }

    private void runAttempt(Connection connection, String create) throws Exception {
        try (Statement statement = connection.createStatement()) {
            if (tableExists(connection)) {
                statement.execute("select 1");
            } else {
                statement.execute(create);
            }
        }
    }

    private boolean tableExists(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from information_schema.tables where table_name='merchant_aftercare_response'")) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private List<String> indexColumns(Connection connection, String indexName) throws Exception {
        Map<Short, String> ordered = new TreeMap<>();
        try (ResultSet result = connection.getMetaData().getIndexInfo(
                null, null, "merchant_aftercare_response", false, false)) {
            while (result.next()) {
                String actualName = result.getString("INDEX_NAME");
                String column = result.getString("COLUMN_NAME");
                if (actualName != null && actualName.equalsIgnoreCase(indexName) && column != null) {
                    ordered.put(result.getShort("ORDINAL_POSITION"), column.toLowerCase());
                }
            }
        }
        return new ArrayList<>(ordered.values());
    }

    private String dynamicCreate(String migration) {
        int assignment = migration.indexOf("SET @merchant_aftercare_response_create_sql := IF(");
        int start = migration.indexOf("'CREATE TABLE", assignment);
        int end = migration.indexOf("',\n  'SELECT 1'", start);
        assertTrue(assignment >= 0 && start >= 0 && end > start, "未找到 aftercare 条件 CREATE");
        return migration.substring(start + 1, end).replace("''", "'");
    }
}
