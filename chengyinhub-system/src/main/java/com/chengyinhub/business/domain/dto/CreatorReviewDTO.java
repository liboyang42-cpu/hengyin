package com.chengyinhub.business.domain.dto;

/**
 * 创作者准入审核请求白名单。
 */
public class CreatorReviewDTO {
    private Long id;
    private Integer applyStatus;
    private String reviewReason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getApplyStatus() { return applyStatus; }
    public void setApplyStatus(Integer applyStatus) { this.applyStatus = applyStatus; }
    public String getReviewReason() { return reviewReason; }
    public void setReviewReason(String reviewReason) { this.reviewReason = reviewReason; }
}
