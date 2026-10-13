package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/** COOP_ORDER 原始归属明细草稿。 */
public class CoopSettlementOriginLine {
    private final Long registrationId;
    private BigDecimal originAmount;
    private BigDecimal eligibleSalesSnapshot;
    private String settlementRoute;
    private String sourceContractType;
    private Long sourceContractId;
    private String commercialContextType;
    private Long commercialContextId;
    private String beneficiaryRole;
    private Long beneficiaryId;
    private String serviceUnit;

    public CoopSettlementOriginLine(Long registrationId, BigDecimal originAmount) {
        this.registrationId = registrationId;
        this.originAmount = originAmount;
    }

    public Long getRegistrationId() { return registrationId; }
    public BigDecimal getOriginAmount() { return originAmount; }
    public void setOriginAmount(BigDecimal value) { this.originAmount = value; }
    public BigDecimal getEligibleSalesSnapshot() { return eligibleSalesSnapshot; }
    public void setEligibleSalesSnapshot(BigDecimal value) { this.eligibleSalesSnapshot = value; }
    public String getSettlementRoute() { return settlementRoute; }
    public void setSettlementRoute(String value) { this.settlementRoute = value; }
    public String getSourceContractType() { return sourceContractType; }
    public void setSourceContractType(String value) { this.sourceContractType = value; }
    public Long getSourceContractId() { return sourceContractId; }
    public void setSourceContractId(Long value) { this.sourceContractId = value; }
    public String getCommercialContextType() { return commercialContextType; }
    public void setCommercialContextType(String value) { this.commercialContextType = value; }
    public Long getCommercialContextId() { return commercialContextId; }
    public void setCommercialContextId(Long value) { this.commercialContextId = value; }
    public String getBeneficiaryRole() { return beneficiaryRole; }
    public void setBeneficiaryRole(String value) { this.beneficiaryRole = value; }
    public Long getBeneficiaryId() { return beneficiaryId; }
    public void setBeneficiaryId(Long value) { this.beneficiaryId = value; }
    public String getServiceUnit() { return serviceUnit; }
    public void setServiceUnit(String value) { this.serviceUnit = value; }
}
