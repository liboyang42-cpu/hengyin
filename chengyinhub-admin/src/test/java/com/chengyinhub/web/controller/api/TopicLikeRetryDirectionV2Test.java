package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicLikeMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.impl.CmsTopicLikeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/** CL-21 / F-R2S-11: the request's type decides the direction; a retry never toggles. */
class TopicLikeRetryDirectionV2Test {
    private ApiTopicController controller;
    private CmsTopicLikeMapper mapper;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiTopicController());
        doReturn(19L).when(controller).getAppUserId();
        ICmsTopicService topics = mock(ICmsTopicService.class);
        CmsTopic topic = new CmsTopic();
        topic.setDelFlag(0);
        when(topics.selectCmsTopicById(42L)).thenReturn(topic);
        mapper = mock(CmsTopicLikeMapper.class);
        CmsTopicLikeServiceImpl likes = new CmsTopicLikeServiceImpl();
        ReflectionTestUtils.setField(likes, "cmsTopicLikeMapper", mapper);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
        ReflectionTestUtils.setField(controller, "cmsTopicLikeService", likes);
    }

    @Test
    void repeatedLikeOnlyEnsuresAndRepeatedUnlikeOnlyDeletes() {
        assertEquals(200, controller.topicLike("42", "1").get("code"));
        assertEquals(200, controller.topicLike("42", "1").get("code"));
        verify(mapper, times(2)).ensureOwnedLike(19L, 42L);
        verify(mapper, never()).deleteOwnedLike(any(), any());

        assertEquals(200, controller.topicLike("42", "0").get("code"));
        assertEquals(200, controller.topicLike("42", "0").get("code"));
        verify(mapper, times(2)).deleteOwnedLike(19L, 42L);
        verify(mapper, times(2)).ensureOwnedLike(19L, 42L);
    }

    @Test
    void likeUnlikeLikeEndsWithAnEnsureAsTheLastWrite() {
        controller.topicLike("42", "1");
        controller.topicLike("42", "0");
        controller.topicLike("42", "1");
        InOrder order = inOrder(mapper);
        order.verify(mapper).ensureOwnedLike(19L, 42L);
        order.verify(mapper).deleteOwnedLike(19L, 42L);
        order.verify(mapper).ensureOwnedLike(19L, 42L);
        verifyNoMoreInteractions(mapper);
    }
}
