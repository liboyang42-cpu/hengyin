package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 视频悬赏 campaign 的成交归因投影(给作者中心与运营审核读)。
 *
 * <p><b>为什么这里要保留 null 而不是 0</b>:上游(归因域)在"没有服务端绑定凭据"时
 * 会返回计数 0 与金额 0。但如果把 0 直接透出去,界面就会显示"确认成交 0 单",
 * 这是<b>错误的结论</b> —— 真实含义是"未绑定、不知道"。因此未绑定时本视图把
 * 计数与金额全部置为 {@code null},并把 {@code status} 标为 {@code UNBOUND},
 * 强制调用方走"未确认"分支。
 *
 * <p><b>不泄漏他人身份</b>:只含计数与合计金额,不含 orderId / memberId / registrationId。
 *
 * @author chengyinhub
 */
public class VideoCampaignAttributionView
{
    /** 服务端已绑定凭据,数字可信。 */
    public static final String STATUS_BOUND = "BOUND";
    /** 未绑定:数字未知,不得显示为 0 成交。 */
    public static final String STATUS_UNBOUND = "UNBOUND";
    /** 活动未配置归属 owner,无法确定该向谁聚合。 */
    public static final String STATUS_OWNER_NOT_SET = "OWNER_NOT_SET";

    private String campaignKey;
    private Integer targetType;
    private Long targetId;
    /** 绑定状态:{@link #STATUS_BOUND} / {@link #STATUS_UNBOUND} / {@link #STATUS_OWNER_NOT_SET}。 */
    private String status;
    /** 等价于 {@code status == BOUND};客户端应以此决定是否展示数字。 */
    private boolean confirmed;
    private Long attributedOrders;
    private Long paidOrders;
    private BigDecimal paidAmount;
    private Long refundedOrders;
    private BigDecimal refundedAmount;
    /** 币种口径:上游按订单实付快照(CNY)聚合,这里显式写出来,免得被误当活动币种。 */
    private String amountCurrency;
    private String reasonCode;

    /** 未绑定:所有数字为 null,状态为 UNBOUND。 */
    public static VideoCampaignAttributionView unbound(String campaignKey, Integer targetType, Long targetId) {
        VideoCampaignAttributionView view = base(campaignKey, targetType, targetId);
        view.status = STATUS_UNBOUND;
        view.confirmed = false;
        view.reasonCode = "NOT_BOUND";
        return view;
    }

    /** 活动未配置归属 owner:与"未绑定"区分开,因为这是配置缺口而不是凭据缺失。 */
    public static VideoCampaignAttributionView ownerNotSet(String campaignKey, Integer targetType, Long targetId) {
        VideoCampaignAttributionView view = base(campaignKey, targetType, targetId);
        view.status = STATUS_OWNER_NOT_SET;
        view.confirmed = false;
        view.reasonCode = "CAMPAIGN_OWNER_NOT_SET";
        return view;
    }

    private static VideoCampaignAttributionView base(String campaignKey, Integer targetType, Long targetId) {
        VideoCampaignAttributionView view = new VideoCampaignAttributionView();
        view.campaignKey = campaignKey;
        view.targetType = targetType;
        view.targetId = targetId;
        return view;
    }

    /** 从上游聚合结果构造;上游 {@code bound=false} 时按未绑定处理。 */
    public static VideoCampaignAttributionView from(CampaignAttributionAggregate aggregate) {
        if (aggregate == null || !aggregate.isBound()) {
            return aggregate == null
                    ? unbound(null, null, null)
                    : unbound(aggregate.getCampaignKey(), aggregate.getTargetType(), aggregate.getTargetId());
        }
        VideoCampaignAttributionView view = base(
                aggregate.getCampaignKey(), aggregate.getTargetType(), aggregate.getTargetId());
        view.status = STATUS_BOUND;
        view.confirmed = true;
        view.attributedOrders = aggregate.getAttributedOrders();
        view.paidOrders = aggregate.getPaidOrders();
        view.paidAmount = aggregate.getPaidAmount();
        view.refundedOrders = aggregate.getRefundedOrders();
        view.refundedAmount = aggregate.getRefundedAmount();
        view.amountCurrency = "CNY";
        return view;
    }

    public String getCampaignKey() { return campaignKey; }
    public Integer getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public String getStatus() { return status; }
    public boolean isConfirmed() { return confirmed; }
    public Long getAttributedOrders() { return attributedOrders; }
    public Long getPaidOrders() { return paidOrders; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public Long getRefundedOrders() { return refundedOrders; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public String getAmountCurrency() { return amountCurrency; }
    public String getReasonCode() { return reasonCode; }
}
