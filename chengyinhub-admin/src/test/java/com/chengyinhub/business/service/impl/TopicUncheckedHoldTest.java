package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.util.TopicContentSafetyHold;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Round5B-3:机审未完成的主题下架留置并挂复核上架单,复用内容安全留置(审核通过自动上架)。 */
class TopicUncheckedHoldTest {
    private final CmsTopicMapper mapper = mock(CmsTopicMapper.class);
    private final TopicPublicationService publication = mock(TopicPublicationService.class);

    private CmsTopicServiceImpl service() {
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mapper);
        ReflectionTestUtils.setField(service, "topicPublicationService", publication);
        return service;
    }

    private CmsTopic online() {
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setMemberId(9L);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setCheckStatus(1);
        return topic;
    }

    @Test
    void publishedTopicIsTakenOfflineHeldAndQueuedForRelistReview() {
        when(mapper.selectCmsTopicById(7L)).thenReturn(online());
        when(mapper.updateCmsTopic(any())).thenReturn(1);
        when(mapper.selectTopicIsDraft(7L)).thenReturn(0);
        when(publication.requestContentSafetyRelistReview(7L, 9L)).thenReturn(55L);

        assertEquals(Long.valueOf(55L), service().holdUncheckedTopicForReview(7L, 9L));

        ArgumentCaptor<CmsTopic> patch = ArgumentCaptor.forClass(CmsTopic.class);
        verify(mapper).updateCmsTopic(patch.capture());
        assertEquals(Integer.valueOf(0), patch.getValue().getUserStatus());
        assertEquals(Integer.valueOf(2), patch.getValue().getCheckStatus());
        assertEquals(TopicContentSafetyHold.REASON_UNCHECKED, patch.getValue().getReason());
        CmsTopic after = online();
        after.setCheckStatus(2);
        assertTrue(TopicContentSafetyHold.isHeld(after));
    }

    @Test
    void draftIsHeldWithoutTaskSoPublishDraftTakesTheHeldBranch() {
        when(mapper.selectCmsTopicById(7L)).thenReturn(online());
        when(mapper.updateCmsTopic(any())).thenReturn(1);
        when(mapper.selectTopicIsDraft(7L)).thenReturn(1);

        assertNull(service().holdUncheckedTopicForReview(7L, 9L));
        verify(mapper).updateCmsTopic(any());
        verify(publication, never()).requestContentSafetyRelistReview(anyLong(), any());
    }

    @Test
    void rejectedTopicKeepsItsRejectionReason() {
        CmsTopic rejected = online();
        rejected.setStatus(2);
        when(mapper.selectCmsTopicById(7L)).thenReturn(rejected);

        assertNull(service().holdUncheckedTopicForReview(7L, 9L));
        verify(mapper, never()).updateCmsTopic(any());
    }
}
