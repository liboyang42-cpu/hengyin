package com.chengyinhub.business.domain;

import java.util.Date;

/** 城市定向局终投影(orient_score_settlement):每局恰好一行,积分/XP 只随这一行发一次。 */
public class OrientScoreSettlement {
    private Long id;
    private Long sessionId;
    private Long activityId;
    private Long topicId;
    private Long memberId;
    private Long totalCenti;
    private Integer entryCount;
    private Long ledgerWatermarkId;
    private Integer pointsAwarded;
    private Integer xpAwarded;
    private Integer pointsRatePct;
    private Integer xpRatePct;
    private String idempotencyKey;
    private String requestFingerprint;
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
    public Long getTotalCenti() { return totalCenti; }
    public void setTotalCenti(Long totalCenti) { this.totalCenti = totalCenti; }
    public Integer getEntryCount() { return entryCount; }
    public void setEntryCount(Integer entryCount) { this.entryCount = entryCount; }
    public Long getLedgerWatermarkId() { return ledgerWatermarkId; }
    public void setLedgerWatermarkId(Long ledgerWatermarkId) { this.ledgerWatermarkId = ledgerWatermarkId; }
    public Integer getPointsAwarded() { return pointsAwarded; }
    public void setPointsAwarded(Integer pointsAwarded) { this.pointsAwarded = pointsAwarded; }
    public Integer getXpAwarded() { return xpAwarded; }
    public void setXpAwarded(Integer xpAwarded) { this.xpAwarded = xpAwarded; }
    public Integer getPointsRatePct() { return pointsRatePct; }
    public void setPointsRatePct(Integer pointsRatePct) { this.pointsRatePct = pointsRatePct; }
    public Integer getXpRatePct() { return xpRatePct; }
    public void setXpRatePct(Integer xpRatePct) { this.xpRatePct = xpRatePct; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
