package com.chengyinhub.business.push;

/**
 * 一条要推的语义消息。
 *
 * @param kind           chat / club / event
 * @param threadId       aps.thread-id，同时作为 apns-collapse-id 与合并窗口键
 * @param page           小程序源路径（带 query，无 scheme/host），App 按它路由
 * @param conversationId 仅 chat
 */
public record PushMessage(String kind, String title, String body, String threadId, String page, String conversationId)
{
    public static final String KIND_CHAT = "chat";
    public static final String KIND_CLUB = "club";
    public static final String KIND_EVENT = "event";
}
