package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MemberVisitedPoi;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 点亮城市「点到店」非参与地点到点记录。INSERT IGNORE 幂等,返回 1=首次到点 0=已到过。
 */
public interface MemberVisitedPoiMapper {
    int insertVisitedPoiIgnore(@Param("memberId") Long memberId, @Param("geoKey") String geoKey,
                               @Param("poiName") String poiName, @Param("lat") BigDecimal lat,
                               @Param("lng") BigDecimal lng, @Param("firstVisitTime") Date firstVisitTime);

    // ---- O-02 稳定身份(仅开关 roam.visited_poi.stable_identity=true 时调用,依赖 poi_key 列迁移) ----

    /** 同一玩家是否已有该稳定身份的到点记录。 */
    int countByPoiKey(@Param("memberId") Long memberId, @Param("poiKey") String poiKey);

    /** 同一玩家在包围盒内的到点记录(含未回填 poi_key 的旧行),调用方再按球面距离精筛。 */
    List<MemberVisitedPoi> selectMemberVisitsInBox(@Param("memberId") Long memberId,
                                                   @Param("minLat") BigDecimal minLat, @Param("maxLat") BigDecimal maxLat,
                                                   @Param("minLng") BigDecimal minLng, @Param("maxLng") BigDecimal maxLng);

    /** 包围盒内上架的地标 POI(roam_poi.type=1),只取 id/坐标,调用方按球面距离挑最近。 */
    List<MemberVisitedPoi> selectLandmarkPoisInBox(@Param("minLat") BigDecimal minLat, @Param("maxLat") BigDecimal maxLat,
                                                   @Param("minLng") BigDecimal minLng, @Param("maxLng") BigDecimal maxLng);

    /** geo_key 写与 poi_key 相同的值,只为满足旧唯一键 uk_member_geo 的非空约束,不再承担身份。 */
    int insertVisitedPoiWithKeyIgnore(@Param("memberId") Long memberId, @Param("poiKey") String poiKey,
                                      @Param("poiName") String poiName, @Param("lat") BigDecimal lat,
                                      @Param("lng") BigDecimal lng, @Param("firstVisitTime") Date firstVisitTime);
}
