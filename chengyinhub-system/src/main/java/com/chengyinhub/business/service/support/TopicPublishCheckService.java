package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
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

    public static final String CODE_TOPIC_BUNDLE_INVALID = "TOPIC_BUNDLE_INVALID";
    public static final String CODE_STATE_KEY_UNREGISTERED = "STATE_KEY_UNREGISTERED";
    /** 键注册了但类型不对(目前只有尾声 relationKey 会走这条:类型必须是 relation)。 */
    public static final String CODE_STATE_KEY_TYPE_INVALID = "STATE_KEY_TYPE_INVALID";
    public static final String CODE_BLOCK_CONFIG_INVALID = "BLOCK_CONFIG_INVALID";
    public static final String CODE_JOURNEY_RULES_INVALID = "JOURNEY_RULES_INVALID";
    public static final String CODE_JOURNEY_STORY_INVALID = "JOURNEY_STORY_INVALID";
    /** R22-SEC S14:章节故事流 blocks 解析失败(与节点块同样是发布期会拦的错)。 */
    public static final String CODE_CHAPTER_BLOCKS_INVALID = "CHAPTER_BLOCKS_INVALID";
    public static final String CODE_LINEAR_JOURNEY_SESSION = "LINEAR_JOURNEY_SESSION";

    private final CmsTopicNodeMapper cmsTopicNodeMapper;
    private final CmsMemberTemplateMapper cmsMemberTemplateMapper;
    private final ThemeStateKeyService themeStateKeyService;
    private final TopicStateKeyRefService topicStateKeyRefService;
    private final TopicBundleReviewService topicBundleReviewService;
    /** O5c:可选注入,未注入(老测试直接 new)时等同开关关闭。 */
    private TopicAiPrecheckService topicAiPrecheckService;
    /** 与 TemplatePublishServiceImpl 同一用法:无状态,直接 new。 */
    private final AdvancedGameConfigValidator advancedGameConfigValidator = new AdvancedGameConfigValidator();

    public TopicPublishCheckService(CmsTopicNodeMapper cmsTopicNodeMapper,
                                    CmsMemberTemplateMapper cmsMemberTemplateMapper,
                                    ThemeStateKeyService themeStateKeyService,
                                    TopicStateKeyRefService topicStateKeyRefService,
                                    TopicBundleReviewService topicBundleReviewService) {
        this.cmsTopicNodeMapper = cmsTopicNodeMapper;
        this.cmsMemberTemplateMapper = cmsMemberTemplateMapper;
        this.themeStateKeyService = themeStateKeyService;
        this.topicStateKeyRefService = topicStateKeyRefService;
        this.topicBundleReviewService = topicBundleReviewService;
    }

    @Autowired(required = false)
    public void setTopicAiPrecheckService(TopicAiPrecheckService topicAiPrecheckService) {
        this.topicAiPrecheckService = topicAiPrecheckService;
    }

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
        return issues;
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

    /** 一条试检查问题:{level, code, nodeId?, message}。 */
    public static final class PublishCheckIssue {

        public static final String LEVEL_ERROR = "error";
        public static final String LEVEL_WARN = "warn";

        private final String level;
        private final String code;
        private final Long nodeId;
        private final String message;

        private PublishCheckIssue(String level, String code, Long nodeId, String message) {
            this.level = level;
            this.code = code;
            this.nodeId = nodeId;
            this.message = message;
        }

        public static PublishCheckIssue error(String code, Long nodeId, String message) {
            return new PublishCheckIssue(LEVEL_ERROR, code, nodeId, message);
        }

        public static PublishCheckIssue warn(String code, Long nodeId, String message) {
            return new PublishCheckIssue(LEVEL_WARN, code, nodeId, message);
        }

        public String getLevel() { return level; }

        public String getCode() { return code; }

        public Long getNodeId() { return nodeId; }

        public String getMessage() { return message; }
    }
}
