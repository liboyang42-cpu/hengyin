package com.chengyinhub.business.domain;

import java.util.Date;

public class CommunityReportAppeal {
    private Long id;
    private Long reportId;
    private Long appellantMemberId;
    private String reason;
    private String requestId;
    private String payloadHash;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public Long getAppellantMemberId() { return appellantMemberId; }
    public void setAppellantMemberId(Long appellantMemberId) { this.appellantMemberId = appellantMemberId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
