package com.chengyinhub.business.domain.dto;

/** 草稿保存、删除与发布命令；expectedVersion=0 只用于首次创建。 */
public class ContentDraftCommand {
    private Long id;
    private String businessType;
    private String clientDraftKey;
    private Long subjectId;
    private String payloadJson;
    private Long expectedVersion;
    private String deviceId;
    private Long publishedResourceId;
    private String scope;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }
    public String getClientDraftKey() { return clientDraftKey; }
    public void setClientDraftKey(String clientDraftKey) { this.clientDraftKey = clientDraftKey; }
    public Long getSubjectId() { return subjectId; }
    public void setSubjectId(Long subjectId) { this.subjectId = subjectId; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public Long getPublishedResourceId() { return publishedResourceId; }
    public void setPublishedResourceId(Long publishedResourceId) { this.publishedResourceId = publishedResourceId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
}
