package com.chengyinhub.business.domain;

import java.io.Serializable;
import java.util.Date;

/**
 * 通用内容申诉 audit_appeal：作者对自己被驳回/下架的活动、主题、玩法、模板广场条目申诉。
 * 每位作者每条内容只能申诉一次（uk_audit_appeal_biz_member）；申诉单驱动一张 source=appeal 的审核任务。
 */
public class AuditAppeal implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String STATE_SUBMITTED = "SUBMITTED";
    public static final String STATE_REVIEWING = "REVIEWING";
    public static final String STATE_ACCEPTED = "ACCEPTED";
    public static final String STATE_REJECTED = "REJECTED";
    /** 作者修改了被申诉的内容(或内容安全已另行下架),申诉自动撤回;终态,不再进入裁决。 */
    public static final String STATE_WITHDRAWN = "WITHDRAWN";

    private Long id;
    private String bizType;
    private Long bizId;
    private Long taskId;
    private Long memberId;
    private String reason;
    private String evidenceJson;
    private String state;
    private Date dueAt;
    private Long handledBy;
    private Date handledAt;
    private Date createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Date getDueAt() { return dueAt; }
    public void setDueAt(Date dueAt) { this.dueAt = dueAt; }
    public Long getHandledBy() { return handledBy; }
    public void setHandledBy(Long handledBy) { this.handledBy = handledBy; }
    public Date getHandledAt() { return handledAt; }
    public void setHandledAt(Date handledAt) { this.handledAt = handledAt; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
