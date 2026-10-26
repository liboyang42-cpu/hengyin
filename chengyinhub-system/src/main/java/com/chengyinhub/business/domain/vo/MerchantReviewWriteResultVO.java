package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public class MerchantReviewWriteResultVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reviewId;
    private String status;
    private Integer version;
    private boolean replayed;
    private Long auditTaskId;

    public Long getReviewId() { return reviewId; }
    public void setReviewId(Long reviewId) { this.reviewId = reviewId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public boolean isReplayed() { return replayed; }
    public void setReplayed(boolean replayed) { this.replayed = replayed; }
    public Long getAuditTaskId() { return auditTaskId; }
    public void setAuditTaskId(Long auditTaskId) { this.auditTaskId = auditTaskId; }
}
