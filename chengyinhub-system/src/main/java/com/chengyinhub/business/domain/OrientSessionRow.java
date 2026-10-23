package com.chengyinhub.business.domain;

import java.util.Date;

/** topic_route_session 的定向投影:只在开关打开后读 phase 列,既有 RouteSession 映射不碰该列。 */
public class OrientSessionRow {
    private Long id;
    private Long activityId;
    private Long topicId;
    private Long memberId;
    private String playInstanceId;
    private Long configVersion;
    private Integer status;
    private String phase;
    private Integer version;
    private Date completedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getPlayInstanceId() { return playInstanceId; }
    public void setPlayInstanceId(String playInstanceId) { this.playInstanceId = playInstanceId; }
    public Long getConfigVersion() { return configVersion; }
    public void setConfigVersion(Long configVersion) { this.configVersion = configVersion; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Date getCompletedAt() { return completedAt; }
    public void setCompletedAt(Date completedAt) { this.completedAt = completedAt; }
}
