package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 迁移静态契约：保留旧 ordinal、两阶段唯一性与未知值负控不得被“简化”掉。 */
class TopicCommercialReviewMigrationContractTest {
    private static final String SQL =
            "chengyinhub-admin/sql/migration_topic_commercial_review_20260814.sql";

    @Test
    void migrationPreservesOldOrdinalsAndAddsPendingAsSeven() throws Exception {
        String sql = WiringSource.read(SQL).replaceAll("\\s+", " ");
        assertTrue(sql.contains("0草稿 1招募 2定价 3售票 4进行 5结算 6关闭 7立项待审"));
        assertTrue(sql.contains("lifecycle NOT IN (0,1,2,3,4,5,6)"), "存量未知值必须在迁移前让门禁转红");
        assertFalse(sql.contains("open_line"));
        assertFalse(sql.contains("tier_k"));
        assertFalse(sql.contains("unlocked_at"));
    }

    @Test
    void phaseBizTypesShareExistingOpenGuardWithoutSecondAuditTable() throws Exception {
        String sql = WiringSource.read(SQL);
        assertTrue(sql.contains("TOPIC_PROJECT_REVIEW/TOPIC_SALE_REVIEW"));
        assertTrue(sql.contains("uk_audit_biz_open"));
        assertFalse(sql.matches("(?s).*CREATE\\s+TABLE\\s+(?!topic_commercial_review_guard).*audit.*"),
                "不得创建第二套审核表");
    }
}
