package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 到达读数（BE-15）：作者在节点公开配置里写 {@code arrivalReading{level,log}}，
 * 发布冻结进 routeStations，只有真实到达成功后的响应才投影这一段。
 *
 * <p>边界：
 * <ul>
 *   <li>只投影 level/log 两个字段；不整段序列化节点配置，答案/secret 留在服务端；</li>
 *   <li>{@code level} 沿既有叙事块的整数档（{@code ChapterBlockDTO.level}，0–3），
 *       不自造强弱枚举，也不映射任何奖励数值；</li>
 *   <li>没有该字段 = 不输出；旧客户端忽略未知字段即可；</li>
 *   <li>跳过的动作（help/skip）不得调用本投影，不制造 ARRIVED 假事实。</li>
 * </ul>
 */
public final class ArrivalReading {

    public static final String FIELD = "arrivalReading";
    /** 与既有叙事块 level 同一语义区间，不是新的强弱/奖励枚举。 */
    public static final int LEVEL_MIN = 0;
    public static final int LEVEL_MAX = 3;
    public static final int MAX_LOG_LENGTH = 500;

    private static final Set<String> KNOWN_KEYS =
            Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList("level", "log")));
    private static final ObjectMapper JSON = new ObjectMapper();

    private ArrivalReading() {
    }

    /** 保存/发布校验并规范化；空对象或未知字段直接拒绝，避免作者以为配了却读不到。 */
    public static ObjectNode normalize(JsonNode raw) {
        if (raw == null || !raw.isObject()) throw new IllegalArgumentException("到达读数必须是对象");
        Iterator<String> names = raw.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!KNOWN_KEYS.contains(name)) throw new IllegalArgumentException("到达读数包含未知字段:" + name);
        }
        ObjectNode out = JSON.createObjectNode();
        if (raw.has("level")) {
            JsonNode level = raw.get("level");
            if (!level.isIntegralNumber() || level.asInt() < LEVEL_MIN || level.asInt() > LEVEL_MAX) {
                throw new IllegalArgumentException("到达读数 level 须为 " + LEVEL_MIN + " 至 " + LEVEL_MAX + " 的整数");
            }
            out.put("level", level.asInt());
        }
        if (raw.has("log")) {
            JsonNode log = raw.get("log");
            String text = log.isTextual() ? log.asText().trim() : "";
            if (text.isEmpty() || text.length() > MAX_LOG_LENGTH) {
                throw new IllegalArgumentException("到达读数 log 须为 1 至 " + MAX_LOG_LENGTH + " 字");
            }
            out.put("log", text);
        }
        if (out.size() == 0) throw new IllegalArgumentException("到达读数至少配置 level 或 log");
        return out;
    }

    /**
     * 从冻结站点投影：没有字段/结构损坏都返回 null（读数只影响呈现，不能让一次真实到达失败）。
     * 只复制 level/log；其他任何键（题目、答案、秘密）一律不读。
     */
    public static ObjectNode project(JsonNode station) {
        if (station == null || !station.isObject()) return null;
        JsonNode raw = station.path(FIELD);
        if (!raw.isObject()) return null;
        ObjectNode out = JSON.createObjectNode();
        JsonNode level = raw.path("level");
        if (level.isIntegralNumber() && level.asInt() >= LEVEL_MIN && level.asInt() <= LEVEL_MAX) {
            out.put("level", level.asInt());
        }
        JsonNode log = raw.path("log");
        if (log.isTextual()) {
            String text = log.asText().trim();
            if (!text.isEmpty() && text.length() <= MAX_LOG_LENGTH) out.put("log", text);
        }
        return out.size() == 0 ? null : out;
    }
}
