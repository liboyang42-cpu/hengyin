package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Frozen verbatim publication algorithms from 0addcba. Never update to match the extracted validator. */
public final class LegacyTopicPublicationOracle {
    private CmsTopicChapterMapper cmsTopicChapterMapper;
    private CmsTopicNodeMapper cmsTopicNodeMapper;
    private CmsMemberTemplateMapper cmsMemberTemplateMapper;
    private NpcProfileMapper npcProfileMapper;
    private ThemeStateKeyService themeStateKeyService;
    private LegacyTopicStateKeyRefService topicStateKeyRefService;
    private final JourneyStoryValidator journeyStoryValidator = new JourneyStoryValidator();
    private final AdvancedGameConfigValidator advancedGameConfigValidator = new AdvancedGameConfigValidator();
    private static final ObjectMapper ROUTE_JSON = new ObjectMapper();
    private static final List<String> NARRATIVE_NPC_SECTIONS = Arrays.asList("enter", "outcome");

    private void assertThoughtRefsResolvable(CmsTopic topic) {
        if (cmsTopicChapterMapper == null) return;
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(topic.getId());
        List<CmsTopicChapter> chapters = cmsTopicChapterMapper.selectCmsTopicChapterList(probe);
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

    private final void autoRegisterOptionTags(Long topicId, java.util.Collection<String>... sources) {
        List<ThemeStateKey> tags = new ArrayList<ThemeStateKey>();
        Set<String> seen = new LinkedHashSet<String>();
        for (java.util.Collection<String> source : sources) {
            for (String key : source) {
                if (key == null || !key.startsWith("tag.") || !ThemeStateKey.KEY_NAME.matcher(key).matches() || !seen.add(key)) continue;
                ThemeStateKey row = new ThemeStateKey();
                row.setStateKey(key);
                row.setKeyType(ThemeStateKey.TYPE_TAG);
                row.setScope(ThemeStateKey.SCOPE_RUN);
                row.setLabel(key);
                tags.add(row);
            }
        }
        if (tags.isEmpty()) return;
        Set<String> existing = new LinkedHashSet<String>();
        for (ThemeStateKey row : themeStateKeyService.listByTopic(topicId)) {
            if (row != null) existing.add(row.getStateKey());
        }
        List<ThemeStateKey> fresh = new ArrayList<ThemeStateKey>();
        for (ThemeStateKey row : tags) if (!existing.contains(row.getStateKey())) fresh.add(row);
        themeStateKeyService.register(topicId, fresh);
    }

    private void assertStateKeysRegistered(CmsTopic topic) {
        if (themeStateKeyService == null) {
            // 手工 new 的旧单测没注入本类依赖(Spring 运行态必注入,缺失会是启动期错误)。
            return;
        }
        // 键 → 引用它的节点名,发布失败时一起报出来
        Map<String, Set<String>> refNodeNames = new LinkedHashMap<String, Set<String>>();
        Set<String> storyKeys = new LinkedHashSet<String>();
        Set<String> chapterKeys = new LinkedHashSet<String>();
        String exhaustTag;
        if (topicStateKeyRefService == null) {
            // 收集服务未注入(手工 new 的单测):数值规则的力竭标记按本类口径兜底核对;
            // 节点块引用只在收集服务里,这一支没有,行为与旧单测一致。
            exhaustTag = journeyRulesExhaustTag(topic);
        } else {
            LegacyTopicStateKeyRefService.RefCollection refs = topicStateKeyRefService.collect(topic);
            if (!refs.getParseErrors().isEmpty()) {
                throw new ServiceException(LegacyTopicStateKeyRefService.parseErrorMessage(refs.getParseErrors().get(0)));
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
            return;
        }
        Set<String> thoughtDoneTags = com.chengyinhub.business.util.StateKeyRefs
                .declaredThoughtDoneTags(topic.getJourneyRulesJson());
        autoRegisterOptionTags(topic.getId(), refNodeNames.keySet(), storyKeys, chapterKeys, storyRefs.keySet());
        Set<String> registered = new LinkedHashSet<String>();
        Map<String, String> registeredTypes = new LinkedHashMap<String, String>();
        for (ThemeStateKey row : themeStateKeyService.listByTopic(topic.getId())) {
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

    private Set<Long> journeyStoryAvailableNpcIds(Long topicId) {
        if (npcProfileMapper == null) {
            return null;
        }
        Set<Long> ids = new LinkedHashSet<Long>();
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        probe.setStatus(1L);
        for (CmsTopicNode node : nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(probe))) {
            for (NpcProfile profile : nullToEmpty(npcProfileMapper.selectEnabledByScope(
                    NpcProfileService.SCOPE_NODE, node.getId()))) {
                if (profile.getId() != null) ids.add(profile.getId());
            }
            if (node.getMerchantMemberId() != null) {
                for (NpcProfile profile : nullToEmpty(npcProfileMapper.selectEnabledByScope(2, node.getMerchantMemberId()))) {
                    if (profile.getId() != null) ids.add(profile.getId());
                }
            }
        }
        return ids;
    }

    private void assertBlockNarrativeNpcReferences(CmsTopic topic) {
        if (topic == null || npcProfileMapper == null || cmsMemberTemplateMapper == null) {
            return;
        }
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topic.getId());
        probe.setStatus(1L);
        List<CmsTopicNode> nodes = nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(probe));
        Set<Long> templateIds = new LinkedHashSet<Long>();
        for (CmsTopicNode node : nodes) {
            if (node.getTemplateId() != null && node.getTemplateId() > 0L) templateIds.add(node.getTemplateId());
        }
        if (templateIds.isEmpty()) {
            return;
        }
        Map<Long, String> configByTemplate = new LinkedHashMap<Long, String>();
        for (CmsMemberTemplate template : nullToEmpty(
                cmsMemberTemplateMapper.selectCmsMemberTemplateByIds(new ArrayList<Long>(templateIds)))) {
            if (template != null && template.getId() != null) {
                configByTemplate.put(template.getId(), template.getAdvancedConfigJson());
            }
        }
        // 老主题没有任何叙事 npcId 时连 NPC 名单都不查(发布行为与以前一致)。
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
        // R18:叙事段已迁往章节故事流 —— beat 块(enter / outcome)上的 npcId 过同一道闸,
        // 否则故事流里配了本主题外的 NPC 会静默通过发布,玩家侧只看到 npc=null。
        if (cmsTopicChapterMapper != null) {
            Map<Long, String> nodeNames = new LinkedHashMap<Long, String>();
            for (CmsTopicNode node : nodes) {
                if (node.getId() != null) nodeNames.put(node.getId(), node.getName());
            }
            CmsTopicChapter chapterProbe = new CmsTopicChapter();
            chapterProbe.setTopicId(topic.getId());
            for (CmsTopicChapter chapter : nullToEmpty(cmsTopicChapterMapper.selectCmsTopicChapterList(chapterProbe))) {
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
        if (declared.isEmpty()) {
            return;
        }
        Set<Long> available = journeyStoryAvailableNpcIds(topic.getId());
        if (available == null) {
            return;
        }
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

    private void assertBlockSysVarReferences(CmsTopic topic) {
        if (topic == null || topic.getId() == null || cmsMemberTemplateMapper == null) {
            return;
        }
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topic.getId());
        probe.setStatus(1L);
        List<CmsTopicNode> nodes = nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(probe));
        Set<Long> nodeIds = new LinkedHashSet<Long>();
        Set<Long> templateIds = new LinkedHashSet<Long>();
        for (CmsTopicNode node : nodes) {
            if (node == null || node.getId() == null) continue;
            nodeIds.add(node.getId());
            if (node.getTemplateId() != null && node.getTemplateId() > 0L) templateIds.add(node.getTemplateId());
        }
        if (templateIds.isEmpty()) {
            return;
        }
        Map<Long, String> configByTemplate = new LinkedHashMap<Long, String>();
        for (CmsMemberTemplate template : nullToEmpty(
                cmsMemberTemplateMapper.selectCmsMemberTemplateByIds(new ArrayList<Long>(templateIds)))) {
            if (template != null && template.getId() != null) {
                configByTemplate.put(template.getId(), template.getAdvancedConfigJson());
            }
        }
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

    private void assertJourneyStoryReferences(CmsTopic topic) {
        String raw = topic == null ? null : topic.getJourneyStoryJson();
        if (StringUtils.isEmpty(raw)) {
            return;
        }
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(topic.getId());
        Set<Long> chapterIds = new LinkedHashSet<Long>();
        for (CmsTopicChapter chapter : nullToEmpty(cmsTopicChapterMapper.selectCmsTopicChapterList(probe))) {
            if (chapter.getId() != null) chapterIds.add(chapter.getId());
        }
        // R14:sys.* 条件引用的 nodeId 与 dispositionNodeId 都要对得上本主题节点;
        // 处置节点还必须是分支玩法(否则 sys.outcome.{nodeId} 永远不产生)。
        CmsTopicNode nodeProbe = new CmsTopicNode();
        nodeProbe.setTopicId(topic.getId());
        nodeProbe.setStatus(1L);
        List<CmsTopicNode> nodes = nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(nodeProbe));
        Set<Long> nodeIds = new LinkedHashSet<Long>();
        Set<Long> branchNodeIds = new LinkedHashSet<Long>();
        Set<Long> templateIds = new LinkedHashSet<Long>();
        for (CmsTopicNode node : nodes) {
            if (node == null || node.getId() == null) continue;
            nodeIds.add(node.getId());
            if (node.getTemplateId() != null && node.getTemplateId() > 0L) templateIds.add(node.getTemplateId());
        }
        Set<Long> branchNodeRefs = branchNodeIds;
        Set<Long> outcomeNodeIds = new LinkedHashSet<Long>();
        Set<Long> outcomeNodeRefs = outcomeNodeIds;
        if (cmsMemberTemplateMapper == null) {
            branchNodeRefs = null;      // 手工 new 的单测:该侧不核对
            outcomeNodeRefs = null;
        } else if (!templateIds.isEmpty()) {
            // 空模板集合不能进 in 查询(生成 where id in 空,语法错)
            Map<Long, Boolean> branchByTemplate = new LinkedHashMap<Long, Boolean>();
            Map<Long, Boolean> outcomeByTemplate = new LinkedHashMap<Long, Boolean>();
            for (CmsMemberTemplate template : nullToEmpty(
                    cmsMemberTemplateMapper.selectCmsMemberTemplateByIds(new ArrayList<Long>(templateIds)))) {
                if (template != null && template.getId() != null) {
                    branchByTemplate.put(template.getId(), blockIsBranch(template.getAdvancedConfigJson()));
                    outcomeByTemplate.put(template.getId(), blockWritesOutcome(template.getAdvancedConfigJson()));
                }
            }
            for (CmsTopicNode node : nodes) {
                if (node == null || node.getId() == null) continue;
                if (Boolean.TRUE.equals(branchByTemplate.get(node.getTemplateId()))) branchNodeIds.add(node.getId());
                if (Boolean.TRUE.equals(outcomeByTemplate.get(node.getTemplateId()))) outcomeNodeIds.add(node.getId());
            }
        }
        try {
            journeyStoryValidator.validate(raw, com.chengyinhub.business.service.support.JourneyStoryValidator.Context.of(
                    chapterIds, journeyStoryAvailableNpcIds(topic.getId()), nodeIds, branchNodeRefs, outcomeNodeRefs));
        } catch (RuntimeException e) {
            throw new ServiceException("主题的剧情配置引用无效,主题发布失败:" + e.getMessage());
        }
    }

    private static boolean blockWritesOutcome(String advancedConfigJson) {
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

    private static boolean blockIsBranch(String advancedConfigJson) {
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
