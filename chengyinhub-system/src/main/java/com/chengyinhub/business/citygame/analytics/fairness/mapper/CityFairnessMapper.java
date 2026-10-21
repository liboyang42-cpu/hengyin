package com.chengyinhub.business.citygame.analytics.fairness.mapper;

import com.chengyinhub.business.citygame.analytics.fairness.CityFairnessReadRow;
import com.chengyinhub.business.citygame.analytics.fairness.CityFairnessReader.EventRow;
import com.chengyinhub.business.citygame.analytics.fairness.CityFairnessReader.ParameterRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** No @Mapper annotation. Scanned with the other mappers. Candidate tables are not created here. */
public interface CityFairnessMapper {
    List<ParameterRow> selectParameters();

    List<EventRow> selectEvents(@Param("actorId") long actorId,
                                 @Param("windowStartExclusive") long windowStartExclusive,
                                 @Param("asOfInclusive") long asOfInclusive,
                                 @Param("limit") int limit);

    EventRow selectEvent(@Param("source") String source, @Param("eventId") String eventId);

    int insertEvent(EventRow row);

    int insertRead(CityFairnessReadRow row);

    CityFairnessReadRow selectRead(@Param("accountId") long accountId, @Param("queryId") String queryId);
}
