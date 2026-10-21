package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.map.MapPublicRow;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/** 公开地图只读查询。没有插入、更新或删除。 */
public interface MapPublicMapper {

    List<MapPublicRow> selectRoamPois(@Param("viewerId") long viewerId,
                                      @Param("minLat") BigDecimal minLat,
                                      @Param("maxLat") BigDecimal maxLat,
                                      @Param("minLng") BigDecimal minLng,
                                      @Param("maxLng") BigDecimal maxLng,
                                      @Param("afterId") Long afterId,
                                      @Param("limit") int limit);

    List<MapPublicRow> selectStores(@Param("minLat") BigDecimal minLat,
                                    @Param("maxLat") BigDecimal maxLat,
                                    @Param("minLng") BigDecimal minLng,
                                    @Param("maxLng") BigDecimal maxLng,
                                    @Param("afterId") Long afterId,
                                    @Param("limit") int limit);

    List<MapPublicRow> selectActivities(@Param("minLat") BigDecimal minLat,
                                        @Param("maxLat") BigDecimal maxLat,
                                        @Param("minLng") BigDecimal minLng,
                                        @Param("maxLng") BigDecimal maxLng,
                                        @Param("afterId") Long afterId,
                                        @Param("limit") int limit);
}
