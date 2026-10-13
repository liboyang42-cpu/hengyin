package com.chengyinhub.business.domain;

import java.util.Date;

/** 个人信息处理同意/撤回的 append-only 留痕。 */
public class ConsentRecord {
    private Long id;
    private Long memberId;
    private String docType;
    private String docVersion;
    private String scene;
    private String scopeType;
    private Long scopeId;
    private String eventType;
    private Date occurredAt;
    private String requestId;
    private String ipAddress;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public String getDocVersion() { return docVersion; }
    public void setDocVersion(String docVersion) { this.docVersion = docVersion; }
    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Date getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Date occurredAt) { this.occurredAt = occurredAt; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
