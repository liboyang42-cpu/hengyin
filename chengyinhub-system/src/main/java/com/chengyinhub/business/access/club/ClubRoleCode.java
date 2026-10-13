package com.chengyinhub.business.access.club;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Owner/Member/LegacyAdmin 由既有 club/club_member 派生，只有其余四种角色允许持久化。 */
public final class ClubRoleCode {
    public static final String CLUB_OWNER = "CLUB_OWNER";
    public static final String CLUB_MEMBER = "CLUB_MEMBER";
    public static final String CLUB_LEGACY_ADMIN = "CLUB_LEGACY_ADMIN";
    public static final String CLUB_CO_OWNER = "CLUB_CO_OWNER";
    public static final String CLUB_OPERATOR = "CLUB_OPERATOR";
    public static final String EVENT_LEAD = "EVENT_LEAD";
    public static final String EVENT_CHECKIN = "EVENT_CHECKIN";

    private static final Set<String> CLUB_ASSIGNABLE = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(CLUB_CO_OWNER, CLUB_OPERATOR)));
    private static final Set<String> EVENT_ASSIGNABLE = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(EVENT_LEAD, EVENT_CHECKIN)));
    private static final Set<String> ASSIGNABLE = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(CLUB_CO_OWNER, CLUB_OPERATOR, EVENT_LEAD, EVENT_CHECKIN)));

    private ClubRoleCode() {
    }

    public static boolean isAssignable(String roleCode) { return ASSIGNABLE.contains(roleCode); }
    public static boolean isClubRole(String roleCode) { return CLUB_ASSIGNABLE.contains(roleCode); }
    public static boolean isEventRole(String roleCode) { return EVENT_ASSIGNABLE.contains(roleCode); }
    public static Set<String> assignableRoles() { return ASSIGNABLE; }
}
