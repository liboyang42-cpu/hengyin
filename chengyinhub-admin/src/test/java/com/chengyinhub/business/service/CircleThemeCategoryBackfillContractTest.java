package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 圈层候选必须映射到生产现有的一级商家品类，不能留下无法承接的空 category_id。 */
class CircleThemeCategoryBackfillContractTest {
    private static final String SQL =
            "chengyinhub-admin/sql/migration_seed_circle_theme_supply_category_backfill_20260821.sql";

    @Test
    void mapsAllEightyCandidatesToActiveMerchantCategories() throws Exception {
        String sql = WiringSource.read(SQL).replaceAll("\\s+", " ").toLowerCase();

        for (String prefix : new String[] {"a", "o", "k", "h", "n", "f", "m", "c", "p", "s"}) {
            for (int number = 1; number <= 8; number++) {
                assertTrue(sql.contains("'" + prefix + number + "'"),
                        "缺少候选品类映射: " + prefix.toUpperCase() + number);
            }
        }
        for (String category : new String[] {
                "运动健身", "美食烹饪", "娱乐达人", "生活美学", "礼品纪念"
        }) {
            assertTrue(sql.contains("'" + category + "'"), "缺少生产一级品类: " + category);
        }
        assertTrue(sql.contains("-- runner: requires=transaction"));
        assertTrue(sql.contains("ch.category_id is null"));
        assertTrue(sql.contains("check (bad_count = 0)"));
    }
}
