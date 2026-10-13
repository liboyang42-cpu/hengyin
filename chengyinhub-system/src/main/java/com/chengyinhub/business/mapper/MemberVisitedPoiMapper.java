package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 点亮城市「点到店」非参与地点到点记录。INSERT IGNORE 幂等,返回 1=首次到点 0=已到过。
 */
public interface MemberVisitedPoiMapper {
    int insertVisitedPoiIgnore(@Param("memberId") Long memberId, @Param("geoKey") String geoKey,
                               @Param("poiName") String poiName, @Param("lat") BigDecimal lat,
                               @Param("lng") BigDecimal lng, @Param("firstVisitTime") Date firstVisitTime);
}
