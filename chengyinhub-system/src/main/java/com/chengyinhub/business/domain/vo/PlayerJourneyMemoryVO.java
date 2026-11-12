package com.chengyinhub.business.domain.vo;

import java.util.Date;
import java.util.List;

/**
 * 本局私人回忆投影（P8）。作用域只来自本场会话/本人完成事实；
 * 不含队友身份与他人照片；没有既有私照分享同意事实时，分享能力如实为 UNAVAILABLE。
 */
public class PlayerJourneyMemoryVO {

    public static final String PHOTO_SHARE_UNAVAILABLE = "UNAVAILABLE";
    public static final String PHOTO_SHARE_NO_CONSENT_FACT = "NO_PRIVATE_PHOTO_SHARE_CONSENT_FACT";
    public static final String IDENTITY_SELF_ONLY = "SELF_ONLY";

    public static final String SCOPE_RUN = "RUN";
    public static final String SCOPE_LEGACY_TOPIC = "LEGACY_TOPIC_SCOPE";

    /** 进度归属口径：RUN_INSTANCE=本局 play_instance_id；LEGACY_TOPIC=仅主题历史；UNATTRIBUTED_RUN=有局无实例值。 */
    public static final String PROGRESS_RUN_INSTANCE = "RUN_INSTANCE";
    public static final String PROGRESS_LEGACY_TOPIC = "LEGACY_TOPIC";
    public static final String PROGRESS_UNATTRIBUTED = "UNATTRIBUTED_RUN";

    private Long activityId;
    private Long topicId;
    private String scopeKind;
    private Long runId;
    private String runKind;
    private String playInstanceId;
    private String progressScope;
    private Integer participantCount;
    private String viewerRole;
    private String identityPolicy;
    private String photoShareCapability;
    private String photoShareReason;
    private Boolean readOnly;
    private List<Moment> moments;

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public String getScopeKind() { return scopeKind; }
    public void setScopeKind(String scopeKind) { this.scopeKind = scopeKind; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getPlayInstanceId() { return playInstanceId; }
    public String getRunKind() { return runKind; }
    public void setRunKind(String runKind) { this.runKind = runKind; }
    public void setPlayInstanceId(String playInstanceId) { this.playInstanceId = playInstanceId; }
    public String getProgressScope() { return progressScope; }
    public void setProgressScope(String progressScope) { this.progressScope = progressScope; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Integer getParticipantCount() { return participantCount; }
    public void setParticipantCount(Integer participantCount) { this.participantCount = participantCount; }
    public String getViewerRole() { return viewerRole; }
    public void setViewerRole(String viewerRole) { this.viewerRole = viewerRole; }
    public String getIdentityPolicy() { return identityPolicy; }
    public void setIdentityPolicy(String identityPolicy) { this.identityPolicy = identityPolicy; }
    public String getPhotoShareCapability() { return photoShareCapability; }
    public void setPhotoShareCapability(String photoShareCapability) { this.photoShareCapability = photoShareCapability; }
    public String getPhotoShareReason() { return photoShareReason; }
    public void setPhotoShareReason(String photoShareReason) { this.photoShareReason = photoShareReason; }
    public Boolean getReadOnly() { return readOnly; }
    public void setReadOnly(Boolean readOnly) { this.readOnly = readOnly; }
    public List<Moment> getMoments() { return moments; }
    public void setMoments(List<Moment> moments) { this.moments = moments; }

    /** 只含本人已完成节点；picUrl 也只来自本人进度行。 */
    public static class Moment {
        private Long nodeId;
        private String name;
        private String fragmentText;
        private String picUrl;
        private Date checkinTime;

        public Long getNodeId() { return nodeId; }
        public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getFragmentText() { return fragmentText; }
        public void setFragmentText(String fragmentText) { this.fragmentText = fragmentText; }
        public String getPicUrl() { return picUrl; }
        public void setPicUrl(String picUrl) { this.picUrl = picUrl; }
        public Date getCheckinTime() { return checkinTime; }
        public void setCheckinTime(Date checkinTime) { this.checkinTime = checkinTime; }
    }
}
