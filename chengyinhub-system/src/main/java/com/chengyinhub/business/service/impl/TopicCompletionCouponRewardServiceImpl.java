package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CmsTopicCompletionCouponRewardMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.ITopicCompletionCouponRewardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TopicCompletionCouponRewardServiceImpl implements ITopicCompletionCouponRewardService
{
    private static final Logger logger = LoggerFactory.getLogger(TopicCompletionCouponRewardServiceImpl.class);

    @Autowired
    private CmsTopicCompletionCouponRewardMapper rewardMapper;

    @Autowired
    private SmsCouponMapper smsCouponMapper;

    @Autowired
    private ISmsCouponHistoryService smsCouponHistoryService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean grantOnce(Long topicId, Long memberId, Long couponId)
    {
        if (topicId == null || memberId == null || couponId == null || couponId <= 0) return false;
        // C-30:奖励券已失效(3)/已停发(4) → 不发券、不落领取标记,通关/终章核销(含应付台账)照常提交
        if (smsCouponHistoryService.isCouponNotIssuable(couponId))
        {
            logger.warn("COUPON_DISABLED_SKIP caller=topicCompletion topicId={} memberId={} couponId={}", topicId, memberId, couponId);
            return false;
        }
        if (rewardMapper.insertIgnore(topicId, memberId, couponId) == 0) return false;
        Long historyId = smsCouponHistoryService.grantCouponWithReceipt(memberId,
                smsCouponMapper.selectSmsCouponById(couponId));
        if (historyId == null || historyId <= 0)
        {
            throw new IllegalStateException("主题通关奖励券发放失败");
        }
        if (rewardMapper.bindHistoryId(topicId, memberId, couponId, historyId) != 1)
        {
            throw new IllegalStateException("主题通关奖励券原回执写入失败");
        }
        return true;
    }
}
