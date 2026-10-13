package com.chengyinhub.business.access.merchant;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MerchantPermissionPolicyTest {

    @Test
    void ownerHasEveryMerchantPermission() {
        assertRolePermissions(MerchantRoleCode.MERCHANT_OWNER,
                MerchantPermission.all().toArray(new String[0]));
    }

    @Test
    void managerPermissionSetIsExact() {
        assertRolePermissions(MerchantRoleCode.MERCHANT_MANAGER,
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
                MerchantPermission.COOP_MANAGE);
    }

    @Test
    void checkinPermissionSetIsExact() {
        assertRolePermissions(MerchantRoleCode.MERCHANT_CHECKIN,
                MerchantPermission.BASIC_READ,
                MerchantPermission.VERIFY,
                MerchantPermission.VERIFY_RECORD_READ);
    }

    @Test
    void marketingPermissionSetIsExact() {
        assertRolePermissions(MerchantRoleCode.MERCHANT_MARKETING,
                MerchantPermission.BASIC_READ,
                MerchantPermission.CRM_READ,
                MerchantPermission.CRM_SEGMENT,
                MerchantPermission.MARKETING_READ,
                MerchantPermission.MARKETING_WRITE,
                MerchantPermission.COUPON_MANAGE,
                MerchantPermission.COOP_MANAGE);
    }

    @Test
    void financePermissionSetIsExact() {
        assertRolePermissions(MerchantRoleCode.MERCHANT_FINANCE,
                MerchantPermission.BASIC_READ,
                MerchantPermission.VERIFY_RECORD_READ,
                MerchantPermission.ORDER_READ,
                MerchantPermission.FINANCE_READ,
                MerchantPermission.AFTERCARE_READ,
                MerchantPermission.AFTERCARE_RESPOND,
                MerchantPermission.AFTERCARE_EVIDENCE);
        assertFalse(MerchantPermissionPolicy.hasPermission(
                MerchantRoleCode.MERCHANT_FINANCE, MerchantPermission.AFTERCARE_DECIDE),
                "财务只能补充凭证，不能同意或建议驳回售后申请");
        assertFalse(MerchantPermissionPolicy.hasPermission(
                MerchantRoleCode.MERCHANT_MARKETING, MerchantPermission.CRM_EXPORT),
                "基础 CRM 分群权限不包含客户导出");
        assertFalse(MerchantPermission.all().contains("merchant:finance:export"),
                "安全导出链未落地前不得通过 access/me 宣称 finance export 能力");
    }

    @Test
    void unknownRolesAndPermissionsFailClosed() {
        assertFalse(MerchantPermissionPolicy.hasPermission("MERCHANT_SUPER_ADMIN", MerchantPermission.FINANCE_READ));
        assertFalse(MerchantPermissionPolicy.hasPermission(MerchantRoleCode.MERCHANT_OWNER, "merchant:anything"));
        assertFalse(MerchantPermissionPolicy.hasPermission(null, MerchantPermission.VERIFY));
        assertFalse(MerchantPermissionPolicy.hasPermission(MerchantRoleCode.MERCHANT_OWNER, null));
    }

    private static void assertRolePermissions(String roleCode, String... expectedValues) {
        Set<String> expected = new LinkedHashSet<>(Arrays.asList(expectedValues));
        assertEquals(expected, MerchantPermissionPolicy.permissionsFor(roleCode));
        for (String permission : MerchantPermission.all()) {
            assertEquals(expected.contains(permission),
                    MerchantPermissionPolicy.hasPermission(roleCode, permission),
                    roleCode + " 权限漂移: " + permission);
        }
    }
}
