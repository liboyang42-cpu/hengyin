package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 来自真实报名关系的商家客户摘要；不持久化消费者 PII 快照。 */
public class MerchantCrmCustomerSummary {
    private Long customerMemberId;
    private String displayName;
    private String avatar;
    private Integer arrivedCount;
    private Integer pendingCount;
    private Integer refundedCount;
    private BigDecimal paidAmount;
    private Date lastInteractionTime;

    public Long getCustomerMemberId() { return customerMemberId; }
    public void setCustomerMemberId(Long value) { customerMemberId = value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String value) { avatar = value; }
    public Integer getArrivedCount() { return arrivedCount; }
    public void setArrivedCount(Integer value) { arrivedCount = value; }
    public Integer getPendingCount() { return pendingCount; }
    public void setPendingCount(Integer value) { pendingCount = value; }
    public Integer getRefundedCount() { return refundedCount; }
    public void setRefundedCount(Integer value) { refundedCount = value; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal value) { paidAmount = value; }
    public Date getLastInteractionTime() { return lastInteractionTime; }
    public void setLastInteractionTime(Date value) { lastInteractionTime = value; }
}
