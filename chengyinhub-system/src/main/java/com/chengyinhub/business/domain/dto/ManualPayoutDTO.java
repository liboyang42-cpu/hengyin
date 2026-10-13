package com.chengyinhub.business.domain.dto;

/**
 * §6.1 退款人工兜底处理入参。
 * method: original_retry(原路重试) / offline_transfer(线下转账) /
 *         wx_abnormal_change(异常退款到零钱) / wx_abnormal_bankcard(异常退款到银行卡)
 */
public class ManualPayoutDTO {
    private Long refundId;
    private String method;
    private String receiptUrl;
    private String remark;
    private String leaseToken;

    public Long getRefundId() { return refundId; }
    public void setRefundId(Long refundId) { this.refundId = refundId; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getReceiptUrl() { return receiptUrl; }
    public void setReceiptUrl(String receiptUrl) { this.receiptUrl = receiptUrl; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String leaseToken) { this.leaseToken = leaseToken; }
}
