package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.vo.PublicationProblem;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.util.StateKeyRefs;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发布前试检查:列出专业主题首个整包问题及 R8 配置问题,只报不改。
 *
 * <p>与发布闸共用 {@link TopicStateKeyRefService} 的收集代码,区别只在「抛」还是「列」:
 * 发布闸拦在 {@code CmsTopicServiceImpl.assertStateKeysRegistered},这里是同一个事实的预演。
 * 覆盖:未注册状态键、节点块配置校验失败、主题数值规则校验失败、以及线性主题建局说明(warn)。
 */
@Service
public class TopicPublishCheckService {
    private static final com.fasterxml.jackson.databind.ObjectMapper PREVIEW_JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    public static final String CODE_TOPIC_BUNDLE_INVALID = PublicationProblem.CODE_TOPIC_BUNDLE_INVALID;
    public static final String CODE_STATE_KEY_UNREGISTERED = PublicationProblem.CODE_STATE_KEY_UNREGISTERED;
    /** 键注册了但类型不对(目前只有尾声 relationKey 会走这条:类型必须是 relation)。 */
    public static final String CODE_STATE_KEY_TYPE_INVALID = PublicationProblem.CODE_STATE_KEY_TYPE_INVALID;
    public static final String CODE_BLOCK_CONFIG_INVALID = PublicationProblem.CODE_BLOCK_CONFIG_INVALID;
    public static final String CODE_JOURNEY_RULES_INVALID = PublicationProblem.CODE_JOURNEY_RULES_INVALID;
    public static final String CODE_JOURNEY_STORY_INVALID = PublicationProblem.CODE_JOURNEY_STORY_INVALID;
    /** R22-SEC S14:章节故事流 blocks 解析失败(与节点块同样是发布期会拦的错)。 */
    public static final String CODE_CHAPTER_BLOCKS_INVALID = PublicationProblem.CODE_CHAPTER_BLOCKS_INVALID;
    public static final String CODE_LINEAR_JOURNEY_SESSION = "LINEAR_JOURNEY_SESSION";

    private final CmsTopicNodeMapper cmsTopicNodeMapper;
    private final CmsMemberTemplateMapper cmsMemberTemplateMapper;
    private final ThemeStateKeyService themeStateKeyService;
    private final TopicStateKeyRefService topicStateKeyRefService;
    private final TopicBundleReviewService topicBundleReviewService;
    /** 章节标题定位用;老测试手工 new 时为 null,只是少个标题,不影响任何问题项。 */
    private final com.chengyinhub.business.mapper.CmsTopicChapterMapper cmsTopicChapterMapper;
    /** 发布闸本体:预检复用它的既有 assert 收集问题;未注入时只跑本类原有判据。 */
    private final TopicPublicationContentGuard publicationContentGuard;
    /** O5c:可选注入,未注入(老测试直接 new)时等同开关关闭。 */
    private TopicAiPrecheckService topicAiPrecheckService;
    /** 与 TemplatePublishServiceImpl 同一用法:无状态,直接 new。 */
    private final AdvancedGameConfigValidator advancedGameConfigValidator = new AdvancedGameConfigValidator();

    /** 老测试用的最小构造:不含发布闸与章节标题,行为与改造前一致。 */
    public TopicPublishCheckService(CmsTopicNodeMapper cmsTopicNodeMapper,
                                    CmsMemberTemplateMapper cmsMemberTemplateMapper,
                                    ThemeStateKeyService themeStateKeyService,
                                    TopicStateKeyRefService topicStateKeyRefService,
                                    TopicBundleReviewService topicBundleReviewService) {
        this(cmsTopicNodeMapper, cmsMemberTemplateMapper, themeStateKeyService, topicStateKeyRefService,
                topicBundleReviewService, null, null);
    }

    @Autowired
    public TopicPublishCheckService(CmsTopicNodeMapper cmsTopicNodeMapper,
                                    CmsMemberTemplateMapper cmsMemberTemplateMapper,
                                    ThemeStateKeyService themeStateKeyService,
                                    TopicStateKeyRefService topicStateKeyRefService,
                                    TopicBundleReviewService topicBundleReviewService,
                                    com.chengyinhub.business.mapper.CmsTopicChapterMapper cmsTopicChapterMapper,
                                    TopicPublicationContentGuard publicationContentGuard) {
        this.cmsTopicNodeMapper = cmsTopicNodeMapper;
        this.cmsMemberTemplateMapper = cmsMemberTemplateMapper;
        this.themeStateKeyService = themeStateKeyService;
        this.topicStateKeyRefService = topicStateKeyRefService;
        this.topicBundleReviewService = topicBundleReviewService;
        this.cmsTopicChapterMapper = cmsTopicChapterMapper;
        this.publicationContentGuard = publicationContentGuard;
    }

    @Autowired(required = false)
    public void setTopicAiPrecheckService(TopicAiPrecheckService topicAiPrecheckService) {
        this.topicAiPrecheckService = topicAiPrecheckService;
    }

    @Autowired
    private AppMediaPublicationGuard mediaPublicationGuard;

    /** 试检查:error = 发布会被拦,warn = 不拦但有话要说。主题为空返回空列表。 */
    public List<PublishCheckIssue> check(CmsTopic topic) {
        List<PublishCheckIssue> issues = new ArrayList<PublishCheckIssue>();
        if (topic == null || topic.getId() == null || topic.getId() <= 0L) {
            return issues;
        }
        if (!"simple".equalsIgnoreCase(topic.getPublishMode())
                && !"ai_simple".equalsIgnoreCase(topic.getPublishMode())) {
            try {
                topicBundleReviewService.freezeForSubmission(topic.getId(), topic.getMemberId());
            } catch (IllegalArgumentException | IllegalStateException e) {
                issues.add(PublishCheckIssue.error(CODE_TOPIC_BUNDLE_INVALID, null, e.getMessage()));
            }
        }
        TopicStateKeyRefService.RefCollection refs = topicStateKeyRefService.collect(topic);

        Set<Long> unparsableNodes = new LinkedHashSet<Long>();
        for (TopicStateKeyRefService.ParseError error : refs.getParseErrors()) {
            if (error.getNodeId() != null) {
                unparsableNodes.add(error.getNodeId());
                issues.add(PublishCheckIssue.error(CODE_BLOCK_CONFIG_INVALID, error.getNodeId(),
                        "节点「" + error.getNodeLabel() + "」的高级玩法配置不合法:" + error.getMessage()));
            } else if (TopicStateKeyRefService.KIND_JOURNEY_STORY.equals(error.getKind())) {
                issues.add(PublishCheckIssue.error(CODE_JOURNEY_STORY_INVALID, null,
                        "主题剧情配置不合法:" + error.getMessage()));
            } else if (TopicStateKeyRefService.KIND_CHAPTER_BLOCKS.equals(error.getKind())) {
                issues.add(PublishCheckIssue.error(CODE_CHAPTER_BLOCKS_INVALID, null,
                        "章节故事流配置不合法:" + error.getMessage()));
            } else {
                issues.add(PublishCheckIssue.error(CODE_JOURNEY_RULES_INVALID, null,
                        "主题数值规则不合法:" + error.getMessage()));
            }
        }

        List<CmsTopicNode> nodes = enabledNodes(topic.getId());
        Set<String> derivedTemplateClues = new LinkedHashSet<String>();
        boolean anyBlockNeedsJourneyState = collectBlockConfigIssues(nodes, unparsableNodes, issues, derivedTemplateClues);

        Map<String, String> registered = registeredKeys(topic.getId());
        for (String clue : derivedTemplateClues) registered.putIfAbsent(clue, ThemeStateKey.TYPE_FLAG);
        Set<String> thoughtDoneTags = new LinkedHashSet<String>();
        if (refs.getParseErrors().stream().noneMatch(error ->
                TopicStateKeyRefService.KIND_JOURNEY_RULES.equals(error.getKind()))) {
            thoughtDoneTags.addAll(StateKeyRefs.declaredThoughtDoneTags(topic.getJourneyRulesJson()));
        }
        appendUnregisteredKeyIssues(refs, registered.keySet(), thoughtDoneTags, issues);
        appendRelationKeyTypeIssues(topic, registered, issues);

        // 建局条件与运行期一致:没启用节点就没有局,别对空主题喊建局。
        if (!nodes.isEmpty() && !TopicRouteState.BRANCH_GRAPH.equalsIgnoreCase(topic.getRouteMode())
                && (refs.isJourneyRulesCheckEnabled() || anyBlockNeedsJourneyState)) {
            issues.add(PublishCheckIssue.warn(CODE_LINEAR_JOURNEY_SESSION, null,
                    "该主题是线性模式且已用到旅程配置:玩家开始即建局,进度与 hp/luck 存进本局 state_json"
                            + "(与分支主题同一套);老线性主题不建局,行为不变"));
        }
        // O5c:AI 安全预检(开关默认关;关时不追加任何项、不调外部服务)。
        if (topicAiPrecheckService != null && topicAiPrecheckService.isEnabled()) {
            issues.addAll(topicAiPrecheckService.check(topic, nodes));
        }
        appendPublicationRuleIssues(topic, issues);
        if (mediaPublicationGuard != null) for (String asset : mediaPublicationGuard.missingTopicLicenses(topic)) {
            PublicationProblem problem = mediaPublicationGuard.publicationLicenseProblem(asset);
            PublishCheckIssue issue = problem != null && PublishCheckIssue.LEVEL_ERROR.equals(problem.getLevel())
                    ? PublishCheckIssue.error(problem.getCode(), problem.getNodeId(), problem.getMessage() + "：" + asset)
                    : PublishCheckIssue.warn(AppMediaProvenance.LICENSE_MISSING, null, "素材来源或授权声明待确认：" + asset);
            issues.add(issue.at("mediaAsset", AppMediaProvenance.LICENSE_MISSING));
        }
        return locate(topic.getId(), issues, nodes);
    }

    /**
     * 真实发布闸剩下的那批判据:尺寸 / 内容完整度 / 路线模式 / 剧情引用 / 运行配置引用 /
     * 叙事 NPC / 块内 sys.* nodeId / 路线图结构 / 地点合同。判定与文案直接来自
     * {@link TopicPublicationContentGuard#collectPublicationProblems} 逐条调用的既有 assert,
     * 所以「预检说没事、发布被拦」不再可能。
     *
     * <p>STATE_KEYS 一条不重复报:上面 {@code appendUnregisteredKeyIssues} 已经把未注册键按引用位置逐键列出,
     * 用的是同一个 {@link TopicStateKeyRefService#collect} 投影 —— 同一事实只出一次。
     */
    private void appendPublicationRuleIssues(CmsTopic topic, List<PublishCheckIssue> issues) {
        if (publicationContentGuard == null) {
            return;
        }
        for (PublicationProblem problem
                : publicationContentGuard.collectPublicationProblems(topic)) {
            if (PublicationProblem.CODE_STATE_KEY_UNREGISTERED.equals(problem.getCode())
                    || CODE_STATE_KEY_TYPE_INVALID.equals(problem.getCode())
                    || issues.stream().anyMatch(existing -> existing.getCode().equals(problem.getCode())
                        && java.util.Objects.equals(existing.getNodeId(), problem.getNodeId())
                        && (CODE_BLOCK_CONFIG_INVALID.equals(problem.getCode())
                            || CODE_CHAPTER_BLOCKS_INVALID.equals(problem.getCode())
                            || CODE_JOURNEY_RULES_INVALID.equals(problem.getCode())
                            || CODE_JOURNEY_STORY_INVALID.equals(problem.getCode())))) {
                continue;
            }
            PublishCheckIssue issue = PublicationProblem.LEVEL_WARN
                    .equals(problem.getLevel())
                    ? PublishCheckIssue.warn(problem.getCode(), problem.getNodeId(), problem.getMessage())
                    : PublishCheckIssue.error(problem.getCode(), problem.getNodeId(), problem.getMessage());
            issues.add(issue.at(problem.getField(), problem.getCode()));
        }
    }

    /**
     * 给每条问题补上「哪一章 / 哪一站 / 哪个块 / 哪个字段」。
     *
     * <p>作者端拿不到定位就只能猜;这里用启用节点表把 nodeId 换成章节 id + 章节标题 + 节点名,
     * 再按错误码补一条默认字段路径(问题自带 field 的不覆盖)。
     */
    private List<PublishCheckIssue> locate(Long topicId, List<PublishCheckIssue> issues, List<CmsTopicNode> nodes) {
        Map<Long, CmsTopicNode> nodeById = new LinkedHashMap<Long, CmsTopicNode>();
        for (CmsTopicNode node : nodes) {
            if (node != null && node.getId() != null) nodeById.put(node.getId(), node);
        }
        Map<Long, String> titles = chapterTitles(topicId, issues, nodeById);
        List<PublishCheckIssue> located = new ArrayList<PublishCheckIssue>(issues.size());
        for (PublishCheckIssue issue : issues) {
            CmsTopicNode node = issue.getNodeId() == null ? null : nodeById.get(issue.getNodeId());
            Long chapterId = node == null ? null : node.getChapterId();
            String chapterTitle = chapterId == null ? null : titles.get(chapterId);
            String nodeName = node == null ? null : node.getName();
            String field = issue.getField() != null ? issue.getField() : defaultField(issue);
            located.add(issue.at(chapterId, chapterTitle, nodeName, field));
        }
        return located;
    }

    /** 只在确有问题时才去查章节表(健康主题一次都不查)。 */
    private Map<Long, String> chapterTitles(Long topicId, List<PublishCheckIssue> issues,
                                            Map<Long, CmsTopicNode> nodeById) {
        Map<Long, String> titles = new LinkedHashMap<Long, String>();
        if (cmsTopicChapterMapper == null || issues.isEmpty() || topicId == null) {
            return titles;
        }
        Set<Long> chapterIds = new LinkedHashSet<Long>();
        for (PublishCheckIssue issue : issues) {
            CmsTopicNode node = issue.getNodeId() == null ? null : nodeById.get(issue.getNodeId());
            if (node != null && node.getChapterId() != null) chapterIds.add(node.getChapterId());
        }
        if (chapterIds.isEmpty()) {
            return titles;
        }
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(topicId);
        for (CmsTopicChapter chapter : chapterRows(cmsTopicChapterMapper.selectCmsTopicChapterList(probe))) {
            if (chapter != null && chapter.getId() != null && chapterIds.contains(chapter.getId())) {
                titles.put(chapter.getId(), chapter.getName());
            }
        }
        return titles;
    }

    private static List<CmsTopicChapter> chapterRows(List<CmsTopicChapter> rows) {
        return rows == null ? new ArrayList<CmsTopicChapter>() : rows;
    }

    /** 问题没自带字段时按错误码给一条最小字段路径,让作者知道去哪儿改;给不出就是 null。 */
    private static String defaultField(PublishCheckIssue issue) {
        String code = issue.getCode();
        if (code == null) return null;
        if (CODE_BLOCK_CONFIG_INVALID.equals(code)) {
            return issue.getNodeId() == null ? "nodes[].template.advancedConfigJson"
                    : "nodes[nodeId=" + issue.getNodeId() + "].template.advancedConfigJson";
        }
        if (CODE_CHAPTER_BLOCKS_INVALID.equals(code)) return "chapters[].blocksJson";
        if (CODE_JOURNEY_STORY_INVALID.equals(code)) return "journeyStoryJson";
        if (CODE_JOURNEY_RULES_INVALID.equals(code)) return "journeyRulesJson";
        if (CODE_STATE_KEY_UNREGISTERED.equals(code) || CODE_STATE_KEY_TYPE_INVALID.equals(code)) {
            return "stateKeys";
        }
        if (CODE_TOPIC_BUNDLE_INVALID.equals(code)) return "chapters/nodes";
        return null;
    }

    /**
     * 每个启用节点跑一遍块配置校验器,坏配置单独成项;返回「有节点块需要本局状态」。
     * JSON 都解析不了的节点已由收集阶段报出(同一句「不是合法 JSON」),这里不重复报。
     */
    private boolean collectBlockConfigIssues(List<CmsTopicNode> nodes, Set<Long> unparsableNodes,
                                             List<PublishCheckIssue> issues, Set<String> derivedTemplateClues) {
        boolean anyBlockNeedsJourneyState = false;
        Set<Long> nodeIdSet = new LinkedHashSet<Long>();
        for (CmsTopicNode node : nodes) {
            if (node != null && node.getId() != null) nodeIdSet.add(node.getId());
        }
        for (CmsTopicNode node : nodes) {
            Long templateId = node.getTemplateId();
            if (templateId == null || templateId <= 0L) {
                continue;
            }
            CmsMemberTemplate template = cmsMemberTemplateMapper.selectCmsMemberTemplateById(templateId);
            if (template == null || StringUtils.isEmpty(template.getAdvancedConfigJson())) {
                continue;
            }
            if (unparsableNodes.contains(node.getId())) {
                continue;
            }
            try {
                advancedGameConfigValidator.validateAndNormalize(template.getAdvancedConfigJson());
                derivedTemplateClues.addAll(StateKeyRefs.declaredTemplateClues(
                        PREVIEW_JSON.readTree(template.getAdvancedConfigJson())).keySet());
                // R22-SEC S16:NODE_COMPLETED 引用的 nodeId 归属与发布闸同一口径,试检查也提前报出。
                advancedGameConfigValidator.assertCompletedNodeIdsOwned(
                        template.getAdvancedConfigJson(), nodeIdSet);
            } catch (java.io.IOException | RuntimeException e) {
                issues.add(PublishCheckIssue.error(CODE_BLOCK_CONFIG_INVALID, node.getId(),
                        "节点「" + TopicStateKeyRefService.nodeLabel(node) + "」的高级玩法配置不合法:" + e.getMessage()));
            }
            anyBlockNeedsJourneyState = anyBlockNeedsJourneyState
                    || blockNeedsJourneyState(template.getAdvancedConfigJson());
        }
        return anyBlockNeedsJourneyState;
    }

    /** 未注册键:与发布闸同一份引用集合,一个键一条(引用位置合并),改成列表返回而不是抛。 */
    private void appendUnregisteredKeyIssues(TopicStateKeyRefService.RefCollection refs,
                                             Set<String> registered, Set<String> thoughtDoneTags, List<PublishCheckIssue> issues) {
        Map<String, Long> firstNodeByKey = new LinkedHashMap<String, Long>();
        for (Map.Entry<Long, Set<String>> entry : refs.getRefsByNode().entrySet()) {
            for (String key : entry.getValue()) {
                if (!firstNodeByKey.containsKey(key)) {
                    firstNodeByKey.put(key, entry.getKey());
                }
            }
        }
        for (Map.Entry<String, Set<String>> entry : refs.positionsByKey().entrySet()) {
            // tag.* 在正式发布时自动注册(CmsTopicServiceImpl#autoRegisterOptionTags),试检查不再报它
            // 数值规则的力竭标记不在自动注册之列(它有语义,要作者显式登记),仍要报
            if (registered.contains(entry.getKey())
                    || (entry.getKey().startsWith("tag.") && !entry.getKey().equals(refs.getExhaustTag()))
                    // 普通节点集合混合了读写引用，不豁免；仅故事/章节条件可读本主题念头完成标签。
                    || (!firstNodeByKey.containsKey(entry.getKey()) && !entry.getKey().equals(refs.getExhaustTag())
                    && thoughtDoneTags.contains(entry.getKey()))) {
                continue;
            }
            issues.add(PublishCheckIssue.error(CODE_STATE_KEY_UNREGISTERED, firstNodeByKey.get(entry.getKey()),
                    "状态键未注册:" + entry.getKey() + "(引用位置:"
                            + String.join("、", entry.getValue()) + ")"));
        }
    }

    /**
     * 块配置是否需要本局状态:与运行期建局判定共用 {@link NodeJourneySnapshot#needsJourneyState}
     * 一份口径(requires / effects 非空、check 启用、或配了选择结果 effects);坏 JSON 按「没配」算。
     */
    private boolean blockNeedsJourneyState(String advancedConfigJson) {
        return NodeJourneySnapshot.needsJourneyState(advancedConfigJson);
    }

    /**
     * 尾声 relationKey 是关系值来源,注册了还不够 —— 类型必须就是 relation(R11-C 口径,
     * 与发布闸同判据)。未注册已由通用未注册闸报出,这里只报类型不对;剧情配置解析失败
     * 也已由 parseErrors 报出,这里不重复出项。
     */
    private void appendRelationKeyTypeIssues(CmsTopic topic, Map<String, String> registered,
                                             List<PublishCheckIssue> issues) {
        Set<String> relationKeys;
        try {
            relationKeys = StateKeyRefs.collectJourneyStoryRelationKeys(topic.getJourneyStoryJson());
        } catch (RuntimeException unparsable) {
            return;
        }
        for (String relationKey : relationKeys) {
            if (registered.containsKey(relationKey)
                    && !ThemeStateKey.TYPE_RELATION.equals(registered.get(relationKey))) {
                issues.add(PublishCheckIssue.error(CODE_STATE_KEY_TYPE_INVALID, null,
                        "状态键类型不对:" + relationKey + "(来源:主题剧情配置尾声,键类型必须是 relation)"));
            }
        }
    }

    /** 已注册键 → 键类型;未注册判定与类型判定共用同一次注册表查询。 */
    private Map<String, String> registeredKeys(Long topicId) {
        Map<String, String> registered = new LinkedHashMap<String, String>();
        for (ThemeStateKey row : themeStateKeyService.listByTopic(topicId)) {
            if (row != null && !StringUtils.isEmpty(row.getStateKey())) {
                registered.put(row.getStateKey(), row.getKeyType());
            }
        }
        return registered;
    }

    private List<CmsTopicNode> enabledNodes(Long topicId) {
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        probe.setStatus(1L);
        List<CmsTopicNode> nodes = cmsTopicNodeMapper.selectCmsTopicNodeList(probe);
        return nodes == null ? new ArrayList<CmsTopicNode>() : nodes;
    }

    /**
     * 一条试检查问题:{level, code, nodeId?, message} + 定位字段。
     *
     * <p>{@code level/code/nodeId/message} 是作者端一直在消费的老形状,一个都没改;
     * {@code chapterId/chapterTitle/nodeName/blockId/field/source} 是后加的定位,老客户端忽略即可。
     */
    public static final class PublishCheckIssue {

        public static final String LEVEL_ERROR = "error";
        public static final String LEVEL_WARN = "warn";

        private final String level;
        private final String code;
        private final Long nodeId;
        private final String message;
        private final Long chapterId;
        private final String chapterTitle;
        private final String nodeName;
        private final String blockId;
        private final String field;
        private final String source;

        private PublishCheckIssue(String level, String code, Long nodeId, String message,
                                  Long chapterId, String chapterTitle, String nodeName,
                                  String blockId, String field, String source) {
            this.level = level;
            this.code = code;
            this.nodeId = nodeId;
            this.message = message;
            this.chapterId = chapterId;
            this.chapterTitle = chapterTitle;
            this.nodeName = nodeName;
            this.blockId = blockId;
            this.field = field;
            this.source = source;
        }

        public static PublishCheckIssue error(String code, Long nodeId, String message) {
            return new PublishCheckIssue(LEVEL_ERROR, code, nodeId, message, null, null, null, null, null, null);
        }

        public static PublishCheckIssue warn(String code, Long nodeId, String message) {
            return new PublishCheckIssue(LEVEL_WARN, code, nodeId, message, null, null, null, null, null, null);
        }

        /** 记录这条问题来自哪条发布规则,以及它落在作者配置的哪个字段。 */
        PublishCheckIssue at(String field, String source) {
            return new PublishCheckIssue(level, code, nodeId, message, chapterId, chapterTitle,
                    nodeName, blockId, field, source);
        }

        /** 补章节 / 节点 / 块的定位;字段只在还没有时填,不覆盖规则给出的精确路径。 */
        PublishCheckIssue at(Long chapterId, String chapterTitle, String nodeName, String field) {
            return new PublishCheckIssue(level, code, nodeId, message, chapterId, chapterTitle,
                    nodeName, blockId == null ? blockIdOf(field) : blockId, field == null ? this.field : field, source);
        }

        /** 块级定位:字段路径里带 templateId 时指到那个玩法块,否则指到节点。 */
        private static String blockIdOf(String field) {
            if (field == null) return null;
            java.util.regex.Matcher template = BLOCK_PATH.matcher(field);
            if (template.find()) return "template:" + template.group(1);
            java.util.regex.Matcher node = BLOCK_PATH_NODE.matcher(field);
            if (node.find()) return "node:" + node.group(1);
            return null;
        }

        private static final java.util.regex.Pattern BLOCK_PATH =
                java.util.regex.Pattern.compile("template\\[templateId=(\\d+)\\]");
        private static final java.util.regex.Pattern BLOCK_PATH_NODE =
                java.util.regex.Pattern.compile("nodes\\[nodeId=(\\d+)\\]");

        public String getLevel() { return level; }

        public String getCode() { return code; }

        public Long getNodeId() { return nodeId; }

        public String getMessage() { return message; }

        public Long getChapterId() { return chapterId; }

        public String getChapterTitle() { return chapterTitle; }

        public String getNodeName() { return nodeName; }

        public String getBlockId() { return blockId; }

        public String getField() { return field; }

        public String getSource() { return source; }
    }
}
