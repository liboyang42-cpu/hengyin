package com.chengyinhub.business.domain;

import java.util.Date;

/** 跨 campaign 的单商家、单客户、单渠道、单自然日投递互斥与真实回执。 */
public class MerchantCrmDeliveryGuard {
    public static final String RESERVED = "RESERVED";
    public static final String DISPATCHING = "DISPATCHING";
    public static final String DELIVERED = "DELIVERED";
    public static final String FAILED_UNCERTAIN = "FAILED_UNCERTAIN";

    private Long id;
    private Long merchantId;
    private Long memberId;
    private String channel;
    private Date deliveryDate;
    private Long campaignId;
    private Long recipientId;
    private String status;
    private String leaseToken;
    private Date leaseUntil;
    private Long version;
    private Long messageReceiptId;
    private Long couponHistoryId;
    private String failureCode;
    private Integer leaseSeconds;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public String getChannel() { return channel; }
    public void setChannel(String value) { channel = value; }
    public Date getDeliveryDate() { return deliveryDate; }
    public void setDeliveryDate(Date value) { deliveryDate = value; }
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long value) { campaignId = value; }
    public Long getRecipientId() { return recipientId; }
    public void setRecipientId(Long value) { recipientId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String value) { leaseToken = value; }
    public Date getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Date value) { leaseUntil = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
    public Long getMessageReceiptId() { return messageReceiptId; }
    public void setMessageReceiptId(Long value) { messageReceiptId = value; }
    public Long getCouponHistoryId() { return couponHistoryId; }
    public void setCouponHistoryId(Long value) { couponHistoryId = value; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String value) { failureCode = value; }
    public Integer getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(Integer value) { leaseSeconds = value; }
}
