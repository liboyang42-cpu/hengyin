package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/**
 * P3 创作者每日指标 creator_metric_daily。
 */
public class CreatorMetricDaily extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Date statDate;
    private Long creatorId;
    private Integer contentCount;
    private Long viewCount;
    private Long likeCount;
    private Long favoriteCount;
    private Long signupCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Date getStatDate() { return statDate; }
    public void setStatDate(Date statDate) { this.statDate = statDate; }
    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }
    public Integer getContentCount() { return contentCount; }
    public void setContentCount(Integer contentCount) { this.contentCount = contentCount; }
    public Long getViewCount() { return viewCount; }
    public void setViewCount(Long viewCount) { this.viewCount = viewCount; }
    public Long getLikeCount() { return likeCount; }
    public void setLikeCount(Long likeCount) { this.likeCount = likeCount; }
    public Long getFavoriteCount() { return favoriteCount; }
    public void setFavoriteCount(Long favoriteCount) { this.favoriteCount = favoriteCount; }
    public Long getSignupCount() { return signupCount; }
    public void setSignupCount(Long signupCount) { this.signupCount = signupCount; }
}
