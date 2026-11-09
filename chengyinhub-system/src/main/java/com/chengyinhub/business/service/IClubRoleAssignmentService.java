package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubRoleAssignment;

import java.util.Date;
import java.util.List;

public interface IClubRoleAssignmentService {
    List<ClubRoleAssignment> list(Long principalMemberId, Long clubId, Long activityId);
    ClubRoleAssignment assign(Long principalMemberId, Long clubId, Long targetMemberId,
                              String roleCode, Long activityId, Date expiresAt, String requestId);

    /**
     * 2026-11-02 用户:活动核销员可带「可见客户手机号」开关(默认否)。只对 EVENT_CHECKIN 有效,
     * 其它角色传 true 一律拒绝 —— 他们的号码权限来自固定角色策略,不靠逐条开关。
     */
    default ClubRoleAssignment assign(Long principalMemberId, Long clubId, Long targetMemberId,
                                      String roleCode, Long activityId, Date expiresAt, String requestId,
                                      boolean customerPhoneVisible) {
        if (!customerPhoneVisible) {
            return assign(principalMemberId, clubId, targetMemberId, roleCode, activityId, expiresAt, requestId);
        }
        throw new UnsupportedOperationException("customerPhoneVisible");
    }
    ClubRoleAssignment assignEventLeadForOccurrence(Long actorMemberId, Long clubId,
                                                     Long targetMemberId, Long activityId,
                                                     String requestId);
    int revokeEventLeadForCancelledOccurrence(Long actorMemberId, Long clubId,
                                              Long activityId, String requestId);
    ClubRoleAssignment setPlatformOperatorRole(Long platformUserId, Long clubId,
                                               Long targetMemberId, boolean enabled,
                                               String requestId);
    void revokeLegacyOperator(Long principalMemberId, Long clubId, Long targetMemberId,
                              String reason, String requestId);
    void revoke(Long principalMemberId, Long clubId, Long activityId, Long assignmentId,
                Integer version, String reason, String requestId);
    int revokeAllForAccessEnd(Long clubId, Long targetMemberId, Long actorMemberId,
                              String actorRoleCode, String reason);
}
