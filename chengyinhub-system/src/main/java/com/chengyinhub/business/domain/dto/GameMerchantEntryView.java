package com.chengyinhub.business.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

/** 当前登录商家被服务端授权的活动级站点入口；不含配置、答案或其他商家信息。 */
public class GameMerchantEntryView {
    private Long activityId;
    private Long topicId;
    private String activityName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endAt;
    private Integer stationCount;

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getActivityName() { return activityName; }
    public void setActivityName(String activityName) { this.activityName = activityName; }
    public Date getStartAt() { return startAt; }
    public void setStartAt(Date startAt) { this.startAt = startAt; }
    public Date getEndAt() { return endAt; }
    public void setEndAt(Date endAt) { this.endAt = endAt; }
    public Integer getStationCount() { return stationCount; }
    public void setStationCount(Integer stationCount) { this.stationCount = stationCount; }
}
