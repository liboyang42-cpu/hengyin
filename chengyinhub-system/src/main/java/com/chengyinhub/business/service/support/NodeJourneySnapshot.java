package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 开局冻结的节点旅程配置(B8 不可变快照)。
 *
 * <p>建局时把每个挂玩法模板节点的 requires / effects / check 与叙事段(enter / outcome /
 * deliver / unlock / revisit / brief / route / roleViews)从 advancedConfigJson 拷进 session 快照的
 * {@code nodeJourney},主题 journeyRules 拷进 {@code journeyRules},主题剧情
 * journey_story_json 拷进 {@code journeyStory};运行期按 nodeId 读冻结块 / 读冻结主题配置,
 * 创作者之后改模板 / 改主题剧情不影响进行中的局。本次改动前建的老局快照没有这些键,
 * 读取方法返回 null,由调用方回退实时配置(兼容,老局行为不变)。
 *
 * <p>R18-FREEZE 追加 {@code nodeAnswers}:模板里参与「对错判定」的字段(validationMethod
 * 与各验证方式的答案 / 揭示 / 偏好 / 传感器配置)同样建局冻结;每局每个在架节点写一条
 * (没绑定模板也写空对象,封死新局回退),老快照没有该键返回 null,由调用方回退实时模板。
 *
 * <p>R18:七段叙事不再是模板契约,改为从章节故事流按站点组装后写进同一份 {@code nodeJourney}
 * (旧契约形状不变,运行期读侧零改动)。模板侧冻结只留玩法段,见 {@link #copyMechanicSegments}。
 */
public final class NodeJourneySnapshot {

    public static final String NODE_JOURNEY_KEY = "nodeJourney";
    public static final String JOURNEY_RULES_KEY = "journeyRules";
    public static final String JOURNEY_STORY_KEY = "journeyStory";
    /**
     * R14:选择结果 effects 的冻结投影(branch/qa 的选项级 + 五个通过类玩法的段级),
     * 冻结块里的紧凑子对象 —— 只留 id 与 effects,不把答案与跳转冻进本局。
     */
    public static final String CHOICE_EFFECTS_KEY = "choiceEffects";
    /** R18-FREEZE:模板对错判定字段的建局冻结区。 */
    public static final String NODE_ANSWERS_KEY = "nodeAnswers";
    /**
     * R22-C4:玩法入口段的启用态冻结。encounter 的 allowedActions 原来读实时模板,check 读冻结块
     * —— 会话中途新增玩法会立刻出现在进行中的局。键总是写入(nodeJourney 每块都有,空对象 =
     * 建局时没有任何玩法入口),键在 = 以快照为准,老快照没有该键才回退实时模板。
     */
    public static final String PLAYABLES_KEY = "playables";
    /**
     * R22-C1:分支终点声明的冻结投影:[{outcomeCode, outcomeLabel}]。outcomeContract 的
     * outcomeCodes / 标签与 nextNodeIds 同源读本局快照,不再取实时 liveConfig;键总是写入
     * (空数组 = 建局时没有终点声明),键在 = 以快照为准,老快照没有该键才回退实时模板。
     */
    public static final String BRANCH_OUTCOMES_KEY = "branchOutcomes";

    /**
     * 可作为这一拍玩家动作的玩法段名单(与玩法配置段落同名,前端合同落地前先按配置名下发)。
     * 这份名单是唯一真源:建局冻结({@link #copyPlayables})与运行期下发
     * (PlayEncounterServiceImpl.allowedActions)都用它 —— 漏登记 = 到店后客户端拿不到该玩法入口,
     * 玩法静默不存在。
     */
    public static final List<String> PLAYABLE_SECTIONS = Collections.unmodifiableList(Arrays.asList(
            "timeWindow", "blindTaste", "silentOrder", "diyName", "musicCorner", "steps", "dailySign",
            "slowTask", "estimate", "pricePair", "hiddenObject", "predict", "coinFlip", "diceRoll",
            "reaction", "ballShake", "quietHold", "shout", "countdown", "stopwatch", "random", "qa", "scan",
            // R17-INT 待办 b:排序 / 连线 / 分类(R3)与对照(R16)也必须下发。
            "sort", "match", "classify", "compare",
            // 罗盘指向(现场感契约 §5.1):漏这一行 = 到店后客户端拿不到该玩法入口,静默不存在。
            "compass"));

    /** 块配置里要冻结的旅程相关段;其余玩法段落(如 timeWindow)不冻结,仍读实时配置。 */
    private static final List<String> JOURNEY_SEGMENTS = Collections.unmodifiableList(Arrays.asList(
            "requires", "effects", "check", "present", "enter", "outcome", "deliver", "unlock", "revisit",
            "brief", "route", "roleViews"));

    /**
     * R14:通过时执行段级 effects 的玩法段。这份名单是**唯一真源**:建局冻结投影
     * ({@link #copyChoiceEffects})、建局判定({@link #needsJourneyState})与运行期写入
     * (TopicRouteRuntimeServiceImpl.recordChoice)都用它 —— 两处各存一份时,一边只加玩法
     * 另一边没加,选择效果会静默不写或建局判定与冻结不一致。
     */
    public static final List<String> CHOICE_KITS = Collections.unmodifiableList(Arrays.asList(
            "qa", "sort", "match", "classify", "hiddenObject", "pricePair",
            // 预制人生建档:加成挂在 questions[].options[] 的 key 上,与 qa 的选项级 effects 同层语义,
            // 只是多了一层问题;冻结与执行都要按那层结构走(见 copyChoiceEffects / kitChoice)。
            "profile"));

    private static final ObjectMapper JSON = new ObjectMapper();

    private NodeJourneySnapshot() {
    }

    /** 把块配置里的旅程段拷进目标节点;没配的段不写,非对象配置视为没有旅程段。 */
    public static void copyJourneySegments(JsonNode blockConfig, ObjectNode target) {
        if (blockConfig == null || !blockConfig.isObject()) return;
        for (String segment : JOURNEY_SEGMENTS) {
            JsonNode value = blockConfig.get(segment);
            if (value != null && !value.isNull()) target.set(segment, value);
        }
        copyCheckPresentation(blockConfig, target);
        copyChoiceEffects(blockConfig, target);
        copyPlayables(blockConfig, target);
        copyBranchOutcomes(blockConfig, target);
    }

    /** 检定与其他玩法共享根层呈现方式;冻结完整配置的默认推导,避免只留 check 后误改档位。 */
    private static void copyCheckPresentation(JsonNode blockConfig, ObjectNode target) {
        JsonNode check = blockConfig.get("check");
        if (check != null && check.isObject() && check.path("enabled").asBoolean(false)) {
            target.put("present", AdvancedGameConfigValidator.resolvePresent(blockConfig));
        }
    }

    /** R22-C4:玩法入口段的启用态(只留启用的段名,空对象 = 建局时没有玩法入口)。 */
    private static void copyPlayables(JsonNode blockConfig, ObjectNode target) {
        ObjectNode playables = target.putObject(PLAYABLES_KEY);
        for (String section : PLAYABLE_SECTIONS) {
            JsonNode value = blockConfig.get(section);
            if (value != null && value.isObject() && value.path("enabled").asBoolean(false)) {
                playables.put(section, true);
            }
        }
    }

    /** R22-C1:分支终点的 outcomeCode / outcomeLabel 冻结投影(空数组也写,键在即以快照为准)。 */
    private static void copyBranchOutcomes(JsonNode blockConfig, ObjectNode target) {
        com.fasterxml.jackson.databind.node.ArrayNode rows = target.putArray(BRANCH_OUTCOMES_KEY);
        JsonNode branch = blockConfig.get("branch");
        if (branch == null || !branch.isObject() || !branch.path("enabled").asBoolean(false)) return;
        for (JsonNode step : branch.path("steps")) {
            if (step == null || !step.isObject() || !step.path("terminal").asBoolean(false)) continue;
            String code = step.path("outcomeCode").asText("").trim();
            if (code.isEmpty()) continue;
            ObjectNode row = rows.addObject();
            row.put("outcomeCode", code);
            String label = step.path("outcomeLabel").asText("").trim();
            if (!label.isEmpty()) row.put("outcomeLabel", label);
        }
    }

    /**
     * R14:选择结果 effects 的冻结投影。分支只留 steps[].{id, options[].{id, effects}},
     * 各玩法段只留 options[].{id, effects} 或段级 effects —— 答案(nextStepId / correct /
     * answerOrder / pairs / answer)一个字都不冻进本局。
     * profile(预制人生建档)的加成在 questions[].options[] 上,选项 id 用 key 表达。
     */
    private static void copyChoiceEffects(JsonNode blockConfig, ObjectNode target) {
        ObjectNode view = JSON.createObjectNode();
        JsonNode branch = blockConfig.get("branch");
        if (branch != null && branch.isObject()) {
            ObjectNode branchRow = view.putObject("branch");
            com.fasterxml.jackson.databind.node.ArrayNode steps = branchRow.putArray("steps");
            for (JsonNode step : branch.path("steps")) {
                if (step == null || !step.isObject()) continue;
                ObjectNode row = steps.addObject();
                row.put("id", step.path("id").asText(""));
                com.fasterxml.jackson.databind.node.ArrayNode options = row.putArray("options");
                for (JsonNode option : step.path("options")) {
                    if (option == null || !option.isObject()) continue;
                    ObjectNode optionRow = options.addObject();
                    optionRow.put("id", option.path("id").asText(""));
                    if (option.has("effects")) optionRow.set("effects", option.get("effects"));
                }
            }
        }
        for (String kit : CHOICE_KITS) {
            JsonNode section = blockConfig.get(kit);
            if (section == null || !section.isObject()) continue;
            ObjectNode row = view.putObject(kit);
            if ("profile".equals(kit)) {
                // 建档的加成在第二层(questions[].options[]),选项 id 用 key 表达(契约 §2.1)。
                ArrayNode questions = row.putArray("questions");
                for (JsonNode question : section.path("questions")) {
                    if (question == null || !question.isObject()) continue;
                    ObjectNode questionRow = questions.addObject();
                    questionRow.put("key", question.path("key").asText(""));
                    ArrayNode questionOptions = questionRow.putArray("options");
                    for (JsonNode option : question.path("options")) {
                        if (option == null || !option.isObject()) continue;
                        ObjectNode optionRow = questionOptions.addObject();
                        optionRow.put("id", option.path("key").asText(""));
                        if (option.has("effects")) optionRow.set("effects", option.get("effects"));
                    }
                }
                continue;
            }
            // qa 的效果只在选项级(契约 v4);段级 effects 不是它的配置形状,不冻也不执行。
            if (!"qa".equals(kit) && section.has("effects")) row.set("effects", section.get("effects"));
            JsonNode options = section.get("options");
            if (options == null || !options.isArray()) continue;
            com.fasterxml.jackson.databind.node.ArrayNode optionRows = row.putArray("options");
            for (JsonNode option : options) {
                if (option == null || !option.isObject()) continue;
                ObjectNode optionRow = optionRows.addObject();
                optionRow.put("id", option.path("id").asText(""));
                if (option.has("effects")) optionRow.set("effects", option.get("effects"));
            }
        }
        if (view.size() > 0) target.set(CHOICE_EFFECTS_KEY, view);
    }

    /**
     * R14:块配置是否需要本局状态 —— 建局判定({@code TopicRouteRuntimeServiceImpl.journeyConfigured})
     * 与发布前试检查({@code TopicPublishCheckService})共用这一份口径:
     * requires / effects 非空、check 启用、配了任一处选择结果 effects(否则选择效果无处可写),
     * 或启用了 roleViews 双人视角(按局分半,没有本局就没有 activityId 可定队伍)。
     * 空配置 / 坏 JSON / 非对象 = 不需要(坏配置由校验器与状态键闸负责拦)。
     */
    public static boolean needsJourneyState(String advancedConfigJson) {
        if (advancedConfigJson == null || advancedConfigJson.trim().isEmpty()) {
            return false;
        }
        final JsonNode config;
        try {
            config = JSON.readTree(advancedConfigJson);
        } catch (Exception unusableBlockConfig) {
            return false;
        }
        if (config == null || !config.isObject()) {
            return false;
        }
        JsonNode requires = config.get("requires");
        if (requires != null && requires.isArray() && requires.size() > 0) return true;
        JsonNode effects = config.get("effects");
        if (effects != null && effects.isArray() && effects.size() > 0) return true;
        JsonNode check = config.get("check");
        if (check != null && check.isObject() && check.path("enabled").asBoolean(false)) return true;
        if (enabledRoleViews(config)) return true;
        return writesChoiceState(config);
    }

    /**
     * R19-INT 收口 a:roleViews 是按局下发的双人视角 —— PlayEncounterServiceImpl#appendRoleView
     * 要用本局 activityId 定队伍再分半。只配 roleViews 的线性主题若不建局,
     * /api/play/encounter 报「这一局还没开始」,双人视角永远拿不到。
     * 判定与运行期 {@code enabled(raw)} 同一口径:对象且 enabled=true。
     */
    private static boolean enabledRoleViews(JsonNode config) {
        JsonNode roleViews = config.get("roleViews");
        return roleViews != null && roleViews.isObject() && roleViews.path("enabled").asBoolean(false);
    }

    /** 选择结果 effects:分支选项 / 问答选项 / 五个通过类玩法的段级 effects;qa 没有段级。 */
    private static boolean writesChoiceState(JsonNode config) {
        JsonNode branch = config.get("branch");
        if (branch != null && branch.isObject()) {
            for (JsonNode step : branch.path("steps")) {
                if (step != null && step.isObject() && optionHasEffects(step.get("options"))) return true;
            }
        }
        for (String kit : CHOICE_KITS) {
            JsonNode section = config.get(kit);
            if (section == null || !section.isObject()) continue;
            if ("profile".equals(kit)) {
                for (JsonNode question : section.path("questions")) {
                    if (question != null && question.isObject() && optionHasEffects(question.get("options"))) {
                        return true;
                    }
                }
                continue;
            }
            if ("qa".equals(kit)) {
                if (optionHasEffects(section.get("options"))) return true;
                continue;
            }
            JsonNode effects = section.get("effects");
            if (effects != null && effects.isArray() && effects.size() > 0) return true;
        }
        return false;
    }

    private static boolean optionHasEffects(JsonNode options) {
        if (options == null || !options.isArray()) return false;
        for (JsonNode option : options) {
            if (option == null || !option.isObject()) continue;
            JsonNode effects = option.get("effects");
            if (effects != null && effects.isArray() && effects.size() > 0) return true;
        }
        return false;
    }

    /**
     * R18 建局冻结的模板侧段落:玩法段 + 双人视角(R16-DUO)+ 选择效果投影(R14)+ 判错档位(阶段 2)。
     * 七段叙事已迁往章节故事流,不随模板冻结。建局方按站点从故事流组装叙事段后,
     * 写进同一个 nodeJourney 块(旧契约形状不变)。
     *
     * <p>{@code mistakeTier} 必须在这里:运行期读块配置只认冻结块(有块即不回退实时模板),
     * 漏收这个根层键 = 配了 hard 的模板在全新一局里静默按 easy 扣血。
     */
    private static final List<String> MECHANIC_SEGMENTS = Collections.unmodifiableList(Arrays.asList(
            "requires", "effects", "check", "present", "roleViews", "mistakeTier"));

    /** 把块配置里的玩法段拷进目标节点;没配的段不写,非对象配置视为没有玩法段。 */
    public static void copyMechanicSegments(JsonNode blockConfig, ObjectNode target) {
        if (blockConfig == null || !blockConfig.isObject()) return;
        for (String segment : MECHANIC_SEGMENTS) {
            JsonNode value = blockConfig.get(segment);
            if (value != null && !value.isNull()) target.set(segment, value);
        }
        copyCheckPresentation(blockConfig, target);
        copyChoiceEffects(blockConfig, target);
        copyPlayables(blockConfig, target);
        copyBranchOutcomes(blockConfig, target);
    }

    /** 快照里按 nodeId 取冻结块;老快照没有 nodeJourney 键时返回 null,由调用方回退实时配置。 */
    public static JsonNode block(String configSnapshotJson, Long nodeId) {
        if (configSnapshotJson == null || nodeId == null) return null;
        final JsonNode snapshot;
        try {
            snapshot = JSON.readTree(configSnapshotJson);
        } catch (Exception unusableSnapshot) {
            return null;
        }
        JsonNode frozen = snapshot.path(NODE_JOURNEY_KEY);
        if (!frozen.isObject()) return null;
        JsonNode block = frozen.get(String.valueOf(nodeId));
        return block != null && block.isObject() ? block : null;
    }

    /** 快照里冻结的主题 journeyRules 文本;老快照没有该键时返回 null,由调用方回退实时配置。 */
    public static String journeyRules(String configSnapshotJson) {
        if (configSnapshotJson == null) return null;
        final JsonNode snapshot;
        try {
            snapshot = JSON.readTree(configSnapshotJson);
        } catch (Exception unusableSnapshot) {
            return null;
        }
        JsonNode rules = snapshot.get(JOURNEY_RULES_KEY);
        if (rules == null || rules.isNull()) return null;
        try {
            return JSON.writeValueAsString(rules);
        } catch (Exception unusableRules) {
            return null;
        }
    }

    /**
     * 快照里冻结的主题剧情 journey_story_json 文本;老快照没有该键时返回 null,由调用方回退实时配置。
     *
     * <p>键在但值为空串 = 建局时主题还没配剧情,冻结的就是「没有」,不再回退 —— 否则开局后
     * 补配置会穿透进行中的局,与节点旅程段的冻结口径不一致。
     */
    public static String journeyStory(String configSnapshotJson) {
        if (configSnapshotJson == null) return null;
        final JsonNode snapshot;
        try {
            snapshot = JSON.readTree(configSnapshotJson);
        } catch (Exception unusableSnapshot) {
            return null;
        }
        JsonNode story = snapshot.get(JOURNEY_STORY_KEY);
        if (story == null) return null;
        if (story.isTextual()) return story.asText();
        if (story.isNull()) return "";
        try {
            return JSON.writeValueAsString(story);
        } catch (Exception unusableStory) {
            return "";
        }
    }

    /**
     * 快照里按 nodeId 取冻结的模板判定字段(R18-FREEZE);老快照没有 nodeAnswers 键时返回
     * null,由调用方回退实时模板。条目在而字段缺 = 建局时就「没有这个字段」,不回退。
     */
    public static JsonNode answer(String configSnapshotJson, Long nodeId) {
        if (configSnapshotJson == null || nodeId == null) return null;
        final JsonNode snapshot;
        try {
            snapshot = JSON.readTree(configSnapshotJson);
        } catch (Exception unusableSnapshot) {
            return null;
        }
        JsonNode frozen = snapshot.path(NODE_ANSWERS_KEY);
        if (!frozen.isObject()) return null;
        JsonNode block = frozen.get(String.valueOf(nodeId));
        return block != null && block.isObject() ? block : null;
    }
}
