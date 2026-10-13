package com.chengyinhub.business.domain.community;

import com.chengyinhub.common.core.domain.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Date;

/**
 * 广场帖文聚合根。Audience、Lifecycle 和分发资格彼此独立，不共用状态字段。
 */
public class CommunityPost extends BaseEntity {
    public static final String TYPE_MOMENT = "MOMENT";
    public static final String TYPE_ACTIVITY_RECAP = "ACTIVITY_RECAP";
    public static final String TYPE_ROUTE_DISCOVERY = "ROUTE_DISCOVERY";
    public static final String TYPE_CITY_TIP = "CITY_TIP";
    public static final String TYPE_CLUB_ANNOUNCEMENT = "CLUB_ANNOUNCEMENT";

    public static final String AUDIENCE_PUBLIC = "PUBLIC";
    public static final String AUDIENCE_FOLLOWERS = "FOLLOWERS";
    public static final String AUDIENCE_COMMUNITY = "COMMUNITY";
    public static final String AUDIENCE_PRIVATE = "PRIVATE";

    public static final String COMMENT_EVERYONE = "EVERYONE";
    public static final String COMMENT_FOLLOWERS = "FOLLOWERS";
    public static final String COMMENT_MEMBERS = "MEMBERS";
    public static final String COMMENT_MENTIONED = "MENTIONED";
    public static final String COMMENT_OFF = "OFF";

    public static final String LIFECYCLE_DRAFT = "DRAFT";
    public static final String LIFECYCLE_CHECKING = "CHECKING";
    public static final String LIFECYCLE_PUBLISHED = "PUBLISHED";
    public static final String LIFECYCLE_LIMITED = "LIMITED";
    public static final String LIFECYCLE_HIDDEN = "HIDDEN";
    public static final String LIFECYCLE_REMOVED = "REMOVED";
    public static final String LIFECYCLE_DELETED = "DELETED";

    private Long id;
    private Long authorId;
    private Long communityId;
    private String clientRequestId;
    private String postType;
    private String title;
    private String body;
    private String audience;
    private String commentPolicy;
    private Integer replyApprovalEnabled;
    private Integer slowModeSeconds;
    private String lifecycle;
    private String locationPrecision;
    private String cityCode;
    private String poiName;
    private String approximateGeohash;
    private Integer recommendationEligible;
    private Integer searchEligible;
    private Integer interactionEnabled;
    private String disclosureType;
    private String safetyLabelsJson;
    private Long guidelineVersionId;
    private Integer likeCount;
    private Integer commentCount;
    private Integer bookmarkCount;
    private Integer shareCount;
    private Date publishedAt;
    private Date editedAt;
    private Date deletedAt;
    private Integer version;

    /** 读模型字段。 */
    private String authorNickname;
    private String authorAvatar;
    private Integer viewerLiked;
    private Integer viewerBookmarked;
    private String feedSource;
    private String reasonCode;
    private Long trendingScore;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAuthorId() { return authorId; }
    public void setAuthorId(Long authorId) { this.authorId = authorId; }
    public Long getCommunityId() { return communityId; }
    public void setCommunityId(Long communityId) { this.communityId = communityId; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public String getPostType() { return postType; }
    public void setPostType(String postType) { this.postType = postType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getCommentPolicy() { return commentPolicy; }
    public void setCommentPolicy(String commentPolicy) { this.commentPolicy = commentPolicy; }
    public Integer getReplyApprovalEnabled() { return replyApprovalEnabled; }
    public void setReplyApprovalEnabled(Integer replyApprovalEnabled) { this.replyApprovalEnabled = replyApprovalEnabled; }
    public Integer getSlowModeSeconds() { return slowModeSeconds; }
    public void setSlowModeSeconds(Integer slowModeSeconds) { this.slowModeSeconds = slowModeSeconds; }
    public String getLifecycle() { return lifecycle; }
    public void setLifecycle(String lifecycle) { this.lifecycle = lifecycle; }
    public String getLocationPrecision() { return locationPrecision; }
    public void setLocationPrecision(String locationPrecision) { this.locationPrecision = locationPrecision; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public String getPoiName() { return poiName; }
    public void setPoiName(String poiName) { this.poiName = poiName; }
    public String getApproximateGeohash() { return approximateGeohash; }
    public void setApproximateGeohash(String approximateGeohash) { this.approximateGeohash = approximateGeohash; }
    public Integer getRecommendationEligible() { return recommendationEligible; }
    public void setRecommendationEligible(Integer recommendationEligible) { this.recommendationEligible = recommendationEligible; }
    public Integer getSearchEligible() { return searchEligible; }
    public void setSearchEligible(Integer searchEligible) { this.searchEligible = searchEligible; }
    public Integer getInteractionEnabled() { return interactionEnabled; }
    public void setInteractionEnabled(Integer interactionEnabled) { this.interactionEnabled = interactionEnabled; }
    public String getDisclosureType() { return disclosureType; }
    public void setDisclosureType(String disclosureType) { this.disclosureType = disclosureType; }
    public String getSafetyLabelsJson() { return safetyLabelsJson; }
    public void setSafetyLabelsJson(String safetyLabelsJson) { this.safetyLabelsJson = safetyLabelsJson; }
    public Long getGuidelineVersionId() { return guidelineVersionId; }
    public void setGuidelineVersionId(Long guidelineVersionId) { this.guidelineVersionId = guidelineVersionId; }
    public Integer getLikeCount() { return likeCount; }
    public void setLikeCount(Integer likeCount) { this.likeCount = likeCount; }
    public Integer getCommentCount() { return commentCount; }
    public void setCommentCount(Integer commentCount) { this.commentCount = commentCount; }
    public Integer getBookmarkCount() { return bookmarkCount; }
    public void setBookmarkCount(Integer bookmarkCount) { this.bookmarkCount = bookmarkCount; }
    public Integer getShareCount() { return shareCount; }
    public void setShareCount(Integer shareCount) { this.shareCount = shareCount; }
    public Date getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Date publishedAt) { this.publishedAt = publishedAt; }
    public Date getEditedAt() { return editedAt; }
    public void setEditedAt(Date editedAt) { this.editedAt = editedAt; }
    public Date getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Date deletedAt) { this.deletedAt = deletedAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getAuthorNickname() { return authorNickname; }
    public void setAuthorNickname(String authorNickname) { this.authorNickname = authorNickname; }
    public String getAuthorAvatar() { return authorAvatar; }
    public void setAuthorAvatar(String authorAvatar) { this.authorAvatar = authorAvatar; }
    public Integer getViewerLiked() { return viewerLiked; }
    public void setViewerLiked(Integer viewerLiked) { this.viewerLiked = viewerLiked; }
    public Integer getViewerBookmarked() { return viewerBookmarked; }
    public void setViewerBookmarked(Integer viewerBookmarked) { this.viewerBookmarked = viewerBookmarked; }
    public String getFeedSource() { return feedSource; }
    public void setFeedSource(String feedSource) { this.feedSource = feedSource; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    @JsonIgnore
    public Long getTrendingScore() { return trendingScore; }
    public void setTrendingScore(Long trendingScore) { this.trendingScore = trendingScore; }
}
