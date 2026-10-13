package com.chengyinhub.business.access.club;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 固定角色到权限的唯一映射；事件角色不具备全团成员或财务权限。 */
public final class ClubPermissionPolicy {
    private static final Map<String, Set<String>> PERMISSIONS_BY_ROLE;

    static {
        Map<String, Set<String>> policy = new HashMap<>();
        policy.put(ClubRoleCode.CLUB_OWNER, ClubPermission.all());
        policy.put(ClubRoleCode.CLUB_CO_OWNER, permissions(
                ClubPermission.CLUB_READ, ClubPermission.CLUB_WRITE,
                ClubPermission.MEMBER_LIST_READ, ClubPermission.MEMBER_APPROVE,
                ClubPermission.MEMBER_MANAGE, ClubPermission.CONTENT_MANAGE,
                ClubPermission.NOTIFY_SEND,
                ClubPermission.ACTIVITY_READ, ClubPermission.ACTIVITY_MANAGE,
                ClubPermission.EVENT_OPERATE, ClubPermission.EVENT_CHECKIN,
                ClubPermission.FINANCE_READ));
        policy.put(ClubRoleCode.CLUB_OPERATOR, permissions(
                ClubPermission.CLUB_READ, ClubPermission.MEMBER_LIST_READ,
                ClubPermission.MEMBER_APPROVE, ClubPermission.CONTENT_MANAGE,
                ClubPermission.NOTIFY_SEND, ClubPermission.ACTIVITY_READ));
        policy.put(ClubRoleCode.CLUB_LEGACY_ADMIN, permissions(
                ClubPermission.CLUB_READ, ClubPermission.MEMBER_LIST_READ,
                ClubPermission.MEMBER_APPROVE, ClubPermission.MEMBER_MANAGE,
                ClubPermission.CONTENT_MANAGE, ClubPermission.ACTIVITY_READ));
        policy.put(ClubRoleCode.CLUB_MEMBER, permissions(
                ClubPermission.CLUB_READ, ClubPermission.ACTIVITY_READ));
        policy.put(ClubRoleCode.EVENT_LEAD, permissions(
                ClubPermission.CLUB_READ, ClubPermission.ACTIVITY_READ,
                ClubPermission.EVENT_OPERATE, ClubPermission.EVENT_CHECKIN));
        policy.put(ClubRoleCode.EVENT_CHECKIN, permissions(
                ClubPermission.CLUB_READ, ClubPermission.ACTIVITY_READ,
                ClubPermission.EVENT_CHECKIN));
        PERMISSIONS_BY_ROLE = Collections.unmodifiableMap(policy);
    }

    private ClubPermissionPolicy() {
    }

    public static Set<String> permissionsFor(String roleCode) {
        Set<String> permissions = PERMISSIONS_BY_ROLE.get(roleCode);
        return permissions == null ? Collections.<String>emptySet() : permissions;
    }

    private static Set<String> permissions(String... permissions) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(permissions)));
    }
}
