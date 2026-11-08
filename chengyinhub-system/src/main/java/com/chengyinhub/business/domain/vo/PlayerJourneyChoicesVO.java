package com.chengyinhub.business.domain.vo;

import java.util.List;

/**
 * 本主题真实支持的游玩选择（P4）。只读：runtimeScope/worldMode/routeMode 来自已发布内容，
 * 不按姓名或关键词猜能力；未声明的模式一律不展示，不静默改写已开局的局。
 */
public class PlayerJourneyChoicesVO {

    /** 配置来源：FROZEN_RUN=进行中局的开局冻结；PUBLISHED=当前已发布；UNAVAILABLE=有局但快照缺失。 */
    public static final String CONFIG_FROZEN_RUN = "FROZEN_RUN";
    public static final String CONFIG_PUBLISHED = "PUBLISHED";
    public static final String CONFIG_UNAVAILABLE = "UNAVAILABLE";
    public static final String CONFIG_MISSING_RUN_SNAPSHOT = "RUN_CONFIG_SNAPSHOT_MISSING";

    /**
     * team_mode 的唯一真源是 {@code cms_topic.team_mode} 列，不进入发布/冻结快照；
     * 因此本字段只能标 CURRENT_TOPIC_COLUMN，不能谎称来自本局冻结配置。
     */
    public static final String TEAM_MODE_SOURCE_CURRENT_TOPIC = "CURRENT_TOPIC_COLUMN";

    private Long topicId;
    private Long activityId;
    private String configState;
    private String configUnavailableReason;
    private String runtimeScope;
    private String worldMode;
    private String routeMode;
    private Boolean teamModeEnabled;
    private String teamModeSource;
    private List<Choice> choices;
    /** 与运行配置同源的能力声明；null = 该修订未声明能力（不等于"无要求"）。 */
    private List<Capability> requiredCapabilities;

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public String getConfigState() { return configState; }
    public void setConfigState(String configState) { this.configState = configState; }
    public String getConfigUnavailableReason() { return configUnavailableReason; }
    public void setConfigUnavailableReason(String configUnavailableReason) {
        this.configUnavailableReason = configUnavailableReason;
    }
    public String getTeamModeSource() { return teamModeSource; }
    public void setTeamModeSource(String teamModeSource) { this.teamModeSource = teamModeSource; }
    public String getRuntimeScope() { return runtimeScope; }
    public void setRuntimeScope(String runtimeScope) { this.runtimeScope = runtimeScope; }
    public String getWorldMode() { return worldMode; }
    public void setWorldMode(String worldMode) { this.worldMode = worldMode; }
    public String getRouteMode() { return routeMode; }
    public void setRouteMode(String routeMode) { this.routeMode = routeMode; }
    public Boolean getTeamModeEnabled() { return teamModeEnabled; }
    public void setTeamModeEnabled(Boolean teamModeEnabled) { this.teamModeEnabled = teamModeEnabled; }
    public List<Choice> getChoices() { return choices; }
    public void setChoices(List<Choice> choices) { this.choices = choices; }
    public List<Capability> getRequiredCapabilities() { return requiredCapabilities; }
    public void setRequiredCapabilities(List<Capability> requiredCapabilities) { this.requiredCapabilities = requiredCapabilities; }

    public static class Choice {
        public static final String INDEPENDENT = "INDEPENDENT";
        public static final String SHARED = "SHARED_TEAM";

        private String key;
        private String progressScope;
        private String hintScope;
        private String scoreScope;
        private Boolean requiresPaidTicket;
        private Boolean requiresTeam;
        private Boolean available;
        private String unavailableReason;
        private Boolean competition;
        private Integer exclusiveQuestSlots;
        private Integer additionalQuestSlots;
        private TeamCapacity teamCapacity;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getProgressScope() { return progressScope; }
        public void setProgressScope(String progressScope) { this.progressScope = progressScope; }
        public String getHintScope() { return hintScope; }
        public void setHintScope(String hintScope) { this.hintScope = hintScope; }
        public String getScoreScope() { return scoreScope; }
        public void setScoreScope(String scoreScope) { this.scoreScope = scoreScope; }
        public Boolean getRequiresPaidTicket() { return requiresPaidTicket; }
        public void setRequiresPaidTicket(Boolean requiresPaidTicket) { this.requiresPaidTicket = requiresPaidTicket; }
        public Boolean getRequiresTeam() { return requiresTeam; }
        public void setRequiresTeam(Boolean requiresTeam) { this.requiresTeam = requiresTeam; }
        public Boolean getAvailable() { return available; }
        public void setAvailable(Boolean available) { this.available = available; }
        public String getUnavailableReason() { return unavailableReason; }
        public void setUnavailableReason(String unavailableReason) { this.unavailableReason = unavailableReason; }
        public Boolean getCompetition() { return competition; }
        public void setCompetition(Boolean competition) { this.competition = competition; }
        public Integer getExclusiveQuestSlots() { return exclusiveQuestSlots; }
        public void setExclusiveQuestSlots(Integer exclusiveQuestSlots) { this.exclusiveQuestSlots = exclusiveQuestSlots; }
        public Integer getAdditionalQuestSlots() { return additionalQuestSlots; }
        public void setAdditionalQuestSlots(Integer additionalQuestSlots) { this.additionalQuestSlots = additionalQuestSlots; }
        public TeamCapacity getTeamCapacity() { return teamCapacity; }
        public void setTeamCapacity(TeamCapacity teamCapacity) { this.teamCapacity = teamCapacity; }
    }

    /** 只在查看者已有本场队伍时下发；容量真源是 play_team.max_members，不猜默认人数。 */
    public static class TeamCapacity {
        private Long teamId;
        private Integer joinedCount;
        private Integer maxMembers;

        public Long getTeamId() { return teamId; }
        public void setTeamId(Long teamId) { this.teamId = teamId; }
        public Integer getJoinedCount() { return joinedCount; }
        public void setJoinedCount(Integer joinedCount) { this.joinedCount = joinedCount; }
        public Integer getMaxMembers() { return maxMembers; }
        public void setMaxMembers(Integer maxMembers) { this.maxMembers = maxMembers; }
    }

    public static class Capability {
        private String key;
        private Integer minVersion;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public Integer getMinVersion() { return minVersion; }
        public void setMinVersion(Integer minVersion) { this.minVersion = minVersion; }
    }
}
