package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.math.BigDecimal;

/** COOP_ORDER 原始毛额之外的 append-only 调整事件。 */
public class CoopSettlementAdjustment extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long settlementId;
    private Long claimId;
    private String entryKind;
    private String eventKey;
    private BigDecimal amount;
    private String status;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long value) { this.settlementId = value; }
    public Long getClaimId() { return claimId; }
    public void setClaimId(Long value) { this.claimId = value; }
    public String getEntryKind() { return entryKind; }
    public void setEntryKind(String value) { this.entryKind = value; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String value) { this.eventKey = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { this.amount = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
}
