package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 对外评价投影；刻意不含 authorMemberId、报名/核销内部 ID。 */
public class MerchantReviewVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private Integer rating;
    private String content;
    private List<String> imageUrls = new ArrayList<>();
    private String authorNickname;
    private String authorAvatar;
    private boolean verifiedRedemption;
    private String status;
    private String merchantReply;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date repliedAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    private Integer version;
    private boolean canReply;
    private boolean canEditReply;
    private boolean canReport;
    /** 仅管理态有意义:平台复审中(待复核或有未结案举报)。公开态恒 false。 */
    private boolean underPlatformReview;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }
    public String getAuthorNickname() { return authorNickname; }
    public void setAuthorNickname(String authorNickname) { this.authorNickname = authorNickname; }
    public String getAuthorAvatar() { return authorAvatar; }
    public void setAuthorAvatar(String authorAvatar) { this.authorAvatar = authorAvatar; }
    public boolean isVerifiedRedemption() { return verifiedRedemption; }
    public void setVerifiedRedemption(boolean verifiedRedemption) { this.verifiedRedemption = verifiedRedemption; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMerchantReply() { return merchantReply; }
    public void setMerchantReply(String merchantReply) { this.merchantReply = merchantReply; }
    public Date getRepliedAt() { return repliedAt; }
    public void setRepliedAt(Date repliedAt) { this.repliedAt = repliedAt; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public boolean isCanReply() { return canReply; }
    public void setCanReply(boolean canReply) { this.canReply = canReply; }
    public boolean isCanEditReply() { return canEditReply; }
    public void setCanEditReply(boolean canEditReply) { this.canEditReply = canEditReply; }
    public boolean isCanReport() { return canReport; }
    public void setCanReport(boolean canReport) { this.canReport = canReport; }
    public boolean isUnderPlatformReview() { return underPlatformReview; }
    public void setUnderPlatformReview(boolean underPlatformReview) { this.underPlatformReview = underPlatformReview; }
}
