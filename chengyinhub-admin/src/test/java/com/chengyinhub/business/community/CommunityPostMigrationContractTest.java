package com.chengyinhub.business.community;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CommunityPostMigrationContractTest {
    @Test
    void migrationContainsEveryProfessionalCommunityCapabilityAndReadback() throws Exception {
        Path path = Paths.get("sql/migration_community_post_platform_20260909.sql");
        assertTrue(Files.exists(path), "community post migration must exist");
        String sql = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).toLowerCase();

        String[] tables = {
                "community_post", "community_post_media", "community_post_reference",
                "community_post_revision", "community_post_member_action", "community_post_comment", "community_post_comment_action",
                "community_post_command_receipt",
                "community_post_topic", "community_post_mention", "community_post_outbox",
                "community_guideline_version", "community_guideline_ack", "community_post_enforcement",
                "community_post_appeal", "community_featured_entry", "community_member_mute",
                "community_notification", "community_notification_preference", "community_notification_actor",
                "community_member_governance_state", "community_search_document", "community_erasure_job"
        };
        for (String table : tables) {
            assertTrue(sql.contains("create table if not exists " + table),
                    "migration must be safely resumable for table " + table);
        }
        assertTrue(sql.contains("unique key uk_community_post_request"));
        assertTrue(sql.contains("policy_version"), "举报案件必须固化提交时采用的规则版本");
        assertTrue(sql.contains("idx_biz_event_occurred_at"),
                "高频曝光事件的日聚合必须有 occurred_at 前导索引");
        assertTrue(sql.contains("actor_scope varchar(96) not null default ''anonymous''"),
                "动态 ALTER 语句必须把非空 actor_scope 默认值写入迁移");
        assertTrue(sql.contains("uk_biz_event_idem (event_name, actor_scope, idempotency_key)"),
                "匿名和登录事件都必须落到非空幂等作用域，不能依赖 nullable member_id");
        assertFalse(sql.contains("&lt;"), "普通 SQL 文件不能混入 XML/HTML 转义实体");
        int normalizedDedupe = sql.indexOf("case when canonical_event.member_id is null");
        assertTrue(normalizedDedupe >= 0, "必须存在按权威 member_id 规范化 scope 的预去重");
        assertTrue(normalizedDedupe
                        < sql.indexOf("update biz_event_log\nset actor_scope=case"),
                "重跑必须先按规范化 scope 去重，再回填旧 jar 写入的 ANONYMOUS");
        assertTrue(sql.contains("sha2('priority_snapshotin''urgent'',''standard''', 256)"),
                "CHECK 回读必须比较规范化后的完整定义 hash，宽泛 LIKE 会让错误约束假绿");
        assertTrue(sql.contains("sha2('first_response_minutes_snapshotbetween1and10080', 256)"));
        assertTrue(sql.contains("unique key uk_community_post_media_receipt"));
        assertTrue(sql.contains("unique key uk_community_notification_aggregate"));
        assertTrue(sql.contains("check (lifecycle in"));
        assertTrue(sql.contains("check (audience in"));
        assertTrue(sql.contains("chk_community_post_location_payload"));
        assertTrue(sql.contains("-- runner: assert=scalar-true"));
        assertTrue(sql.contains("count(*) = 23"), "readback must assert every table");
        assertTrue(sql.contains("count(distinct index_name) = 6"),
                "readback must assert command and media receipt idempotency indexes");
        assertTrue(sql.contains("from ums_creative_square"), "legacy square posts must be backfilled before cutover");
        assertTrue(sql.contains("legacy-square-file-"), "legacy file attachments must not disappear");
        assertTrue(sql.contains("cross join json_table"), "legacy media lists require MySQL 8 JSON_TABLE expansion");
        assertTrue(sql.contains("legacy-square-comment-%"), "legacy comments require an independent readback");
        assertTrue(sql.contains("legacy-comment-like-%"), "legacy comment likes require an independent readback");
        assertTrue(sql.contains("root.parent_id is not null"), "legacy reply roots must be canonical top-level comments");
        assertTrue(!sql.contains("communitypostmaintenancetask.run()"),
                "pre-deploy schema migration must not activate a task bean before the new jar exists");
        assertTrue(sql.contains("fk_community_post_guideline"), "post must reference its guideline version");
        assertTrue(sql.contains("fk_community_appeal_enforcement"), "appeal must reference its enforcement");
        assertTrue(sql.contains("$.rules[*].code"), "guideline rules need stable machine-readable codes");
        assertTrue(sql.contains("add column comment_id"), "rerun must upgrade an older notification table shape");
        assertTrue(sql.contains("chk_community_post_outbox_aggregate"), "outbox aggregate contract must be read back");
        assertTrue(sql.contains("idx_community_notification_comment"), "notification comment index must be restored");
        assertTrue(sql.contains("delete_rule='cascade'"), "actor foreign key readback must verify cascade semantics");
        assertTrue(sql.contains("information_schema.key_column_usage"),
                "actor foreign key readback must verify its three-column mapping");
        assertTrue(sql.indexOf("constraint fk_community_notification_actor_parent")
                        > sql.indexOf("community_add_notification_aggregate_idx"),
                "old parent unique index must be restored before the actor foreign key is added");
    }

    @Test
    void maintenanceJobRegistrationIsAnExplicitPostDeployMigration() throws Exception {
        Path path = Paths.get("sql/migration_community_post_platform_job_20260909.sql");
        assertTrue(Files.exists(path), "post-deploy maintenance job migration must exist");
        String sql = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).toLowerCase();

        assertTrue(sql.contains("insert into sys_job"));
        assertTrue(sql.contains("communitypostmaintenancetask.run()"));
        assertTrue(sql.contains("analyticsaggregationtask.aggregaterecentwindow()"),
                "社区互动漏斗必须重算声明的迟到窗口，不能只算一次昨天");
        assertTrue(sql.contains("update sys_job"),
                "任务 seed 必须能把错误旧配置自愈到当前定义");
        assertTrue(sql.contains("update biz_event_log\nset actor_scope=case"),
                "新 jar 部署后必须再次修复旧 jar 交叠期间写入的作用域");
        assertTrue(sql.contains("actor_scope <> case when member_id is null"),
                "post-deploy readback 必须证明作用域不变量，而不只验证列和索引");
        assertTrue(sql.contains("'0'"), "maintenance job must be enabled after the jar is deployed");
        assertTrue(sql.contains("-- runner: assert=scalar-true"),
                "post-deploy registration requires a business-table readback");
        assertTrue(sql.contains("community_report_policy_legacy"),
                "旧案件必须保留旧哈希并用显式规则版本标记兼容重放");
        assertTrue(!sql.contains("sha2(concat"),
                "数据库 JSON 序列化不能冒充 Java 请求哈希的规范字节");
        assertTrue(!sql.contains("modify column policy_version varchar(64) not null"),
                "举报快照列必须保持旧 jar 可写，不能在部署后破坏回滚合同");

        Path dependencyPath = Paths.get("../ci/migration-dependencies.json");
        String dependencies = new String(Files.readAllBytes(dependencyPath), StandardCharsets.UTF_8);
        assertTrue(dependencies.replaceAll("\\s+", "").contains(
                "\"community_post_platform_job_20260909\":[\"community_post_platform_20260909\"]"),
                "post-deploy job migration must depend on the pre-deploy platform migration");
    }

    @Test
    void childTableReadbackNegativeControlActuallyTurnsRed() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:community_migration_negative;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("create table legacy_child(id bigint primary key)");
        jdbc.execute("create table migrated_child(id bigint primary key)");
        jdbc.update("insert into legacy_child(id) values (1),(2)");
        Integer failed = jdbc.queryForObject(
                "select case when (select count(*) from legacy_child)=(select count(*) from migrated_child) then 1 else 0 end",
                Integer.class);
        assertEquals(Integer.valueOf(0), failed, "omitting child rows must make the gate red");
        jdbc.update("insert into migrated_child(id) values (1),(2)");
        Integer passed = jdbc.queryForObject(
                "select case when (select count(*) from legacy_child)=(select count(*) from migrated_child) then 1 else 0 end",
                Integer.class);
        assertEquals(Integer.valueOf(1), passed);
    }

    @Test
    void oldNotificationShapeMustFailUntilRequiredColumnAndIndexAreRestored() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:community_notification_resume_negative;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("create table community_notification(id bigint primary key,recipient_member_id bigint)");
        Integer before = jdbc.queryForObject(
                "select case when exists(select 1 from information_schema.columns where table_name='COMMUNITY_NOTIFICATION' and column_name='COMMENT_ID') then 1 else 0 end",
                Integer.class);
        assertEquals(Integer.valueOf(0), before, "old shape must turn the structural readback red");

        jdbc.execute("alter table community_notification add column comment_id bigint");
        jdbc.execute("create index idx_community_notification_comment on community_notification(comment_id,id)");
        Integer after = jdbc.queryForObject(
                "select case when exists(select 1 from information_schema.columns where table_name='COMMUNITY_NOTIFICATION' and column_name='COMMENT_ID') and exists(select 1 from information_schema.indexes where table_name='COMMUNITY_NOTIFICATION' and index_name='IDX_COMMUNITY_NOTIFICATION_COMMENT') then 1 else 0 end",
                Integer.class);
        assertEquals(Integer.valueOf(1), after);
    }

    @Test
    void actorForeignKeyWithTheRightNameButWrongDeleteRuleMustTurnRed() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:community_actor_fk_negative;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("create table parent_notification(recipient_id bigint,type varchar(32),aggregate_key varchar(64),"
                + "unique(recipient_id,type,aggregate_key))");
        jdbc.execute("create table notification_actor(recipient_id bigint,type varchar(32),aggregate_key varchar(64),"
                + "constraint fk_actor_parent foreign key(recipient_id,type,aggregate_key) "
                + "references parent_notification(recipient_id,type,aggregate_key))");

        Integer wrong = jdbc.queryForObject(
                "select case when exists(select 1 from information_schema.referential_constraints "
                        + "where constraint_name='FK_ACTOR_PARENT' and delete_rule='CASCADE') then 1 else 0 end",
                Integer.class);
        assertEquals(Integer.valueOf(0), wrong, "same-name NO ACTION foreign key must fail the gate");

        jdbc.execute("alter table notification_actor drop constraint fk_actor_parent");
        jdbc.execute("alter table notification_actor add constraint fk_actor_parent "
                + "foreign key(recipient_id,type,aggregate_key) "
                + "references parent_notification(recipient_id,type,aggregate_key) on delete cascade");
        Integer fixed = jdbc.queryForObject(
                "select case when exists(select 1 from information_schema.referential_constraints "
                        + "where constraint_name='FK_ACTOR_PARENT' and delete_rule='CASCADE') then 1 else 0 end",
                Integer.class);
        assertEquals(Integer.valueOf(1), fixed);
    }
}
