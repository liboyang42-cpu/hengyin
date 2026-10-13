package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostComment;
import com.chengyinhub.business.service.IClubPostCommentService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.api.ApiClubController;
import com.chengyinhub.web.controller.api.ApiImController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubGovernanceParticipationEntryTest {
    private static final long CLUB = 11L;
    private static final long MEMBER = 902L;

    private ApiClubController controller;
    private IClubParticipationGuard participationGuard;
    private IClubService clubs;
    private IClubPostService posts;
    private IClubPostCommentService comments;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiClubController());
        doReturn(MEMBER).when(controller).getAppUserId();
        participationGuard = mock(IClubParticipationGuard.class);
        clubs = mock(IClubService.class);
        posts = mock(IClubPostService.class);
        comments = mock(IClubPostCommentService.class);
        ReflectionTestUtils.setField(controller, "clubParticipationGuard", participationGuard);
        ReflectionTestUtils.setField(controller, "clubService", clubs);
        ReflectionTestUtils.setField(controller, "clubPostService", posts);
        ReflectionTestUtils.setField(controller, "clubPostCommentService", comments);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        doThrow(new ServiceException("你当前无法参与该俱乐部", HttpStatus.FORBIDDEN))
                .when(participationGuard).requireAllowed(CLUB, MEMBER);
    }

    @Test
    void banBlocksChatAndPostBeforeAnyBusinessWrite() {
        Club club = new Club();
        club.setId(CLUB);
        AjaxResult chat = controller.chat(club);
        assertEquals(HttpStatus.FORBIDDEN, chat.get(AjaxResult.CODE_TAG));
        verify(clubs, never()).selectClubById(any());

        ClubPost post = new ClubPost();
        post.setClubId(CLUB);
        post.setContent("内容");
        AjaxResult publish = controller.postCreate(post);
        assertEquals(HttpStatus.FORBIDDEN, publish.get(AjaxResult.CODE_TAG));
        verify(posts, never()).insertClubPostInActiveClub(any(), any());
    }

    @Test
    void banBlocksCommentAfterResolvingThePostsRealClubScope() {
        ClubPost existing = new ClubPost();
        existing.setId(81L);
        existing.setClubId(CLUB);
        when(posts.selectClubPostById(81L)).thenReturn(existing);
        ClubPostComment request = new ClubPostComment();
        request.setPostId(81L);
        request.setContent("评论");

        AjaxResult result = controller.commentCreate(request);

        assertEquals(HttpStatus.FORBIDDEN, result.get(AjaxResult.CODE_TAG));
        verify(participationGuard).requireAllowed(CLUB, MEMBER);
        verify(comments, never()).insertClubPostComment(any());
        verify(posts, never()).incrementCommentCount(any(), anyInt());
    }

    @Test
    void imSendPreservesTheGovernanceForbiddenStatus() {
        IImService im = mock(IImService.class);
        when(im.send(MEMBER, 100L, null, 1, "hi", null))
                .thenThrow(new ServiceException("你当前无法参与该俱乐部", HttpStatus.FORBIDDEN));
        ApiImController imController = new ApiImController() {
            @Override public Long getAppUserId() { return MEMBER; }
        };
        ReflectionTestUtils.setField(imController, "imService", im);

        AjaxResult result = imController.send("100", "0", "1", "hi", null);

        assertEquals(HttpStatus.FORBIDDEN, result.get(AjaxResult.CODE_TAG));
    }
}
