package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 地图附近公开队伍白名单。独立于 PlayTeam:实体上的 inviteCode(入队凭证)与 ownerType 等内部字段不会进入响应。
 */
public class PlayTeamNearbyVO {
    private Long teamId;
    private String title;
    private Long activityId;
    private String activityName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startDate;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endDate;
    private Long topicId;
    private String topicName;
    private Integer productType;
    private String addressName;
    private BigDecimal latitude;
    private BigDecimal longitude;
    /** GATHER=票上集合点 ACTIVITY=场次坐标 TOPIC_NODE=主题首节点(只代表大致区域)。 */
    private String coordSource;
    private Double distance;
    private String leaderName;
    private String leaderAvatar;
    private Integer joinedCount;
    private Integer maxMembers;
    @JsonIgnore
    private String memberAvatarsJoined;
    /** NONE / PENDING / JOINED / LEADER / REJECTED(被拒或被移出,不能再申请)。 */
    private String viewerStatus;
    private Boolean viewerHasTicket;
    /** 仅 viewerStatus=PENDING 时为本人申请的失效时刻 = min(申请+24h, 场次开始);其他恒 null。 */
    private Date applyExpireTime;
    /** 仅队长本人可见的待审申请数;其他人恒 null。 */
    private Integer pendingCount;

    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public String getActivityName() { return activityName; }
    public void setActivityName(String activityName) { this.activityName = activityName; }
    public Date getStartDate() { return startDate; }
    public void setStartDate(Date startDate) { this.startDate = startDate; }
    public Date getEndDate() { return endDate; }
    public void setEndDate(Date endDate) { this.endDate = endDate; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }
    public Integer getProductType() { return productType; }
    public void setProductType(Integer productType) { this.productType = productType; }
    public String getAddressName() { return addressName; }
    public void setAddressName(String addressName) { this.addressName = addressName; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
    public String getCoordSource() { return coordSource; }
    public void setCoordSource(String coordSource) { this.coordSource = coordSource; }
    public Double getDistance() { return distance; }
    public void setDistance(Double distance) { this.distance = distance; }
    public String getLeaderName() { return leaderName; }
    public void setLeaderName(String leaderName) { this.leaderName = leaderName; }
    public String getLeaderAvatar() { return leaderAvatar; }
    public void setLeaderAvatar(String leaderAvatar) { this.leaderAvatar = leaderAvatar; }
    public Integer getJoinedCount() { return joinedCount; }
    public void setJoinedCount(Integer joinedCount) { this.joinedCount = joinedCount; }
    public Integer getMaxMembers() { return maxMembers; }
    public void setMaxMembers(Integer maxMembers) { this.maxMembers = maxMembers; }
    public String getMemberAvatarsJoined() { return memberAvatarsJoined; }
    public void setMemberAvatarsJoined(String memberAvatarsJoined) { this.memberAvatarsJoined = memberAvatarsJoined; }
    /** 头像堆:最多 3 个已加入成员头像(队长在前)。 */
    public List<String> getMemberAvatars() {
        if (memberAvatarsJoined == null || memberAvatarsJoined.isEmpty()) return Collections.emptyList();
        List<String> all = Arrays.asList(memberAvatarsJoined.split("\n"));
        return all.size() > 3 ? all.subList(0, 3) : all;
    }
    public String getViewerStatus() { return viewerStatus; }
    public void setViewerStatus(String viewerStatus) { this.viewerStatus = viewerStatus; }
    public Boolean getViewerHasTicket() { return viewerHasTicket; }
    public void setViewerHasTicket(Boolean viewerHasTicket) { this.viewerHasTicket = viewerHasTicket; }
    public Date getApplyExpireTime() { return applyExpireTime; }
    public void setApplyExpireTime(Date applyExpireTime) { this.applyExpireTime = applyExpireTime; }
    public Integer getPendingCount() { return pendingCount; }
    public void setPendingCount(Integer pendingCount) { this.pendingCount = pendingCount; }
}
