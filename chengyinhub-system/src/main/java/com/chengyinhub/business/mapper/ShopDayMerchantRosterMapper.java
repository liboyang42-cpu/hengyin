package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.ShopDayMerchantRosterFact;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ShopDayMerchantRosterMapper {
    int countLiveMerchantScope(@Param("topicId") Long topicId,
                               @Param("merchantMemberId") Long merchantMemberId);

    List<ShopDayMerchantRosterFact> selectRoster(@Param("topicId") Long topicId,
                                                 @Param("merchantMemberId") Long merchantMemberId);
}
