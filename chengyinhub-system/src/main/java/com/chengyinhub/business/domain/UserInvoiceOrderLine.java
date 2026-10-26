package com.chengyinhub.business.domain;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.util.Date;

/**
 * DOC2 F7 发票申请占用的一笔订单，也用作「可开票订单」列表行。
 * activeKey=1 表示占用中；驳回/取消后置 NULL 释放，唯一键 (orderSource, orderId, activeKey) 防一单两开。
 */
public class UserInvoiceOrderLine {
    public static final String SOURCE_REGISTRATION = "registration";

    private Long id;
    private Long applicationId;
    private Long memberId;
    private String orderSource;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long issuerId;
    private String issuerName;
    public Long getIssuerId() { return issuerId; }
    public void setIssuerId(Long v) { issuerId=v; }
    public String getIssuerName() { return issuerName; }
    public void setIssuerName(String v) { issuerName=v; }
    private BigDecimal amount;
    private Integer activeKey;
    private Date createTime;
    /** 仅可开票列表回显：订单标题 / 票种 / 支付时间（来自 cms_registration 冗余列）。 */
    private String orderTitle;
    private String ticketName;
    private Date paymentTime;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getApplicationId() { return applicationId; } public void setApplicationId(Long v) { applicationId = v; }
    public Long getMemberId() { return memberId; } public void setMemberId(Long v) { memberId = v; }
    public String getOrderSource() { return orderSource; } public void setOrderSource(String v) { orderSource = v; }
    public Long getOrderId() { return orderId; } public void setOrderId(Long v) { orderId = v; }
    public BigDecimal getAmount() { return amount; } public void setAmount(BigDecimal v) { amount = v; }
    public Integer getActiveKey() { return activeKey; } public void setActiveKey(Integer v) { activeKey = v; }
    public Date getCreateTime() { return createTime; } public void setCreateTime(Date v) { createTime = v; }
    public String getOrderTitle() { return orderTitle; } public void setOrderTitle(String v) { orderTitle = v; }
    public String getTicketName() { return ticketName; } public void setTicketName(String v) { ticketName = v; }
    public Date getPaymentTime() { return paymentTime; } public void setPaymentTime(Date v) { paymentTime = v; }
}
