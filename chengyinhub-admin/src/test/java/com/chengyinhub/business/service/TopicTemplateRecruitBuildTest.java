package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.domain.vo.TopicTemplateDetailVO;
import com.chengyinhub.business.service.impl.TopicQueryService;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * buildTopicTemplateDetail 商家招募/俱乐部承接扩展的核心算术测试。
 *
 * <p>重点负控:
 * 1) 计数源必须是新的章节申请 + 生效供给，不能再读旧 cms_registration_merchant;
 * 2) 非商家视角(viewerIsMerchant=false)不读任何招商统计,recruitStatus 全为 null;
 * 3) recruitEnabled 才是招商开关，maxMerchant=4 的存量章节不能自动出现在招募区;
 * 4) clubAdoptionStatus 只出状态(OPEN/CLAIMED),对两种视角都下发。
 */
class TopicTemplateRecruitBuildTest {

    private TopicQueryService service;
    private CmsTopicMapper cmsTopicMapper;
    private CmsTopicChapterMapper cmsTopicChapterMapper;
    private CmsTopicNodeMapper cmsTopicNodeMapper;
    private CmsMemberTemplateMapper cmsMemberTemplateMapper;
    private IMerchantChapterApplicationService merchantChapterApplicationService;
    private ISysCategoryService categoryService;

    private static final Long TOPIC = 42L;

    @BeforeEach
    void setUp() {
        service = new TopicQueryService();
        cmsTopicMapper = mock(CmsTopicMapper.class);
        cmsTopicChapterMapper = mock(CmsTopicChapterMapper.class);
        cmsTopicNodeMapper = mock(CmsTopicNodeMapper.class);
        cmsMemberTemplateMapper = mock(CmsMemberTemplateMapper.class);
        merchantChapterApplicationService = mock(IMerchantChapterApplicationService.class);
        categoryService = mock(ISysCategoryService.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", cmsTopicChapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", cmsTopicNodeMapper);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateMapper", cmsMemberTemplateMapper);
        // 模板元数据附表:本类不测元数据,给个空 mock 即可(返回 null/空列表 = 未录入,合法状态)。
        ReflectionTestUtils.setField(service, "cmsTopicTemplateMetaMapper",
                mock(com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper.class));
        ReflectionTestUtils.setField(service, "merchantChapterApplicationService", merchantChapterApplicationService);
        ReflectionTestUtils.setField(service, "sysCategoryService", categoryService);
        when(categoryService.selectSysCategoryById(18L)).thenReturn(activeCategory());
    }

    private CmsTopic topic(Long clubId) {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setName("赛博夜巡");
        t.setClubId(clubId);
        t.setProductType(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
        t.setStatus(1);
        t.setUserStatus(1);
        t.setLifecycle(1);
        t.setRecruitDeadline(new Date(System.currentTimeMillis() + 60_000));
        return t;
    }

    private CmsTopicChapter chapter(long id, int recruitEnabled, Integer maxMerchant, String termsMode) {
        CmsTopicChapter ch = new CmsTopicChapter();
        ch.setId(id);
        ch.setName("章" + id);
        ch.setRecruitEnabled(recruitEnabled);
        ch.setCategoryId(18);
        ch.setMaxMerchant(maxMerchant);
        ch.setTermsMode(termsMode);
        return ch;
    }

    private CmsTopicNode node(long id) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setName("节点" + id);
        return n;
    }

    private MerchantChapterRecruitmentStats stats(int pending, int approved, int activeOffers,
                                                   int reserved, Integer remaining) {
        MerchantChapterRecruitmentStats stats = new MerchantChapterRecruitmentStats();
        stats.setPendingApplicationCount(pending);
        stats.setApprovedApplicationCount(approved);
        stats.setActiveOfferCount(activeOffers);
        stats.setReservedMerchantCount(reserved);
        stats.setRemainingMerchantCount(remaining);
        return stats;
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

    @Test
    void merchant_view_uses_applicationAndOfferStats() {
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(null));
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Collections.singletonList(chapter(1L, 1, 2, null)));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Arrays.asList(node(11L), node(12L)));
        when(merchantChapterApplicationService.getRecruitmentStatsByChapters(Collections.singletonMap(1L, 2)))
                .thenReturn(Collections.singletonMap(1L, stats(1, 1, 1, 1, 1)));

        TopicTemplateDetailVO vo = service.buildTopicTemplateDetail(TOPIC, true);

        assertNotNull(vo.getChapters().get(0).getRecruitStatus());
        assertEquals(1, (int) vo.getChapters().get(0).getRecruitStatus().getCurrentOfferCount());
        assertEquals(1, (int) vo.getChapters().get(0).getRecruitStatus().getPendingApplicationCount());
        assertEquals(1, (int) vo.getChapters().get(0).getRecruitStatus().getApprovedApplicationCount());
        assertEquals(1, (int) vo.getChapters().get(0).getRecruitStatus().getActiveOfferCount());
        assertEquals(1, (int) vo.getChapters().get(0).getRecruitStatus().getRemainingMerchantCount());
        assertEquals(2, (int) vo.getChapters().get(0).getRecruitStatus().getMaxMerchant());
        assertTrue(vo.getChapters().get(0).getRecruitStatus().getIsOpen());
        // termsMode null → 按 PERK(D21 口径)
        assertEquals("PERK", vo.getChapters().get(0).getRecruitStatus().getTermsMode());
        assertTrue(vo.getViewerIsMerchant());
    }

    @Test
    void closed_recruitment_window_keeps_history_but_never_offers_an_application() {
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Collections.singletonList(chapter(1L, 1, 2, "PERK")));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        when(merchantChapterApplicationService.getRecruitmentStatsByChapters(Collections.singletonMap(1L, 2)))
                .thenReturn(Collections.singletonMap(1L, stats(0, 1, 0, 1, 1)));
        for (int scenario = 0; scenario < 4; scenario++) {
            CmsTopic closed = topic(null);
            if (scenario == 0) closed.setRecruitDeadline(new Date(0));
            if (scenario == 1) closed.setRecruitDeadline(null);
            if (scenario == 2) closed.setLifecycle(0);
            if (scenario == 3) closed.setLifecycle(null);
            when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(closed);
            com.chengyinhub.business.domain.vo.TopicTemplateRecruitVO recruit =
                    service.buildTopicTemplateDetail(TOPIC, true).getChapters().get(0).getRecruitStatus();
            assertFalse(recruit.getIsOpen(), "closed window scenario " + scenario);
            assertEquals(scenario == 3 ? "LOCKED" : "CLOSED", recruit.getState());
            assertEquals(1, recruit.getApprovedApplicationCount().intValue());
            assertEquals(0, recruit.getActiveOfferCount().intValue());
        }
    }

    @Test
    void v1_recruitment_hides_revshare_even_when_capacity_is_available() {
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(null));
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Collections.singletonList(chapter(1L, 1, 2, "REVSHARE")));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Arrays.asList(node(11L), node(12L)));

        TopicTemplateDetailVO vo = service.buildTopicTemplateDetail(TOPIC, true);

        assertNull(vo.getChapters().get(0).getRecruitStatus());
        verify(merchantChapterApplicationService, never()).getRecruitmentStatsByChapters(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void merchant_view_exposes_perk_threshold_for_v1_recruitment() {
        CmsTopicChapter chapter = chapter(1L, 1, 2, "PERK");
        chapter.setPerkMinValue(new BigDecimal("50.00"));
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(null));
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Collections.singletonList(chapter));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Collections.singletonList(node(11L)));
        when(merchantChapterApplicationService.getRecruitmentStatsByChapters(Collections.singletonMap(1L, 2)))
                .thenReturn(Collections.singletonMap(1L, stats(0, 0, 0, 0, 2)));

        TopicTemplateDetailVO vo = service.buildTopicTemplateDetail(TOPIC, true);

        assertEquals(new BigDecimal("50.00"), vo.getChapters().get(0).getRecruitStatus().getPerkMinValue());
    }

    @Test
    void merchant_view_hides_recruitment_when_legacy_perk_threshold_is_non_positive() {
        CmsTopicChapter chapter = chapter(1L, 1, 2, "PERK");
        chapter.setPerkMinValue(BigDecimal.ZERO);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(null));
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Collections.singletonList(chapter));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Collections.singletonList(node(11L)));

        TopicTemplateDetailVO vo = service.buildTopicTemplateDetail(TOPIC, true);

        assertNull(vo.getChapters().get(0).getRecruitStatus());
        verify(merchantChapterApplicationService, never()).getRecruitmentStatsByChapters(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void merchant_view_hides_recruitment_when_traffic_chapter_carries_perk_threshold() {
        CmsTopicChapter chapter = chapter(1L, 1, 2, "TRAFFIC");
        chapter.setPerkMinValue(BigDecimal.ONE);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(null));
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Collections.singletonList(chapter));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Collections.singletonList(node(11L)));

        TopicTemplateDetailVO vo = service.buildTopicTemplateDetail(TOPIC, true);

        assertNull(vo.getChapters().get(0).getRecruitStatus());
        verify(merchantChapterApplicationService, never()).getRecruitmentStatsByChapters(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void merchant_view_hides_recruitment_when_topic_has_been_deleted() {
        CmsTopic deleted = topic(null);
        deleted.setDelFlag(2);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(deleted);
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Collections.singletonList(chapter(1L, 1, 2, "PERK")));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Collections.singletonList(node(11L)));

        TopicTemplateDetailVO vo = service.buildTopicTemplateDetail(TOPIC, true);

        assertNull(vo.getChapters().get(0).getRecruitStatus());
        verify(merchantChapterApplicationService, never()).getRecruitmentStatsByChapters(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void non_merchant_view_never_queries_registrations_and_has_no_recruit() {
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(null));
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Collections.singletonList(chapter(1L, 1, 5, "PERK")));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Collections.singletonList(node(11L)));

        TopicTemplateDetailVO vo = service.buildTopicTemplateDetail(TOPIC, false);

        assertNull(vo.getChapters().get(0).getRecruitStatus());
        assertFalse(vo.getViewerIsMerchant());
        // 非商家视角一次都不许查招商统计(不是查了再藏)
        verify(merchantChapterApplicationService, never()).getRecruitmentStatsByChapters(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void chapter_disabledWithDefaultCapacity_has_no_recruit_even_for_merchant() {
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(null));
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Arrays.asList(chapter(1L, 0, 4, "PERK"), chapter(2L, 0, null, "PERK")));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Collections.singletonList(node(11L)));

        TopicTemplateDetailVO vo = service.buildTopicTemplateDetail(TOPIC, true);

        // 即使 maxMerchant=4（存量默认）也不能凭名额推导开放招商
        assertNull(vo.getChapters().get(0).getRecruitStatus());
        assertNull(vo.getChapters().get(1).getRecruitStatus());
        verify(merchantChapterApplicationService, never()).getRecruitmentStatsByChapters(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void merchant_category_filter_hides_non_matching_chapter_before_it_is_serialized() {
        CmsTopicChapter coffee = chapter(1L, 1, 2, "PERK");
        coffee.setCategoryId(18);
        CmsTopicChapter bookstore = chapter(2L, 1, 2, "TRAFFIC");
        bookstore.setCategoryId(19);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(null));
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Arrays.asList(coffee, bookstore));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Collections.singletonList(node(11L)));
        when(merchantChapterApplicationService.getRecruitmentStatsByChapters(Collections.singletonMap(1L, 2)))
                .thenReturn(Collections.singletonMap(1L, stats(0, 0, 0, 0, 2)));

        TopicTemplateDetailVO vo = service.buildTopicTemplateDetail(TOPIC, true, 18);

        assertNotNull(vo.getChapters().get(0).getRecruitStatus());
        assertNull(vo.getChapters().get(1).getRecruitStatus());
        verify(merchantChapterApplicationService).getRecruitmentStatsByChapters(Collections.singletonMap(1L, 2));
        verify(merchantChapterApplicationService, never()).getRecruitmentStatsByChapters(Collections.singletonMap(2L, 2));
    }

    @Test
    void recruitment_is_hidden_before_topic_is_published() {
        CmsTopic draft = topic(null);
        draft.setStatus(0);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(draft);
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Collections.singletonList(chapter(1L, 1, 2, "PERK")));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Collections.singletonList(node(11L)));
        TopicTemplateDetailVO vo = service.buildTopicTemplateDetail(TOPIC, true);

        assertNull(vo.getChapters().get(0).getRecruitStatus());
        verify(merchantChapterApplicationService, never()).getRecruitmentStatsByChapters(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void recruitment_is_hidden_without_a_matching_category() {
        CmsTopicChapter chapter = chapter(1L, 1, 2, "PERK");
        chapter.setCategoryId(null);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(null));
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Collections.singletonList(chapter));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Collections.singletonList(node(11L)));
        TopicTemplateDetailVO vo = service.buildTopicTemplateDetail(TOPIC, true);

        assertNull(vo.getChapters().get(0).getRecruitStatus());
        verify(merchantChapterApplicationService, never()).getRecruitmentStatsByChapters(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void recruitment_is_hidden_when_configuredCategoryHasBeenDisabled() {
        SysCategory disabled = activeCategory();
        disabled.setStatus(0);
        when(categoryService.selectSysCategoryById(18L)).thenReturn(disabled);
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(null));
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Collections.singletonList(chapter(1L, 1, 2, "PERK")));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Collections.singletonList(node(11L)));

        TopicTemplateDetailVO vo = service.buildTopicTemplateDetail(TOPIC, true);

        assertNull(vo.getChapters().get(0).getRecruitStatus());
        verify(merchantChapterApplicationService, never()).getRecruitmentStatsByChapters(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void recruitment_is_hidden_when_legacyCapacityIsInvalid() {
        CmsTopicChapter chapter = chapter(1L, 1, -1, "PERK");
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(null));
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Collections.singletonList(chapter));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Collections.singletonList(node(11L)));

        TopicTemplateDetailVO vo = service.buildTopicTemplateDetail(TOPIC, true);

        assertNull(vo.getChapters().get(0).getRecruitStatus());
        verify(merchantChapterApplicationService, never()).getRecruitmentStatsByChapters(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void club_adoption_status_from_club_id_for_both_views() {
        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(null));
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
        assertEquals("OPEN", service.buildTopicTemplateDetail(TOPIC, false).getClubAdoptionStatus());

        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(7L));
        assertEquals("CLAIMED", service.buildTopicTemplateDetail(TOPIC, true).getClubAdoptionStatus());
    }
}
