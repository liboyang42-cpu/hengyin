package com.chengyinhub.business.domain.dto;

import java.util.Date;

public class TopicOnsiteTicketFact {
    private Long registrationId;
    private Long memberId;
    private Integer paymentStatus;
    private Integer registrationStatus;
    private Date ticketStart;
    private Date lastSessionEnd;
    private Date refundRequestedAt;
    private Date refundTime;
    private boolean arrived;
    private int redeemedCount;
    private int requiredCount;
    private Date lastRedemptionAt;
    private boolean joinedWithinSevenDays;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Integer getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(Integer paymentStatus) { this.paymentStatus = paymentStatus; }
    public Integer getRegistrationStatus() { return registrationStatus; }
    public void setRegistrationStatus(Integer registrationStatus) { this.registrationStatus = registrationStatus; }
    public Date getTicketStart() { return ticketStart; }
    public void setTicketStart(Date ticketStart) { this.ticketStart = ticketStart; }
    public Date getLastSessionEnd() { return lastSessionEnd; }
    public void setLastSessionEnd(Date lastSessionEnd) { this.lastSessionEnd = lastSessionEnd; }
    public Date getRefundRequestedAt() { return refundRequestedAt; }
    public void setRefundRequestedAt(Date refundRequestedAt) { this.refundRequestedAt = refundRequestedAt; }
    public Date getRefundTime() { return refundTime; }
    public void setRefundTime(Date refundTime) { this.refundTime = refundTime; }
    public boolean isArrived() { return arrived; }
    public void setArrived(boolean arrived) { this.arrived = arrived; }
    public int getRedeemedCount() { return redeemedCount; }
    public void setRedeemedCount(int redeemedCount) { this.redeemedCount = redeemedCount; }
    public int getRequiredCount() { return requiredCount; }
    public void setRequiredCount(int requiredCount) { this.requiredCount = requiredCount; }
    public Date getLastRedemptionAt() { return lastRedemptionAt; }
    public void setLastRedemptionAt(Date lastRedemptionAt) { this.lastRedemptionAt = lastRedemptionAt; }
    public boolean isJoinedWithinSevenDays() { return joinedWithinSevenDays; }
    public void setJoinedWithinSevenDays(boolean joinedWithinSevenDays) { this.joinedWithinSevenDays = joinedWithinSevenDays; }
}
