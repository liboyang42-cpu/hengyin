package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.common.utils.CoordinateUtils;

import java.util.Date;

/**
 * O5b-1:编辑分级 —— 轻改(错别字/措辞微调)与实质改动(内容/媒体/时间/地点)。
 *
 * <p>只做确定性判定,不调外部服务。字段口径与 {@code CmsActivityServiceImpl#reviewableContentChanged} 一致:
 * 请求里没带(null)的字段视为未改。任何媒体/时间/地点字段变化都算实质改动;名称/简介只有在
 * 「去掉空白与标点后相同」或「编辑距离很小」时才算轻改,其余一律实质改动(拿不准就重审)。</p>
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
        if (normalize(a).equals(normalize(requested))) return EditClass.LIGHT;
        if (a.length() > MAX_DISTANCE_INPUT || requested.length() > MAX_DISTANCE_INPUT) return EditClass.SUBSTANTIVE;
        int allowed = Math.min(MAX_LIGHT_DISTANCE,
                Math.max(1, a.codePointCount(0, a.length()) * LIGHT_DISTANCE_PERCENT / 100));
        return distance(a, requested, allowed) <= allowed ? EditClass.LIGHT : EditClass.SUBSTANTIVE;
    }

    /** 去掉空白与标点(含全角),大小写不敏感。 */
    static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return;
            int type = Character.getType(cp);
            if (type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                    || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                    || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                    || type == Character.OTHER_PUNCTUATION) return;
            sb.appendCodePoint(Character.toLowerCase(cp));
        });
        return sb.toString();
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
