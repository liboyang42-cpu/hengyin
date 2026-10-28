package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;

import java.util.List;
import java.util.Map;

public interface IRefundService {
    List<RefundApplication> selectRefundList(RefundApplication query);

    /**
     * 内部当前读：调用方必须在同一事务中先锁原报名（父单按一致次序锁全组），再锁退款历史。
     * 本方法不自行建立完整事务/归属边界，不应直接暴露为客户端查询接口。
     * status=0/1 是待审/批准事实；payout 失败不撤销批准。发票消费者据此重新核验自身状态，
     * 不得因发票占用阻止退款，亦不得把退款驳回或空历史当作自动恢复发票的授权。
     */
    List<RefundApplication> selectRegistrationHistoryForUpdate(Long registrationId);

    /**
     * 系统全额退款（主办方取消/玩家取消）取代该报名尚待审核的退款申请：仅 status=0 的行按 CAS 置为驳回并留痕，
     * 未打款、不改金额。返回被取代的申请数。
     */
    int supersedePendingRegistrationRefunds(Long registrationId, String reason);

    int saveRefund(RefundApplication refund);

    /** 后台运营建单(RefundController.add):多票订单首单只能退同单还没退、也没核销的部分(FX-04)。 */
    int saveBackOfficeRefund(RefundApplication refund);

    int reviewRefund(RefundReviewDTO request, Long operatorId);

    // ===== §6.1 退款失败人工兜底 =====

    RefundApplication getRefundById(Long id);

    /** 打款结果:原路受理成功→PROCESSING(0);受理失败→PENDING_MANUAL(2,不再自动转平台余额)。 */
    void markPayoutResult(Long refundId, String leaseToken, boolean wxAccepted, String remark);

    /** 人工退款待办队列(payout_status in 2待人工/3待复核)。 */
    List<RefundApplication> selectManualPayoutQueue();

    /** 钱移动前领取人工处理权；返回 fencing token，null 表示已被他人持有。 */
    String claimManualPayout(Long refundId, Long handlerId);

    /** 放弃人工处理权，只允许当前 owner 按 fencing token 释放；过期也能释放（释放不动钱）。 */
    boolean releaseManualPayoutClaim(Long refundId, Long handlerId, String leaseToken);

    /**
     * 接管他人**已过期**的人工处理权；返回新 fencing token，null 表示不满足接管条件
     * （租约未过期 / 无人持有 / 就是自己）。接管会把前一个 owner 写进备注留痕。
     */
    String takeoverExpiredManualPayout(Long refundId, Long handlerId);

    /**
     * 人工处理:登记退款方式+回执 → 待复核(2→3)。返回受影响行数(0=状态已变/非待人工)。
     * receiptUrl 非空时必须过安全合同(HTTPS+白名单 host 或单斜杠站内路径),不合规抛
     * IllegalArgumentException 且一行不落库 —— 它会被另一名财务在队列里直接点开。
     */
    int handleManualPayout(Long refundId, String method, String receiptUrl, String remark,
                           Long handlerId, String leaseToken);

    /** 双人复核确认:3→4,复核人须≠处理人;完成后通知用户。返回受影响行数(0=状态已变)。 */
    int verifyManualPayout(Long refundId, Long reviewer2Id);

    /**
     * 退款结果通知回调:按 out_refund_no(=refund_no)反查退款单,回写打款终态。
     * SUCCESS→原路成功(1);ABNORMAL/CLOSED→待人工(2);PROCESSING→保持。返回 true=已受理(可回 ACK)。
     */
    boolean handleRefundNotify(Map<String, String> facts);
}
