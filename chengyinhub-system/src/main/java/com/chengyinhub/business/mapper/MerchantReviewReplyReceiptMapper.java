package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

/** Read-only recovery of committed merchant reply audit facts. */
public interface MerchantReviewReplyReceiptMapper {
    int countApplied(@Param("merchantId") Long merchantId, @Param("ownerMemberId") Long ownerMemberId,
                     @Param("actorMemberId") Long actorMemberId, @Param("reviewId") Long reviewId,
                     @Param("action") String action, @Param("requestId") String requestId);
}
