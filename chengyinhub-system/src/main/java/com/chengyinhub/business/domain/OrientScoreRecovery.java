package com.chengyinhub.business.domain;

/** Manual investigation; REVIEWED never asserts the missing original request succeeded. */
public class OrientScoreRecovery {
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long id;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long sessionId;
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long value) { sessionId = value; }
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long memberId;
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long referenceId;
    public Long getReferenceId() { return referenceId; }
    public void setReferenceId(Long value) { referenceId = value; }
    private String kind;
    public String getKind() { return kind; }
    public void setKind(String value) { kind = value; }
    private String resolutionNote;
    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String value) { resolutionNote = value; }
    private String handledBy;
    public String getHandledBy() { return handledBy; }
    public void setHandledBy(String value) { handledBy = value; }
    private Integer status;
    public Integer getStatus() { return status; }
    public void setStatus(Integer value) { status = value; }
    private java.util.Date createTime;
    public java.util.Date getCreateTime() { return createTime; }
    public void setCreateTime(java.util.Date value) { createTime = value; }
    private java.util.Date handledAt;
    public java.util.Date getHandledAt() { return handledAt; }
    public void setHandledAt(java.util.Date value) { handledAt = value; }
    /** Player-facing audit result omits staff account identity. */
    public java.util.Map<String, Object> playerView() {
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", id); view.put("sessionId", sessionId); view.put("memberId", memberId);
        view.put("kind", kind); view.put("status", status); view.put("referenceId", referenceId);
        view.put("resolutionNote", resolutionNote); view.put("createTime", createTime); view.put("handledAt", handledAt);
        return view;
    }

}
