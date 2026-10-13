package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamMember;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface PlayTeamMapper {
    PlayTeam selectPlayTeamById(Long id);
    PlayTeam selectPlayTeamByInviteCode(String inviteCode);
    PlayTeam selectPlayTeamByIdForUpdate(Long id);
    int insertPlayTeam(PlayTeam team);
    int updateJoinMode(@Param("teamId") Long teamId, @Param("leaderMemberId") Long leaderMemberId,
                       @Param("joinMode") Integer joinMode);
    int insertPlayTeamMember(PlayTeamMember member);
    int reactivateMember(@Param("teamId") Long teamId, @Param("memberId") Long memberId);
    PlayTeamMember selectActiveMember(@Param("teamId") Long teamId, @Param("memberId") Long memberId);
    PlayTeamMember selectEarliestOtherActiveMember(@Param("teamId") Long teamId,
                                                    @Param("memberId") Long memberId);
    List<PlayTeamMember> selectActiveMembers(Long teamId);
    int countActiveMembers(Long teamId);
    int reserveSlot(Long teamId);
    int releaseSlot(Long teamId);
    int markMemberStatus(@Param("teamId") Long teamId, @Param("memberId") Long memberId,
                         @Param("fromStatus") Integer fromStatus, @Param("toStatus") Integer toStatus);
    int transferLeadership(@Param("teamId") Long teamId, @Param("oldLeaderId") Long oldLeaderId,
                           @Param("newLeaderId") Long newLeaderId);
    int updateMemberRole(@Param("teamId") Long teamId, @Param("memberId") Long memberId,
                         @Param("fromRole") Integer fromRole, @Param("toRole") Integer toRole);
    int disbandTeam(@Param("teamId") Long teamId, @Param("leaderMemberId") Long leaderMemberId);
    int markActiveMembersExited(Long teamId);
    int hasPaidRegistration(@Param("memberId") Long memberId, @Param("ownerType") Integer ownerType,
                            @Param("ownerId") Long ownerId);
    List<PlayTeam> selectMyTeams(Long memberId);
    List<Long> selectExpiredTeamIds(@Param("now") Date now, @Param("limit") int limit);
    int endExpiredTeam(@Param("teamId") Long teamId, @Param("now") Date now);
    PlayTeamMember selectMemberRow(@Param("teamId") Long teamId, @Param("memberId") Long memberId);
    int reapplyMember(@Param("teamId") Long teamId, @Param("memberId") Long memberId,
                      @Param("fromStatus") Integer fromStatus, @Param("applyMessage") String applyMessage,
                      @Param("applyExpireTime") Date applyExpireTime);
    int rejectPending(@Param("teamId") Long teamId, @Param("memberId") Long memberId, @Param("now") Date now);
    int approvePending(@Param("teamId") Long teamId, @Param("memberId") Long memberId, @Param("now") Date now);
    int expirePendingApplications(Long teamId);
    int expireStaleApplications(@Param("now") Date now);
    int hideFromPublic(Long teamId);
    int countOpenReports(Long teamId);
    List<PlayTeamMember> selectPendingApplications(@Param("teamId") Long teamId, @Param("now") Date now);
    List<Map<String, Object>> selectMyApplications(@Param("memberId") Long memberId, @Param("now") Date now);
}
