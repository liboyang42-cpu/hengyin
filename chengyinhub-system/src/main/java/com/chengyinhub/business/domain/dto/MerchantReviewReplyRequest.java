package com.chengyinhub.business.domain.dto;

public class MerchantReviewReplyRequest {
    private Long reviewId;
    private String content;
    private Integer expectedVersion;
    private String requestId;

    public Long getReviewId() { return reviewId; }
    public void setReviewId(Long reviewId) { this.reviewId = reviewId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
