package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** C delivery intents; original A drop ledger is read-only. */
public interface RoamRewardDeliveryMapper {
    Map<String, Object> selectOriginalRewardDrop(@Param("dropLogId") Long dropLogId, @Param("memberId") Long memberId);
    int prepare(@Param("dropLogId") Long dropLogId, @Param("memberId") Long memberId, @Param("couponId") Long couponId);
    int prepareXp(@Param("dropLogId") Long dropLogId, @Param("memberId") Long memberId, @Param("extraXp") Integer extraXp);
    int prepareUnknownXp(@Param("dropLogId") Long dropLogId, @Param("memberId") Long memberId);
    Map<String, Object> selectIntentForUpdate(@Param("dropLogId") Long dropLogId);
    int ensureCursor();
    Map<String, Object> selectCursorForUpdate();
    int beginRound(@Param("throughId") Long throughId);
    int advanceCursor(@Param("expectedAfter") Long expectedAfter, @Param("afterId") Long afterId, @Param("throughId") Long throughId);
    int completeRound(@Param("expectedAfter") Long expectedAfter, @Param("throughId") Long throughId);
    Map<String, Object> selectIntent(@Param("dropLogId") Long dropLogId);
    int completeIfDue(@Param("dropLogId") Long dropLogId, @Param("memberId") Long memberId,
            @Param("couponId") Long couponId, @Param("state") String state, @Param("historyId") Long historyId);
    Long highWaterMark();
    List<Map<String, Object>> scanOriginalPending(@Param("afterId") Long afterId, @Param("throughId") Long throughId,
            @Param("limit") Integer limit);
}
