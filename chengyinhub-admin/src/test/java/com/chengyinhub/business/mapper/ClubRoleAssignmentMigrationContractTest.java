package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClubRoleAssignmentMigrationContractTest {
    @Test
    void migrationHasActiveUniquenessAndIdempotentLegacyAdminBackfill() throws Exception {
        String sql = new String(Files.readAllBytes(Paths.get("sql",
                "migration_club_role_assignment_20260823.sql")), StandardCharsets.UTF_8)
                .toLowerCase().replaceAll("\\s+", " ");

        assertTrue(sql.contains("generated always as")
                && sql.contains("unique key uk_club_role_assignment_active"),
                "同一成员同一 scope 只能有一个 ACTIVE assignment");
        assertTrue(sql.contains("'active','revoked','expired'"),
                "过期 assignment 必须释放 ACTIVE 唯一键，允许重新排班");
        assertTrue(sql.contains("where cm.role = 1 and cm.status = 1 and cm.del_flag = 0"));
        assertTrue(sql.contains("'club_operator', 'club', cm.club_id, 'active'"));
        assertTrue(sql.contains("and not exists (")
                && sql.contains("cra.scope_type = 'club'")
                && sql.contains("cra.status = 'active'")
                && sql.contains("cra.expires_at is null or cra.expires_at > now()"),
                "只有当前有效委派能阻断回填，历史 revoked/expired 不能让旧管理员失去权限");
        assertTrue(sql.contains("set status = 'expired'")
                && sql.contains("where status = 'active' and expires_at is not null and expires_at <= now()"),
                "回填前必须释放已过期却仍占用 active 唯一槽的历史委派");
        assertTrue(sql.contains("left join club_role_assignment cra")
                && sql.contains("and cra.id is null) = 0"),
                "迁移终态必须回读每个有效旧管理员都获得当前有效 assignment");
        assertTrue(sql.contains("ccc3b7adc415ccf486399b075aac12b9efa95e1745b5b4d50635a228634c9c9d")
                        && sql.contains("040e56193b66e9ad8e0fbc94f2d1c5c2b5975c7d15a0ac1c7598fb41714ef0bd"),
                "候选 assignment 表必须精确回读全部列、生成表达式与权限 CHECK 正文/ENFORCED");
        assertFalse(sql.contains("'club_owner'"), "Owner 只能从 club.member_id 派生");
        assertFalse(sql.contains("'club_member'"), "Member 只能从 active club_member 派生");
    }
}
