package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostComment;
import com.chengyinhub.business.domain.ClubPostLike;
import com.chengyinhub.business.domain.dto.ClubPostMutationRequest;
import com.chengyinhub.business.domain.dto.ClubPostUpdateResult;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubPostCommentService;
import com.chengyinhub.business.service.IClubPostLikeService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IClubPostMediaReviewService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 解散与动态写共用 club 行锁，不能 success-after-dissolve。 */
class ClubPostWriteLifecycleTest {

    private static final Long MEMBER_ID = 42L;
    private static final Long CLUB_ID = 73L;
    private static final Long POST_ID = 81L;

    private ApiClubController controller;
    private IClubService clubService;
    private IClubPostService postService;
    private IClubPostLikeService likeService;
    private IClubPostCommentService commentService;
    private IWxContentSecurityService securityService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiClubController());
        doReturn(MEMBER_ID).when(controller).getAppUserId();
        clubService = mock(IClubService.class);
        postService = mock(IClubPostService.class);
        likeService = mock(IClubPostLikeService.class);
        commentService = mock(IClubPostCommentService.class);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "clubPostService", postService);
        ReflectionTestUtils.setField(controller, "clubPostLikeService", likeService);
        ReflectionTestUtils.setField(controller, "clubPostCommentService", commentService);
        ReflectionTestUtils.setField(controller, "clubMemberService", mock(IClubMemberService.class));
        ReflectionTestUtils.setField(controller, "clubParticipationGuard", mock(IClubParticipationGuard.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        // 发帖现在先认成员(2026-09-10 裁决:非成员不能在俱乐部里发帖)。
        // 这几条用例测的是解散与送检,不是那道闸,所以把 42 当成主理人喂进去。
        Club postClub = new Club();
        postClub.setId(CLUB_ID);
        postClub.setMemberId(MEMBER_ID);
        when(clubService.selectClubById(CLUB_ID)).thenReturn(postClub);
        securityService = mock(IWxContentSecurityService.class);
        when(securityService.checkText(any(), any())).thenReturn(null);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", securityService);
    }

    @Test
    void dissolvedClubRejectsPostBeforeInsert() {
        ClubPost body = new ClubPost();
        body.setClubId(CLUB_ID);
        body.setContent("内容");
        body.setImages("https://example.invalid/a.jpg");
        when(postService.insertClubPostInActiveClub(any(), eq(MEMBER_ID)))
                .thenThrow(new IllegalStateException("俱乐部不存在或已解散"));

        AjaxResult result = controller.postCreate(body);

        assertEquals(500, result.get("code"));
        assertEquals("俱乐部不存在或已解散", result.get("msg"));
        verify(postService).insertClubPostInActiveClub(any(), eq(MEMBER_ID));
        verify(securityService, never()).submitMediaCheck(anyInt(), any(), any(), any());
    }

    @Test
    void dissolvedClubRejectsLikeBeforeAnyLikeWrite() {
        ClubPost post = post();
        when(postService.selectClubPostById(POST_ID)).thenReturn(post);
        when(clubService.selectClubByIdForUpdate(CLUB_ID)).thenReturn(null);
        ClubPostLike body = new ClubPostLike();
        body.setPostId(POST_ID);

        AjaxResult result = controller.postLike(body);

        assertEquals(500, result.get("code"));
        verify(likeService, never()).insertClubPostLike(any());
        verify(likeService, never()).deleteByPostAndMember(any(), any());
        verify(postService, never()).incrementLikeCount(any(), anyInt());
    }

    @Test
    void dissolvedClubRejectsCommentBeforeInsertOrCounterWrite() {
        when(postService.selectClubPostById(POST_ID)).thenReturn(post());
        when(clubService.selectClubByIdForUpdate(CLUB_ID)).thenReturn(null);
        ClubPostComment body = new ClubPostComment();
        body.setPostId(POST_ID);
        body.setContent("评论");

        AjaxResult result = controller.commentCreate(body);

        assertEquals(500, result.get("code"));
        assertEquals("俱乐部不存在或已解散", result.get("msg"));
        verify(commentService, never()).insertClubPostComment(any());
        verify(postService, never()).incrementCommentCount(any(), anyInt());
    }

    @Test
    void postCreateDoesNotBlockTheRequestOnSynchronousMediaDispatch() {
        when(postService.insertClubPostInActiveClub(any(), eq(MEMBER_ID))).thenAnswer(invocation -> {
            ClubPost inserted = invocation.getArgument(0);
            inserted.setId(POST_ID);
            inserted.setVersion(0);
            return 1;
        });
        ClubPost body = new ClubPost();
        body.setClubId(CLUB_ID);
        body.setContent("内容");
        body.setImages("https://example.invalid/a.jpg");

        assertEquals(200, controller.postCreate(body).get("code"));

        verify(postService).insertClubPostInActiveClub(any(), eq(MEMBER_ID));
        verify(securityService, never()).submitMediaCheck(
                IWxContentSecurityService.BIZ_TYPE_CLUB_POST, POST_ID,
                "https://example.invalid/a.jpg", null);
    }

    @Test
    void exactUpdateReplayNeverRunsSynchronousMediaDispatchInTheRequest() {
        ClubPost updated = post();
        updated.setImages("https://example.invalid/a.jpg");
        updated.setVersion(3);
        when(postService.updatePost(MEMBER_ID, POST_ID, "新正文",
                "https://example.invalid/a.jpg", 2, "update-replay-1"))
                .thenReturn(new ClubPostUpdateResult(updated, true),
                        new ClubPostUpdateResult(updated, false));
        ClubPostMutationRequest body = new ClubPostMutationRequest();
        body.setId(POST_ID);
        body.setContent("新正文");
        body.setImages("https://example.invalid/a.jpg");
        body.setVersion(2);
        body.setRequestId("update-replay-1");

        assertEquals(200, controller.postUpdate(body).get("code"));
        assertEquals(200, controller.postUpdate(body).get("code"));

        verify(securityService, never()).submitMediaCheck(
                IWxContentSecurityService.BIZ_TYPE_CLUB_POST, POST_ID,
                "https://example.invalid/a.jpg", null);
    }

    @Test
    void allClubPostWritesKeepTransactionAroundForUpdateLock() throws Exception {
        assertTransactional("postLike", ClubPostLike.class);
        assertTransactional("commentCreate", ClubPostComment.class);
        Method postCreate = ApiClubController.class.getMethod("postCreate", ClubPost.class);
        assertNull(postCreate.getAnnotation(Transactional.class),
                "发帖 Controller 不能包住送检外呼，锁团与入库由独立 service 事务负责");
        for (java.lang.reflect.Field field : ApiClubController.class.getDeclaredFields()) {
            org.junit.jupiter.api.Assertions.assertNotEquals(IClubPostMediaReviewService.class, field.getType(),
                    "Controller 禁止注入媒体 dispatcher；微信外呼只能由 durable recovery task 驱动");
        }
    }

    private void assertTransactional(String methodName, Class<?> bodyType) throws Exception {
        Method method = ApiClubController.class.getMethod(methodName, bodyType);
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertNotNull(annotation, methodName + " 必须在事务内持有 club FOR UPDATE 锁");
        assertTrue(annotation.rollbackFor().length > 0);
        assertEquals(Exception.class, annotation.rollbackFor()[0]);
    }

    private ClubPost post() {
        ClubPost post = new ClubPost();
        post.setId(POST_ID);
        post.setClubId(CLUB_ID);
        return post;
    }
}
