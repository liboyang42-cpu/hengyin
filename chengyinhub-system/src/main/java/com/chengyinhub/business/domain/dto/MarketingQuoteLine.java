package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/** 一行报价的定价明细:票面单价 → 实际单价(阶梯后) → 行金额。 */
public class MarketingQuoteLine {

    private Long ticketId;
    private Integer quantity;
    private BigDecimal listUnitPrice = BigDecimal.ZERO;
    private BigDecimal unitPrice = BigDecimal.ZERO;
    private BigDecimal listAmount = BigDecimal.ZERO;
    private BigDecimal amount = BigDecimal.ZERO;

    public MarketingQuoteLine() {
    }

    public MarketingQuoteLine(Long ticketId, Integer quantity, BigDecimal listUnitPrice) {
        this.ticketId = ticketId;
        this.quantity = quantity;
        this.listUnitPrice = listUnitPrice;
    }

    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getListUnitPrice() { return listUnitPrice; }
    public void setListUnitPrice(BigDecimal listUnitPrice) { this.listUnitPrice = listUnitPrice; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal getListAmount() { return listAmount; }
    public void setListAmount(BigDecimal listAmount) { this.listAmount = listAmount; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
