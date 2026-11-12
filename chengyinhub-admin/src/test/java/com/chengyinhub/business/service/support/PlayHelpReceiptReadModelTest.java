package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * UG07 成绩明细的**实际读取路径**：只从 {@code play_attempt_log} 的既有事实行回放。
 *
 * <p>这一层测三件生产上必须成立的事：
 * <ol>
 *   <li>明细是"读"出来的，不是写出来的 —— 同一批事实读两次结果一致，重试不会多出事件；</li>
 *   <li>积分（chargedPoints）与解谜分（helpDeduction/netScore）严格分列；</li>
 *   <li>没有权威基准分时明确 {@code NOT_SCORED}、分数为 null，但层使用与积分照给。</li>
 * </ol>
 */
class PlayHelpReceiptReadModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MEMBER = 9001L;
    private static final long TOPIC = 55L;
    private static final long ACTIVITY = 66L;
    private static final long NODE = 701L;
    private static final String INSTANCE = "run-uuid-0001";

    private PlayAttemptLogMapper attempts;
    private PlayHelpReceiptReadModel model;

    @BeforeEach
    void setUp() {
        attempts = mock(PlayAttemptLogMapper.class);
        model = new PlayHelpReceiptReadModel(attempts);
    }

    private static PlayHelpUsagePolicy.Scope scope() {
        return new PlayHelpUsagePolicy.Scope(TOPIC, ACTIVITY, NODE, INSTANCE);
    }

    /** 帮助事实行：只写 G5 真会写的字段（free / chargedPoints）。 */
    private static PlayAttemptLog helpFact(long member, int level, boolean free, int charged) {
        PlayAttemptLog row = new PlayAttemptLog();
        row.setMemberId(member);
        row.setAttemptType(PlayHelpCostApplication.ATTEMPT_TYPE_HELP);
        row.setResultJson("{\"schema\":\"play-help-fact:v1\",\"level\":" + level
                + ",\"free\":" + free + ",\"chargedPoints\":" + (free ? 0 : charged) + "}");
        return row;
    }

    private void givenFact(int level, PlayAttemptLog row) {
        when(attempts.selectByIdempotencyKey(
                PlayHelpUsagePolicy.levelFactKey(MEMBER, PlayHelpUsagePolicy.scopeKey(scope()), level)))
                .thenReturn(row);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> levels(Map<String, Object> view) {
        return (List<Map<String, Object>>) view.get("levels");
    }

    private static String ladderJson() throws Exception {
        return "[{\"level\":1,\"type\":\"TEXT\",\"payload\":\"看屋檐\"},"
                + "{\"level\":2,\"type\":\"IMAGE\",\"cost\":{\"points\":20}}]";
    }

    /** 用过一级提示（免费）+ 二级（扣 20 积分）：积分与解谜分分列，且不重复计数。 */
    @Test
    void levelFactsBecomeTheReceiptEventsWithPointsSeparatedFromScore() throws Exception {
        givenFact(1, helpFact(MEMBER, 1, true, 0));
        givenFact(2, helpFact(MEMBER, 2, false, 20));

        Map<String, Object> view = model.receipt(MEMBER, scope(),
                JSON.readTree(ladderJson()), 100, false, false);

        assertEquals(PlayHelpOutcomeSupport.SCORE_SCORED, view.get("scoreState"));
        assertEquals(100, view.get("baseScore"));
        assertEquals(20, view.get("pointsCharged"), "只累计真实积分消费");
        assertEquals(0, view.get("helpDeduction"), "本次没有既有提示等级降档");
        assertEquals(100, view.get("netScore"));
        assertEquals(2, levels(view).size());
        assertEquals(1, levels(view).get(0).get("level"));
        assertEquals(true, levels(view).get(0).get("free"));
        assertEquals(20, levels(view).get(1).get("pointsCharged"));
    }

    /** 同一批事实读两次结果完全一致：明细是回放，不是累加。 */
    @Test
    void readingTwiceReplaysTheSameFactsWithoutDuplicatingEvents() throws Exception {
        givenFact(1, helpFact(MEMBER, 1, false, 30));

        Map<String, Object> first = model.receipt(MEMBER, scope(),
                JSON.readTree(ladderJson()), 100, false, false);
        Map<String, Object> second = model.receipt(MEMBER, scope(),
                JSON.readTree(ladderJson()), 100, false, false);

        assertEquals(1, levels(first).size());
        assertEquals(1, levels(second).size(), "重试/重进不多出事件");
        assertEquals(first.get("pointsCharged"), second.get("pointsCharged"));
        assertEquals(first.get("netScore"), second.get("netScore"));
    }

    /** 没有权威基准（高级玩法/自由收集）：NOT_SCORED、分数 null，但积分与层记录照给。 */
    @Test
    void withoutAuthoritativeBaseScoreTheReceiptIsExplicitlyNotScored() throws Exception {
        givenFact(2, helpFact(MEMBER, 2, false, 20));

        Map<String, Object> view = model.receipt(MEMBER, scope(),
                JSON.readTree(ladderJson()), null, false, false);

        assertEquals(PlayHelpOutcomeSupport.SCORE_NOT_SCORED, view.get("scoreState"));
        assertNull(view.get("baseScore"));
        assertNull(view.get("netScore"), "没有基准就不给假分数");
        assertEquals(PlayHelpReceiptReadModel.NO_BASE_SCORE, view.get("reason"));
        assertEquals(20, view.get("pointsCharged"));
        assertEquals(1, levels(view).size());
    }

    /** 跳过：净分 0，辅助完成标记在，仍不给假分数以外的任何东西。 */
    @Test
    void skippedNodeScoresZeroAndIsMarkedAssisted() throws Exception {
        Map<String, Object> view = model.receipt(MEMBER, scope(),
                JSON.readTree(ladderJson()), 100, true, false);

        assertEquals(100, view.get("skipDeduction"));
        assertEquals(0, view.get("netScore"));
        assertEquals(true, view.get("skipped"));
        assertEquals(true, view.get("assisted"));
    }

    /** 别人写的同键事实不能算到本人头上。 */
    @Test
    void aFactOwnedByAnotherMemberIsNotCountedHere() throws Exception {
        givenFact(1, helpFact(MEMBER + 1, 1, false, 30));

        Map<String, Object> view = model.receipt(MEMBER, scope(),
                JSON.readTree(ladderJson()), 100, false, false);

        assertEquals(0, levels(view).size(), "异会员的事实不进我的明细");
        assertEquals(0, view.get("pointsCharged"));
    }

    /** 非帮助类事实（例如答题日志）同键也不算层级使用。 */
    @Test
    void aNonHelpAttemptWithTheSameKeyIsIgnored() throws Exception {
        PlayAttemptLog answer = helpFact(MEMBER, 1, false, 30);
        answer.setAttemptType(PlayAttemptLog.ATTEMPT_TYPE_ANSWER);
        givenFact(1, answer);

        Map<String, Object> view = model.receipt(MEMBER, scope(),
                JSON.readTree(ladderJson()), 100, false, false);

        assertEquals(0, levels(view).size());
    }

    /** 旧链提示等级参与解谜分降档（100 → 70）：与 PuzzleScorePolicy 同一来源。 */
    @Test
    void legacyHintLevelStillDrivesTheExistingScoreReduction() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nodeId", NODE);
        row.put("hintLevel", 1);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row);
        when(attempts.selectPuzzleHintLevels(MEMBER, TOPIC, ACTIVITY)).thenReturn(rows);

        Map<String, Object> view = model.receipt(MEMBER, scope(),
                JSON.readTree(ladderJson()), 100, false, false);

        assertEquals(30, view.get("helpDeduction"), "100 - 70，沿用既有策略");
        assertEquals(70, view.get("netScore"));
    }

    /** 旧局没有阶梯也不能崩：返回可用的空层列表。 */
    @Test
    void absentLadderYieldsNoLevelEventsButStillReportsScore() throws Exception {
        Map<String, Object> view = model.receipt(MEMBER, scope(), null, 100, false, false);

        assertEquals(0, levels(view).size());
        assertEquals(100, view.get("netScore"));
        assertNull(view.get("reason"));
    }

    /** 高级局作用域也能读：kind 只影响键前缀，不影响明细结构。 */
    @Test
    void advancedScopeProducesTheSameShape() throws Exception {
        PlayHelpUsagePolicy.Scope advanced =
                PlayHelpUsagePolicy.Scope.advanced(TOPIC, ACTIVITY, NODE, 4242L);
        when(attempts.selectByIdempotencyKey(any())).thenReturn(null);

        Map<String, Object> view = model.receipt(MEMBER, advanced, JSON.readTree(ladderJson()),
                null, true, false);

        assertEquals(PlayHelpOutcomeSupport.SCORE_NOT_SCORED, view.get("scoreState"));
        assertEquals(true, view.get("skipped"));
        assertEquals(true, view.get("assisted"));
        assertEquals(PlayHelpUsagePolicy.RunKind.ADVANCED_GAME, advanced.kind());
    }
}
