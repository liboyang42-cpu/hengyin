package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 不可变来源证据的存储 DTO；不表示资金归一或支付执行成功。 */
public class CoopSettlementCashClose {
    private Long id;
    private Long topicId;
    private String commercialContextType;
    private Long commercialContextId;
    private String closeKind;
    private Long initiatorMemberId;
    private BigDecimal originalSalesBasis;
    private Integer originalContributionCount;
    private BigDecimal platformRate;
    private BigDecimal guaranteeBaseRate;
    private BigDecimal guaranteeStepRate;
    private Integer guaranteeMaxMerchant;
    private Integer originalVerifiedMerchantCount;
    private String originCalculationVersion;
    private String contractSnapshot;
    private String sourceFingerprint;
    private Date closedAt;
    private Date createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public String getCommercialContextType() { return commercialContextType; }
    public void setCommercialContextType(String value) { commercialContextType = value; }
    public Long getCommercialContextId() { return commercialContextId; }
    public void setCommercialContextId(Long value) { commercialContextId = value; }
    public String getCloseKind() { return closeKind; }
    public void setCloseKind(String value) { closeKind = value; }
    public Long getInitiatorMemberId() { return initiatorMemberId; }
    public void setInitiatorMemberId(Long value) { initiatorMemberId = value; }
    public BigDecimal getOriginalSalesBasis() { return originalSalesBasis; }
    public void setOriginalSalesBasis(BigDecimal value) { originalSalesBasis = value; }
    public Integer getOriginalContributionCount() { return originalContributionCount; }
    public void setOriginalContributionCount(Integer value) { originalContributionCount = value; }
    public BigDecimal getPlatformRate() { return platformRate; }
    public void setPlatformRate(BigDecimal value) { platformRate = value; }
    public BigDecimal getGuaranteeBaseRate() { return guaranteeBaseRate; }
    public void setGuaranteeBaseRate(BigDecimal value) { guaranteeBaseRate = value; }
    public BigDecimal getGuaranteeStepRate() { return guaranteeStepRate; }
    public void setGuaranteeStepRate(BigDecimal value) { guaranteeStepRate = value; }
    public Integer getGuaranteeMaxMerchant() { return guaranteeMaxMerchant; }
    public void setGuaranteeMaxMerchant(Integer value) { guaranteeMaxMerchant = value; }
    public Integer getOriginalVerifiedMerchantCount() { return originalVerifiedMerchantCount; }
    public void setOriginalVerifiedMerchantCount(Integer value) { originalVerifiedMerchantCount = value; }
    public String getOriginCalculationVersion() { return originCalculationVersion; }
    public void setOriginCalculationVersion(String value) { originCalculationVersion = value; }
    public String getContractSnapshot() { return contractSnapshot; }
    public void setContractSnapshot(String value) { contractSnapshot = value; }
    public String getSourceFingerprint() { return sourceFingerprint; }
    public void setSourceFingerprint(String value) { sourceFingerprint = value; }
    public Date getClosedAt() { return closedAt; }
    public void setClosedAt(Date value) { closedAt = value; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date value) { createdAt = value; }
}
