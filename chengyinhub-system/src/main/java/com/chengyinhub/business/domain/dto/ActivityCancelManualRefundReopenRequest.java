package com.chengyinhub.business.domain.dto;

/** 第二名退款审核员撤销 NO_REFUND 判定并要求重新评估。 */
public class ActivityCancelManualRefundReopenRequest {
    private Long caseId;
    private Long expectedVersion;
    private String reason;
    private String requestId;

    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
