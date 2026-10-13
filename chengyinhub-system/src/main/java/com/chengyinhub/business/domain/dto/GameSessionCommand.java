package com.chengyinhub.business.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.LinkedHashMap;
import java.util.Map;

/** `/api/game/session/command` 的稳定命令信封。 */
public class GameSessionCommand {
    private Long activityId;
    private Long nodeId;
    private String requestId;
    private Integer expectedRevision;
    private String action;
    private Integer authorityEpoch;
    private Map<String, Object> payload = new LinkedHashMap<>();
    @JsonIgnore
    private String requestHash;

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Integer getExpectedRevision() { return expectedRevision; }
    public void setExpectedRevision(Integer expectedRevision) { this.expectedRevision = expectedRevision; }
    public Integer getAuthorityEpoch() { return authorityEpoch; }
    public void setAuthorityEpoch(Integer authorityEpoch) { this.authorityEpoch = authorityEpoch; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload == null ? new LinkedHashMap<>() : payload; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
}
