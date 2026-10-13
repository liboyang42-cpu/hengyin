package com.chengyinhub.business.access.club;

public interface IClubAccessService {
    ClubAccessContext resolve(Long principalMemberId, Long clubId, Long activityId);
    ClubAccessContext resolveWithEventAccesses(Long principalMemberId, Long clubId);
    ClubAccessContext require(Long principalMemberId, Long clubId, Long activityId, String permission);
}
