package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/** 分配试算结果：净额分项 + 三方金额 + 守恒校验。 */
public class RevenueTrialResult {

    private String currency;
    private BigDecimal paidAmount;
    private BigDecimal taxAmount;
    private BigDecimal paymentFeeAmount;
    private BigDecimal channelCommissionAmount;
    private BigDecimal netAmount;
    private BigDecimal authorAmount;
    private BigDecimal referrerAmount;
    private BigDecimal platformAmount;
    private BigDecimal authorBaseRate;
    private BigDecimal platformBaseRate;
    private BigDecimal referralRate;
    private Integer referralFundingSide;
    private Long ruleId;
    private String ruleKey;
    private Integer ruleVersion;
    private Integer scenario;
    private String contractRef;
    private boolean conserved;

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }

    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }

    public BigDecimal getPaymentFeeAmount() { return paymentFeeAmount; }
    public void setPaymentFeeAmount(BigDecimal paymentFeeAmount) { this.paymentFeeAmount = paymentFeeAmount; }

    public BigDecimal getChannelCommissionAmount() { return channelCommissionAmount; }
    public void setChannelCommissionAmount(BigDecimal channelCommissionAmount) { this.channelCommissionAmount = channelCommissionAmount; }

    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }

    public BigDecimal getAuthorAmount() { return authorAmount; }
    public void setAuthorAmount(BigDecimal authorAmount) { this.authorAmount = authorAmount; }

    public BigDecimal getReferrerAmount() { return referrerAmount; }
    public void setReferrerAmount(BigDecimal referrerAmount) { this.referrerAmount = referrerAmount; }

    public BigDecimal getPlatformAmount() { return platformAmount; }
    public void setPlatformAmount(BigDecimal platformAmount) { this.platformAmount = platformAmount; }

    public BigDecimal getAuthorBaseRate() { return authorBaseRate; }
    public void setAuthorBaseRate(BigDecimal authorBaseRate) { this.authorBaseRate = authorBaseRate; }

    public BigDecimal getPlatformBaseRate() { return platformBaseRate; }
    public void setPlatformBaseRate(BigDecimal platformBaseRate) { this.platformBaseRate = platformBaseRate; }

    public BigDecimal getReferralRate() { return referralRate; }
    public void setReferralRate(BigDecimal referralRate) { this.referralRate = referralRate; }

    public Integer getReferralFundingSide() { return referralFundingSide; }
    public void setReferralFundingSide(Integer referralFundingSide) { this.referralFundingSide = referralFundingSide; }

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }

    public String getRuleKey() { return ruleKey; }
    public void setRuleKey(String ruleKey) { this.ruleKey = ruleKey; }

    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }

    public Integer getScenario() { return scenario; }
    public void setScenario(Integer scenario) { this.scenario = scenario; }

    public String getContractRef() { return contractRef; }
    public void setContractRef(String contractRef) { this.contractRef = contractRef; }

    public boolean isConserved() { return conserved; }
    public void setConserved(boolean conserved) { this.conserved = conserved; }
}
