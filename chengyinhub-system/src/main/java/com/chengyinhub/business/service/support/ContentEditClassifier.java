package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.common.utils.CoordinateUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O5b-1:编辑分级 —— 轻改(错别字/措辞微调)与实质改动(内容/媒体/时间/地点)。
 *
 * <p>只做确定性判定,不调外部服务。字段口径与 {@code CmsActivityServiceImpl#reviewableContentChanged} 一致:
 * 请求里没带(null)的字段视为未改。任何媒体/时间/地点字段变化都算实质改动;名称/简介只有在
 * 「去掉空白与标点后相同」或「编辑距离很小」时才算轻改,其余一律实质改动(拿不准就重审)。
 * S531-1:数字串(金额/日期/时间,含数字间的小数点等分隔符)有任何变化、或否定词有增删,一律实质改动。</p>
 */
public final class ContentEditClassifier {

    public enum EditClass { NONE, LIGHT, SUBSTANTIVE }

    /** 轻改上限:编辑距离不超过 max(1, 原文长度 10%),且绝对值不超过 4 个字。 */
    static final int MAX_LIGHT_DISTANCE = 4;
    static final int LIGHT_DISTANCE_PERCENT = 10;
    /** 超长文本不算编辑距离(O(n·m)),直接按实质改动处理。 */
    static final int MAX_DISTANCE_INPUT = 2000;

    private ContentEditClassifier() {
    }

    public static EditClass classifyActivity(CmsActivity before, CmsActivityPublishRequest request) {
        if (before == null || request == null) return EditClass.SUBSTANTIVE;
        if (changed(before.getImgUrl(), request.getImgUrl())
                || changed(before.getImgArr(), request.getImgArr())
                || changedSecond(before.getStartDate(), request.getStartDate())
                || changedSecond(before.getEndDate(), request.getEndDate())
                || changed(before.getAddressName(), request.getAddressName())
                || changed(before.getAddress(), request.getAddress())
                || request.getLongitude() != null
                        && !CoordinateUtils.sameCoordinate(before.getLongitude(), request.getLongitude())
                || request.getLatitude() != null
                        && !CoordinateUtils.sameCoordinate(before.getLatitude(), request.getLatitude())) {
            return EditClass.SUBSTANTIVE;
        }
        EditClass name = classifyText(before.getName(), request.getName());
        EditClass description = classifyText(before.getDescription(), request.getDescription());
        if (name == EditClass.SUBSTANTIVE || description == EditClass.SUBSTANTIVE) return EditClass.SUBSTANTIVE;
        if (name == EditClass.LIGHT || description == EditClass.LIGHT) return EditClass.LIGHT;
        return EditClass.NONE;
    }

    /** 单个文本字段:未传/未变 = NONE;去空白标点相同或编辑距离很小 = LIGHT;其余 = SUBSTANTIVE。 */
    public static EditClass classifyText(String before, String requested) {
        if (requested == null || requested.equals(before)) return EditClass.NONE;
        String a = before == null ? "" : before;
        if (a.isEmpty() || requested.isEmpty()) return EditClass.SUBSTANTIVE;
        // S531-1:数字/金额/日期(含小数点、冒号等分隔)任何变化,或否定词增删,都会改变语义 —— 一律实质改动。
        if (!numberTokens(a).equals(numberTokens(requested))) return EditClass.SUBSTANTIVE;
        if (!negationProfile(a).equals(negationProfile(requested))) return EditClass.SUBSTANTIVE;
        if (normalize(a).equals(normalize(requested))) return EditClass.LIGHT;
        if (a.length() > MAX_DISTANCE_INPUT || requested.length() > MAX_DISTANCE_INPUT) return EditClass.SUBSTANTIVE;
        int allowed = Math.min(MAX_LIGHT_DISTANCE,
                Math.max(1, a.codePointCount(0, a.length()) * LIGHT_DISTANCE_PERCENT / 100));
        return distance(a, requested, allowed) <= allowed ? EditClass.LIGHT : EditClass.SUBSTANTIVE;
    }

    /** 去掉空白与标点(含全角),大小写不敏感;夹在两个数字之间的标点(10.00 / 8:30 / 1,000)保留。 */
    static String normalize(String text) {
        int[] cps = text.codePoints().toArray();
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) continue;
            int type = Character.getType(cp);
            boolean punctuation = type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                    || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                    || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                    || type == Character.OTHER_PUNCTUATION;
            if (punctuation && !(i > 0 && i + 1 < cps.length && isNumeral(cps[i - 1]) && isNumeral(cps[i + 1]))) {
                continue;
            }
            sb.appendCodePoint(Character.toLowerCase(cp));
        }
        return sb.toString();
    }

    /** 中文数字字符(拿不准就算数字:宁可多判实质改动)。 */
    private static final String CJK_NUMERALS = "〇零一二三四五六七八九十百千万亿两壹贰叁肆伍陆柒捌玖拾佰仟";

    static boolean isNumeral(int cp) {
        return Character.isDigit(cp) || CJK_NUMERALS.indexOf(cp) >= 0;
    }

    /**
     * 文本里按顺序出现的数字串(阿拉伯/全角/中文数字),夹在数字之间的单个标点(. : , / - 等)算作数字串的一部分,
     * 所以 10.00 与 1000、8:30 与 830 是不同的数字串。全角数字按数值归一,其余原样保留。
     */
    static List<String> numberTokens(String text) {
        int[] cps = text.codePoints().toArray();
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];
            if (isNumeral(cp)) {
                int v = Character.digit(cp, 10);
                if (v >= 0) cur.append((char) ('0' + v)); else cur.appendCodePoint(cp);
            } else if (cur.length() > 0 && i + 1 < cps.length && isNumeral(cps[i + 1])
                    && !Character.isWhitespace(cp) && !Character.isLetter(cp)) {
                cur.appendCodePoint(cp);
            } else if (cur.length() > 0) {
                tokens.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) tokens.add(cur.toString());
        return tokens;
    }

    /** 否定/禁止类字词(单字计数已覆盖「不得/不可/严禁/禁止/没有」等复合词)。 */
    private static final String NEGATION_CHARS = "不非无没未别勿莫否禁拒免毋甭休";
    private static final Pattern NEGATION_WORDS = Pattern.compile(
            "\\b(no|not|non|never|none|nor|without|cannot|can't|don't|won't|isn't|aren't|doesn't|didn't)\\b");

    /** 否定字词出现次数画像:增删任何一个否定词(可退款 → 不可退款)画像就不同。 */
    static List<Integer> negationProfile(String text) {
        List<Integer> profile = new ArrayList<>(NEGATION_CHARS.length() + 1);
        for (int k = 0; k < NEGATION_CHARS.length(); k++) {
            char c = NEGATION_CHARS.charAt(k);
            int n = 0;
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == c) n++;
            profile.add(n);
        }
        Matcher m = NEGATION_WORDS.matcher(text.toLowerCase(Locale.ROOT).replace('\u2019', '\''));
        int words = 0;
        while (m.find()) words++;
        profile.add(words);
        return profile;
    }

    /** 按码点算 Levenshtein 距离;超过 limit 即提前返回 limit+1。 */
    static int distance(String left, String right, int limit) {
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        if (Math.abs(a.length - b.length) > limit) return limit + 1;
        int[] prev = new int[b.length + 1];
        int[] cur = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) prev[j] = j;
        for (int i = 1; i <= a.length; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length; j++) {
                int cost = a[i - 1] == b[j - 1] ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > limit) return limit + 1;
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length];
    }

    private static boolean changed(String before, String requested) {
        return requested != null && !requested.equals(before);
    }

    /** 与 CmsActivityServiceImpl#changedSecond 同口径:按秒比较,未传视为未改。 */
    private static boolean changedSecond(Date before, Date requested) {
        if (requested == null) return false;
        if (before == null) return true;
        return before.getTime() / 1000L != requested.getTime() / 1000L;
    }
}
