package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.impl.PricingServiceImpl;
import com.chengyinhub.business.service.impl.TopicSaleFinalizationServiceImpl;
import com.chengyinhub.business.service.support.CoopOrderLineupSource;
import com.chengyinhub.business.service.support.ApprovedSaleCanonical;
import com.chengyinhub.business.service.support.ApprovedSaleCanonicalSource;
import com.chengyinhub.business.service.support.TopicSaleCanonicalBuilder;
import com.chengyinhub.business.service.TopicSaleCanonicalTestFixture;
import com.chengyinhub.business.service.support.PendingRecruitWorkflowTerminator;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Statement;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ③ 自由探索「获客联盟」最小局(4 家权益档商家)的**开售终检端到端**,跑在真库真 SQL 上。
 *
 * <p>验的是这条链上真正决定「能不能开卖」的那一步:
 * {@code processRecruitDeadlines} 判定零现金义务后 → {@code finalizeForSale} 七项终检 → SELLING。
 *
 * <p><b>为什么必须真库</b>:七项终检里的必选章覆盖、有效 offer 谓词、PRICING→SELLING 的 CAS
 * 全是 SQL,mock 掉就等于把要验的东西换成了我自己的假设。
 *
 * <p><b>诚实的边界</b>:{@code CoopOrderLineupSource} 用 mock 返回「一个 CLUB/ORGANIZER 主体 +
 * 零现金义务」的阵容 —— H2 之后探店日必须转交俱乐部才能开售,空阵容不再是可开售局的真值;
 * {@code PendingRecruitWorkflowTerminator} 仍用 mock;
 * ORGANIZER actor seam 也固定为已声明 PLATFORM,其真实写入/回读由 ShopDayOneTicketMySqlIT 覆盖。
 * 因此本类不覆盖合作单阵容或 actor 声明链路。
 *
 * <p>本地库缺失时**跳过**;要真跑请先建库:
 * <pre>
 *   mysqldump --no-data 生产 → mysql -u root chengyin_explore_it
 *   CREATE USER 'ituser'@'127.0.0.1' IDENTIFIED BY 'itpass';
 *   GRANT ALL ON chengyin_explore_it.* TO 'ituser'@'127.0.0.1';
 * </pre>
 */
class ExploreAllianceMvpRealDbIT {

    /** 走资金门禁的唯一临时 schema（CHENGYIN_TEST_MYSQL_*），不再硬编码本机 3306 的长期库。 */
    private static final ExploreItDatabase DATABASE = new ExploreItDatabase("explore_alliance_mvp_it");

    @org.junit.jupiter.api.AfterAll
    static void dropTemporarySchema() throws Exception {
        DATABASE.drop();
    }

    private static final long TOPIC = 990001L;
    private static final long PUBLISHER = 9001L;
    private static final long FIRST_CHAPTER = 991001L;
    private static final long FIRST_MERCHANT = 992001L;
    private static final long ACTIVITY = 990101L;
    /** 唯一执行俱乐部：H2 之后没有它就开不了售。 */
    private static final long EXECUTING_CLUB = 993001L;
    private static final int CHAPTER_COUNT = 4;

    private static final long DAY = 24L * 60 * 60 * 1000;

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsTopicChapter", CmsTopicChapter.class);
        cfg.getTypeAliasRegistry().registerAlias("MerchantChapterOffer", MerchantChapterOffer.class);
        cfg.getTypeAliasRegistry().registerAlias("OmsTicket", OmsTicket.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsActivity", CmsActivity.class);
        cfg.getTypeAliasRegistry().registerAlias("TopicSaleCanonicalDraft",
                com.chengyinhub.business.domain.TopicSaleCanonicalDraft.class);
        cfg.getTypeAliasRegistry().registerAlias("TopicSaleSnapshot",
                com.chengyinhub.business.domain.TopicSaleSnapshot.class);
        PooledDataSource ds = DATABASE.freshDataSource();
        cfg.setEnvironment(new Environment("explore-it", new JdbcTransactionFactory(), ds));
        for (String r : new String[]{
                "mapper/business/CmsTopicMapper.xml",
                "mapper/business/CmsTopicChapterMapper.xml",
                "mapper/business/MerchantChapterOfferMapper.xml",
                "mapper/business/OmsTicketMapper.xml",
                "mapper/business/CmsActivityMapper.xml",
                "mapper/business/TopicSaleSnapshotMapper.xml"}) {
            try (InputStream in = Resources.getResourceAsStream(r)) {
                new XMLMapperBuilder(in, cfg, r, cfg.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private SqlSessionFactory liveFactoryOrSkip() {
        try {
            SqlSessionFactory f = factory();
            try (SqlSession probe = f.openSession()) {
                probe.getConnection().isValid(2);
            }
            return f;
        } catch (Throwable t) {
            // ★ 2026-08-14:这里原本是 Assumptions.assumeTrue(false, ...) —— 库连不上就整套 skip,
            // 而 skip 在构建里算**绿**。一个「永远不会红」的测试比没有测试更危险:它让人以为验过了。
            // 现在改成硬失败,由 ci/funds-mysql-it-gate.sh 在自托管 runner 上跑,
            // ci/funds_it_gate.py 再回读 skipped=0 —— 缺库 = 变红,不是变绿。
            throw new IllegalStateException(
                    "IT 库不可达,本套必须真跑不得跳过(见 ci/funds-mysql-it-gate.sh):" + t.getMessage(), t);
        }
    }

    /**
     * 把「人宠四商家局」摆进真库。
     *
     * @param ticketPrice   探索票价(0 = 免费通行证)
     * @param feeSnapshot   主题费率快照;null 模拟「没走过 confirmPrice」
     * @param offerCount    实际入驻商家数(< CHAPTER_COUNT 用于必选章覆盖负控)
     */
    private void seed(SqlSession s, BigDecimal ticketPrice, BigDecimal feeSnapshot, int offerCount)
            throws Exception {
        long now = System.currentTimeMillis();
        Date startDate = new Date(now - DAY);
        Date endDate = new Date(now + 30 * DAY);
        Date saleEndAt = new Date(now + 21 * DAY);
        Date recruitDeadline = new Date(now - DAY);

        try (Statement st = s.getConnection().createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS topic_sale_canonical_draft ("
                    + "topic_id BIGINT PRIMARY KEY,schema_version VARCHAR(32) NOT NULL,"
                    + "parameter_version BIGINT NOT NULL,canonical_json LONGTEXT NOT NULL,"
                    + "updated_by BIGINT NOT NULL,update_time DATETIME NOT NULL) ENGINE=InnoDB");
            st.execute("CREATE TABLE IF NOT EXISTS topic_sale_snapshot ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT,topic_id BIGINT NOT NULL UNIQUE,"
                    + "schema_version VARCHAR(32) NOT NULL,parameter_version BIGINT NOT NULL,"
                    + "source_review_task_id BIGINT NOT NULL UNIQUE,canonical_json LONGTEXT NOT NULL,"
                    + "frozen_at DATETIME NOT NULL) ENGINE=InnoDB");
            st.execute("DELETE FROM topic_sale_snapshot WHERE topic_id = " + TOPIC);
            st.execute("DELETE FROM topic_sale_canonical_draft WHERE topic_id = " + TOPIC);
            st.execute("DELETE FROM merchant_chapter_offer WHERE topic_id = " + TOPIC);
            st.execute("DELETE FROM cms_topic_chapter WHERE topic_id = " + TOPIC);
            st.execute("DELETE FROM oms_ticket WHERE owner_type = 2 AND owner_id = " + ACTIVITY);
            st.execute("DELETE FROM oms_ticket WHERE owner_type = 1 AND owner_id = " + TOPIC);
            st.execute("DELETE FROM cms_activity WHERE id = " + ACTIVITY);
            st.execute("DELETE FROM cms_topic WHERE id = " + TOPIC);
        }

        try (java.sql.PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO cms_topic (id, member_id, name, product_type, lifecycle, del_flag, status,"
                        + " self_play, self_play_price, start_date, end_date, sale_end_at, selling_started_at,"
                        + " recruit_deadline, fee_platform_rate, merchant_status, open_club_pool)"
                        + " VALUES (?,?,?,2,2,0,1, 0,NULL, ?,?,?,NULL, ?,?, 0,0)")) {
            ps.setLong(1, TOPIC);
            ps.setLong(2, PUBLISHER);
            ps.setString(3, "带毛孩子好好生活｜人宠友好生活圈");
            ps.setTimestamp(4, new java.sql.Timestamp(startDate.getTime()));
            ps.setTimestamp(5, new java.sql.Timestamp(endDate.getTime()));
            ps.setTimestamp(6, new java.sql.Timestamp(saleEndAt.getTime()));
            ps.setTimestamp(7, new java.sql.Timestamp(recruitDeadline.getTime()));
            if (feeSnapshot == null) {
                ps.setNull(8, java.sql.Types.DECIMAL);
            } else {
                ps.setBigDecimal(8, feeSnapshot);
            }
            ps.executeUpdate();
        }

        try (java.sql.PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO cms_activity(id,member_id,name,topic_id,del_flag,start_date,end_date)"
                        + " VALUES (?,?,?,?,0,?,?)")) {
            ps.setLong(1, ACTIVITY);
            ps.setLong(2, PUBLISHER);
            ps.setString(3, "探店日活动壳");
            ps.setLong(4, TOPIC);
            ps.setTimestamp(5, new java.sql.Timestamp(startDate.getTime()));
            ps.setTimestamp(6, new java.sql.Timestamp(endDate.getTime()));
            ps.executeUpdate();
        }

        String[] seatNames = {"吃得好·营养门店", "保持体面·洗护", "安心健康·宠物医院", "一起吃饭·友好餐厅"};
        try (java.sql.PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO cms_topic_chapter (id, topic_id, name, sort_id, required, terms_mode,"
                        + " recruit_enabled, max_merchant, category_id) VALUES (?,?,?,?,1,'PERK',1,1,?)")) {
            for (int i = 0; i < CHAPTER_COUNT; i++) {
                ps.setLong(1, FIRST_CHAPTER + i);
                ps.setLong(2, TOPIC);
                ps.setString(3, seatNames[i]);
                ps.setLong(4, i + 1);
                ps.setInt(5, 18 + i);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (java.sql.PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO merchant_chapter_offer (topic_id, chapter_id, merchant_id, perk_id,"
                        + " terms_mode, quota_total, quota_used, status) VALUES (?,?,?,?,'PERK',20,0,1)")) {
            for (int i = 0; i < offerCount; i++) {
                ps.setLong(1, TOPIC);
                ps.setLong(2, FIRST_CHAPTER + i);
                ps.setLong(3, FIRST_MERCHANT + i);
                ps.setLong(4, 993001L + i);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (java.sql.PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO oms_ticket (owner_type, owner_id, name, price, total_inventory,"
                        + " remaining_inventory, start_time, end_time, del_flag, mode)"
                        + " VALUES (1,?,?,?,200,200,?,?,0,2)")) {
            ps.setLong(1, TOPIC);
            ps.setString(2, "人宠生活通行证");
            ps.setBigDecimal(3, ticketPrice);
            ps.setTimestamp(4, new java.sql.Timestamp(startDate.getTime()));
            ps.setTimestamp(5, new java.sql.Timestamp(endDate.getTime()));
            ps.executeUpdate();
        }
        try (java.sql.PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO oms_ticket (owner_type, owner_id, name, price, total_inventory,"
                        + " remaining_inventory, start_time, end_time, del_flag, mode)"
                        + " VALUES (2,?,?,?,200,200,?,?,0,2)")) {
            ps.setLong(1, ACTIVITY);
            ps.setString(2, "人宠生活通行证");
            ps.setBigDecimal(3, ticketPrice);
            ps.setTimestamp(4, new java.sql.Timestamp(startDate.getTime()));
            ps.setTimestamp(5, new java.sql.Timestamp(endDate.getTime()));
            ps.executeUpdate();
        }
        try (java.sql.PreparedStatement ps = s.getConnection().prepareStatement(
                "INSERT INTO topic_sale_canonical_draft(topic_id,schema_version,parameter_version,"
                        + "canonical_json,updated_by,update_time) VALUES (?,?,?,?,?,NOW())")) {
            ps.setLong(1, TOPIC);
            ps.setString(2, com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1.SCHEMA_VERSION);
            ps.setLong(3, 1L);
            ps.setString(4, com.chengyinhub.business.service.support.TopicSaleCanonicalCodec.writeDraft(
                    TopicSaleCanonicalTestFixture.canonical()));
            ps.setLong(5, PUBLISHER);
            ps.executeUpdate();
        }
    }

    private TopicSaleFinalizationServiceImpl service(SqlSession s) {
        // 阵容里必须有唯一执行俱乐部：探店日按 H2「转交俱乐部才能开售」，
        // finalizeForSale 从冻结阵容里解析 role=ORGANIZER 的 CLUB 主体，解析不出就
        // SALE_EXECUTING_CLUB_UNRESOLVED。原来这里返回空阵容 —— 那是 H2 之前的前提，
        // 之后就成了「本套件永远开不了售」，只是套件从没在门禁里真跑过所以没人看见。
        // 零现金义务(cashObligation=false)仍然保留：这套件验的就是零 coop_order 现金义务那条路径。
        CoopOrderLineupSource lineup = mock(CoopOrderLineupSource.class);
        when(lineup.readEffectiveLineup(TOPIC)).thenReturn(new CoopOrderLineupSource.ReadResult(
                Collections.singletonList(new CoopOrderLineupSource.LineupEntry(
                        "CLUB", EXECUTING_CLUB, "ORGANIZER", "TOPIC", TOPIC, "COOP_ORDER", 990501L, false)),
                Collections.<String>emptyList()));

        @SuppressWarnings("unchecked")
        ObjectProvider<PendingRecruitWorkflowTerminator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(PendingRecruitWorkflowTerminator.class));
        com.chengyinhub.business.service.support.CommercialTopicPolicy commercialPolicy =
                mock(com.chengyinhub.business.service.support.CommercialTopicPolicy.class);
        when(commercialPolicy.evaluate(TOPIC)).thenReturn(
                com.chengyinhub.business.service.support.CommercialTopicPolicy.Evaluation.ugc());

        com.chengyinhub.business.service.support.CommercialReviewStatusSource reviewSource =
                mock(com.chengyinhub.business.service.support.CommercialReviewStatusSource.class);
        TopicSaleFinalizationServiceImpl result = new TopicSaleFinalizationServiceImpl(
                s.getMapper(CmsTopicMapper.class),
                s.getMapper(OmsTicketMapper.class),
                s.getMapper(CmsTopicChapterMapper.class),
                s.getMapper(MerchantChapterOfferMapper.class),
                lineup, provider, commercialPolicy, reviewSource,
                mock(com.chengyinhub.business.service.support.MerchantPublishedTopicTransferGate.class));
        // A4 / A1+A2 两道开售闸在生产是必填注入；本套件验的是七项终检与 PRICING→SELLING 的 CAS，
        // 这两道闸各自有专属真库套件承重（ClubCorporatePayeeMybatisIntegrationTest /
        // ClubEditionCompensationMybatisIntegrationTest），这里用 mock 放行，不重复验也不假装验过。
        ReflectionTestUtils.setField(result, "clubPayeeService",
                mock(com.chengyinhub.business.service.IClubCorporatePayeeService.class));
        ReflectionTestUtils.setField(result, "compensationService",
                mock(com.chengyinhub.business.service.IClubEditionCompensationService.class));
        ReflectionTestUtils.setField(result, "activityMapper", s.getMapper(CmsActivityMapper.class));
        ReflectionTestUtils.setField(result, "saleSnapshotMapper", s.getMapper(TopicSaleSnapshotMapper.class));
        IProductRoleService productRoleService = mock(IProductRoleService.class);
        ProductRole organizerActor = new ProductRole();
        organizerActor.setId(880001L);
        organizerActor.setEntityType("PLATFORM");
        organizerActor.setDeclaredBy(PUBLISHER);
        organizerActor.setCreatedAt(new Date());
        when(productRoleService.activeOrganizerSlot(TOPIC)).thenReturn(organizerActor);
        ReflectionTestUtils.setField(result, "productRoleService", productRoleService);
        TopicSaleCanonicalBuilder canonicalBuilder = mock(TopicSaleCanonicalBuilder.class);
        when(canonicalBuilder.rebuild(org.mockito.ArgumentMatchers.any(CmsTopic.class),
                org.mockito.ArgumentMatchers.any(com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1.class)))
                .thenReturn(TopicSaleCanonicalTestFixture.canonical());
        ReflectionTestUtils.setField(result, "canonicalBuilder", canonicalBuilder);
        ApprovedSaleCanonicalSource approvedSource = mock(ApprovedSaleCanonicalSource.class);
        when(approvedSource.read(org.mockito.ArgumentMatchers.eq(TOPIC), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ApprovedSaleCanonical(770001L,
                com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1.SCHEMA_VERSION, 1L,
                TopicSaleCanonicalTestFixture.canonical()));
        ReflectionTestUtils.setField(result, "approvedCanonicalSource", approvedSource);
        return result;
    }

    private Integer lifecycleOf(SqlSession s) {
        return s.getMapper(CmsTopicMapper.class).selectCmsTopicById(TOPIC).getLifecycle();
    }

    /**
     * ★ 主假设:全 PERK = 零现金 ⇒ 自动路径直奔 finalizeForSale,**跳过 confirmPrice**;
     * 而 confirmPrice 是 fee_platform_rate 的唯一写入点 ⇒ 收费票在终检处被拒。
     */
    @Test
    void pricedTicket_withoutFeeSnapshot_isRejected() throws Exception {
        SqlSessionFactory f = liveFactoryOrSkip();
        if (f == null) return;
        try (SqlSession s = f.openSession(true)) {
            seed(s, new BigDecimal("39.00"), null, CHAPTER_COUNT);
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service(s).finalizeForSale(TOPIC));
            assertTrue(ex.getMessage().contains("费率快照"),
                    "应因费率快照缺失被拒,实际:" + ex.getMessage());
            assertEquals(Integer.valueOf(2), lifecycleOf(s), "被拒后必须仍停在 PRICING");
        }
    }

    /** 免费通行证:零现金 + 零票价 ⇒ 自动路径能一路开到 SELLING。 */
    @Test
    void freeTicket_withoutFeeSnapshot_finalizes() throws Exception {
        SqlSessionFactory f = liveFactoryOrSkip();
        if (f == null) return;
        try (SqlSession s = f.openSession(true)) {
            seed(s, BigDecimal.ZERO, null, CHAPTER_COUNT);
            Date startedAt = service(s).finalizeForSale(TOPIC);
            assertNotNull(startedAt, "开售时刻必须落库");
            assertEquals(Integer.valueOf(3), lifecycleOf(s), "应进入 SELLING");
        }
    }

    /** 收费票补上费率快照(= 走过 confirmPrice)后同一局能开售 —— 证明上面那条卡的确实是快照。 */
    @Test
    void pricedTicket_withFeeSnapshot_finalizes() throws Exception {
        SqlSessionFactory f = liveFactoryOrSkip();
        if (f == null) return;
        try (SqlSession s = f.openSession(true)) {
            seed(s, new BigDecimal("39.00"), new BigDecimal("0.10"), CHAPTER_COUNT);
            assertNotNull(service(s).finalizeForSale(TOPIC));
            assertEquals(Integer.valueOf(3), lifecycleOf(s), "应进入 SELLING");
        }
    }

    /**
     * ★ 修法 A 的验收:零现金自动路径先 {@code freezeFeeSnapshot} 再终检 ⇒ 收费票能开售,
     * 且冻进去的是 settlement_rule 的**当前值**(不是硬编码兜底)。
     *
     * <p>这条与 {@link #pricedTicket_withoutFeeSnapshot_isRejected} 是一对:那条证明不补冻会死,
     * 这条证明补冻之后同一局能活,且 finalizeForSale 那道闸**原样保留**没有被放宽。
     */
    @Test
    void autoPath_freezesFeeSnapshotThenFinalizes() throws Exception {
        SqlSessionFactory f = liveFactoryOrSkip();
        if (f == null) return;
        try (SqlSession s = f.openSession(true)) {
            seed(s, new BigDecimal("39.00"), null, CHAPTER_COUNT);

            SettlementRule rule = new SettlementRule();
            rule.setPlatformCommissionRate(new BigDecimal("0.15"));
            rule.setGuaranteeBaseRate(new BigDecimal("0.05"));
            rule.setGuaranteeStepRate(new BigDecimal("0.02"));
            rule.setGuaranteeMaxMerchant(5);
            ISettlementRuleService ruleService = mock(ISettlementRuleService.class);
            when(ruleService.getActiveRule(0L, "topic")).thenReturn(rule);

            PricingServiceImpl pricing = new PricingServiceImpl();
            ReflectionTestUtils.setField(pricing, "cmsTopicMapper", s.getMapper(CmsTopicMapper.class));
            ReflectionTestUtils.setField(pricing, "settlementRuleService", ruleService);

            assertEquals(1, pricing.freezeFeeSnapshot(TOPIC), "首次应写入快照");
            assertEquals(0, pricing.freezeFeeSnapshot(TOPIC), "幂等:已冻不再覆盖");

            CmsTopic frozen = s.getMapper(CmsTopicMapper.class).selectCmsTopicById(TOPIC);
            assertEquals(0, new BigDecimal("0.15").compareTo(frozen.getFeePlatformRate()),
                    "必须冻 settlement_rule 的当前值");
            assertNotNull(frozen.getFeeGuaranteeBaseRate(), "保底公式参数必须一起冻,否则结算时回落 live 值");
            assertNotNull(frozen.getFeeGuaranteeStepRate());
            assertNotNull(frozen.getFeeGuaranteeMaxMerchant());

            assertNotNull(service(s).finalizeForSale(TOPIC));
            assertEquals(Integer.valueOf(3), lifecycleOf(s), "补冻后收费票应能开售");
        }
    }

    /** 负控:4 个必选席位只招到 3 家 ⇒ 必选章覆盖闸必须红,否则那道闸是假的。 */
    @Test
    void requiredChapterWithoutOffer_isRejected() throws Exception {
        SqlSessionFactory f = liveFactoryOrSkip();
        if (f == null) return;
        try (SqlSession s = f.openSession(true)) {
            seed(s, BigDecimal.ZERO, null, CHAPTER_COUNT - 1);
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service(s).finalizeForSale(TOPIC));
            assertTrue(ex.getMessage().contains("缺有效 SUPPLY 供给"),
                    "应因必选章缺供给被拒,实际:" + ex.getMessage());
            assertEquals(Integer.valueOf(2), lifecycleOf(s), "被拒后必须仍停在 PRICING");
        }
    }
}
