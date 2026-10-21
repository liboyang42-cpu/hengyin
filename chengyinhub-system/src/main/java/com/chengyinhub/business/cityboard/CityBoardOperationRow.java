package com.chengyinhub.business.cityboard;

import java.util.Date;

public class CityBoardOperationRow {
    private Long id;
    private String requestId;
    private String action;
    private Long actorUserId;
    private String requestHash;
    private Integer httpStatus;
    private String resultJson;
    private Long draftId;
    private Long releaseId;
    private Date createTime;

    public CityBoardOperationRow copy() {
        CityBoardOperationRow row = new CityBoardOperationRow();
        row.id = id;
        row.requestId = requestId;
        row.action = action;
        row.actorUserId = actorUserId;
        row.requestHash = requestHash;
        row.httpStatus = httpStatus;
        row.resultJson = resultJson;
        row.draftId = draftId;
        row.releaseId = releaseId;
        row.createTime = createTime;
        return row;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public Long getDraftId() { return draftId; }
    public void setDraftId(Long draftId) { this.draftId = draftId; }
    public Long getReleaseId() { return releaseId; }
    public void setReleaseId(Long releaseId) { this.releaseId = releaseId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
