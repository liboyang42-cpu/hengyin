package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BE-16 / M.7 / G12:帮助与跳过必须逐笔对账,且旧配置零变化。
 * 阶梯只读原文 {@code hintLadder},解析复用 {@link PlayHelpUsagePolicy},不另存一份。
 */
class PlayHelpOutcomeSupportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long NODE = 7L;
    private static final long NEXT = 8L;

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PlayHelpOutcomeSupport.Rule ladder(String text) {
        return PlayHelpOutcomeSupport.ruleFromLadder(json(text));
    }

    private static Map<Long, Integer> bases(int base) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        map.put(NODE, base);
        map.put(NEXT, base);
        return map;
    }

    /** 原文层级:L1 文字免费、L2 图片 20 分、L3 字母 30 分、L5 跳过。 */
    private static final String LADDER = "["
            + "{\"level\":1,\"type\":\"TEXT\",\"payload\":{\"text\":\"看时间\"},\"freeQuota\":1},"
            + "{\"level\":2,\"type\":\"IMAGE\",\"cost\":{\"points\":20}},"
            + "{\"level\":3,\"type\":\"LETTER\",\"cost\":{\"points\":30}},"
            + "{\"level\":4,\"type\":\"SKIP\"}]";

    @Test
    void topicsWithoutALadderKeepOldScoring() {
        assertFalse(PlayHelpOutcomeSupport.ruleFromLadder(null).enabled());
        assertFalse(ladder("[]").enabled());
        assertFalse(ladder("null").enabled());
        PlayHelpOutcomeSupport.Ledger legacy =
                new PlayHelpOutcomeSupport.Ledger(PlayHelpOutcomeSupport.ruleFromLadder(null), bases(100));
        assertFalse(legacy.solve(NODE, "r1").accepted());
        assertFalse(legacy.useHelp(NODE, 1, "h1").accepted());
        assertFalse(legacy.skip(NODE, "s1").accepted());
        assertEquals(0, legacy.netScore(), "旧主题不产生任何新账本行");
    }

    @Test
    void netScoreReconcilesBaseAwardedHelpAndSkip() {
        PlayHelpOutcomeSupport.Ledger ledger =
                new PlayHelpOutcomeSupport.Ledger(ladder(LADDER), bases(100));

        assertTrue(ledger.useHelp(NODE, 1, "h1").applied());
        assertEquals(0, ledger.helpDeductionTotal(), "freeQuota=1 的层首次免费");

        assertTrue(ledger.useHelp(NODE, 2, "h2").applied());
        assertTrue(ledger.useHelp(NODE, 3, "h3").applied());
        // 提示代价只花**积分**，不扣解谜分（用户定稿：解谜分与积分余额分开）。
        assertEquals(50, ledger.helpPointsChargedTotal(), "20 + 30 积分");
        assertEquals(0, ledger.helpDeductionTotal(), "提示不扣解谜分");

        assertTrue(ledger.solve(NODE, "s1").applied());
        assertTrue(ledger.award(15, "STREAK", "a1").applied());

        assertEquals(100, ledger.baseTotal());
        assertEquals(15, ledger.awardedTotal());
        assertEquals(0, ledger.skipDeductionTotal());
        assertEquals(ledger.baseTotal() + ledger.awardedTotal()
                - ledger.helpDeductionTotal() - ledger.skipDeductionTotal(), ledger.netScore());
        // 解谜分 = 100 基准 + 15 加成 − 0（提示只花积分，不扣解谜分）− 0 = 115
        assertEquals(115, ledger.netScore());
        assertEquals(50, ledger.helpPointsChargedTotal(), "积分另账，不回填进解谜分");
        assertFalse(ledger.assistedComplete(NODE), "只用到文字/图片/字母层,不算辅助完成");
    }

    @Test
    void repeatedRequestReplaysAndNeverDeductsTwice() {
        PlayHelpOutcomeSupport.Ledger ledger = new PlayHelpOutcomeSupport.Ledger(
                ladder("[{\"level\":1,\"type\":\"TEXT\",\"cost\":{\"points\":30}}]"), bases(100));
        PlayHelpOutcomeSupport.Apply first = ledger.useHelp(NODE, 1, "same-request");
        PlayHelpOutcomeSupport.Apply replay = ledger.useHelp(NODE, 1, "same-request");
        assertTrue(first.applied());
        assertTrue(replay.accepted());
        assertTrue(replay.replayed());
        assertFalse(replay.applied());
        assertEquals(30, ledger.helpPointsChargedTotal());
        assertEquals(0, ledger.helpDeductionTotal());
        assertEquals(1, ledger.entries().size(), "重复请求不留第二笔账");
    }

    @Test
    void reReadingAnUnlockedLevelCostsNothingExtra() {
        PlayHelpOutcomeSupport.Ledger ledger = new PlayHelpOutcomeSupport.Ledger(
                ladder("[{\"level\":1,\"type\":\"TEXT\",\"cost\":{\"points\":10}}]"), bases(100));
        assertTrue(ledger.useHelp(NODE, 1, "h1").applied());
        PlayHelpOutcomeSupport.Apply reread = ledger.useHelp(NODE, 1, "h2");
        assertTrue(reread.replayed());
        assertEquals(10, ledger.helpPointsChargedTotal(), "重看不懂不另扣");
    }

    @Test
    void levelsUnlockInLadderOrderNotBySkippingAhead() {
        PlayHelpOutcomeSupport.Ledger ledger =
                new PlayHelpOutcomeSupport.Ledger(ladder(LADDER), bases(100));
        PlayHelpOutcomeSupport.Apply ahead = ledger.useHelp(NODE, 2, "h2");
        assertFalse(ahead.accepted());
        assertEquals(PlayHelpOutcomeSupport.LEVEL_OUT_OF_ORDER, ahead.rejectionCode());
        assertEquals(0, ledger.helpPointsChargedTotal());
        assertEquals(PlayHelpOutcomeSupport.LEVEL_NOT_CONFIGURED,
                ledger.useHelp(NODE, 9, "h9").rejectionCode());
    }

    @Test
    void skipScoresZeroAndCarriesNoSuccessProof() {
        PlayHelpOutcomeSupport.Ledger ledger =
                new PlayHelpOutcomeSupport.Ledger(ladder(LADDER), bases(100));
        assertTrue(ledger.skip(NODE, "skip-1").applied());
        assertEquals(100, ledger.baseTotal());
        assertEquals(100, ledger.skipDeductionTotal());
        assertEquals(0, ledger.netScore(), "跳过 = 本题 0 分");
        assertEquals(0, ledger.awardedTotal(), "跳过不发任何成功奖励");
        assertEquals(0, ledger.nodeScore(NODE));
        assertTrue(ledger.assistedComplete(NODE));
        assertEquals(PlayHelpOutcomeSupport.COMPLETION_SKIPPED, ledger.completionMode(NODE));
        assertEquals(1, ledger.entries().size());
        assertEquals(PlayHelpOutcomeSupport.KIND_SKIP, ledger.entries().get(0).kind());
    }

    @Test
    void skippingOneQuestionLeavesLaterQuestionsReachable() {
        PlayHelpOutcomeSupport.Ledger ledger =
                new PlayHelpOutcomeSupport.Ledger(ladder(LADDER), bases(100));
        ledger.skip(NODE, "skip-1");
        assertTrue(ledger.solve(NEXT, "solve-2").applied(), "跳过不是失败,后续节点照常可达");
        assertEquals(100, ledger.skipDeductionTotal(), "只扣被跳过那一题");
        assertEquals(100, ledger.netScore());
        assertTrue(ledger.assistedComplete(NODE));
        assertFalse(ledger.assistedComplete(NEXT));
    }

    @Test
    void skipTwiceOnTheSameNodeIsRejected() {
        PlayHelpOutcomeSupport.Ledger ledger =
                new PlayHelpOutcomeSupport.Ledger(ladder(LADDER), bases(100));
        assertTrue(ledger.skip(NODE, "skip-1").applied());
        PlayHelpOutcomeSupport.Apply second = ledger.skip(NODE, "skip-2");
        assertFalse(second.accepted());
        assertEquals(PlayHelpOutcomeSupport.NODE_ALREADY_CLOSED, second.rejectionCode());
        assertEquals(100, ledger.skipDeductionTotal());
    }

    @Test
    void skipRequiresASkipLevelInTheLadder() {
        PlayHelpOutcomeSupport.Ledger ledger = new PlayHelpOutcomeSupport.Ledger(
                ladder("[{\"level\":1,\"type\":\"TEXT\"}]"), bases(100));
        assertEquals(PlayHelpOutcomeSupport.SKIP_DISABLED, ledger.skip(NODE, "skip-1").rejectionCode());
        assertEquals(0, ledger.netScore());
    }

    @Test
    void gemsAndCoinsLevelsAreRejectedAsUnusableNotCharged() {
        PlayHelpOutcomeSupport.Rule rule = ladder("["
                + "{\"level\":1,\"type\":\"TEXT\",\"cost\":{\"gems\":2}},"
                + "{\"level\":2,\"type\":\"IMAGE\",\"cost\":{\"coins\":1}}]");
        PlayHelpOutcomeSupport.Ledger ledger = new PlayHelpOutcomeSupport.Ledger(rule, bases(100));
        PlayHelpOutcomeSupport.Apply gems = ledger.useHelp(NODE, 1, "h1");
        assertFalse(gems.accepted(), "没有真实账本就不能解锁");
        assertEquals(PlayHelpUsagePolicy.REASON_GEMS_LEDGER_ABSENT, gems.rejectionCode());
        assertEquals(PlayHelpUsagePolicy.REASON_GEMS_LEDGER_ABSENT, gems.reason());
        PlayHelpOutcomeSupport.Apply coins = ledger.useHelp(NODE, 2, "h2");
        assertEquals(PlayHelpUsagePolicy.REASON_COINS_LEDGER_ABSENT, coins.rejectionCode());
        assertEquals(0, ledger.helpPointsChargedTotal(), "不做假扣款");
        assertEquals(0, ledger.helpDeductionTotal());
    }

    @Test
    void locationToolsStayUnavailable() {
        PlayHelpOutcomeSupport.Ledger ledger = new PlayHelpOutcomeSupport.Ledger(
                ladder("[{\"level\":1,\"type\":\"LOCATION\"}]"), bases(100));
        PlayHelpOutcomeSupport.Apply outcome = ledger.useHelp(NODE, 1, "h1");
        assertFalse(outcome.accepted());
        assertEquals(PlayHelpUsagePolicy.REASON_LOCATION_NOT_WIRED, outcome.rejectionCode());
    }

    @Test
    void pointChargesAreSeparateFromTheQuestionScore() {
        PlayHelpOutcomeSupport.Receipt receipt = PlayHelpOutcomeSupport.assemble(100, 0, 0, null,
                List.of(new PlayHelpOutcomeSupport.LevelEvent(1, "TEXT", false, 60),
                        new PlayHelpOutcomeSupport.LevelEvent(2, "IMAGE", false, 60)), false, false);
        assertEquals(120, receipt.pointsCharged());
        assertEquals(0, receipt.helpDeduction());
        assertEquals(100, receipt.netScore());
    }

    @Test
    void awardsRequireAReasonAndDoNotRepeat() {
        PlayHelpOutcomeSupport.Ledger ledger =
                new PlayHelpOutcomeSupport.Ledger(ladder(LADDER), bases(100));
        assertEquals(PlayHelpOutcomeSupport.BAD_REQUEST, ledger.award(15, " ", "a1").rejectionCode());
        assertEquals(PlayHelpOutcomeSupport.BAD_REQUEST, ledger.award(0, "STREAK", "a2").rejectionCode());
        assertTrue(ledger.award(15, "STREAK", "a3").applied());
        assertTrue(ledger.award(15, "STREAK", "a3").replayed());
        assertEquals(15, ledger.awardedTotal());
    }

    @Test
    void unscoredAndUnknownNodesAreRejected() {
        PlayHelpOutcomeSupport.Ledger ledger =
                new PlayHelpOutcomeSupport.Ledger(ladder(LADDER), bases(100));
        assertEquals(PlayHelpOutcomeSupport.NODE_NOT_SCORED, ledger.solve(999L, "s1").rejectionCode());
        assertEquals(PlayHelpOutcomeSupport.BAD_REQUEST, ledger.solve(NODE, " ").rejectionCode());
    }

    @Test
    void perNodeScoreFeedsTheExistingPuzzleScoreColumn() {
        PlayHelpOutcomeSupport.Ledger ledger =
                new PlayHelpOutcomeSupport.Ledger(ladder(LADDER), bases(100));
        ledger.useHelp(NODE, 1, "h1");
        ledger.useHelp(NODE, 2, "h2");
        assertTrue(ledger.solve(NODE, "s1").applied());
        assertEquals(100, ledger.nodeScore(NODE), "提示花积分，不扣解谜分");
        assertEquals("SOLVED", ledger.completionMode(NODE));

        assertEquals(0, ledger.nodeScore(NEXT), "未完成节点不计分");
        assertNull(ledger.completionMode(NEXT));
        assertEquals(0, ledger.nodeScore(999L));
    }

    @Test
    void revealingTheAnswerMarksAssistedCompletion() {
        PlayHelpOutcomeSupport.Ledger ledger = new PlayHelpOutcomeSupport.Ledger(
                ladder("[{\"level\":1,\"type\":\"ANSWER\",\"freeQuota\":1}]"), bases(100));
        assertTrue(ledger.useHelp(NODE, 1, "h1").applied());
        assertTrue(ledger.assistedComplete(NODE), "揭示答案 = 辅助完成");
        assertTrue(ledger.solve(NODE, "s1").applied());
        assertEquals("REVEALED", ledger.completionMode(NODE), "沿用既有完成方式取值");
        assertEquals(100, ledger.nodeScore(NODE), "免费揭示不扣分,但完成方式已留痕");
    }

    @Test
    void receiptCarriesTheExactBreakdownForTheImmediateReply() {
        PlayHelpOutcomeSupport.Ledger ledger =
                new PlayHelpOutcomeSupport.Ledger(ladder(LADDER), bases(100));
        ledger.useHelp(NODE, 1, "h1");
        ledger.useHelp(NODE, 2, "h2");
        ledger.solve(NODE, "s1");
        ledger.skip(NEXT, "skip-2");
        Map<String, Object> view = ledger.receiptView();
        assertNotNull(view);
        assertEquals(200, view.get("baseScore"));
        assertEquals(0, view.get("awardedBonus"));
        assertEquals(0, view.get("helpDeduction"));
        assertEquals(20, view.get("helpPointsCharged"));
        assertEquals(100, view.get("skipDeduction"));
        assertEquals(100, view.get("netScore"));
        assertTrue(view.containsKey("events"));
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> events = (java.util.List<Map<String, Object>>) view.get("events");
        assertEquals(4, events.size(), "逐笔事件可复算");
        assertEquals(100, ledger.nodeScore(NODE));
        assertEquals(0, ledger.nodeScore(NEXT));
    }

    // ---------------------------------------------------------------- UG07 成绩明细（assemble）

    private static PlayHelpOutcomeSupport.LevelEvent level(int level, String type, boolean free, int points) {
        return new PlayHelpOutcomeSupport.LevelEvent(level, type, free, points);
    }

    /** 有基准 + 用过一级提示(旧链降档) + 有加成：四项可逐笔复算。 */
    @Test
    void receiptSplitsBaseBonusHelpAndSkipWithTheExistingScorePolicy() {
        PlayHelpOutcomeSupport.Receipt receipt = PlayHelpOutcomeSupport.assemble(
                100, 1, 15, "STREAK", List.of(level(1, "TEXT", false, 30)), false, false);

        assertEquals(PlayHelpOutcomeSupport.SCORE_SCORED, receipt.scoreState());
        assertEquals(100, receipt.baseScore().intValue());
        assertEquals(15, receipt.bonus());
        assertEquals("STREAK", receipt.bonusSource());
        // 旧链 hintLevel=1 → solvedScore=70，因此提示降档 30 分（与 PuzzleScorePolicy 同源）
        assertEquals(30, receipt.helpDeduction());
        assertEquals(0, receipt.skipDeduction());
        assertEquals(85, receipt.netScore().intValue());
        // 积分与解谜分两本账：这 30 是**积分**消费，不进 netScore
        assertEquals(30, receipt.pointsCharged());
        assertEquals(1, receipt.levels().size());
    }

    /** 没有加成来源就不编造加成。 */
    @Test
    void receiptWithoutAnAuthoritativeBonusSourceReportsZeroAndNoFakeReason() {
        PlayHelpOutcomeSupport.Receipt receipt = PlayHelpOutcomeSupport.assemble(
                100, 0, 0, null, List.of(), false, false);

        assertEquals(0, receipt.bonus());
        assertEquals("NONE", receipt.bonusSource());
        assertEquals(100, receipt.netScore().intValue());
    }

    /** 跳过 = 本题基准分扣满 → 净分 0，但仍保留积分消费与辅助完成标记。 */
    @Test
    void receiptAfterSkipScoresZeroAndKeepsPointsSeparate() {
        PlayHelpOutcomeSupport.Receipt receipt = PlayHelpOutcomeSupport.assemble(
                100, 0, 0, null, List.of(level(1, "SKIP", false, 0)), true, false);

        assertEquals(100, receipt.skipDeduction());
        assertEquals(0, receipt.netScore().intValue());
        assertTrue(receipt.skipped());
        assertTrue(receipt.assisted());
        assertEquals(0, receipt.pointsCharged(), "跳过不收积分");
    }

    /** 揭示答案 = 辅助完成，但不等于跳过：不扣满基准分。 */
    @Test
    void receiptAfterAnswerRevealIsAssistedButNotSkipped() {
        PlayHelpOutcomeSupport.Receipt receipt = PlayHelpOutcomeSupport.assemble(
                100, 1, 0, null, List.of(level(1, "ANSWER", false, 0)), false, true);

        assertFalse(receipt.skipped());
        assertTrue(receipt.assisted());
        assertEquals(0, receipt.skipDeduction());
        assertEquals(70, receipt.netScore().intValue());
    }

    /** 没有权威计分基准：分数为 null（不是 0），但积分消费与层使用如实给出。 */
    @Test
    void receiptWithoutAuthoritativeBaseScoreIsExplicitlyNotScored() {
        PlayHelpOutcomeSupport.Receipt receipt = PlayHelpOutcomeSupport.assemble(
                null, 0, 0, null, List.of(level(2, "IMAGE", false, 20)), false, false);

        assertEquals(PlayHelpOutcomeSupport.SCORE_NOT_SCORED, receipt.scoreState());
        assertNull(receipt.baseScore());
        assertNull(receipt.netScore(), "没有基准就不给假分数");
        assertEquals(0, receipt.helpDeduction());
        assertEquals(0, receipt.skipDeduction());
        assertEquals(20, receipt.pointsCharged(), "积分消费仍要如实给出");
        assertEquals(1, receipt.levels().size());
    }

    /** 没有基准时跳过：辅助完成标记要在，但不产生假分数。 */
    @Test
    void receiptNotScoredStillCarriesTheSkipAssistedMarker() {
        PlayHelpOutcomeSupport.Receipt receipt = PlayHelpOutcomeSupport.assemble(
                null, 0, 0, null, List.of(), true, false);

        assertEquals(PlayHelpOutcomeSupport.SCORE_NOT_SCORED, receipt.scoreState());
        assertNull(receipt.netScore());
        assertTrue(receipt.skipped());
        assertTrue(receipt.assisted());
    }

    /** 提示等级越高降档越多，且永不超过基准分。 */
    @Test
    void receiptHintDeductionNeverExceedsTheBaseScore() {
        assertEquals(30, PlayHelpOutcomeSupport.assemble(100, 1, 0, null, List.of(), false, false).helpDeduction());
        assertEquals(60, PlayHelpOutcomeSupport.assemble(100, 2, 0, null, List.of(), false, false).helpDeduction());
        PlayHelpOutcomeSupport.Receipt deep =
                PlayHelpOutcomeSupport.assemble(100, 99, 0, null, List.of(), false, false);
        assertEquals(60, deep.helpDeduction());
        assertEquals(40, deep.netScore().intValue());
    }

    @Test
    void ladderOrderPreservesAuthorOrderWithoutInventingLevels() {
        PlayHelpOutcomeSupport.Rule rule = ladder("["
                + "{\"level\":5,\"type\":\"TEXT\"},{\"level\":9,\"type\":\"IMAGE\"}]");
        assertEquals(2, rule.levels().size());
        assertEquals(5, rule.levels().get(0).level());
        assertEquals(9, rule.levels().get(1).level());
        PlayHelpOutcomeSupport.Ledger ledger = new PlayHelpOutcomeSupport.Ledger(rule, bases(100));
        assertTrue(ledger.useHelp(NODE, 5, "h5").applied(), "位次优先,不要求数字连续");
        assertTrue(ledger.useHelp(NODE, 9, "h9").applied());
        assertEquals(0, ledger.helpPointsChargedTotal(), "两级都未配代价");
        assertEquals(0, ledger.helpDeductionTotal());
    }
}
