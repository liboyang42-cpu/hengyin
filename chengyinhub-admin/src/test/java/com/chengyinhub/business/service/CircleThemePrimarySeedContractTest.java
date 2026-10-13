package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文档第一组五个圈层主题必须原样进入可复制主题模板。 */
class CircleThemePrimarySeedContractTest {
    private static final String SQL =
            "chengyinhub-admin/sql/migration_seed_circle_theme_primary_20260821.sql";
    private static final String SCHEMA_SQL =
            "chengyinhub-admin/sql/migration_circle_theme_primary_schema_20260821.sql";

    @Test
    void seedsFiveDocumentThemesAndFortyCandidates() throws Exception {
        String sql = normalizedSql();
        for (String theme : new String[] {"fitness", "midlife", "date", "friends", "shanghai"}) {
            assertTrue(sql.contains("'" + theme + "'"), "缺少主题编码: " + theme);
        }
        for (String name : new String[] {
                "一起动起来", "四十正当时", "今天认真约会", "朋友别只在群里", "上海生活切片"
        }) {
            assertTrue(sql.contains(name), "缺少主题: " + name);
        }
        for (String code : candidateCodes()) {
            assertTrue(sql.contains("'" + code.toLowerCase() + "'"), "缺少角色: " + code);
        }
        assertEquals(40, count(sql, " as candidate_code"));
        assertTrue(sql.contains("每主题8个"));
        assertTrue(sql.contains("任意2家"));
        assertTrue(sql.contains("'traffic', 1,"));
        assertTrue(sql.contains("r.circle_role, 'common', 1"));
        assertFalse(sql.contains("r.circle_role, r.circle_policy, 1"));
    }

    @Test
    void seedIsIdempotentAndDoesNotInventMerchantPackages() throws Exception {
        String sql = normalizedSql();
        assertTrue(sql.contains("where not exists"));
        assertTrue(sql.contains("不要求新建套餐"));
        assertTrue(sql.contains("不要求打折"));
        assertTrue(sql.contains("'experimental'"));
        assertTrue(sql.contains("'none'"));
    }

    @Test
    void schemaExpansionAllowsAllFivePrimaryThemeCodes() throws Exception {
        String sql = WiringSource.read(SCHEMA_SQL).toLowerCase();
        for (String code : new String[] {"fitness", "midlife", "date", "friends", "shanghai"}) {
            assertTrue(sql.contains("'" + code + "'"), "约束缺少主题编码: " + code);
        }
        assertTrue(sql.contains("drop check ck_topic_circle_theme_code"));
        assertTrue(sql.contains("drop check ck_circle_answer_stage"));
        assertTrue(sql.contains("add column duration_minutes"));
        assertTrue(sql.contains("add column circle_reviewed_at"));
        assertTrue(sql.contains("add column split_booking_allowed"));
        assertTrue(sql.contains("add column optional_benefit"));
        assertTrue(sql.contains("add column benefit_rules"));
        assertTrue(sql.contains("add column shared_answer_stage"));
        assertTrue(sql.contains("unique key uk_circle_answer_session_shared"));
        assertTrue(sql.contains("'question_card','next_pick','city_keywords'"));
        for (String stage : new String[] {
                "pre_choice", "post_choice", "self_moment", "question_card", "pre_wish",
                "next_pick", "city_keywords"
        }) {
            assertTrue(sql.contains("'" + stage + "'"), "互动约束缺少阶段: " + stage);
        }
        assertTrue(sql.contains("error 3819"));
    }

    private static String[] candidateCodes() {
        String[] codes = new String[40];
        int index = 0;
        for (String prefix : new String[] {"F", "M", "C", "P", "S"}) {
            for (int number = 1; number <= 8; number++) codes[index++] = prefix + number;
        }
        return codes;
    }

    private static int count(String source, String token) {
        int count = 0;
        for (int index = source.indexOf(token); index >= 0;
             index = source.indexOf(token, index + token.length())) count++;
        return count;
    }

    private static String normalizedSql() throws Exception {
        return WiringSource.read(SQL).replaceAll("\\s+", " ").replace("( ", "(").toLowerCase();
    }
}
