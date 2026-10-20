package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.mapper.AuditAppealMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审核集成修复在真 MySQL 8 上的证明(表结构取仓库真源:base_schema + 本批迁移,不手写)。
 *
 * <p>AUDIT-INT-5:migration_audit_report_lane_20261020.sql 把 open_guard 改成「主题/玩法/广场/活动的未结举报单=2,
 * 其余未结单=1」。钉住:存量行按新表达式重算;迁移可重跑;同一内容一张未结举报单 + 一张未结内容审核单可并存,
 * 各自仍只许一张;其他业务类型仍共用一个槽;selectOpenAuditTaskForUpdate 只返回内容槽;cancelOpenTemplateTasks
 * 不碰申诉单与举报单。顺带把本批其余新 SQL(申诉撤回、举报下架恢复、内容安全留置、抽检池标记)在真库上跑一遍。
 */
class AuditReportLaneMyBatisTest {

    private static final String LANE_MIGRATION = "chengyinhub-admin/sql/migration_audit_report_lane_20261020.sql";

    @Test
    void reportLaneMigrationSplitsOpenSlotsOnExistingRowsAndIsRerunnable() throws Exception {
        try (SqlSession session = openSession("audit_report_lane")) {
            Connection c = session.getConnection();
            String base = read("sql/base_schema_20260818.sql");
            exec(c, "DROP TABLE IF EXISTS audit_task", ddl(base, "audit_task"));
            // 存量:主题上一张未结举报单、另一个主题一张未结内容单、广场一张未结举报单、若干终态单
            exec(c, "INSERT INTO audit_task(id,biz_type,biz_id,source_type,status,submitted_at,version) VALUES"
                    + " (1,'TOPIC',10,'report',0,NOW(),0),(2,'TOPIC',11,'user_publish',0,NOW(),0),"
                    + " (3,'TOPIC',10,'user_publish',1,NOW(),0),(4,'SQUARE',12,'report',0,NOW(),0),"
                    + " (5,'TEMPLATE',13,NULL,3,NOW(),0)");
            assertEquals(1, number(c, "SELECT open_guard FROM audit_task WHERE id=1"), "改前举报与审核共用槽");

            applyMigration(c, LANE_MIGRATION);
            assertEquals(2, number(c, "SELECT open_guard FROM audit_task WHERE id=1"), "存量未结举报单重算进举报槽");
            assertEquals(1, number(c, "SELECT open_guard FROM audit_task WHERE id=2"));
            assertEquals(0, number(c, "SELECT COUNT(*) FROM audit_task WHERE id=3 AND open_guard IS NOT NULL"));
            assertEquals(1, number(c, "SELECT open_guard FROM audit_task WHERE id=4"), "广场举报仍在共用槽(行为不变)");
            assertEquals(1, number(c, "SELECT open_guard FROM audit_task WHERE id=5"), "source_type 为空按内容单");

            applyMigration(c, LANE_MIGRATION);
            assertEquals(1, number(c, "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()"
                    + " AND table_name='audit_task' AND index_name='uk_audit_biz_open' AND seq_in_index=3"
                    + " AND column_name='open_guard' AND non_unique=0"), "重跑不动唯一键");

            exec(c, "INSERT INTO audit_task(id,biz_type,biz_id,source_type,status,submitted_at,version)"
                    + " VALUES (6,'TOPIC',10,'topic_bundle_v2',0,NOW(),0)");
            assertEquals(2, number(c, "SELECT COUNT(*) FROM audit_task WHERE biz_type='TOPIC' AND biz_id=10 AND status IN (0,3)"),
                    "同一主题:未结举报单与未结内容审核单并存");
            assertDuplicate(c, "INSERT INTO audit_task(id,biz_type,biz_id,source_type,status,submitted_at,version)"
                    + " VALUES (7,'TOPIC',10,'report',0,NOW(),0)");
            assertDuplicate(c, "INSERT INTO audit_task(id,biz_type,biz_id,source_type,status,submitted_at,version)"
                    + " VALUES (8,'TOPIC',10,'appeal',0,NOW(),0)");
            assertDuplicate(c, "INSERT INTO audit_task(id,biz_type,biz_id,source_type,status,submitted_at,version)"
                    + " VALUES (9,'SQUARE',12,'auto_gate',0,NOW(),0)");

            AuditTaskMapper tasks = session.getMapper(AuditTaskMapper.class);
            AuditTask open = tasks.selectOpenAuditTaskForUpdate(AuditTask.BIZ_TOPIC, 10L);
            assertEquals(Long.valueOf(6L), open.getId(), "内容槽查询不返回举报单");
            // 举报单 id 更大(order by id desc 会先取到它):内容槽查询仍只能返回内容单
            exec(c, "INSERT INTO audit_task(id,biz_type,biz_id,source_type,status,submitted_at,version)"
                    + " VALUES (15,'TOPIC',11,'report',0,NOW(),0)");
            assertEquals(Long.valueOf(2L), tasks.selectOpenAuditTaskForUpdate(AuditTask.BIZ_TOPIC, 11L).getId(),
                    "开着的举报单不能被当成内容审核单(否则挡住作者编辑/重提)");
            assertEquals(Long.valueOf(4L), tasks.selectOpenAuditTaskForUpdate(AuditTask.BIZ_SQUARE, 12L).getId(),
                    "共用槽类型照旧能取到举报单");

            // 模板:撤销在途审核单不碰申诉单(AUDIT-INT-9)与举报单(AUDIT-INT-5)
            exec(c, "INSERT INTO audit_task(id,biz_type,biz_id,source_type,status,submitted_at,version) VALUES"
                    + " (20,'TEMPLATE_LIBRARY',30,'report',0,NOW(),0),(21,'TEMPLATE_LIBRARY',30,'appeal',0,NOW(),0),"
                    + " (22,'TEMPLATE_LIBRARY',31,'template_market',0,NOW(),0)");
            tasks.cancelOpenTemplateTasks(AuditTask.BIZ_TEMPLATE_LIBRARY, 30L);
            tasks.cancelOpenTemplateTasks(AuditTask.BIZ_TEMPLATE_LIBRARY, 31L);
            assertEquals(0, number(c, "SELECT status FROM audit_task WHERE id=20"));
            assertEquals(0, number(c, "SELECT status FROM audit_task WHERE id=21"));
            assertEquals(4, number(c, "SELECT status FROM audit_task WHERE id=22"));

            // 申诉撤回(AUDIT-INT-6/9):任务按版本 CAS 撤销,申诉记录 WITHDRAWN
            exec(c, "DROP TABLE IF EXISTS audit_appeal",
                    ddl(read("chengyinhub-admin/sql/migration_audit_appeal_20261020.sql"), "audit_appeal"),
                    "INSERT INTO audit_appeal(id,biz_type,biz_id,task_id,member_id,reason,state,due_at,created_at)"
                            + " VALUES (1,'TEMPLATE_LIBRARY',30,21,5,'理由','REVIEWING',NOW(),NOW())");
            assertEquals(0, tasks.cancelOpenAuditTaskCas(21L, 9));
            assertEquals(1, tasks.cancelOpenAuditTaskCas(21L, 0));
            assertEquals(1, session.getMapper(AuditAppealMapper.class).withdrawByTask(21L, new Date()));
            assertEquals("WITHDRAWN", text(c, "SELECT state FROM audit_appeal WHERE id=1"));
            assertEquals(1, tasks.updateTaskSnapshot(20L, "{\"preTakedown\":{}}"));

            // 举报下架恢复(AUDIT-INT-4):只从举报下架态原样恢复
            exec(c, "DROP TABLE IF EXISTS cms_member_template", ddl(base, "cms_member_template"),
                    "DROP TABLE IF EXISTS cms_template_library", ddl(base, "cms_template_library"),
                    "INSERT INTO cms_member_template(id,member_id,title,status,check_status,publish_status,draft_status,del_flag)"
                            + " VALUES (40,5,'玩法',2,2,0,1,0)",
                    "INSERT INTO cms_template_library(id,member_id,member_template_id,title,status,publish_status,del_flag)"
                            + " VALUES (41,5,40,'玩法',2,0,0)");
            assertEquals(1, session.getMapper(CmsMemberTemplateMapper.class).restoreReportTakedown(40L, 5L, 1L, 1, 0L, ""));
            assertEquals(0, session.getMapper(CmsMemberTemplateMapper.class).restoreReportTakedown(40L, 5L, 1L, 1, 0L, ""),
                    "已离开举报下架态不再恢复");
            assertEquals(1, session.getMapper(CmsTemplateLibraryMapper.class).restoreReportTakedown(41L, 1L, 1L, ""));
            assertEquals(0, number(c, "SELECT publish_status FROM cms_member_template WHERE id=40"), "未上架的保持未上架");
            assertEquals(1, number(c, "SELECT publish_status FROM cms_template_library WHERE id=41"));

            // 内容安全留置(AUDIT-INT-7)与抽检池标记(AUDIT-INT-8)
            exec(c, "DROP TABLE IF EXISTS cms_topic", ddl(base, "cms_topic"),
                    "DROP TABLE IF EXISTS cms_activity", ddl(base, "cms_activity"),
                    // review_flag 由 migration_audit_level_20261020.sql 加(该文件还要 community_post 表),这里按同形状补上
                    "ALTER TABLE cms_topic ADD COLUMN review_flag TINYINT NOT NULL DEFAULT 0");
            applyMigration(c, "chengyinhub-admin/sql/migration_content_safety_hold_20261020.sql");
            applyMigration(c, "chengyinhub-admin/sql/migration_content_safety_hold_20261020.sql");
            exec(c, "INSERT INTO cms_topic(id,member_id,name,status,check_status,del_flag) VALUES (50,5,'主题',2,2,0)",
                    "INSERT INTO cms_activity(id,member_id,name,status,publish_status,del_flag) VALUES (51,5,'活动',2,0,0)");
            CmsTopicMapper topics = session.getMapper(CmsTopicMapper.class);
            CmsActivityMapper activities = session.getMapper(CmsActivityMapper.class);
            assertEquals(Integer.valueOf(0), topics.selectContentSafetyHold(50L));
            assertEquals(1, topics.markContentSafetyHold(50L));
            assertEquals(1, activities.markContentSafetyHold(51L));
            assertEquals(Integer.valueOf(1), topics.selectContentSafetyHold(50L));
            assertEquals(Integer.valueOf(1), activities.selectContentSafetyHold(51L));
            assertEquals(1, activities.clearContentSafetyHold(51L));
            assertEquals(0, activities.clearContentSafetyHold(51L));
            assertEquals(1, topics.updateReviewFlag(50L, 1));
            assertEquals(1, number(c, "SELECT review_flag FROM cms_topic WHERE id=50"));
        }
    }

    private static void assertDuplicate(Connection c, String sql) {
        Exception e = assertThrows(Exception.class, () -> exec(c, sql));
        assertTrue(e instanceof SQLIntegrityConstraintViolationException
                || String.valueOf(e.getMessage()).contains("uk_audit_biz_open"), String.valueOf(e));
    }

    /** 按仓库执行器同样的方式逐条执行迁移(跳过单引号内分号),并校验末尾的 scalar-true 断言。 */
    private static void applyMigration(Connection c, String relative) throws Exception {
        String text = read(relative);
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.trim().startsWith("--")) body.append(line).append('\n');
        }
        java.util.List<String> statements = ItSchema.splitStatements(body.toString());
        try (Statement st = c.createStatement()) {
            for (int i = 0; i < statements.size() - 1; i++) st.execute(statements.get(i));
            try (ResultSet rs = st.executeQuery(statements.get(statements.size() - 1))) {
                assertTrue(rs.next() && rs.getInt(1) == 1, "迁移末尾断言未通过:" + relative);
            }
        }
    }

    private SqlSession openSession(String db) throws Exception {
        Configuration cfg = new Configuration();
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MybatisItDatabase.url(db), MybatisItDatabase.user(), MybatisItDatabase.password())));
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain.vo");
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain.dto");
        for (Class<?> mapper : new Class<?>[] {AuditTaskMapper.class, AuditAppealMapper.class,
                CmsMemberTemplateMapper.class, CmsTemplateLibraryMapper.class, CmsTopicMapper.class,
                CmsActivityMapper.class}) {
            String resource = "mapper/business/" + mapper.getSimpleName() + ".xml";
            cfg.addMapper(mapper);
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(cfg).openSession(true);
    }

    private static String ddl(String sql, String table) {
        Matcher m = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?`?" + table + "`? \\(.*?\\) ENGINE=[^;]*;",
                Pattern.DOTALL).matcher(sql);
        if (!m.find()) throw new IllegalStateException("仓库里找不到 " + table + " 的建表语句");
        return m.group().replaceAll(";$", "");
    }

    private static String read(String relative) throws Exception {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("sql/base_schema_20260818.sql"))) dir = dir.getParent();
        if (dir == null) throw new IllegalStateException("找不到仓库根");
        return new String(Files.readAllBytes(dir.resolve(relative)), StandardCharsets.UTF_8);
    }

    private static void exec(Connection c, String... sql) throws Exception {
        try (Statement st = c.createStatement()) {
            for (String s : sql) st.execute(s);
        }
    }

    private static int number(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) throw new IllegalStateException("无结果: " + sql);
            return rs.getInt(1);
        }
    }

    private static String text(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) throw new IllegalStateException("无结果: " + sql);
            return rs.getString(1);
        }
    }
}
