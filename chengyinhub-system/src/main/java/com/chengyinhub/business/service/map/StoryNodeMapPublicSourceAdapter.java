package com.chengyinhub.business.service.map;

import com.chengyinhub.business.domain.map.MapPublicException;
import com.chengyinhub.business.domain.map.MapPublicRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** status = 1 且 audit_status = 1 不能证明未来剧情节点可公开。 */
@Component
public class StoryNodeMapPublicSourceAdapter implements MapPublicSourceAdapter {

    public static final String SOURCE = "story_node";
    public static final String REASON = "STORY_NODE_NOT_PROVEN_PUBLIC";

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
