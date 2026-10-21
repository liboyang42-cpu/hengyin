package com.chengyinhub.business.citygame.operations;

import java.util.Set;

/**
 * CITY 运营诊断候选权限。只作为权限端口和 {@code @ss.hasPermi} 的权限串，不代表角色名。
 * 候选 SQL 不写 sys_role_menu，默认无人持有。
 */
public final class CityGameOperationsPermissions {

    public static final String TRACE = "citygame:ops:trace";
    public static final String PROPOSE = "citygame:ops:propose";
    public static final String REVIEW = "citygame:ops:review";
    public static final String APPLY = "citygame:ops:apply";
    public static final String QUERY = "citygame:ops:query";

    public static final Set<String> ALL = Set.of(TRACE, PROPOSE, REVIEW, APPLY, QUERY);

    private CityGameOperationsPermissions() {
    }
}
