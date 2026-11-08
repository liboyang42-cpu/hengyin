package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 「我的加座」只读投影：一次 JOIN 取齐加座单、原单号与礼品领取状态。
 *
 * <p>为什么不直接返回 {@code MarketingProductOrder}：列表要显示原单号与领取状态，
 * 逐行回查会变成 N+1；而给订单域加字段属于别人 owner 的写权限。
 */
public class MarketingExtraSeatOrderRow {

    private String orderNo;
    private Integer status;
    private Long productId;
    private Long productRevision;
    private Integer fulfillmentKind;
    private Integer fulfillmentState;
    private Integer payMode;
    private BigDecimal totalCashAmount;
    private Integer totalPointsAmount;
    private Integer paymentFactType;
    private Long primaryRegistrationId;
    private Long recipientMemberId;
    private Date paidAt;
    private Date refundedAt;
    private Date createTime;
    /** 被加座的原营销单号；原单被删/缺失时为 null。 */
    private String originalOrderNo;
    private Long giftId;
    private String giftPublicId;
    private Integer giftStatus;
    private Long giftRecipientMemberId;
    private Long giftClaimedMemberId;
    private Long giftClaimedRegistrationId;
    private Date giftClaimExpiresAt;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Long getProductRevision() { return productRevision; }
    public void setProductRevision(Long productRevision) { this.productRevision = productRevision; }

    public Integer getFulfillmentKind() { return fulfillmentKind; }
    public void setFulfillmentKind(Integer fulfillmentKind) { this.fulfillmentKind = fulfillmentKind; }

    public Integer getFulfillmentState() { return fulfillmentState; }
    public void setFulfillmentState(Integer fulfillmentState) { this.fulfillmentState = fulfillmentState; }

    public Integer getPayMode() { return payMode; }
    public void setPayMode(Integer payMode) { this.payMode = payMode; }

    public BigDecimal getTotalCashAmount() { return totalCashAmount; }
    public void setTotalCashAmount(BigDecimal totalCashAmount) { this.totalCashAmount = totalCashAmount; }

    public Integer getTotalPointsAmount() { return totalPointsAmount; }
    public void setTotalPointsAmount(Integer totalPointsAmount) { this.totalPointsAmount = totalPointsAmount; }

    public Integer getPaymentFactType() { return paymentFactType; }
    public void setPaymentFactType(Integer paymentFactType) { this.paymentFactType = paymentFactType; }

    public Long getPrimaryRegistrationId() { return primaryRegistrationId; }
    public void setPrimaryRegistrationId(Long primaryRegistrationId) { this.primaryRegistrationId = primaryRegistrationId; }

    public Long getRecipientMemberId() { return recipientMemberId; }
    public void setRecipientMemberId(Long recipientMemberId) { this.recipientMemberId = recipientMemberId; }

    public Date getPaidAt() { return paidAt; }
    public void setPaidAt(Date paidAt) { this.paidAt = paidAt; }

    public Date getRefundedAt() { return refundedAt; }
    public void setRefundedAt(Date refundedAt) { this.refundedAt = refundedAt; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public String getOriginalOrderNo() { return originalOrderNo; }
    public void setOriginalOrderNo(String originalOrderNo) { this.originalOrderNo = originalOrderNo; }

    public Long getGiftId() { return giftId; }
    public void setGiftId(Long giftId) { this.giftId = giftId; }

    public String getGiftPublicId() { return giftPublicId; }
    public void setGiftPublicId(String giftPublicId) { this.giftPublicId = giftPublicId; }

    public Integer getGiftStatus() { return giftStatus; }
    public void setGiftStatus(Integer giftStatus) { this.giftStatus = giftStatus; }

    public Long getGiftRecipientMemberId() { return giftRecipientMemberId; }
    public void setGiftRecipientMemberId(Long giftRecipientMemberId) { this.giftRecipientMemberId = giftRecipientMemberId; }

    public Long getGiftClaimedMemberId() { return giftClaimedMemberId; }
    public void setGiftClaimedMemberId(Long giftClaimedMemberId) { this.giftClaimedMemberId = giftClaimedMemberId; }

    public Long getGiftClaimedRegistrationId() { return giftClaimedRegistrationId; }
    public void setGiftClaimedRegistrationId(Long giftClaimedRegistrationId) { this.giftClaimedRegistrationId = giftClaimedRegistrationId; }

    public Date getGiftClaimExpiresAt() { return giftClaimExpiresAt; }
    public void setGiftClaimExpiresAt(Date giftClaimExpiresAt) { this.giftClaimExpiresAt = giftClaimExpiresAt; }
}
