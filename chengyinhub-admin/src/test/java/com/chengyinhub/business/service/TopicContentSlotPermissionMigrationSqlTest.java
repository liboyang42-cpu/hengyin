package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 内容归属影响票款归属：迁移只能新增独立 F 权限，绝不能复制普通编辑角色。 */
class TopicContentSlotPermissionMigrationSqlTest {

    private static final String MIGRATION =
            "chengyinhub-admin/sql/migration_topic_source_menu_20260722_02_content_slot_permission.sql";
    private static final String ROLLBACK =
            "chengyinhub-admin/sql/bak_topic_content_slot_permission_20260722_rollback.sql";

    @Test
    void createsAnIndependentPermissionUnderTheUniqueOfficialTopicMenu() throws Exception {
        String sql = WiringSource.read(MIGRATION).replaceAll("\\s+", " ");

        int permissionCountBeforeInsert = sql.indexOf("SET @contentSlotPermissionExactCount :=");
        int insertPermission = sql.indexOf("INSERT INTO sys_menu");
        int permissionCountAfterInsert = sql.indexOf(
                "SET @contentSlotPermissionExactCount :=", insertPermission + 1);
        assertTrue(permissionCountBeforeInsert >= 0 && permissionCountBeforeInsert < insertPermission,
                "写前必须计算幂等条件: " + sql);
        assertTrue(permissionCountAfterInsert > insertPermission,
                "写后必须重新读取权限行数，不能把初始 0 当回读: " + sql);

        assertTrue(sql.contains("route_name = 'OfficialTopic'"), sql);
        assertTrue(sql.contains("'F', '0', '0', 'business:topic:content-slot:edit'"), sql);
        assertTrue(sql.contains("@topicMenuCount <> 1"), sql);
        assertTrue(sql.contains("@contentSlotPermissionConflictCount"), sql);
        assertTrue(sql.contains("@migrationBlockCount = 0"), sql);
        assertTrue(sql.contains("回读断言"), sql);
    }

    @Test
    void doesNotEscalateExistingTopicEditRoles() throws Exception {
        String sql = WiringSource.read(MIGRATION).replaceAll("\\s+", " ");

        assertFalse(sql.contains("INSERT INTO sys_role_menu"), sql);
        assertTrue(sql.contains("故意不写 sys_role_menu"), sql);
        assertTrue(sql.contains("最小授权"), sql);
    }

    @Test
    void rollbackOnlyRemovesTheExactIndependentPermission() throws Exception {
        String rollback = WiringSource.read(ROLLBACK).replaceAll("\\s+", " ");

        assertTrue(rollback.contains("@contentSlotPermissionCount"), rollback);
        assertTrue(rollback.contains("@rollbackReady = 1"), rollback);
        assertTrue(rollback.contains("perms = 'business:topic:content-slot:edit'"), rollback);
        assertTrue(rollback.contains("DELETE FROM sys_role_menu"), rollback);
        assertTrue(rollback.contains("DELETE FROM sys_menu"), rollback);
    }
}
