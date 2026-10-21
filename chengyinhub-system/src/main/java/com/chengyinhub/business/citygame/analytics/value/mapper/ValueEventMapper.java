package com.chengyinhub.business.citygame.analytics.value.mapper;

import com.chengyinhub.business.citygame.analytics.value.ValueEventRow;
import com.chengyinhub.business.citygame.analytics.value.ValueReportRow;
import com.chengyinhub.business.citygame.analytics.value.ValueRequestRow;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface ValueEventMapper {
    int insertEvent(ValueEventRow row);

    ValueEventRow selectByDedupe(String dedupeKey);

    List<ValueEventRow> selectVisits(@Param("regionId") String regionId, @Param("boardId") String boardId,
            @Param("seasonId") String seasonId, @Param("rulesReleaseId") String rulesReleaseId,
            @Param("from") Date from, @Param("to") Date to, @Param("cursorAt") Date cursorAt,
            @Param("cursorId") Long cursorId, @Param("limit") int limit);

    List<ValueEventRow> selectFacts(@Param("regionId") String regionId, @Param("boardId") String boardId,
            @Param("seasonId") String seasonId, @Param("rulesReleaseId") String rulesReleaseId,
            @Param("limit") int limit);

    int insertReport(ValueReportRow row);

    ValueReportRow selectReportByKey(String resultKey);

    ValueReportRow selectReportById(long id);

    int insertRequest(ValueRequestRow row);

    ValueRequestRow selectRequest(String requestId);
}
