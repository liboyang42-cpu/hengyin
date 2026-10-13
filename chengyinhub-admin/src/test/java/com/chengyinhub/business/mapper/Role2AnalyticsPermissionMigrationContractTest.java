package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Role2AnalyticsPermissionMigrationContractTest {
    private static final String MIGRATION = "migration_role2_analytics_perm_20260918.sql";

    @Test
    void grantsAnalyticsPermissionToRole2Idempotently() throws Exception {
        String sql = normalizedSql();

        assertTrue(sql.contains("insert ignore into sys_role_menu"), "重复执行不得报错");
        assertTrue(sql.contains("perms = 'business:analytics:list'"),
                "menu_id 必须从 sys_menu 现查,权限标识是唯一判据");
        assertTrue(sql.contains("select 2, @analytics_menu"), "只给 role_id=2 授权");
        assertTrue(sql.contains("where @analytics_menu is not null"),
                "菜单行不存在时整句不插入,不裸挂");
    }

    @Test
    void doesNotCreateMenusAndLeavesAReadbackAssertion() throws Exception {
        String sql = normalizedSql();

        assertFalse(sql.contains("insert into sys_menu"), "本迁移只补授权,菜单行由既有迁移负责");
        assertTrue(sql.contains("-- runner: assert=scalar-true"),
                "缺对端回读断言的迁移无法用 audit_migrations_applied.py 对账");
        assertTrue(sql.contains("role_id = 2"), "回读必须从 sys_role_menu 读回 role 2 的授权");
    }

    private String normalizedSql() throws Exception {
        return new String(Files.readAllBytes(Paths.get("sql", MIGRATION)), StandardCharsets.UTF_8)
                .toLowerCase().replaceAll("\\s+", " ");
    }
}
