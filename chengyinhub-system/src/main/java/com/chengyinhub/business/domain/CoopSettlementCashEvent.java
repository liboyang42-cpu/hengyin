package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 不可变来源证据的存储 DTO；不表示资金归一或支付执行成功。 */
public class CoopSettlementCashEvent {
    private Long id;
    private Long cashCloseId;
    private String eventType;
    private Long sourceId;
    private Long sourceRegistrationId;
    private BigDecimal sourceAmount;
    private String sourceSnapshot;
    private String sourceFingerprint;
    private Date createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getCashCloseId() { return cashCloseId; }
    public void setCashCloseId(Long value) { cashCloseId = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { eventType = value; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long value) { sourceId = value; }
    public Long getSourceRegistrationId() { return sourceRegistrationId; }
    public void setSourceRegistrationId(Long value) { sourceRegistrationId = value; }
    public BigDecimal getSourceAmount() { return sourceAmount; }
    public void setSourceAmount(BigDecimal value) { sourceAmount = value; }
    public String getSourceSnapshot() { return sourceSnapshot; }
    public void setSourceSnapshot(String value) { sourceSnapshot = value; }
    public String getSourceFingerprint() { return sourceFingerprint; }
    public void setSourceFingerprint(String value) { sourceFingerprint = value; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date value) { createdAt = value; }
}
