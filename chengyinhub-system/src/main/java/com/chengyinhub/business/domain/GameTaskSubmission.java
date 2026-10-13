package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/** 玩家提交给指定商家站点的叙事任务；不是订单或权益核销。 */
public class GameTaskSubmission extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long gameSessionId;
    private Long activityId;
    private Long stationRunId;
    private Long nodeId;
    private Long teamId;
    private Long memberId;
    private String taskCode;
    private String evidenceJson;
    private String status;
    private Long decidedBy;
    private String decisionReasonCode;
    private String decisionReason;
    private Integer revision;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGameSessionId() { return gameSessionId; }
    public void setGameSessionId(Long gameSessionId) { this.gameSessionId = gameSessionId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getStationRunId() { return stationRunId; }
    public void setStationRunId(Long stationRunId) { this.stationRunId = stationRunId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getTaskCode() { return taskCode; }
    public void setTaskCode(String taskCode) { this.taskCode = taskCode; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getDecidedBy() { return decidedBy; }
    public void setDecidedBy(Long decidedBy) { this.decidedBy = decidedBy; }
    public String getDecisionReasonCode() { return decisionReasonCode; }
    public void setDecisionReasonCode(String decisionReasonCode) { this.decisionReasonCode = decisionReasonCode; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
}
