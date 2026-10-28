package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.IPlayEncounterService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import static org.mockito.Mockito.verifyNoInteractions;
import java.util.Collections;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdvancedGameRuntimeServiceImplTest {

    private static final long NOW = 1_775_000_000_000L;
    private static final long MEMBER_ID = 7L;
    private static final long ACTIVITY_ID = 9L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;
    private static final long TEMPLATE_ID = 39L;

    private GameRuntimeSessionMapper sessionMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsMemberTemplateMapper templateMapper;
    private CmsActivityMapper activityMapper;
    private CmsRegistrationMapper registrationMapper;
    private PlayTeamMapper teamMapper;
    private PlayAttemptLogMapper attemptMapper;
    private AdvancedGameRuntimeServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.install(activityMapper,registrationMapper);
        teamMapper = mock(PlayTeamMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service,"verifiedWeRunService",com.chengyinhub.business.it.R5WeRunFixture.service());
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "attemptMapper", attemptMapper);
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(NOW));
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setTemplateId(TEMPLATE_ID);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTopicId(TOPIC_ID);
        activity.setProductType(1);
        activity.setStartDate(new Date(NOW - 60_000));
        activity.setEndDate(new Date(NOW + 3_600_000));
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID)).thenReturn(1);
    }

    @Test
    void startFreezesConfigInitializesBranchAndUsesServerDeadline() {
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(fullConfig()));
        AtomicReference<GameRuntimeSession> inserted = new AtomicReference<>();
        when(sessionMapper.insert(any())).thenAnswer(invocation -> {
            GameRuntimeSession row = invocation.getArgument(0);
            row.setId(51L);
            inserted.set(row);
            return 1;
        });

        Map<String, Object> state = service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null);

        assertEquals(51L, state.get("sessionId"));
        assertEquals("RUNNING", state.get("status"));
        assertEquals("start", ((Map<?, ?>) state.get("branch")).get("currentStepId"));
        assertEquals(NOW + 60_000, inserted.get().getDeadlineAt().getTime());
        assertEquals(0, inserted.get().getVersion());
    }

    @Test
    void runtimeVersionComesOnlyFromFrozenConfigAndIsAbsentForLegacy() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        GameRuntimeSession legacy = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(legacy);
        assertFalse(json.valueToTree(service.state(MEMBER_ID, 51L)).path("config").has("outcomeRulesVersion"));

        com.fasterxml.jackson.databind.node.ObjectNode draft = json.createObjectNode().put("schemaVersion", 1);
        draft.putObject("album").put("enabled", true);
        draft.putObject("outcomeRules").put("version", 1).putObject("pass");
        GameRuntimeSession frozen = runningSession(draft.toString(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(frozen);
        assertEquals(1, json.valueToTree(service.state(MEMBER_ID, 51L)).path("config").path("outcomeRulesVersion").asInt());
        draft.remove("outcomeRules");
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(draft.toString()));
        assertEquals(1, json.valueToTree(service.state(MEMBER_ID, 51L)).path("config").path("outcomeRulesVersion").asInt());
        assertEquals(1, json.readTree(frozen.getConfigSnapshotJson()).path("outcomeRules").path("version").asInt());
    }

    @Test
    void personalTaskStartCreatesTheCurrentTaskInstanceInsteadOfReusingOldPassedNode() {
        com.chengyinhub.business.service.ITopicRouteRuntimeService route=mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);
        when(route.genericPlayer(MEMBER_ID,TOPIC_ID,ACTIVITY_ID)).thenReturn(true);
        when(route.gameplayInstance(MEMBER_ID,TOPIC_ID,ACTIVITY_ID,NODE_ID)).thenReturn("quest:new");
        when(route.canPlay(MEMBER_ID,TOPIC_ID,ACTIVITY_ID,NODE_ID)).thenReturn(true);
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(fullConfig()));
        GameRuntimeSession old=runningSession(fullConfig(),"{}");old.setStatus(GameRuntimeSession.STATUS_COMPLETED);
        when(sessionMapper.selectByOwner(ACTIVITY_ID,TOPIC_ID,NODE_ID,GameRuntimeSession.OWNER_PLAYER,MEMBER_ID)).thenReturn(old);
        AtomicReference<GameRuntimeSession> inserted=new AtomicReference<>();
        when(sessionMapper.insert(any())).thenAnswer(i2->{GameRuntimeSession row=i2.getArgument(0);row.setId(52L);inserted.set(row);return 1;});
        assertEquals(52L,service.start(MEMBER_ID,ACTIVITY_ID,TOPIC_ID,NODE_ID,null).get("sessionId"));
        assertEquals("quest:new",inserted.get().getPlayInstanceId());verify(sessionMapper,never()).selectByOwner(ACTIVITY_ID,TOPIC_ID,NODE_ID,GameRuntimeSession.OWNER_PLAYER,MEMBER_ID);
    }

    @Test
    void activityEndRejectsNewStartAtTheActualTimeInsteadOfMidnight() {
        activityMapper.selectCmsActivityById(ACTIVITY_ID).setEndDate(new Date(NOW - 1));
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(fullConfig()));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null));
        assertEquals("活动已结束", error.getMessage());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void activityEndRejectsAnExistingSessionActionWithoutWritingProgressOrAttempt() {
        activityMapper.selectCmsActivityById(ACTIVITY_ID).setEndDate(new Date(NOW - 1));
        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "ended-draw", "DRAW", Collections.emptyMap()));
        assertEquals("活动已结束", error.getMessage());
        verify(sessionMapper, never()).updateStateCas(any());
        verify(attemptMapper, never()).insertPlayAttemptLog(any());
    }

    @Test
    void activityEndRejectsCompletionEvenWhenTheGameIsAlreadyCompleted() {
        activityMapper.selectCmsActivityById(ACTIVITY_ID).setEndDate(new Date(NOW - 1));
        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        session.setStatus(GameRuntimeSession.STATUS_COMPLETED);
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
        assertEquals("活动已结束", error.getMessage());
        verify(sessionMapper, never()).updateStateCas(any());
    }

    @Test
    void completedHistoryRemainsReadableAfterTheActivityEnd() {
        activityMapper.selectCmsActivityById(ACTIVITY_ID).setEndDate(new Date(NOW - 1));
        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        session.setStatus(GameRuntimeSession.STATUS_COMPLETED);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        assertEquals("COMPLETED", service.state(MEMBER_ID, 51L).get("status"));
        verify(sessionMapper, never()).updateStateCas(any());
    }

    @Test
    void drawUsesConfiguredWeightsOnceAndPersistsThroughCas() {
        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey("draw-1")).thenReturn(null);

        Map<String, Object> state = service.act(MEMBER_ID, 51L, 0, "draw-1", "DRAW",
                Collections.emptyMap());

        assertEquals(1, ((java.util.List<?>) state.get("draws")).size());
        assertEquals(1, state.get("version"));
        verify(sessionMapper).updateStateCas(any());
        verify(attemptMapper).insertPlayAttemptLog(any());
    }

    /** 2026-10-14 用户已定：自玩通行证在本局内有效 —— 开局后中途到期可打完本局，到期后不能新开局。 */
    @Test
    void expiredSelfPlayPassCanFinishRunningSessionButCannotStartNewOne() {
        CmsRegistration pass = new CmsRegistration();
        pass.setId(777L); pass.setMemberId(MEMBER_ID); pass.setOwnerType(1L); pass.setOwnerId(TOPIC_ID);
        pass.setRegistrationStatus(2); pass.setPaymentStatus(0); pass.setPurchaseKind(2);
        pass.setExpiresAt(new Date(NOW - 1));
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(Collections.singletonList(pass));
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(777L)).thenReturn(pass);

        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        session.setActivityId(0L);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey("draw-expired-pass")).thenReturn(null);
        Map<String, Object> state = service.act(MEMBER_ID, 51L, 0, "draw-expired-pass", "DRAW", Collections.emptyMap());
        assertEquals(1, ((java.util.List<?>) state.get("draws")).size(), "已开局：通行证中途到期仍可打完本局");

        IllegalStateException fresh = assertThrows(IllegalStateException.class,
                () -> service.start(MEMBER_ID, 0L, TOPIC_ID, NODE_ID, null));
        assertEquals("当前自玩资格已失效", fresh.getMessage(), "到期后不能新开局");
    }

    /** 普通线性路线不建玩法局：本路线已开局时，到站完成闸同样按「打完本局」放行；未开局仍拒。 */
    @Test
    void expiredSelfPlayPassCompleteGateFollowsRouteStarted() {
        CmsRegistration pass = new CmsRegistration();
        pass.setId(778L); pass.setMemberId(MEMBER_ID); pass.setOwnerType(1L); pass.setOwnerId(TOPIC_ID);
        pass.setRegistrationStatus(2); pass.setPaymentStatus(0); pass.setPurchaseKind(2);
        pass.setExpiresAt(new Date(NOW - 1));
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(Collections.singletonList(pass));
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(778L)).thenReturn(pass);
        ITopicRouteRuntimeService route = mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);

        when(route.hasRunningSession(MEMBER_ID, TOPIC_ID, 0L)).thenReturn(true);
        assertNull(service.completeGate(MEMBER_ID, 0L, TOPIC_ID, NODE_ID), "本路线已开局：中途到期仍可到站完成");

        when(route.hasRunningSession(MEMBER_ID, TOPIC_ID, 0L)).thenReturn(false);
        IllegalStateException fresh = assertThrows(IllegalStateException.class,
                () -> service.completeGate(MEMBER_ID, 0L, TOPIC_ID, NODE_ID));
        assertEquals("当前自玩资格已失效", fresh.getMessage(), "未开局：到期后不能新开局");
    }

    // ==================== M1 回执自带新世界 ====================

    @Test
    void advancedActionReceiptCarriesJourneyVersionAndEncounter() {
        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey("world-draw")).thenReturn(null);
        IPlayEncounterService encounterService = mock(IPlayEncounterService.class);
        Map<String, Object> encounter = new LinkedHashMap<>();
        encounter.put("nodeId", NODE_ID);
        encounter.put("stateVersion", 3);
        when(encounterService.resolve(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn(encounter);
        ReflectionTestUtils.setField(service, "playEncounterService", encounterService);

        Map<String, Object> view = service.act(MEMBER_ID, 51L, 0, "world-draw", "DRAW",
                Collections.emptyMap());

        assertEquals(3, view.get("stateVersion"), "玩法回包自带本局新 version");
        assertEquals(encounter, view.get("encounter"), "玩法回包自带这一站刷新后的 Encounter");
    }

    /** R20-INT 收口 b:玩法回包顶层带本局 hp / luck 当前值(判错扣血后的新血值)。 */
    @Test
    void advancedActionReceiptCarriesCurrentHpAndLuck() {
        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey("world-draw")).thenReturn(null);
        ITopicRouteRuntimeService journey = mock(ITopicRouteRuntimeService.class);
        Map<String, Integer> vitals = new LinkedHashMap<>();
        vitals.put("hp", 4);
        vitals.put("luck", 2);
        when(journey.vitals(MEMBER_ID, TOPIC_ID, ACTIVITY_ID)).thenReturn(vitals);
        when(journey.canPlay(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn(true);
        when(journey.playInstance(MEMBER_ID, TOPIC_ID, ACTIVITY_ID)).thenReturn("default");
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", journey);

        Map<String, Object> view = service.act(MEMBER_ID, 51L, 0, "world-draw", "DRAW",
                Collections.emptyMap());

        assertEquals(4, view.get("hp"), "玩法回包顶层带当前 hp");
        assertEquals(2, view.get("luck"), "玩法回包顶层带当前 luck");
    }

    /** 缺装配(测试排空)时 hp / luck 照旧缺省,既有行为不变。 */
    @Test
    void advancedActionOmitsVitalsWithoutJourneyRuntime() {
        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey("world-draw")).thenReturn(null);

        Map<String, Object> view = service.act(MEMBER_ID, 51L, 0, "world-draw", "DRAW",
                Collections.emptyMap());

        assertFalse(view.containsKey("hp"));
        assertFalse(view.containsKey("luck"));
    }

    @Test
    void openingGateProtectsBothAdvancedStartAndExistingSessionActions() {
        ITopicRouteRuntimeService route = mock(ITopicRouteRuntimeService.class);
        when(route.canPlay(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn(true);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);
        org.mockito.Mockito.doThrow(new IllegalStateException("请先完成开局流程")).when(route)
                .requireOpeningAccess(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID);
        assertThrows(IllegalStateException.class,
                () -> service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null));
        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        assertThrows(IllegalStateException.class, () -> service.act(MEMBER_ID, 51L, 0,
                "skip-opening", "DRAW", Collections.emptyMap()));
        verify(route, org.mockito.Mockito.times(2))
                .requireOpeningAccess(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID);
        verify(sessionMapper, never()).insert(any());
        verify(sessionMapper, never()).updateStateCas(any());
        verify(attemptMapper, never()).insertPlayAttemptLog(any());
    }

    @Test
    void routeReceptionGateProtectsAdvancedStartAndOldSessionActions() {
        ITopicRouteRuntimeService route = mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);
        when(route.canPlay(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID))
                .thenThrow(new IllegalStateException("请先由商家扫描带队主理人的团码完成上一站接待"));
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(fullConfig()));
        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        session.setDeadlineAt(new Date(NOW - 1));
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        IllegalStateException start = assertThrows(IllegalStateException.class,
                () -> service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null));
        assertTrue(start.getMessage().contains("团码"));
        IllegalStateException action = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "skip-reception", "DRAW", Collections.emptyMap()));
        assertTrue(action.getMessage().contains("团码"));
        verify(sessionMapper, never()).insert(any());
        verify(sessionMapper, never()).updateStateCas(any());
        verify(attemptMapper, never()).insertPlayAttemptLog(any());
    }

    @Test
    void freeExploreAdvancedGameDoesNotAcquireLinearRouteOrder() {
        activityMapper.selectCmsActivityById(ACTIVITY_ID).setProductType(2);
        ITopicRouteRuntimeService route = mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);
        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        service.act(MEMBER_ID, 51L, 0, "free-order", "DRAW", Collections.emptyMap());
        verify(route, never()).canPlay(any(), any(), any(), any());
        verify(sessionMapper).updateStateCas(any());
    }

    @Test
    void staleVersionRejectionCarriesCurrentViewAndEncounter() {
        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        IPlayEncounterService encounterService = mock(IPlayEncounterService.class);
        Map<String, Object> encounter = new LinkedHashMap<>();
        encounter.put("nodeId", NODE_ID);
        encounter.put("stateVersion", 0);
        when(encounterService.resolve(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn(encounter);
        ReflectionTestUtils.setField(service, "playEncounterService", encounterService);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.act(MEMBER_ID, 51L, 7, "stale-draw", "DRAW", Collections.emptyMap()));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
        Map<?, ?> data = (Map<?, ?>) error.getData();
        assertNotNull(data, "旧 version 被拒必须随包带当前状态");
        Map<?, ?> current = (Map<?, ?>) data.get("view");
        assertNotNull(current);
        assertEquals(0, current.get("version"), "当前玩法会话 version");
        assertNotNull(current.get("encounter"), "当前世界的 Encounter");
        verify(sessionMapper, never()).updateStateCas(any());
    }

    @Test
    void chooseRejectsOptionOutsideCurrentBranchStep() {
        GameRuntimeSession session = runningSession(branchOnlyConfig(),
                "{\"branch\":{\"currentStepId\":\"start\",\"visitedStepIds\":[\"start\"]}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("optionId", "missing");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "branch-1", "CHOOSE", payload));

        assertEquals("当前步骤没有这个选项", error.getMessage());
        verify(sessionMapper, never()).updateStateCas(any());
    }

    @Test
    void completeGateFailsClosedUntilEveryEnabledMechanicIsReady() {
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(fullConfig()));
        GameRuntimeSession session = runningSession(fullConfig(),
                "{\"draws\":[],\"branch\":{\"currentStepId\":\"start\",\"visitedStepIds\":[\"start\"]}}");
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));

        assertEquals("高级玩法尚未完成", error.getMessage());
        verify(sessionMapper, never()).updateStateCas(any());
    }

    // ── v5.1 玩法必须真闸住 completeGate(修复前 mechanicsReady 只查 random/branch/multiplayer,
    //    纯 v5.1 模板开局即可通关:盲品不用答对、低碳不用走步、城市签不用领)。

    @Test
    void completeGateBlocksV51MechanicsUntilTheyAreActuallyDone() {
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(v51GateConfig()));
        GameRuntimeSession session = runningSession(v51GateConfig(), "{}");
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));

        assertEquals("高级玩法尚未完成", error.getMessage());
        verify(sessionMapper, never()).updateStateCas(any());
    }

    @Test
    void completeGatePassesWhenEveryV51MechanicIsDone() {
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(v51GateConfig()));
        GameRuntimeSession session = runningSession(v51GateConfig(),
                "{\"blindTaste\":{\"solved\":true},\"diyName\":{\"name\":\"熬夜特调\"},"
                        + "\"steps\":{\"reached\":true},\"dailySign\":{\"claimedDate\":\"2026-08-27\"}}");
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);

        service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID);

        verify(sessionMapper).updateStateCas(any());
    }

    @Test
    void musicCornerAndSilentOrderDoNotGateCompletion() {
        // #857 拍板:音乐角/无声点单是氛围玩法,不判定通关 —— 空 state 也能过闸
        String config = "{\"schemaVersion\":1,"
                + "\"musicCorner\":{\"enabled\":true,\"title\":\"只用耳朵\",\"durationSeconds\":180},"
                + "\"silentOrder\":{\"enabled\":true,\"title\":\"不说一个字\",\"rule\":\"开口即失败\"}}";
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(config));
        GameRuntimeSession session = runningSession(config, "{}");
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);

        service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID);

        verify(sessionMapper).updateStateCas(any());
    }

    @Test
    void timeWindowGateFailsClosedOnMalformedClockText() {
        // 种子 SQL 直写库可绕过 Java 校验器,"配置层已拦过"不成立:
        // "9:00" 这类非法格式必须拒绝进入(fail-closed),不能静默当没配时段。
        com.fasterxml.jackson.databind.node.ObjectNode timeWindow =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        timeWindow.put("enabled", true);
        timeWindow.put("openFrom", "9:00");
        timeWindow.put("openTo", "23:00");

        assertThrows(IllegalStateException.class, () ->
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        service, "requireWithinTimeWindow", timeWindow));
    }

    /** 纯 v5.1 判定型四段(盲品/命名/计步/城市签),不含旧五段 —— 修复前这份配置开局即可通关。 */
    private String v51GateConfig() {
        return "{\"schemaVersion\":1,"
                + "\"blindTaste\":{\"enabled\":true,\"title\":\"先尝再猜\",\"xp\":15,\"answerKey\":\"B\","
                + "\"options\":[{\"key\":\"A\",\"label\":\"桂花乌龙\"},{\"key\":\"B\",\"label\":\"茉莉雪芽\"}]},"
                + "\"diyName\":{\"enabled\":true,\"title\":\"起个名字\",\"maxLength\":16},"
                + "\"steps\":{\"enabled\":true,\"goal\":6000,\"xp\":20},"
                + "\"dailySign\":{\"enabled\":true,\"signer\":\"猫向导\",\"poems\":[[\"第一行\"]]}}";
    }

    @Test
    void stateExpiresFromServerClockAndNeverAcceptsLateActions() {
        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        session.setDeadlineAt(new Date(NOW - 1));
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);

        Map<String, Object> state = service.state(MEMBER_ID, 51L);

        assertEquals("EXPIRED", state.get("status"));
        assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, 51L, 1, "late", "DRAW", Collections.emptyMap()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PASS", "FAIL", "CLOSED"})
    void settledR5StartReturnsAuthoritativeFeedbackWithoutRestart(String outcome) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode config = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(branchOnlyConfig());
        config.putObject("outcomeRules").put("version", 1);
        GameRuntimeSession session = runningSession(config.toString(),
                "{\"attemptOrdinal\":1,\"originalRouteSessionId\":61,\"gameplayDecision\":{\"outcome\":\"" + ("FAIL".equals(outcome) ? "FAIL" : "PASS") + "\"}}");
        session.setStatus("FAIL".equals(outcome) ? GameRuntimeSession.STATUS_FAILED : GameRuntimeSession.STATUS_COMPLETED);
        session.setVersion(3);
        String before = session.getStateJson();
        ITopicRouteRuntimeService route = mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);
        when(route.genericPlayer(MEMBER_ID, TOPIC_ID, ACTIVITY_ID)).thenReturn(true);
        when(route.gameplaySessionId(MEMBER_ID, TOPIC_ID, ACTIVITY_ID)).thenReturn(61L);
        when(sessionMapper.selectPlayerSessionsByTopic(ACTIVITY_ID, TOPIC_ID, MEMBER_ID)).thenReturn(Collections.singletonList(session));
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(route.gameplayFeedback(MEMBER_ID, 61L, "advanced:51:1", null)).thenReturn(
                json.readTree("{\"currentStepId\":\"start\",\"writable\":" + !"CLOSED".equals(outcome) + ",\"ended\":" + "CLOSED".equals(outcome) + "}"));

        com.fasterxml.jackson.databind.JsonNode view = json.valueToTree(service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null));

        assertEquals(51L, view.path("sessionId").asLong());
        assertEquals(3, view.path("version").asInt());
        assertEquals("start", view.path("branch").path("currentStepId").asText());
        assertEquals(!"CLOSED".equals(outcome), view.path("branch").path("writable").asBoolean());
        assertEquals(before, session.getStateJson());
        verify(route, never()).gameplayInstance(any(), any(), any(), any());
        verify(route, never()).requireOpeningAccess(any(), any(), any(), any());
        verify(route, never()).assertGameplayOutcomeStart(any(), any(), any(), any(), any());
        verify(sessionMapper, never()).restartTerminalCas(any());
        verify(sessionMapper, never()).updateStateCas(any());
        verify(sessionMapper, never()).insert(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"OLD_ROUTE", "OTHER_NODE", "ENDED_FEEDBACK"})
    void feedbackRecoveryDoesNotReplaceANewOrDifferentAttempt(String kind) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode config = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(branchOnlyConfig());
        config.putObject("outcomeRules").put("version", 1);
        GameRuntimeSession old = runningSession(config.toString(),
                "{\"originalRouteSessionId\":61,\"gameplayDecision\":{\"outcome\":\"PASS\"}}");
        old.setStatus(GameRuntimeSession.STATUS_COMPLETED);
        if ("OTHER_NODE".equals(kind)) old.setNodeId(30L);
        GameRuntimeSession current = runningSession(config.toString(), "{}"); current.setId(52L);
        ITopicRouteRuntimeService route = mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);
        when(route.canPlay(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn(true);
        when(route.genericPlayer(MEMBER_ID, TOPIC_ID, ACTIVITY_ID)).thenReturn(true);
        when(route.gameplaySessionId(MEMBER_ID, TOPIC_ID, ACTIVITY_ID)).thenReturn("OLD_ROUTE".equals(kind) ? 62L : 61L);
        when(route.gameplayInstance(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn("quest:new");
        when(sessionMapper.selectPlayerSessionsByTopic(ACTIVITY_ID, TOPIC_ID, MEMBER_ID)).thenReturn(Collections.singletonList(old));
        when(sessionMapper.selectOwnerByInstance(ACTIVITY_ID, TOPIC_ID, NODE_ID, GameRuntimeSession.OWNER_PLAYER, MEMBER_ID, "quest:new")).thenReturn(current);
        when(route.gameplayFeedback(MEMBER_ID, 61L, "advanced:51:1", null)).thenReturn(json.readTree("{\"currentStepId\":\"end\",\"ended\":true,\"writable\":true}"));

        assertEquals(52L, service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null).get("sessionId"));
        verify(route).requireOpeningAccess(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID);
        verify(sessionMapper, never()).selectAccessibleById(51L, MEMBER_ID);
    }

    @Test
    void startRetainsFailedSessionUntilExplicitRetry() {
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(randomOnlyConfig()));
        GameRuntimeSession failed = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        failed.setStatus(GameRuntimeSession.STATUS_FAILED);
        failed.setVersion(3);
        failed.setCompletedAt(new Date(NOW - 1_000));
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(failed);

        Map<String, Object> state = service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null);

        assertEquals("FAILED", state.get("status"));
        assertEquals(3, state.get("version"));
        assertEquals(Boolean.TRUE, state.get("canRetry"));
        verify(sessionMapper, never()).restartTerminalCas(any());
    }

    @Test
    void retryKeepsOriginalRulesAfterAuthorEditsTemplate() {
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(randomOnlyConfig()));
        GameRuntimeSession failed = runningSession(fullConfig(), "{}");
        failed.setStatus(GameRuntimeSession.STATUS_FAILED);
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(failed);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(failed);
        when(sessionMapper.restartTerminalCas(any())).thenReturn(1);

        Map<String, Object> view = service.act(MEMBER_ID, 51L, failed.getVersion(), "retry-frozen-rules", "RETRY", Collections.emptyMap());

        assertEquals(fullConfig(), failed.getConfigSnapshotJson(), "重试不能改写本局规则");
        assertEquals(NOW + 60_000, failed.getDeadlineAt().getTime());
        assertEquals("start", ((Map<?, ?>) view.get("branch")).get("currentStepId"));
    }

    @Test
    void teammateEntryAndRetryCannotResetFailedTeamSession() {
        GameRuntimeSession failed = runningSession(randomOnlyConfig(), "{\"draws\":[{\"id\":\"keep\"}]}");
        failed.setOwnerType(GameRuntimeSession.OWNER_TEAM); failed.setOwnerId(88L);
        failed.setStatus(GameRuntimeSession.STATUS_FAILED);
        com.chengyinhub.business.domain.PlayTeam team = new com.chengyinhub.business.domain.PlayTeam();
        team.setId(88L); team.setOwnerType(2); team.setOwnerId(ACTIVITY_ID); team.setStatus(2);
        team.setLeaderMemberId(MEMBER_ID + 1);
        when(sessionMapper.selectTeamByMember(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(failed);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(failed);
        when(teamMapper.selectPlayTeamById(88L)).thenReturn(team);
        when(teamMapper.selectActiveMember(88L, MEMBER_ID)).thenReturn(new com.chengyinhub.business.domain.PlayTeamMember());
        String original = failed.getStateJson();
        Map<String, Object> view = service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null);
        assertEquals("FAILED", view.get("status")); assertEquals(Boolean.FALSE, view.get("canRetry"));
        assertThrows(ServiceException.class, () -> service.act(MEMBER_ID, 51L, failed.getVersion(),
                "teammate-retry", "RETRY", Collections.emptyMap()));
        assertEquals(original, failed.getStateJson());
        verify(sessionMapper, never()).restartTerminalCas(any());
        verify(attemptMapper, never()).insertPlayAttemptLog(any());
    }

    @Test
    void currentLeaderExplicitRetryResetsOnlyFailedAttemptAtExactVersion() {
        GameRuntimeSession failed = runningSession(randomOnlyConfig(), "{\"draws\":[{\"id\":\"keep\"}]}");
        failed.setOwnerType(GameRuntimeSession.OWNER_TEAM); failed.setOwnerId(88L);
        failed.setStatus(GameRuntimeSession.STATUS_EXPIRED); failed.setVersion(3);
        com.chengyinhub.business.domain.PlayTeam team = new com.chengyinhub.business.domain.PlayTeam();
        team.setId(88L); team.setOwnerType(2); team.setOwnerId(ACTIVITY_ID); team.setStatus(2); team.setLeaderMemberId(MEMBER_ID);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(failed);
        when(teamMapper.selectPlayTeamById(88L)).thenReturn(team);
        when(teamMapper.selectPlayTeamByIdForUpdate(88L)).thenReturn(team);
        when(teamMapper.selectActiveMember(88L, MEMBER_ID)).thenReturn(new com.chengyinhub.business.domain.PlayTeamMember());
        when(sessionMapper.restartTerminalCas(any())).thenReturn(1);
        AtomicReference<com.chengyinhub.business.domain.PlayAttemptLog> persisted = new AtomicReference<>();
        when(attemptMapper.insertPlayAttemptLog(any())).thenAnswer(call -> { persisted.set(call.getArgument(0)); return 1; });
        assertThrows(ServiceException.class, () -> service.act(MEMBER_ID, 51L, 2, "old-retry", "RETRY", Collections.emptyMap()));
        Map<String, Object> view = service.act(MEMBER_ID, 51L, 3, "leader-retry", "RETRY", Collections.emptyMap());
        assertEquals("RUNNING", view.get("status")); assertEquals(4, view.get("version"));
        assertEquals(Boolean.FALSE, view.get("canRetry"));
        when(attemptMapper.selectByIdempotencyKey("leader-retry")).thenReturn(persisted.get());
        Map<String, Object> replayed = service.act(MEMBER_ID, 51L, 3, "leader-retry", "RETRY", Collections.emptyMap());
        assertEquals(((Number) view.get("sessionId")).longValue(), ((Number) replayed.get("sessionId")).longValue());
        assertEquals(view.get("version"), replayed.get("version"), "丢失响应重试必须读原回执，不再重置玩法");
        assertEquals("RUNNING", replayed.get("status"));
        verify(sessionMapper).restartTerminalCas(any());
        verify(attemptMapper).insertPlayAttemptLog(any());
    }

    @Test
    void retryRechecksLeaderUnderTeamLockBeforeClearingState() {
        GameRuntimeSession failed = runningSession(randomOnlyConfig(), "{\"draws\":[{\"id\":\"keep\"}]}");
        failed.setOwnerType(GameRuntimeSession.OWNER_TEAM); failed.setOwnerId(88L);
        failed.setStatus(GameRuntimeSession.STATUS_FAILED);
        com.chengyinhub.business.domain.PlayTeam before = new com.chengyinhub.business.domain.PlayTeam();
        before.setId(88L); before.setOwnerType(2); before.setOwnerId(ACTIVITY_ID); before.setStatus(2); before.setLeaderMemberId(MEMBER_ID);
        com.chengyinhub.business.domain.PlayTeam locked = new com.chengyinhub.business.domain.PlayTeam();
        locked.setId(88L); locked.setOwnerType(2); locked.setOwnerId(ACTIVITY_ID); locked.setStatus(2); locked.setLeaderMemberId(MEMBER_ID + 1);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(failed);
        when(teamMapper.selectPlayTeamById(88L)).thenReturn(before);
        when(teamMapper.selectPlayTeamByIdForUpdate(88L)).thenReturn(locked);
        when(teamMapper.selectActiveMember(88L, MEMBER_ID)).thenReturn(new com.chengyinhub.business.domain.PlayTeamMember());
        String original = failed.getStateJson();
        assertThrows(ServiceException.class, () -> service.act(MEMBER_ID, 51L, failed.getVersion(),
                "changed-leader-retry", "RETRY", Collections.emptyMap()));
        assertEquals(original, failed.getStateJson());
        verify(sessionMapper, never()).restartTerminalCas(any());
        verify(attemptMapper, never()).insertPlayAttemptLog(any());
    }

    @Test
    void resumeDoesNotReadRemovedOrInvalidLiveTemplate() {
        GameRuntimeSession session = runningSession(fullConfig(), "{\"draws\":[]}");
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);

        Map<String, Object> view = service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null);

        assertEquals(51L, view.get("sessionId"));
        assertEquals(fullConfig(), session.getConfigSnapshotJson());
        verify(templateMapper, never()).selectCmsMemberTemplateById(any());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void completionStillRequiresFrozenGameWhenLiveTemplateWasRemoved() {
        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID))
                .thenReturn(session);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));

        assertEquals("高级玩法尚未完成", error.getMessage());
        verify(sessionMapper, never()).updateStateCas(any());
        verify(templateMapper, never()).selectCmsMemberTemplateById(any());
    }

    @Test
    void existingSnapshotDoesNotBypassPaidRegistration() {
        GameRuntimeSession session = runningSession(fullConfig(), "{}");
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID)).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null));

        assertEquals("请先报名并完成支付", error.getMessage());
        verify(sessionMapper, never()).restartTerminalCas(any());
    }

    @Test
    void originalSmallGameResumesAfterUnbindWithoutAuthoringTemplateReads() {
        GameRuntimeSession original = runningSession(fullConfig(), "{}");
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(original);
        CmsTopicNode current = new CmsTopicNode(); current.setId(NODE_ID); current.setTopicId(TOPIC_ID);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(current);
        when(templateMapper.selectCmsMemberTemplateById(any())).thenThrow(new AssertionError("original game must use its original config"));

        Map<String, Object> result = service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null);

        assertEquals(51L, result.get("sessionId"));
        assertEquals(TEMPLATE_ID, original.getTemplateId());
        assertEquals(fullConfig(), original.getConfigSnapshotJson());
        verify(nodeMapper).selectCmsTopicNodeById(NODE_ID);
        verify(templateMapper, never()).selectCmsMemberTemplateById(any());
        verify(sessionMapper, never()).insert(any());
        verify(sessionMapper, never()).restartTerminalCas(any());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode originalContent() {
        com.fasterxml.jackson.databind.node.ObjectNode content = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        content.put("contentSnapshotVersion", 1).put("templateId", TEMPLATE_ID);
        content.putObject("nodePresentation").put("id", NODE_ID).put("topicId", TOPIC_ID).put("templateId", TEMPLATE_ID);
        content.putObject("templatePresentation").put("title", "原小游戏");
        return content;
    }

    @Test
    void newNodeGameUsesOriginalPublishedTemplateIdentityAfterAuthorTemplateDelete() throws Exception {
        ITopicRouteRuntimeService route = mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);
        when(route.nodeAnswers(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn(originalContent());
        when(route.nodeRuntimeConfig(MEMBER_ID, TOPIC_ID, NODE_ID, ACTIVITY_ID))
                .thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree(randomOnlyConfig()));
        when(route.canPlay(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn(true);
        when(route.playInstance(MEMBER_ID, TOPIC_ID, ACTIVITY_ID)).thenReturn("original-route");
        CmsTopicNode current = new CmsTopicNode(); current.setId(NODE_ID); current.setTopicId(TOPIC_ID); current.setTemplateId(999L);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(current);
        AtomicReference<GameRuntimeSession> inserted = new AtomicReference<>();
        when(sessionMapper.insert(any())).thenAnswer(invocation -> {
            GameRuntimeSession row = invocation.getArgument(0); row.setId(51L); inserted.set(row); return 1;
        });

        assertEquals(51L, service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null).get("sessionId"));
        assertEquals(Long.valueOf(TEMPLATE_ID), inserted.get().getTemplateId());
        assertEquals("original-route", inserted.get().getPlayInstanceId());
        assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().readTree(originalContent().toString()), new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(inserted.get().getStateJson()).path("originalNodeContent"));
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().readTree(inserted.get().getConfigSnapshotJson()).path("random").path("enabled").asBoolean());
        verify(nodeMapper).selectCmsTopicNodeById(NODE_ID);
        verify(templateMapper, never()).selectCmsMemberTemplateById(any());
    }

    @Test
    void incompleteOrForeignPublishedIdentityNeverUsesLiveAuthorBinding() {
        ITopicRouteRuntimeService route = mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);
        com.fasterxml.jackson.databind.node.ObjectNode content = originalContent();
        when(route.nodeAnswers(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn(content);
        content.remove("templateId");
        assertThrows(IllegalStateException.class, () -> service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null));
        content.put("templateId", TEMPLATE_ID);
        ((com.fasterxml.jackson.databind.node.ObjectNode) content.path("nodePresentation")).put("topicId", TOPIC_ID + 1L);
        assertThrows(IllegalStateException.class, () -> service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null));
        verify(templateMapper, never()).selectCmsMemberTemplateById(any());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void publishedIdentityWithoutOriginalConfigCannotStartFromCurrentTemplate() {
        ITopicRouteRuntimeService route = mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);
        when(route.nodeAnswers(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn(originalContent());
        assertThrows(IllegalStateException.class, () -> service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null));
        verify(templateMapper, never()).selectCmsMemberTemplateById(any());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void authorTemplateUnbindCannotBypassOriginalCompletionOrRewardCap() {
        GameRuntimeSession original = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(original);
        CmsTopicNode current = new CmsTopicNode(); current.setId(NODE_ID); current.setTopicId(TOPIC_ID);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(current);
        assertEquals("高级玩法尚未完成", assertThrows(ServiceException.class,
                () -> service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID)).getMessage());
        original.setConfigSnapshotJson("{\"schemaVersion\":1,\"scan\":{\"enabled\":true}}");
        assertTrue(service.rewardCappedToXpBadge(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
        assertFalse(service.rewardCappedToXpBadge(MEMBER_ID + 1L, ACTIVITY_ID, TOPIC_ID, NODE_ID));
        verify(sessionMapper, never()).updateStateCas(any());
        verify(templateMapper, never()).selectCmsMemberTemplateById(any());
    }

    @Test
    void defaultGameContentReadKeepsOriginalIdentityConfigAndDoesNotExposePrivateBodyInPublicState() throws Exception {
        GameRuntimeSession row=runningSession(randomOnlyConfig(), "{}");
        row.setPlayInstanceId("default");
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var state=json.createObjectNode(); state.putArray("draws");
        var content=originalContent(); content.put("questionAnswer","secret-original-answer");
        state.set("originalNodeContent",content); row.setStateJson(state.toString());
        when(sessionMapper.selectDefaultMemberSessionsByTopic(ACTIVITY_ID,TOPIC_ID,MEMBER_ID)).thenReturn(Arrays.asList(row));
        when(sessionMapper.selectAccessibleById(row.getId(),MEMBER_ID)).thenReturn(row);

        var first=service.queryDefaultNodeContents(MEMBER_ID,ACTIVITY_ID,TOPIC_ID);
        assertEquals(TEMPLATE_ID,first.path(String.valueOf(NODE_ID)).path("nodeAnswers").path(String.valueOf(NODE_ID)).path("templateId").asLong());
        assertEquals(json.readTree(randomOnlyConfig()),first.path(String.valueOf(NODE_ID)).path("nodeConfigs").path(String.valueOf(NODE_ID)));
        ((com.fasterxml.jackson.databind.node.ObjectNode)first.path(String.valueOf(NODE_ID)).path("nodeAnswers").path(String.valueOf(NODE_ID))).put("templateId",999L);
        assertEquals(TEMPLATE_ID,service.queryDefaultNodeContents(MEMBER_ID,ACTIVITY_ID,TOPIC_ID).path(String.valueOf(NODE_ID))
                .path("nodeAnswers").path(String.valueOf(NODE_ID)).path("templateId").asLong());
        Map<String,Object> view=ReflectionTestUtils.invokeMethod(service,"toView",row,MEMBER_ID);
        String publicJson=json.writeValueAsString(view);
        assertFalse(publicJson.contains("secret-original-answer")); assertFalse(publicJson.contains("originalNodeContent"));
        verify(sessionMapper,never()).insert(any()); verify(sessionMapper,never()).updateStateCas(any());
        verifyNoInteractions(templateMapper);
    }

    @Test
    void defaultGameLegacyHasExplicitOldContentAndCannotBorrowAnotherActorOrScene() {
        GameRuntimeSession row=runningSession(randomOnlyConfig(),"{}"); row.setPlayInstanceId("default");
        when(sessionMapper.selectDefaultMemberSessionsByTopic(ACTIVITY_ID,TOPIC_ID,MEMBER_ID)).thenReturn(Arrays.asList(row));
        var source=service.queryDefaultNodeContents(MEMBER_ID,ACTIVITY_ID,TOPIC_ID).path(String.valueOf(NODE_ID));
        assertTrue(source.path("legacyNodeContent").asBoolean()); assertFalse(source.has("nodeAnswers"));
        row.setActivityId(ACTIVITY_ID+1L);
        assertThrows(IllegalStateException.class,()->service.queryDefaultNodeContents(MEMBER_ID,ACTIVITY_ID,TOPIC_ID));
        row.setActivityId(ACTIVITY_ID); row.setOwnerId(MEMBER_ID+1L);
        assertThrows(IllegalStateException.class,()->service.queryDefaultNodeContents(MEMBER_ID,ACTIVITY_ID,TOPIC_ID));
        verify(sessionMapper,never()).insert(any()); verify(sessionMapper,never()).updateStateCas(any());
    }

    @Test
    void restartingAnOriginalGameRetainsPrivateContentWhileAuthorConfigCannotSupplyIt() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        GameRuntimeSession original=runningSession(randomOnlyConfig(),"{}"); original.setPlayInstanceId("default");
        original.setStatus(GameRuntimeSession.STATUS_FAILED);
        var state=json.createObjectNode(); state.set("originalNodeContent",originalContent());
        original.setStateJson(state.toString());
        when(sessionMapper.restartTerminalCas(any())).thenReturn(1);
        ReflectionTestUtils.invokeMethod(service,"restart",original,null,Collections.emptyList(),MEMBER_ID);
        assertEquals(json.readTree(originalContent().toString()),json.readTree(original.getStateJson()).path("originalNodeContent"));

        var authored=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(randomOnlyConfig());
        authored.set("originalNodeContent",originalContent());
        CmsMemberTemplate live=new CmsMemberTemplate(); live.setId(TEMPLATE_ID); live.setAdvancedConfigJson(authored.toString());
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(live);
        AtomicReference<GameRuntimeSession> inserted=new AtomicReference<>();
        when(sessionMapper.insert(any())).thenAnswer(call->{GameRuntimeSession row=call.getArgument(0);row.setId(61L);inserted.set(row);return 1;});
        service.start(MEMBER_ID,ACTIVITY_ID,TOPIC_ID,NODE_ID,null);
        assertFalse(json.readTree(inserted.get().getStateJson()).has("originalNodeContent"));
    }

    @Test
    void originalSnapshotDoesNotBypassCurrentNodeExistenceScopeGuard() {
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(null);
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID))
                .thenReturn(runningSession(fullConfig(), "{}"));
        assertThrows(IllegalArgumentException.class, () -> service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null));
        verify(sessionMapper, never()).insert(any());
        verify(sessionMapper, never()).restartTerminalCas(any());
    }

    @Test
    void teamSnapshotDoesNotBypassCurrentMembership() {
        GameRuntimeSession session = runningSession(multiplayerConfig(), "{}");
        session.setOwnerType(GameRuntimeSession.OWNER_TEAM);
        session.setOwnerId(88L);
        when(sessionMapper.selectTeamByMember(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);
        com.chengyinhub.business.domain.PlayTeam team = new com.chengyinhub.business.domain.PlayTeam();
        team.setId(88L);
        team.setOwnerType(2);
        team.setOwnerId(ACTIVITY_ID);
        team.setStatus(2);
        when(teamMapper.selectPlayTeamById(88L)).thenReturn(team);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, 99L));

        assertEquals("你不是该队伍成员", error.getMessage());
        verify(teamMapper).selectPlayTeamById(88L);
        verify(teamMapper, never()).selectPlayTeamById(99L);
        verify(sessionMapper, never()).restartTerminalCas(any());
    }

    @Test
    void resumeAndCompletionChooseSamePersonalSessionWhenHistoricalTeamSessionAlsoExists() {
        GameRuntimeSession personal = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        GameRuntimeSession team = runningSession(multiplayerConfig(), "{}");
        team.setId(52L);
        team.setOwnerType(GameRuntimeSession.OWNER_TEAM);
        team.setOwnerId(88L);
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID))
                .thenReturn(personal);
        when(sessionMapper.selectTeamByMember(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(team);

        Map<String, Object> view = service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, 88L);
        assertEquals(51L, view.get("sessionId"));
        assertEquals(GameRuntimeSession.OWNER_PLAYER, view.get("ownerType"));
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
        assertEquals("高级玩法尚未完成", error.getMessage());
        verify(teamMapper, never()).selectPlayTeamById(any());
    }

    @Test
    void startIgnoresTeamIdWhenMultiplayerIsDisabled() {
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(randomOnlyConfig()));
        AtomicReference<GameRuntimeSession> inserted = new AtomicReference<>();
        when(sessionMapper.insert(any())).thenAnswer(invocation -> {
            GameRuntimeSession row = invocation.getArgument(0);
            row.setId(51L);
            inserted.set(row);
            return 1;
        });

        Map<String, Object> state = service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, 88L);

        assertEquals(GameRuntimeSession.OWNER_PLAYER, inserted.get().getOwnerType());
        assertEquals(MEMBER_ID, inserted.get().getOwnerId());
        assertEquals(GameRuntimeSession.OWNER_PLAYER, state.get("ownerType"));
        verify(teamMapper, never()).selectPlayTeamById(any());
    }

    @Test
    void actRejectsIdempotencyKeyClaimedByAnotherSessionDuringInsertRace() {
        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey("shared-key"))
                .thenReturn(null, attemptLog(MEMBER_ID, 52L));
        when(attemptMapper.insertPlayAttemptLog(any()))
                .thenThrow(new DuplicateKeyException("duplicate idempotency key"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "shared-key", "DRAW", Collections.emptyMap()));

        assertEquals("幂等键已被占用", error.getMessage());
    }

    @Test
    void runtimeViewDoesNotExposeFullConfigOrOtherPlayersRole() {
        String config = "{\"schemaVersion\":1,\"multiplayer\":{\"enabled\":true,\"assignment\":\"LEADER\","
                + "\"minPlayers\":2,\"maxPlayers\":3,\"requiredTurns\":1,\"unitScore\":5,"
                + "\"roles\":[{\"id\":\"scout\",\"label\":\"侦察\",\"min\":1,\"max\":1},"
                + "{\"id\":\"smuggler\",\"label\":\"SECRET_OTHER\",\"min\":0,\"max\":1}],"
                + "\"turnOrder\":[\"scout\",\"smuggler\"]}}";
        GameRuntimeSession session = runningSession(config,
                "{\"multiplayer\":{\"roles\":{\"7\":\"scout\",\"8\":\"smuggler\"},"
                        + "\"members\":[{\"memberId\":7,\"name\":\"甲\"},{\"memberId\":8,\"name\":\"乙\"}],"
                        + "\"completedUnitIds\":[],\"turnIndex\":0,\"leaderMemberId\":8}}" );
        session.setOwnerType(GameRuntimeSession.OWNER_TEAM);
        session.setOwnerId(88L);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);

        Map<String, Object> view = service.state(MEMBER_ID, 51L);
        String projection = String.valueOf(view);

        assertTrue(projection.contains("myRole=scout"));
        assertEquals("侦察", ((Map<?, ?>) view.get("multiplayer")).get("myRoleLabel"));
        assertFalse(projection.contains("SECRET_OTHER"));
        assertFalse(projection.contains("roleAssignments"));
        assertFalse(projection.contains("turnOrder"));
    }

    // ── 小队玩法的准入契约(#813 已有 18 个 multiplayer 模板在跑,组队流程是既定设计)
    //    这两条锁的是「挡人的时候要说人话」:多人玩法确实需要队伍,但错误必须是
    //    可操作的指引,不能是静默卡死或语焉不详的失败 —— 后者玩家只会以为玩法坏了。

    @Test
    void multiplayerTemplateInSelfPlayTellsPlayerItNeedsAnActivity() {
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(multiplayerConfig()));
        // 先让自玩通行证这一关过掉,否则测的就是通行证闸,不是队伍闸
        CmsRegistration paid = new CmsRegistration();
        paid.setId(9000001L);paid.setMemberId(MEMBER_ID);paid.setOwnerType(1L);paid.setOwnerId(TOPIC_ID);
        paid.setPaymentStatus(2);paid.setRegistrationStatus(2);paid.setPurchaseKind(2);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(9000001L)).thenReturn(paid);
        paid.setExpiresAt(new Date(NOW + 86_400_000L));
        when(registrationMapper.selectCmsRegistrationList(any())).thenReturn(java.util.Arrays.asList(paid));

        // 自玩 activityId=0:多人玩法没有场次概念,必须明确拒绝而不是建出一个永远完不成的会话
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.start(MEMBER_ID, 0L, TOPIC_ID, NODE_ID, null));
        assertEquals("队伍玩法仅支持活动场次", error.getMessage());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void multiplayerTemplateWithoutTeamPointsPlayerAtJoiningOne() {
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(multiplayerConfig()));
        when(teamMapper.selectMyTeams(MEMBER_ID)).thenReturn(java.util.Collections.emptyList());

        // 活动内但没队伍:同样必须挡在 start,且给出「去组队」这个可执行的下一步
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null));
        assertEquals("请先加入本场队伍", error.getMessage());
        verify(sessionMapper, never()).insert(any());
    }

    /** 形状对齐 #813 已上线的 18 个多人模板:ROLE_BASED / 2–4 人 / AUTO / 2 角色。 */
    private String multiplayerConfig() {
        return "{\"schemaVersion\":1,\"multiplayer\":{\"enabled\":true,\"mode\":\"ROLE_BASED\","
                + "\"minPlayers\":2,\"maxPlayers\":4,\"assignment\":\"AUTO\",\"requiredTurns\":2,"
                + "\"roles\":[{\"id\":\"pacer\",\"label\":\"领跑\",\"min\":1,\"max\":2},"
                + "{\"id\":\"closer\",\"label\":\"收尾\",\"min\":1,\"max\":2}],"
                + "\"turnOrder\":[\"pacer\",\"closer\"]}}";
    }

    private CmsMemberTemplate template(String config) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE_ID);
        template.setAdvancedConfigJson(config);
        return template;
    }

    @Test
    void themeReplayCanReuseClientActionKeyWithoutReusingOldDraw() throws Exception {
        ITopicRouteRuntimeService route = mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);
        when(route.nodeRuntimeConfig(MEMBER_ID,TOPIC_ID,NODE_ID,ACTIVITY_ID))
                .thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree(randomOnlyConfig()));
        when(route.canPlay(MEMBER_ID,TOPIC_ID,ACTIVITY_ID,NODE_ID)).thenReturn(true);
        when(route.playInstance(MEMBER_ID,TOPIC_ID,ACTIVITY_ID)).thenReturn("default","replay:80");
        GameRuntimeSession first=runningSession(randomOnlyConfig(),"{\"draws\":[]}");
        first.setPlayInstanceId("default");
        GameRuntimeSession second=runningSession(randomOnlyConfig(),"{\"draws\":[]}");
        second.setId(52L); second.setPlayInstanceId("replay:80");
        when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(first);
        when(sessionMapper.selectAccessibleById(52L,MEMBER_ID)).thenReturn(second);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        Map<String,PlayAttemptLog> receipts=new java.util.HashMap<>();
        when(attemptMapper.selectByIdempotencyKey(any())).thenAnswer(i -> receipts.get(i.getArgument(0)));
        when(attemptMapper.insertPlayAttemptLog(any())).thenAnswer(i -> {
            PlayAttemptLog log=i.getArgument(0); receipts.put(log.getIdempotencyKey(),log); return 1;
        });
        assertEquals(1,((java.util.List<?>)service.act(MEMBER_ID,51L,0,"draw-1","DRAW",Collections.emptyMap()).get("draws")).size());
        assertEquals(1,((java.util.List<?>)service.act(MEMBER_ID,52L,0,"draw-1","DRAW",Collections.emptyMap()).get("draws")).size());
        assertEquals(2,receipts.size());
        for(String key:receipts.keySet()) assertTrue(key.length()<=64);
    }

    @Test
    void completionStillUsesFrozenThemeMechanicAfterLiveConfigCleared() throws Exception {
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);
        when(route.nodeRuntimeConfig(MEMBER_ID,TOPIC_ID,NODE_ID,ACTIVITY_ID))
                .thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree(randomOnlyConfig()));
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(null));
        when(route.canPlay(MEMBER_ID,TOPIC_ID,ACTIVITY_ID,NODE_ID)).thenReturn(true);
        assertThrows(ServiceException.class,()->service.completeGate(MEMBER_ID,ACTIVITY_ID,TOPIC_ID,NODE_ID));
    }

    private GameRuntimeSession runningSession(String config, String state) {
        GameRuntimeSession session = new GameRuntimeSession();
        session.setId(51L);
        session.setActivityId(ACTIVITY_ID);
        session.setTopicId(TOPIC_ID);
        session.setNodeId(NODE_ID);
        session.setTemplateId(TEMPLATE_ID);
        session.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        session.setOwnerId(MEMBER_ID);
        session.setConfigSnapshotJson(config);
        session.setStateJson(state);
        session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setVersion(0);
        session.setStartedAt(new Date(NOW));
        return session;
    }

    private PlayAttemptLog attemptLog(Long memberId, Long sessionId) {
        PlayAttemptLog log = new PlayAttemptLog();
        log.setMemberId(memberId);
        log.setAttemptType(8);
        log.setResultJson("{\"sessionId\":" + sessionId + "}");
        return log;
    }

    @Test
    void flipCoinKeepsTheFirstSideOnRepeat() {
        GameRuntimeSession session = runningSession(coinOnlyConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> first = service.act(MEMBER_ID, 51L, 0, "coin-1", "FLIP_COIN",
                Collections.emptyMap());
        Map<String, Object> coin = playKit(first, "coinFlip");
        // 桩随机数恒返回 0 —— 0 就是正面
        assertEquals("HEADS", coin.get("side"));
        assertEquals("这杯店家请", coin.get("action"));
        assertTrue((Boolean) coin.get("awarded"));

        // 「抛一次,认结果」:再抛一次拿到的还得是同一面,不然人可以抛到满意为止
        Map<String, Object> again = service.act(MEMBER_ID, 51L, 1, "coin-2", "FLIP_COIN",
                Collections.emptyMap());
        Map<String, Object> coinAgain = playKit(again, "coinFlip");
        assertEquals("HEADS", coinAgain.get("side"));
    }

    @Test
    void flipCoinRejectsWhenNotEnabled() {
        GameRuntimeSession session = runningSession(randomOnlyConfig(), "{\"draws\":[]}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "coin-off", "FLIP_COIN", Collections.emptyMap()));

        assertEquals("该玩法未开启抛硬币", error.getMessage());
        verify(sessionMapper, never()).updateStateCas(any());
    }

    @Test
    void rollDiceReturnsServerPipsAndKeepsThem() {
        GameRuntimeSession session = runningSession(diceOnlyConfig(2), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> view = service.act(MEMBER_ID, 51L, 0, "dice-1", "ROLL_DICE",
                Collections.emptyMap());
        Map<String, Object> dice = playKit(view, "diceRoll");
        // 桩随机数恒返回 0 → 每颗都是 1 点,两颗和为 2
        assertEquals(2, ((Number) dice.get("sum")).intValue());
        assertEquals(2, ((java.util.List<?>) dice.get("pips")).size());
        // 和为 2 → 查第 2 面
        assertEquals("换一个座位坐", dice.get("action"));
    }

    @Test
    void d20NaturalOneUsesModifierAndReturnsTheSameResultOnRetry() {
        String config = "{\"schemaVersion\":1,\"diceRoll\":{\"enabled\":true,\"mode\":\"d20\","
                + "\"dc\":13,\"modifier\":12,\"rollMode\":\"normal\","
                + "\"successText\":\"发现暗门\",\"failText\":\"继续寻找\"}}";
        GameRuntimeSession session = runningSession(config, "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(config));
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> {
            assertEquals(20, bound);
            return 0;
        });
        Map<String, Object> first = service.act(MEMBER_ID, 51L, 0, "d20-1", "ROLL_DICE",
                Collections.singletonMap("success", false));
        Map<String, Object> dice = playKit(first, "diceRoll");
        assertEquals(Boolean.TRUE, dice.get("success"), "自然 1 + 12 = 13，达到 DC 即成功");
        assertEquals(13, dice.get("total"));
        assertEquals("发现暗门", dice.get("action"));
        assertFalse(dice.containsKey("failText"), "未发生的结果不下发");
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> {
            throw new AssertionError("重复请求不能再掷骰");
        });
        Map<String, Object> again = service.act(MEMBER_ID, 51L, 1, "d20-2", "ROLL_DICE",
                Collections.emptyMap());
        assertEquals(dice, playKit(again, "diceRoll"));
        assertEquals("D20_SUCCESS", service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
    }

    @Test
    void d20ModesRespectThresholdAndFailureStillCompletesWithItsOutcome() {
        String[] modes = { "normal", "advantage", "disadvantage" };
        for (String mode : modes) {
            String config = "{\"schemaVersion\":1,\"diceRoll\":{\"enabled\":true,\"mode\":\"d20\","
                    + "\"dc\":20,\"modifier\":-1,\"rollMode\":\"" + mode + "\","
                    + "\"successText\":\"找到线索\",\"failText\":\"继续寻找\"}}";
            GameRuntimeSession session = runningSession(config, "{}");
            when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
            when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);
            when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(config));
            when(sessionMapper.updateStateCas(any())).thenReturn(1);
            assertThrows(ServiceException.class, () -> service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
            java.util.ArrayDeque<Integer> dice = new java.util.ArrayDeque<>(java.util.Arrays.asList(19, 4));
            ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> {
                assertEquals(20, bound);
                return dice.removeFirst();
            });
            Map<String, Object> result = playKit(service.act(MEMBER_ID, 51L, 0, "d20-" + mode,
                    "ROLL_DICE", Collections.singletonMap("modifier", 100)), "diceRoll");
            assertEquals("disadvantage".equals(mode) ? 5 : 20, result.get("kept"));
            assertEquals(Boolean.FALSE, result.get("success"), "自然 20 不自动成功，客户端加值无效");
            assertEquals("继续寻找", result.get("action"));
            assertEquals("D20_FAILURE", service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
        }
    }

    @Test
    void reactionRejectsAFalseStart() {
        // 单轮 90ms 比人的极限还快 —— 那不是反应,是提前按下去的
        GameRuntimeSession session = runningSession(reactionConfig(),
                "{\"reaction\":{\"startedAtMs\":" + (NOW - 9000) + "}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roundsMs", java.util.Arrays.asList(300, 90, 320));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "rx-1", "SUBMIT_REACTION", payload));

        assertEquals("有一轮抢跑了", error.getMessage());
        verify(sessionMapper, never()).updateStateCas(any());
    }

    @Test
    void reactionRejectsScoresLongerThanTheRealElapsedTime() {
        // 服务端只量到 1 秒,却报上来三轮共 2.4 秒 —— 这些数不可能是这一局产生的
        GameRuntimeSession session = runningSession(reactionConfig(),
                "{\"reaction\":{\"startedAtMs\":" + (NOW - 1000) + "}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roundsMs", java.util.Arrays.asList(800, 800, 800));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "rx-2", "SUBMIT_REACTION", payload));

        assertEquals("成绩与用时对不上", error.getMessage());
    }

    @Test
    void reactionRejectsRoundCountDifferentFromFrozenConfig() {
        GameRuntimeSession session = runningSession(reactionConfig(),
                "{\"reaction\":{\"startedAtMs\":" + (NOW - 9000) + "}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        for (int count : new int[] {1, 2, 4}) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("roundsMs", Collections.nCopies(count, 200));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.act(MEMBER_ID, 51L, 0, "rx-count-" + count, "SUBMIT_REACTION", payload));
            assertEquals("请完成配置的全部轮次后提交", error.getMessage());
        }
        verify(sessionMapper, never()).updateStateCas(any());
        verify(attemptMapper, never()).insertPlayAttemptLog(any());
    }

    @Test
    void reactionAcceptsEveryConfiguredRoundCount() {
        for (int count = 1; count <= 10; count++) {
            GameRuntimeSession session = runningSession(
                    reactionConfig().replace("\"rounds\":3", "\"rounds\":" + count),
                    "{\"reaction\":{\"startedAtMs\":" + (NOW - 9000) + "}}");
            when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
            when(sessionMapper.updateStateCas(any())).thenReturn(1);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("roundsMs", Collections.nCopies(count, 200));
            Map<String, Object> result = playKit(
                    service.act(MEMBER_ID, 51L, 0, "rx-valid-" + count, "SUBMIT_REACTION", payload), "reaction");
            assertTrue((Boolean) result.get("submitted"));
            assertTrue((Boolean) result.get("passed"));
            assertTrue((Boolean) result.get("awarded"));
        }
    }

    @Test
    void reactionPassesOnFastestRoundWhilePreservingActualAverage() {
        GameRuntimeSession session = runningSession(reactionConfig(),
                "{\"reaction\":{\"startedAtMs\":" + (NOW - 9000) + "}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roundsMs", java.util.Arrays.asList(200, 500, 500));

        Map<String, Object> view = service.act(MEMBER_ID, 51L, 0, "rx-fastest", "SUBMIT_REACTION", payload);
        Map<String, Object> rx = playKit(view, "reaction");
        assertTrue((Boolean) rx.get("passed"));
        assertTrue((Boolean) rx.get("awarded"));
        assertEquals(200L, ((Number) rx.get("bestMs")).longValue());
        assertEquals(400L, ((Number) rx.get("averageMs")).longValue());
        assertEquals(rx, playKit(service.state(MEMBER_ID, 51L), "reaction"));
    }

    @Test
    void reactionUsesValidatedRoundMinimumAtBothBoundariesAndIgnoresClientVerdict() {
        for (int fastest : new int[] {120, 320, 321}) {
            GameRuntimeSession session = runningSession(reactionConfig(),
                    "{\"reaction\":{\"startedAtMs\":" + (NOW - 9000) + "}}");
            when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
            when(sessionMapper.updateStateCas(any())).thenReturn(1);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("roundsMs", java.util.Arrays.asList(500, fastest, 500));
            payload.put("bestMs", 1);
            payload.put("passed", fastest > 320);
            Map<String, Object> rx = playKit(service.act(MEMBER_ID, 51L, 0,
                    "rx-boundary-" + fastest, "SUBMIT_REACTION", payload), "reaction");
            assertEquals((long) fastest, ((Number) rx.get("bestMs")).longValue());
            assertEquals(fastest <= 320, rx.get("passed"));
            assertEquals(fastest <= 320, rx.get("awarded"));
            assertTrue((Boolean) rx.get("submitted"));
        }
    }

    @Test
    void reactionRejectsSubmissionBeforeStartWithoutSavingResult() {
        GameRuntimeSession session = runningSession(reactionConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        assertThrows(IllegalStateException.class, () -> service.act(MEMBER_ID, 51L, 0,
                "rx-not-started", "SUBMIT_REACTION",
                Collections.singletonMap("roundsMs", java.util.Arrays.asList(200, 500, 500))));
        assertEquals("{}", session.getStateJson());
        verify(sessionMapper, never()).updateStateCas(any());
        verify(attemptMapper, never()).insertPlayAttemptLog(any());
    }

    @Test
    void reactionInvalidRoundCannotHideBehindAnotherFastRound() {
        for (Object invalid : new Object[] {90, -1, 200.5, "bad"}) {
            GameRuntimeSession session = runningSession(reactionConfig(),
                    "{\"reaction\":{\"startedAtMs\":" + (NOW - 9000) + "}}");
            String previous = session.getStateJson();
            when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
            assertThrows(IllegalArgumentException.class, () -> service.act(MEMBER_ID, 51L, 0,
                    "rx-invalid-" + invalid, "SUBMIT_REACTION",
                    Collections.singletonMap("roundsMs", java.util.Arrays.asList(200, invalid, 500))));
            assertEquals(previous, session.getStateJson());
        }
        verify(sessionMapper, never()).updateStateCas(any());
        verify(attemptMapper, never()).insertPlayAttemptLog(any());
    }

    @Test
    void reactionReplaysOriginalReceiptAndRejectsFreshKeyAfterSubmission() {
        GameRuntimeSession session = runningSession(reactionConfig(),
                "{\"reaction\":{\"startedAtMs\":" + (NOW - 9000) + "}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        AtomicReference<PlayAttemptLog> receipt = new AtomicReference<>();
        when(attemptMapper.insertPlayAttemptLog(any())).thenAnswer(invocation -> {
            receipt.set(invocation.getArgument(0));
            return 1;
        });
        Map<String, Object> first = service.act(MEMBER_ID, 51L, 0, "rx-once", "SUBMIT_REACTION",
                Collections.singletonMap("roundsMs", java.util.Arrays.asList(200, 500, 500)));
        String saved = session.getStateJson();
        when(attemptMapper.selectByIdempotencyKey("rx-once")).thenReturn(receipt.get());
        Map<String, Object> replay = service.act(MEMBER_ID, 51L, 0, "rx-once", "SUBMIT_REACTION",
                Collections.singletonMap("roundsMs", java.util.Arrays.asList(200, 500, 500)));
        assertEquals(first.toString(), replay.toString());
        assertThrows(ServiceException.class, () -> service.act(MEMBER_ID, 51L, 0, "rx-once", "SUBMIT_REACTION",
                Collections.singletonMap("roundsMs", java.util.Arrays.asList(900, 900, 900))));
        assertThrows(IllegalStateException.class, () -> service.act(MEMBER_ID, 51L, 1,
                "rx-another-key", "SUBMIT_REACTION",
                Collections.singletonMap("roundsMs", java.util.Arrays.asList(120, 120, 120))));
        assertEquals(saved, session.getStateJson());
        verify(sessionMapper).updateStateCas(any());
        verify(attemptMapper).insertPlayAttemptLog(any());
    }

    @Test
    void reactionLegacyResultReadDoesNotInventBestAndUnboundReplayIsRejected() {
        for (boolean passed : new boolean[] {false, true}) {
            String legacy = "{\"reaction\":{\"startedAtMs\":" + (NOW - 9000)
                    + ",\"submitted\":true,\"averageMs\":400,\"passed\":" + passed
                    + ",\"awarded\":" + passed + "}}";
            GameRuntimeSession session = runningSession(reactionConfig(), legacy);
            when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
            Map<String, Object> rx = playKit(service.state(MEMBER_ID, 51L), "reaction");
            assertFalse(rx.containsKey("bestMs"));
            assertEquals(passed, rx.get("passed"));
            assertEquals(passed, rx.get("awarded"));
            assertEquals(400L, ((Number) rx.get("averageMs")).longValue());
            PlayAttemptLog receipt = attemptLog(MEMBER_ID, 51L);
            receipt.setResultJson("{\"sessionId\":51,\"playKit\":{\"reaction\":"
                    + legacy.substring("{\"reaction\":".length(), legacy.length() - 1) + "}}");
            String key = "rx-legacy-" + passed;
            when(attemptMapper.selectByIdempotencyKey(key)).thenReturn(receipt);
            assertThrows(ServiceException.class, () ->
                    service.act(MEMBER_ID, 51L, 0, key, "SUBMIT_REACTION", Collections.emptyMap()));
            assertThrows(IllegalStateException.class, () -> service.act(MEMBER_ID, 51L, 0,
                    "rx-legacy-new-" + passed, "SUBMIT_REACTION",
                    Collections.singletonMap("roundsMs", java.util.Arrays.asList(120, 120, 120))));
            assertEquals(legacy, session.getStateJson());
        }
        verify(sessionMapper, never()).updateStateCas(any());
        verify(attemptMapper, never()).insertPlayAttemptLog(any());
    }

    @Test
    void reactionPassesWhenAverageBeatsTheGoal() {
        GameRuntimeSession session = runningSession(reactionConfig(),
                "{\"reaction\":{\"startedAtMs\":" + (NOW - 9000) + "}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roundsMs", java.util.Arrays.asList(300, 280, 320));

        Map<String, Object> view = service.act(MEMBER_ID, 51L, 0, "rx-3", "SUBMIT_REACTION", payload);
        Map<String, Object> rx = playKit(view, "reaction");
        assertEquals(300L, ((Number) rx.get("averageMs")).longValue());
        assertTrue((Boolean) rx.get("passed"));
        assertTrue((Boolean) rx.get("awarded"));
    }

    @Test
    void ballShakeRejectsMoreHitsThanTimeAllows() {
        // 服务端只量到 1 秒,每秒最多 12 下 —— 报 90 下是不可能的
        GameRuntimeSession session = runningSession(ballShakeConfig(),
                "{\"ballShake\":{\"startedAtMs\":" + (NOW - 1000) + "}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hits", 90);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "bl-1", "SUBMIT_BALL_SHAKE", payload));

        assertEquals("撞击次数与用时对不上", error.getMessage());
    }

    @Test
    void countdownRefusesToFinishEarly() {
        // 倒计时不接受客户端报数,等没等够服务端自己算 —— 才过 3 秒就想收,不行
        GameRuntimeSession session = runningSession(countdownConfig(),
                "{\"countdown\":{\"startedAtMs\":" + (NOW - 3000) + "}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "cd-1", "SUBMIT_COUNTDOWN", Collections.emptyMap()));

        assertEquals("还没到点", error.getMessage());
    }

    @Test
    void countdownCompletesOnceTheClockRanOut() {
        GameRuntimeSession session = runningSession(countdownConfig(),
                "{\"countdown\":{\"startedAtMs\":" + (NOW - 31000) + "}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> view = service.act(MEMBER_ID, 51L, 0, "cd-2", "SUBMIT_COUNTDOWN",
                Collections.emptyMap());
        Map<String, Object> cd = playKit(view, "countdown");
        assertTrue((Boolean) cd.get("passed"));
        assertTrue((Boolean) cd.get("awarded"));
    }

    @Test
    void challengeRefusesToSubmitWithoutStarting() {
        // 没报开始就交成绩 = 没有可比对的时间基准,只能拒
        GameRuntimeSession session = runningSession(quietConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("heldMs", 15000);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "qt-1", "SUBMIT_QUIET_HOLD", payload));

        assertEquals("安静挑战还没开始", error.getMessage());
    }

    // ==================== 喊一嗓子 shout(现场感契约 §4) ====================
    //
    // 与安静挑战同一条复核线(先开表、按服务器时间验 heldMs 没超发),判定反向:
    // 那边过线即死,这边掉线即归零 —— 但归零发生在客户端,服务端只看报上来的毫秒数。

    private String shoutConfig() {
        return "{\"schemaVersion\":1,\"shout\":{\"enabled\":true,\"kicker\":\"一起喊,让这儿听见\","
                + "\"seconds\":5,\"xp\":10}}";
    }

    @Test
    void shoutTargetIsDeliveredAtStartBecauseItIsThePrompt() {
        GameRuntimeSession session = runningSession(shoutConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);

        Map<String, Object> sh = playKit(service.state(MEMBER_ID, 51L), "shout");
        assertEquals(5, ((Number) sh.get("seconds")).intValue());
        assertEquals("一起喊,让这儿听见", sh.get("kicker"));
        assertEquals(10, ((Number) sh.get("xp")).intValue());
        assertEquals(Boolean.FALSE, sh.get("submitted"));
    }

    @Test
    void unconfiguredShoutDoesNotAppearInTheKitAtAll() {
        // 负控素材:没开喊一嗓子的会话,回包里不许凭空多出一个 shout 段
        GameRuntimeSession session = runningSession(quietConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);

        assertNull(playKit(service.state(MEMBER_ID, 51L), "shout"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("heldMs", 5200);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "sh-off", "SUBMIT_SHOUT", payload));
        assertEquals("该玩法未开启喊一嗓子", error.getMessage());
    }

    @Test
    void shoutRefusesToSubmitWithoutStarting() {
        GameRuntimeSession session = runningSession(shoutConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("heldMs", 5200);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "sh-1", "SUBMIT_SHOUT", payload));
        assertEquals("喊一嗓子还没开始", error.getMessage());
    }

    @Test
    void shoutRejectsHeldLongerThanTheRealElapsedTime() {
        // 服务器这边只过了 6 秒,却报来 20 秒的「连续过线」 —— 不可能,拒
        GameRuntimeSession session = runningSession(shoutConfig(),
                "{\"shout\":{\"startedAtMs\":" + (NOW - 6000) + "}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("heldMs", 20000);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "sh-2", "SUBMIT_SHOUT", payload));
        assertEquals("喊的时长与用时对不上", error.getMessage());
    }

    @Test
    void shoutPassesOnSustainedLoudnessAndAwardsOnce() {
        GameRuntimeSession session = runningSession(shoutConfig(),
                "{\"shout\":{\"startedAtMs\":" + (NOW - 8000) + "}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("heldMs", 5200);
        Map<String, Object> view = service.act(MEMBER_ID, 51L, 0, "sh-3", "SUBMIT_SHOUT", payload);

        Map<String, Object> sh = playKit(view, "shout");
        assertEquals(Boolean.TRUE, sh.get("submitted"));
        assertEquals(Boolean.TRUE, sh.get("passed"));
        assertEquals(5200L, ((Number) sh.get("heldMs")).longValue());
        // 挑战类的分不在提交那一刻入账:这里只落 awarded 旗,结算时按旗加 xp(与安静挑战同一口径)
        assertEquals(Boolean.TRUE, sh.get("awarded"), "喊满该落章一次");

        // 交过一次就不再收:重交既不改判也不重复发分
        Map<String, Object> again = new LinkedHashMap<>();
        again.put("heldMs", 3000);
        IllegalStateException dup = assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, 51L, 1, "sh-4", "SUBMIT_SHOUT", again));
        assertEquals("这一局已经交过了", dup.getMessage());
    }

    @Test
    void shoutBelowTargetRecordsFailureWithoutAward() {
        // 没喊够也记账:submitted=true 放行通关闸(玩过这一屏了),passed=false 一分不给
        GameRuntimeSession session = runningSession(shoutConfig(),
                "{\"shout\":{\"startedAtMs\":" + (NOW - 8000) + "}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("heldMs", 3000);
        Map<String, Object> sh = playKit(
                service.act(MEMBER_ID, 51L, 0, "sh-5", "SUBMIT_SHOUT", payload), "shout");

        assertEquals(Boolean.TRUE, sh.get("submitted"));
        assertEquals(Boolean.FALSE, sh.get("passed"));
        assertEquals(Boolean.FALSE, sh.get("awarded"), "没喊够不该落章");
        assertNull(session.getScore(), "没喊够不该有任何分");
    }

    @Test
    void shoutAloneGatesTheNodeUntilSubmitted() {
        // 漏了 mechanicsReady 数组里 shout 那一行 = 只配喊一嗓子的节点开局即可打卡,玩法静默不存在
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(shoutConfig()));
        GameRuntimeSession session = runningSession(shoutConfig(), "{}");
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
        assertEquals("高级玩法尚未完成", error.getMessage());
    }

    @Test
    void legacyBranchStartsChoosesAndCompletesWithStableOutcome() {
        String[] configs = {branchOnlyConfig().replace("DONE", "finish_a"),
                branchOnlyConfig().replace("\"outcomeCode\":\"DONE\",", "")};
        String[] codes = {"finish_a", "COMPLETED"};
        for (int i = 0; i < configs.length; i++) {
            when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(configs[i]));
            AtomicReference<GameRuntimeSession> inserted = new AtomicReference<>();
            doAnswer(invocation -> {
                GameRuntimeSession row = invocation.getArgument(0);
                row.setId(51L); inserted.set(row); return 1;
            }).when(sessionMapper).insert(any());
            service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null);
            GameRuntimeSession session = inserted.get();
            assertNotNull(session);
            when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
            when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);
            when(sessionMapper.updateStateCas(any())).thenReturn(1);
            Map<String, Object> view = service.act(MEMBER_ID, 51L, 0, "legacy-branch-" + i,
                    "CHOOSE", Collections.singletonMap("optionId", "go"));
            assertEquals("end", ((Map<?, ?>) view.get("branch")).get("currentStepId"));
            assertEquals(codes[i], service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
            assertEquals(codes[i], service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID), "完成回读不改码");
            when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(null);
        }
    }

    @Test
    void legacyFrozenBranchCompletesWithoutRewritingSnapshotOrReadingLiveTemplate() {
        String missing = branchOnlyConfig().replace("\"outcomeCode\":\"DONE\",", "");
        String[] configs = {missing, missing.replace("\"terminal\":true", "\"terminal\":true,\"outcomeCode\":null"),
                missing.replace("\"options\":[]}]", "\"options\":[]},{\"id\":\"other\",\"terminal\":true,\"options\":[]}]")};
        String[] codes = {"COMPLETED", "COMPLETED", "end"};
        for (int i = 0; i < configs.length; i++) {
            GameRuntimeSession session = runningSession(configs[i], "{\"branch\":{\"currentStepId\":\"start\"}}");
            when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);
            when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
            when(sessionMapper.updateStateCas(any())).thenReturn(1);
            assertEquals(51L, service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null).get("sessionId"));
            service.act(MEMBER_ID, 51L, 0, "legacy-frozen-" + i, "CHOOSE", Collections.singletonMap("optionId", "go"));
            assertEquals(codes[i], service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
            assertEquals(codes[i], service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
            assertEquals(configs[i], session.getConfigSnapshotJson(), "兼容读取不改写冻结规则");
        }
        verify(templateMapper, never()).selectCmsMemberTemplateById(any());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void frozenBranchExplicitEmptyOutcomeStillRefused() {
        GameRuntimeSession session = runningSession(branchOnlyConfig().replace("DONE", " "),
                "{\"branch\":{\"currentStepId\":\"end\"}}");
        session.setStatus(GameRuntimeSession.STATUS_COMPLETED);
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);
        assertThrows(ServiceException.class, () -> service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
        verify(sessionMapper, never()).updateStateCas(any());
    }

    // ==================== R3 三个新玩法:排序 / 连线 / 分类 ====================

    private String sortConfig() {
        return "{\"schemaVersion\":1,\"sort\":{\"enabled\":true,\"prompt\":\"按苦味从轻到重排\","
                + "\"xp\":25,\"maxAttempts\":2,\"items\":[{\"id\":\"a\",\"label\":\"摩卡\"},"
                + "{\"id\":\"b\",\"label\":\"拿铁\"},{\"id\":\"c\",\"label\":\"浓缩\"}],"
                + "\"answerOrder\":[\"c\",\"a\",\"b\"]}}";
    }

    @Test
    void sortAwardsXpOnceWhenTheOrderIsRight() {
        GameRuntimeSession session = runningSession(sortConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        playKit(service.act(MEMBER_ID, 51L, 0, "sort-xp-wrong", "SUBMIT_SORT",
                orderPayload("a", "c", "b")), "sort");
        assertNull(session.getScore(), "排错不给分");

        playKit(service.act(MEMBER_ID, 51L, 1, "sort-xp-ok", "SUBMIT_SORT",
                orderPayload("c", "a", "b")), "sort");
        assertEquals(25, session.getScore().intValue(), "排对按 xp 加一次分");
    }

    @Test
    void sortPassesOnlyWhenEveryPositionMatches() {
        GameRuntimeSession session = runningSession(sortConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> sort = playKit(service.act(MEMBER_ID, 51L, 0, "sort-ok", "SUBMIT_SORT",
                orderPayload("c", "a", "b")), "sort");

        assertEquals(Boolean.TRUE, sort.get("passed"));
        assertEquals(Boolean.TRUE, sort.get("finished"));
        assertEquals(Boolean.TRUE, sort.get("lastCorrect"));
        // 回执里给玩家的自己的顺序,但绝不给正确的那个
        assertEquals(java.util.Arrays.asList("c", "a", "b"), sort.get("lastOrder"));
        assertFalse(String.valueOf(session.getStateJson()).contains("answerOrder"));
        assertFalse(String.valueOf(sort).contains("answerOrder"));
    }

    @Test
    void sortRejectsAnOrderWithOnePositionWrong() {
        GameRuntimeSession session = runningSession(sortConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> sort = playKit(service.act(MEMBER_ID, 51L, 0, "sort-wrong", "SUBMIT_SORT",
                orderPayload("a", "c", "b")), "sort");

        assertEquals(Boolean.FALSE, sort.get("lastCorrect"));
        assertEquals(Boolean.FALSE, sort.get("passed"));
        assertEquals(Boolean.FALSE, sort.get("finished"));
        assertEquals(1, ((Number) sort.get("attempts")).intValue());
    }

    @Test
    void sortFinishesTheRoundOnceAttemptsRunOut() {
        GameRuntimeSession session = runningSession(sortConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        playKit(service.act(MEMBER_ID, 51L, 0, "sort-a", "SUBMIT_SORT", orderPayload("a", "c", "b")), "sort");
        Map<String, Object> second = playKit(service.act(MEMBER_ID, 51L, 1, "sort-b", "SUBMIT_SORT",
                orderPayload("a", "b", "c")), "sort");

        assertEquals(Boolean.TRUE, second.get("finished"));
        assertEquals(Boolean.FALSE, second.get("passed"));
        // 收摊之后再排直接拒,不会靠重排刷出"排对了"
        assertThrows(IllegalStateException.class, () -> service.act(MEMBER_ID, 51L, 2, "sort-c",
                "SUBMIT_SORT", orderPayload("c", "a", "b")));
    }

    @Test
    void sortRejectsIncompleteDuplicateOrUnknownOrders() {
        for (Map<String, Object> payload : java.util.Arrays.asList(
                orderPayload("c", "a"), orderPayload("c", "a", "a"), orderPayload("c", "a", "ghost"))) {
            GameRuntimeSession session = runningSession(sortConfig(), "{}");
            when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
            assertThrows(IllegalArgumentException.class, () -> service.act(MEMBER_ID, 51L, 0,
                    "sort-bad-" + payload.hashCode(), "SUBMIT_SORT", payload));
            assertEquals("{}", session.getStateJson());
        }
        verify(sessionMapper, never()).updateStateCas(any());
    }

    private Map<String, Object> orderPayload(String... ids) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order", java.util.Arrays.asList(ids));
        return payload;
    }

    private com.chengyinhub.business.service.IGameSessionRuntimeService sharedRuntime() {
        com.chengyinhub.business.service.IGameSessionRuntimeService shared = mock(com.chengyinhub.business.service.IGameSessionRuntimeService.class);
        com.fasterxml.jackson.databind.node.ObjectNode snapshot = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("teamId", 88).put("gameSessionId", 50);
        when(shared.sharedRouteSnapshot(any(), any(), any(), any())).thenReturn(snapshot);
        ReflectionTestUtils.setField(service, "gameSessionRuntimeService", shared);
        return shared;
    }

    @Test void sharedTeamVerdictUsesServerWholeMechanicsAndSkipsPersonalChain() {
        com.chengyinhub.business.service.IGameSessionRuntimeService shared = sharedRuntime();
        ITopicRouteRuntimeService personal = mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", personal);
        when(personal.canPlay(any(),any(),any(),any())).thenReturn(true);
        GameRuntimeSession session = runningSession(matchConfig().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"dailySign\":{\"enabled\":true},\"timer\":{\"enabled\":true,\"durationSeconds\":300,\"timeoutResult\":\"FAILED\"}"), "{\"runtimeAuthorityEpoch\":3}");
        session.setPlayInstanceId("game:50");session.setOwnerType(GameRuntimeSession.OWNER_TEAM);session.setOwnerId(88L);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);when(sessionMapper.updateStateCas(any())).thenReturn(1);
        service.act(MEMBER_ID,51L,0,"wrong","SUBMIT_MATCH",pairsPayload(new String[]{"l1","r1"},new String[]{"l2","r2"}));
        verify(shared).recordSharedRouteResult(org.mockito.ArgumentMatchers.eq(MEMBER_ID),org.mockito.ArgumentMatchers.eq(TOPIC_ID),org.mockito.ArgumentMatchers.eq(ACTIVITY_ID),org.mockito.ArgumentMatchers.eq(NODE_ID),org.mockito.ArgumentMatchers.eq("game:50"),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(false),any(),org.mockito.ArgumentMatchers.eq(3));
        service.act(MEMBER_ID,51L,1,"right","SUBMIT_MATCH",pairsPayload(new String[]{"l1","r2"},new String[]{"l2","r1"}));
        verify(shared).recordSharedRouteResult(org.mockito.ArgumentMatchers.eq(MEMBER_ID),org.mockito.ArgumentMatchers.eq(TOPIC_ID),org.mockito.ArgumentMatchers.eq(ACTIVITY_ID),org.mockito.ArgumentMatchers.eq(NODE_ID),org.mockito.ArgumentMatchers.eq("game:50"),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(true),any(),org.mockito.ArgumentMatchers.eq(3));
        verify(personal,never()).recordNodeResult(any(),any(),any(),any(),any(),any(),any(),org.mockito.ArgumentMatchers.anyBoolean(),org.mockito.ArgumentMatchers.anyInt(),any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"qaText", "qaPick", "sort", "match", "classify"})
    void matrixObjectiveTypesWriteOnlyServerJudgedProofToTeamSettlement(String key) throws Exception {
        com.chengyinhub.business.service.IGameSessionRuntimeService shared = sharedRuntime();
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode config = com.chengyinhub.business.service.support.GameplaySupportMatrixTest.config(key);
        config.putObject("interactionRules").put("hpCost", 1);
        GameRuntimeSession session = runningSession(config.toString(), "{\"runtimeAuthorityEpoch\":3}");
        session.setPlayInstanceId("game:50"); session.setOwnerType(GameRuntimeSession.OWNER_TEAM); session.setOwnerId(88L);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session); when(sessionMapper.updateStateCas(any())).thenReturn(1);
        String kind = key.startsWith("qa") ? "qa" : key;
        Map<String,Object> payload = json.convertValue(com.chengyinhub.business.service.support.GameplaySupportMatrixTest.answers(key).path(kind), Map.class);
        service.act(MEMBER_ID, 51L, 0, "server-judged", "SUBMIT_" + kind.toUpperCase(java.util.Locale.ROOT), payload);
        verify(shared).recordSharedRouteResult(org.mockito.ArgumentMatchers.eq(MEMBER_ID), org.mockito.ArgumentMatchers.eq(TOPIC_ID), org.mockito.ArgumentMatchers.eq(ACTIVITY_ID), org.mockito.ArgumentMatchers.eq(NODE_ID), org.mockito.ArgumentMatchers.eq("game:50"), any(), org.mockito.ArgumentMatchers.eq(true), any(), org.mockito.ArgumentMatchers.eq(3));
    }

    @Test void sharedSessionRejectsOldInstanceAndPersonalAwardGate() {
        sharedRuntime();GameRuntimeSession old = runningSession(matchConfig(), "{}");old.setPlayInstanceId("game:49");
        when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(old);
        assertThrows(ServiceException.class,()->service.act(MEMBER_ID,51L,0,"old","SUBMIT_MATCH",Collections.emptyMap()));
        assertThrows(ServiceException.class,()->service.completeGate(MEMBER_ID,ACTIVITY_ID,TOPIC_ID,NODE_ID));
        verify(sessionMapper,never()).updateStateCas(any());
    }

    @Test void sharedStartUsesRealTeamAndExplicitInstanceWithoutMultiplayerTemplate() {
        sharedRuntime();ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);when(route.canPlay(any(),any(),any(),any())).thenReturn(true);when(route.playInstance(MEMBER_ID,TOPIC_ID,ACTIVITY_ID)).thenReturn("game:50");
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(matchConfig()));
        com.chengyinhub.business.domain.PlayTeam team=new com.chengyinhub.business.domain.PlayTeam();team.setId(88L);team.setOwnerType(2);team.setOwnerId(ACTIVITY_ID);team.setStatus(2);
        when(teamMapper.selectPlayTeamById(88L)).thenReturn(team);when(teamMapper.selectActiveMember(88L,MEMBER_ID)).thenReturn(new com.chengyinhub.business.domain.PlayTeamMember());
        when(sessionMapper.insert(any())).thenAnswer(call->{GameRuntimeSession row=call.getArgument(0);row.setId(51L);assertEquals(88L,row.getOwnerId());assertEquals(GameRuntimeSession.OWNER_TEAM,row.getOwnerType());assertEquals("game:50",row.getPlayInstanceId());return 1;});
        assertThrows(ServiceException.class,()->service.start(MEMBER_ID,ACTIVITY_ID,TOPIC_ID,NODE_ID,99L));
        service.start(MEMBER_ID,ACTIVITY_ID,TOPIC_ID,NODE_ID,null);
        verify(sessionMapper,org.mockito.Mockito.atLeastOnce()).selectOwnerByInstance(ACTIVITY_ID,TOPIC_ID,NODE_ID,GameRuntimeSession.OWNER_TEAM,88L,"game:50");
        verify(sessionMapper,never()).selectByOwner(any(),any(),any(),org.mockito.ArgumentMatchers.anyInt(),any());
    }

    @Test void sharedWriteChecksOriginalEpochBeforeAnyAttemptOrStateMutation() {
        com.chengyinhub.business.service.IGameSessionRuntimeService shared=sharedRuntime();
        GameRuntimeSession session=runningSession(matchConfig(),"{\"runtimeAuthorityEpoch\":2}");session.setOwnerType(GameRuntimeSession.OWNER_TEAM);session.setOwnerId(88L);session.setPlayInstanceId("game:50");
        when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);
        org.mockito.Mockito.doThrow(new ServiceException("权威版本已切换",409)).when(shared).assertSharedRouteWritable(MEMBER_ID,TOPIC_ID,ACTIVITY_ID,NODE_ID,2);
        assertThrows(ServiceException.class,()->service.act(MEMBER_ID,51L,0,"old-epoch","SUBMIT_MATCH",Collections.emptyMap()));
        verify(sessionMapper,never()).updateStateCas(any());verify(attemptMapper,never()).insertPlayAttemptLog(any());
    }
    @Test void operationInstanceCannotReuseAnotherRoundPassedSession() {
        com.chengyinhub.business.service.IGameSessionRuntimeService shared=sharedRuntime();
        com.fasterxml.jackson.databind.node.ObjectNode snapshot=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("teamId",88).put("gameSessionId",50).put("authorityEpoch",3).put("runtimeInstance","game:50:operation:new");
        when(shared.sharedRouteSnapshot(any(),any(),any(),any())).thenReturn(snapshot);
        GameRuntimeSession old=runningSession(matchConfig(),"{\"runtimeAuthorityEpoch\":3}");old.setOwnerType(GameRuntimeSession.OWNER_TEAM);old.setOwnerId(88L);old.setPlayInstanceId("game:50:operation:old");
        when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(old);
        assertThrows(ServiceException.class,()->service.act(MEMBER_ID,51L,0,"old-round","SUBMIT_MATCH",Collections.emptyMap()));verify(sessionMapper,never()).updateStateCas(any());
    }



    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"coinFlip","diceRoll","random","blindTaste","pricePair","estimate"})
    void r5MainFamilySettlesOnlyItsServerResult(String kit) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();String source,action;Map<String,Object> payload=new LinkedHashMap<>();String expected="PASS";
        switch(kit){
            case "coinFlip":source=coinOnlyConfig();action="FLIP_COIN";break;
            case "diceRoll":source=diceOnlyConfig(1);action="ROLL_DICE";break;
            case "random":source=randomOnlyConfig();action="DRAW";break;
            case "blindTaste":source="{\"schemaVersion\":1,\"blindTaste\":{\"enabled\":true,\"answerKey\":\"b\",\"options\":[{\"key\":\"a\",\"label\":\"A\"},{\"key\":\"b\",\"label\":\"B\"}]}}";action="SUBMIT_BLIND_TASTE";payload.put("key","a");expected="FAIL";break;
            case "pricePair":source="{\"schemaVersion\":1,\"pricePair\":{\"enabled\":true,\"items\":[{\"id\":\"a\",\"correct\":false},{\"id\":\"b\",\"correct\":true}]}}";action="SUBMIT_PRICE_PAIR";payload.put("pickId","a");expected="FAIL";break;
            default:source="{\"schemaVersion\":1,\"estimate\":{\"enabled\":true,\"min\":0,\"max\":100,\"answer\":90,\"tolerance\":1}}";action="SUBMIT_ESTIMATE";payload.put("value",0);expected="FAIL";
        }
        com.fasterxml.jackson.databind.node.ObjectNode config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(source);if(java.util.Arrays.asList("blindTaste","pricePair","estimate").contains(kit))config.with(kit).put("title","Question");if("pricePair".equals(kit)){for(com.fasterxml.jackson.databind.JsonNode row:config.path(kit).path("items"))((com.fasterxml.jackson.databind.node.ObjectNode)row).put("name",row.path("id").asText());config.with(kit).withArray("items").addObject().put("id","c").put("name","C").put("correct",false);}config.putObject("outcomeRules").put("version",1).putObject("pass");config.with("outcomeRules").putObject("fail");
        assertTrue(new com.chengyinhub.business.service.support.AdvancedGameConfigValidator().hasEnabledMechanic(config.toString()));
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);
        when(route.genericPlayer(any(),any(),any())).thenReturn(true);when(route.canPlay(any(),any(),any(),any())).thenReturn(true);when(route.gameplayInstance(any(),any(),any(),any())).thenReturn("quest:r5");when(route.nodeRuntimeConfig(any(),any(),any(),any())).thenReturn(config);
        GameRuntimeSession session=runningSession(config.toString(),"{\"attemptOrdinal\":1}");session.setPlayInstanceId("quest:r5");when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);when(sessionMapper.updateStateCas(any())).thenReturn(1);payload.put("passed",true);
        service.act(MEMBER_ID,51L,0,"r5:"+kit,action,payload);
        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> decision=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(route).recordGameplayOutcome(any(),any(),any(),any(),any(),org.mockito.ArgumentMatchers.eq("advanced:51:1"),decision.capture(),any());assertEquals(expected,decision.getValue().path("outcome").asText());
    }



    @Test void r5TypeInIgnoresLegacySecondsWithoutTimer() {
        String cfg="{\"schemaVersion\":1,\"typeIn\":{\"enabled\":true,\"target\":\"hello\",\"seconds\":3},\"outcomeRules\":{\"version\":1,\"pass\":{},\"fail\":{}}}";
        GameRuntimeSession session=runningSession(cfg,"{\"typeIn\":{\"startedAtMs\":"+(NOW-20000)+"}}");when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);when(sessionMapper.updateStateCas(any())).thenReturn(1);
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);when(route.genericPlayer(any(),any(),any())).thenReturn(true);when(route.canPlay(any(),any(),any(),any())).thenReturn(true);when(route.gameplayInstance(any(),any(),any(),any())).thenReturn("default");when(route.nodeRuntimeConfig(any(),any(),any(),any())).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("text","hello");payload.put("elapsedMs",20000);assertEquals("COMPLETED",service.act(MEMBER_ID,51L,0,"typing","SUBMIT_TYPE_IN",payload).get("status"));
    }
    @Test void requestProvidedFrozenMarkerDoesNotBypassAuthorValidator() throws Exception {
        String cfg="{\"schemaVersion\":1,\"r5AuthorValidationVersion\":1,\"interactionRules\":{\"resourceSlots\":[{\"key\":\"vital\",\"kind\":\"VALUE\",\"label\":\"HP\",\"required\":true}]},\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"title\",\"answerText\":\"answer\"},\"outcomeRules\":{\"version\":1,\"pass\":{\"costs\":[{\"statKey\":\"sys.hp\",\"amount\":1}]},\"fail\":{}}}";
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);when(route.canPlay(any(),any(),any(),any())).thenReturn(true);when(route.nodeRuntimeConfig(any(),any(),any(),any())).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree(cfg));when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(cfg));assertThrows(IllegalArgumentException.class,()->service.start(MEMBER_ID,ACTIVITY_ID,TOPIC_ID,NODE_ID,null));verify(sessionMapper,never()).insert(any());
    }

    private String r5PredictConfig() { return "{\"schemaVersion\":1,\"predict\":{\"enabled\":true,\"question\":\"明天会下雨吗\",\"options\":[{\"key\":\"rain\",\"label\":\"下雨\"},{\"key\":\"sun\",\"label\":\"晴天\"}],\"closeAtHour\":23,\"revealDays\":1,\"revealHour\":0},\"outcomeRules\":{\"version\":1,\"pass\":{},\"fail\":{}}}"; }
    @Test void r5PredictEarlyMerchantAnswerRemainsPendingUntilFrozenReveal() {
        com.chengyinhub.business.mapper.PlayPredictMapper predict=mock(com.chengyinhub.business.mapper.PlayPredictMapper.class);ReflectionTestUtils.setField(service,"predictMapper",predict);
        java.sql.Date day=java.sql.Date.valueOf(java.time.Instant.ofEpochMilli(NOW).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate());
        com.chengyinhub.business.domain.PlayPredictResult round=new com.chengyinhub.business.domain.PlayPredictResult();round.setSettleStatus(1);round.setSettledOption("rain");when(predict.selectResult(NODE_ID,day)).thenReturn(round);
        GameRuntimeSession session=runningSession(r5PredictConfig(),"{\"attemptOrdinal\":1,\"predict\":{\"betId\":101,\"memberId\":7,\"optionKey\":\"rain\",\"playDay\":\""+day+"\"}}");when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);
        Map<String,Object> view=service.state(MEMBER_ID,51L);Map<String,Object> kit=playKit(view,"predict");assertEquals(0,((Number)kit.get("settleStatus")).intValue());assertFalse(kit.containsKey("settledOption"));assertFalse(kit.containsKey("won"));verify(sessionMapper,never()).updateStateCas(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"PASS","FAIL","VOID","CLOSED","TIMEOUT"})
    void r5PredictRevealUsesFrozenSelectionAndNeverReplacesTimeout(String result) throws Exception {
        com.chengyinhub.business.mapper.PlayPredictMapper predict=mock(com.chengyinhub.business.mapper.PlayPredictMapper.class);ReflectionTestUtils.setField(service,"predictMapper",predict);
        String day="2026-03-30";
        com.chengyinhub.business.domain.PlayPredictResult round=new com.chengyinhub.business.domain.PlayPredictResult();round.setSettleStatus("VOID".equals(result)?2:1);round.setSettledOption("PASS".equals(result)?"rain":"sun");when(predict.selectResult(NODE_ID,java.sql.Date.valueOf(day))).thenReturn(round);
        com.chengyinhub.business.domain.PlayPredictBet bet=new com.chengyinhub.business.domain.PlayPredictBet();bet.setId(101L);bet.setTopicId(TOPIC_ID);bet.setActivityId(ACTIVITY_ID);bet.setMemberId(MEMBER_ID);bet.setOptionKey("rain");bet.setRewardStatus(1);when(predict.selectBet(NODE_ID,java.sql.Date.valueOf(day),MEMBER_ID)).thenReturn(bet);
        GameRuntimeSession session=runningSession(r5PredictConfig(),"{\"attemptOrdinal\":1,\"originalRouteSessionId\":88,\"predict\":{\"betId\":101,\"memberId\":7,\"optionKey\":\"rain\",\"playDay\":\""+day+"\"}}");
        if("TIMEOUT".equals(result)){session.setStatus(GameRuntimeSession.STATUS_FAILED);session.setStateJson(session.getStateJson().replace("\"attemptOrdinal\":1","\"attemptOrdinal\":1,\"gameplayDecision\":{\"outcome\":\"FAIL\",\"reason\":\"TIMEOUT\"}"));}
        when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);when(sessionMapper.selectByIdForUpdate(51L)).thenReturn(session);when(sessionMapper.updateStateCas(any())).thenReturn(1);when(sessionMapper.updateFeedbackStateCas(any())).thenReturn(1);
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);when(route.recordOriginalGameplayOutcome(any(),any(),any(),any(),any(),any(),any())).thenReturn(!"CLOSED".equals(result));
        Map<String,Object> view=service.state(MEMBER_ID,51L);com.fasterxml.jackson.databind.JsonNode stored=new com.fasterxml.jackson.databind.ObjectMapper().readTree(session.getStateJson());
        assertTrue(stored.path("predict").path("revealRecorded").asBoolean());
        if(Arrays.asList("PASS","FAIL").contains(result)){assertEquals(result,stored.path("gameplayDecision").path("outcome").asText());assertEquals("predict",stored.path("gameplayDecision").path("selection").path("kit").asText());assertEquals("rain",stored.path("gameplayDecision").path("selection").path("optionId").asText());}
        else {if(!"CLOSED".equals(result))verify(route,never()).recordOriginalGameplayOutcome(any(),any(),any(),any(),any(),any(),any());if("TIMEOUT".equals(result))assertEquals("TIMEOUT",stored.path("gameplayDecision").path("reason").asText());else assertFalse(stored.has("gameplayDecision"));}
        if("VOID".equals(result))assertEquals(GameRuntimeSession.STATUS_EXPIRED,session.getStatus());
        service.state(MEMBER_ID,51L);verify(sessionMapper,org.mockito.Mockito.times("TIMEOUT".equals(result)?0:1)).updateStateCas(any());
    }
    @Test void r5TeamPredictKeepsTheFirstSubmittersBetWhenAnotherMemberSubmits() {
        String day=java.time.Instant.ofEpochMilli(NOW).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString();GameRuntimeSession session=runningSession(r5PredictConfig(),"{\"attemptOrdinal\":1,\"predict\":{\"betId\":101,\"memberId\":7,\"optionKey\":\"rain\",\"playDay\":\""+day+"\"}}");session.setOwnerType(GameRuntimeSession.OWNER_TEAM);session.setOwnerId(90L);when(sessionMapper.selectAccessibleById(51L,8L)).thenReturn(session);when(teamMapper.hasPaidRegistration(8L,2,ACTIVITY_ID)).thenReturn(1);
        com.chengyinhub.business.mapper.PlayPredictMapper predictions=mock(com.chengyinhub.business.mapper.PlayPredictMapper.class);ReflectionTestUtils.setField(service,"predictMapper",predictions);
        assertThrows(IllegalStateException.class,()->service.act(8L,51L,0,"second-member","SUBMIT_PREDICT",Collections.singletonMap("optionKey","sun")));verify(predictions,never()).insertBet(any());assertTrue(session.getStateJson().contains("rain"));assertTrue(session.getStateJson().contains("101"));
    }

    @Test void r5PredictSubmissionFreezesRealBetIdentityWithoutPassing() throws Exception {
        com.chengyinhub.business.mapper.PlayPredictMapper predict=mock(com.chengyinhub.business.mapper.PlayPredictMapper.class);ReflectionTestUtils.setField(service,"predictMapper",predict);com.chengyinhub.business.domain.PlayPredictResult round=new com.chengyinhub.business.domain.PlayPredictResult();round.setSettleStatus(0);when(predict.selectResultForUpdate(any(),any())).thenReturn(round);when(predict.insertBet(any())).thenAnswer(i->{((com.chengyinhub.business.domain.PlayPredictBet)i.getArgument(0)).setId(101L);return 1;});
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);when(route.canPlay(any(),any(),any(),any())).thenReturn(true);when(route.nodeRuntimeConfig(any(),any(),any(),any())).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree(r5PredictConfig()));
        GameRuntimeSession session=runningSession(r5PredictConfig(),"{\"attemptOrdinal\":1,\"originalRouteSessionId\":88}");when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);when(sessionMapper.updateStateCas(any())).thenReturn(1);
        service.act(MEMBER_ID,51L,0,"predict-bet","SUBMIT_PREDICT",Collections.singletonMap("optionKey","rain"));com.fasterxml.jackson.databind.JsonNode state=new com.fasterxml.jackson.databind.ObjectMapper().readTree(session.getStateJson());assertEquals(101,state.path("predict").path("betId").asLong());assertEquals(7,state.path("predict").path("memberId").asLong());assertFalse(state.has("gameplayDecision"));assertEquals(GameRuntimeSession.STATUS_RUNNING,session.getStatus());verify(route,never()).recordGameplayOutcome(any(),any(),any(),any(),any(),any(),any(),any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"profile","note","dailySign"})
    void r5CompletionOnlyFamiliesRequireActualSaveAndFreezeOnePass(String kit) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();com.fasterxml.jackson.databind.node.ObjectNode config=json.createObjectNode().put("schemaVersion",1);
        com.fasterxml.jackson.databind.node.ObjectNode main=config.putObject(kit).put("enabled",true);String action;Map<String,Object> payload=new LinkedHashMap<>();
        if("profile".equals(kit)){main.putArray("questions").addObject().put("key","name").put("label","姓名").put("kind","text").put("required",true).put("maxLength",12);payload.put("answers",Collections.singletonMap("name","小满"));action="SUBMIT_PROFILE";}
        else if("note".equals(kit)){main.put("maxLength",40);payload.put("text","留下一句话");action="SUBMIT_NOTE";com.chengyinhub.business.mapper.PlayNoteMapper notes=mock(com.chengyinhub.business.mapper.PlayNoteMapper.class);when(notes.insert(any())).thenReturn(1);ReflectionTestUtils.setField(service,"playNoteMapper",notes);}
        else{main.putArray("poems").addArray().add("签文");action="CLAIM_DAILY_SIGN";}
        config.putObject("outcomeRules").put("version",1).putObject("pass");
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);when(route.genericPlayer(any(),any(),any())).thenReturn(true);when(route.canPlay(any(),any(),any(),any())).thenReturn(true);when(route.gameplayInstance(any(),any(),any(),any())).thenReturn("quest:r5");when(route.nodeRuntimeConfig(any(),any(),any(),any())).thenReturn(config);
        GameRuntimeSession session=runningSession(config.toString(),"{\"attemptOrdinal\":1}");session.setPlayInstanceId("quest:r5");when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);when(sessionMapper.updateStateCas(any())).thenReturn(1);
        service.act(MEMBER_ID,51L,0,"first:"+kit,action,payload);service.act(MEMBER_ID,51L,1,"replay:"+kit,action,payload);
        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> decision=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);verify(route).recordGameplayOutcome(any(),any(),any(),any(),any(),org.mockito.ArgumentMatchers.eq("advanced:51:1"),decision.capture(),any());assertEquals("PASS",decision.getValue().path("outcome").asText());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"SHOT","REVIEW","CARD","ALBUM"})
    void r5PhotoMustVerifyExistingUploadBeforeAccepting(String mode) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();com.fasterxml.jackson.databind.node.ObjectNode config=json.createObjectNode().put("schemaVersion",1);String action;
        if("SHOT".equals(mode)){config.putObject("qa").put("enabled",true).put("mode","SHOT");action="SUBMIT_QA";}else if("ALBUM".equals(mode)){config.putObject("album").put("enabled",true);action="SUBMIT_ALBUM";}else{config.putObject("photoCheck").put("enabled",true).put("mode","CARD".equals(mode)?"CARD":"").put("minConfidence",60);action="SUBMIT_PHOTO_CHECK";}
        config.putObject("outcomeRules").put("version",1).putObject("pass");if(!"ALBUM".equals(mode))config.with("outcomeRules").putObject("fail");
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);when(route.genericPlayer(any(),any(),any())).thenReturn(true);when(route.canPlay(any(),any(),any(),any())).thenReturn(true);when(route.gameplayInstance(any(),any(),any(),any())).thenReturn("quest:r5");when(route.nodeRuntimeConfig(any(),any(),any(),any())).thenReturn(config);
        GameRuntimeSession session=runningSession(config.toString(),"{\"attemptOrdinal\":1}");session.setPlayInstanceId("quest:r5");when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);when(sessionMapper.updateStateCas(any())).thenReturn(1);
        if ("SUBMIT_PHOTO_CHECK".equals(action)) AdvancedActionTestFixtures.photos(service,
                () -> { throw new AssertionError("Rejected evidence must not call provider"); });
        java.util.concurrent.atomic.AtomicInteger evidenceChecks = new java.util.concurrent.atomic.AtomicInteger();
        ReflectionTestUtils.setField(service,"photoEvidenceVerifier",(java.util.function.BiConsumer<String,Long>)(url,member)->{evidenceChecks.incrementAndGet();throw new IllegalArgumentException("照片不属于当前会员");});
        Map<String,Object> evidence = "ALBUM".equals(mode) ? Collections.singletonMap("imageUrls",Collections.singletonList("http://127.0.0.1/profile/unowned.jpg")) : Collections.singletonMap("imageUrl","http://127.0.0.1/profile/unowned.jpg");
        assertThrows(RuntimeException.class,()->service.act(MEMBER_ID,51L,0,"foreign-photo",action,evidence));assertEquals(1, evidenceChecks.get());verify(sessionMapper,never()).updateStateCas(any());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"SHOT","REVIEW","CARD","REJECT","DEGRADED","ALBUM"})
    void r5PhotoAndAlbumUseVerifiedServerEvidence(String mode) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();com.fasterxml.jackson.databind.node.ObjectNode config=json.createObjectNode().put("schemaVersion",1);Map<String,Object> payload=new LinkedHashMap<>();String action;
        if("SHOT".equals(mode)){config.putObject("qa").put("enabled",true).put("mode","SHOT");action="SUBMIT_QA";}
        else if("ALBUM".equals(mode)){config.putObject("album").put("enabled",true);action="SUBMIT_ALBUM";payload.put("imageUrls",Arrays.asList("first","second"));}
        else{config.putObject("photoCheck").put("enabled",true).put("mode","CARD".equals(mode)?"CARD":"").put("fallback","pass").put("maxTries",1).put("minConfidence",60);action="SUBMIT_PHOTO_CHECK";com.chengyinhub.business.service.ai.PhotoCheckVisionService vision=mock(com.chengyinhub.business.service.ai.PhotoCheckVisionService.class);when(vision.review(any(),any(),any())).thenReturn("DEGRADED".equals(mode)?com.chengyinhub.business.service.ai.PhotoCheckVisionResult.degraded("unavailable"):com.chengyinhub.business.service.ai.PhotoCheckVisionResult.concluded(!"REJECT".equals(mode),90,"review"));AdvancedActionTestFixtures.photos(service, () -> vision.review(MEMBER_ID, "unit-photo", "unit-requirement"));}
        config.putObject("outcomeRules").put("version",1).putObject("pass");if(!"ALBUM".equals(mode))config.with("outcomeRules").putObject("fail");if(!"ALBUM".equals(mode))payload.put("imageUrl","first");
        java.util.List<String> checked=new ArrayList<>();ReflectionTestUtils.setField(service,"photoEvidenceVerifier",(java.util.function.BiConsumer<String,Long>)(url,member)->{assertEquals(MEMBER_ID,member);checked.add(url);});
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);when(route.genericPlayer(any(),any(),any())).thenReturn(true);when(route.canPlay(any(),any(),any(),any())).thenReturn(true);when(route.gameplayInstance(any(),any(),any(),any())).thenReturn("quest:r5");when(route.nodeRuntimeConfig(any(),any(),any(),any())).thenReturn(config);
        GameRuntimeSession session=runningSession(config.toString(),"{\"attemptOrdinal\":1}");session.setPlayInstanceId("quest:r5");when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);when(sessionMapper.updateStateCas(any())).thenReturn(1);
        Map<String,Object> result=service.act(MEMBER_ID,51L,0,"proof:"+mode,action,payload);assertEquals("ALBUM".equals(mode)?2:1,checked.size());
        if("DEGRADED".equals(mode)){verify(route,never()).recordGameplayOutcome(any(),any(),any(),any(),any(),any(),any(),any());assertEquals(GameRuntimeSession.STATUS_RUNNING,session.getStatus());assertFalse(json.readTree(session.getStateJson()).path("photoCheck").path("passed").asBoolean());}
        else{org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> decision=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);verify(route).recordGameplayOutcome(any(),any(),any(),any(),any(),any(),decision.capture(),any());assertEquals("REJECT".equals(mode)?"FAIL":"PASS",decision.getValue().path("outcome").asText());}
        if("ALBUM".equals(mode)){assertTrue(json.valueToTree(result).path("playKit").path("album").path("done").asBoolean());service.act(MEMBER_ID,51L,1,"new-key","SUBMIT_ALBUM",payload);assertEquals(2,checked.size());}
    }
    @Test void r5NonPublishedConfigurationCannotWriteHpThroughLegacyBranchEffects() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();com.fasterxml.jackson.databind.node.ObjectNode config=json.createObjectNode().put("schemaVersion",1);config.putObject("qa").put("enabled",true).put("mode","TYPE").put("title","问答").put("answerText","yes");config.putObject("outcomeRules").put("version",1).putObject("pass");config.with("outcomeRules").putObject("fail");com.fasterxml.jackson.databind.node.ArrayNode steps=config.putObject("branch").put("enabled",true).put("startStepId","start").putArray("steps");steps.addObject().put("id","start").putArray("options").addObject().put("id","a").put("label","继续").put("nextStepId","done").putArray("effects").addObject().put("var","sys.hp").put("op","INC").put("value",1);steps.addObject().put("id","done").put("terminal",true).putArray("options");
        CmsMemberTemplate template=new CmsMemberTemplate();template.setId(TEMPLATE_ID);template.setAdvancedConfigJson(config.toString());when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template);ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);when(route.nodeRuntimeConfig(any(),any(),any(),any())).thenReturn(config);when(route.genericPlayer(any(),any(),any())).thenReturn(true);when(route.gameplayInstance(any(),any(),any(),any())).thenReturn("quest:original");
        assertThrows(IllegalArgumentException.class,()->service.start(MEMBER_ID,ACTIVITY_ID,TOPIC_ID,NODE_ID,null));when(route.nodeRuntimeConfig(any(),any(),any(),any())).thenReturn(null);assertThrows(IllegalArgumentException.class,()->service.start(MEMBER_ID,ACTIVITY_ID,TOPIC_ID,NODE_ID,null));verify(sessionMapper,never()).insert(any());
    }
    @Test void r5StepsRejectsOtherIdentityThenFreezesOnlyVerifiedGoal() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();com.fasterxml.jackson.databind.node.ObjectNode config=json.createObjectNode().put("schemaVersion",1);config.putObject("steps").put("enabled",true).put("goal",100);config.putObject("outcomeRules").put("version",1).putObject("pass");config.with("outcomeRules").putObject("fail");
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);when(route.genericPlayer(any(),any(),any())).thenReturn(true);when(route.canPlay(any(),any(),any(),any())).thenReturn(true);when(route.gameplayInstance(any(),any(),any(),any())).thenReturn("quest:r5");when(route.nodeRuntimeConfig(any(),any(),any(),any())).thenReturn(config);
        GameRuntimeSession session=runningSession(config.toString(),"{\"attemptOrdinal\":1}");session.setPlayInstanceId("quest:r5");when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);when(sessionMapper.updateStateCas(any())).thenReturn(1);
        Map<String,Object> wrong=json.convertValue(com.chengyinhub.business.it.R5WeRunFixture.today(NOW,8,"",100),Map.class);wrong.remove("episodeId");assertThrows(IllegalArgumentException.class,()->service.act(MEMBER_ID,51L,0,"wrong-member","SUBMIT_STEPS",wrong));
        Map<String,Object> payload=json.convertValue(com.chengyinhub.business.it.R5WeRunFixture.today(NOW,7,"",99),Map.class);payload.remove("episodeId");service.act(MEMBER_ID,51L,0,"below-goal","SUBMIT_STEPS",payload);verify(route,never()).recordGameplayOutcome(any(),any(),any(),any(),any(),any(),any(),any());
        payload=json.convertValue(com.chengyinhub.business.it.R5WeRunFixture.today(NOW,7,"",100),Map.class);payload.remove("episodeId");service.act(MEMBER_ID,51L,1,"at-goal","SUBMIT_STEPS",payload);verify(route).recordGameplayOutcome(any(),any(),any(),any(),any(),any(),any(),any());assertFalse(session.getStateJson().contains("encryptedData"));
    }
    @Test void r5HiddenObjectKeepsPartialHitsPendingAndOnlyWholeGameSettles() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();com.fasterxml.jackson.databind.node.ObjectNode config=json.createObjectNode().put("schemaVersion",1);com.fasterxml.jackson.databind.node.ArrayNode spots=config.putObject("hiddenObject").put("enabled",true).put("maxTries",2).putArray("hotspots");
        spots.addObject().put("id","a").put("x",0.1).put("y",0.1).put("r",0.03);spots.addObject().put("id","b").put("x",0.5).put("y",0.5).put("r",0.03);config.putObject("outcomeRules").put("version",1).putObject("pass");config.with("outcomeRules").putObject("fail");
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);when(route.genericPlayer(any(),any(),any())).thenReturn(true);when(route.canPlay(any(),any(),any(),any())).thenReturn(true);when(route.gameplayInstance(any(),any(),any(),any())).thenReturn("quest:r5");when(route.nodeRuntimeConfig(any(),any(),any(),any())).thenReturn(config);
        GameRuntimeSession session=runningSession(config.toString(),"{\"attemptOrdinal\":1}");session.setPlayInstanceId("quest:r5");when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);when(sessionMapper.updateStateCas(any())).thenReturn(1);
        Map<String,Object> hit=new LinkedHashMap<>();hit.put("x",0.1);hit.put("y",0.1);service.act(MEMBER_ID,51L,0,"hit-a","SUBMIT_HIDDEN_OBJECT",hit);verify(route,never()).recordGameplayOutcome(any(),any(),any(),any(),any(),any(),any(),any());assertEquals(GameRuntimeSession.STATUS_RUNNING,session.getStatus());
        hit.put("x",Double.NaN);assertThrows(IllegalArgumentException.class,()->service.act(MEMBER_ID,51L,1,"bad-coordinate","SUBMIT_HIDDEN_OBJECT",hit));
        hit.put("x",0.5);hit.put("y",0.5);service.act(MEMBER_ID,51L,1,"hit-b","SUBMIT_HIDDEN_OBJECT",hit);org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> decision=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);verify(route).recordGameplayOutcome(any(),any(),any(),any(),any(),any(),decision.capture(),any());assertEquals("PASS",decision.getValue().path("outcome").asText());
    }

    @Test void r5TimeoutCompletedFreezesPassWithoutInventingAnswerAndSettlesOnce() throws Exception {
        String config="{\"schemaVersion\":1,\"timer\":{\"enabled\":true,\"durationSeconds\":30,\"timeoutResult\":\"COMPLETED\"},\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"Answer\",\"answerText\":\"correct\"},\"outcomeRules\":{\"version\":1,\"pass\":{},\"fail\":{}}}";
        GameRuntimeSession session=runningSession(config,"{\"attemptOrdinal\":1}");session.setPlayInstanceId("quest:r5");session.setDeadlineAt(new Date(NOW-1));when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);when(sessionMapper.updateStateCas(any())).thenReturn(1);
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);
        assertEquals(true,service.state(MEMBER_ID,51L).get("readyForBase"));service.state(MEMBER_ID,51L);
        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> decision=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(route).recordGameplayOutcome(org.mockito.ArgumentMatchers.eq(MEMBER_ID),any(),any(),any(),any(),org.mockito.ArgumentMatchers.eq("advanced:51:1"),decision.capture(),any());
        assertEquals("PASS",decision.getValue().path("outcome").asText());assertEquals("TIMEOUT",decision.getValue().path("reason").asText());assertFalse(decision.getValue().has("selection"));assertFalse(session.getStateJson().contains("lastCorrect"));
    }

    @Test void r5TimeoutSettlesOneServerFailureAndReplaysTheSameAttempt() throws Exception {
        String config="{\"schemaVersion\":1,\"timer\":{\"enabled\":true,\"durationSeconds\":30,\"timeoutResult\":\"EXPIRED\"},\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"Answer\",\"answerText\":\"correct\"},\"outcomeRules\":{\"version\":1,\"pass\":{},\"fail\":{}}}";
        GameRuntimeSession session=runningSession(config,"{\"attemptOrdinal\":1}");session.setPlayInstanceId("quest:r5");session.setDeadlineAt(new Date(NOW-1));when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);when(sessionMapper.updateStateCas(any())).thenReturn(1);
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);
        service.state(MEMBER_ID,51L);service.state(MEMBER_ID,51L);
        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> decision=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(route).recordGameplayOutcome(org.mockito.ArgumentMatchers.eq(MEMBER_ID),any(),any(),any(),any(),org.mockito.ArgumentMatchers.eq("advanced:51:1"),decision.capture(),any());assertEquals("FAIL",decision.getValue().path("outcome").asText());assertEquals("TIMEOUT",decision.getValue().path("reason").asText());
    }

    @Test void r5CheckReadbackProjectsServerActionsAndExactAttemptWithoutChangingStoredReceipt() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
        String cfg="{\"schemaVersion\":1,\"check\":{\"enabled\":true,\"tier\":\"easy\"},\"outcomeRules\":{\"version\":1,\"pass\":{},\"fail\":{}}}";
        GameRuntimeSession session=runningSession(cfg,"{\"attemptOrdinal\":3,\"check\":{\"dice\":[12],\"success\":true,\"luck\":2}}");
        when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);
        com.fasterxml.jackson.databind.node.ObjectNode actions=json.createObjectNode().put("attemptId","advanced:51:3").put("disabledReason","");actions.putArray("allowedActions").add("SUBMIT_CHECK").add("REROLL_CHECK");
        when(route.gameplayCheckActions(any(),any(),any(),any(),any(),any())).thenReturn(actions);
        String stored=session.getStateJson();com.fasterxml.jackson.databind.JsonNode view=json.valueToTree(service.state(MEMBER_ID,51L));
        assertEquals("advanced:51:3",view.path("check").path("attemptId").asText());assertEquals(1,view.path("check").path("rerollRemaining").asInt());assertEquals(stored,session.getStateJson());
        com.fasterxml.jackson.databind.node.ObjectNode zeroState=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(stored);zeroState.put("zeroChallengeInstance","challenge:1");
        com.fasterxml.jackson.databind.JsonNode zeroView=ReflectionTestUtils.invokeMethod(service,"checkView",session,zeroState,MEMBER_ID);
        assertEquals("SUBMIT_CHECK",zeroView.path("allowedActions").path(0).asText());assertEquals(1,zeroView.path("allowedActions").size());assertEquals(0,zeroView.path("rerollRemaining").asInt());
        session.setStateJson(stored.replace("\"luck\":2","\"luck\":2,\"submitted\":true"));
        assertTrue(json.valueToTree(service.state(MEMBER_ID,51L)).path("check").path("allowedActions").isEmpty());
    }

    @Test void r5CheckRollIsPendingUntilSubmitAndNeverAcceptsClientSuccess() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("{\"schemaVersion\":1,\"check\":{\"enabled\":true,\"checkId\":\"c\",\"tier\":\"easy\"},\"outcomeRules\":{\"version\":1,\"pass\":{},\"fail\":{}}}");
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);
        when(route.genericPlayer(any(),any(),any())).thenReturn(true);when(route.canPlay(any(),any(),any(),any())).thenReturn(true);
        when(route.gameplayInstance(any(),any(),any(),any())).thenReturn("quest:r5");when(route.nodeRuntimeConfig(any(),any(),any(),any())).thenReturn(config);
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(config.toString()));AtomicReference<GameRuntimeSession> created=new AtomicReference<>();when(sessionMapper.insert(any())).thenAnswer(call->{GameRuntimeSession row=call.getArgument(0);row.setId(51L);created.set(row);return 1;});service.start(MEMBER_ID,ACTIVITY_ID,TOPIC_ID,NODE_ID,null);GameRuntimeSession session=created.get();when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);when(sessionMapper.updateStateCas(any())).thenReturn(1);
        com.chengyinhub.business.domain.route.PlayCheckReceipt dice=new com.chengyinhub.business.domain.route.PlayCheckReceipt();dice.setDice(Collections.singletonList(1));dice.setSuccess(false);dice.setNat("FUMBLE");
        when(route.rollGameplayCheck(any(),any(),any(),any(),any(),any(),org.mockito.ArgumentMatchers.eq(false))).thenReturn(dice);
        Map<String,Object> roll=service.act(MEMBER_ID,51L,0,"roll","ROLL_CHECK",Collections.singletonMap("success",true));assertEquals(false,roll.get("readyForBase"));
        verify(route,never()).recordGameplayOutcome(any(),any(),any(),any(),any(),any(),any(),any());
        com.chengyinhub.business.domain.route.PlayCheckReceipt reroll=new com.chengyinhub.business.domain.route.PlayCheckReceipt();reroll.setDice(Collections.singletonList(2));reroll.setSuccess(false);reroll.setRerolled(true);
        when(route.rollGameplayCheck(any(),any(),any(),any(),any(),any(),org.mockito.ArgumentMatchers.eq(true))).thenReturn(reroll);
        service.act(MEMBER_ID,51L,1,"reroll","REROLL_CHECK",Collections.emptyMap());String frozen=session.getStateJson();
        assertThrows(IllegalStateException.class,()->service.act(MEMBER_ID,51L,2,"revert-roll","ROLL_CHECK",Collections.emptyMap()));assertEquals(frozen,session.getStateJson());
        service.act(MEMBER_ID,51L,2,"submit","SUBMIT_CHECK",Collections.singletonMap("success",true));assertEquals(GameRuntimeSession.STATUS_FAILED,session.getStatus());
    }

    @Test void r5WrongAnswerUsesOneImmutableServerOutcomeInsteadOfLegacyCompletion() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode config = json.createObjectNode().put("schemaVersion", 1);
        config.putObject("qa").put("enabled", true).put("mode", "TYPE").put("title", "Answer").put("answerText", "correct").put("maxTries", 0);
        config.putObject("outcomeRules").put("version", 1).putObject("pass"); config.with("outcomeRules").putObject("fail");
        ITopicRouteRuntimeService route = mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);
        when(route.genericPlayer(any(), any(), any())).thenReturn(true); when(route.canPlay(any(), any(), any(), any())).thenReturn(true);
        when(route.gameplayInstance(any(), any(), any(), any())).thenReturn("quest:r5"); when(route.nodeRuntimeConfig(any(), any(), any(), any())).thenReturn(config);
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(config.toString()));
        AtomicReference<GameRuntimeSession> created = new AtomicReference<>();
        when(sessionMapper.insert(any())).thenAnswer(call -> { GameRuntimeSession row = call.getArgument(0); row.setId(51L); created.set(row); return 1; });
        service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenAnswer(call -> created.get()); when(sessionMapper.updateStateCas(any())).thenReturn(1);
        Map<String, Object> input = new LinkedHashMap<>(); input.put("input", "wrong"); input.put("passed", true);
        service.act(MEMBER_ID, 51L, 0, "r5-wrong", "SUBMIT_QA", input);
        assertEquals(GameRuntimeSession.STATUS_FAILED, created.get().getStatus());
        assertEquals(1, json.readTree(created.get().getStateJson()).path("attemptOrdinal").asInt());
        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> result = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(route).recordGameplayOutcome(any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq("quest:r5"), org.mockito.ArgumentMatchers.eq("advanced:51:1"), result.capture(), any());
        assertEquals("FAIL", result.getValue().path("outcome").asText());
        verify(route, never()).recordNodeResult(any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test void genericPersonalObjectiveIgnoresMultiplayerModifierForOwnerAndCompletion() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(matchConfig());config.set("multiplayer",json.readTree(fullConfig()).path("multiplayer"));
        ITopicRouteRuntimeService route=mock(ITopicRouteRuntimeService.class);ReflectionTestUtils.setField(service,"topicRouteRuntimeService",route);
        when(route.genericPlayer(any(),any(),any())).thenReturn(true);when(route.canPlay(any(),any(),any(),any())).thenReturn(true);when(route.gameplayInstance(any(),any(),any(),any())).thenReturn("quest:one");when(route.nodeRuntimeConfig(any(),any(),any(),any())).thenReturn(config);
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(config.toString()));
        AtomicReference<GameRuntimeSession> created=new AtomicReference<>();when(sessionMapper.insert(any())).thenAnswer(call->{GameRuntimeSession row=call.getArgument(0);row.setId(51L);created.set(row);return 1;});
        assertEquals(GameRuntimeSession.OWNER_PLAYER,service.start(MEMBER_ID,ACTIVITY_ID,TOPIC_ID,NODE_ID,null).get("ownerType"));
        when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenAnswer(call->created.get());when(sessionMapper.updateStateCas(any())).thenReturn(1);
        service.act(MEMBER_ID,51L,0,"modifier","SUBMIT_MATCH",pairsPayload(new String[]{"l1","r2"},new String[]{"l2","r1"}));
        verify(route).recordNodeResult(any(),any(),any(),any(),org.mockito.ArgumentMatchers.eq("quest:one"),any(),org.mockito.ArgumentMatchers.eq(true),org.mockito.ArgumentMatchers.eq(false),org.mockito.ArgumentMatchers.eq(0),any());
    }

    @Test void challengeBallUsesEachMembersServerTimerAndPreservesSubmissionPlausibility() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
        com.chengyinhub.business.service.IGameSessionRuntimeService shared=sharedRuntime();
        com.fasterxml.jackson.databind.node.ObjectNode snapshot=json.createObjectNode().put("teamId",88).put("gameSessionId",50).put("authorityEpoch",3).put("runtimeInstance","game:50:challenge:one");
        snapshot.putObject("challenge").put("instanceId","one").put("goal",10).put("progress",0);
        when(shared.sharedRouteSnapshot(any(),any(),any(),any())).thenReturn(snapshot);
        GameRuntimeSession session=runningSession(ballShakeConfig().replace("30","10"),"{\"runtimeAuthorityEpoch\":3,\"zeroChallengeInstance\":\"one\",\"zeroChallengeStarts\":{\"7\":{\"ballShake\":"+(NOW-1000)+"},\"8\":{\"ballShake\":"+(NOW-1000)+"}}}");
        session.setOwnerType(GameRuntimeSession.OWNER_TEAM);session.setOwnerId(88L);session.setPlayInstanceId("game:50:challenge:one");
        when(sessionMapper.selectAccessibleById(51L,7L)).thenReturn(session);when(sessionMapper.selectAccessibleById(51L,8L)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        final long[] total={0};final java.util.Set<String> keys=new java.util.HashSet<>();
        when(shared.recordSharedZeroChallengeResult(any(),any(),any(),any(),any(),any(),org.mockito.ArgumentMatchers.anyBoolean(),any(),org.mockito.ArgumentMatchers.anyBoolean(),any())).thenAnswer(call->{
            total[0]+=(Long)call.getArgument(7);keys.add(call.getArgument(5));return json.createObjectNode().put("instanceId","one").put("goal",10).put("progress",total[0]).put("success",total[0]>=10);
        });
        Map<String,PlayAttemptLog> attempts=new LinkedHashMap<>();
        when(attemptMapper.selectByIdempotencyKey(any())).thenAnswer(call->attempts.get(call.getArgument(0)));
        when(attemptMapper.insertPlayAttemptLog(any())).thenAnswer(call->{PlayAttemptLog log=call.getArgument(0);attempts.put(log.getIdempotencyKey(),log);return 1;});
        Map<String,Object> hits=new LinkedHashMap<>();hits.put("hits",99);
        assertThrows(IllegalArgumentException.class,()->service.act(7L,51L,0,"bad","SUBMIT_BALL_SHAKE",hits));assertEquals(0,total[0]);
        hits.put("hits",6);Map<String,Object> first=service.act(7L,51L,0,"same","SUBMIT_BALL_SHAKE",hits);
        assertEquals(6,((com.fasterxml.jackson.databind.JsonNode)first.get("challenge")).path("progress").asInt());
        assertEquals(json.writeValueAsString(first),json.writeValueAsString(service.act(7L,51L,0,"same","SUBMIT_BALL_SHAKE",hits)));assertEquals(6,total[0]);
        Map<String,Object> second=service.act(8L,51L,1,"same","SUBMIT_BALL_SHAKE",hits);
        assertEquals("COMPLETED",second.get("status"));assertEquals(12,total[0]);assertEquals(2,keys.size());
        verify(shared,never()).recordSharedRouteResult(any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test void challengeTimeoutWritesFreeRetryInsteadOfOrdinaryFailureEffects() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
        com.chengyinhub.business.service.IGameSessionRuntimeService shared=sharedRuntime();
        com.fasterxml.jackson.databind.node.ObjectNode snapshot=json.createObjectNode().put("teamId",88).put("gameSessionId",50).put("runtimeInstance","game:50:challenge:one");snapshot.putObject("challenge").put("instanceId","one");
        when(shared.sharedRouteSnapshot(any(),any(),any(),any())).thenReturn(snapshot);
        GameRuntimeSession session=runningSession(ballShakeConfig(),"{\"runtimeAuthorityEpoch\":3,\"zeroChallengeInstance\":\"one\"}");session.setPlayInstanceId("game:50:challenge:one");session.setOwnerType(GameRuntimeSession.OWNER_TEAM);session.setOwnerId(88L);session.setDeadlineAt(new Date(NOW-1));
        when(sessionMapper.selectAccessibleById(51L,MEMBER_ID)).thenReturn(session);when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(shared.recordSharedZeroChallengeResult(any(),any(),any(),any(),any(),any(),org.mockito.ArgumentMatchers.eq(false),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq(true),any())).thenReturn(json.createObjectNode().put("retry",true).put("success",false));
        assertEquals("FAILED",service.act(MEMBER_ID,51L,0,"timeout","SUBMIT_BALL_SHAKE",Collections.emptyMap()).get("status"));
        verify(shared).recordSharedZeroChallengeResult(any(),any(),any(),any(),any(),any(),org.mockito.ArgumentMatchers.eq(false),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq(true),any());
        verify(shared,never()).recordSharedRouteResult(any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    private GameRuntimeSession failedZeroChallenge(ITopicRouteRuntimeService route) throws Exception {
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);
        GameRuntimeSession old = runningSession(randomOnlyConfig(),
                "{\"originalRouteSessionId\":88,\"zeroChallengeInstance\":\"old\",\"challenge\":{\"instanceId\":\"old\",\"retry\":true,\"progress\":4}}");
        old.setPlayInstanceId("challenge:old"); old.setStatus(GameRuntimeSession.STATUS_FAILED); old.setVersion(3); old.setScore(7);
        when(route.genericPlayer(MEMBER_ID, TOPIC_ID, ACTIVITY_ID)).thenReturn(true);
        when(route.canPlay(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn(true);
        when(route.gameplaySessionId(MEMBER_ID, TOPIC_ID, ACTIVITY_ID)).thenReturn(88L);
        when(route.gameplayInstance(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn("challenge:next");
        when(route.zeroChallenge(MEMBER_ID, TOPIC_ID, ACTIVITY_ID, NODE_ID)).thenReturn(
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("instanceId", "next").put("deadlineAt", NOW + 60000));
        when(sessionMapper.selectLatestZeroChallenge(ACTIVITY_ID, TOPIC_ID, NODE_ID, GameRuntimeSession.OWNER_PLAYER,
                MEMBER_ID, null, 88L, null)).thenReturn(old);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(old);
        when(sessionMapper.selectByIdForUpdate(51L)).thenReturn(old);
        return old;
    }

    @Test
    void enteringZeroChallengeKeepsFailedReceiptWithoutStartingGeneratedNextAttempt() throws Exception {
        GameRuntimeSession old = failedZeroChallenge(mock(ITopicRouteRuntimeService.class));
        String original = old.getStateJson();
        Map<String, Object> view = service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null);
        assertEquals(51L, view.get("sessionId")); assertEquals("FAILED", view.get("status"));
        assertEquals(Boolean.TRUE, view.get("canRetry")); assertEquals(7, view.get("score"));
        assertEquals(original, old.getStateJson());
        verify(sessionMapper, never()).insert(any()); verify(sessionMapper, never()).restartTerminalCas(any());
    }

    @Test
    void explicitZeroRetryCreatesNextInstancePreservesOldResultAndReplaysOriginalKey() throws Exception {
        GameRuntimeSession old = failedZeroChallenge(mock(ITopicRouteRuntimeService.class));
        AtomicReference<GameRuntimeSession> next = new AtomicReference<>();
        when(sessionMapper.insert(any())).thenAnswer(call -> { GameRuntimeSession row = call.getArgument(0); row.setId(52L); next.set(row); return 1; });
        when(sessionMapper.updateFeedbackStateCas(any())).thenReturn(1);
        AtomicReference<PlayAttemptLog> receipt = new AtomicReference<>();
        when(attemptMapper.insertPlayAttemptLog(any())).thenAnswer(call -> { receipt.set(call.getArgument(0)); return 1; });
        Map<String, Object> view = service.act(MEMBER_ID, 51L, 3, "explicit-zero", "RETRY", Collections.emptyMap());
        assertEquals(52L, view.get("sessionId")); assertEquals(51L, view.get("retrySourceSessionId"));
        assertEquals("RUNNING", view.get("status")); assertEquals("challenge:next", next.get().getPlayInstanceId());
        assertEquals(old.getConfigSnapshotJson(), next.get().getConfigSnapshotJson());
        assertEquals(GameRuntimeSession.STATUS_FAILED, old.getStatus()); assertEquals(7, old.getScore());
        assertTrue(old.getStateJson().contains("\"progress\":4"));
        assertTrue(next.get().getStateJson().contains("\"originalRouteSessionId\":88"));
        when(attemptMapper.selectByIdempotencyKey(receipt.get().getIdempotencyKey())).thenReturn(receipt.get());
        Map<String, Object> replay = service.act(MEMBER_ID, 51L, 3, "explicit-zero", "RETRY", Collections.emptyMap());
        assertEquals(52L, ((Number) replay.get("sessionId")).longValue());
        verify(sessionMapper).insert(any()); verify(sessionMapper, never()).restartTerminalCas(any());
    }

    @Test
    void zeroChallengePartialCompletionWaitsForExplicitNextAttemptAndPreservesCompletions() throws Exception {
        GameRuntimeSession old = failedZeroChallenge(mock(ITopicRouteRuntimeService.class));
        old.setStatus(GameRuntimeSession.STATUS_COMPLETED);
        old.setStateJson(old.getStateJson().replace("\"retry\":true", "\"gameCompleted\":true,\"success\":false,\"completions\":1,\"requiredCompletions\":2"));
        Map<String, Object> view = service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null);
        assertEquals("COMPLETED", view.get("status")); assertEquals(Boolean.TRUE, view.get("canRetry"));
        assertEquals(Boolean.FALSE, view.get("readyForBase"), "恢复次数不足不能展示普通完成按钮");
        assertTrue(old.getStateJson().contains("\"completions\":1"));
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void zeroRetryRejectsOldVersionChangedRouteAndMissingPersistedReceipt() throws Exception {
        ITopicRouteRuntimeService route = mock(ITopicRouteRuntimeService.class);
        GameRuntimeSession old = failedZeroChallenge(route);
        assertThrows(ServiceException.class, () -> service.act(MEMBER_ID, 51L, 2, "old-zero", "RETRY", Collections.emptyMap()));
        when(route.gameplaySessionId(MEMBER_ID, TOPIC_ID, ACTIVITY_ID)).thenReturn(89L);
        assertThrows(ServiceException.class, () -> service.act(MEMBER_ID, 51L, 3, "foreign-zero", "RETRY", Collections.emptyMap()));
        verify(sessionMapper, never()).insert(any());
        when(route.gameplaySessionId(MEMBER_ID, TOPIC_ID, ACTIVITY_ID)).thenReturn(88L);
        when(sessionMapper.insert(any())).thenAnswer(call -> { ((GameRuntimeSession) call.getArgument(0)).setId(52L); return 1; });
        when(sessionMapper.updateFeedbackStateCas(any())).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> service.act(MEMBER_ID, 51L, 3, "no-receipt", "RETRY", Collections.emptyMap()));
        assertEquals(GameRuntimeSession.STATUS_FAILED, old.getStatus());
    }

    @Test
    void zeroTeamRetryRequiresCurrentLeaderUnderLockAndNeverResetsOldTeamState() throws Exception {
        com.chengyinhub.business.service.IGameSessionRuntimeService shared = sharedRuntime();
        com.fasterxml.jackson.databind.node.ObjectNode snapshot = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("teamId", 88).put("gameSessionId", 50).put("authorityEpoch", 3).put("runtimeInstance", "game:50:challenge:next");
        snapshot.putObject("challenge").put("instanceId", "next").put("deadlineAt", NOW + 60000);
        when(shared.sharedRouteSnapshot(any(), any(), any(), any())).thenReturn(snapshot);
        GameRuntimeSession old = runningSession(randomOnlyConfig(),
                "{\"originalGameSessionId\":50,\"runtimeAuthorityEpoch\":3,\"zeroChallengeInstance\":\"old\",\"challenge\":{\"instanceId\":\"old\"}}");
        old.setOwnerType(GameRuntimeSession.OWNER_TEAM); old.setOwnerId(88L); old.setStatus(GameRuntimeSession.STATUS_FAILED);
        old.setPlayInstanceId("game:50:challenge:old");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(old);
        com.chengyinhub.business.domain.PlayTeam team = new com.chengyinhub.business.domain.PlayTeam();
        team.setId(88L); team.setOwnerType(2); team.setOwnerId(ACTIVITY_ID); team.setStatus(2); team.setLeaderMemberId(MEMBER_ID + 1);
        when(teamMapper.selectPlayTeamByIdForUpdate(88L)).thenReturn(team);
        when(teamMapper.selectActiveMember(88L, MEMBER_ID)).thenReturn(new com.chengyinhub.business.domain.PlayTeamMember());
        String original = old.getStateJson();
        assertThrows(ServiceException.class, () -> service.act(MEMBER_ID, 51L, 0, "teammate-zero", "RETRY", Collections.emptyMap()));
        assertEquals(original, old.getStateJson());
        verify(sessionMapper, never()).insert(any()); verify(sessionMapper, never()).restartTerminalCas(any());
    }

    private String matchConfig() {
        return "{\"schemaVersion\":1,\"match\":{\"enabled\":true,\"prompt\":\"把店和招牌连起来\","
                + "\"xp\":25,"
                + "\"left\":[{\"id\":\"l1\",\"label\":\"甲\"},{\"id\":\"l2\",\"label\":\"乙\"}],"
                + "\"right\":[{\"id\":\"r1\",\"label\":\"一\"},{\"id\":\"r2\",\"label\":\"二\"}],"
                + "\"pairs\":[[\"l1\",\"r2\"],[\"l2\",\"r1\"]]}}";
    }

    @Test
    void matchAwardsXpOnceWhenEveryLineIsRight() {
        GameRuntimeSession session = runningSession(matchConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        playKit(service.act(MEMBER_ID, 51L, 0, "match-xp-wrong", "SUBMIT_MATCH",
                pairsPayload(new String[] {"l1", "r1"}, new String[] {"l2", "r2"})), "match");
        assertNull(session.getScore(), "连错不给分");

        playKit(service.act(MEMBER_ID, 51L, 1, "match-xp-ok", "SUBMIT_MATCH",
                pairsPayload(new String[] {"l1", "r2"}, new String[] {"l2", "r1"})), "match");
        assertEquals(25, session.getScore().intValue(), "全连对按 xp 加一次分");
    }

    @Test
    void matchPassesOnExactMappingRegardlessOfSubmissionOrder() {
        GameRuntimeSession session = runningSession(matchConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> match = playKit(service.act(MEMBER_ID, 51L, 0, "match-ok", "SUBMIT_MATCH",
                pairsPayload(new String[] {"l2", "r1"}, new String[] {"l1", "r2"})), "match");

        assertEquals(Boolean.TRUE, match.get("passed"));
        assertEquals(Boolean.FALSE, String.valueOf(session.getStateJson()).contains("pairs"));
        assertFalse(String.valueOf(match).contains("pairs"));
    }

    @Test
    void matchFailsWhenAnySingleLineIsWrong() {
        GameRuntimeSession session = runningSession(matchConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> match = playKit(service.act(MEMBER_ID, 51L, 0, "match-wrong", "SUBMIT_MATCH",
                pairsPayload(new String[] {"l1", "r1"}, new String[] {"l2", "r2"})), "match");

        assertEquals(Boolean.FALSE, match.get("lastCorrect"));
        assertEquals(Boolean.FALSE, match.get("passed"));
        assertEquals(1, ((Number) match.get("attempts")).intValue());
    }

    @Test
    void matchRejectsUnknownOrRepeatedEndpoints() {
        GameRuntimeSession session = runningSession(matchConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        assertThrows(IllegalArgumentException.class, () -> service.act(MEMBER_ID, 51L, 0, "match-bad-1",
                "SUBMIT_MATCH", pairsPayload(new String[] {"l1", "ghost"}, new String[] {"l2", "r1"})));
        assertThrows(IllegalArgumentException.class, () -> service.act(MEMBER_ID, 51L, 0, "match-bad-2",
                "SUBMIT_MATCH", pairsPayload(new String[] {"l1", "r1"}, new String[] {"l1", "r2"})));
        verify(sessionMapper, never()).updateStateCas(any());
    }

    private Map<String, Object> pairsPayload(String[]... pairs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
        for (String[] pair : pairs) rows.add(java.util.Arrays.asList(pair));
        payload.put("pairs", rows);
        return payload;
    }

    private String classifyConfig() {
        return "{\"schemaVersion\":1,\"classify\":{\"enabled\":true,\"prompt\":\"分冷热\","
                + "\"xp\":25,"
                + "\"bins\":[{\"id\":\"hot\",\"label\":\"热的\"},{\"id\":\"iced\",\"label\":\"冰的\"}],"
                + "\"items\":[{\"id\":\"americano\",\"label\":\"美式\"},{\"id\":\"frappe\",\"label\":\"星冰乐\"}],"
                + "\"answer\":{\"americano\":\"hot\",\"frappe\":\"iced\"}}}";
    }

    @Test
    void classifyAwardsXpOnceWhenEveryCardLandsRight() {
        GameRuntimeSession session = runningSession(classifyConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        playKit(service.act(MEMBER_ID, 51L, 0, "classify-xp-wrong", "SUBMIT_CLASSIFY",
                placementPayload(new String[] {"americano", "iced"}, new String[] {"frappe", "iced"})),
                "classify");
        assertNull(session.getScore(), "分错不给分");

        playKit(service.act(MEMBER_ID, 51L, 1, "classify-xp-ok", "SUBMIT_CLASSIFY",
                placementPayload(new String[] {"americano", "hot"}, new String[] {"frappe", "iced"})),
                "classify");
        assertEquals(25, session.getScore().intValue(), "全分对按 xp 加一次分");
    }

    @Test
    void classifyPassesOnlyWhenEveryCardLandsInItsBin() {
        GameRuntimeSession session = runningSession(classifyConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> classify = playKit(service.act(MEMBER_ID, 51L, 0, "classify-ok", "SUBMIT_CLASSIFY",
                placementPayload(new String[] {"americano", "hot"}, new String[] {"frappe", "iced"})), "classify");

        assertEquals(Boolean.TRUE, classify.get("passed"));
        assertFalse(String.valueOf(classify).contains("\"answer\""));
        assertFalse(String.valueOf(session.getStateJson()).contains("\"answer\""));
    }

    @Test
    void classifyFailsWhenAnySingleCardIsMisplaced() {
        GameRuntimeSession session = runningSession(classifyConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> classify = playKit(service.act(MEMBER_ID, 51L, 0, "classify-wrong", "SUBMIT_CLASSIFY",
                placementPayload(new String[] {"americano", "iced"}, new String[] {"frappe", "iced"})), "classify");

        assertEquals(Boolean.FALSE, classify.get("lastCorrect"));
        assertEquals(Boolean.FALSE, classify.get("passed"));
    }

    @Test
    void classifyRejectsMissingCardsAndUnknownBins() {
        GameRuntimeSession session = runningSession(classifyConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        assertThrows(IllegalArgumentException.class, () -> service.act(MEMBER_ID, 51L, 0, "classify-bad-1",
                "SUBMIT_CLASSIFY", placementPayload(new String[] {"americano", "hot"})));
        assertThrows(IllegalArgumentException.class, () -> service.act(MEMBER_ID, 51L, 0, "classify-bad-2",
                "SUBMIT_CLASSIFY", placementPayload(new String[] {"americano", "warm"},
                        new String[] {"frappe", "iced"})));
        verify(sessionMapper, never()).updateStateCas(any());
    }

    private Map<String, Object> placementPayload(String[]... entries) {
        Map<String, Object> placement = new LinkedHashMap<>();
        for (String[] entry : entries) placement.put(entry[0], entry[1]);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("placement", placement);
        return payload;
    }

    // ==================== R16 时间轴对照题:两个版本逐条并排 ====================

    private String compareConfig() {
        return "{\"schemaVersion\":1,\"compare\":{\"enabled\":true,\"prompt\":\"找出两版对不上的地方\","
                + "\"xp\":25,\"maxAttempts\":2,"
                + "\"left\":{\"label\":\"版本 A\",\"items\":["
                + "{\"id\":\"a1\",\"time\":\"14:00\",\"text\":\"取号\"},"
                + "{\"id\":\"a2\",\"time\":\"14:10\",\"text\":\"称重\"}]},"
                + "\"right\":{\"label\":\"版本 B\",\"items\":["
                + "{\"id\":\"b1\",\"time\":\"14:00\",\"text\":\"取号\"},"
                + "{\"id\":\"b2\",\"time\":\"14:15\",\"text\":\"送检\"}]},"
                + "\"answer\":[\"a2\",\"b2\"]}}";
    }

    @Test
    void compareAwardsXpOnceWhenTheMarkedSetMatches() {
        GameRuntimeSession session = runningSession(compareConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        playKit(service.act(MEMBER_ID, 51L, 0, "compare-xp-wrong", "SUBMIT_COMPARE",
                markedPayload("b2")), "compare");
        assertNull(session.getScore(), "少标不给分");

        playKit(service.act(MEMBER_ID, 51L, 1, "compare-xp-ok", "SUBMIT_COMPARE",
                markedPayload("b2", "a2")), "compare");
        assertEquals(25, session.getScore().intValue(), "全标对按 xp 加一次分");
    }

    @Test
    void comparePassesOnlyOnExactSetAndNeverShipsTheAnswer() {
        GameRuntimeSession session = runningSession(compareConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> compare = playKit(service.act(MEMBER_ID, 51L, 0, "compare-ok", "SUBMIT_COMPARE",
                markedPayload("b2", "a2")), "compare");

        assertEquals(Boolean.TRUE, compare.get("passed"));
        assertEquals(Boolean.TRUE, compare.get("finished"));
        assertEquals(Boolean.TRUE, compare.get("lastCorrect"));
        // 回执只给玩家自己的标记与剩余次数,正确的那些一条都不给
        assertEquals(java.util.Arrays.asList("b2", "a2"), compare.get("lastMarked"));
        assertEquals(1, ((Number) compare.get("remainingAttempts")).intValue());
        assertFalse(String.valueOf(compare).contains("\"answer\""));
        assertFalse(String.valueOf(session.getStateJson()).contains("\"answer\""));
    }

    @Test
    void compareFailsOnMissingOrExtraMarks() {
        for (Map<String, Object> payload : java.util.Arrays.asList(
                markedPayload("b2"), markedPayload("a2", "b2", "b1"))) {
            GameRuntimeSession session = runningSession(compareConfig(), "{}");
            when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
            when(sessionMapper.updateStateCas(any())).thenReturn(1);
            when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);
            Map<String, Object> compare = playKit(service.act(MEMBER_ID, 51L, 0,
                    "compare-wrong-" + payload.hashCode(), "SUBMIT_COMPARE", payload), "compare");
            assertEquals(Boolean.FALSE, compare.get("lastCorrect"), String.valueOf(payload));
            assertEquals(Boolean.FALSE, compare.get("passed"), String.valueOf(payload));
            assertEquals(Boolean.FALSE, compare.get("finished"), String.valueOf(payload));
            assertEquals(1, ((Number) compare.get("attempts")).intValue(), String.valueOf(payload));
        }
    }

    @Test
    void compareFinishesTheRoundOnceAttemptsRunOut() {
        GameRuntimeSession session = runningSession(compareConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        playKit(service.act(MEMBER_ID, 51L, 0, "compare-a", "SUBMIT_COMPARE", markedPayload("b2")), "compare");
        Map<String, Object> second = playKit(service.act(MEMBER_ID, 51L, 1, "compare-b", "SUBMIT_COMPARE",
                markedPayload("a1")), "compare");

        assertEquals(Boolean.TRUE, second.get("finished"));
        assertEquals(Boolean.FALSE, second.get("passed"));
        assertEquals(0, ((Number) second.get("remainingAttempts")).intValue());
        // 收摊之后再交直接拒,不会靠重标刷出"标对了"
        assertThrows(IllegalStateException.class, () -> service.act(MEMBER_ID, 51L, 2, "compare-c",
                "SUBMIT_COMPARE", markedPayload("a2", "b2")));
    }

    @Test
    void compareRejectsUnknownOrRepeatedMarksAsBadInput() {
        GameRuntimeSession session = runningSession(compareConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        assertThrows(IllegalArgumentException.class, () -> service.act(MEMBER_ID, 51L, 0, "compare-bad-1",
                "SUBMIT_COMPARE", markedPayload("ghost")));
        assertThrows(IllegalArgumentException.class, () -> service.act(MEMBER_ID, 51L, 0, "compare-bad-2",
                "SUBMIT_COMPARE", markedPayload("a2", "a2")));
        assertEquals("{}", session.getStateJson());
        verify(sessionMapper, never()).updateStateCas(any());
    }

    private Map<String, Object> markedPayload(String... ids) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("marked", java.util.Arrays.asList(ids));
        return payload;
    }

    private String multiPickQaConfig() {
        return "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"multi\":true,"
                + "\"title\":\"哪些是冷萃?\",\"reveal\":true,\"maxTries\":3,\"xp\":20,\"options\":["
                + "{\"id\":\"a\",\"label\":\"冷萃\",\"correct\":true,\"fb\":\"对,冷萃是冷的。\"},"
                + "{\"id\":\"b\",\"label\":\"拿铁\",\"correct\":false,\"fb\":\"拿铁是热的。\"},"
                + "{\"id\":\"c\",\"label\":\"冰滴\",\"correct\":true,\"fb\":\"对,冰滴也是。\"}]}}";
    }

    @Test
    void advancedQaCompletionRequiresTheServerFinishedStateForTextAndChoice() {
        String textConfig = "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\","
                + "\"title\":\"本地回归题\",\"answerText\":\"CY150\"}}";
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        for (String config : java.util.Arrays.asList(textConfig, multiPickQaConfig())) {
            when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(config));
            GameRuntimeSession session = runningSession(config, "{\"qa\":{\"finished\":false}}");
            when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);
            assertThrows(ServiceException.class,
                    () -> service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
            session.setStateJson("{\"qa\":{\"finished\":true,\"passed\":true}}");
            service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID);
        }
        verify(sessionMapper, org.mockito.Mockito.times(2)).updateStateCas(any());
    }

    @Test
    void multiPickPassesOnlyWhenTheChosenSetEqualsTheAnswerSet() {
        GameRuntimeSession session = runningSession(multiPickQaConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> qa = playKit(service.act(MEMBER_ID, 51L, 0, "multi-ok", "SUBMIT_QA",
                optionIdsPayload("a", "c")), "qa");

        assertEquals(Boolean.TRUE, qa.get("passed"));
        assertEquals(Boolean.TRUE, qa.get("finished"));
        assertEquals(Boolean.TRUE, qa.get("multi"));
        assertEquals(java.util.Arrays.asList("a", "c"), qa.get("answerIds"));
    }

    @Test
    void multiPickKeepsThreeImagesPerSelectedOptionAndRevealsAllAnswersOnlyWhenExhaustedAndEnabled() {
        String sixImages = multiPickQaConfig()
                .replace("\"id\":\"a\"", "\"fbImgs\":[\"/profile/qa/a1.jpg\",\"/profile/qa/a2.jpg\",\"/profile/qa/a3.jpg\"],\"id\":\"a\"")
                .replace("\"id\":\"c\"", "\"fbImgs\":[\"/profile/qa/c1.jpg\",\"/profile/qa/c2.jpg\",\"/profile/qa/c3.jpg\"],\"id\":\"c\"")
                .replace("\"id\":\"b\"", "\"fbImgs\":[\"/profile/qa/b1.jpg\"],\"id\":\"b\"");
        GameRuntimeSession passed = runningSession(sixImages, "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(passed);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);
        Map<String, Object> ok = playKit(service.act(MEMBER_ID, 51L, 0, "six-ok", "SUBMIT_QA", optionIdsPayload("c", "a")), "qa");
        assertEquals(java.util.Arrays.asList("/profile/qa/a1.jpg", "/profile/qa/a2.jpg", "/profile/qa/a3.jpg",
                "/profile/qa/c1.jpg", "/profile/qa/c2.jpg", "/profile/qa/c3.jpg"), ok.get("lastFeedbackImages"), "两个所选选项各 3 张，共 6 张；未选 b 的图不出现");

        String oneTry = sixImages.replace("\"maxTries\":3", "\"maxTries\":1");
        GameRuntimeSession revealed = runningSession(oneTry, "{}");
        when(sessionMapper.selectAccessibleById(52L, MEMBER_ID)).thenReturn(revealed);
        Map<String, Object> wrong = playKit(service.act(MEMBER_ID, 52L, 0, "reveal-on", "SUBMIT_QA", optionIdsPayload("a", "b")), "qa");
        assertEquals(Boolean.TRUE, wrong.get("finished"));
        assertEquals(java.util.Arrays.asList("a", "c"), wrong.get("answerIds"));
        assertEquals(java.util.Arrays.asList("a", "b"), wrong.get("lastOptionIds"), "玩家选择与正确答案分开回读");
        assertEquals(java.util.Arrays.asList("/profile/qa/a1.jpg", "/profile/qa/a2.jpg", "/profile/qa/a3.jpg", "/profile/qa/b1.jpg"), wrong.get("lastFeedbackImages"));

        GameRuntimeSession hidden = runningSession(oneTry.replace("\"reveal\":true", "\"reveal\":false"), "{}");
        when(sessionMapper.selectAccessibleById(53L, MEMBER_ID)).thenReturn(hidden);
        Map<String, Object> noReveal = playKit(service.act(MEMBER_ID, 53L, 0, "reveal-off", "SUBMIT_QA", optionIdsPayload("a", "b")), "qa");
        assertEquals(Boolean.TRUE, noReveal.get("finished"));
        assertFalse(noReveal.containsKey("answerIds") || noReveal.containsKey("answerId"), "未开亮答案不下发");
    }

    @Test
    void judgeOffNeverProjectsRetainedAnswerFieldsEvenIfStateCarriesThem() {
        GameRuntimeSession session = runningSession(noJudgeQaConfig().replace("\"multi\":false", "\"multi\":true"),
                "{\"qa\":{\"attempts\":1,\"finished\":true,\"passed\":true,\"lastOptionId\":\"a\",\"answerId\":\"a\",\"answerIds\":[\"a\",\"b\"]}}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        Map<String, Object> qa = playKit(service.state(MEMBER_ID, 51L), "qa");
        assertEquals(Boolean.FALSE, qa.get("judge"));
        assertFalse(qa.containsKey("answerId") || qa.containsKey("answerIds"));
        assertEquals("a", qa.get("lastOptionId"));
    }

    @Test
    void multiPickFailsOnMissingOrExtraSelections() {
        for (Map<String, Object> payload : java.util.Arrays.asList(
                optionIdsPayload("a"), optionIdsPayload("a", "b", "c"))) {
            GameRuntimeSession session = runningSession(multiPickQaConfig(), "{}");
            when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
            when(sessionMapper.updateStateCas(any())).thenReturn(1);
            when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);
            Map<String, Object> qa = playKit(service.act(MEMBER_ID, 51L, 0,
                    "multi-" + payload.hashCode(), "SUBMIT_QA", payload), "qa");
            assertEquals(Boolean.FALSE, qa.get("lastCorrect"), String.valueOf(payload));
            assertEquals(Boolean.FALSE, qa.get("passed"), String.valueOf(payload));
        }
    }

    @Test
    void multiPickRejectsRepeatedOrUnknownSelectionsAsBadInput() {
        // 重复选项 / 不存在的选项是坏输入,直接拒,不该被当成一次作答
        GameRuntimeSession session = runningSession(multiPickQaConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        assertThrows(IllegalArgumentException.class, () -> service.act(MEMBER_ID, 51L, 0, "multi-dup",
                "SUBMIT_QA", optionIdsPayload("a", "a", "c")));
        assertThrows(IllegalArgumentException.class, () -> service.act(MEMBER_ID, 51L, 0, "multi-ghost",
                "SUBMIT_QA", optionIdsPayload("a", "ghost")));
        assertEquals("{}", session.getStateJson());
        verify(sessionMapper, never()).updateStateCas(any());
    }

    // ===== 2026-10-11 选项问答:判断答案对错开关、选项反馈图片、完成后只读重入 =====
    private String noJudgeQaConfig() {
        return "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"judge\":false,\"multi\":false,"
                + "\"title\":\"茶馆最后该怎么办?\",\"reveal\":true,\"maxTries\":2,\"xp\":7,\"options\":["
                + "{\"id\":\"a\",\"label\":\"留下来\",\"correct\":false,\"fb\":\"灯又亮了很多年。\",\"fbImgs\":[\"https://img.example/a1.jpg\",\"https://img.example/a2.jpg\"]},"
                + "{\"id\":\"b\",\"label\":\"改成书店\",\"correct\":false,\"fb\":\"\",\"fbImgs\":[\"https://img.example/b1.jpg\"]},"
                + "{\"id\":\"c\",\"label\":\"关门\",\"correct\":false}]}}";
    }

    @Test
    void noJudgeQaFinishesOnAnySingleChoiceAndRecordsOnlyThatOptionsFeedback() {
        GameRuntimeSession session = runningSession(noJudgeQaConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> qa = playKit(service.act(MEMBER_ID, 51L, 0, "nojudge-b", "SUBMIT_QA",
                Collections.singletonMap("optionId", "b")), "qa");

        assertEquals(Boolean.TRUE, qa.get("passed"));
        assertEquals(Boolean.TRUE, qa.get("finished"));
        assertEquals(Boolean.FALSE, qa.get("judge"));
        assertEquals("b", qa.get("lastOptionId"));
        assertEquals(Collections.singletonList("https://img.example/b1.jpg"), qa.get("lastFeedbackImages"));
        assertFalse(qa.containsKey("answerId"), "不判断对错时没有正确答案可亮");
        assertEquals(7, session.getScore().intValue());
        for (Object option : (java.util.List<?>) qa.get("options")) {
            Map<?, ?> row = (Map<?, ?>) option;
            assertFalse(row.containsKey("fb") || row.containsKey("fbImgs") || row.containsKey("correct"), "选项不下发反馈和正确答案");
        }
        assertThrows(IllegalStateException.class, () -> service.act(MEMBER_ID, 51L, 1, "nojudge-again", "SUBMIT_QA",
                Collections.singletonMap("optionId", "a")), "完成后不能再选");
    }

    @Test
    void noJudgeQaRejectsMultiPayloadAndUnknownOption() {
        GameRuntimeSession session = runningSession(noJudgeQaConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        assertThrows(IllegalArgumentException.class, () -> service.act(MEMBER_ID, 51L, 0, "nojudge-multi", "SUBMIT_QA", optionIdsPayload("a", "b")));
        assertThrows(IllegalArgumentException.class, () -> service.act(MEMBER_ID, 51L, 0, "nojudge-ghost", "SUBMIT_QA", Collections.singletonMap("optionId", "ghost")));
        assertEquals("{}", session.getStateJson());
        verify(sessionMapper, never()).updateStateCas(any());
    }

    @Test
    void judgedQaStillRequiresTheCorrectOptionAndRecordsFeedbackImagesOfThePick() {
        String config = singlePickQaConfig().replace("\"id\":\"b\"", "\"fbImgs\":[\"/profile/qa/b.jpg\"],\"id\":\"b\"");
        GameRuntimeSession session = runningSession(config, "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);
        Map<String, Object> qa = playKit(service.act(MEMBER_ID, 51L, 0, "judged-b", "SUBMIT_QA",
                Collections.singletonMap("optionId", "b")), "qa");
        assertEquals(Boolean.FALSE, qa.get("passed"));
        assertFalse(qa.containsKey("judge"), "旧配置视图形状不变");
        assertEquals("b", qa.get("lastOptionId"));
        assertEquals(Collections.singletonList("/profile/qa/b.jpg"), qa.get("lastFeedbackImages"));
    }

    @Test
    void passedQaReopensReadOnlyForLegacyConfigsWhileFailedLegacyKeepsItsRetryPath() {
        GameRuntimeSession passed = runningSession(singlePickQaConfig(),
                "{\"qa\":{\"attempts\":1,\"finished\":true,\"passed\":true,\"lastCorrect\":true,\"lastOptionId\":\"a\",\"lastFeedback\":\"对\"}}");
        passed.setStatus(GameRuntimeSession.STATUS_COMPLETED);
        passed.setVersion(4);
        String before = passed.getStateJson();
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(passed);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(passed);

        Map<String, Object> view = service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null);

        Map<String, Object> qa = playKit(view, "qa");
        assertEquals("a", qa.get("lastOptionId"));
        assertEquals(Boolean.TRUE, qa.get("finished"));
        assertEquals(before, passed.getStateJson());
        verify(sessionMapper, never()).restartTerminalCas(any());
        verify(sessionMapper, never()).updateStateCas(any());
        verify(sessionMapper, never()).insert(any());

        GameRuntimeSession failed = runningSession(singlePickQaConfig(), "{\"qa\":{\"attempts\":2,\"finished\":true,\"passed\":false}}");
        failed.setStatus(GameRuntimeSession.STATUS_FAILED);
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(service, "settledReadOnly", failed), "旧配置答错后的重试行为不变");
        assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(service, "settledReadOnly", passed));
    }

    private Map<String, Object> optionIdsPayload(String... ids) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("optionIds", java.util.Arrays.asList(ids));
        return payload;
    }

    @Test
    void singlePickStillJudgesOneOptionAndNeverShipsTheMultiShape() {
        GameRuntimeSession session = runningSession(singlePickQaConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("optionId", "a");
        Map<String, Object> qa = playKit(service.act(MEMBER_ID, 51L, 0, "single-ok", "SUBMIT_QA", payload), "qa");

        assertEquals(Boolean.TRUE, qa.get("passed"));
        assertEquals("a", qa.get("answerId"));
        assertFalse(qa.containsKey("multi"));
        assertFalse(qa.containsKey("answerIds"));
    }

    private String singlePickQaConfig() {
        return "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"reveal\":true,"
                + "\"maxTries\":2,\"xp\":10,\"options\":["
                + "{\"id\":\"a\",\"label\":\"对\",\"correct\":true,\"fb\":\"对了\"},"
                + "{\"id\":\"b\",\"label\":\"错\",\"correct\":false,\"fb\":\"再想想\"}]}}";
    }

    @Test
    void newGamesAloneGateTheNodeUntilTheyAreActuallySolved() {
        // 只配排序的模板:没排对之前 completeGate 不放行 —— 漏了 mechanicsReady 的这一行,
        // 开局即可打卡,玩法静默不存在
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(sortConfig()));
        GameRuntimeSession session = runningSession(sortConfig(), "{}");
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
        assertEquals("高级玩法尚未完成", error.getMessage());
    }

    @Test
    void compareGameAloneGatesTheNodeUntilFinished() {
        // 只配对照题:没交完之前 completeGate 不放行 —— 漏了 mechanicsReady 的这一行,
        // 开局即可打卡,对照玩法静默不存在
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(compareConfig()));
        GameRuntimeSession session = runningSession(compareConfig(), "{}");
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
        assertEquals("高级玩法尚未完成", error.getMessage());
    }

    private String reactionConfig() {
        return "{\"schemaVersion\":1,\"reaction\":{\"enabled\":true,\"rounds\":3,"
                + "\"goalMs\":320,\"xp\":10}}";
    }

    @Test
    void profileExposesRequiredRulesAndRejectsBlankLegacyOptionalChoice() {
        String config = "{\"schemaVersion\":1,\"profile\":{\"enabled\":true,\"questions\":["
                + "{\"key\":\"name\",\"label\":\"姓名\",\"kind\":\"text\",\"required\":true,\"maxLength\":12},"
                + "{\"key\":\"job\",\"label\":\"梦想\",\"kind\":\"pick\",\"required\":false,"
                + "\"options\":[{\"key\":\"artist\",\"label\":\"画家\"}]}]}}";
        GameRuntimeSession session = runningSession(config, "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        Map<String, Object> profile = playKit(service.state(MEMBER_ID, 51L), "profile");
        java.util.List<?> questions = (java.util.List<?>) profile.get("questions");
        assertEquals(true, ((Map<?, ?>) questions.get(0)).get("required"));
        assertEquals(true, ((Map<?, ?>) questions.get(1)).get("required"));
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> invalidAnswers = new LinkedHashMap<>();
        invalidAnswers.put("name", "小满");
        invalidAnswers.put("job", "unknown");
        payload.put("answers", invalidAnswers);
        assertThrows(IllegalArgumentException.class, () -> service.act(MEMBER_ID, 51L, 0,
                "profile-invalid", "SUBMIT_PROFILE", payload));
        verify(sessionMapper, never()).updateStateCas(any());
        payload.put("answers", Collections.singletonMap("name", "小满"));
        assertThrows(IllegalArgumentException.class, () -> service.act(MEMBER_ID, 51L, 0,
                "profile-missing", "SUBMIT_PROFILE", payload));
        verify(sessionMapper, never()).updateStateCas(any());
        Map<String, Object> completeAnswers = new LinkedHashMap<>();
        completeAnswers.put("name", "小满");
        completeAnswers.put("job", "artist");
        payload.put("answers", completeAnswers);
        Map<String, Object> submitted = playKit(service.act(MEMBER_ID, 51L, 0,
                "profile-required", "SUBMIT_PROFILE", payload), "profile");
        assertEquals(true, submitted.get("done"));
        assertEquals("artist", ((Map<?, ?>) submitted.get("answers")).get("job"));
    }

    private String ballShakeConfig() {
        return "{\"schemaVersion\":1,\"ballShake\":{\"enabled\":true,\"goal\":30,\"xp\":10}}";
    }

    private String quietConfig() {
        return "{\"schemaVersion\":1,\"quietHold\":{\"enabled\":true,\"seconds\":15,\"xp\":10}}";
    }

    private String countdownConfig() {
        return "{\"schemaVersion\":1,\"countdown\":{\"enabled\":true,\"seconds\":30,"
                + "\"doneText\":\"时间到。\",\"xp\":10}}";
    }

    /** 玩法状态挂在 view.playKit.<玩法> 下,不在顶层 —— 顶层只放会话本身的字段 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> playKit(Map<String, Object> view, String game) {
        Map<String, Object> kit = (Map<String, Object>) view.get("playKit");
        return kit == null ? null : (Map<String, Object>) kit.get(game);
    }

    private String coinOnlyConfig() {
        return "{\"schemaVersion\":1,\"coinFlip\":{\"enabled\":true,\"xp\":10,"
                + "\"heads\":{\"label\":\"正面\",\"action\":\"这杯店家请\"},"
                + "\"tails\":{\"label\":\"反面\",\"action\":\"这杯你请\"}}}";
    }

    private String diceOnlyConfig(int count) {
        return "{\"schemaVersion\":1,\"diceRoll\":{\"enabled\":true,\"diceCount\":" + count
                + ",\"xp\":10,\"faces\":[\"跟店员说一句今天的天气\",\"换一个座位坐\","
                + "\"点一杯你没喝过的\",\"给同行的人拍一张\",\"把手机反扣五分钟\",\"再抽一张卡\"]}}";
    }

    private String randomOnlyConfig() {
        return "{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":1,\"items\":["
                + "{\"id\":\"a\",\"label\":\"A\",\"weight\":10},{\"id\":\"b\",\"label\":\"B\",\"weight\":1}]}}";
    }

    private String branchOnlyConfig() {
        return "{\"schemaVersion\":1,\"branch\":{\"enabled\":true,\"startStepId\":\"start\",\"steps\":["
                + "{\"id\":\"start\",\"terminal\":false,\"options\":[{\"id\":\"go\",\"label\":\"继续\",\"nextStepId\":\"end\"}]},"
                + "{\"id\":\"end\",\"terminal\":true,\"outcomeCode\":\"DONE\",\"options\":[]}]}}";
    }

    private String fullConfig() {
        return "{\"schemaVersion\":1,\"timer\":{\"enabled\":true,\"durationSeconds\":60,\"timeoutResult\":\"FAILED\"},"
                + "\"random\":{\"enabled\":true,\"drawCount\":1,\"items\":[{\"id\":\"a\",\"label\":\"A\",\"weight\":1}]},"
                + "\"branch\":{\"enabled\":true,\"startStepId\":\"start\",\"steps\":["
                + "{\"id\":\"start\",\"terminal\":false,\"options\":[{\"id\":\"go\",\"label\":\"继续\",\"nextStepId\":\"end\"}]},"
                + "{\"id\":\"end\",\"terminal\":true,\"outcomeCode\":\"DONE\",\"options\":[]}]}}";
    }

    // ==================== 罗盘指向 compass(现场感契约 §3) ====================
    //
    // 与估数同口径:没有对错、不限次数,对准过一次就算。服务端只复核
    // 「报来的读数确实在容差内」—— 防误发,不是防作弊(所以这段只许发 xp)。

    private String compassConfig() {
        return "{\"schemaVersion\":1,\"compass\":{\"enabled\":true,\"kicker\":\"把手机转向钟楼\","
                + "\"bearing\":135,\"tolerance\":15,\"holdSeconds\":3,"
                + "\"hint\":\"它比你想的更靠右\",\"xp\":8}}";
    }

    private String compassConfigAt(int bearing, int tolerance) {
        return "{\"schemaVersion\":1,\"compass\":{\"enabled\":true,\"bearing\":" + bearing
                + ",\"tolerance\":" + tolerance + ",\"holdSeconds\":3,\"xp\":8}}";
    }

    @Test
    void compassTargetIsDeliveredAtStartBecauseItIsThePrompt() {
        // 反面钉子(与 OVERLAY 一对):目标方位是题面,没对准之前就必须在回包里 ——
        // 玩家得先知道往哪转才谈得上对准
        GameRuntimeSession session = runningSession(compassConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);

        Map<String, Object> view = service.state(MEMBER_ID, 51L);
        Map<String, Object> cp = playKit(view, "compass");

        assertEquals(135, ((Number) cp.get("bearing")).intValue());
        assertEquals(15, ((Number) cp.get("tolerance")).intValue());
        assertEquals(3, ((Number) cp.get("holdSeconds")).intValue());
        assertEquals("把手机转向钟楼", cp.get("kicker"));
        assertEquals("它比你想的更靠右", cp.get("hint"));
        assertEquals(Boolean.FALSE, cp.get("done"), "开局谁都没对准,done 必须是 false");
    }

    @Test
    void unconfiguredCompassDoesNotAppearInTheKitAtAll() {
        // 负控素材:没开罗盘的会话,回包里不许凭空多出一个 compass 段
        GameRuntimeSession session = runningSession(quietConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);

        assertNull(playKit(service.state(MEMBER_ID, 51L), "compass"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bearing", 10);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "cp-off", "SUBMIT_COMPASS", payload));
        assertEquals("该玩法未开启罗盘指向", error.getMessage());
    }

    @Test
    void compassSubmitInToleranceCompletesAndAwardsOnce() {
        GameRuntimeSession session = runningSession(compassConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        // 142 与目标 135 差 7°,在容差 15 之内 —— 报的是读数不是目标值,严丝合缝反而可疑
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bearing", 142);
        Map<String, Object> view = service.act(MEMBER_ID, 51L, 0, "cp-1", "SUBMIT_COMPASS", payload);

        Map<String, Object> cp = playKit(view, "compass");
        assertEquals(Boolean.TRUE, cp.get("done"));
        assertEquals(8, session.getScore().intValue(), "对准一次按 xp 加一次分");
    }

    @Test
    void compassAngleWrapAroundIsTheShortArc() {
        // 契约 §6.2 钉死:359° 与 1° 相差 2° 而不是 358°。目标 2°、容差 10°,
        // 读数 352°(差 10°)必须过;351°(差 11°)必须拒 —— 拿线性减法做的话前一条会被误杀
        GameRuntimeSession pass = runningSession(compassConfigAt(2, 10), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(pass);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> near = new LinkedHashMap<>();
        near.put("bearing", 352);
        Map<String, Object> view = service.act(MEMBER_ID, 51L, 0, "cp-wrap", "SUBMIT_COMPASS", near);
        assertEquals(Boolean.TRUE, playKit(view, "compass").get("done"));

        GameRuntimeSession reject = runningSession(compassConfigAt(2, 10), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(reject);
        Map<String, Object> over = new LinkedHashMap<>();
        over.put("bearing", 351);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "cp-over", "SUBMIT_COMPASS", over));
        assertEquals("还没对准方向,再转一转", error.getMessage());
        assertNull(reject.getScore(), "没对准不该有任何分");
    }

    @Test
    void compassRejectsBearingOutsideItsDomain() {
        GameRuntimeSession session = runningSession(compassConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);

        Map<String, Object> tooBig = new LinkedHashMap<>();
        tooBig.put("bearing", 400);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "cp-400", "SUBMIT_COMPASS", tooBig));
        assertEquals("方位角须为 0 至 359 的整数", error.getMessage());

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.act(MEMBER_ID, 51L, 0, "cp-none", "SUBMIT_COMPASS",
                        Collections.emptyMap()));
        assertEquals("方位角须为 0 至 359 的整数", missing.getMessage());
    }

    @Test
    void compassStopsAcceptingAfterTheFirstAlign() {
        // 对准过一次就不再收:再交一个离谱读数既不报错也不重复发分 ——
        // 报错会让「进来看一眼」变成一次失败,发分就是刷分口
        GameRuntimeSession session = runningSession(compassConfig(),
                "{\"compass\":{\"bearing\":140,\"done\":true,\"awarded\":true}}");
        session.setScore(8);
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> far = new LinkedHashMap<>();
        far.put("bearing", 300);   // 与目标 135 差 165°,但已经 done —— 静默收下不改判
        Map<String, Object> view = service.act(MEMBER_ID, 51L, 0, "cp-again", "SUBMIT_COMPASS", far);

        assertEquals(Boolean.TRUE, playKit(view, "compass").get("done"));
        assertEquals(8, session.getScore().intValue(), "重交不许再发一分");
    }

    @Test
    void compassAloneGatesTheNodeUntilItIsAligned() {
        // 漏了 mechanicsReady 里 compass 那一行 = 只配罗盘的节点开局即可打卡,玩法静默不存在
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template(compassConfig()));
        GameRuntimeSession session = runningSession(compassConfig(), "{}");
        when(sessionMapper.selectPlayerByOwner(ACTIVITY_ID, TOPIC_ID, NODE_ID, MEMBER_ID)).thenReturn(session);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.completeGate(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID));
        assertEquals("高级玩法尚未完成", error.getMessage());
    }

    // ==================== 扫码第四档 OVERLAY(AR 契约 §2.2) ====================

    private String overlayScanConfig() {
        return "{\"schemaVersion\":1,\"scan\":{\"enabled\":true,\"kind\":\"OVERLAY\","
                + "\"overlayUrl\":\"https://cdn.chengyinhub.com/overlay/cat.png\",\"overlayScale\":75,"
                + "\"reply\":\"它就在这儿。\",\"xp\":10}}";
    }

    private void stubNodeCheckinCode(String code) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setTemplateId(TEMPLATE_ID);
        node.setCheckinCode(code);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);
    }

    @Test
    void scanOverlayUrlStaysHiddenUntilScanned() {
        // 与 reply 同一条防泄漏口径:扫之前的网络回包里搜不到叠加图
        GameRuntimeSession session = runningSession(overlayScanConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);

        Map<String, Object> scan = playKit(service.state(MEMBER_ID, 51L), "scan");

        assertEquals("OVERLAY", scan.get("kind"));
        assertEquals(Boolean.FALSE, scan.get("scanned"));
        assertFalse(scan.containsKey("overlayUrl"), "扫之前不得下发叠加图");
        assertFalse(scan.containsKey("reply"), "扫之前不得下发回复");
    }

    @Test
    void scanOverlayDeliveredAfterMatchingCode() {
        stubNodeCheckinCode("NODE-CODE");
        GameRuntimeSession session = runningSession(overlayScanConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "NODE-CODE");

        Map<String, Object> view = service.act(MEMBER_ID, 51L, 0, "scan-ok", "SUBMIT_SCAN", payload);
        Map<String, Object> scan = playKit(view, "scan");

        assertEquals(Boolean.TRUE, scan.get("scanned"));
        assertEquals("https://cdn.chengyinhub.com/overlay/cat.png", scan.get("overlayUrl"));
        assertEquals(75, ((Number) scan.get("overlayScale")).intValue());
        assertEquals("它就在这儿。", scan.get("reply"));
        assertEquals(10, ((Number) view.get("score")).intValue());
    }

    @Test
    void rescanNeverRedeliversOverlayTwiceOrDoublePaysXp() {
        stubNodeCheckinCode("NODE-CODE");
        GameRuntimeSession session = runningSession(overlayScanConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "NODE-CODE");

        service.act(MEMBER_ID, 51L, 0, "scan-a", "SUBMIT_SCAN", payload);
        // 再扫一次(另一把幂等键):if (scanned) return 保证不重复发分
        Map<String, Object> again = service.act(MEMBER_ID, 51L, 1, "scan-b", "SUBMIT_SCAN", payload);

        assertEquals(10, ((Number) again.get("score")).intValue(), "重复扫码不得再发一次 xp");
        assertEquals("https://cdn.chengyinhub.com/overlay/cat.png", playKit(again, "scan").get("overlayUrl"));
    }

    @Test
    void scanWithoutOverlayConfigNeverGrowsOverlayKeys() {
        // 负控素材:老 TEXT 模板的回包里不应出现 overlay 相关的键 —— 老三档形状一个字不变
        String config = "{\"schemaVersion\":1,\"scan\":{\"enabled\":true,\"kind\":\"TEXT\",\"reply\":\"到了。\",\"xp\":5}}";
        stubNodeCheckinCode("NODE-CODE");
        GameRuntimeSession session = runningSession(config, "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "NODE-CODE");

        Map<String, Object> scan = playKit(service.act(MEMBER_ID, 51L, 0, "scan-text", "SUBMIT_SCAN", payload), "scan");

        assertEquals("到了。", scan.get("reply"));
        assertFalse(scan.containsKey("overlayUrl"));
        assertFalse(scan.containsKey("overlayScale"));
    }

    // ==================== 显形档的真 AR:方式与识别图扫完才给 ====================

    private String arScanConfig(String arFields) {
        return "{\"schemaVersion\":1,\"scan\":{\"enabled\":true,\"kind\":\"OVERLAY\","
                + "\"overlayUrl\":\"https://cdn.chengyinhub.com/overlay/cat.png\"," + arFields + ",\"xp\":10}}";
    }

    @Test
    void arModeAndMarkerStayHiddenUntilScanned() {
        GameRuntimeSession session = runningSession(arScanConfig(
                "\"arMode\":\"MARKER\",\"markerUrl\":\"https://cdn.chengyinhub.com/painting.jpg\","
                        + "\"modelUrl\":\"https://cdn.chengyinhub.com/cat.glb\""), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);

        Map<String, Object> scan = playKit(service.state(MEMBER_ID, 51L), "scan");

        assertFalse(scan.containsKey("arMode"), "AR 在扫码之后才开始,扫前不需要也不下发");
        assertFalse(scan.containsKey("markerUrl"), "识别图与叠加图同一条扫前保密口径");
        assertFalse(scan.containsKey("modelUrl"), "3D 模型是扫完的奖励,扫前下发 = 到店当场作废");
    }

    @Test
    void arModeAndMarkerDeliveredWithTheOverlayAfterScan() {
        stubNodeCheckinCode("NODE-CODE");
        GameRuntimeSession session = runningSession(arScanConfig(
                "\"arMode\":\"MARKER\",\"markerUrl\":\"https://cdn.chengyinhub.com/painting.jpg\","
                        + "\"modelUrl\":\"https://cdn.chengyinhub.com/cat.glb\""), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "NODE-CODE");

        Map<String, Object> scan = playKit(service.act(MEMBER_ID, 51L, 0, "scan-ar", "SUBMIT_SCAN", payload), "scan");

        assertEquals("MARKER", scan.get("arMode"));
        assertEquals("https://cdn.chengyinhub.com/painting.jpg", scan.get("markerUrl"));
        assertEquals("https://cdn.chengyinhub.com/cat.glb", scan.get("modelUrl"));
    }

    @Test
    void overlayWithoutArModeReadsAsNoneSoOldConfigsKeepTheScreenOverlay() {
        // 存量显形配置没有 arMode:扫完回包里明确给 NONE,客户端照旧走屏幕叠加,不去试 AR
        stubNodeCheckinCode("NODE-CODE");
        GameRuntimeSession session = runningSession(overlayScanConfig(), "{}");
        when(sessionMapper.selectAccessibleById(51L, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "NODE-CODE");

        Map<String, Object> scan = playKit(service.act(MEMBER_ID, 51L, 0, "scan-old", "SUBMIT_SCAN", payload), "scan");

        assertEquals("NONE", scan.get("arMode"));
        assertFalse(scan.containsKey("markerUrl"));
    }
    @Test
    void defaultContentReadKeepsNullActivityAndLegacyConfigWithoutLiveFallback() {
        GameRuntimeSession row = runningSession("{\"frozen\":7}", "{}");
        row.setActivityId(0L); row.setPlayInstanceId("default");
        when(sessionMapper.selectDefaultMemberSessionsByTopic(0L, TOPIC_ID, MEMBER_ID)).thenReturn(List.of(row));
        JsonNode result = service.queryDefaultNodeContents(MEMBER_ID, null, TOPIC_ID).path(String.valueOf(NODE_ID));
        assertTrue(result.path("legacyNodeContent").asBoolean());
        assertEquals(7, result.path("nodeConfigs").path(String.valueOf(NODE_ID)).path("frozen").asInt());
        assertFalse(result.has("nodeAnswers"));
        verifyNoInteractions(nodeMapper, templateMapper);
        verify(sessionMapper, never()).insert(any());
        verify(sessionMapper, never()).updateStateCas(any());
    }

    @Test
    void defaultContentReadRejectsRowsFromAnotherRunOrOwner() {
        GameRuntimeSession row = runningSession("{}", "{}");
        row.setPlayInstanceId("new-run");
        when(sessionMapper.selectDefaultMemberSessionsByTopic(ACTIVITY_ID, TOPIC_ID, MEMBER_ID)).thenReturn(List.of(row));
        assertThrows(IllegalStateException.class, () -> service.queryDefaultNodeContents(MEMBER_ID, ACTIVITY_ID, TOPIC_ID));
        row.setPlayInstanceId("default"); row.setOwnerId(99L);
        assertThrows(IllegalStateException.class, () -> service.queryDefaultNodeContents(MEMBER_ID, ACTIVITY_ID, TOPIC_ID));
        row.setOwnerId(MEMBER_ID); row.setTopicId(999L);
        assertThrows(IllegalStateException.class, () -> service.queryDefaultNodeContents(MEMBER_ID, ACTIVITY_ID, TOPIC_ID));
        verifyNoInteractions(nodeMapper, templateMapper);
    }

    @Test
    void defaultContentReadPrefersOwnPlayerOtherwiseLastAccessibleTeam() {
        GameRuntimeSession team = runningSession("{\"source\":1}", "{}");
        team.setPlayInstanceId("default"); team.setOwnerType(GameRuntimeSession.OWNER_TEAM); team.setOwnerId(88L);
        GameRuntimeSession own = runningSession("{\"source\":2}", "{}"); own.setPlayInstanceId("default");
        GameRuntimeSession laterTeam = runningSession("{\"source\":3}", "{}");
        laterTeam.setPlayInstanceId("default"); laterTeam.setOwnerType(GameRuntimeSession.OWNER_TEAM); laterTeam.setOwnerId(89L);
        when(sessionMapper.selectDefaultMemberSessionsByTopic(ACTIVITY_ID, TOPIC_ID, MEMBER_ID)).thenReturn(List.of(team, own, laterTeam));
        String key = String.valueOf(NODE_ID);
        assertEquals(2, service.queryDefaultNodeContents(MEMBER_ID, ACTIVITY_ID, TOPIC_ID).path(key).path("nodeConfigs").path(key).path("source").asInt());
        when(sessionMapper.selectDefaultMemberSessionsByTopic(ACTIVITY_ID, TOPIC_ID, MEMBER_ID)).thenReturn(List.of(team, laterTeam));
        assertEquals(3, service.queryDefaultNodeContents(MEMBER_ID, ACTIVITY_ID, TOPIC_ID).path(key).path("nodeConfigs").path(key).path("source").asInt());
    }

    @Test
    void defaultContentReadRejectsMismatchedFrozenIdentityAndReturnsIndependentCopy() {
        String state = "{\"originalNodeContent\":{\"contentSnapshotVersion\":1,\"nodePresentation\":{\"id\":29,\"topicId\":19},\"templateId\":null}}";
        GameRuntimeSession row = runningSession("{}", state); row.setTemplateId(null); row.setPlayInstanceId("default");
        when(sessionMapper.selectDefaultMemberSessionsByTopic(ACTIVITY_ID, TOPIC_ID, MEMBER_ID)).thenReturn(List.of(row));
        JsonNode first = service.queryDefaultNodeContents(MEMBER_ID, ACTIVITY_ID, TOPIC_ID);
        ((com.fasterxml.jackson.databind.node.ObjectNode)first.path("29").path("nodeAnswers").path("29").path("nodePresentation")).put("topicId", 999);
        assertEquals(19, service.queryDefaultNodeContents(MEMBER_ID, ACTIVITY_ID, TOPIC_ID).path("29").path("nodeAnswers").path("29").path("nodePresentation").path("topicId").asInt());
        assertEquals(state, row.getStateJson());
        row.setTemplateId(TEMPLATE_ID);
        assertThrows(IllegalStateException.class, () -> service.queryDefaultNodeContents(MEMBER_ID, ACTIVITY_ID, TOPIC_ID));
        row.setTemplateId(null); row.setNodeId(30L);
        assertThrows(IllegalStateException.class, () -> service.queryDefaultNodeContents(MEMBER_ID, ACTIVITY_ID, TOPIC_ID));
        verifyNoInteractions(nodeMapper, templateMapper);
    }

}
