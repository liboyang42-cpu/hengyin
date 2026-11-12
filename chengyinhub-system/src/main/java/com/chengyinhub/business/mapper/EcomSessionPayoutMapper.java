package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.EcomLedgerComponent;
import com.chengyinhub.business.domain.EcomOrderSeller;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Session obligations use negative activity/detail ids in the shared payout scope namespace. */
public interface EcomSessionPayoutMapper {
    List<Long> selectMissingActivityIds(@Param("limit") int limit);
    List<EcomOrderSeller> selectPaidTrades(@Param("activityId") Long activityId);
    List<EcomLedgerComponent> selectComponents(@Param("activityId") Long activityId,
                                             @Param("registrationId") Long registrationId);
    int markShareDetailsReceived(@Param("shareOrderId") Long shareOrderId);
    int completeActivityIfReceived(@Param("shareOrderId") Long shareOrderId);
}
