package com.chengyinhub.business.domain.dto;

import java.io.Serializable;
import java.util.Map;

/** App 端传感器挑战完成上报。 */
public class SensorResultRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long activityId;
    private Long topicId;
    private Long nodeId;
    private String sensorType;
    private Map<String, Object> payload;
    private String routeActionId;
    private Integer expectedRouteVersion;

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getSensorType() { return sensorType; }
    public void setSensorType(String sensorType) { this.sensorType = sensorType; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public String getRouteActionId() { return routeActionId; }
    public void setRouteActionId(String routeActionId) { this.routeActionId = routeActionId; }
    public Integer getExpectedRouteVersion() { return expectedRouteVersion; }
    public void setExpectedRouteVersion(Integer expectedRouteVersion) { this.expectedRouteVersion = expectedRouteVersion; }
    private Long routeSessionId;
    private String runtimeInstance;
    private Integer authorityEpoch;
    public Long getRouteSessionId() { return routeSessionId; }
    public void setRouteSessionId(Long value) { routeSessionId = value; }
    public String getRuntimeInstance() { return runtimeInstance; }
    public void setRuntimeInstance(String value) { runtimeInstance = value; }
    public Integer getAuthorityEpoch() { return authorityEpoch; }
    public void setAuthorityEpoch(Integer value) { authorityEpoch = value; }
}
