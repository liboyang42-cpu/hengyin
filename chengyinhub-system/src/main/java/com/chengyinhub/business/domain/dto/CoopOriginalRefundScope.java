package com.chengyinhub.business.domain.dto;

/** 仅用于定位原锁地址；锁后必须当前读复核，缺关系不等于独立活动。 */
public class CoopOriginalRefundScope {
    private Long refundId;
    private String sourceType;
    private Long sourceId;
    private Long registrationId;
    private Long ownerType;
    private Long ownerId;
    private Long topicId;
    private Boolean relationPresent;
    public Long getRefundId() { return refundId; }
    public void setRefundId(Long value) { refundId = value; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String value) { sourceType = value; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long value) { sourceId = value; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { registrationId = value; }
    public Long getOwnerType() { return ownerType; }
    public void setOwnerType(Long value) { ownerType = value; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long value) { ownerId = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public Boolean getRelationPresent() { return relationPresent; }
    public void setRelationPresent(Boolean value) { relationPresent = value; }
}
