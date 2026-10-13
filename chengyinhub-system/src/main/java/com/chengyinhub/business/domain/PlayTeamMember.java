package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/** 玩家队伍成员。 */
public class PlayTeamMember extends BaseEntity {
    private static final long serialVersionUID = 1L;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_JOINED = 1;
    public static final int STATUS_QUIT = 2;
    public static final int STATUS_KICKED = 3;
    public static final int STATUS_REJECTED = 4;
    /** 申请人撤回;可再次申请。 */
    public static final int STATUS_WITHDRAWN = 5;
    /** 申请超 24h / 场次开始 / 队伍满员或结束而失效;可再次申请。 */
    public static final int STATUS_EXPIRED = 6;

    private Long id;
    private Long teamId;
    private Long memberId;
    private Integer role;
    private Integer status;
    private String applyMessage;
    private Date applyExpireTime;
    private String memberName;
    private String memberAvatar;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Integer getRole() { return role; }
    public void setRole(Integer role) { this.role = role; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getApplyMessage() { return applyMessage; }
    public void setApplyMessage(String applyMessage) { this.applyMessage = applyMessage; }
    public Date getApplyExpireTime() { return applyExpireTime; }
    public void setApplyExpireTime(Date applyExpireTime) { this.applyExpireTime = applyExpireTime; }
    public String getMemberName() { return memberName; }
    public void setMemberName(String memberName) { this.memberName = memberName; }
    public String getMemberAvatar() { return memberAvatar; }
    public void setMemberAvatar(String memberAvatar) { this.memberAvatar = memberAvatar; }
}
