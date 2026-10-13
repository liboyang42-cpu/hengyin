package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 主题广场推荐是独立治理能力，不能随普通编辑权限扩散。 */
class TopicPlazaRecommendationPermissionMigrationSqlTest {

    private static final String MIGRATION =
            "chengyinhub-admin/sql/migration_topic_plaza_recommend_permission_20260723.sql";
    private static final String ROLLBACK =
            "chengyinhub-admin/sql/bak_topic_plaza_recommend_permission_20260723_rollback.sql";

    @Test
    void createsTheNarrowPermissionUnderTheUniqueTopicPlazaMenu() throws Exception {
        String sql = WiringSource.read(MIGRATION).replaceAll("\\s+", " ");

        int countBeforeInsert = sql.indexOf("SET @plazaRecommendPermissionExactCount :=");
        int insertPermission = sql.indexOf("INSERT INTO sys_menu");
        int countAfterInsert = sql.indexOf("SET @plazaRecommendPermissionExactCount :=", insertPermission + 1);
        assertTrue(countBeforeInsert >= 0 && countBeforeInsert < insertPermission, sql);
        assertTrue(countAfterInsert > insertPermission, "写后必须重新读取权限行数: " + sql);
        assertTrue(sql.contains("route_name = 'TopicPlaza'"), sql);
        assertTrue(sql.contains("'F', '0', '0', 'business:topic:plaza:recommend'"), sql);
        assertTrue(sql.contains("@topicPlazaMenuCount <> 1"), sql);
        assertTrue(sql.contains("@plazaRecommendMigrationBlockCount = 0"), sql);
        assertTrue(sql.contains("回读断言"), sql);
    }

    @Test
    void doesNotEscalateOfficialTopicEditors() throws Exception {
        String sql = WiringSource.read(MIGRATION).replaceAll("\\s+", " ");

        assertFalse(sql.contains("INSERT INTO sys_role_menu"), sql);
        assertTrue(sql.contains("故意不写 sys_role_menu"), sql);
        assertTrue(sql.contains("最小授权"), sql);
    }

    @Test
    void rollbackOnlyRemovesTheExactTopicPlazaPermission() throws Exception {
        String rollback = WiringSource.read(ROLLBACK).replaceAll("\\s+", " ");

        assertTrue(rollback.contains("route_name = 'TopicPlaza'"), rollback);
        assertTrue(rollback.contains("@plazaRecommendPermissionCount"), rollback);
        assertTrue(rollback.contains("@rollbackReady = 1"), rollback);
        assertTrue(rollback.contains("perms = 'business:topic:plaza:recommend'"), rollback);
        assertTrue(rollback.contains("DELETE FROM sys_role_menu"), rollback);
        assertTrue(rollback.contains("DELETE FROM sys_menu"), rollback);
    }
}
