package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.domain.OfficialBroadcastLog;

/** 广播专属持久投递意图；唯一身份始终为 (broadcast, member, channel)。 */
public interface OfficialBroadcastLogMapper
{
    java.util.List<java.util.Map<String,Object>> deliveryRows(@Param("broadcastId") Long broadcastId);
    java.util.Map<String,Object> lockDelivery(@Param("id") Long id);
    int snapshot(@Param("broadcastId") Long broadcastId,@Param("memberId") Long memberId,@Param("role") String role,@Param("channel") String channel,@Param("title") String title,@Param("sub") String sub,@Param("state") String state);
    int deliveryState(@Param("id") Long id,@Param("state") String state,@Param("intent") Long intent,@Param("message") Long message,@Param("attempt") int attempt);
    /** Historical direct-message protocol; never overload the Map recovery lock. */
    OfficialBroadcast lockBroadcast(@Param("broadcastId") Long broadcastId);
    List<OfficialBroadcast> selectRecoverableBroadcasts();
    List<OfficialBroadcastLog> selectByBroadcast(@Param("broadcastId") Long broadcastId);
    OfficialBroadcastLog lockDeliveryIntent(@Param("id") Long id);
    int insertPending(OfficialBroadcastLog log);
    int insertIgnore(OfficialBroadcastLog log);
    int markReached(@Param("broadcastId") Long broadcastId, @Param("memberId") Long memberId,
                    @Param("channel") String channel);
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
