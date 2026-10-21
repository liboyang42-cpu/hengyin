package com.chengyinhub.business.citygame.operations;

import java.util.Date;

/** 权威请求。同一 requestId 只认一次正文；UNKNOWN 不会被再次执行。 */
public class CityGameOperationsRequestRow {
    private Long id;
    private String requestId;
    private String requestHash;
    private String bodyJson;
    private Long caseId;
    private String action;
    private Long actorUserId;
    private String roleKeys;
    private String state;
    private Integer httpStatus;
    private String resultJson;
    private Date createTime;
    private Date updateTime;

    public CityGameOperationsRequestRow copy() {
        CityGameOperationsRequestRow row = new CityGameOperationsRequestRow();
        row.id = id;
        row.requestId = requestId;
        row.requestHash = requestHash;
        row.bodyJson = bodyJson;
        row.caseId = caseId;
        row.action = action;
        row.actorUserId = actorUserId;
        row.roleKeys = roleKeys;
        row.state = state;
        row.httpStatus = httpStatus;
        row.resultJson = resultJson;
        row.createTime = createTime;
        row.updateTime = updateTime;
        return row;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public String getBodyJson() { return bodyJson; }
    public void setBodyJson(String bodyJson) { this.bodyJson = bodyJson; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
    public String getRoleKeys() { return roleKeys; }
    public void setRoleKeys(String roleKeys) { this.roleKeys = roleKeys; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
