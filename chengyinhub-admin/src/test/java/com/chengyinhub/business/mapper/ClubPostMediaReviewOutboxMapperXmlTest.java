package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClubPostMediaReviewOutboxMapperXmlTest {
    @Test
    void mapperAndMigrationKeepUniqueClaimReceiptAndPoisonContracts() throws Exception {
        String mapper = read("../chengyinhub-system/src/main/resources/mapper/business/ClubPostMediaReviewOutboxMapper.xml");
        String migration = read("sql/migration_club_role_media_review_outbox_20260824.sql");
        String expanded = read("sql/migration_club_post_media_review_budget_20261024.sql");
        assertTrue(expanded.contains("DROP CHECK chk_club_post_media_review_trace"));
        assertTrue(expanded.contains("OR state = 5"));
        String jobMigration = read("sql/migration_club_role_media_review_outbox_job_20260825.sql");

        assertTrue(mapper.contains("insert ignore into club_post_media_review_outbox"));
        assertTrue(mapper.contains("state in (0, 3)"));
        assertTrue(mapper.contains("LEAST(max_attempts, 5)"));
        assertTrue(mapper.contains("owner_token = UNHEX(#{ownerToken})"));
        assertTrue(mapper.contains("fence_token = #{fenceToken}"));
        assertTrue(mapper.contains("lease_until = TIMESTAMPADD(SECOND, 120, NOW(3))"));
        assertTrue(mapper.contains("trace_hash = UNHEX(#{traceHash})"));
        assertTrue(mapper.contains("attempt_count &lt; <include refid=\"retryBudget\"/>"),
                "扫描和原子 claim 必须使用相同预算");
        assertTrue(mapper.contains("last_error_code = 'PRE_DISPATCH_UNAVAILABLE'"));
        assertTrue(mapper.contains("id=\"retireUnsafeBatch\""));
        assertTrue(mapper.contains("id=\"markManualReview\""));
        assertTrue(mapper.contains("THEN 'REVIEW_OUTCOME_UNKNOWN' ELSE 'REVIEW_ATTEMPTS_EXHAUSTED'"));
        assertTrue(mapper.contains("where state in (0, 1, 3, 5)"));
        assertTrue(mapper.contains("select min(create_time)"),
                "积压年龄必须基于不可被退避重写的创建时间");
        assertTrue(migration.contains("UNIQUE KEY uk_club_post_media_review_identity (post_id, post_version, media_hash)"));
        assertTrue(migration.contains("UNIQUE KEY uk_club_post_media_review_trace (trace_hash)"));
        assertTrue(migration.contains("KEY idx_club_post_media_review_due (state, next_attempt_at, lease_until, id)"));
        assertTrue(migration.contains("KEY idx_club_post_media_review_lease (state, lease_until, id)"),
                "PROCESSING lease 回收必须有独立前导索引");
        assertTrue(migration.contains("KEY idx_club_post_media_review_backlog (state, create_time, id)"));
        assertTrue(migration.contains("AND prefix_parts = 0 AND ascending_parts = parts"),
                "索引坏行不能在 GROUP BY 前被过滤掉后伪装成完整索引");
        assertTrue(migration.contains("REGEXP_REPLACE(LOWER(cc.check_clause)"),
                "不能只按 CHECK 名称伪造结构回读");
        assertTrue(migration.contains("tc.enforced = 'YES'"));
        assertTrue(migration.contains("AND COUNT(*) = 16"), "必须拒绝额外或缺失列");
        assertFalse(migration.toLowerCase().contains("sys_job"),
                "pre-deploy schema 不得提前注册依赖新 jar 的 Quartz bean");
        assertTrue(jobMigration.contains("post-deploy"));
        assertTrue(jobMigration.contains("clubPostMediaReviewTask.run()"));
        String compactMigration = jobMigration.replaceAll("\\s+", " ");
        assertTrue(compactMigration.contains(
                "SELECT COUNT(*) = 1 AND SUM(job_name = '俱乐部动态图片送审恢复'"),
                "Quartz readback 必须拒绝同 invoke_target 的额外错误配置行");
        assertTrue(compactMigration.contains("job_group = 'DEFAULT'")
                && compactMigration.contains("cron_expression = '0 * * * * ?'")
                && compactMigration.contains("misfire_policy = '3'")
                && compactMigration.contains("concurrent = '1'")
                && compactMigration.contains("status = '0'"),
                "post-deploy job 必须 exact-one 回读全部调度配置");
        assertTrue(migration.contains("state IN (0,1,2,3,4,5)"));
        assertTrue(!migration.contains("media_url"), "outbox 禁止持久化原始媒体 URL");
    }

    private String read(String relative) throws Exception {
        Path path = Paths.get(relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
