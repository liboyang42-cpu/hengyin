package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OrientScoreAppeal;
import com.chengyinhub.business.domain.OrientScoreEntry;
import com.chengyinhub.business.domain.OrientScoreSettlement;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import com.chengyinhub.business.mapper.OrientScoringMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.support.OrientScoringException;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.service.ISysConfigService;
import com.chengyinhub.web.controller.api.ApiOrientScoringController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F1b 城市定向计分规则:默认关闭、旧主题不变、本人归属、局终投影恰好一次且 = 流水合计、
 * 冲正只追加、幂等键指纹冲突、争议窗口、排行榜只附加不重排。
 */
class OrientScoringServiceImplTest {
    private OrientScoringServiceImpl service;
    private OrientScoringMapper mapper;
    private ISysConfigService config;
    private ICmsTopicService topics;
    private IGrowthService growth;
    private UmsMemberMapper members;
    private UmsMemberPointsDetailMapper pointsDetails;
    private final Map<String, String> cfg = new HashMap<>();
    private final List<OrientScoreEntry> ledger = new ArrayList<>();
    private OrientScoreSettlement settled;
    private TopicRouteSession session;

    @BeforeEach
    void setUp() {
        service = new OrientScoringServiceImpl();
        mapper = mock(OrientScoringMapper.class);
        config = mock(ISysConfigService.class);
        topics = mock(ICmsTopicService.class);
        growth = mock(IGrowthService.class);
        members = mock(UmsMemberMapper.class);
        pointsDetails = mock(UmsMemberPointsDetailMapper.class);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "configService", config);
        ReflectionTestUtils.setField(service, "cmsTopicService", topics);
        ReflectionTestUtils.setField(service, "growthService", growth);
        ReflectionTestUtils.setField(service, "umsMemberMapper", members);
        ReflectionTestUtils.setField(service, "pointsDetailMapper", pointsDetails);
        when(config.selectConfigByKey(anyString())).thenAnswer(inv -> cfg.get(inv.getArgument(0, String.class)));
        cfg.put("orient.scoring.enabled", "true");

        CmsTopic topic = new CmsTopic();
        topic.setId(300L);
        topic.setProductType(1);
        when(topics.selectCmsTopicById(300L)).thenReturn(topic);

        session = new TopicRouteSession();
        session.setId(10L);
        session.setActivityId(500L);
        session.setTopicId(300L);
        session.setMemberId(7L);
        session.setPlayInstanceId("default");
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        session.setCreateTime(new Date(System.currentTimeMillis() - 3600_000L));
        session.setCompletedAt(new Date(System.currentTimeMillis() - 60_000L));
        when(mapper.selectSessionForUpdate(10L)).thenReturn(session);
        when(mapper.selectSessionById(10L)).thenReturn(session);

        // 内存流水:insert 分配自增 id,按幂等键/冲正关系回读
        when(mapper.insertEntry(any())).thenAnswer(inv -> {
            OrientScoreEntry e = inv.getArgument(0);
            e.setId((long) ledger.size() + 1);
            ledger.add(e);
            return 1;
        });
        when(mapper.selectEntriesBySession(10L)).thenAnswer(inv -> new ArrayList<>(ledger));
        when(mapper.selectEntryByIdem(eq(10L), anyString())).thenAnswer(inv -> ledger.stream()
                .filter(e -> e.getIdempotencyKey().equals(inv.getArgument(1))).findFirst().orElse(null));
        when(mapper.selectEntryById(anyLong())).thenAnswer(inv -> ledger.stream()
                .filter(e -> e.getId().equals(inv.getArgument(0))).findFirst().orElse(null));
        when(mapper.selectEntryByReverses(anyLong())).thenAnswer(inv -> ledger.stream()
                .filter(e -> inv.getArgument(0).equals(e.getReversesEntryId())).findFirst().orElse(null));
        when(mapper.selectSettlementBySession(10L)).thenAnswer(inv -> settled);
        when(mapper.insertSettlement(any())).thenAnswer(inv -> {
            settled = inv.getArgument(0);
            settled.setId(1L);
            return 1;
        });

        PlayerNodeProgress cleared = new PlayerNodeProgress();
        cleared.setNodeId(20L);
        cleared.setPuzzleScore(80);
        PlayerNodeProgress plain = new PlayerNodeProgress();
        plain.setNodeId(21L);
        when(mapper.selectClearedProgress(7L, 500L, 300L, "default")).thenReturn(List.of(cleared, plain));
    }

    @Test
    void unavailableConfigurationCannotMasqueradeAsDisabledScoring() {
        when(config.selectConfigByKey("orient.scoring.enabled")).thenThrow(new IllegalStateException("configuration unavailable"));
        assertThrows(IllegalStateException.class, () -> service.isActiveFor(topics.selectCmsTopicById(300L)));
        org.mockito.Mockito.verifyNoInteractions(mapper, growth, members, pointsDetails);
    }

    @Test
    void pendingReviewBlocksPreflightWhileRunIsStillActiveWithoutLedgerWrites() {
        session.setStatus(TopicRouteSession.STATUS_RUNNING);
        when(mapper.countPendingProgress(7L, 500L, 300L, "default")).thenReturn(1);
        assertEquals(OrientScoringException.REVIEW_PENDING,
                assertThrows(OrientScoringException.class, () -> service.assertCompletionAllowed(10L)).getErrorCode());
        assertTrue(ledger.isEmpty()); assertNull(settled);
        org.mockito.Mockito.verifyNoInteractions(growth, members, pointsDetails);
    }

    @Test
    void switchOffByDefaultMakesEveryEntryPointInert() {
        cfg.remove("orient.scoring.enabled");
        assertEquals(OrientScoringException.DISABLED,
                assertThrows(OrientScoringException.class, () -> service.settle(7L, 10L, "s-1")).getErrorCode());
        assertEquals(OrientScoringException.DISABLED,
                assertThrows(OrientScoringException.class, () -> service.breakdown(7L, 10L)).getErrorCode());
        LeaderboardItemVO row = new LeaderboardItemVO();
        row.setMemberId(7L);
        service.decorateLeaderboard(topics.selectCmsTopicById(300L), 500L, 300L, List.of(row), row);
        assertNull(row.getScoreCenti(), "开关关闭时排行榜不附加任何字段");
        verify(mapper, never()).selectBoardTotals(anyLong(), anyLong(), any());
    }

    @Test
    void nonOrienteeringTopicIsUnchanged() {
        topics.selectCmsTopicById(300L).setProductType(2);
        assertEquals(OrientScoringException.NOT_APPLICABLE,
                assertThrows(OrientScoringException.class, () -> service.settle(7L, 10L, "s-1")).getErrorCode());
        topics.selectCmsTopicById(300L).setProductType(null);
        LeaderboardItemVO row = new LeaderboardItemVO();
        row.setMemberId(7L);
        service.decorateLeaderboard(topics.selectCmsTopicById(300L), 500L, 300L, List.of(row), null);
        assertNull(row.getScoreCenti());
    }

    @Test
    void otherMembersSessionLooksLikeNotFound() {
        assertEquals(OrientScoringException.SESSION_NOT_FOUND,
                assertThrows(OrientScoringException.class, () -> service.settle(8L, 10L, "s-1")).getErrorCode());
        assertEquals(OrientScoringException.SESSION_NOT_FOUND,
                assertThrows(OrientScoringException.class, () -> service.breakdown(8L, 10L)).getErrorCode());
        assertEquals(OrientScoringException.SESSION_NOT_FOUND, assertThrows(OrientScoringException.class,
                () -> service.submitDispute(8L, 10L, "不服", "d-1")).getErrorCode());
    }

    @Test
    void cannotSettleRunningSession() {
        session.setStatus(TopicRouteSession.STATUS_RUNNING);
        assertEquals(OrientScoringException.SESSION_NOT_ENDED,
                assertThrows(OrientScoringException.class, () -> service.settle(7L, 10L, "s-1")).getErrorCode());
        verify(mapper, never()).insertSettlement(any());
    }

    @Test
    void settlementProjectsLedgerExactlyOnceAndIsRecomputable() {
        cfg.put("orient.scoring.points_rate_pct", "10");
        cfg.put("orient.scoring.xp_rate_pct", "100");
        when(members.lockMemberById(7L)).thenReturn(7L);
        when(members.selectPointsForUpdate(7L)).thenReturn(5L);
        when(members.addPoints(7L, 28)).thenReturn(1);
        when(pointsDetails.insertOrientSettlementPointsDetail(any())).thenReturn(1);

        Map<String, Object> first = service.settle(7L, 10L, "s-1");
        // 两个任务各 100 分 + 谜题 80 分 = 280 分 = 28000 centi
        assertEquals(28000L, first.get("totalCenti"));
        assertEquals(3, first.get("entryCount"));
        assertEquals(28, first.get("pointsAwarded"), "280 分 × 10% = 28 积分");
        assertEquals(280, first.get("xpAwarded"), "280 分 × 100% = 280 XP");
        assertEquals(false, first.get("replayed"));

        Map<String, Object> again = service.settle(7L, 10L, "s-2");
        assertEquals(true, again.get("replayed"), "换键重试也只读回首份回执");
        assertEquals(first.get("settlementId"), again.get("settlementId"));
        verify(members, times(1)).addPoints(7L, 28);
        verify(growth, times(1)).addExp(eq(7L), eq(280), anyString());
        ArgumentCaptor<UmsMemberPointsDetail> detail = ArgumentCaptor.forClass(UmsMemberPointsDetail.class);
        verify(pointsDetails).insertOrientSettlementPointsDetail(detail.capture());
        assertEquals("orient-settlement:10", detail.getValue().getRewardBusinessKey());
        assertEquals(Long.valueOf(21L), detail.getValue().getEventType());

        Map<String, Object> check = service.verify(10L);
        assertEquals(true, check.get("consistent"), "SUM(流水 ≤ 水位) == 投影");
        assertEquals(28000L, check.get("ledgerAtWatermarkCenti"));
    }

    @Test
    void defaultRatesProjectWithoutAwardingAnything() {
        Map<String, Object> receipt = service.settle(7L, 10L, "s-1");
        assertEquals(0, receipt.get("pointsAwarded"));
        assertEquals(0, receipt.get("xpAwarded"));
        verify(members, never()).addPoints(anyLong(), anyInt());
        verify(growth, never()).addExp(anyLong(), anyInt(), anyString());
    }

    @Test
    void settledSessionIsFrozenForAccrualAndCorrectionsAreReverseEntries() {
        service.settle(7L, 10L, "s-1");
        int before = ledger.size();
        assertEquals(0, service.syncSession(7L, 10L).get("added"), "已结算的局不再入账");
        assertEquals(before, ledger.size());

        OrientScoreEntry task = ledger.get(0);
        OrientScoreEntry reversal = service.reverseEntry(task.getId(), "WRONG_NODE", 1L, "rev-1");
        assertEquals(-task.getDeltaCenti(), reversal.getDeltaCenti());
        assertEquals(task.getId(), reversal.getReversesEntryId());
        assertEquals(10000, task.getDeltaCenti(), "原条目不改写");
        assertEquals(reversal.getId(), service.reverseEntry(task.getId(), "WRONG_NODE", 1L, "rev-1").getId(),
                "同键同内容回放");
        assertEquals(OrientScoringException.ALREADY_REVERSED, assertThrows(OrientScoringException.class,
                () -> service.reverseEntry(task.getId(), "WRONG_NODE", 1L, "rev-2")).getErrorCode());

        Map<String, Object> check = service.verify(10L);
        assertEquals(true, check.get("consistent"), "水位后的冲正不破坏投影复算");
        assertEquals(-10000L, check.get("postSettlementDeltaCenti"));
    }

    @Test
    void sameKeyDifferentPayloadIsConflict() {
        service.appendEntry(10L, OrientScoreEntry.DIM_PENALTY, -500, "GPS_JUMP", "ANTI_CHEAT", "f1c:1", null, "ac-1");
        assertEquals(1, ledger.size());
        service.appendEntry(10L, OrientScoreEntry.DIM_PENALTY, -500, "GPS_JUMP", "ANTI_CHEAT", "f1c:1", null, "ac-1");
        assertEquals(1, ledger.size(), "同键同指纹不重复入账");
        assertEquals(OrientScoringException.IDEMPOTENCY_CONFLICT, assertThrows(OrientScoringException.class,
                () -> service.appendEntry(10L, OrientScoreEntry.DIM_PENALTY, -900, "GPS_JUMP", "ANTI_CHEAT", "f1c:1",
                        null, "ac-1")).getErrorCode());
        assertEquals(OrientScoringException.INVALID_ARGUMENT, assertThrows(OrientScoringException.class,
                () -> service.appendEntry(10L, "MONEY", 1, "X", "ANTI_CHEAT", null, null, "ac-2")).getErrorCode());
    }

    @Test
    void disputeOnlyWithinWindowAfterEnd() {
        cfg.put("orient.scoring.dispute_window_hours", "1");
        when(mapper.insertDispute(any())).thenReturn(1);
        OrientScoreAppeal ok = service.submitDispute(7L, 10L, "终点打卡未记录", "d-1");
        assertTrue(ok.getWindowDeadline().after(new Date()));

        session.setCompletedAt(new Date(System.currentTimeMillis() - 2 * 3600_000L));
        assertEquals(OrientScoringException.WINDOW_CLOSED, assertThrows(OrientScoringException.class,
                () -> service.submitDispute(7L, 10L, "终点打卡未记录", "d-2")).getErrorCode());

        session.setStatus(TopicRouteSession.STATUS_RUNNING);
        assertEquals(OrientScoringException.SESSION_NOT_ENDED, assertThrows(OrientScoringException.class,
                () -> service.submitDispute(7L, 10L, "x", "d-3")).getErrorCode());
    }

    @Test
    void disputeReplayReadsBackReceiptAndConflictsOnDifferentPayload() {
        OrientScoreAppeal stored = new OrientScoreAppeal();
        stored.setId(5L);
        stored.setStatus(OrientScoreAppeal.STATUS_PENDING);
        stored.setRequestFingerprint(OrientScoringServiceImpl.fingerprint("DISPUTE", 10L, "原因"));
        when(mapper.selectDisputeByIdem(7L, "d-1")).thenReturn(stored);
        assertEquals(5L, service.submitDispute(7L, 10L, "原因", "d-1").getId());
        assertEquals(OrientScoringException.IDEMPOTENCY_CONFLICT, assertThrows(OrientScoringException.class,
                () -> service.submitDispute(7L, 10L, "另一个原因", "d-1")).getErrorCode());
        assertEquals("PENDING", service.receipt(7L, "DISPUTE", 10L, "d-1").get("state"));
        assertEquals("NOT_FOUND", service.receipt(7L, "APPEAL", 10L, "unknown-key").get("state"));
        assertEquals("NOT_FOUND", service.receipt(7L, "SETTLE", 10L, "s-1").get("state"));
    }

    @Test
    void acceptedAppealAppendsAdjustmentAndCannotBeResolvedTwice() {
        OrientScoreAppeal pending = new OrientScoreAppeal();
        pending.setId(3L);
        pending.setSessionId(10L);
        pending.setMemberId(7L);
        pending.setNodeId(22L);
        pending.setStatus(OrientScoreAppeal.STATUS_PENDING);
        when(mapper.selectAppealById(3L)).thenReturn(pending);
        when(mapper.resolveAppeal(any())).thenReturn(1, 0);

        service.resolveAppeal(3L, true, 10000, "补记", "admin", 1L);
        OrientScoreEntry adjustment = ledger.get(ledger.size() - 1);
        assertEquals(OrientScoreEntry.DIM_ADJUSTMENT, adjustment.getDimension());
        assertEquals(10000, adjustment.getDeltaCenti());
        assertEquals("appeal:3", adjustment.getIdempotencyKey());

        assertEquals(OrientScoringException.ALREADY_RESOLVED, assertThrows(OrientScoringException.class,
                () -> service.resolveAppeal(3L, false, null, "x", "admin", 1L)).getErrorCode());
        assertEquals(OrientScoringException.INVALID_ARGUMENT, assertThrows(OrientScoringException.class,
                () -> service.resolveAppeal(3L, true, null, "漏记却不给分值", "admin", 1L)).getErrorCode(),
                "漏记申诉没有可冲正的条目,采纳必须给修正分值");
    }

    @Test
    void leaderboardOnlyGetsScoreAttachedNeverReordered() {
        LeaderboardItemVO a = new LeaderboardItemVO();
        a.setMemberId(7L);
        a.setRank(1);
        LeaderboardItemVO b = new LeaderboardItemVO();
        b.setMemberId(8L);
        b.setRank(2);
        Map<String, Object> row = new HashMap<>();
        row.put("memberid", 8L);
        row.put("totalcenti", 99999L);
        when(mapper.selectBoardTotals(500L, 300L, List.of(7L, 8L))).thenReturn(List.of(row));
        List<LeaderboardItemVO> list = new ArrayList<>(List.of(a, b));
        service.decorateLeaderboard(topics.selectCmsTopicById(300L), 500L, 300L, list, a);
        assertEquals(List.of(7L, 8L), List.of(list.get(0).getMemberId(), list.get(1).getMemberId()), "顺序不变");
        assertEquals(Integer.valueOf(2), b.getRank(), "名次不变");
        assertEquals(Long.valueOf(99999L), b.getScoreCenti());
        assertNull(a.getScoreCenti(), "没开过局的人不伪造 0 分");
    }

    @Test
    void receiptReadsTheOriginalSettlementAndDoesNotWrite() {
        ApiOrientScoringController controller = spy(new ApiOrientScoringController());
        ReflectionTestUtils.setField(controller, "orientScoringService", service);
        doReturn(7L).when(controller).getAppUserId();
        AjaxResult absent = controller.receipt("SETTLE", "10", "orient-key-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> absentBody = (Map<String, Object>) absent.get(AjaxResult.DATA_TAG);
        assertEquals("NOT_FOUND", absentBody.get("state"));
        verify(mapper, never()).insertSettlement(any());

        Map<String, Object> missing = service.receipt(7L, "SETTLE", 10L, "orient-key-1");
        assertEquals("NOT_FOUND", missing.get("state"));
        assertEquals("SETTLE", missing.get("kind"));
        assertEquals("orient-key-1", missing.get("idempotencyKey"));
        verify(mapper, never()).insertSettlement(any());
        verify(pointsDetails, never()).insertOrientSettlementPointsDetail(any());

        OrientScoreSettlement row = new OrientScoreSettlement();
        row.setId(44L);
        row.setSessionId(10L);
        row.setTotalCenti(8800L);
        row.setIdempotencyKey("orient-key-1");
        settled = row;
        Map<String, Object> found = service.receipt(7L, "SETTLE", 10L, "orient-key-2");
        assertEquals("SETTLED", found.get("state"));
        assertEquals(Boolean.TRUE, found.get("replayed"));
        assertEquals(10L, found.get("sessionId"));
        assertEquals(44L, found.get("settlementId"));
        assertEquals(8800L, found.get("totalCenti"));
        assertEquals("orient-key-1", found.get("idempotencyKey"));
        verify(mapper, never()).insertSettlement(any());

        TopicRouteSession other = new TopicRouteSession();
        other.setId(11L);
        other.setMemberId(8L);
        when(mapper.selectSessionById(11L)).thenReturn(other);
        OrientScoringException denied = assertThrows(OrientScoringException.class,
                () -> service.receipt(7L, "SETTLE", 11L, "orient-key-1"));
        assertEquals(OrientScoringException.SESSION_NOT_FOUND, denied.getErrorCode());
        verify(mapper, never()).selectSettlementBySession(11L);

        AjaxResult read = controller.receipt("SETTLE", "10", "orient-key-2");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) read.get(AjaxResult.DATA_TAG);
        assertEquals(200, ((Number) read.get(AjaxResult.CODE_TAG)).intValue());
        assertEquals("SETTLED", body.get("state"));
        assertEquals(Boolean.TRUE, body.get("replayed"));
        assertEquals(10L, body.get("sessionId"));
        assertEquals(44L, body.get("settlementId"));
        assertEquals(8800L, body.get("totalCenti"));
        AjaxResult otherMember = controller.receipt("SETTLE", "11", "orient-key-1");
        assertEquals(OrientScoringException.SESSION_NOT_FOUND, otherMember.get("errorCode"));
        verify(mapper, never()).insertSettlement(any());
        verify(pointsDetails, never()).insertOrientSettlementPointsDetail(any());
        verify(mapper, never()).selectSettlementBySession(11L);
    }

    @Test
    void projectionRoundsDownAndNeverPenalisesRewards() {
        assertEquals(0, OrientScoringServiceImpl.project(-5000, 100));
        assertEquals(0, OrientScoringServiceImpl.project(99, 100));
        assertEquals(1, OrientScoringServiceImpl.project(199, 100));
        assertEquals(0, OrientScoringServiceImpl.project(100000, 0));
        assertEquals(Integer.MAX_VALUE, OrientScoringServiceImpl.project(Long.MAX_VALUE, 100_000));
        assertEquals(Integer.MAX_VALUE, OrientScoringServiceImpl.project(Long.MAX_VALUE, 1));
    }
    @Test
    void keyedSyncReplaysOriginalSnapshotAndReadbackDoesNotAccrueLaterTasks() {
        Map<String, com.chengyinhub.business.domain.OrientScoreSyncReceipt> receipts = new HashMap<>();
        when(mapper.selectSyncReceipt(eq(10L), anyString())).thenAnswer(i -> receipts.get(i.getArgument(1)));
        when(mapper.selectSyncReceiptForUpdate(eq(10L), anyString())).thenAnswer(i -> receipts.get(i.getArgument(1)));
        when(mapper.insertSyncReceipt(any())).thenAnswer(i -> {
            com.chengyinhub.business.domain.OrientScoreSyncReceipt receipt = i.getArgument(0);
            receipts.put(receipt.getIdempotencyKey(), receipt); return 1;
        });
        Map<String, Object> first = service.syncSession(7L, 10L, "sync-1");
        assertEquals("SYNCED", first.get("state"));
        assertEquals(3, first.get("added"));
        when(mapper.selectClearedProgress(7L, 500L, 300L, "default")).thenThrow(new AssertionError("replay must not accrue"));
        assertEquals(first, service.syncSession(7L, 10L, "sync-1"));
        assertEquals(first, service.receipt(7L, "SYNC", 10L, "sync-1"));
        assertEquals("NOT_FOUND", service.receipt(7L, "SYNC", 10L, "unknown").get("state"));
        assertThrows(OrientScoringException.class, () -> service.receipt(8L, "SYNC", 10L, "sync-1"));
        assertThrows(OrientScoringException.class, () -> service.syncSession(8L, 10L, "sync-1"));
        assertEquals(3, ledger.size());
    }

    @Test
    void photoPendingBlocksSettlementBeforeLedgerOrRewards() {
        when(mapper.countPendingProgress(7L, 500L, 300L, "default")).thenReturn(1);
        assertEquals(OrientScoringException.REVIEW_PENDING,
                assertThrows(OrientScoringException.class, () -> service.settle(7L, 10L, "pending")).getErrorCode());
        assertTrue(ledger.isEmpty());
        verify(mapper, never()).insertSettlement(any());
        verify(pointsDetails, never()).insertOrientSettlementPointsDetail(any());
        verify(growth, never()).addExp(anyLong(), anyInt(), anyString());
    }

    @Test
    void puzzleCentiConversionRejectsOverflowInsteadOfWrapping() {
        assertEquals(0, OrientScoringServiceImpl.puzzleCenti(0));
        assertEquals(2147483600, OrientScoringServiceImpl.puzzleCenti(21474836));
        assertEquals(-100, OrientScoringServiceImpl.puzzleCenti(-1));
        assertThrows(OrientScoringException.class, () -> OrientScoringServiceImpl.puzzleCenti(21474837));
        assertThrows(OrientScoringException.class, () -> OrientScoringServiceImpl.puzzleCenti(Integer.MAX_VALUE));
        assertThrows(OrientScoringException.class, () -> OrientScoringServiceImpl.puzzleCenti(Integer.MIN_VALUE));
    }

    @Test
    void boardKeepsExistingPaidEntryAndHiddenBoardRestrictions() {
        var registrations = mock(com.chengyinhub.business.service.ICmsRegistrationService.class);
        var activities = mock(com.chengyinhub.business.service.ICmsActivityService.class);
        var game = mock(com.chengyinhub.business.service.IGameSessionRuntimeService.class);
        ReflectionTestUtils.setField(service, "scoringRegistrations", registrations);
        ReflectionTestUtils.setField(service, "scoringActivities", activities);
        ReflectionTestUtils.setField(service, "scoringGameSessions", game);
        var activity = new com.chengyinhub.business.domain.CmsActivity(); activity.setTopicId(300L);
        when(activities.selectCmsActivityById(500L)).thenReturn(activity);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(List.of());
        assertThrows(OrientScoringException.class, () -> service.leaderboard(7L, 10L));
        var registration = new com.chengyinhub.business.domain.CmsRegistration();
        when(registrations.selectCmsRegistrationList(any())).thenReturn(List.of(registration));
        when(game.isRouteLeaderboardHidden(500L)).thenReturn(true);
        assertThrows(OrientScoringException.class, () -> service.leaderboard(7L, 10L));
        session.setActivityId(0L); registration.setExpiresAt(new Date(1L));
        assertThrows(OrientScoringException.class, () -> service.leaderboard(7L, 10L));
        verify(mapper, never()).selectRankedBoard(anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean(), anyInt());
        registration.setExpiresAt(null);
        when(mapper.selectRankedBoard(0L, 300L, 7L, false, 50)).thenReturn(List.of());
        assertEquals("SCORE", service.leaderboard(7L, 10L).get("mode"));
        ArgumentCaptor<com.chengyinhub.business.domain.CmsRegistration> probe = ArgumentCaptor.forClass(com.chengyinhub.business.domain.CmsRegistration.class);
        verify(registrations, times(4)).selectCmsRegistrationList(probe.capture());
        assertEquals(7L, probe.getValue().getMemberId()); assertEquals(300L, probe.getValue().getOwnerId());
        assertEquals(1L, probe.getValue().getOwnerType()); assertEquals(2, probe.getValue().getRegistrationStatus());
    }

}
