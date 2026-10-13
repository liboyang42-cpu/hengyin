package com.chengyinhub.business.domain.dto;

public class RefundReviewDTO {
    private Long id;
    private Integer status;
    private String reviewReason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getReviewReason() { return reviewReason; }
    public void setReviewReason(String reviewReason) { this.reviewReason = reviewReason; }
}
