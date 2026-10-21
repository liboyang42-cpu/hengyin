package com.chengyinhub.business.cityboard;

import java.util.Set;

/**
 * CITY 棋盘候选权限。只作为 {@code @ss.hasPermi} 的权限串，不代表任何角色名。
 * 未写入 sys_role_menu，默认无人持有。
 */
public final class CityBoardPermissions {

    public static final String DRAFT_SAVE = "cityboard:draft:save";
    public static final String DRAFT_REOPEN = "cityboard:draft:reopen";
    public static final String DRAFT_CANCEL = "cityboard:draft:cancel";
    public static final String DRAFT_QUERY = "cityboard:draft:query";
    public static final String REVIEW_VALIDATE = "cityboard:review:validate";
    public static final String REVIEW_REJECT = "cityboard:review:reject";
    public static final String PUBLISH = "cityboard:publish:freeze";
    public static final String OPERATION_QUERY = "cityboard:operation:query";
    public static final String PLAYER_READ = "cityboard:player:read";

    public static final Set<String> ALL = Set.of(
            DRAFT_SAVE, DRAFT_REOPEN, DRAFT_CANCEL, DRAFT_QUERY,
            REVIEW_VALIDATE, REVIEW_REJECT, PUBLISH, OPERATION_QUERY, PLAYER_READ);

    private CityBoardPermissions() {
    }
}
