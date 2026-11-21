package com.chengyinhub.business.push;

import java.util.Map;

import com.chengyinhub.business.service.channel.platform.ChannelNotifyService;

/**
 * 推送是否该发：完全沿用既有通知偏好（im_notify_pref / im_conversation_member.muted / im_club_notify_default）。
 * <ul>
 * <li>会话免打扰（旧 muted 或 永久/定时静音）或处于免打扰时段：不推，@ 也不推（与未读角标口径一致）。</li>
 * <li>有效等级：会话级（≠3 继承）→ 账号默认 → 频道默认（im_channel.notify_default，公告/活动为 0 全部）
 *     →（俱乐部频道/群）俱乐部共享默认（未配置为 1 仅@）→ 0 全部。</li>
 * <li>等级 0 全部推；1 只推 @我 或 @全体（本人开了“屏蔽 @全体”时 @全体 不算）；2 不推。</li>
 * </ul>
 */
public final class PushPolicy
{
    private PushPolicy() { }

    public static boolean chatAllowed(Map<String, Object> row, boolean mentionedMe, boolean mentionedEveryone, Integer clubDefaultLevel)
    {
        return chatAllowed(row, mentionedMe, mentionedEveryone, null, clubDefaultLevel);
    }

    /** W7 x-channel-reach (SOC-2): the channel's own default sits between the account default and the club default. */
    public static boolean chatAllowed(Map<String, Object> row, boolean mentionedMe, boolean mentionedEveryone,
            Integer channelDefaultLevel, Integer clubDefaultLevel)
    {
        if (row == null) return false;
        if (flag(row.get("memberMuted")) || flag(row.get("convPermanent")) || flag(row.get("convMutedActive"))) return false;
        if (quiet(row, "convQuiet") || quiet(row, "accountQuiet")) return false;
        int level = effectiveLevel(integer(row.get("convLevel")), integer(row.get("accountLevel")), channelDefaultLevel, clubDefaultLevel);
        boolean mention = mentionedMe || (mentionedEveryone && !flag(row.get("suppressBroadcast")));
        return level == 0 || (level == 1 && mention);
    }

    static int effectiveLevel(Integer conversationLevel, Integer accountLevel, Integer clubDefaultLevel)
    {
        return effectiveLevel(conversationLevel, accountLevel, null, clubDefaultLevel);
    }

    static int effectiveLevel(Integer conversationLevel, Integer accountLevel, Integer channelDefaultLevel, Integer clubDefaultLevel)
    {
        if (conversationLevel != null && conversationLevel != 3) return conversationLevel;
        if (accountLevel != null && accountLevel != 3) return accountLevel;
        if (channelDefaultLevel != null && channelDefaultLevel >= 0 && channelDefaultLevel <= 2) return channelDefaultLevel;
        if (clubDefaultLevel != null) return clubDefaultLevel;
        return 0;
    }

    /** 会话级 show_preview 优先，其次账号默认；都没设置时显示预览。 */
    public static boolean showPreview(Map<String, Object> row)
    {
        Integer conv = integer(row.get("convShowPreview"));
        if (conv != null) return conv == 1;
        Integer account = integer(row.get("accountShowPreview"));
        return account == null || account == 1;
    }

    /** 俱乐部通知 / 活动提醒：账号默认 2=关闭或免打扰时段不推；俱乐部范围静音、关闭或免打扰时段不推。 */
    public static boolean noticeAllowed(Map<String, Object> accountPref, Map<String, Object> clubPref)
    {
        if (accountPref != null && (Integer.valueOf(2).equals(integer(accountPref.get("level"))) || quiet(accountPref, "quiet"))) return false;
        if (clubPref != null && (flag(clubPref.get("permanentMuted")) || flag(clubPref.get("mutedActive"))
                || Integer.valueOf(2).equals(integer(clubPref.get("level"))) || quiet(clubPref, "quiet"))) return false;
        return true;
    }

    private static boolean quiet(Map<String, Object> row, String prefix)
    {
        Object s = row.get(prefix + "Start"), e = row.get(prefix + "End"), z = row.get(prefix + "Tz");
        return s != null && e != null && z != null && ChannelNotifyService.inQuiet(s.toString(), e.toString(), z.toString());
    }

    static boolean flag(Object value)
    {
        Integer v = integer(value);
        return v != null && v == 1;
    }

    static Integer integer(Object value)
    {
        if (value == null) return null;
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.valueOf(value.toString().trim()); }
        catch (NumberFormatException invalid) { return null; }
    }
}
