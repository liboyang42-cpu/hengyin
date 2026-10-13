package com.chengyinhub.business.access.merchant;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 固定预设角色到权限的唯一映射。动态作用域由 MerchantAccessService 负责。 */
public final class MerchantPermissionPolicy {
    private static final Map<String, Set<String>> PERMISSIONS_BY_ROLE;

    static {
        Map<String, Set<String>> policy = new HashMap<>();
        policy.put(MerchantRoleCode.MERCHANT_OWNER, MerchantPermission.all());
        policy.put(MerchantRoleCode.MERCHANT_MANAGER, permissions(
                MerchantPermission.BASIC_READ,
                MerchantPermission.PROFILE_WRITE,
                MerchantPermission.PROJECT_MANAGE,
                MerchantPermission.VERIFY,
                MerchantPermission.VERIFY_RECORD_READ,
                MerchantPermission.ORDER_READ,
                MerchantPermission.CRM_READ,
                MerchantPermission.CRM_SENSITIVE_READ,
                MerchantPermission.CRM_SEGMENT,
                MerchantPermission.CRM_EXPORT,
                MerchantPermission.AFTERCARE_READ,
                MerchantPermission.AFTERCARE_RESPOND,
                MerchantPermission.AFTERCARE_DECIDE,
                MerchantPermission.AFTERCARE_EVIDENCE,
                MerchantPermission.MARKETING_READ,
                MerchantPermission.MARKETING_WRITE,
                MerchantPermission.COUPON_MANAGE,
                MerchantPermission.COOP_MANAGE));
        policy.put(MerchantRoleCode.MERCHANT_CHECKIN, permissions(
                MerchantPermission.BASIC_READ,
                MerchantPermission.VERIFY,
                MerchantPermission.VERIFY_RECORD_READ));
        policy.put(MerchantRoleCode.MERCHANT_MARKETING, permissions(
                MerchantPermission.BASIC_READ,
                MerchantPermission.CRM_READ,
                MerchantPermission.CRM_SEGMENT,
                MerchantPermission.MARKETING_READ,
                MerchantPermission.MARKETING_WRITE,
                MerchantPermission.COUPON_MANAGE,
                MerchantPermission.COOP_MANAGE));
        policy.put(MerchantRoleCode.MERCHANT_FINANCE, permissions(
                MerchantPermission.BASIC_READ,
                MerchantPermission.VERIFY_RECORD_READ,
                MerchantPermission.ORDER_READ,
                MerchantPermission.FINANCE_READ,
                MerchantPermission.AFTERCARE_READ,
                MerchantPermission.AFTERCARE_RESPOND,
                MerchantPermission.AFTERCARE_EVIDENCE));
        PERMISSIONS_BY_ROLE = Collections.unmodifiableMap(policy);
    }

    private MerchantPermissionPolicy() {
    }

    public static boolean hasPermission(String roleCode, String permission) {
        if (roleCode == null || permission == null || !MerchantPermission.all().contains(permission)) {
            return false;
        }
        Set<String> permissions = PERMISSIONS_BY_ROLE.get(roleCode);
        return permissions != null && permissions.contains(permission);
    }

    public static Set<String> permissionsFor(String roleCode) {
        Set<String> permissions = PERMISSIONS_BY_ROLE.get(roleCode);
        return permissions == null ? Collections.<String>emptySet() : permissions;
    }

    private static Set<String> permissions(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }
}
