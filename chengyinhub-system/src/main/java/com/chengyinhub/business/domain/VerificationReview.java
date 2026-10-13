package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;
import java.math.BigDecimal;

/** 核销异常人工复核队列。只标记，不自动扣款、打款或改核销事实。 */
public class VerificationReview extends BaseEntity {
    private Long id;
    private Long verifierMemberId;
    private Long topicId;
    private String riskType;
    private BigDecimal verificationRate;
    private Integer paidCount;
    private Integer verifiedCount;
    private Integer oneMinuteCount;
    private Integer status;
    private String idempotencyKey;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVerifierMemberId() { return verifierMemberId; }
    public void setVerifierMemberId(Long verifierMemberId) { this.verifierMemberId = verifierMemberId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getRiskType() { return riskType; }
    public void setRiskType(String riskType) { this.riskType = riskType; }
    public BigDecimal getVerificationRate() { return verificationRate; }
    public void setVerificationRate(BigDecimal verificationRate) { this.verificationRate = verificationRate; }
    public Integer getPaidCount() { return paidCount; }
    public void setPaidCount(Integer paidCount) { this.paidCount = paidCount; }
    public Integer getVerifiedCount() { return verifiedCount; }
    public void setVerifiedCount(Integer verifiedCount) { this.verifiedCount = verifiedCount; }
    public Integer getOneMinuteCount() { return oneMinuteCount; }
    public void setOneMinuteCount(Integer oneMinuteCount) { this.oneMinuteCount = oneMinuteCount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
