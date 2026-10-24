package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats;
import com.chengyinhub.business.domain.vo.TopicTemplateRecruitVO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.service.support.TopicSizeLimits;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Query + original statistics service; mapper doubles establish call counts, not MySQL execution. */
class TopicQueryRecruitmentBatchTest {
    @Test void originalSingleChapterShadowAndRealBatchAgreeWhileNineStatisticsReadsBecomeThree() {
        Fixture f = new Fixture();
        f.chapterRows.addAll(Arrays.asList(chapter(11L, 4), chapter(12L, 1), chapter(13L, 0)));
        f.approved.addAll(Arrays.asList(application(11L, 10L), application(11L, 11L), application(12L, 20L)));
        f.pending.add(application(11L, 90L));
        f.live.addAll(Arrays.asList(offer(11L, 11L), offer(11L, 12L), offer(12L, 20L)));
        List<List<Integer>> shadow = new ArrayList<>();
        for (CmsTopicChapter chapter : f.chapterRows)
            shadow.add(counts(f.statistics.getRecruitmentStats(chapter.getId(), chapter.getMaxMerchant())));
        verify(f.applications, times(6)).selectByChapterAndStatus(anyLong(), anyInt());
        verify(f.offers, times(3)).selectByChapter(anyLong());
        clearInvocations(f.applications, f.offers);

        var detail = f.query.buildPublicTopicTemplateDetail(42L, null, true, 18);

        assertEquals(Arrays.asList(11L, 12L, 13L), detail.getChapters().stream().map(v -> v.getId()).toList());
        assertEquals(shadow, detail.getChapters().stream().map(v -> counts(v.getRecruitStatus())).toList());
        assertEquals(Arrays.asList("OPEN", "FULL", "OPEN"), detail.getChapters().stream().map(v -> v.getRecruitStatus().getState()).toList());
        assertNull(detail.getChapters().get(2).getRecruitStatus().getRemainingMerchantCount());
        assertTrue(detail.getChapters().get(0).getRecruitStatus().getIsOpen());
        assertFalse(detail.getChapters().get(1).getRecruitStatus().getIsOpen());
        verify(f.applications).selectByChapterIdsAndStatus(Arrays.asList(11L, 12L, 13L), MerchantChapterApplication.STATUS_APPROVED);
        verify(f.applications).selectByChapterIdsAndStatus(Arrays.asList(11L, 12L, 13L), MerchantChapterApplication.STATUS_PENDING);
        verify(f.offers).selectLiveByChapterIds(Arrays.asList(11L, 12L, 13L));
        verifyNoMoreInteractions(f.applications, f.offers);
    }

    @Test void chapterLimitBoundsEveryStatisticsBatchWithoutTruncatingOversizedLegacyOutput() {
        Fixture f = new Fixture(); int count = TopicSizeLimits.MAX_CHAPTERS * 2 + 1;
        for (long id = 1; id <= count; id++) f.chapterRows.add(chapter(id, 4));
        var detail = f.query.buildPublicTopicTemplateDetail(42L, null, true, 18);
        assertEquals(count, detail.getChapters().size());
        assertEquals(f.chapterRows.stream().map(CmsTopicChapter::getId).toList(), detail.getChapters().stream().map(v -> v.getId()).toList());
        assertTrue(detail.getChapters().stream().allMatch(v -> "OPEN".equals(v.getRecruitStatus().getState())));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(f.offers, times(3)).selectLiveByChapterIds(ids.capture());
        assertEquals(Arrays.asList(TopicSizeLimits.MAX_CHAPTERS, TopicSizeLimits.MAX_CHAPTERS, 1),
                ids.getAllValues().stream().map(List::size).toList());
        assertEquals(f.chapterRows.stream().map(CmsTopicChapter::getId).toList(),
                ids.getAllValues().stream().flatMap(List::stream).toList());
        verify(f.applications, times(6)).selectByChapterIdsAndStatus(anyList(), anyInt());
        verifyNoMoreInteractions(f.applications, f.offers);
    }

    @Test void anonymousUnpublishedDisabledAndInactiveCategoryChaptersNeverReachStatistics() {
        for (int variant = 0; variant < 4; variant++) {
            Fixture f = new Fixture(); CmsTopicChapter chapter = chapter(11L, 4); f.chapterRows.add(chapter);
            if (variant == 1) f.topic.setStatus(0);
            if (variant == 2) chapter.setRecruitEnabled(0);
            if (variant == 3) f.category.setStatus(0);
            var detail = variant == 1 ? f.query.buildTopicTemplateDetail(42L, true, 18)
                    : f.query.buildPublicTopicTemplateDetail(42L, null, variant != 0, 18);
            assertNull(detail.getChapters().get(0).getRecruitStatus());
            assertNull(detail.getChapters().get(0).getEffort());
            verifyNoInteractions(f.applications, f.offers);
        }
        Fixture scoped = new Fixture();
        CmsTopicChapter admitted = chapter(11L, 4), hidden = chapter(12L, 4); hidden.setCategoryId(19);
        scoped.chapterRows.addAll(Arrays.asList(admitted, hidden));
        var detail = scoped.query.buildPublicTopicTemplateDetail(42L, null, true, 18);
        assertNotNull(detail.getChapters().get(0).getRecruitStatus()); assertNull(detail.getChapters().get(1).getRecruitStatus());
        verify(scoped.offers).selectLiveByChapterIds(Collections.singletonList(11L));
        verify(scoped.categories, never()).selectSysCategoryById(19L);
        verify(scoped.applications).selectByChapterIdsAndStatus(Collections.singletonList(11L), MerchantChapterApplication.STATUS_APPROVED);
        verify(scoped.applications).selectByChapterIdsAndStatus(Collections.singletonList(11L), MerchantChapterApplication.STATUS_PENDING);
        verifyNoMoreInteractions(scoped.applications, scoped.offers);
    }

    @Test void realStatisticsStorageExceptionPropagatesWithoutReturningPartiallyPopulatedViews() {
        Fixture f = new Fixture(); f.chapterRows.addAll(Arrays.asList(chapter(11L, 4), chapter(12L, 4)));
        IllegalStateException failure = new IllegalStateException("controlled statistics storage failure");
        when(f.applications.selectByChapterIdsAndStatus(anyList(), eq(MerchantChapterApplication.STATUS_APPROVED))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> f.query.buildPublicTopicTemplateDetail(42L, null, true, 18)));
        verifyNoInteractions(f.offers);
    }

    @Test void missingBatchContractResultCannotBecomeZeroOccupancyOrAUsableEmptySuccess() {
        for (boolean missingMap : new boolean[]{false, true}) {
            Fixture f = new Fixture(); f.chapterRows.add(chapter(11L, 4));
            IMerchantChapterApplicationService broken = mock(IMerchantChapterApplicationService.class);
            when(broken.getRecruitmentStatsByChapters(anyMap())).thenReturn(missingMap ? null : Collections.emptyMap());
            ReflectionTestUtils.setField(f.query, "merchantChapterApplicationService", broken);
            assertThrows(IllegalStateException.class, () -> f.query.buildPublicTopicTemplateDetail(42L, null, true, 18));
            verify(broken).getRecruitmentStatsByChapters(Collections.singletonMap(11L, 4)); verifyNoMoreInteractions(broken);
        }
    }

    @Test void originalNullChapterContractUsesPureSingleChapterZeroCaseWithoutAnyMapperCalls() {
        Fixture f = new Fixture(); f.chapterRows.add(chapter(null, 4));
        var detail = f.query.buildPublicTopicTemplateDetail(42L, null, true, 18);
        assertEquals(Collections.singletonList(Arrays.asList(0, 0, 0, 0, 4)),
                detail.getChapters().stream().map(v -> counts(v.getRecruitStatus())).toList());
        verifyNoInteractions(f.applications, f.offers);
    }

    private static List<Integer> counts(MerchantChapterRecruitmentStats row) {
        return Arrays.asList(row.getReservedMerchantCount(), row.getPendingApplicationCount(), row.getApprovedApplicationCount(), row.getActiveOfferCount(), row.getRemainingMerchantCount());
    }
    private static List<Integer> counts(TopicTemplateRecruitVO row) {
        return Arrays.asList(row.getCurrentOfferCount(), row.getPendingApplicationCount(), row.getApprovedApplicationCount(), row.getActiveOfferCount(), row.getRemainingMerchantCount());
    }
    private static CmsTopicChapter chapter(Long id, Integer limit) {
        CmsTopicChapter row = new CmsTopicChapter(); row.setId(id); row.setCategoryId(18); row.setRecruitEnabled(1);
        row.setMaxMerchant(limit); row.setTermsMode("PERK"); row.setPerkMinValue(BigDecimal.valueOf(30)); return row;
    }
    private static MerchantChapterApplication application(Long chapterId, Long merchantId) {
        MerchantChapterApplication row = new MerchantChapterApplication(); row.setChapterId(chapterId); row.setMerchantId(merchantId); return row;
    }
    private static MerchantChapterOffer offer(Long chapterId, Long merchantId) {
        MerchantChapterOffer row = new MerchantChapterOffer(); row.setChapterId(chapterId); row.setMerchantId(merchantId); return row;
    }

    private static class Fixture {
        final TopicQueryService query = new TopicQueryService();
        final MerchantChapterApplicationServiceImpl statistics = new MerchantChapterApplicationServiceImpl();
        final MerchantChapterApplicationMapper applications = mock(MerchantChapterApplicationMapper.class);
        final MerchantChapterOfferMapper offers = mock(MerchantChapterOfferMapper.class);
        final ISysCategoryService categories = mock(ISysCategoryService.class);
        final CmsTopic topic = new CmsTopic(); final SysCategory category = new SysCategory();
        final List<CmsTopicChapter> chapterRows = new ArrayList<>();
        final List<MerchantChapterApplication> approved = new ArrayList<>(), pending = new ArrayList<>();
        final List<MerchantChapterOffer> live = new ArrayList<>();

        Fixture() {
            CmsTopicMapper topics = mock(CmsTopicMapper.class); CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
            ReflectionTestUtils.setField(query, "cmsTopicMapper", topics); ReflectionTestUtils.setField(query, "cmsTopicChapterMapper", chapters);
            ReflectionTestUtils.setField(query, "cmsTopicNodeMapper", mock(CmsTopicNodeMapper.class));
            ReflectionTestUtils.setField(query, "cmsMemberTemplateMapper", mock(CmsMemberTemplateMapper.class));
            ReflectionTestUtils.setField(query, "cmsTopicTemplateMetaMapper", mock(CmsTopicTemplateMetaMapper.class));
            ReflectionTestUtils.setField(query, "sysCategoryService", categories); ReflectionTestUtils.setField(query, "merchantChapterApplicationService", statistics);
            ReflectionTestUtils.setField(statistics, "applicationMapper", applications); ReflectionTestUtils.setField(statistics, "offerMapper", offers);
            topic.setId(42L); topic.setMemberId(9L); topic.setProductType(ProductType.FREE_EXPLORE);
            topic.setStatus(1); topic.setUserStatus(1); topic.setLifecycle(1); topic.setRecruitDeadline(new Date(4102444800000L));
            when(topics.selectPublicTopicTemplateById(42L)).thenReturn(topic);
            when(topics.selectCmsTopicById(42L)).thenReturn(topic);
            when(chapters.selectCmsTopicChapterList(any())).thenReturn(chapterRows);
            category.setId(18L); category.setType(1L); category.setParentId(0L); category.setStatus(1); category.setDelFlag(0);
            when(categories.selectSysCategoryById(18L)).thenReturn(category);
            when(applications.selectByChapterAndStatus(anyLong(), anyInt())).thenAnswer(i -> applicationRows(
                    Collections.singletonList(i.getArgument(0)), i.getArgument(1)));
            when(applications.selectByChapterIdsAndStatus(anyList(), anyInt())).thenAnswer(i -> applicationRows(i.getArgument(0), i.getArgument(1)));
            when(offers.selectByChapter(anyLong())).thenAnswer(i -> offerRows(Collections.singletonList(i.getArgument(0))));
            when(offers.selectLiveByChapterIds(anyList())).thenAnswer(i -> offerRows(i.getArgument(0)));
        }

        private List<MerchantChapterApplication> applicationRows(List<Long> ids, Integer status) {
            return (status == MerchantChapterApplication.STATUS_APPROVED ? approved : pending).stream().filter(row -> ids.contains(row.getChapterId())).toList();
        }
        private List<MerchantChapterOffer> offerRows(List<Long> ids) { return live.stream().filter(row -> ids.contains(row.getChapterId())).toList(); }
    }
}
