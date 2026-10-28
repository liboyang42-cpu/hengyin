package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.OfficialBroadcast;

/**
 * 官方通知/广播 服务:按角色×城市圈人 → 站内信+订阅消息 fan-out(uk 防重发)。
 *
 * @author cy
 * @date 2026-07-08
 */
public interface IOfficialBroadcastService
{
    /**
     * 发布通知；若绑定 V2 活动，必须传入当前会员并由服务校验本场受控操作者身份。
     * V1 或纯公告可传空身份，仍由各自入口的权限控制约束。
     */
    OfficialBroadcast publish(OfficialBroadcast bc, Long actorMemberId);

    /** 仅发布者本人读取自己通知；V2 行还会逐条复核本场受控操作者范围。 */
    List<OfficialBroadcast> listBySender(Long senderAccount, Long actorMemberId);

    java.util.Map<String,Object> cancelCreation(String owner,String key);

    java.util.Map<String,Object> creationReceipt(String owner,String key);

    java.util.Map<String,Object> recover(Long id, Long actorMemberId);
    java.util.Map<String,Object> recoverOwned(Long id, Long actorMemberId, String owner);

    /** Quartz:发送所有到点的定时广播。 */
    void runDueScheduled();

    /** 点击回流(复盘用)。 */
    void markClicked(Long broadcastId, Long memberId, String channel);

    /** 复盘:某广播的触达数/点击数；V2 活动须校验受控操作者。 */
    java.util.Map<String, Object> reachStats(Long broadcastId, Long actorMemberId);

    /** 按 id 取通知；V2 活动须校验受控操作者。 */
    OfficialBroadcast getById(Long id, Long actorMemberId);
}
