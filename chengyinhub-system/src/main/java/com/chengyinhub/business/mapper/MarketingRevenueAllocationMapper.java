package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface MarketingRevenueAllocationMapper {

    int insertAllocation(MarketingRevenueAllocation allocation);

    MarketingRevenueAllocation selectById(@Param("id") Long id);

    MarketingRevenueAllocation selectByIdForUpdate(@Param("id") Long id);

    MarketingRevenueAllocation selectByOrder(@Param("orderType") Integer orderType,
                                             @Param("orderId") Long orderId);

    MarketingRevenueAllocation selectByOrderForUpdate(@Param("orderType") Integer orderType,
                                                      @Param("orderId") Long orderId);

    List<MarketingRevenueAllocation> selectByAuthor(@Param("authorMemberId") Long authorMemberId);

    List<MarketingRevenueAllocation> selectList(@Param("status") Integer status,
                                                @Param("scenario") Integer scenario);

    /** 按 DB 时钟推进到期应计；无调用方传入时间，避免任意时间升级财务状态。 */
    int markSettleableDue();

    int markSettleable(@Param("id") Long id);

    int updateAfterReversal(@Param("id") Long id,
                            @Param("reversedFen") Long reversedFen,
                            @Param("status") Integer status,
                            @Param("updateTime") Date updateTime);
}
