package com.chengyinhub.business.domain;

import java.util.Date;

/** 评价举报的不可变请求回执；平台终态仍以 audit_task/audit_record 为准。 */
public class MerchantReviewReport {
    private Long id;
    private Long reviewId;
    private Long merchantRowId;
    private Long merchantOwnerMemberId;
    private Long reporterMemberId;
    private String reporterRoleCode;
    private String reasonText;
    private String requestId;
    private String requestFingerprint;
    private Long auditTaskId;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReviewId() { return reviewId; }
    public void setReviewId(Long reviewId) { this.reviewId = reviewId; }
    public Long getMerchantRowId() { return merchantRowId; }
    public void setMerchantRowId(Long merchantRowId) { this.merchantRowId = merchantRowId; }
    public Long getMerchantOwnerMemberId() { return merchantOwnerMemberId; }
    public void setMerchantOwnerMemberId(Long merchantOwnerMemberId) { this.merchantOwnerMemberId = merchantOwnerMemberId; }
    public Long getReporterMemberId() { return reporterMemberId; }
    public void setReporterMemberId(Long reporterMemberId) { this.reporterMemberId = reporterMemberId; }
    public String getReporterRoleCode() { return reporterRoleCode; }
    public void setReporterRoleCode(String reporterRoleCode) { this.reporterRoleCode = reporterRoleCode; }
    public String getReasonText() { return reasonText; }
    public void setReasonText(String reasonText) { this.reasonText = reasonText; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public Long getAuditTaskId() { return auditTaskId; }
    public void setAuditTaskId(Long auditTaskId) { this.auditTaskId = auditTaskId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
