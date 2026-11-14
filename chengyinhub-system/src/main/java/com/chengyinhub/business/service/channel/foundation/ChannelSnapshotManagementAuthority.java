package com.chengyinhub.business.service.channel.foundation;

import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Archive-only adapter. Deliberately not auto-registered until a verified SQL source exists. */
public final class ChannelSnapshotManagementAuthority implements ChannelLifecyclePersistence.ManagementAuthority {
    @FunctionalInterface
    public interface CurrentSnapshotSource {
        /**
         * Load and retain locks on current login/member/business/role/safety facts and full
         * ancestry in the caller's transaction. Never accept snapshots, bitmaps, encoding tags
         * or administrator flags from HTTP. Verify mask provenance before FORMAL_25_V1.
         * Missing sources or an expired snapshot must throw, never fall back to cached grants.
         */
        ChannelManagementResolver.Snapshot loadCurrent(long actor, int guildKind, long guildId, long channelId);
    }
    private final CurrentSnapshotSource source;
    public ChannelSnapshotManagementAuthority(CurrentSnapshotSource source) { this.source=Objects.requireNonNull(source); }
    @Override
    public void requireCurrentManagement(long actor,int guildKind,long guildId,long channelId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Writable authority transaction required");
        ChannelManagementResolver.resolve(Objects.requireNonNull(source.loadCurrent(actor,guildKind,guildId,channelId)))
                .requireArchive(actor,guildKind,guildId,channelId);
    }
}
