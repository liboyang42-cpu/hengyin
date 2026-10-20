package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.GameCommandReceipt;
import com.chengyinhub.business.domain.GamePlayerNodeOutcome;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SC-SCAN-LEGAL [P0] 验收:商家 VERIFY_SUBMISSION 只完成本站。
 *
 * <ul>
 *   <li>APPROVE 只为本站写一条 COMPLETED 结果;指向本站的暂停源站若没有获批兜底绑定
 *       (同商家 / 计划版本未获批),不得被顺带完成;</li>
 *   <li>正控:获批绑定的暂停源站才会得到 FALLBACK_COMPLETED;</li>
 *   <li>提交所属站点 (stationRunId) 与商家站点 id 不一致 ⇒ 拒绝,且不做 CAS、不写结果、不推进会话。</li>
 * </ul>
 *
 * <p>夹具抄自 {@link GameSessionRuntimeServiceImplTest}(同一套 mock 装配与配置 JSON)。
 */
class GameSessionVerifyStationGateAcceptanceTest {

    private static final long ACTIVITY = 7001L;
    private static final long TOPIC = 8001L;
    private static final long CLUB = 9001L;
    private static final long SESSION = 6001L;
    private static final long SOURCE_NODE = 5001L;
    private static final long TARGET_NODE = 5002L;
    private static final long SOURCE_MERCHANT = 3001L;
    private static final long TARGET_MERCHANT = 3002L;
    private static final long TARGET_STATION_ID = 5102L;
    private static final long PLAYER = 4001L;
    private static final long TEAM = 4501L;

    private GameSessionRuntimeServiceImpl service;
    private GameSessionRuntimeMapper runtimeMapper;
    private IPlayerNodeProgressService playerNodeProgressService;
    private final List<GamePlayerNodeOutcome> inserted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new GameSessionRuntimeServiceImpl();
        runtimeMapper = mock(GameSessionRuntimeMapper.class);
        when(runtimeMapper.selectSessionByActivityForUpdate(anyLong()))
                .thenAnswer(call -> runtimeMapper.selectSessionByActivity(call.getArgument(0)));
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        playerNodeProgressService = mock(IPlayerNodeProgressService.class);
        ReflectionTestUtils.setField(service, "capabilityService",
                mock(com.chengyinhub.business.service.ICapabilityService.class));
        ReflectionTestUtils.setField(service, "runtimeMapper", runtimeMapper);
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "topicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "clubMemberService", mock(IClubMemberService.class));
        ReflectionTestUtils.setField(service, "playTeamMapper", mock(PlayTeamMapper.class));
        ReflectionTestUtils.setField(service, "playerNodeProgressService", playerNodeProgressService);
        ReflectionTestUtils.setField(service, "imService", mock(IImService.class));
        ReflectionTestUtils.setField(service, "clock",
                (com.chengyinhub.business.service.support.AdvancedGameClock)
                        () -> DateUtils.parseDate("2026-08-23 12:00:00"));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activeActivity());
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);

        GameSession session = new GameSession();
        session.setId(SESSION);
        session.setActivityId(ACTIVITY);
        session.setTopicId(TOPIC);
        session.setClubId(CLUB);
        session.setStatus("RUNNING");
        session.setRevision(4);
        session.setModuleConfigSnapshotJson(fallbackPlanConfig());
        when(runtimeMapper.selectSessionByActivity(ACTIVITY)).thenReturn(session);

        when(runtimeMapper.insertReceiptClaim(any(GameCommandReceipt.class))).thenAnswer(invocation -> {
            GameCommandReceipt receipt = invocation.getArgument(0);
            receipt.setId(9902L);
            return 1;
        });
        when(runtimeMapper.completeReceipt(any(GameCommandReceipt.class))).thenReturn(1);
        when(runtimeMapper.updateSessionCas(anyLong(), anyInt(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        inserted.clear();
        when(runtimeMapper.insertPlayerNodeOutcome(any())).thenAnswer(invocation -> {
            GamePlayerNodeOutcome row = invocation.getArgument(0);
            row.setId(700L + inserted.size());
            inserted.add(row);
            return 1;
        });
    }

    @Test
    void approvalCompletesOnlyThisStationWhenPausedSourcesLackApprovedBinding() {
        GameStationRun target = targetStation();
        // 同商家源站:绑定计划字段齐全且指向本站,但兜底不得在同一商家内部互转
        GameStationRun sameMerchantSource = pausedSource(5111L, SOURCE_NODE + 10, TARGET_MERCHANT, 1);
        // 他商家源站:指向本站,但计划版本 2 未获批
        GameStationRun unapprovedSource = pausedSource(5112L, SOURCE_NODE + 11, SOURCE_MERCHANT, 2);
        when(runtimeMapper.selectStation(SESSION, TARGET_NODE)).thenReturn(target);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(
                Arrays.asList(sameMerchantSource, unapprovedSource, target));
        GameTaskSubmission submission = pendingSubmission(81L, TARGET_STATION_ID);
        when(runtimeMapper.selectSubmission(81L)).thenReturn(submission);
        when(runtimeMapper.decideSubmissionCas(submission)).thenReturn(1);

        GameCommandReceiptView receipt = service.command(GameActorContext.merchant(TARGET_MERCHANT),
                approve("approve-only-this-station", 81L));

        assertEquals("APPROVED", receipt.getResult().get("decision"));
        assertEquals(1, inserted.size(), "只能为本站写一条结果,未获批绑定的源站不得被顺带完成");
        GamePlayerNodeOutcome only = inserted.get(0);
        assertEquals(TARGET_STATION_ID, only.getStationRunId());
        assertEquals(TARGET_NODE, only.getNodeId());
        assertEquals(PLAYER, only.getMemberId());
        assertEquals(TEAM, only.getTeamId());
        assertEquals(GamePlayerNodeOutcome.COMPLETED, only.getStatus());
        assertEquals("MERCHANT_APPROVAL", only.getCompletionSource());
        assertEquals(81L, only.getSourceSubmissionId());
        assertEquals(9902L, only.getSourceReceiptId());
        assertNull(only.getFallbackPlanCode());
        assertEquals(TARGET_MERCHANT, submission.getDecidedBy());
        // 本路径不写玩家打卡进度(player_node_progress)
        verifyNoInteractions(playerNodeProgressService);
    }

    @Test
    void approvedBindingIsTheOnlyWayASourceStationIsCompletedAlongside() {
        GameStationRun target = targetStation();
        GameStationRun approvedSource = pausedSource(5101L, SOURCE_NODE, SOURCE_MERCHANT, 1);
        GameStationRun unapprovedSource = pausedSource(5112L, SOURCE_NODE + 11, SOURCE_MERCHANT, 2);
        when(runtimeMapper.selectStation(SESSION, TARGET_NODE)).thenReturn(target);
        when(runtimeMapper.selectStations(SESSION)).thenReturn(
                Arrays.asList(approvedSource, unapprovedSource, target));
        GameTaskSubmission submission = pendingSubmission(82L, TARGET_STATION_ID);
        when(runtimeMapper.selectSubmission(82L)).thenReturn(submission);
        when(runtimeMapper.decideSubmissionCas(submission)).thenReturn(1);

        service.command(GameActorContext.merchant(TARGET_MERCHANT), approve("approve-with-binding", 82L));

        assertEquals(2, inserted.size());
        assertEquals(TARGET_STATION_ID, inserted.get(0).getStationRunId());
        assertEquals(GamePlayerNodeOutcome.COMPLETED, inserted.get(0).getStatus());
        assertEquals(5101L, inserted.get(1).getStationRunId());
        assertEquals(SOURCE_NODE, inserted.get(1).getNodeId());
        assertEquals(GamePlayerNodeOutcome.FALLBACK_COMPLETED, inserted.get(1).getStatus());
        assertEquals("FALLBACK_PLAN", inserted.get(1).getCompletionSource());
        assertEquals("CLOCK_TO_ARCHIVE", inserted.get(1).getFallbackPlanCode());
        assertNull(inserted.get(1).getSourceSubmissionId());
    }

    @Test
    void submissionBelongingToAnotherStationRunIsRefusedWithoutCas() {
        when(runtimeMapper.selectStation(SESSION, TARGET_NODE)).thenReturn(targetStation());
        when(runtimeMapper.selectStations(SESSION)).thenReturn(Arrays.asList(targetStation()));
        // 同节点,但提交挂在另一个站点运行实例上(例如上一局/被替换的站点)
        GameTaskSubmission submission = pendingSubmission(83L, 9999L);
        when(runtimeMapper.selectSubmission(83L)).thenReturn(submission);
        when(runtimeMapper.decideSubmissionCas(any())).thenReturn(1);

        GameSessionException error = assertThrows(GameSessionException.class, () -> service.command(
                GameActorContext.merchant(TARGET_MERCHANT), approve("approve-foreign-run", 83L)));

        assertEquals("GAME_FORBIDDEN_SCOPE", error.getErrorCode());
        assertEquals("PENDING", submission.getStatus(), "被拒时提交状态不得被改写");
        verify(runtimeMapper, never()).decideSubmissionCas(any());
        verify(runtimeMapper, never()).insertPlayerNodeOutcome(any());
        verify(runtimeMapper, never()).updateSessionCas(anyLong(), anyInt(), anyString(), anyString(),
                any(), any(), any());
        verify(runtimeMapper, never()).completeReceipt(any());
    }

    // ------------------------------------------------------------------ 夹具

    private GameSessionCommand approve(String requestId, long submissionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submissionId", submissionId);
        payload.put("decision", "APPROVE");
        GameSessionCommand command = new GameSessionCommand();
        command.setActivityId(ACTIVITY);
        command.setNodeId(TARGET_NODE);
        command.setRequestId(requestId);
        command.setExpectedRevision(4);
        command.setAction("VERIFY_SUBMISSION");
        command.setPayload(payload);
        return command;
    }

    private GameTaskSubmission pendingSubmission(long id, long stationRunId) {
        GameTaskSubmission submission = new GameTaskSubmission();
        submission.setId(id);
        submission.setGameSessionId(SESSION);
        submission.setActivityId(ACTIVITY);
        submission.setStationRunId(stationRunId);
        submission.setNodeId(TARGET_NODE);
        submission.setTeamId(TEAM);
        submission.setMemberId(PLAYER);
        submission.setStatus("PENDING");
        submission.setRevision(0);
        return submission;
    }

    private GameStationRun targetStation() {
        GameStationRun station = baseStation(TARGET_STATION_ID, TARGET_NODE, TARGET_MERCHANT, GameStationRun.ACTIVE);
        station.setStationCode("ARCHIVE");
        station.setNodeName("旧档案馆");
        return station;
    }

    private GameStationRun pausedSource(long id, long nodeId, long merchant, int planVersion) {
        GameStationRun station = baseStation(id, nodeId, merchant, GameStationRun.PAUSED);
        station.setStationCode("CLOCK_TOWER");
        station.setNodeName("旧钟楼");
        station.setFallbackNodeId(TARGET_NODE);
        station.setFallbackPlanCode("CLOCK_TO_ARCHIVE");
        station.setFallbackPlanVersion(planVersion);
        return station;
    }

    private GameStationRun baseStation(long id, long nodeId, long merchant, String status) {
        GameStationRun station = new GameStationRun();
        station.setId(id);
        station.setGameSessionId(SESSION);
        station.setActivityId(ACTIVITY);
        station.setNodeId(nodeId);
        station.setMerchantMemberId(merchant);
        station.setStatus(status);
        station.setRevision(0);
        station.setServiceStartAt(DateUtils.parseDate("2026-08-23 10:00:00"));
        station.setServiceEndAt(DateUtils.parseDate("2026-08-23 18:00:00"));
        return station;
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

    private String fallbackPlanConfig() {
        return "{\"schemaVersion\":1,\"roles\":["
                + "{\"code\":\"DETECTIVE\",\"name\":\"侦探\",\"clues\":{\"CLOCK_TOWER\":\"侦探私密线索\"}},"
                + "{\"code\":\"SMUGGLER\",\"name\":\"走私者\",\"clues\":{\"CLOCK_TOWER\":\"走私者私密线索\"}}],"
                + "\"chapters\":[{\"chapterId\":1,\"title\":\"第一章\"}],"
                + "\"stations\":[{\"code\":\"CLOCK_TOWER\",\"nodeName\":\"旧钟楼\","
                + "\"preparationChecklist\":[{\"code\":\"STAFF\",\"label\":\"人员已到位\"}],"
                + "\"playerTask\":{\"taskCode\":\"CLOCK_PHOTO\",\"prompt\":\"拍下公开钟面\","
                + "\"inputType\":\"PHOTO\",\"verificationRequired\":false,"
                + "\"completionPolicy\":\"EVIDENCE_ONLY\"},"
                + "\"merchantInstruction\":\"只核对玩家现场展示的钟面照片\","
                + "\"hiddenInfoReminder\":\"不要透露钟楼暗格答案\"},"
                + "{\"code\":\"ARCHIVE\",\"nodeName\":\"旧档案馆\","
                + "\"preparationChecklist\":[{\"code\":\"STAFF\",\"label\":\"人员已到位\"}],"
                + "\"playerTask\":{\"taskCode\":\"ARCHIVE_TEXT\",\"prompt\":\"输入档案年份\","
                + "\"inputType\":\"TEXT\",\"verificationRequired\":true},"
                + "\"merchantInstruction\":\"确认现场公开档案\",\"hiddenInfoReminder\":\"不得透露答案\"}],"
                + "\"fallbackPlans\":[{\"planCode\":\"CLOCK_TO_ARCHIVE\",\"version\":1,"
                + "\"status\":\"APPROVED\",\"sourceStationCode\":\"CLOCK_TOWER\","
                + "\"targetStationCode\":\"ARCHIVE\",\"playerMessage\":\"请前往旧档案馆完成替代任务\"}],"
                + "\"story\":{\"variables\":{\"trust\":0},\"choices\":[],\"endings\":[]}}";
    }
}
