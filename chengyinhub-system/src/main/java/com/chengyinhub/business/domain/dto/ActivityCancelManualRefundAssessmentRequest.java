package com.chengyinhub.business.domain.dto;

/** 后台对活动取消人工退款 case 的评估请求；actor 只取后台登录态。 */
public class ActivityCancelManualRefundAssessmentRequest {
    private Long caseId;
    private Long expectedVersion;
    private String decision;
    private Long assessedRefundAmountFen;
    private String note;
    private String requestId;

    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public Long getAssessedRefundAmountFen() { return assessedRefundAmountFen; }
    public void setAssessedRefundAmountFen(Long assessedRefundAmountFen) { this.assessedRefundAmountFen = assessedRefundAmountFen; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
