package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.ITopicContentGraphAuthoringService;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.business.util.ChapterAtmospherePreset;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.bean.BeanUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import java.util.*;

/** Writes the complete authored graph inside the caller's existing transaction. */
@Service
public class TopicContentGraphAuthoringServiceImpl implements ITopicContentGraphAuthoringService {
    private static final Logger logger = LoggerFactory.getLogger(TopicContentGraphAuthoringServiceImpl.class);
    private static final ObjectMapper ROUTE_JSON = new ObjectMapper();
    @Autowired private CmsTopicMapper cmsTopicMapper;
    @Autowired private CmsTopicChapterMapper cmsTopicChapterMapper;
    @Autowired private CmsTopicNodeMapper cmsTopicNodeMapper;
    @Autowired private CmsMemberTemplateMapper cmsMemberTemplateMapper;
    @Autowired private ICmsMemberTemplateService cmsMemberTemplateService;
    @Autowired private ITopicRouteRuntimeService topicRouteRuntimeService;
    @Autowired private TopicContentWriteGuard contentWriteGuard;
    @Autowired private TopicRouteOutcomeReader routeOutcomeReader;
    private final TopicRouteConfigValidator topicRouteConfigValidator = new TopicRouteConfigValidator();
    private final JourneyStoryValidator journeyStoryValidator = new JourneyStoryValidator();

    /**
     * [W4闭环] 把节点引用的私有模板深拷贝给副本归属。
     * 返回副本节点应引用的 templateId;无模板 / 无法判定归属(P0-35 无 memberId 的复制)时原样返回。
     * {@code forceCopy=true}(块配置引用了 NPC)时,即使归属未变也深拷贝一份 —— 否则没有可回写的行,
     * enter/outcome.npcId 只能继续指向来源主题的 NPC。
     * 拷贝保留 status=1 / draft_status=1(源模板本就是审核通过的已发布模板,否则 W4 会拦节点引用),
     * original_template_id 只继承源模板的公共库溯源 id；源模板无公共库溯源则写 0，
     * 绝不把会员模板 id 冒充公共库 id。is_sync=0 不带进公共模板库。
     */
    private Long copyTemplateForOwner(Long templateId, Long newOwnerMemberId, Map<Long, Long> templateIdMap,
                                      boolean forceCopy) {
        if (templateId == null || templateId <= 0L || newOwnerMemberId == null) {
            return templateId;
        }
        Long mapped = templateIdMap.get(templateId);
        if (mapped != null) {
            return mapped;
        }
        Long copiedId = cmsMemberTemplateService.copyForContentOwner(templateId, newOwnerMemberId, forceCopy);
        templateIdMap.put(templateId, copiedId);
        return copiedId;
    }

    /**
     * R8-STORY:章节重建会整批换新 id,剧情 stages[].chapterId 必须按 old→new 重映射后回填,
     * 否则保存「成功」而玩家读到的是上一批章节 id(静默错位、发布期才拦)。
     *
     * <p>映射不全时 fail-closed:剧情引用了本次提交里没有的章节就拒绝保存,不留坏引用。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void persistJourneyStoryAfterChapterRebuild(Long topicId, String storedStory,
                                                       TopicAuthoredGraphIds routeNodeMaps, String username) {
        String sourceStory = storedStory;
        if (!routeNodeMaps.endingChapters().isEmpty() && StringUtils.isNotBlank(storedStory)) {
            // 本次结局章将整体替换旧 endings；只剔除待废弃列表，保留段仍严格换期。
            try {
                JsonNode source = ROUTE_JSON.readTree(storedStory);
                JsonNode ending = source == null ? null : source.get("ending");
                if (ending instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) ending).remove("endings");
                    sourceStory = source.toString();
                }
            } catch (java.io.IOException invalidStory) {
                throw new IllegalArgumentException("主题剧情配置无法解析", invalidStory);
            }
        }
        // 来源剧情先按旧节点身份换期；结局章随后独立映射，避免把新 ID 再作旧 ID。
        String content = JourneyStory.withOpeningChapterId(
                JourneyStory.remapNodeIds(sourceStory, routeNodeMaps.oldToNew), null);
        String remapped = JourneyStory.withOpeningChapterId(
                JourneyStory.remapChapterIds(content, routeNodeMaps.chapterOldToNew), routeNodeMaps.openingChapterId);
        if (!routeNodeMaps.endingChapters().isEmpty()) {
            // 结局 = 结局章:按章节重建结局列表;形状在这里验,引用(章节 / 站点属于本主题)发布期全量重验
            remapped = JourneyStory.withEndingChapters(remapped, routeNodeMaps.endingChapters(), routeNodeMaps.oldToNew);
            journeyStoryValidator.validate(remapped, com.chengyinhub.business.service.support.JourneyStoryValidator.Context.none());
        }
        if (StringUtils.isBlank(remapped)) return;
        CmsTopic storyPatch = new CmsTopic();
        storyPatch.setId(topicId);
        storyPatch.setJourneyStoryJson(remapped);
        storyPatch.setUpdateBy(username);
        storyPatch.setUpdateTime(new Date());
        if (cmsTopicMapper.updateCmsTopic(storyPatch) != 1) {
            throw new IllegalStateException("主题剧情配置保存失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remapRebuiltTemplateNodes(Long topicId,Map<Long,Long> nodeMap,Long ownerId) {
        if(cmsMemberTemplateMapper==null || nodeMap==null || nodeMap.isEmpty())return;
        CmsTopicNode query=new CmsTopicNode();query.setTopicId(topicId);Map<Long,Long> copied=new LinkedHashMap<>();
        for(CmsTopicNode node:nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(query))) {
            if(node.getTemplateId()==null || node.getTemplateId()<=0)continue;
            CmsMemberTemplate source=cmsMemberTemplateMapper.selectCmsMemberTemplateById(node.getTemplateId());
            if(source==null || com.chengyinhub.business.util.StateKeyRefs.collectSysNodeIds(source.getAdvancedConfigJson()).isEmpty())continue;
            String remapped=com.chengyinhub.business.util.StateKeyRefs.remapNodeIds(source.getAdvancedConfigJson(),nodeMap);
            if(java.util.Objects.equals(remapped,source.getAdvancedConfigJson()))continue;
            Long templateId=copyTemplateForOwner(source.getId(),ownerId,copied,true);
            if(templateId==null || templateId.equals(source.getId()))throw new IllegalStateException("节点条件重映射未取得独立模板");
            CmsMemberTemplate templatePatch=new CmsMemberTemplate();templatePatch.setId(templateId);templatePatch.setAdvancedConfigJson(remapped);
            if(cmsMemberTemplateMapper.updateCmsMemberTemplate(templatePatch)!=1)throw new IllegalStateException("节点条件模板重映射失败");
            CmsTopicNode nodePatch=new CmsTopicNode();nodePatch.setId(node.getId());nodePatch.setTemplateId(templateId);
            if(cmsTopicNodeMapper.updateCmsTopicNode(nodePatch)!=1)throw new IllegalStateException("节点条件模板绑定失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void persistTemplateInteractionRules(Long topicId) {
        if(topicRouteRuntimeService==null)return;
        CmsTopic actual = cmsTopicMapper.selectCmsTopicById(topicId); if(actual==null)return;
        String compiled = topicRouteRuntimeService.compileTemplateRules(actual);
        if(compiled==null || java.util.Objects.equals(compiled,actual.getThemeRuntimeConfigJson()))return;
        CmsTopic patch=new CmsTopic(); patch.setId(topicId); patch.setThemeRuntimeConfigJson(compiled);
        if(cmsTopicMapper.updateCmsTopic(patch)!=1)throw new IllegalStateException("模板使用规则保存失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<Long, Long> existingNodeUnlocks(Long topicId) {
        CmsTopicNode query = new CmsTopicNode();
        query.setTopicId(topicId);
        Map<Long, Long> unlocks = new LinkedHashMap<Long, Long>();
        for (CmsTopicNode node : nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(query))) {
            if (node.getId() != null && node.getUnlockAfterNodeId() != null
                    && node.getUnlockAfterNodeId() > 0L) {
                unlocks.put(node.getId(), node.getUnlockAfterNodeId());
            }
        }
        return Collections.unmodifiableMap(unlocks);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreNodeUnlocks(Map<Long, Long> existingUnlocks, TopicAuthoredGraphIds maps) {
        for (Map.Entry<Long, Long> entry : existingUnlocks.entrySet()) {
            Long nodeId = maps.oldToNew.get(entry.getKey());
            if (nodeId == null) continue; // 依赖节点本次已删除，不再保留其前置关系。
            Long predecessorId = maps.oldToNew.get(entry.getValue());
            if (predecessorId == null) {
                throw new IllegalArgumentException("节点前置解锁引用不在本次提交里: " + entry.getValue());
            }
            CmsTopicNode patch = new CmsTopicNode();
            patch.setId(nodeId);
            patch.setUnlockAfterNodeId(predecessorId);
            if (cmsTopicNodeMapper.updateCmsTopicNode(patch) != 1) {
                throw new IllegalStateException("节点前置解锁保存失败: " + nodeId);
            }
        }
    }

    /** 本主题后台「隐藏」(status=0)的节点 id。走不带商家可见性闸的行锁查询,待审商家节点也算在内。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Set<Long> selectHiddenNodeIds(Long topicId) {
        Set<Long> hidden = new HashSet<Long>();
        for (CmsTopicNode node : nullToEmpty(cmsTopicNodeMapper.selectByTopicForUpdate(topicId))) {
            if (node != null && node.getId() != null && Long.valueOf(0L).equals(node.getStatus())) {
                hidden.add(node.getId());
            }
        }
        return Collections.unmodifiableSet(hidden);
    }

    /** 重建后按旧 id → 新 id 把隐藏态写回;只会隐藏、不会取消隐藏,新节点(无旧 id)不受影响。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreHiddenNodes(Set<Long> hiddenNodeIds, Map<Long, Long> oldToNew) {
        for (Long oldId : hiddenNodeIds) {
            Long newId = oldToNew.get(oldId);
            if (newId == null) continue;
            CmsTopicNode patch = new CmsTopicNode();
            patch.setId(newId);
            patch.setStatus(0L);
            if (cmsTopicNodeMapper.updateCmsTopicNode(patch) != 1) {
                throw new IllegalStateException("隐藏节点状态保留失败");
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTopicChapterFlow(Long topicId) {
        contentWriteGuard.lockTopicContent(topicId);
        CmsTopicNode nodeQ = new CmsTopicNode();
        nodeQ.setTopicId(topicId);
        for (CmsTopicNode node : nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(nodeQ))) {
            cmsTopicNodeMapper.deleteCmsTopicNodeById(node.getId());
        }
        CmsTopicChapter chapterQ = new CmsTopicChapter();
        chapterQ.setTopicId(topicId);
        for (CmsTopicChapter chapter : nullToEmpty(cmsTopicChapterMapper.selectCmsTopicChapterList(chapterQ))) {
            cmsTopicChapterMapper.deleteCmsTopicChapterById(chapter.getId());
        }
    }

    private Builder saveChaptersAndNodes(List<ChapterDTO> chapters, Long topicId, String username, Long memberId,
                                               boolean allowBundledDraft) {
        Builder routeNodeMaps = new Builder();
        if (CollectionUtils.isEmpty(chapters)) {
            return routeNodeMaps;
        }

        TopicSizeLimits.requireWithinLimits(chapters);
        contentWriteGuard.lockTopicContent(topicId);
        long chapterSortId = 0L;
        for (ChapterDTO chapterDTO : chapters) {
            CmsTopicChapter chapter = new CmsTopicChapter();

            // [D21] copyProperties 会把前端送来的这几个字段**原样落库**(与下面 saveNodes 里
            // templateId 那个洞同型 —— 同一个方法里已经栽过一次)。必须挡在 copy 之前。
            contentWriteGuard.assertChapterShapeForTopic(chapterDTO, topicId);

            BeanUtils.copyProperties(chapterDTO, chapter);
            // 旧章节 id 只用于剧情 chapterId 映射,绝不带进新行(与 saveNodes 的 id 处理同口径)
            chapter.setId(null);
            // 配色不靠 copyProperties 隐式透传:枚举必须当场收敛(存量别名也在这里被写成新档)
            chapter.setAtmospherePreset(ChapterAtmospherePreset.requireValid(chapterDTO.getAtmospherePreset()));
            chapter.setSortId(++chapterSortId);
            chapter.setTopicId(topicId);
            chapter.setCreateBy(username);
            chapter.setCreateTime(new Date());
            if (cmsTopicChapterMapper.insertCmsTopicChapter(chapter) != 1
                    || chapter.getId() == null || chapter.getId() <= 0L) {
                throw new IllegalStateException("章节保存失败，未生成有效章节身份");
            }
            Long chapterId = chapter.getId();
            routeNodeMaps.recordChapter(chapterDTO.getId(), chapterId);

            // 保存节点
            saveNodes(chapterDTO.getNodes(), topicId, chapterId, username, memberId, routeNodeMaps,
                    allowBundledDraft);
        }
        contentWriteGuard.assertTopicSizeWithinLimits(topicId);
        return routeNodeMaps;
    }

    /** 旧接口永不物化 blocks；V2 的经典定向及显式提交 blocks 的自由探索走块化写路径。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicAuthoredGraphIds saveTopicChapters(List<ChapterDTO> chapters, Long topicId, String username, Long memberId,
                                   Integer productType, boolean chapterFlowV2, boolean allowBundledDraft) {
        if (shouldCompileChapterFlow(chapterFlowV2, productType, chapters)) {
            return compileChapterFlow(chapters, topicId, username, memberId, false, allowBundledDraft);
        }
        if (chapterFlowV2 && !Integer.valueOf(ProductType.FREE_EXPLORE).equals(productType)) {
            ChapterFlowCompiler.assertNoBlockPayload(chapters,
                    "当前产品类型不支持章节 blocks");
        }
        return saveChaptersAndNodes(chapters, topicId, username, memberId, allowBundledDraft).freeze();
    }

    @Override
    public boolean shouldCompileChapterFlow(boolean chapterFlowV2, Integer productType,
                                             List<ChapterDTO> chapters) {
        if (!chapterFlowV2) {
            return false;
        }
        if (Integer.valueOf(ProductType.ORIENTEERING).equals(productType)) {
            return true;
        }
        return Integer.valueOf(ProductType.FREE_EXPLORE).equals(productType)
                && ChapterFlowCompiler.hasBlockPayload(chapters);
    }

    /**
     * V2 唯一块化写路径：校验完整 payload，按章节数组与块数组派生顺序，再把真实 nodeId 写入 blocks。
     * 调用方 {@code createTopicV2/updateTopicByOwnerV2} 均由 Spring 事务代理进入；更新时删除与重建同事务。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicAuthoredGraphIds compileChapterFlow(List<ChapterDTO> chapters, Long topicId, String username, Long memberId,
                                    boolean replaceExisting, boolean allowBundledDraft) {
        TopicSizeLimits.requireWithinLimits(chapters);
        ChapterFlowCompiler.CompiledFlow flow = ChapterFlowCompiler.compileChapterFlow(chapters);
        Builder routeNodeMaps = new Builder();
        contentWriteGuard.lockTopicContent(topicId);

        // 所有既有章节/模板规则也先验完，再开始任何删除或插入。
        for (ChapterFlowCompiler.CompiledChapter compiled : flow.getChapters()) {
            ChapterDTO chapter = compiled.getSource();
            contentWriteGuard.assertChapterShapeForTopic(chapter, topicId);
            List<NodeDTO> nodes = chapter.getNodes() == null
                    ? Collections.<NodeDTO>emptyList() : chapter.getNodes();
            for (NodeDTO node : nodes) {
                if (node == null) {
                    throw new ChapterFlowValidationException(
                            "第 " + compiled.getChapterSortId() + " 章包含空节点");
                }
                contentWriteGuard.assertTemplateOwned(node.getTemplateId(), memberId, allowBundledDraft);
            }
            for (ChapterBlockDTO block : chapter.getBlocks()) {
                if (!Boolean.FALSE.equals(block.getLocationRequired())) continue;
                com.chengyinhub.business.domain.CmsMemberTemplate template = cmsMemberTemplateMapper
                        .selectCmsMemberTemplateById(nodes.get(block.getNodeIndex()).getTemplateId());
                Integer method = template.getValidationMethod();
                if (Integer.valueOf(4).equals(method) || Integer.valueOf(5).equals(method)) {
                    throw new ChapterFlowValidationException("扫码到达和 GPS 到达请添加为地点");
                }
            }
        }

        if (replaceExisting) {
            deleteTopicChapterFlow(topicId);
        }

        for (ChapterFlowCompiler.CompiledChapter compiled : flow.getChapters()) {
            ChapterDTO source = compiled.getSource();
            CmsTopicChapter chapter = new CmsTopicChapter();
            try {
                BeanUtils.copyProperties(source, chapter);
                // 旧章节 id 只用于剧情 chapterId 映射,绝不带进新行
                chapter.setId(null);
                chapter.setAtmospherePreset(ChapterAtmospherePreset.requireValid(source.getAtmospherePreset()));
                chapter.setTopicId(topicId);
                chapter.setSortId(compiled.getChapterSortId());
                chapter.setRequired(1);
                chapter.setDescription(compiled.getDescription());
                chapter.setBlocksJson(null);
                chapter.setCreateBy(username);
                chapter.setCreateTime(new Date());
                if (cmsTopicChapterMapper.insertCmsTopicChapter(chapter) != 1
                        || chapter.getId() == null || chapter.getId() <= 0L) {
                    throw new IllegalStateException("章节保存失败，未生成有效章节身份");
                }
                routeNodeMaps.recordChapter(source.getId(), chapter.getId());
                if (Boolean.TRUE.equals(source.getOpening())) routeNodeMaps.openingChapterId = chapter.getId();
                if (source.getEnding() != null && !source.getEnding().isNull()) {
                    routeNodeMaps.endingChapters.add(new TopicAuthoredGraphIds.EndingChapterSnapshot(
                            chapter.getId(), source.getName(), compiled.getDescription(), source.getEnding()));
                }

                List<NodeDTO> nodes = source.getNodes() == null
                        ? Collections.<NodeDTO>emptyList() : source.getNodes();
                List<Long> nodeIds = new ArrayList<>(nodes.size());
                for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
                    NodeDTO nodeDTO = nodes.get(nodeIndex);
                    CmsTopicNode node = new CmsTopicNode();
                    BeanUtils.copyProperties(nodeDTO, node);
                    node.setId(null);
                    node.setTopicId(topicId);
                    node.setChapterId(chapter.getId());
                    node.setSortId(compiled.getNodeSortId(nodeIndex));
                    node.setStatus(1L);
                    node.setCheckinCode(java.util.UUID.randomUUID().toString().replace("-", ""));
                    node.setCreateBy(username);
                    node.setCreateTime(new Date());
                    if (cmsTopicNodeMapper.insertCmsTopicNode(node) != 1
                            || node.getId() == null || node.getId() <= 0L) {
                        throw new IllegalStateException("节点保存失败，未生成有效节点身份");
                    }
                    nodeIds.add(node.getId());
                    routeNodeMaps.record(nodeDTO, node.getId());
                }

                chapter.setBlocksJson(compiled.materializeBlocks(nodeIds));
                if (cmsTopicChapterMapper.updateCmsTopicChapter(chapter) <= 0) {
                    throw new IllegalStateException("章节 blocks 写入失败: chapterId=" + chapter.getId());
                }
            } catch (RuntimeException e) {
                logger.warn("章节故事流写入失败: topicId={}, chapterId={}", topicId, chapter.getId(), e);
                throw e;
            }
        }
        contentWriteGuard.assertTopicSizeWithinLimits(topicId);
        return routeNodeMaps.freeze();
    }

    private void saveNodes(List<NodeDTO> nodes, Long topicId, Long chapterId, String username, Long memberId,
                           Builder routeNodeMaps, boolean allowBundledDraft) {
        if (CollectionUtils.isEmpty(nodes)) {
            return;
        }

        long nodeSortId = 0L;
        for (NodeDTO nodeDTO : nodes) {
            CmsTopicNode node = new CmsTopicNode();
            BeanUtils.copyProperties(nodeDTO, node);
            node.setId(null);

            // [W4] copyProperties 原样把前端送来的 templateId 落库 —— 挂谁的模板全凭前端说了算。
            contentWriteGuard.assertTemplateOwned(nodeDTO.getTemplateId(), memberId, allowBundledDraft);

            node.setTopicId(topicId);
            node.setChapterId(chapterId);
            node.setSortId(++nodeSortId);
            node.setStatus(1L); // 默认显示
            node.setCheckinCode(java.util.UUID.randomUUID().toString().replace("-", "")); // 节点打卡码
            node.setCreateBy(username);
            node.setCreateTime(new Date());

            if (cmsTopicNodeMapper.insertCmsTopicNode(node) != 1
                    || node.getId() == null || node.getId() <= 0L) {
                throw new IllegalStateException("节点保存失败，未生成有效节点身份");
            }
            routeNodeMaps.record(nodeDTO, node.getId());
        }
    }

    /** 保留存量测试/反射 seam；旧调用未携带主题级路线图。 */
    private void saveNodes(List<NodeDTO> nodes, Long topicId, Long chapterId, String username, Long memberId) {
        saveNodes(nodes, topicId, chapterId, username, memberId, new Builder(), false);
    }

    /** 节点全部落库后才发布主题路线；任何未知 key/id 都抛错并回滚整次建题/编辑。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveRouteDefinition(Long topicId, String requestedMode, String rawGraph,
                                     TopicAuthoredGraphIds maps, Long requestExpectedVersion,
                                     Long expectedVersion, Long targetVersion, Integer productType) {
        if ((requestedMode == null || requestedMode.trim().isEmpty())
                && (rawGraph == null || rawGraph.trim().isEmpty()) && requestExpectedVersion == null) {
            return; // 老客户端未提交路线字段：保留原 LINEAR/既有配置，不额外触发 CAS。
        }
        String mode = requestedMode == null || requestedMode.trim().isEmpty()
                ? "LINEAR" : requestedMode.trim().toUpperCase(Locale.ROOT);
        if (!"LINEAR".equals(mode) && !"BRANCH_GRAPH".equals(mode)) {
            throw new IllegalArgumentException("routeMode 仅支持 LINEAR/BRANCH_GRAPH");
        }
        contentWriteGuard.assertBranchGraphOnlyForOrienteering(mode, productType);
        if (requestExpectedVersion != null && !requestExpectedVersion.equals(expectedVersion)) {
            throw new IllegalStateException("路线配置已更新，请刷新后重试");
        }
        String graph = null;
        if ("BRANCH_GRAPH".equals(mode)) {
            if (rawGraph == null || rawGraph.trim().isEmpty()) {
                throw new IllegalArgumentException("BRANCH_GRAPH 必须配置 routeGraphJson");
            }
            com.fasterxml.jackson.databind.JsonNode tree = topicRouteConfigValidator.readTree(rawGraph);
            boolean usesClientKeys = tree.has("startNodeKey") || tree.has("terminalNodeKeys")
                    || containsNodeKey(tree.path("edges")) || containsNodeKey(tree.path("fallbacks"));
            if (usesClientKeys) graph = topicRouteConfigValidator.remapClientNodeKeys(rawGraph, maps.clientToNew);
            else if (!maps.oldToNew.isEmpty()) graph = topicRouteConfigValidator.remapNodeIds(rawGraph, maps.oldToNew);
            else graph = rawGraph;
            graph = topicRouteConfigValidator.validateAndNormalize(graph, maps.newNodeIds,
                    routeOutcomeReader.declaredRouteOutcomes(topicId));
        }
        if (cmsTopicMapper.updateRouteDefinition(topicId, mode, graph, expectedVersion, targetVersion,
                new Date()) != 1) {
            throw new IllegalStateException("路线配置已更新，请刷新后重试");
        }
    }

    private boolean containsNodeKey(com.fasterxml.jackson.databind.JsonNode array) {
        if (!array.isArray()) return false;
        for (com.fasterxml.jackson.databind.JsonNode item : array) {
            if (item.has("fromNodeKey") || item.has("toNodeKey")) return true;
            if (item.path("conditions").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode condition : item.path("conditions")) {
                    if (condition.has("nodeKey")) return true;
                }
            }
        }
        return false;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void persistCreatedRuntimeBindings(Long topicId, String runtimeRaw, TopicAuthoredGraphIds ids) {
        CmsTopic runtimePatch = new CmsTopic(); runtimePatch.setId(topicId);
        runtimePatch.setThemeRuntimeConfigJson(remapTemplateRuleIdentities(com.chengyinhub.business.service.support.ThemeRuntimeConfig.remapForNodeSave(runtimeRaw,ids.oldToNew,ids.chapterOldToNew,ids.createdClientToNew),ids.oldToNew));
        if (cmsTopicMapper.updateCmsTopic(runtimePatch) != 1) throw new IllegalStateException("主题运行配置保存失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void persistRebuiltRuntimeBindings(Long topicId, String runtimeRaw, TopicAuthoredGraphIds ids) {
        CmsTopic runtimePatch = new CmsTopic(); runtimePatch.setId(topicId);
        runtimePatch.setThemeRuntimeConfigJson(remapTemplateRuleIdentities(com.chengyinhub.business.service.support.ThemeRuntimeConfig.remapForNodeSave(runtimeRaw, ids.oldToNew, ids.chapterOldToNew, ids.createdClientToNew),ids.oldToNew));
        if (cmsTopicMapper.updateCmsTopic(runtimePatch) != 1) throw new IllegalStateException("主题运行配置重映射失败");
    }

    private String remapTemplateRuleIdentities(String raw, Map<Long, Long> nodes) {
        return TopicTemplateRuleIdentityRemapper.remap(raw, nodes);
    }

    private static <T> List<T> nullToEmpty(List<T> rows) {
        return rows == null ? Collections.<T>emptyList() : rows;
    }

    private static final class Builder {
        private Long openingChapterId;
        /** 结局章(2026-09-24):按章节顺序,剧情落库前据此重建 ending.endings。 */
        private final List<TopicAuthoredGraphIds.EndingChapterSnapshot> endingChapters = new ArrayList<TopicAuthoredGraphIds.EndingChapterSnapshot>();
        private final Map<String, Long> clientToNew = new LinkedHashMap<String, Long>();
        private final Map<String, Long> createdClientToNew = new LinkedHashMap<String, Long>();
        private final Map<Long, Long> oldToNew = new LinkedHashMap<Long, Long>();
        private final Set<Long> newNodeIds = new LinkedHashSet<Long>();
        /** R8-STORY:章节 old→new,剧情 stages[].chapterId 重建后据此重映射。 */
        private final Map<Long, Long> chapterOldToNew = new LinkedHashMap<Long, Long>();

        private void record(NodeDTO source, Long newNodeId) {
            if (newNodeId == null || newNodeId <= 0L) return;
            newNodeIds.add(newNodeId);
            if (source.getId() != null && source.getId() > 0L) {
                Long old = oldToNew.put(source.getId(), newNodeId);
                if (old != null) throw new IllegalArgumentException("节点 id 重复:" + source.getId());
            }
            String key = source.getClientNodeKey();
            if (key != null && !key.trim().isEmpty()) {
                key = key.trim();
                if (key.length() > 64) throw new IllegalArgumentException("clientNodeKey 最多 64 位");
                Long old = clientToNew.put(key, newNodeId);
                if (old != null) throw new IllegalArgumentException("clientNodeKey 重复:" + key);
                if (source.getId() == null || source.getId() <= 0L) createdClientToNew.put(key, newNodeId);
            }
        }

        private void recordChapter(Long oldChapterId, Long newChapterId) {
            if (oldChapterId == null || oldChapterId <= 0L || newChapterId == null || newChapterId <= 0L) return;
            Long old = chapterOldToNew.put(oldChapterId, newChapterId);
            if (old != null) throw new IllegalArgumentException("章节 id 重复:" + oldChapterId);
        }
        private TopicAuthoredGraphIds freeze() {
            return new TopicAuthoredGraphIds(openingChapterId, endingChapters, clientToNew,
                    createdClientToNew, oldToNew, newNodeIds, chapterOldToNew);
        }
    }
}
