package com.chengyinhub.business.push;

/**
 * R11 growth-loops：发给单个会员的一条提醒（召回、邀请奖励、CITY 领地事件）。
 * 文案两种语言都带上，按设备语言挑（en* 用英文，其他用中文）。
 *
 * @param kind     {@link PushMessage#KIND_GROWTH} 或 {@link PushMessage#KIND_CITY}
 * @param threadId aps.thread-id 与 apns-collapse-id，例如 winback、city-cell-123
 * @param page     小程序源路径（App 按它路由），不带 scheme/host
 * @param ttlSeconds APNs 过期秒数
 */
public record MemberNotice(String kind, String threadId, String page,
                           String zhTitle, String zhBody, String enTitle, String enBody, long ttlSeconds)
{
    public PushMessage message(boolean english)
    {
        return new PushMessage(kind, english ? enTitle : zhTitle, english ? enBody : zhBody, threadId, page, null);
    }
}
