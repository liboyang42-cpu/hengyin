package com.chengyinhub.business.domain;

import java.util.Date;

/** 活动/主题发布流云端草稿。 */
public class ContentDraft {
    public static final String TYPE_ACTIVITY = "ACTIVITY";
    public static final String TYPE_TOPIC = "TOPIC";
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DELETED = "DELETED";

    private Long id;
    private Long ownerMemberId;
    private String businessType;
    private String clientDraftKey;
    private Long subjectId;
    private String payloadJson;
    private String payloadHash;
    private String status;
    private Long version;
    private String updatedByDevice;
    private Long publishedResourceId;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long ownerMemberId) { this.ownerMemberId = ownerMemberId; }
    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }
    public String getClientDraftKey() { return clientDraftKey; }
    public void setClientDraftKey(String clientDraftKey) { this.clientDraftKey = clientDraftKey; }
    public Long getSubjectId() { return subjectId; }
    public void setSubjectId(Long subjectId) { this.subjectId = subjectId; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getUpdatedByDevice() { return updatedByDevice; }
    public void setUpdatedByDevice(String updatedByDevice) { this.updatedByDevice = updatedByDevice; }
    public Long getPublishedResourceId() { return publishedResourceId; }
    public void setPublishedResourceId(Long publishedResourceId) { this.publishedResourceId = publishedResourceId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
