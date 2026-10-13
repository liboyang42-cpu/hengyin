package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubWaitlistEntry;

import java.util.Date;

public interface IClubWaitlistService {
    ClubWaitlistEntry join(Long memberId, Long activityId, Long ticketId);
    ClubWaitlistEntry myStatus(Long memberId, Long activityId, Long ticketId);
    boolean cancel(Long memberId, Long activityId, Long ticketId, Date now);
    boolean promoteNext(Long activityId, Long ticketId, Date now);
    boolean expireOffered(Long entryId, Date now);
    int maintain(Date now);
}
