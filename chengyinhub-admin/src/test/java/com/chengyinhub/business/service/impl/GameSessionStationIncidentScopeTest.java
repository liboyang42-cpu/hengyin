package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.GameBroadcast;
import com.chengyinhub.business.domain.GameBroadcastDelivery;
import com.chengyinhub.business.domain.GameCommandReceipt;
import com.chengyinhub.business.domain.GameSession;
import com.chengyinhub.business.domain.GameStationRun;
import com.chengyinhub.business.domain.GameTaskSubmission;
import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.domain.dto.GameCommandReceiptView;
import com.chengyinhub.business.domain.dto.GameSessionCommand;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 城市活动导演台 / 商家站点（SC-CITY-PAUSE、SC-CITY-RESUME-RACE、SC-CITY-INCIDENT、SC-CITY-BROADCAST-TARGET）
 * 在服务层能证明的部分：只动选中的站点；后到的恢复不产生第二次迁移；CAS 落空给出明确冲突；
 * 俱乐部处置事故只能驳回本场本站的任务；定向广播只投给本局内的目标队伍。
 */
class GameSessionStationIncidentScopeTest {
    private static final long ACTIVITY = 7001L;
    private static final long TOPIC = 8001L;
    private static final long CLUB = 9001L;
    private static final long SESSION = 6001L;
    private static final long NODE = 5001L;
    private static final long OTHER_NODE = 5003L;
    private static final long MERCHANT = 3001L;
    private static final long DIRECTOR = 4101L;
    private static final long TEAM = 4501L;
    private static final long FOREIGN_TEAM = 4599L;

    private GameSessionRuntimeServiceImpl service;
    private GameSessionRuntimeMapper runtimeMapper;
    private IClubMemberService clubMemberService;
    private IImService imService;

    @BeforeEach
    void setUp() {
        service = new GameSessionRuntimeServiceImpl();
        runtimeMapper = mock(GameSessionRuntimeMapper.class);
        when(runtimeMapper.selectSessionByActivityForUpdate(anyLong()))
                .thenAnswer(call -> runtimeMapper.selectSessionByActivity(call.getArgument(0)));
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        clubMemberService = mock(IClubMemberService.class);
        imService = mock(IImService.class);
        ReflectionTestUtils.setField(service, "runtimeMapper", runtimeMapper);
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "topicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "clubMemberService", clubMemberService);
        com.chengyinhub.business.service.ICapabilityService capabilityService =
                mock(com.chengyinhub.business.service.ICapabilityService.class);
        when(capabilityService.hasCapability(DIRECTOR,
                com.chengyinhub.business.domain.MemberCapability.CAP_CLUB_LEADER)).thenReturn(true);
        ReflectionTestUtils.setField(service, "capabilityService", capabilityService);
        ReflectionTestUtils.setField(service, "playTeamMapper", mock(PlayTeamMapper.class));
        ReflectionTestUtils.setField(service, "playerNodeProgressService", mock(IPlayerNodeProgressService.class));
        ReflectionTestUtils.setField(service, "imService", imService);
        ReflectionTestUtils.setField(service, "clock",
                (com.chengyinhub.business.service.support.AdvancedGameClock)
                        () -> DateUtils.parseDate("2026-08-23 12:00:00"));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activeActivity());
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setGameModuleConfigJson(configJson());
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(clubMemberService.isTopicOwner(TOPIC, CLUB, DIRECTOR)).thenReturn(true);
        when(runtimeMapper.insertReceiptClaim(any(GameCommandReceipt.class))).thenAnswer(call -> {
            call.getArgument(0, GameCommandReceipt.class).setId(9902L);
            return 1;
        });
        when(runtimeMapper.completeReceipt(any(GameCommandReceipt.class))).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
    }

    @Test
    void merchantPauseTransitionsOnlyTheChosenStationRow() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        GameStationRun chosen = station(5101L, NODE, GameStationRun.ACTIVE);
        GameStationRun other = station(5103L, OTHER_NODE, GameStationRun.ACTIVE);
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(chosen);
        when(runtimeMapper.selectStation(SESSION, OTHER_NODE)).thenReturn(other);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Arrays.asList(chosen, other));
        when(runtimeMapper.updateStationStatusCas(any(), anyLong(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", "EQUIPMENT");
        payload.put("resumeEta", "2026-08-23 16:00:00");

        service.command(GameActorContext.merchant(MERCHANT), command("pause-one", 4, NODE, "STATION_PAUSE", payload));

        assertEquals(GameStationRun.PAUSED, chosen.getStatus());
        assertEquals(GameStationRun.ACTIVE, other.getStatus());
        verify(runtimeMapper, times(1)).updateStationStatusCas(any(), anyLong(), anyString(), anyString(), anyInt(), any());
        verify(runtimeMapper).updateStationStatusCas(eq(chosen), eq(MERCHANT), eq(GameStationRun.ACTIVE),
                eq(GameStationRun.PAUSED), eq(0), any());
        verify(runtimeMapper, never()).updateStationStatusCas(eq(other), any(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void secondMerchantTerminalResumingAnAlreadyActiveStationMakesNoSecondTransition() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(5));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(5101L, NODE, GameStationRun.ACTIVE));

        // SC-CITY-RESUME-RACE:后到的恢复什么都没改,不能记成一张 APPLIED 回执
        GameSessionException noop = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.merchant(MERCHANT),
                command("resume-terminal-b", 4, NODE, "STATION_RESUME", Collections.emptyMap())));

        assertEquals("GAME_STATION_ALREADY_ACTIVE", noop.getErrorCode());
        verify(runtimeMapper, never()).completeReceipt(any());
        verify(runtimeMapper, never()).updateStationStatusCas(any(), any(), anyString(), anyString(), anyInt(), any());
        verify(runtimeMapper, never()).updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void secondMerchantTerminalPausingAnAlreadyPausedStationIsANoOpNotAnAppliedIncident() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(5));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(5101L, NODE, GameStationRun.PAUSED));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", "EQUIPMENT");
        payload.put("resumeEta", "2026-08-23 16:00:00");

        GameSessionException noop = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.merchant(MERCHANT), command("pause-terminal-b", 4, NODE, "STATION_PAUSE", payload)));

        // 复盘的 pauseEventCount / merchantPauseEvents 只数 outcome='APPLIED' 的暂停回执:
        // 这里不落 APPLIED(由控制器记 FAILED + 本原因码),一次迁移就只算一次事故
        assertEquals("GAME_STATION_ALREADY_PAUSED", noop.getErrorCode());
        verify(runtimeMapper, never()).completeReceipt(any());
        verify(runtimeMapper, never()).updateStationStatusCas(any(), any(), anyString(), anyString(), anyInt(), any());
        verify(runtimeMapper, never()).updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void secondDirectorTerminalPausingOrResumingWithNoChangeIsANoOpNotAnAppliedIncident() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(5));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(5101L, NODE, GameStationRun.PAUSED));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", "ONSITE");
        payload.put("resumeEta", "2026-08-23 16:00:00");

        GameSessionException pause = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(DIRECTOR), command("club-pause-b", 4, NODE, "CLUB_STATION_PAUSE", payload)));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(5101L, NODE, GameStationRun.ACTIVE));
        GameSessionException resume = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(DIRECTOR),
                command("club-resume-b", 4, NODE, "CLUB_STATION_RESUME", Collections.emptyMap())));

        assertEquals("GAME_STATION_ALREADY_PAUSED", pause.getErrorCode());
        assertEquals("GAME_STATION_ALREADY_ACTIVE", resume.getErrorCode());
        verify(runtimeMapper, never()).completeReceipt(any());
        verify(runtimeMapper, never()).updateStationStatusCas(any(), any(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void resumeWhoseStationCasLosesTheRaceFailsWithAClearConflictAndNoSessionBump() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(5101L, NODE, GameStationRun.PAUSED));
        when(runtimeMapper.updateStationStatusCas(any(), anyLong(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(0);

        GameSessionException conflict = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.merchant(MERCHANT),
                command("resume-stale", 4, NODE, "STATION_RESUME", Collections.emptyMap())));

        assertEquals("GAME_STATE_CONFLICT", conflict.getErrorCode());
        verify(runtimeMapper, never()).updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void directorIncidentHandlingCannotApproveASubmission() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(5101L, NODE, GameStationRun.ACTIVE));
        when(runtimeMapper.selectSubmission(77L)).thenReturn(pendingSubmission(77L, NODE, 5101L));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submissionId", 77L);
        payload.put("decision", "APPROVE");
        payload.put("reasonCode", "OTHER");

        GameSessionException denied = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(DIRECTOR), command("club-approve", 4, NODE, "CLUB_REJECT_SUBMISSION", payload)));

        assertEquals("GAME_FORBIDDEN_SCOPE", denied.getErrorCode());
        verify(runtimeMapper, never()).decideSubmissionCas(any());
    }

    @Test
    void directorIncidentHandlingCannotRejectASubmissionThatBelongsToAnotherStationRun() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(runtimeMapper.selectStation(SESSION, NODE)).thenReturn(station(5101L, NODE, GameStationRun.ACTIVE));
        when(runtimeMapper.selectSubmission(78L)).thenReturn(pendingSubmission(78L, NODE, 5199L));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submissionId", 78L);
        payload.put("reasonCode", "BLURRY");

        GameSessionException denied = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(DIRECTOR), command("club-reject-cross", 4, NODE, "CLUB_REJECT_SUBMISSION", payload)));

        assertEquals("GAME_FORBIDDEN_SCOPE", denied.getErrorCode());
        verify(runtimeMapper, never()).decideSubmissionCas(any());
    }

    @Test
    void teamBroadcastToATeamOutsideThisSessionIsRefusedBeforeAnyRowIsWritten() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(runtimeMapper.isActiveTeam(ACTIVITY, FOREIGN_TEAM)).thenReturn(0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetType", "TEAM");
        payload.put("targetId", FOREIGN_TEAM);
        payload.put("content", "集合提醒");

        GameSessionException denied = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.club(DIRECTOR), command("broadcast-foreign", 4, NODE, "BROADCAST", payload)));

        assertEquals("GAME_FORBIDDEN_SCOPE", denied.getErrorCode());
        verify(runtimeMapper, never()).insertBroadcast(any());
        verify(runtimeMapper, never()).insertBroadcastDeliveries(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any());
        verify(imService, never()).sendSystemInNestedTransaction(any(), any(), any(), any());
    }

    @Test
    void teamBroadcastScopesDeliveriesToTheTargetTeamAndSendsOncePerRecipientRow() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        when(runtimeMapper.isActiveTeam(ACTIVITY, TEAM)).thenReturn(1);
        when(runtimeMapper.insertBroadcast(any())).thenAnswer(call -> {
            call.getArgument(0, GameBroadcast.class).setId(66L);
            return 1;
        });
        when(runtimeMapper.insertBroadcastDeliveries(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any()))
                .thenReturn(2);
        when(runtimeMapper.selectPendingDeliveries(66L)).thenReturn(Arrays.asList(delivery(1L, 4001L), delivery(2L, 4002L)));
        when(imService.sendSystemInNestedTransaction(any(), any(), any(), any())).thenReturn(1L);
        when(runtimeMapper.markDelivery(anyLong(), anyString(), any(), any(), any())).thenReturn(1);
        when(runtimeMapper.updateBroadcastStatus(anyLong(), anyString(), anyString(), any())).thenReturn(1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetType", "TEAM");
        payload.put("targetId", TEAM);
        payload.put("content", "集合提醒");

        service.command(GameActorContext.club(DIRECTOR), command("broadcast-team", 4, NODE, "BROADCAST", payload));

        verify(runtimeMapper).insertBroadcastDeliveries(eq(66L), eq(SESSION), eq(ACTIVITY), eq("TEAM"),
                eq(TEAM), any(), any());
        verify(imService, times(1)).sendSystemInNestedTransaction(eq(4001L), any(), eq("集合提醒"), any());
        verify(imService, times(1)).sendSystemInNestedTransaction(eq(4002L), any(), eq("集合提醒"), any());
        verify(imService, times(2)).sendSystemInNestedTransaction(any(), any(), any(), any());
        verify(runtimeMapper).updateBroadcastStatus(66L, "SENDING", "SENT",
                DateUtils.parseDate("2026-08-23 12:00:00"));
    }

    @Test
    void sameRequestRetryOfAPartlyFailedBroadcastResendsOnlyTheFailedDeliveriesAndReportsTheNewState() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        stubTeamBroadcast();
        when(runtimeMapper.selectPendingDeliveries(66L))
                .thenReturn(Arrays.asList(delivery(1L, 4001L), delivery(2L, 4002L)))
                .thenReturn(Collections.singletonList(delivery(2L, 4002L)));
        when(imService.sendSystemInNestedTransaction(eq(4001L), any(), any(), any())).thenReturn(11L);
        when(imService.sendSystemInNestedTransaction(eq(4002L), any(), any(), any()))
                .thenThrow(new RuntimeException("im down")).thenReturn(12L);
        when(runtimeMapper.countBroadcastDeliveries(66L, "FAILED")).thenReturn(1, 1, 0);
        when(runtimeMapper.countBroadcastDeliveries(66L, "REACHED")).thenReturn(2);
        when(runtimeMapper.countBroadcastDeliveries(66L, "PENDING")).thenReturn(0);

        GameCommandReceiptView first = service.command(GameActorContext.club(DIRECTOR),
                command("broadcast-retry", 4, NODE, "BROADCAST", teamBroadcastPayload()));
        assertEquals(1, ((Number) first.getResult().get("reachedCount")).intValue());
        verify(runtimeMapper).updateBroadcastStatus(66L, "SENDING", "PARTIAL", DateUtils.parseDate("2026-08-23 12:00:00"));

        GameCommandReceipt stored = completedReceipt();
        when(runtimeMapper.selectReceipt(ACTIVITY, DIRECTOR, "broadcast-retry")).thenReturn(stored);
        when(runtimeMapper.selectBroadcast(66L)).thenReturn(storedBroadcast("PARTIAL"));
        when(runtimeMapper.resetFailedDeliveries(eq(66L), any())).thenReturn(1);
        when(runtimeMapper.updateAppliedReceiptResult(any())).thenReturn(1);

        GameCommandReceiptView retried = service.command(GameActorContext.club(DIRECTOR),
                command("broadcast-retry", 4, NODE, "BROADCAST", teamBroadcastPayload()));

        // 已送达的 4001 不再收到;只有失败的 4002 被补发一次
        verify(imService, times(1)).sendSystemInNestedTransaction(eq(4001L), any(), any(), any());
        verify(imService, times(2)).sendSystemInNestedTransaction(eq(4002L), any(), any(), any());
        verify(runtimeMapper).resetFailedDeliveries(eq(66L), any());
        verify(runtimeMapper).updateBroadcastStatus(66L, "PARTIAL", "SENT", DateUtils.parseDate("2026-08-23 12:00:00"));
        verify(runtimeMapper, times(1)).insertBroadcast(any());
        assertEquals("APPLIED", retried.getOutcome());
        assertEquals(66L, ((Number) retried.getResult().get("broadcastId")).longValue());
        assertEquals(2, ((Number) retried.getResult().get("reachedCount")).intValue());
        assertEquals(0, ((Number) retried.getResult().get("failedCount")).intValue());
        assertEquals("SENT", retried.getResult().get("broadcastStatus"));
        verify(runtimeMapper).updateAppliedReceiptResult(stored);
    }

    @Test
    void sameRequestRetryOfAFullyDeliveredBroadcastSendsNothingAgain() {
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(runningSession(4));
        stubTeamBroadcast();
        when(runtimeMapper.selectPendingDeliveries(66L))
                .thenReturn(Arrays.asList(delivery(1L, 4001L), delivery(2L, 4002L)));
        when(imService.sendSystemInNestedTransaction(any(), any(), any(), any())).thenReturn(11L);
        service.command(GameActorContext.club(DIRECTOR),
                command("broadcast-done", 4, NODE, "BROADCAST", teamBroadcastPayload()));
        GameCommandReceipt stored = completedReceipt();
        when(runtimeMapper.selectReceipt(ACTIVITY, DIRECTOR, "broadcast-done")).thenReturn(stored);
        when(runtimeMapper.selectBroadcast(66L)).thenReturn(storedBroadcast("SENT"));

        GameCommandReceiptView retried = service.command(GameActorContext.club(DIRECTOR),
                command("broadcast-done", 4, NODE, "BROADCAST", teamBroadcastPayload()));

        assertTrue(retried.isReplayed());
        verify(imService, times(2)).sendSystemInNestedTransaction(any(), any(), any(), any());
        verify(runtimeMapper, never()).resetFailedDeliveries(anyLong(), any());
        verify(runtimeMapper, never()).updateAppliedReceiptResult(any());
    }

    private void stubTeamBroadcast() {
        when(runtimeMapper.isActiveTeam(ACTIVITY, TEAM)).thenReturn(1);
        when(runtimeMapper.insertBroadcast(any())).thenAnswer(call -> {
            call.getArgument(0, GameBroadcast.class).setId(66L);
            return 1;
        });
        when(runtimeMapper.insertBroadcastDeliveries(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any()))
                .thenReturn(2);
        when(runtimeMapper.markDelivery(anyLong(), anyString(), any(), any(), any())).thenReturn(1);
        when(runtimeMapper.updateBroadcastStatus(anyLong(), anyString(), anyString(), any())).thenReturn(1);
    }

    private Map<String, Object> teamBroadcastPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetType", "TEAM");
        payload.put("targetId", TEAM);
        payload.put("content", "集合提醒");
        return payload;
    }

    private GameCommandReceipt completedReceipt() {
        org.mockito.ArgumentCaptor<GameCommandReceipt> captor = org.mockito.ArgumentCaptor.forClass(GameCommandReceipt.class);
        verify(runtimeMapper).completeReceipt(captor.capture());
        return captor.getValue();
    }

    private GameBroadcast storedBroadcast(String status) {
        GameBroadcast broadcast = new GameBroadcast();
        broadcast.setId(66L);
        broadcast.setGameSessionId(SESSION);
        broadcast.setActivityId(ACTIVITY);
        broadcast.setSenderMemberId(DIRECTOR);
        broadcast.setTargetType("TEAM");
        broadcast.setTargetId(TEAM);
        broadcast.setContent("集合提醒");
        broadcast.setStatus(status);
        return broadcast;
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

    private GameStationRun station(long id, long nodeId, String status) {
        GameStationRun station = new GameStationRun();
        station.setId(id);
        station.setGameSessionId(SESSION);
        station.setActivityId(ACTIVITY);
        station.setNodeId(nodeId);
        station.setNodeName("站点" + nodeId);
        station.setStationCode("CLOCK_TOWER");
        station.setMerchantMemberId(MERCHANT);
        station.setStatus(status);
        station.setRevision(0);
        station.setServiceStartAt(DateUtils.parseDate("2026-08-23 10:00:00"));
        station.setServiceEndAt(DateUtils.parseDate("2026-08-23 18:00:00"));
        return station;
    }

    private GameTaskSubmission pendingSubmission(long id, long nodeId, long stationRunId) {
        GameTaskSubmission submission = new GameTaskSubmission();
        submission.setId(id);
        submission.setGameSessionId(SESSION);
        submission.setStationRunId(stationRunId);
        submission.setNodeId(nodeId);
        submission.setStatus("PENDING");
        submission.setRevision(0);
        return submission;
    }

    private GameBroadcastDelivery delivery(long id, long memberId) {
        GameBroadcastDelivery delivery = new GameBroadcastDelivery();
        delivery.setId(id);
        delivery.setBroadcastId(66L);
        delivery.setMemberId(memberId);
        delivery.setStatus("PENDING");
        return delivery;
    }

    private GameSessionCommand command(String requestId, int revision, long nodeId, String action,
                                       Map<String, Object> payload) {
        GameSessionCommand command = new GameSessionCommand();
        command.setActivityId(ACTIVITY);
        command.setNodeId(nodeId);
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
}
