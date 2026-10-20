package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.io.Serializable;

/** One atomic team replay generation; personal state and rewards remain member-owned. */
public class ThemeTeamRun extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long teamId;
    private Long activityId;
    private Long topicId;
    private Long previousRunId;
    private Long sourceSessionId;
    private String requestKey;
    private String requestDigest;
    private String playInstanceId;
    private Integer generation;
    private Long configVersion;
    private String configSnapshotJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getPreviousRunId() { return previousRunId; }
    public void setPreviousRunId(Long previousRunId) { this.previousRunId = previousRunId; }
    public Long getSourceSessionId() { return sourceSessionId; }
    public void setSourceSessionId(Long sourceSessionId) { this.sourceSessionId = sourceSessionId; }
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String value) { requestKey=value; }
    public String getRequestDigest() { return requestDigest; }
    public void setRequestDigest(String value) { requestDigest=value; }
    public String getPlayInstanceId() { return playInstanceId; }
    public void setPlayInstanceId(String playInstanceId) { this.playInstanceId = playInstanceId; }
    public Integer getGeneration() { return generation; }
    public void setGeneration(Integer generation) { this.generation = generation; }
    public Long getConfigVersion() { return configVersion; }
    public void setConfigVersion(Long configVersion) { this.configVersion = configVersion; }
    public String getConfigSnapshotJson() { return configSnapshotJson; }
    public void setConfigSnapshotJson(String configSnapshotJson) { this.configSnapshotJson = configSnapshotJson; }

    /** Immutable replay audit link, never the live team's gameplay-admission roster. */
    public static class Member implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long runId;
        private Long memberId;
        private Long previousSessionId;
        private Long sessionId;

        public Long getRunId() { return runId; }
        public void setRunId(Long runId) { this.runId = runId; }
        public Long getMemberId() { return memberId; }
        public void setMemberId(Long memberId) { this.memberId = memberId; }
        public Long getPreviousSessionId() { return previousSessionId; }
        public void setPreviousSessionId(Long previousSessionId) { this.previousSessionId = previousSessionId; }
        public Long getSessionId() { return sessionId; }
        public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    }
}
