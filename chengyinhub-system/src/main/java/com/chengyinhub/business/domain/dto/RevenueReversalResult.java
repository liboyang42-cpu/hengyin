package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 反向分录结果。
 *
 * <p>{@link #lines} 只含<b>本事件</b>产生的分录，绝不把历史退款一起回吐 ——
 * 否则重放同一退款事件会看起来像"又冲了一次"。
 */
public class RevenueReversalResult {

    private Long allocationId;
    private Integer orderType;
    private Long orderId;
    private Long refundId;
    private String eventRef;
    private BigDecimal eventRefundAmount;
    private BigDecimal reversedAmount;
    private Integer status;
    private boolean replayed;
    private List<Line> lines;

    public static class Line {
        private String role;
        private Long recipientMemberId;
        private BigDecimal amount;
        private Long amountFen;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public Long getRecipientMemberId() { return recipientMemberId; }
        public void setRecipientMemberId(Long recipientMemberId) { this.recipientMemberId = recipientMemberId; }

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }

        public Long getAmountFen() { return amountFen; }
        public void setAmountFen(Long amountFen) { this.amountFen = amountFen; }
    }

    public Long getAllocationId() { return allocationId; }
    public void setAllocationId(Long allocationId) { this.allocationId = allocationId; }

    public Integer getOrderType() { return orderType; }
    public void setOrderType(Integer orderType) { this.orderType = orderType; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getRefundId() { return refundId; }
    public void setRefundId(Long refundId) { this.refundId = refundId; }

    public String getEventRef() { return eventRef; }
    public void setEventRef(String eventRef) { this.eventRef = eventRef; }

    public BigDecimal getEventRefundAmount() { return eventRefundAmount; }
    public void setEventRefundAmount(BigDecimal eventRefundAmount) { this.eventRefundAmount = eventRefundAmount; }

    public BigDecimal getReversedAmount() { return reversedAmount; }
    public void setReversedAmount(BigDecimal reversedAmount) { this.reversedAmount = reversedAmount; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public boolean isReplayed() { return replayed; }
    public void setReplayed(boolean replayed) { this.replayed = replayed; }

    public List<Line> getLines() { return lines; }
    public void setLines(List<Line> lines) { this.lines = lines; }
}
