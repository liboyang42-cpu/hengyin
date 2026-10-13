package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubRoleAssignment;

import java.util.Date;
import java.util.List;

public interface IClubRoleAssignmentService {
    List<ClubRoleAssignment> list(Long principalMemberId, Long clubId, Long activityId);
    ClubRoleAssignment assign(Long principalMemberId, Long clubId, Long targetMemberId,
                              String roleCode, Long activityId, Date expiresAt, String requestId);
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
