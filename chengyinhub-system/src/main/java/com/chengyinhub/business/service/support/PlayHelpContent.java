package com.chengyinhub.business.service.support;

import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * BE-16:按层级下发**获准片段**。服务端从冻结答案里算出这一层允许给的内容,不把整份答案交给客户端截字符串。
 *
 * <pre>
 * TEXT   → 冻结层配置的 payload;没有则回落既有 hint1/hint2(既有提示即 L1 文字)
 * IMAGE  → 冻结层配置的 payload(图片地址);旧数据没有图片提示,不臆造
 * LETTER → 冻结答案的首个字母(英文首字母)
 * DIGIT  → 冻结答案的首个数字
 * ANSWER → 冻结答案的作者揭示文案(answerReveal),其次标准答案
 * SKIP   → 无内容(跳过不给答案,结算由 G6/G0 负责)
 * </pre>
 *
 * <p>推不出内容时返回 null,调用方必须拒绝且不扣任何代价 —— 绝不发半个答案或拿题面冒充提示。
 * 位置类工具(扩半径/解锁/地图)不在本类,也不在任何层级里。
 */
public final class PlayHelpContent {

    public static final String TYPE_TEXT = PlayHelpUsagePolicy.TYPE_TEXT;
    public static final String TYPE_IMAGE = PlayHelpUsagePolicy.TYPE_IMAGE;
    public static final String TYPE_LETTER = PlayHelpUsagePolicy.TYPE_LETTER;
    public static final String TYPE_DIGIT = "DIGIT";
    public static final String TYPE_ANSWER = PlayHelpUsagePolicy.TYPE_ANSWER;
    public static final String TYPE_SKIP = PlayHelpUsagePolicy.TYPE_SKIP;

    private PlayHelpContent() {
    }

    /**
     * @param level        层级序号(用于既有 hint1/hint2 回落)
     * @param type         冻结层配置的形式
     * @param ladderEntry  冻结层配置条目(可为 null)
     * @param frozenAnswer 本节点冻结答案块(可为 null;老局没有冻结答案时只能靠 payload)
     * @return 该层允许下发的内容;null 表示推不出,不得收费也不得解锁
     */
    public static String approved(int level, String type, JsonNode ladderEntry, JsonNode frozenAnswer) {
        if (type == null) return null;
        JsonNode payloadNode = ladderEntry == null ? null : ladderEntry.path("payload");
        String payload = text(payloadNode);
        switch (type) {
            case TYPE_TEXT:
                if (StringUtils.isNotBlank(payload)) return payload;
                return text(frozenAnswer == null ? null : frozenAnswer.path("hint" + level));
            case TYPE_IMAGE:
                return StringUtils.isBlank(payload) ? null : payload;
            case TYPE_LETTER:
                return letterAt(answerText(frozenAnswer), payloadNode);
            case TYPE_DIGIT:
                return firstOfClass(answerText(frozenAnswer), false);
            case TYPE_ANSWER:
                if (StringUtils.isNotBlank(payload)) return payload;
                return answerText(frozenAnswer);
            case TYPE_SKIP:
                return null;
            default:
                return null;
        }
    }

    private static String answerText(JsonNode frozenAnswer) {
        if (frozenAnswer == null) return null;
        String reveal = text(frozenAnswer.path("answerReveal"));
        if (StringUtils.isNotBlank(reveal)) return reveal;
        String answer = text(frozenAnswer.path("questionAnswer"));
        if (StringUtils.isNotBlank(answer)) return answer;
        return text(frozenAnswer.path("correctAnswer"));
    }

    /** 只取一个字符:首字母或首个数字,绝不下发整串答案。 */
    private static String firstOfClass(String answer, boolean letter) {
        if (StringUtils.isBlank(answer)) return null;
        for (int i = 0; i < answer.length(); i++) {
            char c = answer.charAt(i);
            if (letter ? Character.isLetter(c) : Character.isDigit(c)) return String.valueOf(c);
        }
        return null;
    }

    /**
     * 首字层:作者用整数 payload 指定「揭示第几个字符」时只揭示那一个(中文按完整字符计),
     * 没指定则退回首个字母/数字。位置越界或该位不是字母数字时不发内容 —— 不拿标点或空格充提示。
     */
    private static String letterAt(String answer, JsonNode payload) {
        if (StringUtils.isBlank(answer)) return null;
        if (payload == null || !payload.isIntegralNumber()) return firstOfClass(answer, true);
        int index = payload.asInt();
        int[] codePoints = answer.codePoints().toArray();
        if (index < 1 || index > codePoints.length) return null;
        int cp = codePoints[index - 1];
        return Character.isLetterOrDigit(cp) ? new String(Character.toChars(cp)) : null;
    }

    private static String text(JsonNode node) {
        return node == null || !node.isTextual() ? null : node.asText();
    }
}
