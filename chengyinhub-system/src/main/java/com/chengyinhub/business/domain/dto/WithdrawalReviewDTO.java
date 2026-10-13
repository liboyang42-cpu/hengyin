package com.chengyinhub.business.domain.dto;

public class WithdrawalReviewDTO {
    private Long id;
    private Long status;
    private String reviewReason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStatus() { return status; }
    public void setStatus(Long status) { this.status = status; }
    public String getReviewReason() { return reviewReason; }
    public void setReviewReason(String reviewReason) { this.reviewReason = reviewReason; }
}
