package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.UmsComment;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.CommentPublicationService;
import com.chengyinhub.business.service.IUmsCommentService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.redis.RedisCache;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** E-B13:评论提交带 request_id 时,响应丢失后的重试不会落第二条。 */
class ApiCommentRequestDedupTest {
    private static final String RID = "req_1700000000000_abcd1234";
    private static final String KEY = "comment:add-request:8:" + RID;

    private final CommentPublicationService publication = mock(CommentPublicationService.class);
    private final IWxContentSecurityService security = mock(IWxContentSecurityService.class);
    private final IUmsCommentService comments = mock(IUmsCommentService.class);
    private final RedisCache redis = mock(RedisCache.class);

    private ApiCommentController controller() {
        ApiCommentController controller = spy(new ApiCommentController());
        doReturn(8L).when(controller).getAppUserId();
        IUmsMemberService members = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setWxOpenId("openid-8");
        when(members.selectUmsMemberById(8L)).thenReturn(member);
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        ReflectionTestUtils.setField(controller, "commentPublicationService", publication);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        ReflectionTestUtils.setField(controller, "umsCommentService", comments);
        ReflectionTestUtils.setField(controller, "redisCache", redis);
        return controller;
    }

    @Test
    void requestKeyOnlyForWellFormedIds() {
        assertEquals(KEY, ApiCommentController.commentRequestKey(8L, " " + RID + " "));
        assertNull(ApiCommentController.commentRequestKey(8L, "short"));
        assertNull(ApiCommentController.commentRequestKey(null, RID));
        assertNull(ApiCommentController.commentRequestKey(8L, null));
    }

    @Test
    void retryWithSameRequestIdReturnsTheFirstCommentWithoutPublishingAgain() {
        when(redis.getCacheObject(KEY)).thenReturn(null);
        when(redis.setIfAbsent(eq(KEY), any(), anyInt(), any())).thenReturn(true);
        when(publication.publish(any())).thenAnswer(call -> {
            ((UmsComment) call.getArgument(0)).setId(77L);
            return null;
        });

        AjaxResult first = controller().addComment("1", "3", "5", "好玩", "0", null, RID);

        assertEquals(200, first.get("code"));
        verify(redis).setCacheObject(KEY, 77L, 7, TimeUnit.DAYS);

        UmsComment stored = new UmsComment();
        stored.setId(77L);
        stored.setMemberId(8L);
        when(redis.getCacheObject(KEY)).thenReturn(77L);
        when(comments.selectUmsCommentById(77L)).thenReturn(stored);

        AjaxResult retry = controller().addComment("1", "3", "5", "好玩", "0", null, RID);

        assertEquals(200, retry.get("code"));
        assertEquals(stored, retry.get("data"));
        verify(publication, times(1)).publish(any());
    }

    @Test
    void failedPublishReleasesTheKeySoTheRetryCanTryAgain() {
        when(redis.getCacheObject(KEY)).thenReturn(null);
        when(redis.setIfAbsent(eq(KEY), any(), anyInt(), any())).thenReturn(true);
        when(publication.publish(any())).thenReturn("内容不合规");

        AjaxResult result = controller().addComment("1", "3", "5", "好玩", "0", null, RID);

        assertEquals(500, result.get("code"));
        verify(redis).deleteObject(KEY);
    }

    @Test
    void sameKeyInFlightIsRefusedWithoutPublishing() {
        when(redis.getCacheObject(KEY)).thenReturn("PENDING");
        when(redis.setIfAbsent(eq(KEY), any(), anyInt(), any())).thenReturn(false);

        AjaxResult result = controller().addComment("1", "3", "5", "好玩", "0", null, RID);

        assertEquals(500, result.get("code"));
        verify(publication, never()).publish(any());
    }

    @Test
    void withoutRequestIdBehaviourIsUnchanged() {
        when(publication.publish(any())).thenReturn(null);

        AjaxResult result = controller().addComment("1", "3", "5", "好玩", "0", null);

        assertEquals(200, result.get("code"));
        verifyNoInteractions(redis);
    }
}
