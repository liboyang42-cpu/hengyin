package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.CmsTopic;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-35 角色槽 mapper 的 SQL 守卫:解析真 XML 生成 BoundSql,**不连库**。
 *
 * <p>为什么必须有:{@code ProductRoleServiceTest} 里 mapper 是 mock —— 它「按什么条件过滤」
 * 完全是我假设的,SQL 少一个条件那边照样全绿。而这张表少条件的代价不对称:
 * <ul>
 *   <li>查询漏 {@code status = 1} ⇒ 读到已失效的旧承接方 ⇒ **票款付给了前任内容方**(D8:
 *       票款归内容槽持有者)。旧行是**故意不删**的(留痕),所以「表里有它」不代表「它还生效」。</li>
 *   <li>insert 漏列 ⇒ 槽落不全,而 P0-50 的 REVSHARE 范围闸读的就是 entity_type。</li>
 * </ul>
 *
 * ⚠️ 负控已实测:
 *  - selectActiveByTopicAndSlot 删掉 status = 1 ⇒ selectActive_requiresStatusAxis 红
 *  - deactivate 删掉 status = 1 前置 ⇒ deactivate_onlyTouchesActiveRows 红
 *  - insert 漏 entity_type ⇒ insert_carriesAllBusinessColumns 红
 */
class ProductRoleMapperSqlTest {

    private static final String XML = "mapper/business/ProductRoleMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.ProductRoleMapper.";

    private String sql(String statementId, Object param) throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("ProductRole", ProductRole.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, cfg, XML, cfg.getSqlFragments()).parse();
        }
        return cfg.getMappedStatement(NS + statementId)
                .getBoundSql(param)
                .getSql()
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    private Map<String, Object> params() {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("topicId", 1L);
        m.put("slot", "CONTENT");
        return m;
    }

    /**
     * <b>查询必须带 status 轴</b> —— 失效行是**故意留着**的(旧承接标失效不删行),
     * 漏了它就会读到前任内容方,而票款归内容槽持有者(D8)⇒ 钱付错人。
     */
    @Test
    void selectActive_requiresStatusAxis() throws Exception {
        String s = sql("selectActiveByTopicAndSlot", params());
        assertTrue(s.contains("status = 1"),
                "缺 status 轴 —— 会读到已失效的旧承接方,票款付给前任。实际: " + s);
        assertTrue(s.contains("topic_id = ?") && s.contains("slot = ?"), "实际: " + s);
    }

    /** 标失效只动生效行 —— 少了前置条件会把留痕的历史行反复改写,审计线索没了。 */
    @Test
    void deactivate_onlyTouchesActiveRows() throws Exception {
        String s = sql("deactivateByTopicAndSlot", params());
        assertTrue(s.contains("set status = 0"), "实际: " + s);
        assertTrue(s.contains("status = 1"),
                "deactivate 必须只动生效行,否则会重写历史留痕。实际: " + s);
    }

    /** insert 必须带齐业务列 —— 漏 entity_type 则 P0-50 的范围闸无从判断。 */
    @Test
    void insert_carriesAllBusinessColumns() throws Exception {
        ProductRole r = new ProductRole();
        r.setTopicId(1L);
        r.setSlot("CONTENT");
        r.setEntityType("PLATFORM");
        String s = sql("insertProductRole", r);
        for (String col : new String[]{"topic_id", "slot", "entity_type", "entity_id", "revenue_share", "status", "declared_by"}) {
            assertTrue(s.contains(col), "insert 漏列 " + col + "。实际: " + s);
        }
    }

    /** 即使绕过 service 直调 mapper，ORGANIZER 写/失效也必须受售前生命周期 SQL 守卫。 */
    @Test
    void organizerMutationsCarryLifecycleGuardInsideMapper() throws Exception {
        ProductRole role = new ProductRole();
        role.setTopicId(1L);
        role.setSlot("ORGANIZER");
        role.setEntityType("PLATFORM");
        role.setDeclaredBy(9L);
        String insert = sql("insertProductRole", role);
        assertTrue(insert.contains("lifecycle in (0, 1, 2, 7)"), "ORGANIZER insert 缺售前守卫:" + insert);

        Map<String, Object> params = params();
        params.put("slot", "ORGANIZER");
        String deactivate = sql("deactivateByTopicAndSlot", params);
        assertTrue(deactivate.contains("lifecycle in (0, 1, 2, 7)"),
                "ORGANIZER deactivate 缺售前守卫:" + deactivate);
        String lock = sql("selectTopicForOrganizerUpdate", params);
        assertTrue(lock.endsWith("for update"), "ORGANIZER 服务必须先锁主题行:" + lock);
    }

    /**
     * <b>不许 select 生成列</b> {@code singleton_key} —— 它是库表达「单例槽唯一性」的内部手段
     * (MySQL 8 没有 UNIQUE...WHERE),不是业务字段。
     * select 它 = 把一个实现细节泄进领域模型,而且 domain 里根本没有对应字段,
     * resultMap 也接不住。
     */
    @Test
    void doesNotSelectGeneratedColumn() throws Exception {
        String s = sql("selectActiveByTopicAndSlot", params());
        assertFalse(s.contains("singleton_key"),
                "singleton_key 是库的内部约束手段,不该进 select。实际: " + s);
    }

    private Map<String, Object> topicIdsParam() {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("topicIds", java.util.Arrays.asList(1L, 2L));
        return m;
    }

    /**
     * <b>「来源」栏投影的关联必须同时带 entity_type 和 entity_id</b>。
     *
     * <p>product_role.entity_id 是**共享 ID 空间**：CLUB 存 clubId、MEMBER 存 memberId、
     * PLATFORM 为 NULL。只按 id 关联 ⇒ 俱乐部 7 号会被读成会员 7 号，
     * 卡片上的「来源」显示成另一个主体的名字。这是这张表上反复踩过的坑，不是理论风险。
     */
    @Test
    void contentPublisher_pairsEntityTypeWithEntityId() throws Exception {
        String s = sql("selectActiveContentPublisherByTopicIds", topicIdsParam());

        assertTrue(s.contains("content_role.entity_type = 'club'")
                        && s.contains("content_club.id = content_role.entity_id"),
                "俱乐部关联必须 entity_type + entity_id 配对,只按 id 关联会混读。实际: " + s);
        assertTrue(s.contains("content_role.entity_type = 'member'")
                        && s.contains("content_member.id = content_role.entity_id"),
                "会员关联必须 entity_type + entity_id 配对,只按 id 关联会混读。实际: " + s);
    }

    /**
     * <b>只读生效的 CONTENT 槽</b>。漏 slot ⇒ SUPPLY/DISTRIBUTION 多行涌入 ⇒ 列表行乘法；
     * 漏 status ⇒ 读到改判前的前任内容方 ⇒ 卡片显示错误来源。
     * 这两个条件合起来才落在 uk_product_role_singleton 的保护范围内（至多一行）。
     */
    @Test
    void contentPublisher_requiresSlotAndStatusAxes() throws Exception {
        String s = sql("selectActiveContentPublisherByTopicIds", topicIdsParam());

        assertTrue(s.contains("content_role.slot = 'content'"),
                "漏 slot ⇒ SUPPLY/DISTRIBUTION 多行涌入,列表行乘法。实际: " + s);
        assertTrue(s.contains("content_role.status = 1"),
                "漏 status ⇒ 读到改判前的前任内容方。实际: " + s);
    }

    /**
     * <b>已删主体不得带出名字</b>。少了 del_flag ⇒ 已解散俱乐部/已注销会员的名字
     * 仍会出现在公开卡片上。
     */
    @Test
    void contentPublisher_excludesDeletedEntities() throws Exception {
        String s = sql("selectActiveContentPublisherByTopicIds", topicIdsParam());

        assertTrue(s.contains("content_club.del_flag = 0"),
                "已解散俱乐部的名字不该出现在公开卡片上。实际: " + s);
        assertTrue(s.contains("content_member.del_flag = 0"),
                "已注销会员的昵称不该出现在公开卡片上。实际: " + s);
    }

    /**
     * <b>对外投影不许带 entity_id</b>。主体识别只暴露「类型 + 公开名」；
     * 下发裸 ID 会把主体识别退化成可枚举的内部标识。
     */
    @Test
    void contentPublisher_doesNotExposeEntityId() throws Exception {
        String s = sql("selectActiveContentPublisherByTopicIds", topicIdsParam());
        String selectClause = s.substring(0, s.indexOf(" from "));

        assertFalse(selectClause.contains("entityid") || selectClause.contains("entity_id as"),
                "对外投影不得下发裸 entity_id。实际 select 段: " + selectClause);
    }
}
