package com.chengyinhub.business.mapper.channel.platform;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.service.channel.platform.ChannelRow;

/** 频道平台持久层：im_channel(#726) + 覆盖/角色/回执/操作日志 + 空间成员真源只读查询。 */
public interface ChannelPlatformMapper
{
    // ---- 空间真源（只读） ----
    Map<String, Object> selectClubGuild(@Param("clubId") long clubId);
    int countClubMember(@Param("clubId") long clubId, @Param("memberId") long memberId);
    int countClubAdmin(@Param("clubId") long clubId, @Param("memberId") long memberId);
    Map<String, Object> selectTeamGuild(@Param("teamId") long teamId);
    int countTeamMember(@Param("teamId") long teamId, @Param("memberId") long memberId);
    List<Map<String, Object>> selectGuildMembers(@Param("guildKind") int guildKind, @Param("guildId") long guildId,
            @Param("afterMemberId") long afterMemberId, @Param("limit") int limit);
    int countGuildMembers(@Param("guildKind") int guildKind, @Param("guildId") long guildId);

    // ---- 频道 ----
    ChannelRow selectChannel(@Param("id") long id);
    ChannelRow lockChannel(@Param("id") long id);
    List<ChannelRow> selectGuildChannels(@Param("guildKind") int guildKind, @Param("guildId") long guildId,
            @Param("includeDeleted") boolean includeDeleted);
    int countLiveChildren(@Param("parentId") long parentId);
    int nextPosition(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("parentId") long parentId);
    int insertConversation(Map<String, Object> row);
    int insertChannel(ChannelRow row);
    int updateMetadata(@Param("id") long id, @Param("expected") long expected, @Param("name") String name,
            @Param("topic") String topic, @Param("setTopic") boolean setTopic, @Param("visibility") Integer visibility,
            @Param("slowmodeSec") Integer slowmodeSec);
    int updateLifecycle(@Param("id") long id, @Param("expected") long expected, @Param("fromStatus") int fromStatus,
            @Param("toStatus") int toStatus, @Param("archiveReason") String archiveReason,
            @Param("purgeAt") Date purgeAt, @Param("deleteReason") String deleteReason);
    int bumpPermissionVersion(@Param("id") long id, @Param("expected") long expected,
            @Param("expectedPerm") long expectedPerm);
    int setConversationStatus(@Param("id") long id, @Param("status") int status);

    // ---- 覆盖与角色 ----
    List<Map<String, Object>> selectOverwrites(@Param("channelId") long channelId);
    List<Map<String, Object>> selectOverwritesOf(@Param("channelIds") List<Long> channelIds);
    int upsertOverwrite(@Param("channelId") long channelId, @Param("targetType") int targetType,
            @Param("targetId") long targetId, @Param("allow") long allow, @Param("deny") long deny,
            @Param("note") String note, @Param("operatorId") long operatorId);
    int deleteOverwrite(@Param("channelId") long channelId, @Param("targetType") int targetType, @Param("targetId") long targetId);
    List<Map<String, Object>> selectGuildRoles(@Param("guildKind") int guildKind, @Param("guildId") long guildId,
            @Param("includeDisabled") boolean includeDisabled);
    List<Map<String, Object>> selectMemberRoles(@Param("guildKind") int guildKind, @Param("guildId") long guildId,
            @Param("memberId") long memberId);
    int countGuildRole(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("roleId") long roleId);

    // ---- 回执与日志 ----
    int reserveReceipt(@Param("operatorId") long operatorId, @Param("family") String family,
            @Param("requestId") String requestId, @Param("fingerprint") String fingerprint,
            @Param("operation") String operation, @Param("channelId") long channelId);
    Map<String, Object> lockReceipt(@Param("operatorId") long operatorId, @Param("family") String family,
            @Param("requestId") String requestId);
    Map<String, Object> selectReceipt(@Param("operatorId") long operatorId, @Param("family") String family,
            @Param("requestId") String requestId);
    int finishReceipt(@Param("operatorId") long operatorId, @Param("family") String family,
            @Param("requestId") String requestId, @Param("response") String response);
    int insertOpLog(Map<String, Object> row);
    List<Map<String, Object>> selectOpLog(@Param("channelId") long channelId, @Param("beforeId") Long beforeId,
            @Param("limit") int limit);

    // ---- 会话索引 / 未读 ----
    int ensureConversationMember(@Param("conversationId") long conversationId, @Param("memberId") long memberId);
    List<Map<String, Object>> selectUnread(@Param("memberId") long memberId, @Param("conversationIds") List<Long> conversationIds);
    Map<String, Object> selectReadState(@Param("memberId") long memberId, @Param("conversationId") long conversationId);
    Integer selectConversationType(@Param("id") long id);
}
