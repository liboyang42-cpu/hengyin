package com.chengyinhub.business.mapper.channel.platform;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** 空间审计投影（im_channel_audit_log / im_channel_audit_head）持久化原语；链与授权判定在 ChannelAuditLogService。 */
public interface ChannelAuditMapper
{
    int ensureHead(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("genesis") String genesis);

    Map<String, Object> lockHead(@Param("guildKind") int guildKind, @Param("guildId") long guildId);

    int updateHead(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("headId") long headId,
            @Param("headHash") String headHash);

    List<Map<String, Object>> pendingChannelOps(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("limit") int limit);

    List<Map<String, Object>> pendingModerations(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("limit") int limit);

    List<Map<String, Object>> pendingRevocations(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("limit") int limit);

    long countUnprojected(@Param("guildKind") int guildKind, @Param("guildId") long guildId);

    int insertRow(Map<String, Object> row);

    List<Map<String, Object>> listRows(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("beforeId") Long beforeId,
            @Param("limit") int limit);

    List<Map<String, Object>> chainRows(@Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("afterId") long afterId,
            @Param("limit") int limit);
}
