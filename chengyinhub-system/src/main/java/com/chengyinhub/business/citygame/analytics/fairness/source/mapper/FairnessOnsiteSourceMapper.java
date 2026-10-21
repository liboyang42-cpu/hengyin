package com.chengyinhub.business.citygame.analytics.fairness.source.mapper;

import com.chengyinhub.business.citygame.analytics.fairness.source.OnsiteTaskFact;
import org.apache.ibatis.annotations.Param;

/** Read-only. No FOR UPDATE and no write against the onsite tables. */
public interface FairnessOnsiteSourceMapper {
    OnsiteTaskFact selectFact(@Param("resultId") String resultId);
}
