package com.chengyinhub.business.access.merchant;

import java.util.Set;

/** 登录操作者与商家主体解析后的不可变访问上下文。 */
public final class MerchantAccessContext {
    private final Long principalMemberId;
    private final Long merchantId;
    private final Long ownerMemberId;
    private final String roleCode;
    private final Set<String> permissions;

    public MerchantAccessContext(Long principalMemberId, Long merchantId, Long ownerMemberId, String roleCode) {
        this.principalMemberId = principalMemberId;
        this.merchantId = merchantId;
        this.ownerMemberId = ownerMemberId;
        this.roleCode = roleCode;
        this.permissions = MerchantPermissionPolicy.permissionsFor(roleCode);
    }

    public Long getPrincipalMemberId() {
        return principalMemberId;
    }

    /** mms_merchant.id；只用于明确以商家行主键为外键的表，例如 oms_order。 */
    public Long getMerchantId() {
        return merchantId;
    }

    /** mms_merchant.member_id；用于报名、核销、余额、结算等既有 memberId 口径。 */
    public Long getOwnerMemberId() {
        return ownerMemberId;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
