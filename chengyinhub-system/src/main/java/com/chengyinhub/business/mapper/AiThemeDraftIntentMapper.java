package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.AiThemeDraftIntentRow;
import java.util.List;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;

public interface AiThemeDraftIntentMapper {
    int insertPrepared(AiThemeDraftIntentRow row);
    AiThemeDraftIntentRow selectByPrepareKey(@Param("memberId") Long memberId, @Param("prepareKey") String prepareKey);
    AiThemeDraftIntentRow selectByPrepareKeyForUpdate(@Param("memberId") Long memberId, @Param("prepareKey") String prepareKey);
    AiThemeDraftIntentRow selectOwned(@Param("memberId") Long memberId, @Param("requestId") String requestId);
    List<AiThemeDraftIntentRow> selectRecovery(@Param("memberId") Long memberId, @Param("beforeId") Long beforeId);
    int claim(@Param("id") Long id, @Param("memberId") Long memberId, @Param("requestHash") String requestHash,
            @Param("planHash") String planHash, @Param("claimToken") String claimToken, @Param("now") Instant now);
    int rejectPrepared(AiThemeDraftIntentRow row);
    int complete(AiThemeDraftIntentRow row);
    List<AiThemeDraftIntentRow> selectExpiredContexts(@Param("limit") int limit, @Param("now") Instant now);
    List<AiThemeDraftIntentRow> selectExpiredResults(@Param("limit") int limit, @Param("now") Instant now);
    int markContextPurged(@Param("id") Long id, @Param("requestId") String requestId, @Param("expiry") Instant expiry, @Param("now") Instant now);
    int markResultPurged(@Param("id") Long id, @Param("requestId") String requestId, @Param("expiry") Instant expiry, @Param("now") Instant now);
}
