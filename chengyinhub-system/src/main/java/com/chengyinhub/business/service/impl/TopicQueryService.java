package com.chengyinhub.business.service.impl;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.service.support.TopicSizeLimits;
import com.chengyinhub.business.support.ChapterRecruitmentRules;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

/** Topic visibility reads and whitelist shelf/detail projections; no publication or runtime mutation. */
@Service
public class TopicQueryService {
    /** Physical read-parameter budget, not a limit on topics/nodes or returned cards. */
    static final int MAX_IDS_PER_QUERY = 300;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;
    @Autowired
    private CmsTopicChapterMapper cmsTopicChapterMapper;
    @Autowired
    private CmsTopicNodeMapper cmsTopicNodeMapper;
    @Autowired
    private CmsTopicTemplateMetaMapper cmsTopicTemplateMetaMapper;
    @Autowired
    private CmsMemberTemplateMapper cmsMemberTemplateMapper;
    @Autowired
    private ISysCategoryService sysCategoryService;
    @Autowired
    private IMerchantChapterApplicationService merchantChapterApplicationService;

    public CmsTopic selectPublicCmsTopicById(Long id) {
        CmsTopic topic = cmsTopicMapper.selectPublicCmsTopicById(id);
        if (topic != null) refreshPublicNodeCounts(Collections.singletonList(topic));
        return topic;
    }

    public CmsTopic selectCmsTopicDetailById(Long id, Long viewerMemberId) {
        CmsTopic topic = cmsTopicMapper.selectCmsTopicDetailById(id, viewerMemberId);
        // Resolve visibility first; denied/missing topics must not trigger a node lookup.
        if (topic != null) refreshPublicNodeCounts(Collections.singletonList(topic));
        return topic;
    }

    public CmsTopic selectMerchantVisibleCmsTopicById(Long id) {
        CmsTopic topic = cmsTopicMapper.selectMerchantVisibleCmsTopicById(id);
        // Count only after the existing visibility/management gate has admitted this topic.
        if (topic != null) refreshPublicNodeCounts(Collections.singletonList(topic));
        return topic;
    }

    public CmsTopic selectMerchantManagedCmsTopicById(Long id, Long merchantId) {
        CmsTopic topic = cmsTopicMapper.selectMerchantManagedCmsTopicById(id, merchantId);
        // Count only after the existing visibility/management gate has admitted this topic.
        if (topic != null) refreshPublicNodeCounts(Collections.singletonList(topic));
        return topic;
    }

    /**
     * 查询主题列表
     *
     * @param cmsTopic 主题
     * @return 主题
     */
    public List<CmsTopic> selectCmsTopicList(CmsTopic cmsTopic) {
        List<CmsTopic> topics = cmsTopicMapper.selectCmsTopicList(cmsTopic);
        refreshPublicNodeCounts(topics);
        return topics;
    }

    public Map<Long, Integer> selectUpcomingTopicCounts(List<Long> clubIds) {
        if (clubIds == null || clubIds.isEmpty()) return Collections.emptyMap();
        Map<Long, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : cmsTopicMapper.selectUpcomingTopicCounts(clubIds, new Date())) {
            Object clubId = row.get("clubId");
            Object topicCount = row.get("topicCount");
            if (clubId instanceof Number && topicCount instanceof Number) {
                counts.put(((Number) clubId).longValue(), ((Number) topicCount).intValue());
            }
        }
        return counts;
    }

    public List<CmsTopic> selectPublicCmsTopicList(CmsTopic cmsTopic) {
        List<CmsTopic> topics = cmsTopicMapper.selectPublicCmsTopicList(cmsTopic);
        refreshPublicNodeCounts(topics);
        return topics;
    }

    private void refreshPublicNodeCounts(List<CmsTopic> topics) {
        if (topics == null || topics.isEmpty()) return;
        List<Long> ids = topics.stream().map(CmsTopic::getId).distinct().collect(java.util.stream.Collectors.toList());
        Map<Long, CmsTopic> counts = new HashMap<>();
        for (int offset = 0; offset < ids.size();) {
            int end = offset + Math.min(MAX_IDS_PER_QUERY, ids.size() - offset);
            for (CmsTopic row : nullToEmpty(cmsTopicMapper.selectPublicNodeCountsByTopicIds(ids.subList(offset, end))))
                counts.put(row.getId(), row);
            offset = end;
        }
        for (CmsTopic topic : topics) {
            CmsTopic count = counts.get(topic.getId());
            topic.setLocationCount(count == null ? 0L : count.getLocationCount());
            topic.setTemplateCount(count == null ? 0L : count.getTemplateCount());
        }
    }

    public java.util.List<CmsTopic> selectTopicTemplateList() {
        return cmsTopicMapper.selectTopicTemplateList();
    }

    public java.util.List<com.chengyinhub.business.domain.vo.TopicTemplateListItemVO> buildTopicTemplateListVO() {
        return buildTopicTemplateListVO(false);
    }

    public java.util.List<com.chengyinhub.business.domain.vo.TopicTemplateListItemVO> buildTopicTemplateListVO(boolean recommendedOnly) {
        java.util.List<CmsTopic> rawList = cmsTopicMapper.selectTopicTemplateList();
        if (CollectionUtils.isEmpty(rawList)) {
            return java.util.Collections.emptyList();
        }
        // Public templates are the same topic/node records; count the admitted shelf in one batch.
        refreshPublicNodeCounts(rawList);
        java.util.List<com.chengyinhub.business.domain.vo.TopicTemplateListItemVO> result = new java.util.ArrayList<>(rawList.size());
        for (CmsTopic t : rawList) {
            com.chengyinhub.business.domain.vo.TopicTemplateListItemVO vo =
                    new com.chengyinhub.business.domain.vo.TopicTemplateListItemVO();
            vo.setId(t.getId());
            vo.setName(t.getName());
            vo.setSubtitle(t.getSubtitle());
            vo.setDescription(t.getDescription());
            vo.setImgUrl(t.getImgUrl());
            vo.setImgArr(t.getImgArr());
            vo.setProductType(t.getProductType());
            vo.setCircleThemeCode(t.getCircleThemeCode());
            vo.setTotalTime(t.getTotalTime());
            vo.setTotalMileage(t.getTotalMileage());
            vo.setLocationCount(t.getLocationCount());
            vo.setTemplateCount(t.getTemplateCount());
            vo.setCategoryIds(t.getCategoryIds());
            // productType 兜底:NULL → 经典定向(存量未回填)
            if (vo.getProductType() == null) {
                vo.setProductType(ProductType.ORIENTEERING);
            }
            // 章节数
            CmsTopicChapter probeC = new CmsTopicChapter();
            probeC.setTopicId(t.getId());
            java.util.List<CmsTopicChapter> chapters = cmsTopicChapterMapper.selectCmsTopicChapterList(probeC);
            vo.setChapterCount(chapters != null ? chapters.size() : 0);
            // 玩法数(去重 templateId)
            CmsTopicNode probeN = new CmsTopicNode();
            probeN.setTopicId(t.getId());
            java.util.List<CmsTopicNode> nodes = cmsTopicNodeMapper.selectCmsTopicNodeList(probeN);
            int gameCount = 0;
            if (nodes != null && !nodes.isEmpty()) {
                java.util.Set<Long> tids = new java.util.HashSet<>();
                for (CmsTopicNode n : nodes) {
                    if (n.getTemplateId() != null && n.getTemplateId() > 0) {
                        tids.add(n.getTemplateId());
                    }
                }
                gameCount = tids.size();
            }
            vo.setGameCount(gameCount);
            result.add(vo);
        }

        // 货架必须能区分「实验模板」与「已验证模板」——指南明令二者不得混排。
        // 一次批量拉元数据,不进上面的循环(那里已经是 N+1,别再加一条)。
        java.util.List<Long> metaTopicIds = new java.util.ArrayList<>(result.size());
        for (com.chengyinhub.business.domain.vo.TopicTemplateListItemVO vo : result) {
            if (vo.getId() != null) {
                metaTopicIds.add(vo.getId());
            }
        }
        if (!metaTopicIds.isEmpty()) {
            java.util.List<com.chengyinhub.business.domain.CmsTopicTemplateMeta> metas =
                    selectTemplateMetadataInBatches(metaTopicIds);
            if (!CollectionUtils.isEmpty(metas)) {
                java.util.Map<Long, com.chengyinhub.business.domain.CmsTopicTemplateMeta> byTopicId =
                        new java.util.HashMap<>();
                for (com.chengyinhub.business.domain.CmsTopicTemplateMeta m : metas) {
                    if (m != null && m.getTopicId() != null) {
                        byTopicId.put(m.getTopicId(), m);
                    }
                }
                for (com.chengyinhub.business.domain.vo.TopicTemplateListItemVO vo : result) {
                    com.chengyinhub.business.domain.CmsTopicTemplateMeta m = byTopicId.get(vo.getId());
                    // 未录入元数据的模板两个字段都留空;前端按「实验」保守渲染,不得当作已验证。
                    if (m != null) {
                        vo.setTemplateStatus(m.getTemplateStatus());
                        vo.setEvidenceLevel(m.getEvidenceLevel());
                        vo.setIsOfficial(m.getIsOfficial());
                    }
                }
            }
        }
        // 推荐口径:只回已验证模板。货架 SQL 本身已是 VERIFIED-only(T7 闸),
        // 这里是 service 层第二道闸——未录入元数据(templateStatus=null)同样不算推荐。
        if (recommendedOnly) {
            result.removeIf(vo -> !com.chengyinhub.business.util.TemplateStatus.VERIFIED
                    .equals(vo.getTemplateStatus()));
        }
        // 官方范本置顶:isOfficial DESC 排在既有 id desc 之前(List.sort 稳定,组内保持 id desc)。
        result.sort((a, b) -> {
            int oa = Integer.valueOf(1).equals(a.getIsOfficial()) ? 1 : 0;
            int ob = Integer.valueOf(1).equals(b.getIsOfficial()) ? 1 : 0;
            return ob - oa;
        });
        return result;
    }

    private java.util.List<com.chengyinhub.business.domain.CmsTopicTemplateMeta> selectTemplateMetadataInBatches(
            java.util.List<Long> topicIds) {
        java.util.List<com.chengyinhub.business.domain.CmsTopicTemplateMeta> result = new java.util.ArrayList<>();
        for (int offset = 0; offset < topicIds.size();) {
            int end = offset + Math.min(MAX_IDS_PER_QUERY, topicIds.size() - offset);
            result.addAll(nullToEmpty(cmsTopicTemplateMetaMapper.selectByTopicIds(topicIds.subList(offset, end))));
            offset = end;
        }
        return result;
    }

    public com.chengyinhub.business.domain.vo.TopicTemplateDetailVO buildTopicTemplateDetail(Long id, boolean viewerIsMerchant) {
        return buildTopicTemplateDetail(id, viewerIsMerchant, null);
    }

    public com.chengyinhub.business.domain.vo.TopicTemplateDetailVO buildTopicTemplateDetail(
            Long id, boolean viewerCanViewRecruitment, Integer merchantCategoryId) {
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(id);
        return buildTopicTemplateDetailFromSnapshot(topic, viewerCanViewRecruitment, merchantCategoryId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public com.chengyinhub.business.domain.vo.TopicTemplateDetailVO buildPublicTopicTemplateDetail(
            Long id, Long viewerMemberId, boolean viewerIsMerchant, Integer merchantCategoryId) {
        CmsTopic topic = cmsTopicMapper.selectPublicTopicTemplateById(id);
        if (topic == null) {
            return null;
        }
        // Preserve the public-template eligibility gate before querying the full-route scalar.
        refreshPublicNodeCounts(Collections.singletonList(topic));
        boolean viewerIsPublisher = viewerMemberId != null && viewerMemberId.equals(topic.getMemberId());
        boolean viewerCanViewRecruitment = viewerIsMerchant || viewerIsPublisher;
        Integer effectiveCategoryId = viewerIsMerchant && !viewerIsPublisher ? merchantCategoryId : null;
        com.chengyinhub.business.domain.vo.TopicTemplateDetailVO detail =
                buildTopicTemplateDetailFromSnapshot(topic, viewerCanViewRecruitment, effectiveCategoryId);
        detail.setViewerIsMerchant(viewerIsMerchant);
        detail.setViewerIsPublisher(viewerIsPublisher);
        return detail;
    }

    private com.chengyinhub.business.domain.vo.TopicTemplateDetailVO buildTopicTemplateDetailFromSnapshot(
            CmsTopic topic, boolean viewerCanViewRecruitment, Integer merchantCategoryId) {
        if (topic == null) {
            return null;
        }
        Long id = topic.getId();
        // 白名单组装:不 copyProperties，逐字段显式赋值
        com.chengyinhub.business.domain.vo.TopicTemplateDetailVO vo =
                new com.chengyinhub.business.domain.vo.TopicTemplateDetailVO();
        vo.setId(topic.getId());
        vo.setName(topic.getName());
        vo.setSubtitle(topic.getSubtitle());
        vo.setDescription(topic.getDescription());
        vo.setImgUrl(topic.getImgUrl());
        vo.setImgArr(topic.getImgArr());
        vo.setProductType(topic.getProductType() != null ? topic.getProductType() : ProductType.ORIENTEERING);
        vo.setCircleThemeCode(topic.getCircleThemeCode());
        vo.setTotalTime(topic.getTotalTime());
        vo.setTotalMileage(topic.getTotalMileage());
        vo.setLocationCount(topic.getLocationCount());
        vo.setTemplateCount(topic.getTemplateCount());
        vo.setFinishMedalName(topic.getFinishMedalName());
        vo.setFinishMedalImg(topic.getFinishMedalImg());
        vo.setXpBudget(topic.getXpBudget());
        vo.setAudioUrl(topic.getAudioUrl());
        vo.setAudioDuration(topic.getAudioDuration());
        vo.setAddressName(topic.getAddressName());
        // 俱乐部承接状态:主题级,只出状态不出身份,对所有访问者可见
        vo.setClubAdoptionStatus(topic.getClubId() == null ? "OPEN" : "CLAIMED");
        vo.setViewerIsMerchant(viewerCanViewRecruitment);

        boolean recruitmentWindowOpen = false;
        if (viewerCanViewRecruitment && topic.getLifecycle() != null) {
            try {
                com.chengyinhub.common.constant.RecruitmentWindowPolicy.assertOpen(
                        topic.getLifecycle(), topic.getStatus(), topic.getUserStatus(),
                        topic.getRecruitDeadline(), DateUtils.getNowDate());
                recruitmentWindowOpen = true;
            } catch (IllegalStateException closed) {
                recruitmentWindowOpen = false;
            }
        }

        // 章节→节点→玩法子树
        CmsTopicChapter probeC = new CmsTopicChapter();
        probeC.setTopicId(id);
        java.util.List<CmsTopicChapter> chapters = cmsTopicChapterMapper.selectCmsTopicChapterList(probeC);
        java.util.List<com.chengyinhub.business.domain.vo.TopicTemplateChapterVO> chapterVOs = new java.util.ArrayList<>();
        java.util.Map<Long, com.chengyinhub.business.domain.vo.TopicTemplateGameVO> gameMap = new java.util.LinkedHashMap<>();
        java.util.List<RecruitmentProjection> recruitment = new java.util.ArrayList<>();

        if (chapters != null && !chapters.isEmpty()) {
            for (CmsTopicChapter ch : chapters) {
                com.chengyinhub.business.domain.vo.TopicTemplateChapterVO chVO =
                        new com.chengyinhub.business.domain.vo.TopicTemplateChapterVO();
                chVO.setId(ch.getId());
                chVO.setName(ch.getName());
                chVO.setDescription(ch.getDescription());
                chVO.setCategory(ch.getCategory());
                chVO.setRequired(ch.getRequired() != null ? ch.getRequired() : 1);
                chVO.setSortId(ch.getSortId());
                chVO.setCircleCandidateCode(ch.getCircleCandidateCode());
                chVO.setCircleRole(ch.getCircleRole());
                chVO.setCirclePolicy(ch.getCirclePolicy());
                chVO.setCircleEnabled(ch.getCircleEnabled());

                // 节点查询
                CmsTopicNode probeN = new CmsTopicNode();
                probeN.setChapterId(ch.getId());
                java.util.List<CmsTopicNode> nodes = cmsTopicNodeMapper.selectCmsTopicNodeList(probeN);
                // Same mapper projection/predicate as the original id reads; bounded batches per chapter.
                java.util.Map<Long, CmsMemberTemplate> chapterTemplates = selectChapterTemplates(nodes);
                java.util.List<com.chengyinhub.business.domain.vo.TopicTemplateNodeVO> nodeVOs = new java.util.ArrayList<>();
                java.util.List<CmsTopicNode> validNodes = new java.util.ArrayList<>();

                if (nodes != null && !nodes.isEmpty()) {
                    for (CmsTopicNode n : nodes) {
                        com.chengyinhub.business.domain.vo.TopicTemplateNodeVO nVO =
                                new com.chengyinhub.business.domain.vo.TopicTemplateNodeVO();
                        nVO.setName(n.getName());
                        // ⚠️ 只出 POI 文本地址，不出 longitude/latitude
                        nVO.setAddressName(n.getAddress());
                        nVO.setImgUrl(n.getImgUrl());
                        // hookTeaser = hookText，空则回退 description 截 40 字
                        String teaser = n.getHookText();
                        if (StringUtils.isEmpty(teaser) && !StringUtils.isEmpty(n.getDescription())) {
                            teaser = n.getDescription().length() > 40
                                    ? n.getDescription().substring(0, 40) : n.getDescription();
                        }
                        nVO.setHookTeaser(teaser);
                        // interactionType 从模板取
                        if (n.getTemplateId() != null && n.getTemplateId() > 0) {
                            CmsMemberTemplate tpl = chapterTemplates.get(n.getTemplateId());
                            if (tpl != null) {
                                nVO.setInteractionType(tpl.getInteractionType());
                                // V-24(2026-10-30):主题模板详情「能力」按站列出这一站玩什么、大概多久
                                nVO.setGameTitle(tpl.getTitle());
                                nVO.setGameDuration(tpl.getDuration());
                                // 玩法去重
                                if (!gameMap.containsKey(n.getTemplateId())) {
                                    com.chengyinhub.business.domain.vo.TopicTemplateGameVO gVO =
                                            new com.chengyinhub.business.domain.vo.TopicTemplateGameVO();
                                    gVO.setId(tpl.getId());
                                    gVO.setTitle(tpl.getTitle());
                                    gVO.setImgUrl(tpl.getImgUrl());
                                    gVO.setPlayers(tpl.getPlayers());
                                    gVO.setDuration(tpl.getDuration());
                                    gVO.setDifficulty(tpl.getDifficulty());
                                    gVO.setInteractionType(tpl.getInteractionType());
                                    gameMap.put(n.getTemplateId(), gVO);
                                }
                            }
                        }
                        nodeVOs.add(nVO);
                        // 路线剪影:只取有经纬度的节点
                        if (!StringUtils.isEmpty(n.getLatitude()) && !StringUtils.isEmpty(n.getLongitude())) {
                            validNodes.add(n);
                        }
                    }
                }
                chVO.setNodes(nodeVOs);
                chVO.setNodeCount(nodeVOs.size());
                // 路线剪影:归一化
                chVO.setRouteShape(normalizeRouteShape(validNodes));
                // 招商状态的唯一开关是 recruitEnabled。maxMerchant 只表示容量（0/空=不限），
                // 绝不能再把存量默认值 4 推导成「自动开放招商」。
                // 商家只能看到与自身受控品类匹配的章节；发布者审核自己的主题时不带品类过滤。
                boolean freeExplore = topic.getProductType() != null
                        && topic.getProductType() == ProductType.FREE_EXPLORE;
                boolean published = Integer.valueOf(1).equals(topic.getStatus())
                        && Integer.valueOf(1).equals(topic.getUserStatus())
                        && !Integer.valueOf(2).equals(topic.getDelFlag());
                String chapterTermsMode = StringUtils.isEmpty(ch.getTermsMode())
                        ? TermsMode.PERK : ch.getTermsMode();
                boolean perkMinValueValid = ChapterRecruitmentRules.isPerkMinValueValidForTerms(
                        chapterTermsMode, ch.getPerkMinValue());
                boolean canShowRecruitment = viewerCanViewRecruitment
                        && ChapterRecruitmentRules.isEnabled(ch.getRecruitEnabled())
                        && (merchantCategoryId == null || merchantCategoryId.equals(ch.getCategoryId()))
                        && freeExplore && published
                        && ChapterRecruitmentRules.isV1RecruitmentTermsMode(ch.getTermsMode())
                        && perkMinValueValid
                        && ChapterRecruitmentRules.isMaxMerchantValid(ch.getMaxMerchant());
                boolean configured = false;
                if (canShowRecruitment) {
                    SysCategory category = ch.getCategoryId() == null ? null
                            : sysCategoryService.selectSysCategoryById(ch.getCategoryId().longValue());
                    configured = ChapterRecruitmentRules.isMerchantCategory(ch.getCategoryId(), category);
                }
                if (configured) {
                    com.chengyinhub.business.domain.vo.TopicTemplateRecruitVO rec =
                            new com.chengyinhub.business.domain.vo.TopicTemplateRecruitVO();
                    rec.setTermsMode(StringUtils.isEmpty(ch.getTermsMode()) ? "PERK" : ch.getTermsMode());
                    rec.setPerkMinValue(TermsMode.PERK.equals(rec.getTermsMode()) ? ch.getPerkMinValue() : null);
                    // 玩法边界随招商状态一起公示 —— 商家申请前就该看得到自己能用哪些验证方式
                    rec.setAllowedValidationMethods(ch.getAllowedValidationMethods());
                    rec.setMaxNodeXp(ch.getMaxNodeXp());
                    rec.setMaxMerchant(ch.getMaxMerchant());
                    recruitment.add(new RecruitmentProjection(ch.getId(), ch.getMaxMerchant(), rec));
                    chVO.setRecruitStatus(rec);
                }
                chapterVOs.add(chVO);
            }
        }
        applyRecruitmentStats(recruitment, topic.getLifecycle(), recruitmentWindowOpen);
        vo.setChapters(chapterVOs);
        vo.setGames(new java.util.ArrayList<>(gameMap.values()));

        // 「模式」的真源是 product_type，不是元数据表 —— 上面已按 ORIENTEERING 兜底过 NULL，
        // 所以这里恒能给出确定的一种，不存在空值或第三种。
        vo.setMode(vo.getProductType() != null && vo.getProductType() == ProductType.FREE_EXPLORE
                ? "FREE_EXPLORE" : "ORIENTEERING");
        applyTemplateMeta(vo, id, viewerCanViewRecruitment);
        return vo;
    }

    /** Only chapters admitted by the existing view/category gates reach this request-local batch. */
    private void applyRecruitmentStats(java.util.List<RecruitmentProjection> targets, Integer lifecycle,
            boolean recruitmentWindowOpen) {
        for (int offset = 0; offset < targets.size();) {
            int end = offset + Math.min(TopicSizeLimits.MAX_CHAPTERS, targets.size() - offset);
            java.util.List<RecruitmentProjection> batchTargets = targets.subList(offset, end);
            java.util.Map<Long, Integer> limits = new java.util.LinkedHashMap<>();
            for (RecruitmentProjection target : batchTargets) {
                if (target.chapterId() != null) limits.put(target.chapterId(), target.maxMerchant());
            }
            java.util.Map<Long, com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats> results =
                    limits.isEmpty() ? java.util.Collections.emptyMap()
                            : merchantChapterApplicationService.getRecruitmentStatsByChapters(limits);
            if (results == null) throw new IllegalStateException("Recruitment statistics unavailable");
            for (RecruitmentProjection target : batchTargets) {
                com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats stats = target.chapterId() == null
                        ? merchantChapterApplicationService.getRecruitmentStats(null, target.maxMerchant())
                        : results.get(target.chapterId());
                if (stats == null) throw new IllegalStateException("Recruitment statistics unavailable");
                com.chengyinhub.business.domain.vo.TopicTemplateRecruitVO rec = target.view();
                rec.setCurrentOfferCount(stats.getReservedMerchantCount());
                rec.setPendingApplicationCount(stats.getPendingApplicationCount());
                rec.setApprovedApplicationCount(stats.getApprovedApplicationCount());
                rec.setActiveOfferCount(stats.getActiveOfferCount());
                rec.setRemainingMerchantCount(stats.getRemainingMerchantCount());
                boolean locked = TopicLifecycleState.commercialTermsFrozen(lifecycle);
                boolean full = stats.getRemainingMerchantCount() != null
                        && stats.getRemainingMerchantCount() <= 0;
                rec.setState(locked ? "LOCKED" : !recruitmentWindowOpen ? "CLOSED" : full ? "FULL" : "OPEN");
                rec.setIsOpen(recruitmentWindowOpen && !locked && !full);
            }
            offset = end;
        }
    }

    private record RecruitmentProjection(Long chapterId, Integer maxMerchant,
            com.chengyinhub.business.domain.vo.TopicTemplateRecruitVO view) { }

    /** Request-local rows for this admitted chapter only; no cache or SQL for an empty id set. */
    private java.util.Map<Long, CmsMemberTemplate> selectChapterTemplates(java.util.List<CmsTopicNode> nodes) {
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        if (nodes != null) {
            for (CmsTopicNode node : nodes) {
                if (node.getTemplateId() != null && node.getTemplateId() > 0) ids.add(node.getTemplateId());
            }
        }
        if (ids.isEmpty()) return java.util.Collections.emptyMap();
        java.util.Map<Long, CmsMemberTemplate> result = new java.util.HashMap<>();
        java.util.List<Long> orderedIds = new java.util.ArrayList<>(ids);
        // Keep each IN bounded by the existing publication node limit; do not truncate legacy oversized chapters.
        for (int offset = 0; offset < orderedIds.size();) {
            int end = offset + Math.min(TopicSizeLimits.MAX_NODES, orderedIds.size() - offset);
            java.util.List<Long> batch = orderedIds.subList(offset, end);
            for (CmsMemberTemplate template : nullToEmpty(cmsMemberTemplateMapper.selectCmsMemberTemplateByIds(batch))) {
                result.put(template.getId(), template);
            }
            offset = end;
        }
        return result;
    }

    /**
     * 把模板元数据(07-23 P0 字段字典)贴到详情 VO 上。
     *
     * <p>附表没有行 ⇒ 所有元数据字段保持 null ⇒ 配合 {@code @JsonInclude(NON_NULL)} 整体不下发。
     * 这是「本模板尚未录入元数据」的正常表达,不是错误,不打日志也不抛异常。
     *
     * <p>⚠️ 本方法<b>只读元数据附表</b>,不碰 cms_member_template。
     * 玩法内容仍只经 {@code TopicTemplateGameVO} 那 7 个白名单字段下发,
     * 谜底/提示没有任何一条路径能从这里流出去。
     */
    private void applyTemplateMeta(com.chengyinhub.business.domain.vo.TopicTemplateDetailVO vo, Long topicId,
            boolean viewerCanViewRecruitment) {
        com.chengyinhub.business.domain.CmsTopicTemplateMeta meta =
                cmsTopicTemplateMetaMapper.selectByTopicId(topicId);
        if (meta != null) {
            vo.setTemplateStatus(meta.getTemplateStatus());
            vo.setVersion(meta.getVersion());
            vo.setIsOfficial(meta.getIsOfficial());
            vo.setApplicableRoles(splitCsv(meta.getApplicableRoles()));
            vo.setPlayerPromise(meta.getPlayerPromise());
            vo.setCoreLoop(splitLines(meta.getCoreLoop()));
            vo.setMainSkeleton(meta.getMainSkeletonTemplateId());
            vo.setEvidenceLevel(meta.getEvidenceLevel());
            vo.setAuthor(meta.getAuthor());
            vo.setUpdatedAt(meta.getUpdateTime());

            // 三段人群:只要有任意一段就下发整个对象，让「只写了人群」这种半成品在接口上可见。
            if (!StringUtils.isEmpty(meta.getAudienceWho())
                    || !StringUtils.isEmpty(meta.getAudienceMoment())
                    || !StringUtils.isEmpty(meta.getAudienceNeed())) {
                com.chengyinhub.business.domain.vo.TopicTemplateAudienceVO audience =
                        new com.chengyinhub.business.domain.vo.TopicTemplateAudienceVO();
                audience.setWho(meta.getAudienceWho());
                audience.setMoment(meta.getAudienceMoment());
                audience.setNeed(meta.getAudienceNeed());
                vo.setTargetAudience(audience);
            }

            if (!StringUtils.isEmpty(meta.getRiskTags())
                    || !StringUtils.isEmpty(meta.getRiskQualifications())
                    || !StringUtils.isEmpty(meta.getRiskCancellation())
                    || !StringUtils.isEmpty(meta.getRiskLiability())
                    || !StringUtils.isEmpty(meta.getRiskSafetyNotice())) {
                com.chengyinhub.business.domain.vo.TopicTemplateRiskVO risk =
                        new com.chengyinhub.business.domain.vo.TopicTemplateRiskVO();
                risk.setTags(splitCsv(meta.getRiskTags()));
                risk.setQualifications(meta.getRiskQualifications());
                risk.setCancellation(meta.getRiskCancellation());
                risk.setLiability(meta.getRiskLiability());
                risk.setSafetyNotice(meta.getRiskSafetyNotice());
                vo.setRisk(risk);
            }

            if (!StringUtils.isEmpty(meta.getPreviewBefore())
                    || !StringUtils.isEmpty(meta.getPreviewDuring())
                    || !StringUtils.isEmpty(meta.getPreviewAfter())) {
                com.chengyinhub.business.domain.vo.TopicTemplatePlayerPreviewVO preview =
                        new com.chengyinhub.business.domain.vo.TopicTemplatePlayerPreviewVO();
                preview.setBefore(meta.getPreviewBefore());
                preview.setDuring(meta.getPreviewDuring());
                preview.setAfter(meta.getPreviewAfter());
                vo.setPlayerPreview(preview);
            }
        }

        if (CollectionUtils.isEmpty(vo.getChapters())) {
            return;
        }
        java.util.List<com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta> chapterMetas =
                cmsTopicTemplateMetaMapper.selectChapterMetaByTopicId(topicId);
        if (CollectionUtils.isEmpty(chapterMetas)) {
            return;
        }
        java.util.Map<Long, com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta> byChapterId =
                new java.util.HashMap<>();
        for (com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta cm : chapterMetas) {
            if (cm != null && cm.getChapterId() != null) {
                byChapterId.put(cm.getChapterId(), cm);
            }
        }
        for (com.chengyinhub.business.domain.vo.TopicTemplateChapterVO chVO : vo.getChapters()) {
            com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta cm = byChapterId.get(chVO.getId());
            if (cm == null) {
                continue;
            }
            chVO.setRoleNeed(cm.getRoleNeed());

            // 排他规则属于合作方口径; controller 已将商家或发布者归一到 viewerCanViewRecruitment。
            if (viewerCanViewRecruitment
                    && (!StringUtils.isEmpty(cm.getExclusivityMode())
                        || !StringUtils.isEmpty(cm.getExclusivityScope())
                        || !StringUtils.isEmpty(cm.getExclusivityPeriod())
                        || !StringUtils.isEmpty(cm.getExclusivityReason()))) {
                com.chengyinhub.business.domain.vo.TopicTemplateExclusivityVO ex =
                        new com.chengyinhub.business.domain.vo.TopicTemplateExclusivityVO();
                ex.setMode(cm.getExclusivityMode());
                ex.setScope(cm.getExclusivityScope());
                ex.setPeriod(cm.getExclusivityPeriod());
                ex.setReason(cm.getExclusivityReason());
                chVO.setExclusivity(ex);
            }

            // ⚠️ 运行投入的可见范围是「合作方」。它与 recruitStatus 走同一道闸:
            // recruitStatus 为 null 意味着当前访问者没有通过商家/发布者校验(或本章未开招商),
            // 此时把商家成本口径下发给匿名玩家就是越权。
            if (chVO.getRecruitStatus() != null) {
                chVO.setEffort(toEffortVO(cm));
            }
        }
    }

    /** 运行投入映射;全字段皆空时返回 null,避免下发一个空壳对象。 */
    private com.chengyinhub.business.domain.vo.TopicTemplateEffortVO toEffortVO(
            com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta cm) {
        boolean anyPresent = cm.getEffortPrepHours() != null
                || cm.getEffortStaffMinutes() != null
                || cm.getEffortMaxConcurrent() != null
                || cm.getEffortNeedsBooking() != null
                || !StringUtils.isEmpty(cm.getEffortVenue())
                || !StringUtils.isEmpty(cm.getEffortMaterials())
                || !StringUtils.isEmpty(cm.getEffortAvailableSlots())
                || !StringUtils.isEmpty(cm.getEffortCancelCost())
                || !StringUtils.isEmpty(cm.getEffortQualifications());
        if (!anyPresent) {
            return null;
        }
        com.chengyinhub.business.domain.vo.TopicTemplateEffortVO effort =
                new com.chengyinhub.business.domain.vo.TopicTemplateEffortVO();
        effort.setPrepHours(cm.getEffortPrepHours());
        effort.setStaffMinutes(cm.getEffortStaffMinutes());
        effort.setVenue(cm.getEffortVenue());
        effort.setMaterials(cm.getEffortMaterials());
        effort.setMaxConcurrent(cm.getEffortMaxConcurrent());
        effort.setAvailableSlots(cm.getEffortAvailableSlots());
        effort.setNeedsBooking(cm.getEffortNeedsBooking() == null
                ? null : Integer.valueOf(1).equals(cm.getEffortNeedsBooking()));
        effort.setCancelCost(cm.getEffortCancelCost());
        effort.setQualifications(cm.getEffortQualifications());
        return effort;
    }

    /** 逗号分隔转列表;空白项丢弃。全空返回 null(而不是空列表)以便整字段不下发。 */
    private static java.util.List<String> splitCsv(String raw) {
        return splitBy(raw, ",");
    }

    /** 换行分隔转有序列表 —— 核心循环的顺序即阶段顺序,不排序。 */
    private static java.util.List<String> splitLines(String raw) {
        return splitBy(raw, "\\r?\\n");
    }

    private static java.util.List<String> splitBy(String raw, String regex) {
        if (StringUtils.isEmpty(raw)) {
            return null;
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String p : raw.split(regex)) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts.isEmpty() ? null : parts;
    }

    /**
     * 归一化路线剪影:把有序节点的经纬度映射到 0~1 相对坐标系。
     * 绝对经纬度在方法内丢弃，上游不可恢复。
     *
     * @param nodes 有合法经纬度的有序节点
     * @return 归一化点列;少于 2 点返回 null(前端隐藏剪影区)
     */
    private java.util.List<com.chengyinhub.business.domain.vo.RouteShapePoint> normalizeRouteShape(
            java.util.List<CmsTopicNode> nodes) {
        if (nodes == null || nodes.size() < 2) {
            return null;
        }
        // 解析经纬度并求包围盒
        double minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        java.util.List<double[]> pts = new java.util.ArrayList<>(nodes.size());
        for (CmsTopicNode n : nodes) {
            try {
                double lat = Double.parseDouble(n.getLatitude());
                double lng = Double.parseDouble(n.getLongitude());
                pts.add(new double[]{lng, lat});
                if (lng < minLng) minLng = lng;
                if (lng > maxLng) maxLng = lng;
                if (lat < minLat) minLat = lat;
                if (lat > maxLat) maxLat = lat;
            } catch (NumberFormatException ignore) {
            }
        }
        if (pts.size() < 2) {
            return null;
        }
        double rangeLng = maxLng - minLng;
        double rangeLat = maxLat - minLat;
        if (rangeLng < 0.000001 && rangeLat < 0.000001) {
            // 所有点几乎重合 → 无法画线
            return null;
        }
        // 归一化:包围盒映射到单位正方形，保留纵横比(按长边缩放,另一个维度中心对齐)
        double scale;
        if (rangeLng > rangeLat) {
            scale = rangeLng;
        } else {
            scale = rangeLat;
        }
        if (scale < 0.000001) {
            return null;
        }
        java.util.List<com.chengyinhub.business.domain.vo.RouteShapePoint> result = new java.util.ArrayList<>(pts.size());
        for (double[] p : pts) {
            double x = (p[0] - minLng) / scale;
            double y = (p[1] - minLat) / scale;
            // 中心对齐
            if (rangeLng > rangeLat) {
                y += (1.0 - rangeLat / scale) / 2.0;
            } else {
                x += (1.0 - rangeLng / scale) / 2.0;
            }
            result.add(new com.chengyinhub.business.domain.vo.RouteShapePoint(
                    Math.round(x * 10000.0) / 10000.0,
                    Math.round(y * 10000.0) / 10000.0));
        }
        return result;
    }

    private static <T> List<T> nullToEmpty(List<T> rows) {
        return rows == null ? Collections.<T>emptyList() : rows;
    }
}
