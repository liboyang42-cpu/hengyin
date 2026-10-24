package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.NpcChatLog;
import com.chengyinhub.business.domain.vo.ai.NpcChatDataExportVO;
import com.chengyinhub.business.mapper.NpcChatLogMapper;
import com.chengyinhub.business.service.impl.NpcChatDataService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiAiNpcController;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import com.chengyinhub.business.service.impl.NpcChatWriteService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * 真实 MyBatis + 本地 MySQL:证明用户数据导出/删除的 SQL 归属语义(《漫游AI方案》§7.4)。
 *
 * <p>为什么必须连真库:{@code ApiAiNpcChatDataTest} 用 mock mapper 只能锁死传参链路,
 * 证明不了 {@code WHERE user_id = #{userId}} 本身对不对、resultMap 映射是否生效
 * (mapUnderscoreToCamelCase 全局关着,漏写 resultMap 导出字段会静默全 null)。
 * <ul>
 *   <li>正控:A 能导出/删除自己的全部记录,字段(user_msg 等)真被映射出来。</li>
 *   <li>负控①:A 的导出结果不含 B 的行 —— 若谁把 WHERE user_id 条件删掉/写反,这条转红。</li>
 *   <li>负控②:A 的删除动不到 B 的行 —— 防止用户主动删除退化成全表清理。</li>
 *   <li>入口级:真调 ApiAiNpcController 端点方法(登录态伪造为 A),走真 service + 真 SQL,
 *       复现「A 的登录态尝试拿/删 B 的数据」被归属过滤挡下。</li>
 * </ul>
 *
 * <p>本地库缺失时自动 skip(不破坏 CI/常规 mvn test)。手动跑法同 {@link NpcChatRetentionMyBatisIT}:
 *   mvn -pl chengyinhub-admin -am test -Dtest=NpcChatUserDataMyBatisIT
 * <p>⚠️ surefire 默认只收 *Test.java,<b>本 *IT 不会在 CI 自动跑</b>,须 -Dtest 点名。
 */
class NpcChatUserDataMyBatisIT {

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS npc_chat_log ("
          + "  id BIGINT PRIMARY KEY AUTO_INCREMENT,"
          + "  user_id BIGINT NOT NULL,"
          + "  profile_id BIGINT NOT NULL,"
          + "  activity_id BIGINT NOT NULL,"
          + "  user_msg TEXT NOT NULL,"
          + "  npc_reply TEXT,"
          + "  safety_flag TINYINT DEFAULT 0,"
          + "  model VARCHAR(32),"
          + "  latency_ms INT, token_in INT, token_out INT,"
          + "  create_time DATETIME,"
          + "  KEY idx_user_activity (user_id, activity_id),"
          + "  KEY idx_safety (safety_flag)"
          + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("npc_chat_userdata"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new SpringManagedTransactionFactory(), ds));
        try (InputStream in = Resources.getResourceAsStream("mapper/business/NpcChatLogMapper.xml")) {
            new XMLMapperBuilder(in, cfg, "mapper/business/NpcChatLogMapper.xml", cfg.getSqlFragments()).parse();
        }
        try (InputStream in = Resources.getResourceAsStream("mapper/business/NpcChatRequestMapper.xml")) {
            new XMLMapperBuilder(in, cfg, "mapper/business/NpcChatRequestMapper.xml", cfg.getSqlFragments()).parse();
        }
        try(InputStream in=Resources.getResourceAsStream("mapper/business/NpcAsrIntentMapper.xml")) {
            new XMLMapperBuilder(in,cfg,"mapper/business/NpcAsrIntentMapper.xml",cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private SqlSession open() {
        try {
            SqlSession s = factory().openSession();
            s.getConnection().isValid(2);
            Statement st = s.getConnection().createStatement();
            st.execute(CREATE_TABLE);
            st.execute("DELETE FROM npc_chat_log");
            st.execute("CREATE TABLE IF NOT EXISTS ums_member (id BIGINT PRIMARY KEY) ENGINE=InnoDB");
            st.execute("INSERT IGNORE INTO ums_member(id) VALUES (1),(2)");
            java.nio.file.Path migration = java.nio.file.Paths.get("chengyinhub-admin/sql/migration_npc_chat_request_20260722.sql");
            if (!java.nio.file.Files.exists(migration)) migration = java.nio.file.Paths.get("sql/migration_npc_chat_request_20260722.sql");
            String ddl = new String(java.nio.file.Files.readAllBytes(migration), java.nio.charset.StandardCharsets.UTF_8);
            int begin = ddl.indexOf("CREATE TABLE IF NOT EXISTS npc_chat_request");
            st.execute(ddl.substring(begin, ddl.indexOf(';', begin)));
            // 删除擦除语句同时清 audio_asset_id：按仓库迁移原样补列（与 A02NpcDeleteBarrierMySqlIT 同口径，迁移幂等）。
            java.nio.file.Path mediaMigration = java.nio.file.Paths.get("chengyinhub-admin/sql/migration_20261012_npc_chat_media_asset.sql");
            if (!java.nio.file.Files.exists(mediaMigration)) mediaMigration = java.nio.file.Paths.get("sql/migration_20261012_npc_chat_media_asset.sql");
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(s.getConnection(),
                    new org.springframework.core.io.FileSystemResource(mediaMigration.toFile()));
            try(java.sql.ResultSet existing=st.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='npc_asr_intent'")) {
                if(existing.next() && existing.getInt(1)==0) {
                    java.nio.file.Path asrMigration=java.nio.file.Paths.get("chengyinhub-admin/sql/migration_20261024_d_npc_asr_intent.sql");
                    if(!java.nio.file.Files.exists(asrMigration))asrMigration=java.nio.file.Paths.get("sql/migration_20261024_d_npc_asr_intent.sql");
                    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(s.getConnection(),
                            new org.springframework.core.io.FileSystemResource(asrMigration.toFile()));

                }
            }
            try (java.sql.ResultSet columns=st.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
                    +"WHERE table_schema=DATABASE() AND table_name='npc_asr_intent' AND column_name='quota_reservation'")) {
                if (columns.next() && columns.getInt(1)==0) {
                    java.nio.file.Path asrQuotaMigration=java.nio.file.Paths.get("chengyinhub-admin/sql/migration_20261024_d_npc_asr_shared_quota.sql");
                    if(!java.nio.file.Files.exists(asrQuotaMigration))asrQuotaMigration=java.nio.file.Paths.get("sql/migration_20261024_d_npc_asr_shared_quota.sql");
                    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(s.getConnection(),
                            new org.springframework.core.io.FileSystemResource(asrQuotaMigration.toFile()));
                }
            }
            st.execute("DELETE FROM npc_chat_request");
            st.execute("DELETE FROM npc_asr_intent");
            return s;
        } catch (Throwable t) {
            // ★ 2026-08-14:原本是 Assumptions.assumeTrue(false, ...) —— 库连不上就整套 skip,
            // 而 skip 在构建里算**绿**。永远不会红的测试比没有测试更危险:它让人以为验过了。
            // 现在缺库 = 红。跑法见 ci/backend-test.sh(CI 的 backend job 自带 MySQL 服务)。
            throw new IllegalStateException("IT 前置不满足(库不可达 / Mapper XML / schema 配置有误),本套必须真跑不得跳过:" + t.getMessage(), t);
        }
    }

    private void insert(Connection c, long userId, String userMsg) throws Exception {
        c.createStatement().execute(
                "INSERT INTO npc_chat_log (user_id, profile_id, activity_id, user_msg, npc_reply, create_time) "
              + "VALUES (" + userId + ", 1, 0, '" + userMsg + "', '回复', NOW())");
    }

    private int count(Connection c, String whereClause) throws Exception {
        try (ResultSet rs = c.createStatement()
                .executeQuery("SELECT COUNT(*) FROM npc_chat_log WHERE " + whereClause)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** 正控 + 负控①:A 导出自己的全部行且字段真被映射,B 的行绝不出现。 */
    @Test
    void exportSql_returnsOnlyOwnRows_withFieldsMapped() throws Exception {
        try (SqlSession s = open()) {
            Connection c = s.getConnection();
            insert(c, 1, "A的消息一");
            insert(c, 1, "A的消息二");
            insert(c, 2, "B的机密");

            List<NpcChatLog> rows = s.getMapper(NpcChatLogMapper.class).selectNpcChatLogsByUserId(1L);

            assertEquals(2, rows.size(), "A 必须拿到且只拿到自己的 2 条");
            for (NpcChatLog row : rows) {
                assertEquals(Long.valueOf(1L), row.getUserId(), "负控:结果集出现非 A 归属的行 = WHERE user_id 失守");
                assertNotNull(row.getUserMsg(), "user_msg 必须被 resultMap 映射出来(漏映射会静默全 null)");
                assertNotNull(row.getCreateTime(), "create_time 必须被 resultMap 映射出来");
            }
            // 按内容断言身份,不只数个数:B 的原文绝不允许穿透
            assertEquals("A的消息一", rows.get(0).getUserMsg());
            assertEquals("A的消息二", rows.get(1).getUserMsg());
        }
    }

    /** 正控 + 负控②:A 删光自己的行,B 的行必须存活。 */
    @Test
    void deleteSql_removesOnlyOwnRows_otherUserSurvives() throws Exception {
        try (SqlSession s = open()) {
            Connection c = s.getConnection();
            insert(c, 1, "A的消息一");
            insert(c, 1, "A的消息二");
            insert(c, 2, "B的消息一");
            insert(c, 2, "B的消息二");

            int deleted = s.getMapper(NpcChatLogMapper.class).deleteNpcChatLogsByUserId(1L);
            s.commit();

            assertEquals(2, deleted, "A 的 2 条被删");
            assertEquals(0, count(c, "user_id = 1"), "A 的记录必须删干净");
            assertEquals(2, count(c, "user_id = 2"), "负控:B 的记录必须存活;死在这 = 删除退化成全表清理");
        }
    }

    /** 入口级:真调控制器端点(登录态=A),A 只能看到/删掉自己的数据,B 毫发无损。 */
    @Test
    void controllerEntry_loginAOnlySeesAndDeletesOwnData() throws Exception {
        try (SqlSession s = open()) {
            Connection c = s.getConnection();
            insert(c, 1, "A自己的话");
            insert(c, 2, "B的机密");

            NpcChatDataService svc = new NpcChatDataService();
            ReflectionTestUtils.setField(svc, "npcChatLogMapper", s.getMapper(NpcChatLogMapper.class));
            ReflectionTestUtils.setField(svc, "npcChatRetentionDays", 90);
            // Commit fixture rows before the helper opens its independent short transaction.
            s.commit();
            SqlSessionTemplate template = new SqlSessionTemplate(factory());
            javax.sql.DataSource source = template.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
            NpcChatWriteService writes = new NpcChatWriteService(template.getMapper(NpcChatRequestMapper.class),
                    template.getMapper(NpcChatLogMapper.class), new DataSourceTransactionManager(source));
            ReflectionTestUtils.setField(writes,"asrIntents",template.getMapper(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class));
            ReflectionTestUtils.setField(svc, "npcChatWriteService", writes);
            ApiAiNpcController controller = spy(new ApiAiNpcController());
            ReflectionTestUtils.setField(controller, "npcChatDataService", svc);
            doReturn(1L).when(controller).getAppUserId(); // A 的登录态

            AjaxResult export = controller.exportChatData();
            NpcChatDataExportVO vo = (NpcChatDataExportVO) export.get("data");
            assertEquals(1, vo.getRecords().size(), "A 的登录态导出必须只含 A 自己的记录");
            assertEquals("A自己的话", vo.getRecords().get(0).getUserMsg());

            AjaxResult del = controller.deleteChatData();
            s.commit();
            assertEquals(1, ((Map<?, ?>) del.get("data")).get("deleted"), "只删掉 A 的那 1 条");
            assertEquals(0, count(c, "user_id = 1"), "A 的记录被删");
            assertEquals(1, count(c, "user_id = 2"), "负控:A 的登录态动不到 B 的数据");
        }
    }
}
