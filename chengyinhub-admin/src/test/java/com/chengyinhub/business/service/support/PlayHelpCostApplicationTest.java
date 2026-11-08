package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

/**
 * BE-16 提示代价适配器回归：每局每级唯一、同级重看 0 扣、余额不足零副作用、
 * 同键异代价冲突、旧 member+node 唯一键不得冒充每局每级。
 *
 * <p>用**内存台账**模拟两条既有唯一键的语义（`uk_play_attempt_idempotency` 与
 * `uk_member_reward_business`）和 `point >= cost` 的条件扣减；这样测的是适配器与唯一键的配合，
 * 不是 Mockito 的返回值。真实 MySQL 的行锁/RR/撞键仍须由 I 在测试 VPS 验证。
 */
class PlayHelpCostApplicationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MEMBER = 9001L;

    private PlayHelpCostApplication application;
    private Ledger ledger;
    private PlayAttemptLogMapper attempts;
    private UmsMemberMapper members;
    private IUmsMemberService memberService;
    private IUmsMemberPointsDetailService pointsDetails;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        attempts = new FakeAttemptLogMapper(ledger);
        members = mock(UmsMemberMapper.class);
        memberService = mock(IUmsMemberService.class);
        pointsDetails = mock(IUmsMemberPointsDetailService.class);

        when(members.lockMemberById(MEMBER)).thenAnswer(call -> ledger.exists(MEMBER) ? Long.valueOf(MEMBER) : null);
        when(members.selectPointsForUpdate(MEMBER)).thenAnswer(call -> ledger.points(MEMBER));
        when(memberService.deductPoints(org.mockito.ArgumentMatchers.eq(Long.valueOf(MEMBER)),
                org.mockito.ArgumentMatchers.anyInt())).thenAnswer(call -> {
                    Integer cost = call.getArgument(1);
                    return ledger.deduct(MEMBER, cost == null ? 0 : cost.intValue());
                });
        when(pointsDetails.insertUmsMemberPointsDetail(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ledger.insertDetail(call.getArgument(0)));

        application = new PlayHelpCostApplication(attempts, members, memberService, pointsDetails, JSON);
    }

    private static String scope(String run) {
        return PlayHelpUsagePolicy.scopeKey(new PlayHelpUsagePolicy.Scope(55L, 66L, 77L, run));
    }

    @Test
    void freeQuotaFirstUseDoesNotTouchPoints() {
        String scope = scope("run-1");
        PlayHelpCostApplication.Charge charge = application.use(MEMBER, scope, 1, 1, 30);

        assertTrue(charge.applied());
        assertFalse(charge.alreadyUnlocked());
        assertEquals(0, charge.chargedPoints());
        assertNull(charge.businessKey());
        assertEquals(100, ledger.points(MEMBER));
        assertTrue(ledger.details.isEmpty());
        assertEquals(1, ledger.attempts.size());
        PlayAttemptLog fact = ledger.attempts.values().iterator().next();
        assertEquals(Integer.valueOf(PlayHelpCostApplication.ATTEMPT_TYPE_HELP), fact.getAttemptType());
        assertEquals(PlayHelpUsagePolicy.levelFactKey(MEMBER, scope, 1), fact.getIdempotencyKey());
        // 帮助事实不是答对：result 留空，避免被只按 result 统计的通关/质量读数当成通过。
        assertNull(fact.getResult());
    }

    @Test
    void paidHintWritesOneLedgerRowAndDeductsOnce() {
        String scope = scope("run-1");
        PlayHelpCostApplication.Charge charge = application.use(MEMBER, scope, 2, 0, 30);

        assertTrue(charge.applied());
        assertEquals(30, charge.chargedPoints());
        assertEquals(70, ledger.points(MEMBER));
        assertEquals(1, ledger.details.size());
        UmsMemberPointsDetail row = ledger.details.get(0);
        assertEquals(Long.valueOf(MEMBER), row.getMemberId());
        assertEquals(Long.valueOf(PlayHelpCostApplication.POINTS_EVENT_TYPE_HELP), row.getEventType());
        assertNull(row.getEventId(), "event_id 必须为 NULL，否则 uk_member_event_once 会按 member+node 跨局撞键");
        assertEquals(Long.valueOf(2L), row.getChangeType());
        assertEquals(Long.valueOf(30L), row.getChangePoints());
        assertEquals(Long.valueOf(100L), row.getCurrentPoints());
        assertEquals(Long.valueOf(70L), row.getAfterPoints());
        assertEquals(PlayHelpUsagePolicy.pointsBusinessKey(MEMBER, scope, 2), row.getRewardBusinessKey());
        assertEquals(charge.businessKey(), row.getRewardBusinessKey());
    }

    @Test
    void sameRunSameLevelReplayIsNotChargedTwice() {
        String scope = scope("run-1");
        application.use(MEMBER, scope, 2, 0, 30);
        PlayHelpCostApplication.Charge replay = application.use(MEMBER, scope, 2, 0, 30);

        assertFalse(replay.applied());
        assertTrue(replay.alreadyUnlocked());
        assertEquals(0, replay.chargedPoints(), "重看不产生新的扣款，本次代价必须是 0");
        assertEquals(70, ledger.points(MEMBER));
        assertEquals(1, ledger.details.size());
        assertEquals(1, ledger.attempts.size());
    }

    @Test
    void sameRunDifferentLevelIsChargedSeparately() {
        String scope = scope("run-1");
        application.use(MEMBER, scope, 2, 0, 30);
        application.use(MEMBER, scope, 3, 0, 10);

        assertEquals(60, ledger.points(MEMBER));
        assertEquals(2, ledger.details.size());
        assertEquals(2, ledger.attempts.size());
    }

    /**
     * 这是本工作位存在的理由：同一会员、同一节点，换一局必须能重新扣。
     * 用旧的 {@code uk_member_event_once(member, 14, nodeId)} 在这里就会误判成「已解锁」。
     */
    @Test
    void nextRunOnSameNodeChargesAgainInsteadOfPretendingUnlocked() {
        application.use(MEMBER, scope("run-1"), 2, 0, 30);
        PlayHelpCostApplication.Charge secondRun = application.use(MEMBER, scope("run-2"), 2, 0, 30);

        assertTrue(secondRun.applied());
        assertEquals(30, secondRun.chargedPoints());
        assertEquals(40, ledger.points(MEMBER));
        assertEquals(2, ledger.details.size());
        assertEquals(2, ledger.attempts.size());
        assertEquals(2, ledger.attempts.keySet().stream()
                .filter(key -> key.endsWith(":L2")).count());
    }

    @Test
    void sameLevelAtDifferentPriceIsAConflictNotASuccess() {
        String scope = scope("run-1");
        application.use(MEMBER, scope, 2, 0, 30);

        PlayHelpCostApplication.PlayHelpCostException conflict = assertThrows(
                PlayHelpCostApplication.PlayHelpCostException.class,
                () -> application.use(MEMBER, scope, 2, 0, 50));
        assertEquals("PLAY_HELP_LEVEL_CONFLICT", conflict.code());
        assertEquals(70, ledger.points(MEMBER));
        assertEquals(1, ledger.details.size());
    }

    @Test
    void insufficientPointsHasZeroSideEffects() {
        ledger.setPoints(MEMBER, 10);
        String scope = scope("run-1");

        PlayHelpCostApplication.PlayHelpCostException insufficient = assertThrows(
                PlayHelpCostApplication.PlayHelpCostException.class,
                () -> application.use(MEMBER, scope, 2, 0, 30));
        assertEquals("POINTS_INSUFFICIENT", insufficient.code());
        assertEquals(10, ledger.points(MEMBER));
        assertTrue(ledger.details.isEmpty());
        assertTrue(ledger.attempts.isEmpty(), "余额不足不该留下已解锁事实");
    }

    /**
     * 并发窗口里余额被别的动作花掉：deduct 抛错后适配器必须向上抛出，让外层事务回滚已写的事实与流水。
     * 单测只能证明它不吞异常、不返回成功；真实回滚由 I 在 MySQL 上验。
     */
    @Test
    void deductFailurePropagatesSoTheCallerTransactionRollsBack() {
        String scope = scope("run-1");
        when(memberService.deductPoints(MEMBER, 30))
                .thenThrow(new RuntimeException("扣除积分失败"));

        PlayHelpCostApplication.PlayHelpCostException failure = assertThrows(
                PlayHelpCostApplication.PlayHelpCostException.class,
                () -> application.use(MEMBER, scope, 2, 0, 30));
        assertEquals("POINTS_INSUFFICIENT", failure.code());
        verify(memberService).deductPoints(MEMBER, 30);
    }

    @Test
    void duplicateLedgerBusinessKeyIsRefusedInsteadOfDoubleCharging() {
        String scope = scope("run-1");
        application.use(MEMBER, scope, 2, 0, 30);
        // 抹掉事实行，模拟「事实丢失但钱已收」的异常库存：此时必须拒绝，不能按新解锁再扣一次。
        ledger.attempts.clear();

        PlayHelpCostApplication.PlayHelpCostException conflict = assertThrows(
                PlayHelpCostApplication.PlayHelpCostException.class,
                () -> application.use(MEMBER, scope, 2, 0, 30));
        assertEquals("PLAY_HELP_CHARGE_CONFLICT", conflict.code());
        assertEquals(70, ledger.points(MEMBER));
        assertEquals(1, ledger.details.size());
    }

    @Test
    void invalidInputFailsBeforeAnyWrite() {
        assertThrows(PlayHelpCostApplication.PlayHelpCostException.class,
                () -> application.use(0L, scope("run-1"), 1, 0, 0));

        PlayHelpCostApplication.PlayHelpCostException config = assertThrows(
                PlayHelpCostApplication.PlayHelpCostException.class,
                () -> application.use(MEMBER, scope("run-1"), 1, 0, -5));
        assertEquals(PlayHelpUsagePolicy.REASON_CONFIG_INVALID, config.code());

        PlayHelpCostApplication.PlayHelpCostException noRun = assertThrows(
                PlayHelpCostApplication.PlayHelpCostException.class,
                () -> application.use(MEMBER, "help1:55:66:77:ZGVmYXVsdA", 1, 0, 5));
        assertEquals(PlayHelpUsagePolicy.REASON_RUN_REQUIRED, noRun.code());

        assertTrue(ledger.attempts.isEmpty());
        assertTrue(ledger.details.isEmpty());
    }

    @Test
    void missingMemberFailsAfterLock() {
        when(members.lockMemberById(MEMBER)).thenReturn(null);
        PlayHelpCostApplication.PlayHelpCostException login = assertThrows(
                PlayHelpCostApplication.PlayHelpCostException.class,
                () -> application.use(MEMBER, scope("run-1"), 2, 0, 30));
        assertEquals("LOGIN_REQUIRED", login.code());
        assertTrue(ledger.attempts.isEmpty());
        assertTrue(ledger.details.isEmpty());
    }

    @Test
    void releaseRefusesToUnchargeAndIsNoOpWhenNothingClaimed() {
        String scope = scope("run-1");
        application.release(MEMBER, scope, 2); // 未解锁：无事可补偿

        application.use(MEMBER, scope, 2, 0, 30);
        PlayHelpCostApplication.PlayHelpCostException refused = assertThrows(
                PlayHelpCostApplication.PlayHelpCostException.class,
                () -> application.release(MEMBER, scope, 2));
        assertEquals("PLAY_HELP_RELEASE_UNSUPPORTED", refused.code());
        assertEquals(70, ledger.points(MEMBER));
        assertEquals(1, ledger.details.size());
    }

    @Test
    void legacyHintChainIsNotPollutedByHelpFacts() {
        String scope = scope("run-1");
        application.use(MEMBER, scope, 2, 0, 30);
        // 旧链用 coalesce(max(result),0) 读最高提示级；本节事实 result 为 NULL，不能被当成「已看提示」。
        assertEquals(0, attempts.selectMaxPuzzleHintLevel(MEMBER, 55L, 77L, 66L));
    }

    @Test
    void sameRequestIdAndPayloadReplaysInsteadOfChargingAgain() {
        String scope = scope("run-1");
        PlayHelpCostApplication.Charge first = application.use(MEMBER, scope, 2, 0, 30, "req-abc");
        PlayHelpCostApplication.Charge replay = application.use(MEMBER, scope, 2, 0, 30, "req-abc");

        assertTrue(first.applied());
        assertFalse(replay.applied());
        assertEquals(30, replay.chargedPoints(), "回放如实报告首次代价");
        assertEquals(70, ledger.points(MEMBER));
        assertEquals(1, ledger.details.size());
        // 一行每局每级事实 + 一行请求回执；两者都在既有唯一索引上。
        assertEquals(2, ledger.attempts.size());
    }

    /**
     * 同一个 requestId 换了层级：请求身份已属于别的动作，必须报冲突。
     * 在真实事务里，随后抛出的异常会连同已写事实一起回滚；本单测只能验证抛出的是冲突而不是成功。
     */
    @Test
    void sameRequestIdWithDifferentLevelConflicts() {
        String scope = scope("run-1");
        application.use(MEMBER, scope, 2, 0, 30, "req-1");

        PlayHelpCostApplication.PlayHelpCostException conflict = assertThrows(
                PlayHelpCostApplication.PlayHelpCostException.class,
                () -> application.use(MEMBER, scope, 3, 0, 10, "req-1"));
        assertEquals("PLAY_HELP_REQUEST_CONFLICT", conflict.code());
    }

    @Test
    void newRequestForAlreadyUnlockedLevelIsFreeReread() {
        String scope = scope("run-1");
        application.use(MEMBER, scope, 2, 0, 30, "req-1");
        PlayHelpCostApplication.Charge reread = application.use(MEMBER, scope, 2, 0, 30, "req-2");

        assertFalse(reread.applied());
        assertTrue(reread.alreadyUnlocked());
        assertEquals(0, reread.chargedPoints());
        assertEquals(70, ledger.points(MEMBER));
        assertEquals(1, ledger.details.size());
    }

    @Test
    void requestIdLongerThanTheLedgerColumnIsRejected() {
        String scope = scope("run-1");
        String tooLong = "r".repeat(PlayHelpUsagePolicy.MAX_REQUEST_ID_LENGTH + 1);
        PlayHelpCostApplication.PlayHelpCostException invalid = assertThrows(
                PlayHelpCostApplication.PlayHelpCostException.class,
                () -> application.use(MEMBER, scope, 2, 0, 30, tooLong));
        assertEquals(PlayHelpUsagePolicy.REASON_REQUEST_ID_INVALID, invalid.code());
        assertTrue(ledger.attempts.isEmpty());
        assertTrue(ledger.details.isEmpty());
    }

    @Test
    void requestReplayNeverReportsAChargeThatDidNotHappen() {
        String scope = scope("run-1");
        // 免费层：请求回执里的 chargedPoints 必须就是 0，回放不能编出一个扣款。
        application.use(MEMBER, scope, 1, 1, 30, "req-free");
        PlayHelpCostApplication.Charge replay = application.use(MEMBER, scope, 1, 1, 30, "req-free");
        assertEquals(0, replay.chargedPoints());
        assertEquals(100, ledger.points(MEMBER));
        assertTrue(ledger.details.isEmpty());
    }


    // ---------------------------------------------------------------------
    // 内存台账：模拟两条既有唯一键与 point >= cost 的条件扣减
    // ---------------------------------------------------------------------

    private static final class Ledger {
        private final Map<Long, Long> points = new LinkedHashMap<>();
        private final Map<String, PlayAttemptLog> attempts = new LinkedHashMap<>();
        private final Map<String, UmsMemberPointsDetail> detailsByKey = new LinkedHashMap<>();
        private final List<UmsMemberPointsDetail> details = new ArrayList<>();

        Ledger() {
            points.put(MEMBER, 100L);
        }

        boolean exists(long memberId) {
            return points.containsKey(memberId);
        }

        long points(long memberId) {
            return points.getOrDefault(memberId, 0L);
        }

        void setPoints(long memberId, long value) {
            points.put(memberId, value);
        }

        boolean deduct(long memberId, int cost) {
            long current = points(memberId);
            if (current < cost) throw new RuntimeException("用户积分不足，当前积分：" + current);
            points.put(memberId, current - cost);
            return true;
        }

        int insertDetail(UmsMemberPointsDetail row) {
            String key = row.getRewardBusinessKey();
            if (key != null && detailsByKey.containsKey(key)) {
                throw new DuplicateKeyException("uk_member_reward_business");
            }
            if (key != null) detailsByKey.put(key, row);
            details.add(row);
            return 1;
        }
    }

    private static final class FakeAttemptLogMapper implements PlayAttemptLogMapper {
        private final Ledger ledger;

        FakeAttemptLogMapper(Ledger ledger) {
            this.ledger = ledger;
        }

        @Override
        public int insertPlayAttemptLog(PlayAttemptLog log) {
            String key = log.getIdempotencyKey();
            if (key != null && ledger.attempts.containsKey(key)) {
                throw new DuplicateKeyException("uk_play_attempt_idempotency");
            }
            log.setId((long) (ledger.attempts.size() + 1));
            if (key != null) ledger.attempts.put(key, log);
            return 1;
        }

        @Override
        public int selectMaxPuzzleHintLevel(Long memberId, Long topicId, Long nodeId, Long activityId) {
            int max = 0;
            for (PlayAttemptLog log : ledger.attempts.values()) {
                if (PlayAttemptLog.ATTEMPT_TYPE_PUZZLE_HINT == log.getAttemptType()
                        && memberId.equals(log.getMemberId())
                        && topicId.equals(log.getTopicId())
                        && nodeId.equals(log.getNodeId())
                        && java.util.Objects.equals(activityId, log.getActivityId())) {
                    max = Math.max(max, log.getResult() == null ? 0 : log.getResult());
                }
            }
            return max;
        }

        @Override
        public List<Map<String, Object>> selectPuzzleHintLevels(Long memberId, Long topicId, Long activityId) {
            return List.of();
        }

        @Override
        public PlayAttemptLog selectByIdempotencyKey(String idempotencyKey) {
            return ledger.attempts.get(idempotencyKey);
        }

        @Override
        public PlayAttemptLog selectByIdempotencyKeyForUpdate(String idempotencyKey) {
            return ledger.attempts.get(idempotencyKey);
        }

        @Override
        public List<PlayAttemptLog> selectPreferenceResults(Long memberId, Long topicId) {
            return List.of();
        }
    }
}
