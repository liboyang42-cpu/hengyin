package com.chengyinhub.business.locationtrust;

import java.math.BigDecimal;
import java.util.Date;

/** One row of {@code location_trust_fix}. */
public class LocationTrustFixRow {
    private Long id;
    private Long memberId;
    private Long sessionId;
    private String kind;
    private String refId;
    private BigDecimal lat;
    private BigDecimal lng;
    private BigDecimal accuracyM;
    private Date clientTs;
    private Date receivedAt;
    private Integer simulated;
    private Integer accessory;
    private BigDecimal speedMps;
    private String decision;
    private String appliedDecision;
    private String reasons;
    private String detail;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }
    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }
    public BigDecimal getLng() { return lng; }
    public void setLng(BigDecimal lng) { this.lng = lng; }
    public BigDecimal getAccuracyM() { return accuracyM; }
    public void setAccuracyM(BigDecimal accuracyM) { this.accuracyM = accuracyM; }
    public Date getClientTs() { return clientTs; }
    public void setClientTs(Date clientTs) { this.clientTs = clientTs; }
    public Date getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Date receivedAt) { this.receivedAt = receivedAt; }
    public Integer getSimulated() { return simulated; }
    public void setSimulated(Integer simulated) { this.simulated = simulated; }
    public Integer getAccessory() { return accessory; }
    public void setAccessory(Integer accessory) { this.accessory = accessory; }
    public BigDecimal getSpeedMps() { return speedMps; }
    public void setSpeedMps(BigDecimal speedMps) { this.speedMps = speedMps; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getAppliedDecision() { return appliedDecision; }
    public void setAppliedDecision(String appliedDecision) { this.appliedDecision = appliedDecision; }
    public String getReasons() { return reasons; }
    public void setReasons(String reasons) { this.reasons = reasons; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
