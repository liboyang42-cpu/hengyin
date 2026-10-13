package com.chengyinhub.business.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 选项媒体(图片 / 音频)。选项文本仍在 question_a~d 四列上，这里只承载「每个字母对应的图和音」，
 * 按字母 A/B/C/D 对齐 —— 字母本来就是这套模型的键(correct_answer 存的就是字母)。
 *
 * <p>为什么单独一列 JSON 而不是摊成列:四个选项 × (图 + 音) = 8 个新列，且加第五个选项还要再加列。
 *
 * <p>两个方向的口径不同，别混用:
 * <ul>
 *   <li>{@link #sanitize} 写入端 —— 畸形直接抛，不让脏数据进库；同时把未知键剥掉，
 *       保证库里存的形状与 {@link #parse} 读出来的一致。</li>
 *   <li>{@link #parse} 读出端 —— 对库里的历史 / 脏数据免疫，解析失败返回空表。
 *       题面照常下发，只是没有媒体；不能因为一个坏 JSON 让整个节点取不到题。</li>
 * </ul>
 */
public final class QuestionOptionMedia
{
    /** 与 cms_member_template.question_option_media_json 的 varchar(2000) 对齐。 */
    public static final int MAX_JSON_LENGTH = 2000;
    public static final int MAX_URL_LENGTH = 500;

    static final List<String> LETTERS = Collections.unmodifiableList(Arrays.asList("A", "B", "C", "D"));
    static final List<String> MEDIA_KEYS = Collections.unmodifiableList(Arrays.asList("img", "audio"));

    private static final Logger LOG = LoggerFactory.getLogger(QuestionOptionMedia.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OBJECT_TYPE =
            new TypeReference<Map<String, Object>>() { };

    private QuestionOptionMedia()
    {
    }

    /**
     * 写入端校验 + 归一。
     *
     * <p>★ 返回值分三档，别把前两档合并:
     * <ul>
     *   <li>入参 {@code null}（字段压根没传）→ 返回 {@code null}，mapper 的
     *       {@code <if test="... != null">} 会跳过该列，库里的值原样不动。</li>
     *   <li>入参空串 / {@code {}}（显式清空）→ 返回 <b>空串</b>，mapper 才会真把列写空。
     *       这两档以前都返回 null，后果是「选项题→改成拍照→再改回选项题」时旧选项图复活，
     *       而同一批的 questionImg 传空串能清 —— 同批字段两种行为。</li>
     *   <li>其余 → 归一后的 JSON。</li>
     * </ul>
     *
     * @throws IllegalArgumentException 键越界、值不是对象、url 过长或整体超长
     */
    public static String sanitize(String json)
    {
        if (json == null)
        {
            return null;
        }
        if (json.trim().isEmpty())
        {
            return "";
        }
        if (json.length() > MAX_JSON_LENGTH)
        {
            throw new IllegalArgumentException("选项媒体超过 " + MAX_JSON_LENGTH + " 字符");
        }
        Map<String, Object> raw;
        try
        {
            raw = JSON.readValue(json, OBJECT_TYPE);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("选项媒体不是合法 JSON 对象", e);
        }
        if (raw == null)
        {
            // 字面量 null 也是「传了字段」，同样按显式清空处理
            return "";
        }

        Map<String, Map<String, String>> clean = new LinkedHashMap<>();
        for (String letter : LETTERS)
        {
            Object value = raw.get(letter);
            if (value == null)
            {
                continue;
            }
            if (!(value instanceof Map))
            {
                throw new IllegalArgumentException("选项 " + letter + " 的媒体必须是对象");
            }
            Map<?, ?> media = (Map<?, ?>) value;
            Map<String, String> entry = new LinkedHashMap<>();
            for (String key : MEDIA_KEYS)
            {
                Object url = media.get(key);
                if (url == null)
                {
                    continue;
                }
                if (!(url instanceof String))
                {
                    throw new IllegalArgumentException("选项 " + letter + " 的 " + key + " 必须是字符串");
                }
                String text = ((String) url).trim();
                if (text.isEmpty())
                {
                    continue;
                }
                if (text.length() > MAX_URL_LENGTH)
                {
                    throw new IllegalArgumentException(
                            "选项 " + letter + " 的 " + key + " 超过 " + MAX_URL_LENGTH + " 字符");
                }
                entry.put(key, text);
            }
            if (!entry.isEmpty())
            {
                clean.put(letter, entry);
            }
        }
        // 越界的键必须报错而不是静默丢:选项 E 的媒体被无声吞掉，作者会以为自己配过了。
        for (Object key : raw.keySet())
        {
            if (!LETTERS.contains(String.valueOf(key)))
            {
                throw new IllegalArgumentException("选项媒体只允许 A/B/C/D，收到: " + key);
            }
        }
        if (clean.isEmpty())
        {
            // 传了字段但一个媒体都没剩 = 显式清空，同样要写空串把列清掉
            return "";
        }
        String normalized;
        try
        {
            normalized = JSON.writeValueAsString(clean);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("选项媒体序列化失败", e);
        }
        if (normalized.length() > MAX_JSON_LENGTH)
        {
            throw new IllegalArgumentException("选项媒体超过 " + MAX_JSON_LENGTH + " 字符");
        }
        return normalized;
    }

    /** 读出端。库里的脏数据不得阻断题面下发，解析不了就当没配。 */
    public static Map<String, Map<String, String>> parse(String json)
    {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty())
        {
            return result;
        }
        Map<String, Object> raw;
        try
        {
            raw = JSON.readValue(json, OBJECT_TYPE);
        }
        catch (Exception e)
        {
            LOG.warn("选项媒体解析失败，本次按未配媒体下发: {}", e.getMessage());
            return result;
        }
        if (raw == null)
        {
            return result;
        }
        for (String letter : LETTERS)
        {
            Object value = raw.get(letter);
            if (!(value instanceof Map))
            {
                continue;
            }
            Map<?, ?> media = (Map<?, ?>) value;
            Map<String, String> entry = new LinkedHashMap<>();
            for (String key : MEDIA_KEYS)
            {
                Object url = media.get(key);
                if (url instanceof String && !((String) url).trim().isEmpty())
                {
                    entry.put(key, ((String) url).trim());
                }
            }
            if (!entry.isEmpty())
            {
                result.put(letter, entry);
            }
        }
        return result;
    }
}
