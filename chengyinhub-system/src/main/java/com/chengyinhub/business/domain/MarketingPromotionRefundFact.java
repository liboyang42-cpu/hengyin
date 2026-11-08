package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 退款到账事实(促销域只读账)。表 marketing_promotion_refund_fact,insert-only。
 *
 * <p><b>为什么需要这一张表</b>:退款到账是「每一笔退款一次」的事实,而"整单是否退完"是
 * <b>累计</b>属性。既有退款链支持部分退款(累计不得超过实付),所以单看一笔
 * {@code refundedFen} 无法判断整单是否已退 —— 两次 50% 都不是全额,合起来才是。
 *
 * <p><b>不重复记钱</b>:这里只存「已到账的退款金额」用于<b>判断撤销条件</b>,
 * 不参与应付、结算、冲销;真正的资金冲销仍在 {@code settlement_detail} 按比例口径。
 */
public class MarketingPromotionRefundFact {

    /** 1 = cms_registration。 */
    public static final int ORDER_REGISTRATION = 1;

    private Long id;
    private Integer orderType;
    /** 促销成交快照所在的报名单 id(组合/多票按 parent_order_no 解析,可能不是被退那张)。 */
    private Long orderId;
    /** 实际被退款的报名单 id:子单退款时与 orderId 不同,保留以备审计。 */
    private Long refundRegistrationId;
    private Long refundApplicationId;
    private String outRefundNo;
    /** 本次实退金额(分),来自已校验的渠道退款事实。 */
    private Long refundedFen;
    /** 原支付总额(分),同一单各事实应一致。 */
    private Long totalFen;
    private String idempotencyKey;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getOrderType() { return orderType; }
    public void setOrderType(Integer orderType) { this.orderType = orderType; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getRefundRegistrationId() { return refundRegistrationId; }
    public void setRefundRegistrationId(Long refundRegistrationId) { this.refundRegistrationId = refundRegistrationId; }

    public Long getRefundApplicationId() { return refundApplicationId; }
    public void setRefundApplicationId(Long refundApplicationId) { this.refundApplicationId = refundApplicationId; }

    public String getOutRefundNo() { return outRefundNo; }
    public void setOutRefundNo(String outRefundNo) { this.outRefundNo = outRefundNo; }

    public Long getRefundedFen() { return refundedFen; }
    public void setRefundedFen(Long refundedFen) { this.refundedFen = refundedFen; }

    public Long getTotalFen() { return totalFen; }
    public void setTotalFen(Long totalFen) { this.totalFen = totalFen; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
