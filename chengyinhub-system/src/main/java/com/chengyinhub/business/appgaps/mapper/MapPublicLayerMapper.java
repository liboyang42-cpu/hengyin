package com.chengyinhub.business.appgaps.mapper;

import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 公共地图图层只读：只取对外公开的门店（审核通过、启用、有坐标）与已上架活动（审核通过、上架、有坐标）。 */
public interface MapPublicLayerMapper {
    List<Map<String, Object>> selectStores(@Param("south") BigDecimal south, @Param("north") BigDecimal north,
                                           @Param("west") BigDecimal west, @Param("east") BigDecimal east,
                                           @Param("afterId") long afterId, @Param("limit") int limit);
    List<Map<String, Object>> selectActivities(@Param("south") BigDecimal south, @Param("north") BigDecimal north,
                                               @Param("west") BigDecimal west, @Param("east") BigDecimal east,
                                               @Param("afterId") long afterId, @Param("limit") int limit);
}
