package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.IChapterMerchantNodeService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.service.support.TopicSupplyReviewService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 2026-11-03 业主:自由探索主题一章都没有时,商家只带玩法模板报名。
 * 服务端建出这一章,走原承接申请(待主办方审核,同一套品类/启用/招商窗口闸),再走原点位提交。
 * 主题已有任何章节时由建章一步拒绝 —— 那时按主办方的招商走。
 *
 * <p>单独成一个编排服务,只被 controller 引用:承接申请服务已经依赖点位服务,
 * 若让点位服务反过来调申请服务,两者就成了启动期循环依赖
 * ({@code SupportedStackServiceCyclesTest} 的 topic/application/node 契约)。
 */
@Service
public class EmptyTopicMerchantJoinService {

    private final IChapterMerchantNodeService nodeService;
    private final ICmsTopicChapterService chapterService;
    private final IMerchantChapterApplicationService applicationService;
    private final TopicSupplyReviewService supplyReviewService;
    private final MmsMerchantMapper merchantMapper;

    public EmptyTopicMerchantJoinService(IChapterMerchantNodeService nodeService,
                                         ICmsTopicChapterService chapterService,
                                         IMerchantChapterApplicationService applicationService,
                                         TopicSupplyReviewService supplyReviewService,
                                         MmsMerchantMapper merchantMapper) {
        this.nodeService = nodeService;
        this.chapterService = chapterService;
        this.applicationService = applicationService;
        this.supplyReviewService = supplyReviewService;
        this.merchantMapper = merchantMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public CmsTopicNode submit(Long merchantMemberId, Long topicId, String message, CmsTopicNode draft) {
        if (topicId == null || topicId <= 0) throw new IllegalStateException("缺少主题");
        nodeService.assertEmptyTopicDraft(merchantMemberId, draft);
        // 与 apply / submit 同一锁序:publication task → 主题行 → 章节
        supplyReviewService.lockTopic(topicId);
        MmsMerchant merchant = merchantMapper.selectMmsMerchantByMemberId(merchantMemberId);
        Integer categoryId = merchant == null || merchant.getCategoryId() == null
                ? null : merchant.getCategoryId().intValue();
        CmsTopicChapter chapter = chapterService.openChapterForEmptyTopic(
                topicId, merchantMemberId, categoryId, draft.getName());
        // 原申请入口:启用商家、品类匹配、招商窗口、自己的主题 —— 全部再走一遍;申请待主办方审核
        applicationService.apply(chapter.getId(), merchantMemberId, message);
        return nodeService.submit(merchantMemberId, chapter.getId(), draft);
    }
}
