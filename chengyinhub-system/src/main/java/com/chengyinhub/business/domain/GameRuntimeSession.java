package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/** 高级玩法权威运行会话。 */
public class GameRuntimeSession extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final int OWNER_PLAYER = 1;
    public static final int OWNER_TEAM = 2;
    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_COMPLETED = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_EXPIRED = 3;

    private String playInstanceId = "default";
    public String getPlayInstanceId() { return playInstanceId; }
    public void setPlayInstanceId(String v) { playInstanceId = v; }
    private Long id;
    private Long activityId;
    private Long topicId;
    private Long nodeId;
    private Long templateId;
    private Integer ownerType;
    private Long ownerId;
    private String configSnapshotJson;
    private String stateJson;
    private Integer status;
    private Integer score;
    private Integer completedUnits;
    private Integer version;
    private Date startedAt;
    private Date deadlineAt;
    private Date completedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Integer getOwnerType() { return ownerType; }
    public void setOwnerType(Integer ownerType) { this.ownerType = ownerType; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getConfigSnapshotJson() { return configSnapshotJson; }
    public void setConfigSnapshotJson(String configSnapshotJson) { this.configSnapshotJson = configSnapshotJson; }
    public String getStateJson() { return stateJson; }
    public void setStateJson(String stateJson) { this.stateJson = stateJson; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public Integer getCompletedUnits() { return completedUnits; }
    public void setCompletedUnits(Integer completedUnits) { this.completedUnits = completedUnits; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Date getStartedAt() { return startedAt; }
    public void setStartedAt(Date startedAt) { this.startedAt = startedAt; }
    public Date getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(Date deadlineAt) { this.deadlineAt = deadlineAt; }
    public Date getCompletedAt() { return completedAt; }
    public void setCompletedAt(Date completedAt) { this.completedAt = completedAt; }
}
