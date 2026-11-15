package com.chengyinhub.business.growthloops;

import java.time.LocalTime;
import java.util.Date;

import com.chengyinhub.business.push.MemberNotice;
import com.chengyinhub.business.push.PushMessage;

/**
 * R11 growth-loops 召回规则（纯函数，便于测试）。
 * <ul>
 * <li>频率：两条之间至少隔 {@code gapDays} 天（编译期下限 7）。</li>
 * <li>没回应：发出后 {@code gapDays} 天内没回来算一次“没回应”；累计到 {@code maxIgnored}（编译期上限 2）就不再发。
 *     只要玩家之后回来过（登录、走路、打卡），计数清零。</li>
 * <li>免打扰：全局时段内整轮不发；账号默认的免打扰时段与“通知关闭”由推送扇出再判一次。</li>
 * <li>退订：玩家关掉“回来看看的提醒”后不再发。</li>
 * </ul>
 */
public final class WinbackPolicy
{
    public enum Trigger { STREAK_AT_RISK, CLUB_EVENT, NEW_PLACES }

    public static final String THREAD = "winback";
    public static final long TTL_SECONDS = 12L * 3600L;
    static final long DAY_MS = 24L * 3600L * 1000L;

    private WinbackPolicy() {}

    /** 跨午夜的时段（21:00–09:00）与同日时段（13:00–14:00）都支持；起止相同视为没有免打扰。 */
    public static boolean inQuiet(LocalTime now, LocalTime start, LocalTime end)
    {
        if (now == null || start == null || end == null || start.equals(end)) return false;
        return start.isBefore(end) ? !now.isBefore(start) && now.isBefore(end) : !now.isBefore(start) || now.isBefore(end);
    }

    /** 召回结算后的状态。 */
    public record Resolution(boolean awaiting, int ignored) {}

    /**
     * 上一条发出后，玩家回来了吗？
     * 回来过（最近活跃晚于发送时间）→ 清零；等满 gapDays 天还没回来 → 没回应 +1；否则不变。
     */
    public static Resolution resolve(boolean awaiting, int ignored, Date lastSent, Date lastActive, Date now, int gapDays)
    {
        if (lastSent == null) return new Resolution(false, Math.max(0, ignored));
        if (lastActive != null && lastActive.after(lastSent)) return new Resolution(false, 0);
        if (awaiting && now != null && now.getTime() - lastSent.getTime() >= gapDays * DAY_MS)
            return new Resolution(false, Math.max(0, ignored) + 1);
        return new Resolution(awaiting, Math.max(0, ignored));
    }

    /** 能否再发一条。 */
    public static boolean mayContact(boolean optedOut, int ignored, Date lastSent, Date now, int gapDays, int maxIgnored)
    {
        if (optedOut || ignored >= maxIgnored) return false;
        return lastSent == null || now.getTime() - lastSent.getTime() >= gapDays * DAY_MS;
    }

    /** 本轮该发哪一种：连续天数 → 俱乐部活动 → 新地点。都没有则 null。 */
    public static Trigger choose(boolean streakAtRisk, boolean clubEvent, boolean newPlaces)
    {
        if (streakAtRisk) return Trigger.STREAK_AT_RISK;
        if (clubEvent) return Trigger.CLUB_EVENT;
        if (newPlaces) return Trigger.NEW_PLACES;
        return null;
    }

    public static MemberNotice streakNotice(int streakDays)
    {
        return new MemberNotice(PushMessage.KIND_GROWTH, THREAD, "/subpackageP3/pages/growthcenter/index/index",
                "连续 " + streakDays + " 天，别断了", "今天走一小段，连续记录就能保住。",
                "Keep your " + streakDays + "-day streak", "A short walk today keeps it going.", TTL_SECONDS);
    }

    public static MemberNotice clubEventNotice(long activityId, String name, String whenText)
    {
        String n = clip(name);
        return new MemberNotice(PushMessage.KIND_GROWTH, THREAD, "/pages/activity/detail/index?id=" + activityId,
                "附近有活动", n + (whenText == null ? "" : " · " + whenText + " 开始"),
                "An event near you", n + (whenText == null ? "" : " starts " + whenText), TTL_SECONDS);
    }

    public static MemberNotice newPlacesNotice(long firstPoiId, String firstName, int count)
    {
        String n = clip(firstName);
        int more = Math.max(0, count - 1);
        return new MemberNotice(PushMessage.KIND_GROWTH, THREAD, "/subpackageRoam/poi-detail/index?id=" + firstPoiId,
                "你走过的地方有新去处",
                more > 0 ? n + " 等 " + count + " 个新地点等你去发现。" : n + " 等你去发现。",
                "New places where you've walked",
                more > 0 ? n + " and " + more + " more are waiting for you." : n + " is waiting for you.", TTL_SECONDS);
    }

    static String clip(String text)
    {
        if (text == null || text.isBlank()) return "";
        String t = text.trim();
        return t.codePointCount(0, t.length()) <= 24 ? t : t.substring(0, t.offsetByCodePoints(0, 24)) + "…";
    }
}
