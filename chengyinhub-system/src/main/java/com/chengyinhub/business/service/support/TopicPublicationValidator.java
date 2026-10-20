package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * Existing publication rules over already-loaded source values. No mapper, service,
 * Spring registration, transaction or authority decision. Staged entry points preserve
 * ordinary publication's original read/write order; captured sources never reread live rows.
 */
public final class TopicPublicationValidator {
    private final JourneyStoryValidator journeyStoryValidator = new JourneyStoryValidator();
    private final AdvancedGameConfigValidator advancedGameConfigValidator = new AdvancedGameConfigValidator();
    private static final ObjectMapper ROUTE_JSON = new ObjectMapper();
    private static final List<String> NARRATIVE_NPC_SECTIONS = Arrays.asList("enter", "outcome");

    /** Pure first stage. No registry read/write; null means the old gate had no references. */
    public StateKeys prepareStateKeys(CmsTopic topic, TopicStateKeyRefService.RefCollection refs) {
        return prepareStateKeys(topic, refs, false);
    }

    /** Existing service-only compatibility for hand-built tests without the collector. */
    public StateKeys prepareWithoutCollector(CmsTopic topic) {
        return prepareStateKeys(topic, null, true);
    }

    private StateKeys prepareStateKeys(CmsTopic topic, TopicStateKeyRefService.RefCollection refs,
                                       boolean legacyWithoutCollector) {
        // 键 → 引用它的节点名,发布失败时一起报出来
        Map<String, Set<String>> refNodeNames = new LinkedHashMap<String, Set<String>>();
        Set<String> storyKeys = new LinkedHashSet<String>();
        Set<String> chapterKeys = new LinkedHashSet<String>();
        String exhaustTag;
        if (legacyWithoutCollector) {
            // 收集服务未注入(手工 new 的单测):数值规则的力竭标记按本类口径兜底核对;
            // 节点块引用只在收集服务里,这一支没有,行为与旧单测一致。
            exhaustTag = journeyRulesExhaustTag(topic);
        } else {
            if (!refs.getParseErrors().isEmpty()) {
                throw new ServiceException(TopicStateKeyRefService.parseErrorMessage(refs.getParseErrors().get(0)));
            }
            for (Map.Entry<Long, Set<String>> entry : refs.getRefsByNode().entrySet()) {
                String label = refs.getNodeLabels().get(entry.getKey());
                for (String ref : entry.getValue()) {
                    Set<String> names = refNodeNames.get(ref);
                    if (names == null) {
                        names = new LinkedHashSet<String>();
                        refNodeNames.put(ref, names);
                    }
                    names.add(label);
                }
            }
            storyKeys.addAll(refs.getStoryKeys());
            chapterKeys.addAll(refs.getChapterKeys());
            exhaustTag = refs.getExhaustTag();
        }
        // R8-STORY:剧情配置里 cond / optionalClues 引用的键单独收一份 —— 收集服务那份只认 when
        // (且未注入时会整侧跳过),这里按键去重后补齐 optionalClues。
        Map<String, String> storyRefs = journeyStoryStateKeyRefs(topic);
        if (refNodeNames.isEmpty() && exhaustTag == null && storyKeys.isEmpty() && storyRefs.isEmpty()
                && chapterKeys.isEmpty()) {
            return null;
        }
        Set<String> thoughtDoneTags = com.chengyinhub.business.util.StateKeyRefs
                .declaredThoughtDoneTags(topic.getJourneyRulesJson());
        return new StateKeys(topic, refNodeNames, storyKeys, chapterKeys, exhaustTag, storyRefs, thoughtDoneTags);
    }

    /** Second stage runs after the caller's existing option-tag registration and key reread. */
    public void assertRegisteredStateKeys(StateKeys context, List<ThemeStateKey> keys) {
        CmsTopic topic = context.topic;
        Map<String, Set<String>> refNodeNames = context.refNodeNames;
        Set<String> storyKeys = context.storyKeys;
        Set<String> chapterKeys = context.chapterKeys;
        String exhaustTag = context.exhaustTag;
        Map<String, String> storyRefs = context.storyRefs;
        Set<String> thoughtDoneTags = context.thoughtDoneTags;
        Set<String> registered = new LinkedHashSet<String>();
        Map<String, String> registeredTypes = new LinkedHashMap<String, String>();
        for (ThemeStateKey row : keys) {
            if (row != null && !StringUtils.isEmpty(row.getStateKey())) {
                registered.add(row.getStateKey());
                registeredTypes.put(row.getStateKey(), row.getKeyType());
            }
        }
        List<String> missing = new ArrayList<String>();
        for (Map.Entry<String, Set<String>> entry : refNodeNames.entrySet()) {
            if (!registered.contains(entry.getKey())) {
                missing.add(entry.getKey() + "(节点:" + String.join(",", entry.getValue()) + ")");
            }
        }
        // R2-B10:主题数值规则的力竭标记与玩法块引用同一道闸 —— 它会被检定链路写进本局 state_json,
        // 没注册就是往一个主题没声明过的键上写。
        if (exhaustTag != null && !registered.contains(exhaustTag)) {
            missing.add(exhaustTag + "(来源:主题数值规则)");
        }
        // R8:主题剧情配置(journey_story_json)的 when 条件同样只能引用已注册的键。
        for (String storyKey : storyKeys) {
            if (!registered.contains(storyKey) && !thoughtDoneTags.contains(storyKey)) {
                missing.add(storyKey + "(来源:主题剧情配置)");
            }
        }
        // R19:叙事搬进章节故事流后,beat 块上的 when(brief carry / outcome fact / revisit change)
        // 与模板叙事段同一道闸 —— 否则「配了条件但没注册键」会从必拦变成静默放行。
        for (String chapterKey : chapterKeys) {
            if (!registered.contains(chapterKey) && !thoughtDoneTags.contains(chapterKey)) {
                missing.add(chapterKey + "(来源:章节故事流)");
            }
        }
        // R8-STORY:剧情配置的 cond 与 optionalClues 引用同一注册表(收集服务已收过的键不重复报)
        for (Map.Entry<String, String> entry : storyRefs.entrySet()) {
            if (!registered.contains(entry.getKey()) && !storyKeys.contains(entry.getKey())
                    && !thoughtDoneTags.contains(entry.getKey())) {
                missing.add(entry.getKey() + "(来源:" + entry.getValue() + ")");
            }
        }
        // R11-C:尾声的 relationKey 已注册还不够,类型必须就是 relation(关系值来源口径)。
        for (String relationKey : journeyStoryRelationKeys(topic)) {
            if (!registered.contains(relationKey)) {
                continue;       // 未注册已由上面的通用闸报出,不重复报
            }
            if (!ThemeStateKey.TYPE_RELATION.equals(registeredTypes.get(relationKey))) {
                missing.add(relationKey + "(来源:主题剧情配置尾声,键类型必须是 relation)");
            }
        }
        if (!missing.isEmpty()) {
            throw new ServiceException("主题发布失败:以下状态键未注册 → " + missing);
        }
    }

    /** Transaction-local computed references; not an authorization or release approval. */
    public static final class StateKeys {
        private final CmsTopic topic;
        private final Map<String, Set<String>> refNodeNames;
        private final Set<String> storyKeys, chapterKeys, thoughtDoneTags;
        private final String exhaustTag;
        private final Map<String, String> storyRefs;
        private StateKeys(CmsTopic topic, Map<String, Set<String>> refNodeNames, Set<String> storyKeys,
                          Set<String> chapterKeys, String exhaustTag, Map<String, String> storyRefs,
                          Set<String> thoughtDoneTags) {
            this.topic=topic; this.refNodeNames=refNodeNames; this.storyKeys=storyKeys;
            this.chapterKeys=chapterKeys; this.exhaustTag=exhaustTag; this.storyRefs=storyRefs;
            this.thoughtDoneTags=thoughtDoneTags;
        }
        public Set<String> optionTagReferences() {
            Set<String> refs = new LinkedHashSet<String>(refNodeNames.keySet());
            refs.addAll(storyKeys); refs.addAll(chapterKeys); refs.addAll(storyRefs.keySet());
            return refs;
        }
    }

    public void assertThoughtRefsResolvable(CmsTopic topic, List<CmsTopicChapter> chapters) {
        if (chapters == null) return;
        java.util.Set<String> refs = new java.util.LinkedHashSet<String>();
        for (CmsTopicChapter chapter : chapters) {
            if (chapter == null || StringUtils.isBlank(chapter.getBlocksJson())) continue;
            refs.addAll(com.chengyinhub.business.util.StateKeyRefs.collectChapterThoughtRefs(chapter.getBlocksJson()));
        }
        if (refs.isEmpty()) return;
        java.util.Set<String> defined = new java.util.LinkedHashSet<String>();
        if (StringUtils.isNotBlank(topic.getJourneyRulesJson())) {
            for (com.fasterxml.jackson.databind.JsonNode row
                    : com.chengyinhub.business.domain.JourneyRules.parse(topic.getJourneyRulesJson()).getThoughts()) {
                defined.add(row.path("key").asText());
            }
        }
        for (String key : refs) {
            if (!defined.contains(key)) {
                throw new ServiceException("故事里用到的念头「" + key + "」还没在思维阁里定义,玩家永远得不到它");
            }
        }
    }

    public Map<Long, List<String>> templateNarrativeNpcReferences(List<CmsTopicNode> nodes,
                                                                 Map<Long, String> configByTemplate) {
        Map<Long, List<String>> declared = new LinkedHashMap<Long, List<String>>();
        for (CmsTopicNode node : nodes) {
            String raw = node.getTemplateId() == null ? null : configByTemplate.get(node.getTemplateId());
            for (String section : NARRATIVE_NPC_SECTIONS) {
                Long npcId = blockNarrativeNpcId(raw, section);
                if (npcId == null) {
                    continue;
                }
                declareNpc(declared, npcId, "节点:" + node.getName() + ",段:" + section);
            }
        }
        return declared;
    }

    public void addChapterNarrativeNpcReferences(Map<Long, List<String>> declared,
                                                 List<CmsTopicNode> nodes, List<CmsTopicChapter> chapters) {
        Map<Long, String> nodeNames = new LinkedHashMap<Long, String>();
        for (CmsTopicNode node : nodes) {
            if (node.getId() != null) nodeNames.put(node.getId(), node.getName());
        }
        for (CmsTopicChapter chapter : nullToEmpty(chapters)) {
            if (chapter == null || StringUtils.isEmpty(chapter.getBlocksJson())) {
                continue;
            }
            for (Map.Entry<Long, com.fasterxml.jackson.databind.node.ObjectNode> entry
                    : ChapterNarrativeBlocks.assembleByNode(chapter.getBlocksJson()).entrySet()) {
                String nodeName = nodeNames.get(entry.getKey());
                if (nodeName == null) nodeName = "节点#" + entry.getKey();
                for (String section : NARRATIVE_NPC_SECTIONS) {
                    JsonNode npcId = entry.getValue().path(section).path("npcId");
                    if (!npcId.isIntegralNumber() || npcId.asLong() <= 0L) {
                        continue;
                    }
                    declareNpc(declared, Long.valueOf(npcId.asLong()),
                            "节点:" + nodeName + ",段:" + section + "(故事流)");
                }
            }
        }
    }

    public void assertNarrativeNpcReferences(Map<Long, List<String>> declared, Set<Long> available) {
        List<String> missing = new ArrayList<String>();
        for (Map.Entry<Long, List<String>> entry : declared.entrySet()) {
            if (!available.contains(entry.getKey())) {
                missing.add("npcId=" + entry.getKey() + "(" + String.join(",", entry.getValue()) + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw new ServiceException("主题发布失败:节点叙事的 npcId 不属于本主题可用 NPC → " + missing);
        }
    }

    public void assertBlockSysVarReferences(List<CmsTopicNode> nodes, Set<Long> nodeIds,
                                            Map<Long, String> configByTemplate) {
        List<String> missing = new ArrayList<String>();
        List<String> foreignCompleted = new ArrayList<String>();
        for (CmsTopicNode node : nodes) {
            String raw = node.getTemplateId() == null ? null : configByTemplate.get(node.getTemplateId());
            if (StringUtils.isEmpty(raw)) {
                continue;
            }
            for (Long referenced : com.chengyinhub.business.util.StateKeyRefs.collectSysNodeIds(raw)) {
                if (!nodeIds.contains(referenced)) {
                    missing.add("nodeId=" + referenced + "(节点:" + node.getName() + ")");
                }
            }
            // R22-SEC S16:NODE_COMPLETED 也是节点引用 —— nodeId 写错的条件永远不成立,发布期必须拦。
            try {
                advancedGameConfigValidator.assertCompletedNodeIdsOwned(raw, nodeIds);
            } catch (RuntimeException e) {
                foreignCompleted.add("节点:" + node.getName() + " — " + e.getMessage());
            }
        }
        if (!missing.isEmpty() || !foreignCompleted.isEmpty()) {
            StringBuilder message = new StringBuilder("主题发布失败:");
            if (!missing.isEmpty()) {
                message.append("块配置引用的系统变量节点不属于本主题 → ").append(missing);
            }
            if (!foreignCompleted.isEmpty()) {
                if (!missing.isEmpty()) {
                    message.append(";");
                }
                message.append("块配置的 NODE_COMPLETED 引用无效 → ").append(foreignCompleted);
            }
            throw new ServiceException(message.toString());
        }
    }

    public void assertJourneyStoryReferences(String raw, Set<Long> chapterIds, Set<Long> availableNpcIds,
                                              Set<Long> nodeIds, Set<Long> branchNodeRefs, Set<Long> outcomeNodeRefs) {
        try {
            journeyStoryValidator.validate(raw, com.chengyinhub.business.service.support.JourneyStoryValidator.Context.of(
                    chapterIds, availableNpcIds, nodeIds, branchNodeRefs, outcomeNodeRefs));
        } catch (RuntimeException e) {
            throw new ServiceException("主题的剧情配置引用无效,主题发布失败:" + e.getMessage());
        }
    }

    private String journeyRulesExhaustTag(CmsTopic topic) {
        String raw = topic == null ? null : topic.getJourneyRulesJson();
        if (StringUtils.isEmpty(raw)) {
            return null;
        }
        JourneyRules rules;
        try {
            rules = JourneyRules.parse(raw);
        } catch (RuntimeException e) {
            throw new ServiceException("主题的旅程数值规则无法解析,主题发布失败:" + e.getMessage());
        }
        // R22-SEC S15:checkEnabled 只关检定,不豁免力竭标记的注册闸(运行期仍会写入)。
        if (rules.getRecovery() == null) {
            return null;
        }
        String tag = rules.getRecovery().getExhaustTag();
        return StringUtils.isEmpty(tag) ? null : tag;
    }

    private static void declareNpc(Map<Long, List<String>> declared, Long npcId, String where) {
        List<String> positions = declared.get(npcId);
        if (positions == null) {
            positions = new ArrayList<String>();
            declared.put(npcId, positions);
        }
        positions.add(where);
    }

    private static Long blockNarrativeNpcId(String advancedConfigJson, String section) {
        if (StringUtils.isEmpty(advancedConfigJson)) {
            return null;
        }
        try {
            JsonNode config = ROUTE_JSON.readTree(advancedConfigJson);
            if (config == null || !config.isObject()) {
                return null;
            }
            JsonNode npcId = config.path(section).path("npcId");
            return npcId.isIntegralNumber() && npcId.asLong() > 0L ? Long.valueOf(npcId.asLong()) : null;
        } catch (Exception unusableBlockConfig) {
            return null;
        }
    }

    public static boolean blockWritesOutcome(String advancedConfigJson) {
        if (StringUtils.isEmpty(advancedConfigJson)) {
            return false;
        }
        try {
            JsonNode config = ROUTE_JSON.readTree(advancedConfigJson);
            if (config == null || !config.isObject()) return false;
            for (String kit : new String[]{"branch", "coinFlip", "diceRoll", "random"}) {
                if (config.path(kit).path("enabled").asBoolean(false)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean blockIsBranch(String advancedConfigJson) {
        if (StringUtils.isEmpty(advancedConfigJson)) {
            return false;
        }
        try {
            JsonNode config = ROUTE_JSON.readTree(advancedConfigJson);
            return config != null && config.isObject()
                    && config.path("branch").path("enabled").asBoolean(false);
        } catch (Exception unusableBlockConfig) {
            return false;
        }
    }

    private Map<String, String> journeyStoryStateKeyRefs(CmsTopic topic) {
        String raw = topic == null ? null : topic.getJourneyStoryJson();
        Map<String, String> refs = new LinkedHashMap<String, String>();
        if (StringUtils.isEmpty(raw)) {
            return refs;
        }
        Set<String> keys;
        try {
            keys = com.chengyinhub.business.util.StateKeyRefs.collectJourneyStory(raw);
        } catch (RuntimeException e) {
            throw new ServiceException("主题的剧情配置无法解析,主题发布失败:" + e.getMessage());
        }
        for (String key : keys) {
            refs.put(key, "剧情配置");
        }
        return refs;
    }

    private Set<String> journeyStoryRelationKeys(CmsTopic topic) {
        String raw = topic == null ? null : topic.getJourneyStoryJson();
        if (StringUtils.isEmpty(raw)) {
            return new LinkedHashSet<String>();
        }
        try {
            return com.chengyinhub.business.util.StateKeyRefs.collectJourneyStoryRelationKeys(raw);
        } catch (RuntimeException e) {
            throw new ServiceException("主题的剧情配置无法解析,主题发布失败:" + e.getMessage());
        }
    }

    private static <T> List<T> nullToEmpty(List<T> rows) {
        return rows == null ? Collections.<T>emptyList() : rows;
    }
}
