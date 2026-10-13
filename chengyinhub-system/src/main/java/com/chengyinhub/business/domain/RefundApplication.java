package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.util.Date;

/**
 * P3 退款申请 refund_application。
 */
public class RefundApplication extends BaseEntity {
    private static final long serialVersionUID = 1L;

    // 打款结果(payout_status):与审核 status 解耦——status 管"是否批准退款",payout 管"钱是否真退到用户"
    public static final int PAYOUT_PROCESSING = 0;      // 原路已受理,处理中/成功待确认(P3 回调确认)
    public static final int PAYOUT_SUCCESS = 1;         // 原路确认到账
    public static final int PAYOUT_PENDING_MANUAL = 2;  // 原路失败,待人工处理(不再自动转余额)
    public static final int PAYOUT_MANUAL_HANDLED = 3;  // 人工已退,待第二人复核
    public static final int PAYOUT_MANUAL_VERIFIED = 4; // 双人复核完成
    /** 本地退款事务已提交、尚未派发微信请求;提交后回调/定时任务会用确定性退款号重试。 */
    public static final int PAYOUT_DISPATCH_PENDING = 5;
    /** 已由一个 worker 原子领取派发权；超时后对账任务允许重新领取。 */
    public static final int PAYOUT_DISPATCHING = 6;

    private Long id;
    private String refundNo;
    private String sourceType;
    private Long sourceId;
    private Long ownerType;
    private Long ownerId;
    private Long memberId;
    private Long merchantId;
    private BigDecimal paidAmount;
    private BigDecimal refundAmount;
    private Integer status;
    private String reason;
    private String rejectReason;
    private Long reviewerId;
    private Date reviewedAt;
    // A1-1(P0):后台建单人 sys_user_id。maker≠checker 用——建此单的后台账号不得自审自批。
    // 仅后台 HTTP 建单(RefundController.add)写入;C端/系统发起的退款为 NULL,不受双人闸约束。
    private Long createdByUserId;
    private Integer adjustmentCreated;
    private String idempotencyKey;
    private Integer delFlag;
    /** 商家售后超时自动转平台客服的处理时间;为空 = 还没转过(幂等判据)。 */
    private Date platformTakeoverAt;

    /** 退款台查询参数(只读筛选,不落库):1=只看商家超时已转平台,0=只看未转,null=全部。 */
    private Integer platformTakeover;
    /** 退款台查询参数(只读排序,不落库):1=按转平台时间倒序(未转的排最后)。 */
    private Integer takeoverOrder;

    // §6.1 退款失败人工兜底
    private Integer payoutStatus;
    private String payoutMethod;
    private String payoutReceiptUrl;
    private Long payoutHandlerId;
    private Date payoutHandledAt;
    private Long payoutReviewer2Id;
    private Date payoutReviewed2At;
    private String payoutRemark;
    /** 派发租约 fencing token；超时重领后旧 worker 的迟到写必须因 token 不匹配失败。 */
    private String payoutLeaseToken;
    private Long payoutLeaseOwner;
    private Date payoutLeaseExpiresAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getOwnerType() { return ownerType; }
    public void setOwnerType(Long ownerType) { this.ownerType = ownerType; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }
    public Date getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Date reviewedAt) { this.reviewedAt = reviewedAt; }
    public Integer getAdjustmentCreated() { return adjustmentCreated; }
    public void setAdjustmentCreated(Integer adjustmentCreated) { this.adjustmentCreated = adjustmentCreated; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public Date getPlatformTakeoverAt() { return platformTakeoverAt; }
    public void setPlatformTakeoverAt(Date platformTakeoverAt) { this.platformTakeoverAt = platformTakeoverAt; }
    public Integer getPlatformTakeover() { return platformTakeover; }
    public void setPlatformTakeover(Integer platformTakeover) { this.platformTakeover = platformTakeover; }
    public Integer getTakeoverOrder() { return takeoverOrder; }
    public void setTakeoverOrder(Integer takeoverOrder) { this.takeoverOrder = takeoverOrder; }
    public Integer getPayoutStatus() { return payoutStatus; }
    public void setPayoutStatus(Integer payoutStatus) { this.payoutStatus = payoutStatus; }
    public String getPayoutMethod() { return payoutMethod; }
    public void setPayoutMethod(String payoutMethod) { this.payoutMethod = payoutMethod; }
    public String getPayoutReceiptUrl() { return payoutReceiptUrl; }
    public void setPayoutReceiptUrl(String payoutReceiptUrl) { this.payoutReceiptUrl = payoutReceiptUrl; }
    public Long getPayoutHandlerId() { return payoutHandlerId; }
    public void setPayoutHandlerId(Long payoutHandlerId) { this.payoutHandlerId = payoutHandlerId; }
    public Date getPayoutHandledAt() { return payoutHandledAt; }
    public void setPayoutHandledAt(Date payoutHandledAt) { this.payoutHandledAt = payoutHandledAt; }
    public Long getPayoutReviewer2Id() { return payoutReviewer2Id; }
    public void setPayoutReviewer2Id(Long payoutReviewer2Id) { this.payoutReviewer2Id = payoutReviewer2Id; }
    public Date getPayoutReviewed2At() { return payoutReviewed2At; }
    public void setPayoutReviewed2At(Date payoutReviewed2At) { this.payoutReviewed2At = payoutReviewed2At; }
    public String getPayoutRemark() { return payoutRemark; }
    public void setPayoutRemark(String payoutRemark) { this.payoutRemark = payoutRemark; }
    public String getPayoutLeaseToken() { return payoutLeaseToken; }
    public void setPayoutLeaseToken(String payoutLeaseToken) { this.payoutLeaseToken = payoutLeaseToken; }
    public Long getPayoutLeaseOwner() { return payoutLeaseOwner; }
    public void setPayoutLeaseOwner(Long payoutLeaseOwner) { this.payoutLeaseOwner = payoutLeaseOwner; }
    public Date getPayoutLeaseExpiresAt() { return payoutLeaseExpiresAt; }
    public void setPayoutLeaseExpiresAt(Date payoutLeaseExpiresAt) { this.payoutLeaseExpiresAt = payoutLeaseExpiresAt; }
}
