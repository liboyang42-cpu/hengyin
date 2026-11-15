package com.chengyinhub.business.domain.dto.ai;

/**
 * AI NPC dialogue request payload with registered state-key effects.
 */
public class NpcDialogueReq
{
    private Long runId;
    private Long topicId;
    private Long activityId;
    private Long npcId;
    private String utterance;
    private String routeActionId;

    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }

    public Long getNpcId() { return npcId; }
    public void setNpcId(Long npcId) { this.npcId = npcId; }

    public String getUtterance() { return utterance; }
    public void setUtterance(String utterance) { this.utterance = utterance; }

    public String getRouteActionId() { return routeActionId; }
    public void setRouteActionId(String routeActionId) { this.routeActionId = routeActionId; }
}
