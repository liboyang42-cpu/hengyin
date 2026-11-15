package com.chengyinhub.business.service;

import java.util.Date;

/**
 * App 远程推送扇出（APNs）。全部方法都不抛异常、不阻塞调用方：真正的读偏好与发送在提交后放进线程池。
 */
public interface IPushFanoutService
{
    /** 发送端是否已配置（未配置时所有扇出都是空操作）。 */
    boolean apnsConfigured();

    /** 聊天/频道消息落库后调用（事务提交后异步扇出给除发送者外的会话成员）。 */
    void chatMessageSent(Long messageId);

    /** 俱乐部通知任务选择了 APNS 渠道：记下标记（幂等）。 */
    void markClubCampaignPush(long campaignId);

    boolean clubCampaignPush(long campaignId);

    /** 俱乐部通知的站内信已送达某收件人后，再推一条到其设备。 */
    void clubNoticeDelivered(long campaignId, long clubId, Long activityId, String title, String content, long recipientId);

    /** 出发前提醒（BeforeStartNotifyTask 扫到的票次 × 已付费成员）：每个 (票次, 会员) 只推一次。 */
    void eventReminder(long ticketId, long memberId, String ticketName, Date startTime, Long ownerType, Long ownerId);
}
