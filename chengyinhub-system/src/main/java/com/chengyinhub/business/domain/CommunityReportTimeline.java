package com.chengyinhub.business.domain;

import java.util.Date;

public class CommunityReportTimeline {
    private Long id;
    private Long reportId;
    private String eventKey;
    private String payloadHash;
    private String actorType;
    private String eventType;
    private String fromStage;
    private String toStage;
    private String publicNote;
    private String internalNote;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String eventKey) { this.eventKey = eventKey; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getFromStage() { return fromStage; }
    public void setFromStage(String fromStage) { this.fromStage = fromStage; }
    public String getToStage() { return toStage; }
    public void setToStage(String toStage) { this.toStage = toStage; }
    public String getPublicNote() { return publicNote; }
    public void setPublicNote(String publicNote) { this.publicNote = publicNote; }
    public String getInternalNote() { return internalNote; }
    public void setInternalNote(String internalNote) { this.internalNote = internalNote; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
