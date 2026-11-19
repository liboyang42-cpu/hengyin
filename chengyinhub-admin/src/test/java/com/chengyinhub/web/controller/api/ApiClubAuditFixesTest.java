package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostComment;
import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.vo.PublicClubVO;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICapabilityGuard;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubPostCommentService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 审计项 MC-031/032/053/054/055/060/061/063 的控制器行为。 */
class ApiClubAuditFixesTest {

    private static final Long ME = 501L;
    private static final Long OWNER = 600L;
    private static final Long CLUB = 70L;

    private ApiClubController controller;
    private IClubService clubs;
    private IClubMemberService members;
    private IClubPostService posts;
    private IClubPostCommentService comments;
    private IAuditTaskService audit;
    private IClubAccessService access;
    private ICmsTopicService topics;
    private GrowthBadgeMapper badges;
    private Club club;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiClubController());
        doReturn(ME).when(controller).getAppUserId();
        clubs = mock(IClubService.class);
        members = mock(IClubMemberService.class);
        posts = mock(IClubPostService.class);
        comments = mock(IClubPostCommentService.class);
        audit = mock(IAuditTaskService.class);
        access = mock(IClubAccessService.class);
        topics = mock(ICmsTopicService.class);
        badges = mock(GrowthBadgeMapper.class);
        ReflectionTestUtils.setField(controller, "clubService", clubs);
        ReflectionTestUtils.setField(controller, "clubMemberService", members);
        ReflectionTestUtils.setField(controller, "clubPostService", posts);
        ReflectionTestUtils.setField(controller, "clubPostCommentService", comments);
        ReflectionTestUtils.setField(controller, "auditTaskService", audit);
        ReflectionTestUtils.setField(controller, "clubAccessService", access);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
        ReflectionTestUtils.setField(controller, "growthBadgeMapper", badges);
        ReflectionTestUtils.setField(controller, "clubLeaderService", mock(IClubLeaderService.class));
        ReflectionTestUtils.setField(controller, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        club.setStatus(1L);
        club.setName("公开团");
        when(clubs.selectClubById(CLUB)).thenReturn(club);
    }

    private Club privateClub() {
        club.setPublicVisible(0);
        return club;
    }

    private ClubPost post(Long author, Integer delFlag) {
        ClubPost p = new ClubPost();
        p.setId(9L);
        p.setClubId(CLUB);
        p.setAuthorMemberId(author);
        p.setDelFlag(delFlag);
        when(posts.selectClubPostById(9L)).thenReturn(p);
        return p;
    }

    private static String msg(AjaxResult r) { return String.valueOf(r.get(AjaxResult.MSG_TAG)); }

    // ---------- MC-031 举报动态 ----------

    @Test
    void authorCannotReportOwnPost() {
        post(ME, 0);
        ClubPost body = new ClubPost();
        body.setId(9L);
        AjaxResult r = controller.postReport(body);
        assertEquals("不能举报自己的动态", msg(r));
        verify(audit, never()).reportContent(anyString(), anyLong(), anyLong(), anyString());
    }

    @Test
    void nonMemberCannotReportPostInPrivateClub() {
        privateClub();
        post(777L, 0);
        ClubPost body = new ClubPost();
        body.setId(9L);
        AjaxResult r = controller.postReport(body);
        assertEquals("这个俱乐部只对成员开放", msg(r));
        verify(audit, never()).reportContent(anyString(), anyLong(), anyLong(), anyString());
    }

    @Test
    void visiblePostByOthersCanBeReported() {
        post(777L, 0);
        ClubPost body = new ClubPost();
        body.setId(9L);
        assertEquals(200, controller.postReport(body).get(AjaxResult.CODE_TAG));
        verify(audit).reportContent("CLUB_POST", 9L, ME, "用户举报");
    }

    // ---------- MC-053 评论列表 ----------

    @Test
    void commentListOfDeletedPostIsRefused() {
        post(777L, 2);
        ClubPostComment body = new ClubPostComment();
        body.setPostId(9L);
        assertEquals("动态不存在", msg(controller.commentList(body)));
        verify(comments, never()).selectClubPostCommentList(any());
    }

    @Test
    void commentListOfLivePostStillWorks() {
        post(777L, 0);
        when(comments.selectClubPostCommentList(any())).thenReturn(Collections.emptyList());
        ClubPostComment body = new ClubPostComment();
        body.setPostId(9L);
        assertEquals(200, controller.commentList(body).get(AjaxResult.CODE_TAG));
    }

    // ---------- MC-061 删除评论计数 ----------

    private ClubPostComment ownComment() {
        ClubPostComment c = new ClubPostComment();
        c.setId(3L);
        c.setPostId(9L);
        c.setMemberId(ME);
        when(comments.selectClubPostCommentById(3L)).thenReturn(c);
        post(777L, 0);
        return c;
    }

    @Test
    void commentDeleteDecrementsOnlyWhenARowWasDeleted() {
        ownComment();
        when(comments.deleteClubPostCommentById(3L)).thenReturn(0);
        ClubPostComment body = new ClubPostComment();
        body.setId(3L);
        controller.commentDelete(body);
        verify(posts, never()).incrementCommentCount(anyLong(), anyInt());

        when(comments.deleteClubPostCommentById(3L)).thenReturn(1);
        controller.commentDelete(body);
        verify(posts).incrementCommentCount(9L, -1);
    }

    // ---------- MC-054 勋章列表 ----------

    @Test
    void badgeListOfPrivateClubIsHiddenFromNonMembers() {
        privateClub();
        Club body = new Club();
        body.setId(CLUB);
        assertEquals("这个俱乐部只对成员开放", msg(controller.badgeList(body)));
        verify(badges, never()).selectByClub(any());
    }

    // ---------- MC-032 勋章编码与上限 ----------

    private Map<String, Object> badgeBody() {
        Map<String, Object> b = new HashMap<>();
        b.put("clubId", CLUB);
        b.put("badgeName", "到场勋章");
        b.put("iconUrl", "");
        return b;
    }

    @Test
    void badgeCreateRefusesAtPerClubCap() {
        doReturn(OWNER).when(controller).getAppUserId();
        when(badges.countByClub(CLUB)).thenReturn(ApiClubController.MAX_BADGES_PER_CLUB);
        assertEquals("本俱乐部勋章数量已达上限", msg(controller.badgeCreate(badgeBody())));
        verify(badges, never()).insertGrowthBadge(any());
    }

    @Test
    void badgeCodeCarriesRandomSuffix() {
        doReturn(OWNER).when(controller).getAppUserId();
        when(badges.countByClub(CLUB)).thenReturn(0);
        controller.badgeCreate(badgeBody());
        controller.badgeCreate(badgeBody());
        ArgumentCaptor<GrowthBadge> cap = ArgumentCaptor.forClass(GrowthBadge.class);
        verify(badges, org.mockito.Mockito.times(2)).insertGrowthBadge(cap.capture());
        String a = cap.getAllValues().get(0).getBadgeCode();
        String b = cap.getAllValues().get(1).getBadgeCode();
        assertTrue(a.matches("CLUB" + CLUB + "_\\d+_[0-9a-f]{8}"), a);
        assertTrue(a.length() <= 64);
        assertFalse(a.equals(b));
    }

    // ---------- MC-055 / MC-060 / MC-063 详情 ----------

    private PublicClubVO detail() {
        Club body = new Club();
        body.setId(CLUB);
        return (PublicClubVO) controller.detail(body).get(AjaxResult.DATA_TAG);
    }

    @Test
    void detailCountsTopicsWithoutLoadingRowsAndFormatsMemberPrice() {
        club.setMemberDiscountPrice(new BigDecimal("12.5"));
        when(topics.countCmsTopicByClubId(CLUB)).thenReturn(4);
        PublicClubVO vo = detail();
        assertEquals(Integer.valueOf(4), vo.getTopicCount());
        assertEquals("¥12.50", vo.getMemberDiscountPriceText());
        verify(topics, never()).selectCmsTopicList(any());
    }

    @Test
    void detailMemberPriceTextIsNullWithoutPrice() {
        assertEquals(null, detail().getMemberDiscountPriceText());
    }

    @Test
    void viewerCanGovernComesFromClubAccessContext() {
        when(access.resolve(ME, CLUB, null)).thenReturn(new ClubAccessContext(ME, CLUB, OWNER, null,
                new LinkedHashSet<>(Collections.singletonList(ClubRoleCode.CLUB_OPERATOR))));
        assertTrue(detail().getViewerCanGovern());
    }

    @Test
    void viewerCanGovernFalseForPlainMemberGuestAndFailures() {
        when(access.resolve(ME, CLUB, null)).thenReturn(new ClubAccessContext(ME, CLUB, OWNER, null,
                new LinkedHashSet<>(Collections.singletonList(ClubRoleCode.CLUB_MEMBER))));
        assertFalse(detail().getViewerCanGovern());

        when(access.resolve(ME, CLUB, null)).thenThrow(new RuntimeException("boom"));
        assertFalse(detail().getViewerCanGovern());

        doThrow(new RuntimeException("anonymous")).when(controller).getAppUserId();
        assertFalse(detail().getViewerCanGovern());
    }
}
