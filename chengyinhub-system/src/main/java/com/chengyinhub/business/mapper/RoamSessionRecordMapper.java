package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** Client-reported archives never participate in exploration/reward accounting. */
public interface RoamSessionRecordMapper {
    List<Map<String, Object>> selectOriginalRecords(@Param("memberId") Long memberId,
            @Param("sessionId") Long sessionId, @Param("tiles") List<String> tiles);
    int insertRecords(@Param("memberId") Long memberId, @Param("sessionId") Long sessionId,
            @Param("records") List<Map<String, Object>> records, @Param("receivedAt") Date receivedAt);
    int deleteBySession(@Param("sessionId") Long sessionId, @Param("memberId") Long memberId);
}
