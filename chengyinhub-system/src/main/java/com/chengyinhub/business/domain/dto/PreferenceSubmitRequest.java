package com.chengyinhub.business.domain.dto;

import java.util.Map;

/** 偏好题整组提交；节点 id 由路径给出。 */
public class PreferenceSubmitRequest {
    private String activityId;
    private String topicId;
    private Map<String, String> choices;
    private String reuseTagCode;
    private String routeActionId;
    private Integer expectedRouteVersion;

    public String getActivityId() { return activityId; }
    public void setActivityId(String activityId) { this.activityId = activityId; }
    public String getTopicId() { return topicId; }
    public void setTopicId(String topicId) { this.topicId = topicId; }
    public Map<String, String> getChoices() { return choices; }
    public void setChoices(Map<String, String> choices) { this.choices = choices; }
    public String getReuseTagCode() { return reuseTagCode; }
    public void setReuseTagCode(String reuseTagCode) { this.reuseTagCode = reuseTagCode; }
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
