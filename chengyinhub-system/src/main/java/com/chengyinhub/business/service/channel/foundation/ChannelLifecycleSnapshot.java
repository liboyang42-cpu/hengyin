package com.chengyinhub.business.service.channel.foundation;

import java.time.LocalDateTime;

/** Internal storage projection. It grants no permission and is not an HTTP response. */
public record ChannelLifecycleSnapshot(long id, int guildKind, long guildId, int status,
        boolean deleted, String archiveReason, LocalDateTime archivedAt, int permissionVersion) { }
