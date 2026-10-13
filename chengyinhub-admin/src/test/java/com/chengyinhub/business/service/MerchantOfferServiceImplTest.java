package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.MerchantCircleSupplyProfile;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MerchantCircleSupplyProfileMapper;
import com.chengyinhub.business.service.impl.MerchantOfferServiceImpl;
import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家入驻章节(P0-12)—— merchant_chapter_offer 的唯一 insert 入口。
 *
 * <p><b>这个入口为什么是 ③ 的开卖前置</b>:在它之前 offer 表零 insert 入口 ⇒ 供给池恒空
 * ⇒ {@code sumRemainingQuotaByChapterForUpdate} 返 0 ⇒ capacity = 0 − held 恒 ≤0
 * ⇒ 任何有章节的 ③ 主题购票 100% 被拒(不是部分受限,是全站熔断)。
 *
 * <p>⚠️ 本类守的是**校验规则**(mock 能证明的部分)。mock 证明不了的:
 * <ul>
 *   <li>DDL 的 {@code ck_offer_terms} 是否真拦得住形状不合法的行 → 归真库;
 *       本层与它是同一套规则的两层,应用层先拒只为给人话错误,别让商家吃 3819。</li>
 *   <li>{@code uk_offer_merchant_chapter} 是否真拦重复入驻 → 归真库;
 *       本层只验「撞了唯一键要翻译成人话」。</li>
 * </ul>
 *
 * ⚠️ 负控已实测(注入→红→撤销→绿),见各用例上方标注。
 */
class MerchantOfferServiceImplTest {

    private static final Long CHAPTER = 33L;
    private static final Long TOPIC = 10L;
    private static final Long MERCHANT = 500L;
    private static final Long OTHER_MERCHANT = 999L;
    private static final Long PERK = 77L;
    // 同一用例里「提交的声明」与「回读的声明」都来自 circleSupply():各自 new Date() 会在跨整秒时
    // 被服务端 sameSecond 判成回读不一致(2026-09-23 master CI 偶发红,挡掉了部署)。一个用例共用一个截到秒的时间点。
    private final Date checkedAt = new Date(System.currentTimeMillis() / 1000 * 1000);

    private MerchantChapterOfferMapper offerMapper;
    private MerchantCircleSupplyProfileMapper circleSupplyMapper;
    private CmsTopicChapterMapper chapterMapper;
    private com.chengyinhub.business.mapper.CmsTopicMapper cmsTopicMapper;
    private CoopPerkMapper coopPerkMapper;
    private IProductRoleService productRoleService;
    private ICoopInviteService coopInviteService;
    private ICmsRegistrationMerchantService registrationMerchantService;
    private IMerchantChapterApplicationService merchantChapterApplicationService;
    private ISysCategoryService categoryService;
    private com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer scopeAuthorizer;
    private MerchantOfferServiceImpl svc;
    private com.chengyinhub.business.domain.CmsTopic stubTopic;

    @BeforeEach
    void setUp() {
        offerMapper = mock(MerchantChapterOfferMapper.class);
        circleSupplyMapper = mock(MerchantCircleSupplyProfileMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        cmsTopicMapper = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        coopPerkMapper = mock(CoopPerkMapper.class);
        productRoleService = mock(IProductRoleService.class);
        coopInviteService = mock(ICoopInviteService.class);
        registrationMerchantService = mock(ICmsRegistrationMerchantService.class);
        merchantChapterApplicationService = mock(IMerchantChapterApplicationService.class);
        when(merchantChapterApplicationService.approvedReplacementOfferId(anyLong(), anyLong())).thenReturn(null);
        categoryService = mock(ISysCategoryService.class);
        scopeAuthorizer = mock(com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer.class);
        when(scopeAuthorizer.canViewTopic(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        when(scopeAuthorizer.hasGlobalTopicScope()).thenReturn(true);
        when(cmsTopicMapper.clearCircleReviewedAt(anyLong(), any(Date.class))).thenReturn(1);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenAnswer(invocation -> stubTopic);

        svc = new MerchantOfferServiceImpl();
        ReflectionTestUtils.setField(svc, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(svc, "circleSupplyMapper", circleSupplyMapper);
        ReflectionTestUtils.setField(svc, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(svc, "coopPerkMapper", coopPerkMapper);
        ReflectionTestUtils.setField(svc, "productRoleService", productRoleService);
        ReflectionTestUtils.setField(svc, "coopInviteService", coopInviteService);
        ReflectionTestUtils.setField(svc, "registrationMerchantService", registrationMerchantService);
        ReflectionTestUtils.setField(svc, "merchantChapterApplicationService", merchantChapterApplicationService);
        ReflectionTestUtils.setField(svc, "sysCategoryService", categoryService);
        ReflectionTestUtils.setField(svc, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        when(categoryService.selectSysCategoryById(18L)).thenReturn(activeCategory());

        // 默认:调用方是本主题的中标商家 —— 让既有用例继续只测它们自己那道闸。
        // ⚠️ 各「无中标」用例用 givenWonBid(emptyList()) 显式覆盖,别靠默认值反推。
        givenWonBid(Collections.singletonList(new CmsRegistrationMerchant()));
    }

    /** 中标探针的返回值。空 list = 该会员在本主题没有中标报名。 */
    private void givenWonBid(java.util.List<CmsRegistrationMerchant> bids) {
        when(registrationMerchantService.selectCmsRegistrationMerchantList(any(CmsRegistrationMerchant.class)))
                .thenReturn(bids);
    }

    private CmsTopicChapter chapter(String mode, BigDecimal minValue, Integer maxMerchant) {
        CmsTopicChapter c = new CmsTopicChapter();
        c.setId(CHAPTER);
        c.setTopicId(TOPIC);
        c.setTermsMode(mode);
        c.setPerkMinValue(minValue);
        c.setMaxMerchant(maxMerchant);
        c.setRecruitEnabled(0); // migration 默认 0：存量章节继续走主题级中标闸
        c.setCategoryId(18);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(c);
        // 章节行锁(防 max_merchant 并发撑破);返非空 = 章节存在
        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER)))
                .thenReturn(Collections.singletonList(CHAPTER));
        // 只有 ③ 的章节能被入驻。⚠️ 主题走**行锁读**(ForUpdate):与 confirmPrice 串行,
        // 否则「锁价读阵容」与「enroll 插供给」之间有 TOCTOU 悬空窗。
        stubTopic = new com.chengyinhub.business.domain.CmsTopic();
        stubTopic.setId(TOPIC);
        stubTopic.setProductType(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
        stubTopic.setLifecycle(1);
        stubTopic.setRecruitDeadline(new Date(System.currentTimeMillis() + 60_000));
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(stubTopic);
        return c;
    }

    private SysCategory activeCategory() {
        SysCategory category = new SysCategory();
        category.setId(18L);
        category.setType(1L);
        category.setParentId(0L);
        category.setStatus(1);
        category.setDelFlag(0);
        return category;
    }

    /** 主题生命周期：默认 RECRUITING；显式覆盖用于验证冻结边界。 */
    private void givenTopicLifecycle(Integer lifecycle) {
        stubTopic.setLifecycle(lifecycle);
    }

    private void givenPerk(BigDecimal retailValue, Long owner) {
        CoopPerk p = new CoopPerk();
        p.setId(PERK);
        p.setRetailValue(retailValue);
        p.setQuota(50);
        when(coopPerkMapper.selectById(PERK)).thenReturn(p);
        when(coopInviteService.resolvePerkOwner(PERK)).thenReturn(owner);
    }

    private MerchantChapterOffer perkOffer() {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setTermsMode(TermsMode.PERK);
        o.setPerkId(PERK);
        o.setQuotaTotal(50);
        return o;
    }

    private MerchantChapterOffer revshareOffer() {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setTermsMode(TermsMode.REVSHARE);
        o.setPerHeadFee(new BigDecimal("15.00"));
        return o;
    }

    private MerchantCircleSupplyProfile circleSupply() {
        MerchantCircleSupplyProfile profile = new MerchantCircleSupplyProfile();
        profile.setSupplyName("正常在售晚间饮品");
        profile.setSupplyKind("PRODUCT");
        profile.setRegularPrice(new BigDecimal("29.00"));
        profile.setActionType("BUY");
        profile.setActionValue("https://merchant.example/item/1");
        profile.setAvailableHours("工作日18:00—22:00");
        profile.setMinPeople(1);
        profile.setMaxPeople(4);
        profile.setCheckedAt(checkedAt);
        profile.setMerchantRules("沿用商家正常取消和退款规则");
        profile.setWorkdayEveningCovered(1);
        return profile;
    }

    private MerchantChapterOffer circleTrafficOffer() {
        MerchantChapterOffer offer = new MerchantChapterOffer();
        offer.setChapterId(CHAPTER);
        offer.setTermsMode(TermsMode.TRAFFIC);
        offer.setCircleSupplyProfile(circleSupply());
        return offer;
    }

    // ------------------------------------------------- 正控

    /** ★ 这条是「③ 熔断被解除」的证据:入驻成功 ⇒ 供给池里终于有行了。 */
    @Test
    void enroll_perk_insertsLiveOffer() {
        chapter(TermsMode.PERK, new BigDecimal("50.00"), 4);
        givenPerk(new BigDecimal("88.00"), MERCHANT);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());

        svc.enroll(perkOffer(), MERCHANT);

        ArgumentCaptor<MerchantChapterOffer> c = ArgumentCaptor.forClass(MerchantChapterOffer.class);
        verify(offerMapper).insertMerchantChapterOffer(c.capture());
        MerchantChapterOffer saved = c.getValue();
        assertEquals(TOPIC, saved.getTopicId(), "topicId 必须由章节反查,它是容量聚合的分组键");
        assertEquals(MERCHANT, saved.getMerchantId());
        assertEquals(Integer.valueOf(1), saved.getStatus(), "落库即生效");
        assertEquals(Integer.valueOf(0), saved.getQuotaUsed());
        assertEquals(Integer.valueOf(50), saved.getQuotaTotal());
    }

    /** REVSHARE:自营路线放行,quota_total 落 0(不走容量链)。 */
    @Test
    void enroll_revshare_platformOwned_quotaZero() {
        chapter(TermsMode.REVSHARE, null, 4).setMaxPerHeadFee(new BigDecimal("20.00"));
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());

        svc.enroll(revshareOffer(), MERCHANT);

        ArgumentCaptor<MerchantChapterOffer> c = ArgumentCaptor.forClass(MerchantChapterOffer.class);
        verify(offerMapper).insertMerchantChapterOffer(c.capture());
        assertEquals(Integer.valueOf(0), c.getValue().getQuotaTotal(),
                "非 PERK 档不走容量链,quota_total 落 0(consumeQuota 已按档位跳过它)");
        assertEquals(0, new BigDecimal("15.00").compareTo(c.getValue().getPerHeadFee()));
    }

    /**
     * 开放招商的权益来自模板快照，offer 的生效期必须跟随快照，不能信任客户端自报一个更晚日期。
     */
    @Test
    void enroll_recruitmentPerkCopiesSnapshotValidEnd() {
        CmsTopicChapter chapter = chapter(TermsMode.PERK, null, 4);
        chapter.setRecruitEnabled(1);
        when(merchantChapterApplicationService.hasApprovedApplication(CHAPTER, MERCHANT)).thenReturn(true);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());
        when(coopInviteService.materializeStandalonePerkFromTemplate(9L, MERCHANT, 50)).thenReturn(PERK);

        Date snapshotValidEnd = new Date(2_000_000_000_000L);
        CoopPerk snapshot = new CoopPerk();
        snapshot.setId(PERK);
        snapshot.setRetailValue(new BigDecimal("88.00"));
        snapshot.setQuota(50);
        snapshot.setValidEnd(snapshotValidEnd);
        when(coopPerkMapper.selectById(PERK)).thenReturn(snapshot);
        when(coopInviteService.resolvePerkOwner(PERK)).thenReturn(MERCHANT);

        MerchantChapterOffer offer = new MerchantChapterOffer();
        offer.setChapterId(CHAPTER);
        offer.setTermsMode(TermsMode.PERK);
        offer.setPerkTemplateId(9L);
        offer.setQuotaTotal(50);
        offer.setValidEnd(new Date(2_100_000_000_000L)); // 客户端试图拉长有效期

        svc.enroll(offer, MERCHANT);

        ArgumentCaptor<MerchantChapterOffer> cap = ArgumentCaptor.forClass(MerchantChapterOffer.class);
        verify(offerMapper).insertMerchantChapterOffer(cap.capture());
        assertEquals(snapshotValidEnd, cap.getValue().getValidEnd());
    }

    // ------------------------------------------------- 二清边界
    //
    // 负控:去掉 assertTermsShape 里的 isPlatformOwned 判断 ⇒ 本例红。

    /**
     * ★ 计酬档只对 ③-自营 开放。③-第三方 的计酬 = 第三方之间分钱 = 二清,必须等收付通(P0-43)。
     * isPlatformOwned 本身 fail-closed(查不到 CONTENT 槽即 false)⇒ 存量主题一律拒。
     */
    @Test
    void enroll_revshare_thirdParty_rejected() {
        chapter(TermsMode.REVSHARE, null, 4);
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(false);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.enroll(revshareOffer(), MERCHANT));
        assertTrue(e.getMessage().contains("俱乐部直付主体"));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    @Test
    void clubDirectRevshareCapturesServerPayerRatherThanClientValue() {
        CmsTopicChapter c = chapter(TermsMode.REVSHARE, null, 4);
        c.setMaxPerHeadFee(new BigDecimal("20.00"));
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(stubTopic);
        com.chengyinhub.business.domain.ProductRole role = new com.chengyinhub.business.domain.ProductRole();
        role.setEntityType("CLUB"); role.setEntityId(61L);
        when(productRoleService.activeContentSlot(TOPIC)).thenReturn(role);
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(false);
        c.setRecruitEnabled(1);
        when(categoryService.selectSysCategoryById(18L)).thenReturn(activeCategory());
        when(merchantChapterApplicationService.hasApprovedApplication(CHAPTER, MERCHANT)).thenReturn(true);
        MerchantChapterOffer direct = revshareOffer(); direct.setPayerClubId(999L);
        svc.enroll(direct, MERCHANT);
        assertEquals(61L, direct.getPayerClubId());
    }

    // ------------------------------------------------- 档必须与章节一致

    @Test
    void enroll_modeMismatchWithChapter_rejected() {
        chapter(TermsMode.PERK, null, 4);

        // 章节是权益档,却想按计酬档入驻
        assertThrows(IllegalStateException.class, () -> svc.enroll(revshareOffer(), MERCHANT));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    @Test
    void enroll_invalidMode_rejected() {
        chapter(TermsMode.PERK, null, 4);
        MerchantChapterOffer o = perkOffer();
        o.setTermsMode("FREEBIE");
        assertThrows(IllegalStateException.class, () -> svc.enroll(o, MERCHANT));
    }

    // ------------------------------------------------- 入驻门槛
    //
    // 负控:去掉 assertPerkMeetsThreshold 的 retail.compareTo(min) 判断 ⇒ belowThreshold 红。

    /** ★ 门槛比的是 retail_value(零售标价),不是 unit_cost(成本价)—— 拿成本价当门槛会让高毛利商家轻松过线。 */
    @Test
    void enroll_perkBelowThreshold_rejected() {
        chapter(TermsMode.PERK, new BigDecimal("50.00"), 4);
        givenPerk(new BigDecimal("20.00"), MERCHANT);   // 零售价 20 < 门槛 50

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.enroll(perkOffer(), MERCHANT));
        assertTrue(e.getMessage().contains("50"));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    /**
     * ★ retail_value 为 NULL 必须拒,不能放过。
     * attachPerks 曾漏抄 setRetailValue ⇒ 存量 coop_perk 的 retail_value 恒 NULL。那个 bug 已修,
     * 但**存量行不会自愈** ⇒ 放过就等于「没报零售价也能进供给池」,设计意图被静默绕开。
     */
    @Test
    void enroll_perkWithNullRetailValue_rejected() {
        chapter(TermsMode.PERK, new BigDecimal("50.00"), 4);
        givenPerk(null, MERCHANT);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.enroll(perkOffer(), MERCHANT));
        assertTrue(e.getMessage().contains("零售价"));
    }

    /** 章节不设门槛(perk_min_value 为空)⇒ 零售价多少都放行,但仍要求非空归属正确。 */
    @Test
    void enroll_noThreshold_passes() {
        chapter(TermsMode.PERK, null, 4);
        givenPerk(new BigDecimal("88.00"), MERCHANT);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());

        svc.enroll(perkOffer(), MERCHANT);
        verify(offerMapper).insertMerchantChapterOffer(any());
    }

    // ------------------------------------------------- IDOR
    //
    // 负控:去掉 resolvePerkOwner 的归属判断 ⇒ 本例红。

    /**
     * ★ 只能用自己申报的权益入驻。
     * 不校验的话商家 A 能挑商家 B 那份 88 元权益去过门槛,自己端便宜货 —— 门槛形同虚设,
     * 且核销时端出来的东西与承诺不符。coop_perk 没存 merchantId,归属经 invite 受邀方反查。
     */
    @Test
    void enroll_perkOfAnotherMerchant_rejected() {
        chapter(TermsMode.PERK, new BigDecimal("50.00"), 4);
        givenPerk(new BigDecimal("88.00"), OTHER_MERCHANT);   // 零售价够,但不是我的

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.enroll(perkOffer(), MERCHANT));
        assertTrue(e.getMessage().contains("自己"));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    // ------------------------------------------------- 承接商家数上限
    //
    // 负控:去掉 assertMaxMerchant 调用 ⇒ 本例红。

    /** ★ max_merchant 库侧无任何约束 ⇒ 不在应用层数就等于无限入驻。 */
    @Test
    void enroll_chapterFull_rejected() {
        chapter(TermsMode.PERK, null, 2);
        givenPerk(new BigDecimal("88.00"), MERCHANT);
        when(offerMapper.selectByChapter(CHAPTER))
                .thenReturn(Arrays.asList(new MerchantChapterOffer(), new MerchantChapterOffer()));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.enroll(perkOffer(), MERCHANT));
        assertTrue(e.getMessage().contains("已满"));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    /**
     * 数的是**生效** offer:selectByChapter 已含 liveOffer 两条轴(status=1 且 valid_end 未过期)。
     * 若换成裸数 status=1,过期供给会占着名额,让本该能进的商家进不来。
     */
    @Test
    void enroll_maxMerchantCountsLiveOffersOnly() {
        chapter(TermsMode.PERK, null, 4);
        givenPerk(new BigDecimal("88.00"), MERCHANT);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());

        svc.enroll(perkOffer(), MERCHANT);

        verify(offerMapper).selectByChapter(CHAPTER);   // 走的是含生效两条轴的那个查询
        verify(offerMapper).insertMerchantChapterOffer(any());
    }

    /** 历史脏数据也不能把 -1 解释成“不限”，否则修掉写入入口仍会留下可被入驻绕过的章节。 */
    @Test
    void enroll_rejectsInvalidLegacyMaxMerchant() {
        chapter(TermsMode.PERK, null, -1);
        givenPerk(new BigDecimal("88.00"), MERCHANT);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.enroll(perkOffer(), MERCHANT));

        assertTrue(error.getMessage().contains("0到127"));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    // ------------------------------------------------- 形状

    @Test
    void enroll_perkWithoutQuota_rejected() {
        chapter(TermsMode.PERK, null, 4);
        givenPerk(new BigDecimal("88.00"), MERCHANT);
        MerchantChapterOffer o = perkOffer();
        o.setQuotaTotal(0);   // 一份都核销不了,却占着 max_merchant 的坑

        assertThrows(IllegalStateException.class, () -> svc.enroll(o, MERCHANT));
    }

    @Test
    void enroll_perkWithPerHeadFee_rejected() {
        chapter(TermsMode.PERK, null, 4);
        MerchantChapterOffer o = perkOffer();
        o.setPerHeadFee(new BigDecimal("15.00"));   // 与 DDL ck_offer_terms 同口径:非 REVSHARE 必须为空

        assertThrows(IllegalStateException.class, () -> svc.enroll(o, MERCHANT));
    }

    @Test
    void enroll_revshareWithPerk_rejected() {
        chapter(TermsMode.REVSHARE, null, 4);
        MerchantChapterOffer o = revshareOffer();
        o.setPerkId(PERK);   // 非 PERK 档必须 perk_id IS NULL

        assertThrows(IllegalStateException.class, () -> svc.enroll(o, MERCHANT));
    }

    @Test
    void enroll_revshareWithoutFee_rejected() {
        chapter(TermsMode.REVSHARE, null, 4);
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
        MerchantChapterOffer o = revshareOffer();
        o.setPerHeadFee(null);

        assertThrows(IllegalStateException.class, () -> svc.enroll(o, MERCHANT));
    }

    @Test
    void enroll_traffic_noPerkNoFee_passes() {
        chapter(TermsMode.TRAFFIC, null, 4);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setTermsMode(TermsMode.TRAFFIC);

        svc.enroll(o, MERCHANT);

        ArgumentCaptor<MerchantChapterOffer> c = ArgumentCaptor.forClass(MerchantChapterOffer.class);
        verify(offerMapper).insertMerchantChapterOffer(c.capture());
        assertEquals(Integer.valueOf(0), c.getValue().getQuotaTotal());
        // 引流档不需要自营校验:它零结算,不涉及平台掏钱,没有二清问题
        verify(productRoleService, never()).isPlatformOwned(anyLong());
    }

    @Test
    void enroll_circleCandidate_withoutMerchantSupplyFacts_rejectedBeforeOfferInsert() {
        CmsTopicChapter c = chapter(TermsMode.TRAFFIC, null, 4);
        c.setCircleCandidateCode("A1");
        c.setCirclePolicy("WORKDAY_EVENING");
        c.setCircleEnabled(1);
        stubTopic.setCircleThemeCode("AFTER_WORK");
        MerchantChapterOffer offer = new MerchantChapterOffer();
        offer.setChapterId(CHAPTER);
        offer.setTermsMode(TermsMode.TRAFFIC);

        assertThrows(IllegalStateException.class, () -> svc.enroll(offer, MERCHANT));

        verify(offerMapper, never()).insertMerchantChapterOffer(any());
        verify(circleSupplyMapper, never()).insert(any());
    }

    @Test
    void enroll_circleCandidate_insertsValidatedSnapshotBoundToGeneratedOfferId() {
        CmsTopicChapter c = chapter(TermsMode.TRAFFIC, null, 4);
        c.setCircleCandidateCode("A1");
        c.setCirclePolicy("WORKDAY_EVENING");
        c.setCircleEnabled(1);
        stubTopic.setCircleThemeCode("AFTER_WORK");
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());
        org.mockito.Mockito.doAnswer(invocation -> {
            ((MerchantChapterOffer) invocation.getArgument(0)).setId(321L);
            return 1;
        }).when(offerMapper).insertMerchantChapterOffer(any());
        when(circleSupplyMapper.insert(any())).thenReturn(1);
        MerchantChapterOffer offer = new MerchantChapterOffer();
        offer.setChapterId(CHAPTER);
        offer.setTermsMode(TermsMode.TRAFFIC);
        offer.setCircleSupplyProfile(circleSupply());

        svc.enroll(offer, MERCHANT);

        ArgumentCaptor<MerchantCircleSupplyProfile> snapshot =
                ArgumentCaptor.forClass(MerchantCircleSupplyProfile.class);
        verify(circleSupplyMapper).insert(snapshot.capture());
        assertEquals(Long.valueOf(321L), snapshot.getValue().getOfferId());
        verify(cmsTopicMapper).clearCircleReviewedAt(org.mockito.ArgumentMatchers.eq(TOPIC), any(Date.class));
    }

    @Test
    void enroll_circleCandidate_rejectsASecondCandidateForTheSameMerchantInOneInstance() {
        CmsTopicChapter c = chapter(TermsMode.TRAFFIC, null, 1);
        c.setCircleCandidateCode("A2");
        c.setCirclePolicy("WORKDAY_EVENING");
        c.setCircleEnabled(1);
        stubTopic.setCircleThemeCode("AFTER_WORK");
        MerchantChapterOffer existing = new MerchantChapterOffer();
        existing.setId(300L);
        existing.setTopicId(TOPIC);
        existing.setChapterId(32L);
        existing.setMerchantId(MERCHANT);
        existing.setStatus(1);
        when(offerMapper.selectByTopic(TOPIC)).thenReturn(Collections.singletonList(existing));

        assertThrows(IllegalStateException.class,
                () -> svc.enroll(circleTrafficOffer(), MERCHANT));

        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    @Test
    void reconfirmCircleSupplyRequiresOwnerAndCreatesAReadBackVerifiedVersion() {
        CmsTopicChapter c = chapter(TermsMode.TRAFFIC, null, 1);
        c.setCircleCandidateCode("A1");
        c.setCirclePolicy("WORKDAY_EVENING");
        c.setCircleEnabled(1);
        stubTopic.setCircleThemeCode("AFTER_WORK");
        MerchantChapterOffer persisted = new MerchantChapterOffer();
        persisted.setId(321L);
        persisted.setTopicId(TOPIC);
        persisted.setChapterId(CHAPTER);
        persisted.setMerchantId(MERCHANT);
        persisted.setStatus(1);
        when(offerMapper.selectById(321L)).thenReturn(persisted);
        MerchantCircleSupplyProfile current = circleSupply();
        current.setId(10L);
        current.setOfferId(321L);
        current.setVersionNo(1);
        current.setCurrent(1);
        MerchantCircleSupplyProfile readback = circleSupply();
        readback.setId(11L);
        readback.setOfferId(321L);
        readback.setVersionNo(2);
        readback.setCurrent(1);
        when(circleSupplyMapper.selectByOfferId(321L)).thenReturn(current, readback);
        when(circleSupplyMapper.retireCurrentSnapshot(321L)).thenReturn(1);
        when(circleSupplyMapper.insert(any())).thenReturn(1);

        MerchantCircleSupplyProfile replacement = circleSupply();
        svc.reconfirmCircleSupply(321L, replacement, MERCHANT);

        ArgumentCaptor<MerchantCircleSupplyProfile> snapshot =
                ArgumentCaptor.forClass(MerchantCircleSupplyProfile.class);
        verify(circleSupplyMapper).retireCurrentSnapshot(321L);
        verify(circleSupplyMapper).insert(snapshot.capture());
        assertEquals(Long.valueOf(321L), snapshot.getValue().getOfferId());
        assertEquals(Integer.valueOf(2), snapshot.getValue().getVersionNo());
        verify(cmsTopicMapper).clearCircleReviewedAt(org.mockito.ArgumentMatchers.eq(TOPIC), any(Date.class));

        assertThrows(IllegalStateException.class,
                () -> svc.reconfirmCircleSupply(321L, circleSupply(), OTHER_MERCHANT));
    }

    @Test
    void reconfirmCircleSupplyRejectsMaterialChangesUntilMerchantPausesTheOffer() {
        CmsTopicChapter c = chapter(TermsMode.TRAFFIC, null, 1);
        c.setCircleCandidateCode("A1");
        c.setCirclePolicy("WORKDAY_EVENING");
        c.setCircleEnabled(1);
        stubTopic.setCircleThemeCode("AFTER_WORK");
        MerchantChapterOffer persisted = new MerchantChapterOffer();
        persisted.setId(321L);
        persisted.setTopicId(TOPIC);
        persisted.setChapterId(CHAPTER);
        persisted.setMerchantId(MERCHANT);
        persisted.setStatus(1);
        when(offerMapper.selectById(321L)).thenReturn(persisted);
        MerchantCircleSupplyProfile current = circleSupply();
        current.setId(10L);
        current.setOfferId(321L);
        current.setVersionNo(1);
        current.setCurrent(1);
        when(circleSupplyMapper.selectByOfferId(321L)).thenReturn(current);
        MerchantCircleSupplyProfile changed = circleSupply();
        changed.setRegularPrice(new BigDecimal("99.00"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.reconfirmCircleSupply(321L, changed, MERCHANT));

        assertTrue(error.getMessage().contains("暂停"));
        verify(circleSupplyMapper, never()).retireCurrentSnapshot(321L);
        verify(circleSupplyMapper, never()).insert(any());
    }

    @Test
    void pausingOneSupplyKeepsTheExistingCityReviewForTheRemainingCountGate() {
        MerchantChapterOffer persisted = new MerchantChapterOffer();
        persisted.setId(321L);
        persisted.setTopicId(TOPIC);
        persisted.setMerchantId(MERCHANT);
        persisted.setStatus(1);
        when(offerMapper.selectById(321L)).thenReturn(persisted);
        when(circleSupplyMapper.selectByOfferId(321L)).thenReturn(circleSupply());
        when(offerMapper.deactivate(321L)).thenReturn(1);

        svc.deactivateOwnCircleSupply(321L, MERCHANT);

        verify(offerMapper).deactivate(321L);
        verify(cmsTopicMapper, never()).clearCircleReviewedAt(anyLong(), any(Date.class));
    }

    // ------------------------------------------------- 重复入驻

    /** uk_offer_merchant_chapter (chapter_id, merchant_id):撞唯一键要翻译成人话,不能吐 500。 */
    @Test
    void enroll_duplicate_translatesToFriendlyMessage() {
        chapter(TermsMode.PERK, null, 4);
        givenPerk(new BigDecimal("88.00"), MERCHANT);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());
        when(offerMapper.insertMerchantChapterOffer(any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_offer_merchant_chapter"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.enroll(perkOffer(), MERCHANT));
        assertTrue(e.getMessage().contains("重复"));
    }

    @Test
    void enroll_chapterNotFound_rejected() {
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> svc.enroll(perkOffer(), MERCHANT));
    }

    // ------------------------------------------------- 只有 ③ 的章节能入驻
    //
    // 负控:去掉 product_type 校验 ⇒ enroll_nonExploreTopic_rejected 红。

    /**
     * ★ ①/② 主题的章节不许入驻:reserveEntitlements 只对 purchase_kind==EXPLORE_PASS 触发
     * ⇒ ①② 的票不建 entitlement ⇒ 这行 offer 的 quota 永不消耗,纯占着 max_merchant 名额、
     * 还让 chapterCapacity 算出个没意义的数。生产 34 个存量章节**全属 ① 主题**,误操作空间不小。
     */
    @Test
    void enroll_nonExploreTopic_rejected() {
        chapter(TermsMode.PERK, null, 4);
        com.chengyinhub.business.domain.CmsTopic guided = new com.chengyinhub.business.domain.CmsTopic();
        guided.setId(TOPIC);
        guided.setProductType(com.chengyinhub.common.constant.ProductType.ORIENTEERING);  // ①
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(guided);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.enroll(perkOffer(), MERCHANT));
        assertTrue(e.getMessage().contains("自由定向"));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    /** product_type 为 null(存量未回填)同样拒 —— fail-closed,判不出就别放进供给池。 */
    @Test
    void enroll_nullProductType_rejected() {
        chapter(TermsMode.PERK, null, 4);
        com.chengyinhub.business.domain.CmsTopic legacy = new com.chengyinhub.business.domain.CmsTopic();
        legacy.setId(TOPIC);
        legacy.setProductType(null);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(legacy);

        assertThrows(IllegalStateException.class, () -> svc.enroll(perkOffer(), MERCHANT));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    // ------------------------------------------------- 并发上限
    //
    // 负控:去掉 lockChaptersForCapacity 调用 ⇒ enroll_takesChapterRowLockBeforeCounting 红。

    /**
     * ★ max_merchant 是读-判-写序列,必须在**章节行锁之内**:
     * 两个商家同时入驻会双双读到 n &lt; cap 然后各插一行,把上限撑破。
     * uk_offer_merchant_chapter 只挡「同一商家同一章重复」,挡不住这个;DDL 侧零约束兜底。
     * ⚠️ 复用 reserveEntitlements 用的同一把锁,锁序一致不引入 ABBA。
     */
    @Test
    void enroll_takesChapterRowLockBeforeCounting() {
        chapter(TermsMode.PERK, null, 4);
        givenPerk(new BigDecimal("88.00"), MERCHANT);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());

        svc.enroll(perkOffer(), MERCHANT);

        org.mockito.InOrder o = org.mockito.Mockito.inOrder(chapterMapper, offerMapper);
        o.verify(chapterMapper).lockChaptersForCapacity(Collections.singletonList(CHAPTER));
        o.verify(offerMapper).selectByChapter(CHAPTER);          // 数名额必须在锁之后
        o.verify(offerMapper).insertMerchantChapterOffer(any());
    }

    /** 行锁拿不到(章节不存在)⇒ 拒,不继续。 */
    @Test
    void enroll_chapterRowLockEmpty_rejected() {
        chapter(TermsMode.PERK, null, 4);
        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER)))
                .thenReturn(Collections.<Long>emptyList());

        assertThrows(IllegalStateException.class, () -> svc.enroll(perkOffer(), MERCHANT));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    // ------------------------------------------------- 入驻鉴权闸(P0-5)
    //
    // 关系凭证 = cms_registration_merchant.audit_status=1(中标),由后台 select() 用 CAS 置位。
    // 选它不选「已接受邀约」的理由(2026-07-17 拍板):
    //   ① createTopicByPublisher 自己的注释就写着「商家经【报名】进供给槽」—— enroll 填的正是供给槽;
    //   ② 中标只有后台运营能置(business:registrationmerchant 权限)⇒ 会员侧伪造不了;
    //   ③ coop_invite 的唯一 insert 入口在会员侧(ApiCoopController),③-自营 由平台后台发、
    //      没有会员发布者去发邀约 ⇒ 拿邀约当凭证会把 REVSHARE(只在自营开)整档锁死。
    //
    // ⚠️ 本层 mock 证明不了「audit_status=1 这个过滤真的写进了 SQL」—— 那归 mapper XML。
    // 所以下面**断言探针形状**(memberId/topicId/auditStatus/delFlag),它才是「待调配/落选/
    // 别人的中标」被挡住的真正依据;只 stub 一个空 list 去断言「拒了」是自证自话。

    /**
     * ★ 三档统一的洞:入驻前不校验调用方与该主题有任何关系。
     * 引流档此前**零闸**(只校验 perkId/perHeadFee 都空)⇒ 任何登录会员可入驻任意 ③ 章节、
     * 占满 max_merchant 挤掉真商家、并拿到核销权。
     */
    @Test
    void enroll_traffic_withoutWonBid_rejected() {
        chapter(TermsMode.TRAFFIC, null, 4);
        givenWonBid(Collections.<CmsRegistrationMerchant>emptyList());
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setTermsMode(TermsMode.TRAFFIC);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.enroll(o, MERCHANT));
        // ⚠️ 别只断言「抛了」:本方法里 assertTermsShape/assertMaxMerchant 抛的是同型异常,
        // 拆掉本闸会被它们接住 ⇒ 必须断言错到的是**这道**闸。
        assertTrue(e.getMessage().contains("未被选为"), "错误文案须指明是入驻鉴权闸,实际:" + e.getMessage());
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    /**
     * ★ 计酬档:此前只有 isPlatformOwned(topicId) —— 那校验的是**主题是不是自营**,不是调用方是谁。
     * 比引流档更值钱:引流档零结算,计酬档是**平台真金白银按人头付**。
     */
    @Test
    void enroll_revshare_withoutWonBid_rejected() {
        chapter(TermsMode.REVSHARE, null, 4);
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);   // 自营:原有的闸全放行
        givenWonBid(Collections.<CmsRegistrationMerchant>emptyList());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.enroll(revshareOffer(), MERCHANT));
        assertTrue(e.getMessage().contains("未被选为"), "错误文案须指明是入驻鉴权闸,实际:" + e.getMessage());
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    /** 权益档原有 IDOR 闸只管「权益是不是我的」,管不了「我跟这主题有没有关系」⇒ 同样要过本闸。 */
    @Test
    void enroll_perk_withoutWonBid_rejected() {
        chapter(TermsMode.PERK, null, 4);
        givenPerk(new BigDecimal("88.00"), MERCHANT);   // 权益确实是自己的:原有闸放行
        givenWonBid(Collections.<CmsRegistrationMerchant>emptyList());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.enroll(perkOffer(), MERCHANT));
        assertTrue(e.getMessage().contains("未被选为"), "错误文案须指明是入驻鉴权闸,实际:" + e.getMessage());
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    /**
     * V1 正控：章节显式开放招商后，已通过本章申请即可入驻，不再强依赖主题级中标记录。
     * 去掉 assertEnrollmentAuthorized 的新授权分支，此例会因无中标而变红。
     */
    @Test
    void enroll_recruitEnabledChapter_withApprovedApplication_passesWithoutWonBid() {
        CmsTopicChapter c = chapter(TermsMode.TRAFFIC, null, 4);
        c.setRecruitEnabled(1);
        givenWonBid(Collections.<CmsRegistrationMerchant>emptyList());
        when(merchantChapterApplicationService.hasApprovedApplication(CHAPTER, MERCHANT)).thenReturn(true);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setTermsMode(TermsMode.TRAFFIC);

        svc.enroll(o, MERCHANT);

        verify(merchantChapterApplicationService).hasApprovedApplication(CHAPTER, MERCHANT);
        verify(merchantChapterApplicationService).assertCurrentMerchantEligibility(c, MERCHANT);
        verify(registrationMerchantService, never()).selectCmsRegistrationMerchantList(any(CmsRegistrationMerchant.class));
        verify(offerMapper).insertMerchantChapterOffer(any());
    }

    @Test
    void enroll_recruitEnabledRevshare_rejectedEvenForPlatformTopic() {
        CmsTopicChapter c = chapter(TermsMode.REVSHARE, null, 4);
        c.setRecruitEnabled(1);
        c.setMaxPerHeadFee(new BigDecimal("20.00"));
        when(merchantChapterApplicationService.hasApprovedApplication(CHAPTER, MERCHANT)).thenReturn(true);
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.enroll(revshareOffer(), MERCHANT));

        assertTrue(error.getMessage().contains("待P2开放"));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    @Test
    void enroll_rejectsDeletedTopic() {
        chapter(TermsMode.TRAFFIC, null, 4);
        stubTopic.setDelFlag(2);
        MerchantChapterOffer offer = new MerchantChapterOffer();
        offer.setChapterId(CHAPTER);
        offer.setTermsMode(TermsMode.TRAFFIC);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> svc.enroll(offer, MERCHANT));

        assertTrue(error.getMessage().contains("不支持商家入驻"));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    @Test
    void enroll_rejectsLegacyNonPositivePerkThreshold() {
        chapter(TermsMode.PERK, BigDecimal.ZERO, 4);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.enroll(perkOffer(), MERCHANT));

        assertTrue(error.getMessage().contains("权益最低价值"));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    @Test
    void enroll_rejectsLegacyTrafficChapterWithPerkThreshold() {
        CmsTopicChapter chapter = chapter(TermsMode.TRAFFIC, null, 4);
        chapter.setPerkMinValue(BigDecimal.ONE);
        MerchantChapterOffer offer = new MerchantChapterOffer();
        offer.setChapterId(CHAPTER);
        offer.setTermsMode(TermsMode.TRAFFIC);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> svc.enroll(offer, MERCHANT));

        assertTrue(error.getMessage().contains("只有权益档"));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    @Test
    void adjustTerms_rejectsExistingRevshareOnV1RecruitmentChapter() {
        CmsTopicChapter c = chapter(TermsMode.REVSHARE, null, 4);
        c.setRecruitEnabled(1);
        MerchantChapterOffer existing = new MerchantChapterOffer();
        existing.setId(1L);
        existing.setChapterId(CHAPTER);
        existing.setTopicId(TOPIC);
        existing.setStatus(1);
        when(offerMapper.selectById(1L)).thenReturn(existing);
        MerchantChapterOffer patch = new MerchantChapterOffer();
        patch.setId(1L);
        patch.setTermsMode(TermsMode.REVSHARE);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.adjustTerms(patch, "admin"));

        assertTrue(error.getMessage().contains("待P2开放"));
        verify(offerMapper, never()).updateTerms(any());
    }

    @Test
    void enroll_recruitEnabledChapter_rechecksCurrentMerchantEligibilityAfterApproval() {
        CmsTopicChapter c = chapter(TermsMode.TRAFFIC, null, 4);
        c.setRecruitEnabled(1);
        when(merchantChapterApplicationService.hasApprovedApplication(CHAPTER, MERCHANT)).thenReturn(true);
        doThrow(new IllegalStateException("仅审核通过且启用的商家可申请承接"))
                .when(merchantChapterApplicationService).assertCurrentMerchantEligibility(c, MERCHANT);
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setTermsMode(TermsMode.TRAFFIC);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> svc.enroll(o, MERCHANT));

        assertTrue(error.getMessage().contains("审核通过且启用"));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    /** 品类停用是对新供给资格的撤销：已批准申请不能绕过当前有效品类闸。 */
    @Test
    void enroll_recruitEnabledChapter_rejectsApprovedMerchantAfterCategoryIsDisabled() {
        CmsTopicChapter c = chapter(TermsMode.TRAFFIC, null, 4);
        c.setRecruitEnabled(1);
        SysCategory disabled = activeCategory();
        disabled.setStatus(0);
        when(categoryService.selectSysCategoryById(18L)).thenReturn(disabled);
        when(merchantChapterApplicationService.hasApprovedApplication(CHAPTER, MERCHANT)).thenReturn(true);
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setTermsMode(TermsMode.TRAFFIC);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.enroll(o, MERCHANT));

        assertTrue(e.getMessage().contains("不存在或已停用"));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    /**
     * V1 开放招商的权益供给只能从自己的常备模板落一份独立快照，不能把模板 ID 误当 coop_perk.id。
     * 否则前端能选到模板、提交时却会被旧的权益校验拒掉，形成一条走不通的“实际供给”链路。
     */
    @Test
    void enroll_recruitEnabledPerk_materializesOwnTemplateBeforeSavingOffer() {
        CmsTopicChapter c = chapter(TermsMode.PERK, new BigDecimal("50.00"), 4);
        c.setRecruitEnabled(1);
        givenWonBid(Collections.<CmsRegistrationMerchant>emptyList());
        when(merchantChapterApplicationService.hasApprovedApplication(CHAPTER, MERCHANT)).thenReturn(true);
        when(coopInviteService.materializeStandalonePerkFromTemplate(888L, MERCHANT, 50)).thenReturn(PERK);
        givenPerk(new BigDecimal("88.00"), MERCHANT);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setTermsMode(TermsMode.PERK);
        o.setPerkTemplateId(888L);
        o.setQuotaTotal(50);

        svc.enroll(o, MERCHANT);

        verify(coopInviteService).materializeStandalonePerkFromTemplate(888L, MERCHANT, 50);
        ArgumentCaptor<MerchantChapterOffer> captor = ArgumentCaptor.forClass(MerchantChapterOffer.class);
        verify(offerMapper).insertMerchantChapterOffer(captor.capture());
        assertEquals(PERK, captor.getValue().getPerkId(), "offer 必须引用新建的权益快照，而非模板 ID");
    }

    /** 开放招商不是无鉴权：没有本章通过态申请，直调 enroll 必须拒。 */
    @Test
    void enroll_recruitEnabledChapter_withoutApprovedApplication_rejected() {
        CmsTopicChapter c = chapter(TermsMode.TRAFFIC, null, 4);
        c.setRecruitEnabled(1);
        when(merchantChapterApplicationService.hasApprovedApplication(CHAPTER, MERCHANT)).thenReturn(false);
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setTermsMode(TermsMode.TRAFFIC);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.enroll(o, MERCHANT));

        assertTrue(e.getMessage().contains("尚未通过"));
        verify(registrationMerchantService, never()).selectCmsRegistrationMerchantList(any(CmsRegistrationMerchant.class));
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    // ------------------------------------------------- 计酬档人头费上限
    //
    // ★ 为什么必须有:计酬档是**平台按核销真金白银付**,而 per_head_fee 由商家在 enroll 自报;
    // P0-50 必亏闸挂在 confirmPrice(定价时),对定价后新增/改价的 offer **不复查**
    // ⇒ 没有上限,中标商家可自挂天价人头费,平台照付且零告警。
    // 上限挂**章节**:与 termsMode 同源(章节才是档位口径真源),且定价按章算成本。

    /** ★ 超过章节配的上限 ⇒ 拒。 */
    @Test
    void enroll_revshare_feeAboveChapterCap_rejected() {
        chapter(TermsMode.REVSHARE, null, 4).setMaxPerHeadFee(new BigDecimal("15.00"));
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
        MerchantChapterOffer o = revshareOffer();
        o.setPerHeadFee(new BigDecimal("15.01"));   // 只超一分也不行

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.enroll(o, MERCHANT));
        assertTrue(e.getMessage().contains("上限"), "错误文案须指明是人头费上限闸,实际:" + e.getMessage());
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    /** 边界:等于上限放行 —— 闸是「不得**超过**」,不是「必须低于」。 */
    @Test
    void enroll_revshare_feeAtChapterCap_passes() {
        chapter(TermsMode.REVSHARE, null, 4).setMaxPerHeadFee(new BigDecimal("15.00"));
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());
        MerchantChapterOffer o = revshareOffer();
        o.setPerHeadFee(new BigDecimal("15.00"));

        svc.enroll(o, MERCHANT);

        verify(offerMapper).insertMerchantChapterOffer(any());
    }

    /**
     * ★ 章节没配上限 ⇒ 拒(fail-closed),**不是**「不设上限」。
     * 与同章 perkMinValue「空=不设门槛」相反:那是质量门槛(商家承诺自己的东西),
     * 这是**平台成本上界**,留空 = 平台敞口无限。口径同 REVSHARE 既有的 isPlatformOwned
     * fail-closed:宁可当场拒,也不静默放过一个掏钱的口子。
     */
    @Test
    void enroll_revshare_noCapConfigured_rejected() {
        chapter(TermsMode.REVSHARE, null, 4);   // maxPerHeadFee 留空
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.enroll(revshareOffer(), MERCHANT));
        assertTrue(e.getMessage().contains("上限"), "错误文案须指明是人头费上限闸,实际:" + e.getMessage());
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    /** 引流/权益档不谈人头费 ⇒ 不该被本闸波及(它们的 perHeadFee 恒空)。 */
    @Test
    void enroll_traffic_unaffectedByFeeCap() {
        chapter(TermsMode.TRAFFIC, null, 4);   // 上限留空
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setTermsMode(TermsMode.TRAFFIC);

        svc.enroll(o, MERCHANT);

        verify(offerMapper).insertMerchantChapterOffer(any());
    }

    // ------------------------------------------------- 锁价后禁加供给
    //
    // acceptInviteWithLock 对同一不变式已有闸(「主题已锁价,不能再接受合作邀请」),
    // 理由写在它的注释里:新增分成商家会使结算时 Σ分成/保底档超过定价假设 → margin 翻负
    // → 守恒断言拒结 → 款项悬空转人工。enroll 是**同一不变式的平行入口**,此前没有这道闸
    // ⇒ 只守住了一半。⚠️ 对 ③ 尤其真:P0-50 必亏闸挂在 confirmPrice,对事后新增的 offer 不复查。

    /** ★ lifecycle≥3(已锁价/售票)⇒ 拒。定价快照之后不许再改阵容。 */
    @Test
    void enroll_topicPriced_rejected() {
        chapter(TermsMode.TRAFFIC, null, 4);
        givenTopicLifecycle(3);
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setTermsMode(TermsMode.TRAFFIC);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.enroll(o, MERCHANT));
        assertTrue(e.getMessage().contains("锁价"), "错误文案须指明是锁价闸,实际:" + e.getMessage());
        verify(offerMapper, never()).insertMerchantChapterOffer(any());
    }

    /** 招募态照常放行；PRICING 锁价后即冻结。 */
    @Test
    void enroll_topicNotPriced_passes() {
        chapter(TermsMode.TRAFFIC, null, 4);
        givenTopicLifecycle(1);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setTermsMode(TermsMode.TRAFFIC);

        svc.enroll(o, MERCHANT);

        verify(offerMapper).insertMerchantChapterOffer(any());
    }

    /**
     * ★ 主题必须**行锁读**:与 confirmPrice 的 selectCmsTopicByIdForUpdate 串行。
     * 裸读会留下「confirmPrice 读完阵容 → enroll 插一行 → confirmPrice 提交」的 TOCTOU 悬空窗,
     * 那时定价假设的成本与真实应付已经对不上,而必亏闸不会再跑第二次。
     * ⚠️ 锁序:章节锁 → 主题锁。confirmPrice 只锁主题、不锁章节(PricingServiceImpl 没注入
     * 章节 mapper),无 topic→chapter 路径 ⇒ 不构成 ABBA。
     */
    @Test
    void enroll_readsTopicUnderRowLock() {
        chapter(TermsMode.TRAFFIC, null, 4);
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setTermsMode(TermsMode.TRAFFIC);

        svc.enroll(o, MERCHANT);

        verify(cmsTopicMapper).selectCmsTopicByIdForUpdate(TOPIC);
        verify(cmsTopicMapper, never()).selectCmsTopicById(anyLong());
    }

    /**
     * ★ 探针形状断言 —— 这条才是「待调配(0)/落选(2)/别人的中标/别的主题的中标」被挡住的依据。
     * mapper XML 对这四个字段都有 {@code <if test="… != null">} 过滤,探针少给一个,闸就漏一类。
     */
    @Test
    void enroll_wonBidProbe_scopedByMerchantTopicAndWinning() {
        chapter(TermsMode.TRAFFIC, null, 4);
        givenWonBid(Collections.singletonList(new CmsRegistrationMerchant()));
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Collections.<MerchantChapterOffer>emptyList());
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setTermsMode(TermsMode.TRAFFIC);

        svc.enroll(o, MERCHANT);

        ArgumentCaptor<CmsRegistrationMerchant> p = ArgumentCaptor.forClass(CmsRegistrationMerchant.class);
        verify(registrationMerchantService).selectCmsRegistrationMerchantList(p.capture());
        CmsRegistrationMerchant probe = p.getValue();
        assertEquals(MERCHANT, probe.getMemberId(), "不限调用方 ⇒ 拿别人的中标就能入驻");
        assertEquals(TOPIC, probe.getTopicId(), "不限主题 ⇒ 在 A 主题中标就能入驻 B 主题(topicId 须由章节反查)");
        assertEquals(Long.valueOf(1L), probe.getAuditStatus(), "不限中标 ⇒ 报名(待调配 0)即可入驻,等于没闸");
        assertEquals(Integer.valueOf(0), probe.getDelFlag(), "不限 del_flag ⇒ 已软删的报名仍能入驻");
    }

    private MerchantChapterOffer trafficOffer() {
        MerchantChapterOffer row = new MerchantChapterOffer();
        row.setChapterId(CHAPTER); row.setTermsMode(TermsMode.TRAFFIC);
        return row;
    }

    @Test
    void lockedPausedReplacementIsPendingAndCannotIncreaseSupplyCost() {
        chapter(TermsMode.TRAFFIC, null, 1);
        stubTopic.setLifecycle(3); stubTopic.setStatus(1); stubTopic.setUserStatus(0);
        stubTopic.setRecruitDeadline(new Date(0));
        MerchantChapterOffer original = new MerchantChapterOffer();
        original.setId(800L); original.setTopicId(TOPIC); original.setChapterId(CHAPTER);
        original.setMerchantId(MERCHANT + 1); original.setStatus(0); original.setWithdrawReason("停止营业");
        original.setTermsMode(TermsMode.TRAFFIC);
        when(merchantChapterApplicationService.approvedReplacementOfferId(CHAPTER, MERCHANT)).thenReturn(800L);
        when(offerMapper.selectByIdForUpdate(800L)).thenReturn(original);
        MerchantChapterOffer replacement = trafficOffer();
        svc.enroll(replacement, MERCHANT);
        assertEquals(800L, replacement.getReplacementOfOfferId());
        assertEquals(0, replacement.getStatus(), "供给重审前不能进入可核销池");
        assertEquals(0, stubTopic.getUserStatus());
        original.setPerHeadFee(new BigDecimal("1"));
        assertThrows(IllegalStateException.class, () -> svc.enroll(trafficOffer(), MERCHANT));
    }
}
