package com.chengyinhub.business.service.map;

import com.chengyinhub.business.domain.map.MapPublicException;
import com.chengyinhub.business.domain.map.MapPublicRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * selectPublicCmsTopicList 没有单一公开坐标。selectNearbyTopics 少了
 * primaryCirclePublicGate 和 clubMemberOnlyGate，不能当作权威公开条件。
 */
@Component
public class ThemeMapPublicSourceAdapter implements MapPublicSourceAdapter {

    public static final String SOURCE = "theme";
    public static final String REASON = "NO_AUTHORITATIVE_PUBLIC_QUERY";

    @Override
    public String sourceType() { return SOURCE; }

    @Override
    public boolean available() { return false; }

    @Override
    public String unavailableReason() { return REASON; }

    @Override
    public List<MapPublicRow> page(long viewerId, BigDecimal minLat, BigDecimal maxLat,
                                   BigDecimal minLng, BigDecimal maxLng, Long afterId, int limit) {
        throw new MapPublicException("SOURCE_NOT_READ");
    }
}
