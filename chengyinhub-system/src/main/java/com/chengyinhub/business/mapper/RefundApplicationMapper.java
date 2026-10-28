package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RefundApplication;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface RefundApplicationMapper {
    List<RefundApplication> selectRefundApplicationList(RefundApplication query);

    List<RefundApplication> selectRegistrationHistoryForUpdate(@Param("registrationId") Long registrationId);

    RefundApplication selectRefundApplicationById(@Param("id") Long id);

    int insertRefundApplication(RefundApplication refund);

    int updateRefundStatusIfPending(@Param("id") Long id,
                                    @Param("status") Integer status,
                                    @Param("reviewerId") Long reviewerId,
                                    @Param("reviewedAt") Date reviewedAt,
                                    @Param("rejectReason") String rejectReason);

    // §6.1 退款失败人工兜底
    int updatePayoutResult(@Param("id") Long id,
                           @Param("payoutStatus") Integer payoutStatus,
                           @Param("payoutRemark") String payoutRemark);

    /**
     * 该期<b>尚未落定</b>的退款单数：待审(status=0)，或已批准但钱还没确认退到用户
     * (status=1 且 payout_status 不在终态 1/4)。
     *
     * <p>探店日票走活动壳(owner_type=2 → cms_activity.topic_id 归期)，旧主题票 owner_type=1 直接是
     * topic_id，两条腿都查。&gt;0 即「退款还没了结」⇒ FINAL 拒绝(审计 2 暂结与终结动作合同)。
     */
    int countUnsettledRefundsByTopic(@Param("topicId") Long topicId);

    /** 从待派发/遗留空态领取唯一微信派发权；仅允许超时 worker 重新领取 DISPATCHING。 */
    int claimPayoutDispatch(@Param("id") Long id,
                            @Param("staleMinutes") long staleMinutes,
                            @Param("leaseToken") String leaseToken);

    /** 微信调用结果不确定时释放为待派发，由确定性退款号安全重试。 */
    int releasePayoutDispatch(@Param("id") Long id,
                              @Param("leaseToken") String leaseToken,
                              @Param("payoutRemark") String payoutRemark);

    /** worker 同步结果必须携带本次租约 token；微信回调仍走权威 updatePayoutResult。 */
    int updatePayoutResultWithLease(@Param("id") Long id,
                                    @Param("leaseToken") String leaseToken,
                                    @Param("payoutStatus") Integer payoutStatus,
                                    @Param("payoutRemark") String payoutRemark);

    /** 修复历史空 refund_no，并保证渠道号与本地号同源。 */
    int ensureRefundNo(@Param("id") Long id, @Param("refundNo") String refundNo);

    int claimManualPayout(@Param("id") Long id,
                          @Param("handlerId") Long handlerId,
                          @Param("leaseToken") String leaseToken,
                          @Param("leaseMinutes") long leaseMinutes);

    /** 放弃处理权：owner + token 双 fence，但不看是否过期——释放不动钱，过期反而更需要放回队列。 */
    int releaseManualPayoutClaim(@Param("id") Long id,
                                 @Param("handlerId") Long handlerId,
                                 @Param("leaseToken") String leaseToken);

    /** 跨人接管：只允许接管**已过期**且属于他人的租约，并把前一个 owner 写进备注留痕。 */
    int takeoverExpiredManualPayout(@Param("id") Long id,
                                    @Param("handlerId") Long handlerId,
                                    @Param("leaseToken") String leaseToken,
                                    @Param("leaseMinutes") long leaseMinutes,
                                    @Param("payoutRemark") String payoutRemark);

    int completeManualPayout(@Param("id") Long id,
                             @Param("payoutMethod") String payoutMethod,
                             @Param("payoutReceiptUrl") String payoutReceiptUrl,
                             @Param("handlerId") Long handlerId,
                             @Param("handledAt") Date handledAt,
                             @Param("payoutRemark") String payoutRemark,
                             @Param("leaseToken") String leaseToken);

    int markPayoutVerified(@Param("id") Long id,
                           @Param("reviewer2Id") Long reviewer2Id,
                           @Param("reviewed2At") Date reviewed2At);

    List<RefundApplication> selectManualPayoutQueue();

    RefundApplication selectByRefundNo(@Param("refundNo") String refundNo);

    /**
     * 受理后卡在 PROCESSING(0),或已审核但尚未派发微信的 DISPATCH_PENDING(5) 超 minutes 分钟的退款单。
     * 前者拉取微信终态,后者以确定性退款号重新派发。
     */
    List<RefundApplication> selectStalePayoutProcessing(@Param("minutes") int minutes);

    /** 本轮固定上界，避免扫描中新增退款使本轮无限延长。 */
    Long selectStalePayoutScanUpperBound(@Param("minutes") int minutes);

    List<RefundApplication> selectStalePayoutProcessingAfter(@Param("minutes") int minutes,
            @Param("afterId") long afterId, @Param("maxId") long maxId, @Param("limit") int limit);

    /** 待人工/待复核(payout_status in 2,3)积压数,供巡检告警。 */
    int countManualPayoutBacklog();

    /**
     * 卡在派发态(5 待派发 / 6 派发中)超过 minutes 分钟的退款单数。
     * 这批钱同样一分没退，却不在人工队列里 —— 只数 (2,3) 的积压告警对它们完全失明。
     */
    int countStuckPayoutDispatch(@Param("minutes") int minutes);

    /** 与 countManualPayoutBacklog 同谓词的前 20 个退款单 id，仅作告警定位样本。 */
    List<Long> sampleManualPayoutBacklogIds();

    /** 与 countStuckPayoutDispatch 同谓词的前 20 个退款单 id，仅作告警定位样本。 */
    List<Long> sampleStuckPayoutDispatchIds(@Param("minutes") int minutes);
}
