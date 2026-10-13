package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/** 单局中的商家站点运营状态；与全局 POI 上下架、权益核销事实隔离。 */
public class GameStationRun extends BaseEntity {
    private static final long serialVersionUID = 1L;

    public static final String INVITED = "INVITED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String READY = "READY";
    public static final String ACTIVE = "ACTIVE";
    public static final String PAUSED = "PAUSED";
    public static final String CLOSED = "CLOSED";

    private Long id;
    private Long gameSessionId;
    private Long activityId;
    private Long nodeId;
    private String nodeName;
    private String stationCode;
    private Long merchantMemberId;
    private String sourceType;
    private Long sourceId;
    private String status;
    private String preparationSnapshotJson;
    private Integer capacity;
    private Date serviceStartAt;
    private Date serviceEndAt;
    private String pauseReasonCode;
    private String pauseReason;
    private Date resumeEta;
    private Long fallbackNodeId;
    private String fallbackPlanCode;
    private Integer fallbackPlanVersion;
    private Integer revision;
    private Integer pendingVerificationCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGameSessionId() { return gameSessionId; }
    public void setGameSessionId(Long gameSessionId) { this.gameSessionId = gameSessionId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getStationCode() { return stationCode; }
    public void setStationCode(String stationCode) { this.stationCode = stationCode; }
    public Long getMerchantMemberId() { return merchantMemberId; }
    public void setMerchantMemberId(Long merchantMemberId) { this.merchantMemberId = merchantMemberId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPreparationSnapshotJson() { return preparationSnapshotJson; }
    public void setPreparationSnapshotJson(String preparationSnapshotJson) { this.preparationSnapshotJson = preparationSnapshotJson; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public Date getServiceStartAt() { return serviceStartAt; }
    public void setServiceStartAt(Date serviceStartAt) { this.serviceStartAt = serviceStartAt; }
    public Date getServiceEndAt() { return serviceEndAt; }
    public void setServiceEndAt(Date serviceEndAt) { this.serviceEndAt = serviceEndAt; }
    public String getPauseReasonCode() { return pauseReasonCode; }
    public void setPauseReasonCode(String pauseReasonCode) { this.pauseReasonCode = pauseReasonCode; }
    public String getPauseReason() { return pauseReason; }
    public void setPauseReason(String pauseReason) { this.pauseReason = pauseReason; }
    public Date getResumeEta() { return resumeEta; }
    public void setResumeEta(Date resumeEta) { this.resumeEta = resumeEta; }
    public Long getFallbackNodeId() { return fallbackNodeId; }
    public void setFallbackNodeId(Long fallbackNodeId) { this.fallbackNodeId = fallbackNodeId; }
    public String getFallbackPlanCode() { return fallbackPlanCode; }
    public void setFallbackPlanCode(String fallbackPlanCode) { this.fallbackPlanCode = fallbackPlanCode; }
    public Integer getFallbackPlanVersion() { return fallbackPlanVersion; }
    public void setFallbackPlanVersion(Integer fallbackPlanVersion) { this.fallbackPlanVersion = fallbackPlanVersion; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
    public Integer getPendingVerificationCount() { return pendingVerificationCount; }
    public void setPendingVerificationCount(Integer pendingVerificationCount) { this.pendingVerificationCount = pendingVerificationCount; }
}
