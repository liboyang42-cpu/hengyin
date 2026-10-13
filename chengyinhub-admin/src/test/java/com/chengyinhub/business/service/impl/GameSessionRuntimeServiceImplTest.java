package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.GameCommandReceipt;
import com.chengyinhub.business.domain.GamePlayerNodeOutcome;
import com.chengyinhub.business.domain.GameSession;
import com.chengyinhub.business.domain.GameStationRun;
import com.chengyinhub.business.domain.GameStoryState;
import com.chengyinhub.business.domain.GameTaskSubmission;
import com.chengyinhub.business.domain.GameTeamRoleAssignment;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.domain.dto.GameMerchantEntryView;
import com.chengyinhub.business.domain.dto.GameSessionCommand;
import com.chengyinhub.business.domain.dto.GameSessionView;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.GameSessionException;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.common.utils.DateUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.eq;

class GameSessionRuntimeServiceImplTest {

    private static final long ACTIVITY = 7001L;
    private static final long TOPIC = 8001L;
    private static final long CLUB = 9001L;
    private static final long SESSION = 6001L;
    private static final long NODE = 5001L;
    private static final long FALLBACK_NODE = 5002L;
    private static final long MERCHANT = 3001L;
    /** 商家员工(非店主)登录人:站点动作应落到其所属商家主体上。 */
    private static final long OPERATOR = 3901L;
    private static final long PLAYER = 4001L;
    private static final long TARGET_PLAYER = 4002L;
    private static final long DIRECTOR = 4101L;
    private static final long TEAM = 4501L;

    private GameSessionRuntimeServiceImpl service;
    private GameSessionRuntimeMapper runtimeMapper;
    private CmsActivityMapper activityMapper;
    private CmsTopicMapper topicMapper;
    private IClubMemberService clubMemberService;
    private PlayTeamMapper playTeamMapper;
    private IPlayerNodeProgressService playerNodeProgressService;
    private IImService imService;
    /** 照片上传事实替身:记录每次核对的 url|memberId;默认放行,负控用例单独换成拒绝/存储故障。 */
    private final List<String> verifiedPhotos = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new GameSessionRuntimeServiceImpl();
        runtimeMapper = mock(GameSessionRuntimeMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        com.chengyinhub.business.mapper.CmsRegistrationMapper registrations=mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.install(activityMapper,registrations);
        ReflectionTestUtils.setField(service,"currentPlayRegistrationMapper",registrations);
        topicMapper = mock(CmsTopicMapper.class);
        clubMemberService = mock(IClubMemberService.class);
        playTeamMapper = mock(PlayTeamMapper.class);
        playerNodeProgressService = mock(IPlayerNodeProgressService.class);
        imService = mock(IImService.class);
        ReflectionTestUtils.setField(service, "runtimeMapper", runtimeMapper);
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "topicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "clubMemberService", clubMemberService);
        ReflectionTestUtils.setField(service, "playTeamMapper", playTeamMapper);
        ReflectionTestUtils.setField(service, "playerNodeProgressService", playerNodeProgressService);
        ReflectionTestUtils.setField(service, "imService", imService);
        ReflectionTestUtils.setField(service, "clock",
                (com.chengyinhub.business.service.support.AdvancedGameClock)
                        () -> DateUtils.parseDate("2026-08-23 12:00:00"));
        verifiedPhotos.clear();
        usePhotoVerifier((url, memberId) -> verifiedPhotos.add(url + "|" + memberId));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activeActivity());
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(configuredTopic());
        when(runtimeMapper.insertPlayerNodeOutcome(any())).thenReturn(1);
        when(runtimeMapper.selectRole(SESSION, PLAYER)).thenReturn(role("DETECTIVE", "侦探"));
    }

    @Test
    void everyViewIncludesServerSnapshotAtWithSecondPrecision() throws Exception {
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        ReflectionTestUtils.setField(service, "clock",
                (com.chengyinhub.business.service.support.AdvancedGameClock)
                        () -> DateUtils.parseDate("2026-08-23 12:34:56"));

        GameSessionView view = service.view(GameActorContext.club(PLAYER), ACTIVITY);

        assertEquals("2026-08-23 12:34:56",
                view.getClass().getMethod("getSnapshotAt").invoke(view));
    }

    @Test
    void gameModuleAvailabilityGateUsesAuthoritativeFailClosedValidation() {
        assertFalse(service.isGameModuleConfigEnabled(null));
        assertFalse(service.isGameModuleConfigEnabled("{not-json"));
        assertFalse(service.isGameModuleConfigEnabled(
                "{\"schemaVersion\":1,\"roles\":[],\"stations\":[]}"));
        assertTrue(service.isGameModuleConfigEnabled(runtimeConfigJson()));
        assertTrue(service.isGameModuleConfigEnabled(fallbackPlanConfig()));
        assertTrue(service.isGameModuleConfigEnabled(hintConfigJson()));
        assertFalse(service.isGameModuleConfigEnabled(fallbackPlanConfig().replace(
                "\"status\":\"APPROVED\"", "\"status\":\"DRAFT\"")));
        assertFalse(service.isGameModuleConfigEnabled(hintConfigJson().replace(
                "\"level\":2", "\"level\":3")));
        assertFalse(service.isGameModuleConfigEnabled(runtimeConfigJson().replace(
                ",\"completionPolicy\":\"EVIDENCE_ONLY\"", "")));
    }

    @Test
    void merchantEntryListComesOnlyFromServerScopedStationAssignments() {
        GameMerchantEntryView entry = new GameMerchantEntryView();
        entry.setActivityId(ACTIVITY);
        entry.setTopicId(TOPIC);
        entry.setActivityName("商圈谜案季");
        entry.setStartAt(DateUtils.parseDate("2026-08-23 10:00:00"));
        entry.setEndAt(DateUtils.parseDate("2026-08-23 18:00:00"));
        entry.setStationCount(2);
        when(runtimeMapper.selectMerchantEntries(MERCHANT)).thenReturn(Collections.singletonList(entry));

        List<GameMerchantEntryView> result = service.merchantEntries(GameActorContext.merchant(MERCHANT));

        assertEquals(1, result.size());
        assertEquals(ACTIVITY, result.get(0).getActivityId());
        assertEquals(TOPIC, result.get(0).getTopicId());
        assertEquals(2, result.get(0).getStationCount());
        verify(runtimeMapper).selectMerchantEntries(MERCHANT);
    }

    @Test
    void playerCannotEnumerateMerchantStationEntries() {
        GameSessionException error = assertThrows(GameSessionException.class,
                () -> service.merchantEntries(GameActorContext.player(PLAYER)));

        assertEquals("GAME_FORBIDDEN_SCOPE", error.getErrorCode());
        verify(runtimeMapper, never()).selectMerchantEntries(anyLong());
    }

    @Test
    void merchantCannotMutateAnotherStoreStation() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, "ACTIVE"));

        GameSessionException error = assertThrows(GameSessionException.class,
                () -> service.command(GameActorContext.merchant(MERCHANT + 1),
                        command("cross-store", 4, "STATION_PAUSE", mapOf("reasonCode", "STAFF_SHORTAGE"))));

        assertEquals("GAME_FORBIDDEN_SCOPE", error.getErrorCode());
        verify(runtimeMapper, never()).updateStationStatusCas(any(GameStationRun.class), anyLong(), anyString(),
                anyString(), anyInt(), any());
    }

    @Test
    void merchantOperatorEntriesResolveOwnerScopeAndRequireStationPermission() {
        GameMerchantEntryView entry = new GameMerchantEntryView();
        entry.setActivityId(ACTIVITY);
        entry.setTopicId(TOPIC);
        entry.setActivityName("商圈谜案季");
        entry.setStationCount(1);
        when(runtimeMapper.selectMerchantEntries(MERCHANT)).thenReturn(Collections.singletonList(entry));

        List<GameMerchantEntryView> result = service.merchantEntries(GameActorContext.merchant(
                OPERATOR, merchantOperator(MerchantRoleCode.MERCHANT_CHECKIN)));

        assertEquals(1, result.size(), "核销员有 VERIFY,应看到店主名下的站点入口");
        verify(runtimeMapper).selectMerchantEntries(MERCHANT);
        verify(runtimeMapper, never()).selectMerchantEntries(OPERATOR);

        GameSessionException denied = assertThrows(GameSessionException.class,
                () -> service.merchantEntries(GameActorContext.merchant(
                        OPERATOR, merchantOperator(MerchantRoleCode.MERCHANT_FINANCE))));
        assertEquals("GAME_FORBIDDEN_SCOPE", denied.getErrorCode(),
                "财务没有 VERIFY/PROJECT_MANAGE,不应看到站点入口");
    }

    @Test
    void checkinOperatorCannotAcceptStationButManagerCanForOwner() {
        GameSession session = runningSession(2);
        session.setStatus("PREPARING");
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.INVITED));

        GameSessionException denied = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.merchant(OPERATOR, merchantOperator(MerchantRoleCode.MERCHANT_CHECKIN)),
                command("checkin-accept", 2, "STATION_ACCEPT", Collections.emptyMap())));
        assertEquals("GAME_FORBIDDEN_SCOPE", denied.getErrorCode());
        verify(runtimeMapper, never()).updateStationStatusCas(any(GameStationRun.class), anyLong(),
                anyString(), anyString(), anyInt(), any());

        stubReceiptPersistence("manager-accept");
        when(runtimeMapper.updateStationStatusCas(any(GameStationRun.class), anyLong(),
                eq(GameStationRun.INVITED), eq(GameStationRun.ACCEPTED), anyInt(), any())).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        service.command(GameActorContext.merchant(OPERATOR, merchantOperator(MerchantRoleCode.MERCHANT_MANAGER)),
                command("manager-accept", 2, "STATION_ACCEPT", Collections.emptyMap()));

        org.mockito.ArgumentCaptor<Long> merchantScope = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(runtimeMapper).updateStationStatusCas(any(GameStationRun.class), merchantScope.capture(),
                eq(GameStationRun.INVITED), eq(GameStationRun.ACCEPTED), anyInt(), any());
        assertEquals(MERCHANT, merchantScope.getValue(),
                "站点 CAS 的 merchant_member_id 必须是商家主体(店主),不是操作员工本人");
    }

    @Test
    void checkinOperatorVerifiesSubmissionOnlyAtOwningStoreStation() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        GameTaskSubmission submission = new GameTaskSubmission();
        submission.setId(991L);
        submission.setGameSessionId(SESSION);
        submission.setNodeId(NODE);
        submission.setStationRunId(5101L);
        submission.setStatus("PENDING");
        when(runtimeMapper.selectSubmission(991L)).thenReturn(submission);
        Map<String, Object> approve = new LinkedHashMap<>();
        approve.put("submissionId", 991L);
        approve.put("decision", "APPROVE");
        MerchantAccessContext checkin = merchantOperator(MerchantRoleCode.MERCHANT_CHECKIN);

        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT + 1, GameStationRun.ACTIVE));
        stubReceiptPersistence("checkin-foreign-verify");
        GameSessionException foreign = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.merchant(OPERATOR, checkin),
                command("checkin-foreign-verify", 4, "VERIFY_SUBMISSION", approve)));
        assertEquals("GAME_FORBIDDEN_SCOPE", foreign.getErrorCode());

        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.PAUSED));
        stubReceiptPersistence("checkin-paused-verify");
        GameSessionException paused = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.merchant(OPERATOR, checkin),
                command("checkin-paused-verify", 4, "VERIFY_SUBMISSION", approve)));
        assertEquals("GAME_STATION_PAUSED", paused.getErrorCode(),
                "权限与站点归属都通过后,才轮到暂停状态拦核验");
    }

    @Test
    void ordinaryPlayerCannotRunDirectorCommand() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(readySession(2));
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(false);

        GameSessionException error = assertThrows(GameSessionException.class,
                () -> service.command(GameActorContext.club(PLAYER),
                        command("player-start", 2, "START", Collections.emptyMap())));

        assertEquals("GAME_FORBIDDEN_SCOPE", error.getErrorCode());
        verify(runtimeMapper, never()).updateSessionCas(anyLong(), anyInt(), anyString(), anyString(),
                any(), any(), any());
    }

    @Test
    void directorCannotStartBeforeTheActivityServiceWindow() {
        CmsActivity activity = activeActivity();
        activity.setStartDate(DateUtils.parseDate("2026-08-23 10:00:00"));
        activity.setEndDate(DateUtils.parseDate("2026-08-23 18:00:00"));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(readySession(2));
        when(runtimeMapper.selectStations(SESSION)).thenReturn(
                Collections.singletonList(station(MERCHANT, GameStationRun.READY)));
        when(runtimeMapper.countActiveTeams(ACTIVITY)).thenReturn(1);
        when(runtimeMapper.countTeamMembersWithoutRole(SESSION, ACTIVITY)).thenReturn(0);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        ReflectionTestUtils.setField(service, "clock",
                (com.chengyinhub.business.service.support.AdvancedGameClock)
                        () -> DateUtils.parseDate("2026-08-23 09:59:59"));
        stubReceiptPersistence("start-before-window");
        when(runtimeMapper.activateReadyStations(SESSION, DateUtils.parseDate("2026-08-23 09:59:59"))).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any())).thenReturn(1);

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(PLAYER),
                command("start-before-window", 2, "START", Collections.emptyMap())));

        assertEquals("GAME_ACTIVITY_OUTSIDE_SERVICE_WINDOW", error.getErrorCode());
        verify(runtimeMapper, never()).activateReadyStations(anyLong(), any());
    }

    @Test
    void directorCannotStartUntilEveryRequiredReadyStationWindowIsOpen() {
        GameSession session = readySession(2);
        GameStationRun open = station(MERCHANT, GameStationRun.READY);
        GameStationRun later = station(MERCHANT + 1, GameStationRun.READY);
        later.setId(5102L);
        later.setNodeId(FALLBACK_NODE);
        later.setNodeName("旧档案馆");
        later.setStationCode("ARCHIVE");
        later.setServiceStartAt(DateUtils.parseDate("2026-08-23 13:00:00"));
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Arrays.asList(open, later));
        when(runtimeMapper.countActiveTeams(ACTIVITY)).thenReturn(1);
        when(runtimeMapper.countTeamMembersWithoutRole(SESSION, ACTIVITY)).thenReturn(0);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        stubReceiptPersistence("start-before-station-window");
        when(runtimeMapper.activateReadyStations(SESSION, DateUtils.parseDate("2026-08-23 12:00:00")))
                .thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(DIRECTOR),
                command("start-before-station-window", 2, "START", Collections.emptyMap())));

        assertEquals("GAME_STATION_NOT_READY", error.getErrorCode());
        assertTrue(error.getMessage().contains("旧档案馆"));
        assertTrue(error.getMessage().contains("13:00:00"));
        verify(runtimeMapper, never()).activateReadyStations(anyLong(), any());
        verify(runtimeMapper, never()).updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void partialStationActivationCannotAdvanceTheSessionToRunning() {
        GameSession session = readySession(2);
        GameStationRun first = station(MERCHANT, GameStationRun.READY);
        GameStationRun second = station(MERCHANT + 1, GameStationRun.READY);
        second.setId(5102L);
        second.setNodeId(FALLBACK_NODE);
        second.setNodeName("旧档案馆");
        second.setStationCode("ARCHIVE");
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Arrays.asList(first, second));
        when(runtimeMapper.countActiveTeams(ACTIVITY)).thenReturn(1);
        when(runtimeMapper.countTeamMembersWithoutRole(SESSION, ACTIVITY)).thenReturn(0);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        stubReceiptPersistence("start-partial-activation");
        when(runtimeMapper.activateReadyStations(SESSION, DateUtils.parseDate("2026-08-23 12:00:00")))
                .thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(DIRECTOR),
                command("start-partial-activation", 2, "START", Collections.emptyMap())));

        assertEquals("GAME_STATE_CONFLICT", error.getErrorCode());
        verify(runtimeMapper, never()).updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any());
        verify(runtimeMapper, never()).completeReceipt(any());
    }

    @Test
    void sessionCasConflictRollsBackStationActivationAndDoesNotCompleteTheCommand() throws Exception {
        org.springframework.transaction.annotation.Transactional transaction = GameSessionRuntimeServiceImpl.class
                .getMethod("command", GameActorContext.class, GameSessionCommand.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertTrue(transaction != null && Arrays.asList(transaction.rollbackFor()).contains(Exception.class));

        GameSession session = readySession(2);
        GameStationRun first = station(MERCHANT, GameStationRun.READY);
        GameStationRun second = station(MERCHANT + 1, GameStationRun.READY);
        second.setId(5102L);
        second.setNodeId(FALLBACK_NODE);
        second.setNodeName("旧档案馆");
        second.setStationCode("ARCHIVE");
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Arrays.asList(first, second));
        when(runtimeMapper.countActiveTeams(ACTIVITY)).thenReturn(1);
        when(runtimeMapper.countTeamMembersWithoutRole(SESSION, ACTIVITY)).thenReturn(0);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        stubReceiptPersistence("start-session-cas-conflict");
        when(runtimeMapper.activateReadyStations(SESSION, DateUtils.parseDate("2026-08-23 12:00:00")))
                .thenReturn(2);
        when(runtimeMapper.updateSessionCas(SESSION, 2, GameSession.READY, GameSession.RUNNING,
                null, DIRECTOR, DateUtils.parseDate("2026-08-23 12:00:00"))).thenReturn(0);

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(DIRECTOR),
                command("start-session-cas-conflict", 2, "START", Collections.emptyMap())));

        assertEquals("GAME_STATE_CONFLICT", error.getErrorCode());
        assertEquals(GameSession.READY, session.getStatus());
        verify(runtimeMapper, never()).completeReceipt(any());
    }

    @Test
    void playerProjectionContainsOnlyOwnRoleSecrets() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(configJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        GameTeamRoleAssignment role = new GameTeamRoleAssignment();
        role.setRoleCode("DETECTIVE");
        role.setRoleName("侦探");
        role.setStatus("CONFIRMED");
        when(runtimeMapper.selectRole(SESSION, PLAYER)).thenReturn(role);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(station(MERCHANT, "ACTIVE")));
        stubArrived();

        GameSessionView view = service.view(GameActorContext.player(PLAYER), ACTIVITY);
        String projection = String.valueOf(view.getPlayer());

        assertTrue(projection.contains("侦探私密线索"));
        assertFalse(projection.contains("走私者私密线索"));
        assertFalse(projection.contains("moduleConfigSnapshotJson"));
    }

    @Test
    void playerStationIsNotPlayableOutsideActivityOrStationServiceWindow() {
        CmsActivity activity = activeActivity();
        activity.setStartDate(DateUtils.parseDate("2026-08-23 10:00:00"));
        activity.setEndDate(DateUtils.parseDate("2026-08-23 18:00:00"));
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        GameStationRun active = station(MERCHANT, GameStationRun.ACTIVE);
        active.setServiceStartAt(DateUtils.parseDate("2026-08-23 11:00:00"));
        active.setServiceEndAt(DateUtils.parseDate("2026-08-23 17:00:00"));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectRole(SESSION, PLAYER)).thenReturn(role("DETECTIVE", "侦探"));
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(active));
        ReflectionTestUtils.setField(service, "clock",
                (com.chengyinhub.business.service.support.AdvancedGameClock)
                        () -> DateUtils.parseDate("2026-08-23 18:00:00"));
        stubArrived();

        GameSessionView view = service.view(GameActorContext.player(PLAYER), ACTIVITY);
        Map<?, ?> node = (Map<?, ?>) ((List<?>) view.getPlayer().get("nodes")).get(0);

        assertEquals(Boolean.FALSE, node.get("playable"));
        assertFalse(node.containsKey("clue"));
        assertFalse(node.containsKey("playerTask"));
    }

    @Test
    void playerWriteIsRejectedBeforeReceiptClaimOutsideTheServiceWindow() {
        CmsActivity activity = activeActivity();
        activity.setStartDate(DateUtils.parseDate("2026-08-23 10:00:00"));
        activity.setEndDate(DateUtils.parseDate("2026-08-23 18:00:00"));
        GameStationRun active = station(MERCHANT, GameStationRun.ACTIVE);
        active.setServiceStartAt(DateUtils.parseDate("2026-08-23 11:00:00"));
        active.setServiceEndAt(DateUtils.parseDate("2026-08-23 17:00:00"));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(3));
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(active);
        ReflectionTestUtils.setField(service, "clock",
                (com.chengyinhub.business.service.support.AdvancedGameClock)
                        () -> DateUtils.parseDate("2026-08-23 18:00:00"));

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.player(PLAYER), playerSubmitCommand("submit-after-window")));

        assertEquals("GAME_ACTIVITY_OUTSIDE_SERVICE_WINDOW", error.getErrorCode());
        verify(runtimeMapper, never()).insertReceiptClaim(any());
        verify(runtimeMapper, never()).insertSubmission(any());
    }

    @Test
    void pausedStationCannotAcceptPlayerChoice() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(5));
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, "PAUSED"));

        GameSessionException error = assertThrows(GameSessionException.class,
                () -> service.command(GameActorContext.player(PLAYER),
                        command("paused-choice", 5, "PLAYER_CHOICE", mapOf("choiceId", "FOLLOW_NOTE"))));

        assertEquals("GAME_STATION_PAUSED", error.getErrorCode());
        verify(runtimeMapper, never()).updateStoryStateCas(any());
    }

    @Test
    void sameRequestReplaysOriginalReceiptWithoutSecondEffect() {
        GameSession session = runningSession(6);
        session.setModuleConfigSnapshotJson(fallbackPlanConfig());
        GameStationRun station = station(MERCHANT, "ACTIVE");
        GameStationRun target = fallbackStation();
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Arrays.asList(station, target));
        when(runtimeMapper.selectReceipt(ACTIVITY, MERCHANT, "pause-once")).thenReturn(null);
        when(runtimeMapper.insertReceiptClaim(any(GameCommandReceipt.class))).thenAnswer(invocation -> {
            GameCommandReceipt receipt = invocation.getArgument(0);
            receipt.setId(9901L);
            return 1;
        });
        when(runtimeMapper.updateStationStatusCas(any(GameStationRun.class), anyLong(), anyString(), anyString(),
                anyInt(), any())).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any())).thenReturn(1);
        when(runtimeMapper.completeReceipt(any(GameCommandReceipt.class))).thenReturn(1);

        Map<String, Object> pausePayload = new LinkedHashMap<>();
        pausePayload.put("reasonCode", "STAFF_SHORTAGE");
        pausePayload.put("resumeEta", "2026-08-24 10:00:00");
        pausePayload.put("fallbackPlanCode", "CLOCK_TO_ARCHIVE");
        pausePayload.put("fallbackPlanVersion", 1);
        GameSessionCommand request = command("pause-once", 6, "STATION_PAUSE", pausePayload);
        service.command(GameActorContext.merchant(MERCHANT), request);

        GameCommandReceipt persisted = new GameCommandReceipt();
        persisted.setId(9901L);
        persisted.setActivityId(ACTIVITY);
        persisted.setActorMemberId(MERCHANT);
        persisted.setRequestId("pause-once");
        persisted.setAction("STATION_PAUSE");
        persisted.setRequestHash(request.getRequestHash());
        persisted.setOutcome("APPLIED");
        persisted.setRevision(7);
        persisted.setResultJson("{}");
        when(runtimeMapper.selectReceipt(ACTIVITY, MERCHANT, "pause-once")).thenReturn(persisted);

        assertTrue(service.command(GameActorContext.merchant(MERCHANT), request).isReplayed());
        verify(runtimeMapper).updateStationStatusCas(any(GameStationRun.class), anyLong(), anyString(), anyString(),
                anyInt(), any());
    }

    @Test
    void sameRequestIdWithDifferentBodyConflicts() {
        GameCommandReceipt persisted = new GameCommandReceipt();
        persisted.setRequestId("same-key");
        persisted.setRequestHash("not-the-new-request-hash");
        persisted.setOutcome("APPLIED");
        when(runtimeMapper.selectReceipt(ACTIVITY, MERCHANT, "same-key")).thenReturn(persisted);

        GameSessionException error = assertThrows(GameSessionException.class,
                () -> service.command(GameActorContext.merchant(MERCHANT),
                        command("same-key", 1, "STATION_PAUSE", mapOf("reasonCode", "WEATHER"))));

        assertEquals("GAME_IDEMPOTENCY_CONFLICT", error.getErrorCode());
    }

    @Test
    void cancelledActivityRejectsEveryCommand() {
        CmsActivity cancelled = activeActivity();
        cancelled.setCancelTime(new Date());
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(cancelled);

        GameSessionException error = assertThrows(GameSessionException.class,
                () -> service.command(GameActorContext.club(PLAYER),
                        command("cancelled", 0, "PREPARE", Collections.emptyMap())));

        assertEquals("GAME_ACTIVITY_CANCELLED", error.getErrorCode());
        verify(runtimeMapper, never()).insertSession(any(GameSession.class));
    }

    @Test
    void staleClientRevisionIsRejectedBeforeReceiptClaimOrSideEffect() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(9));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, "ACTIVE"));

        GameSessionException error = assertThrows(GameSessionException.class,
                () -> service.command(GameActorContext.merchant(MERCHANT),
                        command("stale-revision", 8, "STATION_PAUSE", mapOf("reasonCode", "WEATHER"))));

        assertEquals("GAME_STATE_CONFLICT", error.getErrorCode());
        verify(runtimeMapper, never()).insertReceiptClaim(any(GameCommandReceipt.class));
        verify(runtimeMapper, never()).updateStationStatusCas(any(GameStationRun.class), anyLong(), anyString(),
                anyString(), anyInt(), any());
    }

    @Test
    void missingReceiptIsPendingWithoutClientSuppliedPerspective() {
        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt =
                service.receipt(new GameActorContext(PLAYER, null), ACTIVITY, "pending-1");

        assertEquals(ACTIVITY, receipt.getActivityId());
        assertEquals("pending-1", receipt.getRequestId());
        assertEquals("PENDING", receipt.getOutcome());
        assertNull(receipt.getReceiptId());
        verify(activityMapper, never()).selectCmsActivityById(anyLong());
    }

    @Test
    void failedCommandCanBeRecordedWithStructuredReasonAfterRollback() {
        GameSessionCommand failedCommand = command("pause-failed-audit", 4, "STATION_PAUSE",
                mapOf("reasonCode", "WEATHER"));
        failedCommand.setRequestHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(runtimeMapper.insertReceiptClaim(any())).thenAnswer(invocation -> {
            GameCommandReceipt row = invocation.getArgument(0);
            assertEquals("FAILED", row.getOutcome());
            assertEquals("GAME_BAD_REQUEST", row.getReasonCode());
            assertTrue(row.getResultJson().contains("请选择备用服务站点"));
            return 1;
        });

        service.recordFailure(GameActorContext.merchant(MERCHANT), failedCommand,
                new GameSessionException("GAME_BAD_REQUEST", "请选择备用服务站点"));

        verify(runtimeMapper).insertReceiptClaim(any());
    }

    @Test
    void merchantAcceptsInvitationBeforeReady() {
        GameSession session = runningSession(2);
        session.setStatus(GameSession.PREPARING);
        GameStationRun invited = station(MERCHANT, GameStationRun.INVITED);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(invited);
        stubReceiptPersistence("accept-1");
        when(runtimeMapper.updateStationStatusCas(any(GameStationRun.class), anyLong(), anyString(), anyString(),
                anyInt(), any())).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any())).thenReturn(1);

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.merchant(MERCHANT), command("accept-1", 2, "STATION_ACCEPT", Collections.emptyMap()));

        assertEquals("ACCEPTED", ((Map<?, ?>) receipt.getResult().get("station")).get("status"));
        verify(runtimeMapper).updateStationStatusCas(eq(invited), eq(MERCHANT), eq("INVITED"), eq("ACCEPTED"),
                eq(0), any());
    }

    @Test
    void declinedClosedStationKeepsSessionPreparingAndReturnsConcreteBlocker() {
        GameSession session = runningSession(2);
        session.setStatus(GameSession.PREPARING);
        GameStationRun invited = station(MERCHANT, GameStationRun.INVITED);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(invited);
        when(runtimeMapper.updateStationStatusCas(any(), anyLong(), anyString(), anyString(), anyInt(), any())).thenReturn(1);
        when(runtimeMapper.countUnreadyStations(SESSION)).thenReturn(1);
        when(runtimeMapper.countActiveTeams(ACTIVITY)).thenReturn(1);
        when(runtimeMapper.countTeamMembersWithoutRole(SESSION, ACTIVITY)).thenReturn(0);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any())).thenReturn(1);
        stubReceiptPersistence("decline-recoverable");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", "CAPACITY_UNAVAILABLE");
        payload.put("reason", "本场无法承接，由其余站点继续服务");

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.merchant(MERCHANT),
                command("decline-recoverable", 2, "STATION_DECLINE", payload));

        assertEquals(3, receipt.getRevision());
        verify(runtimeMapper).updateSessionCas(eq(SESSION), eq(2), eq(GameSession.PREPARING), eq(GameSession.PREPARING),
                eq(null), eq(MERCHANT), any());

        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(invited));
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        GameSessionView view = service.view(GameActorContext.club(DIRECTOR), ACTIVITY);
        Map<?, ?> readiness = (Map<?, ?>) view.getClub().get("readiness");
        assertTrue(String.valueOf(readiness.get("blockers")).contains("旧钟楼"));
        assertTrue(String.valueOf(readiness.get("blockers")).contains("已关闭"));
    }

    @Test
    void readySessionCannotStartWhenAnyStationIsClosed() {
        GameSession session = readySession(2);
        GameStationRun closed = station(MERCHANT, GameStationRun.CLOSED);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        when(runtimeMapper.countUnreadyStations(SESSION)).thenReturn(1);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(closed));
        stubReceiptPersistence("start-with-closed");

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(DIRECTOR),
                command("start-with-closed", 2, "START", Collections.emptyMap())));

        assertEquals("GAME_STATION_NOT_READY", error.getErrorCode());
        assertTrue(error.getMessage().contains("旧钟楼"));
        assertTrue(error.getMessage().contains("已关闭"));
        verify(runtimeMapper, never()).activateReadyStations(anyLong(), any());
    }

    @Test
    void readyRequiresAcceptedStationAndCompleteAuthoritativeConfig() {
        GameSession session = runningSession(2);
        session.setStatus(GameSession.PREPARING);
        GameStationRun accepted = station(MERCHANT, GameStationRun.ACCEPTED);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(accepted);
        stubReceiptPersistence("ready-invalid");

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.merchant(MERCHANT), command("ready-invalid", 2, "STATION_READY",
                        mapOf("capacity", 10))));

        assertEquals("GAME_STATION_CONFIG_INVALID", error.getErrorCode());
        verify(runtimeMapper, never()).updateStationStatusCas(any(GameStationRun.class), anyLong(), anyString(),
                anyString(), anyInt(), any());
    }

    @Test
    void prepareRejectsUnmappedOrMerchantlessRuntimeStation() {
        CmsTopic topic = configuredTopic();
        topic.setGameModuleConfigJson(runtimeConfigJson());
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        when(runtimeMapper.insertReceiptClaim(any())).thenReturn(1);
        when(runtimeMapper.insertSession(any())).thenAnswer(invocation -> {
            GameSession row = invocation.getArgument(0);
            row.setId(SESSION);
            return 1;
        });
        GameStationRun invalid = station(MERCHANT, GameStationRun.INVITED);
        invalid.setMerchantMemberId(null);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(invalid));

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(PLAYER), command("prepare-invalid", 0, "PREPARE", Collections.emptyMap())));

        assertEquals("GAME_CONFIG_INVALID", error.getErrorCode());
        verify(runtimeMapper, never()).completeReceipt(any());
    }

    @Test
    void prepareRejectsDuplicateRequiredChoiceIds() {
        CmsTopic topic = configuredTopic();
        topic.setGameModuleConfigJson(choiceConfigJson().replace("\"id\":\"ASK_CLERK\"",
                "\"id\":\"FOLLOW_NOTE\""));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        when(runtimeMapper.insertReceiptClaim(any())).thenReturn(1);

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(PLAYER), command("prepare-duplicate-choice", 0, "PREPARE",
                        Collections.emptyMap())));

        assertEquals("GAME_CONFIG_INVALID", error.getErrorCode());
        verify(runtimeMapper, never()).insertSession(any());
    }

    @Test
    void playerViewScopesTeamActionsAndSubmissionHistoryToActor() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(station(MERCHANT, "ACTIVE")));
        when(runtimeMapper.selectTeamActionProjection(SESSION, TEAM)).thenReturn(
                Collections.singletonList(mapOf("displayName", "同队玩家")));
        when(runtimeMapper.selectMySubmissionProjection(SESSION, PLAYER)).thenReturn(
                Collections.singletonList(mapOf("submissionId", 99L)));

        GameSessionView view = service.view(GameActorContext.player(PLAYER), ACTIVITY);
        String projection = String.valueOf(view.getPlayer());

        assertTrue(projection.contains("同队玩家"));
        assertTrue(projection.contains("99"));
        verify(runtimeMapper).selectTeamActionProjection(SESSION, TEAM);
        verify(runtimeMapper).selectMySubmissionProjection(SESSION, PLAYER);
    }

    @Test
    void hiddenLeaderboardIsOmittedEvenWhenRowsExist() {
        GameSession session = runningSession(3);
        session.setLeaderboardVisible(false);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectSessionLeaderboard(SESSION)).thenReturn(
                Collections.singletonList(mapOf("rank", 1)));

        GameSessionView view = service.view(GameActorContext.player(PLAYER), ACTIVITY);

        assertFalse(view.getPlayer().containsKey("leaderboard"));
        verify(runtimeMapper, never()).selectSessionLeaderboard(anyLong());
    }

    @Test
    void playerTaskProjectionIsAllowlistedAndSubmissionCodeMustMatchStation() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(station(MERCHANT, "ACTIVE")));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, "ACTIVE"));
        stubArrived();

        String projection = String.valueOf(service.view(GameActorContext.player(PLAYER), ACTIVITY).getPlayer());
        assertTrue(projection.contains("CLOCK_PHOTO"));
        assertTrue(projection.contains("拍下公开钟面"));
        assertTrue(projection.contains("PHOTO"));
        assertTrue(projection.contains("completionPolicy=EVIDENCE_ONLY"));
        assertFalse(projection.contains("secretAnswer"));
        assertFalse(projection.contains("expectedToken"));
        assertFalse(projection.contains("expectedHash"));
        assertFalse(projection.contains("merchantNote"));
        assertFalse(projection.contains("只核对玩家现场展示的钟面照片"));
        assertFalse(projection.contains("不要透露钟楼暗格答案"));

        stubReceiptPersistence("submit-wrong-task");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskCode", "OTHER_TASK");
        payload.put("evidenceUrls", Collections.singletonList("https://example.invalid/evidence.jpg"));
        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.player(PLAYER), command("submit-wrong-task", 3, "PLAYER_SUBMIT", payload)));
        assertEquals("GAME_BAD_REQUEST", error.getErrorCode());
        verify(runtimeMapper, never()).insertSubmission(any());
    }

    @Test
    void merchantAndClubStationsExposeAllowlistedOperationsCardWithoutAnswers() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        GameStationRun active = station(MERCHANT, GameStationRun.ACTIVE);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectMerchantStations(SESSION, MERCHANT)).thenReturn(Collections.singletonList(active));
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(active));
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);

        String merchantProjection = String.valueOf(
                service.view(GameActorContext.merchant(MERCHANT), ACTIVITY).getMerchant());
        String clubProjection = String.valueOf(
                service.view(GameActorContext.club(PLAYER), ACTIVITY).getClub());
        String projections = merchantProjection + clubProjection;

        assertTrue(projections.contains("CLOCK_PHOTO"));
        assertTrue(projections.contains("拍下公开钟面"));
        assertTrue(projections.contains("PHOTO"));
        assertTrue(projections.contains("只核对玩家现场展示的钟面照片"));
        assertTrue(projections.contains("不要透露钟楼暗格答案"));
        assertFalse(projections.contains("SECRET_ANSWER"));
        assertFalse(projections.contains("EXPECTED_TOKEN"));
        assertFalse(projections.contains("secretAnswer"));
        assertFalse(projections.contains("expectedToken"));
        assertFalse(projections.contains("expectedHash"));
    }

    @Test
    void merchantFallbackOptionsExcludeCrossSessionOwnAndInactiveStations() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(fallbackPlanConfig());
        GameStationRun own = station(MERCHANT, GameStationRun.ACTIVE);
        GameStationRun eligible = fallbackStation();
        GameStationRun sameMerchant = fallbackStation();
        sameMerchant.setNodeId(FALLBACK_NODE + 1);
        sameMerchant.setNodeName("同商家分站");
        sameMerchant.setStationCode("OWN_BRANCH");
        sameMerchant.setMerchantMemberId(MERCHANT);
        GameStationRun paused = fallbackStation();
        paused.setNodeId(FALLBACK_NODE + 2);
        paused.setNodeName("暂停站点");
        paused.setStationCode("PAUSED_BRANCH");
        paused.setStatus(GameStationRun.PAUSED);
        GameStationRun crossSession = fallbackStation();
        crossSession.setNodeId(FALLBACK_NODE + 3);
        crossSession.setNodeName("跨局站点");
        crossSession.setStationCode("CROSS_SESSION");
        crossSession.setGameSessionId(SESSION + 1);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectMerchantStations(SESSION, MERCHANT)).thenReturn(Collections.singletonList(own));
        when(runtimeMapper.selectStations(SESSION)).thenReturn(
                Arrays.asList(own, eligible, sameMerchant, paused, crossSession));

        GameSessionView view = service.view(GameActorContext.merchant(MERCHANT), ACTIVITY);
        List<?> options = (List<?>) view.getMerchant().get("fallbackOptions");
        Map<?, ?> option = (Map<?, ?>) options.get(0);

        assertEquals(1, options.size());
        assertEquals(NODE, option.get("sourceNodeId"));
        assertEquals("CLOCK_TO_ARCHIVE", option.get("planCode"));
        assertEquals(1, option.get("planVersion"));
        assertEquals(FALLBACK_NODE, option.get("nodeId"));
        assertEquals("旧档案馆", option.get("nodeName"));
        assertEquals("请前往旧档案馆完成替代任务", option.get("playerMessage"));
        assertEquals(6, option.size());
        assertFalse(String.valueOf(options).contains("同商家分站"));
        assertFalse(String.valueOf(options).contains("暂停站点"));
        assertFalse(String.valueOf(options).contains("跨局站点"));
    }

    @Test
    void merchantFallbackOptionsAreEmptyWhenNoOtherActiveStationExists() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        GameStationRun own = station(MERCHANT, GameStationRun.ACTIVE);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectMerchantStations(SESSION, MERCHANT)).thenReturn(Collections.singletonList(own));
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(own));

        GameSessionView view = service.view(GameActorContext.merchant(MERCHANT), ACTIVITY);

        assertTrue(((List<?>) view.getMerchant().get("fallbackOptions")).isEmpty());
    }

    @Test
    void pauseRejectsClientSelectedFallbackNodeBeforeMutation() {
        GameSession session = runningSession(4);
        GameStationRun source = station(MERCHANT, GameStationRun.ACTIVE);
        GameStationRun sameMerchant = fallbackStation();
        sameMerchant.setMerchantMemberId(MERCHANT);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(source);
        when(runtimeMapper.selectStation(SESSION, FALLBACK_NODE)).thenReturn(sameMerchant);
        stubReceiptPersistence("pause-own-fallback");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", "STAFF_SHORTAGE");
        payload.put("resumeEta", "2026-08-24 10:00:00");
        payload.put("fallbackNodeId", FALLBACK_NODE);

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.merchant(MERCHANT),
                command("pause-own-fallback", 4, "STATION_PAUSE", payload)));

        assertEquals("GAME_BAD_REQUEST", error.getErrorCode());
        verify(runtimeMapper, never()).updateStationStatusCas(any(), anyLong(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void pauseRejectsStaleOrUnapprovedFallbackPlanBeforeMutation() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson(fallbackPlanConfig());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.ACTIVE));
        stubReceiptPersistence("pause-stale-plan");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", "STAFF_SHORTAGE");
        payload.put("resumeEta", "2026-08-23 16:00:00");
        payload.put("fallbackPlanCode", "CLOCK_TO_ARCHIVE");
        payload.put("fallbackPlanVersion", 2);

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.merchant(MERCHANT),
                command("pause-stale-plan", 4, "STATION_PAUSE", payload)));

        assertEquals("GAME_FORBIDDEN_SCOPE", error.getErrorCode());
        verify(runtimeMapper, never()).updateStationStatusCas(any(), anyLong(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void pauseResolvesApprovedFallbackPlanByCodeAndVersionOnTheServer() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson(fallbackPlanConfig());
        GameStationRun source = station(MERCHANT, GameStationRun.ACTIVE);
        GameStationRun target = fallbackStation();
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(source);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Arrays.asList(source, target));
        when(runtimeMapper.updateStationStatusCas(any(), anyLong(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        stubReceiptPersistence("pause-approved-plan");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", "STAFF_SHORTAGE");
        payload.put("resumeEta", "2026-08-23 16:00:00");
        payload.put("fallbackPlanCode", "CLOCK_TO_ARCHIVE");
        payload.put("fallbackPlanVersion", 1);

        service.command(GameActorContext.merchant(MERCHANT),
                command("pause-approved-plan", 4, "STATION_PAUSE", payload));

        assertEquals(FALLBACK_NODE, source.getFallbackNodeId());
        assertEquals("CLOCK_TO_ARCHIVE", source.getFallbackPlanCode());
        assertEquals(1, source.getFallbackPlanVersion());
        assertEquals(GameStationRun.PAUSED, source.getStatus());
    }

    /** R9-38 正控:没有已批准备用方案也必须能紧急停业,且不许凭空造一个兜底站点。 */
    @Test
    void stationPauseWithoutApprovedFallbackStillStopsReception() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());   // 全配置里没有任何 fallbackPlans
        GameStationRun source = station(MERCHANT, GameStationRun.ACTIVE);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(source);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(source));
        when(runtimeMapper.updateStationStatusCas(any(), anyLong(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        stubReceiptPersistence("pause-no-fallback");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", "STAFF_SHORTAGE");
        payload.put("reason", "人员暂时离岗");
        payload.put("resumeEta", "2026-08-23 16:00:00");

        service.command(GameActorContext.merchant(MERCHANT),
                command("pause-no-fallback", 4, "STATION_PAUSE", payload));

        assertEquals(GameStationRun.PAUSED, source.getStatus());
        assertNull(source.getFallbackNodeId());
        assertNull(source.getFallbackPlanCode());
        assertNull(source.getFallbackPlanVersion());
    }

    /** R9-38 俱乐部侧同形:没有已批准备用方案时主办也要能停本站。 */
    @Test
    void clubStationPauseWithoutApprovedFallbackStillStopsReception() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        GameStationRun source = station(MERCHANT, GameStationRun.ACTIVE);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(source);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(source));
        when(runtimeMapper.updateStationStatusCas(any(), anyLong(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        stubReceiptPersistence("club-pause-no-fallback");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", "EQUIPMENT");
        payload.put("resumeEta", "2026-08-23 16:00:00");

        service.command(GameActorContext.club(DIRECTOR),
                command("club-pause-no-fallback", 4, "CLUB_STATION_PAUSE", payload));

        assertEquals(GameStationRun.PAUSED, source.getStatus());
        assertNull(source.getFallbackNodeId());
        assertNull(source.getFallbackPlanCode());
        verify(runtimeMapper).updateStationStatusCas(eq(source), eq(MERCHANT), eq(GameStationRun.ACTIVE),
                eq(GameStationRun.PAUSED), eq(0), any());
    }

    /** R9-38 收尾:暂停但没有兜底路线时,玩家仍要看得到恢复时间,而不是只看到一个"暂停"。 */
    @Test
    void pausedStationWithoutFallbackProjectsArrangementToPlayer() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        GameStationRun paused = station(MERCHANT, GameStationRun.PAUSED);
        paused.setPauseReasonCode("STAFF_SHORTAGE");
        paused.setPauseReason("人员暂时离岗");
        paused.setResumeEta(DateUtils.parseDate("2026-08-23 16:00:00"));
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(paused));

        GameSessionView view = service.view(GameActorContext.player(PLAYER), ACTIVITY);
        Map<?, ?> node = (Map<?, ?>) ((List<?>) view.getPlayer().get("nodes")).get(0);
        Map<?, ?> pause = (Map<?, ?>) node.get("pause");

        assertEquals(GameStationRun.PAUSED, node.get("status"));
        assertFalse(node.containsKey("fallback"));
        assertTrue(pause != null, "暂停站点必须向玩家下发暂停安排");
        assertEquals("STAFF_SHORTAGE", pause.get("reasonCode"));
        assertEquals("2026-08-23 16:00:00", pause.get("resumeEta"));
    }

    @Test
    void completedPlayerStationUsesAuthoritativeSubmissionTimeAndOrderWithoutEvidence() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(
                Collections.singletonList(station(MERCHANT, GameStationRun.ACTIVE)));
        GameTaskSubmission approved = new GameTaskSubmission();
        approved.setId(203L);
        approved.setStatus("APPROVED");
        approved.setEvidenceJson("[\"https://secret.example/evidence.jpg\"]");
        approved.setUpdateTime(DateUtils.parseDate("2026-08-23 10:15:37"));
        when(runtimeMapper.selectActiveSubmission(SESSION, PLAYER, NODE, "CLOCK_PHOTO")).thenReturn(approved);
        GamePlayerNodeOutcome outcome = outcome(303L, NODE, GamePlayerNodeOutcome.COMPLETED, "AUTO_HASH");
        outcome.setCompletedAt(DateUtils.parseDate("2026-08-23 10:16:42"));
        when(runtimeMapper.selectPlayerNodeOutcomes(SESSION, PLAYER)).thenReturn(Collections.singletonList(outcome));
        stubArrived();

        GameSessionView view = service.view(GameActorContext.player(PLAYER), ACTIVITY);
        Map<?, ?> node = (Map<?, ?>) ((List<?>) view.getPlayer().get("nodes")).get(0);

        assertEquals("COMPLETED", node.get("personalState"));
        assertEquals("COMPLETED", node.get("completionStatus"));
        assertEquals("AUTO_HASH", node.get("completionSource"));
        assertEquals("2026-08-23 10:16:42", node.get("completedAt"));
        assertEquals(303L, node.get("completionOrder"));
        assertFalse(String.valueOf(node).contains("secret.example"));
        assertFalse(String.valueOf(node).contains("evidence"));
    }

    @Test
    void pausedSourceWithFallbackOutcomeProjectsPersonalCompletionBeforeStationState() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        GameStationRun paused = station(MERCHANT, GameStationRun.PAUSED);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(paused));
        when(runtimeMapper.selectPlayerNodeOutcomes(SESSION, PLAYER)).thenReturn(Collections.singletonList(
                outcome(304L, NODE, GamePlayerNodeOutcome.FALLBACK_COMPLETED, "FALLBACK_PLAN")));

        GameSessionView view = service.view(GameActorContext.player(PLAYER), ACTIVITY);
        Map<?, ?> node = (Map<?, ?>) ((List<?>) view.getPlayer().get("nodes")).get(0);

        assertEquals(GameStationRun.PAUSED, node.get("status"));
        assertEquals(GamePlayerNodeOutcome.FALLBACK_COMPLETED, node.get("personalState"));
        assertEquals(GamePlayerNodeOutcome.FALLBACK_COMPLETED, node.get("completionStatus"));
    }

    @Test
    void pausedSourceWithoutOutcomeRemainsPausedForPlayer() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(
                station(MERCHANT, GameStationRun.PAUSED)));

        GameSessionView view = service.view(GameActorContext.player(PLAYER), ACTIVITY);
        Map<?, ?> node = (Map<?, ?>) ((List<?>) view.getPlayer().get("nodes")).get(0);

        assertEquals(GameStationRun.PAUSED, node.get("status"));
        assertEquals(GameStationRun.PAUSED, node.get("personalState"));
        assertNull(node.get("completionStatus"));
    }

    @Test
    void pausedSourceProjectsOnlyTheApprovedFallbackActionToPlayer() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(fallbackPlanConfig());
        GameStationRun source = station(MERCHANT, GameStationRun.PAUSED);
        source.setFallbackNodeId(FALLBACK_NODE);
        source.setFallbackPlanCode("CLOCK_TO_ARCHIVE");
        source.setFallbackPlanVersion(1);
        GameStationRun target = fallbackStation();
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Arrays.asList(source, target));

        GameSessionView view = service.view(GameActorContext.player(PLAYER), ACTIVITY);
        Map<?, ?> node = (Map<?, ?>) ((List<?>) view.getPlayer().get("nodes")).get(0);
        Map<?, ?> fallback = (Map<?, ?>) node.get("fallback");

        assertEquals("CLOCK_TO_ARCHIVE", fallback.get("planCode"));
        assertEquals(1, fallback.get("planVersion"));
        assertEquals(FALLBACK_NODE, fallback.get("targetNodeId"));
        assertEquals("旧档案馆", fallback.get("targetNodeName"));
        assertEquals("请前往旧档案馆完成替代任务", fallback.get("playerMessage"));
        assertEquals(5, fallback.size());
        assertFalse(String.valueOf(node).contains("merchantInstruction"));
        assertFalse(String.valueOf(node).contains("hiddenInfoReminder"));
    }

    @Test
    void verifiedPlayerTaskCreatesPendingSubmission() {
        stubPlayerSubmission(configWithInputType("TEXT"), "submit-pending");
        when(runtimeMapper.insertSubmission(any())).thenAnswer(invocation -> {
            GameTaskSubmission row = invocation.getArgument(0);
            row.setId(101L);
            return 1;
        });
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any())).thenReturn(1);

        GameSessionCommand command = playerSubmitCommand("submit-pending");
        command.getPayload().put("evidenceUrls", Collections.singletonList("text:CLOCK_YEAR"));
        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), command);

        assertEquals("PENDING", receipt.getResult().get("status"));
        assertEquals(101L, ((Number) receipt.getResult().get("submissionId")).longValue());
    }

    @Test
    void selfVerifiedPlayerTaskCreatesApprovedSubmissionWithoutLegacyRewardWrite() {
        stubPlayerSubmission(autoVerifiedTextConfig(), "submit-approved");
        when(runtimeMapper.insertSubmission(any())).thenAnswer(invocation -> {
            GameTaskSubmission row = invocation.getArgument(0);
            assertEquals("APPROVED", row.getStatus());
            row.setId(102L);
            return 1;
        });
        when(runtimeMapper.insertPlayerNodeOutcome(any())).thenAnswer(invocation -> {
            GamePlayerNodeOutcome row = invocation.getArgument(0);
            assertEquals(GamePlayerNodeOutcome.COMPLETED, row.getStatus());
            assertEquals("AUTO_HASH", row.getCompletionSource());
            assertEquals(102L, row.getSourceSubmissionId());
            assertEquals(9902L, row.getSourceReceiptId());
            row.setId(302L);
            return 1;
        });
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any())).thenReturn(1);

        GameSessionCommand command = playerSubmitCommand("submit-approved");
        command.getPayload().put("evidenceUrls", Collections.singletonList("text:AUTO_OK"));
        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), command);

        assertEquals("APPROVED", receipt.getResult().get("status"));
        assertEquals(102L, ((Number) receipt.getResult().get("submissionId")).longValue());
        assertEquals("COMPLETED", receipt.getResult().get("completionStatus"));
        assertEquals(302L, ((Number) receipt.getResult().get("completionOrder")).longValue());
    }

    @Test
    void photoEvidenceOnlyIsRecordedAndCompletesWithoutMerchantApproval() {
        stubPlayerSubmission(photoEvidenceOnlyConfig(), "submit-photo-recorded");
        when(runtimeMapper.insertSubmission(any())).thenAnswer(invocation -> {
            GameTaskSubmission row = invocation.getArgument(0);
            assertEquals("RECORDED", row.getStatus());
            row.setId(105L);
            return 1;
        });
        when(runtimeMapper.insertPlayerNodeOutcome(any())).thenAnswer(invocation -> {
            GamePlayerNodeOutcome row = invocation.getArgument(0);
            assertEquals(GamePlayerNodeOutcome.COMPLETED, row.getStatus());
            assertEquals("EVIDENCE_ONLY", row.getCompletionSource());
            assertEquals(105L, row.getSourceSubmissionId());
            row.setId(305L);
            return 1;
        });
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        GameSessionCommand command = playerSubmitCommand("submit-photo-recorded");
        command.getPayload().put("evidenceUrls",
                Collections.singletonList("https://cdn.example.com/evidence/clock.webp"));

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), command);

        assertEquals("RECORDED", receipt.getResult().get("status"));
        assertEquals("COMPLETED", receipt.getResult().get("completionStatus"));
        assertEquals(305L, ((Number) receipt.getResult().get("completionOrder")).longValue());
        verify(runtimeMapper, never()).decideSubmissionCas(any());
        assertEquals(Collections.singletonList("https://cdn.example.com/evidence/clock.webp|" + PLAYER),
                verifiedPhotos, "R9-39:照片证据必须按当前玩家核对上传事实");
    }

    @Test
    void neverUploadedPhotoEvidenceIsRejectedAndRecordsNoCompletion() {
        // R9-39 复现:example.invalid 下从未上传的 PNG 不得得到 RECORDED/COMPLETED。
        stubPlayerSubmission(photoEvidenceOnlyConfig(), "submit-photo-fake");
        usePhotoVerifier((url, memberId) -> {
            throw new IllegalArgumentException("照片地址不是现行上传链生成的照片对象");
        });
        GameSessionCommand command = playerSubmitCommand("submit-photo-fake");
        command.getPayload().put("evidenceUrls",
                Collections.singletonList("https://example.invalid/never-uploaded.png"));

        GameSessionException error = assertThrows(GameSessionException.class,
                () -> service.command(GameActorContext.player(PLAYER), command));

        assertEquals("GAME_TASK_EVIDENCE_INVALID", error.getErrorCode());
        verify(runtimeMapper, never()).insertSubmission(any());
        verify(runtimeMapper, never()).insertPlayerNodeOutcome(any());
        verify(runtimeMapper, never()).updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void anotherPlayersOrDeletedPhotoAmongSeveralRejectsWholeSubmission() {
        stubPlayerSubmission(photoEvidenceOnlyConfig(), "submit-photo-mixed");
        String own = "https://lie-channelwin.oss-cn-hangzhou.aliyuncs.com/upload/0123456789abcdef0123456789abcdef.jpg";
        String foreign = "https://lie-channelwin.oss-cn-hangzhou.aliyuncs.com/upload/fedcba9876543210fedcba9876543210.jpg";
        usePhotoVerifier((url, memberId) -> {
            if (foreign.equals(url)) throw new IllegalArgumentException("该照片不是当前账号上传的");
        });
        GameSessionCommand command = playerSubmitCommand("submit-photo-mixed");
        command.getPayload().put("evidenceUrls", Arrays.asList(own, foreign));

        GameSessionException error = assertThrows(GameSessionException.class,
                () -> service.command(GameActorContext.player(PLAYER), command));

        assertEquals("GAME_TASK_EVIDENCE_INVALID", error.getErrorCode());
        verify(runtimeMapper, never()).insertSubmission(any());
    }

    @Test
    void photoStorageUnavailableIsRetryableAndRecordsNothing() {
        stubPlayerSubmission(photoEvidenceOnlyConfig(), "submit-photo-storage-down");
        usePhotoVerifier((url, memberId) -> {
            throw new IllegalStateException("对象存储查询失败");
        });
        GameSessionCommand command = playerSubmitCommand("submit-photo-storage-down");

        GameSessionException error = assertThrows(GameSessionException.class,
                () -> service.command(GameActorContext.player(PLAYER), command));

        assertEquals("GAME_EVIDENCE_STORAGE_UNAVAILABLE", error.getErrorCode());
        verify(runtimeMapper, never()).insertSubmission(any());
        verify(runtimeMapper, never()).insertPlayerNodeOutcome(any());
    }

    @Test
    void existingPhotoSubmissionReplayDoesNotReverifyStorage() {
        stubPlayerSubmission(photoEvidenceOnlyConfig(), "submit-photo-replay");
        GameTaskSubmission recorded = new GameTaskSubmission();
        recorded.setId(206L);
        recorded.setStatus("RECORDED");
        when(runtimeMapper.selectActiveSubmission(SESSION, PLAYER, NODE, "CLOCK_PHOTO")).thenReturn(recorded);
        usePhotoVerifier((url, memberId) -> {
            throw new IllegalStateException("存储故障不应影响已记录的提交");
        });

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), playerSubmitCommand("submit-photo-replay"));

        assertEquals(206L, ((Number) receipt.getResult().get("submissionId")).longValue());
        verify(runtimeMapper, never()).insertSubmission(any());
    }

    private void usePhotoVerifier(GameSessionRuntimeServiceImpl.PhotoEvidenceVerifier verifier) {
        ReflectionTestUtils.setField(service, "photoEvidenceVerifier", verifier);
    }

    @Test
    void targetCompletionCreatesSourceFallbackOutcomeWithoutFakeSubmission() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(fallbackPlanConfig());
        GameStationRun source = station(MERCHANT, GameStationRun.PAUSED);
        source.setFallbackNodeId(FALLBACK_NODE);
        source.setFallbackPlanCode("CLOCK_TO_ARCHIVE");
        source.setFallbackPlanVersion(1);
        GameStationRun target = fallbackStation();
        GameStationRun sameMerchantSource = station(MERCHANT + 1, GameStationRun.PAUSED);
        sameMerchantSource.setId(5111L);
        sameMerchantSource.setNodeId(NODE + 10);
        sameMerchantSource.setFallbackNodeId(FALLBACK_NODE);
        sameMerchantSource.setFallbackPlanCode("CLOCK_TO_ARCHIVE");
        sameMerchantSource.setFallbackPlanVersion(1);
        GameStationRun stalePlanSource = station(MERCHANT, GameStationRun.PAUSED);
        stalePlanSource.setId(5112L);
        stalePlanSource.setNodeId(NODE + 11);
        stalePlanSource.setFallbackNodeId(FALLBACK_NODE);
        stalePlanSource.setFallbackPlanCode("CLOCK_TO_ARCHIVE");
        stalePlanSource.setFallbackPlanVersion(2);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStation(SESSION, FALLBACK_NODE)).thenReturn(target);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(
                Arrays.asList(source, sameMerchantSource, stalePlanSource, target));
        // 兜底用于源站无法服务的场景；只要求玩家已到可用目标站，不要求曾到暂停源站。
        stubArrivedAt(FALLBACK_NODE);
        stubReceiptPersistence("submit-fallback-target");
        when(runtimeMapper.insertSubmission(any())).thenAnswer(invocation -> {
            GameTaskSubmission row = invocation.getArgument(0);
            row.setId(106L);
            return 1;
        });
        final List<GamePlayerNodeOutcome> inserted = new java.util.ArrayList<>();
        when(runtimeMapper.insertPlayerNodeOutcome(any())).thenAnswer(invocation -> {
            GamePlayerNodeOutcome row = invocation.getArgument(0);
            row.setId(306L + inserted.size());
            inserted.add(row);
            return 1;
        });
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        GameSessionCommand command = playerSubmitCommand("submit-fallback-target");
        command.setNodeId(FALLBACK_NODE);
        command.getPayload().put("taskCode", "ARCHIVE_TEXT");
        command.getPayload().put("evidenceUrls", Collections.singletonList("text:1998"));

        service.command(GameActorContext.player(PLAYER), command);

        assertEquals(2, inserted.size());
        assertEquals(FALLBACK_NODE, inserted.get(0).getNodeId());
        assertEquals(GamePlayerNodeOutcome.COMPLETED, inserted.get(0).getStatus());
        assertEquals(NODE, inserted.get(1).getNodeId());
        assertEquals(GamePlayerNodeOutcome.FALLBACK_COMPLETED, inserted.get(1).getStatus());
        assertEquals("FALLBACK_PLAN", inserted.get(1).getCompletionSource());
        assertEquals("CLOCK_TO_ARCHIVE", inserted.get(1).getFallbackPlanCode());
        assertEquals(1, inserted.get(1).getFallbackPlanVersion());
        assertNull(inserted.get(1).getSourceSubmissionId());
        assertEquals(9902L, inserted.get(1).getSourceReceiptId());
        assertFalse(inserted.stream().anyMatch(row -> Long.valueOf(NODE + 10).equals(row.getNodeId())));
        assertFalse(inserted.stream().anyMatch(row -> Long.valueOf(NODE + 11).equals(row.getNodeId())));
        verify(runtimeMapper).insertSubmission(any());
    }

    @Test
    void playerHintCannotSkipAndProjectionRevealsOnlyOpenedText() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(hintConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectRole(SESSION, PLAYER)).thenReturn(role("DETECTIVE", "侦探"));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.ACTIVE));
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(
                station(MERCHANT, GameStationRun.ACTIVE)));
        when(runtimeMapper.selectPlayerHintLevels(SESSION, PLAYER)).thenReturn(
                Collections.emptyList(), Collections.singletonList(hintLevelRow(NODE, 1)));
        stubArrived();
        stubReceiptPersistence("hint-skip");

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.player(PLAYER), command("hint-skip", 3, "PLAYER_HINT", mapOf("level", 2))));
        assertEquals("GAME_HINT_LEVEL_INVALID", error.getErrorCode());

        GameSessionView view = service.view(GameActorContext.player(PLAYER), ACTIVITY);
        Map<?, ?> node = (Map<?, ?>) ((List<?>) view.getPlayer().get("nodes")).get(0);
        Map<?, ?> hint = (Map<?, ?>) node.get("hint");
        assertEquals(1, hint.get("currentLevel"));
        assertEquals(2, hint.get("nextLevel"));
        assertEquals("会记录二级提示", hint.get("nextImpactLabel"));
        assertEquals(Boolean.FALSE, hint.get("revealAvailable"));
        assertEquals("将记为兜底完成，不计正常榜单分", hint.get("revealImpactLabel"));
        assertEquals(6, hint.size());
        assertTrue(String.valueOf(hint.get("revealedTexts")).contains("先观察钟面年份"));
        assertFalse(String.valueOf(hint).contains("数字由四位组成"));
        assertFalse(String.valueOf(view.getPlayer()).contains("答案是1998"));
    }

    @Test
    void playerHintsAdvanceZeroToOneToTwoAndExposeBothActions() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(hintConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.ACTIVE));
        when(runtimeMapper.selectPlayerHintLevels(SESSION, PLAYER)).thenReturn(
                Collections.emptyList(), Collections.singletonList(hintLevelRow(NODE, 1)));
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        stubArrived();
        stubReceiptPersistence("hint-level-1");
        stubReceiptPersistence("hint-level-2");

        com.chengyinhub.business.domain.dto.GameCommandReceiptView first = service.command(
                GameActorContext.player(PLAYER), command("hint-level-1", 3, "PLAYER_HINT", mapOf("level", 1)));
        com.chengyinhub.business.domain.dto.GameCommandReceiptView second = service.command(
                GameActorContext.player(PLAYER), command("hint-level-2", 4, "PLAYER_HINT", mapOf("level", 2)));

        assertEquals(1, ((Map<?, ?>) first.getResult().get("hint")).get("currentLevel"));
        assertEquals(2, ((Map<?, ?>) second.getResult().get("hint")).get("currentLevel"));
        assertEquals(5, second.getRevision());
        GameSessionView view = service.view(GameActorContext.player(PLAYER), ACTIVITY);
        assertTrue(view.getAvailableActions().contains("PLAYER_HINT"));
        assertTrue(view.getAvailableActions().contains("PLAYER_REVEAL"));
    }

    @Test
    void playerRevealRequiresLevelTwoAndCreatesFallbackOutcome() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(hintConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.ACTIVE));
        when(runtimeMapper.selectPlayerHintLevels(SESSION, PLAYER)).thenReturn(
                Collections.singletonList(hintLevelRow(NODE, 2)));
        stubArrived();
        stubReceiptPersistence("reveal-answer");
        when(runtimeMapper.insertPlayerNodeOutcome(any())).thenAnswer(invocation -> {
            GamePlayerNodeOutcome row = invocation.getArgument(0);
            row.setId(401L);
            return 1;
        });
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), command("reveal-answer", 3, "PLAYER_REVEAL", Collections.emptyMap()));

        assertEquals("答案是1998", receipt.getResult().get("revealText"));
        assertEquals(GamePlayerNodeOutcome.FALLBACK_COMPLETED, receipt.getResult().get("completionStatus"));
        assertEquals("ANSWER_REVEAL", receipt.getResult().get("completionSource"));
        assertEquals(401L, ((Number) receipt.getResult().get("completionOrder")).longValue());
        verify(runtimeMapper).insertPlayerNodeOutcome(any(GamePlayerNodeOutcome.class));
    }

    @Test
    void scanTaskCanAutoApproveOnlyAgainstConfiguredExpectedHash() {
        String config = configWithInputType("SCAN").replace("\"verificationRequired\":true",
                "\"verificationRequired\":false,\"expectedHash\":\""
                        + "b80769566eb1e1cb64284d4cb35c3b4f99a6176993ee3f2e8029a744541438a6\"");
        stubPlayerSubmission(config, "submit-approved-scan");
        when(runtimeMapper.insertSubmission(any())).thenAnswer(invocation -> {
            GameTaskSubmission row = invocation.getArgument(0);
            row.setId(104L);
            return 1;
        });
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        GameSessionCommand command = playerSubmitCommand("submit-approved-scan");
        command.getPayload().put("evidenceUrls", Collections.singletonList("scan:SCAN_OK"));

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), command);

        assertEquals("APPROVED", receipt.getResult().get("status"));
    }

    @Test
    void textScanAndPhotoEvidencePrimitivesAreAccepted() {
        when(runtimeMapper.insertSubmission(any())).thenAnswer(invocation -> {
            GameTaskSubmission row = invocation.getArgument(0);
            row.setId(200L + row.getEvidenceJson().length());
            return 1;
        });
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        List<String> inputTypes = Arrays.asList("TEXT", "SCAN", "PHOTO");
        List<String> evidence = Arrays.asList(
                "text:%E9%92%9F%E9%9D%A2%E6%98%BE%E7%A4%BA%E5%8D%81%E4%BA%8C%E7%82%B9",
                "scan:QR_ABC-123",
                "https://cdn.example.com/evidence/clock.webp");

        for (int i = 0; i < inputTypes.size(); i++) {
            String requestId = "submit-primitive-" + i;
            stubPlayerSubmission(configWithInputType(inputTypes.get(i)), requestId);
            GameSessionCommand command = playerSubmitCommand(requestId);
            command.getPayload().put("evidenceUrls", Collections.singletonList(evidence.get(i)));

            com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                    GameActorContext.player(PLAYER), command);

            assertEquals("PHOTO".equals(inputTypes.get(i)) ? "RECORDED" : "PENDING",
                    receipt.getResult().get("status"));
        }
    }

    @Test
    void emptyEvidenceCannotAutoApprove() {
        stubPlayerSubmission(autoVerifiedTextConfig(), "submit-empty-auto");
        GameSessionCommand command = playerSubmitCommand("submit-empty-auto");
        command.getPayload().put("evidenceUrls", Collections.emptyList());

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.player(PLAYER), command));

        assertEquals("GAME_BAD_REQUEST", error.getErrorCode());
        verify(runtimeMapper, never()).insertSubmission(any());
    }

    @Test
    void wrongAutomaticEvidenceIsRejectedBeforeSubmission() {
        stubPlayerSubmission(autoVerifiedTextConfig(), "submit-wrong-auto");
        GameSessionCommand command = playerSubmitCommand("submit-wrong-auto");
        command.getPayload().put("evidenceUrls", Collections.singletonList("text:WRONG"));

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.player(PLAYER), command));

        assertEquals("GAME_TASK_EVIDENCE_INVALID", error.getErrorCode());
        verify(runtimeMapper, never()).insertSubmission(any());
    }

    @Test
    void automaticEvidenceHashUsesDecodedCanonicalValue() {
        String config = autoVerifiedTextConfig().replace(
                "ed2fed52d4df8c30c2c612155d40024a3b9003e811ce1d0fe4a0bd6458f66fd7",
                "fc26735f4f9acd146545760010c53c70bfc1f6a7d5c3ff82789eb7ac4f6f2f51");
        stubPlayerSubmission(config, "submit-encoded-auto");
        when(runtimeMapper.insertSubmission(any())).thenAnswer(invocation -> {
            GameTaskSubmission row = invocation.getArgument(0);
            row.setId(103L);
            return 1;
        });
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        GameSessionCommand command = playerSubmitCommand("submit-encoded-auto");
        command.getPayload().put("evidenceUrls",
                Collections.singletonList("text:%E9%92%9F%E9%9D%A2"));

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), command);

        assertEquals("APPROVED", receipt.getResult().get("status"));
    }

    @Test
    void evidenceSchemeMustMatchConfiguredInputType() {
        List<String> inputTypes = Arrays.asList("TEXT", "SCAN", "PHOTO", "PHOTO", "TEXT", "TEXT", "TEXT");
        List<String> invalidEvidence = Arrays.asList(
                "scan:NOT_TEXT",
                "text:not-a-scan",
                "http://cdn.example.com/evidence/clock.jpg",
                "https://cdn.example.com/evidence/readme.txt",
                "text:钟面",
                "text:%e9%92%9f",
                "text:%E9%9");

        for (int i = 0; i < inputTypes.size(); i++) {
            String requestId = "submit-wrong-evidence-" + i;
            stubPlayerSubmission(configWithInputType(inputTypes.get(i)), requestId);
            GameSessionCommand command = playerSubmitCommand(requestId);
            command.getPayload().put("evidenceUrls", Collections.singletonList(invalidEvidence.get(i)));

            GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                    GameActorContext.player(PLAYER), command));

            assertEquals("GAME_BAD_REQUEST", error.getErrorCode());
        }
        verify(runtimeMapper, never()).insertSubmission(any());
    }

    @Test
    void prepareRejectsUnsupportedTaskInputType() {
        CmsTopic topic = configuredTopic();
        topic.setGameModuleConfigJson(configWithInputType("BUTTON"));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        when(runtimeMapper.insertReceiptClaim(any())).thenReturn(1);

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(PLAYER), command("prepare-bad-input", 0, "PREPARE",
                        Collections.emptyMap())));

        assertEquals("GAME_CONFIG_INVALID", error.getErrorCode());
        verify(runtimeMapper, never()).insertSession(any());
    }

    @Test
    void prepareRejectsAutoApprovalWithoutExpectedHashOrPhotoWithoutExplicitEvidencePolicy() {
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        when(runtimeMapper.insertReceiptClaim(any())).thenReturn(1);
        CmsTopic topic = configuredTopic();
        topic.setGameModuleConfigJson(configWithInputType("TEXT").replace("\"verificationRequired\":true",
                "\"verificationRequired\":false"));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);

        GameSessionException missingHash = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(PLAYER), command("prepare-missing-hash", 0, "PREPARE",
                        Collections.emptyMap())));

        assertEquals("GAME_CONFIG_INVALID", missingHash.getErrorCode());
        topic.setGameModuleConfigJson(runtimeConfigJson().replace(
                ",\"completionPolicy\":\"EVIDENCE_ONLY\"", ""));
        GameSessionException photoAuto = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(PLAYER), command("prepare-photo-auto", 0, "PREPARE",
                        Collections.emptyMap())));

        assertEquals("GAME_CONFIG_INVALID", photoAuto.getErrorCode());
        verify(runtimeMapper, never()).insertSession(any());
    }

    @Test
    void roleAssignmentBumpsSessionRevisionAndReturnsAuditableAssignments() {
        GameSession session = runningSession(2);
        session.setStatus(GameSession.PREPARING);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        when(runtimeMapper.isActiveTeamMember(ACTIVITY, TEAM, PLAYER)).thenReturn(1);
        when(runtimeMapper.insertRole(any())).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any())).thenReturn(1);
        stubReceiptPersistence("assign-1");
        Map<String, Object> assignment = new LinkedHashMap<>();
        assignment.put("memberId", PLAYER);
        assignment.put("roleCode", "DETECTIVE");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("teamId", TEAM);
        payload.put("assignments", Collections.singletonList(assignment));

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.club(PLAYER), command("assign-1", 2, "ASSIGN_ROLES", payload));

        assertEquals(3, receipt.getRevision());
        assertTrue(String.valueOf(receipt.getResult().get("assignments")).contains("DETECTIVE"));
        verify(runtimeMapper).updateSessionCas(eq(SESSION), eq(2), eq("PREPARING"), eq("PREPARING"),
                eq(null), eq(PLAYER), any());
    }

    @Test
    void identicalRoleAssignmentDoesNotCreateFakeHistoryOrBumpRevision() {
        GameSession session = runningSession(2);
        session.setStatus(GameSession.PREPARING);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        when(runtimeMapper.isActiveTeamMember(ACTIVITY, TEAM, PLAYER)).thenReturn(1);
        GameTeamRoleAssignment active = role("DETECTIVE", "侦探");
        active.setTeamId(TEAM);
        when(runtimeMapper.selectActiveRoleForUpdate(SESSION, PLAYER)).thenReturn(active);
        stubReceiptPersistence("assign-same-role");
        Map<String, Object> assignment = new LinkedHashMap<>();
        assignment.put("memberId", PLAYER);
        assignment.put("roleCode", "DETECTIVE");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("teamId", TEAM);
        payload.put("assignments", Collections.singletonList(assignment));

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.club(PLAYER), command("assign-same-role", 2, "ASSIGN_ROLES", payload));

        assertEquals(2, receipt.getRevision());
        assertTrue(String.valueOf(receipt.getResult()).contains("changed=false"));
        verify(runtimeMapper, never()).supersedeActiveRole(anyLong(), anyLong(), anyLong(), any());
        verify(runtimeMapper, never()).insertRole(any());
        verify(runtimeMapper, never()).updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void repeatedRoleConfirmationReturnsSuccessWithoutBumpingSessionRevision() {
        GameSession session = runningSession(2);
        session.setStatus(GameSession.PREPARING);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectRole(SESSION, PLAYER)).thenReturn(role("DETECTIVE", "侦探"));
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any())).thenReturn(1);
        stubReceiptPersistence("confirm-role-again");

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER),
                command("confirm-role-again", 2, "CONFIRM_ROLE", Collections.emptyMap()));

        assertEquals(2, receipt.getRevision());
        assertEquals(Boolean.TRUE, receipt.getResult().get("confirmed"));
        verify(runtimeMapper, never()).updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void concurrentRoleInsertCollisionReadsActiveWinnerAndCurrentRevision() {
        GameSession initial = runningSession(2);
        initial.setStatus(GameSession.PREPARING);
        initial.setModuleConfigSnapshotJson(runtimeConfigJson());
        GameSession current = runningSession(3);
        current.setStatus(GameSession.PREPARING);
        current.setModuleConfigSnapshotJson(runtimeConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(initial, current);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        when(runtimeMapper.isActiveTeamMember(ACTIVITY, TEAM, PLAYER)).thenReturn(1);
        GameTeamRoleAssignment winner = role("DETECTIVE", "侦探");
        winner.setTeamId(TEAM);
        when(runtimeMapper.selectActiveRoleForUpdate(SESSION, PLAYER)).thenReturn(null, winner);
        when(runtimeMapper.insertRole(any())).thenReturn(0);
        stubReceiptPersistence("assign-concurrent-role");
        Map<String, Object> assignment = new LinkedHashMap<>();
        assignment.put("memberId", PLAYER);
        assignment.put("roleCode", "DETECTIVE");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("teamId", TEAM);
        payload.put("assignments", Collections.singletonList(assignment));

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.club(PLAYER), command("assign-concurrent-role", 2, "ASSIGN_ROLES", payload));

        assertEquals(3, receipt.getRevision());
        assertTrue(String.valueOf(receipt.getResult()).contains("changed=false"));
        verify(runtimeMapper).insertRole(any());
        verify(runtimeMapper, never()).updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void pauseRequiresParseableResumeEta() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, "ACTIVE"));
        stubReceiptPersistence("pause-no-eta");

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.merchant(MERCHANT),
                command("pause-no-eta", 4, "STATION_PAUSE", mapOf("reasonCode", "WEATHER"))));

        assertEquals("GAME_BAD_REQUEST", error.getErrorCode());
        verify(runtimeMapper, never()).updateStationStatusCas(any(), anyLong(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void merchantCannotResumeBeforeTheStationServiceWindow() {
        CmsActivity activity = activeActivity();
        activity.setStartDate(DateUtils.parseDate("2026-08-23 10:00:00"));
        activity.setEndDate(DateUtils.parseDate("2026-08-23 18:00:00"));
        GameStationRun paused = station(MERCHANT, GameStationRun.PAUSED);
        paused.setServiceStartAt(DateUtils.parseDate("2026-08-23 13:00:00"));
        paused.setServiceEndAt(DateUtils.parseDate("2026-08-23 17:00:00"));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(paused);
        ReflectionTestUtils.setField(service, "clock",
                (com.chengyinhub.business.service.support.AdvancedGameClock)
                        () -> DateUtils.parseDate("2026-08-23 12:00:00"));
        stubReceiptPersistence("resume-before-window");
        when(runtimeMapper.updateStationStatusCas(any(), anyLong(), anyString(), anyString(), anyInt(), any())).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any())).thenReturn(1);

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.merchant(MERCHANT),
                command("resume-before-window", 4, "STATION_RESUME", Collections.emptyMap())));

        assertEquals("GAME_STATION_OUTSIDE_SERVICE_WINDOW", error.getErrorCode());
        verify(runtimeMapper, never()).updateStationStatusCas(any(), anyLong(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void rejectSubmissionRequiresReasonCode() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, "ACTIVE"));
        GameTaskSubmission submission = new GameTaskSubmission();
        submission.setId(77L);
        submission.setGameSessionId(SESSION);
        submission.setStationRunId(5101L);
        submission.setNodeId(NODE);
        submission.setStatus("PENDING");
        submission.setRevision(0);
        when(runtimeMapper.selectSubmission(77L)).thenReturn(submission);
        stubReceiptPersistence("reject-no-reason");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submissionId", 77L);
        payload.put("decision", "REJECT");

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.merchant(MERCHANT), command("reject-no-reason", 4, "VERIFY_SUBMISSION", payload)));

        assertEquals("GAME_BAD_REQUEST", error.getErrorCode());
        verify(runtimeMapper, never()).decideSubmissionCas(any());
    }

    @Test
    void merchantApprovalCreatesNormalOutcomeOnceWithReceiptProvenance() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.ACTIVE));
        GameTaskSubmission submission = new GameTaskSubmission();
        submission.setId(78L);
        submission.setGameSessionId(SESSION);
        submission.setActivityId(ACTIVITY);
        submission.setStationRunId(5101L);
        submission.setNodeId(NODE);
        submission.setTeamId(TEAM);
        submission.setMemberId(PLAYER);
        submission.setStatus("PENDING");
        submission.setRevision(0);
        when(runtimeMapper.selectSubmission(78L)).thenReturn(submission);
        when(runtimeMapper.decideSubmissionCas(submission)).thenReturn(1);
        when(runtimeMapper.insertPlayerNodeOutcome(any())).thenAnswer(invocation -> {
            GamePlayerNodeOutcome row = invocation.getArgument(0);
            assertEquals(GamePlayerNodeOutcome.COMPLETED, row.getStatus());
            assertEquals("MERCHANT_APPROVAL", row.getCompletionSource());
            assertEquals(78L, row.getSourceSubmissionId());
            assertEquals(9902L, row.getSourceReceiptId());
            row.setId(402L);
            return 1;
        });
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        stubReceiptPersistence("approve-submission");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submissionId", 78L);
        payload.put("decision", "APPROVE");

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.merchant(MERCHANT),
                command("approve-submission", 4, "VERIFY_SUBMISSION", payload));

        assertEquals("APPROVED", receipt.getResult().get("decision"));
        assertEquals(GamePlayerNodeOutcome.COMPLETED, receipt.getResult().get("completionStatus"));
        assertEquals(402L, ((Number) receipt.getResult().get("completionOrder")).longValue());
    }

    @Test
    void broadcastWithNoRecipientsFailsInsteadOfClaimingSent() {
        GameSession session = readySession(2);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        stubReceiptPersistence("broadcast-empty");
        when(runtimeMapper.insertBroadcast(any())).thenAnswer(invocation -> {
            com.chengyinhub.business.domain.GameBroadcast row = invocation.getArgument(0);
            row.setId(66L);
            return 1;
        });
        when(runtimeMapper.insertBroadcastDeliveries(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any()))
                .thenReturn(0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetType", "ALL");
        payload.put("content", "集合提醒");

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(PLAYER), command("broadcast-empty", 2, "BROADCAST", payload)));

        assertEquals("GAME_BROADCAST_NO_RECIPIENT", error.getErrorCode());
        verify(runtimeMapper, never()).updateBroadcastStatus(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void directorBroadcastIsRejectedByContentSafetyBeforeAnyBroadcastRowIsCreated() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(readySession(2));
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        stubReceiptPersistence("broadcast-unsafe");
        doThrow(new RuntimeException("内容违规")).when(imService)
                .checkUserGeneratedText(PLAYER, "违规集合提醒");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetType", "ALL");
        payload.put("content", "违规集合提醒");

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(PLAYER), command("broadcast-unsafe", 2, "BROADCAST", payload)));

        assertEquals("GAME_CONTENT_UNSAFE", error.getErrorCode());
        verify(runtimeMapper, never()).insertBroadcast(any());
        verify(imService, never()).sendSystem(anyLong(), anyInt(), anyString(), anyString());
        verify(imService, never()).sendSystemInNestedTransaction(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void unavailableAndPendingReviewBroadcastsDoNotAccuseSenderOrWriteDeliveries() {
        String[] messages = {
                com.chengyinhub.business.service.IWxContentSecurityService.MSG_BUSY,
                com.chengyinhub.business.service.IWxContentSecurityService.MSG_UNAVAILABLE,
                com.chengyinhub.business.service.IWxContentSecurityService.REVIEW_REQUIRED
        };
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(readySession(2));
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        for (int i = 0; i < messages.length; i++) {
            String requestId = "broadcast-audit-blocked-" + i;
            stubReceiptPersistence(requestId);
            doThrow(new RuntimeException(new String(messages[i]))).when(imService)
                    .checkUserGeneratedText(PLAYER, "集合提醒");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("targetType", "ALL");
            payload.put("content", "集合提醒");
            GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                    GameActorContext.club(PLAYER), command(requestId, 2, "BROADCAST", payload)));
            assertEquals(i == 2 ? "GAME_CONTENT_REVIEW_REQUIRED" : "GAME_CONTENT_UNAVAILABLE", error.getErrorCode());
            assertEquals(messages[i], error.getMessage());
        }
        verify(runtimeMapper, never()).insertBroadcast(any());
        verify(runtimeMapper, never()).insertBroadcastDeliveries(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
        verify(imService, never()).sendSystemInNestedTransaction(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void unavailableAndPendingReviewTakeoversKeepOriginalRoleHistory() {
        String[] messages = {
                com.chengyinhub.business.service.IWxContentSecurityService.MSG_BUSY,
                com.chengyinhub.business.service.IWxContentSecurityService.MSG_UNAVAILABLE,
                com.chengyinhub.business.service.IWxContentSecurityService.REVIEW_REQUIRED
        };
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        when(runtimeMapper.isActiveTeamMember(ACTIVITY, TEAM, PLAYER)).thenReturn(1);
        when(runtimeMapper.isActiveTeamMember(ACTIVITY, TEAM, TARGET_PLAYER)).thenReturn(1);
        when(runtimeMapper.selectActiveRoleForUpdate(SESSION, PLAYER)).thenReturn(takeoverRole(PLAYER));
        for (int i = 0; i < messages.length; i++) {
            String requestId = "takeover-audit-blocked-" + i;
            stubReceiptPersistence(requestId);
            doThrow(new RuntimeException(new String(messages[i]))).when(imService)
                    .checkUserGeneratedText(DIRECTOR, "玩家临时离场");
            GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                    GameActorContext.club(DIRECTOR), takeoverCommand(requestId, 4)));
            assertEquals(i == 2 ? "GAME_CONTENT_REVIEW_REQUIRED" : "GAME_CONTENT_UNAVAILABLE", error.getErrorCode());
            assertEquals(messages[i], error.getMessage());
        }
        verify(runtimeMapper, never()).supersedeActiveRole(anyLong(), anyLong(), anyLong(), any());
        verify(runtimeMapper, never()).insertRole(any());
    }

    @Test
    void broadcastSessionCasFailureAbortsReceiptAfterNestedDelivery() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(readySession(2));
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        stubReceiptPersistence("broadcast-cas-conflict");
        when(runtimeMapper.insertBroadcast(any())).thenAnswer(invocation -> {
            com.chengyinhub.business.domain.GameBroadcast row = invocation.getArgument(0);
            row.setId(67L);
            return 1;
        });
        when(runtimeMapper.insertBroadcastDeliveries(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any()))
                .thenReturn(1);
        com.chengyinhub.business.domain.GameBroadcastDelivery delivery =
                new com.chengyinhub.business.domain.GameBroadcastDelivery();
        delivery.setId(701L);
        delivery.setMemberId(PLAYER + 1);
        when(runtimeMapper.selectPendingDeliveries(67L)).thenReturn(Collections.singletonList(delivery));
        when(imService.sendSystemInNestedTransaction(PLAYER + 1, ImMessage.MSG_TEXT, "集合提醒",
                "{\"gameSessionId\":11,\"broadcastId\":67}"))
                .thenReturn(801L);
        when(runtimeMapper.markDelivery(eq(701L), eq("REACHED"), eq(801L), eq((String) null), any(Date.class)))
                .thenReturn(1);
        when(runtimeMapper.countBroadcastDeliveries(67L, "FAILED")).thenReturn(0);
        when(runtimeMapper.countBroadcastDeliveries(67L, "PENDING")).thenReturn(0);
        when(runtimeMapper.updateBroadcastStatus(eq(67L), eq("SENDING"), eq("SENT"), any(Date.class)))
                .thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetType", "ALL");
        payload.put("content", "集合提醒");

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(PLAYER), command("broadcast-cas-conflict", 2, "BROADCAST", payload)));

        assertEquals("GAME_STATE_CONFLICT", error.getErrorCode());
        verify(imService).sendSystemInNestedTransaction(anyLong(), anyInt(), anyString(), anyString());
        verify(runtimeMapper, never()).completeReceipt(any());
    }

    @Test
    void broadcastReachedMarkReturningZeroAbortsBeforeFinalizingBroadcast() {
        stubSingleBroadcastDelivery("broadcast-reached-mark-zero", 68L, 702L);
        when(imService.sendSystemInNestedTransaction(PLAYER + 1, ImMessage.MSG_TEXT, "集合提醒",
                "{\"gameSessionId\":11,\"broadcastId\":68}"))
                .thenReturn(802L);
        when(runtimeMapper.markDelivery(eq(702L), eq("REACHED"), eq(802L), eq((String) null), any(Date.class)))
                .thenReturn(0);

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(PLAYER), broadcastCommand("broadcast-reached-mark-zero")));

        assertEquals("GAME_STATE_CONFLICT", error.getErrorCode());
        verify(runtimeMapper, never()).markDelivery(eq(702L), eq("FAILED"), any(), anyString(), any(Date.class));
        verify(runtimeMapper, never()).updateBroadcastStatus(anyLong(), anyString(), anyString(), any());
        verify(runtimeMapper, never()).completeReceipt(any());
    }

    @Test
    void broadcastReachedMarkExceptionIsNotMisclassifiedAsImFailure() {
        stubSingleBroadcastDelivery("broadcast-reached-mark-error", 69L, 703L);
        when(imService.sendSystemInNestedTransaction(PLAYER + 1, ImMessage.MSG_TEXT, "集合提醒",
                "{\"gameSessionId\":11,\"broadcastId\":69}"))
                .thenReturn(803L);
        when(runtimeMapper.markDelivery(eq(703L), eq("REACHED"), eq(803L), eq((String) null), any(Date.class)))
                .thenThrow(new RuntimeException("delivery row unavailable"));

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(PLAYER), broadcastCommand("broadcast-reached-mark-error")));

        assertEquals("GAME_STATE_CONFLICT", error.getErrorCode());
        verify(runtimeMapper, never()).markDelivery(eq(703L), eq("FAILED"), any(), anyString(), any(Date.class));
        verify(runtimeMapper, never()).updateBroadcastStatus(anyLong(), anyString(), anyString(), any());
        verify(runtimeMapper, never()).completeReceipt(any());
    }

    @Test
    void broadcastFailedMarkReturningZeroAbortsBeforeFinalizingBroadcast() {
        stubSingleBroadcastDelivery("broadcast-failed-mark-zero", 70L, 704L);
        when(imService.sendSystemInNestedTransaction(PLAYER + 1, ImMessage.MSG_TEXT, "集合提醒",
                "{\"gameSessionId\":11,\"broadcastId\":70}"))
                .thenThrow(new RuntimeException("im unavailable"));
        when(runtimeMapper.markDelivery(eq(704L), eq("FAILED"), eq((Long) null),
                eq("IM_DELIVERY_FAILED"), any(Date.class))).thenReturn(0);

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(PLAYER), broadcastCommand("broadcast-failed-mark-zero")));

        assertEquals("GAME_STATE_CONFLICT", error.getErrorCode());
        verify(runtimeMapper, never()).updateBroadcastStatus(anyLong(), anyString(), anyString(), any());
        verify(runtimeMapper, never()).completeReceipt(any());
    }

    @Test
    void playerStationBeforeArrivalHidesPrivateGameplayAndMerchantOperations() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        GameStationRun active = station(MERCHANT, GameStationRun.ACTIVE);
        active.setCapacity(999);
        active.setServiceStartAt(new Date());
        active.setPauseReason("运营密文");
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectRole(SESSION, PLAYER)).thenReturn(role("DETECTIVE", "侦探"));
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(active));

        String projection = String.valueOf(service.view(GameActorContext.player(PLAYER), ACTIVITY).getPlayer());

        assertTrue(projection.contains("AVAILABLE"));
        assertFalse(projection.contains("侦探私密线索"));
        assertFalse(projection.contains("CLOCK_PHOTO"));
        assertFalse(projection.contains("preparationChecklist"));
        assertFalse(projection.contains("capacity"));
        assertFalse(projection.contains("serviceWindow"));
        assertFalse(projection.contains("运营密文"));
    }

    @Test
    void pendingReceiptReplayReturnsPendingInsteadOfBusinessFailure() {
        GameCommandReceipt pending = new GameCommandReceipt();
        pending.setActivityId(ACTIVITY);
        pending.setActorMemberId(PLAYER);
        pending.setRequestId("still-running");
        pending.setRequestHash("placeholder");
        pending.setOutcome("PENDING");
        GameSessionCommand request = command("still-running", 3, "PLAYER_SUBMIT", Collections.emptyMap());
        when(runtimeMapper.selectReceipt(ACTIVITY, PLAYER, "still-running")).thenReturn(pending);

        GameSessionException hashError = assertThrows(GameSessionException.class,
                () -> service.command(GameActorContext.player(PLAYER), request));
        assertEquals("GAME_IDEMPOTENCY_CONFLICT", hashError.getErrorCode());

        pending.setRequestHash(request.getRequestHash());
        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt =
                service.command(GameActorContext.player(PLAYER), request);
        assertEquals("PENDING", receipt.getOutcome());
    }

    @Test
    void playerSubmitRequiresAuthoritativeArrivalBeforeInsert() {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.ACTIVE));
        stubReceiptPersistence("submit-before-arrival");

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.player(PLAYER), playerSubmitCommand("submit-before-arrival")));

        assertEquals("GAME_STATION_ARRIVAL_REQUIRED", error.getErrorCode());
        verify(runtimeMapper, never()).insertSubmission(any());
    }

    @Test
    void pendingSubmissionAcrossRequestIdsIsReturnedWithoutSecondInsertOrRevisionBump() {
        stubPlayerSubmission(configWithInputType("TEXT"), "submit-duplicate");
        GameTaskSubmission pending = new GameTaskSubmission();
        pending.setId(201L);
        pending.setStatus("PENDING");
        when(runtimeMapper.selectActiveSubmission(SESSION, PLAYER, NODE, "CLOCK_PHOTO")).thenReturn(pending);

        GameSessionCommand duplicateCommand = playerSubmitCommand("submit-duplicate");
        duplicateCommand.getPayload().put("evidenceUrls", Collections.singletonList("text:CLOCK_YEAR"));
        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), duplicateCommand);

        assertEquals(201L, ((Number) receipt.getResult().get("submissionId")).longValue());
        assertEquals(Boolean.TRUE, receipt.getResult().get("duplicate"));
        assertEquals(3, receipt.getRevision());
        verify(runtimeMapper, never()).insertSubmission(any());
        verify(runtimeMapper, never()).updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void concurrentSubmissionCollisionReadsLockedWinnerAndCurrentRevision() {
        stubPlayerSubmission(configWithInputType("TEXT"), "submit-concurrent");
        GameSession initial = runningSession(3);
        initial.setModuleConfigSnapshotJson(configWithInputType("TEXT"));
        GameSession current = runningSession(4);
        current.setModuleConfigSnapshotJson(configWithInputType("TEXT"));
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(initial, current);
        when(runtimeMapper.insertSubmission(any())).thenReturn(0);
        GameTaskSubmission winner = new GameTaskSubmission();
        winner.setId(202L);
        winner.setStatus("PENDING");
        when(runtimeMapper.selectActiveSubmissionForUpdate(SESSION, PLAYER, NODE, "CLOCK_PHOTO"))
                .thenReturn(winner);

        GameSessionCommand concurrentCommand = playerSubmitCommand("submit-concurrent");
        concurrentCommand.getPayload().put("evidenceUrls", Collections.singletonList("text:CLOCK_YEAR"));
        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), concurrentCommand);

        assertEquals(202L, ((Number) receipt.getResult().get("submissionId")).longValue());
        assertEquals(Boolean.TRUE, receipt.getResult().get("duplicate"));
        assertEquals(4, receipt.getRevision());
        verify(runtimeMapper).selectActiveSubmissionForUpdate(SESSION, PLAYER, NODE, "CLOCK_PHOTO");
        verify(runtimeMapper, never()).updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void playerChoiceRequiresApprovedTaskAfterArrival() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson(choiceConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.ACTIVE));
        when(runtimeMapper.selectRole(SESSION, PLAYER)).thenReturn(role("DETECTIVE", "侦探"));
        GameTaskSubmission pending = new GameTaskSubmission();
        pending.setStatus("PENDING");
        when(runtimeMapper.selectActiveSubmission(SESSION, PLAYER, NODE, "CLOCK_PHOTO")).thenReturn(pending);
        stubArrived();
        stubReceiptPersistence("choice-before-verify");

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.player(PLAYER), command("choice-before-verify", 4, "PLAYER_CHOICE",
                        mapOf("choiceId", "FOLLOW_NOTE"))));

        assertEquals("GAME_TASK_NOT_APPROVED", error.getErrorCode());
        verify(runtimeMapper, never()).updateStoryStateCas(any());
    }

    @Test
    void repeatedChoiceAcrossRequestIdsDoesNotApplyEffectsTwice() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson(choiceConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.ACTIVE));
        when(runtimeMapper.selectRole(SESSION, PLAYER)).thenReturn(role("DETECTIVE", "侦探"));
        when(runtimeMapper.selectPlayerNodeOutcome(SESSION, PLAYER, NODE)).thenReturn(
                outcome(501L, NODE, GamePlayerNodeOutcome.COMPLETED, "AUTO_HASH"));
        GameStoryState story = storyState("{\"trust\":1}", "[\"FOLLOW_NOTE\"]");
        when(runtimeMapper.selectStoryState(SESSION, "TEAM", TEAM)).thenReturn(story);
        stubArrived();
        stubReceiptPersistence("choice-repeat");

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), command("choice-repeat", 4, "PLAYER_CHOICE",
                        mapOf("choiceId", "FOLLOW_NOTE")));

        assertEquals(Boolean.TRUE, receipt.getResult().get("duplicate"));
        assertEquals("{\"trust\":1}", story.getVariablesJson());
        verify(runtimeMapper, never()).updateStoryStateCas(any());
        verify(runtimeMapper, never()).updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void firstRequiredChoiceDoesNotResolveFallbackEndingEarly() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson(choiceConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.ACTIVE));
        when(runtimeMapper.selectRole(SESSION, PLAYER)).thenReturn(role("DETECTIVE", "侦探"));
        when(runtimeMapper.selectPlayerNodeOutcome(SESSION, PLAYER, NODE)).thenReturn(
                outcome(502L, NODE, GamePlayerNodeOutcome.COMPLETED, "AUTO_HASH"));
        GameStoryState story = storyState("{\"trust\":0}", "[]");
        when(runtimeMapper.selectStoryState(SESSION, "TEAM", TEAM)).thenReturn(story);
        when(runtimeMapper.updateStoryStateCas(story)).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any())).thenReturn(1);
        stubArrived();
        stubReceiptPersistence("choice-first");

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), command("choice-first", 4, "PLAYER_CHOICE",
                        mapOf("choiceId", "FOLLOW_NOTE")));

        assertNull(receipt.getResult().get("ending"));
        assertEquals("VARIABLES_UPDATED", story.getStatus());
        assertTrue(story.getVariablesJson().contains("\"trust\":1"));
        assertEquals("[\"FOLLOW_NOTE\"]", story.getAppliedChoicesJson());
    }

    @Test
    void alternativeRequiredChoiceCanResolveTeamTruthOnServer() {
        GameSession session = runningSession(4);
        session.setCurrentChapterId(2L);
        session.setModuleConfigSnapshotJson(branchingChoiceConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.ACTIVE));
        when(runtimeMapper.selectPlayerNodeOutcome(SESSION, PLAYER, NODE)).thenReturn(
                outcome(503L, NODE, GamePlayerNodeOutcome.COMPLETED, "AUTO_HASH"));
        GameStoryState story = storyState("{\"evidence\":0}", "[]");
        when(runtimeMapper.selectStoryState(SESSION, "TEAM", TEAM)).thenReturn(story);
        when(runtimeMapper.updateStoryStateCas(story)).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        stubArrived();
        stubReceiptPersistence("choice-team-truth");

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), command("choice-team-truth", 4, "PLAYER_CHOICE",
                        mapOf("choiceId", "VERIFY")));

        Map<?, ?> ending = (Map<?, ?>) receipt.getResult().get("ending");
        assertEquals("TEAM_TRUTH", ending.get("code"));
        assertEquals("ENDING_RESOLVED", story.getStatus());
        assertTrue(story.getVariablesJson().contains("\"evidence\":1"));
    }

    @Test
    void alternativeRequiredChoiceCanResolveOpenCaseOnServer() {
        GameSession session = runningSession(4);
        session.setCurrentChapterId(2L);
        session.setModuleConfigSnapshotJson(branchingChoiceConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.ACTIVE));
        when(runtimeMapper.selectPlayerNodeOutcome(SESSION, PLAYER, NODE)).thenReturn(
                outcome(504L, NODE, GamePlayerNodeOutcome.COMPLETED, "AUTO_HASH"));
        GameStoryState story = storyState("{\"evidence\":0}", "[]");
        when(runtimeMapper.selectStoryState(SESSION, "TEAM", TEAM)).thenReturn(story);
        when(runtimeMapper.updateStoryStateCas(story)).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        stubArrived();
        stubReceiptPersistence("choice-open-case");

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), command("choice-open-case", 4, "PLAYER_CHOICE",
                        mapOf("choiceId", "SKIP_VERIFY")));

        Map<?, ?> ending = (Map<?, ?>) receipt.getResult().get("ending");
        assertEquals("OPEN_CASE", ending.get("code"));
        assertEquals("ENDING_RESOLVED", story.getStatus());
        assertTrue(story.getVariablesJson().contains("\"evidence\":0"));
    }

    @Test
    void routeStyleChoiceEffectsWriteVariablesAndTagsThenEndingConditionHits() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson(routeStyleStoryConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.ACTIVE));
        when(runtimeMapper.selectPlayerNodeOutcome(SESSION, PLAYER, NODE)).thenReturn(
                outcome(511L, NODE, GamePlayerNodeOutcome.COMPLETED, "AUTO_HASH"));
        GameStoryState story = storyState("{\"trust\":0}", "[]");
        when(runtimeMapper.selectStoryState(SESSION, "TEAM", TEAM)).thenReturn(story);
        when(runtimeMapper.updateStoryStateCas(story)).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        stubArrived();
        stubReceiptPersistence("route-style-hit");

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), command("route-style-hit", 4, "PLAYER_CHOICE",
                        mapOf("choiceId", "FOLLOW_NOTE")));

        Map<?, ?> ending = (Map<?, ?>) receipt.getResult().get("ending");
        assertEquals("TRUSTED", ending.get("code"));
        assertEquals("ENDING_RESOLVED", story.getStatus());
        assertTrue(story.getVariablesJson().contains("\"trust\":2"));
        assertTrue(story.getVariablesJson().contains("\"mood\":7"));
        assertTrue(story.getVariablesJson().contains("tag.hurried"));
    }

    @Test
    void routeStyleEndingConditionMissUsesDeclaredFallback() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson(routeStyleStoryConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.ACTIVE));
        when(runtimeMapper.selectPlayerNodeOutcome(SESSION, PLAYER, NODE)).thenReturn(
                outcome(512L, NODE, GamePlayerNodeOutcome.COMPLETED, "AUTO_HASH"));
        GameStoryState story = storyState("{\"trust\":-5}", "[]");
        when(runtimeMapper.selectStoryState(SESSION, "TEAM", TEAM)).thenReturn(story);
        when(runtimeMapper.updateStoryStateCas(story)).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        stubArrived();
        stubReceiptPersistence("route-style-fallback");

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), command("route-style-fallback", 4, "PLAYER_CHOICE",
                        mapOf("choiceId", "FOLLOW_NOTE")));

        Map<?, ?> ending = (Map<?, ?>) receipt.getResult().get("ending");
        assertEquals("OPEN_CASE", ending.get("code"));
        assertEquals("ENDING_RESOLVED", story.getStatus());
        assertTrue(story.getVariablesJson().contains("\"trust\":-3"));
    }

    @Test
    void routeStyleEndingConditionMissWithoutFallbackKeepsStoryUnresolved() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson(routeStyleStoryConfigJsonWithoutFallback());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.ACTIVE));
        when(runtimeMapper.selectPlayerNodeOutcome(SESSION, PLAYER, NODE)).thenReturn(
                outcome(513L, NODE, GamePlayerNodeOutcome.COMPLETED, "AUTO_HASH"));
        GameStoryState story = storyState("{\"trust\":-5}", "[]");
        when(runtimeMapper.selectStoryState(SESSION, "TEAM", TEAM)).thenReturn(story);
        when(runtimeMapper.updateStoryStateCas(story)).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        stubArrived();
        stubReceiptPersistence("route-style-no-fallback");

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.player(PLAYER), command("route-style-no-fallback", 4, "PLAYER_CHOICE",
                        mapOf("choiceId", "FOLLOW_NOTE")));

        assertNull(receipt.getResult().get("ending"));
        assertEquals("VARIABLES_UPDATED", story.getStatus());
    }

    @Test
    void routeStyleStoryRuleValidationRejectsBadRulesAndLeavesObjectStyleUntouched() {
        assertTrue(service.isGameModuleConfigEnabled(routeStyleStoryConfigJson()));
        assertTrue(service.isGameModuleConfigEnabled(choiceConfigJson()));
        assertFalse(service.isGameModuleConfigEnabled(routeStyleStoryConfigJson().replace(
                "{\"op\":\"INC\",\"var\":\"trust\",\"value\":2}", "{\"op\":\"ADD\",\"var\":\"trust\",\"value\":2}")));
        assertFalse(service.isGameModuleConfigEnabled(routeStyleStoryConfigJson().replace(
                "{\"var\":\"trust\",\"op\":\"GTE\",\"value\":2}", "{\"var\":\"trust\",\"op\":\"ADD\",\"value\":2}")));
        assertFalse(service.isGameModuleConfigEnabled(routeStyleStoryConfigJson().replace(
                "{\"var\":\"trust\",\"op\":\"GTE\",\"value\":2}", "{\"var\":\"trust\",\"op\":\"GTE\",\"value\":100}")));
        assertFalse(service.isGameModuleConfigEnabled(routeStyleStoryConfigJson().replace(
                "{\"op\":\"ADD_TAG\",\"value\":\"tag.hurried\"}", "{\"op\":\"ADD_TAG\",\"value\":\"\"}")));
        assertFalse(service.isGameModuleConfigEnabled(routeStyleStoryConfigJson().replace(
                "{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"}",
                "{\"op\":\"NODE_COMPLETED\",\"nodeId\":123}")));
        StringBuilder seventeen = new StringBuilder("\"conditions\":[");
        for (int index = 0; index < 17; index++) {
            if (index > 0) seventeen.append(',');
            seventeen.append("{\"var\":\"trust\",\"op\":\"EQ\",\"value\":0}");
        }
        seventeen.append(']');
        assertFalse(service.isGameModuleConfigEnabled(routeStyleStoryConfigJson().replace(
                "\"conditions\":[{\"var\":\"trust\",\"op\":\"GTE\",\"value\":2},"
                        + "{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"}]", seventeen.toString())));
    }

    @Test
    void prepareRejectsInvalidRouteStyleStoryRuleBeforeCreatingSession() {
        CmsTopic topic = configuredTopic();
        topic.setGameModuleConfigJson(routeStyleStoryConfigJson().replace(
                "{\"op\":\"INC\",\"var\":\"trust\",\"value\":2}", "{\"op\":\"ADD\",\"var\":\"trust\",\"value\":2}"));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        when(runtimeMapper.insertReceiptClaim(any())).thenReturn(1);

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(PLAYER), command("prepare-route-style-invalid", 0, "PREPARE",
                        Collections.emptyMap())));

        assertEquals("GAME_CONFIG_INVALID", error.getErrorCode());
        verify(runtimeMapper, never()).insertSession(any());
    }

    @Test
    void routeStyleEndingRejectsEmptyConditionsArrayUnlessFallback() {
        assertTrue(service.isGameModuleConfigEnabled(routeStyleStoryConfigJson()));
        assertTrue(service.isGameModuleConfigEnabled(routeStyleStoryConfigJson().replace(
                "\"fallback\":true}", "\"fallback\":true,\"conditions\":[]}")));
        assertFalse(service.isGameModuleConfigEnabled(routeStyleStoryConfigJson().replace(
                "\"conditions\":[{\"var\":\"trust\",\"op\":\"GTE\",\"value\":2},"
                        + "{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"}]", "\"conditions\":[]")));
        assertTrue(service.isGameModuleConfigEnabled(routeStyleStoryConfigJson().replace(
                "\"conditions\":[{\"var\":\"trust\",\"op\":\"GTE\",\"value\":2},"
                        + "{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"}]", "\"conditions\":{}")));
        assertTrue(service.isGameModuleConfigEnabled(branchingChoiceConfigJson()));
    }

    @Test
    void prepareRejectsEmptyEndingConditionsWithHumanReadableMessage() {
        CmsTopic topic = configuredTopic();
        topic.setGameModuleConfigJson(routeStyleStoryConfigJson().replace(
                "\"conditions\":[{\"var\":\"trust\",\"op\":\"GTE\",\"value\":2},"
                        + "{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"}]", "\"conditions\":[]"));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        when(runtimeMapper.insertReceiptClaim(any())).thenReturn(1);

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(PLAYER), command("prepare-empty-ending-conditions", 0, "PREPARE",
                        Collections.emptyMap())));

        assertEquals("GAME_CONFIG_INVALID", error.getErrorCode());
        assertTrue(error.getMessage().contains("结局条件不能为空"));
        assertTrue(error.getMessage().contains("fallback"));
        verify(runtimeMapper, never()).insertSession(any());
    }

    @Test
    void manualChapterUnlockChangesPlayerChoiceProjectionWithoutCompletingTasks() {
        GameSession session = runningSession(3);
        session.setCurrentChapterId(1L);
        session.setModuleConfigSnapshotJson(branchingChoiceConfigJson());
        GameStationRun active = station(MERCHANT, GameStationRun.ACTIVE);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(active));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(active);
        when(runtimeMapper.selectPlayerNodeOutcomes(SESSION, PLAYER)).thenReturn(
                Collections.singletonList(outcome(505L, NODE, GamePlayerNodeOutcome.COMPLETED, "AUTO_HASH")));
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        stubArrived();
        stubReceiptPersistence("unlock-choice-chapter");

        GameSessionView before = service.view(GameActorContext.player(PLAYER), ACTIVITY);
        Map<?, ?> beforeNode = (Map<?, ?>) ((List<?>) before.getPlayer().get("nodes")).get(0);
        assertFalse(before.getAvailableActions().contains("PLAYER_CHOICE"));
        assertTrue(((List<?>) beforeNode.get("allowedChoices")).isEmpty());

        Map<String, Object> unlockPayload = new LinkedHashMap<>();
        unlockPayload.put("chapterId", 2L);
        unlockPayload.put("reason", "现场导演确认进入共同推理章");
        service.command(GameActorContext.club(DIRECTOR),
                command("unlock-choice-chapter", 3, "UNLOCK_CHAPTER", unlockPayload));

        GameSessionView after = service.view(GameActorContext.player(PLAYER), ACTIVITY);
        Map<?, ?> afterNode = (Map<?, ?>) ((List<?>) after.getPlayer().get("nodes")).get(0);
        assertEquals(2L, session.getCurrentChapterId());
        assertTrue(after.getAvailableActions().contains("PLAYER_CHOICE"));
        assertTrue(String.valueOf(afterNode.get("allowedChoices")).contains("VERIFY"));
        assertTrue(String.valueOf(afterNode.get("allowedChoices")).contains("SKIP_VERIFY"));
        verify(runtimeMapper, never()).insertSubmission(any());
    }

    /**
     * CU-C-76(2026-09-24 走查):「定向广播」不可点的时候,投影要说是哪一种不可点。
     *
     * <p>走查读到的是:零成员的主题 #990030,入口照样能点,点完只有一句「当前不能发送广播」,
     * 既不说因为无成员还是无可带队场次,也不给下一步。这里钉三件事:跑起来了但没人可送达
     * 必须给 NO_RECIPIENT;有人可送达给空串;本场没跑起来给 NOT_RUNNING。
     * BROADCAST 仍留在 availableActions 里 —— 那是「本场允不允许这个动作」的能力位,
     * 收掉它就等于把原因又抹回成「没这个功能」。
     */
    @Test
    void clubViewNamesWhichBroadcastBlockerTheSessionHas() {
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        when(runtimeMapper.countTeamMembersWithoutRole(SESSION, ACTIVITY)).thenReturn(0);
        GameSession running = runningSession(4);
        running.setModuleConfigSnapshotJson(runtimeConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(running);

        when(runtimeMapper.countActiveTeams(ACTIVITY)).thenReturn(0);
        Map<?, ?> nobody = service.view(GameActorContext.club(DIRECTOR), ACTIVITY).getClub();
        assertEquals("NO_RECIPIENT", nobody.get("broadcastBlocker"),
                "零成员的场子必须报「没人可送达」,不能再让人点进去吃一句笼统提示");
        assertTrue(service.view(GameActorContext.club(DIRECTOR), ACTIVITY).getAvailableActions()
                .contains("BROADCAST"), "原因码由 broadcastBlocker 承担,BROADCAST 不得整个从能力位里消失");

        when(runtimeMapper.countActiveTeams(ACTIVITY)).thenReturn(1);
        Map<?, ?> sendable = service.view(GameActorContext.club(DIRECTOR), ACTIVITY).getClub();
        assertEquals("", sendable.get("broadcastBlocker"), "有可送达玩家就是可发送,交空串而不是缺键");

        GameSession ready = readySession(4);
        ready.setModuleConfigSnapshotJson(runtimeConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(ready);
        Map<?, ?> notRunning = service.view(GameActorContext.club(DIRECTOR), ACTIVITY).getClub();
        assertEquals("NOT_RUNNING", notRunning.get("broadcastBlocker"),
                "没跑起来要说成场次状态,不能套用「等玩家报名」那句");
    }

    @Test
    void clubTeamProgressPreservesAuthoritativeStuckNodeHintAndRecentReceiptWithoutZeroFallback() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        Map<String, Object> normal = new LinkedHashMap<>();
        normal.put("teamId", TEAM + 1);
        normal.put("teamName", "流动队");
        normal.put("memberCount", 2);
        normal.put("completedNodes", 1);
        normal.put("totalNodes", 3);
        normal.put("status", "RUNNING");
        Map<String, Object> blocked = new LinkedHashMap<>();
        blocked.put("teamId", TEAM);
        blocked.put("teamName", "卡点队");
        blocked.put("memberCount", 2);
        blocked.put("completedNodes", 1);
        blocked.put("totalNodes", 3);
        blocked.put("status", "BLOCKED");
        blocked.put("currentStuckNodeId", NODE);
        blocked.put("currentStuckNodeName", "旧钟楼");
        blocked.put("currentStuckStationStatus", "PAUSED");
        blocked.put("currentStuckSubmissionStatus", "PENDING");
        blocked.put("hintLevel", 2);
        blocked.put("recentEventRequestId", "player-hint-2");
        blocked.put("recentEventAction", "PLAYER_HINT");
        blocked.put("recentEventOutcome", "APPLIED");
        blocked.put("recentEventRevision", 8);
        blocked.put("recentEventAt", DateUtils.parseDate("2026-08-23 11:59:00"));
        when(runtimeMapper.selectClubTeamProgressProjection(SESSION)).thenReturn(Arrays.asList(normal, blocked));

        GameSessionView view = service.view(GameActorContext.club(DIRECTOR), ACTIVITY);
        List<?> teams = (List<?>) view.getClub().get("teams");
        Map<?, ?> normalView = (Map<?, ?>) teams.get(0);
        Map<?, ?> blockedView = (Map<?, ?>) teams.get(1);
        Map<?, ?> stuck = (Map<?, ?>) blockedView.get("currentStuckNode");
        Map<?, ?> recent = (Map<?, ?>) blockedView.get("recentEvent");

        assertEquals(NODE, ((Number) stuck.get("nodeId")).longValue());
        assertEquals("旧钟楼", stuck.get("nodeName"));
        assertEquals("PAUSED", stuck.get("stationStatus"));
        assertEquals(2, ((Number) blockedView.get("hintLevel")).intValue());
        assertTrue(String.valueOf(blockedView.get("blockedReason")).contains("旧钟楼"));
        assertEquals("PLAYER_HINT", recent.get("action"));
        assertEquals("APPLIED", recent.get("outcome"));
        assertNull(normalView.get("currentStuckNode"));
        assertNull(normalView.get("hintLevel"));
        assertFalse(String.valueOf(normalView).contains("hintLevel=0"));
    }

    @Test
    void stationReadyStoresServerCanonicalChecklistLabels() {
        GameSession session = runningSession(2);
        session.setStatus(GameSession.PREPARING);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        GameStationRun accepted = station(MERCHANT, GameStationRun.ACCEPTED);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(accepted);
        when(runtimeMapper.updateStationStatusCas(any(), anyLong(), anyString(), anyString(), anyInt(), any())).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any())).thenReturn(1);
        stubReceiptPersistence("ready-canonical");
        Map<String, Object> clientItem = new LinkedHashMap<>();
        clientItem.put("code", "STAFF");
        clientItem.put("label", "客户端伪造文案");
        clientItem.put("checked", true);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serviceStartAt", "2026-08-24 09:00:00");
        payload.put("serviceEndAt", "2026-08-24 18:00:00");
        payload.put("capacity", 20);
        payload.put("checklist", Collections.singletonList(clientItem));

        service.command(GameActorContext.merchant(MERCHANT),
                command("ready-canonical", 2, "STATION_READY", payload));

        assertTrue(accepted.getPreparationSnapshotJson().contains("人员已到位"));
        assertFalse(accepted.getPreparationSnapshotJson().contains("客户端伪造文案"));
    }

    @Test
    void merchantViewIncludesOnlyServerScopedPiiFreeRecapAndKeepsFallbackSeparate() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        GameStationRun ownStation = station(MERCHANT, GameStationRun.ACTIVE);
        Map<String, Object> recapRow = new LinkedHashMap<>();
        recapRow.put("stationRunId", ownStation.getId());
        recapRow.put("arrivedPlayers", 8);
        recapRow.put("submissionCount", 7);
        recapRow.put("approvedCount", 2);
        recapRow.put("rejectedCount", 1);
        recapRow.put("recordedCount", 1);
        recapRow.put("normalCompletedCount", 2);
        recapRow.put("fallbackCompletedCount", 3);
        recapRow.put("pauseEventCount", 4);
        recapRow.put("authorizedContentCount", 0);
        recapRow.put("contentPolicy", "NO_PUBLIC_CONTENT");
        recapRow.put("phone", "private-phone-marker");
        recapRow.put("evidenceJson", "private-evidence-marker");
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectMerchantStations(SESSION, MERCHANT)).thenReturn(Collections.singletonList(ownStation));
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(ownStation));
        when(runtimeMapper.selectMerchantStationRecaps(SESSION, MERCHANT))
                .thenReturn(Collections.singletonList(recapRow));

        GameSessionView view = service.view(GameActorContext.merchant(MERCHANT), ACTIVITY);

        Map<?, ?> stationView = (Map<?, ?>) ((List<?>) view.getMerchant().get("stations")).get(0);
        Map<?, ?> recap = (Map<?, ?>) stationView.get("recap");
        assertEquals(2, ((Number) recap.get("normalCompletedCount")).intValue());
        assertEquals(3, ((Number) recap.get("fallbackCompletedCount")).intValue());
        assertEquals(0, ((Number) recap.get("authorizedContentCount")).intValue());
        assertEquals("NO_PUBLIC_CONTENT", recap.get("contentPolicy"));
        String publicProjection = String.valueOf(recap).toLowerCase();
        assertFalse(publicProjection.contains("private-phone-marker"));
        assertFalse(publicProjection.contains("private-evidence-marker"));
        assertFalse(publicProjection.contains("phone"));
        assertFalse(publicProjection.contains("evidence"));
        verify(runtimeMapper).selectMerchantStationRecaps(SESSION, MERCHANT);
    }

    @Test
    void clubViewAndExportShareOneSafeRecapWithoutInventingPlayerFailure() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson(runtimeConfigJson());
        Map<String, Object> aggregate = clubRecapAggregate();
        aggregate.put("memberId", 999L);
        aggregate.put("phone", "private-phone-marker");
        Map<String, Object> station = clubStationRecap();
        station.put("memberName", "private-name-marker");
        station.put("evidenceJson", "private-evidence-marker");
        Map<String, Object> collaboration = new LinkedHashMap<>();
        collaboration.put("teamId", TEAM);
        collaboration.put("requiredRoleCount", 2);
        collaboration.put("assignedRoleCount", 2);
        collaboration.put("contributedRoleCount", 2);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        when(runtimeMapper.selectClubRecapAggregate(SESSION, ACTIVITY)).thenReturn(aggregate);
        when(runtimeMapper.selectClubStationRecaps(SESSION)).thenReturn(Collections.singletonList(station));
        when(runtimeMapper.selectClubRoleCollaboration(SESSION, ACTIVITY))
                .thenReturn(Collections.singletonList(collaboration));

        Map<String, Object> exported = service.exportRecap(GameActorContext.club(DIRECTOR), ACTIVITY);
        GameSessionView view = service.view(GameActorContext.club(DIRECTOR), ACTIVITY);

        assertEquals("GAME_RECAP_EXPORT_V1", exported.get("schemaVersion"));
        assertEquals(ACTIVITY, exported.get("activityId"));
        assertEquals(SESSION, exported.get("sessionId"));
        Map<?, ?> recap = (Map<?, ?>) exported.get("recap");
        assertEquals("GAME_RECAP_V1", recap.get("schemaVersion"));
        assertEquals(recap, view.getClub().get("recap"));
        Map<?, ?> funnel = (Map<?, ?>) recap.get("funnel");
        assertEquals(2, ((Number) funnel.get("normalCompleters")).intValue());
        assertEquals(3, ((Number) funnel.get("fallbackCompleters")).intValue());
        Map<?, ?> collaborationView = (Map<?, ?>) recap.get("collaboration");
        assertEquals(1, ((Number) collaborationView.get("eligibleTeams")).intValue());
        assertEquals(1, ((Number) collaborationView.get("completedTeams")).intValue());
        assertEquals(100, ((Number) collaborationView.get("ratePercent")).intValue());
        String publicProjection = String.valueOf(exported).toLowerCase();
        assertFalse(publicProjection.contains("private-phone-marker"));
        assertFalse(publicProjection.contains("private-name-marker"));
        assertFalse(publicProjection.contains("private-evidence-marker"));
        assertFalse(publicProjection.contains("memberid"));
        assertFalse(publicProjection.contains("phone"));
        assertFalse(publicProjection.contains("evidence"));
        assertFalse(publicProjection.contains("failure"));
    }

    @Test
    void recapExportChecksClubOwnerBeforeReadingAnyAggregate() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(false);

        GameSessionException error = assertThrows(GameSessionException.class,
                () -> service.exportRecap(GameActorContext.club(DIRECTOR), ACTIVITY));

        assertEquals("GAME_FORBIDDEN_SCOPE", error.getErrorCode());
        verify(runtimeMapper, never()).selectClubRecapAggregate(anyLong(), anyLong());
        verify(runtimeMapper, never()).selectClubStationRecaps(anyLong());
        verify(runtimeMapper, never()).selectClubRoleCollaboration(anyLong(), anyLong());
    }

    @Test
    void manualTakeoverRejectsNonOwnerBeforeReadingTeamOrRoleState() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(false);

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(DIRECTOR), takeoverCommand("takeover-non-owner", 4)));

        assertEquals("GAME_FORBIDDEN_SCOPE", error.getErrorCode());
        verify(runtimeMapper, never()).isActiveTeamMember(anyLong(), anyLong(), anyLong());
        verify(runtimeMapper, never()).selectActiveRoleForUpdate(anyLong(), anyLong());
        verify(runtimeMapper, never()).supersedeActiveRole(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void manualTakeoverIsRejectedUnlessSessionIsRunning() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(readySession(4));
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        stubReceiptPersistence("takeover-not-running");

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(DIRECTOR), takeoverCommand("takeover-not-running", 4)));

        assertEquals("GAME_STATE_CONFLICT", error.getErrorCode());
        verify(runtimeMapper, never()).selectActiveRoleForUpdate(anyLong(), anyLong());
        verify(runtimeMapper, never()).supersedeActiveRole(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void manualTakeoverRejectsCrossTeamTargetBeforeSupersedingSource() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        when(runtimeMapper.isActiveTeamMember(ACTIVITY, TEAM, PLAYER)).thenReturn(1);
        when(runtimeMapper.isActiveTeamMember(ACTIVITY, TEAM, TARGET_PLAYER)).thenReturn(0);
        stubReceiptPersistence("takeover-cross-team");

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(DIRECTOR), takeoverCommand("takeover-cross-team", 4)));

        assertEquals("GAME_FORBIDDEN_SCOPE", error.getErrorCode());
        verify(runtimeMapper, never()).supersedeActiveRole(anyLong(), anyLong(), anyLong(), any());
        verify(runtimeMapper, never()).insertRole(any());
    }

    @Test
    void manualTakeoverRejectsTargetThatAlreadyHasAnActiveRole() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        when(runtimeMapper.isActiveTeamMember(ACTIVITY, TEAM, PLAYER)).thenReturn(1);
        when(runtimeMapper.isActiveTeamMember(ACTIVITY, TEAM, TARGET_PLAYER)).thenReturn(1);
        when(runtimeMapper.selectActiveRoleForUpdate(SESSION, PLAYER)).thenReturn(takeoverRole(PLAYER));
        when(runtimeMapper.selectActiveRoleForUpdate(SESSION, TARGET_PLAYER)).thenReturn(takeoverRole(TARGET_PLAYER));
        stubReceiptPersistence("takeover-target-occupied");

        assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(DIRECTOR), takeoverCommand("takeover-target-occupied", 4)));

        verify(runtimeMapper, never()).supersedeActiveRole(anyLong(), anyLong(), anyLong(), any());
        verify(runtimeMapper, never()).insertRole(any());
    }

    @Test
    void manualTakeoverRejectsUnsafeReasonBeforeChangingRoleHistory() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        when(runtimeMapper.isActiveTeamMember(ACTIVITY, TEAM, PLAYER)).thenReturn(1);
        when(runtimeMapper.isActiveTeamMember(ACTIVITY, TEAM, TARGET_PLAYER)).thenReturn(1);
        when(runtimeMapper.selectActiveRoleForUpdate(SESSION, PLAYER)).thenReturn(takeoverRole(PLAYER));
        when(runtimeMapper.selectActiveRoleForUpdate(SESSION, TARGET_PLAYER)).thenReturn(null);
        doThrow(new RuntimeException("内容违规")).when(imService)
                .checkUserGeneratedText(DIRECTOR, "玩家临时离场");
        stubReceiptPersistence("takeover-unsafe-reason");

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(DIRECTOR), takeoverCommand("takeover-unsafe-reason", 4)));

        assertEquals("GAME_CONTENT_UNSAFE", error.getErrorCode());
        verify(runtimeMapper, never()).supersedeActiveRole(anyLong(), anyLong(), anyLong(), any());
        verify(runtimeMapper, never()).insertRole(any());
    }

    @Test
    void successfulManualTakeoverBlocksSourceAndTransfersConfirmedRoleToTarget() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson(choiceConfigJson());
        GameTeamRoleAssignment sourceRole = takeoverRole(PLAYER);
        final GameTeamRoleAssignment[] transferred = new GameTeamRoleAssignment[1];
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        when(runtimeMapper.isActiveTeamMember(ACTIVITY, TEAM, PLAYER)).thenReturn(1);
        when(runtimeMapper.isActiveTeamMember(ACTIVITY, TEAM, TARGET_PLAYER)).thenReturn(1);
        when(runtimeMapper.selectActiveRoleForUpdate(SESSION, PLAYER)).thenReturn(sourceRole);
        when(runtimeMapper.selectActiveRoleForUpdate(SESSION, TARGET_PLAYER)).thenReturn(null);
        when(runtimeMapper.supersedeActiveRole(eq(SESSION), eq(PLAYER), eq(DIRECTOR), any())).thenReturn(1);
        when(runtimeMapper.insertRole(any())).thenAnswer(invocation -> {
            transferred[0] = invocation.getArgument(0);
            transferred[0].setId(92L);
            return 1;
        });
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        stubReceiptPersistence("takeover-success");

        com.chengyinhub.business.domain.dto.GameCommandReceiptView receipt = service.command(
                GameActorContext.club(DIRECTOR), takeoverCommand("takeover-success", 4));

        assertEquals(5, receipt.getRevision());
        assertEquals(TARGET_PLAYER, ((Number) receipt.getResult().get("targetMemberId")).longValue());
        assertEquals("DETECTIVE", receipt.getResult().get("roleCode"));
        assertEquals(TEAM, transferred[0].getTeamId());
        assertEquals(TARGET_PLAYER, transferred[0].getMemberId());
        assertEquals("DETECTIVE", transferred[0].getRoleCode());
        assertEquals("CONFIRMED", transferred[0].getStatus());
        assertEquals(DIRECTOR, transferred[0].getAssignedBy());

        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectRole(SESSION, PLAYER)).thenReturn(null);
        when(runtimeMapper.selectActiveRoleForUpdate(SESSION, PLAYER)).thenReturn(null);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, GameStationRun.ACTIVE));
        stubArrived();
        stubReceiptPersistence("source-after-takeover");
        GameSessionException sourceError = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.player(PLAYER), command("source-after-takeover", 5, "PLAYER_CHOICE",
                        mapOf("choiceId", "FOLLOW_NOTE"))));
        assertEquals("GAME_ROLE_NOT_ASSIGNED", sourceError.getErrorCode());

        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.emptyList());
        GameSessionView sourceView = service.view(GameActorContext.player(PLAYER), ACTIVITY);
        assertFalse(sourceView.getAvailableActions().contains("PLAYER_CHOICE"));
        assertFalse(sourceView.getAvailableActions().contains("PLAYER_SUBMIT"));

        when(playTeamMapper.hasPaidRegistration(TARGET_PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, TARGET_PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectRole(SESSION, TARGET_PLAYER)).thenReturn(transferred[0]);
        GameSessionView targetView = service.view(GameActorContext.player(TARGET_PLAYER), ACTIVITY);
        assertEquals("DETECTIVE", ((Map<?, ?>) targetView.getPlayer().get("role")).get("code"));
        assertTrue(targetView.getAvailableActions().contains("PLAYER_CHOICE"));
        assertTrue(targetView.getAvailableActions().contains("PLAYER_SUBMIT"));
    }

    private Map<String, Object> clubRecapAggregate() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("paidPlayers", 10);
        row.put("arrivedPlayers", 8);
        row.put("taskSubmitters", 7);
        row.put("normalCompleters", 2);
        row.put("fallbackCompleters", 3);
        row.put("finishedTeams", 1);
        row.put("level1Uses", 4);
        row.put("level2Uses", 2);
        row.put("answerReveals", 1);
        row.put("merchantPauseEvents", 5);
        row.put("merchantFallbackCompletions", 3);
        row.put("playerRejectedSubmissions", 6);
        row.put("takeoverCount", 1);
        return row;
    }

    private Map<String, Object> clubStationRecap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nodeId", NODE);
        row.put("nodeName", "旧钟楼");
        row.put("arrivedPlayers", 8);
        row.put("submissionCount", 7);
        row.put("normalCompletedCount", 2);
        row.put("fallbackCompletedCount", 3);
        row.put("rejectedCount", 1);
        row.put("pauseEventCount", 4);
        return row;
    }

    private GameSessionCommand takeoverCommand(String requestId, int revision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("teamId", TEAM);
        payload.put("sourceMemberId", PLAYER);
        payload.put("targetMemberId", TARGET_PLAYER);
        payload.put("reason", "玩家临时离场");
        GameSessionCommand command = command(requestId, revision, "TAKEOVER_ROLE", payload);
        command.setNodeId(null);
        return command;
    }

    private GameSessionCommand broadcastCommand(String requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetType", "ALL");
        payload.put("content", "集合提醒");
        GameSessionCommand command = command(requestId, 2, "BROADCAST", payload);
        command.setNodeId(null);
        return command;
    }

    private void stubSingleBroadcastDelivery(String requestId, long broadcastId, long deliveryId) {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(readySession(2));
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        stubReceiptPersistence(requestId);
        when(runtimeMapper.insertBroadcast(any())).thenAnswer(invocation -> {
            com.chengyinhub.business.domain.GameBroadcast row = invocation.getArgument(0);
            row.setId(broadcastId);
            return 1;
        });
        when(runtimeMapper.insertBroadcastDeliveries(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any()))
                .thenReturn(1);
        com.chengyinhub.business.domain.GameBroadcastDelivery delivery =
                new com.chengyinhub.business.domain.GameBroadcastDelivery();
        delivery.setId(deliveryId);
        delivery.setMemberId(PLAYER + 1);
        when(runtimeMapper.selectPendingDeliveries(broadcastId)).thenReturn(Collections.singletonList(delivery));
    }

    private GameTeamRoleAssignment takeoverRole(long memberId) {
        GameTeamRoleAssignment assignment = role("DETECTIVE", "侦探");
        assignment.setId(memberId);
        assignment.setGameSessionId(SESSION);
        assignment.setTeamId(TEAM);
        assignment.setMemberId(memberId);
        return assignment;
    }

    private CmsActivity activeActivity() {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setClubId(CLUB);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        activity.setStartDate(DateUtils.parseDate("2026-08-23 10:00:00"));
        activity.setEndDate(DateUtils.parseDate("2026-08-23 18:00:00"));
        return activity;
    }

    private CmsTopic configuredTopic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setGameModuleConfigJson(configJson());
        return topic;
    }

    private GameSession runningSession(int revision) {
        GameSession session = new GameSession();
        session.setId(SESSION);
        session.setActivityId(ACTIVITY);
        session.setTopicId(TOPIC);
        session.setClubId(CLUB);
        session.setStatus("RUNNING");
        session.setRevision(revision);
        session.setModuleConfigSnapshotJson(configJson());
        return session;
    }

    private GameSession readySession(int revision) {
        GameSession session = runningSession(revision);
        session.setStatus("READY");
        return session;
    }

    private GameTeamRoleAssignment role(String code, String name) {
        GameTeamRoleAssignment role = new GameTeamRoleAssignment();
        role.setRoleCode(code);
        role.setRoleName(name);
        role.setStatus("CONFIRMED");
        return role;
    }

    private GameStationRun station(long merchantMemberId, String status) {
        GameStationRun station = new GameStationRun();
        station.setId(5101L);
        station.setGameSessionId(SESSION);
        station.setActivityId(ACTIVITY);
        station.setNodeId(NODE);
        station.setNodeName("旧钟楼");
        station.setStationCode("CLOCK_TOWER");
        station.setMerchantMemberId(merchantMemberId);
        station.setStatus(status);
        station.setRevision(0);
        station.setServiceStartAt(DateUtils.parseDate("2026-08-23 10:00:00"));
        station.setServiceEndAt(DateUtils.parseDate("2026-08-23 18:00:00"));
        return station;
    }

    private GameStationRun fallbackStation() {
        GameStationRun station = station(MERCHANT + 1, GameStationRun.ACTIVE);
        station.setId(5102L);
        station.setNodeId(FALLBACK_NODE);
        station.setStationCode("ARCHIVE");
        station.setNodeName("旧档案馆");
        return station;
    }

    private GameStoryState storyState(String variables, String appliedChoices) {
        GameStoryState story = new GameStoryState();
        story.setId(88L);
        story.setGameSessionId(SESSION);
        story.setOwnerType("TEAM");
        story.setOwnerId(TEAM);
        story.setVariablesJson(variables);
        story.setAppliedChoicesJson(appliedChoices);
        story.setStatus("VARIABLES_UPDATED");
        story.setRevision(0);
        return story;
    }

    private GamePlayerNodeOutcome outcome(long id, long nodeId, String status, String source) {
        GamePlayerNodeOutcome outcome = new GamePlayerNodeOutcome();
        outcome.setId(id);
        outcome.setGameSessionId(SESSION);
        outcome.setActivityId(ACTIVITY);
        outcome.setStationRunId(5101L);
        outcome.setNodeId(nodeId);
        outcome.setTeamId(TEAM);
        outcome.setMemberId(PLAYER);
        outcome.setStatus(status);
        outcome.setCompletionSource(source);
        outcome.setCompletedAt(DateUtils.parseDate("2026-08-23 12:00:00"));
        outcome.setCreateTime(DateUtils.parseDate("2026-08-23 12:00:00"));
        return outcome;
    }

    private Map<String, Object> hintLevelRow(long nodeId, int currentLevel) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nodeId", nodeId);
        row.put("currentLevel", currentLevel);
        return row;
    }

    private GameSessionCommand command(String requestId, int revision, String action, Map<String, Object> payload) {
        GameSessionCommand command = new GameSessionCommand();
        command.setActivityId(ACTIVITY);
        command.setNodeId(NODE);
        command.setRequestId(requestId);
        command.setExpectedRevision(revision);
        command.setAction(action);
        command.setPayload(payload);
        return command;
    }

    private String configJson() {
        return "{\"schemaVersion\":1,\"roles\":["
                + "{\"code\":\"DETECTIVE\",\"name\":\"侦探\",\"clues\":{\"CLOCK_TOWER\":\"侦探私密线索\"}},"
                + "{\"code\":\"SMUGGLER\",\"name\":\"走私者\",\"clues\":{\"CLOCK_TOWER\":\"走私者私密线索\"}}"
                + "],\"story\":{\"variables\":{\"trust\":0,\"evidence\":0},\"choices\":[],\"endings\":[]}}";
    }

    private String runtimeConfigJson() {
        return "{\"schemaVersion\":1,\"roles\":["
                + "{\"code\":\"DETECTIVE\",\"name\":\"侦探\",\"clues\":{\"CLOCK_TOWER\":\"侦探私密线索\"}},"
                + "{\"code\":\"SMUGGLER\",\"name\":\"走私者\",\"clues\":{\"CLOCK_TOWER\":\"走私者私密线索\"}}],"
                + "\"chapters\":[{\"chapterId\":1,\"title\":\"第一章\"}],"
                + "\"stations\":[{\"code\":\"CLOCK_TOWER\",\"nodeName\":\"旧钟楼\","
                + "\"preparationChecklist\":[{\"code\":\"STAFF\",\"label\":\"人员已到位\"}],"
                + "\"playerTask\":{\"taskCode\":\"CLOCK_PHOTO\",\"prompt\":\"拍下公开钟面\","
                + "\"inputType\":\"PHOTO\",\"verificationRequired\":false,"
                + "\"completionPolicy\":\"EVIDENCE_ONLY\","
                + "\"secretAnswer\":\"SECRET_ANSWER\",\"expectedToken\":\"EXPECTED_TOKEN\","
                + "\"merchantNote\":\"不下发\"},"
                + "\"merchantInstruction\":\"只核对玩家现场展示的钟面照片\","
                + "\"hiddenInfoReminder\":\"不要透露钟楼暗格答案\"}],"
                + "\"story\":{\"variables\":{\"trust\":0},\"choices\":[],\"endings\":[]}}";
    }

    private String configWithInputType(String inputType) {
        if ("PHOTO".equals(inputType)) return runtimeConfigJson();
        return runtimeConfigJson().replace(
                "\"inputType\":\"PHOTO\",\"verificationRequired\":false,\"completionPolicy\":\"EVIDENCE_ONLY\"",
                "\"inputType\":\"" + inputType + "\",\"verificationRequired\":true");
    }

    private String autoVerifiedTextConfig() {
        return configWithInputType("TEXT").replace("\"verificationRequired\":true",
                "\"verificationRequired\":false,\"expectedHash\":\""
                        + "ed2fed52d4df8c30c2c612155d40024a3b9003e811ce1d0fe4a0bd6458f66fd7\"");
    }

    private String photoEvidenceOnlyConfig() {
        return runtimeConfigJson();
    }

    private String fallbackPlanConfig() {
        return runtimeConfigJson().replace(
                "}],\"story\"",
                "},{\"code\":\"ARCHIVE\",\"nodeName\":\"旧档案馆\","
                        + "\"preparationChecklist\":[{\"code\":\"STAFF\",\"label\":\"人员已到位\"}],"
                        + "\"playerTask\":{\"taskCode\":\"ARCHIVE_TEXT\",\"prompt\":\"输入档案年份\","
                        + "\"inputType\":\"TEXT\",\"verificationRequired\":false,"
                        + "\"expectedHash\":\"d54123de468bd42ea00dafbd777f85fe5fa1ff6404d9838c007953c25c92a1c5\"},"
                        + "\"merchantInstruction\":\"确认现场公开档案\",\"hiddenInfoReminder\":\"不得透露答案\"}],"
                        + "\"fallbackPlans\":[{\"planCode\":\"CLOCK_TO_ARCHIVE\",\"version\":1,"
                        + "\"status\":\"APPROVED\",\"sourceStationCode\":\"CLOCK_TOWER\","
                        + "\"targetStationCode\":\"ARCHIVE\",\"playerMessage\":\"请前往旧档案馆完成替代任务\"}],"
                        + "\"story\""
        );
    }

    private String hintConfigJson() {
        return autoVerifiedTextConfig().replace(
                "\"expectedHash\":\"ed2fed52d4df8c30c2c612155d40024a3b9003e811ce1d0fe4a0bd6458f66fd7\"",
                "\"expectedHash\":\"d54123de468bd42ea00dafbd777f85fe5fa1ff6404d9838c007953c25c92a1c5\","
                        + "\"hintPolicy\":{\"levels\":["
                        + "{\"level\":1,\"text\":\"先观察钟面年份\",\"impactLabel\":\"会记录一级提示\"},"
                        + "{\"level\":2,\"text\":\"数字由四位组成\",\"impactLabel\":\"会记录二级提示\"}],"
                        + "\"reveal\":{\"text\":\"答案是1998\","
                        + "\"impactLabel\":\"将记为兜底完成，不计正常榜单分\"}}"
        );
    }

    private String choiceConfigJson() {
        return runtimeConfigJson().replace("\"choices\":[]", "\"choices\":["
                + "{\"id\":\"FOLLOW_NOTE\",\"label\":\"追查纸条\",\"stationCode\":\"CLOCK_TOWER\","
                + "\"allowedRoles\":[\"DETECTIVE\"],\"required\":true,\"resolveEnding\":true,"
                + "\"effects\":{\"trust\":1}},"
                + "{\"id\":\"ASK_CLERK\",\"label\":\"询问店员\",\"stationCode\":\"CLOCK_TOWER\","
                + "\"allowedRoles\":[\"DETECTIVE\"],\"required\":true,\"effects\":{\"trust\":1}}]")
                .replace("\"endings\":[]", "\"endings\":[{\"code\":\"FALLBACK\",\"title\":\"迷雾\","
                        + "\"text\":\"线索仍待拼合\",\"fallback\":true}]");
    }

    private String routeStyleStoryConfigJson() {
        return runtimeConfigJson().replace("\"choices\":[]", "\"choices\":["
                + "{\"id\":\"FOLLOW_NOTE\",\"label\":\"追查纸条\",\"stationCode\":\"CLOCK_TOWER\","
                + "\"allowedRoles\":[\"DETECTIVE\"],\"required\":true,"
                + "\"effects\":[{\"op\":\"INC\",\"var\":\"trust\",\"value\":2},"
                + "{\"op\":\"SET\",\"var\":\"mood\",\"value\":7},"
                + "{\"op\":\"ADD_TAG\",\"value\":\"tag.hurried\"}]}]")
                .replace("\"endings\":[]", "\"endings\":["
                        + "{\"code\":\"TRUSTED\",\"title\":\"信任\",\"text\":\"证据闭环\","
                        + "\"conditions\":[{\"var\":\"trust\",\"op\":\"GTE\",\"value\":2},"
                        + "{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"}]},"
                        + "{\"code\":\"OPEN_CASE\",\"title\":\"未完待续\",\"text\":\"关键线索待补\","
                        + "\"fallback\":true}]");
    }

    private String routeStyleStoryConfigJsonWithoutFallback() {
        return routeStyleStoryConfigJson().replace(
                ",{\"code\":\"OPEN_CASE\",\"title\":\"未完待续\",\"text\":\"关键线索待补\",\"fallback\":true}", "");
    }

    private String branchingChoiceConfigJson() {
        return runtimeConfigJson()
                .replace("\"chapters\":[{\"chapterId\":1,\"title\":\"第一章\"}]",
                        "\"chapters\":[{\"chapterId\":1,\"title\":\"搜集线索\"},"
                                + "{\"chapterId\":2,\"title\":\"共同推理\"}]")
                .replace("\"variables\":{\"trust\":0}", "\"variables\":{\"evidence\":0}")
                .replace("\"choices\":[]", "\"choices\":["
                        + "{\"id\":\"VERIFY\",\"label\":\"核验证据\",\"stationCode\":\"CLOCK_TOWER\","
                        + "\"allowedRoles\":[\"DETECTIVE\"],\"chapterId\":2,"
                        + "\"choiceGroup\":\"COUNTER_DECISION\",\"required\":true,"
                        + "\"effects\":{\"evidence\":1}},"
                        + "{\"id\":\"SKIP_VERIFY\",\"label\":\"保留疑点\",\"stationCode\":\"CLOCK_TOWER\","
                        + "\"allowedRoles\":[\"DETECTIVE\"],\"chapterId\":2,"
                        + "\"choiceGroup\":\"COUNTER_DECISION\",\"required\":true,"
                        + "\"effects\":{}}]")
                .replace("\"endings\":[]", "\"endings\":["
                        + "{\"code\":\"TEAM_TRUTH\",\"title\":\"共同真相\",\"text\":\"证据闭环\","
                        + "\"conditions\":{\"evidence\":{\"gte\":1}}},"
                        + "{\"code\":\"OPEN_CASE\",\"title\":\"未完待续\",\"text\":\"关键线索待补\","
                        + "\"fallback\":true}]");
    }

    private MerchantAccessContext merchantOperator(String roleCode) {
        return new MerchantAccessContext(OPERATOR, 11L, MERCHANT, roleCode);
    }

    private void stubReceiptPersistence(String requestId) {
        when(runtimeMapper.selectReceipt(ACTIVITY, MERCHANT, requestId)).thenReturn(null);
        when(runtimeMapper.selectReceipt(ACTIVITY, PLAYER, requestId)).thenReturn(null);
        when(runtimeMapper.insertReceiptClaim(any(GameCommandReceipt.class))).thenAnswer(invocation -> {
            GameCommandReceipt receipt = invocation.getArgument(0);
            receipt.setId(9902L);
            return 1;
        });
        when(runtimeMapper.completeReceipt(any(GameCommandReceipt.class))).thenReturn(1);
    }

    private void stubPlayerSubmission(String config, String requestId) {
        GameSession session = runningSession(3);
        session.setModuleConfigSnapshotJson(config);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(playTeamMapper.hasPaidRegistration(PLAYER, 2, ACTIVITY)).thenReturn(1);
        when(runtimeMapper.selectActiveTeamId(ACTIVITY, PLAYER)).thenReturn(TEAM);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(MERCHANT, "ACTIVE"));
        stubArrived();
        stubReceiptPersistence(requestId);
    }

    private void stubArrived() {
        stubArrivedAt(NODE);
    }

    private void stubArrivedAt(long... nodeIds) {
        doAnswer(invocation -> {
            PlayerNodeProgress query = invocation.getArgument(0);
            if (query == null) return Collections.emptyList();
            for (long nodeId : nodeIds) {
                if (!Long.valueOf(nodeId).equals(query.getNodeId())) continue;
                PlayerNodeProgress progress = new PlayerNodeProgress();
                progress.setMemberId(PLAYER);
                progress.setActivityId(ACTIVITY);
                progress.setTopicId(TOPIC);
                progress.setNodeId(nodeId);
                progress.setStatus(0);
                progress.setCheckinTime(new Date());
                return Collections.singletonList(progress);
            }
            return Collections.emptyList();
        }).when(playerNodeProgressService).selectPlayerNodeProgressList(any());
    }

    private GameSessionCommand playerSubmitCommand(String requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskCode", "CLOCK_PHOTO");
        payload.put("evidenceUrls", Collections.singletonList("https://example.invalid/evidence.jpg"));
        return command(requestId, 3, "PLAYER_SUBMIT", payload);
    }

    private Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
    @Test
    void ordinaryRoutePreparesFromRealNodesAndStartsWithoutStoryRoles() {
        CmsTopic topic = configuredTopic();
        topic.setProductType(com.chengyinhub.common.constant.ProductType.ORIENTEERING);
        topic.setGameModuleConfigJson(null);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        assertTrue(service.isGameSessionEnabled(topic));
        topic.setGameModuleConfigJson("{broken");
        assertFalse(service.isGameSessionEnabled(topic));
        topic.setGameModuleConfigJson(null);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, PLAYER)).thenReturn(true);
        when(runtimeMapper.insertSession(any())).thenAnswer(call -> {
            GameSession inserted = call.getArgument(0);
            inserted.setId(SESSION);
            return 1;
        });
        GameStationRun checkpoint = station(MERCHANT, GameStationRun.INVITED);
        checkpoint.setMerchantMemberId(null);
        checkpoint.setSourceType("HOST_NODE");
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Collections.singletonList(checkpoint));
        stubReceiptPersistence("route-prepare");
        service.command(GameActorContext.club(PLAYER), command("route-prepare", 0, "PREPARE", Collections.emptyMap()));
        org.mockito.ArgumentCaptor<GameSession> inserted = org.mockito.ArgumentCaptor.forClass(GameSession.class);
        verify(runtimeMapper).insertSession(inserted.capture());
        GameSession session = inserted.getValue();
        assertTrue(session.getModuleConfigSnapshotJson().contains("ROUTE"));
        assertFalse(session.getModuleConfigSnapshotJson().contains("roles"));
        verify(runtimeMapper).readyRouteCheckpoints(eq(SESSION), any(), any(), any());
        checkpoint.setStatus(GameStationRun.READY);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.countActiveTeams(ACTIVITY)).thenReturn(1);
        when(runtimeMapper.countTeamMembersWithoutRole(SESSION, ACTIVITY)).thenReturn(2);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any())).thenReturn(1);
        when(runtimeMapper.activateReadyStations(eq(SESSION), any())).thenReturn(1);
        stubReceiptPersistence("route-start");
        GameSessionView view = service.view(GameActorContext.club(PLAYER), ACTIVITY);
        assertTrue(view.getAvailableActions().contains("START"));
        assertFalse(view.getAvailableActions().contains("ASSIGN_ROLES"));
        assertTrue(view.getAvailableActions().contains("SET_LEADERBOARD_VISIBILITY"));
        service.command(GameActorContext.club(PLAYER), command("route-start", 0, "START", Collections.emptyMap()));
        assertEquals(GameSession.RUNNING, session.getStatus());
        verify(runtimeMapper, never()).countTeamMembersWithoutRole(SESSION, ACTIVITY);
    }

    @Test
    void ordinaryRoutePauseAndFinishBlockExistingTaskEntryButNotSelfPlay() {
        GameSession session = runningSession(1);
        session.setModuleConfigSnapshotJson("{\"schemaVersion\":1,\"playMode\":\"ROUTE\"}");
        when(runtimeMapper.selectSessionByActivityForUpdate(ACTIVITY)).thenReturn(session);
        GameStationRun station = station(MERCHANT, GameStationRun.PAUSED);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station);
        assertThrows(IllegalStateException.class, () -> service.assertRoutePlayable(ACTIVITY, NODE));
        station.setStatus(GameStationRun.ACTIVE);
        service.assertRoutePlayable(ACTIVITY, NODE);
        session.setStatus(GameSession.FINISHED);
        assertThrows(IllegalStateException.class, () -> service.assertRoutePlayable(ACTIVITY, NODE));
        service.assertRoutePlayable(0L, NODE);
    }

    @Test
    void ordinaryRouteRequiresPreviousMerchantReceptionFromTheSameSessionScope() {
        GameSession session = runningSession(1);
        session.setModuleConfigSnapshotJson("{\"schemaVersion\":1,\"playMode\":\"ROUTE\"}");
        when(runtimeMapper.selectSessionByActivityForUpdate(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.countMissingRouteReception(SESSION, NODE)).thenReturn(1);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.assertRouteReception(ACTIVITY, NODE));
        assertTrue(error.getMessage().contains("商家"));
        assertTrue(error.getMessage().contains("团码"));
        when(runtimeMapper.countMissingRouteReception(SESSION, NODE)).thenReturn(0);
        service.assertRouteReception(ACTIVITY, NODE);
    }

    @Test
    void groupReceptionGuardDoesNotApplyToSelfPlayOrStoryRuntime() {
        service.assertRouteReception(0L, NODE);
        GameSession session = runningSession(1);
        when(runtimeMapper.selectSessionByActivityForUpdate(ACTIVITY)).thenReturn(session);
        service.assertRouteReception(ACTIVITY, NODE);
        session.setModuleConfigSnapshotJson("{\"schemaVersion\":1,\"playMode\":\"ROUTE\"}");
        session.setClubId(null);
        service.assertRouteReception(ACTIVITY, NODE);
        verify(runtimeMapper, never()).countMissingRouteReception(anyLong(), anyLong());
    }

    @Test
    void routeDirectorPausesAndResumesPublicAndMerchantCheckpointsWithTheirPersistedOwner() {
        for (Long owner : new Long[]{null, MERCHANT}) {
            GameSession session = runningSession(4);
            session.setModuleConfigSnapshotJson("{\"schemaVersion\":1,\"playMode\":\"ROUTE\"}");
            GameStationRun checkpoint = station(MERCHANT, GameStationRun.ACTIVE);
            checkpoint.setMerchantMemberId(owner);
            checkpoint.setSourceType(owner == null ? "HOST_NODE" : "CHAPTER_NODE");
            when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
            when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
            when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(checkpoint);
            when(runtimeMapper.updateStationStatusCas(eq(checkpoint), eq(owner), eq(GameStationRun.ACTIVE),
                    eq(GameStationRun.PAUSED), eq(0), any())).thenReturn(1);
            when(runtimeMapper.updateStationStatusCas(eq(checkpoint), eq(owner), eq(GameStationRun.PAUSED),
                    eq(GameStationRun.ACTIVE), eq(1), any())).thenReturn(1);
            when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                    .thenReturn(1);
            String suffix = owner == null ? "public" : "merchant";
            stubReceiptPersistence("route-pause-" + suffix);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reasonCode", "ONSITE");
            payload.put("resumeEta", "2026-08-23 16:00:00");
            service.command(GameActorContext.club(DIRECTOR), command("route-pause-" + suffix, 4,
                    "CLUB_STATION_PAUSE", payload));
            assertEquals(GameStationRun.PAUSED, checkpoint.getStatus());
            assertEquals(1, checkpoint.getRevision());
            stubReceiptPersistence("route-resume-" + suffix);
            service.command(GameActorContext.club(DIRECTOR), command("route-resume-" + suffix, 5,
                    "CLUB_STATION_RESUME", Collections.emptyMap()));
            assertEquals(GameStationRun.ACTIVE, checkpoint.getStatus());
            assertEquals(2, checkpoint.getRevision());
            assertNull(checkpoint.getPauseReasonCode());
            assertNull(checkpoint.getResumeEta());
        }
    }

    @Test
    void merchantCannotPauseUnownedPublicCheckpoint() {
        GameSession session = runningSession(4);
        GameStationRun checkpoint = station(MERCHANT, GameStationRun.ACTIVE);
        checkpoint.setMerchantMemberId(null);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(checkpoint);
        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.merchant(MERCHANT), command("public-merchant-denied", 4,
                        "STATION_PAUSE", Collections.emptyMap())));
        assertEquals("GAME_FORBIDDEN_SCOPE", error.getErrorCode());
        verify(runtimeMapper, never()).updateStationStatusCas(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ordinaryRouteRecapUsesV1CompletionFieldsWithoutInventingStoryRoles() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson("{\"schemaVersion\":1,\"playMode\":\"ROUTE\"}");
        Map<String, Object> aggregate = clubRecapAggregate();
        for (String field : Arrays.asList("taskSubmitters", "fallbackCompleters", "level1Uses", "level2Uses",
                "answerReveals", "merchantFallbackCompletions", "playerRejectedSubmissions", "takeoverCount")) {
            aggregate.put(field, 0);
        }
        Map<String, Object> station = clubStationRecap();
        station.put("submissionCount", 0);
        station.put("fallbackCompletedCount", 0);
        station.put("rejectedCount", 0);
        Map<String, Object> collaboration = new LinkedHashMap<>();
        collaboration.put("teamId", TEAM);
        collaboration.put("assignedRoleCount", 0);
        collaboration.put("contributedRoleCount", 0);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        when(runtimeMapper.selectClubRecapAggregate(SESSION, ACTIVITY)).thenReturn(aggregate);
        when(runtimeMapper.selectClubStationRecaps(SESSION)).thenReturn(Collections.singletonList(station));
        when(runtimeMapper.selectClubRoleCollaboration(SESSION, ACTIVITY))
                .thenReturn(Collections.singletonList(collaboration));
        Map<?, ?> recap = (Map<?, ?>) service.exportRecap(GameActorContext.club(DIRECTOR), ACTIVITY).get("recap");
        assertEquals("GAME_RECAP_V1", recap.get("schemaVersion"));
        assertEquals(recap, service.view(GameActorContext.club(DIRECTOR), ACTIVITY).getClub().get("recap"));
        Map<?, ?> funnel = (Map<?, ?>) recap.get("funnel");
        assertEquals(2, funnel.get("normalCompleters"));
        assertEquals(1, funnel.get("finishedTeams"));
        assertEquals(0, ((Map<?, ?>) recap.get("collaboration")).get("eligibleTeams"));
        assertEquals(0, ((Map<?, ?>) recap.get("collaboration")).get("completedTeams"));
        List<?> metrics = (List<?>) recap.get("metrics");
        assertEquals("持票玩家", ((Map<?, ?>) metrics.get(0)).get("label"));
        assertEquals("有完成进度", ((Map<?, ?>) metrics.get(2)).get("label"));
    }

    @Test
    void routeFinishPreservesRecapForReopenAndExportAfterMembersLeave() throws Exception {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson("{\"schemaVersion\":1,\"playMode\":\"ROUTE\"}");
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        when(runtimeMapper.selectClubRecapAggregate(SESSION, ACTIVITY)).thenReturn(clubRecapAggregate());
        when(runtimeMapper.selectClubStationRecaps(SESSION)).thenReturn(Collections.singletonList(clubStationRecap()));
        when(runtimeMapper.selectClubRoleCollaboration(SESSION, ACTIVITY)).thenReturn(Collections.emptyList());
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        stubReceiptPersistence("route-finish-snapshot");

        com.chengyinhub.business.domain.dto.GameCommandReceiptView finished = service.command(GameActorContext.club(DIRECTOR),
                command("route-finish-snapshot", 4, "FINISH", Collections.emptyMap()));
        Map<?, ?> saved = (Map<?, ?>) finished.getResult().get("recap");
        org.junit.jupiter.api.Assertions.assertNotNull(saved);
        org.mockito.ArgumentCaptor<GameCommandReceipt> receipt =
                org.mockito.ArgumentCaptor.forClass(GameCommandReceipt.class);
        verify(runtimeMapper).completeReceipt(receipt.capture());
        assertTrue(receipt.getValue().getResultJson().contains("GAME_RECAP_V1"));
        when(runtimeMapper.selectFinishedRecapJson(SESSION)).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(saved));
        org.mockito.Mockito.clearInvocations(runtimeMapper);

        assertEquals(saved, service.exportRecap(GameActorContext.club(DIRECTOR), ACTIVITY).get("recap"));
        assertEquals(saved, service.view(GameActorContext.club(DIRECTOR), ACTIVITY).getClub().get("recap"));
        verify(runtimeMapper, never()).selectClubRecapAggregate(anyLong(), anyLong());
        verify(runtimeMapper, never()).selectClubStationRecaps(anyLong());
    }

    @Test
    void routeFinishWithoutRecapKeepsSessionAndStationsRunning() {
        GameSession session = runningSession(4);
        session.setModuleConfigSnapshotJson("{\"schemaVersion\":1,\"playMode\":\"ROUTE\"}");
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        stubReceiptPersistence("route-finish-no-recap");
        GameSessionException denied = assertThrows(GameSessionException.class,
                () -> service.command(GameActorContext.club(DIRECTOR),
                        command("route-finish-no-recap", 4, "FINISH", Collections.emptyMap())));
        assertEquals("GAME_RECAP_UNAVAILABLE", denied.getErrorCode());
        assertEquals(GameSession.RUNNING, session.getStatus());
        verify(runtimeMapper, never()).closeStations(anyLong(), any());
        verify(runtimeMapper, never()).updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void routeMerchantViewCarriesServerModeAndAcceptsRealServiceWindowWithoutStoryChecklist() {
        GameSession session = runningSession(4);
        session.setStatus(GameSession.PREPARING);
        session.setModuleConfigSnapshotJson("{\"schemaVersion\":1,\"playMode\":\"ROUTE\"}");
        GameStationRun station = station(MERCHANT, GameStationRun.ACCEPTED);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station);
        when(runtimeMapper.selectMerchantStations(SESSION, MERCHANT)).thenReturn(Collections.singletonList(station));
        Map<String, Object> merchant = service.view(GameActorContext.merchant(MERCHANT), ACTIVITY).getMerchant();
        assertEquals("ROUTE", merchant.get("playMode"));
        Map<?, ?> projectedStation = (Map<?, ?>) ((List<?>) merchant.get("stations")).get(0);
        assertEquals(Collections.emptyList(), projectedStation.get("preparationChecklist"));
        assertNull(projectedStation.get("playerTask"));
        when(runtimeMapper.updateStationStatusCas(eq(station), eq(MERCHANT), eq(GameStationRun.ACCEPTED),
                eq(GameStationRun.READY), eq(0), any())).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        when(runtimeMapper.countActiveTeams(ACTIVITY)).thenReturn(1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("checklist", Collections.emptyList());
        payload.put("capacity", 12);
        payload.put("serviceStartAt", "2026-08-23 10:00:00");
        payload.put("serviceEndAt", "2026-08-23 18:00:00");
        stubReceiptPersistence("route-merchant-ready");
        service.command(GameActorContext.merchant(MERCHANT), command("route-merchant-ready", 4,
                "STATION_READY", payload));
        assertEquals(GameStationRun.READY, station.getStatus());
        assertEquals(Integer.valueOf(12), station.getCapacity());
        assertEquals(DateUtils.parseDate("2026-08-23 10:00:00"), station.getServiceStartAt());
        assertEquals(DateUtils.parseDate("2026-08-23 18:00:00"), station.getServiceEndAt());
        verify(runtimeMapper).updateStationStatusCas(eq(station), eq(MERCHANT), eq(GameStationRun.ACCEPTED),
                eq(GameStationRun.READY), eq(0), any());
    }

    @Test
    void routeSessionStatusIsAbsentForLegacyAndAuthoritativeForPreparedRoutes() {
        assertNull(service.routeSessionStatus(0L));
        assertNull(service.routeSessionStatus(ACTIVITY));
        GameSession session = runningSession(1);
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        assertNull(service.routeSessionStatus(ACTIVITY));
        session.setModuleConfigSnapshotJson("{\"schemaVersion\":1,\"playMode\":\"ROUTE\"}");
        assertEquals("RUNNING", service.routeSessionStatus(ACTIVITY));
        session.setLeaderboardVisible(false);
        assertTrue(service.isRouteLeaderboardHidden(ACTIVITY));
        session.setLeaderboardVisible(true);
        assertFalse(service.isRouteLeaderboardHidden(ACTIVITY));
        session.setStatus(GameSession.FINISHED);
        assertEquals("FINISHED", service.routeSessionStatus(ACTIVITY));
    }

    @Test
    void routeDirectorLeaderboardUsesExistingActivityRankingWithinOwnerProjection() {
        GameSession session = runningSession(1);
        session.setModuleConfigSnapshotJson("{\"schemaVersion\":1,\"playMode\":\"ROUTE\"}");
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        com.chengyinhub.business.domain.vo.LeaderboardItemVO row =
                new com.chengyinhub.business.domain.vo.LeaderboardItemVO();
        row.setMemberId(PLAYER); row.setScore(2L);
        when(playerNodeProgressService.selectActivityLeaderboard(ACTIVITY, null, 50))
                .thenReturn(Collections.singletonList(row));
        GameSessionView view = service.view(GameActorContext.club(DIRECTOR), ACTIVITY);
        assertEquals(Collections.singletonList(row), view.getClub().get("leaderboardRows"));
        assertTrue(view.getAvailableActions().contains("SET_LEADERBOARD_VISIBILITY"));
        assertThrows(GameSessionException.class, () -> service.view(GameActorContext.club(TARGET_PLAYER), ACTIVITY));
    }

}
