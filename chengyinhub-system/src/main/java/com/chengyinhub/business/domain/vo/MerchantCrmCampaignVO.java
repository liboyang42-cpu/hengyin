package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 商家可见触达汇总与逐人脱敏回执。 */
public class MerchantCrmCampaignVO {
    private Long id;
    private Long segmentId;
    private String channel;
    private Long couponId;
    private String title;
    private String status;
    private Integer recipientCount;
    private Integer deliveredCount;
    private Integer noConsentCount;
    private Integer frequencySkippedCount;
    private Integer failedCount;
    private Integer retryableCount;
    private Date createTime;
    private List<RecipientReceipt> recipients = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getSegmentId() { return segmentId; }
    public void setSegmentId(Long value) { segmentId = value; }
    public String getChannel() { return channel; }
    public void setChannel(String value) { channel = value; }
    public Long getCouponId() { return couponId; }
    public void setCouponId(Long value) { couponId = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getRecipientCount() { return recipientCount; }
    public void setRecipientCount(Integer value) { recipientCount = value; }
    public Integer getDeliveredCount() { return deliveredCount; }
    public void setDeliveredCount(Integer value) { deliveredCount = value; }
    public Integer getNoConsentCount() { return noConsentCount; }
    public void setNoConsentCount(Integer value) { noConsentCount = value; }
    public Integer getFrequencySkippedCount() { return frequencySkippedCount; }
    public void setFrequencySkippedCount(Integer value) { frequencySkippedCount = value; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer value) { failedCount = value; }
    public Integer getRetryableCount() { return retryableCount; }
    public void setRetryableCount(Integer value) { retryableCount = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }
    public List<RecipientReceipt> getRecipients() { return recipients; }
    public void setRecipients(List<RecipientReceipt> value) { recipients = value; }

    public static class RecipientReceipt {
        private Long recipientId;
        private String customerName;
        private String status;
        private String failureCode;
        private String failureMessage;
        private Long messageReceiptId;
        private Long couponHistoryId;
        private Date deliveredAt;

        public Long getRecipientId() { return recipientId; }
        public void setRecipientId(Long value) { recipientId = value; }
        public String getCustomerName() { return customerName; }
        public void setCustomerName(String value) { customerName = value; }
        public String getStatus() { return status; }
        public void setStatus(String value) { status = value; }
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
    }
}
