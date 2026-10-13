package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunityTrustMigrationContractTest {
    private static final String MIGRATION = "migration_community_trust_20260828.sql";
    private static final String REPORT_IDEMPOTENCY =
            "unique key uk_community_report_request (reporter_member_id, request_id)";
    private static final String CONTRIBUTION_IDEMPOTENCY =
            "unique key uk_merchant_contribution_request (contributor_member_id, idempotency_key)";
    private static final String CONTRIBUTION_SCOPE_GUARD =
            "unique key uk_merchant_contribution_scope (contributor_member_id, merchant_id, scope_key)";
    private static final String REWARD_BUSINESS_KEY =
            "business_key varchar(96) not null";
    private static final String REWARD_UNIQUE_KEY =
            "unique key uk_contribution_reward_business (business_key)";
    private static final String SAFETY_PERIOD_GUARD =
            "unique key uk_merchant_safety_period (member_id, merchant_id, rating_period)";
    private static final String SUPPLEMENT_REQUEST_GUARD =
            "unique key uk_contribution_supplement_request (contributor_member_id, request_id)";
    private static final String SUPPLEMENT_VERSION_GUARD =
            "unique key uk_contribution_supplement_version (contribution_id, expected_version)";

    @Test
    void reportReplayKeyIsNonNullAndUnique() throws Exception {
        String sql = tableSql(normalizedSql(), "community_report_case", "community_report_timeline");

        assertTrue(sql.contains("request_id varchar(96) not null")
                        && sql.contains(REPORT_IDEMPOTENCY),
                "NF-30 举报重放键必须非空且按举报人唯一");
    }

    @Test
    void contributionReplayAndScopeKeysAreNonNullAndUnique() throws Exception {
        String sql = tableSql(normalizedSql(), "merchant_contribution", "merchant_contribution_review");

        assertTrue(sql.contains("idempotency_key varchar(96) not null")
                        && sql.contains("scope_key char(64) not null")
                        && sql.contains(CONTRIBUTION_IDEMPOTENCY)
                        && sql.contains(CONTRIBUTION_SCOPE_GUARD),
                "NF-31 重放键与同人同店同目标防刷键必须同时非空唯一");
    }

    @Test
    void rewardBusinessKeyIsNonNullAndUnique() throws Exception {
        String sql = tableSql(normalizedSql(), "merchant_contribution_reward_ledger", "merchant_safety_rating");

        assertTrue(sql.contains(REWARD_BUSINESS_KEY) && sql.contains(REWARD_UNIQUE_KEY),
                "NF-31 奖励流水必须由非空业务稳定键唯一约束");
    }

    @Test
    void supplementReplayAndVersionKeysAreNonNullAndUnique() throws Exception {
        String sql = tableSql(normalizedSql(), "merchant_contribution_supplement",
                "merchant_contribution_reward_ledger");

        assertTrue(sql.contains("request_id varchar(96) not null")
                        && sql.contains(SUPPLEMENT_REQUEST_GUARD)
                        && sql.contains(SUPPLEMENT_VERSION_GUARD),
                "NF-31 补证重放键和同版本并发闸必须同时非空唯一");
    }

    @Test
    void safetyPeriodKeyIsNonNullAndUnique() throws Exception {
        String sql = tableSql(normalizedSql(), "merchant_safety_rating", "merchant_safety_rating_dimension");

        assertTrue(sql.contains("idempotency_key varchar(96) not null")
                        && sql.contains("rating_period char(6) not null")
                        && sql.contains(SAFETY_PERIOD_GUARD),
                "NF-32 同一用户同一商家同一评分周期只能写一次");
    }

    @Test
    void migrationAvoidsUnsupportedAddColumnIfNotExists() throws Exception {
        String sql = normalizedSql();

        assertFalse(sql.contains("add column if not exists"),
                "MySQL 8 本项目禁用 ADD COLUMN IF NOT EXISTS");
    }

    @Test
    void migrationSeparatesReporterIdentityFromSubjectSafeProjectionData() throws Exception {
        String sql = normalizedSql();

        assertTrue(sql.contains("reporter_member_id bigint not null")
                        && sql.contains("anonymity_policy varchar(32) not null"),
                "举报人身份只保留在内部举报主表并冻结匿名策略");
        assertTrue(sql.contains("create table if not exists community_report_timeline")
                        && sql.contains("public_note varchar(500) not null"),
                "对外时间线只能消费显式 public_note，不能透传内部备注");
    }

    private String normalizedSql() throws Exception {
        return new String(Files.readAllBytes(Paths.get("sql", MIGRATION)), StandardCharsets.UTF_8)
                .toLowerCase().replaceAll("\\s+", " ");
    }

    private String tableSql(String sql, String table, String nextTable) {
        int start = sql.indexOf("create table if not exists " + table + " (");
        int end = sql.indexOf("create table if not exists " + nextTable + " (", start + 1);
        return sql.substring(start, end);
    }
}
