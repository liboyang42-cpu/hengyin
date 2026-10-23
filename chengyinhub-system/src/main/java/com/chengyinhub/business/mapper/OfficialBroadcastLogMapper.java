package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.domain.OfficialBroadcastLog;

/** 广播专属持久投递意图；唯一身份始终为 (broadcast, member, channel)。 */
public interface OfficialBroadcastLogMapper
{
    OfficialBroadcast lockBroadcast(@Param("broadcastId") Long broadcastId);
    List<OfficialBroadcast> selectRecoverableBroadcasts();
    List<OfficialBroadcastLog> selectByBroadcast(@Param("broadcastId") Long broadcastId);
    OfficialBroadcastLog lockDelivery(@Param("id") Long id);
    int insertPending(OfficialBroadcastLog log);
    int transition(@Param("id") Long id, @Param("expected") String expected,
                   @Param("state") String state, @Param("failureCode") String failureCode,
                   @Param("messageId") Long messageId);
    int countUnsuccessful(@Param("broadcastId") Long broadcastId);
    int countInappReached(@Param("broadcastId") Long broadcastId);
    int countReached(@Param("broadcastId") Long broadcastId);
    int countClicked(@Param("broadcastId") Long broadcastId);
    List<Map<String, Object>> channelStats(@Param("broadcastId") Long broadcastId);
    int countRecentByMember(@Param("memberId") Long memberId, @Param("minutes") int minutes);
    int markClicked(@Param("broadcastId") Long broadcastId, @Param("memberId") Long memberId,
                    @Param("channel") String channel);
}
