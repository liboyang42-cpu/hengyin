package com.chengyinhub.business.access.club;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 登录会员在一个确定 club/event 作用域内的不可变权限上下文。 */
public final class ClubAccessContext {
    private final Long principalMemberId;
    private final Long clubId;
    private final Long ownerMemberId;
    private final Long activityId;
    private final Set<String> roleCodes;
    private final Set<String> permissions;
    private final List<ClubEventAccess> eventAccesses;

    public ClubAccessContext(Long principalMemberId, Long clubId, Long ownerMemberId,
                             Long activityId, Set<String> roleCodes) {
        this(principalMemberId, clubId, ownerMemberId, activityId, roleCodes,
                Collections.<ClubEventAccess>emptyList());
    }

    public ClubAccessContext(Long principalMemberId, Long clubId, Long ownerMemberId,
                             Long activityId, Set<String> roleCodes,
                             List<ClubEventAccess> eventAccesses) {
        this.principalMemberId = principalMemberId;
        this.clubId = clubId;
        this.ownerMemberId = ownerMemberId;
        this.activityId = activityId;
        LinkedHashSet<String> roles = new LinkedHashSet<>(roleCodes);
        this.roleCodes = Collections.unmodifiableSet(roles);
        LinkedHashSet<String> granted = new LinkedHashSet<>();
        for (String roleCode : roles) granted.addAll(ClubPermissionPolicy.permissionsFor(roleCode));
        this.permissions = Collections.unmodifiableSet(granted);
        this.eventAccesses = Collections.unmodifiableList(new ArrayList<>(eventAccesses));
    }

    public Long getPrincipalMemberId() { return principalMemberId; }
    public Long getClubId() { return clubId; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public Long getActivityId() { return activityId; }
    public Set<String> getRoleCodes() { return roleCodes; }
    public Set<String> getPermissions() { return permissions; }
    public List<ClubEventAccess> getEventAccesses() { return eventAccesses; }
    public boolean hasPermission(String permission) { return permissions.contains(permission); }

    public String getPrimaryRoleCode() {
        String[] priority = { ClubRoleCode.CLUB_OWNER, ClubRoleCode.CLUB_CO_OWNER,
                ClubRoleCode.CLUB_LEGACY_ADMIN, ClubRoleCode.CLUB_OPERATOR, ClubRoleCode.EVENT_LEAD,
                ClubRoleCode.EVENT_CHECKIN, ClubRoleCode.CLUB_MEMBER };
        for (String roleCode : priority) if (roleCodes.contains(roleCode)) return roleCode;
        return null;
    }
}
