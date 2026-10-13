package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 MyBatis + 本地 MySQL:主题节点 NPC(npc_profile scope_type=4)的取存与库侧约束。
 *
 * <p>为什么必须连真库:三件事 mock 证明不了 ——
 * <ol>
 *   <li>玩家读闸 `audit_status=1 AND enabled=1 AND del_flag='0'` 真的过滤掉停用/未审行;</li>
 *   <li>「一节点一 NPC」是 `uk_active_scope(scope_type,scope_id,active_slot)` 给的,
 *       写入 active_slot=1 才会撞 1062 —— service 里的先查后插挡不住并发双击;</li>
 *   <li>scope 隔离:update/voice/hide 三条语句都写死 scope_type=4,
 *       拿门店分身(2)的 id 去写必须 0 行,不能悄悄改到别人的行。</li>
 * </ol>
 *
 * <p>本套连不上 = 红,不 skip(理由与 {@link NpcGovernanceMapperMyBatisIT} 同)。
 * 跑法:CHENGYIN_MYBATIS_IT_PORT=3317 覆盖到本机 colima MySQL,见 ci/backend-test.sh。
 */
class NodeNpcMapperMyBatisIT {

    private static final String NAME = "阿福";

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("NpcProfile", NpcProfile.class);
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("node_npc"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        try (InputStream in = Resources.getResourceAsStream("mapper/business/NpcProfileMapper.xml")) {
            new XMLMapperBuilder(in, cfg, "mapper/business/NpcProfileMapper.xml", cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private SqlSession open() {
        try {
            SqlSession s = factory().openSession();
            s.getConnection().isValid(2);
            ItSchema.applyMigrations(s.getConnection(),
                    "sql/migration_npc_profile.sql",
                    "sql/migration_npc_line_cache.sql",
                    "sql/migration_merchant_npc_voice_20260909.sql",
                    "sql/migration_npc_governance_v1.sql",
                    "sql/migration_node_npc_20260915.sql");
            return s;
        } catch (Throwable t) {
            throw new IllegalStateException("IT 前置不满足(库不可达 / Mapper XML / schema 配置有误),本套必须真跑不得跳过:"
                    + t.getMessage(), t);
        }
    }

    private static NpcProfile nodeNpc(Long nodeId, String name, String avatar, String greeting,
                                      Integer auditStatus, Integer enabled) {
        NpcProfile p = new NpcProfile();
        p.setScopeType(4);
        p.setScopeId(nodeId);
        p.setName(name);
        p.setAvatar(avatar);
        p.setPersona("");
        p.setGreeting(greeting);
        p.setSort(0);
        p.setAuditStatus(auditStatus);
        p.setEnabled(enabled);
        return p;
    }

    /** 商家保存后回填 + 玩家读闸:audit=1/enabled=1 才出现在 selectEnabledByScope(玩家侧)。 */
    @Test
    void playerReadSeesOnlyApprovedAndEnabled() throws Exception {
        try (SqlSession s = open()) {
            NpcProfileMapper m = s.getMapper(NpcProfileMapper.class);
            NpcProfile saved = nodeNpc(901L, NAME, "px1:p01", "欢迎来店里", 1, 1);
            assertEquals(1, m.insertNodeNpc(saved));

            NpcProfile back = m.selectNodeNpcByNodeId(901L);
            assertNotNull(back, "商家回填必须查得到自己刚保存的那条");
            assertEquals(NAME, back.getName());
            assertEquals(Integer.valueOf(4), back.getScopeType(), "scope_type 必须写 4");
            assertEquals("欢迎来店里", back.getGreeting());

            List<NpcProfile> visible = m.selectEnabledByScope(4, 901L);
            assertEquals(1, visible.size(), "已审+启用 = 玩家读得到");
            assertEquals(NAME, visible.get(0).getName());

            // 违规下架(enabled=0):玩家侧立即不可见,但商家回填仍找得到(否则再保存会撞唯一键)
            NpcProfile off = new NpcProfile();
            off.setId(back.getId());
            off.setEnabled(0);
            assertEquals(1, m.updateNodeNpc(off));
            assertTrue(m.selectEnabledByScope(4, 901L).isEmpty(), "停用后不得下发");
            assertNotNull(m.selectNodeNpcByNodeId(901L), "停用后商家仍要能回填/改回来");

            // 未过审(audit_status=0)同样不下发
            assertEquals(1, m.insertNodeNpc(nodeNpc(902L, "未审", "px1:p04", null, 0, 1)));
            assertTrue(m.selectEnabledByScope(4, 902L).isEmpty(), "未过审不得下发");
        }
    }

    /**
     * ★N2:下架后重新保存必须回到待审(audit_status=0 + enabled=0),过审前玩家读不到;
     * 只有显式放行(audit_status=1 + enabled=1)才重新可见。
     * 守两件事:updateNodeNpc 真能写回 audit_status;玩家读闸两道都不过。
     */
    @Test
    void resaveAfterTakedownGoesBackToPendingAndStaysOutOfPlayerRead() throws Exception {
        try (SqlSession s = open()) {
            NpcProfileMapper m = s.getMapper(NpcProfileMapper.class);
            NpcProfile saved = nodeNpc(905L, NAME, "px1:p01", "欢迎来店里", 1, 1);
            assertEquals(1, m.insertNodeNpc(saved));
            assertEquals(1, m.selectEnabledByScope(4, 905L).size(), "在架时玩家可见");

            // 内容安全回调:违规下架(清头像 + enabled=0)
            assertEquals(1, m.hideNodeNpcById(saved.getId()));
            assertTrue(m.selectEnabledByScope(4, 905L).isEmpty(), "下架后不可见");

            // 商家重新保存:内容更新 + 回到待审(不是重新上架)
            NpcProfile resave = new NpcProfile();
            resave.setId(saved.getId());
            resave.setName(NAME);
            resave.setAvatar("px1:p04");
            resave.setGreeting("换了合规头像");
            resave.setAuditStatus(0);
            resave.setEnabled(0);
            assertEquals(1, m.updateNodeNpc(resave));

            NpcProfile pending = m.selectNodeNpcById(saved.getId());
            assertEquals(Integer.valueOf(0), pending.getAuditStatus(), "重新保存必须写回待审");
            assertEquals(Integer.valueOf(0), pending.getEnabled());
            assertEquals("px1:p04", pending.getAvatar(), "内容要落库 —— 待审不是丢弃");
            assertTrue(m.selectEnabledByScope(4, 905L).isEmpty(), "待审期间玩家读不到");

            // 过审放行后才重新可见
            NpcProfile approve = new NpcProfile();
            approve.setId(saved.getId());
            approve.setAuditStatus(1);
            approve.setEnabled(1);
            assertEquals(1, m.updateNodeNpc(approve));
            assertEquals(1, m.selectEnabledByScope(4, 905L).size(), "过审后恢复展示");
        }
    }

    /** 「一节点一 NPC」靠库侧唯一键:第二条 INSERT 必须 1062,不能悄悄多出一条。 */
    @Test
    void oneNpcPerNodeIsEnforcedByUniqueKey() throws Exception {
        try (SqlSession s = open()) {
            NpcProfileMapper m = s.getMapper(NpcProfileMapper.class);
            assertEquals(1, m.insertNodeNpc(nodeNpc(903L, NAME, "px1:p01", null, 1, 1)));
            assertThrows(PersistenceException.class,
                    () -> m.insertNodeNpc(nodeNpc(903L, "冒名顶替", "px1:p04", null, 1, 1)),
                    "同节点第二条必须被 uk_active_scope 拒绝(写入不带 active_slot=1 就会静默放行)");
            try (ResultSet rs = s.getConnection().createStatement()
                    .executeQuery("SELECT COUNT(*) FROM npc_profile WHERE scope_type=4 AND scope_id=903")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "被拒后库里仍只有一条");
            }
        }
    }

    /** scope 隔离:拿门店分身(scope 2)的 id 走节点 NPC 的三条写语句,必须 0 行。 */
    @Test
    void nodeNpcWritesAreScopeIsolated() throws Exception {
        try (SqlSession s = open()) {
            java.sql.Connection c = s.getConnection();
            c.createStatement().execute(
                    "INSERT INTO npc_profile (id,scope_type,scope_id,name,avatar,persona,greeting,sort,"
                  + "audit_status,enabled,del_flag,voice_status) "
                  + "VALUES (11,2,777,'店小二','px1:p01','','您好',0,1,1,'0',0)");

            NpcProfileMapper m = s.getMapper(NpcProfileMapper.class);
            NpcProfile edit = new NpcProfile();
            edit.setId(11L);
            edit.setName("改名");
            assertEquals(0, m.updateNodeNpc(edit), "节点 NPC update 不得碰到门店分身");

            NpcProfile voice = new NpcProfile();
            voice.setId(11L);
            voice.setVoiceId("cosyvoice-v2-x");
            voice.setVoiceStatus(2);
            assertEquals(0, m.updateNodeNpcVoice(voice), "节点 NPC voice 不得碰到门店分身");

            assertEquals(0, m.hideNodeNpcById(11L), "节点 NPC 下架不得碰到门店分身");
            assertNull(m.selectNodeNpcById(11L), "按 id 查也必须受 scope_type=4 限制");

            try (ResultSet rs = c.createStatement()
                    .executeQuery("SELECT name,voice_id FROM npc_profile WHERE id=11")) {
                assertTrue(rs.next());
                assertEquals("店小二", rs.getString("name"));
                assertNull(rs.getString("voice_id"));
            }
        }
    }

    /** 违规回调:清头像 + 停用一次完成;voice 字段单独写、不碰形象。 */
    @Test
    void hideAndVoiceUpdatesTouchOnlyTheirOwnColumns() throws Exception {
        try (SqlSession s = open()) {
            NpcProfileMapper m = s.getMapper(NpcProfileMapper.class);
            NpcProfile saved = nodeNpc(904L, NAME, "https://oss.example.com/a.png", "打招呼", 1, 1);
            assertEquals(1, m.insertNodeNpc(saved));

            NpcProfile voice = new NpcProfile();
            voice.setId(saved.getId());
            voice.setVoiceId("cosyvoice-v2-chengyin-abc");
            voice.setVoiceStatus(2);
            voice.setVoiceSample("/profile/voice/a.mp3");
            assertEquals(1, m.updateNodeNpcVoice(voice));

            NpcProfile afterVoice = m.selectNodeNpcById(saved.getId());
            assertEquals("cosyvoice-v2-chengyin-abc", afterVoice.getVoiceId());
            assertEquals(Integer.valueOf(2), afterVoice.getVoiceStatus());
            assertEquals(NAME, afterVoice.getName(), "voice 更新不得碰名字");
            assertEquals("https://oss.example.com/a.png", afterVoice.getAvatar(), "voice 更新不得碰头像");

            assertEquals(1, m.hideNodeNpcById(saved.getId()));
            NpcProfile hidden = m.selectNodeNpcById(saved.getId());
            assertEquals(Integer.valueOf(0), hidden.getEnabled(), "违规必须停用");
            assertNull(hidden.getAvatar(), "违规必须清头像");
            assertEquals(NAME, hidden.getName(), "只下架不清名字(商家改回新头像即可恢复)");
        }
    }

    /**
     * R14-VOICE:READY 音色随 insert 一条落库(复制链路把已就绪的声音带进副本);
     * 不传三列时(复刻中/失败不复制、商家保存)必须落默认 NONE(0)而不是报 NULL ——
     * voice_status 是 NOT NULL,动态列写错就会把保存路径一起炸掉。
     */
    @Test
    void insertCarriesReadyVoiceAndLeavesVoiceNoneWhenAbsent() throws Exception {
        try (SqlSession s = open()) {
            NpcProfileMapper m = s.getMapper(NpcProfileMapper.class);

            NpcProfile ready = nodeNpc(906L, NAME, "px1:p01", null, 1, 1);
            ready.setVoiceId("cosyvoice-v2-copy-ready");
            ready.setVoiceStatus(2);
            ready.setVoiceSample("/media/sample-906.wav");
            assertEquals(1, m.insertNodeNpc(ready));

            NpcProfile back = m.selectNodeNpcByNodeId(906L);
            assertEquals("cosyvoice-v2-copy-ready", back.getVoiceId(), "READY 音色必须随副本落库");
            assertEquals(Integer.valueOf(2), back.getVoiceStatus(), "复制后仍必须判成可用");
            assertEquals("/media/sample-906.wav", back.getVoiceSample());

            assertEquals(1, m.insertNodeNpc(nodeNpc(907L, "未配声", "px1:p01", null, 1, 1)));
            NpcProfile none = m.selectNodeNpcByNodeId(907L);
            assertNull(none.getVoiceId());
            assertEquals(Integer.valueOf(0), none.getVoiceStatus(), "没复制就是 NONE(0),不是可合成状态");
            assertNull(none.getVoiceSample());
        }
    }

    /** migration 的回读断言:scope_type 列注释必须写进「4=节点NPC」,否则 schema 自述会漂。 */
    @Test
    void scopeTypeCommentDocumentsNodeScope() throws Exception {
        try (SqlSession s = open();
             ResultSet rs = s.getConnection().createStatement().executeQuery(
                     "SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()"
                   + " AND TABLE_NAME='npc_profile' AND COLUMN_NAME='scope_type'")) {
            assertTrue(rs.next(), "scope_type 列必须存在");
            String comment = rs.getString(1);
            assertNotNull(comment);
            assertTrue(comment.contains("4=") && comment.contains("节点"),
                    "列注释必须补上节点 NPC 作用域,当前是:" + comment);
        }
    }
}
