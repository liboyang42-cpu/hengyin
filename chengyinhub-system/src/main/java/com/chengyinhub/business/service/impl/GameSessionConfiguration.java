package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.GameSession;
import com.chengyinhub.business.domain.GameStationRun;
import com.chengyinhub.business.domain.GameTeamRoleAssignment;
import com.chengyinhub.business.domain.GamePlayerNodeOutcome;
import com.chengyinhub.business.service.GameSessionException;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Module validation and frozen-session configuration selectors/public projections.
 * No storage, authorization, clocks or gameplay execution: callers retain those boundaries.
 * Reuses the runtime's existing JSON codec and returns the original projection shapes.
 */
final class GameSessionConfiguration {
    static final Set<String> TASK_INPUT_TYPES = new HashSet<>(Arrays.asList("TEXT", "SCAN", "PHOTO"));
    private static final int STORY_RULE_MAX = 16;
    private static final Set<String> STORY_CONDITION_OPS = new HashSet<>(Arrays.asList(
            "EQ", "NE", "GT", "GTE", "LT", "LTE"));
    static final String STORY_TAGS_KEY = "__tags";
    private final ObjectMapper objectMapper;

    GameSessionConfiguration(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    boolean isGameModuleConfigEnabled(String configJson) {
        try {
            validateModuleConfig(configJson);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    JsonNode readRouteJson(String raw) {
        try { return objectMapper.readTree(raw); }
        catch (Exception invalid) { throw error("GAME_CONFIG_INVALID", "运行快照不可用"); }
    }

    ReadyConfig readyConfig(Map<String, Object> payload, GameSession session, GameStationRun station) {
        Date startAt = DateUtils.parseDate(payload.get("serviceStartAt"));
        Date endAt = DateUtils.parseDate(payload.get("serviceEndAt"));
        if ((startAt == null || endAt == null) && payload.get("serviceWindow") instanceof Map) {
            Map<?, ?> window = (Map<?, ?>) payload.get("serviceWindow");
            startAt = DateUtils.parseDate(window.get("start"));
            endAt = DateUtils.parseDate(window.get("end"));
        }
        Integer capacity = Convert.toInt(payload.get("capacity"), 0);
        Object checklist = payload.get("checklist");
        if (startAt == null || endAt == null || !endAt.after(startAt) || capacity < 1 || capacity > 10000
                || !(checklist instanceof List)) {
            throw error("GAME_STATION_CONFIG_INVALID", "请完整填写服务时间、容量和准备清单");
        }
        List<Map<String, Object>> canonicalChecklist = configuredChecklist(sessionConfig(session), station.getStationCode());
        Map<String, Map<String, Object>> configuredByCode = new LinkedHashMap<>();
        for (Map<String, Object> configured : canonicalChecklist) {
            configuredByCode.put(String.valueOf(configured.get("code")), configured);
        }
        Set<String> checked = new HashSet<>();
        for (Object item : (List<?>) checklist) {
            if (!(item instanceof Map)) throw error("GAME_STATION_CONFIG_INVALID", "准备清单格式不正确");
            Map<?, ?> row = (Map<?, ?>) item;
            String code = row.get("code") == null ? null : String.valueOf(row.get("code")).trim();
            if (StringUtils.isBlank(code) || !configuredByCode.containsKey(code) || !checked.add(code)) {
                throw error("GAME_STATION_CONFIG_INVALID", "准备清单包含未知或重复项目");
            }
            if (!Boolean.TRUE.equals(row.get("checked"))) {
                throw error("GAME_STATION_CONFIG_INVALID", "准备清单尚未全部完成");
            }
        }
        if (checked.size() != configuredByCode.size()) throw error("GAME_STATION_CONFIG_INVALID", "准备清单尚未全部完成");
        for (Map<String, Object> configured : canonicalChecklist) configured.put("checked", true);
        Map<String, Object> preparation = new LinkedHashMap<>();
        preparation.put("checklist", canonicalChecklist);
        preparation.put("note", text(payload.get("note"), 200));
        return new ReadyConfig(startAt, endAt, capacity, preparation);
    }

    private List<Map<String, Object>> configuredChecklist(JsonNode config, String stationCode) {
        JsonNode station = configuredStation(config.path("stations"), stationCode, null);
        if (station == null || !station.path("preparationChecklist").isArray()) return Collections.emptyList();
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (JsonNode item : station.path("preparationChecklist")) {
            String code = item.path("code").asText();
            String label = item.path("label").asText();
            if (StringUtils.isBlank(code) || StringUtils.isBlank(label) || !codes.add(code)) {
                throw error("GAME_CONFIG_INVALID", "站点准备清单编号和文案必须唯一且非空");
            }
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("code", code);
            canonical.put("label", label);
            items.add(canonical);
        }
        return items;
    }

    JsonNode configuredStation(JsonNode stations, String stationCode, String nodeName) {
        if (stations == null || !stations.isArray()) return null;
        for (JsonNode station : stations) {
            if (stationCode != null && stationCode.equals(station.path("code").asText())) return station;
            if (nodeName != null && nodeName.equals(station.path("nodeName").asText())) return station;
        }
        return null;
    }

    JsonNode validateModuleConfig(String json) {
        if (StringUtils.isBlank(json) || json.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw error("GAME_CONFIG_INVALID", "主题未配置可用的游戏模组");
        }
        JsonNode config;
        try { config = objectMapper.readTree(json); }
        catch (Exception ex) { throw error("GAME_CONFIG_INVALID", "游戏模组配置不是有效 JSON"); }
        if (!config.isObject() || config.path("schemaVersion").asInt(-1) != 1
                || !config.path("roles").isArray() || config.path("roles").size() < 1
                || !config.path("stations").isArray() || config.path("stations").size() < 1) {
            throw error("GAME_CONFIG_INVALID", "游戏模组缺少版本、角色或站点");
        }
        Set<String> roles = new HashSet<>();
        for (JsonNode role : config.path("roles")) {
            String code = role.path("code").asText();
            if (StringUtils.isBlank(code) || !roles.add(code)) throw error("GAME_CONFIG_INVALID", "剧情角色编号重复或为空");
        }
        Set<String> stationCodes = new HashSet<>();
        Set<String> stationNames = new HashSet<>();
        for (JsonNode station : config.path("stations")) {
            String code = station.path("code").asText();
            String nodeName = station.path("nodeName").asText();
            if (StringUtils.isBlank(code) || StringUtils.isBlank(nodeName)
                    || !stationCodes.add(code) || !stationNames.add(nodeName)) {
                throw error("GAME_CONFIG_INVALID", "站点编号和主题节点名必须唯一且非空");
            }
            Set<String> checklistCodes = new HashSet<>();
            for (JsonNode item : station.path("preparationChecklist")) {
                String itemCode = item.path("code").asText();
                String label = item.path("label").asText();
                if (StringUtils.isBlank(itemCode) || StringUtils.isBlank(label) || !checklistCodes.add(itemCode)) {
                    throw error("GAME_CONFIG_INVALID", "站点准备清单编号和文案必须唯一且非空");
                }
            }
            JsonNode task = station.path("playerTask");
            String inputType = upper(task.path("inputType").asText());
            String merchantInstruction = station.path("merchantInstruction").asText();
            String hiddenInfoReminder = station.path("hiddenInfoReminder").asText();
            if (StringUtils.isBlank(task.path("taskCode").asText())
                    || StringUtils.isBlank(task.path("prompt").asText())
                    || !TASK_INPUT_TYPES.contains(inputType)
                    || StringUtils.isBlank(merchantInstruction) || merchantInstruction.length() > 200
                    || StringUtils.isBlank(hiddenInfoReminder) || hiddenInfoReminder.length() > 200) {
                throw error("GAME_CONFIG_INVALID", "每个站点都必须配置公开任务类型和安全运营说明");
            }
            boolean verificationRequired = task.path("verificationRequired").asBoolean(false);
            String completionPolicy = upper(task.path("completionPolicy").asText());
            if ("PHOTO".equals(inputType)
                    && (verificationRequired || !"EVIDENCE_ONLY".equals(completionPolicy))) {
                throw error("GAME_CONFIG_INVALID", "照片任务仅支持明确的证据留档完成策略");
            }
            if (!"PHOTO".equals(inputType) && !verificationRequired
                    && !task.path("expectedHash").asText().matches("[0-9a-f]{64}")) {
                throw error("GAME_CONFIG_INVALID", "自动核验仅支持带 expectedHash 的文字或扫码任务");
            }
            if (task.has("hintPolicy")) validateHintPolicy(task.path("hintPolicy"));
        }
        if (config.has("fallbackPlans") && !config.path("fallbackPlans").isArray()) {
            throw error("GAME_CONFIG_INVALID", "备用方案配置格式不正确");
        }
        Set<String> fallbackPlanKeys = new HashSet<>();
        for (JsonNode plan : config.path("fallbackPlans")) {
            String planCode = plan.path("planCode").asText();
            int version = plan.path("version").asInt(0);
            String sourceCode = plan.path("sourceStationCode").asText();
            String targetCode = plan.path("targetStationCode").asText();
            String message = plan.path("playerMessage").asText();
            if (!planCode.matches("[A-Z][A-Z0-9_]{0,63}") || version <= 0
                    || !fallbackPlanKeys.add(planCode + "#" + version)
                    || !"APPROVED".equals(plan.path("status").asText())
                    || !stationCodes.contains(sourceCode) || !stationCodes.contains(targetCode)
                    || sourceCode.equals(targetCode) || StringUtils.isBlank(message) || message.length() > 200) {
                throw error("GAME_CONFIG_INVALID", "备用方案必须是唯一、有效且已批准的站点映射");
            }
        }
        Set<Long> chapterIds = new HashSet<>();
        for (JsonNode chapter : config.path("chapters")) {
            long chapterId = chapter.path("chapterId").asLong(0);
            if (chapterId <= 0 || !chapterIds.add(chapterId)) {
                throw error("GAME_CONFIG_INVALID", "剧情章节编号必须唯一且为正数");
            }
        }
        validateStoryVariables(config.path("story").path("variables"), "剧情初始变量");
        Set<String> choiceIds = new HashSet<>();
        for (JsonNode choice : config.path("story").path("choices")) {
            String choiceId = choice.path("id").asText();
            String stationCode = choice.path("stationCode").asText();
            String choiceGroup = choice.path("choiceGroup").asText();
            long chapterId = choice.path("chapterId").asLong(0);
            if (StringUtils.isBlank(choiceId) || !choiceIds.add(choiceId)
                    || (StringUtils.isNotBlank(stationCode) && !stationCodes.contains(stationCode))
                    || (choice.has("chapterId") && !chapterIds.contains(chapterId))
                    || (StringUtils.isNotBlank(choiceGroup)
                        && !choiceGroup.matches("[A-Z][A-Z0-9_]{0,63}"))) {
                throw error("GAME_CONFIG_INVALID", "剧情行动编号必须唯一且站点必须有效");
            }
            JsonNode allowedRoles = choice.path("allowedRoles");
            if (choice.has("allowedRoles") && !allowedRoles.isArray()) {
                throw error("GAME_CONFIG_INVALID", "剧情行动角色范围格式不正确");
            }
            for (JsonNode allowedRole : allowedRoles) {
                if (!allowedRole.isTextual() || !roles.contains(allowedRole.asText())) {
                    throw error("GAME_CONFIG_INVALID", "剧情行动包含无效角色");
                }
            }
            validateStoryEffects(choice.path("effects"));
        }
        for (JsonNode ending : config.path("story").path("endings")) {
            validateStoryConditions(ending);
        }
        return config;
    }

    /**
     * 剧情行动效果:数组 = 路线图写法(SET / INC / ADD_TAG),按契约校验 op、整数范围与条数;
     * 对象 = 旧写法,只要求值为整数(数组写法的条件只认数值变量,非整数会被静默当成缺失)。
     * 声明不合法时在准备阶段就拒绝,不落任何一局。
     */
    private void validateStoryEffects(JsonNode effects) {
        if (effects != null && effects.isObject()) {
            validateStoryVariables(effects, "剧情行动效果");
            return;
        }
        if (effects == null || effects.isNull() || !effects.isArray()) return;
        requireStoryRuleCount(effects, "剧情行动效果");
        for (JsonNode effect : effects) {
            if (!effect.isObject()) throw error("GAME_CONFIG_INVALID", "剧情行动效果条目格式不正确");
            String op = effect.path("op").asText();
            if ("ADD_TAG".equals(op)) {
                requireStoryTag(effect.path("value"), "剧情行动效果");
                continue;
            }
            if (!"SET".equals(op) && !"INC".equals(op)) {
                throw error("GAME_CONFIG_INVALID", "剧情行动效果的 op 只能是 SET、INC 或 ADD_TAG");
            }
            if (StringUtils.isBlank(effect.path("var").asText()) || !isStoryRuleValue(effect.path("value"))) {
                throw error("GAME_CONFIG_INVALID", "剧情行动效果必须带变量名和 -99 到 99 的整数");
            }
        }
    }

    /**
     * 结局条件:数组 = 路线图写法,按契约校验 op、整数范围与条数;对象 = 旧写法,原样放行。
     * 数组为空表示无条件通过,只有 fallback 结局允许,其余明说条件却不写条件的结局一律拒绝,
     * 避免留空静默命中第一个结局。NODE_COMPLETED 需要局内会话才能核实「本队已完成站点」,
     * 配置校验期取不到,故这里直接拒绝,不让一条无法核实的规则带进快照。
     */
    private void validateStoryConditions(JsonNode ending) {
        JsonNode conditions = ending.path("conditions");
        if (conditions == null || conditions.isNull() || !conditions.isArray()) return;
        if (conditions.size() == 0 && !ending.path("fallback").asBoolean(false)) {
            throw error("GAME_CONFIG_INVALID", "结局条件不能为空;兜底结局请用 fallback");
        }
        requireStoryRuleCount(conditions, "结局条件");
        for (JsonNode condition : conditions) {
            if (!condition.isObject()) throw error("GAME_CONFIG_INVALID", "结局条件条目格式不正确");
            String op = condition.path("op").asText();
            if ("NODE_COMPLETED".equals(op)) {
                throw error("GAME_CONFIG_INVALID", "结局条件暂不支持 NODE_COMPLETED");
            }
            if ("HAS_TAG".equals(op)) {
                requireStoryTag(condition.path("value"), "结局条件");
                continue;
            }
            if (!STORY_CONDITION_OPS.contains(op)) {
                throw error("GAME_CONFIG_INVALID", "结局条件的 op 只能是 EQ、NE、GT、GTE、LT、LTE 或 HAS_TAG");
            }
            if (StringUtils.isBlank(condition.path("var").asText()) || !isStoryRuleValue(condition.path("value"))) {
                throw error("GAME_CONFIG_INVALID", "结局条件必须带变量名和 -99 到 99 的整数");
            }
        }
    }

    private void requireStoryRuleCount(JsonNode rules, String label) {
        if (rules.size() > STORY_RULE_MAX) {
            throw error("GAME_CONFIG_INVALID", label + "最多配置 " + STORY_RULE_MAX + " 条");
        }
    }

    private void requireStoryTag(JsonNode value, String label) {
        String tag = value.isTextual() ? value.asText() : "";
        if (StringUtils.isBlank(tag)) {
            throw error("GAME_CONFIG_INVALID", label + "的标签必须是非空文本");
        }
    }

    /** 剧情变量(初始值与旧写法效果)只能是整数;标签保留键不允许被当成变量写。 */
    private void validateStoryVariables(JsonNode variables, String label) {
        if (variables == null || variables.isMissingNode() || variables.isNull()) return;
        if (!variables.isObject()) throw error("GAME_CONFIG_INVALID", label + "格式不正确");
        Iterator<Map.Entry<String, JsonNode>> fields = variables.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (StringUtils.isBlank(field.getKey()) || STORY_TAGS_KEY.equals(field.getKey())
                    || !value.isIntegralNumber() || !value.canConvertToInt()) {
                throw error("GAME_CONFIG_INVALID", label + "的变量值必须是整数");
            }
        }
    }

    private boolean isStoryRuleValue(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
                && value.asInt() >= -99 && value.asInt() <= 99;
    }

    private void validateHintPolicy(JsonNode policy) {
        if (!policy.isObject() || !policy.path("levels").isArray() || policy.path("levels").size() != 2) {
            throw error("GAME_CONFIG_INVALID", "玩家提示必须按一级、二级完整配置");
        }
        int expectedLevel = 1;
        for (JsonNode level : policy.path("levels")) {
            String text = level.path("text").asText();
            String impact = level.path("impactLabel").asText();
            if (level.path("level").asInt(0) != expectedLevel++ || StringUtils.isBlank(text)
                    || text.length() > 200 || StringUtils.isBlank(impact) || impact.length() > 200) {
                throw error("GAME_CONFIG_INVALID", "玩家提示级别、文案或影响说明不正确");
            }
        }
        JsonNode reveal = policy.path("reveal");
        String revealText = reveal.path("text").asText();
        String revealImpact = reveal.path("impactLabel").asText();
        if (!reveal.isObject() || StringUtils.isBlank(revealText) || revealText.length() > 200
                || StringUtils.isBlank(revealImpact) || revealImpact.length() > 200) {
            throw error("GAME_CONFIG_INVALID", "玩家答案揭示配置不正确");
        }
    }

    JsonNode sessionConfig(GameSession session) {
        try { return objectMapper.readTree(session.getModuleConfigSnapshotJson()); }
        catch (Exception ex) { throw error("GAME_CONFIG_INVALID", "本场游戏配置快照不可用"); }
    }

    Map<String, String> configuredRoles(GameSession session) {
        Map<String, String> roles = new LinkedHashMap<>();
        for (JsonNode role : sessionConfig(session).path("roles")) {
            roles.put(role.path("code").asText(), role.path("name").asText(role.path("code").asText()));
        }
        return roles;
    }

    Set<Long> configuredChapterIds(JsonNode config) {
        Set<Long> ids = new HashSet<>();
        for (JsonNode chapter : config.path("chapters")) {
            long id = chapter.path("chapterId").asLong(0);
            if (id > 0) ids.add(id);
        }
        return ids;
    }

    Long firstChapterId(JsonNode config) {
        for (JsonNode chapter : config.path("chapters")) {
            long id = chapter.path("chapterId").asLong(0);
            if (id > 0) return id;
        }
        return null;
    }

    private int chapterIndex(JsonNode config, Long chapterId) {
        if (chapterId == null) return -1;
        int index = 0;
        for (JsonNode chapter : config.path("chapters")) {
            if (chapterId.equals(chapter.path("chapterId").asLong())) return index;
            index++;
        }
        return -1;
    }

    private boolean isChapterUnlocked(JsonNode config, Long currentChapterId, JsonNode choice) {
        if (!choice.has("chapterId")) return true;
        int currentIndex = chapterIndex(config, currentChapterId);
        int choiceIndex = chapterIndex(config, choice.path("chapterId").asLong());
        return currentIndex >= 0 && choiceIndex >= 0 && choiceIndex <= currentIndex;
    }

    boolean isLaterChapter(JsonNode config, Long currentChapterId, Long targetChapterId) {
        int targetIndex = chapterIndex(config, targetChapterId);
        int currentIndex = chapterIndex(config, currentChapterId);
        return targetIndex >= 0 && (currentIndex < 0 ? targetIndex == 0 : targetIndex > currentIndex);
    }

    boolean hasUnlockedChoices(JsonNode config, Long currentChapterId) {
        for (JsonNode choice : config.path("story").path("choices")) {
            if (isChapterUnlocked(config, currentChapterId, choice)) return true;
        }
        return false;
    }

    List<Map<String, Object>> roleOptions(JsonNode config) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (JsonNode role : config.path("roles")) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("roleCode", role.path("code").asText());
            value.put("roleName", role.path("name").asText(role.path("code").asText()));
            values.add(value);
        }
        return values;
    }

    List<Map<String, Object>> chapterOptions(JsonNode config, Long currentChapterId) {
        List<Map<String, Object>> values = new ArrayList<>();
        int currentIndex = chapterIndex(config, currentChapterId);
        int index = 0;
        for (JsonNode chapter : config.path("chapters")) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("chapterId", chapter.path("chapterId").asLong());
            value.put("title", chapter.path("title").asText());
            value.put("unlocked", currentIndex >= 0 && index <= currentIndex);
            value.put("unlockable", currentIndex < 0 ? index == 0 : index > currentIndex);
            values.add(value);
            index++;
        }
        return values;
    }

    JsonNode findChoice(JsonNode config, String choiceId, String stationCode, String roleCode,
                                Long currentChapterId) {
        if (StringUtils.isBlank(choiceId)) throw error("GAME_BAD_REQUEST", "请选择剧情行动");
        for (JsonNode choice : config.path("story").path("choices")) {
            if (!choiceId.equals(choice.path("id").asText())) continue;
            String configuredStation = choice.path("stationCode").asText();
            if (StringUtils.isNotBlank(configuredStation) && !configuredStation.equals(stationCode)) break;
            JsonNode roles = choice.path("allowedRoles");
            if (roles.isArray() && roles.size() > 0 && !containsText(roles, roleCode)) break;
            if (!isChapterUnlocked(config, currentChapterId, choice)) {
                throw error("GAME_CHAPTER_LOCKED", "剧情行动所在章节尚未开放");
            }
            return choice;
        }
        throw error("GAME_FORBIDDEN_SCOPE", "当前角色不能执行该剧情行动");
    }

    boolean allRequiredChoicesApplied(JsonNode config, ArrayNode appliedChoices) {
        Set<String> satisfied = new HashSet<>();
        for (JsonNode applied : appliedChoices) {
            if (!applied.isTextual()) continue;
            JsonNode configured = configuredChoice(config, applied.asText());
            if (configured == null) continue;
            String group = configured.path("choiceGroup").asText();
            satisfied.add(StringUtils.isBlank(group) ? configured.path("id").asText() : group);
        }
        for (JsonNode configured : config.path("story").path("choices")) {
            if (configured.has("required") && !configured.path("required").asBoolean()) continue;
            String choiceId = configured.path("id").asText();
            String group = configured.path("choiceGroup").asText();
            String requirement = StringUtils.isBlank(group) ? choiceId : group;
            if (StringUtils.isBlank(requirement) || !satisfied.contains(requirement)) return false;
        }
        return true;
    }

    boolean choiceGroupAlreadyApplied(JsonNode config, ArrayNode appliedChoices, JsonNode nextChoice) {
        String group = nextChoice.path("choiceGroup").asText();
        if (StringUtils.isBlank(group)) return false;
        for (JsonNode applied : appliedChoices) {
            JsonNode configured = applied.isTextual() ? configuredChoice(config, applied.asText()) : null;
            if (configured != null && group.equals(configured.path("choiceGroup").asText())) return true;
        }
        return false;
    }

    private JsonNode configuredChoice(JsonNode config, String choiceId) {
        for (JsonNode choice : config.path("story").path("choices")) {
            if (choiceId.equals(choice.path("id").asText())) return choice;
        }
        return null;
    }

    Map<String, Object> publicRole(JsonNode config, GameTeamRoleAssignment assignment) {
        if (assignment == null) return null;
        Map<String, Object> role = new LinkedHashMap<>();
        role.put("code", assignment.getRoleCode());
        role.put("name", assignment.getRoleName());
        role.put("status", assignment.getStatus());
        JsonNode configured = configuredRole(config, assignment.getRoleCode());
        if (configured != null && configured.hasNonNull("publicBrief")) role.put("publicBrief", configured.path("publicBrief").asText());
        return role;
    }

    JsonNode configuredTask(JsonNode config, GameStationRun station) {
        JsonNode configured = configuredStation(config.path("stations"), station.getStationCode(), station.getNodeName());
        if (configured == null || !configured.path("playerTask").isObject()) return null;
        return configured.path("playerTask");
    }

    Map<String, Object> publicTask(JsonNode config, GameStationRun station) {
        JsonNode task = configuredTask(config, station);
        String taskCode = task == null ? null : task.path("taskCode").asText();
        String prompt = task == null ? null : task.path("prompt").asText();
        String inputType = task == null ? null : upper(task.path("inputType").asText());
        if (StringUtils.isBlank(taskCode) || StringUtils.isBlank(prompt) || !TASK_INPUT_TYPES.contains(inputType)) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskCode", taskCode);
        value.put("prompt", prompt);
        value.put("inputType", inputType);
        value.put("verificationRequired", task.path("verificationRequired").asBoolean(false));
        String completionPolicy = upper(task.path("completionPolicy").asText());
        value.put("completionPolicy", StringUtils.isBlank(completionPolicy) ? null : completionPolicy);
        return value;
    }

    JsonNode requireHintPolicy(JsonNode config, GameStationRun station) {
        JsonNode task = configuredTask(config, station);
        JsonNode policy = task == null ? null : task.path("hintPolicy");
        if (policy == null || !policy.isObject()) {
            throw error("GAME_HINT_UNAVAILABLE", "本站任务未配置提示与答案揭示");
        }
        return policy;
    }

    Map<String, Object> hintView(JsonNode policy, int currentLevel,
                                         GamePlayerNodeOutcome outcome) {
        int safeLevel = Math.max(0, Math.min(2, currentLevel));
        List<Map<String, Object>> revealed = new ArrayList<>();
        String nextImpact = null;
        for (JsonNode configured : policy.path("levels")) {
            int level = configured.path("level").asInt(0);
            if (level > 0 && level <= safeLevel) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("level", level);
                value.put("text", configured.path("text").asText());
                revealed.add(value);
            } else if (level == safeLevel + 1) {
                nextImpact = configured.path("impactLabel").asText();
            }
        }
        Integer nextLevel = safeLevel < 2 ? safeLevel + 1 : null;
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("currentLevel", safeLevel);
        hint.put("revealedTexts", revealed);
        hint.put("nextLevel", nextLevel);
        hint.put("nextImpactLabel", nextLevel == null ? null : nextImpact);
        hint.put("revealAvailable", safeLevel == 2 && outcome == null);
        hint.put("revealImpactLabel", policy.path("reveal").path("impactLabel").asText());
        return hint;
    }

    String roleClue(JsonNode config, String roleCode, String stationCode) {
        JsonNode role = configuredRole(config, roleCode);
        if (role == null) return null;
        JsonNode clue = role.path("clues").path(stationCode);
        return clue.isMissingNode() || clue.isNull() ? null : clue.asText();
    }

    private JsonNode configuredRole(JsonNode config, String roleCode) {
        for (JsonNode role : config.path("roles")) if (roleCode.equals(role.path("code").asText())) return role;
        return null;
    }

    List<Map<String, Object>> allowedChoices(JsonNode config, GameTeamRoleAssignment assignment,
                                                     String stationCode, Long currentChapterId) {
        if (assignment == null) return Collections.emptyList();
        List<Map<String, Object>> choices = new ArrayList<>();
        for (JsonNode choice : config.path("story").path("choices")) {
            String configuredStation = choice.path("stationCode").asText();
            if (StringUtils.isNotBlank(configuredStation) && !configuredStation.equals(stationCode)) continue;
            JsonNode roles = choice.path("allowedRoles");
            if (roles.isArray() && roles.size() > 0 && !containsText(roles, assignment.getRoleCode())) continue;
            if (!isChapterUnlocked(config, currentChapterId, choice)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", choice.path("id").asText());
            item.put("label", choice.path("label").asText());
            choices.add(item);
        }
        return choices;
    }

    JsonNode findEnding(JsonNode config, String endingCode) {
        if (StringUtils.isBlank(endingCode)) return null;
        for (JsonNode ending : config.path("story").path("endings")) {
            if (endingCode.equals(ending.path("code").asText())) return ending;
        }
        return null;
    }

    Map<String, Object> publicEnding(JsonNode ending) {
        if (ending == null || ending.isMissingNode()) return null;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", ending.path("code").asText());
        value.put("title", ending.path("title").asText());
        value.put("text", ending.path("text").asText());
        return value;
    }

    Map<String, Object> stationView(GameStationRun station, JsonNode config) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("stationId", station.getId());
        view.put("nodeId", station.getNodeId());
        view.put("nodeName", station.getNodeName());
        view.put("stationCode", station.getStationCode());
        view.put("status", station.getStatus());
        view.put("revision", station.getRevision());
        view.put("serviceStartAt", formatDate(station.getServiceStartAt()));
        view.put("serviceEndAt", formatDate(station.getServiceEndAt()));
        view.put("capacity", station.getCapacity());
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("start", formatDate(station.getServiceStartAt()));
        window.put("end", formatDate(station.getServiceEndAt()));
        view.put("serviceWindow", window);
        Map<String, Object> preparation = readMap(station.getPreparationSnapshotJson());
        Object checklist = preparation.get("checklist");
        if (checklist == null) {
            JsonNode configured = configuredStation(config.path("stations"), station.getStationCode(), station.getNodeName());
            checklist = configured == null ? Collections.emptyList()
                    : objectMapper.convertValue(configured.path("preparationChecklist"), List.class);
        }
        view.put("preparationChecklist", checklist);
        view.put("pauseReasonCode", station.getPauseReasonCode());
        view.put("pauseReason", station.getPauseReason());
        view.put("resumeEta", formatDate(station.getResumeEta()));
        view.put("fallbackNodeId", station.getFallbackNodeId());
        view.put("fallbackPlanCode", station.getFallbackPlanCode());
        view.put("fallbackPlanVersion", station.getFallbackPlanVersion());
        view.put("pendingVerificationCount", station.getPendingVerificationCount() == null ? 0 : station.getPendingVerificationCount());
        // 暂停站点必须挑一个**已批准**的备用方案(resolveFallbackPlan 强制),
        // 但方案清单以前没下发,调用方无从挑起。这里只出已批准且源站是本站的那些,
        // 都是运营配置,不含任何机密。
        view.put("fallbackPlanOptions", approvedFallbackPlans(config, station));
        view.put("playable", GameStationRun.ACTIVE.equals(station.getStatus()));
        JsonNode configured = configuredStation(config.path("stations"), station.getStationCode(), station.getNodeName());
        view.put("playerTask", publicTask(config, station));
        view.put("merchantInstruction", configured == null ? null : safeOperationsText(configured, "merchantInstruction"));
        view.put("hiddenInfoReminder", configured == null ? null : safeOperationsText(configured, "hiddenInfoReminder"));
        return view;
    }

    /** 该站可用的已批准备用方案。只读配置投影,判据与 resolveFallbackPlan 逐条对齐。 */
    private List<Map<String, Object>> approvedFallbackPlans(JsonNode config, GameStationRun station) {
        List<Map<String, Object>> options = new ArrayList<>();
        if (station.getStationCode() == null) return options;
        for (JsonNode plan : config.path("fallbackPlans")) {
            if (!"APPROVED".equals(upper(plan.path("status").asText()))) continue;
            if (!station.getStationCode().equals(plan.path("sourceStationCode").asText())) continue;
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("planCode", upper(plan.path("planCode").asText()));
            option.put("version", plan.path("version").asInt(0));
            option.put("targetStationCode", plan.path("targetStationCode").asText());
            options.add(option);
        }
        return options;
    }

    private String safeOperationsText(JsonNode configured, String field) {
        String value = configured.path(field).asText();
        return StringUtils.isBlank(value) || value.length() > 200 ? null : value;
    }

    private Map<String, Object> readMap(String json) {
        if (StringUtils.isBlank(json)) return new LinkedHashMap<>();
        try { return objectMapper.readValue(json, LinkedHashMap.class); }
        catch (Exception ex) { return new LinkedHashMap<>(); }
    }

    private String upper(Object value) {
        return value == null ? null : String.valueOf(value).trim().toUpperCase();
    }

    private String text(Object value, int max) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.length() > max) throw error("GAME_BAD_REQUEST", "文本内容过长");
        return text;
    }

    private String formatDate(Date value) {
        return value == null ? null : DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", value);
    }

    private GameSessionException error(String code, String message) {
        return new GameSessionException(code, message);
    }

    private boolean containsText(JsonNode array, String value) {
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }

    static final class ReadyConfig {
        final Date startAt;
        final Date endAt;
        final Integer capacity;
        final Map<String, Object> preparation;
        private ReadyConfig(Date startAt, Date endAt, Integer capacity, Map<String, Object> preparation) {
            this.startAt = startAt;
            this.endAt = endAt;
            this.capacity = capacity;
            this.preparation = preparation;
        }
    }
}
