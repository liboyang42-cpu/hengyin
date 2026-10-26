package com.chengyinhub.business.domain.dto;

/** Frozen input for a single team/event command; principal is always supplied by the server. */
public class TeamEventCommand {
    private String attendanceConfig;
    private Long configVersion;
    private String credentialCode;
    public String getAttendanceConfig(){return attendanceConfig;}
    public void setAttendanceConfig(String v){attendanceConfig=v;}
    public Long getConfigVersion(){return configVersion;}
    public void setConfigVersion(Long v){configVersion=v;}
    public String getCredentialCode(){return credentialCode;}
    public void setCredentialCode(String v){credentialCode=v;}
    private String inviteCode;
    private String message;
    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String v) { inviteCode = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
    private String requestId;
    private String action;
    private Long targetId;
    private Long memberId;
    private Integer joinMode;
    private Boolean approved;
    private String reason;
    private String impactScope;
    private String alternativePlan;
    private String notificationPlan;
    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { requestId = v; }
    public String getAction() { return action; }
    public void setAction(String v) { action = v; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long v) { targetId = v; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long v) { memberId = v; }
    public Integer getJoinMode() { return joinMode; }
    public void setJoinMode(Integer v) { joinMode = v; }
    public Boolean getApproved() { return approved; }
    public void setApproved(Boolean v) { approved = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { reason = v; }
    public String getImpactScope() { return impactScope; }
    public void setImpactScope(String v) { impactScope = v; }
    public String getAlternativePlan() { return alternativePlan; }
    public void setAlternativePlan(String v) { alternativePlan = v; }
    public String getNotificationPlan() { return notificationPlan; }
    public void setNotificationPlan(String v) { notificationPlan = v; }
}
