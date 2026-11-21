package com.chengyinhub.business.service.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.system.service.ISysConfigService;

/**
 * P7 daily-loop 的运营配置(sys_config)。缺失、非数字、越界一律回落到默认值,不抛错。
 * 种子见 chengyinhub-admin/sql/migration_20261115_daily_loop.sql。
 */
@Component
public class DailyLoopSettings
{
    public static final String KEY_WALK_MIN_METERS = "growth.streak.walkMinMeters";
    public static final String KEY_WALK_MIN_CELLS = "growth.streak.walkMinCells";
    public static final String KEY_FREEZE_EVERY_DAYS = "growth.streak.freezeEveryDays";
    public static final String KEY_FREEZE_MAX = "growth.streak.freezeMax";

    public static final int DEFAULT_WALK_MIN_METERS = 500;
    public static final int DEFAULT_WALK_MIN_CELLS = 10;
    public static final int DEFAULT_FREEZE_EVERY_DAYS = 7;
    public static final int DEFAULT_FREEZE_MAX = 2;

    /** 连续天数徽章阶梯(code = STREAK_&lt;n&gt;,阈值在 growth_badge.unlock_rule_json)。 */
    public static final int[] STREAK_LADDER = { 3, 7, 14, 30 };

    @Autowired(required = false)
    private ISysConfigService configService;

    public DailyLoopSettings() {}

    /** 测试用:不经 Spring 直接给配置源。 */
    public DailyLoopSettings(ISysConfigService configService) { this.configService = configService; }

    /** 一次漫游结算算「今天走过」的最短里程(米)。0 = 不看里程。 */
    public int walkMinMeters() { return read(KEY_WALK_MIN_METERS, DEFAULT_WALK_MIN_METERS, 0, 100_000); }

    /** 一次漫游结算算「今天走过」的最少新点亮格子数。0 = 不看格子。 */
    public int walkMinCells() { return read(KEY_WALK_MIN_CELLS, DEFAULT_WALK_MIN_CELLS, 0, 10_000); }

    public int freezeEveryDays() { return read(KEY_FREEZE_EVERY_DAYS, DEFAULT_FREEZE_EVERY_DAYS, 0, 365); }

    /** 补签卡上限。编译期封顶 5,配置再大也只按 5 算。 */
    public int freezeMax() { return read(KEY_FREEZE_MAX, DEFAULT_FREEZE_MAX, 0, 5); }

    public StreakLedger.Policy policy() { return new StreakLedger.Policy(freezeEveryDays(), freezeMax()); }

    /**
     * 一次结算的漫游是否算数:里程或新格子任一达标即可(两项门槛都配成 0 时,任何已结算漫游都算)。
     */
    public boolean walkQualifies(Integer distanceM, Integer newCells)
    {
        int meters = distanceM == null ? 0 : Math.max(0, distanceM);
        int cells = newCells == null ? 0 : Math.max(0, newCells);
        int minMeters = walkMinMeters();
        int minCells = walkMinCells();
        if (minMeters == 0 && minCells == 0) return true;
        return (minMeters > 0 && meters >= minMeters) || (minCells > 0 && cells >= minCells);
    }

    private int read(String key, int fallback, int min, int max)
    {
        if (configService == null) return fallback;
        try {
            String raw = configService.selectConfigByKey(key);
            if (raw == null || raw.trim().isEmpty()) return fallback;
            int value = Integer.parseInt(raw.trim());
            return value < min || value > max ? fallback : value;
        } catch (Exception e) {
            return fallback;
        }
    }
}
