package com.chengyinhub.business.domain.dto;

import java.util.List;
import java.util.Map;

/** 三身份之一的安全投影；不会暴露模块配置快照。 */
public class GameSessionView {
    private Long sessionId;
    private Long activityId;
    private String perspective;
    private String status;
    private Integer revision;
    private Integer authorityEpoch;
    private Long currentChapterId;
    private String snapshotAt;
    private List<String> availableActions;
    private Map<String, Object> player;
    private Map<String, Object> merchant;
    private Map<String, Object> club;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public String getPerspective() { return perspective; }
    public void setPerspective(String perspective) { this.perspective = perspective; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
    public Integer getAuthorityEpoch() { return authorityEpoch; }
    public void setAuthorityEpoch(Integer authorityEpoch) { this.authorityEpoch = authorityEpoch; }
    public Long getCurrentChapterId() { return currentChapterId; }
    public void setCurrentChapterId(Long currentChapterId) { this.currentChapterId = currentChapterId; }
    public String getSnapshotAt() { return snapshotAt; }
    public void setSnapshotAt(String snapshotAt) { this.snapshotAt = snapshotAt; }
    public List<String> getAvailableActions() { return availableActions; }
    public void setAvailableActions(List<String> availableActions) { this.availableActions = availableActions; }
    public Map<String, Object> getPlayer() { return player; }
    public void setPlayer(Map<String, Object> player) { this.player = player; }
    public Map<String, Object> getMerchant() { return merchant; }
    public void setMerchant(Map<String, Object> merchant) { this.merchant = merchant; }
    public Map<String, Object> getClub() { return club; }
    public void setClub(Map<String, Object> club) { this.club = club; }
}
