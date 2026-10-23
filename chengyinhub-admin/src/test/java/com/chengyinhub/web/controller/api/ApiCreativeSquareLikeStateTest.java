package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.UmsCreativeSquareLike;
import com.chengyinhub.business.service.IUmsCreativeSquareLikeService;
import com.chengyinhub.business.service.ContentInteractionGuard;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-56:/api/creativesquare/like 按「请求态度 × 现有态度」决定结果,
 * 已赞时点踩应改成踩(原实现一律删除,落成无态度),回包带权威 isLiked。
 */
class ApiCreativeSquareLikeStateTest {

    private static final Long ME = 9L;

    private static class StubController extends ApiCreativeSquareController {
        @Override
        public Long getAppUserId() {
            return ME;
        }
    }

    private StubController controller;
    private IUmsCreativeSquareLikeService likes;

    @BeforeEach
    void setUp() {
        controller = new StubController();
        ReflectionTestUtils.setField(controller, "legacyCommunityWritesEnabled", true);
        likes = mock(IUmsCreativeSquareLikeService.class);
        when(likes.insertUmsCreativeSquareLike(any())).thenReturn(1);
        when(likes.updateUmsCreativeSquareLike(any())).thenReturn(1);
        ContentInteractionGuard guard = mock(ContentInteractionGuard.class);
        when(guard.lockSquare(anyLong())).thenReturn(null);
        ReflectionTestUtils.setField(controller, "umsCreativeSquareLikeService", likes);
        ReflectionTestUtils.setField(controller, "contentInteractionGuard", guard);
    }

    private static UmsCreativeSquareLike row(long id, int type) {
        UmsCreativeSquareLike like = new UmsCreativeSquareLike();
        like.setId(id);
        like.setMemberId(ME);
        like.setDataId(10L);
        like.setLikeType(type);
        return like;
    }

    @SuppressWarnings("unchecked")
    private static Object isLiked(AjaxResult result) {
        return ((Map<String, Object>) result.get(AjaxResult.DATA_TAG)).get("isLiked");
    }

    @Test
    void dislikeOnLikedPostSwitchesInsteadOfClearing() {
        when(likes.selectUmsCreativeSquareLikeList(any())).thenReturn(Collections.singletonList(row(5L, 1)));

        AjaxResult result = controller.creativeSquareLike("10", "2");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(2, isLiked(result));
        ArgumentCaptor<UmsCreativeSquareLike> change = ArgumentCaptor.forClass(UmsCreativeSquareLike.class);
        verify(likes).updateUmsCreativeSquareLike(change.capture());
        assertEquals(Long.valueOf(5L), change.getValue().getId());
        assertEquals(Integer.valueOf(2), change.getValue().getLikeType());
        verify(likes, never()).deleteUmsCreativeSquareLikeById(anyLong());
        verify(likes, never()).insertUmsCreativeSquareLike(any());
    }

    @Test
    void sameAttitudeAgainCancelsIt() {
        when(likes.selectUmsCreativeSquareLikeList(any())).thenReturn(Collections.singletonList(row(5L, 2)));

        AjaxResult result = controller.creativeSquareLike("10", "2");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(0, isLiked(result));
        verify(likes).deleteUmsCreativeSquareLikeById(5L);
        verify(likes, never()).updateUmsCreativeSquareLike(any());
    }

    @Test
    void firstLikeInsertsAndReportsState() {
        when(likes.selectUmsCreativeSquareLikeList(any())).thenReturn(Collections.emptyList());

        AjaxResult result = controller.creativeSquareLike("10", "1");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(1, isLiked(result));
        verify(likes).insertUmsCreativeSquareLike(any());
    }

    @Test
    void historicalDuplicateRowsCollapseToOne() {
        when(likes.selectUmsCreativeSquareLikeList(any())).thenReturn(Arrays.asList(row(5L, 1), row(6L, 1), row(7L, 2)));

        AjaxResult result = controller.creativeSquareLike("10", "2");

        assertEquals(2, isLiked(result));
        verify(likes).deleteUmsCreativeSquareLikeById(6L);
        verify(likes).deleteUmsCreativeSquareLikeById(7L);
        verify(likes, never()).deleteUmsCreativeSquareLikeById(5L);
        verify(likes).updateUmsCreativeSquareLike(any());
    }
}
