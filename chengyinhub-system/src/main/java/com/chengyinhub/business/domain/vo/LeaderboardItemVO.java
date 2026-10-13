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
}
