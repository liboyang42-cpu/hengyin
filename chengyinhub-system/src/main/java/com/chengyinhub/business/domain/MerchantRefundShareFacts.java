package com.chengyinhub.business.domain;

import java.math.BigDecimal;

/**
 * 第十二轮 k6(j1 N4 / p2 N-3):商家自有活动的一笔已同意退款,算「这单商家还剩多少」所需的只读事实。
 * 只在退款单已按服务端 owner 绑定、报名属于该 owner 自己的活动时才查得到。
 */
public class MerchantRefundShareFacts {
    private Long registrationId;
    private Long activityId;
    private Long topicId;
    private BigDecimal payableAmount;
    private BigDecimal refundedAmount;
    private Integer verificationStatus;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public BigDecimal getPayableAmount() { return payableAmount; }
    public void setPayableAmount(BigDecimal payableAmount) { this.payableAmount = payableAmount; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }
    public Integer getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(Integer verificationStatus) { this.verificationStatus = verificationStatus; }
}
