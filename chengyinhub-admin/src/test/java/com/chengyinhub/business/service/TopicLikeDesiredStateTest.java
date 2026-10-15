package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.CmsTopicLikeMapper;
import com.chengyinhub.business.service.impl.CmsTopicLikeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TopicLikeDesiredStateTest {
    @Test
    void retrySetsTheSamePairAndNeverTogglesOrDeletesAnotherMembersIds() {
        CmsTopicLikeMapper mapper = mock(CmsTopicLikeMapper.class);
        CmsTopicLikeServiceImpl service = new CmsTopicLikeServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicLikeMapper", mapper);
        service.setLiked(101L, 201L, true);
        service.setLiked(101L, 201L, true);
        service.setLiked(101L, 201L, false);
        service.setLiked(101L, 201L, false);
        verify(mapper, times(2)).ensureOwnedLike(101L, 201L);
        verify(mapper, times(2)).deleteOwnedLike(101L, 201L);
        verifyNoMoreInteractions(mapper);
        assertThrows(IllegalArgumentException.class, () -> service.setLiked(null, 201L, true));
    }
}
