package com.chengyinhub.business.service.support;

import java.util.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.ChapterNarrativeBlocks;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.chengyinhub.business.service.support.TopicRoutePublishGate;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.business.support.OmsTicketWriteValidator;
import com.chengyinhub.common.constant.ProductType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

/** Owns publication content validation and original route snapshots; joins the original caller transaction. */
@Service
public class TopicPublicationContentGuard {
    @Autowired
    private com.chengyinhub.business.service.support.TopicContentWriteGuard contentWriteGuard;
    @Autowired
    private com.chengyinhub.business.service.support.TopicRouteOutcomeReader routeOutcomeReader;
    private final TopicRouteConfigValidator topicRouteConfigValidator = new TopicRouteConfigValidator();
    private final TopicRoutePublishGate topicRoutePublishGate = new TopicRoutePublishGate();
    private static final ObjectMapper ROUTE_JSON = new ObjectMapper();
    @Autowired
    private CmsTopicMapper cmsTopicMapper;
    @Autowired
    private CmsTopicChapterMapper cmsTopicChapterMapper;
    @Autowired
    private CmsTopicNodeMapper cmsTopicNodeMapper;
    @Autowired
    private com.chengyinhub.business.mapper.CmsMemberTemplateMapper cmsMemberTemplateMapper;
    @Autowired
    private com.chengyinhub.business.service.support.ThemeStateKeyService themeStateKeyService;
    @Autowired
    private TopicStateKeyRefService topicStateKeyRefService;
    @Autowired
    private com.chengyinhub.business.mapper.NpcProfileMapper npcProfileMapper;
    private final com.chengyinhub.business.service.support.JourneyStoryValidator journeyStoryValidator =
            new com.chengyinhub.business.service.support.JourneyStoryValidator();
    private final com.chengyinhub.business.service.support.AdvancedGameConfigValidator advancedGameConfigValidator =
            new com.chengyinhub.business.service.support.AdvancedGameConfigValidator();
    @Autowired
    private OmsTicketMapper omsTicketMapper;
    @Autowired
    private MerchantChapterOfferMapper merchantChapterOfferMapper;
    @Autowired
    private com.chengyinhub.business.service.ITopicRouteRuntimeService topicRouteRuntimeService;
    private static final List<String> NARRATIVE_NPC_SECTIONS = Arrays.asList("enter", "outcome");
    private static <T> List<T> nullToEmpty(List<T> rows) { return rows == null ? Collections.<T>emptyList() : rows; }

    public void assertOrdinarySupplyRestored(CmsTopic topic) {
        String reason = ordinarySupplyResumeBlockReason(topic, true);
        if (reason != null) throw new IllegalStateException(reason);
    }

    public String ordinarySupplyResumeBlockReason(CmsTopic topic, boolean explicitRestore) {
        if (topic == null) return "主题不存在";
        if (!Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())
                || (topic.getCircleThemeCode() != null && !topic.getCircleThemeCode().trim().isEmpty())) return null;
        List<MerchantChapterOffer> history;
        try {
            // quota 已持主办账户锁；最终章核销持 offer 后会锁玩家账户。
            // NOWAIT 不跳行：无法取得完整当前读就拒复上，避免主办同时是玩家时反向等待。
            history = merchantChapterOfferMapper.selectByTopicIncludingInactiveForUpdate(topic.getId());
        } catch (RuntimeException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.sql.SQLException && ((java.sql.SQLException) cause).getErrorCode() == 3572) {
                    return "供给正在核销或调整，请稍后恢复报名";
                }
            }
            throw ex;
        }
        if (history == null || history.isEmpty()) return null; // 空招商章节允许发布。
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(topic.getId());
        List<CmsTopicChapter> chapters = cmsTopicChapterMapper.selectCmsTopicChapterList(probe);
        java.util.Set<Long> required = new java.util.HashSet<>();
        if (chapters != null) {
            for (CmsTopicChapter chapter : chapters) {
                if (!Integer.valueOf(0).equals(chapter.getRequired())) required.add(chapter.getId());
            }
        }
        java.util.Map<Long, MerchantChapterOffer> replacements = new java.util.HashMap<>();
        for (MerchantChapterOffer offer : history) {
            if (offer.getReplacementOfOfferId() != null) replacements.put(offer.getReplacementOfOfferId(), offer);
        }
        Date now = DateUtils.getNowDate();
        for (MerchantChapterOffer original : history) {
            if ((!Boolean.TRUE.equals(original.getWithdrawRequired()) && !required.contains(original.getChapterId()))
                    || !Integer.valueOf(0).equals(original.getStatus()) || original.getWithdrawReason() == null
                    || original.getWithdrawReason().trim().isEmpty()) continue;
            if (!explicitRestore) return "商家退出后的报名恢复须由主办显式操作，审核不会自动恢复";
            java.util.Set<Long> visited = new java.util.HashSet<>();
            MerchantChapterOffer cursor = original;
            while (cursor != null && visited.add(cursor.getId())) {
                MerchantChapterOffer next = replacements.get(cursor.getId());
                if (next == null) break;
                try {
                    com.chengyinhub.business.support.OrdinarySupplyReplacementPolicy.assertCompatible(cursor, next);
                } catch (IllegalStateException ex) {
                    return ex.getMessage();
                }
                cursor = next;
                if (Integer.valueOf(1).equals(cursor.getStatus())
                        && (cursor.getValidEnd() == null || cursor.getValidEnd().after(now))) break;
            }
            if (cursor == original || cursor == null || !Integer.valueOf(1).equals(cursor.getStatus())
                    || (cursor.getValidEnd() != null && !cursor.getValidEnd().after(now))) {
                return "必选章节有商家退出，须替补商家确认有效供给后由主办恢复；无法履约请取消退款";
            }
        }
        return null;
    }

    public void assertTopicSizeWithinLimits(Long topicId) {
        CmsTopicChapter chapterQuery = new CmsTopicChapter();
        chapterQuery.setTopicId(topicId);
        CmsTopicNode nodeQuery = new CmsTopicNode();
        nodeQuery.setTopicId(topicId);
        String violation = com.chengyinhub.business.service.support.TopicSizeLimits.violation(
                nullToEmpty(cmsTopicChapterMapper.selectCmsTopicChapterList(chapterQuery)).size(),
                nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(nodeQuery)).size());
        if (violation != null) {
            throw new IllegalStateException("发布前请先调整:" + violation);
        }
    }

    public void assertPublishReady(CmsTopic topic) {
        List<String> issues = new ArrayList<String>();
        Long topicId = topic == null ? null : topic.getId();
        if (StringUtils.isBlank(topic == null ? null : topic.getName())) {
            issues.add("主题名称不能为空");
        }
        if (topicId != null && "ai_simple".equalsIgnoreCase(topic.getPublishMode())) {
            try {
                assertAiSimpleNodeLocations(chaptersFromNodeRows(topicId), true);
            } catch (IllegalArgumentException | IllegalStateException e) {
                issues.add(e.getMessage());
            }
        }
        if (topicId != null && ProductType.FREE_EXPLORE == (topic.getProductType() == null ? -1 : topic.getProductType())
                && topic.getRecruitDeadline() == null) {
            issues.add("自由探索主题缺少招募截止时间");
        }
        if (topicId != null) {
            OmsTicket ticketQuery = new OmsTicket();
            ticketQuery.setOwnerType(1L);
            ticketQuery.setOwnerId(topicId);
            ticketQuery.setDelFlag(0);
            for (OmsTicket ticket : nullToEmpty(omsTicketMapper.selectOmsTicketList(ticketQuery))) {
                try {
                    OmsTicketWriteValidator.validateNewTicket(ticket);
                } catch (ServiceException e) {
                    issues.add("票种「" + (StringUtils.isEmpty(ticket.getName()) ? "未命名" : ticket.getName())
                            + "」" + e.getMessage());
                }
            }
        }
        if (issues.isEmpty()) {
            return;
        }
        String message = "发布前请先补全:" + String.join(";", issues);
        if (message.length() > 110) {
            message = message.substring(0, 110) + "…";
        }
        throw new IllegalStateException(message);
    }

    private List<ChapterDTO> chaptersFromNodeRows(Long topicId) {
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        probe.setStatus(1L);
        List<NodeDTO> nodes = new ArrayList<NodeDTO>();
        for (CmsTopicNode row : nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(probe))) {
            NodeDTO node = new NodeDTO();
            node.setLatitude(row.getLatitude());
            node.setLongitude(row.getLongitude());
            nodes.add(node);
        }
        ChapterDTO chapter = new ChapterDTO();
        chapter.setNodes(nodes);
        return Collections.singletonList(chapter);
    }





    public void assertThoughtRefsResolvable(CmsTopic topic) {
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

    @SafeVarargs
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

    public void assertStateKeysRegistered(CmsTopic topic) {
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
            TopicStateKeyRefService.RefCollection refs = topicStateKeyRefService.collect(topic);
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

    public void assertBlockNarrativeNpcReferences(CmsTopic topic) {
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

    public void assertBlockSysVarReferences(CmsTopic topic) {
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

    public void assertThemeRuntimeReferences(CmsTopic topic) {
        com.fasterxml.jackson.databind.node.ObjectNode config = com.chengyinhub.business.service.support.ThemeRuntimeConfig.parse(topic.getThemeRuntimeConfigJson());
        try {
            com.chengyinhub.business.service.support.ThemeRuntimeConfig.assertItemReferences(ROUTE_JSON.readTree(topic.getJourneyStoryJson()==null?"{}":topic.getJourneyStoryJson()),config);
            com.chengyinhub.business.service.support.ThemeRuntimeConfig.assertItemReferences(ROUTE_JSON.readTree(topic.getRouteGraphJson()==null?"{}":topic.getRouteGraphJson()),config);
        } catch(java.io.IOException invalid){throw new IllegalArgumentException("主题条件无法读取",invalid);}
        CmsTopicNode probe = new CmsTopicNode(); probe.setTopicId(topic.getId());
        Set<Long> nodeIds = new LinkedHashSet<Long>();
        for (CmsTopicNode node : nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(probe))) {
            nodeIds.add(node.getId());
            if(cmsMemberTemplateMapper!=null && node.getTemplateId()!=null) {CmsMemberTemplate template=cmsMemberTemplateMapper.selectCmsMemberTemplateById(node.getTemplateId());if(template!=null && StringUtils.isNotEmpty(template.getAdvancedConfigJson()))try {com.chengyinhub.business.service.support.ThemeRuntimeConfig.assertItemReferences(ROUTE_JSON.readTree(template.getAdvancedConfigJson()),config);}catch(java.io.IOException invalid){throw new IllegalArgumentException("节点条件无法读取",invalid);}}
        }
        if(config==null)return;
        Set<String> keys = new LinkedHashSet<String>();
        for (ThemeStateKey key : nullToEmpty(themeStateKeyService.listByTopic(topic.getId()))) keys.add(key.getStateKey());
        com.chengyinhub.business.domain.JourneyRules rules=com.chengyinhub.business.domain.JourneyRules.parse(topic.getJourneyRulesJson());
        boolean valuesEnabled=config.has("gameplayRuntime") ? rules.hasRuntimeState() : rules.isCheckEnabled();
        com.chengyinhub.business.service.support.ThemeRuntimeConfig.assertReferences(config, nodeIds, keys, rules);
        for(JsonNode scene:config.path("scenes")) if(scene.has("journeyStory")) {
            CmsTopic sceneTopic=new CmsTopic();sceneTopic.setId(topic.getId());sceneTopic.setJourneyStoryJson(scene.path("journeyStory").toString());assertJourneyStoryReferences(sceneTopic);
        }
        if(config.path("chapters").size()>0) {
            CmsTopicChapter chapterProbe=new CmsTopicChapter();chapterProbe.setTopicId(topic.getId());
            List<CmsTopicChapter> chapterRows=nullToEmpty(cmsTopicChapterMapper.selectCmsTopicChapterList(chapterProbe));
            for(JsonNode ch:config.path("chapters")) {
                if(ch.path("index").asInt()>=chapterRows.size())throw new IllegalArgumentException("背包配置引用未知章节");
                if(ch.path("hpCost").asInt()>0 && (!valuesEnabled || !rules.getHp().isEnabled()))throw new IllegalArgumentException("请先开启生命值");
                if(ch.path("luckCost").asInt()>0 && (!valuesEnabled || !rules.getLuck().isEnabled()))throw new IllegalArgumentException("请先开启幸运值");
            }
        }
        for(JsonNode item:config.path("items")) if("ITEM".equals(item.path("kind").asText()) && item.has("stat") && (!valuesEnabled || ("HP".equals(item.path("stat").asText()) ? !rules.getHp().isEnabled() : !rules.getLuck().isEnabled())))
            throw new IllegalArgumentException("物品对应的生命值或幸运值尚未开启");
    }

    public void assertJourneyStoryReferences(CmsTopic topic) {
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

    public boolean requiresFullRuntimeSnapshot(CmsTopic topic) {
        try {
            JsonNode rules = ROUTE_JSON.readTree(topic.getJourneyRulesJson() == null ? "{}" : topic.getJourneyRulesJson());
            JsonNode config = ROUTE_JSON.readTree(topic.getThemeRuntimeConfigJson() == null ? "{}" : topic.getThemeRuntimeConfigJson());
            if (rules.path("attributes").size() > 0 || rules.path("statLinks").size() > 0 || config.has("gameplayRuntime") || config.has("hpName") || config.has("luckName")) return true;
            for (JsonNode item : config.path("items")) if (item.has("statKey") || item.has("slotCost")) return true;
            for (JsonNode chapter : config.path("chapters")) if (chapter.path("statCosts").size() > 0) return true;
            return false;
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("运行配置无法读取", invalid); }
    }

    public String normalizedBranchRouteForPublish(CmsTopic topic) {
        if(topicRouteRuntimeService!=null) {String compiled=topicRouteRuntimeService.compileTemplateRules(topic);if(compiled!=null)topic.setThemeRuntimeConfigJson(compiled);}
        boolean fullSnapshot = requiresFullRuntimeSnapshot(topic);
        if (!"BRANCH_GRAPH".equalsIgnoreCase(topic.getRouteMode())) {
            return fullSnapshot ? topicRouteRuntimeService.freezePublication(topic, null) : null;
        }
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topic.getId());
        probe.setStatus(1L);
        Set<Long> nodeIds = new LinkedHashSet<Long>();
        for (CmsTopicNode node : nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(probe))) {
            nodeIds.add(node.getId());
        }
        String normalized = topicRouteConfigValidator.validateAndNormalize(
                topic.getRouteGraphJson(), nodeIds, declaredRouteOutcomes(topic.getId()));
        topicRoutePublishGate.assertPublishable(normalized);
        return fullSnapshot ? topicRouteRuntimeService.freezePublication(topic, normalized) : normalized;
    }

    public List<NodeDTO> extractAllNodes(List<ChapterDTO> chapters) {
        List<NodeDTO> allNodes = new ArrayList<>();
        if (CollectionUtils.isEmpty(chapters)) {
            return allNodes;
        }

        for (ChapterDTO chapter : chapters) {
            if (chapter != null && !CollectionUtils.isEmpty(chapter.getNodes())) {
                allNodes.addAll(chapter.getNodes());
            }
        }
        return allNodes;
    }

    public void assertAiSimpleNodeLocations(List<ChapterDTO> chapters, boolean requireConfirmed) {
        List<NodeDTO> nodes = extractAllNodes(chapters);
        if (requireConfirmed && nodes.isEmpty()) {
            throw new IllegalArgumentException("简单 AI 主题至少需要一个已确认地点");
        }
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node == null) {
                if (requireConfirmed) throw new IllegalArgumentException("第" + (i + 1) + "个地点无效");
                continue;
            }
            if (!requireConfirmed && StringUtils.isBlank(node.getLatitude()) && StringUtils.isBlank(node.getLongitude())) {
                continue; // 草稿:这个地点的坐标还没确认,允许空着
            }
            validateCoordinate(node.getLatitude(), -90d, 90d, "纬度", i);
            validateCoordinate(node.getLongitude(), -180d, 180d, "经度", i);
        }
    }

    private void validateCoordinate(String raw, double min, double max, String label, int index) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("第" + (index + 1) + "个地点尚未在地图确认");
        }
        try {
            double value = Double.parseDouble(raw);
            if (Double.isNaN(value) || Double.isInfinite(value) || value < min || value > max) {
                throw new IllegalArgumentException("第" + (index + 1) + "个地点" + label + "无效");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("第" + (index + 1) + "个地点尚未在地图确认");
        }
    }

    private Map<Long, Set<String>> declaredRouteOutcomes(Long topicId) {
        return routeOutcomeReader.declaredRouteOutcomes(topicId);
    }

    public void assertBranchGraphOnlyForOrienteering(String routeMode, Integer productType) {
        contentWriteGuard.assertBranchGraphOnlyForOrienteering(routeMode, productType);
    }
}
