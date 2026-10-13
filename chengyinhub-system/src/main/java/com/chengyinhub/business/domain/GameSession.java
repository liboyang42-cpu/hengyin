package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/** 一次 cms_activity 的三身份游戏总局；节点高级玩法仍由 game_runtime_session 承载。 */
public class GameSession extends BaseEntity {
    private static final long serialVersionUID = 1L;

    public static final String PREPARING = "PREPARING";
    public static final String READY = "READY";
    public static final String RUNNING = "RUNNING";
    public static final String FINISHED = "FINISHED";
    public static final String CANCELLED = "CANCELLED";

    private Long id;
    private Long activityId;
    private Long topicId;
    private Long clubId;
    private String moduleConfigSnapshotJson;
    private String status;
    private Long currentChapterId;
    private Integer revision;
    private Long preparedBy;
    private Long startedBy;
    private Long finishedBy;
    private Date startedAt;
    private Date finishedAt;
    private Boolean leaderboardVisible;
    private Long leaderboardUpdatedBy;
    private Date leaderboardUpdatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public String getModuleConfigSnapshotJson() { return moduleConfigSnapshotJson; }
    public void setModuleConfigSnapshotJson(String moduleConfigSnapshotJson) { this.moduleConfigSnapshotJson = moduleConfigSnapshotJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCurrentChapterId() { return currentChapterId; }
    public void setCurrentChapterId(Long currentChapterId) { this.currentChapterId = currentChapterId; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
    public Long getPreparedBy() { return preparedBy; }
    public void setPreparedBy(Long preparedBy) { this.preparedBy = preparedBy; }
    public Long getStartedBy() { return startedBy; }
    public void setStartedBy(Long startedBy) { this.startedBy = startedBy; }
    public Long getFinishedBy() { return finishedBy; }
    public void setFinishedBy(Long finishedBy) { this.finishedBy = finishedBy; }
    public Date getStartedAt() { return startedAt; }
    public void setStartedAt(Date startedAt) { this.startedAt = startedAt; }
    public Date getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Date finishedAt) { this.finishedAt = finishedAt; }
    public Boolean getLeaderboardVisible() { return leaderboardVisible; }
    public void setLeaderboardVisible(Boolean leaderboardVisible) { this.leaderboardVisible = leaderboardVisible; }
    public Long getLeaderboardUpdatedBy() { return leaderboardUpdatedBy; }
    public void setLeaderboardUpdatedBy(Long leaderboardUpdatedBy) { this.leaderboardUpdatedBy = leaderboardUpdatedBy; }
    public Date getLeaderboardUpdatedAt() { return leaderboardUpdatedAt; }
    public void setLeaderboardUpdatedAt(Date leaderboardUpdatedAt) { this.leaderboardUpdatedAt = leaderboardUpdatedAt; }
}
