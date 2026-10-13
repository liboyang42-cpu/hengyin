package com.chengyinhub.common.utils;

import java.util.LinkedHashMap;
import java.util.Map;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;

/**
 * 汉字转拼音 —— 只为「地图上敲字母搜地址」这一件事存在。
 *
 * 两个输出:
 *   全拼   xuhuibinjiang
 *   首字母 xhbj
 * 都是小写、只留 a-z0-9,标点空格一律丢掉 —— 用户不会连标点一起敲。
 *
 * 【多音字取第一个读音】「重庆」会出 zhongqing 而不是 chongqing。
 * 这是有意的:地名多音字要判对得有词库,而搜索只需要「敲得出来」,不需要念得对。
 * 首字母那一路不受影响(zh 和 ch 首字母都不是同一个… 实际上会,所以全拼那一路才是兜底)——
 * 两路同时匹配,一路错另一路还在。
 *
 * 【为什么在后端做】前端做要把一整本字典塞进小程序主包,而主包资源棘轮只准降。
 */
public class PinyinUtils {

    /** 有界缓存:地名在一次次 nearby 请求里高度重复,算一次就够。超出按插入序淘汰最老的。 */
    private static final int CACHE_MAX = 2000;

    private static final Map<String, String[]> CACHE =
            new LinkedHashMap<String, String[]>(256, 0.75f, true) {
                private static final long serialVersionUID = 1L;
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();

    static {
        FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        // WITH_V:「绿」出 lv 而不是 lu:。冒号会被下面的过滤丢掉,那样 lu 和「路」撞车。
        FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private PinyinUtils() {
    }

    /** 全拼,如「徐汇滨江」→ xuhuibinjiang。取不到读音的字直接跳过。 */
    public static String toPinyin(String text) {
        return convert(text)[0];
    }

    /** 首字母,如「徐汇滨江」→ xhbj。 */
    public static String toInitials(String text) {
        return convert(text)[1];
    }

    /** 一次算两路并缓存 —— 调用方总是两个都要,分两次算等于把字典查两遍。 */
    private static String[] convert(String text) {
        if (text == null) {
            return new String[] { "", "" };
        }
        String key = text.trim();
        if (key.isEmpty()) {
            return new String[] { "", "" };
        }
        synchronized (CACHE) {
            String[] hit = CACHE.get(key);
            if (hit != null) {
                return hit;
            }
        }
        StringBuilder full = new StringBuilder(key.length() * 4);
        StringBuilder initials = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            String[] readings = null;
            try {
                readings = PinyinHelper.toHanyuPinyinStringArray(c, FORMAT);
            } catch (Exception e) {
                // 只有格式非法才会抛,这里格式是常量;真抛了也不该让整个地址搜不出来
                readings = null;
            }
            if (readings != null && readings.length > 0 && readings[0] != null && !readings[0].isEmpty()) {
                String py = keepAlnum(readings[0]);
                if (!py.isEmpty()) {
                    full.append(py);
                    initials.append(py.charAt(0));
                }
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                // 已经是拉丁字母/数字的原样留下:「B1 出口」这种搜得到
                full.append(c);
                initials.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                char lower = Character.toLowerCase(c);
                full.append(lower);
                initials.append(lower);
            }
            // 其余(标点、空格、生僻符号)整个丢掉:用户不会连标点一起敲
        }
        String[] result = new String[] { full.toString(), initials.toString() };
        synchronized (CACHE) {
            CACHE.put(key, result);
        }
        return result;
    }

    /** pinyin4j 在 WITH_V 下仍可能带出别的符号,统一只留 a-z0-9。 */
    private static String keepAlnum(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
