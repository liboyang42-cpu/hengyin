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
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.common.exception.ServiceException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private Map<String, Object> reviewedImage;

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
        when(clubService.selectClubByIdForUpdate(CLUB_ID)).thenReturn(postClub);
        securityService = mock(IWxContentSecurityService.class);
        when(securityService.checkText(any(), any())).thenReturn(null);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", securityService);
        reviewedImage = new HashMap<>();
        reviewedImage.put("id", 901L);
        reviewedImage.put("ownerType", "APP");
        reviewedImage.put("memberId", MEMBER_ID);
        reviewedImage.put("reviewState", "APPROVED");
        AppMediaAssetMapper mediaMapper = mock(AppMediaAssetMapper.class);
        when(mediaMapper.select(901L)).thenReturn(reviewedImage);
        AppMediaReferenceGuard mediaReferences = new AppMediaReferenceGuard();
        ReflectionTestUtils.setField(mediaReferences, "mapper", mediaMapper);
        ReflectionTestUtils.setField(controller, "mediaReferences", mediaReferences);
    }

    @Test
    void dissolvedClubRejectsPostBeforeInsert() {
        ClubPost body = new ClubPost();
        body.setClubId(CLUB_ID);
        body.setContent("内容");
        body.setImages("media-asset:901");
        when(postService.insertClubPostInActiveClub(any(), eq(MEMBER_ID)))
                .thenThrow(new IllegalStateException("俱乐部不存在或已解散"));

        AjaxResult result = controller.postCreate(body);

        assertEquals(500, result.get("code"));
        assertEquals("俱乐部不存在或已解散", result.get("msg"));
        verify(postService).insertClubPostInActiveClub(any(), eq(MEMBER_ID));
        verify(securityService, never()).submitMediaCheck(anyInt(), any(), any(), any());
    }

    @Test
    void unreviewedOrForeignPendingImageNeverReachesPostWrite() {
        ClubPost body = new ClubPost();
        body.setClubId(CLUB_ID);
        body.setContent("内容");
        body.setImages("media-asset:901");
        reviewedImage.put("reviewState", "HOLD");
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class,
                () -> controller.postCreate(body)).getCode());
        reviewedImage.put("memberId", 99L);
        assertEquals(Integer.valueOf(403), assertThrows(ServiceException.class,
                () -> controller.postCreate(body)).getCode());
        body.setImages("media-asset:902");
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class,
                () -> controller.postCreate(body)).getCode());
        verify(postService, never()).insertClubPostInActiveClub(any(), any());
        verify(securityService, never()).submitMediaCheck(anyInt(), any(), any(), any());
    }

    @Test
    void deletedPostRejectsLikeBeforeAnyLikeWrite() {
        ClubPost deleted = post();
        deleted.setDelFlag(2);
        when(postService.selectClubPostById(POST_ID)).thenReturn(deleted);
        Club activeClub = new Club();
        activeClub.setMemberId(MEMBER_ID);
        when(clubService.selectClubByIdForUpdate(CLUB_ID)).thenReturn(activeClub);
        ClubPostLike body = new ClubPostLike();
        body.setPostId(POST_ID);

        AjaxResult result = controller.postLike(body);

        assertEquals(500, result.get("code"));
        assertEquals("动态不存在", result.get("msg"));
        verify(likeService, never()).insertClubPostLike(any());
        verify(likeService, never()).deleteByPostAndMember(any(), any());
        verify(postService, never()).incrementLikeCount(any(), anyInt());
    }

    @Test
    void deletedPostRejectsCommentBeforeInsertOrCounterWrite() {
        ClubPost deleted = post();
        deleted.setDelFlag(2);
        when(postService.selectClubPostById(POST_ID)).thenReturn(deleted);
        Club activeClub = new Club();
        activeClub.setMemberId(MEMBER_ID);
        when(clubService.selectClubByIdForUpdate(CLUB_ID)).thenReturn(activeClub);
        ClubPostComment body = new ClubPostComment();
        body.setPostId(POST_ID);
        body.setContent("评论");

        AjaxResult result = controller.commentCreate(body);

        assertEquals(500, result.get("code"));
        assertEquals("动态不存在", result.get("msg"));
        verify(commentService, never()).insertClubPostComment(any());
        verify(postService, never()).incrementCommentCount(any(), anyInt());
    }

    @Test
    void postDeletedWhileLikeWaitsForLockRejectsAllWrites() {
        when(postService.selectClubPostById(POST_ID)).thenReturn(post());
        ClubPost deleted = post();
        deleted.setDelFlag(2);
        when(postService.selectClubPostByIdForUpdate(POST_ID)).thenReturn(deleted);
        ClubPostLike body = new ClubPostLike();
        body.setPostId(POST_ID);

        AjaxResult result = controller.postLike(body);

        assertEquals(500, result.get("code"));
        assertEquals("动态不存在", result.get("msg"));
        verify(likeService, never()).insertClubPostLike(any());
        verify(likeService, never()).deleteByPostAndMember(any(), any());
        verify(postService, never()).incrementLikeCount(any(), anyInt());
    }

    @Test
    void postDeletedDuringTextReviewRejectsCommentBeforeWrite() {
        when(postService.selectClubPostById(POST_ID)).thenReturn(post());
        when(clubService.selectClubByIdForUpdate(CLUB_ID)).thenReturn(new Club());
        ClubPost deleted = post();
        deleted.setDelFlag(2);
        when(postService.selectClubPostByIdForUpdate(POST_ID)).thenReturn(deleted);
        ClubPostComment body = new ClubPostComment();
        body.setPostId(POST_ID);
        body.setContent("评论");

        AjaxResult result = controller.commentCreate(body);

        assertEquals(500, result.get("code"));
        assertEquals("动态不存在", result.get("msg"));
        verify(commentService, never()).insertClubPostComment(any());
        verify(postService, never()).incrementCommentCount(any(), anyInt());
    }

    @Test
    void activePostStillAcceptsLikeAfterLockedRead() {
        when(postService.selectClubPostById(POST_ID)).thenReturn(post());
        when(postService.selectClubPostByIdForUpdate(POST_ID)).thenReturn(post());
        ClubPostLike body = new ClubPostLike();
        body.setPostId(POST_ID);

        assertEquals(200, controller.postLike(body).get("code"));

        verify(postService).selectClubPostByIdForUpdate(POST_ID);
        verify(likeService).insertClubPostLike(any());
        verify(postService).incrementLikeCount(POST_ID, 1);
    }

    /** P1-023:带目标态的点赞重试不会把已生效的赞再翻掉;重复取消同理。 */
    @Test
    void likeWithTargetStateIsIdempotentOnRetry() {
        when(postService.selectClubPostById(POST_ID)).thenReturn(post());
        when(postService.selectClubPostByIdForUpdate(POST_ID)).thenReturn(post());
        when(likeService.selectByPostAndMember(POST_ID, MEMBER_ID)).thenReturn(new ClubPostLike());
        ClubPostLike retry = new ClubPostLike();
        retry.setPostId(POST_ID);
        retry.setLiked(true);

        AjaxResult result = controller.postLike(retry);

        assertEquals(200, result.get("code"));
        assertEquals(true, ((Map<?, ?>) result.get("data")).get("liked"));
        verify(likeService, never()).insertClubPostLike(any());
        verify(likeService, never()).deleteByPostAndMember(any(), any());
        verify(postService, never()).incrementLikeCount(any(), anyInt());

        when(likeService.selectByPostAndMember(POST_ID, MEMBER_ID)).thenReturn(null);
        ClubPostLike unlikeRetry = new ClubPostLike();
        unlikeRetry.setPostId(POST_ID);
        unlikeRetry.setLiked(false);

        AjaxResult unliked = controller.postLike(unlikeRetry);

        assertEquals(false, ((Map<?, ?>) unliked.get("data")).get("liked"));
        verify(likeService, never()).deleteByPostAndMember(any(), any());
        verify(postService, never()).incrementLikeCount(any(), anyInt());
    }

    @Test
    void likeWithTargetStateWritesOnlyWhenStateDiffers() {
        when(postService.selectClubPostById(POST_ID)).thenReturn(post());
        when(postService.selectClubPostByIdForUpdate(POST_ID)).thenReturn(post());
        when(likeService.selectByPostAndMember(POST_ID, MEMBER_ID)).thenReturn(new ClubPostLike());
        ClubPostLike body = new ClubPostLike();
        body.setPostId(POST_ID);
        body.setLiked(false);

        AjaxResult result = controller.postLike(body);

        assertEquals(false, ((Map<?, ?>) result.get("data")).get("liked"));
        verify(likeService).deleteByPostAndMember(POST_ID, MEMBER_ID);
        verify(postService).incrementLikeCount(POST_ID, -1);
    }

    @Test
    void activePostStillAcceptsOwnerCommentAfterLockedRead() {
        when(postService.selectClubPostById(POST_ID)).thenReturn(post());
        when(postService.selectClubPostByIdForUpdate(POST_ID)).thenReturn(post());
        Club club = new Club();
        club.setMemberId(MEMBER_ID);
        when(clubService.selectClubByIdForUpdate(CLUB_ID)).thenReturn(club);
        ClubPostComment body = new ClubPostComment();
        body.setPostId(POST_ID);
        body.setContent("评论");

        assertEquals(200, controller.commentCreate(body).get("code"));

        verify(postService).selectClubPostByIdForUpdate(POST_ID);
        verify(commentService).insertClubPostComment(any());
        verify(postService).incrementCommentCount(POST_ID, 1);
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
        body.setImages("media-asset:901");

        assertEquals(200, controller.postCreate(body).get("code"));

        verify(postService).insertClubPostInActiveClub(any(), eq(MEMBER_ID));
        verify(securityService, never()).submitMediaCheck(
                IWxContentSecurityService.BIZ_TYPE_CLUB_POST, POST_ID,
                "media-asset:901", null);
    }

    @Test
    void exactUpdateReplayNeverRunsSynchronousMediaDispatchInTheRequest() {
        ClubPost updated = post();
        updated.setImages("media-asset:901");
        updated.setVersion(3);
        when(postService.updatePost(MEMBER_ID, POST_ID, "新正文",
                "media-asset:901", 2, "update-replay-1"))
                .thenReturn(new ClubPostUpdateResult(updated, true),
                        new ClubPostUpdateResult(updated, false));
        ClubPostMutationRequest body = new ClubPostMutationRequest();
        body.setId(POST_ID);
        body.setContent("新正文");
        body.setImages("media-asset:901");
        body.setVersion(2);
        body.setRequestId("update-replay-1");

        assertEquals(200, controller.postUpdate(body).get("code"));
        assertEquals(200, controller.postUpdate(body).get("code"));

        verify(securityService, never()).submitMediaCheck(
                IWxContentSecurityService.BIZ_TYPE_CLUB_POST, POST_ID,
                "media-asset:901", null);
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
