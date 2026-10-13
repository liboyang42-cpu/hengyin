package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.NpcLineCache;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcLineCacheMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 MyBatis + 本地 MySQL:证明 mapper 读路径真的读 active_slot / version。
 *
 * <p>为什么必须连真库:mock 单测证明不了 SQL 语义。migration_npc_governance_v1.sql 加的
 * uk_active_scope 唯一约束<b>只管写入</b>;若 selectGlobalNpc 不读 active_slot,
 * 约束就是纯装饰 —— LIMIT 1 仍会在多条 enabled+audit_status=1 里挑一条不确定的。
 * 同理 uk_line_version <b>不阻止</b>两个 version 同时 audit_status=1,
 * 所以 ORDER BY version DESC 是真防线,少了它取哪条是掷骰子。
 *
 * <p>本地库缺失时自动 skip(不破坏 CI/常规 mvn test)。手动跑法:
 *   1) brew services start mysql
 *   2) 建库/用户:
 *      CREATE DATABASE chengyin_mybatis_it;
 *      CREATE USER 'ituser'@'127.0.0.1' IDENTIFIED BY 'itpass';
 *      GRANT ALL ON chengyin_mybatis_it.* TO 'ituser'@'127.0.0.1';
 *   ⚠️ 2026-08-19 起改成**一类一库**:库由 MybatisItDatabase 自己 DROP+CREATE(见该类注释),
 *      本地不再需要预先建 chengyin_mybatis_it、也不再用 ituser —— 默认走 root(本机空口令),
 *      连接信息用 CHENGYIN_MYBATIS_IT_HOST/PORT/USER/PASSWORD 覆盖。
 *   3) 依次 source(顺序不能颠倒,governance 依赖两张基表):
 *      chengyinhub-admin/sql/migration_npc_profile.sql
 *      chengyinhub-admin/sql/migration_npc_line_cache.sql
 *      chengyinhub-admin/sql/migration_npc_governance_v1.sql
 *   4) mvn -pl chengyinhub-admin -am test -Dtest=NpcGovernanceMapperMyBatisIT
 *
 * <p>⚠️ 注意:surefire 默认只收 *Test.java,<b>本 *IT 不会在 CI 自动跑</b>,须 -Dtest 点名。
 */
class NpcGovernanceMapperMyBatisIT {

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("NpcProfile", NpcProfile.class);
        cfg.getTypeAliasRegistry().registerAlias("NpcLineCache", NpcLineCache.class);
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("npc_governance"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        for (String r : new String[]{
                "mapper/business/NpcProfileMapper.xml",
                "mapper/business/NpcLineCacheMapper.xml"}) {
            try (InputStream in = Resources.getResourceAsStream(r)) {
                new XMLMapperBuilder(in, cfg, r, cfg.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private SqlSession open() {
        try {
            SqlSession s = factory().openSession();
            s.getConnection().isValid(2);
            // 建表:直接跑仓库里的 migration 脚本(它才是真源)。顺序不能颠倒 —— governance 依赖两张基表。
            // ★ 2026-08-14:本套在我本机是绿的、在 CI 的**空库**上红("npc_line_cache doesn't exist")——
            //   本机那个遗留 IT 库里恰好躺着这几张表,那个绿是环境给的,不是测试自己挣的。
            ItSchema.applyMigrations(s.getConnection(),
                    "sql/migration_npc_profile.sql",
                    "sql/migration_npc_line_cache.sql",
                    "sql/migration_npc_governance_v1.sql");
            return s;
        } catch (Throwable t) {
            // ★ 2026-08-14:原本是 Assumptions.assumeTrue(false, ...) —— 库连不上就整套 skip,
            // 而 skip 在构建里算**绿**。永远不会红的测试比没有测试更危险:它让人以为验过了。
            // 现在缺库 = 红。跑法见 ci/backend-test.sh(CI 的 backend job 自带 MySQL 服务)。
            throw new IllegalStateException("IT 前置不满足(库不可达 / Mapper XML / schema 配置有误),本套必须真跑不得跳过:" + t.getMessage(), t);
        }
    }

    /**
     * ★ selectGlobalNpc 必须读 active_slot:
     * 造两条 scope_type=3 且都 enabled=1/audit_status=1,只有一条 active_slot=1。
     * 不读 active_slot 的话 LIMIT 1 会挑到 id 小的那条(停用的旧 NPC)。
     */
    @Test
    void selectGlobalNpc_picksActiveSlot_notJustEnabled() throws Exception {
        try (SqlSession s = open()) {
            java.sql.Connection c = s.getConnection();
            c.createStatement().execute("DELETE FROM npc_line_cache");
            c.createStatement().execute("DELETE FROM npc_profile");
            // 旧的停用 NPC:enabled=1 audit_status=1 但 active_slot=NULL,id 更小
            c.createStatement().execute(
                    "INSERT INTO npc_profile (id,scope_type,scope_id,name,persona,greeting,sort,audit_status,enabled,del_flag,active_slot) "
                  + "VALUES (1,3,0,'老周','x','hi',0,1,1,'0',NULL)");
            // 真正生效的小瘾
            c.createStatement().execute(
                    "INSERT INTO npc_profile (id,scope_type,scope_id,name,persona,greeting,sort,audit_status,enabled,del_flag,active_slot) "
                  + "VALUES (2,3,0,'小瘾','x','hi',0,1,1,'0',1)");

            NpcProfile got = s.getMapper(NpcProfileMapper.class).selectGlobalNpc();
            assertNotNull(got, "应取到生效的全局 NPC");
            assertEquals("小瘾", got.getName(),
                    "必须按 active_slot=1 取;取到「老周」= mapper 没读 active_slot,唯一约束沦为装饰");
        }
    }

    /** 全都没生效(active_slot 全 NULL)时应取不到,而不是随便挑一条。 */
    @Test
    void selectGlobalNpc_noActive_returnsNull() throws Exception {
        try (SqlSession s = open()) {
            java.sql.Connection c = s.getConnection();
            c.createStatement().execute("DELETE FROM npc_line_cache");
            c.createStatement().execute("DELETE FROM npc_profile");
            c.createStatement().execute(
                    "INSERT INTO npc_profile (id,scope_type,scope_id,name,persona,greeting,sort,audit_status,enabled,del_flag,active_slot) "
                  + "VALUES (1,3,0,'老周','x','hi',0,1,1,'0',NULL)");
            assertNull(s.getMapper(NpcProfileMapper.class).selectGlobalNpc(),
                    "没有 active_slot=1 的行 = 无生效 NPC,不该回退到停用的那条");
        }
    }

    /**
     * ★ selectByProfileAndEvent 必须按 version DESC 取:
     * uk_line_version 不阻止两版同时 audit_status=1,所以这是真防线。
     */
    @Test
    void selectByProfileAndEvent_prefersNewestVersion() throws Exception {
        try (SqlSession s = open()) {
            java.sql.Connection c = s.getConnection();
            c.createStatement().execute("DELETE FROM npc_line_cache");
            c.createStatement().execute("DELETE FROM npc_profile");
            c.createStatement().execute(
                    "INSERT INTO npc_profile (id,scope_type,scope_id,name,persona,greeting,sort,audit_status,enabled,del_flag,active_slot) "
                  + "VALUES (2,3,0,'小瘾','x','hi',0,1,1,'0',1)");
            // 两个版本同时已审(唯一键允许:version 是键的一部分)
            c.createStatement().execute(
                    "INSERT INTO npc_line_cache (profile_id,event_type,node_id,variants_json,audit_status,version) "
                  + "VALUES (2,'roam_start',NULL,'[\"v1旧文案\"]',1,1)");
            c.createStatement().execute(
                    "INSERT INTO npc_line_cache (profile_id,event_type,node_id,variants_json,audit_status,version) "
                  + "VALUES (2,'roam_start',NULL,'[\"v2新文案\"]',1,2)");

            java.util.List<NpcLineCache> got =
                    s.getMapper(NpcLineCacheMapper.class).selectByProfileAndEvent(2L, "roam_start", null);
            assertFalse(got.isEmpty(), "应查到话术");
            assertTrue(got.get(0).getVariantsJson().contains("v2新文案"),
                    "必须取最新 version;取到 v1 = 少了 ORDER BY version DESC,新旧同审时掷骰子");
        }
    }

    /** node 绑定优先于通用话术(既有行为,防 version DESC 把 node_id DESC 挤掉)。 */
    @Test
    void selectByProfileAndEvent_nodeBoundStillWinsOverGeneric() throws Exception {
        try (SqlSession s = open()) {
            java.sql.Connection c = s.getConnection();
            c.createStatement().execute("DELETE FROM npc_line_cache");
            c.createStatement().execute("DELETE FROM npc_profile");
            c.createStatement().execute(
                    "INSERT INTO npc_profile (id,scope_type,scope_id,name,persona,greeting,sort,audit_status,enabled,del_flag,active_slot) "
                  + "VALUES (2,3,0,'小瘾','x','hi',0,1,1,'0',1)");
            c.createStatement().execute(
                    "INSERT INTO npc_line_cache (profile_id,event_type,node_id,variants_json,audit_status,version) "
                  + "VALUES (2,'roam_start',NULL,'[\"通用\"]',1,1)");
            c.createStatement().execute(
                    "INSERT INTO npc_line_cache (profile_id,event_type,node_id,variants_json,audit_status,version) "
                  + "VALUES (2,'roam_start',77,'[\"节点专属\"]',1,1)");

            java.util.List<NpcLineCache> got =
                    s.getMapper(NpcLineCacheMapper.class).selectByProfileAndEvent(2L, "roam_start", 77L);
            assertTrue(got.get(0).getVariantsJson().contains("节点专属"),
                    "node_id DESC 必须仍排在 version DESC 之前(既有行为不许被挤掉)");
        }
    }

    /** 未审话术(audit_status=0)绝不下发 —— 既有安全语义,顺带锁住。 */
    @Test
    void selectByProfileAndEvent_neverServesUnaudited() throws Exception {
        try (SqlSession s = open()) {
            java.sql.Connection c = s.getConnection();
            c.createStatement().execute("DELETE FROM npc_line_cache");
            c.createStatement().execute("DELETE FROM npc_profile");
            c.createStatement().execute(
                    "INSERT INTO npc_profile (id,scope_type,scope_id,name,persona,greeting,sort,audit_status,enabled,del_flag,active_slot) "
                  + "VALUES (2,3,0,'小瘾','x','hi',0,1,1,'0',1)");
            c.createStatement().execute(
                    "INSERT INTO npc_line_cache (profile_id,event_type,node_id,variants_json,audit_status,version) "
                  + "VALUES (2,'roam_start',NULL,'[\"未过审\"]',0,2)");
            assertTrue(s.getMapper(NpcLineCacheMapper.class)
                            .selectByProfileAndEvent(2L, "roam_start", null).isEmpty(),
                    "audit_status=0 的话术不得下发,哪怕它 version 更高");
        }
    }
}
