package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.util.TemplateStatus;
import com.github.pagehelper.Page;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Query owner and mapper call budgets; no executed MySQL or runtime performance claim. */
class TopicQueryIdBudgetTest {
    @Test void multipleCountBatchesKeepPageIdentityMetadataOrderDuplicateIdsNullIdAndMissingRowZeros() {
        Fixture f = new Fixture(); int unique = TopicQueryService.MAX_IDS_PER_QUERY * 2 + 1;
        Page<CmsTopic> page = new Page<>(); page.setPageNum(3); page.setPageSize(unique + 2); page.setTotal(5000);
        LongStream.rangeClosed(1, unique).forEach(id -> page.add(topic(id)));
        page.add(topic(1L)); page.add(topic(null));
        List<Long> originalIds = page.stream().map(CmsTopic::getId).toList();
        CmsTopic probe = new CmsTopic(); when(f.topics.selectPublicCmsTopicList(probe)).thenReturn(page);
        when(f.topics.selectPublicNodeCountsByTopicIds(anyList())).thenAnswer(i -> {
            List<Long> ids = i.getArgument(0); assertBounded(ids);
            List<CmsTopic> rows = new ArrayList<>();
            for (Long id : ids) if (id != null && id % 2 == 0) rows.add(count(id));
            Collections.reverse(rows); return rows;
        });

        assertSame(page, f.query.selectPublicCmsTopicList(probe));
        assertEquals(originalIds, page.stream().map(CmsTopic::getId).toList());
        assertEquals(3, page.getPageNum()); assertEquals(unique + 2, page.getPageSize()); assertEquals(5000L, page.getTotal());
        for (CmsTopic row : page) {
            boolean present = row.getId() != null && row.getId() % 2 == 0;
            assertEquals(Long.valueOf(present ? row.getId() * 2 : 0), row.getLocationCount());
            assertEquals(Long.valueOf(present ? row.getId() % 3 : 0), row.getTemplateCount());
        }
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(f.topics).selectPublicCmsTopicList(probe);
        verify(f.topics, times(3)).selectPublicNodeCountsByTopicIds(ids.capture());
        List<Long> expectedIds = new ArrayList<>(new LinkedHashSet<>(originalIds));
        assertEquals(expectedIds, ids.getAllValues().stream().flatMap(List::stream).toList());
        assertEquals(Arrays.asList(TopicQueryService.MAX_IDS_PER_QUERY, TopicQueryService.MAX_IDS_PER_QUERY, 2),
                ids.getAllValues().stream().map(List::size).toList());
        verifyNoMoreInteractions(f.topics); verifyNoInteractions(f.nodes, f.chapters, f.metadata);
    }

    @Test void laterCountBatchFailurePropagatesWithoutApplyingPartialOrCachedCounts() {
        Fixture f = new Fixture(); List<CmsTopic> page = new ArrayList<>();
        LongStream.rangeClosed(1, TopicQueryService.MAX_IDS_PER_QUERY + 1).forEach(id -> page.add(topic(id)));
        CmsTopic probe = new CmsTopic(); when(f.topics.selectCmsTopicList(probe)).thenReturn(page);
        IllegalStateException failure = new IllegalStateException("controlled later count failure");
        when(f.topics.selectPublicNodeCountsByTopicIds(anyList())).thenAnswer(i -> {
            List<Long> ids = i.getArgument(0); assertBounded(ids);
            if (ids.contains((long) TopicQueryService.MAX_IDS_PER_QUERY + 1)) throw failure;
            return Collections.singletonList(count(1L));
        });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> f.query.selectCmsTopicList(probe)));
        assertTrue(page.stream().allMatch(row -> Long.valueOf(991L).equals(row.getLocationCount())),
                "counts are projected only after every batch succeeds");
        verify(f.topics, times(2)).selectPublicNodeCountsByTopicIds(anyList());
        verifyNoInteractions(f.metadata, f.nodes, f.chapters);
    }

    @Test void shelfMetadataBatchesRetainAllCardsMissingMetadataRecommendationAndOfficialStableSort() {
        int count = TopicQueryService.MAX_IDS_PER_QUERY * 2 + 1;
        for (boolean recommended : new boolean[]{false, true}) {
            Fixture f = new Fixture(); List<CmsTopic> shelf = new ArrayList<>();
            for (long id = count; id >= 1; id--) shelf.add(topic(id));
            List<Long> originalIds = shelf.stream().map(CmsTopic::getId).toList();
            when(f.topics.selectTopicTemplateList()).thenReturn(shelf);
            when(f.metadata.selectByTopicIds(anyList())).thenAnswer(i -> {
                List<Long> ids = i.getArgument(0); assertBounded(ids);
                List<CmsTopicTemplateMeta> rows = new ArrayList<>();
                for (Long id : ids) if (id % 7 != 0) rows.add(meta(id));
                Collections.reverse(rows); return rows;
            });

            var cards = f.query.buildTopicTemplateListVO(recommended);

            List<Long> admitted = originalIds.stream().filter(id -> !recommended || (id % 7 != 0 && id % 2 == 0)).toList();
            List<Long> expected = new ArrayList<>(admitted.stream().filter(id -> id % 7 != 0 && id % 11 == 0).toList());
            expected.addAll(admitted.stream().filter(id -> id % 7 == 0 || id % 11 != 0).toList());
            assertEquals(expected, cards.stream().map(v -> v.getId()).toList());
            assertEquals(originalIds, shelf.stream().map(CmsTopic::getId).toList(), "the admitted source shelf is not sorted in place");
            assertTrue(cards.stream().allMatch(v -> v.getChapterCount() == 0 && v.getGameCount() == 0));
            if (!recommended) {
                assertEquals(count, cards.size());
                assertTrue(cards.stream().filter(v -> v.getId() % 7 == 0).allMatch(v -> v.getTemplateStatus() == null && v.getIsOfficial() == null));
            }
            @SuppressWarnings("unchecked") ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
            verify(f.metadata, times(3)).selectByTopicIds(ids.capture());
            assertEquals(originalIds, ids.getAllValues().stream().flatMap(List::stream).toList());
            assertEquals(Arrays.asList(TopicQueryService.MAX_IDS_PER_QUERY, TopicQueryService.MAX_IDS_PER_QUERY, 1),
                    ids.getAllValues().stream().map(List::size).toList());
            verifyNoMoreInteractions(f.metadata);
            verify(f.topics, times(3)).selectPublicNodeCountsByTopicIds(anyList());
            verify(f.chapters, times(count)).selectCmsTopicChapterList(any());
            verify(f.nodes, times(count)).selectCmsTopicNodeList(any()); // Remaining shelf N+1 explicitly retained.
        }
    }

    @Test void laterMetadataStorageErrorCannotBecomeAnEmptyRecommendedShelf() {
        Fixture f = new Fixture(); List<CmsTopic> shelf = new ArrayList<>();
        LongStream.rangeClosed(1, TopicQueryService.MAX_IDS_PER_QUERY + 1).forEach(id -> shelf.add(topic(id)));
        when(f.topics.selectTopicTemplateList()).thenReturn(shelf);
        IllegalStateException failure = new IllegalStateException("controlled later metadata failure");
        when(f.metadata.selectByTopicIds(anyList())).thenAnswer(i -> {
            List<Long> ids = i.getArgument(0); assertBounded(ids);
            if (ids.contains((long) TopicQueryService.MAX_IDS_PER_QUERY + 1)) throw failure;
            return Collections.singletonList(meta(2L));
        });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> f.query.buildTopicTemplateListVO(true)));
        verify(f.metadata, times(2)).selectByTopicIds(anyList()); verifyNoMoreInteractions(f.metadata);
    }

    @Test void emptyPageAndShelfDoNotExecuteAnyEmptyInOrUnscopedChildQuery() {
        Fixture f = new Fixture(); CmsTopic probe = new CmsTopic(); List<CmsTopic> page = Collections.emptyList();
        when(f.topics.selectPublicCmsTopicList(probe)).thenReturn(page);
        when(f.topics.selectTopicTemplateList()).thenReturn(Collections.emptyList());
        assertSame(page, f.query.selectPublicCmsTopicList(probe)); assertTrue(f.query.buildTopicTemplateListVO().isEmpty());
        verify(f.topics).selectPublicCmsTopicList(probe); verify(f.topics).selectTopicTemplateList();
        verifyNoMoreInteractions(f.topics); verifyNoInteractions(f.metadata, f.nodes, f.chapters);
    }

    private static void assertBounded(List<Long> ids) { assertFalse(ids.isEmpty()); assertTrue(ids.size() <= TopicQueryService.MAX_IDS_PER_QUERY); }
    private static CmsTopic topic(Long id) { CmsTopic row = new CmsTopic(); row.setId(id); row.setLocationCount(991L); row.setTemplateCount(991L); return row; }
    private static CmsTopic count(Long id) { CmsTopic row = new CmsTopic(); row.setId(id); row.setLocationCount(id * 2); row.setTemplateCount(id % 3); return row; }
    private static CmsTopicTemplateMeta meta(Long id) {
        CmsTopicTemplateMeta row = new CmsTopicTemplateMeta(); row.setTopicId(id); row.setIsOfficial(id % 11 == 0 ? 1 : 0);
        row.setTemplateStatus(id % 2 == 0 ? TemplateStatus.VERIFIED : null); return row;
    }

    private static class Fixture {
        final TopicQueryService query = new TopicQueryService();
        final CmsTopicMapper topics = mock(CmsTopicMapper.class);
        final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        final CmsTopicTemplateMetaMapper metadata = mock(CmsTopicTemplateMetaMapper.class);
        Fixture() {
            ReflectionTestUtils.setField(query, "cmsTopicMapper", topics); ReflectionTestUtils.setField(query, "cmsTopicChapterMapper", chapters);
            ReflectionTestUtils.setField(query, "cmsTopicNodeMapper", nodes); ReflectionTestUtils.setField(query, "cmsTopicTemplateMetaMapper", metadata);
        }
    }
}
