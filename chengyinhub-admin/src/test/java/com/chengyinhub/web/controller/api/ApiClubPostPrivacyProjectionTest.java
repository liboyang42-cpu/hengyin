package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostComment;
import com.chengyinhub.business.domain.dto.ClubPostMutationRequest;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubPostCommentService;
import com.chengyinhub.business.service.IClubPostLikeService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IViewCreativeSquareService;
import com.chengyinhub.business.service.support.ClubPostReferenceGuard;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F0098/F0119:帖文引用卡与编辑历史按「当前」可见性投影。
 * 引用对象下架/转私密 ⇒ 只给「引用已不可见」,不出标题封面;俱乐部转仅成员可见 ⇒ 非成员读帖/历史/评论都拒。
 */
class ApiClubPostPrivacyProjectionTest {

    private static final Long CLUB_ID = 73L;
    private static final Long VIEWER = 42L;

    private IClubService clubService;
    private IClubMemberService memberService;
    private IClubPostService postService;
    private IViewCreativeSquareService squareService;
    private ClubPostReferenceGuard guard;
    private ApiClubController controller;
    private Club club;

    @BeforeEach
    void setUp() {
        clubService = mock(IClubService.class);
        memberService = mock(IClubMemberService.class);
        postService = mock(IClubPostService.class);
        squareService = mock(IViewCreativeSquareService.class);
        guard = mock(ClubPostReferenceGuard.class);
        controller = spy(new ApiClubController());
        doReturn(VIEWER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "clubMemberService", memberService);
        ReflectionTestUtils.setField(controller, "clubPostService", postService);
        // 帖文卡头像「+」关注:查看者对作者的关注态(批量查询,默认空 = 都没关注)
        ReflectionTestUtils.setField(controller, "umsMemberFollowMapper", org.mockito.Mockito.mock(com.chengyinhub.business.mapper.UmsMemberFollowMapper.class));
        ReflectionTestUtils.setField(controller, "viewCreativeSquareService", squareService);
        ReflectionTestUtils.setField(controller, "clubPostReferenceGuard", guard);
        ReflectionTestUtils.setField(controller, "clubPostLikeService", mock(IClubPostLikeService.class));
        ReflectionTestUtils.setField(controller, "clubPostCommentService", mock(IClubPostCommentService.class));
        club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(9L);
        when(clubService.selectClubById(CLUB_ID)).thenReturn(club);
    }

    private ClubPost post(long id, Integer refType, Long refId) {
        ClubPost post = new ClubPost();
        post.setId(id);
        post.setClubId(CLUB_ID);
        post.setRefType(refType);
        post.setRefId(refId);
        return post;
    }

    @Test
    void referenceToHiddenTopicIsMarkedUnavailableWithoutTitleOrCover() {
        ClubPost visible = post(1L, 2, 10L);
        ClubPost hidden = post(2L, 2, 11L);
        ClubPost plain = post(3L, null, null);
        when(postService.selectClubPostPage(any(), any(), anyInt()))
                .thenReturn(new ArrayList<>(Arrays.asList(visible, hidden, plain)));
        when(guard.isPubliclyVisible(2, 10L)).thenReturn(true);
        when(guard.isPubliclyVisible(2, 11L)).thenReturn(false);
        Map<String, Object> summary = new HashMap<>();
        summary.put("topicId", 10L);
        summary.put("sportName", "公开路线");
        when(squareService.selectSportTopicSummaries(Collections.singletonList(10L)))
                .thenReturn(Collections.singletonList(summary));
        ClubPost query = new ClubPost();
        query.setClubId(CLUB_ID);

        AjaxResult res = controller.postList(query);

        assertEquals(200, res.get(AjaxResult.CODE_TAG));
        assertEquals("公开路线", visible.getSportName());
        assertNull(visible.getRefUnavailable());
        assertTrue(hidden.getRefUnavailable(), "被引对象当前不可见:标记不可见");
        assertNull(hidden.getSportName(), "不可见引用不得投影标题");
        assertNull(hidden.getSportCover());
        assertNull(plain.getRefUnavailable(), "普通图文帖不受影响");
        verify(squareService, never()).selectSportTopicSummaries(Arrays.asList(10L, 11L));
    }

    @Test
    void membersOnlyClubHidesPostsHistoryAndCommentsFromNonMembers() {
        club.setPublicVisible(0);
        when(memberService.selectMembershipRecord(CLUB_ID, VIEWER)).thenReturn(null);
        ClubPost query = new ClubPost();
        query.setClubId(CLUB_ID);
        when(postService.selectClubPostById(5L)).thenReturn(post(5L, null, null));

        assertEquals("这个俱乐部只对成员开放", controller.postList(query).get(AjaxResult.MSG_TAG));

        ClubPostMutationRequest history = new ClubPostMutationRequest();
        history.setId(5L);
        assertEquals("这个俱乐部只对成员开放", controller.postHistory(history).get(AjaxResult.MSG_TAG));
        verify(postService, never()).listRevisionHistory(anyLong());

        ClubPostComment comments = new ClubPostComment();
        comments.setPostId(5L);
        assertEquals("这个俱乐部只对成员开放", controller.commentList(comments).get(AjaxResult.MSG_TAG));
    }

    @Test
    void publicClubHistoryStaysReadableForEveryone() {
        when(postService.selectClubPostById(5L)).thenReturn(post(5L, null, null));
        List<com.chengyinhub.business.domain.ClubPostRevision> rows = new ArrayList<>();
        when(postService.listRevisionHistory(5L)).thenReturn(rows);
        ClubPostMutationRequest history = new ClubPostMutationRequest();
        history.setId(5L);

        AjaxResult res = controller.postHistory(history);

        assertEquals(200, res.get(AjaxResult.CODE_TAG), "已确认口径:公开发布的正文大家可看");
        verify(postService).listRevisionHistory(5L);
    }
}
