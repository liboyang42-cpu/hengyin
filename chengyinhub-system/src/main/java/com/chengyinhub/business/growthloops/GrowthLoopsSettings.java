package com.chengyinhub.business.growthloops;

import java.time.LocalTime;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.system.service.ISysConfigService;

/**
 * R11 growth-loops 运营配置（sys_config）。缺失、格式不对、越界一律回落到默认值，不抛错。
 * 种子见 chengyinhub-admin/sql/migration_20261115_growth_loops.sql。
 *
 * <p>召回默认 OFF：营销性质的推送要业主拍板再开（App Store 4.5.4）。SHADOW 只算候选、写日志，不发。</p>
 */
@Component
public class GrowthLoopsSettings
{
    public enum Mode { OFF, SHADOW, ON }

    public static final String KEY_WINBACK_MODE = "growth.winback.mode";
    public static final String KEY_WINBACK_GAP_DAYS = "growth.winback.minGapDays";
    public static final String KEY_WINBACK_MAX_IGNORED = "growth.winback.maxIgnored";
    public static final String KEY_WINBACK_LAPSED_DAYS = "growth.winback.lapsedDays";
    public static final String KEY_WINBACK_QUIET_START = "growth.winback.quietStart";
    public static final String KEY_WINBACK_QUIET_END = "growth.winback.quietEnd";
    public static final String KEY_WINBACK_TZ = "growth.winback.timezone";
    public static final String KEY_WINBACK_STREAK_FROM_HOUR = "growth.winback.streakFromHour";
    public static final String KEY_WINBACK_BATCH = "growth.winback.batch";
    public static final String KEY_INVITE_REWARD_ENABLED = "growth.invite.rewardEnabled";
    public static final String KEY_INVITE_MONTHLY_CAP = "growth.invite.monthlyCapPerInviter";
    public static final String KEY_APP_STORE_URL = "growth.app.appStoreUrl";
    public static final String KEY_APP_LINK_BASE = "growth.app.universalLinkBase";

    /** 编译期上限：配置再宽松，一周最多一条、最多容忍两次没回应。 */
    public static final int MIN_GAP_DAYS_FLOOR = 7;
    public static final int MAX_IGNORED_CEILING = 2;

    @Autowired(required = false)
    private ISysConfigService configService;

    public GrowthLoopsSettings() {}

    public GrowthLoopsSettings(ISysConfigService configService) { this.configService = configService; }

    public Mode winbackMode()
    {
        String raw = text(KEY_WINBACK_MODE);
        if (raw == null) return Mode.OFF;
        try { return Mode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { return Mode.OFF; }
    }

    /** 两条召回之间至少隔几天：默认 7，配置更小也按 7。 */
    public int minGapDays() { return Math.max(MIN_GAP_DAYS_FLOOR, number(KEY_WINBACK_GAP_DAYS, 7, 1, 90)); }

    /** 连续几次没回应后不再发：默认 2，配置更大也按 2。 */
    public int maxIgnored() { return Math.min(MAX_IGNORED_CEILING, number(KEY_WINBACK_MAX_IGNORED, 2, 1, 10)); }

    /** 多少天没来算“流失”：默认 7。 */
    public int lapsedDays() { return number(KEY_WINBACK_LAPSED_DAYS, 7, 3, 60); }

    public LocalTime quietStart() { return time(KEY_WINBACK_QUIET_START, LocalTime.of(21, 0)); }

    public LocalTime quietEnd() { return time(KEY_WINBACK_QUIET_END, LocalTime.of(9, 0)); }

    public ZoneId zone()
    {
        String raw = text(KEY_WINBACK_TZ);
        try { return raw == null ? ZoneId.of("Asia/Shanghai") : ZoneId.of(raw.trim()); }
        catch (Exception invalid) { return ZoneId.of("Asia/Shanghai"); }
    }

    /** “连续天数快断了”只在当地这个钟点之后提醒（默认 18 点）。 */
    public int streakFromHour() { return number(KEY_WINBACK_STREAK_FROM_HOUR, 18, 0, 23); }

    public int batch() { return number(KEY_WINBACK_BATCH, 200, 1, 1000); }

    /** 邀请奖励默认开：只发徽章，不涉及现金或积分。 */
    public boolean inviteRewardEnabled()
    {
        String raw = text(KEY_INVITE_REWARD_ENABLED);
        return raw == null || !("false".equalsIgnoreCase(raw.trim()) || "0".equals(raw.trim()));
    }

    /** 每个邀请人每个自然月最多因邀请得奖几次（默认 10）。 */
    public int inviteMonthlyCap() { return number(KEY_INVITE_MONTHLY_CAP, 10, 1, 100); }

    /** App Store 链接；未配置时为 null，页面如实显示“暂未上架”。只接受 https://apps.apple.com/。 */
    public String appStoreUrl()
    {
        String raw = text(KEY_APP_STORE_URL);
        return raw != null && raw.trim().startsWith("https://apps.apple.com/") ? raw.trim() : null;
    }

    /** 通用链接页（App 已装则直接打开 App，否则网页给 App Store 入口）。 */
    public String universalLinkBase()
    {
        String raw = text(KEY_APP_LINK_BASE);
        return raw != null && raw.trim().startsWith("https://") ? raw.trim() : "https://www.chengyinhub.com/door";
    }

    private String text(String key)
    {
        if (configService == null) return null;
        try {
            String raw = configService.selectConfigByKey(key);
            return raw == null || raw.trim().isEmpty() ? null : raw;
        } catch (Exception e) {
            return null;
        }
    }

    private int number(String key, int fallback, int min, int max)
    {
        String raw = text(key);
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            return value < min || value > max ? fallback : value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private LocalTime time(String key, LocalTime fallback)
    {
        String raw = text(key);
        try { return raw == null ? fallback : LocalTime.parse(raw.trim()); }
        catch (Exception invalid) { return fallback; }
    }
}
