package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ViewCreativeSquare;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IUmsCreativeSquareLikeService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.IUmsMemberFollowService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IViewCreativeSquareService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.impl.CreativeSquareCommunityPolicyServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.github.pagehelper.PageHelper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiCreativeSquareReadModelBudgetTest {

    private static class StubController extends ApiCreativeSquareController {
        private final Long memberId;
        private final boolean anonymous;

        StubController(Long memberId, boolean anonymous) {
            this.memberId = memberId;
            this.anonymous = anonymous;
        }

        @Override
        public Long getAppUserId() {
            if (anonymous) {
                throw new RuntimeException("anonymous");
            }
            return memberId;
        }
    }

    private static class Fixture {
        final IViewCreativeSquareService viewService = mock(IViewCreativeSquareService.class);
        final IUmsCreativeSquareLikeService likeService = mock(IUmsCreativeSquareLikeService.class);
        final IUmsMemberFollowService followService = mock(IUmsMemberFollowService.class);
        final IUmsCreativeSquareService squareService = mock(IUmsCreativeSquareService.class);
        final ICmsActivityService activityService = mock(ICmsActivityService.class);
        final ICmsTopicService topicService = mock(ICmsTopicService.class);
        final ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        final IPlayerNodeProgressService progressService = mock(IPlayerNodeProgressService.class);
        final IImService imService = mock(IImService.class);
        final com.chengyinhub.business.service.ITopicRouteRuntimeService routeRuntimeService =
                mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);

        StubController controller(Long memberId, boolean anonymous) {
            StubController c = new StubController(memberId, anonymous);
            ReflectionTestUtils.setField(c, "viewCreativeSquareService", viewService);
            ReflectionTestUtils.setField(c, "umsCreativeSquareLikeService", likeService);
            ReflectionTestUtils.setField(c, "umsMemberFollowService", followService);
            ReflectionTestUtils.setField(c, "umsCreativeSquareService", squareService);
            ReflectionTestUtils.setField(c, "cmsActivityService", activityService);
            ReflectionTestUtils.setField(c, "cmsTopicService", topicService);
            ReflectionTestUtils.setField(c, "cmsTopicNodeService", nodeService);
            ReflectionTestUtils.setField(c, "playerNodeProgressService", progressService);
            ReflectionTestUtils.setField(c, "creativeSquareCommunityPolicyService",
                    new CreativeSquareCommunityPolicyServiceImpl(imService));
            ReflectionTestUtils.setField(c, "topicRouteRuntimeService", routeRuntimeService);
            ReflectionTestUtils.setField(c, "cmsRegistrationService", mock(com.chengyinhub.business.service.ICmsRegistrationService.class));
            ReflectionTestUtils.setField(c, "perkLedgerService", mock(IPerkLedgerService.class));
            ReflectionTestUtils.setField(c, "auditTaskService", mock(IAuditTaskService.class));
            ReflectionTestUtils.setField(c, "wxContentSecurityService", mock(IWxContentSecurityService.class));
            ReflectionTestUtils.setField(c, "umsMemberService", mock(IUmsMemberService.class));
            return c;
        }
    }

    private static void bindPage(int pageNum, int pageSize) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("pageNum", String.valueOf(pageNum));
        request.setParameter("pageSize", String.valueOf(pageSize));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static ViewCreativeSquare square(long id, long authorId) {
        ViewCreativeSquare s = new ViewCreativeSquare();
        s.setId(id);
        s.setMemberId(authorId);
        s.setStatus(1);
        s.setDelFlag(0);
        return s;
    }

    private static ViewCreativeSquare sportSquare(long id, long authorId, long dataType, long dataId) {
        ViewCreativeSquare s = square(id, authorId);
        s.setDataType(dataType);
        s.setDataId(dataId);
        return s;
    }

    private static Map<String, Object> activitySummary(long activityId, long topicId, String name, int productType) {
        Map<String, Object> m = new HashMap<>();
        m.put("activityId", activityId);
        m.put("topicId", topicId);
        m.put("sportName", name);
        m.put("sportCover", "cover.jpg");
        m.put("productType", productType);
        return m;
    }

    private static Map<String, Object> topicSummary(long topicId, String name, boolean template) {
        Map<String, Object> m = new HashMap<>();
        m.put("topicId", topicId);
        m.put("sportName", name);
        m.put("sportCover", "cover.jpg");
        m.put("publishedRouteGraphJson", null);
        m.put("productType", 1);
        m.put("isTemplate", template ? 1 : 0);
        return m;
    }

    private static Map<String, Object> exploreRow(long registrationId, long memberId, long activityId) {
        // topicId intentionally omitted (NULL) to match production where ③ registrations don't set it
        Map<String, Object> m = new HashMap<>();
        m.put("registrationId", registrationId);
        m.put("memberId", memberId);
        m.put("activityId", activityId);
        return m;
    }

    private static Map<String, Object> countRow(String key, Object id, String countKey, int count) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, id);
        m.put(countKey, count);
        return m;
    }

    @Test
    void listPersonalStateQueryCountDoesNotGrowWithRowsOrDuplicateIds() {
        bindPage(1, 20);
        Fixture f = new Fixture();
        List<ViewCreativeSquare> rows = Arrays.asList(square(1L, 11L), square(2L, 22L), square(2L, 22L), square(3L, 22L));
        when(f.viewService.selectViewCreativeSquareList(any())).thenReturn(rows);
        when(f.viewService.selectCreativeSquareLikesByMemberAndDataIds(eq(9L), anyList())).thenReturn(Collections.emptyList());
        when(f.viewService.selectMemberFollowsByMemberAndFollowIds(eq(9L), anyList())).thenReturn(Collections.emptyList());

        AjaxResult result = f.controller(9L, false).viewCreativeSquareList("0", null, null);

        assertEquals(200, result.get("code"));
        // Negative: old per-item paths never called
        verify(f.likeService, never()).selectUmsCreativeSquareLikeList(any());
        verify(f.followService, never()).selectUmsMemberFollowList(any());
        // Positive: batch seams called exactly once each
        verify(f.viewService, times(1)).selectCreativeSquareLikesByMemberAndDataIds(eq(9L), anyList());
        verify(f.viewService, times(1)).selectMemberFollowsByMemberAndFollowIds(eq(9L), anyList());
        // Field assertions
        for (ViewCreativeSquare row : rows) {
            assertEquals(0, row.getIsLiked());
            assertEquals(0, row.getIsFollowTheUser());
        }
    }

    @Test
    void sportFeedQueryCountDoesNotGrowWithRows() {
        bindPage(1, 20);
        Fixture f = new Fixture();
        List<ViewCreativeSquare> rows = Arrays.asList(
                sportSquare(10L, 11L, 1L, 101L),
                sportSquare(11L, 11L, 1L, 102L),
                sportSquare(12L, 22L, 2L, 201L));
        when(f.viewService.selectViewCreativeSquareList(any())).thenReturn(rows);
        when(f.viewService.selectCreativeSquareBatchByIds(anyList())).thenReturn(Collections.emptyList());
        when(f.viewService.selectSportActivitySummaries(anyList())).thenReturn(Arrays.asList(
                activitySummary(101L, 301L, "A", 1), activitySummary(102L, 302L, "B", 1)));
        when(f.viewService.selectSportTopicSummaries(anyList()))
                .thenReturn(Collections.singletonList(topicSummary(201L, "C", false)));

        AjaxResult result = f.controller(9L, false).viewCreativeSquareList("0", null, null);

        assertEquals(200, result.get("code"));
        // Negative: old per-item paths never called
        verify(f.squareService, never()).selectUmsCreativeSquareById(anyLong());
        verify(f.activityService, never()).selectCmsActivityById(anyLong());
        verify(f.topicService, never()).selectCmsTopicById(anyLong());
        verify(f.nodeService, never()).selectCmsTopicNodeList(any());
        verify(f.progressService, never()).selectPlayerNodeProgressList(any());
        // Positive: batch seams called exactly once each
        verify(f.viewService, times(1)).selectSportActivitySummaries(anyList());
        verify(f.viewService, times(1)).selectSportTopicSummaries(anyList());
        verify(f.viewService, times(1)).selectNodeCountsByTopicIds(anyList());
        verify(f.viewService, times(1)).selectProgressCountsByMemberActivities(anyList());
        verify(f.viewService, times(1)).selectProgressCountsByMemberTopics(anyList());
        verify(f.routeRuntimeService, times(1)).sessionStatuses(anyList());
    }

    @Test
    void topicTemplateFlagIsProjectedIntoFeedReadModel() {
        bindPage(1, 20);
        Fixture f = new Fixture();
        ViewCreativeSquare templateShare = sportSquare(12L, 22L, 2L, 201L);
        when(f.viewService.selectViewCreativeSquareList(any())).thenReturn(Collections.singletonList(templateShare));
        when(f.viewService.selectCreativeSquareBatchByIds(anyList())).thenReturn(Collections.emptyList());
        when(f.viewService.selectSportActivitySummaries(anyList())).thenReturn(Collections.emptyList());
        when(f.viewService.selectSportTopicSummaries(anyList()))
                .thenReturn(Collections.singletonList(topicSummary(201L, "夜航电台", true)));
        when(f.viewService.selectNodeCountsByTopicIds(anyList())).thenReturn(Collections.emptyList());
        when(f.viewService.selectProgressCountsByMemberActivities(anyList())).thenReturn(Collections.emptyList());
        when(f.viewService.selectProgressCountsByMemberTopics(anyList())).thenReturn(Collections.emptyList());

        AjaxResult result = f.controller(9L, false).viewCreativeSquareList("0", null, null);

        assertEquals(200, result.get("code"));
        assertEquals(true, templateShare.getIsTopicTemplate());
    }

    @Test
    void anonymousListFailsClosedToPassOnlyAndNeverReadsPersonalState() {
        bindPage(1, 50);
        Fixture f = new Fixture();
        List<ViewCreativeSquare> rows = Collections.singletonList(square(1L, 11L));
        when(f.viewService.selectViewCreativeSquareList(any())).thenReturn(rows);
        ArgumentCaptor<ViewCreativeSquare> queryCaptor = ArgumentCaptor.forClass(ViewCreativeSquare.class);

        AjaxResult result = f.controller(null, true).viewCreativeSquareList("0", null, null);

        assertEquals(200, result.get("code"));
        verify(f.viewService).selectViewCreativeSquareList(queryCaptor.capture());
        assertEquals(Integer.valueOf(1), queryCaptor.getValue().getStatus());
        assertNull(queryCaptor.getValue().getSelfPendingMemberId());
        // Anonymous: never queries personal state
        verify(f.viewService, never()).selectCreativeSquareLikesByMemberAndDataIds(any(), anyList());
        verify(f.viewService, never()).selectMemberFollowsByMemberAndFollowIds(any(), anyList());
        assertEquals(0, rows.get(0).getIsLiked());
        assertEquals(0, rows.get(0).getIsFollowTheUser());
    }

    @Test
    void exploreActivityWithNullRegistrationTopicIdComputesCompletedViaActivityTopicId() {
        bindPage(1, 20);
        Fixture f = new Fixture();

        // One ③ activity square (dataType=1, dataId=101), authorId=11, topicId=999
        ViewCreativeSquare s = sportSquare(10L, 11L, 1L, 101L);
        List<ViewCreativeSquare> rows = Collections.singletonList(s);

        when(f.viewService.selectViewCreativeSquareList(any())).thenReturn(rows);

        // Personal state batch: no likes/follows
        when(f.viewService.selectCreativeSquareLikesByMemberAndDataIds(eq(9L), anyList())).thenReturn(Collections.emptyList());
        when(f.viewService.selectMemberFollowsByMemberAndFollowIds(eq(9L), anyList())).thenReturn(Collections.emptyList());

        // Route preview: empty
        when(f.viewService.selectCreativeSquareBatchByIds(anyList())).thenReturn(Collections.emptyList());

        // Activity summary: productType=FREE_EXPLORE(2), topicId=999
        when(f.viewService.selectSportActivitySummaries(anyList()))
                .thenReturn(Collections.singletonList(activitySummary(101L, 999L, "Test Activity", 2)));

        // No topic squares
        when(f.viewService.selectSportTopicSummaries(anyList())).thenReturn(Collections.emptyList());

        // Node total: 3 nodes
        when(f.viewService.selectNodeCountsByTopicIds(anyList()))
                .thenReturn(Collections.singletonList(countRow("topicId", 999L, "cnt", 3)));

        // Done by member activity: 0 for this pair (simulate no check-ins)
        when(f.viewService.selectProgressCountsByMemberActivities(anyList())).thenReturn(Collections.emptyList());
        when(f.viewService.selectProgressCountsByMemberTopics(anyList())).thenReturn(Collections.emptyList());

        // Explore pass: registrationId=888, topicId intentionally NULL (production reality)
        List<Map<String, Object>> exploreRows = Collections.singletonList(exploreRow(888L, 11L, 101L));
        when(f.viewService.selectExplorePassesByMemberActivities(anyList())).thenReturn(exploreRows);

        // Required chapters: 2 required for topicId=999
        when(f.viewService.selectRequiredChapterCountsByTopicIds(anyList()))
                .thenReturn(Collections.singletonList(countRow("topicId", 999L, "requiredCount", 2)));

        // Redeemed: 1 for registrationId=888, topicId=999 (not yet fully redeemed)
        Map<String, Object> redeemedRow = new HashMap<>();
        redeemedRow.put("registrationId", 888L);
        redeemedRow.put("topicId", 999L);
        redeemedRow.put("redeemedCount", 1);
        when(f.viewService.selectRedeemedRequiredChapterCountsByRegistrations(anyList()))
                .thenReturn(Collections.singletonList(redeemedRow));

        AjaxResult result = f.controller(9L, false).viewCreativeSquareList("0", null, null);

        assertEquals(200, result.get("code"));
        // Verify batch seams are called the right number of times
        verify(f.viewService, times(1)).selectSportActivitySummaries(anyList());
        verify(f.viewService, times(1)).selectExplorePassesByMemberActivities(anyList());
        verify(f.viewService, times(1)).selectRequiredChapterCountsByTopicIds(anyList());
        verify(f.viewService, times(1)).selectRedeemedRequiredChapterCountsByRegistrations(anyList());

        // Despite registration.topicId being NULL, completed should be computed
        // using activity summary's topicId (999): required=2, redeemed=1 => not completed
        assertEquals(3, s.getNodeTotal());
        assertEquals(0, s.getNodeDoneCount());
        assertEquals(false, s.getCompleted());
    }

    @Test
    void exploreActivityWithNullRegistrationTopicIdPassesWhenFullyRedeemed() {
        bindPage(1, 20);
        Fixture f = new Fixture();

        ViewCreativeSquare s = sportSquare(10L, 11L, 1L, 101L);
        List<ViewCreativeSquare> rows = Collections.singletonList(s);

        when(f.viewService.selectViewCreativeSquareList(any())).thenReturn(rows);
        when(f.viewService.selectCreativeSquareLikesByMemberAndDataIds(eq(9L), anyList())).thenReturn(Collections.emptyList());
        when(f.viewService.selectMemberFollowsByMemberAndFollowIds(eq(9L), anyList())).thenReturn(Collections.emptyList());
        when(f.viewService.selectCreativeSquareBatchByIds(anyList())).thenReturn(Collections.emptyList());
        when(f.viewService.selectSportActivitySummaries(anyList()))
                .thenReturn(Collections.singletonList(activitySummary(101L, 999L, "Test Activity", 2)));
        when(f.viewService.selectSportTopicSummaries(anyList())).thenReturn(Collections.emptyList());
        when(f.viewService.selectNodeCountsByTopicIds(anyList()))
                .thenReturn(Collections.singletonList(countRow("topicId", 999L, "cnt", 3)));
        when(f.viewService.selectProgressCountsByMemberActivities(anyList())).thenReturn(Collections.emptyList());
        when(f.viewService.selectProgressCountsByMemberTopics(anyList())).thenReturn(Collections.emptyList());

        List<Map<String, Object>> exploreRows = Collections.singletonList(exploreRow(888L, 11L, 101L));
        when(f.viewService.selectExplorePassesByMemberActivities(anyList())).thenReturn(exploreRows);

        when(f.viewService.selectRequiredChapterCountsByTopicIds(anyList()))
                .thenReturn(Collections.singletonList(countRow("topicId", 999L, "requiredCount", 2)));

        // Redeemed: 2 for registrationId=888, topicId=999 => fully redeemed => completed
        Map<String, Object> redeemedRow = new HashMap<>();
        redeemedRow.put("registrationId", 888L);
        redeemedRow.put("topicId", 999L);
        redeemedRow.put("redeemedCount", 2);
        when(f.viewService.selectRedeemedRequiredChapterCountsByRegistrations(anyList()))
                .thenReturn(Collections.singletonList(redeemedRow));

        f.controller(9L, false).viewCreativeSquareList("0", null, null);

        assertEquals(true, s.getCompleted());
    }

    @Test
    void infoUsesSameBatchPath() {
        Fixture f = new Fixture();
        ViewCreativeSquare existing = square(1L, 11L);
        when(f.viewService.selectViewCreativeSquareById(1L)).thenReturn(existing);
        when(f.viewService.selectCreativeSquareLikesByMemberAndDataIds(eq(9L), anyList())).thenReturn(Collections.emptyList());
        when(f.viewService.selectMemberFollowsByMemberAndFollowIds(eq(9L), anyList())).thenReturn(Collections.emptyList());

        AjaxResult result = f.controller(9L, false).viewCreativeSquareInfo("1");

        assertEquals(200, result.get("code"));
        verify(f.viewService, times(1)).selectCreativeSquareLikesByMemberAndDataIds(eq(9L), anyList());
        verify(f.viewService, times(1)).selectMemberFollowsByMemberAndFollowIds(eq(9L), anyList());
        assertEquals(0, existing.getIsLiked());
        assertEquals(0, existing.getIsFollowTheUser());
    }

    @Test
    void pageSizeCappedAt100() {
        assertEquals(10, ApiCreativeSquareController.clampPageSize(0));
        assertEquals(10, ApiCreativeSquareController.clampPageSize(-5));
        assertEquals(20, ApiCreativeSquareController.clampPageSize(20));
        assertEquals(100, ApiCreativeSquareController.clampPageSize(100));
        assertEquals(100, ApiCreativeSquareController.clampPageSize(999));
        assertEquals(100, ApiCreativeSquareController.clampPageSize(Integer.MAX_VALUE));
    }

    @Test
    void pageNum2PageSize1000ComputesCorrectOffset() {
        bindPage(2, 1000);
        Fixture f = new Fixture();
        List<ViewCreativeSquare> rows = Arrays.asList(square(1L, 11L), square(2L, 22L));
        when(f.viewService.selectViewCreativeSquareList(any())).thenAnswer(invocation -> {
            com.github.pagehelper.Page<?> page = com.github.pagehelper.PageHelper.getLocalPage();
            assertEquals(2, page.getPageNum());
            assertEquals(100, page.getPageSize());
            assertEquals(100, page.getStartRow());
            return rows;
        });
        when(f.viewService.selectCreativeSquareLikesByMemberAndDataIds(eq(9L), anyList())).thenReturn(Collections.emptyList());
        when(f.viewService.selectMemberFollowsByMemberAndFollowIds(eq(9L), anyList())).thenReturn(Collections.emptyList());

        AjaxResult result = f.controller(9L, false).viewCreativeSquareList("0", null, null);
        assertEquals(200, result.get("code"));
    }
}
