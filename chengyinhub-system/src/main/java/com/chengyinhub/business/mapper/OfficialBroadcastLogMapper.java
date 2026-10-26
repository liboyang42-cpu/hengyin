package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.OfficialBroadcastLog;

/**
 * 官方通知分发日志 Mapper(uk_bc_member_channel 防重发)
 *
 * @author cy
 * @date 2026-07-08
 */
public interface OfficialBroadcastLogMapper
{
    java.util.List<java.util.Map<String,Object>> deliveryRows(@Param("broadcastId") Long broadcastId);
    java.util.Map<String,Object> lockDelivery(@Param("id") Long id);
    int snapshot(@Param("broadcastId") Long broadcastId,@Param("memberId") Long memberId,@Param("role") String role,@Param("channel") String channel,@Param("title") String title,@Param("sub") String sub,@Param("state") String state);
    int deliveryState(@Param("id") Long id,@Param("state") String state,@Param("intent") Long intent,@Param("message") Long message,@Param("attempt") int attempt);
    /** insert ignore:同一(broadcast,member,channel)已存在则返回 0(不重发)。 */
    int insertIgnore(OfficialBroadcastLog log);

    int countReached(@Param("broadcastId") Long broadcastId);

    int countClicked(@Param("broadcastId") Long broadcastId);

    /** 频控:该 member 近 minutes 分钟已收到的官方站内通知条数。 */
    int countRecentByMember(@Param("memberId") Long memberId, @Param("minutes") int minutes);

    /** 点击回流。 */
    int markClicked(@Param("broadcastId") Long broadcastId, @Param("memberId") Long memberId, @Param("channel") String channel);

    /** 发送成功后置达:reached=1(此前 insertIgnore 落的是 reached=0 的占位/尝试行,失败则不置达,统计不虚高)。 */
    int markReached(@Param("broadcastId") Long broadcastId, @Param("memberId") Long memberId, @Param("channel") String channel);
}
