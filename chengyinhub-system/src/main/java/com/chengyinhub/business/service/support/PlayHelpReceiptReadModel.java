package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.util.PuzzleScorePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 帮助/跳过在本局产生的**成绩明细**只读视图（UG07 净分与提示明细）。
 *
 * <p>三个真实调用方，同一份计算，避免任何一处各算一套：
 * <ol>
 *   <li>{@code PlayHelpApplication#receipt} —— 重进后的只读回执（GET）；</li>
 *   <li>{@code PlayNodeCompletionApplication#advanceProgress} —— 节点完成回包里的 {@code helpReceipt}；</li>
 *   <li>{@code AdvancedGameRuntimeServiceImpl#toView} —— 纯高级玩法局的会话视图 {@code helpReceipt}。</li>
 * </ol>
 *
 * <p><b>事实来源只有服务端既有事实行</b>：
 * <ul>
 *   <li>层级使用：{@code play_attempt_log} 的每局每级唯一键（{@code PlayHelpUsagePolicy.levelFactKey}），
 *       行里 {@code free}/{@code chargedPoints} 是 G5 扣积分时写下的真实值；</li>
 *   <li>既有提示等级：{@code PlayAttemptLogMapper.selectPuzzleHintLevels}（旧链 100/70/40 的唯一来源）；</li>
 *   <li>跳过与辅助完成：局状态 {@code skip}、进度行 {@code completion_mode}。</li>
 * </ul>
 * 因此**同一动作重试不会多出一条事件** —— 这里不写任何行，只是按唯一键回放既有事实。
 *
 * <p>不新建成绩库、不复制第二真源：计算全部委托 {@link PlayHelpOutcomeSupport#assemble}，
 * 扣分口径全部来自 {@link PuzzleScorePolicy}。客户端上报的 score/passed 从不参与。
 */
@Service
public class PlayHelpReceiptReadModel {

    /** 该节点没有权威计分基准（例如纯高级玩法局、自由收集）；分数为 null，不造假分。 */
    public static final String NO_BASE_SCORE = "NO_BASE_SCORE";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlayAttemptLogMapper attemptLogs;

    @Autowired
    public PlayHelpReceiptReadModel(PlayAttemptLogMapper attemptLogs) {
        this.attemptLogs = attemptLogs;
    }

    /**
     * 生成本节点本局的成绩明细。
     *
     * @param scope       原局作用域（含 kind；额度/事实键都由它派生）
     * @param ladder      冻结的 {@code hintLadder}（可为 null：旧局无阶梯）
     * @param baseScore   本题基准分；{@code null} = 该玩法不计分
     * @param skipped     本局是否已按跳过收尾
     * @param answerRevealed 进度行完成方式是否为揭示答案
     * @return 可直接下发/落回执的 Map（键名稳定，见 README）
     */
    public Map<String, Object> receipt(long memberId, PlayHelpUsagePolicy.Scope scope, JsonNode ladder,
            Integer baseScore, boolean skipped, boolean answerRevealed) {
        List<PlayHelpOutcomeSupport.LevelEvent> levels = levelEvents(memberId, scope, ladder);
        int hintLevel = legacyHintLevel(memberId, scope);
        PlayHelpOutcomeSupport.Receipt receipt = PlayHelpOutcomeSupport.assemble(
                baseScore, hintLevel, 0, null, levels, skipped, answerRevealed);
        return toView(receipt, baseScore == null ? NO_BASE_SCORE : null);
    }

    /**
     * 逐层使用事实。**每一层只看它自己的唯一键**：同一层重复查看/重试只会命中同一行，
     * 因此不会重复计入，也不需要"去重"逻辑。
     */
    private List<PlayHelpOutcomeSupport.LevelEvent> levelEvents(long memberId, PlayHelpUsagePolicy.Scope scope,
            JsonNode ladder) {
        List<PlayHelpOutcomeSupport.LevelEvent> events = new ArrayList<>();
        if (ladder == null || !ladder.isArray() || ladder.size() == 0) return events;
        String scopeKey = PlayHelpUsagePolicy.scopeKey(scope);
        for (PlayHelpUsagePolicy.LevelSpec spec : PlayHelpUsagePolicy.levels(ladder)) {
            String factKey;
            try {
                factKey = PlayHelpUsagePolicy.levelFactKey(memberId, scopeKey, spec.level());
            } catch (IllegalArgumentException unusableKey) {
                continue;
            }
            PlayAttemptLog fact = attemptLogs.selectByIdempotencyKey(factKey);
            if (fact == null) continue;
            // 事实行必须属于本人且是帮助事实；被别的身份占用时按"没有这条"处理，不冒充已解锁。
            if (fact.getMemberId() == null || fact.getMemberId() != memberId) continue;
            if (fact.getAttemptType() == null
                    || fact.getAttemptType() != PlayAttemptLog.ATTEMPT_TYPE_PUZZLE_HINT) continue;
            JsonNode row = parse(fact.getResultJson());
            boolean free = row == null || !row.has("free") || row.path("free").asBoolean(true);
            int charged = row == null ? 0 : Math.max(0, row.path("chargedPoints").asInt(0));
            events.add(new PlayHelpOutcomeSupport.LevelEvent(spec.level(), spec.type(), free, charged));
        }
        return events;
    }

    /**
     * 既有提示等级：与 {@code PuzzleScorePolicy.solvedScore} 的降档同一来源
     * （{@code play_attempt_log} 的谜题提示记录）。
     */
    private int legacyHintLevel(long memberId, PlayHelpUsagePolicy.Scope scope) {
        try {
            List<Map<String, Object>> rows = attemptLogs.selectPuzzleHintLevels(memberId,
                    scope.topicId(), scope.activityId());
            if (rows == null) return 0;
            int level = 0;
            for (Map<String, Object> row : rows) {
                Object nodeId = row.get("nodeId");
                if (!(nodeId instanceof Number) || ((Number) nodeId).longValue() != scope.nodeId()) continue;
                Object hintLevel = row.get("hintLevel");
                if (hintLevel instanceof Number) level = Math.max(level, ((Number) hintLevel).intValue());
            }
            return level;
        } catch (RuntimeException unavailable) {
            // 读不到就不猜等级：按 0 处理等于"不扣分"，宁可少扣也不误扣玩家解谜分。
            return 0;
        }
    }

    private static JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return JSON.readTree(raw);
        } catch (Exception unusable) {
            return null;
        }
    }

    /**
     * 稳定键名的下发形状。{@code scoreState=NOT_SCORED} 时 {@code baseScore/netScore} 为 null，
     * 客户端据此显示「本题不计分」，而**不是**显示 0 分。
     */
    private static Map<String, Object> toView(PlayHelpOutcomeSupport.Receipt receipt, String reason) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("scoreState", receipt.scoreState());
        view.put("baseScore", receipt.baseScore());
        view.put("bonus", receipt.bonus());
        view.put("bonusSource", receipt.bonusSource());
        view.put("helpDeduction", receipt.helpDeduction());
        view.put("skipDeduction", receipt.skipDeduction());
        view.put("netScore", receipt.netScore());
        view.put("pointsCharged", receipt.pointsCharged());
        view.put("skipped", receipt.skipped());
        view.put("assisted", receipt.assisted());
        if (receipt.scoreState().equals(PlayHelpOutcomeSupport.SCORE_NOT_SCORED) && reason != null) {
            view.put("reason", reason);
        }
        List<Map<String, Object>> levels = new ArrayList<>();
        for (PlayHelpOutcomeSupport.LevelEvent event : receipt.levels()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("level", event.level());
            row.put("type", event.type());
            row.put("free", event.free());
            row.put("pointsCharged", event.pointsCharged());
            levels.add(row);
        }
        view.put("levels", levels);
        return view;
    }
}
