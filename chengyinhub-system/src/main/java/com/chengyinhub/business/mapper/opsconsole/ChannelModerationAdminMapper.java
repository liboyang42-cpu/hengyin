package com.chengyinhub.business.mapper.opsconsole;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** R10 BE-23 平台频道治理读写原语；校验、状态机与审计在 ChannelModerationAdminService。 */
public interface ChannelModerationAdminMapper
{
    List<Map<String, Object>> searchChannels(@Param("keyword") String keyword, @Param("guildKind") Integer guildKind,
                                             @Param("guildId") Long guildId, @Param("status") Integer status);

    Map<String, Object> selectChannel(@Param("id") long id);

    List<Map<String, Object>> searchMessages(@Param("channelId") Long channelId, @Param("senderId") Long senderId,
                                             @Param("keyword") String keyword, @Param("status") Integer status,
                                             @Param("beforeId") Long beforeId, @Param("limit") int limit);

    List<Map<String, Object>> listReports(@Param("status") String status, @Param("channelId") Long channelId,
                                          @Param("beforeId") Long beforeId, @Param("limit") int limit);

    Map<String, Object> lockMessage(@Param("id") long id);

    int setMessageStatus(@Param("id") long id, @Param("from") int from, @Param("to") int to);

    int insertMutation(@Param("conversationId") long conversationId, @Param("messageId") long messageId,
                       @Param("version") long version, @Param("kind") String kind);

    Map<String, Object> lockReport(@Param("id") long id);

    int closeReport(@Param("id") long id, @Param("status") String status);

    int closeReportsForMessage(@Param("messageId") long messageId, @Param("status") String status);

    List<Map<String, Object>> listActions(@Param("guildKind") Integer guildKind, @Param("guildId") Long guildId,
                                          @Param("targetMemberId") Long targetMemberId, @Param("status") String status,
                                          @Param("limit") int limit);

    /** 空间成员判定（俱乐部在册 / 队伍已加入）。 */
    int countClubMember(@Param("clubId") long clubId, @Param("memberId") long memberId);

    int countTeamMember(@Param("teamId") long teamId, @Param("memberId") long memberId);

    Long selectClubOwner(@Param("clubId") long clubId);

    Long selectTeamLeader(@Param("teamId") long teamId);
}
