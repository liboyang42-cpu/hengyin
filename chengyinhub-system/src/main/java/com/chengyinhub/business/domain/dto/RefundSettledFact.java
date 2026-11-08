package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 「退款已真实到账」的领域事实(不可变)。
 *
 * <p>只在 {@code RefundServiceImpl.applyRefundPayoutEvent} 的 SUCCESS 分支构造 ——
 * 那一刻已经过不可变 REFUND 支付事实校验(outRefundNo / provider refundId / transactionId /
 * outTradeNo / 金额全等),并由 {@code updatePayoutTerminal} CAS 置为 PAYOUT_SUCCESS。
 *
 * <p><b>严禁</b>在退款申请、审批、待人工、ABNORMAL/CLOSED/PROCESSING 时构造本事实:
 * 那些状态都不代表钱回到了顾客账上。
 *
 * <p>金额用「分」给,避免 {@link BigDecimal} 标度在跨域传递时漂移;需要元的消费方自己
 * {@code movePointLeft(2)} 并保留原标度即可。
 */
public final class RefundSettledFact {

    private final Long registrationId;
    private final Long refundApplicationId;
    private final String outRefundNo;
    private final String providerRefundId;
    private final long refundedFen;
    private final long totalFen;
    private final String currency;
    private final long verifiedRefundFactId;
    private final String verifiedRefundFactHash;

    public RefundSettledFact(Long registrationId, Long refundApplicationId, String outRefundNo,
                             String providerRefundId, long refundedFen, long totalFen, String currency,
                             long verifiedRefundFactId, String verifiedRefundFactHash) {
        this.registrationId = registrationId;
        this.refundApplicationId = refundApplicationId;
        this.outRefundNo = outRefundNo;
        this.providerRefundId = providerRefundId;
        this.refundedFen = refundedFen;
        this.totalFen = totalFen;
        this.currency = currency;
        this.verifiedRefundFactId = verifiedRefundFactId;
        this.verifiedRefundFactHash = verifiedRefundFactHash;
    }

    /** 被退款的报名单(cms_registration.id)。 */
    public Long registrationId() { return registrationId; }
    /** 退款申请单(refund_application.id)。 */
    public Long refundApplicationId() { return refundApplicationId; }
    /** 我方退款单号。 */
    public String outRefundNo() { return outRefundNo; }
    /** 渠道退款单号。 */
    public String providerRefundId() { return providerRefundId; }
    /** 本次实退金额(分)。 */
    public long refundedFen() { return refundedFen; }
    /** 原支付总额(分)。 */
    public long totalFen() { return totalFen; }
    public String currency() { return currency; }
    /** 不可变退款事实的 id/hash,消费方可用它做幂等键或再校验。 */
    public long verifiedRefundFactId() { return verifiedRefundFactId; }
    public String verifiedRefundFactHash() { return verifiedRefundFactHash; }

    public BigDecimal refundedAmount() { return BigDecimal.valueOf(refundedFen, 2); }
    public BigDecimal totalAmount() { return BigDecimal.valueOf(totalFen, 2); }

    /**
     * 消费方幂等键建议。同一 registration + 退款申请 只会产生一个终态事实;
     * 回调重投由 inbox 去重,重放到这里时消费方应据此读回既有分录而不是再写一次。
     */
    public String idempotencyKey() {
        return "refund-settled:" + registrationId + ":" + refundApplicationId;
    }
}
