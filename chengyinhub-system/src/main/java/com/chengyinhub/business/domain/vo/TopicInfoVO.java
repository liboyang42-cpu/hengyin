package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.ViewComment;
import com.chengyinhub.system.domain.SysCategory;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 公开主题详情白名单。独立于 CmsTopic，实体新增字段不会自动进入公开响应。
 */
public class TopicInfoVO {
    private Boolean canResubmitSupplyReview = false;
    public Boolean getCanResubmitSupplyReview() { return canResubmitSupplyReview; }
    public void setCanResubmitSupplyReview(Boolean value) { canResubmitSupplyReview = Boolean.TRUE.equals(value); }
    private Long id;
    private Long memberId;
    private Long clubId;
    private Long topicType;
    private Integer productType;
    private String circleThemeCode;
    private String name;
    private String subtitle;
    private String description;
    private String addressName;
    private Long totalTime;
    private BigDecimal totalMileage;
    private Long locationCount;
    private Long templateCount;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startDate;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endDate;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date merchantSignUpStartDate;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date merchantSignUpEndDate;
    private String imgUrl;
    private String imgArr;
    private String categoryIds;
    private Integer status;
    private Integer delFlag;
    private Integer userStatus;
    private Integer merchantStatus;
    private Integer openClubPool;
    private String publishMode;
    /** 1=Beta试玩期(pro主题过审先进Beta,作者转正归0) */
    private Integer betaFlag;
    private String audioUrl;
    private Integer audioDuration;
    private Integer selfPlay;
    private BigDecimal selfPlayPrice;
    private Integer selfPlayQuota;
    private Integer xpBudget;
    private String finishMedalName;
    private String finishMedalImg;
    private Long completeRewardCouponId;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date recruitDeadline;
    private Integer lifecycle;

    /**
     * 内容主体类型:PLATFORM / CLUB / MEMBER —— 招募广场卡片的「来源」。
     *
     * <p>⚠️ 无生效 CONTENT 槽时为 null(存量主题的正常状态),前端必须渲染中性态,
     * <b>不得默认成官方</b>。只出类型与公开名,不出 entityId。
     */
    private String contentPublisherType;

    /** 内容主体公开显示名;无槽时为 null,不回退到收款账号。 */
    private String contentPublisherName;

    /**
     * 主题级剩余商家名额,由各开放招商章节汇总(见 {@code TopicMerchantCapacity})。
     *
     * <p>⚠️ 前端契约:{@code null} = <b>不招商或不限名额,不要渲染「已满」</b>;
     * {@code 0} = 确实已满;{@code >0} = 还有名额。
     * 把 null 当 0 处理会把「不招商的主题」错标成「已满」。
     */
    private Integer remainingMerchantCount;

    private PublicMemberSummaryVO umsMember;
    private List<SysCategory> sysCategoryList;

    @Schema(description = "评论数")
    private Integer commentCount = 0;
    @Schema(description = "平均评分")
    private Double averageRating = 0.0;
    @Schema(description = "至少完成过一个节点的玩家数")
    private Long playerCount = 0L;
    @Schema(description = "1-5 星评分数量分布")
    private Map<Integer, Integer> ratingDistribution;
    @Schema(description = "主题下所有章节")
    private List<TopicChapterVO> chaptersList;
    @Schema(description = "主题实际章节总数（锁定章节正文不会随响应下发）")
    private Integer totalChapterCount = 0;
    @Schema(description = "当前用户可见的章节数")
    private Integer unlockedChapterCount = 0;
    @Schema(description = "是否仍有剧情章节被锁定")
    private Boolean storyLocked = false;
    @Schema(description = "评论列表")
    private List<ViewComment> commentList;
    @Schema(description = "模版列表（答案、提示已剥离）")
    private List<CmsMemberTemplate> memberTemplateList;
    @Schema(description = "阵容-合作者列表")
    private List<PublicCollaboratorVO> collaboratorsList;
    @Schema(description = "主题票务列表")
    private List<PublicTicketVO> omsTicketList;
    @Schema(description = "该主题已上架的可参加活动列表")
    private List<PublicActivitySummaryVO> activityList;
    @Schema(description = "是否报名0未报 1已报")
    private Integer isSignUp = 0;
    @Schema(description = "承接俱乐部名称")
    private String clubName;
    @Schema(description = "当前查看者是否为主题创建者")
    private Integer isOwner = 0;
    /**
     * 驳回原因(cms_topic.reason)。**仅主题创建者可见** —— 由 Controller 在 isOwner=1 时才回填,
     * 其余查看者恒为 null。审核态本身走已有字段 {@link #status}(0 待审 / 1 通过 / 2 未通过),
     * 前端不再猜 auditStatus —— 那个字段全库不存在。
     */
    private String rejectReason;
    @Schema(description = "报名商家数")
    private Integer registrationMerchantCount = 0;
    @Schema(description = "商家是否报名")
    private Integer isRegistrationMerchant = 0;
    private Integer perkSellableCapacity;
    private Integer perkCoverageStatus;
    /** 拍板 2026-09-16 #10:主题发布者是已打烊的生效商家时为 true;下单入口据此提示「商家暂停营业」。 */
    private Boolean merchantClosed = Boolean.FALSE;

    /**
     * 主题数值规则 journeyRules(已归一化 JSON 字符串,主题级一份;玩家侧只读)。
     *
     * <p>未配置 = null(不下发);配置了但作者关掉检定 = 下发 {"checkEnabled":false,...}。
     * 没有秘密字段,不需要像玩法配置那样脱敏。
     */
    private String journeyRules;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getTopicType() { return topicType; }
    public void setTopicType(Long topicType) { this.topicType = topicType; }
    public Integer getProductType() { return productType; }
    public void setProductType(Integer productType) { this.productType = productType; }
    public Boolean getMerchantClosed() { return merchantClosed; }
    public void setMerchantClosed(Boolean merchantClosed) { this.merchantClosed = merchantClosed; }
    public String getCircleThemeCode() { return circleThemeCode; }
    public void setCircleThemeCode(String circleThemeCode) { this.circleThemeCode = circleThemeCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAddressName() { return addressName; }
    public void setAddressName(String addressName) { this.addressName = addressName; }
    public Long getTotalTime() { return totalTime; }
    public void setTotalTime(Long totalTime) { this.totalTime = totalTime; }
    public BigDecimal getTotalMileage() { return totalMileage; }
    public void setTotalMileage(BigDecimal totalMileage) { this.totalMileage = totalMileage; }
    public Long getLocationCount() { return locationCount; }
    public void setLocationCount(Long locationCount) { this.locationCount = locationCount; }
    public Long getTemplateCount() { return templateCount; }
    public void setTemplateCount(Long templateCount) { this.templateCount = templateCount; }
    public Date getStartDate() { return startDate; }
    public void setStartDate(Date startDate) { this.startDate = startDate; }
    public Date getEndDate() { return endDate; }
    public void setEndDate(Date endDate) { this.endDate = endDate; }
    public Date getMerchantSignUpStartDate() { return merchantSignUpStartDate; }
    public void setMerchantSignUpStartDate(Date merchantSignUpStartDate) { this.merchantSignUpStartDate = merchantSignUpStartDate; }
    public Date getMerchantSignUpEndDate() { return merchantSignUpEndDate; }
    public void setMerchantSignUpEndDate(Date merchantSignUpEndDate) { this.merchantSignUpEndDate = merchantSignUpEndDate; }
    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
    public String getImgArr() { return imgArr; }
    public void setImgArr(String imgArr) { this.imgArr = imgArr; }
    public String getCategoryIds() { return categoryIds; }
    public void setCategoryIds(String categoryIds) { this.categoryIds = categoryIds; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getUserStatus() { return userStatus; }
    public void setUserStatus(Integer userStatus) { this.userStatus = userStatus; }
    public Integer getMerchantStatus() { return merchantStatus; }
    public void setMerchantStatus(Integer merchantStatus) { this.merchantStatus = merchantStatus; }
    public Integer getOpenClubPool() { return openClubPool; }
    public void setOpenClubPool(Integer openClubPool) { this.openClubPool = openClubPool; }
    public String getPublishMode() { return publishMode; }
    public void setPublishMode(String publishMode) { this.publishMode = publishMode; }
    public String getAudioUrl() { return audioUrl; }
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }
    public Integer getAudioDuration() { return audioDuration; }
    public void setAudioDuration(Integer audioDuration) { this.audioDuration = audioDuration; }
    public Integer getSelfPlay() { return selfPlay; }
    public void setSelfPlay(Integer selfPlay) { this.selfPlay = selfPlay; }
    public BigDecimal getSelfPlayPrice() { return selfPlayPrice; }
    public void setSelfPlayPrice(BigDecimal selfPlayPrice) { this.selfPlayPrice = selfPlayPrice; }
    public Integer getSelfPlayQuota() { return selfPlayQuota; }
    public void setSelfPlayQuota(Integer selfPlayQuota) { this.selfPlayQuota = selfPlayQuota; }
    public Integer getBetaFlag() { return betaFlag; }
    public void setBetaFlag(Integer betaFlag) { this.betaFlag = betaFlag; }
    public Integer getXpBudget() { return xpBudget; }
    public void setXpBudget(Integer xpBudget) { this.xpBudget = xpBudget; }
    public String getFinishMedalName() { return finishMedalName; }
    public void setFinishMedalName(String finishMedalName) { this.finishMedalName = finishMedalName; }
    public String getFinishMedalImg() { return finishMedalImg; }
    public void setFinishMedalImg(String finishMedalImg) { this.finishMedalImg = finishMedalImg; }
    public Long getCompleteRewardCouponId() { return completeRewardCouponId; }
    public void setCompleteRewardCouponId(Long completeRewardCouponId) { this.completeRewardCouponId = completeRewardCouponId; }
    public Date getRecruitDeadline() { return recruitDeadline; }
    public void setRecruitDeadline(Date recruitDeadline) { this.recruitDeadline = recruitDeadline; }
    public Integer getLifecycle() { return lifecycle; }
    public void setLifecycle(Integer lifecycle) { this.lifecycle = lifecycle; }
    public String getContentPublisherType() { return contentPublisherType; }
    public void setContentPublisherType(String contentPublisherType) { this.contentPublisherType = contentPublisherType; }
    public String getContentPublisherName() { return contentPublisherName; }
    public void setContentPublisherName(String contentPublisherName) { this.contentPublisherName = contentPublisherName; }
    public Integer getRemainingMerchantCount() { return remainingMerchantCount; }
    public void setRemainingMerchantCount(Integer remainingMerchantCount) { this.remainingMerchantCount = remainingMerchantCount; }
    public PublicMemberSummaryVO getUmsMember() { return umsMember; }
    public void setUmsMember(PublicMemberSummaryVO umsMember) { this.umsMember = umsMember; }
    public List<SysCategory> getSysCategoryList() { return sysCategoryList; }
    public void setSysCategoryList(List<SysCategory> sysCategoryList) { this.sysCategoryList = sysCategoryList; }
    public Integer getCommentCount() { return commentCount; }
    public void setCommentCount(Integer commentCount) { this.commentCount = commentCount; }
    public Double getAverageRating() { return averageRating; }
    public void setAverageRating(Double averageRating) { this.averageRating = averageRating; }
    public Long getPlayerCount() { return playerCount; }
    public void setPlayerCount(Long playerCount) { this.playerCount = playerCount; }
    public Map<Integer, Integer> getRatingDistribution() { return ratingDistribution; }
    public void setRatingDistribution(Map<Integer, Integer> ratingDistribution) { this.ratingDistribution = ratingDistribution; }
    public List<TopicChapterVO> getChaptersList() { return chaptersList; }
    public void setChaptersList(List<TopicChapterVO> chaptersList) { this.chaptersList = chaptersList; }
    public Integer getTotalChapterCount() { return totalChapterCount; }
    public void setTotalChapterCount(Integer totalChapterCount) { this.totalChapterCount = totalChapterCount; }
    public Integer getUnlockedChapterCount() { return unlockedChapterCount; }
    public void setUnlockedChapterCount(Integer unlockedChapterCount) { this.unlockedChapterCount = unlockedChapterCount; }
    public Boolean getStoryLocked() { return storyLocked; }
    public void setStoryLocked(Boolean storyLocked) { this.storyLocked = storyLocked; }
    public List<ViewComment> getCommentList() { return commentList; }
    public void setCommentList(List<ViewComment> commentList) { this.commentList = commentList; }
    public List<CmsMemberTemplate> getMemberTemplateList() { return memberTemplateList; }
    public void setMemberTemplateList(List<CmsMemberTemplate> memberTemplateList) { this.memberTemplateList = memberTemplateList; }
    public List<PublicCollaboratorVO> getCollaboratorsList() { return collaboratorsList; }
    public void setCollaboratorsList(List<PublicCollaboratorVO> collaboratorsList) { this.collaboratorsList = collaboratorsList; }
    public List<PublicTicketVO> getOmsTicketList() { return omsTicketList; }
    public void setOmsTicketList(List<PublicTicketVO> omsTicketList) { this.omsTicketList = omsTicketList; }
    public List<PublicActivitySummaryVO> getActivityList() { return activityList; }
    public void setActivityList(List<PublicActivitySummaryVO> activityList) { this.activityList = activityList; }
    public Integer getIsSignUp() { return isSignUp; }
    public void setIsSignUp(Integer isSignUp) { this.isSignUp = isSignUp; }
    public String getClubName() { return clubName; }
    public void setClubName(String clubName) { this.clubName = clubName; }
    public Integer getIsOwner() { return isOwner; }
    public void setIsOwner(Integer isOwner) { this.isOwner = isOwner; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public Integer getRegistrationMerchantCount() { return registrationMerchantCount; }
    public void setRegistrationMerchantCount(Integer registrationMerchantCount) { this.registrationMerchantCount = registrationMerchantCount; }
    public Integer getIsRegistrationMerchant() { return isRegistrationMerchant; }
    public void setIsRegistrationMerchant(Integer isRegistrationMerchant) { this.isRegistrationMerchant = isRegistrationMerchant; }
    public Integer getPerkSellableCapacity() { return perkSellableCapacity; }
    public void setPerkSellableCapacity(Integer perkSellableCapacity) { this.perkSellableCapacity = perkSellableCapacity; }
    public Integer getPerkCoverageStatus() { return perkCoverageStatus; }
    public void setPerkCoverageStatus(Integer perkCoverageStatus) { this.perkCoverageStatus = perkCoverageStatus; }
    public String getJourneyRules() { return journeyRules; }
    public void setJourneyRules(String journeyRules) { this.journeyRules = journeyRules; }
}
