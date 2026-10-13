package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** roam_poi 是申请当前态唯一真源，迁移不得再污染主题报名表。 */
class RoamPoiApplicationMigrationContractTest {
    @Test
    void applicationStateBelongsOnlyToRoamPoi() throws Exception {
        String sql = migration().toLowerCase().replaceAll("\\s+", " ");

        assertTrue(sql.contains("alter table roam_poi"), "申请字段必须加在 roam_poi");
        assertTrue(sql.contains("add column audit_status")
                        && sql.contains("add column application_type")
                        && sql.contains("add column applicant_merchant_id")
                        && sql.contains("add column audit_reason")
                        && sql.contains("add column address"),
                "迁移必须完整保留独立审核态、申请人和表单地址");
        assertFalse(sql.contains("alter table cms_registration_merchant"),
                "cms_registration_merchant 是主题报名真源，不得承载 roam 申请字段");
        assertFalse(sql.contains("roam_pending_key"), "不得把 A 方案的审核账本占位列带进 B");
    }

    private String migration() throws Exception {
        Path[] candidates = {
                Paths.get("chengyinhub-admin/sql/migration_roam_merchant_application_20260806.sql"),
                Paths.get("sql/migration_roam_merchant_application_20260806.sql")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("找不到 roam 商家申请迁移，门禁不能静默通过");
    }
}
