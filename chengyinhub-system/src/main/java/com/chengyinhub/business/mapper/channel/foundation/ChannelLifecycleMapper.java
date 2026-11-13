package com.chengyinhub.business.mapper.channel.foundation;

import com.chengyinhub.business.service.channel.foundation.ChannelLifecycleSnapshot;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.Map;

/** Persistence only: callers must establish current management authority in the same transaction. */
public interface ChannelLifecycleMapper {
    ChannelLifecycleSnapshot lockChannel(@Param("id") long id, @Param("guildKind") int guildKind, @Param("guildId") long guildId);
    int reserveReceipt(@Param("actor") long actor, @Param("requestId") String requestId, @Param("fingerprint") String fingerprint);
    Map<String,Object> lockReceipt(@Param("actor") long actor, @Param("requestId") String requestId);
    int finishReceipt(@Param("actor") long actor, @Param("requestId") String requestId, @Param("response") String response);
    int archiveCas(@Param("row") ChannelLifecycleSnapshot row, @Param("reason") String reason, @Param("now") LocalDateTime now);
    int unarchiveCas(@Param("row") ChannelLifecycleSnapshot row, @Param("now") LocalDateTime now);
    int appendAudit(@Param("row") ChannelLifecycleSnapshot row, @Param("toStatus") int toStatus,
                    @Param("action") String action, @Param("reason") String reason, @Param("actor") long actor,
                    @Param("requestId") String requestId, @Param("now") LocalDateTime now);
}
