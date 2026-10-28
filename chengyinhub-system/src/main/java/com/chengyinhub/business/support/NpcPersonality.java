package com.chengyinhub.business.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 门店 AI 角色的性格预设(M-28,2026-10-27 已确认:温柔 / 可爱 / 毒舌)。
 *
 * <p>persona 列存预设编码;旧版商家自由填写的人设文本是 legacy,原样保留、原样喂给对话。
 * 保存端与对话读取端都经这里换算,两边认同一份编码,不会出现「存了但对话里没生效」。
 */
public final class NpcPersonality {

    public static final String GENTLE = "gentle";
    public static final String CUTE = "cute";
    public static final String SAVAGE = "savage";

    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    private static final Map<String, String> TONES = new LinkedHashMap<>();

    static {
        LABELS.put(GENTLE, "温柔");
        LABELS.put(CUTE, "可爱");
        LABELS.put(SAVAGE, "毒舌");
        TONES.put(GENTLE, "说话温柔耐心,先体谅对方的感受,再给出建议。");
        TONES.put(CUTE, "说话活泼可爱、语气轻快,可以偶尔用叠词和语气词。");
        TONES.put(SAVAGE, "说话直接、带点毒舌和调侃,但不做人身攻击、不说脏话,最后仍给出有用的建议。");
    }

    private NpcPersonality() { }

    /** 预设编码或其中文标签 → 编码;其余文本(旧版自由填写)原样返回。null 原样返回。 */
    public static String normalize(String persona) {
        if (persona == null) return null;
        String value = persona.trim();
        for (Map.Entry<String, String> preset : LABELS.entrySet()) {
            if (preset.getKey().equalsIgnoreCase(value) || preset.getValue().equals(value)) return preset.getKey();
        }
        return value;
    }

    public static boolean isPreset(String persona) {
        return persona != null && LABELS.containsKey(persona);
    }

    /** 对话 system prompt 的人设段:预设展开成带角色名的完整人设;legacy 文本原样。 */
    public static String prompt(String name, String persona) {
        if (!isPreset(persona)) return persona;
        String who = name == null || name.trim().isEmpty() ? "这家店的 AI 店铺角色" : "「" + name.trim() + "」,这家店的 AI 店铺角色";
        return "你是" + who + "。性格:" + LABELS.get(persona) + "。" + TONES.get(persona)
                + "回答简短口语化,只聊和本店、到店游玩相关的话题。";
    }
}
