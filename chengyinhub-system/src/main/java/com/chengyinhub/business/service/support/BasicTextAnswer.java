package com.chengyinhub.business.service.support;

/**
 * 老「文字问答」(validationMethod=1)的唯一判定规则。
 *
 * <p>P-02:编辑器提示「可填多个，用、分隔」,作者填的标准答案按、/半角逗号/全角逗号拆开,命中任一项即过;
 * 整串原样比对仍保留(兼容老题)。每一项与玩家答案都去全部空白、全角 ASCII 转半角后忽略大小写比对,
 * 与原归一口径一字不差。</p>
 */
public final class BasicTextAnswer {
    private BasicTextAnswer() {}

    public static boolean matches(String questionAnswer, String answer) {
        if (questionAnswer == null || questionAnswer.trim().isEmpty()) return false;
        String got = normalize(answer);
        if (got.isEmpty()) return false;
        if (normalize(questionAnswer).equalsIgnoreCase(got)) return true;
        for (String one : questionAnswer.split("[、,，]")) {
            String expected = normalize(one);
            if (!expected.isEmpty() && expected.equalsIgnoreCase(got)) return true;
        }
        return false;
    }

    /** 去全部空白 + 全角 ASCII→半角。 */
    public static String normalize(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", "");
        StringBuilder sb = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (c >= '！' && c <= '～') sb.append((char) (c - 0xFEE0));
            else if (c == '　') sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }
}
