package com.chengyinhub.business.access.club;

public interface IClubAccessService {
    ClubAccessContext resolve(Long principalMemberId, Long clubId, Long activityId);
    /** 与 resolve 相同访问校验，普通 SELECT 主体，不取得支付/UI 读取不需要的行锁。 */
    ClubAccessContext resolveReadOnly(Long principalMemberId, Long clubId, Long activityId);
    ClubAccessContext resolveWithEventAccesses(Long principalMemberId, Long clubId);
    ClubAccessContext require(Long principalMemberId, Long clubId, Long activityId, String permission);
}
