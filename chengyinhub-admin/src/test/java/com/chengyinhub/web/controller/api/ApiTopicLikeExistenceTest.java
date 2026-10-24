package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicLike;
import com.chengyinhub.business.service.ICmsTopicLikeService;
import com.chengyinhub.business.service.ICmsTopicService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

class ApiTopicLikeExistenceTest
{
    private ApiTopicController controller;
    private ICmsTopicService topics;
    private ICmsTopicLikeService likes;

    @BeforeEach
    void setUp()
    {
        controller = spy(new ApiTopicController());
        topics = mock(ICmsTopicService.class);
        likes = mock(ICmsTopicLikeService.class);
        doReturn(19L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
        ReflectionTestUtils.setField(controller, "cmsTopicLikeService", likes);
        when(likes.selectCmsTopicLikeList(any())).thenReturn(Collections.emptyList());
        when(likes.insertCmsTopicLike(any())).thenReturn(1);
    }

    @Test
    void missingTopicCannotCreateDanglingLike()
    {
        assertNotEquals(200, controller.topicLike("42", "1").get("code"));
        verify(likes, never()).setLiked(any(), any(), anyBoolean());
    }

    @Test
    void deletedTopicCannotCreateLike()
    {
        CmsTopic topic = new CmsTopic();
        topic.setDelFlag(1);
        when(topics.selectCmsTopicById(42L)).thenReturn(topic);
        assertNotEquals(200, controller.topicLike("42", "1").get("code"));
        verify(likes, never()).setLiked(any(), any(), anyBoolean());
    }

    @Test
    void existingTopicCanStillBeLiked()
    {
        CmsTopic topic = new CmsTopic();
        topic.setDelFlag(0);
        when(topics.selectCmsTopicById(42L)).thenReturn(topic);
        assertEquals(200, controller.topicLike("42", "1").get("code"));
        verify(likes).setLiked(19L, 42L, true);
    }

    @Test
    void cancellationResponseLossRetryKeepsTheDesiredFalseStateAndRejectsInvalidType() {
        assertEquals(200, controller.topicLike("42", "0").get("code"));
        assertEquals(200, controller.topicLike("42", "0").get("code"));
        verify(likes, times(2)).setLiked(19L, 42L, false);
        assertNotEquals(200, controller.topicLike("42", "2").get("code"));
        verify(likes, never()).setLiked(19L, 42L, true);
    }

    @Test
    void staleLikeCanStillBeRemovedAfterTopicDisappears()
    {
        CmsTopicLike old = new CmsTopicLike();
        old.setId(8L);
        when(likes.selectCmsTopicLikeList(any())).thenReturn(Collections.singletonList(old));
        when(likes.deleteCmsTopicLikeByIds(any())).thenReturn(1);
        assertEquals(200, controller.topicLike("42", "0").get("code"));
        verify(likes).setLiked(19L, 42L, false);
        verify(likes, never()).setLiked(19L, 42L, true);
    }
}
