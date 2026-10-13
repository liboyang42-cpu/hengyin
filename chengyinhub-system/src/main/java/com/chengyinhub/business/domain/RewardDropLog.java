package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * M6 掉落流水 reward_drop_log(uk=member+topic+node+activity 保证每节点最多掉一次)。
 */
public class RewardDropLog {
    private Long id;
    private Long memberId;
    private Long topicId;
    private Long nodeId;
    private Long activityId;
    /** 掉落发生场景；与 uk 一起隔离不同玩法中数值相同的节点 ID。 */
    private String sceneKey;
    private Integer dropType;
    private String paramJson;
    private Date createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public String getSceneKey() { return sceneKey; }
    public void setSceneKey(String sceneKey) { this.sceneKey = sceneKey; }
    public Integer getDropType() { return dropType; }
    public void setDropType(Integer dropType) { this.dropType = dropType; }
    public String getParamJson() { return paramJson; }
    public void setParamJson(String paramJson) { this.paramJson = paramJson; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
