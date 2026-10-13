package com.chengyinhub.business.access.club;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NF-16 角色展示级别映射。级别只用于风险提示，不参与鉴权；真实权限仍由 ClubPermissionPolicy 决定。
 */
public final class ClubRoleGovernanceMetadata {
    public static final String LEVEL_L1 = "L1";
    public static final String LEVEL_L2 = "L2";
    public static final String LEVEL_L3 = "L3";

    private static final Map<String, ClubRoleGovernanceMetadata> PROFILES;
    static {
        Map<String, ClubRoleGovernanceMetadata> profiles = new LinkedHashMap<>();
        profiles.put(ClubRoleCode.CLUB_CO_OWNER,
                new ClubRoleGovernanceMetadata(ClubRoleCode.CLUB_CO_OWNER, LEVEL_L3));
        profiles.put(ClubRoleCode.CLUB_OPERATOR,
                new ClubRoleGovernanceMetadata(ClubRoleCode.CLUB_OPERATOR, LEVEL_L2));
        profiles.put(ClubRoleCode.EVENT_LEAD,
                new ClubRoleGovernanceMetadata(ClubRoleCode.EVENT_LEAD, LEVEL_L2));
        profiles.put(ClubRoleCode.EVENT_CHECKIN,
                new ClubRoleGovernanceMetadata(ClubRoleCode.EVENT_CHECKIN, LEVEL_L1));
        PROFILES = Collections.unmodifiableMap(profiles);
    }

    private final String roleId;
    private final String permissionLevel;

    private ClubRoleGovernanceMetadata(String roleId, String permissionLevel) {
        this.roleId = roleId;
        this.permissionLevel = permissionLevel;
    }

    public static ClubRoleGovernanceMetadata forRole(String roleCode) {
        ClubRoleGovernanceMetadata profile = PROFILES.get(roleCode);
        if (profile == null) throw new IllegalArgumentException("未知俱乐部角色");
        return profile;
    }

    public String getRoleId() { return roleId; }
    public String getPermissionLevel() { return permissionLevel; }
    public boolean isAuthorizationSource() { return false; }
}
