package com.chengyinhub.business.domain.vo;

/**
 * 承接商家对某个节点的现场层覆写(规则说明 / 反馈话术 / 到店券)。
 *
 * <p>取值一律走 {@code xxxOr(fallback)}:商家配了就用他的,没配回落主办方模板。
 * 空对象代替 null —— 调用方少一层判空,也就少一处漏判。
 */
public final class NodeMerchantOverride {

    private static final NodeMerchantOverride EMPTY = new NodeMerchantOverride(null, null, null, null);

    private final Long merchantMemberId;
    private final String ruleInstructions;
    private final String feedbackText;
    private final Long couponId;

    public NodeMerchantOverride(Long merchantMemberId, String ruleInstructions, String feedbackText, Long couponId) {
        this.merchantMemberId = merchantMemberId;
        this.ruleInstructions = ruleInstructions;
        this.feedbackText = feedbackText;
        this.couponId = couponId;
    }

    public static NodeMerchantOverride empty() {
        return EMPTY;
    }

    /** 中标商家的 memberId;没有承接方时为 null。审计/排查用,不参与取值。 */
    public Long getMerchantMemberId() {
        return merchantMemberId;
    }

    public String ruleInstructionsOr(String fallback) {
        return blank(ruleInstructions) ? fallback : ruleInstructions;
    }

    public String feedbackTextOr(String fallback) {
        return blank(feedbackText) ? fallback : feedbackText;
    }

    /**
     * 到店券:商家配了用他的,否则回落模板的。
     * ⚠️ 0 和负数按「没配」处理 —— 库里存量有 0 当空值使,直接透传会让下游拿 0 去查券。
     */
    public Long couponIdOr(Long fallback) {
        return (couponId == null || couponId <= 0) ? fallback : couponId;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
