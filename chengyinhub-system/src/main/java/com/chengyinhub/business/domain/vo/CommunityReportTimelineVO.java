package com.chengyinhub.business.domain.vo;

import java.util.Date;

/** 面向举报双方的公开进度投影；类型上不含幂等键、操作人和内部备注。 */
public class CommunityReportTimelineVO {
    private String eventType;
    private String fromStage;
    private String toStage;
    private String publicNote;
    private Date createTime;

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getFromStage() { return fromStage; }
    public void setFromStage(String fromStage) { this.fromStage = fromStage; }
    public String getToStage() { return toStage; }
    public void setToStage(String toStage) { this.toStage = toStage; }
    public String getPublicNote() { return publicNote; }
    public void setPublicNote(String publicNote) { this.publicNote = publicNote; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
