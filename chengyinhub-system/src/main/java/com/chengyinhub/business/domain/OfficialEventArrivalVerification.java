package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 活动到达验证事实。仅保存距离、精度分桶和风险结果，不保存玩家原始坐标。
 */
public class OfficialEventArrivalVerification {
    public static final String TYPE_ROAM_POI = "ROAM_POI";
    public static final String TYPE_EVENT_POINT = "EVENT_POINT";
    public static final String RESULT_ACCEPTED = "ACCEPTED";
    public static final String RESULT_REJECTED = "REJECTED";

    private Long id;
    private Long eventId;
    private Long missionId;
    private Long bindingId;
    private Long memberId;
    private Long sessionId;
    private String verificationType;
    private String requestId;
    private Integer distanceM;
    private String accuracyBucket;
    private String ruleVersion;
    private String result;
    private String riskCode;
    private Date occurredAt;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public Long getMissionId() { return missionId; }
    public void setMissionId(Long missionId) { this.missionId = missionId; }
    public Long getBindingId() { return bindingId; }
    public void setBindingId(Long bindingId) { this.bindingId = bindingId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getVerificationType() { return verificationType; }
    public void setVerificationType(String verificationType) { this.verificationType = verificationType; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Integer getDistanceM() { return distanceM; }
    public void setDistanceM(Integer distanceM) { this.distanceM = distanceM; }
    public String getAccuracyBucket() { return accuracyBucket; }
    public void setAccuracyBucket(String accuracyBucket) { this.accuracyBucket = accuracyBucket; }
    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getRiskCode() { return riskCode; }
    public void setRiskCode(String riskCode) { this.riskCode = riskCode; }
    public Date getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Date occurredAt) { this.occurredAt = occurredAt; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
