package com.chengyinhub.business.citygame.analytics.fairness.mapper;

import com.chengyinhub.business.citygame.analytics.fairness.CityFairnessReader.EventRow;
import com.chengyinhub.business.citygame.analytics.fairness.CityFairnessReader.ParameterRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Read-only. No @Mapper annotation. Nothing in the app calls this reader. */
public interface CityFairnessMapper {
    List<ParameterRow> selectParameters();

    List<EventRow> selectEvents(@Param("windowStartExclusive") long windowStartExclusive,
                                 @Param("asOfInclusive") long asOfInclusive);
}
