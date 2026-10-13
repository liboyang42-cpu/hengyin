package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权益账本 mapper 的 SQL 守卫:解析真 XML 生成 BoundSql,**不连库**。
 *
 * <p>为什么必须有这个类:{@code PerkLedgerServiceTest} 里 offerMapper 是 mock,
 * 它「按什么条件过滤」完全是我假设的 —— SQL 少一个条件那边照样全绿。而这组表里
 * **少条件就等于资损**,不是风格问题:
 * <ul>
 *   <li>「生效」是两条轴(status=1 且 valid_end 未过期),valid_end 到期**不会**自动翻 status。
 *       裸写 status=1 会把过期供给算进容量 —— 真库实测差 10 份 = 超卖 10 张票。
 *       ⚠️ 这不是假想:本批代码初版的四个查询就全漏了这条轴。</li>
 *   <li>consumeQuota 必须是**条件 update**(够扣才扣),换成读判就没有防超发这回事。</li>
 *   <li>核销/退款的 CAS 必须带 status 前置条件,否则并发下「钱退了货照发」。</li>
 * </ul>
 *
 * ⚠️ 负控已实测:从 liveOffer 里删掉 valid_end 那半句 ⇒ 三条 live* 用例红;
 *    把 consumeQuota 的 quota_used &lt; quota_total 删掉 ⇒ consumeQuota_isConditional 红;
 *    把 markRedeemed 的 status=0 删掉 ⇒ markRedeemed_requiresPendingStatus 红。
 */
class PerkLedgerMapperSqlTest {

    private static final String OFFER = "mapper/business/MerchantChapterOfferMapper.xml";
    private static final String ENT = "mapper/business/RegistrationEntitlementMapper.xml";
    private static final String RED = "mapper/business/RedemptionMapper.xml";
    private static final String CHAPTER = "mapper/business/CmsTopicChapterMapper.xml";

    private static final String NS_OFFER = "com.chengyinhub.business.mapper.MerchantChapterOfferMapper.";
    private static final String NS_ENT = "com.chengyinhub.business.mapper.RegistrationEntitlementMapper.";
    private static final String NS_RED = "com.chengyinhub.business.mapper.RedemptionMapper.";
    private static final String NS_CHAPTER = "com.chengyinhub.business.mapper.CmsTopicChapterMapper.";

    private Configuration parse(String resource, Class<?> alias) throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias(alias.getSimpleName(), alias);
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        return cfg;
    }

    private String sql(Configuration cfg, String statementId, Object param) {
        return cfg.getMappedStatement(statementId)
                .getBoundSql(param)
                .getSql()
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    private String offerSql(String stmt, Object param) throws Exception {
        return sql(parse(OFFER, MerchantChapterOffer.class), NS_OFFER + stmt, param);
    }

    /** 「生效」两条轴都在(status + valid_end)。 */
    private void assertLiveOfferPredicate(String s, String stmt) {
        assertTrue(s.contains("status = 1"), stmt + " 缺 status 轴。实际: " + s);
        assertTrue(s.contains("valid_end is null") && s.contains("valid_end >"),
                stmt + " **缺 valid_end 轴** —— 过期供给会被算进容量,直接超卖。实际: " + s);
    }

    // ------------------------------------------------- 生效两条轴

    @Test
    void liveOffer_sumRemainingQuota_filtersBothAxes() throws Exception {
        assertLiveOfferPredicate(offerSql("sumRemainingQuotaByChapter", 1L), "sumRemainingQuotaByChapter");
    }

    @Test
    void liveOffer_selectByChapter_filtersBothAxes() throws Exception {
        assertLiveOfferPredicate(offerSql("selectByChapter", 1L), "selectByChapter");
    }

    @Test
    void liveOffer_selectByTopic_filtersBothAxes() throws Exception {
        assertLiveOfferPredicate(offerSql("selectByTopic", 1L), "selectByTopic");
    }

    // ------------------------------------------------- 当前读 vs 快照读(防超卖的真防线)

    /**
     * **购票容量校验用的两个聚合必须是当前读(FOR SHARE)**。
     *
     * <p>这条是防超卖的真防线,不是行锁。真库实测(供给 1 座 / 2 并发买家):
     * 用普通读时 B 在章节行锁上真阻塞 0.81s,但醒来后 SUM/COUNT 仍走旧 read view
     * (RR 下 read view 在事务第一次普通读时建立 —— reserveEntitlements 一进来的
     * selectRequiredByTopic 就把它建了)⇒ 读到 held=0 ⇒ 算出同样的容量 ⇒ 双双插入 ⇒ sold=2。
     * 压测 8 买家 / 3 座:**8 人全买进、0 拒绝、0 报错、零告警**。改成 FOR SHARE 后:1 座卖 1,
     * 3 座 8 人恰好 3 进 5 拒、0 死锁。
     */
    @Test
    void capacityAggregates_forUpdateVariants_useCurrentRead() throws Exception {
        String offer = offerSql("sumRemainingQuotaByChapterForUpdate", 1L);
        assertTrue(offer.contains("for share"),
                "购票容量的 Σ 必须走当前读,否则行锁形同虚设、必超卖。实际: " + offer);
        assertLiveOfferPredicate(offer, "sumRemainingQuotaByChapterForUpdate");

        Configuration cfg = parse(ENT, RegistrationEntitlement.class);
        String ent = sql(cfg, NS_ENT + "countUnredeemedByChapterForUpdate", 1L);
        assertTrue(ent.contains("for share"),
                "预占计数同样必须当前读 —— 两个聚合缺一个就漏。实际: " + ent);
        assertTrue(ent.contains("status = 0"), "实际: " + ent);
    }

    /**
     * 只读展示路径的那两个**不能**带 FOR SHARE —— 否则每次浏览主题都上 S 锁。
     * 两个版本必须并存,别图省事合成一个。
     */
    @Test
    void capacityAggregates_plainVariants_stayLockFree() throws Exception {
        String offer = offerSql("sumRemainingQuotaByChapter", 1L);
        assertFalse(offer.contains("for share") || offer.contains("for update"),
                "只读展示用的 Σ 不该上锁(topicSellableCapacity/chapterCoverageRatio 共用它)。实际: " + offer);

        Configuration cfg = parse(ENT, RegistrationEntitlement.class);
        String ent = sql(cfg, NS_ENT + "countUnredeemedByChapter", 1L);
        assertFalse(ent.contains("for share") || ent.contains("for update"), "实际: " + ent);
    }

    // ------------------------------------------------- 配额原子性

    /** 防超发的唯一防线:够扣才扣的条件 update。换成读判 = 并发双双通过判断各自 +1。 */
    @Test
    void consumeQuota_isConditional() throws Exception {
        String s = offerSql("consumeQuota", 1L);
        assertTrue(s.contains("quota_used = quota_used + 1"), "必须是原地自增,不能是读出来再写回。实际: " + s);
        assertTrue(s.contains("quota_used < quota_total"),
                "必须够扣才扣 —— 少了这条就没有防超发这回事。实际: " + s);
        assertLiveOfferPredicate(s, "consumeQuota");
    }

    /** 撤销回补有下限,防重复撤销把已用份数扣成负数(凭空多配额)。 */
    @Test
    void releaseQuota_hasFloor() throws Exception {
        String s = offerSql("releaseQuota", 1L);
        assertTrue(s.contains("quota_used = quota_used - 1"), "实际: " + s);
        assertTrue(s.contains("quota_used > 0"),
                "必须有下限,否则重复撤销把 quota_used 扣成负数。实际: " + s);
    }

    // ------------------------------------------------- entitlement 状态机

    /** 核销抢占必须带 status=0 前置条件 —— 这一行是核销与退款的唯一序列化点。 */
    @Test
    void markRedeemed_requiresPendingStatus() throws Exception {
        Configuration cfg = parse(ENT, RegistrationEntitlement.class);
        Map<String, Object> p = new HashMap<String, Object>();
        p.put("registrationId", 1L);
        p.put("chapterId", 2L);

        String s = sql(cfg, NS_ENT + "markRedeemed", p);
        assertTrue(s.contains("status = 1"), "必须置已核销。实际: " + s);
        assertTrue(s.contains("status = 0"),
                "**必须带 status=0 前置条件** —— 少了它,并发退款置的失效态会被覆盖回已核销 = 钱退了货照发。实际: " + s);
    }

    /** 退款失效只动未核销的:已核销的玩家已拿到手,改失效会让容量凭空多一份。 */
    @Test
    void invalidateByRegistration_onlyTouchesPending() throws Exception {
        Configuration cfg = parse(ENT, RegistrationEntitlement.class);
        Map<String, Object> p = new HashMap<String, Object>();
        p.put("registrationId", 1L);
        p.put("reason", "REFUND");

        String s = sql(cfg, NS_ENT + "invalidateByRegistration", p);
        assertTrue(s.contains("status = 2"), "实际: " + s);
        assertTrue(s.contains("status = 0"), "必须只失效应得态。实际: " + s);
        assertTrue(s.contains("invalid_reason"),
                "必须写失效原因,否则商家对账答不出「我这章为什么少了预占」。实际: " + s);
    }

    /** 撤销后退回应得态,且只动已核销的(并发退款下不得把已退款的票改回占容量)。 */
    @Test
    void restoreToPending_onlyTouchesRedeemed() throws Exception {
        Configuration cfg = parse(ENT, RegistrationEntitlement.class);
        Map<String, Object> p = new HashMap<String, Object>();
        p.put("registrationId", 1L);
        p.put("chapterId", 2L);

        String s = sql(cfg, NS_ENT + "restoreToPending", p);
        assertTrue(s.contains("status = 0"), "实际: " + s);
        assertTrue(s.contains("status = 1"), "必须只动已核销态。实际: " + s);
    }

    /** 容量预占只数应得态。 */
    @Test
    void countUnredeemedByChapter_countsOnlyPending() throws Exception {
        Configuration cfg = parse(ENT, RegistrationEntitlement.class);
        String s = sql(cfg, NS_ENT + "countUnredeemedByChapter", 1L);
        assertTrue(s.contains("status = 0"), "预占 = 已售未核销,只数 status=0。实际: " + s);
    }

    // ------------------------------------------------- redemption

    /** 生成列绝不能出现在 insert 里(写生成列 = 3105)。 */
    @Test
    void insertRedemption_neverWritesGeneratedColumn() throws Exception {
        Configuration cfg = parse(RED, Redemption.class);
        Redemption r = new Redemption();
        r.setRegistrationId(1L);
        r.setChapterId(2L);
        r.setTopicId(3L);
        r.setMerchantId(4L);
        r.setOperatorId(5L);
        r.setStatus("ACTIVE");
        r.setIdempotencyKey("redeem:1:2:4");

        String s = sql(cfg, NS_RED + "insertRedemption", r);
        assertFalse(s.contains("active_key"), "active_key 是生成列,写它必 3105。实际: " + s);
        assertTrue(s.contains("idempotency_key"),
                "幂等键是 NOT NULL 列,不写必 1364。实际: " + s);
    }

    /** 撤销必须条件置位,0 行 = 已撤销(否则重复撤销各回补一次配额)。 */
    @Test
    void reverse_isConditional() throws Exception {
        Configuration cfg = parse(RED, Redemption.class);
        Map<String, Object> p = new HashMap<String, Object>();
        p.put("id", 1L);
        p.put("reason", "误扫");

        String s = sql(cfg, NS_RED + "reverse", p);
        assertTrue(s.contains("'reversed'"), "实际: " + s);
        assertTrue(s.contains("'active'"),
                "必须带 status='ACTIVE' 前置条件,0 行即已撤销。实际: " + s);
    }

    /** ③ 通关判定只数必选章(可选章核销了也不该算进通关)。 */
    @Test
    void countActiveRequiredChaptersRedeemed_joinsRequiredOnly() throws Exception {
        Configuration cfg = parse(RED, Redemption.class);
        Map<String, Object> p = new HashMap<String, Object>();
        p.put("registrationId", 1L);
        p.put("topicId", 2L);

        String s = sql(cfg, NS_RED + "countActiveRequiredChaptersRedeemed", p);
        assertTrue(s.contains("required = 1"), "必须只数必选章。实际: " + s);
        assertTrue(s.contains("'active'"), "必须只数生效核销。实际: " + s);
        assertTrue(s.contains("distinct"), "同章多条流水不能重复计数。实际: " + s);
    }

    // ------------------------------------------------- 章节锁

    /** 购票序列化点:必须真的 for update,且 order by id(与调用方升序入参一起防 ABBA)。 */
    @Test
    void lockChaptersForCapacity_isForUpdateOrdered() throws Exception {
        Configuration cfg = parse(CHAPTER, CmsTopicChapter.class);
        Map<String, Object> p = new HashMap<String, Object>();
        p.put("ids", Arrays.asList(1L, 2L));

        String s = sql(cfg, NS_CHAPTER + "lockChaptersForCapacity", p);
        assertTrue(s.contains("for update"),
                "**必须真加锁** —— 容量是跨两表聚合,不锁必幻读超卖。实际: " + s);
        assertTrue(s.contains("order by id"), "必须定序,防 ABBA 死锁。实际: " + s);
    }

    /** 必选章查询按 required=1。 */
    @Test
    void selectRequiredByTopic_filtersRequired() throws Exception {
        Configuration cfg = parse(CHAPTER, CmsTopicChapter.class);
        String s = sql(cfg, NS_CHAPTER + "selectRequiredByTopic", 1L);
        assertTrue(s.contains("required = 1"), "实际: " + s);
    }

    // ------------------------------------------------- 条款档列必须真的被 select 出来
    //
    // 为什么这三条必须在**这个**类里,而不是在 PerkLedgerServiceTest:
    // 两个 mapper 都是**显式列清单**(不是 select *)。resultMap 配了映射、但 selectVo 的列清单
    // 漏了那一列 ⇒ 查出来的对象该字段**静默为 null**,不报错、不炸。而 service 层单测里
    // mapper 是 mock,返回什么由我自己 stub —— 它对「SQL 到底 select 没 select 这列」完全失明。
    // CmsTopicChapterMapper.xml 的 selectVo 上方注释早已写明这个坑:
    //   「显式列清单(非 select *):新列不加在这里就查不出来,resultMap 配了也是空值」
    //
    // 后果具体是什么:redeemChapter 按 chapter.terms_mode 判档、按 offer.per_head_fee 计酬,
    // 这两列若恒 null ⇒ 档位判定全落到 null 分支,计酬金额取不到 —— 与「没接线」等价,且零告警。
    //
    // 负控已实测:从 selectMerchantChapterOfferVo 的列清单里删掉 terms_mode
    //   ⇒ offerVo_selectsTermsColumns 红;从 selectCmsTopicChapterVo 删掉 terms_mode
    //   ⇒ chapterVo_selectsTermsMode 红。

    @Test
    void offerVo_selectsTermsColumns() throws Exception {
        for (String stmt : new String[] {"selectById", "selectByChapter", "selectByTopic"}) {
            String s = offerSql(stmt, 1L);
            assertTrue(s.contains("terms_mode"),
                    stmt + " 的列清单缺 terms_mode ⇒ offer.termsMode 恒 null,档位判定失明。实际: " + s);
            assertTrue(s.contains("per_head_fee"),
                    stmt + " 的列清单缺 per_head_fee ⇒ REVSHARE 计酬金额取不到。实际: " + s);
        }
    }

    @Test
    void chapterVo_selectsTermsMode() throws Exception {
        Configuration cfg = parse(CHAPTER, CmsTopicChapter.class);
        // 档位口径真源在章节;selectRequiredByTopic 是 redeemChapter/isExploreCompleted 的入口
        String s = sql(cfg, NS_CHAPTER + "selectRequiredByTopic", 1L);
        assertTrue(s.contains("terms_mode"),
                "selectRequiredByTopic 的列清单缺 terms_mode ⇒ 章节档位恒 null。实际: " + s);
    }

    // ------------------------------------------------- 撤销核销不得把已退款的票放回容量
    //
    // 负控已实测:从 restoreToPending 删掉 `r.payment_status = 2`
    //   ⇒ restoreToPending_refusesRefundedRegistration 红

    /**
     * ★ 撤销核销 → 权益退回应得态,但**已退款的票不许退回**。
     *
     * <p>为什么 {@code status = 1} 这道守卫不够:{@code invalidateByRegistration} 只动 status=0 的行,
     * 所以「已核销(1)之后又退款」的那一行**退款时根本没被碰过,仍是 1** ——
     * 从 entitlement 自己身上与「已核销未退款」完全无法区分。真实序列:
     * 核销 ch1(0→1)→ 退款(ch1 保持 1)→ 运营撤销该核销 → status=1 正好命中 → 置回 0
     * ⇒ 一张已退款的票重新占住容量,且 releaseOnRefund 不会再被调 ⇒ 永久占坑,零告警。
     * ⇒ 判据只能问 registration,且必须在**同一条 SQL 里** join(Java 层先查再更新有 TOCTOU)。
     */
    @Test
    void restoreToPending_refusesRefundedRegistration() throws Exception {
        Configuration cfg = parse(ENT, RegistrationEntitlement.class);
        Map<String, Object> p = new HashMap<String, Object>();
        p.put("registrationId", 1L);
        p.put("chapterId", 2L);
        String s = sql(cfg, NS_ENT + "restoreToPending", p);

        assertTrue(s.contains("status = 1"),
                "缺 status=1 ⇒ 并发下已退款(status=2)的行会被改回 0。实际: " + s);
        assertTrue(s.contains("cms_registration") && s.contains("payment_status = 2"),
                "**缺 registration 状态守卫** ⇒ 「核销后退款」的行仍是 status=1,"
                        + "撤销时会被放回容量且永不释放 = 永久占坑。实际: " + s);
    }

    /**
     * P0-50 计酬成本上界只数 REVSHARE + 只数必选章 + 生效两条轴。
     * 这条聚合是 Java 侧唯一读 terms_mode 的地方(手写 SQL,不走 resultMap),
     * 少任一条件都会把成本上界算小 ⇒ 必亏闸放行本该拒的定价。
     */
    @Test
    void sumMaxPerHeadFee_filtersRevshareRequiredAndLive() throws Exception {
        String s = offerSql("sumMaxPerHeadFeeByRequiredChapters", 1L);
        assertTrue(s.contains("'revshare'"), "缺 REVSHARE 过滤 ⇒ 会把不付钱的档也算进成本。实际: " + s);
        assertTrue(s.contains("required = 1"), "缺 required=1 ⇒ 可选章成本被当成必付。实际: " + s);
        assertLiveOfferPredicate(s, "sumMaxPerHeadFeeByRequiredChapters");
        assertTrue(s.contains("max(") && s.contains("group by"),
                "必须每章取 max 再求和:一张票每章只核销一次,该章最多付一份。实际: " + s);
    }
}
