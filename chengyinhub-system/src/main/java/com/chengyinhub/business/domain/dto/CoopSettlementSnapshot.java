package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/** W2 冻结列的窄 seam DTO；本类不把未合并字段塞回 CoopOrder。 */
public class CoopSettlementSnapshot {
    private Long billingStationId;
    private String orderTermsSha256;
    public Long getBillingStationId() { return billingStationId; }
    public void setBillingStationId(Long value) { billingStationId = value; }
    public String getOrderTermsSha256() { return orderTermsSha256; }
    public void setOrderTermsSha256(String value) { orderTermsSha256 = value; }
    private Long orderId;
    private Long topicId;
    private Long initiatorMemberId;
    private Integer productType;
    private String termsVersion;
    private Date catalogFrozenAt;
    private String commercialContextType;
    private Long commercialContextId;
    private String beneficiaryRole;
    private Long beneficiaryId;
    private String serviceUnit;
    private String scopeType;
    private Long scopeId;
    private List<String> eligiblePurchaseKinds;
    private String fulfillmentEvidenceRule;
    private String compensationMode;
    private BigDecimal fixedFee;
    private BigDecimal shareRate;
    private Date settlementSnapshotAt;
    private Date fulfillmentEndExclusive;
    private Date fulfillmentStartInclusive;

    public Long getInitiatorMemberId() { return initiatorMemberId; }
    public void setInitiatorMemberId(Long value) { this.initiatorMemberId = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public Integer getProductType() { return productType; }
    public void setProductType(Integer value) { productType = value; }
    public String getTermsVersion() { return termsVersion; }
    public void setTermsVersion(String value) { termsVersion = value; }
    public Date getCatalogFrozenAt() { return catalogFrozenAt; }
    public void setCatalogFrozenAt(Date value) { catalogFrozenAt = value; }
    public Date getFulfillmentStartInclusive() { return fulfillmentStartInclusive; }
    public void setFulfillmentStartInclusive(Date value) { fulfillmentStartInclusive = value; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long value) { this.orderId = value; }
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
    public String getScopeType() { return scopeType; }
    public void setScopeType(String value) { this.scopeType = value; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long value) { this.scopeId = value; }
    public List<String> getEligiblePurchaseKinds() { return eligiblePurchaseKinds; }
    public void setEligiblePurchaseKinds(List<String> value) { this.eligiblePurchaseKinds = value; }
    public String getFulfillmentEvidenceRule() { return fulfillmentEvidenceRule; }
    public void setFulfillmentEvidenceRule(String value) { this.fulfillmentEvidenceRule = value; }
    public String getCompensationMode() { return compensationMode; }
    public void setCompensationMode(String value) { this.compensationMode = value; }
    public BigDecimal getFixedFee() { return fixedFee; }
    public void setFixedFee(BigDecimal value) { this.fixedFee = value; }
    public BigDecimal getShareRate() { return shareRate; }
    public void setShareRate(BigDecimal value) { this.shareRate = value; }
    public Date getSettlementSnapshotAt() { return settlementSnapshotAt; }
    public void setSettlementSnapshotAt(Date value) { this.settlementSnapshotAt = value; }
    public Date getFulfillmentEndExclusive() { return fulfillmentEndExclusive; }
    public void setFulfillmentEndExclusive(Date value) { this.fulfillmentEndExclusive = value; }
}
