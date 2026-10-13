package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/** 玩家队伍。队伍只组织玩家，不承载订单或退款状态。 */
public class PlayTeam extends BaseEntity {
    private static final long serialVersionUID = 1L;

    public static final int STATUS_RECRUITING = 0;
    public static final int STATUS_LOCKED = 1;
    public static final int STATUS_STARTED = 2;
    public static final int STATUS_ENDED = 3;
    public static final int STATUS_DISBANDED = 4;

    private Long id;
    private Integer ownerType;
    private Long ownerId;
    private Long leaderMemberId;
    private String inviteCode;
    private String title;
    private Integer joinMode;
    private Integer maxMembers;
    private Integer joinedCount;
    private Integer status;
    private Date expireTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getOwnerType() { return ownerType; }
    public void setOwnerType(Integer ownerType) { this.ownerType = ownerType; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public Long getLeaderMemberId() { return leaderMemberId; }
    public void setLeaderMemberId(Long leaderMemberId) { this.leaderMemberId = leaderMemberId; }
    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Integer getJoinMode() { return joinMode; }
    public void setJoinMode(Integer joinMode) { this.joinMode = joinMode; }
    public Integer getMaxMembers() { return maxMembers; }
    public void setMaxMembers(Integer maxMembers) { this.maxMembers = maxMembers; }
    public Integer getJoinedCount() { return joinedCount; }
    public void setJoinedCount(Integer joinedCount) { this.joinedCount = joinedCount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Date getExpireTime() { return expireTime; }
    public void setExpireTime(Date expireTime) { this.expireTime = expireTime; }
}
