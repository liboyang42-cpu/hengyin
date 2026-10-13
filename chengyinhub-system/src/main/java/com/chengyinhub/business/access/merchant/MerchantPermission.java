package com.chengyinhub.business.access.merchant;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 商家小程序域权限码。 */
public final class MerchantPermission {
    public static final String BASIC_READ = "merchant:basic:read";
    public static final String PROFILE_WRITE = "merchant:profile:write";
    public static final String PROJECT_MANAGE = "merchant:project:manage";
    public static final String VERIFY = "merchant:verify";
    public static final String VERIFY_RECORD_READ = "merchant:verify:record:read";
    public static final String ORDER_READ = "merchant:order:read";
    public static final String CRM_READ = "merchant:crm:read";
    public static final String CRM_SENSITIVE_READ = "merchant:crm:sensitive:read";
    public static final String CRM_SEGMENT = "merchant:crm:segment";
    public static final String CRM_EXPORT = "merchant:crm:export";
    public static final String FINANCE_READ = "merchant:finance:read";
    public static final String AFTERCARE_READ = "merchant:aftercare:read";
    public static final String AFTERCARE_RESPOND = "merchant:aftercare:respond";
    public static final String AFTERCARE_DECIDE = "merchant:aftercare:decide";
    public static final String AFTERCARE_EVIDENCE = "merchant:aftercare:evidence";
    public static final String MARKETING_READ = "merchant:marketing:read";
    public static final String MARKETING_WRITE = "merchant:marketing:write";
    public static final String COUPON_MANAGE = "merchant:coupon:manage";
    public static final String COOP_MANAGE = "merchant:coop:manage";
    public static final String OPERATOR_MANAGE = "merchant:operator:manage";

    private static final Set<String> ALL = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            BASIC_READ, PROFILE_WRITE, PROJECT_MANAGE, VERIFY, VERIFY_RECORD_READ, ORDER_READ,
            CRM_READ, CRM_SENSITIVE_READ, CRM_SEGMENT, CRM_EXPORT, FINANCE_READ,
            AFTERCARE_READ, AFTERCARE_RESPOND, AFTERCARE_DECIDE, AFTERCARE_EVIDENCE,
            MARKETING_READ, MARKETING_WRITE, COUPON_MANAGE,
            COOP_MANAGE, OPERATOR_MANAGE)));

    private MerchantPermission() {
    }

    public static Set<String> all() {
        return ALL;
    }
}
