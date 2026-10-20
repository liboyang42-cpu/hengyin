package com.chengyinhub.business.service.coupon;

/** Mandatory server-side schema/history guard; independent of new-issuance activation. */
public interface TopicCompletionCouponHistoryResolving {
    boolean protocolInstalled();
    Long currentHistoryForClawback(long topic,long member,long coupon);
}
