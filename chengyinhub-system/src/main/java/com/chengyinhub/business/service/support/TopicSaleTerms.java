package com.chengyinhub.business.service.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开售审核(BIZ_TOPIC_SALE_REVIEW)的「条款指纹」:只保留价格 / 售卖条款,去掉故事与文案。
 *
 * <p>2026-10-20 分级审核拍板:主题内容先发后审,开售先审后发;两者解耦 ——
 * 改故事/名称/简介不重置开售审核,只有改价格或条款才重置。
 *
 * <p>输入是 {@link CommercialTopicPolicy#saleParameters} 的结果(开售审核快照里的同一份权威读)。
 * 去掉的只有:topicId、章节的 id / name / description / blocksJson
 * (章节整包重建会换新 id,章节正文是故事)。票(价格/库存/时间/退改)、自玩价、费率、档期、
 * 招商条款(章节 recruitEnabled / required / termsMode / maxMerchant)与 offer 全部保留,
 * 任何一项变化都算条款变化 —— 只放宽「文案」,不放宽钱。
 */
public final class TopicSaleTerms {

    private static final String[] CHAPTER_TERM_KEYS = {"recruitEnabled", "required", "termsMode", "maxMerchant"};

    private TopicSaleTerms() {
    }

    /** saleParameters 为 null 时返回 null;调用方把 null 当「算不出 ⇒ 视为已变化」。 */
    public static Map<String, Object> of(Map<String, Object> saleParameters) {
        if (saleParameters == null) return null;
        Map<String, Object> terms = new LinkedHashMap<String, Object>(saleParameters);
        terms.remove("topicId");
        Object chapters = terms.get("chapters");
        if (chapters instanceof List) {
            List<Map<String, Object>> stripped = new ArrayList<Map<String, Object>>();
            for (Object chapter : (List<?>) chapters) {
                if (!(chapter instanceof Map)) continue;
                Map<String, Object> value = new LinkedHashMap<String, Object>();
                for (String key : CHAPTER_TERM_KEYS) value.put(key, ((Map<?, ?>) chapter).get(key));
                stripped.add(value);
            }
            // 章节 id 每次重建都会变,排序不能依赖 id:按条款内容排,比较的是条款多重集
            stripped.sort(Comparator.comparing(String::valueOf));
            terms.put("chapters", stripped);
        }
        return terms;
    }

    /** 任一侧算不出(null)一律当作变化,fail-safe 重置开售审核。 */
    public static boolean changed(Map<String, Object> before, Map<String, Object> after) {
        return before == null || after == null || !before.equals(after);
    }
}
