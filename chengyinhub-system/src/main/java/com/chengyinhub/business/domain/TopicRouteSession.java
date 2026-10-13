package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/** 主题级地点路线权威会话；与节点级 game_runtime_session 分离。 */
public class TopicRouteSession extends BaseEntity {
    private static final long serialVersionUID = 1L;
    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_COMPLETED = 1;
    public static final int STATUS_ENDED = 2;

    private Long id;
    private Long activityId;
    private Long topicId;
    private Long memberId;
    private String playInstanceId;
    private Long configVersion;
    private String configSnapshotJson;
    private String stateJson;
    private Integer status;
    private Long currentNodeId;
    private Long recommendedNodeId;
    private Long seed;
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
    public String getConfigSnapshotJson() { return configSnapshotJson; }
    public void setConfigSnapshotJson(String configSnapshotJson) { this.configSnapshotJson = configSnapshotJson; }
    public String getStateJson() { return stateJson; }
    public void setStateJson(String stateJson) { this.stateJson = stateJson; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(Long currentNodeId) { this.currentNodeId = currentNodeId; }
    public Long getRecommendedNodeId() { return recommendedNodeId; }
    public void setRecommendedNodeId(Long recommendedNodeId) { this.recommendedNodeId = recommendedNodeId; }
    public Long getSeed() { return seed; }
    public void setSeed(Long seed) { this.seed = seed; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Date getCompletedAt() { return completedAt; }
    public void setCompletedAt(Date completedAt) { this.completedAt = completedAt; }
}
