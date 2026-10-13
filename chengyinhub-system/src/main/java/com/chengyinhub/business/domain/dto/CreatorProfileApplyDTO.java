package com.chengyinhub.business.domain.dto;

/**
 * P3 创作者申请请求。
 */
public class CreatorProfileApplyDTO {
    private String creatorName;
    private String bio;
    private String avatarUrl;

    public String getCreatorName() { return creatorName; }
    public void setCreatorName(String creatorName) { this.creatorName = creatorName; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
}
