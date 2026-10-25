package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostLike;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubPostLikeService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.nullValue;

/** 复用 ApiClubPostPrivacyProjectionTest 的真实 controller + mock service 注入方式。 */
class ClubPostLikeReadbackTest {
    private static final Long CLUB = 73L, VIEWER = 42L, POST = 5L;
    private ApiClubController controller;
    private IClubService clubs;
    private IClubMemberService members;
    private IClubPostService posts;
    private IClubPostLikeService likes;
    private Club club;
    private int requests = 1;

    @BeforeEach
    void setup() {
        controller = spy(new ApiClubController());
        doReturn(VIEWER).when(controller).getAppUserId();
        clubs = mock(IClubService.class);
        members = mock(IClubMemberService.class);
        posts = mock(IClubPostService.class);
        likes = mock(IClubPostLikeService.class);
        ReflectionTestUtils.setField(controller, "clubService", clubs);
        ReflectionTestUtils.setField(controller, "clubMemberService", members);
        ReflectionTestUtils.setField(controller, "clubPostService", posts);
        ReflectionTestUtils.setField(controller, "clubPostLikeService", likes);
        club = new Club(); club.setId(CLUB); club.setMemberId(9L); club.setPublicVisible(1);
        when(clubs.selectClubById(CLUB)).thenReturn(club);
    }

    @AfterEach
    void onlyBoundedReadMethodsMayBeCalled() {
        Set<String> allowed = Set.of("selectClubById", "selectMembershipRecord",
                "selectClubPostById", "selectByPostAndMember");
        for (Object service : List.of(clubs, members, posts, likes)) {
            mockingDetails(service).getInvocations().forEach(call ->
                    assertTrue(allowed.contains(call.getMethod().getName()), call.getMethod().getName()));
        }
        verify(posts, atMost(requests)).selectClubPostById(any());
        verify(likes, atMost(requests)).selectByPostAndMember(any(), any());
    }

    private ClubPost query(Long id, Long clubId) {
        ClubPost body = new ClubPost(); body.setId(id); body.setClubId(clubId); return body;
    }

    private ClubPost row(Long id) {
        ClubPost row = query(id, club.getId()); row.setDelFlag(0); row.setLikeCount(7);
        row.setContent("must not leave this method"); row.setAuthorMemberId(99L);
        return row;
    }

    private Map<?, ?> data(AjaxResult response) {
        assertEquals(200, response.get(AjaxResult.CODE_TAG));
        Map<?, ?> data = (Map<?, ?>) response.get(AjaxResult.DATA_TAG);
        assertEquals(Set.of("id", "clubId", "isLiked", "likeCount"), data.keySet());
        return data;
    }

    @Test
    void publicClubReturnsOnlyCurrentBooleanAndOriginalCount() {
        when(posts.selectClubPostById(POST)).thenReturn(row(POST));
        when(likes.selectByPostAndMember(POST, VIEWER)).thenReturn(new ClubPostLike());
        assertEquals(Map.of("id", "5", "clubId", "73", "isLiked", true, "likeCount", 7),
                data(controller.postLikeState(query(POST, CLUB))));
        verify(clubs).selectClubById(CLUB);
        verifyNoInteractions(members);
        verify(posts).selectClubPostById(POST);
        verify(likes).selectByPostAndMember(POST, VIEWER);
    }

    @Test
    void nullableCountIsNotFabricatedAndUnlikedIsStrictBoolean() {
        ClubPost row = row(POST); row.setLikeCount(null);
        when(posts.selectClubPostById(POST)).thenReturn(row);
        Map<?, ?> data = data(controller.postLikeState(query(POST, CLUB)));
        assertEquals(Boolean.FALSE, data.get("isLiked"));
        assertTrue(data.containsKey("likeCount")); assertNull(data.get("likeCount"));
    }

    @Test
    void httpResponsePreservesLongStringsBooleanAndNullableCount() throws Exception {
        club.setId(Long.MAX_VALUE);
        when(clubs.selectClubById(Long.MAX_VALUE)).thenReturn(club);
        ClubPost row = row(Long.MAX_VALUE); row.setLikeCount(null);
        when(posts.selectClubPostById(Long.MAX_VALUE)).thenReturn(row);
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/api/club/post/like-state").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"9223372036854775807\",\"clubId\":\"9223372036854775807\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", aMapWithSize(4)))
                .andExpect(jsonPath("$.data.id").value("9223372036854775807"))
                .andExpect(jsonPath("$.data.clubId").value("9223372036854775807"))
                .andExpect(jsonPath("$.data.isLiked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(nullValue()));
    }

    @Test
    void privateClubAllowsExistingApprovedMember() {
        club.setPublicVisible(0);
        ClubMember member = new ClubMember(); member.setStatus(1);
        when(members.selectMembershipRecord(CLUB, VIEWER)).thenReturn(member);
        when(posts.selectClubPostById(POST)).thenReturn(row(POST));
        assertEquals(Boolean.FALSE, data(controller.postLikeState(query(POST, CLUB))).get("isLiked"));
        verify(members).selectMembershipRecord(CLUB, VIEWER);
    }

    @Test
    void privateClubOwnerUsesSameVisibilityRuleWithoutMembershipRead() {
        club.setPublicVisible(0); club.setMemberId(VIEWER);
        when(posts.selectClubPostById(POST)).thenReturn(row(POST));
        data(controller.postLikeState(query(POST, CLUB)));
        verifyNoInteractions(members);
    }

    @Test
    void privateClubNonmemberCannotReadPostOrLike() {
        club.setPublicVisible(0);
        assertNotEquals(200, controller.postLikeState(query(POST, CLUB)).get(AjaxResult.CODE_TAG));
        verifyNoInteractions(posts, likes);
    }

    @Test
    void nonexistentClubCannotReadPostOrLike() {
        when(clubs.selectClubById(CLUB)).thenReturn(null);
        assertNotEquals(200, controller.postLikeState(query(POST, CLUB)).get(AjaxResult.CODE_TAG));
        verifyNoInteractions(posts, likes);
    }

    @Test
    void crossClubPostCannotReadLikeOrExposePostFields() {
        ClubPost row = row(POST); row.setClubId(74L);
        when(posts.selectClubPostById(POST)).thenReturn(row);
        AjaxResult response = controller.postLikeState(query(POST, CLUB));
        assertNotEquals(200, response.get(AjaxResult.CODE_TAG));
        assertFalse(response.containsKey(AjaxResult.DATA_TAG));
        verifyNoInteractions(likes);
    }

    @Test
    void missingDeletedAndUnconfirmedDeletionStateNeverReadLike() {
        requests = 4;
        for (Integer flag : new Integer[] {null, 1, 2}) {
            ClubPost row = row(POST); row.setDelFlag(flag);
            when(posts.selectClubPostById(POST)).thenReturn(row);
            assertNotEquals(200, controller.postLikeState(query(POST, CLUB)).get(AjaxResult.CODE_TAG));
        }
        when(posts.selectClubPostById(POST)).thenReturn(null);
        assertNotEquals(200, controller.postLikeState(query(POST, CLUB)).get(AjaxResult.CODE_TAG));
        verifyNoInteractions(likes);
        verify(posts, times(requests)).selectClubPostById(POST);
    }

    @Test
    void nullAndNonpositiveIdsCauseZeroServiceReads() {
        assertNotEquals(200, controller.postLikeState(null).get(AjaxResult.CODE_TAG));
        for (Long id : new Long[] {null, 0L, -1L, Long.MIN_VALUE}) {
            assertNotEquals(200, controller.postLikeState(query(id, CLUB)).get(AjaxResult.CODE_TAG));
            assertNotEquals(200, controller.postLikeState(query(POST, id)).get(AjaxResult.CODE_TAG));
        }
        verifyNoInteractions(clubs, members, posts, likes);
    }

    @Test
    void anonymousAndInvalidPrincipalCauseZeroServiceReads() {
        for (Long principal : new Long[] {null, 0L, -1L}) {
            doReturn(principal).when(controller).getAppUserId();
            assertNotEquals(200, controller.postLikeState(query(POST, CLUB)).get(AjaxResult.CODE_TAG));
        }
        doThrow(new IllegalStateException("not logged in")).when(controller).getAppUserId();
        assertNotEquals(200, controller.postLikeState(query(POST, CLUB)).get(AjaxResult.CODE_TAG));
        verifyNoInteractions(clubs, members, posts, likes);
    }

    @Test
    void malformedAndOverflowLongJsonFailBeforeServiceReads() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String json : new String[] {"{\"id\":\"9223372036854775808\",\"clubId\":73}",
                "{\"id\":5,\"clubId\":\"bad\"}"}) {
            mvc.perform(post("/api/club/post/like-state").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(clubs, members, posts, likes);
    }

    @Test
    void pinnedBeyondFiftyAndLongMaxNeverUseListOrPagination() {
        club.setId(Long.MAX_VALUE);
        when(clubs.selectClubById(Long.MAX_VALUE)).thenReturn(club);
        List<ClubPost> newerPins = new ArrayList<>();
        for (long id = 1; id <= 80; id++) newerPins.add(row(id));
        when(posts.selectClubPostPage(anyLong(), any(), anyInt())).thenReturn(newerPins);
        ClubPost target = row(Long.MAX_VALUE); target.setIsPinned(1); target.setType(2);
        when(posts.selectClubPostById(Long.MAX_VALUE)).thenReturn(target);
        Map<?, ?> data = data(controller.postLikeState(query(Long.MAX_VALUE, Long.MAX_VALUE)));
        assertEquals("9223372036854775807", data.get("id"));
        assertEquals("9223372036854775807", data.get("clubId"));
        verify(posts).selectClubPostById(Long.MAX_VALUE);
        verify(posts, never()).selectClubPostPage(anyLong(), any(), anyInt());
    }

    @Test
    void smallestLongIdUnpinnedDeepPostAlsoUsesOneExactRead() {
        when(posts.selectClubPostById(1L)).thenReturn(row(1L));
        assertEquals("1", data(controller.postLikeState(query(1L, CLUB))).get("id"));
        verify(posts).selectClubPostById(1L);
    }
}
