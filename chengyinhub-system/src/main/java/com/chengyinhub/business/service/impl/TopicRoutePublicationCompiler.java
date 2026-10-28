package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.support.FrozenAttributeDefinitions;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.support.ChapterNarrativeBlocks;
import com.chengyinhub.business.domain.JourneyStory;
import com.chengyinhub.business.util.ChapterBlockReadSupport;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.JourneyInitialState;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.chengyinhub.business.service.support.ThemeRuntimeConfig;
import com.chengyinhub.business.service.support.GameplaySurvivalEngine;
import com.chengyinhub.business.service.support.GameplayTemplateRules;
import com.chengyinhub.business.service.support.GameplaySupportMatrix;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Publication compilation and immutable journey snapshots; no sessions, receipts, CAS or runtime callbacks.
 * Uses the caller transaction and typed existing collaborators. Not a Spring service or a second rule engine.
 */
final class TopicRoutePublicationCompiler {

    private final CmsTopicNodeMapper nodeMapper;
    private final CmsMemberTemplateMapper templateMapper;
    private final CmsTopicChapterMapper cmsTopicChapterMapper;
    private final ThemeStateKeyMapper themeStateKeyMapper;
    private final Clock clock;
    // Reuse the caller's existing serializer/validator; snapshot bytes and exception contracts stay the same.
    private final ObjectMapper JSON;
    private final TopicRouteConfigValidator validator;
    /** One publication/authoring transaction uses one current, locked child projection.
     * Preview calls outside a writable transaction retain their existing read-only behavior. */
    private CurrentContent currentContent;
    private boolean derivingDeclarations;
    private boolean finalDeclarations;
    private final com.chengyinhub.business.mapper.CmsTopicMapper topicMapper;
    private static final class CurrentContent {
        final Long topicId;
        final CmsTopic topic;
        final List<CmsTopicChapter> chapters;
        final List<CmsTopicNode> nodes;
        final Map<Long, CmsMemberTemplate> templates;
        final List<ThemeStateKey> declarations;
        CurrentContent(CmsTopic topic, List<CmsTopicChapter> chapters, List<CmsTopicNode> nodes,
                Map<Long, CmsMemberTemplate> templates, List<ThemeStateKey> declarations) {
            this.topic = topic;
            this.topicId = topic.getId();
            this.chapters = chapters;
            this.nodes = nodes;
            this.templates = templates;
            this.declarations = new ArrayList<>(declarations);
        }
    }

    private <T> T withCurrentContent(Long topicId, java.util.function.Supplier<T> operation) {
        if (currentContent != null) {
            if (!currentContent.topicId.equals(topicId)) throw new IllegalStateException("发布内容主题不一致");
            return operation.get();
        }
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            return operation.get();
        if (topicId == null || cmsTopicChapterMapper == null || nodeMapper == null)
            throw new IllegalStateException("发布内容当前读依赖缺失");
        // Authoring also calls compileTemplateRules directly. Always acquire topic first,
        // rather than relying on one publication caller already holding the parent lock.
        if (topicMapper == null) throw new IllegalStateException("发布主题当前读依赖缺失");
        CmsTopic currentTopic = topicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (currentTopic == null || !topicId.equals(currentTopic.getId()))
            throw new IllegalStateException("发布主题当前版本缺失");
        List<CmsTopicChapter> chapters = cmsTopicChapterMapper.selectByTopicForUpdate(topicId);
        List<CmsTopicNode> nodes = nodeMapper.selectByTopicForUpdate(topicId);
        if (chapters == null || nodes == null) throw new IllegalStateException("发布内容当前读失败");
        Set<Long> referencedIds = new java.util.TreeSet<>();
        for (CmsTopicNode node : nodes) {
            if (node.getTemplateId() != null && node.getTemplateId() > 0) referencedIds.add(node.getTemplateId());
        }
        List<Long> ids = new ArrayList<>(referencedIds);
        Map<Long, CmsMemberTemplate> templates = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            if (templateMapper == null) throw new IllegalStateException("发布玩法当前读依赖缺失");
            List<CmsMemberTemplate> rows = templateMapper.selectCmsMemberTemplateByIdsForUpdate(ids);
            if (rows == null || rows.size() != ids.size()) throw new IllegalStateException("发布玩法当前版本缺失");
            for (CmsMemberTemplate row : rows) {
                if (row == null || !referencedIds.contains(row.getId()) || templates.put(row.getId(), row) != null)
                    throw new IllegalStateException("发布玩法当前身份不一致");
            }
        }
        if (themeStateKeyMapper == null
                || !topicId.equals(themeStateKeyMapper.lockTopicForStateKeyWrite(topicId)))
            throw new IllegalStateException("主题属性声明当前读依赖缺失");
        List<ThemeStateKey> declarations = themeStateKeyMapper.selectByTopicIdForUpdate(topicId);
        com.chengyinhub.business.service.support.ThemeStateKeyDeclarationSnapshot.capture(topicId, declarations);
        currentContent = new CurrentContent(currentTopic, new ArrayList<>(chapters), new ArrayList<>(nodes), templates, declarations);
        try {
            return operation.get();
        } finally {
            currentContent = null;
        }
    }


    TopicRoutePublicationCompiler(CmsTopicNodeMapper nodeMapper, CmsMemberTemplateMapper templateMapper,
            CmsTopicChapterMapper cmsTopicChapterMapper, ThemeStateKeyMapper themeStateKeyMapper,
            Clock clock, ObjectMapper json, TopicRouteConfigValidator validator,
            com.chengyinhub.business.mapper.CmsTopicMapper topicMapper) {
        this.topicMapper=topicMapper;
        this.nodeMapper=nodeMapper; this.templateMapper=templateMapper;
        this.cmsTopicChapterMapper=cmsTopicChapterMapper; this.themeStateKeyMapper=themeStateKeyMapper;
        this.clock=clock; this.JSON=json; this.validator=validator;
    }

    JourneyTemplateProbe probe(List<CmsTopicNode> liveNodes) {
        return new JourneyTemplateProbe(liveNodes);
    }

    /**
     * 一次请求内的节点模板配置备忘:无本局时的建局判定与建局冻结共用同一次模板查询。
     * 只随调用链传递、不落字段 —— 不做跨请求缓存,创作者改模板对下一次请求立即生效。
     */
    final class JourneyTemplateProbe {
        final List<CmsTopicNode> liveNodes;
        private Map<Long, CmsMemberTemplate> templates;
        private Map<Long, String> configByTemplate;

        private JourneyTemplateProbe(List<CmsTopicNode> liveNodes) {
            this.liveNodes = liveNodes;
        }

        Map<Long, String> configs() {
            if (configByTemplate == null) {
                configByTemplate = new LinkedHashMap<Long, String>();
                for (Map.Entry<Long, CmsMemberTemplate> row : templatesById().entrySet()) {
                    configByTemplate.put(row.getKey(), row.getValue().getAdvancedConfigJson());
                }
            }
            return configByTemplate;
        }

        /** 同一次模板查询的实体表:建局冻结答案判定字段与块配置共用,别对同一请求再查一次。 */
        Map<Long, CmsMemberTemplate> templatesById() {
            if (templates == null) templates = templateEntities(liveNodes);
            return templates;
        }
    }


    /** 主题下节点绑定模板的 advancedConfigJson(按模板 id);templateMapper 缺失/无绑定返回空表。 */
    private Map<Long, String> templateConfigs(List<CmsTopicNode> liveNodes) {
        Map<Long, String> configByTemplate = new LinkedHashMap<Long, String>();
        for (Map.Entry<Long, CmsMemberTemplate> row : templateEntities(liveNodes).entrySet()) {
            configByTemplate.put(row.getKey(), row.getValue().getAdvancedConfigJson());
        }
        return configByTemplate;
    }

    /** 节点绑定模板的实体(按模板 id);templateMapper 缺失 / 无绑定返回空表。 */
    private Map<Long, CmsMemberTemplate> templateEntities(List<CmsTopicNode> liveNodes) {
        if (currentContent != null) {
            Map<Long, CmsMemberTemplate> selected = new LinkedHashMap<>();
            for (CmsTopicNode node : liveNodes) {
                if (currentContent.templates.containsKey(node.getTemplateId()))
                    selected.put(node.getTemplateId(), currentContent.templates.get(node.getTemplateId()));
            }
            return selected;
        }
        Map<Long, CmsMemberTemplate> byId = new LinkedHashMap<Long, CmsMemberTemplate>();
        if (templateMapper == null) return byId;
        Set<Long> templateIds = new LinkedHashSet<Long>();
        for (CmsTopicNode node : liveNodes) {
            if (node.getTemplateId() != null && node.getTemplateId() > 0L) templateIds.add(node.getTemplateId());
        }
        if (templateIds.isEmpty()) return byId;
        List<CmsMemberTemplate> templates =
                templateMapper.selectCmsMemberTemplateByIds(new ArrayList<Long>(templateIds));
        if (templates == null) return byId;
        for (CmsMemberTemplate template : templates) {
            if (template != null && template.getId() != null) byId.put(template.getId(), template);
        }
        return byId;
    }

    public void deriveTemplateCluesForPublication(CmsTopic topic) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("正式发布的声明派生必须加入主题写事务");
        withCurrentContent(topic.getId(), () -> {
            derivingDeclarations = true;
            try {
                String compiled = compileCurrentTemplateRules(currentContent.topic);
                if (compiled != null) registerTemplateClues(validator.readTree(compiled), currentContent.topic);
            } finally { derivingDeclarations = false; }
            return null;
        });
    }

    public String compileTemplateRules(CmsTopic topic) {
        return withCurrentContent(topic.getId(), () -> compileCurrentTemplateRules(currentContent == null ? topic : currentContent.topic));
    }

    private String compileCurrentTemplateRules(CmsTopic topic) {
        CmsTopicNode query = new CmsTopicNode(); query.setTopicId(topic.getId());
        List<CmsTopicNode> actual = currentContent == null ? nodeMapper.selectCmsTopicNodeList(query) : currentContent.nodes;
        if (actual == null) actual = Collections.emptyList();
        actual = new ArrayList<>(actual);
        actual.sort(Comparator.comparing(CmsTopicNode::getSortId, Comparator.nullsLast(Long::compareTo)).thenComparing(CmsTopicNode::getId, Comparator.nullsLast(Long::compareTo)));
        JourneyTemplateProbe probe = new JourneyTemplateProbe(actual);
        ObjectNode configs = JSON.createObjectNode(); boolean configured = false;
        for (CmsTopicNode node : actual) {
            JsonNode config = readBlockConfig(probe.configs().get(node.getTemplateId()));
            configs.set(String.valueOf(node.getId()), config == null ? JSON.createObjectNode() : config);
            configured |= hasTemplateInteraction(config);
        }
        ObjectNode chapterDefinition = null;
        String authorJson = topic.getThemeRuntimeConfigJson();
        JsonNode authored = authorJson==null || authorJson.trim().isEmpty()?null:validator.readTree(authorJson);
        boolean chapterRequirements = hasChapterRequirements(authored);
        if (chapterRequirements) {
            try { chapterDefinition = ThemeRuntimeConfig.parseDraft(authorJson); }
            catch (IllegalArgumentException invalid) {
                List<CmsTopicChapter> chapters = runtimeChapters(topic.getId());
                StringBuilder context = new StringBuilder(invalid.getMessage());
                for (JsonNode chapter : authored.path("chapters")) if (chapter.has("completion") || chapter.has("exclusiveGroups") || chapter.has("extraNodes")) {
                    int index = chapter.path("index").asInt(-1); CmsTopicChapter real = index>=0 && index<chapters.size()?chapters.get(index):null;
                    context.append("；章节 ").append(real==null?"index="+index:real.getName()+"("+real.getId()+", index="+index+")").append(" 节点 ");
                    for (CmsTopicNode node : actual) if (real!=null && real.getId().equals(node.getChapterId())) context.append(node.getId()).append(' ');
                    context.append(chapter.path("exclusiveGroups")).append(' ').append(chapter.path("extraNodes"));
                }
                throw new IllegalArgumentException(context.toString(),invalid);
            }
        }
        if(!configured && topic.getThemeRuntimeConfigJson()!=null && (topic.getThemeRuntimeConfigJson().contains("interactionRules") || topic.getThemeRuntimeConfigJson().contains("interactionTools") || topic.getThemeRuntimeConfigJson().contains("outcomeRules"))) {
            ObjectNode sceneDefinition=ThemeRuntimeConfig.parseDraft(topic.getThemeRuntimeConfigJson());
            for(JsonNode scene:sceneDefinition.path("scenes"))for(JsonNode block:scene.path("nodeConfigs"))configured |= hasTemplateInteraction(block);
        }
        boolean regenerateQuests = authored != null && authored.path("gameplayRuntime").path("acts").size() > 0 && authored.path("gameplayRuntime").path("quests").size() == 0;
        boolean effectSlots=false;for(JsonNode config:configs)effectSlots |= config.path("interactionRules").has("resourceSlots");
        if (!configured && !chapterRequirements && !regenerateQuests && effectSlots) {
            ObjectNode definition=ThemeRuntimeConfig.parseDraft(topic.getThemeRuntimeConfigJson());
            ObjectNode resolved=GameplayTemplateRules.resolveNodeConfigs(definition,configs,nodeTemplateIds(actual));
            com.chengyinhub.business.service.support.TemplateResourceSlots.assertEffectTargets(resolved,JourneyRules.parse(topic.getJourneyRulesJson()),definition);
            if(definition==null)throw new IllegalArgumentException("原效果资源槽需要主题绑定");
            Set<Long> nodeIds=new LinkedHashSet<>();for(CmsTopicNode node:actual)if(node.getId()!=null)nodeIds.add(node.getId());
            assertCompiledReferences(definition,nodeIds,topic);return definition.toString();
        }
        if (!configured && !chapterRequirements && !regenerateQuests && (topic.getThemeRuntimeConfigJson()==null || !topic.getThemeRuntimeConfigJson().contains("tpl_node_") && !topic.getThemeRuntimeConfigJson().contains("templateTools"))) return topic.getThemeRuntimeConfigJson();
        ObjectNode definition = chapterDefinition == null ? ThemeRuntimeConfig.parseDraft(topic.getThemeRuntimeConfigJson()) : chapterDefinition;
        if (definition == null) definition = JSON.createObjectNode().put("schemaVersion",1).put("capacity",0);
        excludeCompiledChallengeQuests(definition);
        List<CmsTopicChapter> chapters = null;
        if (!definition.has("gameplayRuntime") || definition.path("gameplayRuntime").path("quests").size()==0) {
            ObjectNode game = definition.with("gameplayRuntime"); if(!game.has("runtimeScope"))game.put("runtimeScope","PLAYER");
            if(!game.has("survival"))game.putObject("survival").put("zeroHpPolicy","BLOCK_HP_COST").put("actionLimitPolicy","BLOCK_OVERFLOW");
            ArrayNode preservedActs = game.path("acts").isArray() ? ((ArrayNode)game.path("acts")).deepCopy() : JSON.createArrayNode();
            ArrayNode acts = game.putArray("acts"), quests = game.putArray("quests");
            Map<Long,List<CmsTopicNode>> groups = new LinkedHashMap<>();
            chapters = runtimeChapters(topic.getId());
            for(CmsTopicChapter chapter:chapters)groups.put(chapter.getId(),new ArrayList<>());
            for(CmsTopicNode node:actual) {
                if(node.getId()==null)continue;
                if(node.getChapterId()!=null && !groups.containsKey(node.getChapterId())) throw new IllegalArgumentException("模板规则节点未绑定本主题真实章节");
                if (game.path("survival").path("zeroChallenge").path("version").asInt()==2
                        && GameplaySurvivalEngine.challengeNode(definition,node.getId())) continue;
                groups.computeIfAbsent(node.getChapterId(), key->new ArrayList<>()).add(node);
            }
            int actIndex = 0;
            for(Map.Entry<Long,List<CmsTopicNode>> group:groups.entrySet()) {
                if (group.getValue().isEmpty()) continue;
                ObjectNode act = actIndex < preservedActs.size() ? (ObjectNode)preservedActs.get(actIndex++).deepCopy() : null;
                if (act == null) {
                    act = JSON.createObjectNode().put("actKey", group.getKey()==null?"route_nodes":"chapter_"+group.getKey());
                    act.putObject("completionRule").put("type","ALL");
                }
                acts.add(act);
                String actKey = act.path("actKey").asText(); ArrayNode members=act.putArray("questKeys");
                for(CmsTopicNode node:group.getValue()) {
                    String questKey="node_"+node.getId(); members.add(questKey);
                    quests.addObject().put("questKey",questKey).put("actKey",actKey).put("nodeId",node.getId()).put("locationKey","node_"+node.getId()).put("settlementSlot",questKey);
                }
            }
            while (actIndex < preservedActs.size()) acts.add(preservedActs.get(actIndex++).deepCopy());
        }
        if (chapterRequirements) compileChapterRequirements(definition, actual, chapters == null ? runtimeChapters(topic.getId()) : chapters);
        Map<Long,String> actKeys = new LinkedHashMap<>();
        for(JsonNode quest:definition.path("gameplayRuntime").path("quests")) actKeys.put(quest.path("nodeId").asLong(),quest.path("actKey").asText());
        Map<Long,Long> templateIds = nodeTemplateIds(actual);
        resolveTemplateScenes(definition, templateIds, JourneyRules.parse(topic.getJourneyRulesJson()));
        ThemeRuntimeConfig.assertJourneyReferences(definition, JourneyRules.parse(topic.getJourneyRulesJson()));
        // 与冻结时一致：场景节点配置覆盖模板配置后再生成费用与投票适配。
        ObjectNode templateConfigs = configs.deepCopy();
        ThemeRuntimeConfig.freeze(definition,0,clock.millis()).path("nodeConfigs").fields()
                .forEachRemaining(row -> configs.set(row.getKey(),row.getValue().deepCopy()));
        for (CmsTopicNode node : actual) {
            CmsMemberTemplate template = probe.templatesById().get(node.getTemplateId());
            int method = template == null || template.getValidationMethod() == null ? -1 : template.getValidationMethod().intValue();
            GameplaySupportMatrix.validateNode(definition,node.getId(),node.getName(),configs.path(String.valueOf(node.getId())),method,true);
            for (JsonNode scene : definition.path("scenes")) if (scene.path("nodeConfigs").has(String.valueOf(node.getId())))
                GameplaySupportMatrix.validateNode(definition,node.getId(),node.getName(),scene.path("nodeConfigs").path(String.valueOf(node.getId())),method,true);
        }
        GameplaySupportMatrix.validateChallenge(definition,configs);
        for (JsonNode scene : definition.path("scenes")) {
            ObjectNode sceneConfigs = templateConfigs.deepCopy();
            scene.path("nodeConfigs").fields().forEachRemaining(row -> sceneConfigs.set(row.getKey(),row.getValue().deepCopy()));
            GameplaySupportMatrix.validateChallenge(definition,sceneConfigs);
        }
        com.chengyinhub.business.service.support.TemplateResourceSlots.assertEffectTargets(GameplayTemplateRules.resolveNodeConfigs(definition,configs,templateIds),JourneyRules.parse(topic.getJourneyRulesJson()),definition);
        JsonNode story = topic.getJourneyStoryJson()==null || topic.getJourneyStoryJson().trim().isEmpty() ? JSON.createObjectNode() : validator.readTree(topic.getJourneyStoryJson());
        registerTemplateClues(configs, topic);
        ObjectNode compiled=GameplayTemplateRules.merge(definition,configs,actKeys,templateIds,story,finalChapter(topic,chapters == null ? runtimeChapters(topic.getId()) : chapters));
        assertStoryEndingChapters(compiled,runtimeChapters(topic.getId()));
        // 保存和发布会再次编译草稿；作者绑定只在冻结边界移除。
        if (definition.has("templateBindings")) compiled.set("templateBindings", definition.get("templateBindings").deepCopy());
        if (definition.has("scenes")) compiled.set("scenes",definition.get("scenes").deepCopy());
        Set<Long> nodeIds=new LinkedHashSet<>();for(CmsTopicNode node:actual)if(node.getId()!=null)nodeIds.add(node.getId());
        assertCompiledReferences(compiled,nodeIds,topic);return compiled.toString();
    }

    private void excludeCompiledChallengeQuests(ObjectNode definition) {
        JsonNode game = definition.path("gameplayRuntime");
        if (game.path("survival").path("zeroChallenge").path("version").asInt()!=2) return;
        Set<String> removed = new LinkedHashSet<>();
        Iterator<JsonNode> quests = game.path("quests").elements();
        while (quests.hasNext()) {
            JsonNode quest = quests.next(); String key = quest.path("questKey").asText();
            // 只转换编译器的默认身份，带作者结算字段的任务继续由发布闸拒绝。
            if (quest.size()==5 && quest.has("actKey") && key.matches("node_[1-9][0-9]*")
                    && key.equals(quest.path("locationKey").asText()) && key.equals(quest.path("settlementSlot").asText())
                    && GameplaySurvivalEngine.challengeNode(definition,quest.path("nodeId").asLong())) {
                removed.add(key); quests.remove();
            }
        }
        if (removed.isEmpty()) return;
        Iterator<JsonNode> acts = game.path("acts").elements();
        while (acts.hasNext()) {
            JsonNode act = acts.next(); boolean changed = false;
            Iterator<JsonNode> members = act.path("questKeys").elements();
            while (members.hasNext()) if (removed.contains(members.next().asText())) { members.remove(); changed = true; }
            if (changed && act.path("questKeys").isEmpty()) acts.remove();
        }
    }

    private boolean hasChapterRequirements(JsonNode definition) {
        if (definition == null) return false;
        for (JsonNode chapter : definition.path("chapters")) if (chapter.has("completion") || chapter.has("exclusiveGroups") || chapter.has("extraNodes")) return true;
        return false;
    }

    /** 最终章：按章节顺序最后一个非结局章（结局章不在路线里）。 */
    private JsonNode finalChapter(CmsTopic topic, List<CmsTopicChapter> chapters) {
        Set<Long> endingIds = JourneyStory.endingChapterIds(topic.getJourneyStoryJson());
        CmsTopicChapter last = null;
        for (CmsTopicChapter chapter : chapters) if (chapter.getId() != null && !endingIds.contains(chapter.getId())) last = chapter;
        return last == null ? null : JSON.createObjectNode().put("id", last.getId()).put("name", last.getName() == null ? "" : last.getName());
    }

    private List<CmsTopicChapter> runtimeChapters(Long topicId) {
        CmsTopicChapter query = new CmsTopicChapter(); query.setTopicId(topicId);
        List<CmsTopicChapter> rows = currentContent != null && currentContent.topicId.equals(topicId)
                ? currentContent.chapters : cmsTopicChapterMapper == null ? null : cmsTopicChapterMapper.selectCmsTopicChapterList(query);
        List<CmsTopicChapter> chapters = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
        chapters.sort(Comparator.comparing(CmsTopicChapter::getSortId,Comparator.nullsLast(Long::compareTo)).thenComparing(CmsTopicChapter::getId,Comparator.nullsLast(Long::compareTo)));
        return chapters;
    }

    private void compileChapterRequirements(ObjectNode definition, List<CmsTopicNode> actual, List<CmsTopicChapter> chapters) {
        Map<Long,CmsTopicNode> nodes = new LinkedHashMap<>();
        for (CmsTopicNode node : actual) if (node.getId()!=null) nodes.put(node.getId(),node);
        for (JsonNode settings : definition.path("chapters")) {
            if (!settings.has("completion") && !settings.has("exclusiveGroups") && !settings.has("extraNodes")) continue;
            int index = settings.path("index").asInt();
            if (index<0 || index>=chapters.size()) throw new IllegalArgumentException("章节 index="+index+" 节点 "+settings.path("extraNodes")+"：章节不存在");
            CmsTopicChapter chapter = chapters.get(index);
            Map<Long,ObjectNode> quests = new LinkedHashMap<>();
            Set<String> actKeys = new LinkedHashSet<>();
            for (JsonNode quest : definition.path("gameplayRuntime").path("quests")) {
                CmsTopicNode node = nodes.get(quest.path("nodeId").asLong());
                if (node!=null && chapter.getId().equals(node.getChapterId())) { quests.put(node.getId(),(ObjectNode)quest); actKeys.add(quest.path("actKey").asText()); }
            }
            String label = "章节 "+chapter.getName()+"("+chapter.getId()+", index="+index+")";
            Map<Long,String> exclusive = new LinkedHashMap<>(), extra = new LinkedHashMap<>();
            int groupIndex = 0;
            for (JsonNode group : settings.path("exclusiveGroups")) {
                String groupKey = "ch"+chapter.getId()+"_g"+(++groupIndex);
                for (JsonNode member : group) {
                    long nodeId = member.asLong();
                    if (!quests.containsKey(nodeId)) throw new IllegalArgumentException(label+" 节点 "+nodeId+"：节点不属于本章运行任务");
                    if (exclusive.put(nodeId,groupKey)!=null) throw new IllegalArgumentException(label+" 节点 "+nodeId+"：节点重复属于互斥组");
                }
            }
            for (JsonNode row : settings.path("extraNodes")) {
                long nodeId = row.path("nodeId").asLong();
                if (!quests.containsKey(nodeId)) throw new IllegalArgumentException(label+" 节点 "+nodeId+"：节点不属于本章运行任务");
                if (exclusive.containsKey(nodeId)) throw new IllegalArgumentException(label+" 节点 "+nodeId+"：互斥节点不能同时作为额外节点");
                if (extra.put(nodeId,row.path("slot").asText())!=null) throw new IllegalArgumentException(label+" 节点 "+nodeId+"：额外节点重复");
            }
            ObjectNode act = null;
            if (actKeys.size()==1) for (JsonNode row : definition.path("gameplayRuntime").path("acts")) if (actKeys.contains(row.path("actKey").asText())) act=(ObjectNode)row;
            if (act==null || act.path("questKeys").size()!=quests.size()) throw new IllegalArgumentException(label+" 节点 "+quests.keySet()+"：章节须完整绑定同一个真实幕");
            JsonNode completion = settings.path("completion");
            int counted = quests.size()-extra.size();
            int capacity = counted-exclusive.size()+groupIndex;
            if(counted==0 && !"ANY_N".equals(completion.path("type").asText()))throw new IllegalArgumentException(label+" 节点 "+quests.keySet()+"：本章没有计数任务，额外节点不能占满本章全部站点");
            if ("ANY_N".equals(completion.path("type").asText()) && (completion.path("quantity").asInt()<1 || completion.path("quantity").asInt()>capacity)) throw new IllegalArgumentException(label+" 节点 "+quests.keySet()+"：任选数量须为1.."+capacity+"，额外节点不计数，互斥组最多选一个");
            if (completion.isObject()) {
                ObjectNode rule = completion.deepCopy(); rule.remove("overflowSlot");
                act.set("completionRule",rule);
            }
            else act.putObject("completionRule").put("type","ALL");
            for (Map.Entry<Long,ObjectNode> quest : quests.entrySet()) {
                quest.getValue().remove(Arrays.asList("exclusiveGroup","extraSlot","extraOnly"));
                if (exclusive.containsKey(quest.getKey())) quest.getValue().put("exclusiveGroup",exclusive.get(quest.getKey()));
                if (extra.containsKey(quest.getKey())) quest.getValue().put("extraSlot",extra.get(quest.getKey())).put("extraOnly",true);
                else if (completion.has("overflowSlot")) quest.getValue().put("extraSlot",completion.path("overflowSlot").asText());
            }
        }
    }

    private Map<Long,Long> nodeTemplateIds(List<CmsTopicNode> nodes) {
        Map<Long,Long> templateIds = new LinkedHashMap<>();
        for (CmsTopicNode node : nodes) if (node.getId()!=null && node.getTemplateId()!=null && node.getTemplateId()>0) templateIds.put(node.getId(),node.getTemplateId());
        return templateIds;
    }

    private void resolveTemplateScenes(ObjectNode definition, Map<Long,Long> templateIds, JourneyRules rules) {
        for (JsonNode scene : definition.path("scenes")) if (scene.has("nodeConfigs")) {
            ObjectNode resolved = GameplayTemplateRules.resolveNodeConfigs(definition,scene.get("nodeConfigs"),templateIds);
            com.chengyinhub.business.service.support.TemplateResourceSlots.assertEffectTargets(resolved,rules,definition);
        }
    }

    private void assertStoryEndingChapters(JsonNode config,List<CmsTopicChapter> chapters) {
        Set<Long> ids=new LinkedHashSet<>();for(CmsTopicChapter chapter:chapters)ids.add(chapter.getId());
        for(JsonNode plan:config.path("gameplayRuntime").path("plans"))if(plan.has("storyEndingId") && !ids.contains(plan.path("endingChapterId").asLong()))throw new IllegalArgumentException("节点 "+plan.path("operationNodeId").asText()+" 方案 "+plan.path("endingKey").asText()+" 结局章不属于本主题");
    }

    private boolean hasTemplateInteraction(JsonNode config) { return config!=null && (GameplayTemplateRules.hasSettlementRules(config) || config.has("interactionTools") || config.has("outcomeRules")); }

    /** 模板「线索」工具按线索名称生成 clue.h_ 标识（同名同键），保存/发布时自动登记为有/无标记，名称取模板里写的线索名。 */
    private List<ThemeStateKey> declarationRows(Long topicId) {
        if (currentContent != null && currentContent.topicId.equals(topicId)) return currentContent.declarations;
        return themeStateKeyMapper == null ? null : themeStateKeyMapper.selectByTopicId(topicId);
    }

    private void registerTemplateClues(JsonNode config, CmsTopic topic) {
        if (themeStateKeyMapper == null || topic.getId() == null) return;
        Map<String, String> labels = new LinkedHashMap<>(); collectTemplateClues(config, labels);
        if (labels.isEmpty()) return;
        Set<String> existing = new LinkedHashSet<>();
        List<ThemeStateKey> rows = declarationRows(topic.getId());
        if (rows == null) throw new IllegalStateException("主题属性声明读取失败");
        for (ThemeStateKey row : rows) existing.add(row.getStateKey());
        boolean changed = false;
        for (Map.Entry<String, String> clue : labels.entrySet()) if (!existing.contains(clue.getKey())) {
            if (finalDeclarations) throw new IllegalStateException("最终声明投影缺少模板线索，拒绝发布");
            if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                throw new IllegalStateException("自动线索声明必须加入主题写事务");
            if (!topic.getId().equals(themeStateKeyMapper.lockTopicForStateKeyWrite(topic.getId())))
                throw new IllegalStateException("主题属性声明写锁缺失");
            ThemeStateKey original = themeStateKeyMapper.selectByTopicIdAndStateKeyForUpdate(topic.getId(), clue.getKey());
            if (original == null) {
                Date now = new Date();
                ThemeStateKey row = new ThemeStateKey(); row.setTopicId(topic.getId()); row.setStateKey(clue.getKey());
                row.setKeyType(ThemeStateKey.TYPE_FLAG); row.setScope(ThemeStateKey.SCOPE_RUN);
                row.setLabel(clue.getValue()); row.setInitialValue(0); row.setCreateTime(now); row.setUpdateTime(now);
                if (themeStateKeyMapper.upsertThemeStateKey(row) <= 0) throw new IllegalStateException("自动线索声明保存失败");
                original = themeStateKeyMapper.selectByTopicIdAndStateKeyForUpdate(topic.getId(), clue.getKey());
                if (original == null || !topic.getId().equals(original.getTopicId())
                        || !clue.getKey().equals(original.getStateKey()) || !ThemeStateKey.TYPE_FLAG.equals(original.getKeyType())
                        || !ThemeStateKey.SCOPE_RUN.equals(original.getScope()) || !Integer.valueOf(0).equals(original.getInitialValue())
                        || !java.util.Objects.equals(clue.getValue(), original.getLabel()))
                    throw new IllegalStateException("自动线索声明读回不一致");
            }
            existing.add(clue.getKey()); changed = true;
        }
        if (changed && currentContent != null) {
            List<ThemeStateKey> fresh = themeStateKeyMapper.selectByTopicIdForUpdate(topic.getId());
            com.chengyinhub.business.service.support.ThemeStateKeyDeclarationSnapshot.capture(topic.getId(), fresh);
            currentContent.declarations.clear(); currentContent.declarations.addAll(fresh);
        }
    }

    private static void collectTemplateClues(JsonNode n, Map<String, String> labels) {
        com.chengyinhub.business.util.StateKeyRefs.declaredTemplateClues(n).forEach(labels::put);
    }

    private void assertCompiledReferences(ObjectNode config,Set<Long> nodeIds,CmsTopic topic) {
        registerTemplateClues(config, topic);
        if (derivingDeclarations) return; // Final E/Compiler validation runs only after both existing derivations.
        Set<String> stateKeys=new LinkedHashSet<>();
        List<ThemeStateKey> keys=declarationRows(topic.getId());
        if(keys!=null)for(ThemeStateKey key:keys)stateKeys.add(key.getStateKey());
        ThemeRuntimeConfig.assertReferences(config,nodeIds,stateKeys,JourneyRules.parse(topic.getJourneyRulesJson()));
    }

    public String freezePublication(CmsTopic topic, String routeSnapshot) {
        return withCurrentContent(topic.getId(), () -> freezeCurrentPublication(currentContent == null ? topic : currentContent.topic, routeSnapshot));
    }

    public String freezePublication(CmsTopic topic, String routeSnapshot, List<ThemeStateKey> declarations) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("正式发布必须加入主题写事务");
        List<ThemeStateKey> prepared = com.chengyinhub.business.service.support.ThemeStateKeyDeclarationSnapshot.copy(topic.getId(), declarations);
        return withCurrentContent(topic.getId(), () -> {
            if (!com.chengyinhub.business.service.support.ThemeStateKeyDeclarationSnapshot.capture(topic.getId(), prepared).equals(
                    com.chengyinhub.business.service.support.ThemeStateKeyDeclarationSnapshot.capture(topic.getId(), currentContent.declarations)))
                throw new IllegalStateException("最终属性声明投影已变化");
            currentContent.declarations.clear(); currentContent.declarations.addAll(prepared);
            finalDeclarations = true;
            try { return freezeCurrentPublication(currentContent.topic, routeSnapshot); }
            finally { finalDeclarations = false; }
        });
    }

    private String freezeCurrentPublication(CmsTopic topic, String routeSnapshot) {
        List<CmsTopicNode> liveNodes = nodes(topic.getId());
        if (liveNodes.isEmpty()) throw new IllegalArgumentException("主题没有可运行节点");
        JourneyTemplateProbe publicationProbe=new JourneyTemplateProbe(liveNodes);
        com.chengyinhub.business.service.support.AdvancedGameConfigValidator authorValidator=new com.chengyinhub.business.service.support.AdvancedGameConfigValidator();
        for(Map.Entry<Long,String> entry:publicationProbe.configs().entrySet())if(entry.getValue()!=null && validator.readTree(entry.getValue()).path("outcomeRules").path("version").asInt()==1)entry.setValue(authorValidator.validateAndNormalize(entry.getValue()));
        String compiled = compileTemplateRules(topic);
        ObjectNode authoredDefinition=ThemeRuntimeConfig.parseDraft(topic.getThemeRuntimeConfigJson());
        if(authoredDefinition!=null)for(JsonNode scene:authoredDefinition.path("scenes"))for(JsonNode block:scene.path("nodeConfigs"))if(block.path("outcomeRules").path("version").asInt()==1)authorValidator.validateAndNormalize(block.toString());
        CmsTopic publish = new CmsTopic();
        org.springframework.beans.BeanUtils.copyProperties(topic, publish);
        publish.setThemeRuntimeConfigJson(compiled);
        publish.setPublishedThemeRuntimeConfigJson(compiled);
        ObjectNode frozen = (ObjectNode) validator.readTree(freezeJourney(
                routeSnapshot == null ? linearSnapshot(liveNodes) : routeSnapshot, publish, liveNodes,publicationProbe));
        freezeContentSnapshots(frozen, topic, liveNodes, publicationProbe.templatesById());
        frozen.put("runtimeSnapshotVersion", 1);
        frozen.put("r5AuthorValidationVersion",1);
        frozen.put("routeMode", topic.getRouteMode() == null ? "LINEAR" : topic.getRouteMode());
        ObjectNode definition = ThemeRuntimeConfig.parse(compiled);
        if (definition != null) {
            for(JsonNode scene:definition.path("scenes"))if(scene.has("nodeConfigs"))
                ((ObjectNode)scene).set("nodeConfigs",GameplayTemplateRules.resolveNodeConfigs(definition,scene.path("nodeConfigs"),nodeTemplateIds(liveNodes)));
            definition.remove("templateBindings");
            if(frozen.path("themeConfig").has("nodeConfigs"))definition.set("nodeConfigs",frozen.path("themeConfig").path("nodeConfigs").deepCopy());
            frozen.set("themeDefinition", definition);
        }
        ArrayNode stations = frozen.putArray("routeStations");
        for (CmsTopicNode node : liveNodes) {
            ObjectNode station = stations.addObject(); station.put("nodeId", node.getId());
            station.put("nodeName", node.getName());
            if (node.getChapterId() != null) station.put("chapterId", node.getChapterId());
            if (node.getMerchantMemberId() != null) station.put("merchantMemberId", node.getMerchantMemberId());
            station.put("scanReady",node.getMerchantMemberId()!=null && node.getMerchantMemberId()>0 || node.getCheckinCode()!=null && !node.getCheckinCode().trim().isEmpty());
            if(node.getLongitude()!=null)station.put("longitude",node.getLongitude());if(node.getLatitude()!=null)station.put("latitude",node.getLatitude());
        }
        Set<Long> nodeIds=new LinkedHashSet<>();for(CmsTopicNode node:liveNodes)nodeIds.add(node.getId());
        if(frozen.path("themeConfig").isObject())assertCompiledReferences((ObjectNode)frozen.path("themeConfig"),nodeIds,topic);
        List<ThemeStateKey> declarations = declarationRows(topic.getId());
        if (declarations != null) {
            JourneyInitialState.freezeKeys(frozen, declarations, journeyRules(topic), topic.getId());
        }
        validateGameplayRuntime(frozen);
        return write(frozen);
    }

    public String freezePublishedJourney(CmsTopic topic) {
        if (topic == null || topic.getPublishedConfigVersion() == null || topic.getPublishedConfigVersion() <= 0) {
            throw new IllegalStateException("主题尚未发布");
        }
        JsonNode published = topic.getPublishedRouteGraphJson() == null ? null
                : validator.readTree(topic.getPublishedRouteGraphJson());
        if (published != null && published.path("runtimeSnapshotVersion").asInt() == 1) {
            JsonNode frozen = validator.readTree(freezeJourney(published.toString(), topic, java.util.Collections.emptyList()));
            validateGameplayRuntime(frozen);
            return frozen.toString();
        }
        List<CmsTopicNode> liveNodes = nodes(topic.getId());
        return freezeJourney(published == null ? linearSnapshot(liveNodes) : published.toString(), topic, liveNodes);
    }

    private void validateGameplayRuntime(JsonNode frozen) {
        JsonNode theme=frozen.path("themeConfig"),game=theme.path("gameplayRuntime");
        if (!game.isObject()) return;
        GameplaySupportMatrix.validateChallenge(theme,frozen.path("nodeConfigs"));
        for (JsonNode station : frozen.path("routeStations")) {
            long nodeId = station.path("nodeId").asLong();
            GameplaySupportMatrix.validateNode(theme,nodeId,station.path("nodeName").asText(null),
                    frozen.path("nodeConfigs").path(String.valueOf(nodeId)),
                    frozen.path(NodeJourneySnapshot.NODE_ANSWERS_KEY).path(String.valueOf(nodeId)).path("validationMethod").asInt(-1));
        }
        boolean team="TEAM".equals(game.path("runtimeScope").asText());
        boolean tasks=game.path("quests").size()>0;
        if (!team && !tasks && game.path("acts").size()==0 && game.path("survival").size()==0 && game.path("initialGrants").size()==0) return;
        if (!"LINEAR".equals(frozen.path("routeMode").asText())) throw new IllegalArgumentException("通用任务使用幕与任务前置推进，请选择线性路线");
        if ((team && theme.path("replayEnabled").asBoolean()) || theme.path("rescueLimit").asInt()>0 || theme.has("actionSources")) throw new IllegalArgumentException("通用任务不能组合旧自动救援或信号行动计数，队伍局不使用个人重玩");
        for (JsonNode grant:theme.path("grants")) if (!"ENDING".equals(grant.path("source").asText())) throw new IllegalArgumentException("通用收益须配置在任务或章节中，不能启用旧节点信号发放");
        for (JsonNode chapter:theme.path("chapters")) if (chapter.path("eggs").size()>0) throw new IllegalArgumentException("通用局章节彩蛋触发尚未接通");
        if (!tasks) throw new IllegalArgumentException("通用运行规则须配置实际幕和任务");
        if (tasks) {
            JsonNode survival=game.path("survival");
            if (!GameplaySupportMatrix.zeroHpPolicies(true).contains(survival.path("zeroHpPolicy").asText()) || !"BLOCK_OVERFLOW".equals(survival.path("actionLimitPolicy").asText())) throw new IllegalArgumentException("通用任务须显式配置H0策略与BLOCK_OVERFLOW");
        }
        if (!team) for (JsonNode act:game.path("acts")) if (act.path("hostUnlock").asBoolean()) throw new IllegalArgumentException("个人自玩没有工作人员解锁通道");
        Set<Long> questNodes=new LinkedHashSet<>();
        for (JsonNode quest:game.path("quests")) {
            long nodeId=quest.path("nodeId").asLong();
            if(GameplaySurvivalEngine.challengeNode(theme,nodeId))throw new IllegalArgumentException("归零挑战节点不能作为普通任务");
            if (!questNodes.add(nodeId)) throw new IllegalArgumentException("任务须独占节点");
            boolean merchant=false;
            for (JsonNode station:frozen.path("routeStations")) if (station.path("nodeId").asLong()==nodeId && station.path("merchantMemberId").asLong()>0) merchant=true;
            if (!team && !merchant && !hasVerifiedPublicationArrival(frozen,nodeId)) throw new IllegalArgumentException("个人任务须使用真实商户到达或已验证扫码/GPS节点，工作人员点位请建队伍场次");
            if(!team && quest.has("facilityKey"))for(JsonNode facility:game.path("facilities"))if(facility.path("facilityKey").asText().equals(quest.path("facilityKey").asText()) && (!"OWNER".equals(facility.path("scope").asText()) || !"OWNER".equals(facility.path("targetScope").asText()) || facility.has("arrivalLocationKey")))throw new IllegalArgumentException("个人任务只绑定无需工作人员到达核验的OWNER设施");
            validateGameplayNode(frozen,nodeId);
        }
        if(!team)for(JsonNode facility:game.path("facilities"))if(facility.has("arrivalLocationKey"))throw new IllegalArgumentException("个人设施没有工作人员到达核验通道");
        for(JsonNode facility:game.path("facilities"))if(facility.has("operationNodeId")) {
            validateGameplayNode(frozen,facility.path("operationNodeId").asLong());
            if(!team) {boolean merchant=false;for(JsonNode station:frozen.path("routeStations"))if(station.path("nodeId").asLong()==facility.path("operationNodeId").asLong() && station.path("merchantMemberId").asLong()>0)merchant=true;if(!merchant && !hasVerifiedPublicationArrival(frozen,facility.path("operationNodeId").asLong()))throw new IllegalArgumentException("个人设施操作须使用真实商户到达或已验证扫码/GPS节点");}
        }
        for (JsonNode plan:game.path("plans")) if (plan.has("operationNodeId")) validateGameplayNode(frozen,plan.path("operationNodeId").asLong());
        for (JsonNode act:game.path("acts")) if ("ANY_N".equals(act.path("completionRule").path("type").asText())) for (JsonNode chapter:theme.path("chapters")) {
            boolean overlaps=false;for (JsonNode q:game.path("quests")) if (q.path("actKey").asText().equals(act.path("actKey").asText()) && containsNode(chapter.path("binding").path("nodeIds"),q.path("nodeId").asLong())) overlaps=true;
            if (overlaps && !com.chengyinhub.business.service.support.GameplayTaskEngine.chapterMatchesAct(theme,chapter,act)) throw new IllegalArgumentException("任选幕的章节绑定须完整匹配本幕任务节点");
        }
    }

    static String basicCompletionKind(JsonNode frozen,Long nodeId) {
        JsonNode config=frozen.path("nodeConfigs").path(String.valueOf(nodeId));
        int method=frozen.path(NodeJourneySnapshot.NODE_ANSWERS_KEY).path(String.valueOf(nodeId)).path("validationMethod").asInt(-1);
        return GameplaySupportMatrix.basicCompletionKind(config,method);
    }

    private boolean hasVerifiedPublicationArrival(JsonNode frozen, Long nodeId) {
        if (basicCompletionKind(frozen, nodeId) != null) return true;
        if (frozen.path("nodeConfigs").path(String.valueOf(nodeId)).path("outcomeRules").path("version").asInt() != 1) return false;
        int method = frozen.path(NodeJourneySnapshot.NODE_ANSWERS_KEY).path(String.valueOf(nodeId)).path("validationMethod").asInt(-1);
        if (method != 4 && method != 5) return false;
        // 参与方式只证明到达，不替代 R5 玩法的服务端结算。
        validateParticipationNode(frozen, nodeId, method == 4 ? "SCAN" : "GPS");
        return true;
    }

    private void validateGameplayNode(JsonNode frozen,Long nodeId) {
        String kind=basicCompletionKind(frozen,nodeId);if(kind==null){validateObjectiveGroup(frozen.path("nodeConfigs").path(String.valueOf(nodeId)));return;}
        validateParticipationNode(frozen, nodeId, kind);
    }

    private void validateParticipationNode(JsonNode frozen, Long nodeId, String kind) {
        JsonNode station=null;for(JsonNode row:frozen.path("routeStations"))if(row.path("nodeId").asLong()==nodeId)station=row;
        if(station==null)throw new IllegalArgumentException("基础玩法节点不属于本主题");
        if("SCAN".equals(kind) && !station.path("scanReady").asBoolean())throw new IllegalArgumentException("扫码玩法须配置真实节点码或商户动态码入口");
        if("ANSWER".equals(kind)) {JsonNode answer=frozen.path(NodeJourneySnapshot.NODE_ANSWERS_KEY).path(String.valueOf(nodeId));String expected=answer.path(answer.path("validationMethod").asInt()==1?"questionAnswer":"correctAnswer").asText();if(expected.trim().isEmpty())throw new IllegalArgumentException("问答玩法须配置服务端真实答案");}
        if("GPS".equals(kind)) {try {double lng=Double.parseDouble(station.path("longitude").asText()),lat=Double.parseDouble(station.path("latitude").asText());if(!Double.isFinite(lng)||!Double.isFinite(lat)||lng< -180||lng>180||lat< -90||lat>90)throw new NumberFormatException();}catch(NumberFormatException invalid){throw new IllegalArgumentException("GPS玩法须配置有效站点坐标");}}
    }

    private void validateObjectiveGroup(JsonNode config) {
        if(config.has("outcomeRules")){if(!GameplaySupportMatrix.r5CompletionProof(config))throw new IllegalArgumentException("R5 玩法尚无服务端结果桥");return;}
        boolean objective=false;java.util.Iterator<Map.Entry<String,JsonNode>> fields=config.fields();
        while(fields.hasNext()) {
            Map.Entry<String,JsonNode> field=fields.next();if(!field.getValue().path("enabled").asBoolean())continue;
            if(GameplaySupportMatrix.addon(field.getKey()))continue;
            if(!Arrays.asList("qa","sort","match","classify").contains(field.getKey()))throw new IllegalArgumentException("任务只支持问答、排序、连线与分类客观题组");
            if ("qa".equals(field.getKey()) && (!Arrays.asList("TYPE","PICK").contains(field.getValue().path("mode").asText()) || field.getValue().path("maxTries").asInt()>0)) throw new IllegalArgumentException("任务问答须可免费重答且有服务端答案");
            if ("sort".equals(field.getKey()) && field.getValue().path("maxAttempts").asInt()>0) throw new IllegalArgumentException("任务排序须可免费重答");
            objective=true;
        }
        if(!objective)throw new IllegalArgumentException("任务节点需要服务端判定题组");
    }

    String freezeJourney(String snapshot, CmsTopic topic, List<CmsTopicNode> liveNodes) {
        return freezeJourney(snapshot, topic, liveNodes, new JourneyTemplateProbe(liveNodes));
    }

    /**
     * R18:主题各章节 blocks 里的叙事块按站点组装成旧契约形状;章节 mapper 缺失
     * (手工 new 的单测)或章节没有物化 blocks 都按空处理,坏块只跳过不抛 ——
     * 冻结不能因为一段脏数据把整局建不出来。
     */
    private Map<Long, ObjectNode> storyNarrativeByNode(CmsTopic topic) {
        Map<Long, ObjectNode> byNode = new LinkedHashMap<Long, ObjectNode>();
        if (cmsTopicChapterMapper == null || topic == null || topic.getId() == null) return byNode;
        List<CmsTopicChapter> chapters = runtimeChapters(topic.getId());
        for (CmsTopicChapter chapter : chapters) {
            if (chapter == null || chapter.getBlocksJson() == null || chapter.getBlocksJson().trim().isEmpty()) {
                continue;
            }
            byNode.putAll(ChapterNarrativeBlocks.assembleByNode(chapter.getBlocksJson()));
        }
        return byNode;
    }

    /** 建局冻结:模板探测由调用方传入,建局判定 / 块配置 / 判定字段冻结共用同一次模板查询。 */
    String freezeJourney(String snapshot, CmsTopic topic, List<CmsTopicNode> liveNodes,
                                 JourneyTemplateProbe probe) {
        return freezeJourney(snapshot, topic, liveNodes, probe.configs(), probe.templatesById());
    }

    /** 建局冻结:模板配置与实体由调用方传入(同一次查询),块配置与判定字段一起冻结。 */
    String freezeJourney(String snapshot, CmsTopic topic, List<CmsTopicNode> liveNodes,
                                 Map<Long, String> configByTemplate,
                                 Map<Long, CmsMemberTemplate> templatesById) {
        return freezeJourney(snapshot,topic,liveNodes,configByTemplate,templatesById,0);
    }

    String freezeJourney(String snapshot,CmsTopic topic,List<CmsTopicNode> liveNodes,
                                 Map<Long,String> configByTemplate,Map<Long,CmsMemberTemplate> templatesById,int sceneIndex) {
        final JsonNode parsed;
        try {
            parsed = JSON.readTree(snapshot);
        } catch (Exception unusableSnapshot) {
            throw new IllegalStateException("路线快照不可用", unusableSnapshot);
        }
        if (parsed == null || !parsed.isObject()) throw new IllegalStateException("路线快照不可用");
        ObjectNode root = (ObjectNode) parsed;
        FrozenAttributeDefinitions.copyPublishedDefinition(root);
        Map<Long,Long> templateIds = nodeTemplateIds(liveNodes);
        if (root.path("runtimeSnapshotVersion").asInt() == 1) {
            if (root.path("themeDefinition").isObject()) {
                ObjectNode config = ThemeRuntimeConfig.freeze((ObjectNode) root.path("themeDefinition"), sceneIndex, clock.millis());
                for (JsonNode chapter : config.path("chapters")) {
                    for (JsonNode old : root.path("themeConfig").path("chapters")) {
                        if (chapter.path("index").asInt() == old.path("index").asInt() && old.has("binding")) {
                            ((ObjectNode) chapter).set("binding", old.path("binding").deepCopy());
                        }
                    }
                }
                config.set("legacyMedals", root.path("themeConfig").path("legacyMedals").deepCopy());
                root.set("themeConfig", config);
                applySceneNodes(root, config);
                root.set("themeConfig",mergeFrozenTemplateRules(root,config,templateIds,null));
                freezeClueContent(root,liveNodes,templatesById);
            }
            return write(root);
        }
        ObjectNode nodeJourney = root.putObject(NodeJourneySnapshot.NODE_JOURNEY_KEY);
        ObjectNode nodeConfigs = root.putObject("nodeConfigs");
        Map<Long, ObjectNode> storyByNode = storyNarrativeByNode(topic);
        for (CmsTopicNode node : liveNodes) {
            if (node.getId() == null || node.getTemplateId() == null || node.getTemplateId() <= 0L) continue;
            ObjectNode block = nodeJourney.putObject(String.valueOf(node.getId()));
            JsonNode blockConfig = readBlockConfig(configByTemplate.get(node.getTemplateId()));
            nodeConfigs.set(String.valueOf(node.getId()), blockConfig == null ? JSON.createObjectNode() : blockConfig.deepCopy());
            NodeJourneySnapshot.copyMechanicSegments(blockConfig, block);
            ObjectNode story = storyByNode.get(node.getId());
            if (story != null) {
                java.util.Iterator<String> segments = story.fieldNames();
                while (segments.hasNext()) {
                    String segment = segments.next();
                    block.set(segment, story.get(segment));
                }
            }
        }
        try {
            JourneyRules authoredRules = journeyRules(topic);
            root.set(NodeJourneySnapshot.JOURNEY_RULES_KEY, JSON.valueToTree(authoredRules));
            FrozenAttributeDefinitions.copyPublishedDefinition(root);
            JourneyInitialState.freezeKeys(root, root.has("attributeDefinition") ? null
                    : declarationRows(topic.getId()), authoredRules);
        } catch (IllegalArgumentException unusableRules) {
            throw new IllegalStateException("旅程数值规则不可用", unusableRules);
        }
        ObjectNode themeConfig = com.chengyinhub.business.service.support.ThemeRuntimeConfig.parse(
                topic.getPublishedThemeRuntimeConfigJson());
        ObjectNode selectedTheme = themeConfig == null ? null
                : com.chengyinhub.business.service.support.ThemeRuntimeConfig.freeze(themeConfig, sceneIndex, clock.millis());
        String story = selectedTheme != null && selectedTheme.has("journeyStory")
                ? selectedTheme.get("journeyStory").toString() : topic.getJourneyStoryJson();
        root.put(NodeJourneySnapshot.JOURNEY_STORY_KEY, story == null ? "" : story.trim());
        root.put("topicCompleteRule", topic == null ? null : topic.getCompleteRuleJson());
        Long openingId = JourneyStory.openingChapterId(story);
        freezeThoughtChapters(root, topic, liveNodes, openingId, story);
        if (openingId != null) {
            List<CmsTopicChapter> chapters = runtimeChapters(topic.getId());
            CmsTopicChapter opening = null;
            if (chapters != null) for (CmsTopicChapter chapter : chapters) {
                if (openingId.equals(chapter.getId())) { opening = chapter; break; }
            }
            if (opening == null) throw new IllegalStateException("开局流程不可用");
            ObjectNode frozen = root.putObject("openingChapter");
            frozen.put("chapterId", openingId); frozen.put("opening", true);
            frozen.put("name", opening.getName()); frozen.put("idxLabel", "序章");
            frozen.put("imgArr", opening.getImgArr()); frozen.put("audioUrl", opening.getAudioUrl());
            frozen.put("atmospherePreset", opening.getAtmospherePreset());
            frozen.set("blocks", JSON.valueToTree(ChapterBlockReadSupport.resolveFromTopicNodes(opening, liveNodes)));
        }
        freezeNodeAnswers(root.putObject(NodeJourneySnapshot.NODE_ANSWERS_KEY), liveNodes, templatesById);
        if (selectedTheme != null) {
            ObjectNode frozen = selectedTheme;
            for(JsonNode ch:frozen.path("chapters")) {
                int index=ch.path("index").asInt();JsonNode binding=JSON.createObjectNode().path("missing");
                for(JsonNode candidate:root.path("thoughtChapters"))if(candidate.path("authorIndex").asInt(-1)==index)binding=candidate;
                if(!binding.isObject())throw new IllegalArgumentException("背包配置引用未知章节");
                ((ObjectNode)ch).set("binding",binding.deepCopy());
            }
            ArrayNode legacy=frozen.putArray("legacyMedals");
            if(topic.getFinishMedalImg()!=null && !topic.getFinishMedalImg().trim().isEmpty()){ObjectNode medal=legacy.addObject();medal.put("id","legacy_finish");medal.put("kind","MEDAL");medal.put("name",topic.getFinishMedalName()==null?"通关勋章":topic.getFinishMedalName());medal.put("image",topic.getFinishMedalImg());medal.put("legacySource","FINISH");}
            for(CmsTopicNode node:liveNodes){CmsMemberTemplate tpl=templatesById.get(node.getTemplateId());if(tpl==null || tpl.getMedalImg()==null || tpl.getMedalImg().trim().isEmpty())continue;ObjectNode medal=legacy.addObject();medal.put("id","legacy_node_"+node.getId());medal.put("kind","MEDAL");medal.put("name",tpl.getMedalName()==null?"节点奖牌":tpl.getMedalName());medal.put("image",tpl.getMedalImg());medal.put("legacySource","NODE_RANK");medal.put("nodeId",node.getId());}
            root.set("themeConfig", frozen);applySceneNodes(root,frozen);
            root.set("themeConfig",mergeFrozenTemplateRules(root,frozen,templateIds,finalChapter(topic,runtimeChapters(topic.getId()))));
            freezeClueContent(root,liveNodes,templatesById);
        }
        return write(root);
    }

    /**
     * 线索内容冻结：模板「剧情」里标签与本模板线索名相同的段落（正文与图片）即该线索内容。
     * 内容随发布冻结，玩家只在本队/全场实际取得线索后由会话视图按来源站点回读；剧情 JSON 本身从不下发。
     */
    private void freezeClueContent(ObjectNode root,List<CmsTopicNode> liveNodes,Map<Long,CmsMemberTemplate> templatesById) {
        // 发布时冻结一次；建局等后续再冻结沿用发布版本，不随模板后续编辑变化
        if(root.has("clueContent"))return;
        ObjectNode out=JSON.createObjectNode();
        if(liveNodes!=null && templatesById!=null)for(CmsTopicNode node:liveNodes) {
            if(node==null || node.getId()==null)continue;
            JsonNode clues=root.path("nodeConfigs").path(String.valueOf(node.getId())).path("clues");
            CmsMemberTemplate template=templatesById.get(node.getTemplateId());
            if(!clues.isObject() || clues.size()==0 || template==null || template.getStoryJson()==null || template.getStoryJson().trim().isEmpty())continue;
            JsonNode beats;
            try{beats=JSON.readTree(template.getStoryJson());}catch(Exception unreadable){continue;}
            if(!beats.isArray())continue;
            ObjectNode byClue=JSON.createObjectNode();
            clues.fields().forEachRemaining(clue->{
                String name=clue.getValue().asText("").trim();ArrayNode blocks=JSON.createArrayNode();
                if(!name.isEmpty())for(JsonNode beat:beats)if(name.equals(beat.path("tag").asText("").trim())) {
                    ObjectNode block=blocks.addObject().put("text",beat.path("text").asText(""));ArrayNode images=block.putArray("images");
                    JsonNode imgs=beat.path("imgs").isArray()?beat.path("imgs"):JSON.createArrayNode().add(beat.path("img"));
                    for(JsonNode image:imgs)if(image.isTextual() && !image.asText().trim().isEmpty())images.add(image.asText().trim());
                }
                if(blocks.size()>0)byClue.set(clue.getKey(),blocks);
            });
            if(byClue.size()>0){ObjectNode row=out.putObject(String.valueOf(node.getId()));row.put("title",node.getName()==null?"":node.getName());row.set("clues",byClue);}
        }
        root.set("clueContent",out);
    }

    private ObjectNode mergeFrozenTemplateRules(ObjectNode snapshot,ObjectNode config,Map<Long,Long> templateIds,JsonNode finalChapter) {
        Map<Long,String> acts=new LinkedHashMap<>();for(JsonNode quest:config.path("gameplayRuntime").path("quests"))acts.put(quest.path("nodeId").asLong(),quest.path("actKey").asText());
        if (!snapshot.has("nodeConfigs")) return GameplayTemplateRules.merge(config,snapshot.path("nodeConfigs"),acts,templateIds,null,finalChapter);
        JsonNode declarations = snapshot.path("nodeConfigs").deepCopy();
        ObjectNode resolved = GameplayTemplateRules.resolveNodeConfigs(config,declarations,templateIds);
        com.chengyinhub.business.service.support.TemplateResourceSlots.assertEffectTargets(resolved,JourneyRules.parse(snapshot.path(NodeJourneySnapshot.JOURNEY_RULES_KEY).toString()),config);
        snapshot.set("nodeConfigs",resolved);
        ObjectNode mechanics = JSON.createObjectNode(); mechanics.set("nodeConfigs", resolved);
        applySceneNodes(snapshot, mechanics);
        ObjectNode definition = config.deepCopy();
        if (definition.has("nodeConfigs")) definition.set("nodeConfigs",resolved.deepCopy());
        if (definition.has("templateBindings")) ThemeRuntimeConfig.assertJourneyReferences(definition,JourneyRules.parse(snapshot.path(NodeJourneySnapshot.JOURNEY_RULES_KEY).toString()));
        JsonNode story=snapshot.path(NodeJourneySnapshot.JOURNEY_STORY_KEY);
        if(story.isTextual())story=story.asText().trim().isEmpty()?JSON.createObjectNode():validator.readTree(story.asText());
        return snapshot.path("runtimeSnapshotVersion").asInt()==1
                ? GameplayTemplateRules.mergeFrozen(definition,declarations,acts,story)
                : GameplayTemplateRules.merge(definition,declarations,acts,templateIds,story,finalChapter);
    }

    private void freezeThoughtChapters(ObjectNode root, CmsTopic topic, List<CmsTopicNode> nodes, Long openingId,
                                      String effectiveStory) {
        com.fasterxml.jackson.databind.node.ArrayNode frozen = root.putArray("thoughtChapters");
        // 独立保留结局正文；不加入玩家推进章节列表，读侧仅投影已选结局。
        ObjectNode endingChapters = root.putObject("endingChapters");
        if (cmsTopicChapterMapper == null) return;
        List<CmsTopicChapter> rows = runtimeChapters(topic.getId());
        List<CmsTopicChapter> chapters = new ArrayList<CmsTopicChapter>(rows);
        chapters.sort(Comparator.comparing(CmsTopicChapter::getSortId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(CmsTopicChapter::getId, Comparator.nullsLast(Long::compareTo)));
        Set<Long> endingIds = new LinkedHashSet<Long>(JourneyStory.endingChapterIds(topic.getJourneyStoryJson()));
        endingIds.addAll(JourneyStory.endingChapterIds(effectiveStory));
        Set<Long> requiredBefore = new LinkedHashSet<Long>();
        int authorIndex=-1;
        for (CmsTopicChapter chapter : chapters) {
            authorIndex++;
            if (chapter.getId() == null) continue;
            if (endingIds.contains(chapter.getId())) {
                if (topic.getId().equals(chapter.getTopicId())) {
                    ObjectNode ending = endingChapters.putObject(String.valueOf(chapter.getId()));
                    ending.put("id", chapter.getId());
                    ending.put("name", chapter.getName());
                    ending.put("atmospherePreset", chapter.getAtmospherePreset());
                    ending.put("audioUrl", chapter.getAudioUrl());
                    ending.set("blocks", JSON.valueToTree(ChapterBlockReadSupport.resolve(
                            chapter, Collections.<CmsTopicNode>emptyList())));
                }
                continue;
            }
            Set<Long> nodeIds = new LinkedHashSet<Long>();
            for (CmsTopicNode node : nodes) if (chapter.getId().equals(node.getChapterId())) nodeIds.add(node.getId());
            ObjectNode row = frozen.addObject();
            row.put("chapterId", chapter.getId());
            row.put("authorIndex",authorIndex);
            row.put("opening", chapter.getId().equals(openingId));
            row.put("requiresOpening",openingId!=null);
            row.set("nodeIds", JSON.valueToTree(nodeIds));
            row.set("requiredBefore", JSON.valueToTree(requiredBefore));
            row.set("blocks", JSON.valueToTree(ChapterBlockReadSupport.resolveFromTopicNodes(chapter, nodes)));
            if (ChapterBlockReadSupport.isRequiredChapter(chapter)) requiredBefore.addAll(nodeIds);
        }
    }

    private static final String[] TEMPLATE_PRESENTATION_FIELDS = ("id title description imgUrl players usageLocation "
            + "requiredMaterials duration difficulty ruleInstructions merchantGuide interactionType questionName questionImg "
            + "questionAudio questionOptionMediaJson questionA questionB questionC questionD storyText storyImg feedbackText "
            + "photoRequireDesc audioUrl audioDuration medalName medalStyle medalImg couponId").split(" ");
    private static final String[] NODE_PRESENTATION_FIELDS = ("id topicId chapterId templateId merchantMemberId name description "
            + "address longitude latitude imgUrl hookText cardHookLong fragmentText nodeTime businessTime sortId unlockAfterNodeId").split(" ");

    /** Internal snapshot, never a public response. Hint/reveal/answer projection stays with its original owner. */
    private void freezeContentSnapshots(ObjectNode frozen, CmsTopic topic, List<CmsTopicNode> nodes,
            Map<Long, CmsMemberTemplate> templates) {
        Map<Long, CmsTopicChapter> chapters = new LinkedHashMap<>();
        for (CmsTopicChapter chapter : runtimeChapters(topic.getId())) chapters.put(chapter.getId(), chapter);
        ObjectNode answers = frozen.with(NodeJourneySnapshot.NODE_ANSWERS_KEY);
        for (CmsTopicNode node : nodes) {
            if (node.getId() == null || node.getId() <= 0 || !topic.getId().equals(node.getTopicId()))
                throw new IllegalStateException("冻结节点身份不一致");
            CmsTopicChapter chapter = node.getChapterId() == null ? null : chapters.get(node.getChapterId());
            if (node.getChapterId() != null && (chapter == null || !topic.getId().equals(chapter.getTopicId())))
                throw new IllegalStateException("冻结章节身份不一致");
            if (node.getTemplateId() != null && node.getTemplateId() < 0) throw new IllegalStateException("冻结模板身份无效");
            Long templateId = node.getTemplateId() != null && node.getTemplateId() > 0 ? node.getTemplateId() : null;
            CmsMemberTemplate template = templateId == null ? null : templates.get(templateId);
            if (templateId != null && (template == null || !templateId.equals(template.getId())))
                throw new IllegalStateException("冻结模板身份不一致");
            ObjectNode target = answers.with(String.valueOf(node.getId()));
            target.put("contentSnapshotVersion", 1);
            if (templateId == null) target.putNull("templateId"); else target.put("templateId", templateId);
            ObjectNode nodePresentation = selectPresentation(node, NODE_PRESENTATION_FIELDS);
            if (templateId == null) nodePresentation.putNull("templateId");
            target.set("nodePresentation", nodePresentation);
            target.set("templatePresentation", template == null ? JSON.createObjectNode()
                    : selectPresentation(template, TEMPLATE_PRESENTATION_FIELDS));
            if (chapter == null || chapter.getAudioUrl() == null) target.putNull("chapterAudioUrl");
            else target.put("chapterAudioUrl", chapter.getAudioUrl());
            if (template != null) {
                putAnswerText(target, "hint1", template.getHint1());
                putAnswerText(target, "hint2", template.getHint2());
            }
        }
    }

    private ObjectNode selectPresentation(Object entity, String[] fields) {
        JsonNode source = JSON.valueToTree(entity);
        ObjectNode selected = JSON.createObjectNode();
        for (String field : fields) selected.set(field, source.has(field) ? source.get(field) : JSON.nullNode());
        return selected;
    }

    /**
     * 建局冻结模板判定字段(R18-FREEZE):validationMethod 与各验证方式的答案 / 揭示 / 偏好 /
     * 传感器配置。每个在架节点写一条(没绑定模板也写空对象,封死新局回退);模板行取不到时
     * 只留空对象。奖励字段(券 / 探索值 / 条件奖励规则)不在此列,仍读实时。
     */
    private void freezeNodeAnswers(ObjectNode nodeAnswers, List<CmsTopicNode> liveNodes,
                                   Map<Long, CmsMemberTemplate> templates) {
        Map<Long, CmsMemberTemplate> byId = templates == null
                ? Collections.<Long, CmsMemberTemplate>emptyMap() : templates;
        for (CmsTopicNode node : liveNodes) {
            if (node.getId() == null) continue;
            ObjectNode target = nodeAnswers.putObject(String.valueOf(node.getId()));
            if (node.getTemplateId() == null || node.getTemplateId() <= 0L) continue;
            CmsMemberTemplate template = byId.get(node.getTemplateId());
            if (template == null) continue;
            if (template.getValidationMethod() != null) {
                target.put("validationMethod", template.getValidationMethod().intValue());
            }
            if (template.getPhotoReview() != null) {
                target.put("photoReview", template.getPhotoReview().intValue());
            }
            putAnswerText(target, "questionAnswer", template.getQuestionAnswer());
            putAnswerText(target, "correctAnswer", template.getCorrectAnswer());
            putAnswerText(target, "answerReveal", template.getAnswerReveal());
            putAnswerText(target, "questionA", template.getQuestionA());
            putAnswerText(target, "questionB", template.getQuestionB());
            putAnswerText(target, "questionC", template.getQuestionC());
            putAnswerText(target, "questionD", template.getQuestionD());
            putAnswerText(target, "preferenceJson", template.getPreferenceJson());
            putAnswerText(target, "sensorType", template.getSensorType());
            putAnswerText(target, "sensorConfig", template.getSensorConfig());
        }
    }

    /** 冻结字段拷贝:null 不写(建局时没有这个字段 = 冻结成「没有」)。 */
    private static void putAnswerText(ObjectNode target, String field, String value) {
        if (value != null) target.put(field, value);
    }

    /** 冻结用块配置解析:坏 JSON / 非对象一律当成没配(发布门禁负责拦,建局不因坏块失败)。 */
    private JsonNode readBlockConfig(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JsonNode config = JSON.readTree(raw);
            return config != null && config.isObject() ? config : null;
        } catch (Exception unusableBlockConfig) {
            return null;
        }
    }

    static JourneyRules journeyRules(CmsTopic topic) {
        return JourneyRules.parse(topic == null ? null : topic.getJourneyRulesJson());
    }

    /** 线性局快照:没有 edges(正是它区别于分支快照的判别依据),只留起终点与空变量表。 */
    String linearSnapshot(List<CmsTopicNode> liveNodes) {
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.put("schemaVersion", 1);
        if (!liveNodes.isEmpty()) snapshot.put("startNodeId", liveNodes.get(0).getId().longValue());
        ArrayNode terminals = snapshot.putArray("terminalNodeIds");
        if (!liveNodes.isEmpty()) terminals.add(liveNodes.get(liveNodes.size() - 1).getId().longValue());
        snapshot.putObject("variables");
        return write(snapshot);
    }

    List<CmsTopicNode> nodes(Long topicId) {
        CmsTopicNode probe = new CmsTopicNode(); probe.setTopicId(topicId); probe.setStatus(1L);
        List<CmsTopicNode> result;
        if (currentContent != null && currentContent.topicId.equals(topicId)) {
            result = new ArrayList<>();
            for (CmsTopicNode node : currentContent.nodes) {
                if (Long.valueOf(1L).equals(node.getStatus())) result.add(node);
            }
        } else result = nodeMapper.selectCmsTopicNodeList(probe);
        if (result == null) return Collections.emptyList();
        result = new ArrayList<CmsTopicNode>(result);
        result.sort(Comparator.comparingLong(n -> n.getSortId() == null ? 0L : n.getSortId())); return result;
    }

    private String write(Object value) { try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("路线状态序列化失败", e); } }

    private void applySceneNodes(ObjectNode root,JsonNode frozen) {
        frozen.path("nodeConfigs").fields().forEachRemaining(row->{
            root.with("nodeConfigs").set(row.getKey(),row.getValue().deepCopy());
            ObjectNode block=root.with(NodeJourneySnapshot.NODE_JOURNEY_KEY).with(row.getKey());
            block.remove(java.util.Arrays.asList("requires","effects","check","mistakeTier",NodeJourneySnapshot.CHOICE_EFFECTS_KEY,NodeJourneySnapshot.PLAYABLES_KEY,NodeJourneySnapshot.BRANCH_OUTCOMES_KEY));
            NodeJourneySnapshot.copyMechanicSegments(row.getValue(),block);
        });
    }

    static boolean containsNode(JsonNode ids,Long id) {if(id==null)return false;for(JsonNode value:ids)if(value.asLong()==id)return true;return false;}
}
