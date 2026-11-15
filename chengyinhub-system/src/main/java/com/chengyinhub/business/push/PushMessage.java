package com.chengyinhub.business.push;

/**
 * 一条要推的语义消息。
 *
 * @param kind           chat / club / event / growth / city
 * @param threadId       aps.thread-id，同时作为 apns-collapse-id 与合并窗口键
 * @param page           小程序源路径（带 query，无 scheme/host），App 按它路由
 * @param conversationId 仅 chat
 * @param messageId      仅 chat（W5 C2：点开通知直接定位到这条消息），可为 null
 */
public record PushMessage(String kind, String title, String body, String threadId, String page, String conversationId,
        String messageId)
{
    public PushMessage(String kind, String title, String body, String threadId, String page, String conversationId)
    {
        this(kind, title, body, threadId, page, conversationId, null);
    }

    public static final String KIND_CHAT = "chat";
    public static final String KIND_CLUB = "club";
    public static final String KIND_EVENT = "event";
    /** R11 growth-loops: win-back reminders and invite rewards. */
    public static final String KIND_GROWTH = "growth";
    /** R11 growth-loops: CITY territory events (challenged / lost / weekly yield). */
    public static final String KIND_CITY = "city";
}
