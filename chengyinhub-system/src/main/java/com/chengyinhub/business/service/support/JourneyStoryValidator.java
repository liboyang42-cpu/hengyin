package com.chengyinhub.business.service.support;

import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主题级剧情配置 journeyStory 的单一校验入口(R8-STORY,主题级一份)。
 *
 * <p>风格与 {@link JourneyRulesValidator} 一致:非法抛 {@link IllegalArgumentException} 中文人话;
 * 空配置返回 null = 未配置(老主题行为完全不变)。这里只保证配置**自身自洽**与引用口径,
 * 不做任何读接口求值(stage-end / ending 见 PlayJourneyStoryServiceImpl)。
 *
 * <p>与键注册表的分工同 B10:剧情 cond 引用到的状态键有没有注册,由发布期对账
 * (见 CmsTopicServiceImpl.assertStateKeysRegistered);key 注册表本身不是本类的依赖。
 * 尾声的 relationKey 同理:这里只校验形状,「已注册且类型 relation」由发布期闸兜底。
 *
 * <p>章节 / NPC 引用是**可选手闸**:调用方给 {@link Context} 提供可用集合时逐条核对,
 * 拿不到集合(建题时章节/NPC 尚未落库、手工 new 的单测)则只校验形状,
 * 引用再由发布期带上下文重验(与「B10 保存只查自身自洽、发布期查注册表」同一分工)。
 */
public class JourneyStoryValidator {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_JSON_BYTES = 32 * 1024;

    private static final int MAX_TEXT = 200;
    private static final int MAX_ITEMS = 16;
    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 4;

    /** id / code 正则(契约 v4-B)。 */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    /** cond 比较类 var(契约 v3)。 */
    private static final Pattern VAR = Pattern.compile("^(clue|relation|counter)\\.[a-z][a-z0-9_]{0,47}$");
    /** cond HAS_TAG 的 tag 值(契约 v3)。 */
    /* 除了作者自己定义的 tag.*,再认思维阁写的 thought.<key>.done —— 念头想通与否天然是布尔,
       运行时本来就落在 tags 里(TopicRouteRuntimeServiceImpl.syncThoughts)。2026-09-22 之前
       条件读不到它,于是思维阁与结局是两个孤岛:念头配得出、结局也配得出,就是没法让「想通了 X」
       决定走哪个结局,而那正是《预制人生》六结局的主线机制。
       ⚠️ 不走 VAR(比较分支):那条路的 value 限 -99..99,而念头阈值是几千步,对不上;
          真正要表达的也不是「走了多少步」,是「想通了没有」。 */
    private static final Pattern TAG = Pattern.compile(
            "^(tag\\.[a-z][a-z0-9_]{0,47}|thought\\.[a-z][a-z0-9_]{0,47}\\.done)$");
    /** optionalClues 的 clue 键。 */
    private static final Pattern CLUE = Pattern.compile("^clue\\.[a-z][a-z0-9_]{0,47}$");
    /** 尾声的 relationKey(契约 v4-B 显式口径):必须是 relation.* 形状。 */
    private static final Pattern RELATION_KEY = Pattern.compile("^relation\\.[a-z][a-z0-9_]{0,47}$");
    /**
     * R14 只读系统变量(契约 v4):sys.hp / sys.luck 之外,带 nodeId 的三个只读到本主题的节点;
     * nodeId 归属由 Context 核对,形状不对一律拒。
     */
    private static final String SYS_PREFIX = "sys.";
    private static final Pattern SYS_ASKED_VAR =
            Pattern.compile("^sys\\.asked\\.([1-9][0-9]*)\\.[A-Za-z0-9_-]{1,32}$");
    private static final Pattern SYS_MISTAKES_VAR =
            Pattern.compile("^sys\\.mistakes\\.([1-9][0-9]*)$");
    private static final Pattern SYS_OUTCOME_VAR =
            Pattern.compile("^sys\\.outcome\\.([1-9][0-9]*)$");
    private static final Pattern SYS_PASSED_VAR =
            Pattern.compile("^sys\\.passed\\.([1-9][0-9]*)$");

    private static final Set<String> COMPARE_OPS = new HashSet<String>();
    static {
        COMPARE_OPS.add("EQ");
        COMPARE_OPS.add("NE");
        COMPARE_OPS.add("GT");
        COMPARE_OPS.add("GTE");
        COMPARE_OPS.add("LT");
        COMPARE_OPS.add("LTE");
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 引用核对上下文;null 集合 = 该侧不核对。 */
    public static final class Context {
        private final Set<Long> chapterIds;
        private final Set<Long> npcIds;
        /** R14:本主题的节点 id;null = 不核对 sys.* 的 nodeId 归属。 */
        private final Set<Long> nodeIds;
        /** R14:本主题里配了分支玩法的节点 id;null = 不核对 dispositionNodeId 的玩法。 */
        private final Set<Long> branchNodeIds;
        /** 会写 sys.outcome 的节点(分支 + 抛硬币 / 掷骰子 / 抽卡);null = 不核对条件里 sys.outcome 的归属。 */
        private final Set<Long> outcomeNodeIds;

        private Context(Set<Long> chapterIds, Set<Long> npcIds, Set<Long> nodeIds, Set<Long> branchNodeIds,
                        Set<Long> outcomeNodeIds) {
            this.chapterIds = chapterIds;
            this.npcIds = npcIds;
            this.nodeIds = nodeIds;
            this.branchNodeIds = branchNodeIds;
            this.outcomeNodeIds = outcomeNodeIds;
        }

        /** 建题等章节/NPC 尚未落库的场景:只校验形状。 */
        public static Context none() {
            return new Context(null, null, null, null, null);
        }

        public static Context of(Set<Long> chapterIds, Set<Long> npcIds) {
            return new Context(chapterIds, npcIds, null, null, null);
        }

        /** R14 发布期:带本主题节点集合与「哪些节点是分支玩法」,核对 sys.* 引用与处置节点。 */
        public static Context of(Set<Long> chapterIds, Set<Long> npcIds,
                                 Set<Long> nodeIds, Set<Long> branchNodeIds) {
            return new Context(chapterIds, npcIds, nodeIds, branchNodeIds, branchNodeIds);
        }

        /** 同上,另带「会写 sys.outcome 的节点」(随机类玩法 2026-09-23 起也写)。 */
        public static Context of(Set<Long> chapterIds, Set<Long> npcIds, Set<Long> nodeIds,
                                 Set<Long> branchNodeIds, Set<Long> outcomeNodeIds) {
            return new Context(chapterIds, npcIds, nodeIds, branchNodeIds, outcomeNodeIds);
        }
    }

    /**
     * 校验后原样返回(去掉首尾空白),供落库 / 发布期重验;空配置返回 null。
     *
     * <p>不做字段白名单重排:回显要求「存什么读什么」,与 journeyRules 的归一化风格不同,
     * 但校验口径一致(非法一条都不放过)。
     */
    public String validate(String rawJson, Context context) {
        if (StringUtils.isBlank(rawJson)) {
            return null;
        }
        String trimmed = rawJson.trim();
        if (trimmed.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("主题剧情配置不能超过 32 KiB");
        }
        final JsonNode parsed;
        try {
            parsed = objectMapper.readTree(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException("主题剧情配置不是合法 JSON");
        }
        if (!(parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode)) {
            throw new IllegalArgumentException("主题剧情配置必须是对象");
        }
        if (parsed.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
            throw new IllegalArgumentException("主题剧情配置版本不支持");
        }
        Context ctx = context == null ? Context.none() : context;
        Long openingChapterId = optionalPositiveLong(parsed, "openingChapterId", "开局章节");
        if (openingChapterId != null) {
            try {
                requireChapterAvailable(openingChapterId, ctx);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(invalid.getMessage() + " [path=openingChapterId]", invalid);
            }
        }
        validateCall(parsed.get("call"), ctx);
        validateStages(parsed.get("stages"), ctx);
        validateEnding(parsed.get("ending"), ctx);
        return trimmed;
    }

    // ---------- call ----------

    private void validateCall(JsonNode call, Context ctx) {
        if (call == null || call.isNull()) {
            return;
        }
        if (!call.isObject()) {
            throw new IllegalArgumentException("剧情开场 call 必须是对象");
        }
        Long npcId = optionalPositiveLong(call, "npcId", "剧情开场 call.npcId");
        if (npcId == null) {
            throw new IllegalArgumentException("剧情开场 call.npcId 必须是正整数");
        }
        requireNpcAvailable(npcId, ctx);
        optionalText(call, "opener", "剧情开场 call.opener");
        optionalText(call, "brief", "剧情开场 call.brief");
        JsonNode options = call.get("options");
        if (options == null || options.isNull()) {
            return;
        }
        if (!options.isArray() || options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("剧情开场 call.options 须为 "
                    + MIN_OPTIONS + " 至 " + MAX_OPTIONS + " 项");
        }
        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < options.size(); i++) {
            JsonNode option = options.get(i);
            String path = "剧情开场 call.options[" + i + "]";
            if (option == null || !option.isObject()) {
                throw new IllegalArgumentException(path + " 必须是对象");
            }
            String id = requiredId(option, "id", path + ".id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException(path + ".id 重复:" + id);
            }
            requiredText(option, "line", path + ".line");
            optionalText(option, "reply", path + ".reply");
        }
    }

    // ---------- stages ----------

    private void validateStages(JsonNode stages, Context ctx) {
        if (stages == null || stages.isNull()) {
            return;
        }
        if (!stages.isArray()) {
            throw new IllegalArgumentException("剧情阶段 stages 必须是数组");
        }
        requireSize(stages, "剧情阶段 stages");
        Set<Long> chapterIds = new HashSet<Long>();
        for (int i = 0; i < stages.size(); i++) {
            JsonNode stage = stages.get(i);
            String path = "剧情阶段 stages[" + i + "]";
            if (stage == null || !stage.isObject()) {
                throw new IllegalArgumentException(path + " 必须是对象");
            }
            Long chapterId = optionalPositiveLong(stage, "chapterId", path + ".chapterId");
            if (chapterId == null) {
                throw new IllegalArgumentException(path + ".chapterId 必须是正整数");
            }
            if (!chapterIds.add(chapterId)) {
                throw new IllegalArgumentException(path + ".chapterId 重复:" + chapterId);
            }
            requireChapterAvailable(chapterId, ctx);
            optionalText(stage, "question", path + ".question");
            JsonNode end = stage.get("end");
            if (end != null && !end.isNull()) {
                if (!end.isObject()) {
                    throw new IllegalArgumentException(path + ".end 必须是对象");
                }
                JsonNode changed = end.get("changed");
                if (changed != null && !changed.isNull()) {
                    if (!changed.isArray()) {
                        throw new IllegalArgumentException(path + ".end.changed 必须是数组");
                    }
                    requireSize(changed, path + ".end.changed");
                    for (int j = 0; j < changed.size(); j++) {
                        validateChange(changed.get(j), path + ".end.changed[" + j + "]", ctx);
                    }
                }
                optionalText(end, "open", path + ".end.open");
            }
        }
    }

    /** changed[] 条目:{when: cond|null, text}。 */
    private void validateChange(JsonNode change, String path, Context ctx) {
        if (change == null || !change.isObject()) {
            throw new IllegalArgumentException(path + " 必须是对象");
        }
        optionalCondition(change.get("when"), path + ".when", ctx);
        requiredText(change, "text", path + ".text");
    }

    // ---------- ending ----------

    private void validateEnding(JsonNode ending, Context ctx) {
        if (ending == null || ending.isNull()) {
            return;
        }
        if (!ending.isObject()) {
            throw new IllegalArgumentException("结局配置 ending 必须是对象");
        }
        // R14:处置由哪个节点的分支结果决定。节点必须属于本主题,且必须配了分支玩法 ——
        // 否则 endings[].when 里写的 sys.outcome.{nodeId} 永远不成立,三个结局静默不生效。
        Long dispositionNodeId = optionalPositiveLong(ending, "dispositionNodeId",
                "结局配置 ending.dispositionNodeId");
        if (dispositionNodeId != null) {
            requireDispositionNode(dispositionNodeId, ctx);
        }
        validateEndings(ending.get("endings"), ctx);
        // R22-A6:配了处置节点,还要求有结局真的引用它 —— 作者漏写条件时全部落到 fallback、
        // 处置结局静默不生效;形状自洽检查,与 Context 无关(试检查走 Context.none 也要拦)。
        if (dispositionNodeId != null) {
            requireDispositionReferenced(ending.get("endings"), dispositionNodeId);
        }
        validateEpilogues(ending.get("epilogues"), ctx);
        validateExhibits(ending.get("exhibits"), ctx);
        validateBadges(ending.get("badges"), ctx);
        validateOptionalClues(ending.get("optionalClues"));
    }

    private void validateEndings(JsonNode endings, Context ctx) {
        if (endings == null || endings.isNull()) {
            throw new IllegalArgumentException("结局配置 ending.endings 不能为空");
        }
        if (!endings.isArray() || endings.size() == 0) {
            throw new IllegalArgumentException("结局配置 ending.endings 不能为空");
        }
        requireSize(endings, "结局配置 ending.endings");
        int fallbacks = 0;
        Set<String> codes = new HashSet<String>();
        for (int i = 0; i < endings.size(); i++) {
            JsonNode item = endings.get(i);
            String path = "结局配置 ending.endings[" + i + "]";
            if (item == null || !item.isObject()) {
                throw new IllegalArgumentException(path + " 必须是对象");
            }
            String code = requiredId(item, "code", path + ".code");
            if (!codes.add(code)) {
                throw new IllegalArgumentException(path + ".code 重复:" + code);
            }
            requiredText(item, "title", path + ".title");
            optionalText(item, "summary", path + ".summary");
            boolean fallback = boolField(item, "fallback", false, path + ".fallback");
            if (fallback) {
                fallbacks++;
                continue;
            }
            JsonNode when = item.get("when");
            if (when == null || !when.isArray() || when.size() == 0) {
                throw new IllegalArgumentException(path + " 非兜底结局的 when 不能为空数组");
            }
            requireSize(when, path + ".when");
            for (int j = 0; j < when.size(); j++) {
                requireCondition(when.get(j), path + ".when[" + j + "]", ctx);
            }
        }
        if (fallbacks != 1) {
            throw new IllegalArgumentException("结局配置 ending.endings 必须恰好一个 fallback");
        }
    }

    private void validateEpilogues(JsonNode epilogues, Context ctx) {
        if (epilogues == null || epilogues.isNull()) {
            return;
        }
        if (!epilogues.isArray()) {
            throw new IllegalArgumentException("结局配置 ending.epilogues 必须是数组");
        }
        requireSize(epilogues, "结局配置 ending.epilogues");
        Set<Long> npcIds = new HashSet<Long>();
        Set<String> relationKeys = new HashSet<String>();
        for (int i = 0; i < epilogues.size(); i++) {
            JsonNode item = epilogues.get(i);
            String path = "结局配置 ending.epilogues[" + i + "]";
            if (item == null || !item.isObject()) {
                throw new IllegalArgumentException(path + " 必须是对象");
            }
            Long npcId = optionalPositiveLong(item, "npcId", path + ".npcId");
            if (npcId == null) {
                throw new IllegalArgumentException(path + ".npcId 必须是正整数");
            }
            if (!npcIds.add(npcId)) {
                throw new IllegalArgumentException(path + ".npcId 重复:" + npcId);
            }
            requireNpcAvailable(npcId, ctx);
            JsonNode relationKeyNode = item.get("relationKey");
            String relationKey = relationKeyNode != null && relationKeyNode.isTextual()
                    ? relationKeyNode.asText().trim() : null;
            if (relationKey == null || !RELATION_KEY.matcher(relationKey).matches()) {
                throw new IllegalArgumentException(path + ".relationKey 必须是 relation.* 状态键");
            }
            if (!relationKeys.add(relationKey)) {
                throw new IllegalArgumentException(path + ".relationKey 重复:" + relationKey);
            }
            requiredText(item, "ally", path + ".ally");
            requiredText(item, "neutral", path + ".neutral");
            requiredText(item, "guarded", path + ".guarded");
        }
    }

    private void validateExhibits(JsonNode exhibits, Context ctx) {
        if (exhibits == null || exhibits.isNull()) {
            return;
        }
        if (!exhibits.isArray()) {
            throw new IllegalArgumentException("结局配置 ending.exhibits 必须是数组");
        }
        requireSize(exhibits, "结局配置 ending.exhibits");
        for (int i = 0; i < exhibits.size(); i++) {
            JsonNode item = exhibits.get(i);
            String path = "结局配置 ending.exhibits[" + i + "]";
            if (item == null || !item.isObject()) {
                throw new IllegalArgumentException(path + " 必须是对象");
            }
            optionalCondition(item.get("when"), path + ".when", ctx);
            requiredText(item, "text", path + ".text");
        }
    }

    private void validateBadges(JsonNode badges, Context ctx) {
        if (badges == null || badges.isNull()) {
            return;
        }
        if (!badges.isArray()) {
            throw new IllegalArgumentException("结局配置 ending.badges 必须是数组");
        }
        requireSize(badges, "结局配置 ending.badges");
        int fallbacks = 0;
        for (int i = 0; i < badges.size(); i++) {
            JsonNode item = badges.get(i);
            String path = "结局配置 ending.badges[" + i + "]";
            if (item == null || !item.isObject()) {
                throw new IllegalArgumentException(path + " 必须是对象");
            }
            requiredText(item, "title", path + ".title");
            if (boolField(item, "fallback", false, path + ".fallback")) {
                fallbacks++;
                continue;
            }
            JsonNode when = item.get("when");
            if (when == null || !when.isArray() || when.size() == 0) {
                throw new IllegalArgumentException(path + " 非兜底徽章的 when 不能为空数组");
            }
            requireSize(when, path + ".when");
            for (int j = 0; j < when.size(); j++) {
                requireCondition(when.get(j), path + ".when[" + j + "]", ctx);
            }
        }
        if (badges.size() > 0 && fallbacks != 1) {
            throw new IllegalArgumentException("结局配置 ending.badges 必须恰好一个 fallback");
        }
    }

    private void validateOptionalClues(JsonNode optionalClues) {
        if (optionalClues == null || optionalClues.isNull()) {
            return;
        }
        if (!optionalClues.isArray()) {
            throw new IllegalArgumentException("结局配置 ending.optionalClues 必须是数组");
        }
        requireSize(optionalClues, "结局配置 ending.optionalClues");
        Set<String> keys = new HashSet<String>();
        for (int i = 0; i < optionalClues.size(); i++) {
            JsonNode item = optionalClues.get(i);
            String path = "结局配置 ending.optionalClues[" + i + "]";
            if (item == null || !item.isTextual() || !CLUE.matcher(item.asText().trim()).matches()) {
                throw new IllegalArgumentException(path + " 必须是 clue.* 状态键");
            }
            String key = item.asText().trim();
            if (!keys.add(key)) {
                throw new IllegalArgumentException(path + " 重复:" + key);
            }
        }
    }

    // ---------- cond(契约 v3 路线图写法) ----------

    private void optionalCondition(JsonNode condition, String path, Context ctx) {
        if (condition == null || condition.isNull()) {
            return;
        }
        requireCondition(condition, path, ctx);
    }

    private void requireCondition(JsonNode condition, String path, Context ctx) {
        if (condition == null || !condition.isObject()) {
            throw new IllegalArgumentException(path + " 必须是条件对象");
        }
        String op = condition.path("op").asText("");
        if ("ITEM_HELD".equals(op) || "ITEM_ACQUIRED".equals(op)) {
            if (!condition.path("itemId").asText().matches("[a-z][a-z0-9_]{0,31}") || !condition.path("quantity").isIntegralNumber()
                || !condition.path("quantity").canConvertToLong()
                || condition.path("quantity").asLong() < 1 || condition.path("quantity").asLong() > 999) throw new IllegalArgumentException(path + " 物资条件不合法");
            return;
        }
        if ("NODE_COMPLETED".equals(op)) {
            long nodeId = condition.path("nodeId").asLong(0L);
            if (nodeId <= 0L) {
                throw new IllegalArgumentException(path + ".nodeId 必须是正整数");
            }
            // R22-SEC S16:nodeId 不属于本主题的条件永远不成立(结局静默走 fallback);
            // Context 给了节点集合就必须核对(与 sys.* nodeId 同一口径)。
            if (ctx.nodeIds != null && !ctx.nodeIds.contains(Long.valueOf(nodeId))) {
                throw new IllegalArgumentException(
                        path + ".nodeId 引用的节点不属于本主题:nodeId=" + nodeId);
            }
            return;
        }
        if ("HAS_TAG".equals(op)) {
            String tag = condition.path("value").asText("");
            if (!TAG.matcher(tag).matches()) {
                throw new IllegalArgumentException(path + ".value 必须是 tag.* 或 thought.*.done 状态键");
            }
            return;
        }
        if (StoryVocabulary.isCondition(op)) {
            StoryVocabulary.validateCondition(condition, path, ctx.nodeIds,
                    (childPath, child) -> requireCondition(child, childPath, ctx));
            return;
        }
        if (!COMPARE_OPS.contains(op)) {
            throw new IllegalArgumentException(path + ".op 不支持:" + op);
        }
        String var = condition.path("var").asText("");
        if (var.startsWith(SYS_PREFIX)) {
            requireReadableSysVar(var, path, ctx);
        } else if (!VAR.matcher(var).matches()) {
            throw new IllegalArgumentException(
                    path + ".var 必须是 clue./relation./counter. 状态键或只读系统变量");
        }
        JsonNode value = condition.get("value");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(path + ".value 必须是整数");
        }
        int number = value.asInt();
        if (number < -99 || number > 99) {
            throw new IllegalArgumentException(path + ".value 须为 -99 至 99");
        }
    }

    /**
     * R14 只读系统变量白名单:sys.hp / sys.luck 直接放行;sys.asked.{nodeId}.{questionId} /
     * sys.mistakes.{nodeId} / sys.outcome.{nodeId} 形状合法之外,nodeId 还必须是本主题的节点
     * (Context 给了节点集合时核对)。其他 sys.* 一律拒 —— 系统变量是只读的,作者不能自造。
     */
    private void requireReadableSysVar(String var, String path, Context ctx) {
        // 阶段 3:sys.exhausted 与 sys.hp / sys.luck 同档 —— 叙事里可以为「力竭」写一句专门的话。
        if ("sys.hp".equals(var) || "sys.luck".equals(var) || "sys.exhausted".equals(var)) {
            return;
        }
        java.util.regex.Matcher asked = SYS_ASKED_VAR.matcher(var);
        if (asked.matches()) {
            requireSysNodeInTheme(Long.valueOf(asked.group(1)), path, ctx);
            return;
        }
        java.util.regex.Matcher mistakes = SYS_MISTAKES_VAR.matcher(var);
        if (mistakes.matches()) {
            requireSysNodeInTheme(Long.valueOf(mistakes.group(1)), path, ctx);
            return;
        }
        java.util.regex.Matcher passed = SYS_PASSED_VAR.matcher(var);
        if (passed.matches()) {
            requireSysNodeInTheme(Long.valueOf(passed.group(1)), path, ctx);
            // ponytail:只校到「节点属本主题」,没有 sys.outcome 那道「指向的节点必须是该类玩法」的闸。
            // 要加就得在发布期多扫一遍模板、判「这个节点挂的是不是判定型玩法」,那会凭空多出一份
            // 「哪些 kit 算判定型」的清单,与 AdvancedGameRuntimeServiceImpl#judgedOutcome 的 switch
            // 成为两个真源、必然漂移(9-23 起判定清单是 judgedOutcome + passedFact 两张)。等收敛成单一真源再补这道闸。
            return;
        }
        java.util.regex.Matcher outcome = SYS_OUTCOME_VAR.matcher(var);
        if (outcome.matches()) {
            Long nodeId = Long.valueOf(outcome.group(1));
            requireSysNodeInTheme(nodeId, path, ctx);
            // sys.outcome 只有分支玩法和随机类玩法会写;指向别的节点 = 条件永远不成立且不报错。
            if (ctx.outcomeNodeIds != null && !ctx.outcomeNodeIds.contains(nodeId)) {
                throw new IllegalArgumentException(path + ".var 引用的节点不会产生结果序号"
                        + "(只有分支剧情 / 抛硬币 / 掷骰子 / 抽卡会写):nodeId=" + nodeId);
            }
            return;
        }
        throw new IllegalArgumentException(path + ".var 不是可读的系统变量:" + var);
    }

    private void requireSysNodeInTheme(Long nodeId, String path, Context ctx) {
        if (ctx.nodeIds != null && !ctx.nodeIds.contains(nodeId)) {
            throw new IllegalArgumentException(path + ".var 引用的节点不属于本主题:nodeId=" + nodeId);
        }
    }

    // ---------- 小工具 ----------

    private void requireChapterAvailable(Long chapterId, Context ctx) {
        if (ctx.chapterIds != null && !ctx.chapterIds.contains(chapterId)) {
            throw new IllegalArgumentException("剧情阶段引用的章节不属于本主题:chapterId=" + chapterId);
        }
    }

    private void requireNpcAvailable(Long npcId, Context ctx) {
        if (ctx.npcIds != null && !ctx.npcIds.contains(npcId)) {
            throw new IllegalArgumentException("剧情配置引用的 NPC 本主题不可用:npcId=" + npcId);
        }
    }

    /** R14:处置节点必须属于本主题,且配了分支玩法 —— 否则 sys.outcome.{nodeId} 永远不产生。 */
    private void requireDispositionNode(Long nodeId, Context ctx) {
        if (ctx.nodeIds != null && !ctx.nodeIds.contains(nodeId)) {
            throw new IllegalArgumentException("结局配置 dispositionNodeId 不属于本主题:nodeId=" + nodeId);
        }
        if (ctx.branchNodeIds != null && !ctx.branchNodeIds.contains(nodeId)) {
            throw new IllegalArgumentException(
                    "结局配置 dispositionNodeId 指向的节点不是分支玩法:nodeId=" + nodeId);
        }
    }

    /**
     * R22-A6:至少一条非兜底结局的 when 引用 sys.outcome.{dispositionNodeId}。
     * 处置节点写了却没任何结局引用它 = 三个处置结局永远不成立,全部静默落到 fallback。
     */
    private void requireDispositionReferenced(JsonNode endings, Long dispositionNodeId) {
        String expected = "sys.outcome." + dispositionNodeId;
        for (JsonNode item : endings == null ? java.util.Collections.<JsonNode>emptyList() : endings) {
            if (item == null || !item.isObject() || item.path("fallback").asBoolean(false)) {
                continue;
            }
            for (JsonNode when : item.path("when")) {
                if (when != null && when.isObject() && expected.equals(when.path("var").asText(""))) {
                    return;
                }
            }
        }
        throw new IllegalArgumentException("结局配置 dispositionNodeId=" + dispositionNodeId
                + " 没有被任何结局的 when 引用(" + expected + "):处置结局会全部落到 fallback");
    }

    private void requireSize(JsonNode array, String path) {
        if (array.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(path + " 最多 " + MAX_ITEMS + " 条");
        }
    }

    private String requiredId(JsonNode parent, String field, String path) {
        String value = text(parent, field, path);
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(path + " 须匹配 [A-Za-z0-9_-]{1,32}");
        }
        return value;
    }

    private void requiredText(JsonNode parent, String field, String path) {
        String value = text(parent, field, path);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(path + " 不能为空");
        }
    }

    private void optionalText(JsonNode parent, String field, String path) {
        text(parent, field, path);
    }

    /** 读文本字段:缺省/null 返回 null;非字符串或超长拒。 */
    private String text(JsonNode parent, String field, String path) {
        if (parent == null) {
            return null;
        }
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException(path + " 必须是字符串");
        }
        String value = node.asText();
        if (value.length() > MAX_TEXT) {
            throw new IllegalArgumentException(path + " 不能超过 " + MAX_TEXT + " 字");
        }
        return value;
    }

    private Long optionalPositiveLong(JsonNode parent, String field, String path) {
        if (parent == null) {
            return null;
        }
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() <= 0L) {
            throw new IllegalArgumentException(path + " 必须是正整数");
        }
        return node.asLong();
    }

    private boolean boolField(JsonNode parent, String field, boolean defaultValue, String path) {
        if (parent == null) {
            return defaultValue;
        }
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            throw new IllegalArgumentException(path + " 必须是布尔值");
        }
        return node.asBoolean();
    }
}
