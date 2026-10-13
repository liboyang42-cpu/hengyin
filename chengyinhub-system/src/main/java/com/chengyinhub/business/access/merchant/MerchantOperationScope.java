package com.chengyinhub.business.access.merchant;

import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;

/** 商家/个人双身份写链路的显式作用域解析；未知非空值必须失败，不能静默落到个人主体。 */
public final class MerchantOperationScope {
    public static final String MERCHANT = "MERCHANT";
    public static final String CLUB = "CLUB";

    private MerchantOperationScope() {
    }

    public static boolean isMerchant(String rawScope) {
        String scope = rawScope == null ? "" : rawScope.trim();
        if (scope.isEmpty()) return false;
        if (MERCHANT.equalsIgnoreCase(scope)) return true;
        if (CLUB.equalsIgnoreCase(scope)) return false;
        throw new ServiceException("业务作用域参数不合法", HttpStatus.BAD_REQUEST);
    }

    public static boolean isClub(String rawScope) {
        return rawScope != null && CLUB.equalsIgnoreCase(rawScope.trim());
    }
}
