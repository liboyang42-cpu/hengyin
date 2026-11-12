package com.chengyinhub.business.domain.vo;

import java.util.Date;

/**
 * BE-14 多作用域榜单的一行。
 *
 * <p>只含公开资料：昵称/头像与既有榜单一致，<b>不含</b>手机号、邮箱、真实姓名、精确位置。
 * {@code scoreBasis=COMPLETED_NODES} 时 {@code score} 为已完成节点数（与现
 * {@code selectActivityLeaderboard}/{@code selectRouteLeaderboard} 的 score 同义）。
 */
public class ScopedLeaderboardEntryVO {
    /** 名次；「我的名次」在榜外时为 null（不猜名次）。 */
    private Integer rank;
    private Long memberId;
    private String nickname;
    private String avatar;
    private Long score;
    /** 同分裁判时刻：该作用域内最后一次完成节点的时间。 */
    private Date finishedAt;

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
    public Date getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Date finishedAt) { this.finishedAt = finishedAt; }
}
