package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.math.BigDecimal;

/** 聚合头与原子 REGISTRATION claim 的不可变归属明细。 */
public class CoopSettlementDetail extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long settlementId;
    private Long claimId;
    private Long registrationId;
    private BigDecimal originAmount;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long value) { this.settlementId = value; }
    public Long getClaimId() { return claimId; }
    public void setClaimId(Long value) { this.claimId = value; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long value) { this.registrationId = value; }
    public BigDecimal getOriginAmount() { return originAmount; }
    public void setOriginAmount(BigDecimal value) { this.originAmount = value; }
}
