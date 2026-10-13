package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CoopCreditLog;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.mapper.CoopCreditLogMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.service.support.CoopOrderLineupSource;
import com.chengyinhub.business.service.support.FailClosedCoopOrderLineupSource;
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
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MyBatis + 本地 MySQL 集成测试。验证 mock 单测 + pymysql 都覆盖不到的:
 * mapper XML 的 resultMap 属性↔列映射、动态 &lt;trim&gt;/&lt;where&gt; SQL。
 *
 * 本地库缺失时自动 skip(不破坏 CI/常规 mvn test)。手动跑法:
 *   1) brew services start mysql
 *   2) 建库 chengyin_mybatis_it,建 coop_invite/cms_topic/oms_ticket 基表,依次 source
 *   ⚠️ 2026-08-19 起改成**一类一库**:库由 MybatisItDatabase 自己 DROP+CREATE(见该类注释),
 *      本地不再需要预先建 chengyin_mybatis_it、也不再用 ituser —— 默认走 root(本机空口令),
 *      连接信息用 CHENGYIN_MYBATIS_IT_HOST/PORT/USER/PASSWORD 覆盖。
 *      chengyinhub-admin/sql/migration_{coop_invite_terms_deadline,topic_lifecycle_recruit,coop_order_perk_credit,ticket_subtype}.sql
 *   3) CREATE USER 'ituser'@'127.0.0.1' IDENTIFIED BY 'itpass'; GRANT ALL ON chengyin_mybatis_it.* TO ...
 *   4) mvn -pl chengyinhub-admin -am test -Dtest=CoopMapperMyBatisIT
 */
class CoopMapperMyBatisIT {

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CoopInvite", CoopInvite.class);
        cfg.getTypeAliasRegistry().registerAlias("CoopOrder", CoopOrder.class);
        cfg.getTypeAliasRegistry().registerAlias("CoopCreditLog", CoopCreditLog.class);
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("coop_mapper"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        for (String r : new String[]{
                "mapper/business/CoopInviteMapper.xml",
                "mapper/business/CoopOrderMapper.xml",
                "mapper/business/CoopCreditLogMapper.xml"}) {
            try (InputStream in = Resources.getResourceAsStream(r)) {
                new XMLMapperBuilder(in, cfg, r, cfg.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    @Test
    void realMyBatis_coopInvite_dynamicSqlAndResultMap() throws Exception {
        SqlSessionFactory f;
        try {
            f = factory();
            try (SqlSession probe = f.openSession()) {
                probe.getConnection().isValid(2);
            }
        } catch (Throwable t) {
            // ★ 2026-08-14:原本是 Assumptions.assumeTrue(false, ...) —— 库连不上就整套 skip,
            // 而 skip 在构建里算**绿**。永远不会红的测试比没有测试更危险:它让人以为验过了。
            // 现在缺库 = 红。跑法见 ci/backend-test.sh(CI 的 backend job 自带 MySQL 服务)。
            throw new IllegalStateException("IT 前置不满足(库不可达 / Mapper XML / schema 配置有误),本套必须真跑不得跳过:" + t.getMessage(), t);
        }
        try (SqlSession s = f.openSession(true)) {
            // 建表:用 test/resources 里那份【生产导出的真实 DDL】,DROP 后重建。
            // 本套的价值就是拿真 schema 验 mapper 的列映射,所以不能手写简化表;
            // 也不能 IF NOT EXISTS —— 那会静默沿用库里剩下的旧表(实测踩过)。
            ItSchema.apply(s.getConnection(), "it-schema/coop-it-schema.sql");
            ItSchema.apply(s.getConnection(), "it-schema/cms-topic-it-schema.sql");
            java.sql.Statement st = s.getConnection().createStatement();
            st.execute("DELETE FROM coop_invite");
            st.execute("DELETE FROM coop_order");
            st.execute("DELETE FROM coop_credit_log");

            CoopInviteMapper im = s.getMapper(CoopInviteMapper.class);
            CoopOrderMapper om = s.getMapper(CoopOrderMapper.class);
            CoopCreditLogMapper cm = s.getMapper(CoopCreditLogMapper.class);

            // <trim> 动态 insert:含新列 game_id/share_mode/share_rate/config_deadline
            CoopInvite inv = new CoopInvite();
            inv.setInviteType(1); inv.setFromType("merchant"); inv.setFromId(10L);
            inv.setToType("club"); inv.setToId(20L); inv.setTopicId(500L);
            inv.setGameId(5L); inv.setTargetLevel(1); inv.setStatus(0);
            inv.setShareMode(1); inv.setShareRate(new BigDecimal("30.00"));
            inv.setExpireTime(new Date(0L)); inv.setDelFlag(0); inv.setCreateTime(new Date());
            im.insertCoopInvite(inv);
            assertNotNull(inv.getId(), "useGeneratedKeys 回填主键");

            // resultMap 映射:selectCoopInviteList 动态 <where> + gameId 过滤,校验新列读回
            CoopInvite q = new CoopInvite(); q.setGameId(5L); q.setStatus(0);
            List<CoopInvite> list = im.selectCoopInviteList(q);
            assertEquals(1, list.size());
            CoopInvite got = list.get(0);
            assertEquals(Long.valueOf(5L), got.getGameId());
            assertEquals(Integer.valueOf(1), got.getTargetLevel());
            assertEquals(Integer.valueOf(1), got.getShareMode());
            assertEquals(0, new BigDecimal("30.00").compareTo(got.getShareRate()), "share_rate 列→属性映射");

            // selectCoopInviteByIdForUpdate:resultMap + FOR UPDATE
            assertNotNull(im.selectCoopInviteByIdForUpdate(inv.getId()));

            // 双死线 expire UPDATE(真实 mapper 语句)
            int n = im.expirePendingInvites(new Date());
            assertEquals(1, n, "挂起(expire_time=1970)超期被置5");
            assertEquals(Integer.valueOf(5), im.selectCoopInviteById(inv.getId()).getStatus());

            CoopInvite accepted = new CoopInvite();
            accepted.setId(inv.getId());
            accepted.setStatus(1);
            assertEquals(1, im.updateCoopInvite(accepted));

            // coop_order:<trim> insert + resultMap select
            CoopOrder ord = new CoopOrder();
            ord.setInviteId(inv.getId()); ord.setTopicId(500L); ord.setGameId(5L);
            ord.setFromId(10L); ord.setToId(20L); ord.setToType("club");
            ord.setShareMode(1); ord.setShareRate(new BigDecimal("30.00"));
            ord.setRoleSnapshot("ORGANIZER");
            ord.setScopeTypeSnapshot("TOPIC");
            ord.setScopeIdSnapshot(500L);
            ord.setEligiblePurchaseKindsSnapshot("GUIDED_TICKET");
            ord.setFulfillmentEvidenceRuleSnapshot("COOP_GROUP_VERIFY");
            ord.setTermsSnapshot("{\"compensationMode\":\"SALES_PERCENT\",\"benefitMode\":\"NONE\"}");
            ord.setTermsVersion("v1"); ord.setStatus(0); ord.setDelFlag(0); ord.setCreateTime(new Date());
            om.insertCoopOrder(ord);
            List<CoopOrder> orders = om.selectEffectiveOrdersByTopicId(500L);
            assertEquals(1, orders.size());
            assertEquals(0, new BigDecimal("30.00").compareTo(orders.get(0).getShareRate()), "coop_order resultMap 映射");
            assertEquals("ORGANIZER", orders.get(0).getRoleSnapshot());
            assertEquals("TOPIC", orders.get(0).getScopeTypeSnapshot());
            assertEquals(Long.valueOf(500L), orders.get(0).getScopeIdSnapshot());
            assertEquals("GUIDED_TICKET", orders.get(0).getEligiblePurchaseKindsSnapshot());
            assertEquals("COOP_GROUP_VERIFY", orders.get(0).getFulfillmentEvidenceRuleSnapshot());
            assertTrue(orders.get(0).getTermsSnapshot().contains("SALES_PERCENT"));

            // 阻断#8 / 台账 1-11:存量已接受邀约带着过期的 72h 死线,真实 adapter 也不得据此判阵容无效
            st.execute("UPDATE coop_invite SET config_deadline = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE id = " + inv.getId());
            FailClosedCoopOrderLineupSource lineupSource = new FailClosedCoopOrderLineupSource(om, im);
            CoopOrderLineupSource.ReadResult acceptedLineup = lineupSource.readEffectiveLineup(500L);
            assertTrue(acceptedLineup.getRepairReasons().isEmpty());
            assertEquals(1, acceptedLineup.getEntries().size());
            assertEquals(Long.valueOf(10L), acceptedLineup.getEntries().get(0).getInitiatorMemberId());

            assertEquals(1, om.voidOrderByInviteId(inv.getId()));
            assertEquals(0, om.selectEffectiveOrdersByTopicId(500L).size(),
                    "作废合作单不得被有效阵容 mapper 读回");
            assertEquals(0, lineupSource.readEffectiveLineup(500L).getEntries().size(),
                    "作废合作单后真实 adapter 必须从有效阵容移除");

            // coop_credit_log:insert + selectRecentByMember(@Param)
            CoopCreditLog log = new CoopCreditLog();
            log.setMemberId(99L); log.setEventType(2); log.setInviteId(inv.getId());
            log.setReason("行程冲突"); log.setCreateTime(new Date());
            cm.insertCoopCreditLog(log);
            List<CoopCreditLog> logs = cm.selectRecentByMember(99L, new Date(0L));
            assertEquals(1, logs.size());
            assertEquals("行程冲突", logs.get(0).getReason());
            assertEquals(Integer.valueOf(2), logs.get(0).getEventType());
        }
    }
}
