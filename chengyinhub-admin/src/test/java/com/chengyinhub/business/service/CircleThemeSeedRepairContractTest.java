package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 已落生产的旧圈层种子只能按精确前像事务修复，不能重放非幂等结构迁移。 */
class CircleThemeSeedRepairContractTest {
    private static final String SQL =
            "chengyinhub-admin/sql/migration_seed_circle_theme_batch2_repair_20260821.sql";

    @Test
    void repairRequiresExactProductionPreimageAndAtomicExecution() throws Exception {
        String sql = normalizedSql();

        assertTrue(sql.startsWith("-- runner: requires=transaction"));
        assertTrue(sql.contains("create temporary table questify_circle_batch2_repair_guard"));
        assertTrue(sql.contains("create temporary table questify_circle_batch2_repair_target"));
        assertTrue(sql.contains("create temporary table questify_circle_batch2_repair_meta_snapshot"));
        assertTrue(sql.contains("select 'preimage-categories'"));
        assertTrue(sql.contains("select 'preimage-effort'"));
        assertTrue(sql.contains("m.effort_prep_hours = 0.17"));
        assertEquals(10, count(sql, "-- runner: assert=rowcount-positive"),
                "分类目录、目标解析、形状、旧前像、时间快照、两次更新及两组后像都必须由 runner 强制断言");
    }

    @Test
    void repairChangesOnlySixKnownMappingsWithoutHardCodingProductionIds() throws Exception {
        String sql = normalizedSql();
        String targets = sql.substring(
                sql.indexOf("insert into questify_circle_batch2_repair_target"),
                sql.indexOf("update questify_circle_batch2_repair_target", sql.indexOf(
                        "insert into questify_circle_batch2_repair_target")));

        assertTarget(targets, "a5", "生活美学");
        assertTarget(targets, "h6", "礼品纪念");
        assertTarget(targets, "h7", "礼品纪念");
        assertTarget(targets, "k7", "礼品纪念");
        assertTarget(targets, "k8", "生活美学");
        assertTarget(targets, "o8", "礼品纪念");
        assertEquals(6, count(targets, " as candidate_code"));
        assertTrue(targets.contains("'娱乐达人' as source_category_name"));
        assertFalse(sql.contains("990033"));
        assertFalse(sql.contains("category_id = 4"));
        assertFalse(sql.contains("category_id = 24"));
        assertFalse(sql.contains("category_id = 25"));
    }

    @Test
    void repairReadsBackFinalMappingAndSubTenMinuteEffort() throws Exception {
        String sql = normalizedSql();

        assertTrue(sql.contains("update cms_topic_chapter ch join questify_circle_batch2_repair_target r"));
        assertTrue(sql.contains("where ch.category_id = r.source_category_id"));
        assertTrue(sql.contains("update cms_topic_chapter_template_meta m set m.effort_prep_hours = 0.16"));
        assertTrue(sql.contains("m.update_time = m.update_time"));
        assertTrue(sql.contains("not (m.update_time <=> s.update_time)"));
        assertTrue(sql.contains("select 'postimage-categories'"));
        assertTrue(sql.contains("select 'postimage-effort'"));
        assertTrue(sql.contains("when 'a5' then '生活美学'"));
        assertTrue(sql.contains("when 'h6' then '礼品纪念'"));
        assertTrue(sql.contains("when 'o8' then '礼品纪念'"));
        assertTrue(sql.contains("m.effort_prep_hours = 0.16"));
    }

    private static void assertTarget(String targets, String code, String category) {
        String marker = "'" + code + "' as candidate_code";
        int start = targets.indexOf(marker);
        assertTrue(start >= 0, "缺少候选 " + code);
        int end = targets.indexOf("union all select", start + marker.length());
        String definition = targets.substring(start, end < 0 ? targets.length() : end);
        assertTrue(definition.contains("'娱乐达人' as source_category_name"));
        assertTrue(definition.contains("'" + category + "' as target_category_name"),
                code + " 必须与目标分类 " + category + " 成对声明");
    }

    private static int count(String source, String token) {
        int count = 0;
        for (int index = source.indexOf(token); index >= 0;
             index = source.indexOf(token, index + token.length())) {
            count++;
        }
        return count;
    }

    private static String normalizedSql() throws Exception {
        return WiringSource.read(SQL).replaceAll("\\s+", " ").replace("( ", "(").toLowerCase();
    }
}
