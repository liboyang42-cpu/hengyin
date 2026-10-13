package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.MerchantMarketingAnalyticsFact;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/** 商家营销页的独立事件计数，只接受服务端解析出的商家 ownerMemberId。 */
public interface MerchantMarketingAnalyticsMapper {
    MerchantMarketingAnalyticsFact selectAnalytics(
            @Param("ownerMemberId") Long ownerMemberId,
            @Param("previousStart") Date previousStart,
            @Param("currentStart") Date currentStart,
            @Param("endExclusive") Date endExclusive);
}
