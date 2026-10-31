package com.chengyinhub.business.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 主题画面风格（cms_topic.look_json）：风格 + 该风格自己的参数 + 转场 + 故事流字体，全部是固定枚举。
 *
 * <p>⚠️ 取值域与小程序 {@code pages/play/utils/theme-look.js}（及 publish 分包的同名副本）是同一份口径，
 * 改一边必须改另一边。写入时严格校验、规范成紧凑 JSON；空串表示“用默认风格”。</p>
 */
public final class ThemeLook
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LENGTH = 1024;

    /** 风格 → 参数 → 可选值。顺序即规范输出顺序。 */
    private static final Map<String, Map<String, Set<String>>> STYLES;
    static
    {
        Map<String, Map<String, Set<String>>> s = new LinkedHashMap<>();
        s.put("STARDUST", params(
                "density", set("SPARSE", "NORMAL", "DENSE"),
                "drift", set("SLOW", "NORMAL", "FAST"),
                "tone", set("WHITE", "WARM", "COOL")));
        s.put("PAPER", params(
                "paper", set("KRAFT", "XUAN", "CARD"),
                "grain", set("LIGHT", "MEDIUM", "HEAVY"),
                "stamp", set("VERIFIED", "VISITED", "SEAL")));
        s.put("SURVIVAL", params(
                "climate", set("CLEAR", "RAIN", "STORM", "SAND"),
                "volatility", set("CALM", "SHIFTING", "VIOLENT"),
                "edge", set("HP", "FIXED", "OFF")));
        s.put("CYBER", params(
                "neon", set("YELLOW_CYAN", "CYAN_PINK", "RED_CYAN"),
                "current", set("LOW", "MID", "HIGH"),
                "glitch", set("TRANSITION", "OFF")));
        STYLES = Collections.unmodifiableMap(s);
    }
    private static final Set<String> TRANSITIONS = set("AUTO", "FADE", "IRIS", "PAGE", "WIPE", "SHUTTER", "PIXEL", "WARP",
            "GUST", "STATIC", "GLITCH", "SURGE", "INK", "CURTAIN", "ZOOM");
    private static final Set<String> STORY_FONTS = set("HAND", "SONG", "KAI", "HEI", "BOLD");
    /** 主题配色:与章节配色同一套五档;不传 = 各章沿用自己的章节配色(老主题外观不变)。 */
    private static final Set<String> PALETTES = set("DEFAULT", "BLUE", "RED", "YELLOW", "WHITE");
    /** 末世每章的时间段,按章节顺序;空串 = 该章不设。 */
    private static final Set<String> TIMES = set("DAWN", "NOON", "DUSK", "NIGHT");
    private static final int MAX_TIMES = 60;
    /** 故事文字动效:作者选一种;不传 = 跟随风格(按风格的池子逐段轮换)。取值小写,与小程序 theme-look.js TEXT_FX 同口径。 */
    private static final Set<String> TEXT_FX = new HashSet<>(Arrays.asList("rise", "type", "ink", "gather", "glow", "wipe",
            "drop", "wind", "signal", "fog", "neon", "scan", "decode"));
    private static final List<String> ROOT = Arrays.asList("style", "params", "transition", "storyFont", "palette", "times", "textFx");

    private ThemeLook()
    {
    }

    /**
     * 写入前规范化。null = 本次没传（调用方保持原值）；空白 = 恢复默认，存空串；否则严格校验后返回规范 JSON。
     *
     * @throws IllegalArgumentException 任何未知字段或取值
     */
    public static String normalize(String raw)
    {
        if (raw == null)
        {
            return null;
        }
        if (raw.trim().isEmpty())
        {
            return "";
        }
        if (raw.length() > MAX_LENGTH)
        {
            throw new IllegalArgumentException("画面风格配置过长");
        }
        JsonNode node;
        try
        {
            node = JSON.readTree(raw);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("画面风格配置格式不正确");
        }
        if (node == null || !node.isObject())
        {
            throw new IllegalArgumentException("画面风格配置格式不正确");
        }
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); )
        {
            String field = it.next();
            if (!ROOT.contains(field))
            {
                throw new IllegalArgumentException("画面风格不支持字段:" + field);
            }
        }
        String style = upper(node.path("style"));
        Map<String, Set<String>> spec = STYLES.get(style);
        if (spec == null)
        {
            throw new IllegalArgumentException("画面风格不合法:" + style);
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("style", style);
        ObjectNode params = out.putObject("params");
        JsonNode given = node.path("params");
        if (!given.isMissingNode() && !given.isNull() && !given.isObject())
        {
            throw new IllegalArgumentException("画面风格参数格式不正确");
        }
        for (Iterator<String> it = given.fieldNames(); it.hasNext(); )
        {
            String key = it.next();
            if (!spec.containsKey(key))
            {
                throw new IllegalArgumentException("该风格不支持参数:" + key);
            }
        }
        for (Map.Entry<String, Set<String>> p : spec.entrySet())
        {
            JsonNode v = given.path(p.getKey());
            if (v.isMissingNode() || v.isNull())
            {
                continue;
            }
            String value = upper(v);
            if (!p.getValue().contains(value))
            {
                throw new IllegalArgumentException("画面风格参数不合法:" + p.getKey() + "=" + value);
            }
            params.put(p.getKey(), value);
        }
        out.put("transition", option(node.path("transition"), TRANSITIONS, "AUTO", "转场"));
        out.put("storyFont", option(node.path("storyFont"), STORY_FONTS, "HAND", "故事字体"));
        JsonNode palette = node.path("palette");
        if (!palette.isMissingNode() && !palette.isNull() && !upper(palette).isEmpty())
        {
            out.put("palette", option(palette, PALETTES, "DEFAULT", "主题配色"));
        }
        JsonNode textFx = node.path("textFx");
        if (!textFx.isMissingNode() && !textFx.isNull())
        {
            if (!textFx.isTextual())
            {
                throw new IllegalArgumentException("画面风格取值必须是文本");
            }
            String fx = textFx.asText().trim().toLowerCase(Locale.ROOT);
            if (!fx.isEmpty())
            {
                if (!TEXT_FX.contains(fx))
                {
                    throw new IllegalArgumentException("文字动效不合法:" + fx);
                }
                out.put("textFx", fx);
            }
        }
        JsonNode times = node.path("times");
        if (!times.isMissingNode() && !times.isNull())
        {
            if (!"SURVIVAL".equals(style) || !times.isArray() || times.size() > MAX_TIMES)
            {
                throw new IllegalArgumentException("章节时间段格式不正确");
            }
            ArrayNode list = JSON.createArrayNode();
            int last = -1;
            for (int i = 0; i < times.size(); i++)
            {
                String t = upper(times.get(i));
                if (!t.isEmpty() && !TIMES.contains(t))
                {
                    throw new IllegalArgumentException("章节时间段不合法:" + t);
                }
                list.add(t);
                if (!t.isEmpty())
                {
                    last = i;
                }
            }
            if (last >= 0)
            {
                ArrayNode trimmed = out.putArray("times");
                for (int i = 0; i <= last; i++)
                {
                    trimmed.add(list.get(i).asText());
                }
            }
        }
        return out.toString();
    }

    private static String option(JsonNode v, Set<String> allowed, String fallback, String label)
    {
        if (v.isMissingNode() || v.isNull())
        {
            return fallback;
        }
        String value = upper(v);
        if (!allowed.contains(value))
        {
            throw new IllegalArgumentException(label + "不合法:" + value);
        }
        return value;
    }

    private static String upper(JsonNode v)
    {
        if (!v.isTextual())
        {
            throw new IllegalArgumentException("画面风格取值必须是文本");
        }
        return v.asText().trim().toUpperCase(Locale.ROOT);
    }

    private static Set<String> set(String... values)
    {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> params(Object... kv)
    {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put((String) kv[i], (Set<String>) kv[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }
}
