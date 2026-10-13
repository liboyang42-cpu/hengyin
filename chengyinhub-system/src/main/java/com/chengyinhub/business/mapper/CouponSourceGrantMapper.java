package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CouponSourceGrant;
import org.apache.ibatis.annotations.Param;

public interface CouponSourceGrantMapper {
    int ensureIntent(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId,
                     @Param("memberId") Long memberId, @Param("couponId") Long couponId);
    CouponSourceGrant selectForUpdate(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);
    int markDelivered(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId,
                      @Param("historyId") Long historyId);
}
