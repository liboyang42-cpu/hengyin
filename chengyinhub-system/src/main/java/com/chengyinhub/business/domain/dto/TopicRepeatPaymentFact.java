package com.chengyinhub.business.domain.dto;

import java.util.Date;

public class TopicRepeatPaymentFact {
    private Long memberId;
    private Date paymentTime;
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Date getPaymentTime() { return paymentTime; }
    public void setPaymentTime(Date paymentTime) { this.paymentTime = paymentTime; }
}
