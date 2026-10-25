package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.business.service.support.StoryVocabulary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 从一份玩法块配置(advancedConfigJson)里收集引用到的状态键(B1)。
 *
 * <p>来源:requires[] 的 var 或 tag 值、effects[].var / ADD_TAG 的 tag 值、
 * check.mods[].when 的条件、check.advantageIf / disadvantageIf、check.successEffects /
 * failEffects、check.critEffects / fumbleEffects、check.failCostTag、outcome.facts[].when、
 * revisit.changes[].when、brief.carry[].when、
 * branch.steps[].options[].effects、qa.options[].effects、sort / match / classify /
 * hiddenObject / pricePair 的 effects(通过时当场写)。
 * NODE_COMPLETED 条件问的是节点做过没有,不产生键。
 * sys.* 是服务端保留变量(只读或只由服务端写),不进键注册表,收集时整个跳过。
 * 空配置 / 缺字段 / 字段类型不对一律按「没引用」处理,老配置必须原样通过。
 *
 * <p>JSON 本身不合法时抛 {@link IllegalArgumentException} —— 发布闸要 fail-closed,
 * 不能把「解析不了」当成「没有引用」。
 */
public final class StateKeyRefs {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final java.util.regex.Pattern TEMPLATE_CLUE = java.util.regex.Pattern.compile("^clue\\.h_[0-9a-f]{12}$");


    /**
     * 服务端保留变量前缀(共享契约 v4):sys.luck 只读写在效果里,其余 sys.hp / sys.asked /
     * sys.mistakes / sys.outcome / sys.passed 只读;全部不进键注册表,发布闸也不要求注册。
     */
    private static final String SYS_PREFIX = "sys.";

    private StateKeyRefs() {
    }

    /** Original compiler clue declaration projection; pure data, no registry reads/writes or runtime facts. */
    public static Map<String, String> declaredTemplateClues(JsonNode config) {
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        if (config != null) collectTemplateClueDeclarations(config, labels);
        return java.util.Collections.unmodifiableMap(labels);
    }

    private static void collectTemplateClueDeclarations(JsonNode n, Map<String,String> labels) {
        if (n.isObject()) {
            JsonNode names = n.path("clues");
            if (names.isObject()) names.fields().forEachRemaining(row -> { if (TEMPLATE_CLUE.matcher(row.getKey()).matches() && row.getValue().isTextual()) labels.put(row.getKey(), row.getValue().asText()); });
            String var = n.path("var").asText("");
            if (TEMPLATE_CLUE.matcher(var).matches()) labels.putIfAbsent(var, var);
            n.forEach(child -> collectTemplateClueDeclarations(child, labels));
        } else if (n.isArray()) n.forEach(child -> collectTemplateClueDeclarations(child, labels));
    }


    /** 复制节点模板时只翻译实际系统变量和 NODE_COMPLETED 引用，不修改题面文本。 */
    public static String remapNodeIds(String raw, Map<Long, Long> nodeMap) {
        if (StringUtils.isBlank(raw)) return raw;
        final JsonNode root;
        try { root = MAPPER.readTree(raw); }
        catch (Exception invalid) { throw new IllegalArgumentException("高级玩法配置不是合法 JSON", invalid); }
        if (root == null || !root.isObject()) throw new IllegalArgumentException("高级玩法配置必须是对象");
        remapNodeReferences(root, nodeMap);
        try { return MAPPER.writeValueAsString(root); }
        catch (Exception invalid) { throw new IllegalArgumentException("高级玩法配置无法序列化", invalid); }
    }

    private static void remapNodeReferences(JsonNode node, Map<Long, Long> nodeMap) {
        if (node.isObject()) {
            JsonNode variable = node.get("var");
            if (variable != null && variable.isTextual()) {
                java.util.regex.Matcher match = java.util.regex.Pattern.compile("^(sys\\.(?:asked|mistakes|outcome|passed|damage)\\.)([1-9][0-9]*)(\\..*)?$").matcher(variable.asText().trim());
                if (match.matches()) {
                    final long oldId;
                    try { oldId = Long.parseLong(match.group(2)); }
                    catch (NumberFormatException invalid) { throw new IllegalArgumentException("复制模板系统变量节点标识越界", invalid); }
                    ((ObjectNode) node).put("var", match.group(1) + mappedNode(oldId, nodeMap) + (match.group(3) == null ? "" : match.group(3)));
                }
            }
            String nodeOp = node.path("op").asText();
            if ("NODE_COMPLETED".equals(nodeOp) || StoryVocabulary.VISIT_COUNT.equals(nodeOp)
                    || StoryVocabulary.CHECK_RESULT.equals(nodeOp)) {
                JsonNode id = node.get("nodeId");
                if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0) throw new IllegalArgumentException("复制模板节点完成条件标识不合法");
                ((ObjectNode) node).put("nodeId", mappedNode(id.asLong(), nodeMap));
            }
            node.elements().forEachRemaining(child -> remapNodeReferences(child, nodeMap));
        } else if (node.isArray()) node.forEach(child -> remapNodeReferences(child, nodeMap));
    }

    private static long mappedNode(long oldId, Map<Long, Long> nodeMap) {
        Long target = nodeMap == null ? null : nodeMap.get(oldId);
        if (target == null || target <= 0) throw new IllegalArgumentException("复制模板引用的节点不属于源主题：" + oldId);
        return target;
    }

    /** 本主题已声明念头的运行期完成标签，只供故事条件读取，不是可写注册键。 */
    public static Set<String> declaredThoughtDoneTags(String journeyRulesJson) {
        Set<String> tags = new LinkedHashSet<String>();
        for (JsonNode thought : JourneyRules.parse(journeyRulesJson).getThoughts()) {
            tags.add("thought." + thought.path("key").asText() + ".done");
        }
        return tags;
    }

    public static Set<String> collect(String advancedConfigJson) {
        Set<String> keys = new LinkedHashSet<String>();
        if (StringUtils.isBlank(advancedConfigJson)) {
            return keys;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(advancedConfigJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("高级玩法配置不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            return keys;
        }
        collectConditions(root.get("requires"), keys);
        collectEffects(root.get("effects"), keys);
        com.chengyinhub.business.service.support.GameplayOutcomeRules.visit(root, (path, after) -> {
            collectEffects(after.get("effects"), keys);
            for (JsonNode cost : after.path("costs")) add(keys, cost.path("statKey").asText());
        });
        JsonNode check = root.get("check");
        if (check != null && check.isObject()) {
            JsonNode mods = check.get("mods");
            if (mods != null && mods.isArray()) {
                for (JsonNode mod : mods) {
                    collectCondition(mod == null ? null : mod.get("when"), keys);
                    // F2 T03:mods[].var 与绑定属性的 check.skill 同样是状态键引用,必须已注册。
                    if (mod != null && mod.path("var").isTextual()) add(keys, mod.path("var").asText());
                }
            }
            JsonNode skill = check.get("skill");
            if (skill != null && skill.isTextual() && skill.asText().trim().startsWith("counter.")) {
                add(keys, skill.asText().trim());
            }
            collectConditions(check.get("advantageIf"), keys);
            collectConditions(check.get("disadvantageIf"), keys);
            collectEffects(check.get("successEffects"), keys);
            collectEffects(check.get("failEffects"), keys);
            // R14 大成功 / 大失败的额外效果:与 successEffects / failEffects 同一道闸。
            collectEffects(check.get("critEffects"), keys);
            collectEffects(check.get("fumbleEffects"), keys);
            // F6 T09:魔鬼交易的代价也是写入,引用的键同样要注册。
            JsonNode bargain = check.get("bargain");
            if (bargain != null && bargain.isObject()) collectEffects(bargain.get("effects"), keys);
            JsonNode failCostTag = check.get("failCostTag");
            if (failCostTag != null && failCostTag.isTextual()) {
                add(keys, failCostTag.asText());
            }
        }
        // R14 选择结果当场写状态:分支选项 / 问答选项 / 五个通过类玩法的 effects 同样是引用,
        // 漏收 = 作者配了条件写入却没注册键,静默过闸。
        collectOptionEffects(root.get("branch"), keys);
        collectOptionEffects(root.get("qa"), keys);
        for (String kit : new String[] { "sort", "match", "classify", "hiddenObject", "pricePair" }) {
            // qa 的效果只在选项级,上面按 options[] 收过了
            JsonNode section = root.get(kit);
            if (section != null && section.isObject()) {
                collectEffects(section.get("effects"), keys);
            }
        }
        // 叙事段(v4-A):outcome.facts[].when / revisit.changes[].when 与 requires 同一套条件写法,
        // 发布闸必须把它们当引用键对账,否则「配了条件但没注册键」能静默上线。
        collectRowConditions(root.get("outcome"), "facts", keys);
        collectRowConditions(root.get("revisit"), "changes", keys);
        // 叙事段(v4-B):brief.carry[].when 同一个道理 —— 漏了它,带条件的随身携带物
        // 引用的未注册键会静默过闸。
        collectRowConditions(root.get("brief"), "carry", keys);
        return keys;
    }

    /**
     * 带选项的玩法段(R14):branch 的选项挂在 steps[].options[],qa 的选项直接是 options[]。
     * 形状不对一律按「没引用」处理。
     */
    private static void collectOptionEffects(JsonNode section, Set<String> keys) {
        if (section == null || !section.isObject()) {
            return;
        }
        collectOptionRowEffects(section.get("options"), keys);
        JsonNode steps = section.get("steps");
        if (steps != null && steps.isArray()) {
            for (JsonNode step : steps) {
                collectOptionRowEffects(step == null ? null : step.get("options"), keys);
            }
        }
    }

    private static void collectOptionRowEffects(JsonNode options, Set<String> keys) {
        if (options == null || !options.isArray()) {
            return;
        }
        for (JsonNode option : options) {
            if (option == null || !option.isObject()) {
                continue;
            }
            collectEffects(option.get("effects"), keys);
        }
    }

    /** 叙事段里的 when 条件行:when 缺省 / 为 null 或整段形状不对都按「没引用」处理。 */
    private static void collectRowConditions(JsonNode section, String rowsField, Set<String> keys) {
        if (section == null || !section.isObject()) {
            return;
        }
        JsonNode rows = section.get(rowsField);
        if (rows == null || !rows.isArray()) {
            return;
        }
        for (JsonNode row : rows) {
            collectCondition(row == null ? null : row.get("when"), keys);
        }
    }

    /**
     * R19:章节故事流存量 blocks 里 beat 块上的 when(brief carry / outcome fact / revisit change)
     * 引用的状态键。叙事从模板搬进故事流后,模板那份条件是死配置、故事流这份才是生效来源;
     * 发布闸若不跟着搬,「配了条件但键没注册」会从模板时代的必拦变成故事流时代的静默放行。
     *
     * <p>blocks 不是合法 JSON 时抛 {@link IllegalArgumentException},与节点块配置同一口径 ——
     * 发布闸 / 删除前查引用要 fail-closed,读不出引用不能当成没有引用;形状不对
     * (不是数组 / 条目不是对象)仍按「没引用」处理,与读取侧对脏块只跳过一致。
     */
    /**
     * 思维阁:章节 blocks 里 thought 块引用的念头 key。
     *
     * <p>块里只放引用,念头的名字与步数阈值住在 {@code journeyRules.thoughts} —— 同一条数据
     * 不要两个真源。编译期只校验 key 的格式(拿不到 journeyRules),**存在性由发布闸核对**:
     * 引用了不存在的念头,玩家永远得不到它,而且零报错。</p>
     */
    public static Set<String> collectChapterThoughtRefs(String blocksJson) {
        Set<String> keys = new LinkedHashSet<String>();
        if (StringUtils.isBlank(blocksJson)) return keys;
        JsonNode blocks;
        try {
            blocks = MAPPER.readTree(blocksJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("章节故事流不是合法 JSON");
        }
        if (!blocks.isArray()) return keys;
        for (JsonNode block : blocks) {
            if (!"thought".equals(block.path("type").asText())) continue;
            String key = block.path("thoughtKey").asText("").trim();
            if (!key.isEmpty()) keys.add(key);
        }
        return keys;
    }

    public static Set<String> collectChapterBlocks(String blocksJson) {
        Set<String> keys = new LinkedHashSet<String>();
        if (StringUtils.isBlank(blocksJson)) {
            return keys;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(blocksJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("章节故事流不是合法 JSON");
        }
        if (root == null || !root.isArray()) {
            return keys;
        }
        for (JsonNode block : root) {
            if (block == null || !block.isObject()) {
                continue;
            }
            JsonNode beat = block.get("beat");
            if (beat == null || !beat.isTextual() || beat.asText().trim().isEmpty()) {
                continue;
            }
            keys.addAll(collectWhen(block.get("when")));
        }
        return keys;
    }

    /**
     * 单个「when」节点引用的键:对象 = 一条 cond,数组 = 若干 cond,其余形状按「没引用」。
     * 供主题级剧情配置(journey_story_json)的条件收集复用 —— 条件写法与块配置同一套。
     */
    public static Set<String> collectWhen(JsonNode when) {
        Set<String> keys = new LinkedHashSet<String>();
        if (when == null) {
            return keys;
        }
        if (when.isArray()) {
            for (JsonNode item : when) collectCondition(item, keys);
        } else {
            collectCondition(when, keys);
        }
        return keys;
    }

    /**
     * R8-STORY:从主题级剧情配置(journeyStoryJson)里收集 cond 引用到的状态键(B1 发布闸)。
     *
     * <p>来源:stages[].end.changed[].when、ending.endings[].when、ending.exhibits[].when、
     * ending.badges[].when、ending.optionalClues[]、ending.epilogues[].relationKey。
     * 尾声的 npcId 只是 NPC 引用,不产生状态键。
     * 空配置 / 缺字段 / 字段类型不对一律按「没引用」处理,老主题必须原样通过。
     *
     * <p>JSON 本身不合法时抛 {@link IllegalArgumentException} —— 发布闸要 fail-closed。
     */
    public static Set<String> collectJourneyStory(String journeyStoryJson) {
        Set<String> keys = new LinkedHashSet<String>();
        if (StringUtils.isBlank(journeyStoryJson)) {
            return keys;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(journeyStoryJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("主题剧情配置不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            return keys;
        }
        JsonNode stages = root.get("stages");
        if (stages != null && stages.isArray()) {
            for (JsonNode stage : stages) {
                JsonNode end = stage == null ? null : stage.get("end");
                collectConditionEntries(end == null ? null : end.get("changed"), keys);
            }
        }
        JsonNode ending = root.get("ending");
        if (ending != null && ending.isObject()) {
            addRelationKeys(ending.get("epilogues"), keys);
            JsonNode endings = ending.get("endings");
            if (endings != null && endings.isArray()) {
                for (JsonNode item : endings) collectConditions(item == null ? null : item.get("when"), keys);
            }
            JsonNode exhibits = ending.get("exhibits");
            if (exhibits != null && exhibits.isArray()) {
                for (JsonNode item : exhibits) collectCondition(item == null ? null : item.get("when"), keys);
            }
            JsonNode badges = ending.get("badges");
            if (badges != null && badges.isArray()) {
                for (JsonNode item : badges) collectConditions(item == null ? null : item.get("when"), keys);
            }
            JsonNode optionalClues = ending.get("optionalClues");
            if (optionalClues != null && optionalClues.isArray()) {
                for (JsonNode item : optionalClues) add(keys, text(item));
            }
        }
        return keys;
    }

    /**
     * R11-C:尾声的 relationKey 集合。发布闸除「已注册」外还要核对类型必须是 relation,
     * 故单独收一份;解析口径与 {@link #collectJourneyStory} 完全一致(坏 JSON fail-closed)。
     */
    public static Set<String> collectJourneyStoryRelationKeys(String journeyStoryJson) {
        Set<String> keys = new LinkedHashSet<String>();
        if (StringUtils.isBlank(journeyStoryJson)) {
            return keys;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(journeyStoryJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("主题剧情配置不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            return keys;
        }
        JsonNode ending = root.get("ending");
        if (ending != null && ending.isObject()) {
            addRelationKeys(ending.get("epilogues"), keys);
        }
        return keys;
    }

    /** epilogues[].relationKey:显式的关系值来源键。 */
    private static void addRelationKeys(JsonNode epilogues, Set<String> keys) {
        if (epilogues == null || !epilogues.isArray()) {
            return;
        }
        for (JsonNode item : epilogues) {
            add(keys, text(item == null ? null : item.get("relationKey")));
        }
    }

    /** changed[] / exhibits[] 这类「条目里带一个 when 条件」的数组。 */
    private static void collectConditionEntries(JsonNode array, Set<String> keys) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) collectCondition(item == null ? null : item.get("when"), keys);
    }

    private static void collectConditions(JsonNode array, Set<String> keys) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) collectCondition(item, keys);
    }

    /**
     * 条件条目:比较类取 var,HAS_TAG 取 tag 值,NODE_COMPLETED 不产生键。
     * R14:sys.* 是服务端保留变量,不进键注册表,也不要求注册。
     */
    private static void collectCondition(JsonNode item, Set<String> keys) {
        if (item == null || !item.isObject()) {
            return;
        }
        String op = item.path("op").asText("");
        if ("NODE_COMPLETED".equals(op)) {
            return;
        }
        // F5 组合条件:内层叶子照样要进键注册表核对;其余新叶子(到访次数 / 角色 / 状态 / 装备 / 检定)不读具名状态键。
        if (StoryVocabulary.AND.equals(op) || StoryVocabulary.OR.equals(op)) {
            collectConditions(item.get("conditions"), keys);
            return;
        }
        if (StoryVocabulary.NOT.equals(op)) {
            collectCondition(item.get("condition"), keys);
            return;
        }
        if (StoryVocabulary.isCondition(op)) {
            return;
        }
        if ("HAS_TAG".equals(op)) {
            add(keys, text(item.get("value")));
            return;
        }
        String var = text(item.get("var"));
        if (var != null && var.trim().startsWith("thought.")) {
            throw new IllegalArgumentException("念头完成标签只允许 HAS_TAG 读取");
        }
        if (var != null && var.trim().startsWith(SYS_PREFIX)) {
            return;
        }
        add(keys, var);
    }

    /** 效果条目:SET / INC 取 var,ADD_TAG 取 tag 值。 */
    private static void collectEffects(JsonNode array, Set<String> keys) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            if (item == null || !item.isObject()) {
                continue;
            }
            if ("ADD_TAG".equals(item.path("op").asText(""))) {
                add(keys, text(item.get("value")));
                continue;
            }
            String var = text(item.get("var"));
            if (var != null && var.trim().startsWith(SYS_PREFIX)) {
                continue;       // sys.luck 及一切 sys.* 都不进键注册表
            }
            add(keys, var);
        }
    }

    /**
     * R14 发布期闸专用:块配置里条件引用的系统变量 nodeId(sys.asked.{nodeId}.{qid} /
     * sys.mistakes.{nodeId} / sys.outcome.{nodeId})。nodeId 是不是本主题的节点由调用方核对
     * —— 这里只负责把一个配置里出现的 nodeId 全收出来。
     *
     * <p>整棵树扫 var 字段:条件与效果都落在同一批字段名上,多收(比如非法的效果 sys.*)
     * 只会让发布期更严,不会放过真正的坏引用。坏 JSON 与 {@link #collect} 一样 fail-closed。
     */
    public static Set<Long> collectSysNodeIds(String advancedConfigJson) {
        Set<Long> nodeIds = new LinkedHashSet<Long>();
        if (StringUtils.isBlank(advancedConfigJson)) {
            return nodeIds;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(advancedConfigJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("高级玩法配置不是合法 JSON");
        }
        walkSysVars(root, nodeIds);
        return nodeIds;
    }

    private static void walkSysVars(JsonNode node, Set<Long> nodeIds) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode var = node.get("var");
            if (var != null && var.isTextual()) {
                addSysNodeId(var.asText().trim(), nodeIds);
            }
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                walkSysVars(fields.next().getValue(), nodeIds);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                walkSysVars(item, nodeIds);
            }
        }
    }

    /** sys.asked.123.night / sys.mistakes.123 / sys.outcome.123 / sys.passed.123 → 123;其余形状不产生 nodeId。 */
    private static void addSysNodeId(String var, Set<Long> nodeIds) {
        if (var == null || !var.startsWith(SYS_PREFIX)) {
            return;
        }
        String[] parts = var.split("\\.");
        if (parts.length < 3) {
            return;
        }
        if (!"asked".equals(parts[1]) && !"mistakes".equals(parts[1]) && !"outcome".equals(parts[1])
                && !"passed".equals(parts[1])) {
            return;
        }
        try {
            long nodeId = Long.parseLong(parts[2]);
            if (nodeId > 0L) {
                nodeIds.add(Long.valueOf(nodeId));
            }
        } catch (NumberFormatException notANodeRef) {
            // 形状不对:校验器负责拦,这里不产生 nodeId
        }
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static void add(Set<String> keys, String raw) {
        if (raw == null) {
            return;
        }
        String key = raw.trim();
        if (!key.isEmpty()) {
            keys.add(key);
        }
    }
}
