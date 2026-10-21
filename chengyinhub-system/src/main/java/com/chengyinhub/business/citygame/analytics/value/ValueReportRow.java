package com.chengyinhub.business.citygame.analytics.value;

import java.util.Date;

/** 一次只读报告的权威正文。相同请求回放这一行，不另算一遍分母。 */
public class ValueReportRow {
    private Long id;
    private String resultKey;
    private String regionId;
    private String rulesReleaseId;
    private String definitionHash;
    private Long actorUserId;
    private String bodyJson;
    private Date createTime;

    public ValueReportRow copy() {
        ValueReportRow row = new ValueReportRow();
        row.id = id;
        row.resultKey = resultKey;
        row.regionId = regionId;
        row.rulesReleaseId = rulesReleaseId;
        row.definitionHash = definitionHash;
        row.actorUserId = actorUserId;
        row.bodyJson = bodyJson;
        row.createTime = createTime == null ? null : new Date(createTime.getTime());
        return row;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getResultKey() { return resultKey; }
    public void setResultKey(String resultKey) { this.resultKey = resultKey; }
    public String getRegionId() { return regionId; }
    public void setRegionId(String regionId) { this.regionId = regionId; }
    public String getRulesReleaseId() { return rulesReleaseId; }
    public void setRulesReleaseId(String rulesReleaseId) { this.rulesReleaseId = rulesReleaseId; }
    public String getDefinitionHash() { return definitionHash; }
    public void setDefinitionHash(String definitionHash) { this.definitionHash = definitionHash; }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
    public String getBodyJson() { return bodyJson; }
    public void setBodyJson(String bodyJson) { this.bodyJson = bodyJson; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
