package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubPostLikeService;
import com.chengyinhub.business.service.IClubPostRepostService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * 聚合动态流的范围闸。
 *
 * 这不是全站公开流:全站流会把毫无关系的俱乐部内容推给不相干的人,
 * 也会让「加入 / 合作」这件事失去意义。范围只有两个来源:
 *   我加入的俱乐部(含我自己建的 —— 创建者也是成员)
 *   我作为商家谈成过(邀约 status=1)的俱乐部
 *
 * ⚠️ 负控已实测:把 status=1 的过滤去掉 ⇒ 未接受的邀约也进范围,对应用例转红。
 */
class ClubPostFeedScopeTest {

    private static final Long ME = 88L;

    private IClubMemberService memberSvc;
    private ICoopInviteService inviteSvc;
    private IClubPostService postSvc;
    private IClubService clubSvc;
    private IClubAccessService accessSvc;
    private ApiClubController controller;

    @BeforeEach
    void setUp() {
        memberSvc = mock(IClubMemberService.class);
        inviteSvc = mock(ICoopInviteService.class);
        postSvc = mock(IClubPostService.class);
        clubSvc = mock(IClubService.class);
        IClubPostLikeService likeSvc = mock(IClubPostLikeService.class);
        accessSvc = mock(IClubAccessService.class);

        when(memberSvc.selectClubIdsByMember(anyLong())).thenReturn(Collections.<Long>emptyList());
        when(inviteSvc.selectCoopInviteList(any())).thenReturn(Collections.<CoopInvite>emptyList());
        when(postSvc.selectFeedByClubIds(any(), anyInt())).thenReturn(Collections.<ClubPost>emptyList());

        controller = spy(new ApiClubController());
        doReturn(ME).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "clubMemberService", memberSvc);
        ReflectionTestUtils.setField(controller, "coopInviteService", inviteSvc);
        ReflectionTestUtils.setField(controller, "clubPostService", postSvc);
        ReflectionTestUtils.setField(controller, "clubPostLikeService", likeSvc);
        ReflectionTestUtils.setField(controller, "clubPostRepostService", mock(IClubPostRepostService.class));
        ReflectionTestUtils.setField(controller, "clubService", clubSvc);
        ReflectionTestUtils.setField(controller, "clubAccessService", accessSvc);
    }

    private CoopInvite clubInvite(Long clubId, Integer status) {
        CoopInvite i = new CoopInvite();
        i.setToType("club");
        i.setToId(clubId);
        i.setStatus(status);
        return i;
    }

    @SuppressWarnings("unchecked")
    private List<Long> captureScope() {
        ArgumentCaptor<List<Long>> cap = ArgumentCaptor.forClass(List.class);
        verify(postSvc).selectFeedByClubIds(cap.capture(), anyInt());
        return cap.getValue();
    }

    private Map<String, Object> body(Object limit) {
        Map<String, Object> b = new HashMap<>();
        if (limit != null) b.put("limit", limit);
        return b;
    }

    /** 玩家:我加入的俱乐部(创建者也在 club_member 里,所以自建的一并覆盖)。 */
    @Test
    @Timeout(5)
    void joinedClubs_inScope() {
        when(memberSvc.selectClubIdsByMember(ME)).thenReturn(Arrays.asList(1L, 2L));

        controller.postFeed(body(null));

        assertEquals(Arrays.asList(1L, 2L), captureScope());
    }

    /** 商家:谈成过的俱乐部也在范围里 —— 合作方发了什么,他该看得到。 */
    @Test
    @Timeout(5)
    void acceptedCoopClubs_inScope() {
        when(inviteSvc.selectCoopInviteList(any()))
                .thenReturn(Collections.singletonList(clubInvite(9L, 1)));
        Club active = new Club();
        active.setId(9L);
        when(clubSvc.selectClubById(9L)).thenReturn(active);

        controller.postFeed(body(null));

        assertEquals(Collections.singletonList(9L), captureScope());
    }

    @Test
    @Timeout(5)
    void dissolvedAcceptedCoopDoesNotInflateEmptyFeedScope() {
        when(inviteSvc.selectCoopInviteList(any()))
                .thenReturn(Collections.singletonList(clubInvite(9L, 1)));
        when(clubSvc.selectClubById(9L)).thenReturn(null);

        AjaxResult result = controller.postFeed(body(null));

        assertTrue(captureScope().isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertTrue(((List<?>) data.get("rows")).isEmpty());
        assertEquals(0, data.get("clubCount"));
    }

    /**
     * ★ 只有已接受的邀约算数。待确认/被拒的不是合作关系,
     * 把对方的内容推给一个人家还没答应合作的商家,是拿邀约当关注用。
     */
    @Test
    @Timeout(5)
    void pendingOrRejectedCoop_notInScope() {
        controller.postFeed(body(null));

        ArgumentCaptor<CoopInvite> cap = ArgumentCaptor.forClass(CoopInvite.class);
        verify(inviteSvc).selectCoopInviteList(cap.capture());
        assertEquals(Integer.valueOf(1), cap.getValue().getStatus(), "查询必须钉死 status=1(已接受)");
        assertEquals("club", cap.getValue().getToType());
        assertEquals(ME, cap.getValue().getFromId());
    }

    /** 两个来源去重:既加入了又合作过,不该出现两次。 */
    @Test
    @Timeout(5)
    void overlappingSources_deduped() {
        when(memberSvc.selectClubIdsByMember(ME)).thenReturn(Arrays.asList(5L));
        when(inviteSvc.selectCoopInviteList(any()))
                .thenReturn(Collections.singletonList(clubInvite(5L, 1)));

        controller.postFeed(body(null));

        assertEquals(Collections.singletonList(5L), captureScope());
    }

    /** 一个俱乐部都没有 = 正常状态,返回空列表而不是报错。 */
    @Test
    @Timeout(5)
    void emptyScope_returnsEmptyNotError() {
        AjaxResult r = controller.postFeed(body(null));

        assertEquals(200, r.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) r.get("data");
        assertTrue(((List<?>) d.get("rows")).isEmpty());
        assertEquals(0, d.get("clubCount"));
    }

    /** limit 是外部输入:非法值不得炸,也不得让人一次拉走整张表。 */
    @Test
    @Timeout(5)
    void limitIsSanitised() {
        controller.postFeed(body("abc"));
        ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
        verify(postSvc).selectFeedByClubIds(any(), cap.capture());
        assertEquals(Integer.valueOf(20), cap.getValue(), "解析不了就用默认值,不该抛出去");
    }

    /** 越界的 limit 夹住:别让一次请求把整张 club_post 拉走。 */
    @Test
    @Timeout(5)
    void limitIsClamped() {
        controller.postFeed(body(9999));
        ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
        verify(postSvc).selectFeedByClubIds(any(), cap.capture());
        assertEquals(Integer.valueOf(50), cap.getValue());
    }

    @Test
    @Timeout(5)
    void feedRowsExposeTheViewerContentManagementCapability() {
        when(memberSvc.selectClubIdsByMember(ME)).thenReturn(Collections.singletonList(5L));
        ClubPost post = new ClubPost();
        post.setId(7L);
        post.setClubId(5L);
        ClubPost secondPost = new ClubPost();
        secondPost.setId(8L);
        secondPost.setClubId(5L);
        when(postSvc.selectFeedByClubIds(any(), anyInt())).thenReturn(Arrays.asList(post, secondPost));
        when(accessSvc.resolve(ME, 5L, null)).thenReturn(new ClubAccessContext(
                ME, 5L, 1L, null,
                new LinkedHashSet<>(Collections.singletonList(ClubRoleCode.CLUB_OWNER))));

        AjaxResult result = controller.postFeed(body(null));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        ClubPost row = (ClubPost) ((List<?>) data.get("rows")).get(0);
        assertEquals(Boolean.TRUE, row.getViewerCanManage());
        verify(accessSvc, times(1)).resolve(ME, 5L, null);
    }
}
