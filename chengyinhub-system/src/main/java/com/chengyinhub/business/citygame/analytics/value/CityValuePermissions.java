package com.chengyinhub.business.citygame.analytics.value;

/** 价值统计只读权限。候选 SQL 不授予任何角色，默认无人持有。 */
public final class CityValuePermissions {
    public static final String REPORT_QUERY = "cityvalue:report:query";

    private CityValuePermissions() {
    }
}
