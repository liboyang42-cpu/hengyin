package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ViewCreativeSquare;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.RoamHangoutMapper;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IPuzzlePlayService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.IViewCreativeSquareService;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.impl.CreativeSquareCommunityPolicyServiceImpl;
import com.chengyinhub.business.service.impl.RoamHangoutServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Doc2 O-08:私密内容里的位置(坐标/地址)经各条读路径「本人/有权者看得到、旁人看不到」。
 * 每条路径各一组正反例;SQL 判据(clubMemberOnlyGate)本身由 O08MemberOnlyTopicLocationMyBatisTest 在真库钉住。
 */
class O08PrivateLocationReadPathTest {
    private static final Long VIEWER = 71L;
    private static final Long OWNER = 9L;

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------- /api/play/nodes:未报名旁人 × 私密/草稿主题 ----------

    @Test
    void playNodesHideCoordinatesOfNonPublicTopicFromUnregisteredStranger() {
        PlayFixture f = new PlayFixture(false);
        when(f.topicService.selectCmsTopicDetailById(73L, VIEWER)).thenReturn(null);

        AjaxResult response = f.controller.nodes(72L, null);

        assertEquals(500, response.get("code"));
        assertEquals("活动或主题不存在", response.get("msg"));
        assertFalse(String.valueOf(response).contains("121.4737"));
        verify(f.nodeService, never()).selectCmsTopicNodeList(any());
    }

    @Test
    void playNodesStillServePublicTopicToUnregisteredViewer() {
        PlayFixture f = new PlayFixture(false);
        when(f.topicService.selectCmsTopicDetailById(73L, VIEWER)).thenReturn(f.topic);

        assertEquals("121.4737", firstNode(f.controller.nodes(72L, null)).get("longitude"));
    }

    @Test
    void registeredPlayerSeesNodeCoordinatesWithoutPublicVisibilityProbe() {
        PlayFixture f = new PlayFixture(true);

        Map<String, Object> node = firstNode(f.controller.nodes(72L, null));

        assertEquals("121.4737", node.get("longitude"));
        assertEquals("31.2304", node.get("latitude"));
        verify(f.topicService, never()).selectCmsTopicDetailById(anyLong(), any());
    }

    // ---------- /api/activity/info:仅成员主题下、无 club_id 的场次 ----------

    @Test
    void activityDetailUnderMemberOnlyTopicIsNotFoundForNonMember() {
        ActivityFixture f = new ActivityFixture(null);
        when(f.topicMapper.selectClubMemberOnlyHiddenTopicIds(Collections.singletonList(73L), VIEWER))
                .thenReturn(Collections.singletonList(73L));

        assertNull(f.service.selectCmsActivityDetailById(72L, VIEWER));
    }

    @Test
    void activityDetailUnderMemberOnlyTopicStaysReadableForMemberAndAuthor() {
        ActivityFixture f = new ActivityFixture(null);
        when(f.topicMapper.selectClubMemberOnlyHiddenTopicIds(Collections.singletonList(73L), VIEWER))
                .thenReturn(Collections.emptyList());
        assertSame(f.activity, f.service.selectCmsActivityDetailById(72L, VIEWER));

        when(f.activityMapper.selectCmsActivityDetailById(72L, OWNER)).thenReturn(f.activity);
        assertSame(f.activity, f.service.selectCmsActivityDetailById(72L, OWNER));
        verify(f.topicMapper, never()).selectClubMemberOnlyHiddenTopicIds(any(), eq(OWNER));
    }

    @Test
    void clubSessionKeepsExistingControllerJoinGateInsteadOfServiceNotFound() {
        ActivityFixture f = new ActivityFixture(5L);

        assertSame(f.activity, f.service.selectCmsActivityDetailById(72L, VIEWER));
        verify(f.topicMapper, never()).selectClubMemberOnlyHiddenTopicIds(any(), any());
    }

    // ---------- /api/roam/hangout/nearby、/api/roam/entry/nearby:地图场次图层 ----------

    @Test
    void roamMapDropsSessionsOfMemberOnlyTopicsForNonMember() {
        RoamHangoutMapper hangouts = mock(RoamHangoutMapper.class);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        RoamHangoutServiceImpl service = new RoamHangoutServiceImpl();
        ReflectionTestUtils.setField(service, "roamHangoutMapper", hangouts);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        when(hangouts.selectNearbyActivities(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyInt(), eq(VIEWER)))
                .thenReturn(new ArrayList<>(Arrays.asList(row(1L, 73L), row(2L, 74L), row(3L, null))));
        when(topics.selectClubMemberOnlyHiddenTopicIds(Arrays.asList(73L, 74L), VIEWER))
                .thenReturn(Collections.singletonList(73L));

        Map<String, Object> out = service.nearby(VIEWER, 31.23, 121.47, 3000);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        List<Object> ids = new ArrayList<>();
        for (Map<String, Object> item : items) ids.add(item.get("id"));
        assertEquals(Arrays.asList(2L, 3L), ids);
        // 主题图层的过滤在 SQL(selectNearbyTopics 内 clubMemberOnlyGate),这里确认 viewer 传到了 SQL。
        verify(topics).selectNearbyTopics(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyInt(), anyInt(), eq(VIEWER));
    }

    // ---------- /api/club/home:关了公开可见的俱乐部 ----------

    @Test
    void clubHomeDiscoveryHidesPrivateClubRowWithAddressFromNonMember() {
        ClubFixture f = new ClubFixture();
        Club hidden = club(74L, 0, "徐汇区某弄堂 12 号");
        Club open = club(75L, 1, "公开据点");
        when(f.clubService.selectClubList(any(Club.class))).thenReturn(Arrays.asList(hidden, open));
        when(f.memberService.selectMembershipRecord(74L, VIEWER)).thenReturn(null);

        Map<?, ?> home = (Map<?, ?>) f.controller.home().get("data");

        List<?> nearby = (List<?>) home.get("nearby");
        assertEquals(1, nearby.size());
        assertEquals(75L, ((Club) nearby.get(0)).getId());
        assertFalse(String.valueOf(nearby).contains("徐汇区某弄堂"));
    }

    @Test
    void clubHomeDiscoveryKeepsPrivateClubForActiveMember() {
        ClubFixture f = new ClubFixture();
        Club hidden = club(74L, 0, "徐汇区某弄堂 12 号");
        when(f.clubService.selectClubList(any(Club.class))).thenReturn(Collections.singletonList(hidden));
        ClubMember membership = new ClubMember();
        membership.setStatus(1);
        when(f.memberService.selectMembershipRecord(74L, VIEWER)).thenReturn(membership);

        List<?> nearby = (List<?>) ((Map<?, ?>) f.controller.home().get("data")).get("nearby");

        assertEquals(1, nearby.size());
    }

    // ---------- /api/creativesquare/list、/info:旧广场读面 ----------

    @Test
    void legacySquareListShowsExactLocationOnlyOnViewersOwnRows() {
        IViewCreativeSquareService views = mock(IViewCreativeSquareService.class);
        ViewCreativeSquare mine = square(31L, VIEWER);
        ViewCreativeSquare theirs = square(32L, OWNER);
        when(views.selectViewCreativeSquareList(any())).thenReturn(new ArrayList<>(Arrays.asList(mine, theirs)));
        ApiCreativeSquareController controller = squareController(VIEWER, views);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        AjaxResult result = controller.viewCreativeSquareList("0", null, null);

        assertEquals(200, result.get("code"));
        List<?> rows = ((TableDataInfo) result.get("data")).getRows();
        ViewCreativeSquare ownRow = (ViewCreativeSquare) rows.get(0);
        ViewCreativeSquare foreignRow = (ViewCreativeSquare) rows.get(1);
        assertEquals("121.4737", ownRow.getLongitude());
        assertEquals("撤回过的家门口", ownRow.getAddress());
        assertNull(foreignRow.getLongitude());
        assertNull(foreignRow.getLatitude());
        assertNull(foreignRow.getAddress());
    }

    @Test
    void legacySquareListStripsLocationForAnonymousViewer() {
        IViewCreativeSquareService views = mock(IViewCreativeSquareService.class);
        when(views.selectViewCreativeSquareList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(square(32L, OWNER))));
        ApiCreativeSquareController controller = squareController(null, views);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        AjaxResult result = controller.viewCreativeSquareList("0", null, null);

        ViewCreativeSquare row = (ViewCreativeSquare) ((TableDataInfo) result.get("data")).getRows().get(0);
        assertNull(row.getLongitude());
        assertNull(row.getAddress());
    }

    @Test
    void legacySquareDetailStripsLocationForOthersButNotForAuthor() {
        IViewCreativeSquareService views = mock(IViewCreativeSquareService.class);
        when(views.selectViewCreativeSquareById(32L)).thenReturn(square(32L, OWNER));
        ViewCreativeSquare asStranger = (ViewCreativeSquare) squareController(VIEWER, views)
                .viewCreativeSquareInfo("32").get("data");
        assertNotNull(asStranger);
        assertNull(asStranger.getLatitude());
        assertNull(asStranger.getAddress());

        when(views.selectViewCreativeSquareById(32L)).thenReturn(square(32L, OWNER));
        ViewCreativeSquare asAuthor = (ViewCreativeSquare) squareController(OWNER, views)
                .viewCreativeSquareInfo("32").get("data");
        assertEquals("31.2304", asAuthor.getLatitude());
        assertTrue(asAuthor.getAddress().length() > 0);
    }

    // ---------- fixtures ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstNode(AjaxResult response) {
        assertEquals(200, response.get("code"));
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        return ((List<Map<String, Object>>) data.get("nodes")).get(0);
    }

    private static Map<String, Object> row(Long id, Long topicId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("topicId", topicId);
        row.put("latitude", 31.23);
        row.put("longitude", 121.47);
        return row;
    }

    private static Club club(Long id, Integer publicVisible, String address) {
        Club club = new Club();
        club.setId(id);
        club.setMemberId(OWNER);
        club.setStatus(1L);
        club.setPublicVisible(publicVisible);
        club.setAddress(address);
        return club;
    }

    private static ViewCreativeSquare square(Long id, Long author) {
        ViewCreativeSquare row = new ViewCreativeSquare();
        row.setId(id);
        row.setMemberId(author);
        row.setStatus(1);
        row.setDelFlag(0);
        row.setAddress("撤回过的家门口");
        row.setLongitude("121.4737");
        row.setLatitude("31.2304");
        return row;
    }

    private static ApiCreativeSquareController squareController(Long viewer, IViewCreativeSquareService views) {
        ApiCreativeSquareController controller = spy(new ApiCreativeSquareController());
        doReturn(viewer).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "viewCreativeSquareService", views);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "creativeSquareCommunityPolicyService",
                new CreativeSquareCommunityPolicyServiceImpl(mock(IImService.class)));
        return controller;
    }

    private static final class PlayFixture {
        final ApiPlayProgressController controller = spy(new ApiPlayProgressController());
        final ICmsTopicService topicService = mock(ICmsTopicService.class);
        final ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        final CmsTopic topic = new CmsTopic();

        PlayFixture(boolean registered) {
            ICmsActivityService activityService = mock(ICmsActivityService.class);
            ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
            doReturn(VIEWER).when(controller).getAppUserId();
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", mock(ITopicRouteRuntimeService.class));
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService",
                    mock(com.chengyinhub.business.service.IPlayerNodeProgressService.class));
            var fixtureHintSettings = mock(com.chengyinhub.business.service.ICmsPointsSettingService.class);
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService",
                    mock(com.chengyinhub.business.service.ICmsMemberTemplateService.class));
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "omsTicketService",
                    mock(com.chengyinhub.business.service.IOmsTicketService.class));
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService", mock(IPuzzlePlayService.class));

            CmsActivity activity = new CmsActivity();
            activity.setId(72L);
            activity.setTopicId(73L);
            when(activityService.selectCmsActivityById(72L)).thenReturn(activity);
            when(registrationService.selectCmsRegistrationList(any())).thenReturn(
                    registered ? Collections.singletonList(new CmsRegistration()) : Collections.emptyList());

            topic.setId(73L);
            topic.setProductType(1);
            topic.setRouteMode(TopicRouteState.LINEAR);
            when(topicService.selectCmsTopicById(73L)).thenReturn(topic);

            CmsTopicNode node = new CmsTopicNode();
            node.setId(101L);
            node.setTopicId(73L);
            node.setName("私密集合点");
            node.setSortId(1L);
            node.setStatus(1L);
            node.setLongitude("121.4737");
            node.setLatitude("31.2304");
            node.setAddress("某小区 3 号楼");
            when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
            com.chengyinhub.web.controller.api.NodeHintUnlockTestFixtures.pricing(controller, fixtureHintSettings);
        }
    }

    private static final class ActivityFixture {
        final CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        final CmsActivityServiceImpl service = new CmsActivityServiceImpl();
        final CmsActivity activity = new CmsActivity();

        ActivityFixture(Long clubId) {
            ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
            ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
            activity.setId(72L);
            activity.setTopicId(73L);
            activity.setClubId(clubId);
            activity.setMemberId(OWNER);
            activity.setLongitude("121.4737");
            activity.setLatitude("31.2304");
            when(activityMapper.selectCmsActivityDetailById(72L, VIEWER)).thenReturn(activity);
        }
    }

    private static final class ClubFixture {
        final ApiClubController controller = spy(new ApiClubController());
        final IClubService clubService = mock(IClubService.class);
        final IClubMemberService memberService = mock(IClubMemberService.class);

        ClubFixture() {
            ICapabilityService capabilityService = mock(ICapabilityService.class);
            when(capabilityService.hasCapability(anyLong(), anyString())).thenReturn(false);
            doReturn(VIEWER).when(controller).getAppUserId();
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "clubService", clubService);
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "clubMemberService", memberService);
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "capabilityService", capabilityService);
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "clubLeaderService",
                    mock(com.chengyinhub.business.service.IClubLeaderService.class));
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", mock(ICmsTopicService.class));
            com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "umsMemberService",
                    mock(com.chengyinhub.business.service.IUmsMemberService.class));
            when(clubService.selectClubsByMemberId(VIEWER)).thenReturn(Collections.emptyList());
            when(memberService.selectClubIdsByMember(VIEWER)).thenReturn(Collections.emptyList());
        }
    }
}
