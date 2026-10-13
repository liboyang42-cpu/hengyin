package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 玩家节点打卡进度
 *
 * @author cy
 * @date 2026-06-19
 */
public class PlayerNodeProgress
{
    private static final long serialVersionUID = 1L;

    private String playInstanceId;
    public String getPlayInstanceId() { return playInstanceId; }
    public void setPlayInstanceId(String v) { playInstanceId = v; }
    private Long id;
    private Long memberId;
    private Long activityId;
    private Long topicId;
    private Long nodeId;
    private Integer status;
    private Date checkinTime;
    private String picUrl;
    private Integer reviewStatus;
    private String longitude;
    private String latitude;
    /** 城市定向谜题本次得分；非谜题节点为空。 */
    private Integer puzzleScore;
    /** SOLVED 正常解出 / REVEALED 查看答案辅助完成；非谜题节点为空。 */
    private String completionMode;
    private Integer delFlag;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Date getCheckinTime() { return checkinTime; }
    public void setCheckinTime(Date checkinTime) { this.checkinTime = checkinTime; }

    public String getPicUrl() { return picUrl; }
    public void setPicUrl(String picUrl) { this.picUrl = picUrl; }

    public Integer getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(Integer reviewStatus) { this.reviewStatus = reviewStatus; }

    public String getLongitude() { return longitude; }
    public void setLongitude(String longitude) { this.longitude = longitude; }

    public String getLatitude() { return latitude; }
    public void setLatitude(String latitude) { this.latitude = latitude; }

    public Integer getPuzzleScore() { return puzzleScore; }
    public void setPuzzleScore(Integer puzzleScore) { this.puzzleScore = puzzleScore; }

    public String getCompletionMode() { return completionMode; }
    public void setCompletionMode(String completionMode) { this.completionMode = completionMode; }

    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
