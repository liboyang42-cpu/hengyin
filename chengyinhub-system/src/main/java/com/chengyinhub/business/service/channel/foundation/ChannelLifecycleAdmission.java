package com.chengyinhub.business.service.channel.foundation;

/**
 * Mandatory channel-lifecycle restrictions, never a permission grant.
 * Call before resolving permissions (including owner/admin shortcuts), using
 * metadata from the same authoritative snapshot as the operation. A successful
 * check still requires existing membership, scope, permission and safety checks.
 * This helper does not decide guild lifecycle, membership or overwrite inheritance.
 */
public final class ChannelLifecycleAdmission {
    private ChannelLifecycleAdmission() { }

    /** Applies before exposing channel names, members, history or search results. */
    public static void requireContentRead(ChannelDefinition channel) {
        if (channel == null || !channel.lifecycleAllowsHistory()) deny();
    }

    /** Applies to message writes, not lifecycle administration such as unarchiving. */
    public static void requireMessageWrite(ChannelDefinition channel) {
        requireContentRead(channel);
        if (!channel.lifecycleAllowsMessageWrite()) deny();
    }

    private static void deny() {
        // Missing, deleted and banned channels have the same non-disclosing failure.
        throw new SecurityException("Channel content unavailable");
    }
}
