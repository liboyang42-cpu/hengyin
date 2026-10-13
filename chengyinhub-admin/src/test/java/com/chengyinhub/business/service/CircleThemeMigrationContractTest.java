package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第二组圈层主题的谱系、供给快照与玩家自助记录必须落到独立事实。 */
class CircleThemeMigrationContractTest {
    private static final String SQL =
            "chengyinhub-admin/sql/migration_circle_theme_batch2_20260821.sql";

    @Test
    void migrationKeepsCandidateSelectionOnTopicAndChapterFacts() throws Exception {
        String sql = normalizedSql();

        assertTrue(sql.contains("alter table cms_topic add column circle_theme_code"));
        assertTrue(sql.contains("add column source_template_topic_id"));
        assertTrue(sql.contains("alter table cms_topic_chapter add column circle_candidate_code"));
        assertTrue(sql.contains("add column circle_role"));
        assertTrue(sql.contains("add column circle_policy"));
        assertTrue(sql.contains("add column circle_enabled"));
        assertTrue(sql.contains("ck_chapter_circle_role check"));
        assertTrue(sql.contains("circle_role in ('experience','rest','expression')"));
        assertTrue(sql.contains("ck_chapter_circle_enabled check (circle_enabled in (0,1))"));
        for (String policy : new String[] {
                "workday_evening", "solo", "parent_child", "photo_rules", "photo_service", "night"
        }) {
            assertTrue(sql.contains("'" + policy + "'"), "迁移未允许种子使用的策略: " + policy);
        }
    }

    @Test
    void migrationSeparatesMerchantSupplyFromPlayerSelfRecords() throws Exception {
        String sql = normalizedSql();

        assertTrue(sql.contains("create table merchant_circle_supply_profile"));
        assertTrue(sql.contains("unique key uk_circle_supply_offer_version (offer_id, version_no)"));
        assertTrue(sql.contains("unique key uk_circle_supply_one_current (current_offer_id)"));
        assertTrue(sql.contains("supply_profile_id bigint not null"));
        assertTrue(sql.contains("create table circle_explore_session"));
        assertTrue(sql.contains("create table circle_explore_session_member"));
        assertTrue(sql.contains("create table circle_explore_record"));
        assertTrue(sql.contains("create table circle_explore_answer"));
        assertTrue(sql.contains("unique key uk_circle_record_member_offer (session_id, member_id, offer_id)"));
        assertTrue(sql.contains("unique key uk_circle_answer_member_stage (session_id, member_id, answer_stage)"));
        assertTrue(sql.contains("'adult_favorite','child_favorite'"));
    }

    @Test
    void migrationStoresEveryFailClosedPolicyFactNamedByTheDesign() throws Exception {
        String sql = normalizedSql();

        for (String column : new String[] {
                "supply_name", "supply_kind", "regular_price", "action_type", "action_value",
                "available_hours", "min_people", "max_people", "checked_at", "merchant_rules",
                "single_person_allowed", "declared_age_range", "guardian_mode",
                "guardian_present_throughout", "merchant_childcare_required", "adult_child_capacity",
                "price_unit", "restrictions", "tools_animals_materials", "incident_process",
                "cancellation_rule", "personal_photo_allowed", "photo_allowed_area", "tripod_policy",
                "lighting_policy", "photo_duration_limit", "night_hours", "last_entry", "event_end_at",
                "alcohol_involved", "legal_drinking_age", "legal_drinking_age_rule", "alcohol_warning"
        }) {
            assertTrue(sql.contains(column), "缺少专项供给事实列: " + column);
        }
    }

    private static String normalizedSql() throws Exception {
        return WiringSource.read(SQL).replaceAll("\\s+", " ").replace("( ", "(").toLowerCase();
    }
}
