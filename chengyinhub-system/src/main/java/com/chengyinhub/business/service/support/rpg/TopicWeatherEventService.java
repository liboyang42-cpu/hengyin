package com.chengyinhub.business.service.support.rpg;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.WeatherEventRule;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.WeatherEventRuleMapper;
import com.chengyinhub.business.service.support.JourneyRpg;
import com.chengyinhub.business.service.support.JourneyRulesValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主题环境键（{@code journeyRules.rpg.environment}）与天气/时段彩蛋触发规则（{@code weather_event_rule}）的
 * 读取、管理端保存与玩家端求值服务。
 */
@Service
public class TopicWeatherEventService {

    private static final Logger log = LoggerFactory.getLogger(TopicWeatherEventService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9_]{1,24}$");
    private static final int MAX_RULES_PER_TOPIC = 20;
    private static final int MAX_VALUES_PER_RULE = 8;

    private static final List<String> DAYPART_VALUES = List.of("dawn", "morning", "afternoon", "evening", "night");
    private static final List<String> WEEKPART_VALUES = List.of("weekday", "weekend");
    private static final List<String> SEASON_VALUES = List.of("spring", "summer", "autumn", "winter");

    private final TopicEnvironmentService topicEnvironmentService;
    private final WeatherEventRuleMapper ruleMapper;
    private final CmsTopicMapper topicMapper;
    private final JourneyRulesValidator rulesValidator = new JourneyRulesValidator();
    private final Clock clock;

    @Autowired
    public TopicWeatherEventService(
            TopicEnvironmentService topicEnvironmentService,
            WeatherEventRuleMapper ruleMapper,
            CmsTopicMapper topicMapper) {
        this(topicEnvironmentService, ruleMapper, topicMapper, Clock.systemUTC());
    }

    public TopicWeatherEventService(
            TopicEnvironmentService topicEnvironmentService,
            WeatherEventRuleMapper ruleMapper,
            CmsTopicMapper topicMapper,
            Clock clock) {
        this.topicEnvironmentService = topicEnvironmentService;
        this.ruleMapper = ruleMapper;
        this.topicMapper = topicMapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** 玩家只读视角：{@code GET /api/play/environment}。 */
    public Map<String, Object> playerEnvironmentView(CmsTopic topic) {
        if (topic == null || topic.getId() == null) {
            throw new IllegalArgumentException("主题不存在");
        }
        JourneyRules rules = parseRulesSafely(topic.getJourneyRulesJson());
        boolean envEnabled = JourneyRpg.environmentEnabled(rules);
        List<WeatherEventRule> dbRules = safeActiveRules(topic.getId());
        boolean hasEventRules = !dbRules.isEmpty();

        List<String> keys = envEnabled ? JourneyRpg.environmentKeys(rules) : JourneyRpg.ENV_KEYS;
        Map<String, String> snapshot = (envEnabled || hasEventRules) && topicEnvironmentService != null
                ? topicEnvironmentService.resolveSnapshot(topic, keys)
                : Collections.<String, String>emptyMap();

        String sourceStatus;
        String fallbackReason = null;
        if (!envEnabled && !hasEventRules) {
            sourceStatus = "DISABLED";
        } else {
            String weather = snapshot.get(JourneyRpg.ENV_WEATHER);
            if (keys.contains(JourneyRpg.ENV_WEATHER) && (weather == null || JourneyRpg.ENV_UNKNOWN.equals(weather))) {
                sourceStatus = "FALLBACK_UNKNOWN";
                fallbackReason = "天气源暂不可用或未配置主题城市";
            } else {
                sourceStatus = "AVAILABLE";
            }
        }

        LocalTime nowLocal = ZonedDateTime.now(clock).withZoneSameInstant(ZONE).toLocalTime();
        List<Map<String, Object>> evaluatedEvents = new ArrayList<Map<String, Object>>();
        for (WeatherEventRule rule : dbRules) {
            evaluatedEvents.add(evaluateRuleView(rule, snapshot, nowLocal));
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("topicId", topic.getId());
        out.put("city", topic.getAddressName());
        out.put("enabled", Boolean.valueOf(envEnabled || hasEventRules));
        out.put("keys", envEnabled ? keys : Collections.<String>emptyList());
        out.put("environment", snapshot);
        out.put("sourceStatus", sourceStatus);
        if (fallbackReason != null) {
            out.put("fallbackReason", fallbackReason);
        }
        out.put("events", evaluatedEvents);
        return out;
    }

    /** 计算受 {@code weather_event_rule} 门控的点位锁定状态（只覆盖明确配了规则且当前未命中任何启用规则的点位）。 */
    public Map<Long, String> weatherLockedNodes(CmsTopic topic) {
        if (topic == null || topic.getId() == null) {
            return Collections.emptyMap();
        }
        List<WeatherEventRule> dbRules = safeActiveRules(topic.getId());
        if (dbRules.isEmpty()) {
            return Collections.emptyMap();
        }
        JourneyRules rules = parseRulesSafely(topic.getJourneyRulesJson());
        List<String> keys = JourneyRpg.environmentEnabled(rules) ? JourneyRpg.environmentKeys(rules) : JourneyRpg.ENV_KEYS;
        Map<String, String> snapshot = topicEnvironmentService == null
                ? Collections.<String, String>emptyMap()
                : topicEnvironmentService.resolveSnapshot(topic, keys);
        LocalTime nowLocal = ZonedDateTime.now(clock).withZoneSameInstant(ZONE).toLocalTime();

        Set<Long> unlockedNodes = new LinkedHashSet<Long>();
        Map<Long, String> firstLockReasons = new LinkedHashMap<Long, String>();
        for (WeatherEventRule rule : dbRules) {
            if (rule.getUnlockNodeId() == null || rule.getUnlockNodeId() <= 0L) {
                continue;
            }
            Map<String, Object> eval = evaluateRuleView(rule, snapshot, nowLocal);
            Long nodeId = rule.getUnlockNodeId();
            if (Boolean.TRUE.equals(eval.get("active"))) {
                unlockedNodes.add(nodeId);
            } else if (!firstLockReasons.containsKey(nodeId)) {
                Object lockReason = eval.get("lockReason");
                firstLockReasons.put(nodeId, lockReason == null ? "当前环境条件未满足" : String.valueOf(lockReason));
            }
        }
        for (Long unlocked : unlockedNodes) {
            firstLockReasons.remove(unlocked);
        }
        return firstLockReasons;
    }

    /** 管理端读取：{@code GET /business/topic/{id}/environment}。 */
    public Map<String, Object> adminGetConfig(CmsTopic topic) {
        if (topic == null || topic.getId() == null) {
            throw new IllegalArgumentException("主题不存在");
        }
        JourneyRules rules = parseRulesSafely(topic.getJourneyRulesJson());
        boolean envEnabled = JourneyRpg.environmentEnabled(rules);
        List<String> configuredKeys = envEnabled
                ? JourneyRpg.environmentKeys(rules)
                : new ArrayList<String>(JourneyRpg.ENV_KEYS);

        Map<String, Object> envConfig = new LinkedHashMap<String, Object>();
        envConfig.put("enabled", Boolean.valueOf(envEnabled));
        envConfig.put("keys", configuredKeys);

        Map<String, String> previewSnapshot = topicEnvironmentService == null
                ? Collections.<String, String>emptyMap()
                : topicEnvironmentService.resolveSnapshot(topic, JourneyRpg.ENV_KEYS);

        List<WeatherEventRule> rows = safeAllRules(topic.getId());
        List<Map<String, Object>> ruleViews = new ArrayList<Map<String, Object>>();
        for (WeatherEventRule row : rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", row.getId());
            item.put("ruleName", row.getRuleName());
            item.put("conditionKey", row.getConditionKey());
            item.put("conditionValues", parseValues(row.getConditionValues()));
            item.put("unlockNodeId", row.getUnlockNodeId());
            item.put("taskLabel", row.getTaskLabel());
            item.put("windowStart", row.getWindowStart());
            item.put("windowEnd", row.getWindowEnd());
            item.put("enabled", Boolean.valueOf(!Integer.valueOf(0).equals(row.getEnabled())));
            ruleViews.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("topicId", topic.getId());
        out.put("city", topic.getAddressName());
        out.put("environmentConfig", envConfig);
        out.put("currentEnvironment", previewSnapshot);
        out.put("rules", ruleViews);
        out.put("weatherValues", TopicEnvironmentService.WEATHER_VALUES);
        out.put("envKeys", JourneyRpg.ENV_KEYS);
        out.put("valueOptions", Map.of(
                JourneyRpg.ENV_WEATHER, TopicEnvironmentService.WEATHER_VALUES,
                JourneyRpg.ENV_DAYPART, DAYPART_VALUES,
                JourneyRpg.ENV_WEEKPART, WEEKPART_VALUES,
                JourneyRpg.ENV_SEASON, SEASON_VALUES
        ));
        return out;
    }

    /** 管理端保存：{@code PUT /business/topic/{id}/environment}。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> adminSaveConfig(CmsTopic topic, SaveRequest request) {
        if (topic == null || topic.getId() == null) {
            throw new IllegalArgumentException("主题不存在");
        }
        boolean enabled = request != null && Boolean.TRUE.equals(request.enabled);
        List<String> keys = normalizeKeys(request == null ? null : request.keys, enabled);
        List<WeatherEventRule> normalizedRules = validateAndBuildRules(
                topic.getId(), request == null ? null : request.rules);

        String updatedRulesJson = updateEnvironmentInJourneyRules(topic.getJourneyRulesJson(), enabled, keys);
        CmsTopic patch = new CmsTopic();
        patch.setId(topic.getId());
        patch.setJourneyRulesJson(updatedRulesJson);
        if (topicMapper.updateCmsTopic(patch) != 1) {
            throw new IllegalStateException("保存主题环境配置失败");
        }

        if (ruleMapper != null) {
            ruleMapper.softDeleteByTopicId(topic.getId());
            for (WeatherEventRule rule : normalizedRules) {
                ruleMapper.insertRule(rule);
            }
        }

        topic.setJourneyRulesJson(updatedRulesJson);
        return adminGetConfig(topic);
    }

    String updateEnvironmentInJourneyRules(String currentJson, boolean enabled, List<String> keys) {
        try {
            ObjectNode root;
            if (currentJson == null || currentJson.isBlank()) {
                if (!enabled) {
                    return "";
                }
                root = JSON.createObjectNode();
                root.put("schemaVersion", JourneyRulesValidator.SCHEMA_VERSION);
                root.put("stateEnabled", true);
            } else {
                JsonNode parsed = JSON.readTree(currentJson);
                if (!(parsed instanceof ObjectNode)) {
                    throw new IllegalArgumentException("现有 journeyRules 不是对象");
                }
                root = (ObjectNode) parsed.deepCopy();
                if (enabled && !root.path("checkEnabled").asBoolean(false)
                        && !root.path("stateEnabled").asBoolean(false)) {
                    root.put("stateEnabled", true);
                }
            }
            ObjectNode rpg = root.has("rpg") && root.get("rpg").isObject()
                    ? (ObjectNode) root.get("rpg")
                    : root.putObject("rpg");
            ObjectNode env = rpg.putObject("environment");
            env.put("enabled", enabled);
            ArrayNode keysNode = env.putArray("keys");
            for (String key : keys) {
                keysNode.add(key);
            }
            String validated = rulesValidator.validateAndNormalize(JSON.writeValueAsString(root));
            return validated == null ? "" : validated;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("更新 journeyRules.rpg.environment 失败: " + e.getMessage());
        }
    }

    private List<String> normalizeKeys(List<String> rawKeys, boolean enabled) {
        if (rawKeys == null || rawKeys.isEmpty()) {
            return new ArrayList<String>(JourneyRpg.ENV_KEYS);
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (String raw : rawKeys) {
            String key = raw == null ? "" : raw.trim();
            if (!JourneyRpg.ENV_KEYS.contains(key) || !seen.add(key)) {
                throw new IllegalArgumentException("rpg.environment.keys 只能是不重复的 weather、daypart、weekpart、season");
            }
        }
        List<String> ordered = new ArrayList<String>();
        for (String canonical : JourneyRpg.ENV_KEYS) {
            if (seen.contains(canonical)) {
                ordered.add(canonical);
            }
        }
        if (enabled && ordered.isEmpty()) {
            throw new IllegalArgumentException("开启环境感知时至少选择 1 个环境键");
        }
        return ordered;
    }

    private List<WeatherEventRule> validateAndBuildRules(Long topicId, List<RuleInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return Collections.emptyList();
        }
        if (inputs.size() > MAX_RULES_PER_TOPIC) {
            throw new IllegalArgumentException("单个主题最多配置 " + MAX_RULES_PER_TOPIC + " 条环境触发规则");
        }
        List<WeatherEventRule> out = new ArrayList<WeatherEventRule>();
        for (int i = 0; i < inputs.size(); i++) {
            RuleInput input = inputs.get(i);
            if (input == null) {
                throw new IllegalArgumentException("规则第 " + (i + 1) + " 条不能为空");
            }
            String key = input.conditionKey == null ? "" : input.conditionKey.trim();
            if (!JourneyRpg.ENV_KEYS.contains(key)) {
                throw new IllegalArgumentException("规则第 " + (i + 1) + " 条的 conditionKey 无效：" + key);
            }
            List<String> values = normalizeValues(key, input.conditionValues, i + 1);
            String ruleName = input.ruleName == null ? "" : input.ruleName.trim();
            if (ruleName.length() > 64) {
                throw new IllegalArgumentException("规则名称不能超过 64 个字");
            }
            if (ruleName.isEmpty()) {
                ruleName = defaultRuleName(key, values);
            }
            String taskLabel = input.taskLabel == null ? null : input.taskLabel.trim();
            if (taskLabel != null && taskLabel.isEmpty()) {
                taskLabel = null;
            }
            if (taskLabel != null && taskLabel.length() > 120) {
                throw new IllegalArgumentException("限时任务说明不能超过 120 个字");
            }
            Long unlockNodeId = input.unlockNodeId;
            if (unlockNodeId != null && unlockNodeId <= 0L) {
                throw new IllegalArgumentException("解锁点位 ID 必须为正整数");
            }
            String start = normalizeTimeString(input.windowStart, "windowStart");
            String end = normalizeTimeString(input.windowEnd, "windowEnd");
            if ((start == null) != (end == null)) {
                throw new IllegalArgumentException("时段窗口必须同时填写开始和结束时间，或同时留空");
            }

            WeatherEventRule row = new WeatherEventRule();
            row.setTopicId(topicId);
            row.setRuleName(ruleName);
            row.setConditionKey(key);
            row.setConditionValues(writeJsonArray(values));
            row.setUnlockNodeId(unlockNodeId);
            row.setTaskLabel(taskLabel);
            row.setWindowStart(start);
            row.setWindowEnd(end);
            row.setEnabled(Boolean.FALSE.equals(input.enabled) ? 0 : 1);
            out.add(row);
        }
        return out;
    }

    private List<String> normalizeValues(String key, List<String> rawValues, int rowNumber) {
        if (rawValues == null || rawValues.isEmpty()) {
            throw new IllegalArgumentException("规则第 " + rowNumber + " 条至少需要 1 个触发条件取值");
        }
        Set<String> allowed = allowedValuesForKey(key);
        Set<String> dedup = new LinkedHashSet<String>();
        for (String raw : rawValues) {
            String value = raw == null ? "" : raw.trim();
            if (!CODE_PATTERN.matcher(value).matches() || (!allowed.isEmpty() && !allowed.contains(value))) {
                throw new IllegalArgumentException("规则第 " + rowNumber + " 条包含不支持的 " + key + " 取值：" + value);
            }
            dedup.add(value);
        }
        if (dedup.size() > MAX_VALUES_PER_RULE) {
            throw new IllegalArgumentException("单条规则最多选择 " + MAX_VALUES_PER_RULE + " 个条件取值");
        }
        return new ArrayList<String>(dedup);
    }

    private Set<String> allowedValuesForKey(String key) {
        switch (key) {
            case JourneyRpg.ENV_WEATHER: return new LinkedHashSet<String>(TopicEnvironmentService.WEATHER_VALUES);
            case JourneyRpg.ENV_DAYPART: return new LinkedHashSet<String>(DAYPART_VALUES);
            case JourneyRpg.ENV_WEEKPART: return new LinkedHashSet<String>(WEEKPART_VALUES);
            case JourneyRpg.ENV_SEASON: return new LinkedHashSet<String>(SEASON_VALUES);
            default: return Collections.emptySet();
        }
    }

    private Map<String, Object> evaluateRuleView(
            WeatherEventRule rule,
            Map<String, String> snapshot,
            LocalTime nowLocal) {
        List<String> targetValues = parseValues(rule.getConditionValues());
        String actual = snapshot == null ? null : snapshot.get(rule.getConditionKey());
        if (actual == null || actual.isEmpty()) {
            actual = JourneyRpg.ENV_UNKNOWN;
        }
        boolean windowMatched = matchesWindow(nowLocal, rule.getWindowStart(), rule.getWindowEnd());
        boolean envKnown = !JourneyRpg.ENV_UNKNOWN.equals(actual);
        boolean conditionMatched = envKnown && targetValues.contains(actual);
        boolean active = windowMatched && conditionMatched;

        String reason;
        String lockReason = null;
        if (!windowMatched) {
            reason = "OUTSIDE_WINDOW";
            lockReason = "需在 " + shortTime(rule.getWindowStart()) + "–" + shortTime(rule.getWindowEnd()) + " 时段开放";
        } else if (!envKnown) {
            reason = "ENVIRONMENT_UNKNOWN";
            lockReason = "天气/环境信息暂不可用，请稍后再试";
        } else if (!conditionMatched) {
            reason = "CONDITION_NOT_MET";
            lockReason = buildConditionLockReason(rule.getConditionKey(), targetValues);
        } else {
            reason = "MATCHED";
        }

        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", rule.getId());
        row.put("ruleName", rule.getRuleName());
        row.put("conditionKey", rule.getConditionKey());
        row.put("conditionValues", targetValues);
        row.put("unlockNodeId", rule.getUnlockNodeId());
        row.put("taskLabel", rule.getTaskLabel());
        row.put("windowStart", rule.getWindowStart());
        row.put("windowEnd", rule.getWindowEnd());
        row.put("active", Boolean.valueOf(active));
        row.put("reason", reason);
        if (lockReason != null) {
            row.put("lockReason", lockReason);
        }
        return row;
    }

    static boolean matchesWindow(LocalTime now, String rawStart, String rawEnd) {
        if (rawStart == null || rawStart.isBlank() || rawEnd == null || rawEnd.isBlank()) {
            return true;
        }
        LocalTime start = parseTime(rawStart);
        LocalTime end = parseTime(rawEnd);
        if (start == null || end == null) {
            return false;
        }
        if (start.equals(end)) {
            return true;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && !now.isAfter(end);
        }
        // Overnight window such as 20:00 .. 05:00
        return !now.isBefore(start) || !now.isAfter(end);
    }

    private static String normalizeTimeString(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        LocalTime parsed = parseTime(raw.trim());
        if (parsed == null) {
            throw new IllegalArgumentException(field + " 时间格式应为 HH:mm 或 HH:mm:ss");
        }
        return parsed.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    private static LocalTime parseTime(String raw) {
        try {
            if (raw.length() == 5) {
                return LocalTime.parse(raw, DateTimeFormatter.ofPattern("HH:mm"));
            }
            return LocalTime.parse(raw, DateTimeFormatter.ofPattern("HH:mm:ss"));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String shortTime(String time) {
        return time != null && time.length() >= 5 ? time.substring(0, 5) : String.valueOf(time);
    }

    private static String buildConditionLockReason(String key, List<String> values) {
        List<String> labels = new ArrayList<String>(values.size());
        for (String v : values) {
            labels.add(displayConditionValue(v));
        }
        String joined = String.join(" / ", labels);
        switch (key) {
            case JourneyRpg.ENV_WEATHER: return "需在特定天气（" + joined + "）触发";
            case JourneyRpg.ENV_DAYPART: return "需在指定时段（" + joined + "）触发";
            case JourneyRpg.ENV_WEEKPART: return "仅在（" + joined + "）开放";
            case JourneyRpg.ENV_SEASON: return "仅在指定季节（" + joined + "）开放";
            default: return "环境条件未满足（" + joined + "）";
        }
    }

    private static String displayConditionValue(String code) {
        if (code == null) {
            return "";
        }
        switch (code) {
            case "clear": return "晴天";
            case "cloudy": return "多云/阴";
            case "rain": return "雨天";
            case "snow": return "雪天";
            case "fog": return "雾霾";
            case "wind": return "大风";
            case "storm": return "雷暴";
            case "dawn": return "清晨";
            case "morning": return "上午";
            case "afternoon": return "午后";
            case "evening": return "傍晚";
            case "night": return "夜晚";
            case "weekday": return "工作日";
            case "weekend": return "周末";
            case "spring": return "春季";
            case "summer": return "夏季";
            case "autumn": return "秋季";
            case "winter": return "冬季";
            default: return code;
        }
    }

    private static String defaultRuleName(String key, List<String> values) {
        return key + ":" + String.join(",", values);
    }

    private List<WeatherEventRule> safeActiveRules(Long topicId) {
        if (ruleMapper == null || topicId == null) {
            return Collections.emptyList();
        }
        try {
            List<WeatherEventRule> rows = ruleMapper.selectActiveByTopicId(topicId);
            return rows == null ? Collections.<WeatherEventRule>emptyList() : rows;
        } catch (RuntimeException missingTableOrDbError) {
            log.warn("weather_event_rule read skipped for topic {}: {}", topicId, missingTableOrDbError.getMessage());
            return Collections.emptyList();
        }
    }

    private List<WeatherEventRule> safeAllRules(Long topicId) {
        if (ruleMapper == null || topicId == null) {
            return Collections.emptyList();
        }
        try {
            List<WeatherEventRule> rows = ruleMapper.selectAllByTopicId(topicId);
            return rows == null ? Collections.<WeatherEventRule>emptyList() : rows;
        } catch (RuntimeException missingTableOrDbError) {
            log.warn("weather_event_rule admin read skipped for topic {}: {}", topicId, missingTableOrDbError.getMessage());
            return Collections.emptyList();
        }
    }

    private JourneyRules parseRulesSafely(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JourneyRules.parse(json);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    static List<String> parseValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            try {
                JsonNode node = JSON.readTree(trimmed);
                if (node.isArray()) {
                    List<String> out = new ArrayList<String>();
                    for (JsonNode child : node) {
                        if (child.isTextual() && !child.asText().isBlank()) {
                            out.add(child.asText().trim());
                        }
                    }
                    return out;
                }
            } catch (Exception ignored) {
                // fallback to comma split
            }
        }
        List<String> out = new ArrayList<String>();
        for (String part : Arrays.asList(trimmed.split(","))) {
            String clean = part.trim();
            if (!clean.isEmpty()) {
                out.add(clean);
            }
        }
        return out;
    }

    private static String writeJsonArray(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception e) {
            return String.join(",", values);
        }
    }

    public static class SaveRequest {
        private Boolean enabled;
        private List<String> keys;
        private List<RuleInput> rules;

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public List<String> getKeys() { return keys; }
        public void setKeys(List<String> keys) { this.keys = keys; }
        public List<RuleInput> getRules() { return rules; }
        public void setRules(List<RuleInput> rules) { this.rules = rules; }
    }

    public static class RuleInput {
        private String ruleName;
        private String conditionKey;
        private List<String> conditionValues;
        private Long unlockNodeId;
        private String taskLabel;
        private String windowStart;
        private String windowEnd;
        private Boolean enabled;

        public String getRuleName() { return ruleName; }
        public void setRuleName(String ruleName) { this.ruleName = ruleName; }
        public String getConditionKey() { return conditionKey; }
        public void setConditionKey(String conditionKey) { this.conditionKey = conditionKey; }
        public List<String> getConditionValues() { return conditionValues; }
        public void setConditionValues(List<String> conditionValues) { this.conditionValues = conditionValues; }
        public Long getUnlockNodeId() { return unlockNodeId; }
        public void setUnlockNodeId(Long unlockNodeId) { this.unlockNodeId = unlockNodeId; }
        public String getTaskLabel() { return taskLabel; }
        public void setTaskLabel(String taskLabel) { this.taskLabel = taskLabel; }
        public String getWindowStart() { return windowStart; }
        public void setWindowStart(String windowStart) { this.windowStart = windowStart; }
        public String getWindowEnd() { return windowEnd; }
        public void setWindowEnd(String windowEnd) { this.windowEnd = windowEnd; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }
}
