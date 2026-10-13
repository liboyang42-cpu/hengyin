package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityCancelManualRefundCaseContractTest {
    @Test
    void migrationFreezesMoneyAndEligibilityWithoutClaimingRefundCompletion() throws Exception {
        String sql = read(Paths.get("sql", "migration_event_cancel_manual_refund_case_20260826.sql"));

        assertTrue(sql.contains("create table if not exists activity_cancel_manual_refund_case")
                && sql.contains("paid_cash_amount_fen bigint unsigned not null")
                && sql.contains("verified_registration_ids_json json not null")
                && sql.contains("unverified_registration_ids_json json not null")
                && sql.contains("payer_registration_id bigint not null")
                && sql.contains("unique key uk_activity_cancel_manual_order (activity_id, parent_order_no)"));
        assertTrue(sql.contains("'pending_assessment','manual_refund_required','manual_refund_recorded'")
                && sql.contains("'manual_refund_evidence_verified','no_refund'")
                && sql.contains("assessed_refund_amount_fen <= paid_cash_amount_fen")
                && sql.contains("json_length(verified_registration_ids_json) > 0")
                && sql.contains("json_length(unverified_registration_ids_json) > 0"));
        assertTrue(sql.contains("runner: assert=scalar-true")
                && sql.contains("information_schema.columns")
                && sql.contains("information_schema.statistics")
                && sql.contains("information_schema.check_constraints")
                && sql.contains("tc.enforced = 'yes'"));
        assertFalse(sql.contains("insert into refund_application")
                || sql.contains("update refund_application"));
        assertFalse(sql.contains("status = 'refunded'") || sql.contains("status = 'resolved'"));
        assertTrue(sql.contains("handled_by_user_id") && sql.contains("verified_by_user_id")
                && sql.contains("verified_by_user_id <> handled_by_user_id")
                && sql.contains("evidence_object_key varchar(128)")
                && sql.contains("evidence_sha256 binary(32)")
                && sql.contains("actual_refund_amount_fen = assessed_refund_amount_fen"));
        assertTrue(sql.contains("'original_retry','offline_transfer','wx_abnormal_change','wx_abnormal_bankcard'"));
        assertTrue(sql.contains("table_collation = 'utf8mb4_general_ci'")
                && sql.contains("default charset=utf8mb4 collate=utf8mb4_general_ci"));
        assertTrue(sql.contains("count(*) = 32")
                        && sql.contains("19890136c1e9a986edb16e6b534858931ebdb9f172c28f1f6d9cc3cfa411edf9")
                        && sql.contains("generation_expression"),
                "32 列必须以完整 information_schema 签名精确回读");
        assertTrue(sql.contains("and evidence_object_key is not null")
                        && sql.contains("and evidence_object_key is null"),
                "handle CHECK 必须同时固定已处理时 key 非空、未处理时 key 为空");
        assertTrue(sql.contains("mod(version, 2) = 0")
                        && sql.contains("mod(version, 2) = 1")
                        && sql.contains("version >= 2") && sql.contains("version >= 3"),
                "五态循环必须保留 version 奇偶与最低版本约束");
        assertFalse(sql.contains("[[:space:]`\\()]"),
                "CHECK exact readback must not erase semantic parentheses");
        assertTrue(sql.contains("d214fce37e2d38d3f5c19ca4e1d9ebad1f96eb623bbbc3b84c9359e4f50f5b84")
                        && sql.contains("73b2bec505cae385415d6b12f4e9a9fc402ae0289a722ecca558ba63bedf054e")
                        && sql.contains("'[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk'"),
                "CHECK exact readback must bind name/body/ENFORCED while preserving parentheses");
        assertNotEquals(canonicalCheck("a AND (b OR c)"), canonicalCheck("(a AND b) OR c"),
                "wrong boolean grouping must remain distinguishable by the migration canonicalizer");
    }

    @Test
    void mapperLocksRegistrationSnapshotAndUsesForwardOnlyAssessmentCas() throws Exception {
        String registrationMapper = read(Paths.get("..", "chengyinhub-system", "src", "main", "resources",
                "mapper", "business", "CmsRegistrationMapper.xml"));
        String caseMapper = read(Paths.get("..", "chengyinhub-system", "src", "main", "resources",
                "mapper", "business", "ActivityCancelManualRefundCaseMapper.xml"));

        assertTrue(registrationMapper.contains("id=\"selectbyparentordernoforupdate\"")
                && registrationMapper.contains("where parent_order_no = #{parentorderno}")
                && registrationMapper.contains("order by id for update"));
        assertFalse(registrationMapper.contains("where parent_order_no = #{parentorderno} and owner_type"),
                "the lock query must not hide mixed-scope children before fail-closed validation");
        assertTrue(caseMapper.contains("id=\"assesscase\"")
                && caseMapper.contains("where id = #{caseid}")
                && caseMapper.contains("and version = #{expectedversion}")
                && caseMapper.contains("and status = 'pending_assessment'")
                && caseMapper.contains("version = version + 1"));
        assertTrue(caseMapper.contains("id=\"handlecase\"")
                && caseMapper.contains("status = 'manual_refund_recorded'")
                && caseMapper.contains("id=\"verifycase\"")
                && caseMapper.contains("status = 'manual_refund_evidence_verified'")
                && caseMapper.contains("handled_by_user_id &lt;&gt; #{verifieruserid}")
                && caseMapper.contains("evidence_object_key = #{evidenceobjectkey}")
                && caseMapper.contains("evidence_sha256 = unhex(#{evidencesha256})")
                && caseMapper.contains("id=\"reworkcase\"")
                && caseMapper.contains("status = 'manual_refund_required'")
                && caseMapper.contains("evidence_object_key = null")
                && caseMapper.contains("id=\"reopenassessment\"")
                && caseMapper.contains("status = 'pending_assessment'"));
        assertFalse(caseMapper.contains("refund_application"));
    }

    @Test
    void manifestRegistersTheMigrationDependency() throws Exception {
        String manifest = read(Paths.get("..", "ci", "migration-dependencies.json"));
        assertTrue(manifest.contains("\"event_cancel_manual_refund_case_20260826\"")
                && manifest.contains("\"business_audit_event_20260823\""));
    }

    private String read(java.nio.file.Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .toLowerCase().replaceAll("\\s+", " ");
    }

    private String canonicalCheck(String expression) {
        return expression.toLowerCase().replaceAll("[\\s`]", "");
    }
}
