package com.chengyinhub.business.domain.vo;

/**
 * C:U-046:「我的模板」个人审核态与广场态的唯一推导口径,由 my-list 直接下发
 * auditState / marketState,小程序 utils/template-market.js 只按枚举渲染。
 * 规则与小程序的兜底推导逐条一致,由 tests/fixtures/template-market-state-cases.json 两端共测。
 */
public final class TemplateMarketState {
    public static final String AUDIT_DRAFT = "DRAFT";
    public static final String AUDIT_APPROVED = "APPROVED";
    public static final String AUDIT_REJECTED = "REJECTED";
    public static final String AUDIT_REVIEWING = "REVIEWING";
    public static final String MARKET_NONE = "NONE";
    public static final String MARKET_PENDING = "PENDING";
    public static final String MARKET_ONLINE = "ONLINE";
    public static final String MARKET_DELISTED = "DELISTED";
    public static final String MARKET_RESUBMIT = "RESUBMIT";
    public static final String UNKNOWN = "UNKNOWN";

    private TemplateMarketState() { }

    private static boolean is(Number value, long expected) {
        return value != null && value.longValue() == expected;
    }

    public static String audit(Integer draftStatus, Long status, Integer checkStatus) {
        if (is(draftStatus, 0)) return AUDIT_DRAFT;
        if (!is(draftStatus, 1)) return UNKNOWN;
        if (is(status, 1) && is(checkStatus, 1)) return AUDIT_APPROVED;
        if (is(status, 2) || is(checkStatus, 2)) return AUDIT_REJECTED;
        if (is(status, 0) || is(checkStatus, 0)) return AUDIT_REVIEWING;
        return UNKNOWN;
    }

    public static String market(Long marketTemplateId, Long marketStatus, Long marketPublishStatus) {
        if (marketTemplateId == null || marketTemplateId == 0L) return MARKET_NONE;
        if (marketTemplateId < 0L) return UNKNOWN;
        if (is(marketStatus, 0)) return MARKET_PENDING;
        if (is(marketStatus, 1) && is(marketPublishStatus, 1)) return MARKET_ONLINE;
        if (is(marketStatus, 1) && is(marketPublishStatus, 0)) return MARKET_DELISTED;
        if (is(marketStatus, 2)) return MARKET_RESUBMIT;
        return UNKNOWN;
    }
}
