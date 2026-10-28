package com.chengyinhub.business.service.support;

import java.util.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.NpcProfileService;
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
    private com.chengyinhub.business.service.ICouponRewardAuthorizationService couponRewardAuthorizationService;
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
    @Autowired
    private MerchantNpcIdentityDirectory merchantNpcIdentityDirectory;
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
        // Both manual restore and a new moderation decision may make an existing publication public again.
        if (topic != null) assertTopicSizeWithinLimits(topic.getId());
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
        contentWriteGuard.assertTopicSizeWithinLimits(topicId);
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
    private final List<ThemeStateKey> optionTagDeclarations(java.util.Collection<String>... sources) {
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
        return tags;
    }

    private List<ThemeStateKey> optionTagDeclarations(CmsTopic topic) {
        TopicStateKeyRefService.RefCollection refs = topicStateKeyRefService == null
                ? new TopicStateKeyRefService.RefCollection() : topicStateKeyRefService.collect(topic);
        return optionTagDeclarations(topic, refs);
    }

    private List<ThemeStateKey> optionTagDeclarations(CmsTopic topic, TopicStateKeyRefService.RefCollection refs) {
        assertStateKeyReferencesParsed(topic, refs);
        Set<String> nodeKeys = new LinkedHashSet<String>();
        for (Set<String> keys : refs.getRefsByNode().values()) nodeKeys.addAll(keys);
        return optionTagDeclarations(nodeKeys, refs.getStoryKeys(), refs.getChapterKeys(), journeyStoryStateKeyRefs(topic).keySet());
    }

    private void autoRegisterOptionTags(Long topicId, List<ThemeStateKey> declarations, List<ThemeStateKey> tags) {
        if (tags.isEmpty()) return;
        Set<String> existing = new LinkedHashSet<String>();
        for (ThemeStateKey row : declarations) {
            if (row != null) existing.add(row.getStateKey());
        }
        List<ThemeStateKey> fresh = new ArrayList<ThemeStateKey>();
        for (ThemeStateKey row : tags) if (!existing.contains(row.getStateKey())) fresh.add(row);
        if (!fresh.isEmpty()) themeStateKeyService.register(topicId, fresh);
    }

    /** Phase before the caller captures the final declaration projection for hash/validation/freeze.
     * Never called by preview. The existing key service owns topic -> key locks and registration. */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, rollbackFor = Exception.class)
    public void deriveOptionTagsForPublication(CmsTopic topic) {
        requirePublicationWriteTransaction(topic);
        if (themeStateKeyService == null) throw new IllegalStateException("发布状态键声明服务缺失");
        if (topicStateKeyRefService == null) throw new IllegalStateException("发布状态键引用服务缺失");
        List<ThemeStateKey> current = publicationDeclarations(topic,
                themeStateKeyService.listCurrentDeclarations(topic.getId()));
        autoRegisterOptionTags(topic.getId(), current,
                optionTagDeclarations(topic, topicStateKeyRefService.collectForPublication(topic)));
    }

    /** Ordinary read-only check. Formal publication must use the captured-declarations overload. */
    public void assertStateKeysRegistered(CmsTopic topic) {
        if (themeStateKeyService == null) {
            // 手工 new 的旧单测没注入本类依赖(Spring 运行态必注入,缺失会是启动期错误)。
            return;
        }
        assertStateKeysRegisteredUsing(topic, () -> themeStateKeyService.listByTopic(topic.getId()), true);
    }

    /** Internal publication port, never a client-provided registry. Caller supplies the same locked,
     * stable author projection used by the review hash and compiler; this method never re-reads or derives. */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, rollbackFor = Exception.class)
    public void assertStateKeysRegistered(CmsTopic topic, List<ThemeStateKey> declarations) {
        requirePublicationWriteTransaction(topic);
        List<ThemeStateKey> captured = publicationDeclarations(topic, declarations);
        if (topicStateKeyRefService == null) throw new IllegalStateException("发布状态键引用服务缺失");
        assertStateKeysRegisteredUsing(topic, () -> captured, false);
    }

    private void assertStateKeysRegisteredUsing(CmsTopic topic,
            java.util.function.Supplier<List<ThemeStateKey>> declarations, boolean preview) {
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
            TopicStateKeyRefService.RefCollection refs = preview ? topicStateKeyRefService.collect(topic)
                    : topicStateKeyRefService.collectForPublication(topic);
            assertStateKeyReferencesParsed(topic, refs);
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
        Set<String> registered = new LinkedHashSet<String>();
        Map<String, String> registeredTypes = new LinkedHashMap<String, String>();
        for (ThemeStateKey row : declarations.get()) {
            if (row != null && !StringUtils.isEmpty(row.getStateKey())) {
                registered.add(row.getStateKey());
                registeredTypes.put(row.getStateKey(), row.getKeyType());
            }
        }
        if (preview) {
            // The original option-tag mechanism, projected only in memory; no publishing as a preview side effect.
            for (ThemeStateKey tag : optionTagDeclarations(refNodeNames.keySet(), storyKeys, chapterKeys, storyRefs.keySet())) {
                registered.add(tag.getStateKey());
                registeredTypes.putIfAbsent(tag.getStateKey(), tag.getKeyType());
            }
            // Pure original compiler projection, never a new declaration or an arbitrary clue exemption.
            for (String clue : previewTemplateClueKeys(topic)) {
                registered.add(clue); registeredTypes.putIfAbsent(clue, ThemeStateKey.TYPE_FLAG);
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

    private Set<String> previewTemplateClueKeys(CmsTopic topic) {
        Set<String> keys = new LinkedHashSet<String>();
        collectPreviewTemplateClueKeys(topic, topic.getThemeRuntimeConfigJson(), "themeRuntimeConfigJson", keys);
        if (cmsTopicNodeMapper == null || cmsMemberTemplateMapper == null) return keys;
        CmsTopicNode probe = new CmsTopicNode(); probe.setTopicId(topic.getId());
        Set<Long> ids = new LinkedHashSet<Long>();
        for (CmsTopicNode node : nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(probe)))
            if (node != null && node.getTemplateId() != null && node.getTemplateId() > 0) ids.add(node.getTemplateId());
        if (ids.isEmpty()) return keys;
        for (CmsMemberTemplate template : nullToEmpty(cmsMemberTemplateMapper.selectCmsMemberTemplateByIds(new ArrayList<Long>(ids)))) {
            if (template != null) collectPreviewTemplateClueKeys(topic, template.getAdvancedConfigJson(),
                    "templates[templateId=" + template.getId() + "].advancedConfigJson", keys);
        }
        return keys;
    }

    private void collectPreviewTemplateClueKeys(CmsTopic topic, String raw, String path, Set<String> keys) {
        if (StringUtils.isBlank(raw)) return;
        try { keys.addAll(com.chengyinhub.business.util.StateKeyRefs.declaredTemplateClues(ROUTE_JSON.readTree(raw)).keySet()); }
        catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("主题条件无法读取" + publicationLocation(topic, path), invalid);
        }
    }

    private void assertStateKeyReferencesParsed(CmsTopic topic, TopicStateKeyRefService.RefCollection refs) {
        if (refs == null) throw new IllegalStateException("发布状态键引用读取未知");
        if (refs.getParseErrors().isEmpty()) return;
        TopicStateKeyRefService.ParseError error = refs.getParseErrors().get(0);
        String path = error.getNodeId() != null
                ? "nodes[nodeId=" + error.getNodeId() + "].template.advancedConfigJson"
                : TopicStateKeyRefService.KIND_JOURNEY_RULES.equals(error.getKind()) ? "journeyRulesJson"
                : TopicStateKeyRefService.KIND_JOURNEY_STORY.equals(error.getKind()) ? "journeyStoryJson"
                : "chapters.blocksJson";
        throw new ServiceException(TopicStateKeyRefService.parseErrorMessage(error)
                + publicationLocation(topic, path));
    }

    private void requirePublicationWriteTransaction(CmsTopic topic) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("发布状态键声明需要原发布写事务");
        if (topic == null || topic.getId() == null || topic.getId() <= 0)
            throw new IllegalStateException("发布状态键声明主题无效");
    }

    private List<ThemeStateKey> publicationDeclarations(CmsTopic topic, List<ThemeStateKey> declarations) {
        if (declarations == null) throw new IllegalStateException("发布状态键声明读取未知");
        Set<String> names = new LinkedHashSet<String>();
        for (ThemeStateKey row : declarations) {
            if (row == null || !topic.getId().equals(row.getTopicId()) || StringUtils.isBlank(row.getStateKey())
                    || !names.add(row.getStateKey())) throw new IllegalStateException("发布状态键声明归属或身份不一致");
        }
        // Do not mutate caller-owned declaration beans or derive runtime values. G owns the stable author copy.
        return List.copyOf(declarations);
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
        return journeyStoryAvailableNpcIds(topicId, null);
    }

    private Set<Long> journeyStoryAvailableNpcIds(Long topicId, TopicStateKeyRefService.CurrentContent current) {
        if (npcProfileMapper == null) {
            return null;
        }
        Set<Long> ids = new LinkedHashSet<Long>();
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        probe.setStatus(1L);
        List<CmsTopicNode> scopedNodes = current == null
                ? nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(probe)) : current.activeNodes();
        for (CmsTopicNode node : scopedNodes) {
            for (NpcProfile profile : nullToEmpty(npcProfileMapper.selectEnabledByScope(
                    NpcProfileService.SCOPE_NODE, node.getId()))) {
                if (profile.getId() != null) ids.add(profile.getId());
            }
            // Scope 2 belongs to mms_merchant.id, never the node's merchant owner member ID (M-28).
            Long merchantId = node.getMerchantMemberId() == null ? null
                    : merchantNpcIdentityDirectory.availableMerchantIdForMember(node.getMerchantMemberId());
            if (merchantId != null) {
                for (NpcProfile profile : nullToEmpty(npcProfileMapper.selectEnabledByScope(2, merchantId))) {
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
                    missing.add("nodeId=" + referenced + "(节点:" + node.getName() + ")"
                            + publicationLocation(topic, "nodes[nodeId=" + node.getId()
                                + "].template[templateId=" + node.getTemplateId() + "].advancedConfigJson"));
                }
            }
            // R22-SEC S16:NODE_COMPLETED 也是节点引用 —— nodeId 写错的条件永远不成立,发布期必须拦。
            try {
                advancedGameConfigValidator.assertCompletedNodeIdsOwned(raw, nodeIds);
            } catch (RuntimeException e) {
                foreignCompleted.add("节点:" + node.getName() + " — " + e.getMessage()
                        + publicationLocation(topic, "nodes[nodeId=" + node.getId()
                            + "].template[templateId=" + node.getTemplateId() + "].advancedConfigJson"));
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
        assertThemeRuntimeReferencesUsing(topic, () -> themeStateKeyService.listByTopic(topic.getId()), true, null);
    }

    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, rollbackFor = Exception.class)
    public void assertThemeRuntimeReferences(CmsTopic topic, List<ThemeStateKey> declarations) {
        requirePublicationWriteTransaction(topic);
        List<ThemeStateKey> captured = publicationDeclarations(topic, declarations);
        if (topicStateKeyRefService == null) throw new IllegalStateException("发布状态键引用服务缺失");
        TopicStateKeyRefService.CurrentContent current = topicStateKeyRefService.currentForPublication(topic);
        if (current == null || current.topic() == null || !topic.getId().equals(current.topic().getId()))
            throw new IllegalStateException("发布引用投影主题不一致");
        // Also covers ordinary questions with no theme runtime config. Use the same locked templates,
        // and each template's proven owner (which may be a merchant rather than the topic creator).
        for (CmsMemberTemplate template : current.templates().values()) {
            Set<Long> coupons = new LinkedHashSet<>();
            if (template.getCouponId() != null && template.getCouponId() > 0L) coupons.add(template.getCouponId());
            coupons.addAll(new RewardRulesValidator().couponIds(template.getRewardRulesJson()));
            for (Long coupon : coupons) couponRewardAuthorizationService.assertOwnedRewardCoupon(coupon, template.getMemberId());
        }
        assertThemeRuntimeReferencesUsing(current.topic(), () -> captured, false, current);
    }

    private void assertThemeRuntimeReferencesUsing(CmsTopic topic,
            java.util.function.Supplier<List<ThemeStateKey>> declarations, boolean preview,
            TopicStateKeyRefService.CurrentContent current) {
        com.fasterxml.jackson.databind.node.ObjectNode config;
        try { config = ThemeRuntimeConfig.parse(topic.getThemeRuntimeConfigJson()); }
        catch (IllegalArgumentException invalid) { throw located(topic, "themeRuntimeConfigJson", invalid); }
        assertItemReferencesAt(topic, topic.getJourneyStoryJson(), config, "journeyStoryJson");
        assertItemReferencesAt(topic, topic.getRouteGraphJson(), config, "routeGraphJson");
        CmsTopicNode probe = new CmsTopicNode(); probe.setTopicId(topic.getId());
        Set<Long> nodeIds = new LinkedHashSet<Long>();
        Set<String> previewClues = new LinkedHashSet<String>();
        if (preview) previewClues.addAll(com.chengyinhub.business.util.StateKeyRefs.declaredTemplateClues(config).keySet());
        List<CmsTopicNode> scopedNodes = current == null
                ? nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(probe)) : current.nodes();
        for (CmsTopicNode node : scopedNodes) {
            nodeIds.add(node.getId());
            if ((current != null || cmsMemberTemplateMapper != null) && node.getTemplateId() != null) {
                CmsMemberTemplate template = current == null
                        ? cmsMemberTemplateMapper.selectCmsMemberTemplateById(node.getTemplateId())
                        : current.templates().get(node.getTemplateId());
                if (template != null && StringUtils.isNotEmpty(template.getAdvancedConfigJson())) {
                    JsonNode declared = assertItemReferencesAt(topic, template.getAdvancedConfigJson(), config,
                            "nodes[nodeId=" + node.getId() + "].template[templateId=" + template.getId() + "].advancedConfigJson");
                    if (preview) previewClues.addAll(com.chengyinhub.business.util.StateKeyRefs.declaredTemplateClues(declared).keySet());
                }
            }
        }
        if (config == null) return;
        Set<String> keys = new LinkedHashSet<String>();
        for (ThemeStateKey key : nullToEmpty(declarations.get())) keys.add(key.getStateKey());
        if (preview) {
            for (ThemeStateKey tag : optionTagDeclarations(topic)) keys.add(tag.getStateKey());
            keys.addAll(previewClues);
        }
        com.chengyinhub.business.domain.JourneyRules rules;
        try { rules = com.chengyinhub.business.domain.JourneyRules.parse(topic.getJourneyRulesJson()); }
        catch (IllegalArgumentException invalid) { throw located(topic, "journeyRulesJson", invalid); }
        boolean valuesEnabled = config.has("gameplayRuntime") ? rules.hasRuntimeState() : rules.isCheckEnabled();
        try { ThemeRuntimeConfig.assertReferences(config, nodeIds, keys, rules); }
        catch (IllegalArgumentException invalid) { throw located(topic, "themeRuntimeConfigJson", invalid); }
        JsonNode scenes = config.path("scenes");
        for (int index = 0; index < scenes.size(); index++) {
            JsonNode scene = scenes.get(index);
            if (scene.has("journeyStory")) {
                CmsTopic sceneTopic = new CmsTopic(); sceneTopic.setId(topic.getId());
                sceneTopic.setJourneyStoryJson(scene.path("journeyStory").toString());
                assertJourneyStoryReferences(sceneTopic, "themeRuntimeConfigJson.scenes[" + index + "].journeyStory", current);
            }
        }
        if (config.path("chapters").size() > 0) {
            CmsTopicChapter chapterProbe = new CmsTopicChapter(); chapterProbe.setTopicId(topic.getId());
            List<CmsTopicChapter> chapterRows = current == null
                    ? nullToEmpty(cmsTopicChapterMapper.selectCmsTopicChapterList(chapterProbe)) : current.chapters();
            for (int index = 0; index < config.path("chapters").size(); index++) {
                JsonNode chapter = config.path("chapters").get(index);
                String path = "themeRuntimeConfigJson.chapters[" + index + "]";
                if (chapter.path("index").asInt() >= chapterRows.size())
                    throw new IllegalArgumentException("背包配置引用未知章节" + publicationLocation(topic, path + ".index"));
                if (chapter.path("hpCost").asInt() > 0 && (!valuesEnabled || !rules.getHp().isEnabled()))
                    throw new IllegalArgumentException("请先开启生命值" + publicationLocation(topic, path + ".hpCost"));
                if (chapter.path("luckCost").asInt() > 0 && (!valuesEnabled || !rules.getLuck().isEnabled()))
                    throw new IllegalArgumentException("请先开启幸运值" + publicationLocation(topic, path + ".luckCost"));
            }
        }
        for (int index = 0; index < config.path("items").size(); index++) {
            JsonNode item = config.path("items").get(index);
            if ("ITEM".equals(item.path("kind").asText()) && item.has("stat")
                    && (!valuesEnabled || ("HP".equals(item.path("stat").asText()) ? !rules.getHp().isEnabled() : !rules.getLuck().isEnabled())))
                throw new IllegalArgumentException("物品对应的生命值或幸运值尚未开启"
                        + publicationLocation(topic, "themeRuntimeConfigJson.items[" + index + "].stat"));
        }
    }

    private JsonNode assertItemReferencesAt(CmsTopic topic, String raw,
            com.fasterxml.jackson.databind.node.ObjectNode config, String path) {
        try {
            JsonNode content = ROUTE_JSON.readTree(raw == null ? "{}" : raw);
            ThemeRuntimeConfig.assertItemReferences(content, config);
            return content;
        }
        catch (java.io.IOException invalid) {
            throw new IllegalArgumentException((path.startsWith("nodes[") ? "节点条件无法读取" : "主题条件无法读取")
                    + publicationLocation(topic, path), invalid);
        } catch (IllegalArgumentException invalid) { throw located(topic, path, invalid); }
    }

    private IllegalArgumentException located(CmsTopic topic, String path, IllegalArgumentException invalid) {
        return new IllegalArgumentException(invalid.getMessage() + publicationLocation(topic, path), invalid);
    }

    private String publicationLocation(CmsTopic topic, String path) {
        return " [topicId=" + topic.getId() + ", path=" + path + "]";
    }

    public void assertJourneyStoryReferences(CmsTopic topic) {
        assertJourneyStoryReferences(topic, "journeyStoryJson");
    }

    private void assertJourneyStoryReferences(CmsTopic topic, String fieldPath) {
        assertJourneyStoryReferences(topic, fieldPath, null);
    }

    private void assertJourneyStoryReferences(CmsTopic topic, String fieldPath,
            TopicStateKeyRefService.CurrentContent current) {
        String raw = topic == null ? null : topic.getJourneyStoryJson();
        if (StringUtils.isEmpty(raw)) {
            return;
        }
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(topic.getId());
        Set<Long> chapterIds = new LinkedHashSet<Long>();
        List<CmsTopicChapter> scopedChapters = current == null
                ? nullToEmpty(cmsTopicChapterMapper.selectCmsTopicChapterList(probe)) : current.chapters();
        for (CmsTopicChapter chapter : scopedChapters) {
            if (chapter.getId() != null) chapterIds.add(chapter.getId());
        }
        // R14:sys.* 条件引用的 nodeId 与 dispositionNodeId 都要对得上本主题节点;
        // 处置节点还必须是分支玩法(否则 sys.outcome.{nodeId} 永远不产生)。
        CmsTopicNode nodeProbe = new CmsTopicNode();
        nodeProbe.setTopicId(topic.getId());
        nodeProbe.setStatus(1L);
        List<CmsTopicNode> nodes = current == null
                ? nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(nodeProbe)) : current.activeNodes();
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
        if (current == null && cmsMemberTemplateMapper == null) {
            branchNodeRefs = null;      // 手工 new 的单测:该侧不核对
            outcomeNodeRefs = null;
        } else if (!templateIds.isEmpty()) {
            // 空模板集合不能进 in 查询(生成 where id in 空,语法错)
            Map<Long, Boolean> branchByTemplate = new LinkedHashMap<Long, Boolean>();
            Map<Long, Boolean> outcomeByTemplate = new LinkedHashMap<Long, Boolean>();
            List<CmsMemberTemplate> scopedTemplates;
            if (current == null) {
                scopedTemplates = nullToEmpty(cmsMemberTemplateMapper.selectCmsMemberTemplateByIds(new ArrayList<Long>(templateIds)));
            } else {
                scopedTemplates = new ArrayList<CmsMemberTemplate>();
                for (Long id : templateIds) {
                    CmsMemberTemplate template = current.templates().get(id);
                    if (template != null) scopedTemplates.add(template);
                }
            }
            for (CmsMemberTemplate template : scopedTemplates) {
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
                    chapterIds, journeyStoryAvailableNpcIds(topic.getId(), current), nodeIds, branchNodeRefs, outcomeNodeRefs));
        } catch (RuntimeException e) {
            ServiceException invalid = new ServiceException("主题的剧情配置引用无效,主题发布失败:" + e.getMessage()
                    + publicationLocation(topic, fieldPath));
            invalid.initCause(e);
            throw invalid;
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
        String normalized = normalizedBranchGraph(topic);
        return fullSnapshot ? topicRouteRuntimeService.freezePublication(topic, normalized) : normalized;
    }

    /** Formal publication freezes complete content even for ordinary linear/no-template nodes.
     * Preparation/derivation is already finished; never re-enter the legacy compile path after capture. */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, rollbackFor = Exception.class)
    public String normalizedBranchRouteForPublish(CmsTopic topic, List<ThemeStateKey> declarations) {
        requirePublicationWriteTransaction(topic);
        List<ThemeStateKey> captured = publicationDeclarations(topic, declarations);
        if (topicRouteRuntimeService == null) throw new IllegalStateException("主题发布内容冻结服务缺失");
        String normalized = null;
        if ("BRANCH_GRAPH".equalsIgnoreCase(topic.getRouteMode())) {
            if (topicStateKeyRefService == null) throw new IllegalStateException("正式发布引用收集服务缺失");
            normalized = normalizedBranchGraph(topic, topicStateKeyRefService.currentForPublication(topic));
        }
        String frozen = topicRouteRuntimeService.freezePublication(topic, normalized, captured);
        if (StringUtils.isBlank(frozen)) throw new IllegalStateException("主题发布内容快照为空");
        return frozen;
    }

    private String normalizedBranchGraph(CmsTopic topic) {
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topic.getId());
        probe.setStatus(1L);
        Set<Long> nodeIds = new LinkedHashSet<Long>();
        for (CmsTopicNode node : nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(probe))) {
            nodeIds.add(node.getId());
        }
        return normalizedBranchGraph(topic, nodeIds, declaredRouteOutcomes(topic.getId()));
    }

    private String normalizedBranchGraph(CmsTopic topic, TopicStateKeyRefService.CurrentContent current) {
        Set<Long> nodeIds = new LinkedHashSet<Long>();
        for (CmsTopicNode node : current.activeNodes()) nodeIds.add(node.getId());
        return normalizedBranchGraph(topic, nodeIds, routeOutcomeReader.declaredRouteOutcomes(current));
    }

    private String normalizedBranchGraph(CmsTopic topic, Set<Long> nodeIds, Map<Long, Set<String>> outcomes) {
        String normalized;
        try {
            normalized = topicRouteConfigValidator.validateAndNormalize(
                    topic.getRouteGraphJson(), nodeIds, outcomes);
            topicRoutePublishGate.assertPublishable(normalized);
        } catch (IllegalArgumentException invalid) { throw located(topic, "routeGraphJson", invalid); }
        return normalized;
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
