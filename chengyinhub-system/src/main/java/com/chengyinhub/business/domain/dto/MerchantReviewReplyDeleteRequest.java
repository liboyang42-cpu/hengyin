package com.chengyinhub.business.domain.dto;

/** 删公开回复。content 不需要 —— 删的是整条回复,不是改文案。 */
public class MerchantReviewReplyDeleteRequest {
    private Long reviewId;
    private Integer expectedVersion;
    private String requestId;

    public Long getReviewId() { return reviewId; }
    public void setReviewId(Long reviewId) { this.reviewId = reviewId; }
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
