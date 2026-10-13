package com.chengyinhub.business.access.club;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 俱乐部域固定权限码；动态 club/event 作用域由 ClubAccessService 校验。 */
public final class ClubPermission {
    public static final String CLUB_READ = "club:read";
    public static final String CLUB_WRITE = "club:write";
    public static final String MEMBER_LIST_READ = "club:member:list:read";
    public static final String MEMBER_APPROVE = "club:member:approve";
    public static final String MEMBER_MANAGE = "club:member:manage";
    public static final String CONTENT_MANAGE = "club:content:manage";
    public static final String NOTIFY_SEND = "club:notify:send";
    public static final String ACTIVITY_READ = "club:activity:read";
    public static final String ACTIVITY_MANAGE = "club:activity:manage";
    public static final String EVENT_OPERATE = "club:event:operate";
    public static final String EVENT_CHECKIN = "club:event:checkin";
    public static final String FINANCE_READ = "club:finance:read";
    public static final String ROLE_MANAGE = "club:role:manage";

    private static final Set<String> ALL = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            CLUB_READ, CLUB_WRITE, MEMBER_LIST_READ, MEMBER_APPROVE, MEMBER_MANAGE, CONTENT_MANAGE, NOTIFY_SEND, ACTIVITY_READ,
            ACTIVITY_MANAGE, EVENT_OPERATE, EVENT_CHECKIN, FINANCE_READ, ROLE_MANAGE)));

    private ClubPermission() {
    }

    public static Set<String> all() {
        return ALL;
    }
}
