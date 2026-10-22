package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 城市定向计分申诉(orient_score_appeal)。kind 区分两张表共用的读模型:
 * APPEAL=计分申诉(orient_score_appeal) / DISPUTE=成绩争议(orient_result_dispute,无 entryId/nodeId,有 windowDeadline)。
 */
public class OrientScoreAppeal {
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_ACCEPTED = 1;
    public static final int STATUS_REJECTED = 2;

    private Long id;
    private Long sessionId;
    private Long memberId;
    private Long entryId;
    private Long nodeId;
    private String reason;
    private Integer status;
    private Long resolutionEntryId;
    private String resolutionNote;
    private String handledBy;
    private Date handledAt;
    private Date windowDeadline;
    private String idempotencyKey;
    private String requestFingerprint;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getEntryId() { return entryId; }
    public void setEntryId(Long entryId) { this.entryId = entryId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getResolutionEntryId() { return resolutionEntryId; }
    public void setResolutionEntryId(Long resolutionEntryId) { this.resolutionEntryId = resolutionEntryId; }
    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }
    public String getHandledBy() { return handledBy; }
    public void setHandledBy(String handledBy) { this.handledBy = handledBy; }
    public Date getHandledAt() { return handledAt; }
    public void setHandledAt(Date handledAt) { this.handledAt = handledAt; }
    public Date getWindowDeadline() { return windowDeadline; }
    public void setWindowDeadline(Date windowDeadline) { this.windowDeadline = windowDeadline; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
