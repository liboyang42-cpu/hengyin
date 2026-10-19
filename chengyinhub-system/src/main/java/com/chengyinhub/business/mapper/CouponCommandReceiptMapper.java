package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CouponCommandReceipt;
import org.apache.ibatis.annotations.Param;

public interface CouponCommandReceiptMapper {
    CouponCommandReceipt selectCurrent(@Param("actorMemberId") Long actorMemberId,
            @Param("merchantId") Long merchantId, @Param("requestId") String requestId);
    int insertReceipt(CouponCommandReceipt receipt);
}
