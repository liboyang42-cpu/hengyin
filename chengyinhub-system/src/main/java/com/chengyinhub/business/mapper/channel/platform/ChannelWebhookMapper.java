package com.chengyinhub.business.mapper.channel.platform;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * 频道 Webhook（订阅/投递/端点/入站日志）持久化原语；授权、校验与一次性凭据在 ChannelWebhookService，
 * 出站投递在 ChannelWebhookDispatcher，入站接收在 ChannelWebhookInboundService。
 */
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
            @Param("secretPrefix") String secretPrefix, @Param("secretCipher") String secretCipher);

    /** 投递器用：带完整 url 与密钥密文（只在服务端内部使用，从不进响应）。 */
    Map<String, Object> selectSubscriptionForDispatch(@Param("id") long id);

    /** 该会话即频道会话（同 id：type 5 频道会话或旧会话桥）时，频道上启用且未删除的订阅（事件扇出）。 */
    List<Map<String, Object>> listActiveConversationSubscriptions(@Param("conversationId") long conversationId);

    int recordSubscriptionOutcome(@Param("id") long id, @Param("ok") boolean ok, @Param("error") String error);

    int insertDelivery(Map<String, Object> row);

    Map<String, Object> selectDelivery(@Param("id") long id);

    Map<String, Object> lockDelivery(@Param("subscriptionId") long subscriptionId, @Param("id") long id);

    int requeueDelivery(@Param("id") long id, @Param("expectedState") String expectedState, @Param("expectedAttempt") int expectedAttempt,
            @Param("resetAttempt") boolean resetAttempt);

    /** 到期可投：PENDING（无 next_retry_at 或已到期）、FAILED 且已到期、INFLIGHT 且租约过期。 */
    List<Map<String, Object>> selectDueDeliveries(@Param("limit") int limit);

    /** 取件 CAS：state/attempt 不变才转 INFLIGHT 并 attempt+1、设租约。 */
    int claimDelivery(@Param("id") long id, @Param("expectedState") String expectedState, @Param("expectedAttempt") int expectedAttempt,
            @Param("leaseMs") long leaseMs);

    /** 结果落库：仅当仍是本次取件（INFLIGHT 且 attempt 相同）。nextRetryMs 为空表示终态。 */
    int finishDelivery(Map<String, Object> row);

    List<Map<String, Object>> listDeliveries(@Param("subscriptionId") long subscriptionId, @Param("state") String state,
            @Param("beforeId") Long beforeId, @Param("limit") int limit);

    Map<String, Object> selectEndpoint(@Param("id") long id);

    Map<String, Object> lockEndpoint(@Param("id") long id);

    /** 入站校验用：带密钥密文。 */
    Map<String, Object> selectEndpointForIngest(@Param("id") long id);

    int insertEndpoint(Map<String, Object> row);

    int updateEndpoint(Map<String, Object> row);

    int deleteEndpoint(@Param("id") long id, @Param("revision") int revision);

    int rotateEndpointSecret(@Param("id") long id, @Param("revision") int revision, @Param("tokenSha256") String tokenSha256,
            @Param("tokenPrefix") String tokenPrefix, @Param("secretCipher") String secretCipher);

    int touchEndpointCalled(@Param("id") long id);

    int countRecentInbound(@Param("endpointId") long endpointId, @Param("seconds") int seconds);

    /** INSERT IGNORE：0 = 该 nonce 已用过（重放）。 */
    int insertNonce(@Param("endpointId") long endpointId, @Param("nonce") String nonce, @Param("signedAt") long signedAt);

    int deleteExpiredNonces(@Param("seconds") int seconds, @Param("limit") int limit);

    int insertInboundLog(Map<String, Object> row);

    /** 频道的会话（同 id 的 type 5 频道会话或旧会话桥）与频道状态。 */
    Map<String, Object> selectChannelConversation(@Param("channelId") long channelId);

    int insertWebhookMessage(Map<String, Object> row);

    int touchWebhookConversation(@Param("conversationId") long conversationId, @Param("text") String text);

    int fanOutWebhookUnread(@Param("conversationId") long conversationId);

    List<Map<String, Object>> listEndpoints(@Param("ownerId") long ownerId, @Param("channelId") long channelId,
            @Param("beforeId") Long beforeId, @Param("limit") int limit);

    List<Map<String, Object>> listInboundLogs(@Param("endpointId") long endpointId, @Param("result") String result,
            @Param("beforeId") Long beforeId, @Param("limit") int limit);
}
