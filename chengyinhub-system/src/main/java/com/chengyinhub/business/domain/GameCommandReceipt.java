package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/** 游戏命令权威回执；与奖励、权益及财务核销事实严格隔离。 */
public class GameCommandReceipt extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long gameSessionId;
    private Long activityId;
    private Long nodeId;
    private Long actorMemberId;
    private String actorPerspective;
    private String requestId;
    private String requestHash;
    private String action;
    private String outcome;
    private String reasonCode;
    private Integer revision;
    private String resultJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGameSessionId() { return gameSessionId; }
    public void setGameSessionId(Long gameSessionId) { this.gameSessionId = gameSessionId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Long getActorMemberId() { return actorMemberId; }
    public void setActorMemberId(Long actorMemberId) { this.actorMemberId = actorMemberId; }
    public String getActorPerspective() { return actorPerspective; }
    public void setActorPerspective(String actorPerspective) { this.actorPerspective = actorPerspective; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
}
