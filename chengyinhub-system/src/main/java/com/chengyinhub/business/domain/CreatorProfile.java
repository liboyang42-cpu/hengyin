package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/**
 * P3 创作者档案 creator_profile。
 */
public class CreatorProfile extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long memberId;
    private String creatorName;
    private String bio;
    private String avatarUrl;
    private Integer applyStatus;
    private Long reviewerId;
    private String reviewReason;
    private Date reviewedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getCreatorName() { return creatorName; }
    public void setCreatorName(String creatorName) { this.creatorName = creatorName; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public Integer getApplyStatus() { return applyStatus; }
    public void setApplyStatus(Integer applyStatus) { this.applyStatus = applyStatus; }
    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }
    public String getReviewReason() { return reviewReason; }
    public void setReviewReason(String reviewReason) { this.reviewReason = reviewReason; }
    public Date getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Date reviewedAt) { this.reviewedAt = reviewedAt; }
}
