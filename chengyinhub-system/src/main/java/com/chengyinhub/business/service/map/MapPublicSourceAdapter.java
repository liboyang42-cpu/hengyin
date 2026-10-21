package com.chengyinhub.business.service.map;

import com.chengyinhub.business.domain.map.MapPublicRow;

import java.math.BigDecimal;
import java.util.List;

/** 一个地图来源。不可读的实现不得查询行。 */
public interface MapPublicSourceAdapter {

    String sourceType();

    boolean available();

    String unavailableReason();

    List<MapPublicRow> page(long viewerId, BigDecimal minLat, BigDecimal maxLat,
                            BigDecimal minLng, BigDecimal maxLng, Long afterId, int limit);
}
