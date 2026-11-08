package com.chengyinhub.business.service.support;

import com.chengyinhub.business.util.PuzzleScorePolicy;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BE-16 / M.7 / G12:帮助(提示)与跳过在本局产生的**成绩事实**,纯计算、无 IO。
 *
 * <p>原文依据(私有施工文档 v1.3.2,只读):
 * <ul>
 *   <li>BE-16:{@code hintLadder:[{level,type,payload,cost:{points|gems|coins},freeQuota}]};
 *       服务端下发「本局可用层级 + 剩余免费额度 + 每次代价」;「跳过则本题记 0」;
 *       「无阶梯配置即保持旧行为」。</li>
 *   <li>M.7:L1 文字 → L2 图片 → L3 字母/数字 → L4 揭示答案 → L5 跳过 → L6 位置工具;
 *       「Skipping a challenge scores it 0」;分数即榜单分数(榜单口径改动未获裁定,见类尾说明)。</li>
 *   <li>G12:同一动作只落一次账、一次次数、一次进度;跳过不制造答对、到达、扫码或支付证明;
 *       揭晓与跳过保留不同事实;旧局用旧版本规则。</li>
 * </ul>
 *
 * <p>本类**不解析配置**:层级的可读性判定(未知层型、宝石/金币无账本、位置工具未接线、免费额度)
 * 全部由 G5 {@link PlayHelpUsagePolicy} 负责;本类只消费它的 {@link PlayHelpUsagePolicy.LevelSpec},
 * 避免出现第二份阶梯解析。{@code hintLadder} 缺失或为空时 {@link #ruleFromLadder} 返回 disabled,
 * 调用方直接走既有 {@code PuzzlePlayService} 旧链 —— 旧题免费重答、旧计分、旧局冻结全部不变。
 *
 * <p>不产生任何编造数值:基准分由调用方给出,代价只取配置里的 {@code cost.points},
 * {@code hint_probe.sh} 的 300/站与 30/提示是模型参数,不进入本类。
 */
public final class PlayHelpOutcomeSupport {

    /** 账本事件类型。 */
    public static final String KIND_SOLVE = "SOLVE";
    public static final String KIND_SKIP = "SKIP";
    public static final String KIND_HELP = "HELP";
    public static final String KIND_AWARD = "AWARD";

    /**
     * 跳过留痕。唯一真源是 {@link PuzzleScorePolicy#COMPLETION_SKIPPED} —— 这里只是别名,
     * 不再另存一个字面量:两处各写一份,迟早有一处漂成 SOLVED,「跳过」就会被读成答对。
     */
    public static final String COMPLETION_SKIPPED = PuzzleScorePolicy.COMPLETION_SKIPPED;

    public static final String HELP_DISABLED = "HELP_DISABLED";
    public static final String LEVEL_NOT_CONFIGURED = "LEVEL_NOT_CONFIGURED";
    public static final String LEVEL_OUT_OF_ORDER = "LEVEL_OUT_OF_ORDER";
    public static final String NODE_NOT_SCORED = "NODE_NOT_SCORED";
    public static final String NODE_ALREADY_CLOSED = "NODE_ALREADY_CLOSED";
    public static final String SKIP_DISABLED = "SKIP_DISABLED";
    public static final String DEDUCTION_EXCEEDS_NODE_BASE = "DEDUCTION_EXCEEDS_NODE_BASE";
    public static final String BAD_REQUEST = "BAD_REQUEST";

    /** 一次动作的结果。{@code reason} 是 G5 给出的层级不可用原因(如宝石/金币无账本)。 */
    public record Apply(boolean applied, boolean replayed, String rejectionCode, String reason) {
        public boolean accepted() {
            return rejectionCode == null;
        }
    }

    /**
     * 规则 = 已解析的层级表。{@code levels} 为空即 disabled。
     * 跳过只有在作者配了 {@code SKIP} 层时才可用(BE-16 的 L5 是阶梯的一层,不另设开关)。
     */
    public record Rule(List<PlayHelpUsagePolicy.LevelSpec> levels) {
        static Rule disabled() {
            return new Rule(List.of());
        }

        public boolean enabled() {
            return !levels.isEmpty();
        }

        public boolean skipConfigured() {
            for (PlayHelpUsagePolicy.LevelSpec spec : levels) {
                if (PlayHelpUsagePolicy.TYPE_SKIP.equals(spec.type())) {
                    return true;
                }
            }
            return false;
        }

        /** 按层级号取配置;找不到返回 null。 */
        public PlayHelpUsagePolicy.LevelSpec spec(int level) {
            for (PlayHelpUsagePolicy.LevelSpec spec : levels) {
                if (spec.level() == level) {
                    return spec;
                }
            }
            return null;
        }

        /** 该层在阶梯里的位次(0 起);找不到返回 -1。逐级解锁按位次判定,不按数字是否连续。 */
        public int position(int level) {
            for (int i = 0; i < levels.size(); i++) {
                if (levels.get(i).level() == level) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * 账本一行。
     *
     * <p><b>两个维度必须分开</b>（用户 2026-11-08 定稿「提示只免费 + 现有可兑换积分」，
     * 且「解谜分与积分余额分开」）：
     * <ul>
     *   <li>{@code scoreDeduction} —— 扣**解谜分**（跳过 = 本题基准分；提示**不扣**解谜分）。</li>
     *   <li>{@code pointsCharged} —— 扣**可兑换积分余额**（G5 的真实流水；0 = 免费或零代价）。</li>
     * </ul>
     * 把积分写进 {@code scoreDeduction} 就等于"用积分当解谜分"，两本账会被合并成一本，
     * 排行榜与余额都会算错 —— 这是本类此前必须纠正的语义缺陷。
     */
    public record Entry(String kind, long nodeId, int level, boolean freeUse,
                        int base, int awarded, int scoreDeduction, int pointsCharged, String requestId) {
    }

    private PlayHelpOutcomeSupport() {
    }
    /**
     * 一次已发生的层级使用（由服务端事实行回放得出，不由客户端上报）。
     *
     * @param level         层级号
     * @param type          层级形式
     * @param free          首次使用时是否免费
     * @param pointsCharged 本次真实扣掉的**可兑换积分**
     */
    public record LevelEvent(int level, String type, boolean free, int pointsCharged) {
    }

    /** 成绩状态：有权威基准可分账 vs 该玩法本来就不计分。 */
    public static final String SCORE_SCORED = "SCORED";
    public static final String SCORE_NOT_SCORED = "NOT_SCORED";

    /**
     * 玩家可读的本局成绩明细（完成回包与重进只读回执**同一份计算**）。
     *
     * @param baseScore      本题基准分；{@code null} 表示该玩法没有权威计分基准
     * @param bonus          已获加成（只有原规则真给出时才非 0）
     * @param bonusSource    加成来源；没有来源时为 {@code NONE}，不编造
     * @param helpDeduction  提示扣掉的解谜分（来自 {@link PuzzleScorePolicy} 的既有口径）
     * @param skipDeduction  跳过扣掉的解谜分（= 本题基准分，跳过记 0）
     * @param netScore       净得分；{@code null} = 无权威基准，不做假分数
     * @param pointsCharged  提示实际扣掉的积分（与解谜分两本账）
     * @param levels         逐层使用记录（同层重看不重复）
     * @param skipped        是否已跳过
     * @param assisted       是否辅助完成（跳过或揭示过答案）
     * @param scoreState     {@link #SCORE_SCORED} / {@link #SCORE_NOT_SCORED}
     */
    public record Receipt(Integer baseScore, int bonus, String bonusSource,
                          int helpDeduction, int skipDeduction, Integer netScore,
                          int pointsCharged, List<LevelEvent> levels,
                          boolean skipped, boolean assisted, String scoreState) {
    }

    /**
     * 由服务端事实生成成绩明细。**纯计算**：调用方给的都是已经落库的事实，
     * 客户端上报的 score/passed 一个都不进这里。
     *
     * <p>扣分只有两个来源，且都沿用既有实现：
     * <ul>
     *   <li>提示降解谜分：{@code baseScore - PuzzleScorePolicy.solvedScore(hintLevel)}，
     *       即旧链 100 / 70 / 40 的同一份口径（{@code hintLevel} 来自 play_attempt_log 的提示记录）。</li>
     *   <li>跳过：扣满本题基准分 → 本题 0 分（M.7「跳过记 0」）。</li>
     * </ul>
     * 没有基准分就不算分（{@link #SCORE_NOT_SCORED}），但仍如实给出积分消费与层使用记录。
     *
     * @param baseScore      本题基准分；{@code null} = 该玩法不计分
     * @param hintLevel      既有提示等级（0/1/2…），用于解谜分降档；无提示记录传 0
     * @param bonus          原规则给出的加成；没有就传 0
     * @param bonusSource    加成的来源码；没有来源传 {@code null}
     * @param levels         逐层使用事实
     * @param skipped        是否已跳过
     * @param answerRevealed 是否揭示过答案（辅助完成，但不扣满分）
     */
    public static Receipt assemble(Integer baseScore, int hintLevel, int bonus, String bonusSource,
            List<LevelEvent> levels, boolean skipped, boolean answerRevealed) {
        List<LevelEvent> safeLevels = levels == null ? List.of() : List.copyOf(levels);
        int pointsCharged = 0;
        for (LevelEvent event : safeLevels) {
            pointsCharged += Math.max(0, event.pointsCharged());
        }
        if (baseScore == null) {
            // 该玩法没有权威计分基准：不给假分数，也不因为用过提示就凭空扣分。
            return new Receipt(null, 0, "NONE", 0, 0, null, pointsCharged, safeLevels,
                    skipped, skipped || answerRevealed, SCORE_NOT_SCORED);
        }
        int base = Math.max(0, baseScore);
        int bonusAwarded = Math.max(0, bonus);
        String source = bonusAwarded == 0 ? "NONE" : (bonusSource == null || bonusSource.isBlank() ? "UNSPECIFIED" : bonusSource);
        // 提示降档沿用既有 PuzzleScorePolicy（100 / 70 / 40），不另造扣分规则。
        int helpDeduction = Math.max(0, base - PuzzleScorePolicy.solvedScore(Math.max(0, hintLevel)));
        if (helpDeduction > base) helpDeduction = base;
        int skipDeduction = skipped ? base : 0;
        int net = Math.max(0, base + bonusAwarded - helpDeduction - skipDeduction);
        return new Receipt(base, bonusAwarded, source, helpDeduction, skipDeduction, net,
                pointsCharged, safeLevels, skipped, skipped || answerRevealed, SCORE_SCORED);
    }

    /**
     * 从冻结配置取本节点的阶梯。G5 的 {@code levels()} 已处理形状错误与无账本代价,
     * 本方法只把空表翻译成 disabled。
     *
     * @throws IllegalArgumentException 阶梯结构损坏(G5 抛出),调用方按配置错误处理
     */
    public static Rule ruleFromLadder(JsonNode hintLadder) {
        List<PlayHelpUsagePolicy.LevelSpec> specs = PlayHelpUsagePolicy.levels(hintLadder);
        return specs.isEmpty() ? Rule.disabled() : new Rule(List.copyOf(specs));
    }

    /**
     * 一局的可对账账本。按既有事实行重放即可,不需要新表:
     * 提示事实对应 {@code play_attempt_log}(attempt_type=9,幂等键见 G5 {@code levelFactKey}),
     * 跳过/解出对应进度行的 {@code puzzle_score} 与 {@code completion_mode}。
     *
     * <p>{@code net = baseTotal + awardedTotal − helpDeductionTotal − skipDeductionTotal},
     * 四项都能由 {@link #entries()} 逐笔求和复算。
     */
    public static final class Ledger {
        private final Rule rule;
        private final Map<Long, Integer> baseScores;
        private final List<Entry> entries = new ArrayList<>();
        private final Map<String, Entry> byRequest = new LinkedHashMap<>();
        private final Map<Long, Integer> unlockedPosition = new LinkedHashMap<>();
        private final Map<Long, Integer> helpDeductedByNode = new LinkedHashMap<>();
        private final Set<Long> closed = new LinkedHashSet<>();
        private final Set<Long> skipped = new LinkedHashSet<>();
        private final Set<Long> answerRevealed = new LinkedHashSet<>();
        private final Set<Long> assisted = new LinkedHashSet<>();

        public Ledger(Rule rule, Map<Long, Integer> baseScores) {
            this.rule = rule == null ? Rule.disabled() : rule;
            this.baseScores = baseScores == null ? Map.of() : new LinkedHashMap<>(baseScores);
        }

        /** 正常解出:计入本题基准分。 */
        public Apply solve(long nodeId, String requestId) {
            Apply guard = guardNode(nodeId, requestId, true);
            if (!guard.accepted()) {
                return guard;
            }
            if (closed.contains(nodeId)) {
                return reject(NODE_ALREADY_CLOSED, null);
            }
            return append(new Entry(KIND_SOLVE, nodeId, 0, false, baseScores.get(nodeId), 0, 0, 0, requestId));
        }

        /**
         * 跳过:BE-16/M.7「本题记 0」。基准分照计、等额扣回,本题净贡献 0,并打辅助完成标记。
         * 不产生答对、到达、扫码或支付任何事实,也不发成功奖励。
         */
        public Apply skip(long nodeId, String requestId) {
            Apply guard = guardNode(nodeId, requestId, true);
            if (!guard.accepted()) {
                return guard;
            }
            if (!rule.skipConfigured()) {
                return reject(SKIP_DISABLED, null);
            }
            if (closed.contains(nodeId)) {
                return reject(NODE_ALREADY_CLOSED, null);
            }
            int base = baseScores.get(nodeId);
            // 跳过 = 本题解谜分记 0（扣满基准分）；**不扣积分余额**。
            Apply applied = append(new Entry(KIND_SKIP, nodeId, 0, false, base, 0, base, 0, requestId));
            if (applied.applied()) {
                assisted.add(nodeId);
            }
            return applied;
        }

        /**
         * 使用某一级提示。
         *
         * <p>首次使用才落一笔:{@code freeQuota >= 1} 的层级本次免费(M.7「免费 1–3 次/局」按层表达),
         * 否则按该层 {@code cost.points} 扣本题分。已解锁层再查看只回放,不再扣、不再计次。
         * 逐级解锁沿用既有 {@code PuzzlePlayServiceImpl.requestHint}「请先查看上一条提示」的语义。
         */
        public Apply useHelp(long nodeId, int level, String requestId) {
            Apply guard = guardNode(nodeId, requestId, false);
            if (!guard.accepted()) {
                return guard;
            }
            PlayHelpUsagePolicy.LevelSpec spec = rule.spec(level);
            if (spec == null) {
                return reject(LEVEL_NOT_CONFIGURED, null);
            }
            if (!spec.usable()) {
                return reject(spec.reason(), spec.reason());
            }
            int position = rule.position(level);
            int unlocked = unlockedPosition.getOrDefault(nodeId, -1);
            if (position <= unlocked) {
                return new Apply(false, true, null, null);
            }
            if (position != unlocked + 1) {
                return reject(LEVEL_OUT_OF_ORDER, null);
            }
            boolean free = spec.freeOnFirstUse();
            // 提示代价只扣**积分余额**（G5 流水），不扣解谜分：两本账分开。
            int points = free ? 0 : spec.costPoints();
            Apply applied = append(new Entry(KIND_HELP, nodeId, level, free, 0, 0, 0, points, requestId));
            if (applied.applied()) {
                unlockedPosition.put(nodeId, position);
                helpDeductedByNode.put(nodeId, helpDeductedByNode.getOrDefault(nodeId, 0) + points);
                if (PlayHelpUsagePolicy.TYPE_ANSWER.equals(spec.type())) {
                    answerRevealed.add(nodeId);
                    assisted.add(nodeId);
                }
            }
            return applied;
        }

        /**
         * 记一笔**由调用方证明**的加分。本类不推导加成规则:没有理由码的加分一律拒绝,
         * 不得用观察到的榜首分差反推加成公式。
         */
        public Apply award(int points, String reasonCode, String requestId) {
            if (!rule.enabled()) {
                return reject(HELP_DISABLED, null);
            }
            if (points <= 0 || StringUtils.isBlank(reasonCode) || StringUtils.isBlank(requestId)) {
                return reject(BAD_REQUEST, null);
            }
            if (byRequest.containsKey(requestId)) {
                return new Apply(false, true, null, null);
            }
            return append(new Entry(KIND_AWARD, 0L, 0, false, 0, points, 0, 0, requestId));
        }

        private Apply guardNode(long nodeId, String requestId, boolean needsBase) {
            if (!rule.enabled()) {
                return reject(HELP_DISABLED, null);
            }
            if (StringUtils.isBlank(requestId)) {
                return reject(BAD_REQUEST, null);
            }
            if (byRequest.containsKey(requestId)) {
                return new Apply(false, true, null, null);
            }
            if (needsBase && !baseScores.containsKey(nodeId)) {
                return reject(NODE_NOT_SCORED, null);
            }
            return new Apply(true, false, null, null);
        }

        private Apply append(Entry entry) {
            entries.add(entry);
            byRequest.put(entry.requestId(), entry);
            if (KIND_SOLVE.equals(entry.kind()) || KIND_SKIP.equals(entry.kind())) {
                closed.add(entry.nodeId());
            }
            if (KIND_SKIP.equals(entry.kind())) {
                skipped.add(entry.nodeId());
            }
            return new Apply(true, false, null, null);
        }

        private static Apply reject(String code, String reason) {
            return new Apply(false, false, code, reason);
        }

        public int baseTotal() {
            int total = 0;
            for (Entry entry : entries) {
                total += entry.base();
            }
            return total;
        }

        public int awardedTotal() {
            int total = 0;
            for (Entry entry : entries) {
                total += entry.awarded();
            }
            return total;
        }

        /** 提示扣掉的**解谜分**合计（当前规则恒为 0：提示只花积分）。 */
        public int helpDeductionTotal() {
            int total = 0;
            for (Entry entry : entries) {
                if (KIND_HELP.equals(entry.kind())) {
                    total += entry.scoreDeduction();
                }
            }
            return total;
        }

        /** 提示实际扣掉的**可兑换积分**合计（与解谜分是两本账）。 */
        public int helpPointsChargedTotal() {
            int total = 0;
            for (Entry entry : entries) {
                if (KIND_HELP.equals(entry.kind())) {
                    total += entry.pointsCharged();
                }
            }
            return total;
        }

        public int skipDeductionTotal() {
            int total = 0;
            for (Entry entry : entries) {
                if (KIND_SKIP.equals(entry.kind())) {
                    total += entry.scoreDeduction();
                }
            }
            return total;
        }

        /** base + awarded − helpDeductions − skipDeductions。 */
        public int netScore() {
            return baseTotal() + awardedTotal() - helpDeductionTotal() - skipDeductionTotal();
        }

        /**
         * 单节点净贡献,可直接写进既有 {@code player_node_progress.puzzle_score}
         * (个人最佳分就是该列求和)。未关闭节点与跳过的节点均为 0。
         */
        public int nodeScore(long nodeId) {
            Integer base = baseScores.get(nodeId);
            if (base == null || !closed.contains(nodeId) || skipped.contains(nodeId)) {
                return 0;
            }
            // 只扣解谜分维度的提示扣分；积分已另账，不能在这里再减一次。
            return Math.max(0, base);
        }

        /**
         * 既有 {@code completion_mode} 列的值。未关闭 → null;跳过 → {@link #COMPLETION_SKIPPED};
         * 揭示过答案 → {@link PuzzleScorePolicy#COMPLETION_REVEALED};否则 SOLVED。
         * 跳过不写 SOLVED —— 那是答对证明。
         */
        public String completionMode(long nodeId) {
            if (!closed.contains(nodeId)) {
                return null;
            }
            if (skipped.contains(nodeId)) {
                return COMPLETION_SKIPPED;
            }
            return answerRevealed.contains(nodeId)
                    ? PuzzleScorePolicy.COMPLETION_REVEALED
                    : PuzzleScorePolicy.COMPLETION_SOLVED;
        }

        /** 跳过或揭示答案 = 辅助完成;不是答对证明。 */
        public boolean assistedComplete(long nodeId) {
            return assisted.contains(nodeId);
        }

        public int unlockedLevel(long nodeId) {
            int position = unlockedPosition.getOrDefault(nodeId, -1);
            return position < 0 ? 0 : rule.levels().get(position).level();
        }

        public List<Entry> entries() {
            return Collections.unmodifiableList(entries);
        }

        /**
         * 即时回执(G4 可直接下发):净分与四项分解 + 逐笔事件。
         * 不含任何提示正文、答案、结论或完成证明。
         */
        public Map<String, Object> receiptView() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("baseScore", baseTotal());
            view.put("awardedBonus", awardedTotal());
            view.put("helpDeduction", helpDeductionTotal());
            view.put("helpPointsCharged", helpPointsChargedTotal());
            view.put("skipDeduction", skipDeductionTotal());
            view.put("netScore", netScore());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Entry entry : entries) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", entry.kind());
                row.put("nodeId", entry.nodeId());
                row.put("level", entry.level());
                row.put("freeUse", entry.freeUse());
                row.put("base", entry.base());
                row.put("awarded", entry.awarded());
                row.put("scoreDeduction", entry.scoreDeduction());
                row.put("pointsCharged", entry.pointsCharged());
                row.put("requestId", entry.requestId());
                rows.add(row);
            }
            view.put("events", rows);
            return view;
        }
    }
}
