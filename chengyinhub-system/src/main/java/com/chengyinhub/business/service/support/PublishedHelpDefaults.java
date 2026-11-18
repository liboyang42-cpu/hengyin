package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 发布冻结时的兜底(只改冻结副本,不回写模板;已开局的 run 读自己的快照,不受影响)。
 *
 * <ul>
 *   <li>F01 提示阶梯:城市定向主题里有判定的题,保证「方向 → 关键步骤 → 答案 → 跳过」都在。
 *       没有阶梯的由 hint1/hint2/揭示答案生成;已有阶梯缺 ANSWER 或 SKIP 的补上。生成的层一律
 *       {@code cost:{points:0}} —— 提示免费,惩罚只体现在本题得分(100/70/40,跳过 0)。</li>
 *   <li>F02 通用错答:有判定的块配了 {@code outcomeRules.fail} 却没写话时,补一条默认文案。</li>
 * </ul>
 * 兜底不能代替作者写的第三级解释与每站话术,只保证玩家不会卡死、不会只看到空白。
 */
public final class PublishedHelpDefaults {

    public static final String DEFAULT_FAIL_REPLY = "还不对，再看看题面";
    static final String LADDER = "hintLadder";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private PublishedHelpDefaults() {
    }

    /** 冻结快照整体入口:{@code orienteering} 为城市定向主题(product_type=1)时才补提示阶梯。 */
    public static void apply(ObjectNode frozen, boolean orienteering) {
        if (frozen == null) return;
        if (orienteering) backfillLadders(frozen);
        fillFailReplies(frozen);
    }

    // ==================== F01 提示阶梯 ====================

    static void backfillLadders(ObjectNode frozen) {
        JsonNode answers = frozen.path(NodeJourneySnapshot.NODE_ANSWERS_KEY);
        if (!answers.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> rows = answers.fields();
        while (rows.hasNext()) {
            Map.Entry<String, JsonNode> row = rows.next();
            if (!row.getValue().isObject()) continue;
            ObjectNode answer = (ObjectNode) row.getValue();
            JsonNode block = frozen.path("nodeConfigs").path(row.getKey());
            ArrayNode ladder = ladderFor(answer, block);
            if (ladder != null) answer.set(LADDER, ladder);
        }
    }

    /** 本节点完整后的阶梯;不是有判定的题、或无需改动时返回 null。 */
    static ArrayNode ladderFor(JsonNode answer, JsonNode block) {
        if (!judged(answer, block)) return null;
        JsonNode existing = existingLadder(answer, block);
        String answerText = answerText(answer);
        String answerPayload = answerText == null ? sectionAnswer(block) : null;
        boolean hasAnswer = answerText != null || answerPayload != null;
        if (existing == null) {
            ArrayNode ladder = JSON.arrayNode();
            if (text(answer.path("hint1")) != null) level(ladder, 1, PlayHelpUsagePolicy.TYPE_TEXT, null);
            if (text(answer.path("hint2")) != null) level(ladder, 2, PlayHelpUsagePolicy.TYPE_TEXT, null);
            if (hasAnswer) level(ladder, 3, PlayHelpUsagePolicy.TYPE_ANSWER, answerPayload);
            level(ladder, 4, PlayHelpUsagePolicy.TYPE_SKIP, null);
            return ladder;
        }
        boolean hasAnswerLevel = false, hasSkip = false;
        int max = 0;
        for (JsonNode entry : existing) {
            String type = entry.path("type").asText("").trim().toUpperCase(java.util.Locale.ROOT);
            hasAnswerLevel |= PlayHelpUsagePolicy.TYPE_ANSWER.equals(type);
            hasSkip |= PlayHelpUsagePolicy.TYPE_SKIP.equals(type);
            max = Math.max(max, entry.path("level").asInt(0));
        }
        boolean addAnswer = !hasAnswerLevel && hasAnswer;
        if (!addAnswer && hasSkip) return null;
        // 作者书写顺序就是解锁顺序:答案层插在跳过之前,跳过永远在最后。
        ArrayNode ladder = JSON.arrayNode();
        List<JsonNode> skips = new ArrayList<>();
        for (JsonNode entry : existing) {
            if (PlayHelpUsagePolicy.TYPE_SKIP.equals(entry.path("type").asText("").trim().toUpperCase(java.util.Locale.ROOT))) skips.add(entry);
            else ladder.add(entry.deepCopy());
        }
        if (addAnswer) level(ladder, ++max, PlayHelpUsagePolicy.TYPE_ANSWER, answerPayload);
        for (JsonNode skip : skips) ladder.add(skip.deepCopy());
        if (!hasSkip) level(ladder, ++max, PlayHelpUsagePolicy.TYPE_SKIP, null);
        return ladder;
    }

    /** 与运行期 PlayHelpApplication.frozenLadder 同一读取顺序。 */
    private static JsonNode existingLadder(JsonNode answer, JsonNode block) {
        if (answer.path(LADDER).isArray() && answer.path(LADDER).size() > 0) return answer.path(LADDER);
        JsonNode advanced = answer.get("advancedConfigJson");
        if (advanced != null && advanced.isTextual() && !advanced.asText().trim().isEmpty()) {
            try {
                JsonNode parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(advanced.asText());
                JsonNode ladder = parsed == null ? null : parsed.get(LADDER);
                return ladder != null && ladder.isArray() && ladder.size() > 0 ? ladder : null;
            } catch (Exception unreadable) {
                return null;
            }
        }
        JsonNode ladder = block == null ? null : block.get(LADDER);
        return ladder != null && ladder.isArray() && ladder.size() > 0 ? ladder : null;
    }

    /**
     * 有判定的题:文字(1)、拍照(2)、选项(3)验证,或块里开着打字问答/选项问答(判对错)/限时打字/逐字猜词。
     * 纯剧情、到达、偏好(不判对错)不生成 —— 跳过一道没有对错的题没有意义。
     */
    static boolean judged(JsonNode answer, JsonNode block) {
        int vm = answer.path("validationMethod").asInt(0);
        if (vm == 1 || vm == 2 || vm == 3) return true;
        if (block == null || !block.isObject()) return false;
        JsonNode qa = block.path("qa");
        if (qa.path("enabled").asBoolean(false)) {
            String mode = qa.path("mode").asText("");
            if ("TYPE".equals(mode) || "SHOT".equals(mode)) return true;
            if ("PICK".equals(mode) && qa.path("judge").asBoolean(true)) return true;
        }
        return block.path("typeIn").path("enabled").asBoolean(false) || block.path("hangman").path("enabled").asBoolean(false);
    }

    /** 与 PlayHelpContent 的 ANSWER 层同一取值:揭示文案 > 标准答案 > 正确选项。 */
    private static String answerText(JsonNode answer) {
        for (String field : new String[]{"answerReveal", "questionAnswer", "correctAnswer"}) {
            String value = text(answer.path(field));
            if (value != null) return value;
        }
        return null;
    }

    /** 冻结答案里没有时,打字问答/逐字猜词的答案作为 ANSWER 层 payload(仍只在服务端快照里)。 */
    private static String sectionAnswer(JsonNode block) {
        if (block == null || !block.isObject()) return null;
        JsonNode qa = block.path("qa");
        if (qa.path("enabled").asBoolean(false) && "TYPE".equals(qa.path("mode").asText(""))) {
            List<String> candidates = AnswerPolicy.answerTextCandidates(qa.path("answerText").asText(""));
            if (!candidates.isEmpty()) return candidates.get(0);
        }
        JsonNode hangman = block.path("hangman");
        if (hangman.path("enabled").asBoolean(false)) return text(hangman.path("answer"));
        return null;
    }

    private static void level(ArrayNode ladder, int level, String type, String payload) {
        ObjectNode entry = ladder.addObject();
        entry.put("level", level);
        entry.put("type", type);
        if (payload != null) entry.put("payload", payload);
        entry.putObject("cost").put("points", 0);
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    // ==================== F02 通用错答 ====================

    /** 快照里每一份 nodeConfigs(顶层、themeConfig、themeDefinition 及其 scenes)都补,免得读侧取到不同副本。 */
    static void fillFailReplies(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if ("nodeConfigs".equals(field.getKey()) && field.getValue().isObject()) {
                    field.getValue().elements().forEachRemaining(PublishedHelpDefaults::fillFailReply);
                } else if (field.getValue().isContainerNode()) {
                    fillFailReplies(field.getValue());
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) fillFailReplies(child);
        }
    }

    static void fillFailReply(JsonNode block) {
        JsonNode fail = block == null ? null : block.path("outcomeRules").get("fail");
        if (fail == null || !fail.isObject()) return;
        if (fail.path("reply").isTextual() && !fail.path("reply").asText().trim().isEmpty()) return;
        ((ObjectNode) fail).put("reply", DEFAULT_FAIL_REPLY);
    }
}
