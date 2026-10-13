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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantCrmCampaignSchemaUpgradeTest {

    @Test
    void oldSchemaInterruptedAfterOwnerRenameConvergesWhenMigrationIsRetried() throws Exception {
        String migration = new String(Files.readAllBytes(Paths.get(
                "sql/migration_merchant_operator_crm_phase1a_delivery_20260823.sql")),
                StandardCharsets.UTF_8);
        String ownerRename = dynamicAlter(migration, "@merchant_crm_campaign_owner_upgrade_sql");
        String rowRename = rowRename(migration);
        String recipientUpgrade = updateStatement(migration, "merchant_crm_campaign_recipient");
        String guardUpgrade = updateStatement(migration, "merchant_crm_delivery_guard");
        String lineagePreflight = assertionQuery(migration, "-- 先拒绝 orphan");

        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:merchant_crm_campaign_upgrade;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table merchant_crm_campaign("
                    + "id bigint primary key, merchant_id bigint not null, merchant_row_id bigint not null, "
                    + "request_id varchar(64) not null)");
            statement.execute("create unique index uk_merchant_crm_campaign_request "
                    + "on merchant_crm_campaign(merchant_id,request_id)");
            statement.execute("create index idx_merchant_crm_campaign_owner "
                    + "on merchant_crm_campaign(merchant_id,id)");
            statement.execute("create table merchant_crm_campaign_recipient("
                    + "id bigint primary key,campaign_id bigint not null,merchant_id bigint not null,"
                    + "member_id bigint not null,channel varchar(16) not null)");
            statement.execute("create table merchant_crm_delivery_guard("
                    + "id bigint primary key,merchant_id bigint not null,member_id bigint not null,"
                    + "channel varchar(16) not null,delivery_date date not null,campaign_id bigint not null,"
                    + "recipient_id bigint not null)");
            statement.execute("insert into merchant_crm_campaign values (1,900,77,'req-1')");
            statement.execute("insert into merchant_crm_campaign_recipient values (11,1,900,41,'IN_APP')");
            statement.execute("insert into merchant_crm_delivery_guard "
                    + "values (21,900,41,'IN_APP',date '2026-08-25',1,11)");

            converge(connection, ownerRename, rowRename, true);
            assertFalse(columnExists(connection, "merchant_id"), "模拟第一步完成后 migration 中断");
            assertTrue(columnExists(connection, "merchant_owner_member_id"));
            assertTrue(columnExists(connection, "merchant_row_id"));

            converge(connection, ownerRename, rowRename, false);
            converge(connection, ownerRename, rowRename, false);
            assertTrue(columnExists(connection, "merchant_id"));
            assertTrue(columnExists(connection, "merchant_owner_member_id"));
            assertFalse(columnExists(connection, "merchant_row_id"));
            assertTrue(scalarBoolean(statement, lineagePreflight));
            statement.executeUpdate("update merchant_crm_delivery_guard set member_id=42 where id=21");
            assertFalse(scalarBoolean(statement, lineagePreflight),
                    "guard.member_id 与 recipient 不一致必须在迁移前 fail closed");
            statement.executeUpdate("update merchant_crm_delivery_guard set member_id=41,channel='COUPON' where id=21");
            assertFalse(scalarBoolean(statement, lineagePreflight),
                    "guard.channel 与 recipient 不一致必须在迁移前 fail closed");
            statement.executeUpdate("update merchant_crm_delivery_guard set channel='IN_APP' where id=21");
            statement.executeUpdate(recipientUpgrade);
            assertEquals(77L, scalarLong(statement,
                    "select merchant_id from merchant_crm_campaign_recipient where id=11"));
            assertEquals(900L, scalarLong(statement,
                    "select merchant_id from merchant_crm_delivery_guard where id=21"));
            assertTrue(scalarBoolean(statement, lineagePreflight),
                    "recipient 回填后中断，重跑预检仍必须接受 row/owner 混合的可收敛状态");
            upgradeChildren(connection, recipientUpgrade, guardUpgrade);
            upgradeChildren(connection, recipientUpgrade, guardUpgrade);

            try (ResultSet row = statement.executeQuery(
                    "select merchant_id,merchant_owner_member_id from merchant_crm_campaign where id=1")) {
                assertTrue(row.next());
                assertEquals(77L, row.getLong("merchant_id"));
                assertEquals(900L, row.getLong("merchant_owner_member_id"));
            }
            assertEquals(77L, scalarLong(statement,
                    "select merchant_id from merchant_crm_campaign_recipient where id=11"));
            assertEquals(77L, scalarLong(statement,
                    "select merchant_id from merchant_crm_delivery_guard where id=21"));

            String lineageReadback = assertionQuery(migration, "UPDATE `merchant_crm_delivery_guard`");
            statement.executeUpdate("update merchant_crm_delivery_guard set member_id=42 where id=21");
            assertFalse(scalarBoolean(statement, lineageReadback),
                    "最终 readback 必须精确核对 guard.member_id 与 recipient");
            statement.executeUpdate("update merchant_crm_delivery_guard set member_id=41,channel='COUPON' where id=21");
            assertFalse(scalarBoolean(statement, lineageReadback),
                    "最终 readback 必须精确核对 guard.channel 与 recipient");
            statement.executeUpdate("update merchant_crm_delivery_guard set channel='IN_APP' where id=21");
            assertTrue(scalarBoolean(statement, lineageReadback));

            statement.execute("insert into merchant_crm_campaign_recipient values (12,999,77,42,'IN_APP')");
            assertFalse(scalarBoolean(statement, lineagePreflight),
                    "orphan recipient 必须在任何 UPDATE 前 fail closed");
            statement.execute("delete from merchant_crm_campaign_recipient where id=12");

            statement.execute("insert into merchant_crm_campaign values (2,901,77,'req-2')");
            statement.execute("insert into merchant_crm_campaign_recipient values (12,2,77,41,'IN_APP')");
            statement.execute("insert into merchant_crm_delivery_guard "
                    + "values (22,901,41,'IN_APP',date '2026-08-25',2,12)");
            assertFalse(scalarBoolean(statement, lineagePreflight),
                    "按 campaign rowId 投影后冲突的 daily guard 必须 fail closed");
            statement.execute("delete from merchant_crm_delivery_guard where id=22");
            statement.execute("delete from merchant_crm_campaign_recipient where id=12");
            statement.execute("delete from merchant_crm_campaign where id=2");

            statement.execute("insert into merchant_crm_campaign values (2,900,78,'req-1')");
            assertThrows(SQLException.class,
                    () -> statement.execute("insert into merchant_crm_campaign values (3,901,77,'req-1')"),
                    "最终幂等键必须按 merchant row，而不是 owner memberId");
        }
    }

    private void upgradeChildren(Connection connection, String recipientUpgrade, String guardUpgrade)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(recipientUpgrade);
            statement.executeUpdate(guardUpgrade);
        }
    }

    private long scalarLong(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private boolean scalarBoolean(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getBoolean(1);
        }
    }

    private void converge(Connection connection, String ownerRename, String rowRename,
                          boolean interruptAfterOwnerRename) throws Exception {
        try (Statement statement = connection.createStatement()) {
            if (columnExists(connection, "merchant_id")
                    && columnExists(connection, "merchant_row_id")
                    && !columnExists(connection, "merchant_owner_member_id")) {
                statement.execute(ownerRename);
                if (interruptAfterOwnerRename) {
                    return;
                }
            }
            if (!columnExists(connection, "merchant_id")
                    && columnExists(connection, "merchant_row_id")
                    && columnExists(connection, "merchant_owner_member_id")) {
                statement.execute(rowRename);
                statement.execute("drop index uk_merchant_crm_campaign_request");
                statement.execute("drop index idx_merchant_crm_campaign_owner");
                statement.execute("create unique index uk_merchant_crm_campaign_request "
                        + "on merchant_crm_campaign(merchant_id,request_id)");
                statement.execute("create index idx_merchant_crm_campaign_owner "
                        + "on merchant_crm_campaign(merchant_id,id)");
            }
        }
    }

    private boolean columnExists(Connection connection, String column) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from information_schema.columns "
                        + "where table_name='merchant_crm_campaign' and column_name=?")) {
            statement.setString(1, column);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private String dynamicAlter(String migration, String variable) {
        int assignment = migration.indexOf("SET " + variable + " := IF(");
        int start = migration.indexOf("'ALTER TABLE", assignment);
        int end = migration.indexOf("',\n  'SELECT 1'", start);
        assertTrue(assignment >= 0 && start >= 0 && end > start, "未找到 owner 兼容 ALTER");
        return migration.substring(start + 1, end).replace("''", "'");
    }

    private String rowRename(String migration) {
        int assignment = migration.indexOf("SET @merchant_crm_campaign_row_upgrade_sql := IF(");
        int start = migration.indexOf("'ALTER TABLE", assignment);
        int end = migration.indexOf(",\n     DROP INDEX", start);
        assertTrue(assignment >= 0 && start >= 0 && end > start, "未找到 merchant row 兼容 ALTER");
        return migration.substring(start + 1, end).replace("''", "'");
    }

    private String updateStatement(String migration, String table) {
        int start = migration.indexOf("UPDATE `" + table + "`");
        int end = migration.indexOf(';', start);
        assertTrue(start >= 0 && end > start, "未找到 " + table + " 存量作用域回填");
        return migration.substring(start, end);
    }

    private String assertionQuery(String migration, String marker) {
        int markerIndex = migration.indexOf(marker);
        int start = migration.indexOf("SELECT NOT EXISTS", markerIndex);
        int end = migration.indexOf(" AS assertion_ok;", start);
        assertTrue(markerIndex >= 0 && start >= 0 && end > start,
                "未找到 campaign lineage orphan/冲突预断言");
        return migration.substring(start, end + " AS assertion_ok".length());
    }
}
