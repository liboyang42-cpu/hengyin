package com.chengyinhub.business.domain.dto;

/** 第二名后台操作员触发服务端重新读取凭证；复核凭证不等于支付渠道到账。 */
public class ActivityCancelManualRefundVerifyRequest {
    private Long caseId;
    private Long expectedVersion;
    private String note;
    private String requestId;

    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
