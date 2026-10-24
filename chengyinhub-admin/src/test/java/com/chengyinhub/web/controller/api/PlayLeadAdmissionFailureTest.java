package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.PreferenceSubmitRequest;
import com.chengyinhub.business.domain.dto.SensorResultRequest;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import com.chengyinhub.web.service.PreferencePlayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real caller/catch-boundary tests. Receipt rollback and SQL effects require the separate MySQL IT. */
class PlayLeadAdmissionFailureTest {
    private ApiPlayProgressController controller;
    private ClubLeadMapper leads;
    private ICmsMemberTemplateService templates;
    private CmsTopicNode node;
    private CmsMemberTemplate template;
    private CmsTopic topic;
    private ITopicRouteRuntimeService routes;
    private IPlayerNodeProgressService progress;
    private IPointsAwardService points;
    private IUmsMemberService members;
    private IUmsMemberPointsDetailService ledger;
    private IPuzzlePlayService puzzles;
    private PreferencePlayService preferences;
    private ICmsPointsSettingService settings;
    private ISensorChallengeService sensors;
    private IShopDayProofService proof;
    private IGrowthService growth;
    private IRewardDropService drops;
    private IPlayerProgressionService progression;
    private ISmsCouponHistoryService coupons;
    private ITopicCompletionAwardService completionAwards;
    private PlayAttemptLogMapper attempts;

    @BeforeEach void fixture() {
        controller = spy(new ApiPlayProgressController()); doReturn(7L).when(controller).getAppUserId();
        leads = mock(ClubLeadMapper.class);
        when(leads.selectByActivity(500L)).thenThrow(new DataAccessResourceFailureException("SELECT private_dependency WHERE secret=value"));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "clubLeadMapper", leads);
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        node = new CmsTopicNode(); node.setId(401L); node.setTopicId(300L); node.setChapterId(20L); node.setTemplateId(39L); node.setSortId(1L); node.setStatus(1L);
        when(nodes.selectCmsTopicNodeById(401L)).thenReturn(node);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(List.of(node));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodes);
        ICmsActivityService activities = mock(ICmsActivityService.class);
        CmsActivity activity = new CmsActivity(); activity.setId(500L); activity.setTopicId(300L);
        when(activities.selectCmsActivityById(500L)).thenReturn(activity);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activities);
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        CmsRegistration registration = new CmsRegistration(); registration.setId(91L); registration.setMemberId(7L);
        registration.setOwnerType(2L); registration.setOwnerId(500L); registration.setRegistrationStatus(2); registration.setPaymentStatus(2);
        registration.setPurchaseKind(1); registration.setExpiresAt(new Date(System.currentTimeMillis() + 86_400_000L));
        when(registrations.selectCmsRegistrationList(any())).thenReturn(List.of(registration));
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", registrations);
        ICmsTopicService topics = mock(ICmsTopicService.class); topic = new CmsTopic(); topic.setId(300L); topic.setProductType(1);
        when(topics.selectCmsTopicById(300L)).thenReturn(topic); com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topics);
        templates = mock(ICmsMemberTemplateService.class); template = new CmsMemberTemplate(); template.setId(39L); template.setValidationMethod(1);
        template.setHint1("private hint"); template.setQuestionAnswer("answer");
        when(templates.selectCmsMemberTemplateById(39L)).thenReturn(template);
        ReflectionTestUtils.setField(controller, "cmsMemberTemplateService", templates);
        routes = mock(ITopicRouteRuntimeService.class); when(routes.canPlay(eq(7L), eq(300L), eq(500L), eq(401L), nullable(String.class))).thenReturn(true);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", routes);
        progress = mock(IPlayerNodeProgressService.class); points = mock(IPointsAwardService.class);
        members = mock(IUmsMemberService.class); ledger = mock(IUmsMemberPointsDetailService.class);
        puzzles = mock(IPuzzlePlayService.class); preferences = mock(PreferencePlayService.class); settings = mock(ICmsPointsSettingService.class);
        sensors = mock(ISensorChallengeService.class); proof = mock(IShopDayProofService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progress);
        ReflectionTestUtils.setField(controller, "pointsAwardService", points);
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        ReflectionTestUtils.setField(controller, "umsMemberPointsDetailService", ledger);
        ReflectionTestUtils.setField(controller, "puzzlePlayService", puzzles);
        ReflectionTestUtils.setField(controller, "preferencePlayService", preferences);
        ReflectionTestUtils.setField(controller, "cmsPointsSettingService", settings);
        ReflectionTestUtils.setField(controller, "sensorChallengeService", sensors);
        ReflectionTestUtils.setField(controller, "shopDayProofService", proof);
        growth = mock(IGrowthService.class); drops = mock(IRewardDropService.class);
        progression = mock(IPlayerProgressionService.class); coupons = mock(ISmsCouponHistoryService.class);
        completionAwards = mock(ITopicCompletionAwardService.class); attempts = mock(PlayAttemptLogMapper.class);
        ReflectionTestUtils.setField(controller, "growthService", growth);
        ReflectionTestUtils.setField(controller, "rewardDropService", drops);
        ReflectionTestUtils.setField(controller, "playerProgressionService", progression);
        ReflectionTestUtils.setField(controller, "smsCouponHistoryService", coupons);
        ReflectionTestUtils.setField(controller, "topicCompletionAwardService", completionAwards);
        ReflectionTestUtils.setField(controller, "playAttemptLogMapper", attempts);
        CurrentPlayControllerFixtures.install(controller);
    }

    @Test void hintUnlockFaultReturnsNoHintAndNeverReachesPricingOrDebit() {
        reject(() -> controller.unlockHint(401L, "500", null)); verifyNoInteractions(templates);
    }
    @Test void answerFaultDoesNotCompleteReceiptOrAward() {
        reject(() -> controller.answer("500", "401", "answer", null, "lead-answer", 0, 0L, null, null));
    }
    @Test void scanFaultDoesNotCompleteReceiptOrProgress() {
        reject(() -> controller.checkin("500", "STATIC", null, "lead-scan", 0, 91L, "401", 0L, null, null));
    }
    @Test void gpsFaultDoesNotCompleteReceiptOrProgress() {
        reject(() -> controller.arrive("500", "401", "121", "31", null, "lead-gps", 0, "10", 0L, null, null));
    }
    @Test void puzzleHintFaultReturnsNoHintOrAttempt() {
        reject(() -> controller.puzzleHint("500", "401", null, 1));
    }
    @Test void revealFaultReturnsNoAnswerOrCompletion() {
        reject(() -> controller.revealPuzzle("500", "401", null, "lead-reveal", 0));
    }
    @Test void photoFaultEscapesLocalCatchAndDoesNotSubmitProof() {
        template.setValidationMethod(2);
        reject(() -> controller.photo("500", "401", "media-asset:701", null, "lead-photo", 0));
    }
    @Test void freeCollectFilterPhotoFaultEscapesTheInnerIllegalStateCatch() {
        topic.setProductType(2); template.setValidationMethod(2); template.setSensorType("filter_shot");
        template.setSensorConfig("{\"filterStyle\":\"night_vision\"}");
        reject(() -> controller.photo("500", "401", "media-asset:701", null, "lead-filter-photo", 0));
    }
    @Test void sensorFaultNeverAcquiresSensorOrAwards() {
        SensorResultRequest request = new SensorResultRequest(); request.setActivityId(500L); request.setNodeId(401L);
        request.setRouteActionId("lead-sensor"); request.setExpectedRouteVersion(0);
        reject(() -> controller.sensorResult(request));
    }
    @Test void preferenceReadFaultReturnsNoPreferencePayload() {
        reject(() -> controller.preference(401L, "500", null));
        verifyNoInteractions(routes);
    }
    @Test void preferenceSubmitFaultNeverCallsPreferenceMutation() {
        PreferenceSubmitRequest request = new PreferenceSubmitRequest(); request.setActivityId("500"); request.setChoices(Map.of("one", "A"));
        request.setRouteActionId("lead-preference"); request.setExpectedRouteVersion(0);
        reject(() -> controller.submitPreference(401L, request));
        verifyNoInteractions(routes);
    }

    @Test void preferenceReadRecoveryStillCallsOriginalRouteAndLoadsPreference() {
        doReturn(null).when(leads).selectByActivity(500L);
        template.setValidationMethod(ValidationMethod.PREFERENCE_GROUP);
        Map<String, Object> data = Map.of("question", "original-preference");
        when(preferences.load(7L, 300L, 401L, template)).thenReturn(data);
        assertEquals(data, controller.preference(401L, "500", null).get("data"));
        verify(routes).canPlay(7L, 300L, 500L, 401L, null);
        verify(preferences).load(7L, 300L, 401L, template);
    }

    @Test void preferenceSubmitRecoveryPassesTheOriginalCommandAfterOriginalRouteAdmission() {
        doReturn(null).when(leads).selectByActivity(500L);
        template.setValidationMethod(ValidationMethod.PREFERENCE_GROUP);
        PreferenceSubmitRequest request = preferenceRequest();
        when(preferences.submit(eq(7L), eq(300L), eq(500L), eq(401L), same(template), same(request), any()))
                .thenReturn(Map.of("needsTiebreak", false));
        assertEquals(200, controller.submitPreference(401L, request).get("code"));
        verify(routes).canPlay(7L, 300L, 500L, 401L, "lead-preference");
        verify(preferences).submit(eq(7L), eq(300L), eq(500L), eq(401L), same(template), same(request), any());
        assertEquals("lead-preference", request.getRouteActionId());
        assertEquals(Integer.valueOf(0), request.getExpectedRouteVersion());
        assertEquals(Map.of("one", "A"), request.getChoices());
    }

    @Test void preferenceBothHttpMethodsKeepOriginal403WithoutEnteringRouteOrPreference() {
        ServiceException forbidden = new ServiceException("无权读取本场带领状态", 403);
        doThrow(forbidden).when(leads).selectByActivity(500L);
        assertSame(forbidden, assertThrowsExactly(ServiceException.class, () -> controller.preference(401L, "500", null)));
        assertSame(forbidden, assertThrowsExactly(ServiceException.class, () -> controller.submitPreference(401L, preferenceRequest())));
        assertEquals(Integer.valueOf(403), forbidden.getCode());
        verify(leads, times(2)).selectByActivity(500L);
        verifyNoInteractions(routes, preferences, progress, points, ledger, attempts);
    }

    @Test void preferenceLeadRefusalTakesPriorityOverUnlockAndRouteRefusalForBothHttpMethods() {
        ClubLeadState gathering = new ClubLeadState(); gathering.setStatus(1);
        doReturn(gathering).when(leads).selectByActivity(500L);
        node.setUnlockAfterNodeId(400L); // Would read progress and refuse at the former first gate.
        doReturn(false).when(routes).canPlay(anyLong(), anyLong(), anyLong(), anyLong(), nullable(String.class));
        assertEquals("队伍集合中,等队长开始行程", controller.preference(401L, "500", null).get("msg"));
        assertEquals("队伍集合中,等队长开始行程", controller.submitPreference(401L, preferenceRequest()).get("msg"));
        verify(leads, times(2)).selectByActivity(500L);
        verifyNoInteractions(routes, progress, preferences);
    }

    @Test void preferenceNodeOwnershipRefusalStillPrecedesLeadAndRouteForBothHttpMethods() {
        node.setTopicId(301L);
        assertEquals("该节点不属于本次活动", controller.preference(401L, "500", null).get("msg"));
        assertEquals("该节点不属于本次活动", controller.submitPreference(401L, preferenceRequest()).get("msg"));
        verifyNoInteractions(leads, routes, preferences);
    }

    private PreferenceSubmitRequest preferenceRequest() {
        PreferenceSubmitRequest request = new PreferenceSubmitRequest(); request.setActivityId("500");
        request.setChoices(Map.of("one", "A")); request.setRouteActionId("lead-preference"); request.setExpectedRouteVersion(0);
        return request;
    }

    private void reject(Executable operation) {
        ServiceException error = assertThrowsExactly(ServiceException.class, operation);
        assertEquals(Integer.valueOf(503), error.getCode()); assertEquals("带领状态暂时不可用，请稍后重试", error.getMessage());
        assertEquals(Map.of("outcome", "NOT_RUN"), error.getData()); assertNull(error.getCause());
        var response = new GlobalExceptionHandler().handleServiceException(error, new MockHttpServletRequest());
        assertEquals(503, response.get("code")); assertEquals(error.getMessage(), response.get("msg"));
        assertEquals(Map.of("outcome", "NOT_RUN"), response.get("data")); assertFalse(response.containsKey("intentTerminal"));
        verify(leads).selectByActivity(500L); // Proves this was the lead failure, never an early missing-identity rejection.
        verifyNoInteractions(progress, points, members, ledger, puzzles, preferences, settings, sensors, proof, growth, drops, progression, coupons, completionAwards, attempts);
        GameSessionRuntimeMapper receipts = (GameSessionRuntimeMapper) ReflectionTestUtils.getField(controller, "playCommandReceiptMapper");
        verify(receipts, never()).completeReceipt(any());
    }
}
