package com.chengyinhub.business.util;

import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 故事变量 {名字}(施工契约 20260917 §3.1)的**唯一真源**。
 *
 * <p>两条回包共用这一份构造函数:
 * <ul>
 *   <li>{@code /api/play/advanced} 的会话视图(单局 state);</li>
 *   <li>{@code /api/play/nodes} 的游玩进度回包(B 的章节全文 / 节点 story 替换点在这里,
 *       所以要把同一主题下该玩家的几局 state 一并喂进来)。</li>
 * </ul>
 * 两处各拼一份 = 下一个静默漂移:一边加了 kit 结果、另一边没有,替换出来的文案两屏不一致。
 *
 * <p>键 = profile 的 question.key + 已完成 kit 的结果(目前是 diyName → 作品名)。
 * 没值不放空串 —— 替换方要区分「没值」与「值就是空串」,没值且没兜底时保留花括号。
 */
public final class StoryVars {

    private static final Logger LOG = LoggerFactory.getLogger(StoryVars.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StoryVars() {
    }

    /** 单局入口(会话视图用)。 */
    public static Map<String, Object> fromState(JsonNode state) {
        return fromStates(state == null ? Collections.<JsonNode>emptyList()
                : Collections.singletonList(state));
    }

    /** 多局入口(进度回包用):同一玩家在同一主题下的若干局 state_json,按传入顺序合并。 */
    public static Map<String, Object> fromStateJsons(List<String> stateJsons) {
        if (stateJsons == null || stateJsons.isEmpty()) return new LinkedHashMap<>();
        List<JsonNode> states = new ArrayList<>(stateJsons.size());
        for (String json : stateJsons) {
            if (StringUtils.isBlank(json)) continue;
            try {
                JsonNode parsed = MAPPER.readTree(json);
                if (parsed != null && parsed.isObject()) states.add(parsed);
            } catch (Exception unusableState) {
                // 坏一局不影响其余:变量读不出来只是那一段不替换,别把进度回包打挂
                LOG.warn("故事变量:有一局状态解析失败,已跳过:{}", unusableState.toString());
            }
        }
        return fromStates(states);
    }

    /**
     * 合并规则:建档答案**先到先得**(同一个 key 以先被填的那一局为准,建档是一次性的,
     * 后一局再出现同 key 不覆盖前面已成立的身份),作品名(diyName)仅在没有任何答案用过
     * 这个键名时兜底。
     */
    private static Map<String, Object> fromStates(List<? extends JsonNode> states) {
        Map<String, Object> vars = new LinkedHashMap<>();
        String diyName = "";
        for (JsonNode state : states) {
            if (state == null || !state.isObject()) continue;
            JsonNode answers = state.path("profile").path("answers");
            if (answers.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = answers.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    if (!entry.getValue().isTextual() || entry.getValue().asText().isEmpty()) continue;
                    if (!vars.containsKey(entry.getKey())) vars.put(entry.getKey(), entry.getValue().asText());
                }
            }
            if (diyName.isEmpty()) {
                diyName = state.path("diyName").path("name").asText("");
            }
        }
        if (!diyName.isEmpty() && !vars.containsKey("diyName")) vars.put("diyName", diyName);
        return vars;
    }
}
