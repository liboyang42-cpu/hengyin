package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 应付付款批次。确认人与付款登记人必须是不同的两个人。
 *
 * <p><b>一期一批次一张发票</b>（裁决表 §7.1 E4 / 审计 2 P0-9「唯一 (club_id, topic_id)」）：
 * 批次自报它属于哪一期，绝不按俱乐部把多期的钱并进同一张付款单。
 *
 * <p><b>付款轴与发票轴是两条独立的轴</b>（照抄 merchant_payable_batch）：
 * {@code batchStatus} 管钱走没走，{@code invoiceStatus} 管票开没开。混成一条就表达不了
 * 「已打款但未开票」和「已开票需红冲」这两个真实状态。
 */
public class ClubPayableBatch {
    /** 契约（审计 2 对公应付合同表）写的是 PENDING/CONFIRMED/PAID，与 merchant_payable_batch 同名。 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_PAID = "PAID";

    public static final String INVOICE_NONE = "NONE";
    public static final String INVOICE_ISSUED = "ISSUED";
    public static final String INVOICE_RED = "RED";

    private Long id;
    private String batchNo;
    private Long clubId;
    private Long topicId;
    private BigDecimal totalAmount;
    private String batchStatus;
    private String invoiceStatus;
    private String invoiceNo;
    private String redInvoiceNo;
    private Long confirmedBy;
    private Date confirmedAt;
    private Long paidBy;
    private Date paidAt;
    private String paymentReference;
    private Date createdAt;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getBatchNo() { return batchNo; } public void setBatchNo(String v) { batchNo = v; }
    public Long getClubId() { return clubId; } public void setClubId(Long v) { clubId = v; }
    public Long getTopicId() { return topicId; } public void setTopicId(Long v) { topicId = v; }
    public String getInvoiceStatus() { return invoiceStatus; } public void setInvoiceStatus(String v) { invoiceStatus = v; }
    public String getInvoiceNo() { return invoiceNo; } public void setInvoiceNo(String v) { invoiceNo = v; }
    public String getRedInvoiceNo() { return redInvoiceNo; } public void setRedInvoiceNo(String v) { redInvoiceNo = v; }
    public BigDecimal getTotalAmount() { return totalAmount; } public void setTotalAmount(BigDecimal v) { totalAmount = v; }
    public String getBatchStatus() { return batchStatus; } public void setBatchStatus(String v) { batchStatus = v; }
    public Long getConfirmedBy() { return confirmedBy; } public void setConfirmedBy(Long v) { confirmedBy = v; }
    public Date getConfirmedAt() { return confirmedAt; } public void setConfirmedAt(Date v) { confirmedAt = v; }
    public Long getPaidBy() { return paidBy; } public void setPaidBy(Long v) { paidBy = v; }
    public Date getPaidAt() { return paidAt; } public void setPaidAt(Date v) { paidAt = v; }
    public String getPaymentReference() { return paymentReference; } public void setPaymentReference(String v) { paymentReference = v; }
    public Date getCreatedAt() { return createdAt; } public void setCreatedAt(Date v) { createdAt = v; }
}
