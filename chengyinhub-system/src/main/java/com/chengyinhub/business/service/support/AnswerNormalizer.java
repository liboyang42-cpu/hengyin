package com.chengyinhub.business.service.support;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 答案比对的共享归一器(E-A1~E-A5)。
 *
 * <p>规则:NFKC → 去掉全部空白/分隔符({@code \p{Z}}、{@code \s}、{@code \p{Cc}},含 NBSP、U+3000)
 * 和格式字符({@code \p{Cf}},含零宽字符) → 去首尾标点({@code \p{P}}) → 转小写。
 * 繁简不在这里处理,只能靠作者显式写别名。
 *
 * <p><b>只放宽、不收紧:</b>各判定点保留原有比对,再用本归一器多接受一层
 * ({@code 旧规则命中 || 归一后相等}),所以任何今天判对的输入仍然判对。
 * 归一后为空(只有标点/空白)永远不命中。
 *
 * <p>多答案分隔符统一为 {@link #SEPARATOR_REGEX}(、 , ， ; ；),服务端、playkit-qa 与作者编辑器共用。
 */
public final class AnswerNormalizer {
    /** 多个合法写法的分隔符:、 半角逗号 全角逗号 半角分号 全角分号。前端两处需同步这一集合。 */
    public static final String SEPARATOR_REGEX = "[、,，;；]";

    private static final Pattern SEPARATOR = Pattern.compile(SEPARATOR_REGEX);
    private static final Pattern STRIP_ALL = Pattern.compile("[\\p{Z}\\s\\p{Cc}\\p{Cf}]+");

    private AnswerNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return "";
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        text = STRIP_ALL.matcher(text).replaceAll("");
        int start = 0;
        int end = text.length();
        while (start < end && isPunctuation(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && isPunctuation(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(start, end).toLowerCase(Locale.ROOT);
    }

    /** 归一后相等且非空。 */
    public static boolean equivalent(String a, String b) {
        String left = normalize(a);
        return !left.isEmpty() && acceptsSeparatedInput(left, normalize(b));
    }

    /** 区分大小写的打字题：NFKC、去空白/格式字符，额外接受玩家分隔写法，不折叠大小写。 */
    public static boolean equivalentKeepCase(String a, String b) {
        String left = fold(a);
        return !left.isEmpty() && acceptsSeparatedInput(left, fold(b));
    }

    // 只放宽玩家分隔写法；作者串里的分隔符仍表示备选，不能把多个备选拼成一个答案。
    private static boolean acceptsSeparatedInput(String authored, String submitted) {
        return authored.equals(submitted) || (!SEPARATOR.matcher(authored).find()
                && authored.equals(SEPARATOR.matcher(submitted).replaceAll("")));
    }

    private static String fold(String raw) {
        if (raw == null) return "";
        return STRIP_ALL.matcher(Normalizer.normalize(raw, Normalizer.Form.NFKC)).replaceAll("");
    }

    /** 按共享分隔符拆开(不去重、不含整串)。 */
    public static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        if (text == null) return parts;
        for (String one : SEPARATOR.split(text)) {
            String trimmed = one.trim();
            if (!trimmed.isEmpty()) parts.add(trimmed);
        }
        return parts;
    }

    /** 整串或任一分段与 submitted 归一后相等。 */
    public static boolean matchesAnyOf(String authoredAnswer, String submitted) {
        if (equivalent(authoredAnswer, submitted)) return true;
        for (String part : split(authoredAnswer)) {
            if (equivalent(part, submitted)) return true;
        }
        return false;
    }

    private static boolean isPunctuation(int codePoint) {
        switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
                return true;
            default:
                return false;
        }
    }
}
