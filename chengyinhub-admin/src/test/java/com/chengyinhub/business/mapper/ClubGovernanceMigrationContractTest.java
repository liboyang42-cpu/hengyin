package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClubGovernanceMigrationContractTest {
    @Test
    void migrationProvidesOneEffectiveBanPerClubMemberAndSeparateIdempotencyKeys() throws Exception {
        String sql = new String(Files.readAllBytes(Paths.get("sql",
                "migration_club_role_governance_20260823.sql")), StandardCharsets.UTF_8)
                .toLowerCase().replaceAll("\\s+", " ");

        assertTrue(sql.contains("create table if not exists club_governance_ban"));
        assertTrue(sql.contains("generated always as")
                        && sql.contains("unique key uk_club_governance_active"),
                "同一俱乐部成员只能有一个 ACTIVE 封禁");
        assertTrue(sql.contains("'active','unbanned','expired'"),
                "过期/解封必须释放 ACTIVE 唯一槽");
        assertTrue(sql.contains("unique key uk_club_governance_ban_request (club_id, ban_request_id)")
                        && sql.contains("unique key uk_club_governance_unban_request (club_id, unban_request_id)"),
                "封禁和解封必须各自有俱乐部范围幂等键");
        assertTrue(sql.contains("business_audit_event"), "迁移说明必须声明不可变审计依赖");
        assertTrue(sql.contains("source_type = 'club' and expires_at is not null")
                        && sql.contains("interval 90 day")
                        && sql.contains("source_type = 'platform' and expires_at is null"),
                "俱乐部封禁必须限时且最长90天，只有平台封禁可永久");
        assertTrue(sql.contains("create table if not exists club_governance_owner_transfer")
                        && sql.contains("unique key uk_club_owner_transfer_request"),
                "主理人转让必须有不可变历史与俱乐部范围幂等键");
        assertTrue(sql.contains("create table if not exists club_governance_case")
                        && sql.contains("'report','appeal'")
                        && sql.contains("unique key uk_club_governance_pending_appeal"),
                "举报与申诉必须进入平台工单，且同一封禁只能有一张待处理申诉");
        assertTrue(sql.contains("target_type varchar(16) not null")
                        && sql.contains("target_id bigint not null")
                        && sql.contains("evidence_json varchar(1000) not null")
                        && sql.contains("json_valid(evidence_json)")
                        && sql.contains("target_type in ('club','activity','member')"),
                "举报目标必须是 CLUB/ACTIVITY/MEMBER，证据必须是受控 JSON");
        assertTrue(sql.contains("target_type = 'member' and subject_member_id is not null")
                        && sql.contains("subject_member_id = target_id"),
                "MEMBER 举报必须有明确成员主体，不能让 NULL 使 CHECK 变成 UNKNOWN");
        assertTrue(sql.contains("target_type = 'ban'")
                        && sql.contains("subject_member_id is not null and filer_member_id = subject_member_id"),
                "封禁申诉必须有明确申诉成员主体");
    }

    @Test
    void nullSafetyRepairReplacesAndReadsBackTheAlreadyAppliedConstraint() throws Exception {
        String sql = new String(Files.readAllBytes(Paths.get("sql",
                "migration_club_role_z_null_safety_repair_20260826.sql")), StandardCharsets.UTF_8)
                .toLowerCase().replaceAll("\\s+", " ");

        assertTrue(sql.contains("drop check chk_club_governance_case_relation"));
        assertTrue(sql.contains("target_type = 'member' and subject_member_id is not null"));
        assertTrue(sql.contains("target_type = 'ban'")
                        && sql.contains("subject_member_id is not null and filer_member_id = subject_member_id"));
        assertTrue(sql.contains("information_schema.check_constraints"));
        assertTrue(sql.contains("constraint_name = 'chk_club_governance_case_relation'"));
        assertTrue(sql.contains("tc.enforced = 'yes'")
                        && sql.contains("083de7ee2dc1ad6262a3c7700ebe974491a3eb0e4b2f8c2e0f635dac739f18e4")
                        && sql.contains("a50edfff14921a1910de8aa9ccf6a1d90da8b4a8d8cca0ad8a6215b7f8b91d71"),
                "执行前只接受生产旧式或目标式，不能静默覆盖同名第三形态 CHECK");
        assertTrue(sql.contains("-- runner: assert=scalar-true"));
    }
}
