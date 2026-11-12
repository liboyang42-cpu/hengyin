package com.chengyinhub.business.domain.dto;

/** 回执回读：二选一，receiptNo 优先；两者都为空拒绝。 */
public class ChannelReceiptRequest {

    private String receiptNo;
    private String requestId;

    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
