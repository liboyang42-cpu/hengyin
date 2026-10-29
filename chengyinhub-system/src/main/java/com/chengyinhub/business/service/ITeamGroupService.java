package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.domain.dto.BeforeStartNotifyResult;

/**
 * 报名退款服务。
 *
 * 注:类名沿用 TeamGroup 是历史包袱 —— 成团(自动散团引擎)已于 2026-07-15 下线,
 * 本服务现仅承担「已支付报名的取消退款」与「出发前提醒批扫」。改名需牵动 controller 与测试,
 * 收益不抵风险,留作后续清理。
 */
public interface ITeamGroupService {

    /** cancelPaidRegistration / ByOwner 返回值:已退款(或已受理退款)。 */
    int CANCEL_REFUNDED = 1;
    /** FX-02:同单部分核销,未自动退,已冻结人工退款工单(转人工)。 */
    int CANCEL_MANUAL_REVIEW = 2;

    /**
     * 玩家取消【已支付】报名并退款。
     * 校验本人 + 已支付未核销 + 退款时间窗(RefundPolicy)。
     * @return CANCEL_REFUNDED 已退;CANCEL_MANUAL_REVIEW 同单部分核销已转人工;失败抛 RuntimeException(msg 给前端)
     */
    int cancelPaidRegistration(Long registrationId, Long operatorMemberId);

    /**
     * 主题主理人(owner)取消某条【已支付】报名并退款。
     * 校验:报名归属主题(ownerType=1)且 topic.member_id == ownerMemberId;已支付未核销。
     * 与玩家自退区别:按主题归属授权(非报名者本人),用于主理人清退/调整名单。
     * @return 同 cancelPaidRegistration
     */
    int cancelPaidRegistrationByOwner(Long registrationId, Long ownerMemberId);

    /**
     * 主办方取消活动:对该活动下所有【已付款且未核销】的报名整单全额退款(0 手续费),
     * 并把活动置为已取消 + 下架。
     *
     * 0 手续费全额退是跨平台无分歧的口径 —— 主办方违约却扣用户钱,法理站不住。
     * 故本路径【刻意绕开 RefundPolicy 的时间窗】:那是买家【主动】退票的规则,主办方取消不适用。
     * 已核销的报名跳过(服务已交付)。
     *
     * @param reason 取消原因,展示给已报名用户
     * @return 退款结果:自动退款单数 + 含已核销票需人工处置的单数(多票同父订单均算一单)
     */
    ActivityCancelResult cancelActivityByOwner(Long activityId, Long ownerMemberId, String reason);

    /**
     * 俱乐部 owner/co-owner 经俱乐部权限层授权后取消真实活动场次。
     * ownerMemberId 是 CmsActivity 归属真源，actorMemberId 是实际操作人，二者不得混写。
     */
    ActivityCancelResult cancelActivityByClubOperator(Long activityId, Long ownerMemberId,
                                                      Long actorMemberId, String reason);

    /**
     * [FIX-WEATHER / C8-05] 商家主办方(店主或有 PROJECT_MANAGE 权限的员工)取消自己主办的场次并全额退款。
     * 退款与收尾与 {@link #cancelActivityByOwner} 同一个核心;承办别人主题的场次拒绝(403)。
     *
     * @param merchantOwnerMemberId 商家店主 member_id(场次归属真源);actorMemberId 为实际操作人
     */
    ActivityCancelResult cancelActivityByMerchantHost(Long activityId, Long merchantOwnerMemberId,
                                                      Long actorMemberId, String reason);

    /**
     * [FIX-WEATHER / C8-05] 主题主办方取消整个主题:停售 + 主题级已付款票全额退 + 逐场取消该主题下的场次。
     * 已核销口径同场次取消(整单全核销不退;部分核销的主题多票单整次拒绝 —— 人工工单表只认场次,见实现注释)。
     * 俱乐部名下的场次走俱乐部收尾。俱乐部主题本身不走这里。
     */
    ActivityCancelResult cancelTopicByHost(Long topicId, Long hostMemberId, Long actorMemberId, String reason);
    ActivityCancelResult cancelTopicByHost(Long topicId,Long hostMemberId,Long actorMemberId,String reason,Long cancellationEpoch);
    /** Read-only original cancellation cursor, with the same current host authorization as preview. */
    Long topicCancellationEpoch(Long topicId,Long hostMemberId);

    /** 取消前确认框用:该场次「已付款且未核销」的玩家人数(按人去重)。非归属人 403。 */
    int countPaidPlayersForActivityCancel(Long activityId, Long hostMemberId);

    /** 商家 scope 的取消预览:与 {@link #cancelActivityByMerchantHost} 同一道「必须是主办」授权。 */
    int countPaidPlayersForMerchantActivityCancel(Long activityId, Long merchantOwnerMemberId);

    /** 取消前确认框用:主题级票 + 该主题下未取消场次里「已付款且未核销」的玩家人数(按人去重)。 */
    int countPaidPlayersForTopicCancel(Long topicId, Long hostMemberId);

    /** Caller has authorized and locked the original scope in its short preparation transaction. */
    com.chengyinhub.business.domain.dto.RegistrationCancellationPayments captureCancellationPayments(
            Long ownerType, Long ownerId);

    /** No active transaction: observe/close captured original attempts, with no local business mutation. */
    com.chengyinhub.business.domain.dto.RegistrationCancellationPayments observeCancellationPayments(
            com.chengyinhub.business.domain.dto.RegistrationCancellationPayments captured);

    /** Join the original full cancellation transaction after external facts have committed. */
    ActivityCancelResult cancelActivityWithObservedPayments(Long activityId, Long ownerMemberId,
            Long actorMemberId, String reason, boolean refuseSettled,
            com.chengyinhub.business.domain.dto.RegistrationCancellationPayments observed);

    /** 出发前提醒批扫:24h 内出发的经典定向票种,给已支付成员发订阅提醒(redis 去重)。供定时任务调用。 */
    BeforeStartNotifyResult notifyBeforeStartBatch();
}
