package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.JourneyStory;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.util.StateKeyRefs;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主题状态键引用的单一收集入口(B1/R8)。
 *
 * <p>发布期状态键闸({@code CmsTopicServiceImpl.assertStateKeysRegistered})与发布前试检查
 * ({@link TopicPublishCheckService})共用这一份收集:启用节点(status=1)的玩法块配置
 * requires/effects/check、主题数值规则的 {@code recovery.exhaustTag}、以及主题剧情配置
 * journey_story_json 的 cond、optionalClues 与尾声 relationKey(R11-C 显式关系值来源)。
 *
 * <p>R19:叙事搬进章节故事流后,beat 块上的 when(brief carry / outcome fact / revisit change)
 * 与模板里的条件同一道闸 —— 这里把章节 blocks 也收进来,漏了它发布闸就只对死配置负责。
 *
 * <p>坏配置不静默:解析不了的原因进 {@link RefCollection#getParseErrors()},由调用方决定
 * 抛(发布闸,fail-closed)还是列成问题项(试检查)。
 */
@Service
public class TopicStateKeyRefService {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 节点玩法块配置解析失败。 */
    public static final String KIND_BLOCK = "block";
    /** 主题数值规则解析失败。 */
    public static final String KIND_JOURNEY_RULES = "journeyRules";
    /** 主题剧情配置解析失败。 */
    public static final String KIND_JOURNEY_STORY = "journeyStory";
    /** 章节故事流 blocks 解析失败(R22-SEC S14:与节点块同口径,发布闸 fail-closed)。 */
    public static final String KIND_CHAPTER_BLOCKS = "chapterBlocks";

    private final CmsTopicNodeMapper cmsTopicNodeMapper;
    private final CmsMemberTemplateMapper cmsMemberTemplateMapper;
    private final CmsTopicChapterMapper cmsTopicChapterMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.mapper.CmsTopicMapper cmsTopicMapper;

    public TopicStateKeyRefService(CmsTopicNodeMapper cmsTopicNodeMapper,
                                   CmsMemberTemplateMapper cmsMemberTemplateMapper,
                                   CmsTopicChapterMapper cmsTopicChapterMapper) {
        this.cmsTopicNodeMapper = cmsTopicNodeMapper;
        this.cmsMemberTemplateMapper = cmsMemberTemplateMapper;
        this.cmsTopicChapterMapper = cmsTopicChapterMapper;
    }

    /** Short-lived server-only inputs, not an HTTP DTO or a cross-request cache. */
    public static final class CurrentContent {
        private final CmsTopic topic;
        private final List<CmsTopicNode> nodes;
        private final List<CmsTopicChapter> chapters;
        private final Map<Long, CmsMemberTemplate> templates;
        private CurrentContent(CmsTopic topic, List<CmsTopicNode> nodes, List<CmsTopicChapter> chapters,
                Map<Long, CmsMemberTemplate> templates) {
            this.topic = topic;
            this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
            this.chapters = Collections.unmodifiableList(new ArrayList<>(chapters));
            this.templates = Collections.unmodifiableMap(new LinkedHashMap<>(templates));
        }
        public CmsTopic topic() { return topic; }
        public List<CmsTopicNode> nodes() { return nodes; }
        public List<CmsTopicNode> activeNodes() {
            List<CmsTopicNode> active = new ArrayList<>();
            for (CmsTopicNode node : nodes) if (Long.valueOf(1L).equals(node.getStatus())) active.add(node);
            return Collections.unmodifiableList(active);
        }
        public List<CmsTopicChapter> chapters() { return chapters; }
        public Map<Long, CmsMemberTemplate> templates() { return templates; }
    }

    private static void requirePublicationTransaction() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("正式引用收集必须加入主题写事务");
    }

    public CurrentContent currentForPublication(CmsTopic topic) {
        requirePublicationTransaction();
        if (topic == null || topic.getId() == null || topic.getId() <= 0 || cmsTopicMapper == null)
            throw new IllegalStateException("正式引用收集主题依赖缺失");
        if (cmsTopicChapterMapper == null || cmsTopicNodeMapper == null || cmsMemberTemplateMapper == null)
            throw new IllegalStateException("正式引用收集子内容依赖缺失");
        CmsTopic current = cmsTopicMapper.selectCmsTopicByIdForUpdate(topic.getId());
        if (current == null || !topic.getId().equals(current.getId())) throw new IllegalStateException("正式引用收集主题身份不一致");
        List<CmsTopicChapter> chapters = cmsTopicChapterMapper.selectByTopicForUpdate(topic.getId());
        List<CmsTopicNode> nodes = cmsTopicNodeMapper.selectByTopicForUpdate(topic.getId());
        if (chapters == null || nodes == null) throw new IllegalStateException("正式引用收集内容未知");
        for (CmsTopicChapter chapter : chapters) if (chapter == null || chapter.getId() == null || chapter.getId() <= 0
                || !topic.getId().equals(chapter.getTopicId()))
            throw new IllegalStateException("正式引用收集章节归属不一致");
        Set<Long> templateIds = new java.util.TreeSet<>();
        for (CmsTopicNode node : nodes) {
            if (node == null || node.getId() == null || node.getId() <= 0 || !topic.getId().equals(node.getTopicId()))
                throw new IllegalStateException("正式引用收集节点归属不一致");
            if (node.getTemplateId() != null && node.getTemplateId() > 0) templateIds.add(node.getTemplateId());
        }
        Map<Long, CmsMemberTemplate> templates = new LinkedHashMap<>();
        if (!templateIds.isEmpty()) {
            List<CmsMemberTemplate> rows = cmsMemberTemplateMapper.selectCmsMemberTemplateByIdsForUpdate(new ArrayList<>(templateIds));
            if (rows == null || rows.size() != templateIds.size()) throw new IllegalStateException("正式引用收集模板未知");
            for (CmsMemberTemplate row : rows) if (row == null || !templateIds.contains(row.getId()) || templates.put(row.getId(), row) != null)
                throw new IllegalStateException("正式引用收集模板身份不一致");
        }
        List<CmsTopicNode> ordered = new ArrayList<>(nodes);
        ordered.sort(java.util.Comparator.comparing(CmsTopicNode::getSortId, java.util.Comparator.nullsFirst(Long::compareTo)));
        return new CurrentContent(current, ordered, chapters, templates);
    }

    public RefCollection collectForPublication(CmsTopic topic) { return collectCurrent(currentForPublication(topic)); }

    public RefCollection collectCurrent(CurrentContent content) {
        requirePublicationTransaction();
        if (content == null) throw new IllegalStateException("正式引用收集投影缺失");
        RefCollection refs = new RefCollection();
        for (CmsTopicNode node : content.activeNodes())
            collectNode(node, content.topic(), refs, content.templates().get(node.getTemplateId()));
        collectJourneyRules(content.topic(), refs);
        collectJourneyStory(content.topic(), refs);
        collectChapters(content.chapters(), refs);
        return refs;
    }

    /** 收集该主题当前引用的全部状态键与解析问题。主题为空返回空结果,不抛。 */
    public RefCollection collect(CmsTopic topic) {
        RefCollection refs = new RefCollection();
        if (topic == null || topic.getId() == null || topic.getId() <= 0L) {
            return refs;
        }
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topic.getId());
        probe.setStatus(1L);
        List<CmsTopicNode> nodes = cmsTopicNodeMapper.selectCmsTopicNodeList(probe);
        if (nodes != null) {
            for (CmsTopicNode node : nodes) {
                collectNode(node, topic, refs);
            }
        }
        collectJourneyRules(topic, refs);
        collectJourneyStory(topic, refs);
        collectChapters(topic, refs);
        return refs;
    }

    /**
     * 该键在主题上的引用位置(节点展示名 / 主题数值规则 / 主题剧情配置);空列表 = 没被引用。
     *
     * <p>有解析不了的配置时 fail-closed 抛 {@link ServiceException}:
     * 读不出引用不等于没有引用,不能拿它放行删除。
     */
    public List<String> referencesOf(CmsTopic topic, String stateKey) {
        RefCollection refs = collect(topic);
        if (!refs.getParseErrors().isEmpty()) {
            ParseError first = refs.getParseErrors().get(0);
            throw new ServiceException("主题存在无法解析的配置,暂不能删除状态键:" + parseErrorMessage(first));
        }
        Set<String> positions = refs.positionsByKey().get(stateKey);
        return positions == null ? new ArrayList<String>() : new ArrayList<String>(positions);
    }

    /** 发布闸的失败文案:节点块 / 主题数值规则 / 主题剧情配置各一句,语义与 B1 原样一致。 */
    public static String parseErrorMessage(ParseError error) {
        if (error.getNodeId() != null) {
            return "节点「" + error.getNodeLabel() + "」的玩法配置无法解析,主题发布失败:" + error.getMessage();
        }
        if (KIND_JOURNEY_RULES.equals(error.getKind())) {
            return "主题的旅程数值规则无法解析,主题发布失败:" + error.getMessage();
        }
        if (KIND_CHAPTER_BLOCKS.equals(error.getKind())) {
            return "章节故事流配置无法解析,主题发布失败:" + error.getMessage();
        }
        return "主题剧情配置无法解析,主题发布失败:" + error.getMessage();
    }

    private void collectNode(CmsTopicNode node, CmsTopic topic, RefCollection refs) {
        if (node == null) {
            return;
        }
        Long templateId = node.getTemplateId();
        if (templateId == null || templateId <= 0L) {
            return;
        }
        CmsMemberTemplate template = cmsMemberTemplateMapper.selectCmsMemberTemplateById(templateId);
        collectNode(node, topic, refs, template);
    }

    private void collectNode(CmsTopicNode node, CmsTopic topic, RefCollection refs, CmsMemberTemplate template) {
        Long templateId = node.getTemplateId();
        if (template == null || StringUtils.isEmpty(template.getAdvancedConfigJson())) {
            return;
        }
        refs.nodeLabels.put(node.getId(), nodeLabel(node));
        try {
            String raw = template.getAdvancedConfigJson();
            // 先按原合同校验 JSON（非法时给作者“不是合法 JSON”），再解析已绑定的资源槽
            Set<String> keys = StateKeyRefs.collect(raw);
            JsonNode config = JSON.readTree(raw);
            if (config != null && config.path("interactionRules").has("resourceSlots")) {
                ObjectNode nodes = JSON.createObjectNode();nodes.set(String.valueOf(node.getId()), config);
                raw = GameplayTemplateRules.resolveNodeConfigs(ThemeRuntimeConfig.parseDraft(topic.getThemeRuntimeConfigJson()),
                        nodes, Collections.singletonMap(node.getId(), templateId)).path(String.valueOf(node.getId())).toString();
                keys = StateKeyRefs.collect(raw);
            }
            if (config != null && config.path("outcomeRules").path("version").asInt() == 1) {
                // R5 内置费用先核对主题启用状态，再从需要注册的作者键中排除。
                ThemeRuntimeConfig.assertJourneyReferences((ObjectNode) JSON.readTree(raw), JourneyRules.parse(topic.getJourneyRulesJson()));
                keys.remove("sys.hp");
                keys.remove("sys.luck");
            }
            if (!keys.isEmpty()) {
                refs.refsByNode.put(node.getId(), keys);
            }
        } catch (java.io.IOException | RuntimeException e) {
            refs.parseErrors.add(new ParseError(node.getId(), nodeLabel(node), KIND_BLOCK, e.getMessage()));
        }
    }

    private void collectJourneyRules(CmsTopic topic, RefCollection refs) {
        String raw = topic.getJourneyRulesJson();
        if (StringUtils.isEmpty(raw)) {
            return;
        }
        JourneyRules rules;
        try {
            rules = JourneyRules.parse(raw);
        } catch (RuntimeException e) {
            refs.parseErrors.add(new ParseError(null, null, KIND_JOURNEY_RULES, e.getMessage()));
            return;
        }
        // R22-SEC S15:checkEnabled 只关检定,不豁免 exhaustTag 的注册闸 ——
        // 该 tag 仍会被运行期(力竭休整)写进本局,未注册就不能放行。
        if (rules.isCheckEnabled()) {
            refs.journeyRulesCheckEnabled = true;
        }
        if (rules.getRecovery() == null) {
            return;
        }
        String tag = rules.getRecovery().getExhaustTag();
        if (!StringUtils.isEmpty(tag)) {
            refs.exhaustTag = tag;
        }
    }

    /**
     * 主题剧情配置 journey_story_json 的键引用(所有 when 条件 + 尾声的 relationKey)。
     *
     * <p>走 {@link JourneyStory#parse} 的真实模型解析(先过 JourneyStoryValidator 再反序列化),
     * 不再反射 getter、也不再按 JSON 树盲收 when:结构坏了就是解析错误,fail-closed。
     */
    private void collectJourneyStory(CmsTopic topic, RefCollection refs) {
        String raw = topic.getJourneyStoryJson();
        if (StringUtils.isBlank(raw)) {
            return;
        }
        JourneyStory story;
        try {
            story = JourneyStory.parse(raw);
        } catch (RuntimeException e) {
            refs.parseErrors.add(new ParseError(null, null, KIND_JOURNEY_STORY, e.getMessage()));
            return;
        }
        if (story == null) {
            return;
        }
        collectStoryKeys(story, refs.storyKeys);
        // R11-C:尾声的 relationKey 是显式关系值来源,与 cond 一样必须已注册。
        refs.storyKeys.addAll(StateKeyRefs.collectJourneyStoryRelationKeys(raw));
    }

    /**
     * 剧情配置的键引用:stages[].end.changed[].when、ending.endings / exhibits / badges[].when、
     * ending.optionalClues[]。epilogues 的 relationKey 由
     * {@link StateKeyRefs#collectJourneyStoryRelationKeys} 单独收集(发布闸还要核对键类型)。
     */
    private void collectStoryKeys(JourneyStory story, Set<String> keys) {
        if (story.getStages() != null) {
            for (JourneyStory.Stage stage : story.getStages()) {
                if (stage == null || stage.getEnd() == null) {
                    continue;
                }
                collectChangeKeys(stage.getEnd().getChanged(), keys);
            }
        }
        JourneyStory.Ending ending = story.getEnding();
        if (ending == null) {
            return;
        }
        if (ending.getEndings() != null) {
            for (JourneyStory.EndingCase item : ending.getEndings()) {
                if (item != null) {
                    keys.addAll(StateKeyRefs.collectWhen(item.getWhen()));
                }
            }
        }
        if (ending.getExhibits() != null) {
            for (JourneyStory.Exhibit item : ending.getExhibits()) {
                if (item != null) {
                    keys.addAll(StateKeyRefs.collectWhen(item.getWhen()));
                }
            }
        }
        if (ending.getBadges() != null) {
            for (JourneyStory.Badge item : ending.getBadges()) {
                if (item != null) {
                    keys.addAll(StateKeyRefs.collectWhen(item.getWhen()));
                }
            }
        }
        if (ending.getOptionalClues() != null) {
            for (String clue : ending.getOptionalClues()) {
                if (!StringUtils.isEmpty(clue)) {
                    keys.add(clue.trim());
                }
            }
        }
    }

    /**
     * R19:章节故事流 blocks 里 beat 块 when 引用的键。叙事搬进故事流后,模板时代
     * outcome.facts / revisit.changes / brief.carry 的条件真源改在这里;
     * R22-SEC S14:坏 JSON 记 ParseError(与节点块同一口径,发布闸 fail-closed),
     * 形状不对仍按「没引用」处理。
     */
    private void collectChapters(CmsTopic topic, RefCollection refs) {
        if (cmsTopicChapterMapper == null) {
            return;
        }
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(topic.getId());
        List<CmsTopicChapter> chapters = cmsTopicChapterMapper.selectCmsTopicChapterList(probe);
        if (chapters == null) {
            return;
        }
        collectChapters(chapters, refs);
    }

    private void collectChapters(List<CmsTopicChapter> chapters, RefCollection refs) {
        for (CmsTopicChapter chapter : chapters) {
            if (chapter == null || StringUtils.isEmpty(chapter.getBlocksJson())) {
                continue;
            }
            try {
                refs.chapterKeys.addAll(StateKeyRefs.collectChapterBlocks(chapter.getBlocksJson()));
            } catch (RuntimeException e) {
                refs.parseErrors.add(new ParseError(null, null, KIND_CHAPTER_BLOCKS, e.getMessage()));
            }
        }
    }

    private void collectChangeKeys(List<JourneyStory.Change> changes, Set<String> keys) {
        if (changes == null) {
            return;
        }
        for (JourneyStory.Change change : changes) {
            if (change != null) {
                keys.addAll(StateKeyRefs.collectWhen(change.getWhen()));
            }
        }
    }

    static String nodeLabel(CmsTopicNode node) {
        String name = node.getName();
        return StringUtils.isEmpty(name) ? "节点#" + node.getId() : name;
    }

    /** 一次收集的结果:引用、来源标签与解析问题。 */
    public static final class RefCollection {

        /** 节点 id → 该节点块配置引用到的键(保持出现顺序)。 */
        private final Map<Long, Set<String>> refsByNode = new LinkedHashMap<Long, Set<String>>();

        /** 节点 id → 展示名(节点名为空回落 节点#id)。 */
        private final Map<Long, String> nodeLabels = new LinkedHashMap<Long, String>();

        /** journey_story_json 里 when 条件引用到的键。 */
        private final Set<String> storyKeys = new LinkedHashSet<String>();

        /** 章节故事流 beat 块 when 引用到的键(R19)。 */
        private final Set<String> chapterKeys = new LinkedHashSet<String>();

        /** 解析失败项,按遇到顺序;空 = 全部可解析。 */
        private final List<ParseError> parseErrors = new ArrayList<ParseError>();

        /** 主题数值规则启用时为 true(决定线性主题会不会建局)。 */
        private boolean journeyRulesCheckEnabled;

        /** 主题数值规则 recovery.exhaustTag;未启用 / 未配置 = null。 */
        private String exhaustTag;

        public Map<Long, Set<String>> getRefsByNode() { return refsByNode; }

        public Map<Long, String> getNodeLabels() { return nodeLabels; }

        public Set<String> getStoryKeys() { return storyKeys; }

        public Set<String> getChapterKeys() { return chapterKeys; }

        public List<ParseError> getParseErrors() { return parseErrors; }

        public boolean isJourneyRulesCheckEnabled() { return journeyRulesCheckEnabled; }

        public String getExhaustTag() { return exhaustTag; }

        /** 键 → 引用位置展示名(节点「x」/ 主题数值规则 / 主题剧情配置 / 章节故事流),保持出现顺序。 */
        public Map<String, Set<String>> positionsByKey() {
            Map<String, Set<String>> positions = new LinkedHashMap<String, Set<String>>();
            for (Map.Entry<Long, Set<String>> entry : refsByNode.entrySet()) {
                for (String key : entry.getValue()) {
                    addPosition(positions, key, "节点「" + nodeLabels.get(entry.getKey()) + "」");
                }
            }
            if (exhaustTag != null) {
                addPosition(positions, exhaustTag, "主题数值规则");
            }
            for (String key : storyKeys) {
                addPosition(positions, key, "主题剧情配置");
            }
            for (String key : chapterKeys) {
                addPosition(positions, key, "章节故事流");
            }
            return positions;
        }

        private static void addPosition(Map<String, Set<String>> positions, String key, String position) {
            Set<String> values = positions.get(key);
            if (values == null) {
                values = new LinkedHashSet<String>();
                positions.put(key, values);
            }
            values.add(position);
        }
    }

    /** 一处解析失败:nodeId 为空 = 主题级配置(数值规则 / 剧情配置)。 */
    public static final class ParseError {

        private final Long nodeId;
        private final String nodeLabel;
        private final String kind;
        private final String message;

        public ParseError(Long nodeId, String nodeLabel, String kind, String message) {
            this.nodeId = nodeId;
            this.nodeLabel = nodeLabel;
            this.kind = kind;
            this.message = message;
        }

        public Long getNodeId() { return nodeId; }

        public String getNodeLabel() { return nodeLabel; }

        public String getKind() { return kind; }

        public String getMessage() { return message; }
    }
}
