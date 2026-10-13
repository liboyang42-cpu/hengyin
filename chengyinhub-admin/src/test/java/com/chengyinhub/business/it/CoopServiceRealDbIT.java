package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.dto.PricingResult;
import com.chengyinhub.business.mapper.CoopCreditLogMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.service.impl.CoopInviteServiceImpl;
import com.chengyinhub.business.service.impl.PricingServiceImpl;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 服务层 + 真实 MyBatis + 本地 MySQL 集成测试:把 CoopInviteServiceImpl / PricingServiceImpl 的
 * 真实实现跑在真库上,验证 acceptInviteWithLock(容量复核/生成coop_order/顶替)、previewTopic(读真单算P_min)
 * 等最复杂逻辑的端到端数据行为——mock 单测只验逻辑分支,这里验真 SQL+真数据。
 * 本地库 chengyin_mybatis_it 缺失时自动 skip。
 */
/*
 * ⚠️ 2026-08-14 现状:本套**跑不通**,且**没有**被并进 ci/backend-test.sh 的必跑清单。
 *
 * 原因不是环境,是它烂透了:CoopInviteServiceImpl 已经长到 13 个 mapper + 3 个 service 依赖,
 * 而本 IT 只注入了 3 个 —— 一跑就在 clubMapper / cmsRegistrationMapper ... 上连环 NPE。
 * 这正是「从没被执行过的测试会烂掉」的完整标本:它写的时候是对的,之后服务每长一个依赖,
 * 它就更死一分,而 assumeTrue(false) 让这一切**永远显示为绿**。
 *
 * 为什么不硬接满:为一个断言接 13 个真表,以后服务每加一个 @Autowired 就会把 CI 打红 ——
 * 那不是护栏,是绊线。要救它得先把被测面收窄(比如只测 mapper 层,像 CoopMapperMyBatisIT 那样),
 * 那是独立的一件事。
 *
 * 当前保证:库/配置不满足时**硬失败**,不再假装通过。已补的 cmsTopicMapper 注入与
 * it-schema 里的 cms_topic 建表都留着,给下一个来救它的人省一步。
 */
class CoopServiceRealDbIT {

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CoopInvite", CoopInvite.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsTopic", com.chengyinhub.business.domain.CmsTopic.class);
        cfg.getTypeAliasRegistry().registerAlias("CoopOrder", CoopOrder.class);
        cfg.getTypeAliasRegistry().registerAlias("CoopCreditLog",
                com.chengyinhub.business.domain.CoopCreditLog.class);
        cfg.getTypeAliasRegistry().registerAlias("CoopPerk",
                com.chengyinhub.business.domain.CoopPerk.class);
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("coop_service"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        for (String r : new String[]{
                "mapper/business/CoopInviteMapper.xml",
                "mapper/business/CoopOrderMapper.xml",
                "mapper/business/CoopCreditLogMapper.xml",
                "mapper/business/CoopPerkMapper.xml",
                // 2026-08-14:服务后来长出了 cmsTopicMapper 依赖(接受邀约要锁主题行),
                // 本 IT 从没注入过 ⇒ 一跑就 NPE。这正是「从没跑过所以烂掉」的证据。
                "mapper/business/CmsTopicMapper.xml"}) {
            try (InputStream in = Resources.getResourceAsStream(r)) {
                new XMLMapperBuilder(in, cfg, r, cfg.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    @Test
    void acceptWithLock_realDb_generatesOrderAndSupersedes_andPricing() throws Exception {
        SqlSessionFactory f;
        try {
            f = factory();
            try (SqlSession probe = f.openSession()) { probe.getConnection().isValid(2); }
        } catch (Throwable t) {
            // ★ 2026-08-14:原本是 Assumptions.assumeTrue(false, ...) —— 库连不上就整套 skip,
            // 而 skip 在构建里算**绿**。永远不会红的测试比没有测试更危险:它让人以为验过了。
            // 现在缺库 = 红。跑法见 ci/backend-test.sh(CI 的 backend job 自带 MySQL 服务)。
            throw new IllegalStateException("IT 前置不满足(库不可达 / Mapper XML / schema 配置有误),本套必须真跑不得跳过:" + t.getMessage(), t);
        }

        try (SqlSession s = f.openSession(true)) {
            // 建表:test/resources 里那份【生产导出的真实 DDL】,DROP 后重建(理由见 ItSchema)。
            ItSchema.apply(s.getConnection(), "it-schema/coop-it-schema.sql");
            ItSchema.apply(s.getConnection(), "it-schema/cms-topic-it-schema.sql");
            java.sql.Statement st = s.getConnection().createStatement();
            st.execute("DELETE FROM coop_invite");
            st.execute("DELETE FROM coop_order");
            st.execute("DELETE FROM coop_credit_log");

            CoopInviteMapper im = s.getMapper(CoopInviteMapper.class);
            CoopOrderMapper om = s.getMapper(CoopOrderMapper.class);
            CoopCreditLogMapper cm = s.getMapper(CoopCreditLogMapper.class);

            // 真实 service,注入真 mapper(走真库)
            CoopInviteServiceImpl svc = new CoopInviteServiceImpl();
            ReflectionTestUtils.setField(svc, "coopInviteMapper", im);
            ReflectionTestUtils.setField(svc, "coopOrderMapper", om);
            ReflectionTestUtils.setField(svc, "coopCreditLogMapper", cm);
            ReflectionTestUtils.setField(svc, "cmsTopicMapper",
                    s.getMapper(com.chengyinhub.business.mapper.CmsTopicMapper.class));

            // 同一游戏(gameId=5)两个挂起邀约
            CoopInvite a = pending(5L, 1, 10L, 20L, "club");
            a.setShareMode(1); a.setShareRate(new BigDecimal("30.00"));
            im.insertCoopInvite(a);
            CoopInvite b = pending(5L, 1, 11L, 21L, "merchant");
            im.insertCoopInvite(b);

            // 同意 a → 生成合作单 + 顶替 b
            CoopInvite accepted = svc.acceptInviteWithLock(a.getId(), null);
            assertEquals(Integer.valueOf(1), accepted.getStatus());

            assertEquals(Integer.valueOf(1), im.selectCoopInviteById(a.getId()).getStatus(), "a 已接受");
            assertEquals(Integer.valueOf(4), im.selectCoopInviteById(b.getId()).getStatus(), "b 被顶替(4)");
            List<CoopOrder> orders = om.selectCoopOrderByInviteId(a.getId());
            assertEquals(1, orders.size(), "生成合作单");
            assertEquals(0, new BigDecimal("30.00").compareTo(orders.get(0).getShareRate()), "条款快照");

            // 容量复核:该游戏已有接受者,再来一个挂起去同意 → 拒绝
            CoopInvite c = pending(5L, 1, 12L, 22L, "merchant");
            im.insertCoopInvite(c);
            assertThrows(IllegalStateException.class, () -> svc.acceptInviteWithLock(c.getId(), null));

            // 取消已接受 a → status=3 + 写信用
            svc.cancelAcceptedInvite(a.getId(), 10L, "临时有事", 2);
            assertEquals(Integer.valueOf(3), im.selectCoopInviteById(a.getId()).getStatus());
            assertEquals(1, cm.selectRecentByMember(10L, new Date(0L)).size());

            // ---- PricingServiceImpl.previewTopic 走真库 coop_order ----
            st.execute("DELETE FROM coop_order");
            st.execute("DELETE FROM coop_perk"); // 无申报权益 → perkCost=0,不改变本用例 P_min
            om.insertCoopOrder(order(500L, 1, new BigDecimal("30.00"), null));   // 分成30%
            om.insertCoopOrder(order(500L, 2, null, new BigDecimal("15")));       // 固定15
            PricingServiceImpl pricing = new PricingServiceImpl();
            ReflectionTestUtils.setField(pricing, "coopOrderMapper", om);
            ReflectionTestUtils.setField(pricing, "coopPerkMapper", s.getMapper(com.chengyinhub.business.mapper.CoopPerkMapper.class));
            PricingResult pr = pricing.previewTopic(500L, null, null, "self");
            // 2 个非club单 → maxN=2,guaranteeRate=0.09(§3.4 保底进地板);C=15,denom=1-0.10-0.09-0.30=0.51,ceil(15/0.51)=29.42
            assertNotNull(pr.getPriceMin());
            assertEquals(0, new BigDecimal("29.42").compareTo(pr.getPriceMin()), "真库阵容算出的 P_min(含保底率)");
            assertEquals(2, pr.getLineup().size());
        }
    }

    private CoopInvite pending(Long gameId, Integer inviteType, Long fromId, Long toId, String toType) {
        CoopInvite c = new CoopInvite();
        c.setInviteType(inviteType); c.setFromType("merchant"); c.setFromId(fromId);
        c.setToType(toType); c.setToId(toId); c.setTopicId(500L);
        c.setGameId(gameId); c.setTargetLevel(1); c.setStatus(0);
        c.setDelFlag(0); c.setCreateTime(new Date());
        return c;
    }

    private CoopOrder order(Long topicId, Integer shareMode, BigDecimal rate, BigDecimal fee) {
        CoopOrder o = new CoopOrder();
        o.setInviteId(1L); o.setTopicId(topicId); o.setShareMode(shareMode);
        o.setShareRate(rate); o.setFixedFee(fee); o.setStatus(0); o.setDelFlag(0);
        o.setTermsVersion("v1"); o.setCreateTime(new Date());
        return o;
    }
}
