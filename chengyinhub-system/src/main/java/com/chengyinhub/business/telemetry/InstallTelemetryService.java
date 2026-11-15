package com.chengyinhub.business.telemetry;

import com.chengyinhub.business.telemetry.mapper.InstallTelemetryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P6 measurement:匿名安装事件、账号关联与 MetricKit 载荷。
 *
 * <p>总开关 {@code chengyin.telemetry.enabled}(默认开):关掉后三条入口都只回 accepted=0,不落库。
 */
@Service
public class InstallTelemetryService {

    private static final Logger log = LoggerFactory.getLogger(InstallTelemetryService.class);

    /** MetricKit 单条载荷上限(UTF-8 字节);客户端同样按此截断/丢弃。 */
    public static final int MAX_METRIC_PAYLOAD_BYTES = 256 * 1024;
    /** 单安装号每天最多收的 MetricKit 载荷数(MetricKit 本身约一天一次,留余量)。 */
    public static final int MAX_METRIC_PAYLOADS_PER_DAY = 12;
    private static final Set<String> METRIC_KINDS = Set.of("metric", "diagnostic");
    private static final Set<String> METRIC_KEYS = Set.of("installId", "kind", "appVersion", "osVersion", "payload");
    private static final Pattern SHORT_TEXT = Pattern.compile("^[0-9A-Za-z .()_-]{1,64}$");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private InstallTelemetryMapper mapper;

    @Value("${chengyin.telemetry.enabled:true}")
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    /** 校验整批后逐条幂等写入;返回新写入条数。校验失败抛 {@link InstallEventSchema.Rejected}。 */
    public int recordAnonymousBatch(String body, long nowMillis) {
        InstallEventSchema.ValidBatch batch = InstallEventSchema.parseBatch(body, nowMillis);
        if (!enabled) {
            return 0;
        }
        int accepted = 0;
        for (InstallEventSchema.ValidEvent event : batch.events()) {
            accepted += mapper.insertEvent(batch.installId(), event.eventId(), event.name(), event.propsJson(),
                    batch.platform(), batch.appVersion(), new Date(event.occurredAt()));
        }
        return accepted;
    }

    /** 登录后把安装号关联到当前账号;同一对只记一次。返回是否新记。 */
    public boolean link(String installId, long memberId) {
        if (!InstallEventSchema.isInstallId(installId)) {
            throw new InstallEventSchema.Rejected("安装号格式不正确");
        }
        if (memberId <= 0) {
            throw new InstallEventSchema.Rejected("请先登录");
        }
        if (!enabled) {
            return false;
        }
        return mapper.insertLink(installId, memberId) > 0;
    }

    /**
     * MetricKit 指标/诊断载荷。payload 必须是 JSON 对象,序列化后不超过上限;
     * 只原样保存 MetricKit 生成的 JSON(不含个人信息),外层字段严格白名单。
     */
    public int recordMetricPayload(String body, long nowMillis) {
        if (body == null || body.isBlank()) {
            throw new InstallEventSchema.Rejected("上报内容不能为空");
        }
        if (body.length() > MAX_METRIC_PAYLOAD_BYTES + 1024) {
            throw new InstallEventSchema.Rejected("上报内容过大");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new InstallEventSchema.Rejected("上报内容格式不正确");
        }
        if (root == null || !root.isObject()) {
            throw new InstallEventSchema.Rejected("上报内容格式不正确");
        }
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            if (!METRIC_KEYS.contains(names.next())) {
                throw new InstallEventSchema.Rejected("上报内容包含未知字段");
            }
        }
        String installId = InstallEventSchema.requireInstallId(root.get("installId"));
        String kind = textOrNull(root.get("kind"));
        if (kind == null || !METRIC_KINDS.contains(kind)) {
            throw new InstallEventSchema.Rejected("载荷类型不正确");
        }
        String appVersion = shortText(root.get("appVersion"), "版本");
        String osVersion = shortText(root.get("osVersion"), "系统版本");
        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new InstallEventSchema.Rejected("载荷格式不正确");
        }
        String payloadJson;
        try {
            payloadJson = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new InstallEventSchema.Rejected("载荷格式不正确");
        }
        int bytes = payloadJson.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_METRIC_PAYLOAD_BYTES) {
            throw new InstallEventSchema.Rejected("载荷过大");
        }
        if (!enabled) {
            return 0;
        }
        Date since = Date.from(LocalDate.ofInstant(new Date(nowMillis).toInstant(), ZONE).atStartOfDay(ZONE).toInstant());
        if (mapper.countMetricPayloadsSince(installId, since) >= MAX_METRIC_PAYLOADS_PER_DAY) {
            log.info("metrickit_payload_daily_cap installId={} kind={}", installId, kind);
            return 0;
        }
        return mapper.insertMetricPayload(installId, kind, appVersion, osVersion, payloadJson, bytes);
    }

    /**
     * 管理端登录前漏斗。窗口 [start, end] 按上海日历日,含首尾;调用方已限定窗口长度。
     * steps 按 {@link InstallEventSchema#FUNNEL_ORDER} 排序,缺数据的步骤补 0,不省略。
     */
    public Map<String, Object> preSignInFunnel(LocalDate start, LocalDate end) {
        Date from = Date.from(start.atStartOfDay(ZONE).toInstant());
        Date to = Date.from(end.plusDays(1).atStartOfDay(ZONE).toInstant());
        Map<String, Map<String, Object>> byName = new HashMap<>();
        for (Map<String, Object> row : mapper.selectEventCounts(from, to)) {
            Object name = row.get("eventName");
            if (name != null) {
                byName.put(name.toString(), row);
            }
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        int order = 1;
        for (String name : InstallEventSchema.FUNNEL_ORDER) {
            Map<String, Object> row = byName.get(name);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("stepOrder", order++);
            step.put("stepName", name);
            step.put("installCount", row == null ? 0L : toLong(row.get("installCount")));
            step.put("eventCount", row == null ? 0L : toLong(row.get("eventCount")));
            steps.add(step);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("startDate", start.toString());
        data.put("endDate", end.toString());
        data.put("steps", steps);
        data.put("linkedInstallCount", mapper.countLinkedInstalls(from, to));
        data.put("collectionEnabled", enabled);
        return data;
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static String shortText(JsonNode node, String label) {
        String text = textOrNull(node);
        if (text == null || !SHORT_TEXT.matcher(text).matches()) {
            throw new InstallEventSchema.Rejected(label + "格式不正确");
        }
        return text;
    }
}
