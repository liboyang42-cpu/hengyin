package com.chengyinhub.business.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * P6 measurement(GRO-03):匿名安装事件的严格合同。
 *
 * <p>登录前的首会话埋点不带账号,所以这里只认白名单事件、白名单属性键和枚举/短码值,
 * 任何多余字段、嵌套对象、自由文本都整批拒收。不收坐标、手机号、昵称等个人信息 ——
 * 合同本身就让它们没有位置可放。
 */
public final class InstallEventSchema {

    public static final int MAX_BATCH_SIZE = 50;
    public static final int MAX_BODY_CHARS = 64 * 1024;
    public static final long MAX_EVENT_AGE_MILLIS = 14L * 24 * 60 * 60 * 1000;
    public static final long MAX_FUTURE_SKEW_MILLIS = 5L * 60 * 1000;

    /** 随机 UUID(客户端 UUID().uuidString 小写化),既是安装号也是事件幂等号。 */
    public static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9_]{1,40}$");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[0-9]{1,4}(\\.[0-9]{1,4}){0,3}( \\([0-9]{1,8}\\))?$");
    private static final Set<String> PLATFORMS = Set.of("ios");
    private static final Set<String> BATCH_KEYS = Set.of("installId", "platform", "appVersion", "events");
    private static final Set<String> EVENT_KEYS = Set.of("eventId", "name", "occurredAt", "props");

    private static final Set<String> SIGN_IN_METHODS = Set.of("apple", "phone", "wechat", "password", "sms");
    private static final Set<String> PERMISSIONS = Set.of("location", "notifications", "camera");
    private static final Set<String> PERMISSION_RESULTS =
            Set.of("granted", "denied", "limited", "provisional", "restricted", "when_in_use", "always", "not_determined");

    /** 事件 → 允许的属性键 → 允许值(null 表示短码 [a-z0-9_]{1,40})。 */
    static final Map<String, Map<String, Set<String>>> EVENTS;

    static {
        Map<String, Map<String, Set<String>>> m = new LinkedHashMap<>();
        m.put("app_open", props("launch", Set.of("cold", "warm")));
        m.put("onboarding_step", props("step", null, "action", Set.of("shown", "done", "skipped")));
        m.put("permission_prompt", props("permission", PERMISSIONS));
        m.put("permission_result", props("permission", PERMISSIONS, "result", PERMISSION_RESULTS));
        m.put("sign_in_start", props("method", SIGN_IN_METHODS));
        m.put("sign_in_success", props("method", SIGN_IN_METHODS));
        m.put("sign_in_failure", props("method", SIGN_IN_METHODS, "reason", null));
        m.put("first_walk", props());
        m.put("first_fog_reveal", props());
        m.put("first_reward", props("kind", null));
        m.put("first_share", props("channel", null));
        EVENTS = Collections.unmodifiableMap(m);
    }

    /** 管理端漏斗的展示顺序:首开 → 引导 → 授权 → 登录 → 首次体验。 */
    public static final List<String> FUNNEL_ORDER = List.copyOf(EVENTS.keySet());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InstallEventSchema() {
    }

    private static Map<String, Set<String>> props(Object... pairs) {
        Map<String, Set<String>> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            Set<String> values = (Set<String>) pairs[i + 1];
            map.put((String) pairs[i], values);
        }
        return Collections.unmodifiableMap(map);
    }

    /** 一条通过校验的事件;props 已规整为按键排序的短 JSON。 */
    public record ValidEvent(String eventId, String name, long occurredAt, String propsJson) {
    }

    /** 一批通过校验的事件。 */
    public record ValidBatch(String installId, String platform, String appVersion, List<ValidEvent> events) {
    }

    /** 校验失败;message 是给客户端看的固定短句,不回显输入。 */
    public static final class Rejected extends IllegalArgumentException {
        public Rejected(String message) {
            super(message);
        }
    }

    public static ValidBatch parseBatch(String body, long nowMillis) {
        if (body == null || body.isBlank()) {
            throw new Rejected("事件不能为空");
        }
        if (body.length() > MAX_BODY_CHARS) {
            throw new Rejected("上报内容过大");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new Rejected("上报内容格式不正确");
        }
        if (root == null || !root.isObject()) {
            throw new Rejected("上报内容格式不正确");
        }
        requireOnlyKeys(root, BATCH_KEYS);
        String installId = requireInstallId(root.get("installId"));
        String platform = requireText(root.get("platform"), "平台");
        if (!PLATFORMS.contains(platform)) {
            throw new Rejected("不支持的平台");
        }
        String appVersion = requireText(root.get("appVersion"), "版本");
        if (!VERSION_PATTERN.matcher(appVersion).matches()) {
            throw new Rejected("版本格式不正确");
        }
        JsonNode events = root.get("events");
        if (events == null || !events.isArray() || events.isEmpty()) {
            throw new Rejected("事件不能为空");
        }
        if (events.size() > MAX_BATCH_SIZE) {
            throw new Rejected("单次最多上报" + MAX_BATCH_SIZE + "条事件");
        }
        List<ValidEvent> out = new ArrayList<>(events.size());
        for (JsonNode event : events) {
            out.add(parseEvent(event, nowMillis));
        }
        return new ValidBatch(installId, platform, appVersion, out);
    }

    public static String requireInstallId(JsonNode node) {
        if (node == null || !node.isTextual() || !UUID_PATTERN.matcher(node.asText()).matches()) {
            throw new Rejected("安装号格式不正确");
        }
        return node.asText();
    }

    public static boolean isInstallId(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }

    private static ValidEvent parseEvent(JsonNode event, long nowMillis) {
        if (event == null || !event.isObject()) {
            throw new Rejected("事件格式不正确");
        }
        requireOnlyKeys(event, EVENT_KEYS);
        JsonNode idNode = event.get("eventId");
        if (idNode == null || !idNode.isTextual() || !UUID_PATTERN.matcher(idNode.asText()).matches()) {
            throw new Rejected("事件编号格式不正确");
        }
        String name = requireText(event.get("name"), "事件名称");
        Map<String, Set<String>> allowedProps = EVENTS.get(name);
        if (allowedProps == null) {
            throw new Rejected("不支持的事件");
        }
        JsonNode at = event.get("occurredAt");
        if (at == null || !at.canConvertToLong() || !at.isIntegralNumber()) {
            throw new Rejected("事件时间格式不正确");
        }
        long occurredAt = at.asLong();
        if (occurredAt < nowMillis - MAX_EVENT_AGE_MILLIS || occurredAt > nowMillis + MAX_FUTURE_SKEW_MILLIS) {
            throw new Rejected("事件时间超出允许窗口");
        }
        Map<String, String> props = new TreeMap<>();
        JsonNode propsNode = event.get("props");
        if (propsNode != null && !propsNode.isNull()) {
            if (!propsNode.isObject()) {
                throw new Rejected("事件属性格式不正确");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = propsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!allowedProps.containsKey(field.getKey())) {
                    throw new Rejected("事件属性不在允许范围内");
                }
                JsonNode value = field.getValue();
                if (value == null || !value.isTextual()) {
                    throw new Rejected("事件属性格式不正确");
                }
                String text = value.asText();
                Set<String> allowedValues = allowedProps.get(field.getKey());
                boolean ok = allowedValues == null ? CODE_PATTERN.matcher(text).matches() : allowedValues.contains(text);
                if (!ok) {
                    throw new Rejected("事件属性值不在允许范围内");
                }
                props.put(field.getKey(), text);
            }
        }
        String propsJson;
        try {
            propsJson = props.isEmpty() ? null : MAPPER.writeValueAsString(props);
        } catch (Exception e) {
            throw new Rejected("事件属性格式不正确");
        }
        return new ValidEvent(idNode.asText(), name, occurredAt, propsJson);
    }

    private static void requireOnlyKeys(JsonNode node, Set<String> allowed) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw new Rejected("上报内容包含未知字段");
            }
        }
    }

    private static String requireText(JsonNode node, String label) {
        if (node == null || !node.isTextual() || node.asText().isBlank() || node.asText().length() > 40) {
            throw new Rejected(label + "格式不正确");
        }
        return node.asText();
    }
}
