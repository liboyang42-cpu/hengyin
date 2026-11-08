package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;

/**
 * 订单详情里的退款/权益只读投影（U07-04/U07-05）。
 *
 * <p>金额只从既有 cms_registration 与 refund_application 事实汇总，不构成第二套账本、不改变任何状态。
 * 无法证明的值保持 null 并用 {@link #amountsComplete} 标出，绝不用 0 冒充“已核对”。
 */
public class RegistrationSettlementSummaryVO {

    /** 本单应付快照（未支付/未报价为 null）。 */
    private BigDecimal paidAmount;
    /** 同一父订单（含本单）曾经支付过的应付合计；无法汇总时为 null。 */
    private BigDecimal orderPaidAmount;
    /** 已受理退款合计：status=0 待审 + status=1 已批准（含处理中与完成）。 */
    private BigDecimal refundRequestedAmount;
    /** status=0，等待审核。 */
    private BigDecimal refundPendingAmount;
    /** status=1 且打款未落入终态（处理中 / 待人工 / 人工已退待复核）。 */
    private BigDecimal refundProcessingAmount;
    /** status=1 且 payout_status=1 或 4。 */
    private BigDecimal refundCompletedAmount;
    /** false = 退款历史里有金额缺失，前端不得把合计展示为确定值。 */
    private Boolean amountsComplete;

    /** 同父订单票数（order_num 求和）；单票订单为 1。 */
    private Integer ticketCount;
    private Integer paidTicketCount;
    private Integer refundedTicketCount;
    private Integer activeTicketCount;

    /** 本单权益计数（仅 ③ 自由探索票有权益行；其他购买类型保持 null 而不是 0）。 */
    private Integer entitlementTotal;
    private Integer entitlementPending;
    private Integer entitlementRedeemed;
    private Integer entitlementInvalid;

    /** MANUAL_REVIEW / UNDER_REVIEW / REJECTED / PAYOUT_PENDING / MANUAL_PAYOUT / COMPLETED / NONE。 */
    private String refundStage;
    /** 人工退款工单状态原文；null = 没有人工工单。 */
    private String manualRefundStatus;

    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
    public BigDecimal getOrderPaidAmount() { return orderPaidAmount; }
    public void setOrderPaidAmount(BigDecimal orderPaidAmount) { this.orderPaidAmount = orderPaidAmount; }
    public BigDecimal getRefundRequestedAmount() { return refundRequestedAmount; }
    public void setRefundRequestedAmount(BigDecimal refundRequestedAmount) { this.refundRequestedAmount = refundRequestedAmount; }
    public BigDecimal getRefundPendingAmount() { return refundPendingAmount; }
    public void setRefundPendingAmount(BigDecimal refundPendingAmount) { this.refundPendingAmount = refundPendingAmount; }
    public BigDecimal getRefundProcessingAmount() { return refundProcessingAmount; }
    public void setRefundProcessingAmount(BigDecimal refundProcessingAmount) { this.refundProcessingAmount = refundProcessingAmount; }
    public BigDecimal getRefundCompletedAmount() { return refundCompletedAmount; }
    public void setRefundCompletedAmount(BigDecimal refundCompletedAmount) { this.refundCompletedAmount = refundCompletedAmount; }
    public Boolean getAmountsComplete() { return amountsComplete; }
    public void setAmountsComplete(Boolean amountsComplete) { this.amountsComplete = amountsComplete; }
    public Integer getTicketCount() { return ticketCount; }
    public void setTicketCount(Integer ticketCount) { this.ticketCount = ticketCount; }
    public Integer getPaidTicketCount() { return paidTicketCount; }
    public void setPaidTicketCount(Integer paidTicketCount) { this.paidTicketCount = paidTicketCount; }
    public Integer getRefundedTicketCount() { return refundedTicketCount; }
    public void setRefundedTicketCount(Integer refundedTicketCount) { this.refundedTicketCount = refundedTicketCount; }
    public Integer getActiveTicketCount() { return activeTicketCount; }
    public void setActiveTicketCount(Integer activeTicketCount) { this.activeTicketCount = activeTicketCount; }
    public Integer getEntitlementTotal() { return entitlementTotal; }
    public void setEntitlementTotal(Integer entitlementTotal) { this.entitlementTotal = entitlementTotal; }
    public Integer getEntitlementPending() { return entitlementPending; }
    public void setEntitlementPending(Integer entitlementPending) { this.entitlementPending = entitlementPending; }
    public Integer getEntitlementRedeemed() { return entitlementRedeemed; }
    public void setEntitlementRedeemed(Integer entitlementRedeemed) { this.entitlementRedeemed = entitlementRedeemed; }
    public Integer getEntitlementInvalid() { return entitlementInvalid; }
    public void setEntitlementInvalid(Integer entitlementInvalid) { this.entitlementInvalid = entitlementInvalid; }
    public String getRefundStage() { return refundStage; }
    public void setRefundStage(String refundStage) { this.refundStage = refundStage; }
    public String getManualRefundStatus() { return manualRefundStatus; }
    public void setManualRefundStatus(String manualRefundStatus) { this.manualRefundStatus = manualRefundStatus; }
}
