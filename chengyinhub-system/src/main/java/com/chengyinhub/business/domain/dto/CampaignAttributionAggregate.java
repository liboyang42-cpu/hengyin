package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 推广来源(campaign)成交聚合视图。给 M6 视频悬赏等调用方读"我这条 campaign 带来了多少真实成交"。
 *
 * <p><b>只有服务端已绑定凭据才算数</b>:绑定 = 存在一条由服务端签发的
 * {@code marketing_referral_credential}(sharer=调用者本人 + 同一 campaignKey + 同一 target)。
 * 没有绑定就返回 {@code bound=false} 且计数全 0 —— 绝不因为客户端传了同名字符串就把别的单算进来。
 *
 * <p><b>不含他人身份</b>:不返回 orderId、memberId、registrationId,只回计数与合计金额。
 * 金额取自订单自身的 {@code payable_amount} 快照,不做二次计算。
 */
public class CampaignAttributionAggregate {

    private String campaignKey;
    private Integer targetType;
    private Long targetId;
    /** 是否存在服务端签发的绑定凭据。false 时其余字段无意义。 */
    private boolean bound;
    /** 已归因订单数(含未支付)。 */
    private long attributedOrders;
    /** 已支付且未退款订单数。 */
    private long paidOrders;
    /** 已支付订单的实付合计(CNY)。 */
    private BigDecimal paidAmount;
    /** 已退款订单数。 */
    private long refundedOrders;
    /** 已退款订单的实付合计(CNY),用于从 paidAmount 里看出被冲掉多少。 */
    private BigDecimal refundedAmount;

    public String getCampaignKey() { return campaignKey; }
    public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }

    public Integer getTargetType() { return targetType; }
    public void setTargetType(Integer targetType) { this.targetType = targetType; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }

    public boolean isBound() { return bound; }
    public void setBound(boolean bound) { this.bound = bound; }

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
}
