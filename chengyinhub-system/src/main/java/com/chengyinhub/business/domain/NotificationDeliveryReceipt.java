package com.chengyinhub.business.domain;

/** 通知意图/渠道收据；唯一键为原事件、接收人和渠道。 */
public class NotificationDeliveryReceipt {
    private Long id;
    private String eventType;
    private Long eventId;
    private Long recipientId;
    private String channel;
    private String status;
    private String providerCode;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public Long getRecipientId() { return recipientId; }
    public void setRecipientId(Long recipientId) { this.recipientId = recipientId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
}
