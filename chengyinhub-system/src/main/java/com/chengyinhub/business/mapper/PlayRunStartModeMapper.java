package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** CG-ALL-11:本局开局方式(DESK/ALT)与路线替代起点。 */
public interface PlayRunStartModeMapper {

    String selectRunMode(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
            @Param("topicId") Long topicId);

    int upsertRunMode(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
            @Param("topicId") Long topicId, @Param("runMode") String runMode,
            @Param("altStationId") Long altStationId);

    int deleteRunMode(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
            @Param("topicId") Long topicId);

    /** 启用中的替代站:id/name/longitude/latitude。 */
    List<Map<String, Object>> selectAltStations(@Param("topicId") Long topicId);
}
