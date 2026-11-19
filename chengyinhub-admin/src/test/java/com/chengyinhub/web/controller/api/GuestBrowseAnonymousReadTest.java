package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ViewCreativeSquare;
import com.chengyinhub.business.domain.vo.PublicClubVO;
import com.chengyinhub.business.domain.vo.TopicInfoVO;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IViewCollaboratorsService;
import com.chengyinhub.business.service.IViewCreativeSquareService;
import com.chengyinhub.business.service.impl.CreativeSquareCommunityPolicyServiceImpl;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * owner 2026-10-24「游客可浏览，写入时弹窗登录」:本次只对游客开放四条公开只读接口(@Anonymous,不改 SecurityConfig):
 * POST /api/topic/info-to-user、/api/club/detail、/api/club/list、/api/creativesquare/info。
 * 游客(无登录态,getAppUserId 抛 401)读到的只能是公开投影:本人态一律为否/空;同控制器的写兄弟照旧要求登录。
 */
class GuestBrowseAnonymousReadTest {

    private static final long TOPIC_ID = 7001L;
    private static final long AUTHOR = 901L;

    private static <T extends com.chengyinhub.common.core.controller.BaseController> T guest(T controller) {
        doThrow(new ServiceException("获取APP用户信息异常", HttpStatus.UNAUTHORIZED)).when(controller).getAppUserId();
        return controller;
    }

    // ---------- 主题详情 ----------

    @Test
    void anonymousTopicDetailReturnsPublicProjectionWithoutPersonalState() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(AUTHOR);
        topic.setDelFlag(0);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setProductType(1);
        topic.setName("公开主题");
        topic.setReason("驳回原因只给作者");
        ICmsTopicService topics = mock(ICmsTopicService.class);
        when(topics.selectCmsTopicDetailById(TOPIC_ID, 0L)).thenReturn(topic);
        ApiTopicController controller = guest(spy(new ApiTopicController()));
        wireTopicInfo(controller, topics);

        AjaxResult result = controller.topicInfo(String.valueOf(TOPIC_ID));

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        TopicInfoVO vo = (TopicInfoVO) result.get("data");
        assertEquals("公开主题", vo.getName());
        assertEquals(Integer.valueOf(0), vo.getIsOwner());
        assertEquals(Integer.valueOf(0), vo.getIsSignUp());
        assertEquals(Integer.valueOf(0), vo.getIsRegistrationMerchant());
        assertFalse(Boolean.TRUE.equals(vo.getCanResubmitSupplyReview()));
        assertNull(vo.getRejectReason(), "驳回原因不得给游客");
        // 可见性只走公开闸:viewer=0 不能命中「本人草稿」分支
        verify(topics).selectCmsTopicDetailById(TOPIC_ID, 0L);
        verify(topics, never()).selectCmsTopicDetailById(eq(TOPIC_ID), eq((Long) null));
    }

    @Test
    void anonymousTopicInvalidIdDoesNotReadPublicOrRawTopic() {
        ICmsTopicService topics = mock(ICmsTopicService.class);
        ApiTopicController controller = guest(spy(new ApiTopicController()));
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);

        for (String id : new String[] { null, "", "0", "-1", "not-an-id" }) {
            AjaxResult result = controller.topicInfo(id);
            assertEquals(400, result.get(AjaxResult.CODE_TAG));
            assertEquals("参数不合规", result.get(AjaxResult.MSG_TAG));
            assertFalse(result.containsKey(AjaxResult.DATA_TAG));
        }
        org.mockito.Mockito.verifyNoInteractions(topics);
    }

    @Test
    void missingOrHiddenTopicReturnsNotFoundForGuestAndAuthenticatedViewerWithoutRawFallback() {
        for (long viewer : new long[] { 0L, 990050L }) {
            ICmsTopicService topics = mock(ICmsTopicService.class);
            ApiTopicController controller = spy(new ApiTopicController());
            if (viewer == 0L) guest(controller);
            else org.mockito.Mockito.doReturn(viewer).when(controller).getAppUserId();
            ReflectionTestUtils.setField(controller, "cmsTopicService", topics);

            for (long topicId : new long[] { 9912627L, 9999999L }) {
                AjaxResult result = controller.topicInfo(String.valueOf(topicId));
                assertEquals(404, result.get(AjaxResult.CODE_TAG));
                assertEquals("主题活动不存在", result.get(AjaxResult.MSG_TAG));
                assertFalse(result.containsKey(AjaxResult.DATA_TAG));
                verify(topics).selectCmsTopicDetailById(topicId, viewer);
            }
            verify(topics, never()).selectCmsTopicById(anyLong());
            verify(topics, never()).canResubmitSupplyReview(anyLong(), anyLong());
        }
    }

    @Test
    void topicWriteSiblingsStillRequireLogin() {
        ApiTopicController controller = guest(spy(new ApiTopicController()));
        assertThrows(ServiceException.class, () -> controller.topicLike(String.valueOf(TOPIC_ID), "1"));
    }

    private void wireTopicInfo(ApiTopicController controller, ICmsTopicService topics) {
        ReflectionTestUtils.setField(controller, "merchantBusinessStatusService",
                mock(com.chengyinhub.business.service.IMerchantBusinessStatusService.class));
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
        ICmsTopicChapterService chapterService = mock(ICmsTopicChapterService.class);
        when(chapterService.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", chapterService);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", mock(ICmsTopicNodeService.class));
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "omsTicketService", ticketService);
        ReflectionTestUtils.setField(controller, "viewCollaboratorsService", mock(IViewCollaboratorsService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService",
                mock(com.chengyinhub.business.service.ICmsRegistrationMerchantService.class));
        ReflectionTestUtils.setField(controller, "umsCommentService",
                mock(com.chengyinhub.business.service.IUmsCommentService.class));
        ReflectionTestUtils.setField(controller, "commentService",
                mock(com.chengyinhub.business.service.IViewCommentService.class));
        ReflectionTestUtils.setField(controller, "viewRegistrationService",
                mock(com.chengyinhub.business.service.IViewRegistrationService.class));
        ReflectionTestUtils.setField(controller, "cmsActivityService",
                mock(com.chengyinhub.business.service.ICmsActivityService.class));
        ReflectionTestUtils.setField(controller, "cmsRegistrationMapper",
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(controller, "playerNodeProgressService",
                mock(com.chengyinhub.business.service.IPlayerNodeProgressService.class));
    }

    // ---------- 俱乐部详情 / 目录 ----------

    private Club club(long id, Integer publicVisible) {
        Club club = new Club();
        club.setId(id);
        club.setMemberId(AUTHOR);
        club.setName("club-" + id);
        club.setStatus(1L);
        club.setPublicVisible(publicVisible);
        club.setPhone("13800000000");
        return club;
    }

    private ApiClubController clubController(IClubService clubs, IClubMemberService members) {
        ApiClubController controller = guest(spy(new ApiClubController()));
        ReflectionTestUtils.setField(controller, "clubService", clubs);
        ReflectionTestUtils.setField(controller, "clubMemberService", members);
        ReflectionTestUtils.setField(controller, "clubLeaderService", mock(IClubLeaderService.class));
        ICmsTopicService topics = mock(ICmsTopicService.class);
        when(topics.selectCmsTopicList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
        return controller;
    }

    @Test
    void anonymousClubDetailReturnsPublicInfoAndNoViewerState() {
        IClubService clubs = mock(IClubService.class);
        IClubMemberService members = mock(IClubMemberService.class);
        when(clubs.selectClubById(5L)).thenReturn(club(5L, 1));
        when(clubs.selectWelcomeNote(5L)).thenReturn("欢迎");
        when(members.countEffectiveMembers(any())).thenReturn(3);
        when(members.countByClub(5L)).thenReturn(2);

        AjaxResult result = clubController(clubs, members).detail(club(5L, null));

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        PublicClubVO vo = (PublicClubVO) result.get("data");
        assertEquals(Long.valueOf(5L), vo.getId());
        assertEquals(Integer.valueOf(3), vo.getMemberCount());
        assertFalse(Boolean.TRUE.equals(vo.getIsOwner()));
        assertFalse(Boolean.TRUE.equals(vo.getIsJoined()));
        assertFalse(Boolean.TRUE.equals(vo.getViewerIsAdmin()));
        assertEquals("", vo.getMyJoinReviewReason());
        assertEquals(Integer.valueOf(0), vo.getPendingJoinRequestCount());
        assertNoPublicGetter(PublicClubVO.class, "getPhone");
        verify(members, never()).countPendingJoinRequests(anyLong());
    }

    @Test
    void anonymousClubDetailOfMembersOnlyClubStaysClosed() {
        IClubService clubs = mock(IClubService.class);
        when(clubs.selectClubById(6L)).thenReturn(club(6L, 0));

        AjaxResult result = clubController(clubs, mock(IClubMemberService.class)).detail(club(6L, null));

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("这个俱乐部只对成员开放", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void anonymousClubDirectoryListsOnlyPublicClubsWithoutJoinFlags() {
        IClubService clubs = mock(IClubService.class);
        IClubMemberService members = mock(IClubMemberService.class);
        when(clubs.selectClubList(any())).thenReturn(new ArrayList<>(Arrays.asList(club(5L, 1), club(6L, 0))));

        AjaxResult result = clubController(clubs, members).list(null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        @SuppressWarnings("unchecked")
        List<PublicClubVO> rows = (List<PublicClubVO>) result.get("data");
        assertEquals(1, rows.size(), "非公开团不得出现在游客目录里");
        assertEquals(Long.valueOf(5L), rows.get(0).getId());
        assertFalse(Boolean.TRUE.equals(rows.get(0).getIsJoined()));
        assertFalse(Boolean.TRUE.equals(rows.get(0).getIsOwner()));
        verify(members, never()).selectClubIdsByMember(any());
    }

    @Test
    void clubWriteAndPersonalSiblingsStillRequireLogin() {
        ApiClubController controller = clubController(mock(IClubService.class), mock(IClubMemberService.class));
        assertEquals("请先登录", controller.join(club(5L, 1)).get(AjaxResult.MSG_TAG));
        assertEquals("请先登录", controller.home().get(AjaxResult.MSG_TAG));
    }

    // ---------- 第十二轮 k2:游客看俱乐部页的主题 / 帖文 / 成员 / 贡献榜 ----------

    private static void stubJoinPolicy(IClubService clubs, long clubId, int policy) {
        when(clubs.isJoinPolicySupported()).thenReturn(true);
        when(clubs.selectJoinPolicy(clubId)).thenReturn(policy);
    }

    @Test
    void anonymousMembersOfLivePublicClubAreProjectedWithoutIds() {
        IClubService clubs = mock(IClubService.class);
        IClubMemberService members = mock(IClubMemberService.class);
        when(clubs.selectClubById(5L)).thenReturn(club(5L, 1));
        stubJoinPolicy(clubs, 5L, 0);
        com.chengyinhub.business.domain.ClubMember row = new com.chengyinhub.business.domain.ClubMember();
        row.setClubId(5L);
        row.setMemberId(AUTHOR);
        row.setRole(2);
        row.setStatus(1);
        row.setNickname("阿城");
        row.setJoinMessage("我的手机号 13800000000");
        row.setReviewReason("内部备注");
        row.setJoinTime(new java.util.Date(1793860877000L)); // 2026-11-05 14:41:17 北京时间
        when(members.selectClubMemberList(any())).thenReturn(new ArrayList<>(Collections.singletonList(row)));
        com.chengyinhub.business.domain.ClubMember body = new com.chengyinhub.business.domain.ClubMember();
        body.setClubId(5L);

        AjaxResult result = clubController(clubs, members).members(body);

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> rows = (List<java.util.Map<String, Object>>) result.get("data");
        assertEquals(1, rows.size());
        assertEquals("阿城", rows.get(0).get("nickname"));
        assertEquals(Boolean.TRUE, rows.get(0).get("isOwner"));
        // 第十二轮 k7:与登录成员名单同一格式,不是 ISO 原样
        assertEquals("2026-11-05 14:41:17", rows.get(0).get("joinTime"));
        for (String hidden : new String[]{"memberId", "joinMessage", "reviewReason", "reviewerMemberId", "phone", "id"}) {
            assertFalse(rows.get(0).containsKey(hidden), hidden + " 不得给游客");
        }
    }

    @Test
    void anonymousRosterAndBoardStayClosedForReviewJoinPrivateOrClosedClubs() {
        IClubService clubs = mock(IClubService.class);
        IClubMemberService members = mock(IClubMemberService.class);
        when(clubs.selectClubById(5L)).thenReturn(club(5L, 1));
        stubJoinPolicy(clubs, 5L, 1);              // 需审核的团:与已登录非成员一样不开名单
        Club closed = club(7L, 1);
        closed.setStatus(0L);                      // 停业
        when(clubs.selectClubById(7L)).thenReturn(closed);
        stubJoinPolicy(clubs, 7L, 0);
        when(clubs.selectClubById(6L)).thenReturn(club(6L, 0));   // 不对外可见
        ApiClubController controller = clubController(clubs, members);
        for (long id : new long[]{5L, 6L, 7L}) {
            com.chengyinhub.business.domain.ClubMember body = new com.chengyinhub.business.domain.ClubMember();
            body.setClubId(id);
            assertNotEquals(200, controller.members(body).get(AjaxResult.CODE_TAG), "club " + id);
            java.util.Map<String, Object> board = new java.util.HashMap<>();
            board.put("id", id);
            assertNotEquals(200, controller.leaderboard(board).get(AjaxResult.CODE_TAG), "club " + id);
        }
        verify(members, never()).selectClubMemberList(any());
        verify(members, never()).selectClubLeaderboard(anyLong(), any());
    }

    @Test
    void anonymousLeaderboardDropsMemberIds() {
        IClubService clubs = mock(IClubService.class);
        IClubMemberService members = mock(IClubMemberService.class);
        when(clubs.selectClubById(5L)).thenReturn(club(5L, 1));
        stubJoinPolicy(clubs, 5L, 0);
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("memberId", AUTHOR);
        row.put("nickname", "阿城");
        row.put("score", 12);
        when(members.selectClubLeaderboard(5L, null)).thenReturn(new ArrayList<>(Collections.singletonList(row)));
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("id", 5L);

        AjaxResult result = clubController(clubs, members).leaderboard(body);

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> rows = (List<java.util.Map<String, Object>>) result.get("data");
        assertEquals("阿城", rows.get(0).get("nickname"));
        assertEquals(12, rows.get(0).get("score"));
        assertFalse(rows.get(0).containsKey("memberId"));
    }

    @Test
    void guestPostProjectionDropsIdsAndViewerFlags() {
        com.chengyinhub.business.domain.ClubPost post = new com.chengyinhub.business.domain.ClubPost();
        post.setId(11L);
        post.setContent("周末一起走");
        post.setAuthorMemberId(AUTHOR);
        post.setIsLiked(false);
        post.setViewerReposted(false);
        post.setIsFollowTheUser(0);
        com.chengyinhub.business.domain.vo.ClubPostRepostItemVO repost = new com.chengyinhub.business.domain.vo.ClubPostRepostItemVO();
        repost.setReposterMemberId(AUTHOR);
        ApiClubController.guestPostProjection(Collections.singletonList(post), Collections.singletonList(repost));
        assertEquals("周末一起走", post.getContent());
        assertNull(post.getAuthorMemberId());
        assertNull(post.getIsLiked());
        assertNull(post.getViewerReposted());
        assertNull(post.getIsFollowTheUser());
        assertNull(repost.getReposterMemberId());
    }

    @Test
    void anonymousPostsAndTopicsOfClosedOrPrivateClubAreNotServed() {
        IClubService clubs = mock(IClubService.class);
        Club closed = club(7L, 1);
        closed.setStatus(0L);
        when(clubs.selectClubById(7L)).thenReturn(closed);
        when(clubs.selectClubById(6L)).thenReturn(club(6L, 0));
        ApiClubController controller = clubController(clubs, mock(IClubMemberService.class));
        for (long id : new long[]{6L, 7L}) {
            com.chengyinhub.business.domain.ClubPost posts = new com.chengyinhub.business.domain.ClubPost();
            posts.setClubId(id);
            assertEquals("这个俱乐部只对成员开放", controller.postList(posts).get(AjaxResult.MSG_TAG));
            assertEquals("这个俱乐部只对成员开放", controller.topics(club(id, null)).get(AjaxResult.MSG_TAG));
        }
    }

    @Test
    void guestTopicProjectionDropsAuthorIdAndViewerFlags() {
        CmsTopic topic = new CmsTopic();
        topic.setName("公开主题");
        topic.setMemberId(AUTHOR);
        topic.setIsLike(1);
        ApiClubController.guestTopicProjection(Collections.singletonList(topic));
        assertEquals("公开主题", topic.getName());
        assertNull(topic.getMemberId());
        assertNull(topic.getIsLike());
    }

    @Test
    void clubTopicListNeverCarriesTheAuthorsLoginName() {
        CmsTopic topic = new CmsTopic();
        topic.setName("公开主题");
        topic.setCreateBy("13900000003");
        topic.setUpdateBy("13900000003");
        ApiClubController.publicTopicAudit(Collections.singletonList(topic));
        assertEquals("公开主题", topic.getName());
        assertNull(topic.getCreateBy());
        assertNull(topic.getUpdateBy());
    }

    // ---------- 广场动态详情 ----------

    @Test
    void anonymousSquareDetailReturnsReviewedPostWithoutLocationOrPersonalState() {
        IViewCreativeSquareService views = mock(IViewCreativeSquareService.class);
        ViewCreativeSquare post = new ViewCreativeSquare();
        post.setId(31L);
        post.setMemberId(AUTHOR);
        post.setStatus(1);
        post.setDelFlag(0);
        post.setLatitude("31.2304");
        post.setAddress("作者的私密地址");
        when(views.selectViewCreativeSquareById(31L)).thenReturn(post);
        ApiCreativeSquareController controller = squareController(views);

        AjaxResult result = controller.viewCreativeSquareInfo("31");

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        ViewCreativeSquare row = (ViewCreativeSquare) result.get("data");
        assertNull(row.getLatitude(), "游客不是作者,定位必须剥掉");
        assertNull(row.getAddress());
        verify(views, never()).selectCreativeSquareLikesByMemberAndDataIds(anyLong(), any());
    }

    @Test
    void anonymousSquareDetailOfUnreviewedPostIsNotServed() {
        IViewCreativeSquareService views = mock(IViewCreativeSquareService.class);
        ViewCreativeSquare post = new ViewCreativeSquare();
        post.setId(32L);
        post.setMemberId(AUTHOR);
        post.setStatus(0);
        post.setDelFlag(0);
        when(views.selectViewCreativeSquareById(32L)).thenReturn(post);

        AjaxResult result = squareController(views).viewCreativeSquareInfo("32");

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void squareWriteSiblingsStillRequireLogin() {
        ApiCreativeSquareController controller = squareController(mock(IViewCreativeSquareService.class));
        assertThrows(ServiceException.class, () -> controller.creativeSquareLike("31", "1"));
    }

    private ApiCreativeSquareController squareController(IViewCreativeSquareService views) {
        ApiCreativeSquareController controller = guest(spy(new ApiCreativeSquareController()));
        ReflectionTestUtils.setField(controller, "viewCreativeSquareService", views);
        ReflectionTestUtils.setField(controller, "creativeSquareCommunityPolicyService",
                new CreativeSquareCommunityPolicyServiceImpl(mock(IImService.class)));
        return controller;
    }

    // ---------- 放行面:只有这四个方法带 @Anonymous,且路径没有被别的方法(写)共用 ----------

    @Test
    void onlyTheFourPublicReadHandlersAreAnonymousInTheseControllers() {
        Set<String> anonymous = new TreeSet<>();
        Set<String> anonymousPaths = new TreeSet<>();
        List<String> allHandlers = new ArrayList<>();
        for (Class<?> type : Arrays.asList(ApiTopicController.class, ApiClubController.class,
                ApiCreativeSquareController.class)) {
            assertNull(type.getAnnotation(Anonymous.class), type.getSimpleName() + " 不得整类匿名");
            String prefix = type.getAnnotation(RequestMapping.class).value()[0];
            for (Method method : type.getDeclaredMethods()) {
                for (String path : paths(method)) {
                    allHandlers.add(prefix + path);
                    if (method.isAnnotationPresent(Anonymous.class)) {
                        anonymous.add(type.getSimpleName() + "#" + method.getName());
                        anonymousPaths.add(prefix + path);
                    }
                }
            }
        }
        // 第十二轮 k2(业主 2026-11-05「游客可看俱乐部页的全部内容」):主题 / 帖文 / 成员 / 贡献榜四条读加入。
        assertEquals(new TreeSet<>(Arrays.asList("ApiClubController#detail", "ApiClubController#list",
                "ApiClubController#topics", "ApiClubController#postList", "ApiClubController#members",
                "ApiClubController#leaderboard",
                "ApiCreativeSquareController#retiredPostReads", "ApiTopicController#topicInfo")), anonymous);
        assertEquals(new TreeSet<>(Arrays.asList("/api/club/detail", "/api/club/list",
                "/api/club/topics", "/api/club/post/list", "/api/club/members", "/api/club/leaderboard",
                "/api/creativesquare/list", "/api/creativesquare/info", "/api/topic/info-to-user")), anonymousPaths);
        // @Anonymous 的放行不分 HTTP 方法:同一路径上若还挂着别的(写)方法,就会被顺带放行。
        for (String path : anonymousPaths) {
            assertEquals(1, Collections.frequency(allHandlers, path), path + " 只能对应一个处理方法");
        }
    }

    private static List<String> paths(Method method) {
        List<String> out = new ArrayList<>();
        PostMapping post = method.getAnnotation(PostMapping.class);
        GetMapping get = method.getAnnotation(GetMapping.class);
        PutMapping put = method.getAnnotation(PutMapping.class);
        DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
        PatchMapping patch = method.getAnnotation(PatchMapping.class);
        RequestMapping any = method.getAnnotation(RequestMapping.class);
        for (String[] values : Arrays.asList(post == null ? null : post.value(), get == null ? null : get.value(),
                put == null ? null : put.value(), delete == null ? null : delete.value(),
                patch == null ? null : patch.value(), any == null ? null : any.value())) {
            if (values != null) out.addAll(Arrays.asList(values));
        }
        return out;
    }

    private static void assertNoPublicGetter(Class<?> type, String getter) {
        assertTrue(Arrays.stream(type.getMethods()).noneMatch(m -> m.getName().equals(getter)),
                type.getSimpleName() + " 不得暴露 " + getter);
    }
}
