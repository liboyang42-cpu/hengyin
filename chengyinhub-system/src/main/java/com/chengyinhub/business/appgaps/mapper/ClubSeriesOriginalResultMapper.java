package com.chengyinhub.business.appgaps.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Map;

/** club_event_series_original_result：系列创建初始结果快照（只插入、只读）。 */
public interface ClubSeriesOriginalResultMapper {
    int insert(Map<String, Object> row);
    Map<String, Object> selectByRequest(@Param("clubId") Long clubId, @Param("actor") Long actor, @Param("requestId") String requestId);
}
