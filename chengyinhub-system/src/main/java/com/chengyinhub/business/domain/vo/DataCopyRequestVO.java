package com.chengyinhub.business.domain.vo;

/**
 * ONB-10 member-facing shape of a data-copy request (the iOS app reads exactly these four fields).
 * Dates are pre-formatted strings so the wire format does not depend on the JSON serializer:
 * {@code createdAt} "yyyy-MM-dd HH:mm:ss", {@code dueBy} "yyyy-MM-dd".
 */
public class DataCopyRequestVO {
    private Long requestId;
    private String status;
    private String createdAt;
    private String dueBy;

    public DataCopyRequestVO() {}

    public DataCopyRequestVO(Long requestId, String status, String createdAt, String dueBy) {
        this.requestId = requestId;
        this.status = status;
        this.createdAt = createdAt;
        this.dueBy = dueBy;
    }

    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getDueBy() { return dueBy; }
    public void setDueBy(String dueBy) { this.dueBy = dueBy; }
}
