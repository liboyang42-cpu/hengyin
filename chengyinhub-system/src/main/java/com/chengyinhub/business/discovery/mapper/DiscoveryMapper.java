package com.chengyinhub.business.discovery.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** K2 discovery: read-only candidates for the personal "near you" list. Nothing here writes. */
public interface DiscoveryMapper {

    /** Published, approved places in the box; same filter and same distance order as the roam pins. */
    List<Map<String, Object>> selectPlacesInBox(@Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                                @Param("minLng") double minLng, @Param("maxLng") double maxLng,
                                                @Param("limit") int limit);

    /** Published, not cancelled, not finished events in the box: public events, plus club events for that club's members. */
    List<Map<String, Object>> selectEventsInBox(@Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                                @Param("minLng") double minLng, @Param("maxLng") double maxLng,
                                                @Param("memberId") Long memberId, @Param("limit") int limit);
}
