package com.chengyinhub.business.mapper.channel.platform;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** 频道 Webhook（订阅/投递/端点/入站日志）持久化原语；授权、校验与一次性凭据在 ChannelWebhookService。 */
public interface ChannelWebhookMapper
{
    int insertSubscription(Map<String, Object> row);

    Map<String, Object> selectSubscription(@Param("id") long id);

    Map<String, Object> lockSubscription(@Param("id") long id);

    List<Map<String, Object>> listSubscriptions(@Param("ownerId") long ownerId, @Param("channelId") long channelId,
            @Param("beforeId") Long beforeId, @Param("limit") int limit);

    int updateSubscription(Map<String, Object> row);

    int deleteSubscription(@Param("id") long id, @Param("revision") int revision);

    int rotateSubscriptionSecret(@Param("id") long id, @Param("revision") int revision, @Param("secretSha256") String secretSha256,
            @Param("secretPrefix") String secretPrefix);

    int insertDelivery(Map<String, Object> row);

    Map<String, Object> selectDelivery(@Param("id") long id);

    Map<String, Object> lockDelivery(@Param("subscriptionId") long subscriptionId, @Param("id") long id);

    int requeueDelivery(@Param("id") long id, @Param("expectedState") String expectedState, @Param("expectedAttempt") int expectedAttempt);

    List<Map<String, Object>> listDeliveries(@Param("subscriptionId") long subscriptionId, @Param("state") String state,
            @Param("beforeId") Long beforeId, @Param("limit") int limit);

    Map<String, Object> selectEndpoint(@Param("id") long id);

    List<Map<String, Object>> listEndpoints(@Param("ownerId") long ownerId, @Param("channelId") long channelId,
            @Param("beforeId") Long beforeId, @Param("limit") int limit);

    List<Map<String, Object>> listInboundLogs(@Param("endpointId") long endpointId, @Param("result") String result,
            @Param("beforeId") Long beforeId, @Param("limit") int limit);
}
