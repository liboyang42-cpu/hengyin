package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第二组五主题的种子必须完整、幂等，且不能把八个候选冒充八店打卡。 */
class CircleThemeSeedContractTest {
    private static final String SQL =
            "chengyinhub-admin/sql/migration_seed_circle_theme_batch2_20260821.sql";

    @Test
    void seedsFiveFreeExploreTemplatesAndFortyRoleCandidates() throws Exception {
        String sql = normalizedSql();

        for (String theme : new String[] {
                "after_work", "solo", "parent_child", "photo", "night"
        }) {
            assertTrue(sql.contains("'" + theme + "'"), "缺少圈层主题: " + theme);
        }
        for (String code : candidateCodes()) {
            assertTrue(sql.contains("'" + code.toLowerCase() + "'"), "缺少候选: " + code);
        }
        assertEquals(40, count(sql, " as candidate_code"), "必须恰好定义40个候选角色");
        assertTrue(sql.contains("product_type, circle_theme_code"));
        assertTrue(sql.contains("2, r.theme_code"));
        assertTrue(sql.contains("circle_enabled, recruit_enabled"));
        assertTrue(sql.contains("1, 1, 'traffic'"));
        assertTrue(sql.contains("'traffic', 1,"), "每个候选最多绑定1家，实例总数才不会超过3—4家");
    }

    @Test
    void seedIsIdempotentAndDeclaresCitySelectionBoundary() throws Exception {
        String sql = normalizedSql();

        assertTrue(sql.contains("where not exists"));
        assertTrue(sql.contains("unique key") || sql.contains("circle_candidate_code"));
        assertTrue(sql.contains("城市实例复制后必须8选3—4"));
        assertTrue(sql.contains("模板原型的8个候选不是8店打卡"));
        assertTrue(sql.contains("'experimental'"));
        assertTrue(sql.contains("'none'"));
        assertTrue(sql.contains("0.16, 0, '沿用本店正常经营空间'"),
                "商家确认准备时间必须严格低于10分钟");
        assertFalse(sql.contains("0.17, 0, '沿用本店正常经营空间'"));
    }

    @Test
    void seedRequiresAtomicExecutionAndFailClosedReadback() throws Exception {
        String sql = normalizedSql();

        assertTrue(sql.startsWith("-- runner: requires=transaction"));
        assertTrue(sql.contains("create temporary table questify_circle_seed_guard"));
        assertEquals(7, count(sql, "-- runner: assert=rowcount-positive"),
                "分类前置条件和六组业务结果都必须由 runner 强制断言");

        String contentRoleGuard = guardStatement(sql, "content-roles");
        assertTrue(contentRoleGuard.contains("r.entity_type = 'platform'"));
        assertTrue(contentRoleGuard.contains("r.entity_id is null"));

        String categoryGuard = guardStatement(sql, "candidate-categories");
        assertTrue(categoryGuard.contains("join sys_category c on c.id = ch.category_id"));
        assertEquals(2, count(categoryGuard,
                "c.del_flag = 0 and c.status = 1 and c.type = 1"),
                "数量和逐项映射回读都只能接受启用中的一级商家分类");
        assertTrue(categoryGuard.contains("c.category_name <> case ch.circle_candidate_code"));
        for (String category : new String[] {
                "美食烹饪", "运动健身", "娱乐达人", "生活美学", "礼品纪念"
        }) {
            assertTrue(categoryGuard.contains("'" + category.toLowerCase() + "'"));
        }
        assertGuardCategories(categoryGuard, "美食烹饪",
                "A1", "A6", "A8", "O1", "O2", "K4", "K5",
                "H4", "H5", "H8", "N1", "N2", "N7", "N8");
        assertGuardCategories(categoryGuard, "运动健身", "A2", "K1");
        assertGuardCategories(categoryGuard, "娱乐达人", "A7", "O7", "N4", "N5", "N6");
        assertGuardCategories(categoryGuard, "生活美学",
                "A3", "A4", "A5", "O3", "O4", "O6", "K2",
                "K6", "K8", "H1", "H2", "H3", "N3");
        assertGuardCategories(categoryGuard, "礼品纪念", "O5", "O8", "K3", "K7", "H6", "H7");

        String candidateGuard = sql.substring(
                sql.indexOf("select 'candidate-counts'"),
                sql.indexOf(";", sql.indexOf("select 'candidate-counts'")));
        assertEquals(5, count(candidateGuard, ") = 8"), "五个主题都必须恰好有8个候选");
    }

    private static String guardStatement(String sql, String guardName) {
        int start = sql.indexOf("select '" + guardName + "'");
        return sql.substring(start, sql.indexOf(";", start));
    }

    private static void assertGuardCategories(String guard, String category, String... codes) {
        for (String code : codes) {
            assertTrue(guard.contains("when '" + code.toLowerCase() + "' then '"
                    + category.toLowerCase() + "'"), code + " 回读必须核对为 " + category);
        }
    }

    @Test
    void everyCandidateUsesTheConfirmedProductionCategoryMapping() throws Exception {
        String sql = normalizedSql();

        assertFalse(sql.contains(" regexp "), "生产分类必须精确匹配，不能继续使用模糊正则");
        assertFalse(sql.contains("category_pattern"));
        assertTrue(sql.contains(
                "join sys_category c on c.category_name = convert(r.category_name using utf8mb4) "
                        + "collate utf8mb4_general_ci"),
                "派生表文字必须显式对齐生产表排序规则");
        assertTrue(sql.contains(
                "on convert(r.theme_code using utf8mb4) collate utf8mb4_general_ci = "
                        + "t.circle_theme_code"));
        assertTrue(sql.contains(
                "ch.circle_candidate_code = convert(r.candidate_code using utf8mb4) "
                        + "collate utf8mb4_general_ci"));
        assertEquals(2, count(sql,
                "default charset=utf8mb4 collate=utf8mb4_general_ci"),
                "临时表必须与现有业务表使用同一排序规则");

        assertCategories(sql, "美食烹饪",
                "A1", "A6", "A8", "O1", "O2", "K4", "K5",
                "H4", "H5", "H8", "N1", "N2", "N7", "N8");
        assertCategories(sql, "运动健身", "A2", "K1");
        assertCategories(sql, "娱乐达人", "A7", "O7", "N4", "N5", "N6");
        assertCategories(sql, "生活美学",
                "A3", "A4", "A5", "O3", "O4", "O6", "K2",
                "K6", "K8", "H1", "H2", "H3", "N3");
        assertCategories(sql, "礼品纪念", "O5", "O8", "K3", "K7", "H6", "H7");
    }

    private static void assertCategories(String sql, String category, String... codes) {
        String candidates = sql.substring(
                sql.indexOf("select 'after_work' as theme_code, 'a1' as candidate_code"),
                sql.indexOf(") r on convert(r.theme_code using utf8mb4)"));
        for (String code : codes) {
            String marker = "'" + code.toLowerCase() + "' as candidate_code";
            int start = candidates.indexOf(marker);
            int end = candidates.indexOf("union all select", start + marker.length());
            String definition = candidates.substring(start, end < 0 ? candidates.length() : end);
            assertTrue(definition.contains("'" + category.toLowerCase() + "'"),
                    code + " 必须映射到 " + category);
        }
    }

    private static String[] candidateCodes() {
        String[] codes = new String[40];
        int index = 0;
        for (String prefix : new String[] {"A", "O", "K", "H", "N"}) {
            for (int number = 1; number <= 8; number++) {
                codes[index++] = prefix + number;
            }
        }
        return codes;
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
