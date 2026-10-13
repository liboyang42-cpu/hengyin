package com.chengyinhub.business.access.club;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 当前账号在单个俱乐部活动作用域内的不可变委派权限。 */
public final class ClubEventAccess {
    private final Long activityId;
    private final Long topicId;
    private final Set<String> roleCodes;
    private final Set<String> permissions;

    public ClubEventAccess(Long activityId, Long topicId, Set<String> roleCodes) {
        this.activityId = activityId;
        this.topicId = topicId;
        LinkedHashSet<String> roles = new LinkedHashSet<>(roleCodes);
        this.roleCodes = Collections.unmodifiableSet(roles);
        LinkedHashSet<String> granted = new LinkedHashSet<>();
        for (String roleCode : roles) granted.addAll(ClubPermissionPolicy.permissionsFor(roleCode));
        this.permissions = Collections.unmodifiableSet(granted);
    }

    public Long getActivityId() { return activityId; }
    public Long getTopicId() { return topicId; }
    public Set<String> getRoleCodes() { return roleCodes; }
    public Set<String> getPermissions() { return permissions; }
}
