package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.math.BigDecimal;

/**
 * P3 结算规则 settlement_rule。
 */
public class SettlementRule extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long merchantId;
    private String bizType;
    private String ruleName;
    private String settlementCycle;
    private BigDecimal platformCommissionRate;
    private BigDecimal merchantRate;
    private BigDecimal creatorRate;
    private BigDecimal inviterRate;

    /**
     * P0-28 批1:发起人保底率的三个公式参数,从 PricingServiceImpl 的硬编码搬进库。
     * 保底率 = base + step × min(已核销商家数, max)。
     * ⚠️ 存的是**公式参数**不是算好的率 —— 已核销商家数只有结算时才知道,锁价时算不出。
     */
    private BigDecimal guaranteeBaseRate;
    private BigDecimal guaranteeStepRate;
    private Integer guaranteeMaxMerchant;
    /** 商业化规则的后台配置单价；为空表示不可自助售卖。 */
    private BigDecimal unitPrice;
    /** 购买后授予的次数与有效期，回调时以订单快照为准。 */
    private Integer entitlementMaxUsage;
    private Integer entitlementDays;
    private BigDecimal minSettlementAmount;
    private Integer refundDeadlineHours;
    private Integer allowRefundAfterVerified;
    private Integer autoApproveRefund;
    private Integer status;
    private Integer delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public String getSettlementCycle() { return settlementCycle; }
    public void setSettlementCycle(String settlementCycle) { this.settlementCycle = settlementCycle; }
    public BigDecimal getPlatformCommissionRate() { return platformCommissionRate; }
    public void setPlatformCommissionRate(BigDecimal platformCommissionRate) { this.platformCommissionRate = platformCommissionRate; }
    public BigDecimal getMerchantRate() { return merchantRate; }
    public void setMerchantRate(BigDecimal merchantRate) { this.merchantRate = merchantRate; }
    public BigDecimal getCreatorRate() { return creatorRate; }
    public void setCreatorRate(BigDecimal creatorRate) { this.creatorRate = creatorRate; }
    public BigDecimal getInviterRate() { return inviterRate; }
    public BigDecimal getGuaranteeBaseRate() { return guaranteeBaseRate; }
    public void setGuaranteeBaseRate(BigDecimal guaranteeBaseRate) { this.guaranteeBaseRate = guaranteeBaseRate; }
    public BigDecimal getGuaranteeStepRate() { return guaranteeStepRate; }
    public void setGuaranteeStepRate(BigDecimal guaranteeStepRate) { this.guaranteeStepRate = guaranteeStepRate; }
    public Integer getGuaranteeMaxMerchant() { return guaranteeMaxMerchant; }
    public void setGuaranteeMaxMerchant(Integer guaranteeMaxMerchant) { this.guaranteeMaxMerchant = guaranteeMaxMerchant; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public Integer getEntitlementMaxUsage() { return entitlementMaxUsage; }
    public void setEntitlementMaxUsage(Integer entitlementMaxUsage) { this.entitlementMaxUsage = entitlementMaxUsage; }
    public Integer getEntitlementDays() { return entitlementDays; }
    public void setEntitlementDays(Integer entitlementDays) { this.entitlementDays = entitlementDays; }

    public void setInviterRate(BigDecimal inviterRate) { this.inviterRate = inviterRate; }
    public BigDecimal getMinSettlementAmount() { return minSettlementAmount; }
    public void setMinSettlementAmount(BigDecimal minSettlementAmount) { this.minSettlementAmount = minSettlementAmount; }
    public Integer getRefundDeadlineHours() { return refundDeadlineHours; }
    public void setRefundDeadlineHours(Integer refundDeadlineHours) { this.refundDeadlineHours = refundDeadlineHours; }
    public Integer getAllowRefundAfterVerified() { return allowRefundAfterVerified; }
    public void setAllowRefundAfterVerified(Integer allowRefundAfterVerified) { this.allowRefundAfterVerified = allowRefundAfterVerified; }
    public Integer getAutoApproveRefund() { return autoApproveRefund; }
    public void setAutoApproveRefund(Integer autoApproveRefund) { this.autoApproveRefund = autoApproveRefund; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
}
