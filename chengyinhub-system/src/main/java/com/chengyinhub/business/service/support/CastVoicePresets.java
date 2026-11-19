package com.chengyinhub.business.service.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 角色 Cast v1:预设声音 → DashScope CosyVoice 系统音色。全仓只有这一处映射,换音色只改这里。
 * 合成模型沿用 ai.voice.synthModel(默认 cosyvoice-v2),音色 id 必须与该模型匹配。
 */
public final class CastVoicePresets
{
    private static final Map<String, String> VOICES;

    /** 编辑器「试听」念的固定示例句;与小程序 pages/publish/fabu/theme-cast.js 的 SAMPLE_LINE 一致,文本只取服务端这份。 */
    public static final String SAMPLE_LINE = "今晚的风有点凉，我们走吧。";

    static
    {
        Map<String, String> voices = new LinkedHashMap<String, String>();
        voices.put("bright_female", "longxiaochun_v2");
        voices.put("warm_female", "longwan_v2");
        voices.put("deep_male", "longshu_v2");
        voices.put("youth", "longcheng_v2");
        voices.put("elder", "longlaotie_v2");
        VOICES = Collections.unmodifiableMap(voices);
    }

    private CastVoicePresets()
    {
    }

    /** 预设名 → 音色 id;未知预设 → null。 */
    public static String voiceId(String preset)
    {
        return preset == null ? null : VOICES.get(preset);
    }

    public static Map<String, String> all()
    {
        return VOICES;
    }
}
