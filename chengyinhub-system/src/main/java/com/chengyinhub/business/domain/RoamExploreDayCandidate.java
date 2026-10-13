package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 漫游导流候选查询的内部事实载体；只在服务端过滤，不直接序列化给客户端。 */
public class RoamExploreDayCandidate {
    private Long activityId;
    private Long topicId;
    private Long topicMemberId;
    private Long merchantMemberId;
    private Long merchantStatus;
    private Integer merchantDelFlag;
    private Integer productType;
    private Integer topicLifecycle;
    private Integer topicDelFlag;
    private Date sellingStartedAt;
    private Date saleEndAt;
    private Integer roamReferralEnabled;
    private Integer projectReviewStatus;
    private String projectReviewSnapshotJson;
    private Integer saleReviewStatus;
    private Long activityStatus;
    private Long activityPublishStatus;
    private Date activityCancelTime;
    private Integer activityDelFlag;
    private Long ticketId;
    private Long ticketOwnerType;
    private Long ticketOwnerId;
    private Integer ticketDelFlag;
    private Long remainingInventory;
    private Date ticketSignupDeadline;
    private BigDecimal gatherLat;
    private BigDecimal gatherLng;
    private String title;
    private String subtitle;
    private String coverImg;
    private String meetingPoint;

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getTopicMemberId() { return topicMemberId; }
    public void setTopicMemberId(Long topicMemberId) { this.topicMemberId = topicMemberId; }
    public Long getMerchantMemberId() { return merchantMemberId; }
    public void setMerchantMemberId(Long merchantMemberId) { this.merchantMemberId = merchantMemberId; }
    public Long getMerchantStatus() { return merchantStatus; }
    public void setMerchantStatus(Long merchantStatus) { this.merchantStatus = merchantStatus; }
    public Integer getMerchantDelFlag() { return merchantDelFlag; }
    public void setMerchantDelFlag(Integer merchantDelFlag) { this.merchantDelFlag = merchantDelFlag; }
    public Integer getProductType() { return productType; }
    public void setProductType(Integer productType) { this.productType = productType; }
    public Integer getTopicLifecycle() { return topicLifecycle; }
    public void setTopicLifecycle(Integer topicLifecycle) { this.topicLifecycle = topicLifecycle; }
    public Integer getTopicDelFlag() { return topicDelFlag; }
    public void setTopicDelFlag(Integer topicDelFlag) { this.topicDelFlag = topicDelFlag; }
    public Date getSellingStartedAt() { return sellingStartedAt; }
    public void setSellingStartedAt(Date sellingStartedAt) { this.sellingStartedAt = sellingStartedAt; }
    public Date getSaleEndAt() { return saleEndAt; }
    public void setSaleEndAt(Date saleEndAt) { this.saleEndAt = saleEndAt; }
    public Integer getRoamReferralEnabled() { return roamReferralEnabled; }
    public void setRoamReferralEnabled(Integer roamReferralEnabled) { this.roamReferralEnabled = roamReferralEnabled; }
    public Integer getProjectReviewStatus() { return projectReviewStatus; }
    public void setProjectReviewStatus(Integer projectReviewStatus) { this.projectReviewStatus = projectReviewStatus; }
    public String getProjectReviewSnapshotJson() { return projectReviewSnapshotJson; }
    public void setProjectReviewSnapshotJson(String projectReviewSnapshotJson) { this.projectReviewSnapshotJson = projectReviewSnapshotJson; }
    public Integer getSaleReviewStatus() { return saleReviewStatus; }
    public void setSaleReviewStatus(Integer saleReviewStatus) { this.saleReviewStatus = saleReviewStatus; }
    public Long getActivityStatus() { return activityStatus; }
    public void setActivityStatus(Long activityStatus) { this.activityStatus = activityStatus; }
    public Long getActivityPublishStatus() { return activityPublishStatus; }
    public void setActivityPublishStatus(Long activityPublishStatus) { this.activityPublishStatus = activityPublishStatus; }
    public Date getActivityCancelTime() { return activityCancelTime; }
    public void setActivityCancelTime(Date activityCancelTime) { this.activityCancelTime = activityCancelTime; }
    public Integer getActivityDelFlag() { return activityDelFlag; }
    public void setActivityDelFlag(Integer activityDelFlag) { this.activityDelFlag = activityDelFlag; }
    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public Long getTicketOwnerType() { return ticketOwnerType; }
    public void setTicketOwnerType(Long ticketOwnerType) { this.ticketOwnerType = ticketOwnerType; }
    public Long getTicketOwnerId() { return ticketOwnerId; }
    public void setTicketOwnerId(Long ticketOwnerId) { this.ticketOwnerId = ticketOwnerId; }
    public Integer getTicketDelFlag() { return ticketDelFlag; }
    public void setTicketDelFlag(Integer ticketDelFlag) { this.ticketDelFlag = ticketDelFlag; }
    public Long getRemainingInventory() { return remainingInventory; }
    public void setRemainingInventory(Long remainingInventory) { this.remainingInventory = remainingInventory; }
    public Date getTicketSignupDeadline() { return ticketSignupDeadline; }
    public void setTicketSignupDeadline(Date ticketSignupDeadline) { this.ticketSignupDeadline = ticketSignupDeadline; }
    public BigDecimal getGatherLat() { return gatherLat; }
    public void setGatherLat(BigDecimal gatherLat) { this.gatherLat = gatherLat; }
    public BigDecimal getGatherLng() { return gatherLng; }
    public void setGatherLng(BigDecimal gatherLng) { this.gatherLng = gatherLng; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getCoverImg() { return coverImg; }
    public void setCoverImg(String coverImg) { this.coverImg = coverImg; }
    public String getMeetingPoint() { return meetingPoint; }
    public void setMeetingPoint(String meetingPoint) { this.meetingPoint = meetingPoint; }
}
