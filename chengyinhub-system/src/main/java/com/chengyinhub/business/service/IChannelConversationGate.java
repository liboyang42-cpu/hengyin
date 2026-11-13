package com.chengyinhub.business.service;

/**
 * IM 原链路（/api/im/messages|send|send-receipt|read）遇到频道会话(type=5)时的授权闸。
 * 频道读写以空间关系 + 频道有效权限为真源；会话成员行只做索引，由闸在授权通过后补齐。
 * 拒绝时抛 ServiceException(403/404/409/429)。
 */
public interface IChannelConversationGate
{
    int CONVERSATION_TYPE_CHANNEL = 5;

    boolean isChannel(Long conversationId);

    void requireRead(Long memberId, Long conversationId);

    void requireSend(Long memberId, Long conversationId);
}
