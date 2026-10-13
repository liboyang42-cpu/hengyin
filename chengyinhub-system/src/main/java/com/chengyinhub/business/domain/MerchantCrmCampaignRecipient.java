package com.chengyinhub.business.domain;

import java.util.Date;

/** 不含手机号的服务端冻结收件人及真实投递回执。 */
public class MerchantCrmCampaignRecipient {
    public static final String PENDING = "PENDING";
    public static final String DELIVERED = "DELIVERED";
    public static final String SKIPPED_NO_CONSENT = "SKIPPED_NO_CONSENT";
    public static final String SKIPPED_FREQUENCY = "SKIPPED_FREQUENCY";
    public static final String RETRYABLE_BUSY = "RETRYABLE_BUSY";
    public static final String FAILED = "FAILED";

    private Long id;
    private Long campaignId;
    private Long merchantId;
    private Long memberId;
    private String customerNameSnapshot;
    private String channel;
    private Long consentEventIdSnapshot;
    private String status;
    private Integer attemptCount;
    private String failureCode;
    private String failureMessage;
    private Long messageReceiptId;
    private Long couponHistoryId;
    private Date deliveredAt;
    private Date lastAttemptAt;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long value) { campaignId = value; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public String getCustomerNameSnapshot() { return customerNameSnapshot; }
    public void setCustomerNameSnapshot(String value) { customerNameSnapshot = value; }
    public String getChannel() { return channel; }
    public void setChannel(String value) { channel = value; }
    public Long getConsentEventIdSnapshot() { return consentEventIdSnapshot; }
    public void setConsentEventIdSnapshot(Long value) { consentEventIdSnapshot = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer value) { attemptCount = value; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String value) { failureCode = value; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String value) { failureMessage = value; }
    public Long getMessageReceiptId() { return messageReceiptId; }
    public void setMessageReceiptId(Long value) { messageReceiptId = value; }
    public Long getCouponHistoryId() { return couponHistoryId; }
    public void setCouponHistoryId(Long value) { couponHistoryId = value; }
    public Date getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Date value) { deliveredAt = value; }
    public Date getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Date value) { lastAttemptAt = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date value) { updateTime = value; }
}
