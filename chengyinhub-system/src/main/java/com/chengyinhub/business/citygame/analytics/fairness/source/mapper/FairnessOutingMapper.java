package com.chengyinhub.business.citygame.analytics.fairness.source.mapper;

import com.chengyinhub.business.citygame.analytics.fairness.source.FairnessOuting;
import org.apache.ibatis.annotations.Param;

/** Fairness-owned outing, counting switch, and receipt. Does not write onsite tables. */
public interface FairnessOutingMapper {
    int insertActive(@Param("memberId") long memberId, @Param("instanceId") String instanceId);

    FairnessOuting selectByInstance(@Param("instanceId") String instanceId);

    Integer selectCounting(@Param("sourceRef") String sourceRef, @Param("boardId") String boardId,
                           @Param("regionId") String regionId, @Param("seasonId") String seasonId,
                           @Param("rulesVersion") String rulesVersion);

    int insertReceipt(@Param("sourceRef") String sourceRef, @Param("eventId") String eventId,
                       @Param("receivedAt") long receivedAt);
}
