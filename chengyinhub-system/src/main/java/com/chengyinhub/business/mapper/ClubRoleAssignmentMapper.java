package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubRoleAssignment;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface ClubRoleAssignmentMapper {
    List<ClubRoleAssignment> selectActiveForAccess(@Param("clubId") Long clubId,
                                                    @Param("memberId") Long memberId,
                                                    @Param("activityId") Long activityId,
                                                    @Param("now") Date now);
    List<ClubRoleAssignment> selectActiveEventAccesses(@Param("clubId") Long clubId,
                                                       @Param("memberId") Long memberId,
                                                       @Param("now") Date now);
    List<ClubRoleAssignment> selectActiveByClubAndScope(@Param("clubId") Long clubId,
                                                        @Param("activityId") Long activityId,
                                                        @Param("now") Date now);
    ClubRoleAssignment selectByIdForUpdate(Long id);
    ClubRoleAssignment selectActiveByUniqueScope(@Param("clubId") Long clubId,
                                                  @Param("targetMemberId") Long targetMemberId,
                                                  @Param("scopeType") String scopeType,
                                                  @Param("scopeId") Long scopeId,
                                                 @Param("now") Date now);
    List<ClubRoleAssignment> selectActiveEventLeadsForUpdate(@Param("clubId") Long clubId,
                                                             @Param("activityId") Long activityId);
    int insertClubRoleAssignment(ClubRoleAssignment assignment);
    int expireActiveByScope(@Param("clubId") Long clubId,
                            @Param("targetMemberId") Long targetMemberId,
                            @Param("scopeType") String scopeType,
                            @Param("scopeId") Long scopeId,
                            @Param("now") Date now,
                            @Param("updateBy") String updateBy);
    int revokeCas(@Param("id") Long id, @Param("clubId") Long clubId,
                  @Param("expectedVersion") Integer expectedVersion,
                  @Param("revokedByMemberId") Long revokedByMemberId,
                  @Param("revokeReason") String revokeReason,
                  @Param("updateBy") String updateBy);
    int revokeAllActiveByMember(@Param("clubId") Long clubId,
                                @Param("targetMemberId") Long targetMemberId,
                                @Param("revokedByMemberId") Long revokedByMemberId,
                                @Param("revokeReason") String revokeReason,
                                @Param("now") Date now,
                                @Param("updateBy") String updateBy);
}
