package com.chengyinhub.business.domain;

import java.util.Date;

/** 城市定向计分流水(orient_score_ledger):只追加,纠错为反向条目。分值单位 1/100 分。 */
public class OrientScoreEntry {
    public static final String DIM_TASK = "TASK";
    public static final String DIM_PUZZLE = "PUZZLE";
    public static final String DIM_PENALTY = "PENALTY";
    public static final String DIM_BONUS = "BONUS";
    public static final String DIM_ADJUSTMENT = "ADJUSTMENT";

    private Long id;
    private Long sessionId;
    private Long activityId;
    private Long topicId;
    private Long memberId;
    private String dimension;
    private Integer deltaCenti;
    private String reasonCode;
    private String sourceType;
    private String sourceRef;
    private Long nodeId;
    private Long reversesEntryId;
    private String idempotencyKey;
    private String requestFingerprint;
    private String operatorType;
    private Long operatorId;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }
    public Integer getDeltaCenti() { return deltaCenti; }
    public void setDeltaCenti(Integer deltaCenti) { this.deltaCenti = deltaCenti; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Long getReversesEntryId() { return reversesEntryId; }
    public void setReversesEntryId(Long reversesEntryId) { this.reversesEntryId = reversesEntryId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public String getOperatorType() { return operatorType; }
    public void setOperatorType(String operatorType) { this.operatorType = operatorType; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
