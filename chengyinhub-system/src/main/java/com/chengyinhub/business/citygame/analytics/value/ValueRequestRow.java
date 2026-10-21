package com.chengyinhub.business.citygame.analytics.value;

import java.util.Date;

/** 报告请求的持久回执。重试、重启和取消都读这一行，不靠内存。 */
public class ValueRequestRow {
    private Long id;
    private String requestId;
    private Long actorUserId;
    private String payloadHash;
    private String status;
    private Long resultId;
    private String regionId;
    private String rulesReleaseId;
    private String bodyJson;
    private Date createTime;

    public ValueRequestRow copy() {
        ValueRequestRow row = new ValueRequestRow();
        row.id = id;
        row.requestId = requestId;
        row.actorUserId = actorUserId;
        row.payloadHash = payloadHash;
        row.status = status;
        row.resultId = resultId;
        row.regionId = regionId;
        row.rulesReleaseId = rulesReleaseId;
        row.bodyJson = bodyJson;
        row.createTime = createTime == null ? null : new Date(createTime.getTime());
        return row;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getResultId() { return resultId; }
    public void setResultId(Long resultId) { this.resultId = resultId; }
    public String getRegionId() { return regionId; }
    public void setRegionId(String regionId) { this.regionId = regionId; }
    public String getRulesReleaseId() { return rulesReleaseId; }
    public void setRulesReleaseId(String rulesReleaseId) { this.rulesReleaseId = rulesReleaseId; }
    public String getBodyJson() { return bodyJson; }
    public void setBodyJson(String bodyJson) { this.bodyJson = bodyJson; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
