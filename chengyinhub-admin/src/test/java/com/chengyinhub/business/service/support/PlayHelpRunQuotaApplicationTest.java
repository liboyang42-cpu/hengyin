package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.PlayHelpRunQuota;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayHelpRunQuotaMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G7 整局共享免费额度的行为测试：首次消耗、重看不消耗、跨节点最后一份只免费一次、
 * 同 requestId 重放/异 payload、旧局 N 冻结、owner 越权、余额不足不先扣额度。
 *
 * <p>真实 MySQL 行锁/事务回滚不在这里证明；见 {@code PlayHelpRunQuotaConcurrencyMySqlIT}。
 */
class PlayHelpRunQuotaApplicationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MEMBER = 9001L;
    private static final long TOPIC = 55L;
    private static final long ACTIVITY = 66L;
    private static final String INSTANCE = "run-instance-1";
    private static final long NODE_A = 701L;
    private static final long NODE_B = 702L;

    private PlayHelpRunQuotaApplication application;
    private PlayHelpCostApplication cost;
    private FakeAttempts attempts;
    private FakeQuotas quotas;
    private TopicRouteSessionMapper sessions;
    private com.chengyinhub.business.mapper.GameRuntimeSessionMapper gameSessions;
    private com.chengyinhub.business.service.IGameSessionRuntimeService sharedRuns;

    @BeforeEach
    void setUp() {
        cost = mock(PlayHelpCostApplication.class);
        attempts = new FakeAttempts();
        quotas = new FakeQuotas();
        sessions = mock(TopicRouteSessionMapper.class);
        gameSessions = mock(com.chengyinhub.business.mapper.GameRuntimeSessionMapper.class);
        when(sessions.selectByOwner(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(session(TopicRouteSession.STATUS_RUNNING));
        // 真实 G5 的最小替身：首次落 level fact；已存在则回放。它不碰配额，配额由 G7 单独扣。
        when(cost.use(anyLong(), anyString(), anyInt(), anyInt(), anyInt(), anyString()))
                .thenAnswer(call -> {
                    long member = call.getArgument(0);
                    String scopeKey = call.getArgument(1);
                    int level = call.getArgument(2);
                    int freeQuota = call.getArgument(3);
                    int chargePoints = call.getArgument(4);
                    String factKey = PlayHelpUsagePolicy.levelFactKey(member, scopeKey, level);
                    if (attempts.byKey.containsKey(factKey)) {
                        return new PlayHelpCostApplication.Charge(false, true, level, 0, 100L, null);
                    }
                    boolean free = freeQuota >= 1;
                    attempts.insertPlayAttemptLog(fact(member, scopeKey, level, free, chargePoints));
                    return new PlayHelpCostApplication.Charge(true, false, level,
                            free ? 0 : chargePoints, free ? 100L : 100L - chargePoints, null);
                });
        sharedRuns = mock(com.chengyinhub.business.service.IGameSessionRuntimeService.class);
        application = new PlayHelpRunQuotaApplication(cost, attempts, quotas, sessions, gameSessions, JSON, sharedRuns);
    }

    @Test
    void firstFreeUseConsumesOneAndLeavesNMinusOne() {
        PlayHelpRunQuotaApplication.QuotaOutcome outcome = application.use(MEMBER, scope(NODE_A), 1,
                2, 0, 30, "req-1");

        assertTrue(outcome.applied());
        assertTrue(outcome.freeUseConsumed());
        assertEquals(1, outcome.remainingFreeUses());
        assertEquals(1, quotas.used(MEMBER, TOPIC, ACTIVITY, INSTANCE));
        verify(cost).use(eq(MEMBER), anyString(), eq(1), eq(1), eq(30), eq("req-1"));
    }

    @Test
    void paidUseWhenQuotaExhaustedDoesNotConsume() {
        application.use(MEMBER, scope(NODE_A), 1, 1, 0, 30, "req-1");

        PlayHelpRunQuotaApplication.QuotaOutcome paid = application.use(MEMBER, scope(NODE_B), 2,
                1, 0, 30, "req-2");

        assertTrue(paid.applied());
        assertFalse(paid.freeUseConsumed());
        assertEquals(0, paid.remainingFreeUses());
        assertEquals(30, paid.charge().chargedPoints());
        assertEquals(1, quotas.used(MEMBER, TOPIC, ACTIVITY, INSTANCE));
        verify(cost).use(eq(MEMBER), anyString(), eq(2), eq(0), eq(30), eq("req-2"));
    }

    @Test
    void zeroPricedLayerIsFreeByAuthorPriceAndNeverEatsTheRunQuota() {
        application.use(MEMBER, scope(NODE_A), 1, 1, 0, 30, "req-1");
        assertEquals(1, quotas.used(MEMBER, TOPIC, ACTIVITY, INSTANCE));

        PlayHelpRunQuotaApplication.QuotaOutcome zeroPriced = application.use(MEMBER, scope(NODE_B), 2,
                1, 0, 0, "req-2");

        assertTrue(zeroPriced.applied());
        assertFalse(zeroPriced.freeUseConsumed(), "标价 0 的层是作者定价为免，不该消耗整局额度");
        assertEquals(1, quotas.used(MEMBER, TOPIC, ACTIVITY, INSTANCE));
        verify(cost).use(eq(MEMBER), anyString(), eq(2), eq(0), eq(0), eq("req-2"));
    }

    @Test
    void reReadingAnUnlockedLevelDoesNotConsumeQuota() {
        application.use(MEMBER, scope(NODE_A), 1, 2, 0, 30, "req-1");
        assertEquals(1, quotas.used(MEMBER, TOPIC, ACTIVITY, INSTANCE));

        PlayHelpRunQuotaApplication.QuotaOutcome reread = application.use(MEMBER, scope(NODE_A), 1,
                2, 0, 30, "req-2");

        assertFalse(reread.applied());
        assertTrue(reread.alreadyUnlocked());
        assertFalse(reread.freeUseConsumed());
        assertEquals(1, quotas.used(MEMBER, TOPIC, ACTIVITY, INSTANCE), "重看不得再消耗免费额度");
        assertEquals(1, reread.remainingFreeUses());
    }

    @Test
    void sameRequestIdOnAnotherNodeConflictsWithoutExtraQuota() {
        application.use(MEMBER, scope(NODE_A), 1, 2, 0, 30, "req-shared");

        PlayHelpRunQuotaApplication.PlayHelpRunQuotaException conflict = assertThrows(
                PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                () -> application.use(MEMBER, scope(NODE_B), 2, 2, 0, 30, "req-shared"));

        assertEquals(PlayHelpRunQuotaApplication.REASON_REQUEST_CONFLICT, conflict.code());
        assertEquals(1, quotas.used(MEMBER, TOPIC, ACTIVITY, INSTANCE), "冲突请求不得多扣免费额度");
        assertFalse(attempts.byKey.containsKey(
                PlayHelpUsagePolicy.levelFactKey(MEMBER, PlayHelpUsagePolicy.scopeKey(scope(NODE_B)), 2)),
                "冲突在 G5 之前被拦下，不能先写第二层事实");
    }

    /**
     * 纯高级玩法局的归属必须走 game_runtime_session，**不能**硬查 topic_route_session。
     * 这是已证实的接线缺陷：高级局在 topic_route_session 里没有行，硬查会让整个帮助功能 403。
     */
    @Test
    void advancedScopeIsAuthorisedThroughTheGameSessionNotTheRouteSession() {
        com.chengyinhub.business.domain.GameRuntimeSession runtime =
                new com.chengyinhub.business.domain.GameRuntimeSession();
        runtime.setId(4242L);
        runtime.setNodeId(NODE_A);
        runtime.setTopicId(TOPIC);
        runtime.setActivityId(ACTIVITY);
        runtime.setStatus(com.chengyinhub.business.domain.GameRuntimeSession.STATUS_RUNNING);
        when(gameSessions.selectAccessibleById(4242L, MEMBER)).thenReturn(runtime);

        PlayHelpUsagePolicy.Scope advanced =
                PlayHelpUsagePolicy.Scope.advanced(TOPIC, ACTIVITY, NODE_A, 4242L);
        int remaining = application.remainingFreeUses(MEMBER, advanced, 2);

        assertEquals(2, remaining, "尚未使用时剩余就是冻结的整局 N");
        verify(gameSessions).selectAccessibleById(4242L, MEMBER);
        verify(sessions, never()).selectByOwner(anyLong(), anyLong(), anyLong(), anyString());
    }

    /** 高级局不属于本人：与路线局同文案，不给任何额度信息。 */
    @Test
    void advancedScopeOfAnotherMemberIsForbiddenAndNeverFallsBackToTheRouteSession() {
        when(gameSessions.selectAccessibleById(4242L, MEMBER)).thenReturn(null);

        PlayHelpRunQuotaApplication.PlayHelpRunQuotaException refused = assertThrows(
                PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                () -> application.remainingFreeUses(MEMBER,
                        PlayHelpUsagePolicy.Scope.advanced(TOPIC, ACTIVITY, NODE_A, 4242L), 2));

        assertEquals(PlayHelpRunQuotaApplication.REASON_SCOPE_FORBIDDEN, refused.code());
        verify(sessions, never()).selectByOwner(anyLong(), anyLong(), anyLong(), anyString());
    }

    /** 拿 A 局的 sessionId 去读 B 局的额度：主题或场次不符一律拒绝。 */
    @Test
    void advancedScopeWithMismatchedTopicOrActivityIsForbidden() {
        com.chengyinhub.business.domain.GameRuntimeSession otherTopic =
                new com.chengyinhub.business.domain.GameRuntimeSession();
        otherTopic.setId(4242L);
        otherTopic.setNodeId(NODE_A);
        otherTopic.setTopicId(TOPIC + 1);
        otherTopic.setActivityId(ACTIVITY);
        otherTopic.setStatus(com.chengyinhub.business.domain.GameRuntimeSession.STATUS_RUNNING);
        when(gameSessions.selectAccessibleById(4242L, MEMBER)).thenReturn(otherTopic);
        assertEquals(PlayHelpRunQuotaApplication.REASON_SCOPE_FORBIDDEN,
                assertThrows(PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                        () -> application.remainingFreeUses(MEMBER,
                                PlayHelpUsagePolicy.Scope.advanced(TOPIC, ACTIVITY, NODE_A, 4242L), 2)).code());

        com.chengyinhub.business.domain.GameRuntimeSession otherActivity =
                new com.chengyinhub.business.domain.GameRuntimeSession();
        otherActivity.setId(4242L);
        otherActivity.setNodeId(NODE_A);
        otherActivity.setTopicId(TOPIC);
        otherActivity.setActivityId(ACTIVITY + 1);
        otherActivity.setStatus(com.chengyinhub.business.domain.GameRuntimeSession.STATUS_RUNNING);
        when(gameSessions.selectAccessibleById(4242L, MEMBER)).thenReturn(otherActivity);
        assertEquals(PlayHelpRunQuotaApplication.REASON_SCOPE_FORBIDDEN,
                assertThrows(PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                        () -> application.remainingFreeUses(MEMBER,
                                PlayHelpUsagePolicy.Scope.advanced(TOPIC, ACTIVITY, NODE_A, 4242L), 2)).code());
    }

    /** 高级局已结束：与路线局同一「本局已结束」拒绝，不继续扣额度。 */
    @Test
    void closedAdvancedScopeIsRefused() {
        com.chengyinhub.business.domain.GameRuntimeSession closed =
                new com.chengyinhub.business.domain.GameRuntimeSession();
        closed.setId(4242L);
        closed.setNodeId(NODE_A);
        closed.setTopicId(TOPIC);
        closed.setActivityId(ACTIVITY);
        closed.setStatus(com.chengyinhub.business.domain.GameRuntimeSession.STATUS_COMPLETED);
        when(gameSessions.selectAccessibleById(4242L, MEMBER)).thenReturn(closed);

        assertEquals(PlayHelpRunQuotaApplication.REASON_SCOPE_CLOSED,
                assertThrows(PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                        () -> application.remainingFreeUses(MEMBER,
                                PlayHelpUsagePolicy.Scope.advanced(TOPIC, ACTIVITY, NODE_A, 4242L), 2)).code());
    }

    /** 两个 kind 不共用池：同 topic/activity/node/实例数字，路线局的配额行不可被高级局读走。 */
    @Test
    void advancedAndRouteScopesDoNotShareTheFreeUsePool() {
        com.chengyinhub.business.domain.GameRuntimeSession runtime =
                new com.chengyinhub.business.domain.GameRuntimeSession();
        runtime.setId(4242L);
        runtime.setNodeId(NODE_A);
        runtime.setTopicId(TOPIC);
        runtime.setActivityId(ACTIVITY);
        runtime.setStatus(com.chengyinhub.business.domain.GameRuntimeSession.STATUS_RUNNING);
        when(gameSessions.selectAccessibleById(4242L, MEMBER)).thenReturn(runtime);

        PlayHelpUsagePolicy.Scope advanced =
                PlayHelpUsagePolicy.Scope.advanced(TOPIC, ACTIVITY, NODE_A, 4242L);
        PlayHelpUsagePolicy.Scope routeLookalike =
                new PlayHelpUsagePolicy.Scope(TOPIC, ACTIVITY, NODE_A, "game:4242");

        assertNotEquals(PlayHelpUsagePolicy.scopeKey(advanced),
                PlayHelpUsagePolicy.scopeKey(routeLookalike));
        // 两个 key 落到不同的配额行/事实键：这里用 runKey 语义间接验证（额度行按 scopeKey 派生）。
        assertEquals(2, application.remainingFreeUses(MEMBER, advanced, 2));
    }

    @Test
    void sameRequestIdAndPayloadReplaysWithoutSecondConsumption() {
        application.use(MEMBER, scope(NODE_A), 1, 2, 0, 30, "req-1");
        PlayHelpRunQuotaApplication.QuotaOutcome replay = application.use(MEMBER, scope(NODE_A), 1,
                2, 0, 30, "req-1");

        assertFalse(replay.applied());
        assertTrue(replay.alreadyUnlocked());
        assertFalse(replay.freeUseConsumed());
        assertEquals(1, quotas.used(MEMBER, TOPIC, ACTIVITY, INSTANCE));
        assertEquals(1, replay.remainingFreeUses());
    }

    @Test
    void insufficientPointsPropagatesBeforeQuotaIsConsumed() {
        when(cost.use(eq(MEMBER), anyString(), eq(2), anyInt(), anyInt(), eq("req-poor")))
                .thenThrow(new PlayHelpCostApplication.PlayHelpCostException("POINTS_INSUFFICIENT", "积分不足"));

        PlayHelpCostApplication.PlayHelpCostException refused = assertThrows(
                PlayHelpCostApplication.PlayHelpCostException.class,
                () -> application.use(MEMBER, scope(NODE_A), 2, 2, 0, 30, "req-poor"));

        assertEquals("POINTS_INSUFFICIENT", refused.code());
        assertEquals(0, quotas.used(MEMBER, TOPIC, ACTIVITY, INSTANCE), "G5 失败时额度尚未消费");
    }

    @Test
    void otherMembersRunIsRejectedBeforeAnyWrite() {
        when(sessions.selectByOwner(anyLong(), anyLong(), anyLong(), anyString())).thenReturn(null);

        PlayHelpRunQuotaApplication.PlayHelpRunQuotaException refused = assertThrows(
                PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                () -> application.use(MEMBER + 1, scope(NODE_A), 1, 2, 0, 30, "req-x"));

        assertEquals(PlayHelpRunQuotaApplication.REASON_SCOPE_FORBIDDEN, refused.code());
        assertEquals(0, quotas.writeCount);
        assertTrue(attempts.byKey.isEmpty());
        verify(cost, never()).use(anyLong(), anyString(), anyInt(), anyInt(), anyInt(), anyString());
    }

    @Test
    void absentRunNMeansZeroFreeUsesAndTheRetiredPerLevelFlagIsIgnored() {
        // 旧配置残留 freeQuota=1：不得再产生免费次数，否则等于第二套正额度。
        PlayHelpRunQuotaApplication.QuotaOutcome outcome = application.use(MEMBER, scope(NODE_A), 1,
                null, 1, 30, "req-old");

        assertFalse(outcome.runQuotaConfigured());
        assertTrue(outcome.charge().applied());
        assertEquals(30, outcome.charge().chargedPoints(), "缺省/0 = 本局 0 次免费，按标价收费");
        assertEquals(0, outcome.remainingFreeUses());
        assertEquals(0, quotas.writeCount, "本局 0 次免费时不创建额度行");
        verify(cost).use(eq(MEMBER), anyString(), eq(1), eq(0), eq(30), eq("req-old"));
    }

    @Test
    void explicitZeroRunNAlsoChargesInsteadOfFallingBackToPerLevelQuota() {
        PlayHelpRunQuotaApplication.QuotaOutcome outcome = application.use(MEMBER, scope(NODE_A), 1,
                0, 1, 30, "req-zero");

        assertFalse(outcome.runQuotaConfigured());
        assertEquals(30, outcome.charge().chargedPoints());
        assertEquals(0, quotas.writeCount);
        verify(cost).use(eq(MEMBER), anyString(), eq(1), eq(0), eq(30), eq("req-zero"));
    }

    @Test
    void remainingFreeUsesIsReadOnly() {
        assertEquals(3, application.remainingFreeUses(MEMBER, scope(NODE_A), 3));
        assertEquals(3, application.remainingFreeUses(MEMBER, scope(NODE_A), 3));
        assertEquals(0, quotas.writeCount);
    }

    @Test
    void frozenNIsNeverOverwrittenAndADifferentNIsAConfigConflict() {
        application.use(MEMBER, scope(NODE_A), 1, 2, 0, 30, "req-1");
        assertEquals(2, quotas.total(MEMBER, TOPIC, ACTIVITY, INSTANCE));

        PlayHelpRunQuotaApplication.PlayHelpRunQuotaException conflict = assertThrows(
                PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                () -> application.remainingFreeUses(MEMBER, scope(NODE_A), 5));
        assertEquals(PlayHelpRunQuotaApplication.REASON_CONFIG_CONFLICT, conflict.code());

        PlayHelpRunQuotaApplication.PlayHelpRunQuotaException useConflict = assertThrows(
                PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                () -> application.use(MEMBER, scope(NODE_B), 2, 5, 0, 30, "req-2"));
        assertEquals(PlayHelpRunQuotaApplication.REASON_CONFIG_CONFLICT, useConflict.code());
        assertEquals(2, quotas.total(MEMBER, TOPIC, ACTIVITY, INSTANCE),
                "不同 N 只能报冲突，不能覆盖已冻结 total");
        assertEquals(1, quotas.used(MEMBER, TOPIC, ACTIVITY, INSTANCE));

        application.use(MEMBER, scope(NODE_B), 2, 2, 0, 30, "req-2b");
        assertEquals(2, quotas.used(MEMBER, TOPIC, ACTIVITY, INSTANCE));
    }

    /** 个人路线局即使实例字符串相同也保持独立，层事实仍按实际会员隔离。 */
    @Test
    void anotherMemberNeverReadsOrConsumesMyPool() {
        application.use(MEMBER, scope(NODE_A), 1, 2, 0, 30, "req-a");
        assertEquals(2, application.remainingFreeUses(MEMBER + 1, scope(NODE_B), 2));
        assertEquals(1, application.remainingFreeUses(MEMBER, scope(NODE_B), 2));

        application.use(MEMBER + 1, scope(NODE_A), 1, 2, 0, 30, "req-b");
        assertEquals(1, quotas.used(MEMBER + 1, TOPIC, ACTIVITY, INSTANCE));
        assertEquals(1, quotas.used(MEMBER, TOPIC, ACTIVITY, INSTANCE));
    }

    @Test
    void lastQuotaAcrossTwoNodesIsFreeOnlyOnceSequentially() {
        application.use(MEMBER, scope(NODE_A), 1, 1, 0, 30, "req-a");
        PlayHelpRunQuotaApplication.QuotaOutcome second = application.use(MEMBER, scope(NODE_B), 2,
                1, 0, 30, "req-b");

        assertFalse(second.freeUseConsumed());
        assertEquals(30, second.charge().chargedPoints());
        assertEquals(1, quotas.used(MEMBER, TOPIC, ACTIVITY, INSTANCE));
    }

    @Test
    void missingRequestIdIsRejectedBeforeQuotaClaim() {
        PlayHelpRunQuotaApplication.PlayHelpRunQuotaException refused = assertThrows(
                PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                () -> application.use(MEMBER, scope(NODE_A), 1, 2, 0, 30, " "));

        assertEquals(PlayHelpRunQuotaApplication.REASON_REQUEST_INVALID, refused.code());
        assertEquals(0, quotas.writeCount);
    }

    @Test
    void consumeFailureAfterG5PropagatesSoOuterTransactionRollsBackTheFirstFreeUse() {
        quotas.consumeReturnsZeroOnce = true;

        PlayHelpRunQuotaApplication.PlayHelpRunQuotaException exhausted = assertThrows(
                PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                () -> application.use(MEMBER, scope(NODE_A), 1, 1, 0, 30, "req-race"));

        assertEquals(PlayHelpRunQuotaApplication.REASON_EXHAUSTED, exhausted.code());
        assertEquals(0, quotas.used(MEMBER, TOPIC, ACTIVITY, INSTANCE),
                "额度抢失败必须抛异常让外层回滚 G5 已写的首次事实/流水");
    }

    @Test
    void authorisedTeamMembersAcrossNodesConsumeOneWholeRunPoolAndPayAsTheActingMember() {
        sharedSession(4242L, NODE_A, 808L, 77L, MEMBER, MEMBER + 1);
        sharedSession(4243L, NODE_B, 808L, 77L, MEMBER, MEMBER + 1);
        var first = application.use(MEMBER, advanced(4242L, NODE_A), 1, 2, 0, 30, "req-a");
        assertEquals(1, first.remainingFreeUses());
        assertEquals(1, application.remainingFreeUses(MEMBER + 1, advanced(4243L, NODE_B), 2));
        var second = application.use(MEMBER + 1, advanced(4243L, NODE_B), 1, 2, 0, 30, "req-b");
        assertEquals(0, second.remainingFreeUses());
        assertEquals(2, quotas.used(77L, TOPIC, ACTIVITY, "shared-game:808"));
        assertEquals(1, quotas.rows.size(), "不同节点、不同成员只创建一个整局额度行");

        var charged = application.use(MEMBER + 1, advanced(4243L, NODE_B), 2, 2, 0, 30, "req-paid");
        assertEquals(30, charged.charge().chargedPoints());
        verify(cost).use(eq(MEMBER + 1), anyString(), eq(2), eq(0), eq(30), eq("req-paid"));
        verify(sharedRuns, org.mockito.Mockito.times(2))
                .assertSharedRouteWritable(MEMBER + 1, TOPIC, ACTIVITY, NODE_B, 1);
    }

    @Test
    void sameSharedRunRequestOnAnotherNodeConflictsWithoutExtraConsumption() {
        sharedSession(4242L, NODE_A, 808L, 77L, MEMBER);
        sharedSession(4243L, NODE_B, 808L, 77L, MEMBER);
        application.use(MEMBER, advanced(4242L, NODE_A), 1, 2, 0, 30, "req-same");
        assertEquals(PlayHelpRunQuotaApplication.REASON_REQUEST_CONFLICT,
                assertThrows(PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                        () -> application.use(MEMBER, advanced(4243L, NODE_B), 1, 2, 0, 30, "req-same")).code());
        assertEquals(1, quotas.used(77L, TOPIC, ACTIVITY, "shared-game:808"));
    }

    @Test
    void separateSharedRunsAndTeamsNeverShareThePool() {
        sharedSession(4242L, NODE_A, 808L, 77L, MEMBER);
        sharedSession(4243L, NODE_B, 809L, 77L, MEMBER);
        sharedSession(4244L, NODE_A, 808L, 78L, MEMBER + 1);
        application.use(MEMBER, advanced(4242L, NODE_A), 1, 2, 0, 30, "req-1");
        assertEquals(2, application.remainingFreeUses(MEMBER, advanced(4243L, NODE_B), 2));
        assertEquals(2, application.remainingFreeUses(MEMBER + 1, advanced(4244L, NODE_A), 2));
    }

    @Test
    void sharedRunReViewAndRepeatedRequestDoNotConsumeAgain() {
        sharedSession(4242L, NODE_A, 808L, 77L, MEMBER, MEMBER + 1);
        var scope = advanced(4242L, NODE_A);
        application.use(MEMBER, scope, 1, 3, 0, 30, "same-request");
        var replay = application.use(MEMBER, scope, 1, 3, 0, 30, "same-request");
        var reView = application.use(MEMBER, scope, 1, 3, 0, 30, "re-view");
        assertTrue(replay.alreadyUnlocked());
        assertTrue(reView.alreadyUnlocked());
        assertFalse(replay.freeUseConsumed());
        assertFalse(reView.freeUseConsumed());
        // 请求与层事实仍按会员隔离；另一成员的首次提示只消耗同一池的一份。
        application.use(MEMBER + 1, scope, 1, 3, 0, 30, "same-request");
        assertEquals(2, quotas.used(77L, TOPIC, ACTIVITY, "shared-game:808"));
    }

    @Test
    void unauthorisedTeamActorCannotReadSpendOrReplayThePool() {
        sharedSession(4242L, NODE_A, 808L, 77L, MEMBER);
        var scope = advanced(4242L, NODE_A);
        application.use(MEMBER, scope, 1, 2, 0, 30, "req-a");
        int writes = quotas.writeCount;
        int claims = attempts.byKey.size();
        assertEquals(PlayHelpRunQuotaApplication.REASON_SCOPE_FORBIDDEN,
                assertThrows(PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                        () -> application.remainingFreeUses(MEMBER + 1, scope, 2)).code());
        assertEquals(PlayHelpRunQuotaApplication.REASON_SCOPE_FORBIDDEN,
                assertThrows(PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                        () -> application.use(MEMBER + 1, scope, 1, 2, 0, 30, "req-a")).code());
        assertEquals(writes, quotas.writeCount);
        assertEquals(claims, attempts.byKey.size());
        assertEquals(1, quotas.used(77L, TOPIC, ACTIVITY, "shared-game:808"));
    }

    @Test
    void deterministicLegacyNodeMemberUsageIsCarriedIntoTheSharedPoolOnlyOnce() {
        sharedSession(4242L, NODE_A, 808L, 77L, MEMBER, MEMBER + 1);
        legacyQuota(808L, 3, 1);
        legacyQuota(808L, 3, 1);
        var scope = advanced(4242L, NODE_A);
        assertEquals(1, application.remainingFreeUses(MEMBER, scope, 3));
        assertEquals(0, quotas.writeCount, "读旧局不写入或重开 N");
        application.use(MEMBER + 1, scope, 1, 3, 0, 30, "req-last");
        assertEquals(3, quotas.used(77L, TOPIC, ACTIVITY, "shared-game:808"));
        assertEquals(0, application.remainingFreeUses(MEMBER, scope, 3));
    }

    @Test
    void legacyOverspendDoesNotCreateAnyNewFreeUses() {
        sharedSession(4242L, NODE_A, 808L, 77L, MEMBER);
        legacyQuota(808L, 2, 2);
        legacyQuota(808L, 2, 1);
        var paid = application.use(MEMBER, advanced(4242L, NODE_A), 1, 2, 0, 30, "req-paid");
        assertEquals(30, paid.charge().chargedPoints());
        assertEquals(0, paid.remainingFreeUses());
        assertEquals(0, quotas.writeCount);
    }

    @Test
    void ambiguousOrConflictingLegacyRowsKeepTheirOriginalPool() {
        sharedSession(4242L, NODE_A, 808L, 77L, MEMBER);
        quotas.insertIfAbsent(TOPIC, ACTIVITY, "game:4242", MEMBER, 3);
        quotas.consumeOne(TOPIC, ACTIVITY, "game:4242", MEMBER);
        legacyQuota(null, 3, 1);
        var scope = advanced(4242L, NODE_A);
        assertEquals(2, application.remainingFreeUses(MEMBER, scope, 3));
        application.use(MEMBER, scope, 1, 3, 0, 30, "req-old");
        assertEquals(2, quotas.used(MEMBER, TOPIC, ACTIVITY, "game:4242"));
        assertEquals(0, quotas.used(77L, TOPIC, ACTIVITY, "shared-game:808"));

        quotas.legacy.clear();
        legacyQuota(808L, 4, 1);
        assertEquals(1, application.remainingFreeUses(MEMBER, scope, 3),
                "旧冻结 N 冲突时也保留原局余量，不以新共享键重开");
    }

    @Test
    void staleSharedBindingOrAuthorityIsRejectedBeforeAnyPoolReadOrWrite() {
        var runtime = sharedSession(4242L, NODE_A, 808L, 77L, MEMBER);
        runtime.setPlayInstanceId("game:another-run");
        var scope = advanced(4242L, NODE_A);
        assertEquals(PlayHelpRunQuotaApplication.REASON_SCOPE_FORBIDDEN,
                assertThrows(PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                        () -> application.use(MEMBER, scope, 1, 2, 0, 30, "req-x")).code());
        assertEquals(0, quotas.writeCount);
        assertTrue(attempts.byKey.isEmpty());
        runtime.setPlayInstanceId("game:808:quest:4242");
        runtime.setStateJson("{\"originalGameSessionId\":808,\"runtimeAuthorityEpoch\":2}");
        assertThrows(PlayHelpRunQuotaApplication.PlayHelpRunQuotaException.class,
                () -> application.remainingFreeUses(MEMBER, scope, 2));
    }

    @Test
    void authorityTakeoverKeepsTheExistingSharedPool() {
        var runtime = sharedSession(4242L, NODE_A, 808L, 77L, MEMBER);
        var scope = advanced(4242L, NODE_A);
        application.use(MEMBER, scope, 1, 2, 0, 30, "req-before");
        runtime.setStateJson("{\"originalGameSessionId\":808,\"runtimeAuthorityEpoch\":2}");
        when(sharedRuns.sharedRouteSnapshot(MEMBER, TOPIC, ACTIVITY, NODE_A)).thenReturn(
                JSON.createObjectNode().put("gameSessionId", 808L).put("teamId", 77L)
                        .put("authorityEpoch", 2).put("runtimeInstance", runtime.getPlayInstanceId()));
        application.use(MEMBER, scope, 2, 2, 0, 30, "req-after");
        assertEquals(0, application.remainingFreeUses(MEMBER, scope, 2));
        assertEquals(2, quotas.used(77L, TOPIC, ACTIVITY, "shared-game:808"));
        assertEquals(1, quotas.rows.size());
    }

    @Test
    void advancedSoloAndUnboundOldTeamRunsKeepTheirOriginalPool() {
        var runtime = sharedSession(4242L, NODE_A, 808L, 77L, MEMBER);
        runtime.setOwnerType(com.chengyinhub.business.domain.GameRuntimeSession.OWNER_PLAYER);
        runtime.setOwnerId(MEMBER);
        application.use(MEMBER, advanced(4242L, NODE_A), 1, 2, 0, 30, "req-solo");
        assertEquals(1, quotas.used(MEMBER, TOPIC, ACTIVITY, "game:4242"));
        verify(sharedRuns, never()).sharedRouteSnapshot(anyLong(), anyLong(), anyLong(), anyLong());

        runtime.setOwnerType(com.chengyinhub.business.domain.GameRuntimeSession.OWNER_TEAM);
        runtime.setOwnerId(77L);
        runtime.setStateJson("{}");
        application.use(MEMBER, advanced(4242L, NODE_A), 2, 2, 0, 30, "req-old");
        assertEquals(2, quotas.used(MEMBER, TOPIC, ACTIVITY, "game:4242"));
        assertEquals(0, quotas.used(77L, TOPIC, ACTIVITY, "shared-game:808"));
    }

    @Test
    void sharedWriteFenceFailureDoesNotClaimOrConsume() {
        sharedSession(4242L, NODE_A, 808L, 77L, MEMBER);
        org.mockito.Mockito.doThrow(new IllegalStateException("shared run closed"))
                .when(sharedRuns).assertSharedRouteWritable(MEMBER, TOPIC, ACTIVITY, NODE_A, 1);
        assertThrows(IllegalStateException.class,
                () -> application.use(MEMBER, advanced(4242L, NODE_A), 1, 2, 0, 30, "req-x"));
        assertEquals(0, quotas.writeCount);
        assertTrue(attempts.byKey.isEmpty());
    }

    private com.chengyinhub.business.domain.GameRuntimeSession sharedSession(
            long sessionId, long nodeId, long gameId, long teamId, long... members) {
        var runtime = new com.chengyinhub.business.domain.GameRuntimeSession();
        runtime.setId(sessionId);
        runtime.setTopicId(TOPIC);
        runtime.setActivityId(ACTIVITY);
        runtime.setNodeId(nodeId);
        runtime.setOwnerType(com.chengyinhub.business.domain.GameRuntimeSession.OWNER_TEAM);
        runtime.setOwnerId(teamId);
        runtime.setStatus(com.chengyinhub.business.domain.GameRuntimeSession.STATUS_RUNNING);
        runtime.setPlayInstanceId("game:" + gameId + ":quest:" + sessionId);
        runtime.setStateJson("{\"originalGameSessionId\":" + gameId + ",\"runtimeAuthorityEpoch\":1}");
        var snapshot = JSON.createObjectNode().put("gameSessionId", gameId).put("teamId", teamId)
                .put("authorityEpoch", 1).put("runtimeInstance", runtime.getPlayInstanceId());
        for (long member : members) {
            when(gameSessions.selectAccessibleById(sessionId, member)).thenReturn(runtime);
            when(sharedRuns.sharedRouteSnapshot(member, TOPIC, ACTIVITY, nodeId)).thenReturn(snapshot);
        }
        return runtime;
    }

    private void legacyQuota(Long gameId, int total, int used) {
        var quota = new PlayHelpRunQuota();
        quota.setSharedRunId(gameId);
        quota.setQuotaTotal(total);
        quota.setQuotaUsed(used);
        quotas.legacy.add(quota);
    }

    private static PlayHelpUsagePolicy.Scope advanced(long sessionId, long nodeId) {
        return PlayHelpUsagePolicy.Scope.advanced(TOPIC, ACTIVITY, nodeId, sessionId);
    }

    // ------------------------------------------------------------------ fixtures

    private static PlayHelpUsagePolicy.Scope scope(long nodeId) {
        return new PlayHelpUsagePolicy.Scope(TOPIC, ACTIVITY, nodeId, INSTANCE);
    }

    private static TopicRouteSession session(int status) {
        TopicRouteSession session = new TopicRouteSession();
        session.setId(501L);
        session.setTopicId(TOPIC);
        session.setActivityId(ACTIVITY);
        session.setMemberId(MEMBER);
        session.setPlayInstanceId(INSTANCE);
        session.setStatus(status);
        return session;
    }

    private static PlayAttemptLog fact(long member, String scopeKey, int level, boolean free, int chargePoints) {
        PlayAttemptLog log = new PlayAttemptLog();
        log.setMemberId(member);
        log.setAttemptType(PlayHelpCostApplication.ATTEMPT_TYPE_HELP);
        log.setResult(null);
        log.setIdempotencyKey(PlayHelpUsagePolicy.levelFactKey(member, scopeKey, level));
        log.setResultJson("{\"schema\":\"play-help-fact:v1\",\"free\":" + free
                + ",\"chargedPoints\":" + (free ? 0 : chargePoints) + "}");
        return log;
    }

    private static final class FakeQuotas implements PlayHelpRunQuotaMapper {
        private final Map<String, PlayHelpRunQuota> rows = new LinkedHashMap<>();
        private final List<PlayHelpRunQuota> legacy = new java.util.ArrayList<>();
        private int writeCount;
        private boolean consumeReturnsZeroOnce;

        @Override
        public int insertIfAbsent(Long topicId, Long activityId, String playInstanceId,
                Long ownerMemberId, Integer quotaTotal) {
            writeCount++;
            String key = key(ownerMemberId, topicId, activityId, playInstanceId);
            if (!rows.containsKey(key)) {
                PlayHelpRunQuota row = new PlayHelpRunQuota();
                row.setTopicId(topicId);
                row.setActivityId(activityId);
                row.setPlayInstanceId(playInstanceId);
                row.setOwnerMemberId(ownerMemberId);
                row.setQuotaTotal(quotaTotal);
                row.setQuotaUsed(0);
                rows.put(key, row);
            }
            return 1;
        }

        @Override
        public int insertSharedIfAbsent(Long topicId, Long activityId, String instance,
                Long ownerId, Integer total, Integer used) {
            String key = key(ownerId, topicId, activityId, instance);
            boolean exists = rows.containsKey(key);
            insertIfAbsent(topicId, activityId, instance, ownerId, total);
            if (!exists) rows.get(key).setQuotaUsed(used);
            return 1;
        }

        @Override
        public List<PlayHelpRunQuota> selectLegacySharedQuotas(Long topicId, Long activityId,
                Long gameId, Long teamId) {
            return legacy.stream().filter(row -> row.getSharedRunId() == null
                    || gameId.equals(row.getSharedRunId())).toList();
        }

        @Override
        public PlayHelpRunQuota selectByScope(Long topicId, Long activityId, String playInstanceId,
                Long ownerMemberId) {
            return rows.get(key(ownerMemberId, topicId, activityId, playInstanceId));
        }

        @Override
        public int consumeOne(Long topicId, Long activityId, String playInstanceId, Long ownerMemberId) {
            writeCount++;
            if (consumeReturnsZeroOnce) {
                consumeReturnsZeroOnce = false;
                return 0;
            }
            PlayHelpRunQuota row = rows.get(key(ownerMemberId, topicId, activityId, playInstanceId));
            if (row == null || row.getQuotaUsed() >= row.getQuotaTotal()) return 0;
            row.setQuotaUsed(row.getQuotaUsed() + 1);
            return 1;
        }

        int used(Long ownerMemberId, Long topicId, Long activityId, String playInstanceId) {
            PlayHelpRunQuota row = rows.get(key(ownerMemberId, topicId, activityId, playInstanceId));
            return row == null ? 0 : row.getQuotaUsed();
        }

        int total(Long ownerMemberId, Long topicId, Long activityId, String playInstanceId) {
            PlayHelpRunQuota row = rows.get(key(ownerMemberId, topicId, activityId, playInstanceId));
            return row == null ? -1 : row.getQuotaTotal();
        }

        private static String key(Long ownerMemberId, Long topicId, Long activityId, String playInstanceId) {
            return ownerMemberId + ":" + topicId + ":" + activityId + ":" + playInstanceId;
        }
    }

    private static final class FakeAttempts implements PlayAttemptLogMapper {
        private final Map<String, PlayAttemptLog> byKey = new LinkedHashMap<>();

        @Override
        public int insertPlayAttemptLog(PlayAttemptLog log) {
            String key = log.getIdempotencyKey();
            if (key != null && byKey.containsKey(key)) throw new DuplicateKeyException("uk_play_attempt_idempotency");
            if (key != null) byKey.put(key, log);
            return 1;
        }

        @Override
        public int selectMaxPuzzleHintLevel(Long memberId, Long topicId, Long nodeId, Long activityId) {
            return 0;
        }

        @Override
        public List<Map<String, Object>> selectPuzzleHintLevels(Long memberId, Long topicId, Long activityId) {
            return List.of();
        }

        @Override
        public PlayAttemptLog selectByIdempotencyKey(String idempotencyKey) {
            return byKey.get(idempotencyKey);
        }

        @Override
        public PlayAttemptLog selectByIdempotencyKeyForUpdate(String idempotencyKey) {
            return byKey.get(idempotencyKey);
        }

        @Override
        public List<PlayAttemptLog> selectPreferenceResults(Long memberId, Long topicId) {
            return List.of();
        }
    }
}
