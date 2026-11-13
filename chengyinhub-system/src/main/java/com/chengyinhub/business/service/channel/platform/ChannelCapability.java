package com.chengyinhub.business.service.channel.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * App 能力表（NativeChannelManagementCapability）的服务端镜像；位号显式固定，不从枚举顺序推断。
 * 26 为 MANAGE_BOUNTY 保留位，19–22、24 为未开放能力，均不会出现在有效权限里。
 */
public enum ChannelCapability
{
    VIEW_CHANNEL(0, false), SEND_MESSAGE(1, false), ATTACH_FILE(2, false), EMBED_LINK(3, false),
    ADD_REACTION(4, false), READ_HISTORY(5, false), MENTION_EVERYONE(6, false), CREATE_POLL(7, false),
    PIN_MESSAGE(8, false), MANAGE_MESSAGE(9, false), MANAGE_CHANNEL(10, true), MANAGE_ROLE(11, true),
    KICK_MEMBER(12, true), BAN_MEMBER(13, true), TIMEOUT_MEMBER(14, false), MANAGE_BOT(15, true),
    MANAGE_RULE(16, false), VIEW_AUDIT(17, true), ADMINISTRATOR(18, true), BYPASS_SLOWMODE(23, false),
    MANAGE_WEBHOOKS(25, true), MANAGE_PROGRESS(27, true);

    /** App 侧解析上限 0x3ffffff；MANAGE_PROGRESS(27) 只出现在名字列表，不进覆盖位图。 */
    public static final long OVERWRITE_MAX = 0x3ffffffL;

    private final int bit;
    private final boolean guildScoped;

    ChannelCapability(int bit, boolean guildScoped)
    {
        this.bit = bit;
        this.guildScoped = guildScoped;
    }

    public long mask() { return 1L << bit; }
    public boolean guildScoped() { return guildScoped; }

    public static long all()
    {
        long value = 0;
        for (ChannelCapability c : values()) value |= c.mask();
        return value;
    }

    public static long guildMask()
    {
        long value = 0;
        for (ChannelCapability c : values()) if (c.guildScoped) value |= c.mask();
        return value;
    }

    public static long of(ChannelCapability... capabilities)
    {
        long value = 0;
        for (ChannelCapability c : capabilities) value |= c.mask();
        return value;
    }

    public static List<String> names(long bits)
    {
        List<String> names = new ArrayList<>();
        for (ChannelCapability c : values()) if ((bits & c.mask()) != 0) names.add(c.name());
        return Collections.unmodifiableList(names);
    }

    public static String hex(long bits) { return "0x" + Long.toHexString(bits); }

    /** 只接受 App 写出的 "0x…" 形式，超出覆盖上限或格式不对返回 null。 */
    public static Long parseHex(String text)
    {
        if (text == null || !text.matches("0x[0-9a-fA-F]{1,16}")) return null;
        try
        {
            long value = Long.parseUnsignedLong(text.substring(2), 16);
            return value < 0 || value > OVERWRITE_MAX ? null : value;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
