package com.chengyinhub.business.access.merchant;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 商家域角色码。Owner 由 mms_merchant.member_id 派生，不写入 operator 表。 */
public final class MerchantRoleCode {
    public static final String MERCHANT_OWNER = "MERCHANT_OWNER";
    public static final String MERCHANT_MANAGER = "MERCHANT_MANAGER";
    public static final String MERCHANT_CHECKIN = "MERCHANT_CHECKIN";
    public static final String MERCHANT_MARKETING = "MERCHANT_MARKETING";
    public static final String MERCHANT_FINANCE = "MERCHANT_FINANCE";

    private static final Set<String> OPERATOR_ROLES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(MERCHANT_MANAGER, MERCHANT_CHECKIN,
                    MERCHANT_MARKETING, MERCHANT_FINANCE)));

    private MerchantRoleCode() {
    }

    public static boolean isOperatorRole(String roleCode) {
        return OPERATOR_ROLES.contains(roleCode);
    }

    public static Set<String> operatorRoles() {
        return OPERATOR_ROLES;
    }
}
