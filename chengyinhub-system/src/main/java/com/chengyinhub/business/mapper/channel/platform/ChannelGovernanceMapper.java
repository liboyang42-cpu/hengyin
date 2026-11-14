package com.chengyinhub.business.mapper.channel.platform;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** 频道治理（处置/申诉/举报/本人状态/角色写）持久化原语；授权与 CAS 判定在 ChannelGovernanceService。 */
public interface ChannelGovernanceMapper
{
    int insertAction(Map<String, Object> row);

    Map<String, Object> selectAction(@Param("id") long id);

    Map<String, Object> lockAction(@Param("id") long id);

    int revokeAction(@Param("id") long id, @Param("expectedVersion") long expectedVersion, @Param("operatorId") long operatorId,
            @Param("reason") String reason);

    List<Map<String, Object>> listActions(@Param("guildKind") int guildKind, @Param("guildId") long guildId,
            @Param("targetMemberId") Long targetMemberId, @Param("beforeId") Long beforeId, @Param("limit") int limit);

    int countActiveRestriction(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("channelId") long channelId,
            @Param("memberId") long memberId);

    int countActiveBan(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("memberId") long memberId);

    int insertRecallMutations(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("memberId") long memberId,
            @Param("seconds") int seconds);

    int recallWindow(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("memberId") long memberId,
            @Param("seconds") int seconds);

    int endTeamMembership(@Param("teamId") long teamId, @Param("memberId") long memberId);

    int insertAppeal(Map<String, Object> row);

    Map<String, Object> selectAppeal(@Param("id") long id);

    Map<String, Object> lockAppeal(@Param("id") long id);

    Map<String, Object> selectAppealByAction(@Param("actionId") long actionId, @Param("memberId") long memberId);

    List<Map<String, Object>> listAppeals(@Param("guildKind") int guildKind, @Param("guildId") long guildId,
            @Param("memberId") Long memberId);

    int appendEvidence(@Param("id") long id, @Param("evidence") String evidenceJson);

    int withdrawAppeal(@Param("id") long id, @Param("reason") String reason);

    int insertReport(Map<String, Object> row);

    Map<String, Object> selectMessageForReport(@Param("id") long id);

    Map<String, Object> selectMemberState(@Param("channelId") long channelId, @Param("memberId") long memberId);

    /** field 只允许 pinned/hidden/muted（服务层白名单）。 */
    int upsertMemberState(@Param("channelId") long channelId, @Param("memberId") long memberId, @Param("field") String field,
            @Param("value") int value);

    Map<String, Object> selectRoleRow(@Param("id") long id);

    Map<String, Object> lockRole(@Param("id") long id);

    int countRoleName(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("name") String name,
            @Param("exceptId") long exceptId);

    int countRoles(@Param("guildKind") int guildKind, @Param("guildId") long guildId);

    int insertRole(Map<String, Object> row);

    int updateRole(Map<String, Object> row);

    int deleteRole(@Param("id") long id, @Param("expectedVersion") long expectedVersion);

    int deleteRoleGrants(@Param("roleId") long roleId);

    List<Long> selectMemberRoleIds(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("memberId") long memberId);

    List<Long> selectRoleMemberIds(@Param("roleId") long roleId);

    int grantRole(@Param("roleId") long roleId, @Param("memberId") long memberId, @Param("scopeType") String scopeType,
            @Param("scopeId") long scopeId, @Param("expiresAt") Date expiresAt, @Param("reason") String reason,
            @Param("grantedBy") long grantedBy);

    int revokeRole(@Param("roleId") long roleId, @Param("memberId") long memberId);

    Map<String, Object> selectMessageForAttachment(@Param("id") long id);

    int countConversationMember(@Param("conversationId") long conversationId, @Param("memberId") long memberId);

    Map<String, Object> selectClubWelcome(@Param("clubId") long clubId);
}
