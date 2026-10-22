package com.chengyinhub.business.domain.vo;

/**
 * 排行榜单行(列表项与「我的名次」共用)。
 * rankPercentage 仅在「我的名次」上有值(如 TOP20%),列表项为 null。
 */
public class LeaderboardItemVO {
    private Integer rank;
    private Long memberId;
    private String nickname;
    private String avatar;
    private Long score;
    private String rankPercentage;
    /** D-13 分支主题:本局是否已到达结局(会话 COMPLETED);线性主题为 null。 */
    private Boolean reachedEnding;
    /** D-13 分支主题:到达结局的本局总用时(秒,开局→结局);未到达结局为 null。 */
    private Long elapsedSeconds;
    /** 同分裁判时刻:线性=最后一次完成节点时间;分支=到达结局时间。 */
    private java.util.Date finishedAt;

    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public Long getScore() { return score; }
    public void setScore(Long score) { this.score = score; }
    public String getRankPercentage() { return rankPercentage; }
    public void setRankPercentage(String rankPercentage) { this.rankPercentage = rankPercentage; }
    public Boolean getReachedEnding() { return reachedEnding; }
    public void setReachedEnding(Boolean reachedEnding) { this.reachedEnding = reachedEnding; }
    public Long getElapsedSeconds() { return elapsedSeconds; }
    public void setElapsedSeconds(Long elapsedSeconds) { this.elapsedSeconds = elapsedSeconds; }
    public java.util.Date getFinishedAt() { return finishedAt; }
    public void setFinishedAt(java.util.Date finishedAt) { this.finishedAt = finishedAt; }
}
