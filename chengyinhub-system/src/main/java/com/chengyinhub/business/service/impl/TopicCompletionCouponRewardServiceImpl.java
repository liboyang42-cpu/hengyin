package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CmsTopicCompletionCouponRewardMapper;
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
    private ISmsCouponHistoryService smsCouponHistoryService;

    /** Absent by default; only the separately reviewed receipt profile installs this hook. */
    @Autowired(required = false)
    private com.chengyinhub.business.service.coupon.TopicCompletionCouponReceiptService receiptService;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("topicCompletionCouponHistoryResolver")
    private com.chengyinhub.business.service.coupon.TopicCompletionCouponHistoryResolving receiptProtocol;

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
        if (receiptProtocol == null) throw new IllegalStateException("Completion receipt protocol guard is unavailable");
        boolean recordedProtocol = receiptProtocol.protocolInstalled();
        if (recordedProtocol && receiptService == null) throw new IllegalStateException("Recorded completion issuer is disabled");
        if (!recordedProtocol && receiptService != null) throw new IllegalStateException("Recorded completion schema is unavailable");
        if (rewardMapper.insertIgnore(topicId, memberId, couponId) == 0) return false;
        boolean granted = receiptService == null
                ? smsCouponHistoryService.grantCouponById(memberId, couponId)
                : receiptService.grantForNewCompletion(topicId, memberId, couponId);
        if (!granted)
        {
            throw new IllegalStateException("主题通关奖励券发放失败");
        }
        return true;
    }
}
