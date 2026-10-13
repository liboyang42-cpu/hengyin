package com.chengyinhub.business.domain.dto;

public class MerchantSafetyRatingRequest {
    private Long merchantId;
    private String overallLevel;
    private String reason;
    private String idempotencyKey;

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public String getOverallLevel() { return overallLevel; }
    public void setOverallLevel(String overallLevel) { this.overallLevel = overallLevel; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
