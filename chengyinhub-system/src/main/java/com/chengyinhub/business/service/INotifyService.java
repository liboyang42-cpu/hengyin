package com.chengyinhub.business.service;

/**
 * 业务通知的统一入口(2026-08-20 新增)。
 *
 * <p><b>为什么有这一层</b>:此前 13 处业务点直接 @Autowired IWxSubscribeMsgService,
 * 把「发生了什么」和「用什么通道发」焊死了 —— 加 App push 要改 13 处,再加短信又要改 13 处。
 * 现在业务只调本接口,通道由 {@link com.chengyinhub.business.notify.NotifyChannel} 实现类提供,
 * 增删通道不碰业务代码。
 *
 * <p>既有微信事件的方法签名与 IWxSubscribeMsgService 一致；只走站内信的业务事件
 * （如玩家队伍状态）也从本语义入口进入，再由对应 NotifyChannel 选择性处理。
 *
 * <p>⚠️ <b>全部方法 best-effort:失败不抛异常；boolean 方法返回 false，新原意图方法返回待核实状态并留日志。</b>
 * 调用链上游多处带 @Transactional(rollbackFor=Exception.class),抛出会把已生效的业务
 * (典型如已被微信网关受理的退款)连带回滚 = 资损。想判成败的看返回值,不关心的忽略即可。
 *
 * <p>返回值语义:<b>至少一条通道真的交付成功</b>。全部通道失败/未配置 ⇒ false。
 */
public interface INotifyService {

    /** 退款到账提醒 */
    boolean notifyRefund(Long memberId, String topicName, String amount);

    /** 报名成功提醒 */
    boolean notifySignupSuccess(Long memberId, String topicName);

    /** Legacy semantic-channel adapter. Scheduled production uses the original-ticket overload below. */
    boolean notifyBeforeStart(Long memberId, String topicName, String whenText);

    /** Original ticket × recipient, immutable start time; UNKNOWN is read-only and never authorizes resend. */
    com.chengyinhub.business.domain.dto.BeforeStartNotifyResult.Delivery notifyBeforeStart(
            Long ticketId, Long memberId, String topicName, java.util.Date startsAt);


    /** 合作被邀约 → 通知受邀方 */
    boolean notifyCoopInvited(Long toMemberId, String topicName, String fromName);

    /** 俱乐部承接意向 → 通知模板主题创建者 */
    boolean notifyClubInterested(Long toMemberId, String topicName, String clubName);

    /** 邀约状态变更(接受/拒绝)→ 通知发起方 */
    boolean notifyCoopStatus(Long fromMemberId, String topicName, String toName, String statusText, String progress);

    /** 新活动招募状态变更 → 通知发布者 */
    boolean notifyRecruit(Long publisherId, String topicName, String activityTime, String tip);

    /** 订单结算到账 → 通知商家 */
    boolean notifyCoopSettle(Long merchantId, String amount, String whenText, String desc, String orderNo);

    /** 官方活动/公告 */
    boolean notifyOfficialBroadcast(Long memberId, String title, String sub);

    /** 漫游完成 24–72h 召回 */
    boolean notifyRoamRecall(Long memberId, int distanceM, int poisFound);

    /** 玩家队伍状态站内提醒；微信通道不处理该事件。 */
    boolean notifyTeamStatus(Long memberId, String teamTitle, String statusText);

    /** 售后商家超时自动转平台客服,通知玩家；仅站内信通道处理。amount 可为空串。 */
    boolean notifyAftercarePlatformTakeover(Long memberId, String amount);

    /**
     * 漫游召回是否已就绪。未就绪时调度不得消耗重试次数。
     * ⚠️ **当前实现只问微信通道**,与旧的 IWxSubscribeMsgService#isRoamRecallReady 等价。
     * 加 App push(P0-2)时必须回来改成「任一通道就绪」,否则微信模板没配会让
     * 已经能推送的召回被误判成不可发。在改之前,别在文档或注释里说它问了所有通道。
     */
    boolean isRoamRecallReady();
}
