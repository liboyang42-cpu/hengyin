package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * P1 带领场状态 club_lead_state(队长控章节)。
 */
public class ClubLeadState {
    private Long id;
    private Long activityId;
    private Long leaderMemberId;
    private Long currentChapterId;
    private String broadcastText;
    private Date broadcastAt;
    /** 1=集合中 2=行进 3=已结算 */
    private Integer status;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getLeaderMemberId() { return leaderMemberId; }
    public void setLeaderMemberId(Long leaderMemberId) { this.leaderMemberId = leaderMemberId; }
    public Long getCurrentChapterId() { return currentChapterId; }
    public void setCurrentChapterId(Long currentChapterId) { this.currentChapterId = currentChapterId; }
    public String getBroadcastText() { return broadcastText; }
    public void setBroadcastText(String broadcastText) { this.broadcastText = broadcastText; }
    public Date getBroadcastAt() { return broadcastAt; }
    public void setBroadcastAt(Date broadcastAt) { this.broadcastAt = broadcastAt; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
