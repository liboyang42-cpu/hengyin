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
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * 服务层 + 真实 MyBatis + 本地 MySQL 集成测试:把 CoopInviteServiceImpl / PricingServiceImpl 的
 * 真实实现跑在真库上,验证 acceptInviteWithLock(容量复核/生成coop_order/顶替)、previewTopic(读真单算P_min)
 * 等最复杂逻辑的端到端数据行为——mock 单测只验逻辑分支,这里验真 SQL+真数据。
 * 合作、信用、权益与定价阵容走真库；参与方状态与结算费率通过受控夹具注入。
 * 真库不可达时硬失败，不跳过。
 */
class CoopServiceRealDbIT {
    private static boolean scratchCreated;
    private PooledDataSource source;

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CoopInvite", CoopInvite.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsTopic", com.chengyinhub.business.domain.CmsTopic.class);
        cfg.getTypeAliasRegistry().registerAlias("CoopOrder", CoopOrder.class);
        cfg.getTypeAliasRegistry().registerAlias("CoopCreditLog",
                com.chengyinhub.business.domain.CoopCreditLog.class);
        cfg.getTypeAliasRegistry().registerAlias("CoopPerk",
                com.chengyinhub.business.domain.CoopPerk.class);
        String scratchUrl = MybatisItDatabase.url("coop_service");
        scratchCreated = true;
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                scratchUrl,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        source = ds;
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
        SqlSessionFactory f = null;
        try {
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
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "commercialReviewService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.ITopicCommercialReviewService.class));
            ReflectionTestUtils.setField(svc, "coopInviteMapper", im);
            ReflectionTestUtils.setField(svc, "coopOrderMapper", om);
            ReflectionTestUtils.setField(svc, "coopCreditLogMapper", cm);
            ReflectionTestUtils.setField(svc, "cmsTopicMapper",
                    s.getMapper(com.chengyinhub.business.mapper.CmsTopicMapper.class));
            ReflectionTestUtils.setField(svc, "coopPerkMapper",
                    s.getMapper(com.chengyinhub.business.mapper.CoopPerkMapper.class));
            com.chengyinhub.business.mapper.ClubMapper clubs = mock(com.chengyinhub.business.mapper.ClubMapper.class);
            com.chengyinhub.business.domain.Club club = new com.chengyinhub.business.domain.Club();
            club.setId(20L);
            club.setMemberId(20L);
            when(clubs.selectClubByIdForUpdate(20L)).thenReturn(club);
            when(clubs.selectClubById(20L)).thenReturn(club);
            ReflectionTestUtils.setField(svc, "clubMapper", clubs);
            com.chengyinhub.business.mapper.MmsMerchantMapper merchants = mock(com.chengyinhub.business.mapper.MmsMerchantMapper.class);
            com.chengyinhub.business.domain.MmsMerchant merchant = new com.chengyinhub.business.domain.MmsMerchant();
            merchant.setStatus(1L);
            merchant.setAccountStatus(1L);
            merchant.setDelFlag(0);
            when(merchants.selectMmsMerchantByMemberIdForUpdate(10L)).thenReturn(merchant);
            ReflectionTestUtils.setField(svc, "mmsMerchantMapper", merchants);

            // 同一游戏(gameId=5)两个挂起邀约
            CoopInvite a = pending(5L, 1, 10L, 20L, "club");
            a.setShareMode(1); a.setShareRate(new BigDecimal("30.00"));
            im.insertCoopInvite(a);
            CoopInvite b = pending(5L, 0, 11L, 21L, "merchant");
            im.insertCoopInvite(b);

            // 同意 a → 生成合作单 + 顶替 b
            CoopInvite accepted = svc.acceptInviteWithLock(a.getId(), null);
            assertEquals(Integer.valueOf(1), accepted.getStatus());
            verify(clubs).selectClubByIdForUpdate(20L);
            verify(merchants).selectMmsMerchantByMemberIdForUpdate(10L);

            assertEquals(Integer.valueOf(1), im.selectCoopInviteById(a.getId()).getStatus(), "a 已接受");
            assertEquals(Integer.valueOf(4), im.selectCoopInviteById(b.getId()).getStatus(), "b 被顶替(4)");
            List<CoopOrder> orders = om.selectCoopOrderByInviteId(a.getId());
            assertEquals(1, orders.size(), "生成合作单");
            assertEquals(0, new BigDecimal("30.00").compareTo(orders.get(0).getShareRate()), "条款快照");

            // 容量复核:该游戏已有接受者,再来一个挂起去同意 → 拒绝
            CoopInvite c = pending(5L, 0, 12L, 22L, "merchant");
            im.insertCoopInvite(c);
            assertEquals("该游戏已有合作方,请先取消再邀",
                    assertThrows(IllegalStateException.class, () -> svc.acceptInviteWithLock(c.getId(), null)).getMessage());

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
            com.chengyinhub.business.service.ISettlementRuleService settlementRules = mock(com.chengyinhub.business.service.ISettlementRuleService.class);
            com.chengyinhub.business.domain.SettlementRule settlementRule = new com.chengyinhub.business.domain.SettlementRule();
            settlementRule.setPlatformCommissionRate(new BigDecimal("0.10"));
            settlementRule.setGuaranteeBaseRate(new BigDecimal("0.05"));
            settlementRule.setGuaranteeStepRate(new BigDecimal("0.02"));
            settlementRule.setGuaranteeMaxMerchant(5);
            when(settlementRules.getActiveRule(0L, "topic")).thenReturn(settlementRule);
            ReflectionTestUtils.setField(pricing, "settlementRuleService", settlementRules);
            PricingResult pr = pricing.previewTopic(500L, null, null, "self");
            // 2 个非club单 → maxN=2,guaranteeRate=0.09(§3.4 保底进地板);C=15,denom=1-0.10-0.09-0.30=0.51,ceil(15/0.51)=29.42
            assertNotNull(pr.getPriceMin());
            assertEquals(0, new BigDecimal("29.42").compareTo(pr.getPriceMin()), "真库阵容算出的 P_min(含保底率)");
            assertEquals(2, pr.getLineup().size());
        }
        } finally {
            try {
                if (source != null) source.forceCloseAll();
            } finally { dropScratch(); }
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

    private static void dropScratch() throws Exception {
        if (!scratchCreated) return;
        String database = MybatisItDatabase.databaseName("coop_service");
        if (!"127.0.0.1".equals(MybatisItDatabase.host()) || !database.matches("chengyin_mybatis_[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Refusing cleanup outside owned loopback scratch");
        }
        try (java.sql.Connection admin = java.sql.DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:" + MybatisItDatabase.port() + "/?useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password());
             java.sql.Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE `" + database + "`");
            try (java.sql.PreparedStatement check = admin.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
                check.setString(1, database);
                try (java.sql.ResultSet row = check.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals(0, row.getInt(1), "owned scratch must be absent");
                    assertFalse(row.next());
                }
            }
            scratchCreated = false;
        }
    }
}
