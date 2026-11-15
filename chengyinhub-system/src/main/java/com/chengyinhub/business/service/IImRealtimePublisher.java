package com.chengyinhub.business.service;

import java.util.Map;

/**
 * W5 C2：打开中的会话实时提示（SSE）。只发“有变化”的提示（消息 id、会话 id、种类），不带正文；
 * 客户端收到后按原接口（/api/im/messages?after_id、/api/im/message/extras）补拉，可见性仍由原接口判定。
 *
 * <p>实现：事务提交后发布到 Redis pub/sub，各节点把它投给本机订阅者（两节点安全）；Redis 不可用时只投本机。
 * 发布失败绝不影响业务写入。
 */
public interface IImRealtimePublisher
{
    String MESSAGE_CREATED = "message.created";
    String MESSAGE_UPDATED = "message.updated";
    String REACTION_UPDATED = "reaction.updated";
    String PIN_UPDATED = "pin.updated";
    String TYPING = "typing";

    void publish(long conversationId, String type, Map<String, Object> data);
}
