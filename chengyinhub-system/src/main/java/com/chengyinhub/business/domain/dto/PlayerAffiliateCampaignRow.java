package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 玩家自己作为分享人的 campaign 成交聚合只读行。
 *
 * <p>全部计数来自 {@code marketing_attribution} 与 {@code cms_registration} 的真实行：
 * {@code paidOrders} = payment_status 已支付；{@code refundedOrders} = payment_status 已退款；
 * {@code heldOrders} = 已支付但还没有 {@code marketing_revenue_allocation} 行的单
 * (费用依据未知或投递待处理)，这是"暂缓"而不是"可提现"。
 */
public class PlayerAffiliateCampaignRow {

    private String campaignKey;
    private Integer targetType;
    private Long targetId;
    private String targetTitle;
    private long attributedOrders;
    private long paidOrders;
    private BigDecimal paidAmount;
    private long refundedOrders;
    private BigDecimal refundedAmount;
    private long heldOrders;

    public String getCampaignKey() { return campaignKey; }
    public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }

    public Integer getTargetType() { return targetType; }
    public void setTargetType(Integer targetType) { this.targetType = targetType; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }

    public String getTargetTitle() { return targetTitle; }
    public void setTargetTitle(String targetTitle) { this.targetTitle = targetTitle; }

    public long getAttributedOrders() { return attributedOrders; }
    public void setAttributedOrders(long attributedOrders) { this.attributedOrders = attributedOrders; }

    public long getPaidOrders() { return paidOrders; }
    public void setPaidOrders(long paidOrders) { this.paidOrders = paidOrders; }

    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }

    public long getRefundedOrders() { return refundedOrders; }
    public void setRefundedOrders(long refundedOrders) { this.refundedOrders = refundedOrders; }

    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }

    public long getHeldOrders() { return heldOrders; }
    public void setHeldOrders(long heldOrders) { this.heldOrders = heldOrders; }
}
