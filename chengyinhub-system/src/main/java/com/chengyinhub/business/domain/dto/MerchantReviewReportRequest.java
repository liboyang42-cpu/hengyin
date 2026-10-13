package com.chengyinhub.business.domain.dto;

public class MerchantReviewReportRequest {
    private Long reviewId;
    /** 公开举报必填；商家管理举报由服务端当前主体覆盖。 */
    private Long merchantMemberId;
    private String reason;
    private Integer expectedVersion;
    private String requestId;

    public Long getReviewId() { return reviewId; }
    public void setReviewId(Long reviewId) { this.reviewId = reviewId; }
    public Long getMerchantMemberId() { return merchantMemberId; }
    public void setMerchantMemberId(Long merchantMemberId) { this.merchantMemberId = merchantMemberId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
