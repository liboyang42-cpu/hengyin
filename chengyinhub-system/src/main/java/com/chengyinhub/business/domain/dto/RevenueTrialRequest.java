package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 分配试算请求(只算数，不落库)。
 *
 * <p>刻意<b>不</b>接收 discount 参数：净额起点是折后实付，结构上杜绝二次扣折。
 * 试算不需要支付事实(它就是给运营评估配置用的)，因此这里允许直接给 paidAmount；
 * 但真正的应计坚持读已验证支付事实。
 */
public class RevenueTrialRequest {

    private Integer scenario;
    private String currency;
    private BigDecimal paidAmount;
    private BigDecimal taxAmount;
    private BigDecimal paymentFeeAmount;
    private BigDecimal channelCommissionAmount;
    /** 可选：传入 ruleKey 用该规则；不传则按 targetType/targetId 解析生效规则。 */
    private String ruleKey;
    private Integer targetType;
    private Long targetId;
    private Boolean hasReferrer;

    public Integer getScenario() { return scenario; }
    public void setScenario(Integer scenario) { this.scenario = scenario; }

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

    public String getRuleKey() { return ruleKey; }
    public void setRuleKey(String ruleKey) { this.ruleKey = ruleKey; }

    public Integer getTargetType() { return targetType; }
    public void setTargetType(Integer targetType) { this.targetType = targetType; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }

    public Boolean getHasReferrer() { return hasReferrer; }
    public void setHasReferrer(Boolean hasReferrer) { this.hasReferrer = hasReferrer; }
}
