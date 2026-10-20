package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayerBadge;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface RoamBadgeDeliveryMapper {
    int prepare(@Param("sessionId") Long sessionId, @Param("badge") PlayerBadge badge);
    Map<String, Object> selectForUpdate(@Param("memberId") Long memberId, @Param("sessionId") Long sessionId,
            @Param("badgeCode") String badgeCode, @Param("sourceId") Long sourceId);
    int complete(@Param("id") Long id, @Param("memberId") Long memberId);
    List<Map<String, Object>> readStatus(@Param("memberId") Long memberId, @Param("sessionId") Long sessionId);
    Long highWaterMark();
    List<Map<String, Object>> scanPending(@Param("afterId") Long afterId, @Param("throughId") Long throughId, @Param("limit") Integer limit);
    int ensureCursor();
    Map<String, Object> selectCursorForUpdate();
    int beginRound(@Param("throughId") Long throughId);
    int advanceCursor(@Param("expectedAfter") Long expectedAfter, @Param("afterId") Long afterId, @Param("throughId") Long throughId);
    int completeRound(@Param("expectedAfter") Long expectedAfter, @Param("throughId") Long throughId);
}
