package com.chengyinhub.business.domain.vo;

import java.util.Date;

/** 对公批次的金额方向、付款、发票与冻结四条独立事实轴。 */
public class PublicTransferBatch {
    private String batchId;
    private String periodYm;
    private String amountTotal;
    private String netDirection;
    private String paymentState;
    private String invoiceState;
    private String holdState;
    private String displayState;
    private Date paidAt;
    private String payVoucherNo;

    public String getBatchId() { return batchId; }
    public void setBatchId(String value) { batchId = value; }
    public String getPeriodYm() { return periodYm; }
    public void setPeriodYm(String value) { periodYm = value; }
    public String getAmountTotal() { return amountTotal; }
    public void setAmountTotal(String value) { amountTotal = value; }
    public String getNetDirection() { return netDirection; }
    public void setNetDirection(String value) { netDirection = value; }
    public String getPaymentState() { return paymentState; }
    public void setPaymentState(String value) { paymentState = value; }
    public String getInvoiceState() { return invoiceState; }
    public void setInvoiceState(String value) { invoiceState = value; }
    public String getHoldState() { return holdState; }
    public void setHoldState(String value) { holdState = value; }
    public String getDisplayState() { return displayState; }
    public void setDisplayState(String value) { displayState = value; }
    public Date getPaidAt() { return paidAt; }
    public void setPaidAt(Date value) { paidAt = value; }
    public String getPayVoucherNo() { return payVoucherNo; }
    public void setPayVoucherNo(String value) { payVoucherNo = value; }
}
