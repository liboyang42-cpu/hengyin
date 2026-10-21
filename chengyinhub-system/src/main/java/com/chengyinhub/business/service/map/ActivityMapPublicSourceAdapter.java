package com.chengyinhub.business.service.map;

import com.chengyinhub.business.domain.map.MapPublicRow;
import com.chengyinhub.business.mapper.MapPublicMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class ActivityMapPublicSourceAdapter implements MapPublicSourceAdapter {

    public static final String SOURCE = "activity";

    @Autowired
    private MapPublicMapper mapPublicMapper;

    @Override
    public String sourceType() { return SOURCE; }

    @Override
    public boolean available() { return true; }

    @Override
    public String unavailableReason() { return null; }

    @Override
    public List<MapPublicRow> page(long viewerId, BigDecimal minLat, BigDecimal maxLat,
                                   BigDecimal minLng, BigDecimal maxLng, Long afterId, int limit) {
        return mapPublicMapper.selectActivities(minLat, maxLat, minLng, maxLng, afterId, limit);
    }
}
