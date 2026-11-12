package com.chengyinhub.business.domain.marketing;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 原单收入分配快照(表 marketing_revenue_allocation)。一原单一行，insert-only 主账。
 *
 * <p>关键口径：
 * <ul>
 *   <li>金额真源是 {@code *_fen}（整数分）。{@code getXxxAmount()} 只是展示派生，不参与计算。</li>
 *   <li>{@link #paidFen} 来自<b>已验证支付事实</b>（{@link #paymentFactHash}），不是
 *       {@code payment_status} + total/original 回退猜出来的金额。</li>
 *   <li>{@link #discountFen} 仅作事实快照，<b>不参与再扣一次</b>；税费/手续费/渠道佣金各自独立，
 *       由 {@link #feeBasisRef} 指明核算证据，缺证据不得应计。</li>
 *   <li>{@link #authorMemberId} 是合同声明的收款权利人，不是内容 owner。</li>
 *   <li>状态区分已应计(1)/可结算(2)/已打款(3)/部分冲销(4)/全部冲销(5)；本域不持有钱包。</li>
 * </ul>
 */
public class MarketingRevenueAllocation {

    public static final int ORDER_REGISTRATION = 1;

    public static final int STATUS_ACCRUED = 1;
    public static final int STATUS_SETTLEABLE = 2;
    public static final int STATUS_PAID = 3;
    public static final int STATUS_PARTIALLY_REVERSED = 4;
    public static final int STATUS_REVERSED = 5;

    private Long id;
    private Integer orderType;
    private Long orderId;
    private String allocationKey;
    private Long ruleId;
    private String ruleKey;
    private Integer ruleVersion;
    private Integer scenario;
    private Long attributionId;
    private Long sharerMemberId;
    private String campaignKey;
    private Long authorMemberId;
    private String contractRef;
    private Integer ownerType;
    private Long ownerId;
    private String currency;
    private Long paidFen;
    private String paymentFactHash;
    private Long originalAmountFen;
    private Long discountFen;
    private Long pointDeductFen;
    private Long taxFen;
    private Long paymentFeeFen;
    private Long channelCommissionFen;
    private String feeBasisRef;
    private Long netFen;
    private Integer referralFundingSide;
    private Integer status;
    private Date settleableAt;
    private Long reversedFen;
    private String snapshotHash;
    private Date createTime;
    private Date updateTime;

    public BigDecimal getPaidAmount() { return paid(paidFen); }
    public BigDecimal getOriginalAmount() { return paid(originalAmountFen); }
    public BigDecimal getDiscountAmount() { return paid(discountFen); }
    public BigDecimal getPointDeductAmount() { return paid(pointDeductFen); }
    public BigDecimal getTaxAmount() { return paid(taxFen); }
    public BigDecimal getPaymentFeeAmount() { return paid(paymentFeeFen); }
    public BigDecimal getChannelCommissionAmount() { return paid(channelCommissionFen); }
    public BigDecimal getNetAmount() { return paid(netFen); }
    public BigDecimal getReversedAmount() { return paid(reversedFen); }

    private static BigDecimal paid(Long fen) {
        return fen == null ? null : BigDecimal.valueOf(fen, 2);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getOrderType() { return orderType; }
    public void setOrderType(Integer orderType) { this.orderType = orderType; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getAllocationKey() { return allocationKey; }
    public void setAllocationKey(String allocationKey) { this.allocationKey = allocationKey; }

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }

    public String getRuleKey() { return ruleKey; }
    public void setRuleKey(String ruleKey) { this.ruleKey = ruleKey; }

    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }

    public Integer getScenario() { return scenario; }
    public void setScenario(Integer scenario) { this.scenario = scenario; }

    public Long getAttributionId() { return attributionId; }
    public void setAttributionId(Long attributionId) { this.attributionId = attributionId; }

    public Long getSharerMemberId() { return sharerMemberId; }
    public void setSharerMemberId(Long sharerMemberId) { this.sharerMemberId = sharerMemberId; }

    public String getCampaignKey() { return campaignKey; }
    public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }

    public Long getAuthorMemberId() { return authorMemberId; }
    public void setAuthorMemberId(Long authorMemberId) { this.authorMemberId = authorMemberId; }

    public String getContractRef() { return contractRef; }
    public void setContractRef(String contractRef) { this.contractRef = contractRef; }

    public Integer getOwnerType() { return ownerType; }
    public void setOwnerType(Integer ownerType) { this.ownerType = ownerType; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Long getPaidFen() { return paidFen; }
    public void setPaidFen(Long paidFen) { this.paidFen = paidFen; }

    public String getPaymentFactHash() { return paymentFactHash; }
    public void setPaymentFactHash(String paymentFactHash) { this.paymentFactHash = paymentFactHash; }

    public Long getOriginalAmountFen() { return originalAmountFen; }
    public void setOriginalAmountFen(Long originalAmountFen) { this.originalAmountFen = originalAmountFen; }

    public Long getDiscountFen() { return discountFen; }
    public void setDiscountFen(Long discountFen) { this.discountFen = discountFen; }

    public Long getPointDeductFen() { return pointDeductFen; }
    public void setPointDeductFen(Long pointDeductFen) { this.pointDeductFen = pointDeductFen; }

    public Long getTaxFen() { return taxFen; }
    public void setTaxFen(Long taxFen) { this.taxFen = taxFen; }

    public Long getPaymentFeeFen() { return paymentFeeFen; }
    public void setPaymentFeeFen(Long paymentFeeFen) { this.paymentFeeFen = paymentFeeFen; }

    public Long getChannelCommissionFen() { return channelCommissionFen; }
    public void setChannelCommissionFen(Long channelCommissionFen) { this.channelCommissionFen = channelCommissionFen; }

    public String getFeeBasisRef() { return feeBasisRef; }
    public void setFeeBasisRef(String feeBasisRef) { this.feeBasisRef = feeBasisRef; }

    public Long getNetFen() { return netFen; }
    public void setNetFen(Long netFen) { this.netFen = netFen; }

    public Integer getReferralFundingSide() { return referralFundingSide; }
    public void setReferralFundingSide(Integer referralFundingSide) { this.referralFundingSide = referralFundingSide; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Date getSettleableAt() { return settleableAt; }
    public void setSettleableAt(Date settleableAt) { this.settleableAt = settleableAt; }

    public Long getReversedFen() { return reversedFen; }
    public void setReversedFen(Long reversedFen) { this.reversedFen = reversedFen; }

    public String getSnapshotHash() { return snapshotHash; }
    public void setSnapshotHash(String snapshotHash) { this.snapshotHash = snapshotHash; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
