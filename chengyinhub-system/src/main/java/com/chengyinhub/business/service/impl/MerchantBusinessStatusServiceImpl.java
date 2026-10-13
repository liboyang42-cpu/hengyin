package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMerchantBusinessStatusService;
import com.chengyinhub.business.service.IMmsMerchantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 营业状态门禁实现。查询失败不做 fail-open 放行也不 fail-closed 拦截:数据库异常原样抛出,不吞成「营业中」。 */
@Service
public class MerchantBusinessStatusServiceImpl implements IMerchantBusinessStatusService {

    /** 「已打烊」;mms_merchant.business_status 为空时按营业中(建表默认,历史行)处理。 */
    private static final Integer BUSINESS_CLOSED = 0;
    private static final Long MERCHANT_APPROVED = 1L;
    private static final Long MERCHANT_ENABLED = 1L;
    private static final Integer NOT_DELETED = 0;

    @Autowired
    private ICmsTopicService topicService;
    @Autowired
    private ICmsActivityService activityService;
    @Autowired
    private IMmsMerchantService mmsMerchantService;

    @Override
    public boolean isTargetMerchantClosed(Integer ownerType, Long ownerId) {
        if (ownerId == null || ownerId <= 0) return false;
        Long memberId = resolveOwnerMemberId(ownerType, ownerId);
        if (memberId == null || memberId <= 0) return false;
        MmsMerchant merchant = mmsMerchantService.selectMmsMerchantByMemberId(memberId);
        if (merchant == null || !isActiveMerchant(merchant)) return false;
        return BUSINESS_CLOSED.equals(merchant.getBusinessStatus());
    }

    /** 归属 memberId:主题取发布者;场次优先创建者(若是生效商家),否则回落场次所属主题发布者。 */
    private Long resolveOwnerMemberId(Integer ownerType, Long ownerId) {
        if (Integer.valueOf(1).equals(ownerType)) {
            CmsTopic topic = topicService.selectCmsTopicById(ownerId);
            return topic == null ? null : topic.getMemberId();
        }
        if (Integer.valueOf(2).equals(ownerType)) {
            CmsActivity activity = activityService.selectCmsActivityById(ownerId);
            if (activity == null) return null;
            if (isActiveMerchantMember(activity.getMemberId())) return activity.getMemberId();
            if (activity.getTopicId() == null || activity.getTopicId() <= 0) return null;
            CmsTopic topic = topicService.selectCmsTopicById(activity.getTopicId());
            return topic == null ? null : topic.getMemberId();
        }
        return null;
    }

    private boolean isActiveMerchantMember(Long memberId) {
        if (memberId == null || memberId <= 0) return false;
        MmsMerchant merchant = mmsMerchantService.selectMmsMerchantByMemberId(memberId);
        return merchant != null && isActiveMerchant(merchant);
    }

    private boolean isActiveMerchant(MmsMerchant merchant) {
        return MERCHANT_APPROVED.equals(merchant.getStatus())
                && MERCHANT_ENABLED.equals(merchant.getAccountStatus())
                && NOT_DELETED.equals(merchant.getDelFlag());
    }
}
