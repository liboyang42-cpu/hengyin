package com.chengyinhub.business.domain;

import java.util.Date;

/** V2 有效参与关系；event_signup 仅为兼容投影。 */
public class OfficialEventParticipation {
    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_LEFT = "LEFT";
    public static final String STATE_REFUNDED = "REFUNDED";
    public static final String STATE_BANNED = "BANNED";

    private Long id;
    private Long eventId;
    private Long memberId;
    private Long signupId;
    private String state;
    private String reason;
    private Date joinedAt;
    private Date invalidatedAt;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getSignupId() { return signupId; }
    public void setSignupId(Long signupId) { this.signupId = signupId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Date getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Date joinedAt) { this.joinedAt = joinedAt; }
    public Date getInvalidatedAt() { return invalidatedAt; }
    public void setInvalidatedAt(Date invalidatedAt) { this.invalidatedAt = invalidatedAt; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
