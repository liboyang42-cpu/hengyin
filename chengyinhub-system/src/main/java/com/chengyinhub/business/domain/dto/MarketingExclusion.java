package com.chengyinhub.business.domain.dto;

/**
 * 一条"配置存在但本单不适用"的排除说明。
 *
 * <p>为什么要逐条下发而不是静默跳过:用户看到"我有优惠却没便宜"必须有可解释的理由;
 * 后台也要能回答"这条促销为什么没生效"。理由码是稳定枚举,文案可改。
 */
public class MarketingExclusion {

    public static final String OFFER_NOT_FOUND = "OFFER_NOT_FOUND";
    public static final String OFFER_DISABLED = "OFFER_DISABLED";
    public static final String OFFER_WINDOW_CLOSED = "OFFER_WINDOW_CLOSED";
    public static final String OFFER_SCOPE_MISMATCH = "OFFER_SCOPE_MISMATCH";
    public static final String OFFER_ITEMS_MISMATCH = "OFFER_ITEMS_MISMATCH";
    public static final String OFFER_MEMBER_LIMIT = "OFFER_MEMBER_LIMIT";
    public static final String OFFER_PRICE_ABOVE_LIST = "OFFER_PRICE_ABOVE_LIST";

    public static final String PROMO_DISABLED = "PROMO_DISABLED";
    public static final String PROMO_WINDOW_CLOSED = "PROMO_WINDOW_CLOSED";
    public static final String PROMO_WINDOW_UNCONFIGURED = "PROMO_WINDOW_UNCONFIGURED";
    public static final String PROMO_TYPE_UNSUPPORTED = "PROMO_TYPE_UNSUPPORTED";
    public static final String TRIGGER_FACT_MISSING = "TRIGGER_FACT_MISSING";
    public static final String TRIGGER_WINDOW_EXPIRED = "TRIGGER_WINDOW_EXPIRED";
    public static final String ALREADY_USED = "ALREADY_USED";
    public static final String MEMBER_LIMIT_REACHED = "MEMBER_LIMIT_REACHED";
    public static final String BELOW_MIN_AMOUNT = "BELOW_MIN_AMOUNT";
    public static final String EXCLUSIVE_ALREADY_APPLIED = "EXCLUSIVE_ALREADY_APPLIED";
    public static final String STACK_GROUP_TAKEN = "STACK_GROUP_TAKEN";
    public static final String TIER_SUPERSEDED_BY_OFFER = "TIER_SUPERSEDED_BY_OFFER";
    public static final String ZERO_AMOUNT = "ZERO_AMOUNT";

    private String code;
    private String reasonCode;
    private String label;
    private String detail;

    public MarketingExclusion() {
    }

    public MarketingExclusion(String code, String reasonCode, String label, String detail) {
        this.code = code;
        this.reasonCode = reasonCode;
        this.label = label;
        this.detail = detail;
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
