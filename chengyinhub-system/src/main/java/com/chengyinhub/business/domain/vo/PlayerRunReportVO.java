package com.chengyinhub.business.domain.vo;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 玩家本局战报（P7）。只读投影：事实全部来自本人报名/进度/路线会话/只读回执，
 * 不建局、不结算、不发奖、不补名次。缺失事实一律 null/UNKNOWN，不默认 300、不拿积分消费冒充成绩。
 */
public class PlayerRunReportVO {

    public static final String RUN_COMPLETED = "COMPLETED";
    public static final String RUN_ENDED = "ENDED";
    public static final String RUN_IN_PROGRESS = "IN_PROGRESS";
    public static final String RUN_NOT_STARTED = "NOT_STARTED";

    public static final String COMPLETION_LEGITIMATE = "LEGITIMATE_COMPLETION";
    public static final String COMPLETION_ASSISTED = "ASSISTED_COMPLETION";
    public static final String COMPLETION_ENDED = "ENDED";
    public static final String COMPLETION_IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETION_NOT_STARTED = "NOT_STARTED";

    public static final String SCORE_SCORED = "SCORED";
    public static final String SCORE_NOT_SCORED = "NOT_SCORED";

    /** RUN = 绑定了本局 play_instance_id；LEGACY_TOPIC_SCOPE = 旧局仅按主题历史事实展示。 */
    public static final String SCOPE_RUN = "RUN";
    public static final String SCOPE_LEGACY_TOPIC = "LEGACY_TOPIC_SCOPE";

    /** 进度行的归属口径：RUN_INSTANCE=按本局 play_instance_id；LEGACY_TOPIC=仅主题历史；UNATTRIBUTED=有局但无实例值。 */
    public static final String PROGRESS_RUN_INSTANCE = "RUN_INSTANCE";
    public static final String PROGRESS_LEGACY_TOPIC = "LEGACY_TOPIC";
    public static final String PROGRESS_UNATTRIBUTED = "UNATTRIBUTED_RUN";

    /** 主题级暂停行只属于最新一局；历史局没有自己的耗时事实。 */
    public static final String ELAPSED_OTHER_RUN = "UNAVAILABLE_OTHER_RUN";

    /** 背包/已得奖励是主题(活动)范围，不是单局产出；禁止 UI 说成"这一局挣到的"。 */
    public static final String REWARDS_SCOPE_TOPIC_ACTIVITY = "TOPIC_ACTIVITY";

    private Long activityId;
    private Long topicId;
    private String runState;
    private String scopeKind;
    private Long runId;
    /** 查看者在这一局的队伍（play_team.id）；不是队伍局或已不在队为 null。只用于打开队内榜/共享池。 */
    private Long teamId;
    private String playInstanceId;
    private String progressScope;
    private String runKind;
    private String completionKind;
    private Boolean legitimate;
    private Date completedAt;
    private Integer completedNodes;
    private Integer totalNodes;
    private Integer elapsedSeconds;
    private String elapsedSource;
    private Score score;
    private Rank rank;
    private List<NodeScore> nodes;
    private Map<String, Object> rewards;
    private String rewardsState;
    private String rewardsScope;
    private Boolean readOnly = Boolean.TRUE;

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public String getScopeKind() { return scopeKind; }
    public void setScopeKind(String scopeKind) { this.scopeKind = scopeKind; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getRunKind() { return runKind; }
    public void setRunKind(String runKind) { this.runKind = runKind; }
    public String getPlayInstanceId() { return playInstanceId; }
    public void setPlayInstanceId(String playInstanceId) { this.playInstanceId = playInstanceId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getProgressScope() { return progressScope; }
    public void setProgressScope(String progressScope) { this.progressScope = progressScope; }
    public String getRunState() { return runState; }
    public void setRunState(String runState) { this.runState = runState; }
    public String getCompletionKind() { return completionKind; }
    public void setCompletionKind(String completionKind) { this.completionKind = completionKind; }
    public Boolean getLegitimate() { return legitimate; }
    public void setLegitimate(Boolean legitimate) { this.legitimate = legitimate; }
    public Date getCompletedAt() { return completedAt; }
    public void setCompletedAt(Date completedAt) { this.completedAt = completedAt; }
    public Integer getCompletedNodes() { return completedNodes; }
    public void setCompletedNodes(Integer completedNodes) { this.completedNodes = completedNodes; }
    public Integer getTotalNodes() { return totalNodes; }
    public void setTotalNodes(Integer totalNodes) { this.totalNodes = totalNodes; }
    public Integer getElapsedSeconds() { return elapsedSeconds; }
    public void setElapsedSeconds(Integer elapsedSeconds) { this.elapsedSeconds = elapsedSeconds; }
    public String getElapsedSource() { return elapsedSource; }
    public void setElapsedSource(String elapsedSource) { this.elapsedSource = elapsedSource; }
    public Score getScore() { return score; }
    public void setScore(Score score) { this.score = score; }
    public Rank getRank() { return rank; }
    public void setRank(Rank rank) { this.rank = rank; }
    public List<NodeScore> getNodes() { return nodes; }
    public void setNodes(List<NodeScore> nodes) { this.nodes = nodes; }
    public Map<String, Object> getRewards() { return rewards; }
    public void setRewards(Map<String, Object> rewards) { this.rewards = rewards; }
    public String getRewardsState() { return rewardsState; }
    public void setRewardsState(String rewardsState) { this.rewardsState = rewardsState; }
    public String getRewardsScope() { return rewardsScope; }
    public void setRewardsScope(String rewardsScope) { this.rewardsScope = rewardsScope; }
    public Boolean getReadOnly() { return readOnly; }
    public void setReadOnly(Boolean readOnly) { this.readOnly = readOnly; }
    /** 逐节点事实：score 为服务端持久化的谜题分；null = 该节点本来就不计分。 */
    public static class NodeScore {
        private Long nodeId;
        private String name;
        private String completionMode;
        private Integer score;
        private Boolean scored;
        private Date checkinTime;

        public Long getNodeId() { return nodeId; }
        public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCompletionMode() { return completionMode; }
        public void setCompletionMode(String completionMode) { this.completionMode = completionMode; }
        public Integer getScore() { return score; }
        public void setScore(Integer score) { this.score = score; }
        public Boolean getScored() { return scored; }
        public void setScored(Boolean scored) { this.scored = scored; }
        public Date getCheckinTime() { return checkinTime; }
        public void setCheckinTime(Date checkinTime) { this.checkinTime = checkinTime; }
    }

    /** 成绩汇总。没有权威基准时 state=NOT_SCORED、score/penalties 均为 null。 */
    public static class Score {
        private String state;
        private Integer score;
        private Integer penalties;
        private Integer scoredNodes;
        private Integer unscoredNodes;
        private String basis;
        private String reason;

        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public Integer getScore() { return score; }
        public void setScore(Integer score) { this.score = score; }
        public Integer getPenalties() { return penalties; }
        public void setPenalties(Integer penalties) { this.penalties = penalties; }
        public Integer getScoredNodes() { return scoredNodes; }
        public void setScoredNodes(Integer scoredNodes) { this.scoredNodes = scoredNodes; }
        public Integer getUnscoredNodes() { return unscoredNodes; }
        public void setUnscoredNodes(Integer unscoredNodes) { this.unscoredNodes = unscoredNodes; }
        public String getBasis() { return basis; }
        public void setBasis(String basis) { this.basis = basis; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    /**
     * 名次只引用现有 BE-14 quest 作用域；scoreBasis 固定 COMPLETED_NODES（已完成节点数），
     * 不是净得分榜。无有效付费对局时 rank=null，不猜名次。
     */
    public static class Rank {
        private String scope;
        private Long scopeId;
        private String period;
        private String scoreBasis;
        private Integer rank;
        private Long score;
        private Long rankedMembers;
        private Boolean qualified;
        private String basisNote;

        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public Long getScopeId() { return scopeId; }
        public void setScopeId(Long scopeId) { this.scopeId = scopeId; }
        public String getPeriod() { return period; }
        public void setPeriod(String period) { this.period = period; }
        public String getScoreBasis() { return scoreBasis; }
        public void setScoreBasis(String scoreBasis) { this.scoreBasis = scoreBasis; }
        public Integer getRank() { return rank; }
        public void setRank(Integer rank) { this.rank = rank; }
        public Long getScore() { return score; }
        public void setScore(Long score) { this.score = score; }
        public Long getRankedMembers() { return rankedMembers; }
        public void setRankedMembers(Long rankedMembers) { this.rankedMembers = rankedMembers; }
        public Boolean getQualified() { return qualified; }
        public void setQualified(Boolean qualified) { this.qualified = qualified; }
        public String getBasisNote() { return basisNote; }
        public void setBasisNote(String basisNote) { this.basisNote = basisNote; }
    }
}
