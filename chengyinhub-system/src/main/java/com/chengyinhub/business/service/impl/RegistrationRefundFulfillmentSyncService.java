package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.IFreeExploreBundleRefundService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 只按原退款ID消费已提交事实；不接收success参数、不调用渠道、不另建退款意图。 */
@Service
public class RegistrationRefundFulfillmentSyncService {
    @Autowired
    private IFreeExploreBundleRefundService freeExploreBundleRefundService;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void sync(Long originalRefundId) {
        if (originalRefundId == null || originalRefundId <= 0) {
            throw new IllegalArgumentException("原退款ID无效");
        }
        freeExploreBundleRefundService.syncRefundOutcome(originalRefundId);
    }
}
