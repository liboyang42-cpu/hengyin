package com.chengyinhub.business.domain.vo;

/**
 * 场次取消受理结果。退款状态表达“平台已受理/需人工”，不冒充微信已到账；
 * 通知状态表达 outbox 是否已排队，不冒充收件人已读。
 */
public class ClubEventCancellationResult {
    private Long activityId;
    private String occurrenceStatus;
    private String refundStatus;
    private Integer refundedOrders;
    private Integer manualOrders;
    private Integer closedPendingOrders;
    private Long registeredCampaignId;
    private Integer registeredRecipientCount;
    private String registeredNotificationStatus;
    private Long waitlistCampaignId;
    private Integer waitlistRecipientCount;
    private String waitlistNotificationStatus;

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public String getOccurrenceStatus() { return occurrenceStatus; }
    public void setOccurrenceStatus(String occurrenceStatus) { this.occurrenceStatus = occurrenceStatus; }
    public String getRefundStatus() { return refundStatus; }
    public void setRefundStatus(String refundStatus) { this.refundStatus = refundStatus; }
    public Integer getRefundedOrders() { return refundedOrders; }
    public void setRefundedOrders(Integer refundedOrders) { this.refundedOrders = refundedOrders; }
    public Integer getManualOrders() { return manualOrders; }
    public void setManualOrders(Integer manualOrders) { this.manualOrders = manualOrders; }
    public Integer getClosedPendingOrders() { return closedPendingOrders; }
    public void setClosedPendingOrders(Integer closedPendingOrders) { this.closedPendingOrders = closedPendingOrders; }
    public Long getRegisteredCampaignId() { return registeredCampaignId; }
    public void setRegisteredCampaignId(Long registeredCampaignId) { this.registeredCampaignId = registeredCampaignId; }
    public Integer getRegisteredRecipientCount() { return registeredRecipientCount; }
    public void setRegisteredRecipientCount(Integer registeredRecipientCount) { this.registeredRecipientCount = registeredRecipientCount; }
    public String getRegisteredNotificationStatus() { return registeredNotificationStatus; }
    public void setRegisteredNotificationStatus(String registeredNotificationStatus) { this.registeredNotificationStatus = registeredNotificationStatus; }
    public Long getWaitlistCampaignId() { return waitlistCampaignId; }
    public void setWaitlistCampaignId(Long waitlistCampaignId) { this.waitlistCampaignId = waitlistCampaignId; }
    public Integer getWaitlistRecipientCount() { return waitlistRecipientCount; }
    public void setWaitlistRecipientCount(Integer waitlistRecipientCount) { this.waitlistRecipientCount = waitlistRecipientCount; }
    public String getWaitlistNotificationStatus() { return waitlistNotificationStatus; }
    public void setWaitlistNotificationStatus(String waitlistNotificationStatus) { this.waitlistNotificationStatus = waitlistNotificationStatus; }
}
