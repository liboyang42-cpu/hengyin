package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicLike;
import com.chengyinhub.business.service.ICmsTopicLikeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B4:/api/topic/like 按 type 方向幂等;type 缺省保留旧切换语义。 */
class ApiTopicLikeDirectionTest
{
    private ICmsTopicLikeService likeService;
    private ApiTopicController controller;

    @BeforeEach
    void setUp()
    {
        likeService = mock(ICmsTopicLikeService.class);
        controller = spy(new ApiTopicController());
        doReturn(7L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicLikeService", likeService);
        when(likeService.insertCmsTopicLike(any())).thenReturn(1);
        when(likeService.deleteCmsTopicLikeByIds(any())).thenReturn(1);
    }

    private void existing(boolean exists)
    {
        CmsTopicLike row = new CmsTopicLike();
        row.setId(99L);
        when(likeService.selectCmsTopicLikeList(any()))
                .thenReturn(exists ? Collections.singletonList(row) : Collections.<CmsTopicLike>emptyList());
    }

    @Test
    void likeWhenExistingIsIdempotentSuccessWithoutInsert()
    {
        existing(true);
        assertEquals(200, controller.topicLike("5", "1").get("code"));
        verify(likeService, never()).insertCmsTopicLike(any());
        verify(likeService, never()).deleteCmsTopicLikeByIds(any());
    }

    @Test
    void likeWhenAbsentInserts()
    {
        existing(false);
        assertEquals(200, controller.topicLike("5", "1").get("code"));
        verify(likeService).insertCmsTopicLike(any());
    }

    @Test
    void unlikeWhenAbsentIsSuccessAndDoesNotInsert()
    {
        existing(false);
        assertEquals(200, controller.topicLike("5", "0").get("code"));
        verify(likeService, never()).insertCmsTopicLike(any());
    }

    @Test
    void unlikeWhenExistingDeletes()
    {
        existing(true);
        assertEquals(200, controller.topicLike("5", "0").get("code"));
        verify(likeService).deleteCmsTopicLikeByIds(new Long[] { 99L });
        verify(likeService, never()).insertCmsTopicLike(any());
    }

    @Test
    void missingTypeKeepsLegacyToggle()
    {
        existing(true);
        controller.topicLike("5", null);
        verify(likeService).deleteCmsTopicLikeByIds(any());

        existing(false);
        controller.topicLike("5", null);
        verify(likeService).insertCmsTopicLike(any());
    }
}
