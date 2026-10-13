package com.chengyinhub.business.domain;

import java.util.Date;

public class MerchantSafetyRating {
    public static final String LEVEL_SAFE = "SAFE";
    public static final String LEVEL_NEUTRAL = "NEUTRAL";
    public static final String LEVEL_UNSAFE = "UNSAFE";
    public static final String DIMENSION_OVERALL = "OVERALL";

    private Long id;
    private Long memberId;
    private Long merchantId;
    private String overallLevel;
    private String reason;
    private String idempotencyKey;
    private String payloadHash;
    private String ratingPeriod;
    private String eligibilityType;
    private Long eligibilitySourceId;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public String getOverallLevel() { return overallLevel; }
    public void setOverallLevel(String overallLevel) { this.overallLevel = overallLevel; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getRatingPeriod() { return ratingPeriod; }
    public void setRatingPeriod(String ratingPeriod) { this.ratingPeriod = ratingPeriod; }
    public String getEligibilityType() { return eligibilityType; }
    public void setEligibilityType(String eligibilityType) { this.eligibilityType = eligibilityType; }
    public Long getEligibilitySourceId() { return eligibilitySourceId; }
    public void setEligibilitySourceId(Long eligibilitySourceId) { this.eligibilitySourceId = eligibilitySourceId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
