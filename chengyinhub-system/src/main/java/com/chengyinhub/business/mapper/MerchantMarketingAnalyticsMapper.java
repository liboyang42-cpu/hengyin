package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.MerchantMarketingAnalyticsFact;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** 商家营销页的独立事件计数，只接受服务端解析出的商家 ownerMemberId。 */
public interface MerchantMarketingAnalyticsMapper {
    MerchantMarketingAnalyticsFact selectAnalytics(
            @Param("ownerMemberId") Long ownerMemberId,
            @Param("previousStart") Date previousStart,
            @Param("currentStart") Date currentStart,
            @Param("endExclusive") Date endExclusive);

    /**
     * D-93:营销首页与店铺参谋共用的核销事件时间([since, endExclusive) 半开)。
     * 只被 {@code MerchantInsightServiceImpl} 调用,落窗与计数在 Java 侧统一完成。
     */
    List<Date> selectVerifyEventTimes(
            @Param("ownerMemberId") Long ownerMemberId,
            @Param("since") Date since,
            @Param("endExclusive") Date endExclusive);
}
