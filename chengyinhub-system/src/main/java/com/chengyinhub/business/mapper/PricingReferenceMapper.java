package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.Map;

public interface PricingReferenceMapper {
    Map<String, Object> selectCityReference(@Param("topicId") Long topicId,
                                             @Param("subType") String subType);
}
