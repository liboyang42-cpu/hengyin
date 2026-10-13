package com.chengyinhub.business.notify;

/**
 * 业务通知事件。业务侧只声明「发生了什么」,不声明「用什么通道发」。
 *
 * 枚举值与 {@link com.chengyinhub.business.service.INotifyService} 的语义方法一一对应。
 * 新增事件时:加枚举 → 加 INotifyService 方法 → 各通道按需处理(不处理即不发,不是错误)。
 */
public enum NotifyEvent {
    /** 退款到账 */
    REFUND,
    /** 报名成功 */
    SIGNUP_SUCCESS,
    /** 出发前提醒 */
    BEFORE_START,
    /** 合作被邀约 */
    COOP_INVITED,
    /** 邀约状态变更 */
    COOP_STATUS,
    /** 俱乐部承接意向 */
    CLUB_INTERESTED,
    /** 新活动招募状态变更 */
    RECRUIT,
    /** 订单结算到账 */
    COOP_SETTLE,
    /** 官方活动/公告 */
    OFFICIAL_BROADCAST,
    /** 漫游完成召回 */
    ROAM_RECALL,
    /** 玩家队伍状态变化；仅站内信通道处理，不占用订阅消息模板。 */
    TEAM_STATUS,
    /** 售后商家超时自动转平台客服；仅站内信通道处理，不占用订阅消息模板。 */
    AFTERCARE_PLATFORM_TAKEOVER,
}
