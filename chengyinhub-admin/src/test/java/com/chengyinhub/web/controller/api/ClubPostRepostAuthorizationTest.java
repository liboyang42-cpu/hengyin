package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubGovernanceBan;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostRepost;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.vo.ClubPostRepostItemVO;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.mapper.ClubGovernanceMapper;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubPostLikeService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.impl.ClubParticipationGuardImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * dw29 L8 转发接口与动态流:与点赞同一套可见性、封禁、锁序;撤销只动本人;
 * 动态流里查看者读不到原帖的转发整条略去,其余按时间与帖文合并。
 */
class ClubPostRepostAuthorizationTest {
    private static final Long CLUB = 73L, VIEWER = 42L, POST = 81L, OTHER_CLUB = 74L;
    private ApiClubController controller;
    private IClubService clubs;
    private IClubMemberService members;
    private IClubPostService posts;
    /** 转发方法长在 IClubPostService 上(与转发数同一聚合);单独起个名字读起来清楚 */
    private IClubPostService reposts;
    private ClubGovernanceMapper governance;
    private Club club;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiClubController());
        doReturn(VIEWER).when(controller).getAppUserId();
        clubs = mock(IClubService.class);
        members = mock(IClubMemberService.class);
        posts = mock(IClubPostService.class);
        reposts = posts;
        governance = mock(ClubGovernanceMapper.class);
        ClubParticipationGuardImpl guard = new ClubParticipationGuardImpl();
        ReflectionTestUtils.setField(guard, "clubGovernanceMapper", governance);
        ReflectionTestUtils.setField(controller, "clubService", clubs);
        ReflectionTestUtils.setField(controller, "clubMemberService", members);
        ReflectionTestUtils.setField(controller, "clubPostService", posts);
        ReflectionTestUtils.setField(controller, "clubPostLikeService", mock(IClubPostLikeService.class));
        ReflectionTestUtils.setField(controller, "clubParticipationGuard", guard);
        club = club(CLUB, 1);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(club);
        when(clubs.selectClubById(CLUB)).thenReturn(club);
        when(posts.selectClubPostById(POST)).thenReturn(post(POST, CLUB, 0));
        when(posts.selectClubPostByIdForUpdate(POST)).thenReturn(post(POST, CLUB, 0));
    }

    @Nested
    class Visibility {
        @Test
        void publicClubNonmemberAndOwnPostCanRepost() {
            when(reposts.isReposted(POST, VIEWER)).thenReturn(true);
            ClubPost counted = post(POST, CLUB, 0);
            counted.setRepostCount(3);
            when(posts.selectClubPostById(POST)).thenReturn(post(POST, CLUB, 0), counted);

            AjaxResult res = controller.postRepost(body());

            assertEquals(200, res.get(AjaxResult.CODE_TAG));
            Map<?, ?> data = (Map<?, ?>) res.get(AjaxResult.DATA_TAG);
            assertEquals(true, data.get("reposted"));
            assertEquals(3, data.get("repostCount"));
            verify(reposts).setReposted(POST, VIEWER, true);

            ClubPost own = post(POST, CLUB, 0);
            own.setAuthorMemberId(VIEWER);
            when(posts.selectClubPostById(POST)).thenReturn(own);
            when(posts.selectClubPostByIdForUpdate(POST)).thenReturn(own);
            assertEquals(200, controller.postRepost(body()).get(AjaxResult.CODE_TAG), "reposting own post is allowed");
        }

        @Test
        void privateClubOutsiderPendingAndRemovedMembersCannotRepost() {
            club.setPublicVisible(0);
            for (Integer status : new Integer[] {null, 0, 2}) {
                ClubMember member = new ClubMember();
                member.setStatus(status);
                when(members.selectMembershipRecord(CLUB, VIEWER)).thenReturn(status == null ? null : member);
                AjaxResult res = controller.postRepost(body());
                assertEquals(500, res.get(AjaxResult.CODE_TAG));
                assertEquals("这个俱乐部只对成员开放", res.get(AjaxResult.MSG_TAG));
            }
            verify(reposts, never()).setReposted(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        void privateClubApprovedMemberCanRepost() {
            club.setPublicVisible(0);
            ClubMember member = new ClubMember();
            member.setStatus(1);
            when(members.selectMembershipRecord(CLUB, VIEWER)).thenReturn(member);
            assertEquals(200, controller.postRepost(body()).get(AjaxResult.CODE_TAG));
            verify(reposts).setReposted(POST, VIEWER, true);
        }

        @Test
        void deletedMissingOrDissolvedTargetsCannotBeReposted() {
            when(posts.selectClubPostById(POST)).thenReturn(post(POST, CLUB, 2));
            assertRefused(controller.postRepost(body()), "动态不存在");
            when(posts.selectClubPostById(POST)).thenReturn(null);
            assertRefused(controller.postRepost(body()), "动态不存在");
            when(posts.selectClubPostById(POST)).thenReturn(post(POST, CLUB, 0));
            when(posts.selectClubPostByIdForUpdate(POST)).thenReturn(post(POST, CLUB, 2));
            assertRefused(controller.postRepost(body()), "动态不存在");
            when(posts.selectClubPostByIdForUpdate(POST)).thenReturn(post(POST, CLUB, 0));
            when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(null);
            assertRefused(controller.postRepost(body()), "动态不存在");
            verify(reposts, never()).setReposted(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        void bannedMemberCannotRepost() {
            ClubGovernanceBan ban = new ClubGovernanceBan();
            ban.setId(91L);
            ban.setClubId(CLUB);
            ban.setTargetMemberId(VIEWER);
            ban.setStatus(ClubGovernanceBan.STATUS_ACTIVE);
            ban.setSourceType(ClubGovernanceBan.SOURCE_PLATFORM);
            when(governance.selectEffectiveByClubAndMember(eq(CLUB), eq(VIEWER), any(Date.class))).thenReturn(ban);
            ServiceException refused = assertThrows(ServiceException.class, () -> controller.postRepost(body()));
            assertEquals(Integer.valueOf(403), refused.getCode());
            verify(reposts, never()).setReposted(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        void missingPostIdOrLoginIsRefusedBeforeAnyRead() {
            assertRefused(controller.postRepost(new ClubPostRepost()), "缺少动态ID");
            assertRefused(controller.postRepost(null), "缺少动态ID");
            doThrow(new ServiceException("未登录", 401)).when(controller).getAppUserId();
            assertRefused(controller.postRepost(body()), "请先登录");
            verifyNoInteractions(reposts);
        }

        @Test
        void clubThenPostLocksAndGuardPrecedeTheWrite() {
            controller.postRepost(body());
            InOrder order = inOrder(posts, clubs, governance);
            order.verify(posts).selectClubPostById(POST);
            order.verify(clubs).selectClubByIdForUpdate(CLUB);
            order.verify(posts).selectClubPostByIdForUpdate(POST);
            order.verify(governance).selectEffectiveByClubAndMember(eq(CLUB), eq(VIEWER), any(Date.class));
            order.verify(posts).setReposted(POST, VIEWER, true);
        }
    }

    @Nested
    class Undo {
        @Test
        void undoOnlyTouchesTheViewersOwnRepostEvenAfterTheClubTurnedPrivate() {
            club.setPublicVisible(0);
            AjaxResult res = controller.postRepostUndo(body());
            assertEquals(200, res.get(AjaxResult.CODE_TAG));
            assertEquals(false, ((Map<?, ?>) res.get(AjaxResult.DATA_TAG)).get("reposted"));
            verify(reposts).setReposted(POST, VIEWER, false);
            verifyNoInteractions(governance);
        }

        @Test
        void undoStillWorksOnADeletedPost() {
            when(posts.selectClubPostById(POST)).thenReturn(post(POST, CLUB, 2));
            when(posts.selectClubPostByIdForUpdate(POST)).thenReturn(post(POST, CLUB, 2));
            assertEquals(200, controller.postRepostUndo(body()).get(AjaxResult.CODE_TAG));
            verify(reposts).setReposted(POST, VIEWER, false);
        }
    }

    @Nested
    class Feed {
        private ICoopInviteService invites;

        @BeforeEach
        void feedSetUp() {
            invites = mock(ICoopInviteService.class);
            when(invites.selectCoopInviteList(any())).thenReturn(Collections.<CoopInvite>emptyList());
            ReflectionTestUtils.setField(controller, "coopInviteService", invites);
            ReflectionTestUtils.setField(controller, "clubAccessService", mock(IClubAccessService.class));
            when(members.selectClubIdsByMember(VIEWER)).thenReturn(List.of(CLUB));
        }

        @Test
        void feedMergesRepostsByTimeAndOmitsOriginalsTheViewerCannotRead() {
            ClubPost newer = timedPost(1L, CLUB, 50), older = timedPost(2L, CLUB, 10);
            when(posts.selectFeedByClubIds(any(), anyInt())).thenReturn(new ArrayList<>(List.of(newer, older)));
            Club privateClub = club(OTHER_CLUB, 0);
            when(clubs.selectClubById(OTHER_CLUB)).thenReturn(privateClub);
            when(reposts.selectFeedReposts(eq(List.of(CLUB)), eq(null), eq(null), eq(20)))
                    .thenReturn(List.of(repost(5L, 300L, 30), repost(6L, 301L, 20)));
            // 300 在公开俱乐部;301 在查看者不是成员的私密俱乐部 → 整条略去
            when(reposts.selectOriginalPosts(any())).thenReturn(List.of(timedPost(300L, CLUB, 1), timedPost(301L, OTHER_CLUB, 1)));
            when(reposts.selectRepostedPostIds(eq(VIEWER), any())).thenReturn(Set.of(300L));

            List<?> rows = feedRows(controller.postFeed(new HashMap<>()));

            assertEquals(3, rows.size());
            assertSame(newer, rows.get(0));
            ClubPostRepostItemVO item = (ClubPostRepostItemVO) rows.get(1);
            assertSame(older, rows.get(2));
            assertEquals("repost", item.getItemType());
            assertEquals(Long.valueOf(5L), item.getRepostId());
            assertEquals(Long.valueOf(99L), item.getReposterMemberId());
            assertEquals("转发人", item.getReposterNick());
            assertEquals(Long.valueOf(300L), item.getPost().getId());
            assertTrue(item.getPost().getViewerReposted());
            assertFalse(newer.getViewerReposted());
        }

        @Test
        void feedKeepsTheRequestedLimitAfterMerging() {
            Map<String, Object> body = new HashMap<>();
            body.put("limit", 2);
            when(posts.selectFeedByClubIds(any(), anyInt())).thenReturn(new ArrayList<>(List.of(timedPost(1L, CLUB, 50), timedPost(2L, CLUB, 10))));
            when(reposts.selectFeedReposts(any(), any(), any(), anyInt())).thenReturn(List.of(repost(5L, 300L, 30)));
            when(reposts.selectOriginalPosts(any())).thenReturn(List.of(timedPost(300L, CLUB, 1)));

            List<?> rows = feedRows(controller.postFeed(body));

            assertEquals(2, rows.size());
            assertTrue(rows.get(1) instanceof ClubPostRepostItemVO);
        }

        @Test
        void clubPageWindowFollowsThePostCursor() {
            ClubPost query = new ClubPost();
            query.setClubId(CLUB);
            query.setLimit(2);
            query.setBeforeId(9L);
            ClubPost cursor = timedPost(9L, CLUB, 40);
            when(posts.selectClubPostById(9L)).thenReturn(cursor);
            ClubPost a = timedPost(8L, CLUB, 30), b = timedPost(7L, CLUB, 20);
            when(posts.selectClubPostPage(CLUB, 9L, 2)).thenReturn(new ArrayList<>(List.of(a, b)));
            when(reposts.selectFeedReposts(any(), any(), any(), anyInt())).thenReturn(List.of(repost(5L, 300L, 25)));
            when(reposts.selectOriginalPosts(any())).thenReturn(List.of(timedPost(300L, CLUB, 1)));

            AjaxResult res = controller.postList(query);

            verify(reposts).selectFeedReposts(List.of(CLUB), b.getCreateTime(), cursor.getCreateTime(), 50);
            List<?> rows = (List<?>) res.get(AjaxResult.DATA_TAG);
            assertSame(a, rows.get(0));
            assertTrue(rows.get(1) instanceof ClubPostRepostItemVO);
            assertSame(b, rows.get(2));
        }

        @Test
        void clubFirstPageKeepsPinnedOnTopAndLeavesLowerBoundOpenWhenPageIsShort() {
            ClubPost query = new ClubPost();
            query.setClubId(CLUB);
            query.setLimit(5);
            ClubPost pinned = timedPost(3L, CLUB, 1);
            pinned.setIsPinned(1);
            ClubPost a = timedPost(8L, CLUB, 30);
            when(posts.selectClubPostPage(CLUB, null, 5)).thenReturn(new ArrayList<>(List.of(pinned, a)));
            when(reposts.selectFeedReposts(any(), any(), any(), anyInt())).thenReturn(List.of(repost(5L, 300L, 45)));
            when(reposts.selectOriginalPosts(any())).thenReturn(List.of(timedPost(300L, CLUB, 1)));

            List<?> rows = (List<?>) controller.postList(query).get(AjaxResult.DATA_TAG);

            verify(reposts).selectFeedReposts(List.of(CLUB), null, null, 50);
            assertSame(pinned, rows.get(0));
            assertTrue(rows.get(1) instanceof ClubPostRepostItemVO);
            assertSame(a, rows.get(2));
        }
    }

    private static List<?> feedRows(AjaxResult res) {
        assertEquals(200, res.get(AjaxResult.CODE_TAG));
        return (List<?>) ((Map<?, ?>) res.get(AjaxResult.DATA_TAG)).get("rows");
    }

    private static void assertRefused(AjaxResult res, String message) {
        assertEquals(500, res.get(AjaxResult.CODE_TAG));
        assertEquals(message, res.get(AjaxResult.MSG_TAG));
    }

    private static ClubPostRepost body() {
        ClubPostRepost body = new ClubPostRepost();
        body.setPostId(POST);
        return body;
    }

    private static Club club(Long id, Integer publicVisible) {
        Club c = new Club();
        c.setId(id);
        c.setMemberId(9L);
        c.setPublicVisible(publicVisible);
        return c;
    }

    private static ClubPost post(Long id, Long clubId, int delFlag) {
        ClubPost p = new ClubPost();
        p.setId(id);
        p.setClubId(clubId);
        p.setDelFlag(delFlag);
        return p;
    }

    private static ClubPost timedPost(Long id, Long clubId, int minute) {
        ClubPost p = post(id, clubId, 0);
        p.setIsPinned(0);
        p.setCreateTime(at(minute));
        return p;
    }

    private static ClubPostRepost repost(Long id, Long postId, int minute) {
        ClubPostRepost r = new ClubPostRepost();
        r.setId(id);
        r.setPostId(postId);
        r.setMemberId(99L);
        r.setNickname("转发人");
        r.setCreateTime(at(minute));
        return r;
    }

    private static Date at(int minute) {
        return new Date(1_793_000_000_000L + minute * 60_000L);
    }
}
