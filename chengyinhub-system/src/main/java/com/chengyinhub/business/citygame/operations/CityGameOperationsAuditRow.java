package com.chengyinhub.business.citygame.operations;

import java.util.Date;

public class CityGameOperationsAuditRow {
    private Long id;
    private String requestId;
    private Long caseId;
    private String action;
    private String eventType;
    private Long actorUserId;
    private String roleKeys;
    private String requestHash;
    private Integer httpStatus;
    private String resultJson;
    private Date createTime;

    public CityGameOperationsAuditRow copy() {
        CityGameOperationsAuditRow row = new CityGameOperationsAuditRow();
        row.id = id;
        row.requestId = requestId;
        row.caseId = caseId;
        row.action = action;
        row.eventType = eventType;
        row.actorUserId = actorUserId;
        row.roleKeys = roleKeys;
        row.requestHash = requestHash;
        row.httpStatus = httpStatus;
        row.resultJson = resultJson;
        row.createTime = createTime;
        return row;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Long getActorUserId() { return actorUserId; }
    public String getRoleKeys() { return roleKeys; }
    public void setRoleKeys(String roleKeys) { this.roleKeys = roleKeys; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
