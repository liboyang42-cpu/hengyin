package com.chengyinhub.business.domain;

import java.util.Date;

/** 报名事务内已取得的候补库存凭证。 */
public final class ClubWaitlistClaim {
    private final Long offerId;
    private final Long clubId;
    private final Long memberId;
    private final Long activityId;
    private final Long ticketId;
    private final Date expiresAt;

    public ClubWaitlistClaim(Long offerId, Long memberId, Long activityId, Long ticketId, Date expiresAt) {
        this(offerId, null, memberId, activityId, ticketId, expiresAt);
    }

    public ClubWaitlistClaim(Long offerId, Long clubId, Long memberId, Long activityId,
                             Long ticketId, Date expiresAt) {
        this.offerId = offerId;
        this.clubId = clubId;
        this.memberId = memberId;
        this.activityId = activityId;
        this.ticketId = ticketId;
        this.expiresAt = expiresAt;
    }
    public Long getOfferId() { return offerId; }
    public Long getClubId() { return clubId; }
    public Long getMemberId() { return memberId; }
    public Long getActivityId() { return activityId; }
    public Long getTicketId() { return ticketId; }
    public Date getExpiresAt() { return expiresAt; }
}
