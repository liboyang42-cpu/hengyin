package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.dto.ChapterSupplyTermsDTO;
import com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.impl.CmsTopicChapterServiceImpl;
import com.chengyinhub.common.constant.TermsMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [D21] 「谁能把一章标成计酬档」的三个入口 —— 一道闸,三处必须一致。
 *
 * <p><b>为什么这件事值得一个测试类</b>:计酬档 = <b>平台按人头给商家掏现金</b>。
 * 谁能设它,谁就能替平台承诺一笔无上限的应付。而 D21 规定它<b>只对 ③-自营 开放</b>
 * (第三方之间分钱 = 二清,必须等收付通 P0-43)。这道闸有三个入口:
 * <ul>
 *   <li>{@code POST /business/topicchapter}(通用 add)→ 一律拒,让它走改判;</li>
 *   <li>{@code PUT /business/topicchapter}(通用 edit)→ 改档一律拒,让它走改判;</li>
 *   <li>{@code PUT /business/topicchapter/{id}/supply-terms}(配置)→ 形状自洽 + 自营 + 改档位时无生效供给。</li>
 * </ul>
 * <b>只堵一个等于没堵</b> —— 本项目「闸放错层」栽过四次,全是同一个形状:
 * 逻辑对、入口绕开、测试全绿。
 *
 * <p>⚠️ 负控已实测(注入→红→撤销→绿):
 * <ul>
 *   <li>删掉 assertNotCreatingRevshare 调用 ⇒ {@link #add_cannotCreateRevshareDirectly} 红</li>
 *   <li>删掉 assertNotChangingSupplyTerms 调用 ⇒ {@link #edit_cannotChangeTermsMode} 红</li>
 *   <li>删掉 isPlatformOwned 那道闸 ⇒ {@link #reassign_revshareRequiresPlatformOwned} 红</li>
 *   <li>删掉「有生效供给不许改档」那道闸 ⇒ {@link #reassign_refusedWhenChapterHasLiveOffers} 红</li>
 * </ul>
 */
class ChapterSupplyTermsGateTest {

    private static final Long CH = 101L;
    private static final Long TOPIC = 700L;

    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicMapper topicMapper;
    private MerchantChapterOfferMapper offerMapper;
    private IProductRoleService productRoleService;
    private IMerchantChapterApplicationService merchantChapterApplicationService;
    private ITopicOperationsScopeAuthorizer scopeAuthorizer;
    private CmsTopicChapterServiceImpl service;

    @BeforeEach
    void setUp() {
        chapterMapper = mock(CmsTopicChapterMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        productRoleService = mock(IProductRoleService.class);
        merchantChapterApplicationService = mock(IMerchantChapterApplicationService.class);
        scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);

        service = new CmsTopicChapterServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        com.chengyinhub.business.service.support.TopicContentBudgetTestWiring.chapter(service);
        ReflectionTestUtils.setField(service, "merchantChapterOfferMapper", offerMapper);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        ReflectionTestUtils.setField(service, "merchantChapterApplicationService", merchantChapterApplicationService);
        ReflectionTestUtils.setField(service, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        // P1-LC-1:通用 add/edit 也走「已发布免费主题禁转商业」写后复核;本类专测条款闸,
        // 政策桩默认不受保护(复核 no-op),不抢这些用例的判别力。
        ReflectionTestUtils.setField(service, "commercialTopicPolicy",
                mock(com.chengyinhub.business.service.support.CommercialTopicPolicy.class));

        // 默认 = 自营主题 + 该章零生效供给 = **改判该放行**。
        // 各用例只改自己要测的那一个变量,否则「被拒」的原因会有好几个,拆掉一道闸另一道会接住。
        when(chapterMapper.selectCmsTopicChapterById(CH)).thenReturn(chapter(TermsMode.PERK));
        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CH)))
                .thenReturn(Collections.singletonList(CH));
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
        when(scopeAuthorizer.canViewTopic(TOPIC)).thenReturn(true);
        when(offerMapper.selectByChapter(CH)).thenReturn(Collections.<MerchantChapterOffer>emptyList());
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setLifecycle(TopicLifecycleState.DRAFT.getCode());
        topic.setDelFlag(0);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
    }

    private CmsTopicChapter chapter(String termsMode) {
        CmsTopicChapter c = new CmsTopicChapter();
        c.setId(CH);
        c.setTopicId(TOPIC);
        c.setTermsMode(termsMode);
        return c;
    }

    /**
     * 该档位下的**形状合法**配置。
     *
     * <p>⚠️ 计酬档自动带上人头费上限:留空是**非法形状**(enroll 的 assertFeeWithinChapterCap
     * 对 cap==null fail-closed ⇒ 商家一个都进不来)。想测「不带上限」那条,用
     * {@link #revshare_withoutFeeCap_isRejected} —— 别让别的用例顺带踩到它,
     * 否则「被拒」的原因就有两个,拆掉哪道闸都会被另一道接住。
     */
    private ChapterSupplyTermsDTO terms(String termsMode) {
        ChapterSupplyTermsDTO t = new ChapterSupplyTermsDTO();
        t.setTermsMode(termsMode);
        t.setMaxMerchant(0); // 专用 updateSupplyTerms 无条件写列；不限必须显式写 0。
        if (TermsMode.REVSHARE.equals(termsMode)) {
            t.setMaxPerHeadFee(new java.math.BigDecimal("20.00"));
        }
        return t;
    }

    // ------------------------------------------------- 入口一:通用 add

    /**
     * 🔴 {@code POST /business/topicchapter {"termsMode":"REVSHARE"}} 必须被拒。
     *
     * <p>不拒的话:带 topicchapter:add 权限的人能在**任意主题**(包括第三方的)上直接建计酬档章,
     * 完全不经 isPlatformOwned 校验 —— 把改判入口的三道闸整个绕过去。
     */
    @Test
    void add_cannotCreateRevshareDirectly() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.insertCmsTopicChapter(chapter(TermsMode.REVSHARE)));
        assertTrue(e.getMessage().contains("计酬档不能在建章时直接设置"));
        verify(chapterMapper, never()).insertCmsTopicChapter(any());
    }

    /** 权益档/引流档正常建 —— 这道闸只拒计酬档,别把普通建章也拦了。 */
    @Test
    void add_allowsPerkAndTraffic() {
        service.insertCmsTopicChapter(chapter(TermsMode.PERK));
        service.insertCmsTopicChapter(chapter(TermsMode.TRAFFIC));
        service.insertCmsTopicChapter(chapter(null));   // null ⇒ DDL 默认 PERK

        verify(chapterMapper, org.mockito.Mockito.times(3)).insertCmsTopicChapter(any());
    }

    // ------------------------------------------------- 入口二:通用 edit

    /**
     * 🔴 {@code PUT /business/topicchapter {"id":1,"termsMode":"REVSHARE"}} 必须被拒。
     *
     * <p>mapper 的 update 里有 {@code <if test="termsMode != null">terms_mode = ...} ——
     * 不挡的话这条路同样绕开改判入口的三道闸。
     */
    @Test
    void edit_cannotChangeTermsMode() {
        CmsTopicChapter patch = chapter(TermsMode.REVSHARE);   // 库里是 PERK,想改成 REVSHARE

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.updateCmsTopicChapter(patch));
        assertTrue(e.getMessage().contains("条款档不能在这里改"));
        verify(chapterMapper, never()).updateCmsTopicChapter(any());
    }

    /**
     * ★ 原样回传同一个档位要放行。
     *
     * <p>若依的表单习惯把整个实体回传 —— 一律拒会把「改个章节名」这种正常编辑也拒掉。
     * 判据是「值变没变」,不是「带没带这个字段」。
     */
    @Test
    void edit_allowsUnchangedTermsMode() {
        service.updateCmsTopicChapter(chapter(TermsMode.PERK));   // 库里也是 PERK

        verify(chapterMapper).updateCmsTopicChapter(any());
    }

    // ------------------------------------------------- 入口三:改判(唯一合法路径)

    /** ★ 自营主题 + 零生效供给 ⇒ 标计酬档成功,档位与上限一起落库。 */
    @Test
    void reassign_marksRevshareOnPlatformOwnedChapter() {
        service.configureSupplyTerms(CH, terms(TermsMode.REVSHARE));

        verify(chapterMapper).updateSupplyTerms(eq(CH), eq(TermsMode.REVSHARE),
                org.mockito.ArgumentMatchers.isNull(),
                eq(new java.math.BigDecimal("20.00")),
                eq(0), any());
    }

    /**
     * 🔴 D21:计酬档只对 ③-自营 开放。
     * ⚠️ 断言错误文案指明是**哪道闸** —— 三道闸都抛 IllegalStateException,
     * 只断言「抛了」的话,拆掉这道会被另一道接住而测试照样绿。
     */
    @Test
    void reassign_revshareRequiresPlatformOwned() {
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(false);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.configureSupplyTerms(CH, terms(TermsMode.REVSHARE)));
        assertTrue(e.getMessage().contains("只能标在平台自营路线上"), "拒的理由必须是「不自营」,别混进别的闸");
        verify(chapterMapper, never()).updateSupplyTerms(anyLong(), any(), any(), any(), any(), any());
    }

    /** ★ 非自营主题标**权益档/引流档**不受影响 —— 自营闸只管计酬档。 */
    @Test
    void reassign_nonRevshareIgnoresPlatformOwnedGate() {
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(false);

        service.configureSupplyTerms(CH, terms(TermsMode.TRAFFIC));

        verify(chapterMapper).updateSupplyTerms(anyLong(), any(), any(), any(), any(), any());
    }

    /**
     * 🔴 ★ 该章有生效供给 ⇒ 拒。这是**资损闸**,不是洁癖。
     *
     * <p>容量闸按**章节**档位走(F1),而 offer 的档位是**入驻时的快照**(刻意的:
     * 已谈定的条款不被追溯改写)。把有 PERK 供给的章改成 REVSHARE ⇒ 容量闸对该章当场失效,
     * 可存量 PERK offer 仍按 quota 扣 ⇒ 那些商家承诺的接待上限失守 = 超发,且零告警。
     */
    @Test
    void reassign_refusedWhenChapterHasLiveOffers() {
        when(offerMapper.selectByChapter(CH)).thenReturn(Arrays.asList(new MerchantChapterOffer()));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.configureSupplyTerms(CH, terms(TermsMode.REVSHARE)));
        assertTrue(e.getMessage().contains("生效商家供给"), "拒的理由必须是「有存量供给」,别混进自营闸");
        verify(chapterMapper, never()).updateCmsTopicChapter(any());
    }

    /** 🔴 反方向同样不安全:TRAFFIC/REVSHARE 章改回 PERK,存量 offer 的 quota_total=0 会让该章容量恒 0(F1 那个 bug)。 */
    @Test
    void reassign_refusedWhenHasLiveOffers_bothDirections() {
        when(chapterMapper.selectCmsTopicChapterById(CH)).thenReturn(chapter(TermsMode.REVSHARE));
        when(offerMapper.selectByChapter(CH)).thenReturn(Arrays.asList(new MerchantChapterOffer()));

        assertThrows(IllegalStateException.class, () -> service.configureSupplyTerms(CH, terms(TermsMode.PERK)));
        verify(chapterMapper, never()).updateCmsTopicChapter(any());
    }

    // ------------------------------------------------- 形状:三个字段的「空」含义刻意不同

    /**
     * 🔴 ★★ <b>计酬档不配人头费上限 = 配了个谁也进不来的章。</b>
     *
     * <p>这条是本任务**发现得最晚、也最要命**的一道:`max_per_head_fee` 是 W4(#111)后加的列,
     * enroll 的 {@code assertFeeWithinChapterCap} 对 {@code cap == null} 是 <b>fail-closed 直接拒</b>
     * (刻意与 perkMinValue 的「空=不设门槛」相反:那是质量门槛,这是**平台成本上界**,留空=敞口无限)。
     * ⇒ 后台标了计酬档却没配上限,商家入驻**恒被拒**,而运营那边只看到「标成功了」——
     * 台账永远等不到一笔核销,零告警。
     *
     * <p>⚠️ 我一度以为链条通了(在 W4 合并**之前**验的),就是栽在这里。
     */
    @Test
    void revshare_withoutFeeCap_isRejected() {
        ChapterSupplyTermsDTO t = new ChapterSupplyTermsDTO();
        t.setTermsMode(TermsMode.REVSHARE);   // 刻意不设 maxPerHeadFee
        t.setMaxMerchant(0);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.configureSupplyTerms(CH, t));
        assertTrue(e.getMessage().contains("人头费上限"), "拒的理由必须指向上限缺失");
        verify(chapterMapper, never()).updateSupplyTerms(anyLong(), any(), any(), any(), any(), any());
    }

    /** 🔴 上限 ≤0 同样非法 —— 0 元人头费的「计酬档」不是计酬,是引流档伪装的。 */
    @Test
    void revshare_withNonPositiveFeeCap_isRejected() {
        ChapterSupplyTermsDTO t = new ChapterSupplyTermsDTO();
        t.setTermsMode(TermsMode.REVSHARE);
        t.setMaxPerHeadFee(java.math.BigDecimal.ZERO);
        t.setMaxMerchant(0);

        assertThrows(IllegalStateException.class, () -> service.configureSupplyTerms(CH, t));
    }

    /** 🔴 非计酬档不得带人头费上限 —— 留着它,哪天这章改回计酬档,旧上限会静默生效。 */
    @Test
    void nonRevshare_withFeeCap_isRejected() {
        ChapterSupplyTermsDTO t = new ChapterSupplyTermsDTO();
        t.setTermsMode(TermsMode.TRAFFIC);
        t.setMaxPerHeadFee(new java.math.BigDecimal("20.00"));
        t.setMaxMerchant(0);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.configureSupplyTerms(CH, t));
        assertTrue(e.getMessage().contains("只有计酬档谈人头费上限"));
    }

    /** 🔴 非权益档不得带权益门槛 —— 一行「引流档但挂着门槛」的数据说不清它到底承诺没承诺权益。 */
    @Test
    void nonPerk_withPerkThreshold_isRejected() {
        ChapterSupplyTermsDTO t = terms(TermsMode.REVSHARE);
        t.setPerkMinValue(new java.math.BigDecimal("30.00"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.configureSupplyTerms(CH, t));
        assertTrue(e.getMessage().contains("只有权益档有权益门槛"));
    }

    /** ★ 权益档留空门槛是**合法**的(空=不设门槛,与 enroll 的 assertPerkMeetsThreshold 同口径)。 */
    @Test
    void perk_withoutThreshold_isAllowed() {
        service.configureSupplyTerms(CH, terms(TermsMode.PERK));

        verify(chapterMapper).updateSupplyTerms(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void perk_nonPositiveThreshold_isRejected() {
        ChapterSupplyTermsDTO t = terms(TermsMode.PERK);
        t.setPerkMinValue(java.math.BigDecimal.ZERO);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.configureSupplyTerms(CH, t));

        assertTrue(e.getMessage().contains("权益最低价值"));
        verify(chapterMapper, never()).updateSupplyTerms(anyLong(), any(), any(), any(), any(), any());
    }

    /**
     * ★★ <b>四列必须无条件覆盖</b>(走 updateSupplyTerms 而非通用 updateCmsTopicChapter)。
     *
     * <p>通用那条是 {@code <if test="xx != null">} 逐列拼的 ⇒ 传 null **只跳过、不清空**
     * ⇒「计酬档改成引流档」会把 max_per_head_fee 留在库里,哪天改回计酬档旧上限静默生效。
     * 这条断言 null 是**真的作为值传下去**的。
     */
    @Test
    void configure_passesNullsThroughToClearOptionalColumns() {
        when(chapterMapper.selectCmsTopicChapterById(CH)).thenReturn(chapter(TermsMode.TRAFFIC));

        service.configureSupplyTerms(CH, terms(TermsMode.TRAFFIC));   // 三个可选字段全空

        verify(chapterMapper).updateSupplyTerms(eq(CH), eq(TermsMode.TRAFFIC),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                eq(0), any());
        verify(chapterMapper, never()).updateCmsTopicChapter(any());
    }

    /** max_merchant 是 NOT NULL 列；不限必须显式写 0，不能把数据库异常留给运营。 */
    @Test
    void configure_rejectsNullMaxMerchantBeforeUnconditionalWrite() {
        ChapterSupplyTermsDTO t = terms(TermsMode.TRAFFIC);
        t.setMaxMerchant(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.configureSupplyTerms(CH, t));

        assertTrue(e.getMessage().contains("不限请传0"));
        verify(chapterMapper, never()).updateSupplyTerms(anyLong(), any(), any(), any(), any(), any());
    }

    /** 已批准申请和已生效供给都已占位，不能把名额降到它们之下再让商家入驻时才失败。 */
    @Test
    void configure_rejectsCapBelowReservedMerchantUnion() {
        ChapterSupplyTermsDTO t = terms(TermsMode.TRAFFIC);
        t.setMaxMerchant(1);
        CmsTopicChapter existing = chapter(TermsMode.TRAFFIC);
        existing.setMaxMerchant(1);
        when(chapterMapper.selectCmsTopicChapterById(CH)).thenReturn(existing);
        MerchantChapterRecruitmentStats stats = new MerchantChapterRecruitmentStats();
        stats.setReservedMerchantCount(2);
        when(merchantChapterApplicationService.getRecruitmentStats(CH, 1)).thenReturn(stats);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.configureSupplyTerms(CH, t));

        assertTrue(e.getMessage().contains("占用2家"));
        verify(chapterMapper, never()).updateSupplyTerms(anyLong(), any(), any(), any(), any(), any());
    }

    /**
     * ★ <b>同档位下调上限不受「无生效供给」闸限制</b>。
     *
     * <p>这不是放宽,是修一个会真卡住运营的洞:max_per_head_fee 是后加的列 ⇒
     * **存量计酬章全是 NULL = 商家一个都进不来** ⇒ 最需要补配上限的恰恰是「已经在营」的章。
     * 若无条件拦,运营就永远补不上(要先下供给,可根本没有供给能下——商家进不来)。
     * 存量 offer 带的是入驻时的快照,同档位调上限不影响它们。
     */
    @Test
    void sameMode_adjustingCap_allowedEvenWithLiveOffers() {
        when(chapterMapper.selectCmsTopicChapterById(CH)).thenReturn(chapter(TermsMode.REVSHARE));
        when(offerMapper.selectByChapter(CH)).thenReturn(Arrays.asList(new MerchantChapterOffer()));

        service.configureSupplyTerms(CH, terms(TermsMode.REVSHARE));   // 档位没变,只调上限

        verify(chapterMapper).updateSupplyTerms(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void reassign_rejectsInvalidMode() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.configureSupplyTerms(CH, terms("BOGUS")));
        assertTrue(e.getMessage().contains("条款档不合法"));
    }

    /** 档位非法要在查库**之前**就拒 —— 别为一个必然失败的请求白跑一次查询。 */
    @Test
    void reassign_invalidModeRejectedBeforeAnyQuery() {
        assertThrows(IllegalStateException.class, () -> service.configureSupplyTerms(CH, terms(null)));

        verify(chapterMapper, never()).selectCmsTopicChapterById(anyLong());
    }
}
