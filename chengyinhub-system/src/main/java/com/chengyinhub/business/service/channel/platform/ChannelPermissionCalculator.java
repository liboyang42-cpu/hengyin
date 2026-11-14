package com.chengyinhub.business.service.channel.platform;

import java.util.Collection;

import static com.chengyinhub.business.service.channel.platform.ChannelCapability.*;

/**
 * 有效权限的纯函数裁决，不读库：空间关系（非成员/成员/管理员/主理人）由调用方按实时真源给出。
 * 顺序：空间基线 → 角色位 → @everyone 覆盖 → 角色覆盖（合并 allow/deny）→ 生命周期收口。
 */
public final class ChannelPermissionCalculator
{
    public enum GuildStanding { NONE, MEMBER, ADMIN, OWNER }

    public static final long MEMBER_BASE = of(VIEW_CHANNEL, SEND_MESSAGE, ATTACH_FILE, EMBED_LINK, ADD_REACTION,
            READ_HISTORY, CREATE_POLL);
    public static final long ADMIN_BASE = MEMBER_BASE | of(MENTION_EVERYONE, PIN_MESSAGE, MANAGE_MESSAGE, MANAGE_CHANNEL,
            MANAGE_ROLE, KICK_MEMBER, BAN_MEMBER, TIMEOUT_MEMBER, MANAGE_RULE, VIEW_AUDIT, BYPASS_SLOWMODE, MANAGE_PROGRESS);
    /** 归档后只读：发言、附件、互动、置顶与消息管理全部收回。 */
    public static final long WRITE_BITS = of(SEND_MESSAGE, ATTACH_FILE, EMBED_LINK, ADD_REACTION, MENTION_EVERYONE,
            CREATE_POLL, PIN_MESSAGE, MANAGE_MESSAGE);

    /** 一条覆盖；targetType 1=@everyone 2=角色。 */
    public record Overwrite(int targetType, long targetId, long allow, long deny) { }

    private ChannelPermissionCalculator() { }

    public static long guildBase(GuildStanding standing, long rolePermissions)
    {
        return switch (standing)
        {
            case NONE -> 0L;
            case OWNER -> all();
            case ADMIN -> ADMIN_BASE | (rolePermissions & all());
            case MEMBER -> MEMBER_BASE | (rolePermissions & all());
        };
    }

    /**
     * @param memberRoleIds 该成员当前持有的启用角色
     * @param status        频道状态 0 正常 1 归档；其余（封禁/删除）一律无权限
     */
    public static long effective(GuildStanding standing, long rolePermissions, Collection<Long> memberRoleIds,
            Collection<Overwrite> overwrites, int status)
    {
        if (standing == GuildStanding.NONE || status < 0 || status > 1) return 0L;
        long bits = guildBase(standing, rolePermissions);
        boolean bypass = standing == GuildStanding.OWNER || (bits & ADMINISTRATOR.mask()) != 0;
        if (!bypass && overwrites != null)
        {
            for (Overwrite o : overwrites)
            {
                if (o.targetType() == 1) bits = (bits & ~o.deny()) | (o.allow() & ~guildMask());
            }
            long allow = 0, deny = 0;
            for (Overwrite o : overwrites)
            {
                if (o.targetType() == 2 && memberRoleIds != null && memberRoleIds.contains(o.targetId()))
                {
                    allow |= o.allow();
                    deny |= o.deny();
                }
            }
            bits = (bits & ~deny) | (allow & ~guildMask());
        }
        // 看不到频道就没有任何频道内能力；空间级管理位不受单频道 VIEW 影响。
        if ((bits & VIEW_CHANNEL.mask()) == 0) bits &= guildMask();
        if (status == 1) bits &= ~WRITE_BITS;
        return bits & all();
    }

    public static boolean has(long bits, ChannelCapability capability) { return (bits & capability.mask()) != 0; }
}
