package com.chengyinhub.business.service.support;

import com.chengyinhub.common.constant.ValidationMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * 从实际冻结内容推导「这一版内容需要客户端具备什么」—— 玩家节点列表里的 capability 声明。
 *
 * <p>三类能力必须分开（C04）：用户有没有创作权益（既有 guard）、玩法能不能结算
 * （{@link GameplaySupportMatrix} 等既有矩阵）、某端能不能正确呈现和完成（本类）。
 * 本类只做第三类，且只作<b>可选声明</b>：
 *
 * <ul>
 *   <li>能力由服务端从内容推导，不采信客户端把必需项删掉；</li>
 *   <li>客户端没声明（老客户端）= 兼容结论为「未确认」，<b>不是不支持</b>，不阻断读取；</li>
 *   <li>声明不授予任何业务权限，也不改变完成判定与奖励。</li>
 * </ul>
 *
 * <p>键名 {@code area.feature}，版本走 {@code minVersion}。新增玩法段 / 完成方式要在这里登记；
 * 漏登记不会让内容不可玩（本类不阻断），但会让端侧拿不到该能力的提示。
 */
public final class ContentCapabilityRequirements {

    public static final int DEFAULT_VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ContentCapabilityRequirements() {
    }

    /** 去重并按 key 升序的 [{key,minVersion}]。 */
    public static ArrayNode required(JsonNode snapshot) {
        Map<String, Integer> keys = new TreeMap<>();
        if (snapshot != null && snapshot.isObject()) {
            chapters(snapshot, keys);
            stations(snapshot, keys);
            gameplay(snapshot, keys);
            route(snapshot, keys);
        }
        ArrayNode rows = JSON.createArrayNode();
        for (Map.Entry<String, Integer> row : keys.entrySet()) {
            ObjectNode item = rows.addObject();
            item.put("key", row.getKey());
            item.put("minVersion", row.getValue());
        }
        return rows;
    }

    private static void chapters(JsonNode snapshot, Map<String, Integer> keys) {
        for (String container : new String[]{"thoughtChapters", "openingChapter", "endingChapters"}) {
            JsonNode node = snapshot.get(container);
            if (node == null || node.isNull()) continue;
            for (JsonNode row : node) {
                if (row == null || !row.isObject()) continue;
                keys.merge("chapter.blocks", DEFAULT_VERSION, Math::max);
                blocks(row.get("blocks"), keys);
                media(row, "audioUrl", "media.audio", keys);
                media(row, "imgArr", "media.image", keys);
            }
        }
    }

    private static void blocks(JsonNode blocks, Map<String, Integer> keys) {
        if (blocks == null || !blocks.isArray()) return;
        for (JsonNode block : blocks) {
            if (block == null || !block.isObject()) continue;
            String type = block.path("type").asText("").trim();
            if (type.isEmpty()) continue;
            keys.merge("chapter.blocks." + sanitize(type), DEFAULT_VERSION, Math::max);
        }
    }

    private static void stations(JsonNode snapshot, Map<String, Integer> keys) {
        JsonNode answers = snapshot.path(NodeJourneySnapshot.NODE_ANSWERS_KEY);
        if (!answers.isObject()) return;
        boolean any = false;
        Iterator<JsonNode> rows = answers.elements();
        while (rows.hasNext()) {
            JsonNode answer = rows.next();
            if (answer == null || !answer.isObject()) continue;
            any = true;
            JsonNode template = answer.path("templatePresentation");
            JsonNode method = template.has("validationMethod") ? template.get("validationMethod")
                    : answer.get("validationMethod");
            method(method, keys);
            sensor(template.path("sensorType").asText(null), answer.path("sensorType").asText(null), keys);
            JsonNode node = answer.path("nodePresentation");
            media(node, "imgUrl", "media.image", keys);
            media(template, "imgUrl", "media.image", keys);
            media(template, "storyImg", "media.image", keys);
            media(template, "questionImg", "media.image", keys);
            media(template, "medalImg", "media.image", keys);
            media(template, "audioUrl", "media.audio", keys);
            media(template, "questionAudio", "media.audio", keys);
            media(answer, "chapterAudioUrl", "media.audio", keys);
        }
        if (any) keys.merge("station.list", DEFAULT_VERSION, Math::max);
    }

    private static void method(JsonNode method, Map<String, Integer> keys) {
        if (method == null || method.isNull() || !method.isIntegralNumber()) {
            keys.merge("answer.arrive", DEFAULT_VERSION, Math::max);
            return;
        }
        int value = method.asInt();
        String key;
        switch (value) {
            case 0: key = "answer.arrive"; break;
            case 1: key = "answer.text"; break;
            case ValidationMethod.PHOTO:
                key = "answer.photo";
                keys.merge("media.photo", DEFAULT_VERSION, Math::max);
                break;
            case 3: key = "answer.choice"; break;
            case 4: key = "answer.scan"; break;
            case 5:
                key = "answer.gps";
                keys.merge("location.gps", DEFAULT_VERSION, Math::max);
                break;
            case ValidationMethod.PREFERENCE_GROUP: key = "answer.preference"; break;
            case ValidationMethod.APP_SENSOR: key = "answer.sensor"; break;
            case ValidationMethod.ESTIMATE: key = "answer.estimate"; break;
            case ValidationMethod.PRICE_PAIR: key = "answer.pricePair"; break;
            case ValidationMethod.HIDDEN_OBJECT: key = "answer.hiddenObject"; break;
            // 未登记的完成方式不猜成「默认支持」:给出可诊断的键,由端侧合同对齐。
            default: key = "answer.method." + value; break;
        }
        keys.merge(key, DEFAULT_VERSION, Math::max);
    }

    private static void sensor(String templateSensor, String answerSensor, Map<String, Integer> keys) {
        String type = templateSensor == null || templateSensor.trim().isEmpty() ? answerSensor : templateSensor;
        if (type == null || type.trim().isEmpty()) return;
        keys.merge("sensor." + sanitize(type), DEFAULT_VERSION, Math::max);
    }

    /** 玩法入口：冻结时写下的启用段就是这一局真正会出现的玩法（与 NodeJourneySnapshot 同一份名单）。 */
    private static void gameplay(JsonNode snapshot, Map<String, Integer> keys) {
        JsonNode configs = snapshot.path("nodeConfigs");
        if (!configs.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> rows = configs.fields();
        while (rows.hasNext()) {
            JsonNode config = rows.next().getValue();
            if (config == null || !config.isObject()) continue;
            for (String section : NodeJourneySnapshot.PLAYABLE_SECTIONS) {
                JsonNode value = config.get(section);
                if (value != null && value.isObject() && value.path("enabled").asBoolean(false)) {
                    keys.merge("game." + sanitize(section), DEFAULT_VERSION, Math::max);
                }
            }
            JsonNode roleViews = config.get("roleViews");
            if (roleViews != null && roleViews.isObject() && roleViews.path("enabled").asBoolean(false)) {
                keys.merge("role.views", DEFAULT_VERSION, Math::max);
            }
        }
    }

    private static void route(JsonNode snapshot, Map<String, Integer> keys) {
        String mode = snapshot.path("routeMode").asText("");
        if (!mode.trim().isEmpty() && !"LINEAR".equals(mode)) keys.merge("route.branch", DEFAULT_VERSION, Math::max);
        if (snapshot.path("themeConfig").isObject()) keys.merge("theme.runtime", DEFAULT_VERSION, Math::max);
    }

    private static void media(JsonNode row, String field, String key, Map<String, Integer> keys) {
        if (row == null || !row.isObject()) return;
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return;
        if (value.isTextual() && value.asText().trim().isEmpty()) return;
        if (value.isArray() && value.isEmpty()) return;
        keys.merge(key, DEFAULT_VERSION, Math::max);
    }

    private static String sanitize(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x80 && Character.isLetterOrDigit(c)) out.append(Character.toLowerCase(c));
            else if (c == '.' || c == '-' || c == '_') out.append(c);
        }
        return out.length() == 0 ? "unknown" : out.toString();
    }

    /**
     * 端能力声明（客户端自报）。缺省 = 未声明（老客户端），不当作「全支持」。
     * 格式 {@code key@version,key@version}；无法解析的条目丢弃并把整份声明标为不可信。
     */
    public record Declaration(String platform, String clientVersion, Map<String, Integer> capabilities, boolean malformed) {
        public static final Declaration NONE = new Declaration(null, null, Map.of(), false);

        public static Declaration parse(String platform, String clientVersion, String raw) {
            if (raw == null || raw.trim().isEmpty()) return new Declaration(platform, clientVersion, Map.of(), false);
            Map<String, Integer> parsed = new TreeMap<>();
            boolean malformed = false;
            for (String part : raw.split(",")) {
                String token = part.trim();
                if (token.isEmpty()) continue;
                int at = token.lastIndexOf('@');
                String key = at < 0 ? token : token.substring(0, at).trim();
                String version = at < 0 ? "" : token.substring(at + 1).trim();
                if (key.isEmpty()) {
                    malformed = true;
                    continue;
                }
                int value = DEFAULT_VERSION;
                if (!version.isEmpty()) {
                    try {
                        value = Integer.parseInt(version);
                    } catch (NumberFormatException unusableVersion) {
                        malformed = true;
                        continue;
                    }
                    if (value <= 0) {
                        malformed = true;
                        continue;
                    }
                }
                parsed.put(key, value);
            }
            return new Declaration(platform, clientVersion, parsed, malformed);
        }

        public boolean declared() {
            return !capabilities.isEmpty();
        }
    }

    /**
     * 只读兼容结论：{@code supported} 为 null = 未确认兼容（老客户端），不冒充支持，也不阻断读取。
     */
    public static ObjectNode compatibility(JsonNode snapshot, Declaration declaration) {
        ObjectNode view = JSON.createObjectNode();
        ArrayNode required = required(snapshot);
        view.set("requiredCapabilities", required);
        Declaration effective = declaration == null ? Declaration.NONE : declaration;
        view.put("declared", effective.declared());
        view.put("platform", effective.platform());
        view.put("clientVersion", effective.clientVersion());
        ArrayNode missing = view.putArray("missingCapabilities");
        if (!effective.declared()) {
            view.putNull("supported");
            view.put("source", effective.malformed() ? "MALFORMED" : "UNDECLARED");
            return view;
        }
        for (JsonNode item : required) {
            String key = item.path("key").asText("");
            int minVersion = item.path("minVersion").asInt(DEFAULT_VERSION);
            Integer declaredVersion = effective.capabilities().get(key);
            if (declaredVersion == null || declaredVersion < minVersion) {
                ObjectNode row = missing.addObject();
                row.put("key", key);
                row.put("minVersion", minVersion);
                if (declaredVersion == null) row.putNull("declaredVersion");
                else row.put("declaredVersion", declaredVersion);
            }
        }
        view.put("supported", missing.isEmpty());
        view.put("source", effective.malformed() ? "DECLARED_MALFORMED" : "DECLARED");
        return view;
    }
}
