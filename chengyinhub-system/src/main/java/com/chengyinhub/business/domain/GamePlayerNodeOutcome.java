package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/** 玩家在单局单节点的首次完成权威结果；后续提交不得覆盖该终态。 */
public class GamePlayerNodeOutcome extends BaseEntity {
    private static final long serialVersionUID = 1L;

    public static final String COMPLETED = "COMPLETED";
    public static final String FALLBACK_COMPLETED = "FALLBACK_COMPLETED";

    private Long id;
    private Long gameSessionId;
    private Long activityId;
    private Long stationRunId;
    private Long nodeId;
    private Long teamId;
    private Long memberId;
    private String status;
    private String completionSource;
    private Long sourceSubmissionId;
    private Long sourceReceiptId;
    private String fallbackPlanCode;
    private Integer fallbackPlanVersion;
    private Date completedAt;

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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCompletionSource() { return completionSource; }
    public void setCompletionSource(String completionSource) { this.completionSource = completionSource; }
    public Long getSourceSubmissionId() { return sourceSubmissionId; }
    public void setSourceSubmissionId(Long sourceSubmissionId) { this.sourceSubmissionId = sourceSubmissionId; }
    public Long getSourceReceiptId() { return sourceReceiptId; }
    public void setSourceReceiptId(Long sourceReceiptId) { this.sourceReceiptId = sourceReceiptId; }
    public String getFallbackPlanCode() { return fallbackPlanCode; }
    public void setFallbackPlanCode(String fallbackPlanCode) { this.fallbackPlanCode = fallbackPlanCode; }
    public Integer getFallbackPlanVersion() { return fallbackPlanVersion; }
    public void setFallbackPlanVersion(Integer fallbackPlanVersion) { this.fallbackPlanVersion = fallbackPlanVersion; }
    public Date getCompletedAt() { return completedAt; }
    public void setCompletedAt(Date completedAt) { this.completedAt = completedAt; }
}
