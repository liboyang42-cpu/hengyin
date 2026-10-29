package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.PlayPredictResult;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayPredictMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R9-02:已揭晓/已作废的竞猜轮次不能再收新注。
 *
 * <p>商家结算是一次性扫描 —— 它把当时在场的注结完就结束了。结算之后再进来的人
 * 写下的那一注没人再扫:{@code reward_status} 永远停在 0,商家待办里也看不到
 * (待办只列未结算轮次),既没人发奖也没人收尾。要么拒收,要么得有一条重扫路径。
 *
 * <p>拒收的判据必须是**锁住轮次行之后的当前读**({@code selectResultForUpdate}),
 * 不是请求开头那次快照读 —— 结算可能落在两者之间({@link #staleSnapshotCannotSlipBetPastSettle})。
 * 真并发方向由 {@code PredictLateBetRaceMySqlIT} 在真库上验证。
 */
class PredictBetAfterSettleGuardTest {

    /** 2026-04-01 07:33:20 +08 —— 在 closeAtHour=21 之前,截止闸不该是拒绝理由。 */
    private static final long NOW = 1_775_000_000_000L;
    private static final long MEMBER_ID = 7L;
    private static final long NODE_ID = 29L;
    private static final long SESSION_ID = 51L;

    private GameRuntimeSessionMapper sessionMapper;
    private PlayPredictMapper predictMapper;
    private PlayAttemptLogMapper attemptMapper;
    private AdvancedGameRuntimeServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        predictMapper = mock(PlayPredictMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", mock(CmsTopicNodeMapper.class));
        ReflectionTestUtils.setField(service, "templateMapper", mock(CmsMemberTemplateMapper.class));
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsActivity activity = new CmsActivity();
        activity.setId(9L);
        activity.setTopicId(19L);
        activity.setStartDate(new Date(NOW - 3_600_000L));
        activity.setEndDate(new Date(NOW + 3_600_000L));
        when(activityMapper.selectCmsActivityById(9L)).thenReturn(activity);
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", mock(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "teamMapper", mock(PlayTeamMapper.class));
        ReflectionTestUtils.setField(service, "attemptMapper", attemptMapper);
        ReflectionTestUtils.setField(service, "predictMapper", predictMapper);
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(NOW));
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
    }

    @Test
    void settledRoundRejectsLateBet() {
        when(sessionMapper.selectAccessibleById(SESSION_ID, MEMBER_ID)).thenReturn(runningSession());
        when(attemptMapper.selectByIdempotencyKey("late-bet")).thenReturn(null);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(predictMapper.selectResult(eq(NODE_ID), any(java.sql.Date.class))).thenReturn(round(PlayPredictResult.SETTLE_DONE));
        when(predictMapper.selectResultForUpdate(eq(NODE_ID), any(java.sql.Date.class)))
                .thenReturn(round(PlayPredictResult.SETTLE_DONE));

        assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, SESSION_ID, 0, "late-bet", "SUBMIT_PREDICT", payload("b")),
                "商家已经结算的轮次还能押进去 —— 这一注的 reward_status 永远没人收尾");

        verify(predictMapper, never()).insertBet(any());
        verify(sessionMapper, never()).updateStateCas(any());
    }

    @Test
    void voidedRoundRejectsLateBetToo() {
        when(sessionMapper.selectAccessibleById(SESSION_ID, MEMBER_ID)).thenReturn(runningSession());
        when(attemptMapper.selectByIdempotencyKey("late-bet-void")).thenReturn(null);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(predictMapper.selectResult(eq(NODE_ID), any(java.sql.Date.class))).thenReturn(round(PlayPredictResult.SETTLE_VOID));
        when(predictMapper.selectResultForUpdate(eq(NODE_ID), any(java.sql.Date.class)))
                .thenReturn(round(PlayPredictResult.SETTLE_VOID));

        assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, SESSION_ID, 0, "late-bet-void", "SUBMIT_PREDICT", payload("b")));

        verify(predictMapper, never()).insertBet(any());
    }

    /**
     * 竞态在单测里的形状:请求开头的快照读还是「未揭晓」,锁定读拿到的却是已揭晓 ——
     * 结算正落在两者之间。只信快照就会把这一注写进一个已经结束的轮次。
     */
    @Test
    void staleSnapshotCannotSlipBetPastSettle() {
        when(sessionMapper.selectAccessibleById(SESSION_ID, MEMBER_ID)).thenReturn(runningSession());
        when(attemptMapper.selectByIdempotencyKey("race-bet")).thenReturn(null);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(predictMapper.selectResult(eq(NODE_ID), any(java.sql.Date.class)))
                .thenReturn(round(PlayPredictResult.SETTLE_PENDING));
        when(predictMapper.selectResultForUpdate(eq(NODE_ID), any(java.sql.Date.class)))
                .thenReturn(round(PlayPredictResult.SETTLE_DONE));

        assertThrows(IllegalStateException.class,
                () -> service.act(MEMBER_ID, SESSION_ID, 0, "race-bet", "SUBMIT_PREDICT", payload("b")),
                "快照说未揭晓、锁定读说已揭晓时必须拒 —— 两读之间结算已经提交");

        verify(predictMapper, never()).insertBet(any());
        verify(sessionMapper, never()).updateStateCas(any());
    }

    /** 未结算的轮次口径一个字不变 —— 这把闸只拦「已经结束」的那两档。 */
    @Test
    void pendingRoundStillAcceptsBet() {
        when(sessionMapper.selectAccessibleById(SESSION_ID, MEMBER_ID)).thenReturn(runningSession());
        when(attemptMapper.selectByIdempotencyKey("first-bet")).thenReturn(null);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
        when(predictMapper.selectResult(eq(NODE_ID), any(java.sql.Date.class))).thenReturn(round(PlayPredictResult.SETTLE_PENDING));
        when(predictMapper.selectResultForUpdate(eq(NODE_ID), any(java.sql.Date.class)))
                .thenReturn(round(PlayPredictResult.SETTLE_PENDING));

        assertDoesNotThrow(
                () -> service.act(MEMBER_ID, SESSION_ID, 0, "first-bet", "SUBMIT_PREDICT", payload("b")));

        // 锁必须在写注之前拿到,否则「先写注再锁」等于没锁
        InOrder order = inOrder(predictMapper);
        order.verify(predictMapper).selectResultForUpdate(eq(NODE_ID), any(java.sql.Date.class));
        order.verify(predictMapper).insertBet(any());
        verify(sessionMapper).updateStateCas(any());
    }

    // ── 夹具 ──

    private static Map<String, Object> payload(String optionKey) {
        return Collections.singletonMap("optionKey", optionKey);
    }

    private static PlayPredictResult round(int settleStatus) {
        PlayPredictResult round = new PlayPredictResult();
        round.setNodeId(NODE_ID);
        round.setSettleStatus(settleStatus);
        round.setSettledOption(PlayPredictResult.SETTLE_DONE == settleStatus ? "a" : null);
        return round;
    }

    private GameRuntimeSession runningSession() {
        GameRuntimeSession session = new GameRuntimeSession();
        session.setId(SESSION_ID);
        session.setActivityId(9L);
        session.setTopicId(19L);
        session.setNodeId(NODE_ID);
        session.setTemplateId(39L);
        session.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        session.setOwnerId(MEMBER_ID);
        session.setConfigSnapshotJson(config());
        session.setStateJson("{}");
        session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setVersion(0);
        session.setStartedAt(new Date(NOW));
        return session;
    }

    private String config() {
        return "{\"schemaVersion\":1,\"predict\":{\"enabled\":true,\"question\":\"今天哪款卖得最多?\","
                + "\"closeAtHour\":21,\"xp\":30,\"options\":["
                + "{\"key\":\"a\",\"label\":\"冰美式\"},{\"key\":\"b\",\"label\":\"燕麦拿铁\"}]}}";
    }
}
