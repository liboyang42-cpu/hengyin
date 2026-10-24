package com.chengyinhub.business.service.support;

/**
 * 商家看板各块数据的时间窗口声明。{@code /api/merchant/dashboard} 在 {@code windows} 里按块下发 {@link #code()},
 * 端上要对比/相除两个数字前先看窗口是否一致(累计、近 7 自然日、近 30 天滚动三种并存)。
 */
public enum MerchantMetricWindow {
    /** 全部历史,不限窗口(revenue) */
    ALL_TIME("ALL_TIME", null),
    /** 自然日 7 天,含今天,从 6 天前零点起(revenue7d) */
    CALENDAR_7D_INCL_TODAY("CALENDAR_7D_INCL_TODAY", 7),
    /** 从此刻往前滚动 30 天(insight 块) */
    ROLLING_30D("ROLLING_30D", 30);

    private final String code;
    private final Integer days;

    MerchantMetricWindow(String code, Integer days) {
        this.code = code;
        this.days = days;
    }

    public String code() {
        return code;
    }

    public Integer days() {
        return days;
    }
}
