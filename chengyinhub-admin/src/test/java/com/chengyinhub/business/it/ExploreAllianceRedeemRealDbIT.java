package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.mapper.RegistrationEntitlementMapper;
import com.chengyinhub.business.service.IMerchantPayableService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.impl.PerkLedgerServiceImpl;
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
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * ③ 联盟局的**第 6 步**:买票 → 每必选章预占 entitlement → 到店按章核销,跑在真库真 SQL 上。
 *
 * <p>这套测试的第一目的不是「有没有 bug」,而是**把「售出即全额预占」量化出来** ——
 * 商业上「任意领取 vs 全部核销」怎么选,取决于现码到底占掉多少商家库存,
 * 而那个数只有真库能给。
 *
 * <p><b>诚实的边界</b>:本类没有 Spring 事务管理器,{@code @Transactional} 不生效
 * ⇒ **不覆盖**回滚语义(如 quota 扣减失败时 entitlement CAS 是否一起撤销)。
 * 覆盖的是单步的数据事实与幂等键行为。
 */
class ExploreAllianceRedeemRealDbIT {

    /** 走资金门禁的唯一临时 schema（CHENGYIN_TEST_MYSQL_*），不再硬编码本机 3306 的长期库。 */
    private static final ExploreItDatabase DATABASE = new ExploreItDatabase("explore_alliance_redeem_it");

    @org.junit.jupiter.api.AfterAll
    static void dropTemporarySchema() throws Exception {
        DATABASE.drop();
    }

    private static final long TOPIC = 990002L;
    private static final long REGISTRATION = 994001L;
    private static final long PLAYER = 9500L;
    private static final long FIRST_CHAPTER = 991101L;
    private static final long FIRST_MERCHANT = 992101L;
    private static final int CHAPTER_COUNT = 4;
    private static final int QUOTA_PER_MERCHANT = 20;

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CmsTopicChapter", CmsTopicChapter.class);
        cfg.getTypeAliasRegistry().registerAlias("MerchantChapterOffer", MerchantChapterOffer.class);
        cfg.getTypeAliasRegistry().registerAlias("RegistrationEntitlement", RegistrationEntitlement.class);
        cfg.getTypeAliasRegistry().registerAlias("Redemption", Redemption.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsRegistration", CmsRegistration.class);
        PooledDataSource ds = DATABASE.freshDataSource();
        cfg.setEnvironment(new Environment("explore-redeem-it", new JdbcTransactionFactory(), ds));
        for (String r : new String[]{
                "mapper/business/CmsTopicChapterMapper.xml",
                "mapper/business/MerchantChapterOfferMapper.xml",
                "mapper/business/RegistrationEntitlementMapper.xml",
                "mapper/business/RedemptionMapper.xml",
                "mapper/business/CmsRegistrationMapper.xml"}) {
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

    private void seed(SqlSession s) throws Exception {
        try (Statement st = s.getConnection().createStatement()) {
            st.execute("DELETE FROM redemption WHERE topic_id = " + TOPIC);
            st.execute("DELETE FROM registration_entitlement WHERE topic_id = " + TOPIC);
            st.execute("DELETE FROM merchant_chapter_offer WHERE topic_id = " + TOPIC);
            st.execute("DELETE FROM cms_topic_chapter WHERE topic_id = " + TOPIC);
            st.execute("DELETE FROM cms_registration WHERE id = " + REGISTRATION);

            for (int i = 0; i < CHAPTER_COUNT; i++) {
                st.execute("INSERT INTO cms_topic_chapter (id, topic_id, name, sort_id, required,"
                        + " terms_mode, recruit_enabled, max_merchant, category_id) VALUES ("
                        + (FIRST_CHAPTER + i) + "," + TOPIC + ",'席位" + (i + 1) + "'," + (i + 1)
                        + ",1,'PERK',1,1," + (18 + i) + ")");
                st.execute("INSERT INTO merchant_chapter_offer (topic_id, chapter_id, merchant_id,"
                        + " perk_id, terms_mode, quota_total, quota_used, status) VALUES ("
                        + TOPIC + "," + (FIRST_CHAPTER + i) + "," + (FIRST_MERCHANT + i) + ","
                        + (993101L + i) + ",'PERK'," + QUOTA_PER_MERCHANT + ",0,1)");
            }
            // payment_status=2 已支付:核销幂等重放那道闸会复核它。
            // ticket_id 非空 ⇒ 配合 product_type=2 派生 purchase_kind=3 EXPLORE_PASS(③ 自由探索票)。
            st.execute("INSERT INTO cms_registration (id, registration_no, member_id, owner_type,"
                    + " owner_id, ticket_id, ticket_price, original_amount, payable_amount,"
                    + " total_amount, pay_expire_time, payment_status, verification_status) VALUES ("
                    + REGISTRATION + ",'IT" + REGISTRATION + "'," + PLAYER + ",1," + TOPIC
                    + ",995101,39.00,39.00,39.00,39.00,NOW() + INTERVAL 1 DAY,2,0)");
        }
    }

    private PerkLedgerServiceImpl service(SqlSession s) {
        PerkLedgerServiceImpl svc = new PerkLedgerServiceImpl();
        ReflectionTestUtils.setField(svc, "chapterMapper", s.getMapper(CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(svc, "offerMapper", s.getMapper(MerchantChapterOfferMapper.class));
        ReflectionTestUtils.setField(svc, "entitlementMapper",
                s.getMapper(RegistrationEntitlementMapper.class));
        ReflectionTestUtils.setField(svc, "redemptionMapper", s.getMapper(RedemptionMapper.class));
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper",
                s.getMapper(CmsRegistrationMapper.class));
        // redeemChapter / reserveEntitlements 都不碰这两个,给 mock 是为了别让空指针冒充业务失败。
        ReflectionTestUtils.setField(svc, "merchantPayableService", mock(IMerchantPayableService.class));
        ReflectionTestUtils.setField(svc, "officialEventV2Service", mock(IOfficialEventV2Service.class));
        return svc;
    }

    /** ★ 量化「售出即全额预占」:一张票卖出去,4 个席位的库存**同时**各被占 1。 */
    @Test
    void onePurchase_holdsCapacityOnEveryRequiredChapter() throws Exception {
        SqlSessionFactory f = liveFactoryOrSkip();
        if (f == null) return;
        try (SqlSession s = f.openSession(true)) {
            seed(s);
            PerkLedgerServiceImpl svc = service(s);

            for (int i = 0; i < CHAPTER_COUNT; i++) {
                assertEquals(QUOTA_PER_MERCHANT, svc.chapterCapacity(FIRST_CHAPTER + i),
                        "售票前每席位应是满库存");
            }

            svc.reserveEntitlements(REGISTRATION, TOPIC);

            List<RegistrationEntitlement> ents = svc.listEntitlements(REGISTRATION);
            assertEquals(CHAPTER_COUNT, ents.size(), "每必选章各建一行");
            for (RegistrationEntitlement e : ents) {
                assertEquals(Integer.valueOf(0), e.getStatus(), "售出即 status=0 应得,未核销也占容量");
            }
            for (int i = 0; i < CHAPTER_COUNT; i++) {
                assertEquals(QUOTA_PER_MERCHANT - 1, svc.chapterCapacity(FIRST_CHAPTER + i),
                        "一张票同时占掉全部 " + CHAPTER_COUNT + " 个席位各 1 个名额");
            }
        }
    }

    /** ★ 只用一家:核销 1 章后,另外 3 家的库存**仍被这张票占着**,直到退款或失效。 */
    @Test
    void redeemingOneChapter_leavesTheOtherSeatsStillHeld() throws Exception {
        SqlSessionFactory f = liveFactoryOrSkip();
        if (f == null) return;
        try (SqlSession s = f.openSession(true)) {
            seed(s);
            PerkLedgerServiceImpl svc = service(s);
            svc.reserveEntitlements(REGISTRATION, TOPIC);

            List<Long> redeemable = svc.resolveRedeemableChapters(REGISTRATION, TOPIC, FIRST_MERCHANT);
            assertTrue(redeemable.contains(FIRST_CHAPTER),
                    "1 号商家应当能核销 1 号席位,实际候选=" + redeemable);

            Long redemptionId = svc.redeemChapter(REGISTRATION, FIRST_CHAPTER, FIRST_MERCHANT, FIRST_MERCHANT);
            assertNotNull(redemptionId, "核销必须落一条流水");

            for (RegistrationEntitlement e : svc.listEntitlements(REGISTRATION)) {
                if (FIRST_CHAPTER == e.getChapterId()) {
                    assertEquals(Integer.valueOf(1), e.getStatus(), "已核销章应置 1");
                } else {
                    assertEquals(Integer.valueOf(0), e.getStatus(),
                            "★ 其余席位仍是应得态 —— 玩家只用了一家,另外三家的库存照样被占着");
                }
            }
            assertTrue(svc.hasPendingEntitlements(REGISTRATION), "还有未核销章 ⇒ 该票未通关");
            assertEquals(1, s.getMapper(MerchantChapterOfferMapper.class)
                    .selectByTopic(TOPIC).stream()
                    .filter(o -> FIRST_CHAPTER == o.getChapterId())
                    .findFirst().get().getQuotaUsed().intValue(), "核销的那家 quota_used 应 +1");
        }
    }

    /** 幂等:同一(票,章,商家)重复扫码返回同一条流水,不产生第二笔。 */
    @Test
    void repeatedScan_isIdempotent() throws Exception {
        SqlSessionFactory f = liveFactoryOrSkip();
        if (f == null) return;
        try (SqlSession s = f.openSession(true)) {
            seed(s);
            PerkLedgerServiceImpl svc = service(s);
            svc.reserveEntitlements(REGISTRATION, TOPIC);

            Long first = svc.redeemChapter(REGISTRATION, FIRST_CHAPTER, FIRST_MERCHANT, FIRST_MERCHANT);
            Long again = svc.redeemChapter(REGISTRATION, FIRST_CHAPTER, FIRST_MERCHANT, FIRST_MERCHANT);
            assertEquals(first, again, "重复扫码必须返回原流水 id");
        }
    }

    /** 负控:商家核销一个自己没入驻的席位必须被拒,否则「挑最便宜那章交差」的洞就是开的。 */
    @Test
    void merchantCannotRedeemSeatItDoesNotSupply() throws Exception {
        SqlSessionFactory f = liveFactoryOrSkip();
        if (f == null) return;
        try (SqlSession s = f.openSession(true)) {
            seed(s);
            PerkLedgerServiceImpl svc = service(s);
            svc.reserveEntitlements(REGISTRATION, TOPIC);

            List<Long> redeemable = svc.resolveRedeemableChapters(REGISTRATION, TOPIC, FIRST_MERCHANT);
            assertTrue(!redeemable.contains(FIRST_CHAPTER + 1),
                    "1 号商家不该出现在 2 号席位的候选集里");

            assertThrows(RuntimeException.class,
                    () -> svc.redeemChapter(REGISTRATION, FIRST_CHAPTER + 1, FIRST_MERCHANT, FIRST_MERCHANT),
                    "越章核销必须被拒");
        }
    }
}
