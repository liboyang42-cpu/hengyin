package com.chengyinhub.business.service.support;

import com.chengyinhub.common.exception.ServiceException;

import java.util.Locale;

/**
 * M7 公开目录的语言归一与回退。纯函数，无状态，便于单测。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>请求语言、实际返回语言、是否回退分别对外表达 —— 不能只回一个 locale 把回退说成成功；</li>
 *   <li>只有 {@code APPROVED} 译文算「已翻译」；{@code PENDING} 与缺译同等按回退处理；</li>
 *   <li>语言标签只做大小写/下划线归一与「主语言回退」，不做任何语义猜测
 *       (例如不把 {@code zh-Hant} 猜成某一地区的译文)。</li>
 * </ol>
 *
 * <p>城瘾当前只有中文原文；第一期可声明的只有 {@code zh-CN}，不假装多语言已支持。
 */
public final class PublicContentCatalogLocale {

    /** 原文语言；{@code cms_topic} 文案的语言。 */
    public static final String SOURCE_LOCALE = "zh-cn";

    /** 声明给客户端的原文语言显示值。 */
    public static final String SOURCE_LOCALE_DISPLAY = "zh-CN";

    private PublicContentCatalogLocale() {
    }

    /**
     * 归一化语言标签：{@code zh_CN} → {@code zh-cn}，{@code EN-us} → {@code en-us}。
     * 空值取原文语言；格式非法 400(不静默换成原文语言)。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return SOURCE_LOCALE;
        }
        String value = raw.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z]{2,3}(-[a-z0-9]{2,8})*")) {
            throw new ServiceException("locale 格式无效，示例: zh-CN / en / ja", 400);
        }
        return value;
    }

    /** 对外显示用的大小写(zh-cn → zh-CN)；主语言保持小写(en)。 */
    public static String display(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return SOURCE_LOCALE_DISPLAY;
        }
        int dash = normalized.indexOf('-');
        if (dash < 0) {
            return normalized;
        }
        return normalized.substring(0, dash) + normalized.substring(dash).toUpperCase(Locale.ROOT);
    }

    /** 主语言：{@code en-us} → {@code en}。用于「先精确、再主语言」的回退链。 */
    public static String primary(String normalized) {
        int dash = normalized == null ? -1 : normalized.indexOf('-');
        return dash < 0 ? normalized : normalized.substring(0, dash);
    }

    /** 是否原文语言(原文不需要「翻译」)。 */
    public static boolean isSource(String normalized) {
        return primary(normalized).equals(primary(SOURCE_LOCALE));
    }

    /**
     * 译文可用性判定。{@code state} 必须是 {@code APPROVED}，且三段里至少一段有内容，
     * 否则视为缺译 —— {@code PENDING} 不能冒充可用。
     */
    public static boolean usable(String state, String title, String subtitle, String summary) {
        if (!"APPROVED".equals(state)) {
            return false;
        }
        return hasText(title) || hasText(subtitle) || hasText(summary);
    }

    public static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
