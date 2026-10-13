package com.chengyinhub.business.domain.dto;

/** 记录人工退款凭证；只接收专用上传返回的 object key，不接收 URL 或客户端摘要。 */
public class ActivityCancelManualRefundHandleRequest {
    private Long caseId;
    private Long expectedVersion;
    private Long actualRefundAmountFen;
    private String channel;
    private String evidenceObjectKey;
    private String note;
    private String requestId;

    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }
    public Long getActualRefundAmountFen() { return actualRefundAmountFen; }
    public void setActualRefundAmountFen(Long actualRefundAmountFen) { this.actualRefundAmountFen = actualRefundAmountFen; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getEvidenceObjectKey() { return evidenceObjectKey; }
    public void setEvidenceObjectKey(String evidenceObjectKey) { this.evidenceObjectKey = evidenceObjectKey; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
