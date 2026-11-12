package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 完局/复报报表的报名事实行（只读）。
 *
 * <p>来源表 {@code cms_registration} + {@code refund_application}；不含任何客户端自报字段。
 * 「有效报名」= {@code registration_status=2} 且 {@code payment_status=2} 且 {@code payment_time} 非空，
 * 由 SQL 收敛；服务层只分桶，不再二次解释口径。
 */
public class RegistrationCohortFact {
    private Long registrationId;
    private Long memberId;
    private String parentOrderNo;
    private Long ownerType;
    private Long ownerId;
    private Integer orderNum;
    private BigDecimal payableAmount;
    private BigDecimal totalAmount;
    private Integer paymentType;
    private Integer paymentStatus;
    private Integer registrationStatus;
    private Date paymentTime;
    private Date createTime;
    /** 已确认退到用户的钱：refund_application.status=1 且 payout_status in (1,4)。 */
    private BigDecimal settledRefundAmount;
    /** 尚未落定的退款单数：status=0，或 status=1 但 payout_status in (0,2,3,5,6)（钱未确认到账）。 */
    private Integer pendingRefundCount;
    /** 退款单覆盖的到账事实是否存在（用于识别「退款先到账、payment_status 后翻转」的顺序差）。 */
    private Boolean hasSettledRefund;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getParentOrderNo() { return parentOrderNo; }
    public void setParentOrderNo(String parentOrderNo) { this.parentOrderNo = parentOrderNo; }
    public Long getOwnerType() { return ownerType; }
    public void setOwnerType(Long ownerType) { this.ownerType = ownerType; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public Integer getOrderNum() { return orderNum; }
    public void setOrderNum(Integer orderNum) { this.orderNum = orderNum; }
    public BigDecimal getPayableAmount() { return payableAmount; }
    public void setPayableAmount(BigDecimal payableAmount) { this.payableAmount = payableAmount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public Integer getPaymentType() { return paymentType; }
    public void setPaymentType(Integer paymentType) { this.paymentType = paymentType; }
    public Integer getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(Integer paymentStatus) { this.paymentStatus = paymentStatus; }
    public Integer getRegistrationStatus() { return registrationStatus; }
    public void setRegistrationStatus(Integer registrationStatus) { this.registrationStatus = registrationStatus; }
    public Date getPaymentTime() { return paymentTime; }
    public void setPaymentTime(Date paymentTime) { this.paymentTime = paymentTime; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public BigDecimal getSettledRefundAmount() { return settledRefundAmount; }
    public void setSettledRefundAmount(BigDecimal settledRefundAmount) { this.settledRefundAmount = settledRefundAmount; }
    public Integer getPendingRefundCount() { return pendingRefundCount; }
    public void setPendingRefundCount(Integer pendingRefundCount) { this.pendingRefundCount = pendingRefundCount; }
    public Boolean getHasSettledRefund() { return hasSettledRefund; }
    public void setHasSettledRefund(Boolean hasSettledRefund) { this.hasSettledRefund = hasSettledRefund; }
}
