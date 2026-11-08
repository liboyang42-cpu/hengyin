package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.dto.PlayerAffiliateBucketRow;
import com.chengyinhub.business.domain.dto.PlayerAffiliateCampaignRow;
import com.chengyinhub.business.domain.dto.PlayerAffiliateCredentialRow;
import com.chengyinhub.business.domain.dto.ReferralLandingGiftRow;
import com.chengyinhub.business.domain.vo.PlayerAffiliateSummaryVO;
import com.chengyinhub.business.mapper.PlayerAffiliateMapper;
import com.chengyinhub.business.mapper.ReferralLandingMapper;
import com.chengyinhub.business.service.impl.PlayerAffiliateServiceImpl;
import com.chengyinhub.business.service.support.MarketingClaimTokenCodec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1/P2 两个**只读**营销 Mapper 在真实 InnoDB 上的首次执行验收。
 *
 * <p>为什么必须真库：这两条读路径此前只有单测（mock mapper），SQL 一次都没跑过。会红的点全
 * 只存在于真实库里 —— {@code ascii_bin} 摘要比较的大小写敏感、campaign 聚合里
 * {@code left join marketing_revenue_allocation} 是否因一单多变而重复计数、
 * {@code count(distinct a.id)} 在"一单三条分录"时是否仍按分配计、以及
 * {@code line_role='referrer' + recipient_member_id} 双过滤是否真把别人的分录挡在外面。
 *
 * <p>四个用例：
 * <ol>
 *   <li>礼品落地页按<b>码摘要精确命中</b>：pending/claimed/revoked 各自的公开投影；大写化摘要
 *       不命中（ascii_bin 证明）；投影类型<b>没有</b>买家/订单/来源单/领取人字段（反射断言）。</li>
 *   <li>凭据/成交按<b>登录人隔离</b>：一条"归因行 sharer=A 但凭据属 B"的脏数据必须零命中，
 *       证明 join 里的凭据属主再校验真的生效，而不是只信冗余列。</li>
 *   <li>推荐人收入桶：一单三条分录不得乘出三倍金额；跨两单同状态时 {@code allocationCount}
 *       按分配数（2）而非分录数；author/platform 分录不混入。</li>
 *   <li>campaign 已付/退款/待应计与 held 语义对齐 production 合同
 *       （2=已支付、4=已退款、已付但无分配行=held），{@code held.amountFen} 必须为 {@code null}。</li>
 * </ol>
 *
 * <p>一类一库：建库走 {@link MybatisItDatabase}，表结构是仓库 migration 原文加 base 快照原文，
 * 跑完 DROP DATABASE 并由 {@link P0AcceptanceSchema#drop} 回读 information_schema 证明不存在。
 * 不建支付/退款/渠道请求，不碰任何 provider。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_MARKETING_DB_IT_APPROVED", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlayerMarketingReadMySqlIT {

    private final String suite = "mkread_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    private static final long TOPIC_ID = 42L;
    private static final long ACTIVITY_ID = 88L;
    private static final long SHARER_A = 9001L;
    private static final long SHARER_B = 9002L;
    private static final long AUTHOR = 7700L;
    private static final long RULE_ID = 501L;
    private static final String CAMPAIGN_A = "camp-a";
    private static final String CAMPAIGN_B = "camp-b";
    private static final String TOKEN_PENDING = "gt1.aaaaaaaaaaaaaaaaaaaa.0.pending";
    private static final String TOKEN_CLAIMED = "gt1.bbbbbbbbbbbbbbbbbbbb.0.claimed";
    private static final String TOKEN_REVOKED = "gt1.cccccccccccccccccccc.0.revoked";

    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private ReferralLandingMapper landingMapper;
    private PlayerAffiliateMapper affiliateMapper;
    private PlayerAffiliateServiceImpl affiliateService;

    @BeforeAll
    void schemaAndRealMappers() throws Exception {
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()),
                "只许连一次性 scratch MySQL");
        source = P0AcceptanceSchema.dataSource(suite);
        db = new JdbcTemplate(source);
        assertEquals(MybatisItDatabase.databaseName(suite),
                db.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(com.chengyinhub.business.service.MysqlIntegrationTestConfig.isMysql8OrNewer(
                db.queryForObject("SELECT VERSION()", String.class)), "需要 Oracle MySQL >= 8");

        // 公开读面 join 到的三张既有表，取自 base 快照原文。
        P0AcceptanceSchema.baseTables(db, "cms_topic", "cms_activity", "cms_registration");
        // 三个营销迁移原文：商品/礼品、归因/凭据、收入规则/分配/分录。
        P0AcceptanceSchema.migrations(source,
                "migration_marketing_product_20261108.sql",
                "migration_marketing_attribution_20261108.sql",
                "migration_marketing_revenue_allocation_20261108.sql");

        var session = P0AcceptanceSchema.sql(source, "ReferralLandingMapper", "PlayerAffiliateMapper");
        landingMapper = session.getMapper(ReferralLandingMapper.class);
        affiliateMapper = session.getMapper(PlayerAffiliateMapper.class);
        affiliateService = new PlayerAffiliateServiceImpl(affiliateMapper);
    }

    @AfterAll
    void cleanup() {
        if (db != null) {
            assertTrue(MybatisItDatabase.databaseName(suite).matches("chengyin_mybatis_mkread_[a-z0-9_]+"));
            P0AcceptanceSchema.drop(db, suite);
        }
    }

    @BeforeEach
    void wipeOwnedScratchRows() {
        // 先子后父，顺序由真实 FK 决定。
        for (String table : List.of("marketing_revenue_allocation_line", "marketing_revenue_allocation",
                "marketing_revenue_posting_task", "marketing_revenue_rule",
                "marketing_attribution", "marketing_referral_credential",
                "gift_entitlement", "marketing_product_order_line", "marketing_product_order",
                "marketing_product_item", "marketing_product",
                "cms_registration", "cms_topic", "cms_activity")) {
            db.update("DELETE FROM " + table);
        }
        db.update("INSERT INTO cms_topic (id, name) VALUES (?, ?)", TOPIC_ID, "老城谜案");
        db.update("INSERT INTO cms_activity (id, name) VALUES (?, ?)", ACTIVITY_ID, "夜游市集");
    }

    // ------------------------------------------------------------------ 1 礼品落地页投影

    @Test
    void giftLandingHitIsAnExactHashMatchAndProjectsNoIdentity() {
        long productId = insertGiftProduct("gift-read-30d");
        db.update("INSERT INTO marketing_product_item (id,product_id,topic_id,topic_title,content_revision,"
                + "sort_no,create_time) VALUES (?,?,?,?,?,?,now())", 601L, productId, TOPIC_ID, "老城谜案", 9L, 0);
        db.update("INSERT INTO marketing_product_item (id,product_id,topic_id,topic_title,content_revision,"
                + "sort_no,create_time) VALUES (?,?,?,?,?,?,now())", 602L, productId, 43L, "码头夜话", 11L, 1);

        Timestamp pendingExpiry = secondsFromNow(7L * 24 * 3600);
        // 礼品转移的源行是买家已付通行证；这里只用来证明投影不读它。
        insertRegistration(9201L, 7001L, TOPIC_ID, 2, "10.00", 2);
        insertRegistration(9202L, 7002L, TOPIC_ID, 2, "10.00", 2);
        insertRegistration(9203L, 7003L, TOPIC_ID, 2, "10.00", 2);
        Timestamp claimedAt = secondsFromNow(-3600L);
        insertGift(insertGiftOrder(9101L, 11L, productId, "MKR-1"), 7001L, productId, 9201L,
                "PUBPENDING00000001", TOKEN_PENDING, pendingExpiry,
                ReferralLandingGiftRow.STATUS_PENDING, null, null, null);
        insertGift(insertGiftOrder(9102L, 12L, productId, "MKR-2"), 7001L, productId, 9202L,
                "PUBCLAIMED0000002", TOKEN_CLAIMED, pendingExpiry,
                ReferralLandingGiftRow.STATUS_CLAIMED, 7002L, 7002L, claimedAt);
        insertGift(insertGiftOrder(9103L, 13L, productId, "MKR-3"), 7003L, productId, 9203L,
                "PUBREVOKED0000003", TOKEN_REVOKED, pendingExpiry,
                ReferralLandingGiftRow.STATUS_REVOKED, null, null, null);

        ReferralLandingGiftRow pending = landingMapper.selectGiftByTokenHash(
                MarketingClaimTokenCodec.sha256Hex(TOKEN_PENDING));
        ReferralLandingGiftRow claimed = landingMapper.selectGiftByTokenHash(
                MarketingClaimTokenCodec.sha256Hex(TOKEN_CLAIMED));
        ReferralLandingGiftRow revoked = landingMapper.selectGiftByTokenHash(
                MarketingClaimTokenCodec.sha256Hex(TOKEN_REVOKED));

        assertNotNull(pending);
        assertEquals("PUBPENDING00000001", pending.getPublicId());
        assertEquals(Long.valueOf(productId), pending.getProductId());
        assertEquals(Integer.valueOf(ReferralLandingGiftRow.STATUS_PENDING), pending.getStatus());
        assertFalse(pending.isRecipientBound(), "未预绑定的礼品不得显示为指定领取人");
        assertSameSecond(pendingExpiry, pending.getClaimExpiresAt(), "claim_expires_at 原样下发");
        assertNull(pending.getClaimedAt());

        assertEquals(Integer.valueOf(ReferralLandingGiftRow.STATUS_CLAIMED), claimed.getStatus());
        assertTrue(claimed.isRecipientBound(), "有 recipient_member_id 的行必须如实报已绑定领取人");
        assertSameSecond(claimedAt, claimed.getClaimedAt(),
                "claimed_at 原样下发，不按当前时间推算");

        assertEquals(Integer.valueOf(ReferralLandingGiftRow.STATUS_REVOKED), revoked.getStatus());
        assertNull(revoked.getClaimedAt());

        // 摘要按 ascii_bin 精确比较：同一明文的哈希换成大写不得命中。
        assertNull(landingMapper.selectGiftByTokenHash(
                        MarketingClaimTokenCodec.sha256Hex(TOKEN_PENDING).toUpperCase()),
                "大写摘要不得命中：否则十六进制枚举面被无谓放大");
        assertNull(landingMapper.selectGiftByTokenHash(
                        MarketingClaimTokenCodec.sha256Hex("gt1.dddddddddddddddddddd.0.absent")),
                "不存在的摘要必须 miss，不得返回别人的礼品");

        // 投影类型本身不许带买家/订单/来源单/领取人字段：加字段就会红。
        assertEquals(Set.of("publicId", "productId", "status", "recipientBound", "claimExpiresAt", "claimedAt"),
                instanceFieldNames(ReferralLandingGiftRow.class),
                "礼品落地页投影只能有公开字段");
    }

    // ------------------------------------------------------------------ 2 登录人隔离

    @Test
    void credentialsAndCampaignsAreScopedToTheCallerOnly() {
        long credentialA = insertCredential(801L, "pubcredentialaaaaaaaa1", SHARER_A, CAMPAIGN_A, 1, TOPIC_ID);
        long credentialB = insertCredential(802L, "pubcredentialbbbbbbbb2", SHARER_B, CAMPAIGN_B, 2, ACTIVITY_ID);
        insertRegistration(9201L, SHARER_A, TOPIC_ID, 2, "20.00", 2);
        // 成交聚合只读 registration 的 id/payment_status/payable_amount；owner 字段保持主题一致。
        insertRegistration(9204L, SHARER_B, TOPIC_ID, 2, "30.00", 2);
        insertRegistration(9205L, SHARER_B, TOPIC_ID, 2, "40.00", 2);
        insertAttribution(9301L, 9201L, credentialA, SHARER_A, CAMPAIGN_A);
        insertAttribution(9302L, 9204L, credentialB, SHARER_B, CAMPAIGN_B);
        // 脏数据：冗余 sharer_member_id 写成 A，但凭据属于 B —— 只信冗余列就会越权统计。
        // uk_attribution_order 是一单一行的唯一键，所以必须换一张未被归因的单。
        insertAttribution(9303L, 9205L, credentialB, SHARER_A, CAMPAIGN_B);

        List<PlayerAffiliateCredentialRow> credentialsA = affiliateMapper.listOwnCredentials(SHARER_A, 20);
        assertEquals(1, credentialsA.size(), "A 只能看到自己签发的凭据");
        assertEquals(CAMPAIGN_A, credentialsA.get(0).getCampaignKey());
        assertEquals("老城谜案", credentialsA.get(0).getTargetTitle(), "标题取 cms_topic 真实名称");

        List<PlayerAffiliateCampaignRow> campaignsA = affiliateMapper.listOwnCampaigns(SHARER_A, 20);
        assertEquals(1, campaignsA.size(), "脏归因行不得给 A 造出第二个 campaign");
        assertEquals(CAMPAIGN_A, campaignsA.get(0).getCampaignKey());
        assertEquals(1L, campaignsA.get(0).getAttributedOrders(), "只统计凭据属主为 A 的归因");
        assertEquals(new BigDecimal("20.00"), campaignsA.get(0).getPaidAmount());

        PlayerAffiliateSummaryVO summaryB = affiliateService.summary(SHARER_B, 20);
        assertEquals(1, summaryB.getCredentials().size());
        assertEquals(CAMPAIGN_B, summaryB.getCredentials().get(0).getCampaignKey());
        assertEquals("夜游市集", summaryB.getCredentials().get(0).getTargetTitle(),
                "活动目标标题取 cms_activity 真实名称");
        assertEquals(1, summaryB.getCampaigns().size());
        assertEquals(new BigDecimal("30.00"), summaryB.getCampaigns().get(0).getPaidAmount(),
                "A 的订单不得计入 B 的成交金额");
        assertEquals("active", summaryB.getCredentials().get(0).getStatusText());
    }

    // ------------------------------------------------------------------ 3 推荐人收入桶

    @Test
    void referrerBucketsSumRealReversalLinesWithoutRowMultiplication() {
        insertRule();
        insertRegistration(9201L, SHARER_A, TOPIC_ID, 2, "100.00", 2);
        insertRegistration(9202L, SHARER_A, TOPIC_ID, 2, "30.00", 2);
        // 一单 100.00：应计 10.00，两笔冲销 4.00 + 1.00；另一单 30.00：应计 3.00，冲销 0.50。
        insertAllocation(1001L, 9201L, 10000L, 10000L, 500L, 5);
        insertAllocation(1002L, 9202L, 3000L, 3000L, 50L, 5);
        insertLine(1101L, 1001L, 1, "referrer", SHARER_A, 10000L, "0.10000000", 1000L,
                "accrue:referrer", null, null, 9201L);
        insertLine(1102L, 1001L, 2, "referrer", SHARER_A, 10000L, "0.10000000", -400L,
                "reverse:refund:9001:referrer", "refund:9001", 4000L, 9201L);
        insertLine(1103L, 1001L, 2, "referrer", SHARER_A, 10000L, "0.10000000", -100L,
                "reverse:refund:9002:referrer", "refund:9002", 1000L, 9201L);
        insertLine(1104L, 1001L, 1, "author", AUTHOR, 10000L, "0.80000000", 8000L,
                "accrue:author", null, null, 9201L);
        insertLine(1105L, 1001L, 1, "platform", null, 10000L, "0.10000000", 1000L,
                "accrue:platform", null, null, 9201L);
        insertLine(1106L, 1002L, 1, "referrer", SHARER_A, 3000L, "0.10000000", 300L,
                "accrue:referrer", null, null, 9202L);
        insertLine(1107L, 1002L, 1, "author", AUTHOR, 3000L, "0.80000000", 240L,
                "accrue:author", null, null, 9202L);
        insertLine(1108L, 1002L, 1, "platform", null, 3000L, "0.10000000", 60L,
                "accrue:platform", null, null, 9202L);
        insertLine(1109L, 1002L, 2, "referrer", SHARER_A, 3000L, "0.10000000", -50L,
                "reverse:refund:9003:referrer", "refund:9003", 500L, 9202L);
        insertLine(1110L, 1002L, 2, "author", AUTHOR, 3000L, "0.80000000", -40L,
                "reverse:refund:9003:author", "refund:9003", 500L, 9202L);

        List<PlayerAffiliateBucketRow> rows = affiliateMapper.listReferrerBuckets(SHARER_A);
        assertEquals(2, rows.size(), "按 (分配状态, 币种, 分录方向) 只应有两组");

        PlayerAffiliateSummaryVO out = affiliateService.summary(SHARER_A, 20);
        PlayerAffiliateSummaryVO.Bucket reversed = out.getAllocations().get("reversed");
        assertEquals(1300L, reversed.getAccruedFen(), "5 条分录不得乘出 5 倍：应计 1000+300");
        assertEquals(550L, reversed.getReversedFen(), "冲销按绝对值单列：400+100+50");
        assertEquals(750L, reversed.getNetFen());
        assertEquals(2L, reversed.getAllocationCount(), "分配数按 distinct 分配（2），不是分录数");
        assertEquals("CNY", reversed.getCurrency());
        assertEquals(new BigDecimal("13.00"), reversed.getAccruedAmount());
        assertEquals(new BigDecimal("5.50"), reversed.getReversedAmount());
        assertEquals(new BigDecimal("7.50"), reversed.getNetAmount());

        for (String empty : List.of("pending", "confirmed", "paid")) {
            PlayerAffiliateSummaryVO.Bucket bucket = out.getAllocations().get(empty);
            assertEquals(0L, bucket.getAccruedFen(), empty + " 桶不得被 reversed 分录污染");
            assertEquals(0L, bucket.getAllocationCount());
            assertNull(bucket.getCurrency());
        }

        assertTrue(affiliateMapper.listReferrerBuckets(SHARER_B).isEmpty(),
                "推荐人桶只认 recipient_member_id=调用者");
    }

    @Test
    void partiallyAndFullyReversedAllocationsAreCountedOnceEach() {
        insertRule();
        insertRegistration(9211L, SHARER_A, TOPIC_ID, 2, "10.00", 2);
        insertRegistration(9212L, SHARER_A, TOPIC_ID, 4, "20.00", 2);
        insertAllocation(1011L, 9211L, 1000L, 1000L, 500L, 4);
        insertAllocation(1012L, 9212L, 2000L, 2000L, 2000L, 5);
        insertLine(1121L, 1011L, 1, "referrer", SHARER_A, 1000L, "0.10000000", 100L,
                "accrue:referrer", null, null, 9211L);
        insertLine(1122L, 1011L, 2, "referrer", SHARER_A, 1000L, "0.10000000", -50L,
                "reverse:refund:9011:referrer", "refund:9011", 500L, 9211L);
        insertLine(1123L, 1012L, 1, "referrer", SHARER_A, 2000L, "0.10000000", 200L,
                "accrue:referrer", null, null, 9212L);
        insertLine(1124L, 1012L, 2, "referrer", SHARER_A, 2000L, "0.10000000", -200L,
                "reverse:refund:9012:referrer", "refund:9012", 2000L, 9212L);

        PlayerAffiliateSummaryVO.Bucket bucket = affiliateService.summary(SHARER_A, 20)
                .getAllocations().get("reversed");
        assertEquals(2L, bucket.getAllocationCount());
        assertEquals(300L, bucket.getAccruedFen());
        assertEquals(250L, bucket.getReversedFen());
        assertEquals(50L, bucket.getNetFen());
        assertEquals("CNY", bucket.getCurrency());
        assertNull(bucket.getAmountUnavailableReason());
    }

    @Test
    void differentCurrenciesNeverBecomeOneMoneyTotal() {
        insertRule();
        insertRegistration(9221L, SHARER_A, TOPIC_ID, 2, "10.00", 2);
        insertRegistration(9222L, SHARER_A, TOPIC_ID, 2, "20.00", 2);
        insertAllocation(1021L, 9221L, 1000L, 1000L, 0L, 1);
        insertAllocation(1022L, 9222L, 2000L, 2000L, 0L, 1);
        insertLine(1131L, 1021L, 1, "referrer", SHARER_A, 1000L, "0.10000000", 100L,
                "accrue:referrer", null, null, 9221L);
        insertLine(1132L, 1022L, 1, "referrer", SHARER_A, 2000L, "0.10000000", 200L,
                "accrue:referrer", null, null, 9222L);
        db.update("UPDATE marketing_revenue_allocation SET currency='USD' WHERE id=1022");
        db.update("UPDATE marketing_revenue_allocation_line SET currency='USD' WHERE allocation_id=1022");

        PlayerAffiliateSummaryVO.Bucket bucket = affiliateService.summary(SHARER_A, 20)
                .getAllocations().get("pending");
        assertEquals(2L, bucket.getAllocationCount());
        assertEquals("MULTIPLE_CURRENCIES", bucket.getAmountUnavailableReason());
        assertNull(bucket.getCurrency());
        assertNull(bucket.getAccruedFen());
        assertNull(bucket.getReversedFen());
        assertNull(bucket.getNetFen());
        assertNull(bucket.getAccruedAmount());
        assertNull(bucket.getReversedAmount());
        assertNull(bucket.getNetAmount());
    }

    // ------------------------------------------------------------------ 4 成交与 held 语义

    @Test
    void campaignPaidRefundedHeldFactsMatchTheProductionContract() {
        long credential = insertCredential(801L, "pubcredentialaaaaaaaa1", SHARER_A, CAMPAIGN_A, 1, TOPIC_ID);
        insertRule();
        insertRegistration(9201L, SHARER_A, TOPIC_ID, 2, "20.00", 2);
        insertRegistration(9202L, SHARER_A, TOPIC_ID, 2, "30.00", 2);
        insertRegistration(9203L, SHARER_A, TOPIC_ID, 4, "10.00", 2);
        insertRegistration(9204L, SHARER_A, TOPIC_ID, 0, "5.00", 1);
        insertAttribution(9301L, 9201L, credential, SHARER_A, CAMPAIGN_A);
        insertAttribution(9302L, 9202L, credential, SHARER_A, CAMPAIGN_A);
        insertAttribution(9303L, 9203L, credential, SHARER_A, CAMPAIGN_A);
        insertAttribution(9304L, 9204L, credential, SHARER_A, CAMPAIGN_A);
        // 归因指向一张并不存在的报名单：计入归因数，但不能凭空变成已付/退款/held。
        insertAttribution(9305L, 9999L, credential, SHARER_A, CAMPAIGN_A);
        // 9201 已应计（有分配行）⇒ 不是 held；9202 已付无分配行 ⇒ held。
        insertAllocation(1001L, 9201L, 10000L, 10000L, 0L, 1);

        PlayerAffiliateSummaryVO out = affiliateService.summary(SHARER_A, 20);
        assertEquals(1, out.getCampaigns().size());
        PlayerAffiliateSummaryVO.Campaign campaign = out.getCampaigns().get(0);
        assertEquals(5L, campaign.getAttributedOrders(), "归因数按真实归因行计");
        assertEquals(2L, campaign.getPaidOrders(), "2=已支付；无报名行的归因不算已付");
        assertEquals(new BigDecimal("50.00"), campaign.getPaidAmount(), "20.00+30.00，退款单不计入已付");
        assertEquals(1L, campaign.getRefundedOrders(), "4=已退款");
        assertEquals(new BigDecimal("10.00"), campaign.getRefundedAmount());
        assertEquals(1L, campaign.getHeldOrders(), "已付但还没有分配行的那一单才是 held");

        assertEquals(1L, out.getHeld().getOrders());
        assertNull(out.getHeld().getAmountFen(), "没有分配行就没有可信金额依据，不得填 0 或估值");
        assertEquals("ALLOCATION_NOT_YET_ACCRUED", out.getHeld().getReason());
        assertFalse(out.getPayout().isAvailable());
        assertEquals("NO_PLAYER_PAYOUT_PATH", out.getPayout().getReason());
        assertNull(out.getPayout().getEndpoint(), "没有打款路径就没有 endpoint，不得给假提现入口");
        // 已应计那一单没有任何分录 ⇒ 收入桶必须如实为空，held 与桶互不多算。
        assertEquals(0L, out.getAllocations().get("pending").getAccruedFen());
        assertEquals(0L, out.getAllocations().get("reversed").getAccruedFen());
    }

    // ------------------------------------------------------------------ 夹具（只写本库）

    private long insertGiftProduct(String code) {
        db.update("INSERT INTO marketing_product (id,product_code,product_name,product_kind,purchase_kind,"
                        + "explore_capacity,fulfillment_route,validity_kind,valid_days,seat_count,gift_allowed,"
                        + "gift_claim_days,status,cash_price,terms_version,revision,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now())",
                501L, code + "-" + UUID.randomUUID().toString().substring(0, 8), "礼品通行证", 7, 2, 0, 1, 1, 30,
                1, 1, 30, 1, new BigDecimal("10.00"), "IT-TERMS-1", 1L);
        return 501L;
    }

    private long insertGiftOrder(long id, long memberId, long productId, String orderNo) {
        db.update("INSERT INTO marketing_product_order (id,order_no,member_id,product_id,product_revision,"
                        + "fulfillment_kind,gift_recipient_member_id,pay_mode,quantity,total_cash_amount,currency,"
                        + "product_snapshot_json,quote_sign,quote_expires_at,status,request_id,"
                        + "request_fingerprint,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now())",
                id, orderNo, memberId, productId, 1L, 2, null, 1, 1, new BigDecimal("10.00"), "CNY",
                "{\"it\":1}", "s".repeat(64), secondsFromNow(3600L), 1, "req-" + orderNo, "f".repeat(64));
        return id;
    }

    private void insertGift(long orderId, long buyer, long productId, long sourceRegistrationId, String publicId,
                            String token, Timestamp claimExpiresAt, int status, Long recipient, Long claimedBy,
                            Timestamp claimedAt) {
        db.update("INSERT INTO gift_entitlement (public_id,order_id,product_id,source_registration_id,"
                        + "buyer_member_id,recipient_member_id,claim_token_hash,claim_expires_at,status,"
                        + "claimed_member_id,claimed_at,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,now(),now())",
                publicId, orderId, productId, sourceRegistrationId, buyer, recipient,
                MarketingClaimTokenCodec.sha256Hex(token), claimExpiresAt, status, claimedBy, claimedAt);
    }

    private long insertCredential(long id, String publicId, long sharer, String campaignKey, int targetType,
                                  long targetId) {
        db.update("INSERT INTO marketing_referral_credential (id,public_id,sharer_member_id,campaign_key,"
                        + "target_type,target_id,issued_at,expires_at,status,token_hash,create_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,now())",
                id, publicId, sharer, campaignKey, targetType, targetId,
                // uk_referral_token_hash 是唯一键：两个夹具凭据不能共用同一摘要。
                secondsFromNow(-3600L), secondsFromNow(30L * 24 * 3600), 0,
                MarketingClaimTokenCodec.sha256Hex(publicId));
        return id;
    }

    private void insertAttribution(long id, long orderId, long credentialId, long sharer, String campaignKey) {
        db.update("INSERT INTO marketing_attribution (id,order_type,order_id,credential_id,sharer_member_id,"
                        + "campaign_key,claimed_member_id,order_created_at,snapshot_hash,create_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,now())",
                id, 1, orderId, credentialId, sharer, campaignKey, sharer,
                secondsFromNow(-7200L), "b".repeat(64));
    }

    private void insertRegistration(long id, long memberId, long topicId, int paymentStatus, String payable,
                                    int registrationStatus) {
        db.update("INSERT INTO cms_registration (id,registration_no,owner_type,owner_id,ticket_id,ticket_price,"
                        + "member_id,original_amount,total_amount,payable_amount,payment_status,"
                        + "registration_status,pay_expire_time,topic_id,create_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())",
                id, "MKR-" + id, 1, topicId, 1L, new BigDecimal(payable), memberId,
                new BigDecimal(payable), new BigDecimal(payable), new BigDecimal(payable),
                paymentStatus, registrationStatus, secondsFromNow(3600L), topicId);
    }

    private void insertRule() {
        db.update("INSERT INTO marketing_revenue_rule (id,rule_key,version,scenario,currency,author_base_rate,"
                        + "platform_base_rate,referral_rate,referral_funding_side,contract_ref,payee_member_id,"
                        + "hold_days,status,create_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,now())",
                RULE_ID, "topic:" + TOPIC_ID + ":s1", 1, 3, "CNY",
                new BigDecimal("0.80000000"), new BigDecimal("0.20000000"), new BigDecimal("0.10000000"),
                1, "CT-IT-1", AUTHOR, 14, 0);
    }

    private void insertAllocation(long id, long registrationId, long paidFen, long netFen, long reversedFen,
                                  int status) {
        db.update("INSERT INTO marketing_revenue_allocation (id,order_type,order_id,allocation_key,rule_id,"
                        + "rule_key,rule_version,scenario,sharer_member_id,campaign_key,author_member_id,"
                        + "contract_ref,owner_type,owner_id,currency,paid_fen,payment_fact_hash,fee_basis_ref,"
                        + "net_fen,referral_funding_side,status,reversed_fen,snapshot_hash,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now())",
                id, 1, registrationId, "registration:" + registrationId, RULE_ID,
                "topic:" + TOPIC_ID + ":s1", 1, 3, SHARER_A, CAMPAIGN_A, AUTHOR,
                "CT-IT-1", 1, TOPIC_ID, "CNY", paidFen, "p".repeat(64), "fee:it-1",
                netFen, 1, status, reversedFen, "d".repeat(64));
    }

    private long insertLine(long id, long allocationId, int entryType, String role, Long recipient, long basisFen,
                            String rate, long amountFen, String entryKey, String eventRef, Long eventAmountFen,
                            long eventOrderId) {
        boolean reverse = entryType == 2;
        db.update("INSERT INTO marketing_revenue_allocation_line (id,allocation_id,entry_type,line_role,"
                        + "recipient_member_id,funding_side,basis_fen,rate,amount_fen,currency,entry_key,"
                        + "event_ref,event_amount_fen,event_currency,event_order_type,event_order_id,"
                        + "reversal_of_line_id,source_fact,create_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())",
                id, allocationId, entryType, role, recipient, "referrer".equals(role) ? 1 : 0, basisFen,
                new BigDecimal(rate), amountFen, "CNY", entryKey, eventRef, eventAmountFen,
                reverse ? "CNY" : null, reverse ? 1 : null, reverse ? eventOrderId : null,
                reverse ? firstAccrueLineId(allocationId, role) : null, eventRef);
        return id;
    }

    /** 反向分录必须指向原分录（CHECK 强制）；同一 (分配,角色) 只有一条应计行。 */
    private Long firstAccrueLineId(long allocationId, String role) {
        List<Long> ids = db.queryForList("SELECT id FROM marketing_revenue_allocation_line "
                + "WHERE allocation_id = ? AND line_role = ? AND entry_type = 1", Long.class, allocationId, role);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 秒级对齐：DATETIME 无小数秒，带毫秒的时间戳写库再读回会不等。 */
    private static Timestamp secondsFromNow(long seconds) {
        return new Timestamp((System.currentTimeMillis() / 1000L + seconds) * 1000L);
    }

    /**
     * DATETIME 只有秒精度，且 Connector/J 会按 serverTimezone 往返换算。断言"同一秒"而不是
     * 毫秒相等，仍能抓住"按当前时间重算"或时区偏移这类真实缺陷。
     */
    private static void assertSameSecond(Timestamp expected, java.util.Date actual, String message) {
        assertNotNull(actual, message);
        long drift = Math.abs(expected.getTime() - actual.getTime());
        assertTrue(drift <= 1000L, message + "（偏差 " + drift + "ms）");
    }

    /** 非静态实例字段名集合：用来证明投影类型不会多出买家/订单/领取人字段。 */
    private static Set<String> instanceFieldNames(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
            names.add(field.getName());
        }
        return names;
    }
}
