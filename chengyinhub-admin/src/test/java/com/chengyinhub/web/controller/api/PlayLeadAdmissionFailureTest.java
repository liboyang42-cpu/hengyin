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
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrations);
        ICmsTopicService topics = mock(ICmsTopicService.class); topic = new CmsTopic(); topic.setId(300L); topic.setProductType(1);
        when(topics.selectCmsTopicById(300L)).thenReturn(topic); com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topics);
        templates = mock(ICmsMemberTemplateService.class); template = new CmsMemberTemplate(); template.setId(39L); template.setValidationMethod(1);
        template.setHint1("private hint"); template.setQuestionAnswer("answer");
        when(templates.selectCmsMemberTemplateById(39L)).thenReturn(template);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", templates);
        routes = mock(ITopicRouteRuntimeService.class); when(routes.canPlay(eq(7L), eq(300L), eq(500L), eq(401L), nullable(String.class))).thenReturn(true);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteRuntimeService", routes);
        progress = mock(IPlayerNodeProgressService.class); points = mock(IPointsAwardService.class);
        members = mock(IUmsMemberService.class); ledger = mock(IUmsMemberPointsDetailService.class);
        puzzles = mock(IPuzzlePlayService.class); preferences = mock(PreferencePlayService.class); settings = mock(ICmsPointsSettingService.class);
        sensors = mock(ISensorChallengeService.class); proof = mock(IShopDayProofService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progress);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "pointsAwardService", points);

        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "umsMemberPointsDetailService", ledger);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService", puzzles);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "preferencePlayService", preferences);

        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "sensorChallengeService", sensors);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "shopDayProofService", proof);
        growth = mock(IGrowthService.class); drops = mock(IRewardDropService.class);
        progression = mock(IPlayerProgressionService.class); coupons = mock(ISmsCouponHistoryService.class);
        completionAwards = mock(ITopicCompletionAwardService.class); attempts = mock(PlayAttemptLogMapper.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "growthService", growth);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "rewardDropService", drops);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerProgressionService", progression);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "smsCouponHistoryService", coupons);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicCompletionAwardService", completionAwards);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playAttemptLogMapper", attempts);
        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller, 300L);
        puzzles=spy(NodeHintUnlockTestFixtures.application(controller,settings,members,ledger,null));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller,"puzzlePlayService",puzzles);
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
        reject(() -> BasicPlayCommandFixtures.revealPuzzle(controller, "500", "401", null, "lead-reveal", 0));
    }
    @Test void photoFaultEscapesLocalCatchAndDoesNotSubmitProof() {
        template.setValidationMethod(2);
        reject(() -> BasicPlayCommandFixtures.photo(controller, "500", "401", "media-asset:701", null, "lead-photo", 0));
    }
    @Test void freeCollectFilterPhotoFaultEscapesTheInnerIllegalStateCatch() {
        topic.setProductType(2); template.setValidationMethod(2); template.setSensorType("filter_shot");
        template.setSensorConfig("{\"filterStyle\":\"night_vision\"}");
        reject(() -> BasicPlayCommandFixtures.photo(controller, "500", "401", "media-asset:701", null, "lead-filter-photo", 0));
    }
    @Test void sensorFaultNeverAcquiresSensorOrAwards() {
        SensorResultRequest request = new SensorResultRequest(); request.setActivityId(500L); request.setNodeId(401L);
        request.setRouteActionId("lead-sensor"); request.setExpectedRouteVersion(0);
        reject(() -> BasicPlayCommandFixtures.sensorResult(controller, request));
        verifyNoInteractions(routes);
    }
    @Test void preferenceReadFaultReturnsNoPreferencePayload() {
        reject(() -> controller.preference(401L, "500", null));
        verifyNoInteractions(routes);
    }
    @Test void preferenceSubmitFaultNeverCallsPreferenceMutation() {
        PreferenceSubmitRequest request = new PreferenceSubmitRequest(); request.setActivityId("500"); request.setChoices(Map.of("one", "A"));
        request.setRouteActionId("lead-preference"); request.setExpectedRouteVersion(0);
        reject(() -> BasicPlayCommandFixtures.submitPreference(controller, 401L, request));
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
        when(preferences.submit(eq(7L), eq(300L), eq(500L), eq(401L), same(template), same(request), eq("default"), any()))
                .thenReturn(Map.of("needsTiebreak", false));
        assertEquals(200, BasicPlayCommandFixtures.submitPreference(controller, 401L, request).get("code"));
        verify(routes).canPlay(7L, 300L, 500L, 401L, "lead-preference");
        verify(preferences).submit(eq(7L), eq(300L), eq(500L), eq(401L), same(template), same(request), eq("default"), any());
        assertEquals("lead-preference", request.getRouteActionId());
        assertEquals(Integer.valueOf(0), request.getExpectedRouteVersion());
        assertEquals(Map.of("one", "A"), request.getChoices());
    }

    @Test void preferenceBothHttpMethodsKeepOriginal403WithoutEnteringRouteOrPreference() {
        ServiceException forbidden = new ServiceException("无权读取本场带领状态", 403);
        doThrow(forbidden).when(leads).selectByActivity(500L);
        assertSame(forbidden, assertThrowsExactly(ServiceException.class, () -> controller.preference(401L, "500", null)));
        assertSame(forbidden, assertThrowsExactly(ServiceException.class, () -> BasicPlayCommandFixtures.submitPreference(controller, 401L, preferenceRequest())));
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
        assertEquals("队伍集合中,等队长开始行程", BasicPlayCommandFixtures.submitPreference(controller, 401L, preferenceRequest()).get("msg"));
        verify(leads, times(2)).selectByActivity(500L);
        verifyNoInteractions(routes, progress, preferences);
    }

    @Test void preferenceNodeOwnershipRefusalStillPrecedesLeadAndRouteForBothHttpMethods() {
        node.setTopicId(301L);
        assertEquals("该节点不属于本次活动", controller.preference(401L, "500", null).get("msg"));
        assertEquals("该节点不属于本次活动", BasicPlayCommandFixtures.submitPreference(controller, 401L, preferenceRequest()).get("msg"));
        verifyNoInteractions(leads, routes, preferences);
    }

    @Test void sensorGatheringRefusalDoesNotEnterWritableRouteOrAcquireProof() {
        ClubLeadState gathering = new ClubLeadState(); gathering.setStatus(1);
        doReturn(gathering).when(leads).selectByActivity(500L);
        SensorResultRequest request = new SensorResultRequest(); request.setActivityId(500L); request.setNodeId(401L);
        request.setRouteActionId("sensor-original"); request.setExpectedRouteVersion(4);
        BasicPlayCommandFixtures.frozenRun(controller, 500L, 300L, 401L, 4,
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(template));
        var response = BasicPlayCommandFixtures.sensorResult(controller, request);
        assertEquals(500, response.get("code"));
        assertEquals("队伍集合中,等队长开始行程", response.get("msg"));
        // 命令体内的业务拒绝是已执行意图的终态(PlayBasicIntentApplication 落 FAILED 回执并标 intentTerminal);
        // 客户端(pages/play/index.js req.settle)把既无 intentTerminal 也无 intentNotExecuted 的基础命令
        // 响应当成结果未知。异常回滚路径(见 reject)才不带终态标记。
        assertEquals(Boolean.TRUE, response.get("intentTerminal"));
        verify(leads).selectByActivity(500L);
        verifyNoInteractions(routes, sensors, proof, progress, points, ledger, growth, drops, progression,
                coupons, completionAwards, attempts);
    }

    @Test void sensorLeadRecoveryStillUsesOriginalActorScopeActionAndInput() {
        doReturn(null).when(leads).selectByActivity(500L);
        template.setValidationMethod(ValidationMethod.APP_SENSOR);
        SensorResultRequest request = new SensorResultRequest(); request.setActivityId(500L); request.setNodeId(401L);
        request.setRouteActionId("sensor-original"); request.setExpectedRouteVersion(4);
        BasicPlayCommandFixtures.frozenRun(controller, 500L, 300L, 401L, 4,
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(template));
        request.setSensorType("still"); request.setPayload(Map.of("heldSec", 12));
        when(sensors.validateAndAcquire(eq(7L), eq(500L), same(node), argThat(t -> t != null && java.util.Objects.equals(t.getId(), template.getId())
                        && java.util.Objects.equals(t.getValidationMethod(), template.getValidationMethod())
                        && java.util.Objects.equals(t.getSensorType(), template.getSensorType())
                        && java.util.Objects.equals(t.getSensorConfig(), template.getSensorConfig())), eq("still"), same(request.getPayload())))
                .thenReturn("传感器可信证明缺失，暂不能完成挑战");
        var response = BasicPlayCommandFixtures.sensorResult(controller, request);
        assertEquals(500, response.get("code"));
        assertEquals("传感器可信证明缺失，暂不能完成挑战", response.get("msg"));
        var order = inOrder(leads, routes, sensors);
        order.verify(leads).selectByActivity(500L);
        order.verify(routes).canPlay(7L, 300L, 500L, 401L, "sensor-original");
        var frozen = org.mockito.ArgumentCaptor.forClass(CmsMemberTemplate.class);
        order.verify(sensors).validateAndAcquire(eq(7L), eq(500L), same(node), frozen.capture(), eq("still"), same(request.getPayload()));
        assertEquals(template.getId(), frozen.getValue().getId());
        assertEquals(template.getValidationMethod(), frozen.getValue().getValidationMethod());
        assertEquals(template.getSensorType(), frozen.getValue().getSensorType());
        assertEquals(template.getSensorConfig(), frozen.getValue().getSensorConfig());
        assertEquals("sensor-original", request.getRouteActionId());
        assertEquals(Integer.valueOf(4), request.getExpectedRouteVersion());
        assertEquals(Map.of("heldSec", 12), request.getPayload());
        verifyNoInteractions(progress, points, ledger, proof, growth, drops, progression, coupons, completionAwards, attempts);
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
        verifyNoInteractions(progress, points, members, ledger, preferences, settings, sensors, proof, growth, drops, progression, coupons, completionAwards, attempts);
        GameSessionRuntimeMapper receipts = (GameSessionRuntimeMapper) com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "playCommandReceiptMapper");
        verify(receipts, never()).completeReceipt(any());
        verify(puzzles, never()).hintUnlockCost();
        verify(puzzles, never()).requestHint(any(),any(),any(),any(),anyInt());
        verify(puzzles, never()).selectPuzzleTemplate(any());
        verify(puzzles, never()).recordReveal(any(),any(),any(),any(),any());
        verify(puzzles, never()).revealText(any());
        verify(puzzles, never()).solvedScore(any(),any(),any(),any());
        verify(puzzles, never()).selectHintLevels(any(),any(),any());
    }
}
