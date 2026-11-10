package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.JourneyStory;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsCollaborators;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsCollaboratorsMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.service.ITopicContentCopyService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicCompletionRulePolicy;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.chengyinhub.business.service.support.TopicTemplateRuleIdentityRemapper;
import com.chengyinhub.business.service.support.TopicPlatformContentSlotGuard;
import com.chengyinhub.business.service.support.TopicContentWriteGuard;
import com.chengyinhub.business.service.support.TopicLocationContract;
import com.chengyinhub.business.support.ChapterRecruitmentRules;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.RoleEntityType;
import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

/** Complete content-copy transaction. Publication, transfer, sale and settlement remain with their existing owners. */
@Service
public class TopicContentCopyServiceImpl implements ITopicContentCopyService {
    private static final Logger logger = LoggerFactory.getLogger(TopicContentCopyServiceImpl.class);
    private final TopicCompletionRulePolicy topicCompletionRulePolicy = new TopicCompletionRulePolicy();
    private final TopicRouteConfigValidator topicRouteConfigValidator = new TopicRouteConfigValidator();
    @Value("${chengyin.platform.member-id:100000}")
    private Long platformMemberId;
    @Autowired private CmsTopicMapper cmsTopicMapper;
    @Autowired private CmsTopicChapterMapper cmsTopicChapterMapper;
    @Autowired private CmsTopicNodeMapper cmsTopicNodeMapper;
    @Autowired private CmsMemberTemplateMapper cmsMemberTemplateMapper;
    @Autowired private CmsCollaboratorsMapper cmsCollaboratorsMapper;
    @Autowired private NpcProfileMapper npcProfileMapper;
    @Autowired private OmsTicketMapper omsTicketMapper;
    @Autowired private ISysCategoryService sysCategoryService;
    @Autowired private IProductRoleService productRoleService;
    @Autowired private ICmsMemberTemplateService cmsMemberTemplateService;
    @Autowired private TopicPlatformContentSlotGuard platformContentSlotGuard;
    @Autowired private ThemeStateKeyService themeStateKeyService;
    @Autowired private TopicContentWriteGuard contentWriteGuard;
    @Autowired private com.chengyinhub.business.service.ITopicContentRevisionService contentRevisions;
    @Autowired @org.springframework.context.annotation.Lazy
    private com.chengyinhub.business.service.ITopicRouteRuntimeService topicRouteRuntimeService;

    @Autowired
    private com.chengyinhub.business.service.support.WorkshopLicenseGuard workshopLicenses;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long copyCmsTopic(Long id, Map<String, Object> params) {
        return copyCmsTopicWithContentOwner(id, params, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long copyOfficialCmsTopic(Long id, Map<String, Object> params) {
        if (params != null && params.get("memberId") != null) {
            throw new IllegalArgumentException("官方主题复制不得指定 memberId；收款主体固定为平台配置账号");
        }
        return copyCmsTopicWithContentOwner(id, params, RoleEntityType.PLATFORM);
    }

    private Long copyCmsTopicWithContentOwner(Long id, Map<String, Object> params,
                                               String forcedContentEntityType) {
        // 采用方式在写任何行之前校验；旧客户端不传 bindingIntent = LEGACY，复制结果保持原样。
        String bindingIntent = TopicLocationContract.intent(paramString(params, "bindingIntent"));
        String targetCityCode = TopicLocationContract.cityCode(paramString(params, "targetCityCode"), "targetCityCode");
        // 采用模式与目标城市的合同校验必须在**任何写入之前**完成：
        // 若留到末尾的 buildAdoptionContract 才查，副本父行/章节/节点已经落库，
        // 事务回滚只是补救，契约要的是「不合法请求不产生任何 copy/chapter/node 写」。
        assertAdoptionRequestLegal(bindingIntent, targetCityCode);
        // 与章节/节点/路线写者共用同一把父行 fence：来源快照与子内容一致，且本事务只写副本。
        // 手工 new 的旧单测没注入(同 TopicPublicationContentGuard 的既有口径)：运行态必注入。
        if (contentWriteGuard != null) contentWriteGuard.lockTopicContent(id);
        CmsTopic originalTopic = contentRevisions == null ? cmsTopicMapper.selectCmsTopicById(id)
                : cmsTopicMapper.selectCmsTopicByIdForUpdate(id);
        if (originalTopic == null) {
            throw new RuntimeException("原主题不存在");
        }
        if (Integer.valueOf(2).equals(originalTopic.getDelFlag())) {
            throw new IllegalArgumentException("原主题已删除，不能复制");
        }
        // 与来源城市的关系（目标城市不能等于/必须等于来源城市）同样要在写前判：
        // 这一步只需只读的源主题，不需要任何写。
        assertAdoptionCityRelationLegal(bindingIntent, targetCityCode, originalTopic.getCityCode());

        Integer copyType = Convert.toInt(params == null ? null : params.get("copyType"), 1);
        Long sourceRevisionId = validateSourceRevision(originalTopic, params, copyType);
        // 先冻结并锁全量源内容，再做普通读，避免 RR 的旧快照污染后续复制。
        if (RoleEntityType.PLATFORM.equals(forcedContentEntityType) && !productRoleService.isPlatformOwned(id)) {
            throw new IllegalArgumentException("官方主题入口只能复制 PLATFORM 内容主体；广场主题请从其发布链路复制");
        }
        CmsTopic newTopic = copyTopicBasicInfo(originalTopic, bindingIntent, targetCityCode);
        Long memberId = params == null ? null : Convert.toLong(params.get("memberId"));
        if (RoleEntityType.PLATFORM.equals(forcedContentEntityType)) {
            newTopic.setMemberId(platformMemberId);
        } else if (memberId != null) {
            newTopic.setMemberId(memberId);
        }

        if (cmsTopicMapper.insertCmsTopic(newTopic) != 1
                || newTopic.getId() == null || newTopic.getId() <= 0L) {
            throw new IllegalStateException("复制主题失败: 主题写入未生成有效ID");
        }
        Long newTopicId = newTopic.getId();
        if (RoleEntityType.PLATFORM.equals(forcedContentEntityType)) {
            platformContentSlotGuard.declareRequiredPlatformContentSlot(newTopicId);
        } else if (memberId != null) {
            productRoleService.declareContentSlot(newTopicId, RoleEntityType.MEMBER, memberId);
        } else {
            logger.warn("[P0-35] 主题 {} 由 {} 复制而来但无法判定内容方(params 未带 memberId),未落内容槽。"
                    + "票款归属与 REVSHARE 资格待人工确认 —— 用后台 PUT /business/topic/{}/content-slot 补",
                    newTopicId, id, newTopicId);
        }

        if (copyType >= 2) {
            Map<Long, Long> npcIdMap = new LinkedHashMap<Long, Long>();
            CopiedContentIds copiedMaps = copyChaptersAndNodes(id, newTopic, npcIdMap, bindingIntent, sourceRevisionId != null);
            // R8-STORY:副本章节是新 id,剧情 chapterId 必须跟着重映射,否则副本剧情指向来源主题;
            // R13-NPC:副本 NPC 同样是新 id,call / epilogues 的 npcId 一起重映射。
            remapCopiedJourneyStory(id, newTopicId, originalTopic.getJourneyStoryJson(), copiedMaps.chapterOldToNew, copiedMaps.oldToNew, npcIdMap);
            preserveCopiedCompletionRule(originalTopic, newTopic);
            // 站点玩法里引用的状态键要跟着走:副本不注册,发布闸「以下状态键未注册」把套模板的人整个挡住
            copyStateKeys(id, newTopicId);
            materializeLocationContract(originalTopic, newTopic, copiedMaps, bindingIntent, targetCityCode, sourceRevisionId);
        }
        if (copyType >= 3) {
            copyCollaborators(id, newTopicId);
            // p3 N6(第十二轮 k9):「应用」模板后票种丢了,草稿只剩空票务。整包复制把主题票种一起带上;
            // 只抄票的设定,库存回满,不带场次票、报名与售出。副本是草稿,未上架,不会开卖。
            copyTopicTicketTypes(id, newTopic);
        }
        if (params != null && Boolean.TRUE.equals(params.get("saveDraft"))
                && cmsTopicMapper.updateTopicDraftState(newTopicId, 1) != 1) {
            throw new IllegalStateException("复制主题草稿保存失败");
        }
        if(workshopLicenses!=null)workshopLicenses.requireTheme(newTopic);
        return newTopicId;
    }

    private static String paramString(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Long validateSourceRevision(CmsTopic source, Map<String, Object> params, int copyType) {
        Long requested = null;
        if (params != null && params.get("sourceContentRevisionId") != null) {
            String raw = String.valueOf(params.get("sourceContentRevisionId"));
            if (!raw.matches("[1-9][0-9]*")) throw new IllegalArgumentException("sourceContentRevisionId 必须是正整数");
            try { requested = Long.valueOf(raw); }
            catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("sourceContentRevisionId 超出范围", invalid);
            }
        }
        if (copyType < 2) {
            if (requested != null) throw new IllegalArgumentException("锁定来源修订需要复制完整主题内容");
            return null;
        }
        // 手工 new 的历史夹具未装配服务；运行态为必需依赖，显式请求绝不降级。
        if (contentRevisions == null) {
            if (requested != null) throw new IllegalArgumentException("来源修订服务不可用");
            return null;
        }
        com.chengyinhub.business.domain.CmsTopicContentRevision revision =
                contentRevisions.currentForAdoption(source.getId());
        if (revision == null) {
            if (requested != null) throw new IllegalArgumentException("来源尚无可采用的真实发布修订");
            return null;
        }
        if (!Objects.equals(source.getId(), revision.getTopicId()) || revision.getId() == null
                || revision.getId() <= 0L || requested != null && !requested.equals(revision.getId())) {
            throw new IllegalArgumentException("来源修订与当前发布不一致");
        }
        String frozen = revision.getSnapshotJson();
        if (source.getPublishedConfigVersion() == null || source.getPublishedConfigVersion() <= 0L
                || StringUtils.isBlank(frozen)
                || !Objects.equals(revision.getContentSha256(),
                    com.chengyinhub.business.service.support.CanonicalDigest.sha256Hex(frozen))
                || !samePublishedSnapshot(frozen, source.getPublishedRouteGraphJson())) {
            throw new IllegalArgumentException("来源修订与当前发布快照不一致");
        }
        try {
            // shortcut: 仅采用当前发布游玩内容，历史修订须等作者树可完整还原后再开放。
            String live = topicRouteRuntimeService.freezePublication(source,
                    "BRANCH_GRAPH".equals(source.getRouteMode()) ? source.getRouteGraphJson() : null,
                    themeStateKeyService.listCurrentDeclarations(source.getId()));
            CmsTopicChapter chapterQuery = new CmsTopicChapter();
            chapterQuery.setTopicId(source.getId());
            if (CollectionUtils.isEmpty(cmsTopicChapterMapper.selectCmsTopicChapterList(chapterQuery))) {
                throw new IllegalArgumentException("来源发布内容没有可完整复制的章节");
            }
            if (!topicRouteConfigValidator.readTree(frozen).equals(topicRouteConfigValidator.readTree(live))) {
                throw new IllegalArgumentException("来源草稿已偏离发布修订，请先发布再采用");
            }
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new IllegalArgumentException("来源草稿不能按当前发布修订采用：" + invalid.getMessage(), invalid);
        }
        return revision.getId();
    }

    /**
     * 修订行 snapshot_json 是 longtext,原样保存发布时的字节(content_sha256 也按它算);
     * cms_topic.published_route_graph_json 是 MySQL JSON 列,读回时键序与空白已被服务器重排。
     * 两者只能按 JSON 语义比较:字符串相等在真库上恒不成立,会让每一次整主题采用都被拒。
     * 内容哈希仍对修订原文校验,语义比较只用来确认「发布头就是这份修订」。
     */
    private boolean samePublishedSnapshot(String frozen, String publishedHead) {
        if (StringUtils.isBlank(publishedHead)) return false;
        if (frozen.equals(publishedHead)) return true;
        try {
            return topicRouteConfigValidator.readTree(frozen).equals(topicRouteConfigValidator.readTree(publishedHead));
        } catch (IllegalArgumentException | IllegalStateException unreadable) {
            return false;
        }
    }

    /**
     * 采用请求自身的合法性：只看请求参数，不读库、不写行。
     *
     * <p>放在 {@code copyCmsTopicWithContentOwner} 的第一行之后调用，保证「不合法请求一个写都不产生」。
     * 旧客户端不传 {@code bindingIntent}（= LEGACY）与只传 {@code bindingIntent=SAME_CITY} 都照旧放行；
     * 显式 {@code LEGACY} 由 {@link TopicLocationContract#intent} 直接拒（裸 LEGACY 不是客户端契约）。
     */
    private static void assertAdoptionRequestLegal(String bindingIntent, String targetCityCode) {
        if (TopicLocationContract.isNewCity(bindingIntent) && targetCityCode == null) {
            throw new IllegalArgumentException("targetCityCode 不能为空:换城采用必须指定目标城市");
        }
    }

    /**
     * 目标城市与来源城市的关系：这个判定只需要只读的源主题，所以同样放在第一次写入之前。
     *
     * <p>与 {@link TopicLocationContract#buildAdoptionContract} 里的同名判定同源（同一个合同、
     * 同一套错误文案）：那边留在末尾做最后一道防线，这里提前挡住「先写副本再抛」。
     */
    private static void assertAdoptionCityRelationLegal(String bindingIntent, String targetCityCode,
                                                        String sourceCityCode) {
        if (targetCityCode == null || sourceCityCode == null) return;
        if (TopicLocationContract.isNewCity(bindingIntent) && sourceCityCode.equals(targetCityCode)) {
            throw new IllegalArgumentException("目标城市与来源相同，请使用同城复用");
        }
        if (TopicLocationContract.INTENT_SAME_CITY.equals(bindingIntent)
                && !sourceCityCode.equals(targetCityCode)) {
            throw new IllegalArgumentException("同城复用要求目标城市与来源一致；跨城请使用 NEW_CITY");
        }
    }

    /**
     * 写入副本地点合同并回读校验。
     *
     * <p>旧版采用（LEGACY）也写一份合同：来源血缘（sourceTemplateTopicId + 源 configVersion）可追，
     * 且不会把「没有合同」误读成「没有地点要求」。源合同非法直接失败，不静默丢要求。
     */
    private void materializeLocationContract(CmsTopic sourceTopic, CmsTopic newTopic,
                                             CopiedContentIds copiedMaps, String bindingIntent,
                                             String targetCityCode, Long sourceRevisionId) {
        if (newTopic.getId() == null || newTopic.getId() <= 0L) {
            throw new IllegalStateException("地点合同写入失败：副本主题身份缺失");
        }
        String contract = TopicLocationContract.buildAdoptionContract(sourceTopic, copiedMaps.sourceNodes,
                copiedMaps.oldToNew, bindingIntent, targetCityCode, new Date(), sourceRevisionId);

        // 唯一写口：owner 在 SQL 里再判一次；通用 updateCmsTopic 不带这一列（见 mapper 注释）。
        int rows = cmsTopicMapper.updateTopicLocationContract(newTopic.getId(), newTopic.getMemberId(),
                contract, "system_copy", new Date());
        if (rows != 1) {
            throw new IllegalStateException("地点合同保存失败: 副本不归本次复制的内容主体");
        }
        CmsTopic readBack = cmsTopicMapper.selectCmsTopicById(newTopic.getId());
        if (readBack == null
                || !java.util.Objects.equals(readBack.getCityCode(), newTopic.getCityCode())
                || !TopicLocationContract.same(contract, readBack.getContentContractJson())) {
            throw new IllegalStateException("地点合同回读校验失败");
        }
    }

    private void copyStateKeys(Long sourceTopicId, Long copyTopicId) {
        // 手工 new 的旧单测没注入(同 TopicPublicationContentGuard.assertStateKeysRegistered),Spring 运行态必注入
        if (themeStateKeyService == null) return;
        List<ThemeStateKey> copies = new ArrayList<ThemeStateKey>();
        for (ThemeStateKey source : themeStateKeyService.listByTopic(sourceTopicId)) {
            ThemeStateKey key = new ThemeStateKey();
            key.setStateKey(source.getStateKey());
            key.setKeyType(source.getKeyType());
            key.setScope(source.getScope());
            key.setLabel(source.getLabel());
            key.setInitialValue(source.getInitialValue());
            key.setMinValue(source.getMinValue());
            key.setMaxValue(source.getMaxValue());
            key.setDisplayType(source.getDisplayType());
            key.setColor(source.getColor());
            key.setIcon(source.getIcon());
            key.setSortOrder(source.getSortOrder());
            key.setVisible(source.getVisible());
            key.setPersistScope(source.getPersistScope());
            key.setRulesJson(source.getRulesJson());
            copies.add(key);
        }
        themeStateKeyService.register(copyTopicId, copies);
    }

    private void preserveCopiedCompletionRule(CmsTopic original, CmsTopic copy) {
        if (StringUtils.isBlank(original.getCompleteRuleJson())) return;
        boolean sameOwner = original.getMemberId() != null && original.getMemberId().equals(copy.getMemberId());
        if (!sameOwner && !Integer.valueOf(ProductType.ORIENTEERING).equals(copy.getProductType())) return;
        if (copy.getId() == null || copy.getId() <= 0L) {
            throw new IllegalStateException("完整复制失败，副本主题身份缺失");
        }
        CmsTopicNode sourceQuery = new CmsTopicNode();
        sourceQuery.setTopicId(original.getId());
        CmsTopicNode copiedQuery = new CmsTopicNode();
        copiedQuery.setTopicId(copy.getId());
        int sourceCount = nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(sourceQuery)).size();
        int copiedCount = nullToEmpty(cmsTopicNodeMapper.selectCmsTopicNodeList(copiedQuery)).size();
        if (sourceCount != copiedCount) {
            throw new IllegalStateException("完整复制节点数量不一致，未保留通关规则");
        }
        String rule = topicCompletionRulePolicy.validateAndNormalize(
                original.getCompleteRuleJson(), copiedCount, copy.getProductType());
        if (!sameOwner) {
            // 跨作者只带节点数量合同，奖励、核销及其他配置仍须由副本作者自行配置。
            try {
                ObjectMapper json = new ObjectMapper();
                JsonNode root = json.readTree(rule);
                JsonNode source = root.get("nodeCompletion");
                if (source == null || source.isNull()) return;
                ObjectNode safe = json.createObjectNode();
                ObjectNode node = safe.putObject("nodeCompletion");
                node.put("mode", source.path("mode").asText("ALL"));
                if ("AT_LEAST".equals(node.path("mode").asText())) {
                    node.put("requiredCount", source.path("requiredCount").asInt());
                }
                rule = safe.toString();
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("主题通关规则不是合法 JSON", e);
            }
        }
        CmsTopic patch = new CmsTopic();
        patch.setId(copy.getId());
        patch.setCompleteRuleJson(rule);
        if (cmsTopicMapper.updateCmsTopic(patch) != 1) {
            throw new IllegalStateException("副本通关规则保存失败");
        }
    }

    private CmsTopic copyTopicBasicInfo(CmsTopic original, String bindingIntent, String targetCityCode) {
        CmsTopic newTopic = new CmsTopic();

        // 复制所有字段，除了ID、状态和时间相关字段
        newTopic.setMemberId(platformMemberId);
        newTopic.setTopicType(original.getTopicType());
        // [P0-02] 产品真源必须跟着复制 —— 这是**手抄清单**(不是 copyProperties),加字段不会自动带。
        // 漏了的后果不是「副本少个字段」而是**存量 NULL 集合永不收敛**:模板 /use、save-as 会持续
        // 新生 product_type=NULL 的主题 ⇒ resolveMode 的双读 fallback 永远删不掉。
        // ⚠️ 同一个陷阱在 copyChaptersAndNodes 也有(章节四列已补)—— 给这两张表加列时都要来这里补一行。
        newTopic.setProductType(original.getProductType());
        newTopic.setCircleThemeCode(original.getCircleThemeCode());
        newTopic.setGameModuleConfigJson(original.getGameModuleConfigJson());
        // [R5] 数值规则(生命/幸运/难度/恢复)属于主题内容的一部分,模板复制必须一起带 ——
        // 同一份手抄清单:漏抄副本会静默丢规则,玩家侧只读下发直接变回「未配置」。
        newTopic.setJourneyRulesJson(original.getJourneyRulesJson());
        newTopic.setThemeRuntimeConfigJson(original.getThemeRuntimeConfigJson());
        newTopic.setLookJson(original.getLookJson());
        // [R8] 主题级剧情配置同样属于内容的一部分:模板 / 官方复制必须一起带 ——
        // 章节复制完成后由 copyCmsTopicWithContentOwner 按 old→new 章节 id 重映射。
        newTopic.setJourneyStoryJson(original.getJourneyStoryJson());
        newTopic.setSourceTemplateTopicId(original.getSourceTemplateTopicId() != null
                ? original.getSourceTemplateTopicId()
                : (original.getCircleThemeCode() == null ? null : original.getId()));
        // 节点尚未复制，先落兼容线性；copyNodes 取得 nodeIdMap 后再同事务写分支图。
        newTopic.setRouteMode("LINEAR");
        newTopic.setRouteGraphJson(null);
        newTopic.setConfigVersion(1L);
        newTopic.setTopicId(original.getTopicId());
        newTopic.setName(original.getName());
        newTopic.setSubtitle(original.getSubtitle());
        newTopic.setDescription(original.getDescription());
        // 城市事实与故事内容分开：跨城采用时主题地址属于旧城事实，必须清空；同城/旧版沿用。
        // 目的地城市：NEW_CITY 写目标城市；SAME_CITY/LEGACY 继承来源城市（来源未标注则保持 NULL）。
        boolean newCity = TopicLocationContract.INTENT_NEW_CITY.equals(bindingIntent);
        newTopic.setAddressName(newCity ? null : original.getAddressName());
        newTopic.setCityCode(newCity ? targetCityCode : original.getCityCode());
        newTopic.setTotalTime(original.getTotalTime());
        newTopic.setTotalMileage(original.getTotalMileage());
        newTopic.setLocationCount(original.getLocationCount());
        newTopic.setTemplateCount(original.getTemplateCount());
        newTopic.setStartDate(original.getStartDate());
        newTopic.setEndDate(original.getEndDate());
        newTopic.setImgUrl(original.getImgUrl());
        newTopic.setImgArr(original.getImgArr());
        newTopic.setCategoryIds(original.getCategoryIds());

        // 新主题状态重置
        newTopic.setStatus(0); // 待审核
        newTopic.setReason(null); // 清空驳回原因
        newTopic.setUserStatus(0); // 用户端未上架
        newTopic.setMerchantStatus(0); // 商家端未上架
        newTopic.setMerchantSignUpStartDate(null); // 清空报名时间
        newTopic.setMerchantSignUpEndDate(null);

        newTopic.setDelFlag(0);
        newTopic.setIsSync(0); // 不同步到创意广场
        newTopic.setIsRecommend(0); // 不推荐
        newTopic.setCheckStatus(0); // 待审核
        newTopic.setIsSettlement(0); // 未结算

        // 设置创建信息
        newTopic.setCreateBy("system_copy");
        newTopic.setCreateTime(new Date());
        newTopic.setUpdateBy("system_copy");
        newTopic.setUpdateTime(new Date());

        return newTopic;
    }

    private CopiedContentIds copyChaptersAndNodes(Long originalTopicId, CmsTopic newTopic,
                                                  Map<Long, Long> npcIdMap, String bindingIntent, boolean revisionAdoption) {
        CopiedContentIds copiedMaps = new CopiedContentIds();
        Long newTopicId = newTopic.getId();
        // 查询原主题的章节
        CmsTopicChapter chapterQuery = new CmsTopicChapter();
        chapterQuery.setTopicId(originalTopicId);
        List<CmsTopicChapter> originalChapters = cmsTopicChapterMapper.selectCmsTopicChapterList(chapterQuery);

        if (CollectionUtils.isEmpty(originalChapters)) {
            return copiedMaps;
        }

        // 章节ID映射：旧章节ID -> 新章节ID
        Map<Long, Long> chapterIdMap = new HashMap<>();
        Map<Long, CmsTopicChapter> copiedChapterMap = new HashMap<>();

        // 复制章节
        for (CmsTopicChapter originalChapter : originalChapters) {
            assertCopiedChapterRecruitment(originalChapter, newTopic);
            boolean sourceRevshare = TermsMode.REVSHARE.equals(originalChapter.getTermsMode());
            CmsTopicChapter newChapter = new CmsTopicChapter();

            // 复制章节基本信息
            newChapter.setTopicId(newTopicId);
            newChapter.setName(originalChapter.getName());
            newChapter.setDescription(originalChapter.getDescription());
            newChapter.setBlocksJson(originalChapter.getBlocksJson());
            newChapter.setAtmospherePreset(originalChapter.getAtmospherePreset());
            newChapter.setAudioUrl(originalChapter.getAudioUrl());
            newChapter.setVideoUrl(originalChapter.getVideoUrl());
            newChapter.setVideoPosterUrl(originalChapter.getVideoPosterUrl());
            newChapter.setImgArr(originalChapter.getImgArr());
            newChapter.setTotalTime(originalChapter.getTotalTime());
            newChapter.setTotalMileage(originalChapter.getTotalMileage());
            newChapter.setLocationCount(originalChapter.getLocationCount());
            newChapter.setTemplateCount(originalChapter.getTemplateCount());
            newChapter.setSortId(originalChapter.getSortId());
            // ⚠️ 这里是逐字段手抄(不是 copyProperties):章节新增字段必须同步补一行,
            //    漏了副本就静默丢掉该配置——零报错,只有开卖后发现商家匹配不上/门槛失效才暴露
            newChapter.setCategory(originalChapter.getCategory());
            newChapter.setCategoryId(originalChapter.getCategoryId());
            newChapter.setRecruitEnabled(originalChapter.getRecruitEnabled());
            newChapter.setPerkMinValue(originalChapter.getPerkMinValue());
            // ⚠️ 复制主题(移交给俱乐部)时也要带上玩法边界 —— 漏了会让新主题的招商条款
            // 静默退化成「不限」,而运营看不出差别。
            newChapter.setAllowedValidationMethods(originalChapter.getAllowedValidationMethods());
            newChapter.setMaxNodeXp(originalChapter.getMaxNodeXp());
            // 新行不再传播历史 null：0 是「不限」的显式值，也是 max_merchant 的持久化口径。
            newChapter.setMaxMerchant(originalChapter.getMaxMerchant() == null ? 0 : originalChapter.getMaxMerchant());
            newChapter.setRequired(originalChapter.getRequired());
            // [D21] 档位。上面那句「漏了副本就静默丢掉该配置」说的就是这一行 ——
            // terms_mode 是 07-16 加的列,当时没补进来。以前无害(全仓没有任何地方把它设成 PERK 以外的值,
            // 恒取 DDL 默认),但档位一旦真能变,漏抄 = 计酬/引流章被静默降级成权益档:
            // 副本会去等一批永远不会来的 PERK 供给,而没有任何报错。
            // 例外是 REVSHARE：copy 的三条真实入口不会把副本声明成 PLATFORM。把平台成本
            // 原样带过去会形成二清/死章，故明确降为无成本的 PERK，且清掉费用上限；运营完成
            // 内容槽改判后，再走专用供给条款入口恢复计酬。
            newChapter.setTermsMode(sourceRevshare ? TermsMode.PERK : originalChapter.getTermsMode());
            newChapter.setMaxPerHeadFee(sourceRevshare ? null : originalChapter.getMaxPerHeadFee());
            newChapter.setCircleCandidateCode(originalChapter.getCircleCandidateCode());
            newChapter.setCircleRole(originalChapter.getCircleRole());
            newChapter.setCirclePolicy(originalChapter.getCirclePolicy());
            // 模板副本必须由城市代理重新选择，不能把 8 个候选全当成已发布阵容。
            boolean copiedFromCircleTemplate = newTopic.getSourceTemplateTopicId() != null
                    && newTopic.getSourceTemplateTopicId().equals(originalTopicId);
            newChapter.setCircleEnabled(copiedFromCircleTemplate ? 0
                    : (originalChapter.getCircleEnabled() == null ? 1 : originalChapter.getCircleEnabled()));
            if (sourceRevshare) {
                logger.warn("[D21] 复制主题时降级计酬章节 sourceTopicId={} targetTopicId={} chapterId={}："
                                + "REVSHARE -> PERK，已清空人头费上限；如需计酬请先完成副本内容槽改判",
                        originalTopicId, newTopicId, originalChapter.getId());
            }

            // 设置创建信息
            newChapter.setCreateBy("system_copy");
            newChapter.setCreateTime(new Date());
            newChapter.setUpdateBy("system_copy");
            newChapter.setUpdateTime(new Date());

            // 插入新章节
            if (cmsTopicChapterMapper.insertCmsTopicChapter(newChapter) != 1
                    || newChapter.getId() == null || newChapter.getId() <= 0L) {
                throw new IllegalStateException("复制主题失败: 章节写入未生成有效ID");
            }

            // 保存ID映射
            chapterIdMap.put(originalChapter.getId(), newChapter.getId());
            copiedChapterMap.put(originalChapter.getId(), newChapter);
        }

        // 复制节点
        com.fasterxml.jackson.databind.node.ObjectNode runtimeConfig = com.chengyinhub.business.service.support.ThemeRuntimeConfig.parseDraft(newTopic.getThemeRuntimeConfigJson());
        Map<Long, Long> nodeIdMap = copyNodes(originalTopicId, newTopicId, chapterIdMap, newTopic.getMemberId(), npcIdMap, runtimeConfig, bindingIntent, copiedMaps, revisionAdoption);
        CmsTopic runtimePatch = new CmsTopic(); runtimePatch.setId(newTopicId);
        runtimePatch.setThemeRuntimeConfigJson(TopicTemplateRuleIdentityRemapper.remap(com.chengyinhub.business.service.support.ThemeRuntimeConfig.remapAll(runtimeConfig == null ? newTopic.getThemeRuntimeConfigJson() : runtimeConfig.toString(), nodeIdMap, chapterIdMap, npcIdMap), nodeIdMap));
        if (runtimePatch.getThemeRuntimeConfigJson() != null && cmsTopicMapper.updateCmsTopic(runtimePatch) != 1) throw new IllegalStateException("主题运行配置复制失败");
        CmsTopic originalTopic = cmsTopicMapper.selectCmsTopicById(originalTopicId);
        if (originalTopic != null && "BRANCH_GRAPH".equals(originalTopic.getRouteMode())) {
            String graph = topicRouteConfigValidator.remapNodeIdsForTopicCopy(
                    originalTopic.getRouteGraphJson(), nodeIdMap);
            graph = topicRouteConfigValidator.validateAndNormalize(graph,
                    new LinkedHashSet<Long>(nodeIdMap.values()));
            if (cmsTopicMapper.updateRouteDefinition(newTopicId, "BRANCH_GRAPH", graph, 1L, 1L,
                    new Date()) != 1) {
                throw new IllegalStateException("复制主题失败: 路线图写入失败");
            }
        }
        // R19:章节故事流 beat 块上的 npcId 与模板同一套 old→new 重映射;映射不到置空并告警
        List<String> chapterNpcWarnings = new ArrayList<String>();
        for (CmsTopicChapter originalChapter : originalChapters) {
            if (originalChapter.getBlocksJson() == null || originalChapter.getBlocksJson().trim().isEmpty()) {
                continue;
            }
            CmsTopicChapter copiedChapter = copiedChapterMap.get(originalChapter.getId());
            String remappedBlocks = ChapterFlowCompiler.remapMaterializedNodeIds(
                    originalChapter.getBlocksJson(), nodeIdMap, originalChapter.getId());
            copiedChapter.setBlocksJson(com.chengyinhub.business.util.NarrativeNpcRefs.remapChapterBlockNpcIds(
                    remappedBlocks, npcIdMap, chapterNpcWarnings));
            if (cmsTopicChapterMapper.updateCmsTopicChapter(copiedChapter) <= 0) {
                throw new IllegalStateException("复制主题失败: 副本章节 blocks 写入失败, chapterId="
                        + copiedChapter.getId());
            }
        }
        for (String warning : chapterNpcWarnings) {
            logger.warn("复制主题 {} -> {}:{}", originalTopicId, newTopicId, warning);
        }
        copiedMaps.chapterOldToNew.putAll(chapterIdMap);
        copiedMaps.oldToNew.putAll(nodeIdMap);
        return copiedMaps;
    }

    private void assertCopiedChapterRecruitment(CmsTopicChapter chapter, CmsTopic destinationTopic) {
        ChapterRecruitmentRules.assertRecruitEnabled(chapter.getRecruitEnabled());
        ChapterRecruitmentRules.assertMaxMerchant(chapter.getMaxMerchant());
        assertCopiedChapterSupplyTerms(chapter);
        if (!ChapterRecruitmentRules.isEnabled(chapter.getRecruitEnabled())) {
            return;
        }
        if (destinationTopic.getProductType() == null
                || destinationTopic.getProductType() != ProductType.FREE_EXPLORE) {
            throw new IllegalStateException("仅自由探索主题可开启章节商家承接");
        }
        SysCategory category = chapter.getCategoryId() == null ? null
                : sysCategoryService.selectSysCategoryById(chapter.getCategoryId().longValue());
        ChapterRecruitmentRules.assertMerchantCategory(chapter.getCategoryId(), category);
    }

    private void assertCopiedChapterSupplyTerms(CmsTopicChapter chapter) {
        String termsMode = chapter.getTermsMode() == null ? TermsMode.PERK : chapter.getTermsMode();
        if (!TermsMode.isValid(termsMode)) {
            throw new IllegalStateException("章节条款档不合法:" + chapter.getTermsMode());
        }
        ChapterRecruitmentRules.assertPerkMinValueForTerms(termsMode, chapter.getPerkMinValue());
        if (TermsMode.REVSHARE.equals(termsMode)) {
            if (chapter.getMaxPerHeadFee() == null || chapter.getMaxPerHeadFee().signum() <= 0) {
                throw new IllegalStateException("计酬档必须配置每人次人头费上限(须大于 0)");
            }
        } else if (chapter.getMaxPerHeadFee() != null) {
            throw new IllegalStateException("只有计酬档谈人头费上限,权益档/引流档请留空");
        }
    }

    private Map<Long, Long> copyNodes(Long originalTopicId, Long newTopicId, Map<Long, Long> chapterIdMap,
                                      Long newOwnerMemberId, Map<Long, Long> npcIdMap,
                                      com.fasterxml.jackson.databind.node.ObjectNode runtimeConfig,
                                      String bindingIntent, CopiedContentIds copiedMaps, boolean revisionAdoption) {
        Map<Long, Long> nodeIdMap = new HashMap<>();
        // 查询原主题的所有节点
        CmsTopicNode nodeQuery = new CmsTopicNode();
        nodeQuery.setTopicId(originalTopicId);
        List<CmsTopicNode> originalNodes = cmsTopicNodeMapper.selectCmsTopicNodeList(nodeQuery);
        if (copiedMaps != null) copiedMaps.sourceNodes = originalNodes;

        if (CollectionUtils.isEmpty(originalNodes)) {
            return nodeIdMap;
        }

        // 跨城采用：节点上的地址/坐标/现场营业条件/实景图都是旧城事实，不得原样搬进新实例。
        // 要求本身留在 content_contract_json 的 locationSlots；绑定状态由采用合同标 REBIND_REQUIRED。
        boolean newCity = TopicLocationContract.INTENT_NEW_CITY.equals(bindingIntent);

        Map<Long, Long> templateIdMap = new HashMap<>();
        // 节点级前置解锁引用的是本主题节点 id,必须等全部副本节点落库后按 nodeIdMap 重映射
        Map<Long, Long> pendingUnlockByNewNodeId = new HashMap<>();
        // R13-NPC:副本模板 id → 源块配置;节点 NPC 全部落库后再按 npcIdMap 重写 enter/outcome.npcId
        Map<Long, String> copiedTemplateBlocks = new LinkedHashMap<Long, String>();
        List<String> npcWarnings = new ArrayList<String>();
        for (CmsTopicNode originalNode : originalNodes) {
            CmsTopicNode newNode = new CmsTopicNode();

            // 复制节点基本信息
            newNode.setTopicId(newTopicId);

            // 使用章节ID映射获取新章节ID
            Long newChapterId = chapterIdMap.get(originalNode.getChapterId());
            if (newChapterId != null) {
                newNode.setChapterId(newChapterId);
            } else {
                newNode.setChapterId(0L); // 如果没有找到映射，设为0
            }

            newNode.setName(originalNode.getName());
            newNode.setDescription(originalNode.getDescription());
            // 跨城采用不得把旧城地址/坐标/实景图当新实例已确认地点；要求与待绑定状态走地点合同。
            newNode.setAddress(newCity ? null : originalNode.getAddress());
            newNode.setLongitude(newCity ? null : originalNode.getLongitude());
            newNode.setLatitude(newCity ? null : originalNode.getLatitude());
            newNode.setImgUrl(newCity ? null : originalNode.getImgUrl());
            newNode.setNodeTime(originalNode.getNodeTime());
            // [W4闭环] 节点模板是私有的(cms_member_template):副本若原样沿用源作者的 templateId,
            // 新归属在编辑器里一保存就会被 assertTemplateOwned 以「不能引用他人的模板」拒掉。
            // 归属不同则深拷贝一份模板给新归属;同一源模板在一次复制里只拷一次。
            // [R13-NPC] 模板引用 NPC 时也必须有自己的行:块配置的 npcId 要按期重映射,
            // 改共享行会把来源期的引用一起改掉(模板归属未变时也强制深拷贝)。
            CmsMemberTemplate sourceTemplate = originalNode.getTemplateId() == null || originalNode.getTemplateId() <= 0L
                    ? null : cmsMemberTemplateMapper.selectCmsMemberTemplateById(originalNode.getTemplateId());
            boolean templateHasNpcRefs = sourceTemplate != null
                    && com.chengyinhub.business.util.NarrativeNpcRefs.hasNpcRefs(sourceTemplate.getAdvancedConfigJson());
            boolean templateHasNodeRefs = sourceTemplate != null && !com.chengyinhub.business.util.StateKeyRefs.collectSysNodeIds(sourceTemplate.getAdvancedConfigJson()).isEmpty();
            Long copiedTemplateId = copyTemplateForOwner(originalNode.getTemplateId(), newOwnerMemberId,
                    templateIdMap, revisionAdoption || templateHasNpcRefs || templateHasNodeRefs);
            newNode.setTemplateId(copiedTemplateId);
            if (runtimeConfig != null && runtimeConfig.path("templateBindings").has(String.valueOf(originalNode.getId()))) {
                com.fasterxml.jackson.databind.node.ObjectNode binding = (com.fasterxml.jackson.databind.node.ObjectNode) runtimeConfig.path("templateBindings").get(String.valueOf(originalNode.getId()));
                if (!java.util.Objects.equals(originalNode.getTemplateId(), binding.path("templateId").asLong())) throw new IllegalArgumentException("复制时资源绑定模板身份不匹配：节点 " + originalNode.getId());
                binding.put("templateId", copiedTemplateId);
            }
            if ((templateHasNpcRefs || templateHasNodeRefs) && copiedTemplateId != null && sourceTemplate != null) {
                if (copiedTemplateId.equals(originalNode.getTemplateId())) {
                    if(templateHasNodeRefs)throw new IllegalStateException("复制主题失败: 节点条件模板未取得独立副本");
                    // P0-35 无法判定归属 / 模板缺失:没有可写的副本行,引用只能留在源模板上
                    logger.warn("复制主题 {} -> {}:节点 {} 的模板 {} 的 NPC 引用无法换期(模板未深拷贝),仍指向来源主题 NPC",
                            originalTopicId, newTopicId, originalNode.getId(), copiedTemplateId);
                } else {
                    copiedTemplateBlocks.put(copiedTemplateId, sourceTemplate.getAdvancedConfigJson());
                }
            }
            // 营业时间属于旧城现场条件，跨城必须重新确认；打卡码每次复制都换新，旧商家凭证不随副本走。
            newNode.setBusinessTime(newCity ? null : originalNode.getBusinessTime());
            newNode.setHookText(originalNode.getHookText());
            newNode.setCardHookLong(originalNode.getCardHookLong());
            newNode.setFragmentText(originalNode.getFragmentText());
            newNode.setStatus(originalNode.getStatus());
            newNode.setSortId(originalNode.getSortId());
            newNode.setXpValue(originalNode.getXpValue()); // M7 停靠节点探索值:漏抄会让副本静默丢 XP(insert 有 xp_value 列)
            newNode.setCheckinCode(java.util.UUID.randomUUID().toString().replace("-", "")); // 复制主题生成新打卡码
            // 旧商家承接与旧审核痕迹不随内容走：副本只能是本主题/新归属自己的供给。
            newNode.setMerchantMemberId(null);
            newNode.setNodeAuditStatus(null);
            newNode.setNodeAuditReason(null);
            newNode.setNodeAuditMemberId(null);
            newNode.setNodeAuditTime(null);

            // 设置创建信息
            newNode.setCreateBy("system_copy");
            newNode.setCreateTime(new Date());
            newNode.setUpdateBy("system_copy");
            newNode.setUpdateTime(new Date());

            // 插入新节点
            if (cmsTopicNodeMapper.insertCmsTopicNode(newNode) != 1
                    || newNode.getId() == null || newNode.getId() <= 0L) {
                throw new IllegalStateException("复制主题失败: 节点写入未生成有效ID");
            }
            if (originalNode.getId() != null && newNode.getId() != null) {
                nodeIdMap.put(originalNode.getId(), newNode.getId());
            }
            if (originalNode.getUnlockAfterNodeId() != null && originalNode.getUnlockAfterNodeId() > 0
                    && newNode.getId() != null) {
                pendingUnlockByNewNodeId.put(newNode.getId(), originalNode.getUnlockAfterNodeId());
            }
            // R13-NPC:节点级 NPC(scope_type=4,一节点一条)随节点复制到副本节点 id。
            // 门店分身(scope 2)挂商家、不随复制,引用它们的 npcId 由调用方 fail-closed 置空。
            if (npcProfileMapper != null && originalNode.getId() != null && newNode.getId() != null) {
                NpcProfile sourceNpc = npcProfileMapper.selectNodeNpcByNodeId(originalNode.getId());
                if (sourceNpc != null && sourceNpc.getId() != null) {
                    NpcProfile copyNpc = copyNodeNpc(sourceNpc, newNode.getId());
                    if (npcProfileMapper.insertNodeNpc(copyNpc) != 1 || copyNpc.getId() == null) {
                        throw new IllegalStateException("复制主题失败: 节点 NPC 复制失败, nodeId=" + newNode.getId());
                    }
                    npcIdMap.put(sourceNpc.getId(), copyNpc.getId());
                }
            }
        }
        // 二遍:把前置解锁引用翻译成副本节点 id;源引用不在本主题内(脏数据)则不带过去并告警
        for (Map.Entry<Long, Long> entry : pendingUnlockByNewNodeId.entrySet()) {
            Long mappedUnlockId = nodeIdMap.get(entry.getValue());
            if (mappedUnlockId == null) {
                logger.warn("复制主题 {} -> {}:节点 {} 的前置解锁引用 {} 不在源主题内,副本不保留该前置",
                        originalTopicId, newTopicId, entry.getKey(), entry.getValue());
                continue;
            }
            CmsTopicNode patch = new CmsTopicNode();
            patch.setId(entry.getKey());
            patch.setUnlockAfterNodeId(mappedUnlockId);
            if (cmsTopicNodeMapper.updateCmsTopicNode(patch) <= 0) {
                throw new IllegalStateException("复制主题失败: 节点前置解锁写入失败, nodeId="
                        + entry.getKey());
            }
        }
        // 三遍:副本模板的 enter/outcome.npcId 按副本 NPC 重映射;映射不到的置空(见 NarrativeNpcRefs)
        for (Map.Entry<Long, String> entry : copiedTemplateBlocks.entrySet()) {
            String remapped = com.chengyinhub.business.util.NarrativeNpcRefs.remapNpcIds(
                    entry.getValue(), npcIdMap, npcWarnings);
            remapped = com.chengyinhub.business.util.StateKeyRefs.remapNodeIds(remapped,nodeIdMap);
            if (remapped == entry.getValue()) {
                continue;
            }
            CmsMemberTemplate patch = new CmsMemberTemplate();
            patch.setId(entry.getKey());
            patch.setAdvancedConfigJson(remapped);
            if (cmsMemberTemplateMapper.updateCmsMemberTemplate(patch) != 1) {
                throw new IllegalStateException("复制主题失败: 副本节点模板的 NPC 引用回填失败, templateId="
                        + entry.getKey());
            }
        }
        for (String warning : npcWarnings) {
            logger.warn("复制主题 {} -> {}:{}", originalTopicId, newTopicId, warning);
        }
        return nodeIdMap;
    }

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

    private static final Long OWNER_TYPE_TOPIC = 1L;

    private void copyTopicTicketTypes(Long originalTopicId, CmsTopic newTopic) {
        if (omsTicketMapper == null) return; // 手工 new 的旧单测未注入;Spring 运行态必注入
        OmsTicket query = new OmsTicket();
        query.setOwnerType(OWNER_TYPE_TOPIC);
        query.setOwnerId(originalTopicId);
        query.setDelFlag(0);
        for (OmsTicket source : nullToEmpty(omsTicketMapper.selectOmsTicketList(query))) {
            OmsTicket copy = new OmsTicket();
            copy.setMemberId(newTopic.getMemberId());
            copy.setOwnerType(OWNER_TYPE_TOPIC);
            copy.setOwnerId(newTopic.getId());
            copy.setName(source.getName());
            copy.setPrice(source.getPrice());
            copy.setTotalInventory(source.getTotalInventory());
            copy.setRemainingInventory(source.getTotalInventory());
            copy.setStartTime(source.getStartTime());
            copy.setEndTime(source.getEndTime());
            copy.setSaleStartTime(source.getSaleStartTime());
            copy.setSaleEndTime(source.getSaleEndTime());
            copy.setMode(source.getMode());
            copy.setSubType(source.getSubType());
            copy.setMeetingPoint(source.getMeetingPoint());
            copy.setMeetingPointAddress(source.getMeetingPointAddress());
            copy.setTeamSize(source.getTeamSize());
            copy.setMinPeople(source.getMinPeople());
            copy.setMaxPeople(source.getMaxPeople());
            copy.setSignupDeadline(source.getSignupDeadline());
            copy.setGatherLng(source.getGatherLng());
            copy.setGatherLat(source.getGatherLat());
            copy.setRefundRule(source.getRefundRule());
            copy.setRefundSupported(source.getRefundSupported());
            copy.setSyncWithTheme(source.getSyncWithTheme());
            copy.setNeedAudit(source.getNeedAudit());
            copy.setDelFlag(0);
            copy.setCreateBy("system_copy");
            copy.setCreateTime(new Date());
            if (omsTicketMapper.insertOmsTicket(copy) != 1) {
                throw new IllegalStateException("复制主题票种失败");
            }
        }
    }

    private void copyCollaborators(Long originalTopicId, Long newTopicId) {
        CmsCollaborators query = new CmsCollaborators();
        query.setDataType(1L); // 类型1:主题
        query.setDataId(originalTopicId);
        List<CmsCollaborators> originalCollaborators = cmsCollaboratorsMapper.selectCmsCollaboratorsList(query);

        if (CollectionUtils.isEmpty(originalCollaborators)) {
            return;
        }

        for (CmsCollaborators original : originalCollaborators) {
            CmsCollaborators newCollaborator = new CmsCollaborators();

            newCollaborator.setDataType(original.getDataType());
            newCollaborator.setDataId(newTopicId);
            newCollaborator.setMemberId(original.getMemberId());

            // 设置创建信息
            newCollaborator.setCreateBy("system_copy");
            newCollaborator.setCreateTime(new Date());
            newCollaborator.setUpdateBy("system_copy");
            newCollaborator.setUpdateTime(new Date());

            cmsCollaboratorsMapper.insertCmsCollaborators(newCollaborator);
        }
    }

    private void remapCopiedJourneyStory(Long sourceTopicId, Long newTopicId, String story,
                                         Map<Long, Long> chapterIdMap, Map<Long, Long> nodeIdMap, Map<Long, Long> npcIdMap) {
        if (StringUtils.isBlank(story)) {
            return;
        }
        List<String> npcWarnings = new ArrayList<String>();
        String remapped = JourneyStory.remapNpcIds(
                JourneyStory.remapChapterIds(JourneyStory.remapNodeIds(story, nodeIdMap), chapterIdMap), npcIdMap, npcWarnings);
        for (String warning : npcWarnings) {
            logger.warn("复制主题 {} -> {}:{}", sourceTopicId, newTopicId, warning);
        }
        CmsTopic patch = new CmsTopic();
        patch.setId(newTopicId);
        patch.setJourneyStoryJson(remapped);
        patch.setUpdateBy("system_copy");
        patch.setUpdateTime(new Date());
        if (cmsTopicMapper.updateCmsTopic(patch) != 1) {
            throw new IllegalStateException("副本剧情配置回填失败");
        }
    }

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

    private static <T> List<T> nullToEmpty(List<T> rows) {
        return rows == null ? Collections.<T>emptyList() : rows;
    }

    private static final class CopiedContentIds {
        private final Map<Long, Long> chapterOldToNew = new LinkedHashMap<Long, Long>();
        private final Map<Long, Long> oldToNew = new LinkedHashMap<Long, Long>();
        /** 与本次复制同一 fence 下读到的来源节点，供地点要求推导与绑定重映射使用。 */
        private List<CmsTopicNode> sourceNodes = Collections.emptyList();
    }
}
