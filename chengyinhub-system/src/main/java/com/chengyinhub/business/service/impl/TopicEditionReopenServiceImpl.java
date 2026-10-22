package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.JourneyStory;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.TopicEditionReopenRequest;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.mapper.ProductRoleMapper;
import com.chengyinhub.business.mapper.TopicEditionReopenMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ITopicEditionReopenService;
import com.chengyinhub.business.util.NarrativeNpcRefs;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.RoleEntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 合同一：重开一期只复制内容结构，商业结构、历史合同与履约事实全部留在来源期。 */
@Service
public class TopicEditionReopenServiceImpl implements ITopicEditionReopenService {
    private static final String COMMAND = "REOPEN_NEXT_EDITION";
    static final String TEMPLATE_USE_RIGHTS_GATE = "模板使用权校验失败";
    static final String TERMS_RECONFIGURATION_GATE = "REQUIRES_TERMS_RECONFIGURATION";
    static final String SOURCE_LIFECYCLE_GATE = "已开售的显式生命周期";

    private static final Logger logger = LoggerFactory.getLogger(TopicEditionReopenServiceImpl.class);

    private final TopicEditionReopenMapper reopenMapper;
    private final ProductRoleMapper productRoleMapper;
    private final CmsMemberTemplateMapper templateMapper;
    private final ClubMapper clubMapper;
    private final UmsMemberMapper memberMapper;
    /** R13-NPC:节点 NPC(scope_type=4)随节点复制;手工 new 的旧单测传 null 时该侧跳过。 */
    private final NpcProfileMapper npcProfileMapper;

    @Value("${chengyin.platform.member-id:100000}")
    private Long platformMemberId;

    public TopicEditionReopenServiceImpl(TopicEditionReopenMapper reopenMapper,
                                         ProductRoleMapper productRoleMapper,
                                         CmsMemberTemplateMapper templateMapper,
                                         ClubMapper clubMapper,
                                         UmsMemberMapper memberMapper,
                                         NpcProfileMapper npcProfileMapper) {
        this.reopenMapper = reopenMapper;
        this.productRoleMapper = productRoleMapper;
        this.templateMapper = templateMapper;
        this.clubMapper = clubMapper;
        this.memberMapper = memberMapper;
        this.npcProfileMapper = npcProfileMapper;
    }

    /**
     * 媒体目前只保存裸 URL、没有资产所有权真源；临时判据为新旧 CONTENT 主体完全相同。
     * 这是防扩大使用范围的过渡闸，待媒体资产真源建立后必须改为逐资产授权校验。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long reopenNextEdition(Long sourceTopicId, String idempotencyKey) {
        assertRequest(sourceTopicId, idempotencyKey);
        TopicEditionReopenRequest request = new TopicEditionReopenRequest();
        request.setSourceTopicId(sourceTopicId);
        request.setIdempotencyKey(idempotencyKey.trim());
        request.setCreateBy(COMMAND);
        request.setCreateTime(new Date());
        int insertedRequest = reopenMapper.insertReopenRequest(request);
        if (insertedRequest == 0) {
            TopicEditionReopenRequest existing = reopenMapper.selectReopenRequest(sourceTopicId, request.getIdempotencyKey());
            if (existing == null || existing.getNewTopicId() == null) {
                throw new IllegalStateException("REOPEN 请求尚未完成，禁止重复复制");
            }
            return existing.getNewTopicId();
        }
        if (insertedRequest != 1 || request.getId() == null) {
            throw new IllegalStateException("REOPEN 幂等占位失败");
        }

        CmsTopic source = reopenMapper.selectSourceTopicForUpdate(sourceTopicId);
        assertCopyableSource(source);
        ProductRole sourceContent = reopenMapper.selectSourceContentForUpdate(sourceTopicId);
        ContentSubject target = reauthorizeSameContentSubject(sourceContent);
        List<CmsTopicChapter> chapters = nullToEmpty(reopenMapper.selectSourceChapters(sourceTopicId));
        List<CmsTopicNode> nodes = nullToEmpty(reopenMapper.selectSourceNodes(sourceTopicId));

        assertRevshareAllowed(chapters, target);
        assertTemporaryMediaUseRight(source, chapters, nodes, sourceContent, target);
        assertTemplateUseRights(nodes, target.getPayeeMemberId());

        CmsTopic newTopic = composeTopic(source, target);
        requireOne(reopenMapper.insertReopenedTopic(newTopic), "新期主题写入失败");
        declareFreshContentSlot(newTopic.getId(), target);

        Map<Long, Long> chapterIds = copyChapters(chapters, newTopic.getId());
        Map<Long, Long> npcIdMap = new LinkedHashMap<>();
        Map<Long, Long> nodeIds = copyNodes(nodes, newTopic.getId(), chapterIds, target.getPayeeMemberId(), npcIdMap);
        remapBlocks(chapters, chapterIds, nodeIds, npcIdMap);
        remapJourneyStory(newTopic, chapterIds, npcIdMap);

        requireOne(reopenMapper.completeReopenRequest(request.getId(), newTopic.getId()), "REOPEN 请求回填新期失败");
        return newTopic.getId();
    }

    private void assertRequest(Long sourceTopicId, String idempotencyKey) {
        if (sourceTopicId == null || sourceTopicId <= 0L) {
            throw new IllegalArgumentException("source_topic_id 必须为正整数");
        }
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty() || idempotencyKey.trim().length() > 64) {
            throw new IllegalArgumentException("idempotency_key 必须为 1-64 个非空字符");
        }
    }

    private void assertCopyableSource(CmsTopic source) {
        if (source == null || (source.getDelFlag() != null && !Integer.valueOf(0).equals(source.getDelFlag()))) {
            throw new IllegalStateException("来源主题不存在或已删除");
        }
        TopicLifecycleState lifecycle;
        try {
            lifecycle = TopicLifecycleState.fromCode(source.getLifecycle());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("来源主题生命周期未知，拒绝再开一期", e);
        }
        if (!lifecycle.isSaleStarted()) {
            throw new IllegalStateException("来源主题 " + SOURCE_LIFECYCLE_GATE + " 才允许再开一期");
        }
    }

    private ContentSubject reauthorizeSameContentSubject(ProductRole sourceContent) {
        if (sourceContent == null || !ProductSlot.CONTENT.equals(sourceContent.getSlot())
                || sourceContent.getStatus() == null || sourceContent.getStatus() != 1
                || !RoleEntityType.isContentOwner(sourceContent.getEntityType())
                || !RoleEntityType.entityIdConsistent(sourceContent.getEntityType(), sourceContent.getEntityId())) {
            throw new IllegalStateException("CONTENT 主体重新鉴权失败");
        }
        if (RoleEntityType.PLATFORM.equals(sourceContent.getEntityType())) {
            if (platformMemberId == null || platformMemberId <= 0L) {
                throw new IllegalStateException("CONTENT 主体重新鉴权失败:平台收款账号缺失");
            }
            return new ContentSubject(RoleEntityType.PLATFORM, null, platformMemberId, null, 1L);
        }
        if (RoleEntityType.MEMBER.equals(sourceContent.getEntityType())) {
            assertActiveMember(sourceContent.getEntityId(), "MEMBER");
            return new ContentSubject(RoleEntityType.MEMBER, sourceContent.getEntityId(),
                    sourceContent.getEntityId(), null, 2L);
        }
        Club club = clubMapper.selectClubById(sourceContent.getEntityId());
        if (club == null || Integer.valueOf(2).equals(club.getDelFlag()) || !Long.valueOf(1L).equals(club.getStatus())
                || club.getMemberId() == null || club.getMemberId() <= 0L) {
            throw new IllegalStateException("CONTENT 主体重新鉴权失败:CLUB 不可用");
        }
        assertActiveMember(club.getMemberId(), "CLUB 主理人");
        return new ContentSubject(RoleEntityType.CLUB, sourceContent.getEntityId(), club.getMemberId(),
                sourceContent.getEntityId(), 2L);
    }

    private void assertActiveMember(Long memberId, String subject) {
        UmsMember member = memberMapper.selectUmsMemberById(memberId);
        if (member == null || Integer.valueOf(2).equals(member.getDelFlag()) || !Long.valueOf(1L).equals(member.getStatus())) {
            throw new IllegalStateException("CONTENT 主体重新鉴权失败:" + subject + " 不可用");
        }
    }

    private void assertRevshareAllowed(List<CmsTopicChapter> chapters, ContentSubject target) {
        for (CmsTopicChapter chapter : chapters) {
            if ("REVSHARE".equals(chapter.getTermsMode()) && !RoleEntityType.PLATFORM.equals(target.getEntityType())) {
                throw new IllegalStateException(TERMS_RECONFIGURATION_GATE + ": REVSHARE 仅允许 PLATFORM CONTENT 重开");
            }
        }
    }

    private void assertTemporaryMediaUseRight(CmsTopic source, List<CmsTopicChapter> chapters, List<CmsTopicNode> nodes,
                                              ProductRole sourceContent, ContentSubject target) {
        boolean hasMedia = nonBlank(source.getImgUrl()) || nonBlank(source.getImgArr()) || nonBlank(source.getAudioUrl());
        for (CmsTopicChapter chapter : chapters) {
            hasMedia |= nonBlank(chapter.getImgArr());
        }
        for (CmsTopicNode node : nodes) {
            hasMedia |= nonBlank(node.getImgUrl());
        }
        if (hasMedia && (!target.getEntityType().equals(sourceContent.getEntityType())
                || !same(target.getEntityId(), sourceContent.getEntityId()))) {
            throw new IllegalStateException("媒体使用权校验失败:新期 CONTENT 主体必须与来源完全一致");
        }
    }

    private void assertTemplateUseRights(List<CmsTopicNode> nodes, Long newPayeeMemberId) {
        for (CmsTopicNode node : nodes) {
            Long templateId = node.getTemplateId();
            if (templateId == null || templateId <= 0L) {
                continue;
            }
            CmsMemberTemplate template = templateMapper.selectCmsMemberTemplateById(templateId);
            if (template == null || Integer.valueOf(2).equals(template.getDelFlag())
                    || !newPayeeMemberId.equals(template.getMemberId())
                    || !Long.valueOf(1L).equals(template.getStatus())
                    || !Integer.valueOf(1).equals(template.getDraftStatus())) {
                throw new IllegalStateException(TEMPLATE_USE_RIGHTS_GATE + ": templateId=" + templateId);
            }
        }
    }

    private CmsTopic composeTopic(CmsTopic source, ContentSubject target) {
        CmsTopic copy = new CmsTopic();
        copy.setMemberId(target.getPayeeMemberId());
        copy.setClubId(target.getClubId());
        copy.setTopicType(target.getTopicType());
        copy.setProductType(source.getProductType());
        // [R5] 数值规则是内容的一部分,必须随新一期沿用 —— 漏了等于新一期检定静默关闭;
        // gameModuleConfigJson 属于活动级开赛运行时配置,刻意不跨期复制,别顺手加。
        copy.setJourneyRulesJson(source.getJourneyRulesJson());
        // [R8] 主题级剧情配置随新期沿用;章节 id 在新期会重建,章节拷完后按 old→new 重映射回填。
        copy.setJourneyStoryJson(source.getJourneyStoryJson());
        copy.setName(source.getName());
        copy.setSubtitle(source.getSubtitle());
        copy.setDescription(source.getDescription());
        copy.setAddressName(source.getAddressName());
        copy.setTotalTime(source.getTotalTime());
        copy.setTotalMileage(source.getTotalMileage());
        copy.setLocationCount(source.getLocationCount());
        copy.setTemplateCount(source.getTemplateCount());
        copy.setImgUrl(source.getImgUrl());
        copy.setImgArr(source.getImgArr());
        copy.setCategoryIds(source.getCategoryIds());
        copy.setPublishMode(source.getPublishMode());
        copy.setAudioUrl(source.getAudioUrl());
        copy.setAudioDuration(source.getAudioDuration());
        copy.setSelfPlay(source.getSelfPlay());
        copy.setXpBudget(source.getXpBudget());
        copy.setFinishMedalName(source.getFinishMedalName());
        copy.setFinishMedalImg(source.getFinishMedalImg());
        copy.setSourceTopicId(source.getId());
        copy.setStatus(0);
        copy.setUserStatus(0);
        copy.setMerchantStatus(0);
        copy.setOpenClubPool(0);
        copy.setDelFlag(0);
        copy.setIsSync(0);
        copy.setIsRecommend(0);
        copy.setCheckStatus(0);
        copy.setIsSettlement(0);
        copy.setLifecycle(TopicLifecycleState.DRAFT.getCode());
        copy.setCreateBy(COMMAND);
        copy.setCreateTime(new Date());
        copy.setUpdateBy(COMMAND);
        copy.setUpdateTime(new Date());
        return copy;
    }

    private void declareFreshContentSlot(Long topicId, ContentSubject target) {
        ProductRole role = new ProductRole();
        role.setTopicId(topicId);
        role.setSlot(ProductSlot.CONTENT);
        role.setEntityType(target.getEntityType());
        role.setEntityId(target.getEntityId());
        role.setStatus(1);
        requireOne(productRoleMapper.insertProductRole(role), "新期 CONTENT 槽写入失败");
    }

    private Map<Long, Long> copyChapters(List<CmsTopicChapter> chapters, Long newTopicId) {
        Map<Long, Long> ids = new HashMap<>();
        for (CmsTopicChapter source : chapters) {
            CmsTopicChapter copy = new CmsTopicChapter();
            copy.setTopicId(newTopicId);
            copy.setName(source.getName());
            copy.setDescription(source.getDescription());
            // 章节背景旁白跟着新期走 —— 漏了新期就静默没声音,零报错
            copy.setAudioUrl(source.getAudioUrl());
            // 配色同理:漏了新期就整章掉回默认黑,零报错
            copy.setAtmospherePreset(source.getAtmospherePreset());
            copy.setImgArr(source.getImgArr());
            copy.setTotalTime(source.getTotalTime());
            copy.setTotalMileage(source.getTotalMileage());
            copy.setLocationCount(source.getLocationCount());
            copy.setTemplateCount(source.getTemplateCount());
            copy.setSortId(source.getSortId());
            copy.setCategory(source.getCategory());
            copy.setCategoryId(source.getCategoryId());
            copy.setRecruitEnabled(source.getRecruitEnabled());
            copy.setPerkMinValue(source.getPerkMinValue());
            copy.setMaxMerchant(source.getMaxMerchant());
            copy.setRequired(source.getRequired());
            copy.setTermsMode(source.getTermsMode());
            copy.setMaxPerHeadFee(source.getMaxPerHeadFee());
            copy.setAllowedValidationMethods(source.getAllowedValidationMethods());
            copy.setMaxNodeXp(source.getMaxNodeXp());
            copy.setCreateBy(COMMAND);
            copy.setCreateTime(new Date());
            requireOne(reopenMapper.insertReopenedChapter(copy), "新期章节写入失败");
            if (source.getId() == null || copy.getId() == null) {
                throw new IllegalStateException("新期章节 id 重建失败");
            }
            ids.put(source.getId(), copy.getId());
        }
        return ids;
    }

    /**
     * 复制新期节点(内容结构)。
     *
     * <p>R13-NPC:节点 NPC(scope_type=4)随节点复制到新期节点 id,映射写进 {@code npcIdMap};
     * 块配置引用了 NPC 的模板必须为新期深拷贝一份 —— 模板在归属未变时是跨期共享的,
     * 直接回写 enter/outcome.npcId 会把来源期的引用一起改掉,而源模板又是只读的历史事实。
     * 门店分身(scope 2)挂商家、不随期复制,引用由 {@link #remapJourneyStory} fail-closed 置空。
     */
    private Map<Long, Long> copyNodes(List<CmsTopicNode> nodes, Long newTopicId, Map<Long, Long> chapterIds,
                                      Long newPayeeMemberId, Map<Long, Long> npcIdMap) {
        Map<Long, Long> ids = new LinkedHashMap<>();
        Map<Long, String> copiedTemplateBlocks = new LinkedHashMap<>();
        // 同一源模板在一期里只深拷贝一次(与复制链路的 templateIdMap 同口径)
        Map<Long, Long> editionTemplateIds = new LinkedHashMap<>();
        List<String> npcWarnings = new ArrayList<>();
        for (CmsTopicNode source : nodes) {
            Long chapterId = chapterIds.get(source.getChapterId());
            if (chapterId == null) {
                throw new IllegalStateException("来源节点不属于来源章节: nodeId=" + source.getId());
            }
            CmsTopicNode copy = new CmsTopicNode();
            copy.setTopicId(newTopicId);
            copy.setChapterId(chapterId);
            copy.setName(source.getName());
            copy.setDescription(source.getDescription());
            copy.setAddress(source.getAddress());
            copy.setLongitude(source.getLongitude());
            copy.setLatitude(source.getLatitude());
            copy.setImgUrl(source.getImgUrl());
            copy.setNodeTime(source.getNodeTime());
            copy.setTemplateId(editionTemplateId(source.getTemplateId(), newPayeeMemberId,
                    editionTemplateIds, copiedTemplateBlocks));
            copy.setCheckinCode(UUID.randomUUID().toString().replace("-", ""));
            copy.setBusinessTime(source.getBusinessTime());
            copy.setStatus(source.getStatus());
            copy.setSortId(source.getSortId());
            copy.setXpValue(source.getXpValue());
            copy.setHookText(source.getHookText());
            copy.setCardHookLong(source.getCardHookLong());
            copy.setFragmentText(source.getFragmentText());
            copy.setCreateBy(COMMAND);
            copy.setCreateTime(new Date());
            requireOne(reopenMapper.insertReopenedNode(copy), "新期节点写入失败");
            if (source.getId() == null || copy.getId() == null) {
                throw new IllegalStateException("新期节点 id 重建失败");
            }
            ids.put(source.getId(), copy.getId());
            if (npcProfileMapper != null && source.getId() != null && copy.getId() != null) {
                NpcProfile sourceNpc = npcProfileMapper.selectNodeNpcByNodeId(source.getId());
                if (sourceNpc != null && sourceNpc.getId() != null) {
                    NpcProfile copyNpc = copyNodeNpc(sourceNpc, copy.getId());
                    requireOne(npcProfileMapper.insertNodeNpc(copyNpc), "新期节点 NPC 写入失败");
                    if (copyNpc.getId() == null) {
                        throw new IllegalStateException("新期节点 NPC id 重建失败");
                    }
                    npcIdMap.put(sourceNpc.getId(), copyNpc.getId());
                }
            }
        }
        // 节点 NPC 全部落库后:新期模板的 enter/outcome.npcId 按 old→new 重映射,映射不到置空
        for (Map.Entry<Long, String> entry : copiedTemplateBlocks.entrySet()) {
            String remapped = NarrativeNpcRefs.remapNpcIds(entry.getValue(), npcIdMap, npcWarnings);
            if (remapped == entry.getValue()) {
                continue;
            }
            CmsMemberTemplate patch = new CmsMemberTemplate();
            patch.setId(entry.getKey());
            patch.setAdvancedConfigJson(remapped);
            requireOne(templateMapper.updateCmsMemberTemplate(patch), "新期节点模板 NPC 引用回填失败");
        }
        for (String warning : npcWarnings) {
            logger.warn("重开一期 {}:{}", newTopicId, warning);
        }
        return ids;
    }

    /**
     * R13-NPC:模板引用 NPC 时给新期深拷贝一份(同归属也拷,否则没有可回写的行);
     * 未引用 NPC 的模板维持跨期共享,老行为不变。拷贝同时登记到 copiedTemplateBlocks 供二遍回写。
     */
    private Long editionTemplateId(Long templateId, Long newPayeeMemberId, Map<Long, Long> editionTemplateIds,
                                   Map<Long, String> copiedTemplateBlocks) {
        if (templateId == null || templateId <= 0L) {
            return templateId;
        }
        Long mapped = editionTemplateIds.get(templateId);
        if (mapped != null) {
            return mapped;
        }
        CmsMemberTemplate source = templateMapper.selectCmsMemberTemplateById(templateId);
        if (source == null) {
            // 读不到来源模板就无从判断它是否引用 NPC;按「无引用」共享会让新期节点挂一个不存在的模板。
            throw new IllegalStateException("新期节点来源模板不存在: templateId=" + templateId);
        }
        if (!NarrativeNpcRefs.hasNpcRefs(source.getAdvancedConfigJson())) {
            editionTemplateIds.put(templateId, templateId);
            return templateId;
        }
        CmsMemberTemplate copy = new CmsMemberTemplate();
        BeanUtils.copyProperties(source, copy);
        copy.setId(null);
        copy.setMemberId(newPayeeMemberId);
        copy.setOriginalTemplateId(source.getOriginalTemplateId() == null ? 0L : source.getOriginalTemplateId());
        copy.setIsSync(0);
        copy.setCreateBy(COMMAND);
        copy.setCreateTime(new Date());
        copy.setUpdateBy(COMMAND);
        copy.setUpdateTime(new Date());
        requireOne(templateMapper.insertCmsMemberTemplate(copy), "新期节点模板深拷贝失败");
        if (copy.getId() == null) {
            throw new IllegalStateException("新期节点模板 id 重建失败: templateId=" + templateId);
        }
        editionTemplateIds.put(templateId, copy.getId());
        copiedTemplateBlocks.put(copy.getId(), source.getAdvancedConfigJson());
        return copy.getId();
    }

    /**
     * R13-NPC:节点 NPC 副本 —— 只换挂载节点,展示与审核态沿用来源。
     * R14-VOICE:声音只随「已就绪」复制 —— 复刻中/复刻失败是来源节点那条在途或坏掉的状态,
     * 新期没有对应的上游任务,照抄只会把「永远等不来结果的复刻中」钉死在新期上;
     * 不复制时新 NPC 落 NONE(voice_status 库默认 0),商家可重新录制,不烧上游。
     */
    private static NpcProfile copyNodeNpc(NpcProfile source, Long newNodeId) {
        NpcProfile copy = new NpcProfile();
        copy.setScopeType(4);
        copy.setScopeId(newNodeId);
        copy.setName(source.getName());
        copy.setAvatar(source.getAvatar());
        copy.setPersona(source.getPersona() == null ? "" : source.getPersona());
        copy.setGreeting(source.getGreeting() == null ? "" : source.getGreeting());
        copy.setSort(source.getSort() == null ? 0 : source.getSort());
        copy.setAuditStatus(source.getAuditStatus());
        copy.setEnabled(source.getEnabled());
        if (Integer.valueOf(NodeNpcService.VOICE_READY).equals(source.getVoiceStatus())
                && source.getVoiceId() != null) {
            copy.setVoiceId(source.getVoiceId());
            copy.setVoiceStatus(NodeNpcService.VOICE_READY);
            copy.setVoiceSample(source.getVoiceSample());
        } else {
            copy.setVoiceStatus(NodeNpcService.VOICE_NONE);
        }
        return copy;
    }

    private void remapBlocks(List<CmsTopicChapter> chapters, Map<Long, Long> chapterIds, Map<Long, Long> nodeIds,
                             Map<Long, Long> npcIdMap) {
        List<String> npcWarnings = new ArrayList<>();
        for (CmsTopicChapter source : chapters) {
            if (source.getBlocksJson() == null) {
                continue;
            }
            Long chapterId = chapterIds.get(source.getId());
            String blocks = ChapterFlowCompiler.remapMaterializedNodeIds(source.getBlocksJson(), nodeIds, source.getId());
            // R19:beat 块上的 npcId 是新期新 id,与模板 enter/outcome 同一口径重映射;映射不到置空
            blocks = NarrativeNpcRefs.remapChapterBlockNpcIds(blocks, npcIdMap, npcWarnings);
            requireOne(reopenMapper.updateReopenedChapterBlocks(chapterId, blocks), "新期章节 blocks_json 回填失败");
        }
        for (String warning : npcWarnings) {
            logger.warn("重开一期:{}", warning);
        }
    }

    /** R8-STORY/R13-NPC:新期章节与 NPC 都是新 id,剧情 chapterId 与 call/epilogues 的 npcId 一并重映射。 */
    private void remapJourneyStory(CmsTopic newTopic, Map<Long, Long> chapterIds, Map<Long, Long> npcIdMap) {
        if (newTopic == null || newTopic.getId() == null
                || newTopic.getJourneyStoryJson() == null || newTopic.getJourneyStoryJson().trim().isEmpty()) {
            return;
        }
        List<String> npcWarnings = new ArrayList<>();
        String remapped = JourneyStory.remapNpcIds(
                JourneyStory.remapChapterIds(newTopic.getJourneyStoryJson(), chapterIds), npcIdMap, npcWarnings);
        for (String warning : npcWarnings) {
            logger.warn("重开一期 {}:{}", newTopic.getId(), warning);
        }
        requireOne(reopenMapper.updateReopenedJourneyStory(newTopic.getId(), remapped), "新期剧情配置回填失败");
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean same(Long left, Long right) {
        return left == null ? right == null : left.equals(right);
    }

    private static <T> List<T> nullToEmpty(List<T> value) {
        return value == null ? new ArrayList<T>() : value;
    }

    private static void requireOne(int rows, String message) {
        if (rows != 1) {
            throw new IllegalStateException(message);
        }
    }

    private static final class ContentSubject {
        private final String entityType;
        private final Long entityId;
        private final Long payeeMemberId;
        private final Long clubId;
        private final Long topicType;

        private ContentSubject(String entityType, Long entityId, Long payeeMemberId, Long clubId, Long topicType) {
            this.entityType = entityType;
            this.entityId = entityId;
            this.payeeMemberId = payeeMemberId;
            this.clubId = clubId;
            this.topicType = topicType;
        }

        private String getEntityType() { return entityType; }
        private Long getEntityId() { return entityId; }
        private Long getPayeeMemberId() { return payeeMemberId; }
        private Long getClubId() { return clubId; }
        private Long getTopicType() { return topicType; }
    }
}
