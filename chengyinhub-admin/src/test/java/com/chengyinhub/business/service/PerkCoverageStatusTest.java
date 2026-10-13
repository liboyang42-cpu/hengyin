package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.mapper.RegistrationEntitlementMapper;
import com.chengyinhub.business.service.impl.PerkLedgerServiceImpl;
import com.chengyinhub.common.constant.TermsMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-16 覆盖率档位 —— 把 javadoc 承诺的两条规则变成代码。
 *
 * <p><b>接线前的真相</b>:{@code COVERAGE_WARN = 1.2d} 声明了却**零读取**
 * (全库 grep 仅命中声明行),{@code chapterCoverageRatio} 方法体内完全没引用它,
 * 只 {@code return nz(supply) / (double) held}。⇒ javadoc 承诺的
 * 「&lt;1.2 黄色预警、&lt;1.0 缺口硬停售」两条规则**在被调方一行都不存在**,全要接线方实现。
 * 若让每个调用方各写一遍 1.2/1.0,下次改阈值必漏一处 ⇒ 收进 service 做单一真源。
 *
 * <p>⚠️ 负控已实测(注入→红→撤销→绿):
 * <ul>
 *   <li>去掉 {@code Double.isInfinite} 判断 ⇒ zeroHeld_isOkNotGap 红(Infinity &lt; 1.0 为 false
 *       所以不会误判成 GAP,但 {@code ratio < COVERAGE_WARN} 也为 false ⇒ 恰好蒙对;
 *       真正会红的是把判断顺序写反的变体)</li>
 *   <li>去掉 chapterId==null 的挡板 ⇒ nullChapterId_isGapNotSilentPass 红</li>
 *   <li>阈值硬编码成 1.0(丢掉 WARNING 档)⇒ ratioBelow1_2_isWarning 红</li>
 * </ul>
 */
class PerkCoverageStatusTest {

    private static final Long TOPIC = 10L;
    private static final Long CH1 = 101L;
    private static final Long CH2 = 102L;

    private CmsTopicChapterMapper chapterMapper;
    private MerchantChapterOfferMapper offerMapper;
    private RegistrationEntitlementMapper entitlementMapper;
    private PerkLedgerServiceImpl service;

    @BeforeEach
    void setUp() {
        chapterMapper = mock(CmsTopicChapterMapper.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        entitlementMapper = mock(RegistrationEntitlementMapper.class);
        service = new PerkLedgerServiceImpl();
        ReflectionTestUtils.setField(service, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(service, "entitlementMapper", entitlementMapper);
        // [P0-14] 撤销核销时清 verification_status(与「首次核销就置位」成对);本类不测该链路,注入空 mock。
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper",
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "redemptionMapper", mock(RedemptionMapper.class));
    }

    /** supply / held = ratio */
    private void givenCoverage(Long chapterId, int supply, int held) {
        when(offerMapper.sumRemainingQuotaByChapter(chapterId)).thenReturn(supply);
        when(entitlementMapper.countUnredeemedByChapter(chapterId)).thenReturn(held);
    }

    /**
     * 该章有一个生效的非权益档商家。
     *
     * <p>⚠️ **必须显式 stub,别靠 mock 默认空表** —— ratio=0 同时符合「商家在但 quota_total=0」
     * 与「一个商家都没有」两种世界(D21 后半句用商家数而非配额做判据正是因为这个)。
     * 不 stub 就是拿「零商家」冒充「有商家」。
     */
    private void givenLiveMerchantOn(Long chapterId, String termsMode) {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setId(7L);
        o.setTopicId(TOPIC);
        o.setChapterId(chapterId);
        o.setMerchantId(500L);
        o.setTermsMode(termsMode);
        o.setQuotaTotal(0);          // 非权益档 enroll 就是落 0,不是漏填
        o.setQuotaUsed(0);
        when(offerMapper.selectByChapter(chapterId)).thenReturn(Collections.singletonList(o));
    }

    /** ⚠️ 不设 termsMode ⇒ null ⇒ 按 PERK(与 DDL {@code DEFAULT 'PERK'} 同口径),老用例语义不变。 */
    private CmsTopicChapter chapter(Long id) {
        return chapter(id, null);
    }

    private CmsTopicChapter chapter(Long id, String termsMode) {
        CmsTopicChapter c = new CmsTopicChapter();
        c.setId(id);
        c.setTopicId(TOPIC);
        c.setRequired(1);
        c.setTermsMode(termsMode);
        return c;
    }

    // ------------------------------------------------- 三档

    @Test
    void ratioAbove1_2_isOk() {
        givenCoverage(CH1, 20, 10);   // 2.0
        assertEquals(IPerkLedgerService.COVERAGE_OK, service.chapterCoverageStatus(CH1));
    }

    /** ★ 阈值若被硬编码成只判 1.0,WARNING 档就消失了,这条红。 */
    @Test
    void ratioBelow1_2_isWarning() {
        givenCoverage(CH1, 11, 10);   // 1.1 —— 供给还够,但安全垫不足
        assertEquals(IPerkLedgerService.COVERAGE_WARNING, service.chapterCoverageStatus(CH1));
    }

    @Test
    void ratioBelow1_0_isGap() {
        givenCoverage(CH1, 8, 10);    // 0.8 —— 已售 10 份只兑得出 8 份
        assertEquals(IPerkLedgerService.COVERAGE_GAP, service.chapterCoverageStatus(CH1));
    }

    /** 边界:恰好 1.2 不算预警(承诺是「<1.2」)。 */
    @Test
    void ratioExactly1_2_isOk() {
        givenCoverage(CH1, 12, 10);
        assertEquals(IPerkLedgerService.COVERAGE_OK, service.chapterCoverageStatus(CH1));
    }

    /** 边界:恰好 1.0 不算缺口,但算预警(供给刚好够,毫无安全垫)。 */
    @Test
    void ratioExactly1_0_isWarningNotGap() {
        givenCoverage(CH1, 10, 10);
        assertEquals(IPerkLedgerService.COVERAGE_WARNING, service.chapterCoverageStatus(CH1));
    }

    // ------------------------------------------------- 两个陷阱

    /**
     * ★ held=0 ⇒ chapterCoverageRatio 返 POSITIVE_INFINITY(还没卖出去,谈不上缺口)。
     * 必须先判 isInfinite —— 裸拿它参与算术/格式化会输出 "Infinity" 字符串。
     */
    @Test
    void zeroHeld_isOkNotGap() {
        givenCoverage(CH1, 0, 0);   // 供给 0、已售 0 ⇒ Infinity
        assertTrue(Double.isInfinite(service.chapterCoverageRatio(CH1)));
        assertEquals(IPerkLedgerService.COVERAGE_OK, service.chapterCoverageStatus(CH1));
    }

    /**
     * ★ chapterId=null 必须判 GAP,不能静默放行。
     * chapterCoverageRatio 内部**没有** null 保护(不同于 chapterCapacity 的 :57-59):
     * `where chapter_id = null` 恒 false ⇒ held=0 ⇒ 返 Infinity ⇒ 会被当成「供给充足」。
     * 「问错了问题」不等于「一切正常」。
     */
    @Test
    void nullChapterId_isGapNotSilentPass() {
        assertEquals(IPerkLedgerService.COVERAGE_GAP, service.chapterCoverageStatus(null));
    }

    // ------------------------------------------------- 主题级(木桶效应)

    /** ★ 一章缺口 ⇒ 整个主题缺口:一章兑现不了,整张通票就毁了。 */
    @Test
    void topicStatus_takesWorstChapter() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(Arrays.asList(chapter(CH1), chapter(CH2)));
        givenCoverage(CH1, 100, 10);  // 10.0 充足
        givenCoverage(CH2, 8, 10);    // 0.8 缺口

        assertEquals(IPerkLedgerService.COVERAGE_GAP, service.topicCoverageStatus(TOPIC));
    }

    @Test
    void topicStatus_allOk() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(Arrays.asList(chapter(CH1), chapter(CH2)));
        givenCoverage(CH1, 100, 10);
        givenCoverage(CH2, 50, 10);

        assertEquals(IPerkLedgerService.COVERAGE_OK, service.topicCoverageStatus(TOPIC));
    }

    /**
     * 无必选章 ⇒ OK(不受权益容量约束的纯内容型主题)。
     * ⚠️ 同一个输入,topicSellableCapacity 返 MAX_VALUE(放行)、isExploreCompleted 返 false(不通关)、
     * 本方法返 OK —— 三者语义各不相同且都正确,别当统一分支处理。
     */
    @Test
    void topicWithoutRequiredChapters_isOk() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(Collections.<CmsTopicChapter>emptyList());
        assertEquals(IPerkLedgerService.COVERAGE_OK, service.topicCoverageStatus(TOPIC));
    }

    // ------------------------------------------------- topicSellableCapacity 的三个返回域

    /** 无必选章 ⇒ MAX_VALUE = **不受约束**,不是「余量巨大」。调用方不能拿它参与算术。 */
    @Test
    void sellableCapacity_noRequiredChapters_isMaxValue() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(Collections.<CmsTopicChapter>emptyList());
        assertEquals(Integer.MAX_VALUE, service.topicSellableCapacity(TOPIC));
    }

    /**
     * ★ 超卖必须暴露成**负数**,不能 clamp 成 0 —— 容量公式刻意不 clamp,
     * 负数是缺口事件的输入信号。藏成 0 就把缺口从账上抹掉了。
     */
    @Test
    void sellableCapacity_oversold_staysNegative() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(Collections.singletonList(chapter(CH1)));
        givenCoverage(CH1, 7, 10);   // supply 7 − held 10 = -3

        assertEquals(-3, service.topicSellableCapacity(TOPIC));
    }

    /** 取各必选章最小值(木桶效应)。 */
    @Test
    void sellableCapacity_takesMinAcrossChapters() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(Arrays.asList(chapter(CH1), chapter(CH2)));
        givenCoverage(CH1, 50, 10);   // 40
        givenCoverage(CH2, 13, 10);   // 3

        assertEquals(3, service.topicSellableCapacity(TOPIC));
    }

    // ------------------------------------------------- 条款档收窄(F1)
    //
    // quota/容量/安全垫**仅对权益档章节成立**(D21 拍板,TermsMode.usesQuota 是单一真源)。
    // 计酬/引流档的 offer 由 enroll 落 quota_total=0 ⇒ 它们的「容量」恒 0、「覆盖率」恒 0:
    // 那不是缺口,是**这两档压根不在这条链上**。把它们算进 min / 缺口判定 =
    // 一个 ③ 路线只要开非权益档就永远显示 0 可售 + 红色缺口。

    /** ★ 全非权益档 ⇒ 不受容量约束(与「无必选章」同档),不是可售 0。 */
    @Test
    void sellableCapacity_allNonPerkChapters_isUnbounded() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(
                Arrays.asList(chapter(CH1, TermsMode.REVSHARE), chapter(CH2, TermsMode.TRAFFIC)));
        givenCoverage(CH1, 0, 0);   // enroll 落 quota_total=0 的真实形状
        givenCoverage(CH2, 0, 0);

        assertEquals(Integer.MAX_VALUE, service.topicSellableCapacity(TOPIC),
                "计酬/引流档不设接待上限 ⇒ 不参与 min;算进去就把整条 ③ 路线卡成一张票都卖不出");
    }

    /** 混档 ⇒ 只有权益章参与 min。 */
    @Test
    void sellableCapacity_mixedModes_onlyPerkCounts() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(
                Arrays.asList(chapter(CH1, TermsMode.PERK), chapter(CH2, TermsMode.REVSHARE)));
        givenCoverage(CH1, 15, 10);  // 5
        givenCoverage(CH2, 0, 0);    // 恒 0,但不该参与

        assertEquals(5, service.topicSellableCapacity(TOPIC));
    }

    /** ★★ 负控:混档里权益章超卖 ⇒ 仍暴露负数,非权益章的存在不该把它盖掉。 */
    @Test
    void sellableCapacity_mixedModes_perkOversold_staysNegative() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(
                Arrays.asList(chapter(CH1, TermsMode.PERK), chapter(CH2, TermsMode.REVSHARE)));
        givenCoverage(CH1, 7, 10);   // -3
        givenCoverage(CH2, 0, 0);

        assertEquals(-3, service.topicSellableCapacity(TOPIC));
    }

    /**
     * ★ 非权益章**有商家**时的「覆盖率 0」不是缺口 —— 它没承诺任何实物权益,没什么可兑现不了的。
     *
     * <p>与 {@link #topicStatus_nonPerkChapterWithNoMerchant_isGap} 成对:同样 ratio=0,
     * 有商家 ⇒ OK,零商家 ⇒ GAP。这个 stub 就是把两个世界分开的那一笔。
     */
    @Test
    void topicStatus_nonPerkChapter_isNotGap() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(
                Collections.singletonList(chapter(CH1, TermsMode.REVSHARE)));
        givenCoverage(CH1, 0, 10);   // 卖了 10 张、quota_total 恒 0 ⇒ ratio=0
        givenLiveMerchantOn(CH1, TermsMode.REVSHARE);

        assertEquals(IPerkLedgerService.COVERAGE_OK, service.topicCoverageStatus(TOPIC),
                "计酬档没有 quota 这回事,ratio=0 是量纲错配不是缺口");
    }

    /**
     * ★★ 负控:混档里权益章真缺口 ⇒ 整个主题仍判缺口,木桶效应不被非权益章稀释。
     *
     * <p>⚠️ CH2 必须显式给商家:否则 CH2 自己就是缺口,权益章那道判定被改穿了这条也照绿 = 假绿。
     */
    @Test
    void topicStatus_mixedModes_perkGap_stillGap() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(
                Arrays.asList(chapter(CH1, TermsMode.PERK), chapter(CH2, TermsMode.REVSHARE)));
        givenCoverage(CH1, 8, 10);   // 0.8 真缺口
        givenCoverage(CH2, 0, 10);
        givenLiveMerchantOn(CH2, TermsMode.REVSHARE);

        assertEquals(IPerkLedgerService.COVERAGE_GAP, service.topicCoverageStatus(TOPIC));
    }

    /**
     * ★★ D21 后半句:非权益章的缺口判据 = 「该章有效商家数 ≥1」。零商家 ⇒ 缺口。
     *
     * <p>与 {@link #topicStatus_nonPerkChapter_isNotGap} 是一对:同样是 ratio=0,
     * 有商家 ⇒ OK(量纲错配不是缺口),零商家 ⇒ GAP(真的没人能履约)。
     * `Σ(quota_total − quota_used)` 分不清这两者(都是 0),只能按 count 判。
     */
    @Test
    void topicStatus_nonPerkChapterWithNoMerchant_isGap() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(
                Collections.singletonList(chapter(CH1, TermsMode.REVSHARE)));
        givenCoverage(CH1, 0, 10);
        // 刻意不 stub selectByChapter ⇒ 空表 ⇒ 零商家

        assertEquals(IPerkLedgerService.COVERAGE_GAP, service.topicCoverageStatus(TOPIC),
                "计酬章零商家 = 没人能核销 = 永不通关,这是真缺口");
    }

    /**
     * ★ 零商家但**一张票也没卖出** ⇒ OK,不是缺口。与权益档 {@code held<=0 ⇒ OK}
     *(「还没卖出去,谈不上缺口」)对齐。
     *
     * <p>缺口事件的语义是 §4.6「**已售出**的兑现不了 → 替代商家 → 失败即整单退」——
     * 零售出时没有任何单子需要处置,报缺口是假警报。收钱那侧由 reserveEntitlements 的
     * 拒售闸独立守着,不靠这里。
     */
    @Test
    void topicStatus_nonPerkChapterNoMerchantNothingSold_isOkNotGap() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(
                Collections.singletonList(chapter(CH1, TermsMode.REVSHARE)));
        givenCoverage(CH1, 0, 0);    // held=0:还没开卖
        // 零商家(不 stub selectByChapter)

        assertEquals(IPerkLedgerService.COVERAGE_OK, service.topicCoverageStatus(TOPIC),
                "还没卖出去就谈不上缺口 —— 拒售闸管收钱那侧,这里别打假警报");
    }

    /**
     * ★★ 跨档快照:章节已翻成 REVSHARE,但存量 offer 还是 PERK 快照 ⇒ **不算有效商家** ⇒ 缺口。
     *
     * <p>该商家谈的是权益档条款(按配额供货),并没有承诺计酬档的接待义务。数进去就会卖出
     * 无人按该档履约的票。fail-closed 朝少卖那侧倒,与本类既有纪律同向。
     */
    @Test
    void topicStatus_nonPerkChapterWithOnlyMismatchedModeOffer_isGap() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(
                Collections.singletonList(chapter(CH1, TermsMode.REVSHARE)));
        givenCoverage(CH1, 0, 10);
        givenLiveMerchantOn(CH1, TermsMode.PERK);   // 章节是 REVSHARE,offer 却是 PERK 快照

        assertEquals(IPerkLedgerService.COVERAGE_GAP, service.topicCoverageStatus(TOPIC),
                "跨档快照 offer 没承诺计酬档义务,不能拿它冒充有效商家");
    }

    /** ★★ 负控:null 档按 PERK ⇒ 缺口照报。判成非权益就是把缺口从账上抹掉。 */
    @Test
    void topicStatus_nullTermsMode_treatedAsPerk_stillGap() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(
                Collections.singletonList(chapter(CH1, null)));
        givenCoverage(CH1, 8, 10);

        assertEquals(IPerkLedgerService.COVERAGE_GAP, service.topicCoverageStatus(TOPIC),
                "null 必须按 PERK,否则权益章的缺口被静默抹掉");
    }
}
