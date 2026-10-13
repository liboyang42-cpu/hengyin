package com.chengyinhub.business.domain;

import java.util.Date;

/** 已核销消费对商家的评价事实；原评创建后不可修改。 */
public class MerchantReview {
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STATUS_VISIBLE = "VISIBLE";
    public static final String STATUS_HIDDEN = "HIDDEN";

    private Long id;
    private Long merchantRowId;
    private Long merchantOwnerMemberId;
    private Long registrationId;
    private Long redemptionId;
    private Long authorMemberId;
    private Integer rating;
    private String content;
    private String imagesJson;
    private String status;
    private String replyContent;
    private Long replyActorMemberId;
    private String replyActorRoleCode;
    private String replyRequestId;
    private String replyFingerprint;
    private Date repliedAt;
    private String createRequestId;
    private String createFingerprint;
    private String moderationReason;
    private Date moderatedAt;
    private Integer version;
    private Date createTime;
    private Date updateTime;

    /** 仅查询投影；不落入评价表，也不向 API 暴露会员 ID。 */
    private String authorNickname;
    private String authorAvatar;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantRowId() { return merchantRowId; }
    public void setMerchantRowId(Long merchantRowId) { this.merchantRowId = merchantRowId; }
    public Long getMerchantOwnerMemberId() { return merchantOwnerMemberId; }
    public void setMerchantOwnerMemberId(Long merchantOwnerMemberId) { this.merchantOwnerMemberId = merchantOwnerMemberId; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getRedemptionId() { return redemptionId; }
    public void setRedemptionId(Long redemptionId) { this.redemptionId = redemptionId; }
    public Long getAuthorMemberId() { return authorMemberId; }
    public void setAuthorMemberId(Long authorMemberId) { this.authorMemberId = authorMemberId; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getImagesJson() { return imagesJson; }
    public void setImagesJson(String imagesJson) { this.imagesJson = imagesJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReplyContent() { return replyContent; }
    public void setReplyContent(String replyContent) { this.replyContent = replyContent; }
    public Long getReplyActorMemberId() { return replyActorMemberId; }
    public void setReplyActorMemberId(Long replyActorMemberId) { this.replyActorMemberId = replyActorMemberId; }
    public String getReplyActorRoleCode() { return replyActorRoleCode; }
    public void setReplyActorRoleCode(String replyActorRoleCode) { this.replyActorRoleCode = replyActorRoleCode; }
    public String getReplyRequestId() { return replyRequestId; }
    public void setReplyRequestId(String replyRequestId) { this.replyRequestId = replyRequestId; }
    public String getReplyFingerprint() { return replyFingerprint; }
    public void setReplyFingerprint(String replyFingerprint) { this.replyFingerprint = replyFingerprint; }
    public Date getRepliedAt() { return repliedAt; }
    public void setRepliedAt(Date repliedAt) { this.repliedAt = repliedAt; }
    public String getCreateRequestId() { return createRequestId; }
    public void setCreateRequestId(String createRequestId) { this.createRequestId = createRequestId; }
    public String getCreateFingerprint() { return createFingerprint; }
    public void setCreateFingerprint(String createFingerprint) { this.createFingerprint = createFingerprint; }
    public String getModerationReason() { return moderationReason; }
    public void setModerationReason(String moderationReason) { this.moderationReason = moderationReason; }
    public Date getModeratedAt() { return moderatedAt; }
    public void setModeratedAt(Date moderatedAt) { this.moderatedAt = moderatedAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public String getAuthorNickname() { return authorNickname; }
    public void setAuthorNickname(String authorNickname) { this.authorNickname = authorNickname; }
    public String getAuthorAvatar() { return authorAvatar; }
    public void setAuthorAvatar(String authorAvatar) { this.authorAvatar = authorAvatar; }
}
