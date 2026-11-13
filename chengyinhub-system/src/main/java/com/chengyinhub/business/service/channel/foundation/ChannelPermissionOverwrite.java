package com.chengyinhub.business.service.channel.foundation;

/** Structural validation only. Does not resolve permissions or grant access. */
public record ChannelPermissionOverwrite(Target target, long targetId, long allowBits, long denyBits) {
    public static final long ALL_BITS = 0x1FFFFFFL;
    public static final long GUILD_MANAGEMENT_BITS = 0x6BC00L;
    public enum Target { EVERYONE, ROLE, BOT, MEMBER }

    public ChannelPermissionOverwrite {
        if (target == null || (target == Target.EVERYONE ? targetId != 0 : targetId <= 0)) {
            throw new IllegalArgumentException("Invalid overwrite target");
        }
        requireBits(allowBits);
        requireBits(denyBits);
        if ((allowBits & denyBits) != 0) throw new IllegalArgumentException("Allow and deny overlap");
        if ((allowBits & GUILD_MANAGEMENT_BITS) != 0) {
            throw new IllegalArgumentException("Channel overwrite cannot grant guild management");
        }
    }

    /** JSON adapters must emit this as a string, never a role-name authorization. */
    public static String hex(long bits) {
        requireBits(bits);
        return "0x" + Long.toHexString(bits).toUpperCase(java.util.Locale.ROOT);
    }

    private static void requireBits(long bits) {
        if (bits < 0 || (bits & ~ALL_BITS) != 0) throw new IllegalArgumentException("Not a 25-bit permission mask");
    }
}
