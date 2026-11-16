package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRandomAudit;
import com.chengyinhub.business.domain.NodeTaskPool;
import com.chengyinhub.business.domain.NodeTaskPoolItem;
import com.chengyinhub.business.domain.RewardDropRule;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRandomAuditMapper;
import com.chengyinhub.business.mapper.NodeTaskPoolMapper;
import com.chengyinhub.business.mapper.RewardDropLogMapper;
import com.chengyinhub.business.mapper.RewardDropRuleMapper;
import com.chengyinhub.business.service.IGameRandomService;
import com.chengyinhub.business.service.impl.GameRandom;
import com.chengyinhub.business.service.impl.RandomDispatchService;
import com.chengyinhub.business.service.impl.RewardDropRuleServiceImpl;
import com.chengyinhub.business.service.impl.RewardDropServiceImpl;
import com.chengyinhub.system.domain.SysConfig;
import com.chengyinhub.system.mapper.SysConfigMapper;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P3 随机系统真 MySQL 集成测试：
 * 1) 幂等运行 migration_20261123_sys_random.sql 两遍；
 * 2) 验证 game_random_audit 写入与幂等重放；
 * 3) 验证 node_task_pool / item CRUD 与 M3 每日每池每人不重复下发 + 跨日轮换；
 * 4) 验证 reward_drop_rule CRUD 与 RewardDropServiceImpl 写入 reward_drop_log + game_random_audit。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SysRandomMySqlIT {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    private JdbcTemplate db;
    private GameRandomAuditMapper auditMapper;
    private NodeTaskPoolMapper poolMapper;
    private RewardDropRuleMapper ruleMapper;
    private RewardDropLogMapper dropLogMapper;
    private CmsTopicNodeMapper nodeMapper;

    @BeforeAll
    void setupSchema() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                MybatisItDatabase.url("sys_random"),
                MybatisItDatabase.user(),
                MybatisItDatabase.password());
        db = new JdbcTemplate(source);

        // 准备本次迁移涉及到的存量前置表（只建当前测试所需最小结构，不动真实环境）
        db.execute("CREATE TABLE IF NOT EXISTS sys_config ("
                + "config_id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "config_name VARCHAR(100) DEFAULT '',"
                + "config_key VARCHAR(100) DEFAULT '',"
                + "config_value VARCHAR(500) DEFAULT '',"
                + "config_type CHAR(1) DEFAULT 'Y',"
                + "create_by VARCHAR(64) DEFAULT '',"
                + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + "update_by VARCHAR(64) DEFAULT '',"
                + "update_time DATETIME NULL,"
                + "remark VARCHAR(500) NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS sys_menu ("
                + "menu_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "menu_name VARCHAR(50) NOT NULL,"
                + "parent_id BIGINT DEFAULT 0,"
                + "order_num INT DEFAULT 0,"
                + "path VARCHAR(200) DEFAULT '',"
                + "component VARCHAR(255) NULL,"
                + "is_frame INT DEFAULT 1,"
                + "is_cache INT DEFAULT 0,"
                + "menu_type CHAR(1) DEFAULT '',"
                + "visible CHAR(1) DEFAULT '0',"
                + "status CHAR(1) DEFAULT '0',"
                + "perms VARCHAR(100) NULL,"
                + "icon VARCHAR(100) DEFAULT '#',"
                + "create_by VARCHAR(64) DEFAULT '',"
                + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP)");
        db.execute("INSERT INTO sys_menu (menu_name, parent_id, order_num, path, menu_type) "
                + "SELECT '内容与玩法', 0, 1, 'supply', 'M' "
                + "WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_type = 'M' AND path = 'supply')");

        db.execute("CREATE TABLE IF NOT EXISTS cms_topic_node ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "topic_id BIGINT NULL, chapter_id BIGINT NULL, merchant_member_id BIGINT NULL,"
                + "node_audit_status TINYINT NULL, node_audit_reason VARCHAR(255) NULL,"
                + "node_audit_member_id BIGINT NULL, node_audit_time DATETIME NULL,"
                + "name VARCHAR(200) NULL, description TEXT NULL, address VARCHAR(255) NULL,"
                + "longitude VARCHAR(64) NULL, latitude VARCHAR(64) NULL, img_url VARCHAR(500) NULL,"
                + "node_time INT NULL, template_id BIGINT NULL, checkin_code VARCHAR(100) NULL,"
                + "business_time VARCHAR(100) NULL, status CHAR(1) DEFAULT '0', sort_id INT DEFAULT 0,"
                + "xp_value INT DEFAULT 50, hook_text VARCHAR(255) NULL, card_hook_long VARCHAR(255) NULL,"
                + "fragment_text VARCHAR(255) NULL, unlock_after_node_id BIGINT NULL,"
                + "create_by VARCHAR(64) NULL, create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + "update_by VARCHAR(64) NULL, update_time DATETIME NULL)");

        new ResourceDatabasePopulator(new FileSystemResource("sql/migration_m6_reward_drop.sql")).execute(source);
        new ResourceDatabasePopulator(new FileSystemResource("sql/migration_reward_drop_scene_20260806.sql")).execute(source);

        // 执行本次随机系统迁移两遍，证明完全幂等
        for (int pass = 0; pass < 2; pass++) {
            new ResourceDatabasePopulator(new FileSystemResource("sql/migration_20261123_sys_random.sql")).execute(source);
        }

        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM sys_config WHERE config_key = 'reward.drop.base.prob.bp' AND config_value = '1500'",
                Integer.class));
        assertEquals(2, db.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE component IN ('business/rewarddrop/index', 'business/nodetaskpool/index')",
                Integer.class));

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain,com.chengyinhub.system.domain");
        factory.setMapperLocations(
                new ClassPathResource("mapper/business/GameRandomAuditMapper.xml"),
                new ClassPathResource("mapper/business/NodeTaskPoolMapper.xml"),
                new ClassPathResource("mapper/business/RewardDropRuleMapper.xml"),
                new ClassPathResource("mapper/business/RewardDropLogMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicNodeMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        auditMapper = session.getMapper(GameRandomAuditMapper.class);
        poolMapper = session.getMapper(NodeTaskPoolMapper.class);
        ruleMapper = session.getMapper(RewardDropRuleMapper.class);
        dropLogMapper = session.getMapper(RewardDropLogMapper.class);
        nodeMapper = session.getMapper(CmsTopicNodeMapper.class);
    }

    @BeforeEach
    void cleanRows() {
        db.update("DELETE FROM game_random_audit");
        db.update("DELETE FROM node_task_dispatch");
        db.update("DELETE FROM node_task_pool_item");
        db.update("DELETE FROM node_task_pool");
        db.update("DELETE FROM reward_drop_log");
        db.update("DELETE FROM reward_drop_rule");
        db.update("DELETE FROM cms_topic_node");
    }

    @Test
    void randomAuditAndM3DispatchAndDropPersistOnMySql() {
        // 1) GameRandom 审计持久化 + 重放幂等
        GameRandom rng = new GameRandom(20261123L, auditMapper);
        int coin1 = rng.flipCoin("coin-rec-1", 501L, "game_runtime_session", "10");
        int coin2 = rng.flipCoin("coin-rec-1", 501L, "game_runtime_session", "10");
        assertEquals(coin1, coin2);
        GameRandomAudit savedAudit = auditMapper.selectByDomainAndReceipt(IGameRandomService.DOMAIN_PLAYKIT_COIN, "coin-rec-1");
        assertNotNull(savedAudit);
        assertEquals(2, savedAudit.getTotal());

        // 2) M3 节点与盲盒任务池创建与跨日去重下发
        db.update("INSERT INTO cms_topic_node (id, topic_id, name, xp_value, hook_text, status) VALUES "
                + "(2001, 10, '门店内测入口', 20, '入口说明', '0'),"
                + "(2002, 10, '盲盒任务A-手冲鉴赏', 60, '完成手冲体验', '0'),"
                + "(2003, 10, '盲盒任务B-豆袋寻踪', 80, '找到隐藏豆袋', '0')");
        CmsTopicNode entryNode = nodeMapper.selectCmsTopicNodeById(2001L);
        assertNotNull(entryNode);

        NodeTaskPool pool = new NodeTaskPool();
        pool.setPoolName("门店每日随机池");
        pool.setEntryNodeId(2001L);
        pool.setTopicId(10L);
        pool.setStatus(1);
        pool.setCreateBy("it");

        RandomDispatchService day1Svc = new RandomDispatchService(
                poolMapper, nodeMapper, rng, Clock.fixed(Instant.parse("2026-11-23T05:00:00Z"), SH));
        NodeTaskPool savedPool = day1Svc.savePool(pool);
        assertNotNull(savedPool.getId());

        NodeTaskPoolItem itA = new NodeTaskPoolItem();
        itA.setPoolId(savedPool.getId());
        itA.setNodeId(2002L);
        itA.setWeight(100);
        day1Svc.savePoolItem(itA);

        NodeTaskPoolItem itB = new NodeTaskPoolItem();
        itB.setPoolId(savedPool.getId());
        itB.setNodeId(2003L);
        itB.setWeight(100);
        day1Svc.savePoolItem(itB);

        Map<String, Object> d1 = day1Svc.dispatchForEntryNode(501L, entryNode);
        Map<String, Object> d1Repeat = day1Svc.dispatchForEntryNode(501L, entryNode);
        assertNotNull(d1);
        assertEquals(d1.get("nodeId"), d1Repeat.get("nodeId"));
        assertEquals(true, d1Repeat.get("alreadyDispatched"));

        RandomDispatchService day2Svc = new RandomDispatchService(
                poolMapper, nodeMapper, rng, Clock.fixed(Instant.parse("2026-11-24T05:00:00Z"), SH));
        Map<String, Object> d2 = day2Svc.dispatchForEntryNode(501L, entryNode);
        assertNotNull(d2);
        assertNotEquals(d1.get("nodeId"), d2.get("nodeId"));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM node_task_dispatch", Integer.class));

        // 3) 掉落规则管理 + RewardDropServiceImpl 真库掉落 + 审计落表
        ISysConfigService mockConfig = mock(ISysConfigService.class);
        when(mockConfig.selectConfigByKey("feature.flag.drops")).thenReturn("true");
        when(mockConfig.selectConfigByKey("reward.drop.base.prob.bp")).thenReturn("10000");

        RewardDropRuleServiceImpl ruleService = new RewardDropRuleServiceImpl(ruleMapper, mockConfig);
        RewardDropRule rule = new RewardDropRule();
        rule.setScope(0);
        rule.setDropType(1);
        rule.setWeight(100);
        rule.setParamJson("{\"mult\":2}");
        rule.setStatus(1);
        RewardDropRule createdRule = ruleService.insertRule(rule);
        assertNotNull(createdRule.getId());
        assertEquals(100.0, ((Number) ruleService.previewProbabilities(10L, "topic_node").get("expectedExtraXpPercent")).doubleValue(), 0.01);

        RewardDropServiceImpl dropService = new RewardDropServiceImpl();
        ReflectionTestUtils.setField(dropService, "ruleMapper", ruleMapper);
        ReflectionTestUtils.setField(dropService, "logMapper", dropLogMapper);
        ReflectionTestUtils.setField(dropService, "configService", mockConfig);
        ReflectionTestUtils.setField(dropService, "gameRandom", rng);

        Map<String, Object> drop = dropService.rollDrop(501L, 10L, 2002L, 0L, "topic_node", 60L);
        assertNotNull(drop);
        assertEquals(60L, ((Number) drop.get("extraXp")).longValue());
        assertNotNull(drop.get("randomReceiptId"));
        assertNull(dropService.rollDrop(501L, 10L, 2002L, 0L, "topic_node", 60L));

        List<GameRandomAudit> audits = rng.listAudits(new GameRandomAudit());
        assertTrue(audits.size() >= 5, "Expected audits for coin, dispatch*2, drop hit+pick");
    }
}
