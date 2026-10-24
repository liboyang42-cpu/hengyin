package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.CompletedRouteFactReader;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** G 来源接线与 FREE_EXPLORE 边界；只读行为单测，不代表 MySQL/权益链路验证。 */
class ApiPlayCompletedRouteSourcesTest {
    private ApiPlayProgressController controller;
    private CompletedRouteFactReader facts;
    private IPlayerNodeProgressService progress;
    private ICmsRegistrationService registrations;
    private ICmsActivityService activities;
    private ICmsTopicService topics;
    private ICmsTopicNodeService nodes;
    private ITopicRouteRuntimeService routes;
    private IPerkLedgerService perks;

    @BeforeEach void fixture() {
        controller = spy(new ApiPlayProgressController()); doReturn(7L).when(controller).getAppUserId();
        facts = mock(CompletedRouteFactReader.class); progress = mock(IPlayerNodeProgressService.class);
        registrations = mock(ICmsRegistrationService.class); activities = mock(ICmsActivityService.class);
        topics = mock(ICmsTopicService.class); nodes = mock(ICmsTopicNodeService.class);
        routes = mock(ITopicRouteRuntimeService.class); perks = mock(IPerkLedgerService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "completedRouteFactReader", facts);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progress);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrations);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activities);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topics);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodes);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", routes);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "perkLedgerService", perks);
        when(facts.findCurrentCompletedRoutes(7L)).thenReturn(List.of());
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(List.of());
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(List.of());
        when(routes.sessionStatuses(any())).thenReturn(Map.of());
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(List.of(node(401L)));
        topic(301L, null);
    }

    private CmsTopic topic(Long id, Integer product) {
        CmsTopic topic = new CmsTopic(); topic.setId(id); topic.setProductType(product); topic.setName("topic-" + id);
        when(topics.selectCmsTopicById(id)).thenReturn(topic); return topic;
    }
    private CmsTopicNode node(Long id) { CmsTopicNode node = new CmsTopicNode(); node.setId(id); node.setTopicId(301L); return node; }
    private CmsActivity activity(Long id, Long topic) {
        CmsActivity activity = new CmsActivity(); activity.setId(id); activity.setTopicId(topic); activity.setName("activity-" + id);
        when(activities.selectCmsActivityById(id)).thenReturn(activity); return activity;
    }
    private TopicRouteSession route(Long activity, Long topic) {
        TopicRouteSession route = new TopicRouteSession(); route.setId(91L); route.setMemberId(7L);
        route.setActivityId(activity); route.setTopicId(topic); route.setStatus(TopicRouteSession.STATUS_COMPLETED);
        route.setPlayInstanceId("original-91"); route.setConfigVersion(3L); return route;
    }
    private PlayerNodeProgress progress(Long activity, Long topic, int status) {
        PlayerNodeProgress row = new PlayerNodeProgress(); row.setMemberId(7L); row.setActivityId(activity);
        row.setTopicId(topic); row.setNodeId(401L); row.setStatus(status); return row;
    }
    private CmsRegistration paidExplore(Long activity) {
        CmsRegistration row = new CmsRegistration(); row.setId(71L); row.setMemberId(7L);
        row.setOwnerType(2L); row.setOwnerId(activity); row.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        row.setRegistrationStatus(2); row.setPaymentStatus(2); return row;
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> call() {
        AjaxResult response = controller.myCompleted(); assertEquals(200, response.get("code"));
        return (List<Map<String,Object>>) response.get("data");
    }

    @Test void completedPersonalRouteWithoutCompletedProgressIsIncluded() {
        when(facts.findCurrentCompletedRoutes(7L)).thenReturn(List.of(route(0L, 301L)));
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(List.of(progress(0L, 301L, 0)));
        List<Map<String,Object>> rows = call();
        assertEquals(1, rows.size()); assertEquals(2, rows.get(0).get("dataType"));
        assertNull(rows.get(0).get("activityId")); assertEquals(301L, rows.get(0).get("dataId"));
        assertEquals(0, rows.get(0).get("doneCount")); verify(facts).findCurrentCompletedRoutes(7L);
        verifyNoInteractions(perks);
    }

    @Test void routeOnlyActivityAndSelfPlayOfSameTopicRemainDifferentScopes() {
        activity(100L, 301L);
        when(facts.findCurrentCompletedRoutes(7L)).thenReturn(List.of(route(100L, 301L), route(0L, 301L)));
        List<Map<String,Object>> rows = call(); assertEquals(2, rows.size());
        assertEquals(Set.of(1, 2), new HashSet<>(List.of(rows.get(0).get("dataType"), rows.get(1).get("dataType"))));
        assertEquals(Set.of(100L, 301L), new HashSet<>(List.of(rows.get(0).get("dataId"), rows.get(1).get("dataId"))));
    }

    @Test void newerRunningOrEndedStatusOverridesEarlierCompletedCandidate() {
        when(facts.findCurrentCompletedRoutes(7L)).thenReturn(List.of(route(0L, 301L)));
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(List.of(progress(0L, 301L, 1)));
        for (int status : new int[]{TopicRouteSession.STATUS_RUNNING, TopicRouteSession.STATUS_ENDED}) {
            when(routes.sessionStatuses(any())).thenReturn(Map.of(ITopicRouteRuntimeService.sessionKey(7L, 301L, 0L), status));
            assertTrue(call().isEmpty());
        }
    }

    @Test void sourcesForOneScopeDeduplicateAndDoNotInventCompletedNodeRows() {
        activity(100L, 301L); CmsRegistration pass = paidExplore(100L);
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(List.of(pass));
        when(facts.findCurrentCompletedRoutes(7L)).thenReturn(List.of(route(100L, 301L), route(100L, 301L)));
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(List.of(progress(100L, 301L, 1), progress(100L, 301L, 1)));
        List<Map<String,Object>> first = call(); assertEquals(1, first.size()); assertEquals(1, first.get(0).get("doneCount"));
        assertEquals(first, call());
        verify(progress, times(2)).selectPlayerNodeProgressList(any()); verifyNoMoreInteractions(progress);
    }

    @Test void activityTopicMismatchCannotReuseAnotherTopicsRouteOrNodeFacts() {
        activity(100L, 302L); topic(302L, null);
        when(facts.findCurrentCompletedRoutes(7L)).thenReturn(List.of(route(100L, 301L)));
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(List.of(progress(100L, 301L, 1)));
        assertTrue(call().isEmpty());
    }

    @Test void unownedRouteProjectionAndProgressCannotSupplyCandidates() {
        TopicRouteSession other = route(0L, 301L); other.setMemberId(8L);
        PlayerNodeProgress otherProgress = progress(0L, 301L, 1); otherProgress.setMemberId(8L);
        when(facts.findCurrentCompletedRoutes(7L)).thenReturn(List.of(other));
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(List.of(otherProgress));
        assertTrue(call().isEmpty());
    }

    @Test void missingOrGovernanceBlockedTopicCannotBeRevivedByRouteFact() {
        when(facts.findCurrentCompletedRoutes(7L)).thenReturn(List.of(route(0L, 301L)));
        when(topics.selectCmsTopicById(301L)).thenReturn(null); assertTrue(call().isEmpty());
        topic(301L, null).setStatus(2); assertTrue(call().isEmpty());
    }

    @Test void freeExploreWithoutPaidTicketCannotBorrowClassicOrRouteCompletion() {
        topic(301L, ProductType.FREE_EXPLORE);
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(List.of(progress(0L, 301L, 1)));
        when(facts.findCurrentCompletedRoutes(7L)).thenReturn(List.of(route(0L, 301L)));
        assertTrue(call().isEmpty()); verifyNoInteractions(perks);
    }

    @Test void paidPureContentCompletionStillUsesExistingAuthoritativeLedgerPath() {
        topic(301L, ProductType.FREE_EXPLORE); activity(100L, 301L);
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(List.of(paidExplore(100L)));
        when(perks.isExploreCompleted(71L, 301L)).thenReturn(true);
        // Pure-content can have an auxiliary RUNNING route while its paid required chapter games are complete.
        when(routes.sessionStatuses(any())).thenReturn(Map.of(ITopicRouteRuntimeService.sessionKey(7L, 301L, 100L), TopicRouteSession.STATUS_RUNNING));
        List<Map<String,Object>> rows = call(); assertEquals(1, rows.size()); assertEquals(100L, rows.get(0).get("activityId"));
        verify(perks).isExploreCompleted(71L, 301L);
    }

    @Test void unpaidFirstTicketDoesNotHideLaterPaidPureContentCompletion() {
        topic(301L, ProductType.FREE_EXPLORE); activity(100L, 301L);
        CmsRegistration unpaid = paidExplore(100L); unpaid.setId(70L); unpaid.setPaymentStatus(1);
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(List.of(unpaid, paidExplore(100L)));
        when(perks.isExploreCompleted(71L, 301L)).thenReturn(true);
        assertEquals(1, call().size()); verify(perks, never()).isExploreCompleted(70L, 301L);
        verify(perks).isExploreCompleted(71L, 301L);
    }

    @Test void anyPaidCompletedTicketCanSupplyCompletionWhenFirstPaidTicketIsIncomplete() {
        topic(301L, ProductType.FREE_EXPLORE); activity(100L, 301L);
        CmsRegistration later = paidExplore(100L); later.setId(72L);
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(List.of(paidExplore(100L), later));
        when(perks.isExploreCompleted(71L, 301L)).thenReturn(false);
        when(perks.isExploreCompleted(72L, 301L)).thenReturn(true);
        assertEquals(1, call().size()); verify(perks).isExploreCompleted(71L, 301L);
        verify(perks).isExploreCompleted(72L, 301L);
        org.mockito.ArgumentCaptor<CmsRegistration> probe = org.mockito.ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrations).selectCmsRegistrationList(probe.capture());
        assertEquals(7L, probe.getValue().getMemberId()); assertEquals(2L, probe.getValue().getOwnerType());
        assertEquals(2, probe.getValue().getPaymentStatus());
        assertEquals(PurchaseKind.EXPLORE_PASS, probe.getValue().getPurchaseKind());
    }

    @Test void unredeemedPaidExploreDoesNotBorrowCompletedNodeCounts() {
        topic(301L, ProductType.FREE_EXPLORE); activity(100L, 301L);
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(List.of(paidExplore(100L)));
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(List.of(progress(100L, 301L, 1)));
        when(perks.isExploreCompleted(71L, 301L)).thenReturn(false);
        assertTrue(call().isEmpty()); verify(perks).isExploreCompleted(71L, 301L);
    }

    @Test void unpaidRefundedWrongOwnerAndWrongActivityTicketsDoNotAuthorizeExploreCompletion() {
        topic(301L, ProductType.FREE_EXPLORE); activity(100L, 301L);
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(List.of(progress(100L, 301L, 1)));
        List<CmsRegistration> invalid = new ArrayList<>();
        CmsRegistration unpaid = paidExplore(100L); unpaid.setPaymentStatus(1); invalid.add(unpaid);
        CmsRegistration refunded = paidExplore(100L); refunded.setPaymentStatus(4); invalid.add(refunded);
        CmsRegistration other = paidExplore(100L); other.setMemberId(8L); invalid.add(other);
        CmsRegistration wrongActivity = paidExplore(101L); invalid.add(wrongActivity);
        for (CmsRegistration pass : invalid) {
            when(registrations.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(List.of(pass));
            assertTrue(call().isEmpty());
        }
        verifyNoInteractions(perks);
    }

    @Test void anonymousRequestDoesNotQueryAnyCompletionSource() {
        doReturn(0L).when(controller).getAppUserId();
        assertNotEquals(200, controller.myCompleted().get("code"));
        verifyNoInteractions(facts, progress, registrations, routes);
    }
}
