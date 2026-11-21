package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.seed.SeedGeoPoint;
import com.chengyinhub.business.domain.dto.seed.SeedPlace;
import com.chengyinhub.business.domain.dto.seed.SeedRoute;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** Q3 seed-tools: bulk import / export of official places and routes, and the district density report. */
public interface SeedContentMapper {

    /** Every roam_poi in the box (any type or status): dedupe candidates and density places. */
    List<SeedPlace> selectPlacesInBox(@Param("minLat") BigDecimal minLat, @Param("maxLat") BigDecimal maxLat,
            @Param("minLng") BigDecimal minLng, @Param("maxLng") BigDecimal maxLng);

    int insertImportedPlace(SeedPlace place);

    List<SeedPlace> selectOfficialPlacesForExport(@Param("cityCode") String cityCode);

    List<SeedPlace> selectPlacesByIds(@Param("ids") Collection<Long> ids);

    List<String> selectExistingRouteNames(@Param("names") Collection<String> names);

    int insertRouteDraft(SeedRoute route);

    int insertRouteNode(@Param("topicId") Long topicId, @Param("sortId") int sortId, @Param("place") SeedPlace place,
            @Param("templateId") Long templateId, @Param("createBy") String createBy);

    /** key=topic_id, flag1=route live for players, flag2=node has a game. */
    List<SeedGeoPoint> selectRouteNodesInBox(@Param("minLat") BigDecimal minLat, @Param("maxLat") BigDecimal maxLat,
            @Param("minLng") BigDecimal minLng, @Param("maxLng") BigDecimal maxLng);

    /** Live shops (approved, enabled, not deleted); flag1=shop has a live place with a game. */
    List<SeedGeoPoint> selectLiveShopsInBox(@Param("minLat") BigDecimal minLat, @Param("maxLat") BigDecimal maxLat,
            @Param("minLng") BigDecimal minLng, @Param("maxLng") BigDecimal maxLng);

    /** key=board_id; points of boards that are not closed or archived. */
    List<SeedGeoPoint> selectBoardPointsInBox(@Param("minLat") BigDecimal minLat, @Param("maxLat") BigDecimal maxLat,
            @Param("minLng") BigDecimal minLng, @Param("maxLng") BigDecimal maxLng);

    int countActiveDailyMissions();

    int countOpeningEvents(@Param("cityKeyword") String cityKeyword);

    List<Map<String, Object>> selectBoardAreas();
}
