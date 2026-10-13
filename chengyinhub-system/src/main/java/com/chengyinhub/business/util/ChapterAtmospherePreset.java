package com.chengyinhub.business.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 章节配色只允许固定枚举，禁止把任意颜色值变成内容数据。
 *
 * <p>2026-09-06 用户拍板换成 黑 / 蓝 / 红 / 黄 / 白 五档**纯色**（原来是 5 档带渐变的氛围）。
 * 取值域跟着变，但**存量不能读废**：库里已经存着 NIGHT / ARCHIVE / MOSS / NEON。
 * {@code LEGACY_ALIAS} 把它们收敛到最接近的新档（苔野没有对应色，落回默认黑），
 * 于是老数据既读得出来、再保存时也会被写成新的规范值。</p>
 *
 * <p>⚠️ 这张别名表与小程序的 {@code utils/chapter-atmosphere.js} 是**同一份口径**，
 * 改一边必须改另一边 —— 两侧不一致会让同一条数据在发布器与玩家端显示成两个颜色。</p>
 */
public final class ChapterAtmospherePreset
{
    public static final String DEFAULT = "DEFAULT";
    private static final Set<String> VALUES = new HashSet<>(Arrays.asList(
            DEFAULT, "BLUE", "RED", "YELLOW", "WHITE"));

    /** 存量 → 新档。与小程序 utils/chapter-atmosphere.js 的 LEGACY_ALIAS 一一对应。 */
    private static final Map<String, String> LEGACY_ALIAS;
    static
    {
        Map<String, String> alias = new HashMap<>();
        alias.put("NIGHT", "BLUE");      // 靛蓝夜路 → 蓝
        alias.put("ARCHIVE", "YELLOW");  // 旧档案纸 → 黄
        alias.put("NEON", "RED");        // 夜城灯牌 → 红
        alias.put("MOSS", DEFAULT);      // 湿润绿影 —— 新色板里没有绿，落回默认黑
        LEGACY_ALIAS = Collections.unmodifiableMap(alias);
    }

    private ChapterAtmospherePreset()
    {
    }

    public static String requireValid(String value)
    {
        String normalized = value == null ? DEFAULT : value.trim().toUpperCase(Locale.ROOT);
        if (VALUES.contains(normalized))
        {
            return normalized;
        }
        // 存量别名照收，写回去的是新的规范值 —— 否则一条老章节一保存就抛，编辑不了。
        String aliased = LEGACY_ALIAS.get(normalized);
        if (aliased != null)
        {
            return aliased;
        }
        throw new IllegalArgumentException("章节配色预设不合法:" + value);
    }

    public static String normalizeForRead(String value)
    {
        try
        {
            return requireValid(value);
        }
        catch (IllegalArgumentException ignored)
        {
            return DEFAULT;
        }
    }
}
