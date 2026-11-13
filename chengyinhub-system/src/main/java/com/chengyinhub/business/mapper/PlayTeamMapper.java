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
    int rotateInviteCode(@Param("teamId") Long teamId, @Param("leaderMemberId") Long leaderMemberId,
                         @Param("inviteCode") String inviteCode);
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
    int hasCurrentNpcRegistration(@Param("memberId") Long memberId, @Param("topicId") Long topicId);
    List<PlayTeam> selectMyTeams(Long memberId);
    /** 第九轮 m2:同场路线。给定成员里仍在这一场(已付费、报名有效)的人,带他所在的本场队伍(没有队伍为 null)。 */
    List<Map<String, Object>> selectTrailParticipants(@Param("activityId") Long activityId, @Param("memberIds") java.util.Collection<Long> memberIds);
    List<Long> selectExpiredTeamIds(@Param("now") Date now, @Param("limit") int limit);
    int endExpiredTeam(@Param("teamId") Long teamId, @Param("now") Date now);
    PlayTeamMember selectMemberRow(@Param("teamId") Long teamId, @Param("memberId") Long memberId);
    int reapplyMember(@Param("teamId") Long teamId, @Param("memberId") Long memberId,
                      @Param("fromStatus") Integer fromStatus, @Param("applyMessage") String applyMessage,
                      @Param("applyExpireTime") Date applyExpireTime);
    int rejectPending(@Param("teamId") Long teamId, @Param("memberId") Long memberId, @Param("now") Date now);
    int approvePending(@Param("teamId") Long teamId, @Param("memberId") Long memberId, @Param("now") Date now);
    int expirePendingApplications(Long teamId);
    /**
     * 席位投影:已加入成员中仍持本场次已支付报名(owner_type=2,payment_status=2,registration_status=2)的去重人数。
     * 只读事实,不写订单/权益/退款;票款与参与身份因此不能互相冒充。
     */
    int countPaidActiveMembers(@Param("teamId") Long teamId);
    /** 未过期待审申请数。申请不占席:不参与 reserveSlot 判定,也不计入已领取席位。 */
    int countOpenApplications(@Param("teamId") Long teamId, @Param("now") Date now);
    int expireStaleApplications(@Param("now") Date now);
    int hideFromPublic(Long teamId);
    int countOpenReports(Long teamId);
    List<PlayTeamMember> selectPendingApplications(@Param("teamId") Long teamId, @Param("now") Date now);
    List<Map<String, Object>> selectMyApplications(@Param("memberId") Long memberId, @Param("now") Date now);
}
