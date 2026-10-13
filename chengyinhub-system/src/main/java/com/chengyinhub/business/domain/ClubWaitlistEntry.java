package com.chengyinhub.business.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Date;

/** 活动票种候补队列；只有 OFFERED 会预占一张真实票库存。 */
public class ClubWaitlistEntry {
    /** 当前成员没有候补队列记录。 */
    public static final String NONE = "NONE";
    public static final String WAITING = "WAITING";
    public static final String OFFERED = "OFFERED";
    public static final String CLAIMED = "CLAIMED";
    public static final String CONVERTED = "CONVERTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";
    /** 当前活动没有可用的俱乐部场次系列，属于稳定业务状态而非请求失败。 */
    public static final String ELIGIBILITY_NO_SERIES = "NO_SERIES";
    public static final String ELIGIBILITY_CLOSED = "WAITLIST_CLOSED";
    public static final String ELIGIBILITY_NOT_MEMBER = "NOT_MEMBER";
    public static final String ELIGIBILITY_REGISTERED = "ALREADY_REGISTERED";
    public static final String ELIGIBILITY_ELIGIBLE = "ELIGIBLE";
    public static final String ELIGIBILITY_BLOCKED = "BLOCKED";

    private Long id;
    private Long clubId;
    private Long activityId;
    private Long ticketId;
    private Long memberId;
    private String state;
    private Date joinedAt;
    private Date offeredAt;
    private Date offerExpiresAt;
    private String offerTokenHash;
    private Long registrationId;
    private Integer version;
    /** 仅返回给当前 entry 的 member，不持久化。 */
    private String offerToken;
    /** 仅用于 status 响应，不持久化。 */
    private String eligibilityState;
    /** 仅用于 status 响应；默认 false，未知状态不开放加入。 */
    private boolean waitlistJoinAllowed;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Date getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Date joinedAt) { this.joinedAt = joinedAt; }
    public Date getOfferedAt() { return offeredAt; }
    public void setOfferedAt(Date offeredAt) { this.offeredAt = offeredAt; }
    public Date getOfferExpiresAt() { return offerExpiresAt; }
    public void setOfferExpiresAt(Date offerExpiresAt) { this.offerExpiresAt = offerExpiresAt; }
    @JsonIgnore
    public String getOfferTokenHash() { return offerTokenHash; }
    public void setOfferTokenHash(String offerTokenHash) { this.offerTokenHash = offerTokenHash; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getOfferToken() { return offerToken; }
    public void setOfferToken(String offerToken) { this.offerToken = offerToken; }
    public String getEligibilityState() { return eligibilityState; }
    public void setEligibilityState(String eligibilityState) { this.eligibilityState = eligibilityState; }
    public boolean getWaitlistJoinAllowed() { return waitlistJoinAllowed; }
    public void setWaitlistJoinAllowed(boolean waitlistJoinAllowed) {
        this.waitlistJoinAllowed = waitlistJoinAllowed;
    }
}
