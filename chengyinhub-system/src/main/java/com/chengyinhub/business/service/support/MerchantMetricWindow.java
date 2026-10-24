package com.chengyinhub.business.service.support;

/** /api/merchant/dashboard 的字段窗口；只描述现有 SQL，不改变取数条件。 */
public enum MerchantMetricWindow {
    /** owner 钱包全部历史收入。 */
    ALL_TIME,
    /** 流水创建日：六天前零点至今天，含今天共七个自然日。 */
    CALENDAR_7D_INCL_TODAY,
    /** 浏览发生时间自当前时刻前推 30 天。 */
    ROLLING_30D,
    /** 报名创建时间自当前时刻前推 30 天；该 cohort 的到店/核销事件本身不另加时间窗。 */
    REGISTRATION_COHORT_ROLLING_30D,
    /** 查询当时的状态快照，不按创建时间截取。 */
    CURRENT_SNAPSHOT;

    public String code() {
        return name();
    }
}
