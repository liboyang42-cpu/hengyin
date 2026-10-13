package com.chengyinhub.business.domain;

import java.util.Date;

/** 商家售后只读的下单退款政策快照。 */
public class MerchantRefundPolicySnapshot {
    private String policyCode;
    private Integer policyVersion;
    private Date refundDeadline;

    public String getPolicyCode() { return policyCode; }
    public void setPolicyCode(String policyCode) { this.policyCode = policyCode; }
    public Integer getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(Integer policyVersion) { this.policyVersion = policyVersion; }
    public Date getRefundDeadline() { return refundDeadline; }
    public void setRefundDeadline(Date refundDeadline) { this.refundDeadline = refundDeadline; }
}
