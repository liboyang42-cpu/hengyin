package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubGovernanceBan;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostLike;
import com.chengyinhub.business.mapper.ClubGovernanceMapper;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubPostLikeService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.impl.ClubParticipationGuardImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ClubPostLikeAuthorizationTest {
    private static final Long CLUB = 73L, VIEWER = 42L, POST = 81L;
    private static final Boolean[] TARGETS = {true, false, null};
    private ApiClubController controller;
    private IClubService clubs;
    private IClubMemberService members;
    private IClubPostService posts;
    private IClubPostLikeService likes;
    private ClubGovernanceMapper governance;
    private Club club;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiClubController());
        doReturn(VIEWER).when(controller).getAppUserId();
        clubs = mock(IClubService.class);
        members = mock(IClubMemberService.class);
        posts = mock(IClubPostService.class);
        likes = mock(IClubPostLikeService.class);
        governance = mock(ClubGovernanceMapper.class);
        ClubParticipationGuardImpl guard = new ClubParticipationGuardImpl();
        ReflectionTestUtils.setField(guard, "clubGovernanceMapper", governance);
        ReflectionTestUtils.setField(controller, "clubService", clubs);
        ReflectionTestUtils.setField(controller, "clubMemberService", members);
        ReflectionTestUtils.setField(controller, "clubPostService", posts);
        ReflectionTestUtils.setField(controller, "clubPostLikeService", likes);
        ReflectionTestUtils.setField(controller, "clubParticipationGuard", guard);
        club = new Club();
        club.setId(CLUB);
        club.setMemberId(9L);
        club.setPublicVisible(1);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(club);
        when(posts.selectClubPostById(POST)).thenReturn(post());
        when(posts.selectClubPostByIdForUpdate(POST)).thenReturn(post());
    }

    @Test
    void visiblePublicLegacyPublicPrivateOwnerAndApprovedMemberKeepTargetAndToggleSemantics() {
        for (int scenario = 0; scenario < 4; scenario++) {
            club.setPublicVisible(scenario == 1 ? null : Integer.valueOf(scenario == 0 ? 1 : 0));
            club.setMemberId(scenario == 2 ? VIEWER : 9L);
            ClubMember member = new ClubMember();
            member.setStatus(1);
            when(members.selectMembershipRecord(CLUB, VIEWER)).thenReturn(scenario == 3 ? member : null);
            when(likes.selectByPostAndMember(POST, VIEWER))
                    .thenReturn(null, new ClubPostLike(), new ClubPostLike(), null, null, new ClubPostLike());

            assertLiked(true, request(true));
            assertLiked(true, request(true));
            assertLiked(false, request(false));
            assertLiked(false, request(false));
            assertLiked(true, request(null));
            assertLiked(false, request(null));
        }
        verify(likes, times(8)).insertClubPostLike(argThat(like ->
                POST.equals(like.getPostId()) && VIEWER.equals(like.getMemberId())));
        verify(likes, times(8)).deleteByPostAndMember(POST, VIEWER);
        verify(posts, times(8)).incrementLikeCount(POST, 1);
        verify(posts, times(8)).incrementLikeCount(POST, -1);
        verify(members, times(6)).selectMembershipRecord(CLUB, VIEWER);
        verify(governance, times(24)).selectEffectiveByClubAndMember(eq(CLUB), eq(VIEWER), any(Date.class));
    }

    @Test
    void privateOutsiderPendingAndRemovedMembersCannotReadOrChangeLikesInAnyMode() {
        club.setPublicVisible(0);
        for (Integer status : new Integer[] {null, 0, 2}) {
            ClubMember member = new ClubMember();
            member.setStatus(status);
            when(members.selectMembershipRecord(CLUB, VIEWER)).thenReturn(status == null ? null : member);
            assertRefusedInAllModes(500, "这个俱乐部只对成员开放");
        }
        verifyNoInteractions(governance);
    }

    @Test
    void privateMembershipLookupFailureCannotReadOrChangeLikes() {
        club.setPublicVisible(0);
        when(members.selectMembershipRecord(CLUB, VIEWER))
                .thenThrow(new IllegalStateException("membership unavailable"));
        assertRefusedInAllModes(500, "这个俱乐部只对成员开放");
    }

    @Test
    void realClubAndPlatformBansBlockEveryModeForPublicNonmemberPrivateOwnerAndApprovedMember() {
        for (String source : new String[] {ClubGovernanceBan.SOURCE_CLUB, ClubGovernanceBan.SOURCE_PLATFORM}) {
            ClubGovernanceBan ban = new ClubGovernanceBan();
            ban.setId(91L);
            ban.setClubId(CLUB);
            ban.setTargetMemberId(VIEWER);
            ban.setStatus(ClubGovernanceBan.STATUS_ACTIVE);
            ban.setSourceType(source);
            ban.setExpiresAt(ClubGovernanceBan.SOURCE_CLUB.equals(source)
                    ? new Date(System.currentTimeMillis() + 86_400_000L) : null);
            when(governance.selectEffectiveByClubAndMember(eq(CLUB), eq(VIEWER), any(Date.class)))
                    .thenReturn(ban);
            for (int scenario = 0; scenario < 3; scenario++) {
                club.setPublicVisible(scenario == 0 ? 1 : 0);
                club.setMemberId(scenario == 1 ? VIEWER : 9L);
                ClubMember member = new ClubMember();
                member.setStatus(1);
                when(members.selectMembershipRecord(CLUB, VIEWER)).thenReturn(scenario == 2 ? member : null);
                assertRefusedInAllModes(403, "你当前无法参与该俱乐部");
            }
        }
        verify(governance, times(18)).selectEffectiveByClubAndMember(eq(CLUB), eq(VIEWER), any(Date.class));
    }

    @Test
    void missingOrDeletedPostBeforeAndAfterLockCannotReadOrChangeLikes() {
        for (boolean afterLock : new boolean[] {false, true}) {
            for (boolean deleted : new boolean[] {false, true}) {
                ClubPost target = deleted ? post() : null;
                if (target != null) target.setDelFlag(2);
                when(posts.selectClubPostById(POST)).thenReturn(afterLock ? post() : target);
                when(posts.selectClubPostByIdForUpdate(POST)).thenReturn(afterLock ? target : post());
                assertRefusedInAllModes(500, "动态不存在");
            }
        }
        verifyNoInteractions(governance);
    }

    @Test
    void dissolvedClubCannotReadOrChangeLikes() {
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(null);
        assertRefusedInAllModes(500, "动态不存在");
        verify(posts, never()).selectClubPostByIdForUpdate(any());
        verifyNoInteractions(governance);
    }

    @Test
    void postMovedToAnotherOrMissingClubWhileWaitingForLockCannotReadOrChangeLikes() {
        for (Long movedClub : new Long[] {74L, null}) {
            ClubPost moved = post();
            moved.setClubId(movedClub);
            when(posts.selectClubPostByIdForUpdate(POST)).thenReturn(moved);
            assertRefusedInAllModes(500, "动态不存在");
        }
        verifyNoInteractions(governance);
    }

    @Test
    void clubThenPostLocksAndParticipationGuardPrecedeLikeStateRead() {
        assertLiked(true, request(true));
        InOrder order = inOrder(posts, clubs, governance, likes);
        order.verify(posts).selectClubPostById(POST);
        order.verify(clubs).selectClubByIdForUpdate(CLUB);
        order.verify(posts).selectClubPostByIdForUpdate(POST);
        order.verify(governance).expireStaleByClubAndMember(eq(CLUB), eq(VIEWER), any(Date.class));
        order.verify(governance).selectEffectiveByClubAndMember(eq(CLUB), eq(VIEWER), any(Date.class));
        order.verify(likes).selectByPostAndMember(POST, VIEWER);
        verify(clubs, never()).selectClubById(any());
    }

    private AjaxResult request(Boolean target) {
        ClubPostLike body = new ClubPostLike();
        body.setPostId(POST);
        body.setLiked(target);
        return controller.postLike(body);
    }

    private void assertLiked(boolean expected, AjaxResult response) {
        assertEquals(200, response.get(AjaxResult.CODE_TAG));
        assertEquals(expected, ((Map<?, ?>) response.get(AjaxResult.DATA_TAG)).get("liked"));
    }

    private void assertRefusedInAllModes(int code, String message) {
        for (Boolean target : TARGETS) {
            AjaxResult response;
            if (code == 403) {
                ServiceException refused = assertThrows(ServiceException.class, () -> request(target));
                assertEquals(Integer.valueOf(code), refused.getCode());
                response = new GlobalExceptionHandler().handleServiceException(refused, new MockHttpServletRequest());
            } else {
                response = request(target);
            }
            assertEquals(code, response.get(AjaxResult.CODE_TAG));
            assertEquals(message, response.get(AjaxResult.MSG_TAG));
            assertFalse(response.containsKey(AjaxResult.DATA_TAG));
        }
        verifyNoInteractions(likes);
        verify(posts, never()).incrementLikeCount(any(), anyInt());
    }

    private ClubPost post() {
        ClubPost post = new ClubPost();
        post.setId(POST);
        post.setClubId(CLUB);
        post.setDelFlag(0);
        return post;
    }
}
