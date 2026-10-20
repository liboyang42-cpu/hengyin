package com.chengyinhub.business.service.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统消息卡片(ImMessage.MSG_CARD)的 extra_json，与审核结果通知同一结构：{title, sub, result{...}}。
 * 文案沿用审核通知口径：title 一句话结论，sub =「结论。原因：…」。
 */
public final class SystemNoticeCard {
    /** 内容安全图片违规下架通知的统一原因与后续指引。 */
    public static final String IMAGE_TAKEDOWN_TITLE = "内容已下架";
    public static final String IMAGE_TAKEDOWN_REASON = "图片未通过内容安全检测";
    public static final String IMAGE_TAKEDOWN_FOLLOW_UP = "请更换图片后重新提交审核；如有异议，请通过客服提供内容编号补充材料。";

    private SystemNoticeCard() {
    }

    public static String json(String title, String sub, Map<String, Object> result) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", title);
        card.put("sub", sub);
        card.put("result", result);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(card);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("系统通知序列化失败", e);
        }
    }

    /** 图片违规下架：告诉作者是哪一条内容、为什么下架、接下来怎么做。 */
    public static String imageTakedown(String bizType, Long bizId, String item) {
        String outcome = "你发布的" + item + "已被下架";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bizType", bizType);
        result.put("bizId", bizId);
        result.put("outcome", outcome);
        result.put("reason", IMAGE_TAKEDOWN_REASON);
        result.put("reasonCodes", java.util.Collections.singletonList("IMAGE_RISK"));
        result.put("followUp", IMAGE_TAKEDOWN_FOLLOW_UP);
        return json(IMAGE_TAKEDOWN_TITLE, outcome + "。原因：" + IMAGE_TAKEDOWN_REASON, result);
    }

    /** 内容标题/正文摘要，供通知点明是哪一条；空则只写类型。 */
    public static String item(String typeLabel, String title) {
        if (title == null || title.trim().isEmpty()) return typeLabel;
        String clean = title.trim().replaceAll("\\s+", " ");
        if (clean.codePointCount(0, clean.length()) > 20) {
            clean = clean.substring(0, clean.offsetByCodePoints(0, 20)) + "…";
        }
        return typeLabel + "「" + clean + "」";
    }
}
