package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.impl.MerchantOfferServiceImpl;
import com.chengyinhub.common.constant.TermsMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台定档定参(§4.2 商家承接审核台)—— {@code adjustTerms}/{@code deactivate} 的闸。
 *
 * <p>三档形状闸(费率上限/权益门槛/IDOR/二清)与 enroll 共用同一套私有方法,细粒度用例在
 * {@link MerchantOfferServiceImplTest};本类只验 <b>adjust 入口的接线</b>(每档一条穿透负控)
 * 与 <b>adjust 独有的闸</b>:失效行拒改 / 锁价冻结 / 口径对齐 / quota 不得低于已核销 /
 * 过期复活补数 max_merchant / 停用幂等。
 *
 * <p>⚠️ 断言一律指明**哪道闸**的文案(相邻多闸抛同型异常,只 assertThrows 是假绿)。
 * 负控已实测(注入→红→撤销→绿),见各用例上方标注。
 */
class MerchantOfferAdjustTermsTest {

    private static final Long OFFER = 9L;
    private static final Long CHAPTER = 33L;
    private static final Long TOPIC = 10L;
    private static final Long MERCHANT = 500L;
    private static final Long PERK = 77L;
    private static final String OPER = "admin";

    private MerchantChapterOfferMapper offerMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicMapper cmsTopicMapper;
    private CoopPerkMapper coopPerkMapper;
    private IProductRoleService productRoleService;
    private ICoopInviteService coopInviteService;
    private com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer scopeAuthorizer;
    private MerchantOfferServiceImpl svc;
    private CmsTopic stubTopic;

    @BeforeEach
    void setUp() {
        offerMapper = mock(MerchantChapterOfferMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        cmsTopicMapper = mock(CmsTopicMapper.class);
        coopPerkMapper = mock(CoopPerkMapper.class);
        productRoleService = mock(IProductRoleService.class);
        coopInviteService = mock(ICoopInviteService.class);
        scopeAuthorizer = mock(com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer.class);
        when(scopeAuthorizer.canViewTopic(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        when(scopeAuthorizer.hasGlobalTopicScope()).thenReturn(true);

        svc = new MerchantOfferServiceImpl();
        ReflectionTestUtils.setField(svc, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(svc, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(svc, "coopPerkMapper", coopPerkMapper);
        ReflectionTestUtils.setField(svc, "productRoleService", productRoleService);
        ReflectionTestUtils.setField(svc, "coopInviteService", coopInviteService);
        ReflectionTestUtils.setField(svc, "topicOperationsScopeAuthorizer", scopeAuthorizer);

        // 默认:条件覆写命中 1 行。mock 的 int 默认值是 0,不显式 stub 的话每条 happy 用例都会
        // 误触「状态已变化」守卫 —— 守卫自己的用例用 thenReturn(0) 显式覆盖。
        when(offerMapper.updateTerms(any(MerchantChapterOffer.class))).thenReturn(1);
    }

    // ---------- 脚手架 ----------

    private CmsTopicChapter chapter(String mode) {
        CmsTopicChapter c = new CmsTopicChapter();
        c.setId(CHAPTER);
        c.setTopicId(TOPIC);
        c.setTermsMode(mode);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(c);
        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER)))
                .thenReturn(Collections.singletonList(CHAPTER));
        stubTopic = new CmsTopic();
        stubTopic.setId(TOPIC);
        stubTopic.setLifecycle(1);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(stubTopic);
        return c;
    }

    /** 库里那行既有 offer:生效、无有效期(=长期有效)。 */
    private MerchantChapterOffer existing(String mode) {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setId(OFFER);
        o.setChapterId(CHAPTER);
        o.setTopicId(TOPIC);
        o.setMerchantId(MERCHANT);
        o.setTermsMode(mode);
        o.setStatus(1);
        o.setQuotaUsed(0);
        when(offerMapper.selectById(OFFER)).thenReturn(o);
        return o;
    }

    private MerchantChapterOffer patch(String mode) {
        MerchantChapterOffer p = new MerchantChapterOffer();
        p.setId(OFFER);
        p.setTermsMode(mode);
        return p;
    }

    private void givenPerk(BigDecimal retailValue, Long owner) {
        CoopPerk p = new CoopPerk();
        p.setId(PERK);
        p.setRetailValue(retailValue);
        p.setQuota(100);
        when(coopPerkMapper.selectById(PERK)).thenReturn(p);
        when(coopInviteService.resolvePerkOwner(PERK)).thenReturn(owner);
    }

    private String adjustFails(MerchantChapterOffer p) {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> svc.adjustTerms(p, OPER));
        verify(offerMapper, never()).updateTerms(any(MerchantChapterOffer.class));
        return ex.getMessage();
    }

    // ---------- adjust 独有的闸 ----------

    @Test
    void 缺id或行不存在_拒() {
        assertTrue(adjustFails(new MerchantChapterOffer()).contains("缺少供给记录"));
        assertTrue(adjustFails(patch(TermsMode.TRAFFIC)).contains("供给记录不存在"));
    }

    @Test
    void 失效行_拒改() {
        existing(TermsMode.TRAFFIC).setStatus(0);
        assertTrue(adjustFails(patch(TermsMode.TRAFFIC)).contains("已失效"));
    }

    @Test
    void 主题已锁价_条款冻结() {
        existing(TermsMode.TRAFFIC);
        chapter(TermsMode.TRAFFIC);
        stubTopic.setLifecycle(3);
        assertTrue(adjustFails(patch(TermsMode.TRAFFIC)).contains("已锁价"));
    }

    @Test
    void 主题已删除_条款冻结() {
        existing(TermsMode.TRAFFIC);
        chapter(TermsMode.TRAFFIC);
        stubTopic.setDelFlag(2);
        assertTrue(adjustFails(patch(TermsMode.TRAFFIC)).contains("主题不存在"));
    }

    /** 章节是口径真源:档不等于**当前**章节口径的定档必须拒,否则三处按章节答的口径全被写花。 */
    @Test
    void 定档偏离章节口径_拒() {
        existing(TermsMode.PERK);
        chapter(TermsMode.PERK);
        MerchantChapterOffer p = patch(TermsMode.REVSHARE);
        p.setPerHeadFee(new BigDecimal("5"));
        assertTrue(adjustFails(p).contains("口径"));
    }

    @Test
    void 权益档_接待上限不得低于已核销() {
        existing(TermsMode.PERK).setQuotaUsed(30);
        chapter(TermsMode.PERK);
        givenPerk(new BigDecimal("88"), MERCHANT);
        MerchantChapterOffer p = patch(TermsMode.PERK);
        p.setPerkId(PERK);
        p.setQuotaTotal(29);
        assertTrue(adjustFails(p).contains("已核销"));
    }

    /** 过期供给把有效期改回未来 = 重新占本章名额,必须补数 max_merchant(enroll 同口径)。 */
    @Test
    void 过期复活_名额已满_拒() {
        MerchantChapterOffer dead = existing(TermsMode.TRAFFIC);
        dead.setValidEnd(new Date(System.currentTimeMillis() - 86400_000L));   // 已过期
        CmsTopicChapter c = chapter(TermsMode.TRAFFIC);
        c.setMaxMerchant(1);
        // 本章生效集已被别家占满(自身已过期,不在生效集里)
        when(offerMapper.selectByChapter(CHAPTER)).thenReturn(Arrays.asList(new MerchantChapterOffer()));
        MerchantChapterOffer p = patch(TermsMode.TRAFFIC);
        p.setValidEnd(null);   // 改回长期有效
        assertTrue(adjustFails(p).contains("已满"));
    }

    // ---------- 三档接线穿透(共用闸的细粒度用例在 MerchantOfferServiceImplTest) ----------

    @Test
    void 计酬档_超章节上限_拒() {
        existing(TermsMode.REVSHARE);
        CmsTopicChapter c = chapter(TermsMode.REVSHARE);
        c.setMaxPerHeadFee(new BigDecimal("10"));
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
        MerchantChapterOffer p = patch(TermsMode.REVSHARE);
        p.setPerHeadFee(new BigDecimal("10.01"));
        assertTrue(adjustFails(p).contains("上限"));
    }

    @Test
    void 计酬档_无俱乐部直付且非平台采购_拒() {
        existing(TermsMode.REVSHARE);
        chapter(TermsMode.REVSHARE);
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(false);
        MerchantChapterOffer p = patch(TermsMode.REVSHARE);
        p.setPerHeadFee(new BigDecimal("5"));
        assertEquals("计酬档须有俱乐部直付主体或既有平台采购归属", adjustFails(p));
    }

    @Test
    void 计酬档_普通自由探索俱乐部直付_冻结付款主体() {
        existing(TermsMode.REVSHARE).setPayerClubId(7002L);
        chapter(TermsMode.REVSHARE).setMaxPerHeadFee(new BigDecimal("10"));
        stubTopic.setProductType(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
        com.chengyinhub.business.domain.ProductRole content = new com.chengyinhub.business.domain.ProductRole();
        content.setEntityType("CLUB");
        content.setEntityId(7002L);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(stubTopic);
        when(productRoleService.activeContentSlot(TOPIC)).thenReturn(content);
        MerchantChapterOffer p = patch(TermsMode.REVSHARE);
        p.setPerHeadFee(new BigDecimal("5"));

        svc.adjustTerms(p, OPER);

        ArgumentCaptor<MerchantChapterOffer> cap = ArgumentCaptor.forClass(MerchantChapterOffer.class);
        verify(offerMapper).updateTerms(cap.capture());
        assertEquals(Long.valueOf(7002), cap.getValue().getPayerClubId());
        assertEquals(new BigDecimal("5"), cap.getValue().getPerHeadFee());
        verify(productRoleService, never()).isPlatformOwned(TOPIC);
    }

    @Test
    void 计酬档_合法调参_全列覆写含quota归零() {
        existing(TermsMode.REVSHARE);
        CmsTopicChapter c = chapter(TermsMode.REVSHARE);
        c.setMaxPerHeadFee(new BigDecimal("10"));
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
        MerchantChapterOffer p = patch(TermsMode.REVSHARE);
        p.setPerHeadFee(new BigDecimal("10"));   // 等于上限=正常成交,放行

        svc.adjustTerms(p, OPER);

        ArgumentCaptor<MerchantChapterOffer> cap = ArgumentCaptor.forClass(MerchantChapterOffer.class);
        verify(offerMapper).updateTerms(cap.capture());
        MerchantChapterOffer w = cap.getValue();
        assertEquals(TermsMode.REVSHARE, w.getTermsMode());
        assertEquals(new BigDecimal("10"), w.getPerHeadFee());
        assertNull(w.getPerkId());
        assertEquals(Integer.valueOf(0), w.getQuotaTotal());   // 非PERK不走容量链,覆写落0
        assertEquals(OPER, w.getUpdateBy());
    }

    @Test
    void 权益档_零售价低于门槛_拒() {
        existing(TermsMode.PERK);
        CmsTopicChapter c = chapter(TermsMode.PERK);
        c.setPerkMinValue(new BigDecimal("100"));
        givenPerk(new BigDecimal("99.99"), MERCHANT);
        MerchantChapterOffer p = patch(TermsMode.PERK);
        p.setPerkId(PERK);
        p.setQuotaTotal(50);
        assertTrue(adjustFails(p).contains("零售价不低于"));
    }

    /** IDOR:定档换绑的权益仍必须属于 offer 的商家(校验对象是商家,不是后台操作员)。 */
    @Test
    void 权益档_换绑别家权益_拒() {
        existing(TermsMode.PERK);
        chapter(TermsMode.PERK);
        givenPerk(new BigDecimal("88"), 999L);   // 权益归属别家商家
        MerchantChapterOffer p = patch(TermsMode.PERK);
        p.setPerkId(PERK);
        p.setQuotaTotal(50);
        assertTrue(adjustFails(p).contains("自己申报的权益"));
    }

    @Test
    void 权益档_合法调参_quota保留() {
        existing(TermsMode.PERK).setQuotaUsed(10);
        chapter(TermsMode.PERK);
        givenPerk(new BigDecimal("88"), MERCHANT);
        MerchantChapterOffer p = patch(TermsMode.PERK);
        p.setPerkId(PERK);
        p.setQuotaTotal(60);

        svc.adjustTerms(p, OPER);

        ArgumentCaptor<MerchantChapterOffer> cap = ArgumentCaptor.forClass(MerchantChapterOffer.class);
        verify(offerMapper).updateTerms(cap.capture());
        assertEquals(Integer.valueOf(60), cap.getValue().getQuotaTotal());
        assertNull(cap.getValue().getPerHeadFee());
    }

    @Test
    void 引流档_合法调参_金额权益全空quota归零() {
        existing(TermsMode.TRAFFIC);
        chapter(TermsMode.TRAFFIC);
        MerchantChapterOffer p = patch(TermsMode.TRAFFIC);

        svc.adjustTerms(p, OPER);

        ArgumentCaptor<MerchantChapterOffer> cap = ArgumentCaptor.forClass(MerchantChapterOffer.class);
        verify(offerMapper).updateTerms(cap.capture());
        assertNull(cap.getValue().getPerHeadFee());
        assertNull(cap.getValue().getPerkId());
        assertEquals(Integer.valueOf(0), cap.getValue().getQuotaTotal());
    }

    /** consumeQuota 不拿章节锁 ⇒ 「读-判-写」窗口挡不住并发核销/停用,守卫在 WHERE;0 行必须拒。 */
    @Test
    void 条件覆写0行_状态已变_拒() {
        existing(TermsMode.TRAFFIC);
        chapter(TermsMode.TRAFFIC);
        when(offerMapper.updateTerms(any(MerchantChapterOffer.class))).thenReturn(0);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.adjustTerms(patch(TermsMode.TRAFFIC), OPER));
        assertTrue(ex.getMessage().contains("刷新"));
    }

    /** 有核销历史的行改非权益档会以 quota_total=0/used>0 给容量求和贡献负数 ⇒ 必须拒。 */
    @Test
    void 有核销历史_改非权益档_拒() {
        existing(TermsMode.PERK).setQuotaUsed(10);   // 章节已改档 REVSHARE,存量快照还是 PERK 且有核销
        CmsTopicChapter c = chapter(TermsMode.REVSHARE);
        c.setMaxPerHeadFee(new BigDecimal("10"));
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
        MerchantChapterOffer p = patch(TermsMode.REVSHARE);
        p.setPerHeadFee(new BigDecimal("5"));
        assertTrue(adjustFails(p).contains("核销记录"));
    }

    /** §2.2 判据:章节 terms_mode 为 null 按 PERK 读 —— 定档 PERK 应放行,不是拒「口径不一致」。 */
    @Test
    void 章节口径null_按权益档归一() {
        existing(TermsMode.PERK);
        chapter(null);
        givenPerk(new BigDecimal("88"), MERCHANT);
        MerchantChapterOffer p = patch(TermsMode.PERK);
        p.setPerkId(PERK);
        p.setQuotaTotal(50);

        svc.adjustTerms(p, OPER);

        ArgumentCaptor<MerchantChapterOffer> cap = ArgumentCaptor.forClass(MerchantChapterOffer.class);
        verify(offerMapper).updateTerms(cap.capture());
        assertEquals(TermsMode.PERK, cap.getValue().getTermsMode());
    }

    // ---------- 停用 ----------

    @Test
    void 停用_行不存在_拒() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> svc.deactivate(OFFER));
        assertTrue(ex.getMessage().contains("不存在"));
        verify(offerMapper, never()).deactivate(OFFER);
    }

    @Test
    void 停用_幂等_已失效不重复写() {
        existing(TermsMode.PERK).setStatus(0);
        svc.deactivate(OFFER);
        verify(offerMapper, never()).deactivate(OFFER);
    }

    @Test
    void 停用_生效行_落笔() {
        existing(TermsMode.PERK);
        svc.deactivate(OFFER);
        verify(offerMapper).deactivate(OFFER);
    }

    @Test
    void 无主题范围时调参与停用均不得落笔() {
        existing(TermsMode.PERK);
        when(scopeAuthorizer.canViewTopic(TOPIC)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> svc.adjustTerms(patch(TermsMode.PERK), "operator"));
        assertThrows(IllegalStateException.class, () -> svc.deactivate(OFFER));

        verify(offerMapper, never()).updateTerms(any(MerchantChapterOffer.class));
        verify(offerMapper, never()).deactivate(OFFER);
    }
}
