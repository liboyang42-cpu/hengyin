package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.impl.TopicQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UpgradePublicTopicStationCountTest {
    @Test
    void publicCardsUseOneCurrentAggregateIncludingDuplicatesAndZeroNodes() {
        TopicQueryService service = new TopicQueryService();
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);
        List<CmsTopic> page = Arrays.asList(topic(42), topic(43), topic(42));
        when(topics.selectPublicCmsTopicList(any())).thenReturn(page);
        when(topics.selectPublicNodeCountsByTopicIds(Arrays.asList(42L, 43L)))
                .thenReturn(Collections.singletonList(count(42L, 2L)));
        assertSame(page, service.selectPublicCmsTopicList(new CmsTopic()));
        assertEquals(Arrays.asList(2L, 0L, 2L), page.stream().map(CmsTopic::getLocationCount).toList());
        verify(topics).selectPublicNodeCountsByTopicIds(Arrays.asList(42L, 43L));
        verifyNoMoreInteractions(nodes);
    }

    @Test
    void emptyPageDoesNotQueryNodes() {
        TopicQueryService service = new TopicQueryService();
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);
        when(topics.selectPublicCmsTopicList(any())).thenReturn(Collections.emptyList());
        assertTrue(service.selectPublicCmsTopicList(new CmsTopic()).isEmpty());
        verifyNoInteractions(nodes);
    }

    @Test
    void managementCardsUseOneAggregateAndPreserveReturnedListIdentity() {
        TopicQueryService service = new TopicQueryService();
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);
        List<CmsTopic> page = Arrays.asList(topic(42), topic(43));
        CmsTopic ownerQuery = new CmsTopic(); ownerQuery.setMemberId(901L);
        when(topics.selectCmsTopicList(ownerQuery)).thenReturn(page);
        when(topics.selectPublicNodeCountsByTopicIds(Arrays.asList(42L, 43L)))
                .thenReturn(Collections.singletonList(count(42L, 7L)));
        assertSame(page, service.selectCmsTopicList(ownerQuery));
        assertEquals(Arrays.asList(7L, 0L), page.stream().map(CmsTopic::getLocationCount).toList());
        verify(topics).selectCmsTopicList(ownerQuery);
        verify(topics).selectPublicNodeCountsByTopicIds(Arrays.asList(42L, 43L));
        verifyNoMoreInteractions(topics, nodes);
    }

    @Test
    void merchantDetailPreservesDenialAndUsesZeroWhenNoEligibleNodes() {
        TopicQueryService service = new TopicQueryService();
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);
        assertNull(service.selectMerchantVisibleCmsTopicById(42L));
        assertNull(service.selectMerchantManagedCmsTopicById(42L, 901L));
        verifyNoInteractions(nodes);
        when(topics.selectMerchantVisibleCmsTopicById(42L)).thenReturn(topic(42));
        when(topics.selectMerchantManagedCmsTopicById(42L, 901L)).thenReturn(topic(42));
        assertEquals(0L, service.selectMerchantVisibleCmsTopicById(42L).getLocationCount());
        assertEquals(0L, service.selectMerchantManagedCmsTopicById(42L, 901L).getLocationCount());
        verify(topics, times(2)).selectPublicNodeCountsByTopicIds(Collections.singletonList(42L));
        verifyNoMoreInteractions(nodes);
    }

    @Test
    void publicTemplateShelfAddsOneAggregateRegardlessOfShelfSize() {
        for (int size : new int[]{0, 1, 5, 20}) {
            TopicQueryService service = new TopicQueryService();
            CmsTopicMapper topics = mock(CmsTopicMapper.class);
            CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
            ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
            ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);
            ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", mock(com.chengyinhub.business.mapper.CmsTopicChapterMapper.class));
            ReflectionTestUtils.setField(service, "cmsTopicTemplateMetaMapper", mock(com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper.class));
            List<CmsTopic> shelf = new ArrayList<>(); List<Long> ids = new ArrayList<>();
            for (long id = 1; id <= size; id++) { shelf.add(topic(id)); ids.add(id); }
            when(topics.selectTopicTemplateList()).thenReturn(shelf);
            when(topics.selectPublicNodeCountsByTopicIds(ids)).thenReturn(Collections.singletonList(
                    count(1L, 8L)));
            var result = service.buildTopicTemplateListVO(false);
            assertEquals(size, result.size());
            for (var item : result) assertEquals(item.getId() == 1L ? 8L : 0L, item.getLocationCount());
            verify(topics, times(size == 0 ? 0 : 1)).selectPublicNodeCountsByTopicIds(ids);
            // Existing per-row node reads enrich gameCount; they must never replace the total.
            verify(nodes, times(size)).selectCmsTopicNodeList(any());
            verifyNoMoreInteractions(nodes);
        }
    }

    private CmsTopic count(long id, long value) {
        CmsTopic row = topic(id); row.setLocationCount(value); row.setTemplateCount(0L); return row;
    }

    private CmsTopic topic(long id) {
        CmsTopic topic = new CmsTopic(); topic.setId(id); topic.setLocationCount(99L); return topic;
    }
}
